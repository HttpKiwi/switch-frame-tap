/* pad.h - v0.7.20: a controller on this computer plays the Switch.
 *
 * The console turns it on (Switch app: Beta > "Controller from the PC
 * viewer", config viewer_input = 1) and then marks its stream headers with
 * SFT_FLAG_INPUT; only then does anything go out. There the controller is
 * one more Pro Controller (hid:dbg), attached at the first message and let
 * go when the viewer closes, sends "no controller" or goes quiet for 3 s.
 *
 * The message, 32 bytes little-endian (tier4/.../applet_mitm_viewermsg.hpp):
 *   "SFTI", u32 flags (1 = a controller is there), u64 buttons
 *   (HidNpadButton: A B X Y L3 R3 L R ZL ZR + - Left Up Right Down = bits
 *   0-15, HOME 18, Capture 19), s32 lx ly rx ry (-32767..32767, up +y).
 *
 * Buttons by place, not by label: the bottom face button is the Switch's B
 * on every controller (Xbox A, PlayStation Cross, Switch Pro B), the right
 * one A. The main thread reads the controller (SDL wants that) and hands
 * each change to a sender thread, which also repeats the last state every
 * 100 ms so the console knows the viewer is still there. While the window
 * is not focused, everything reads as let go.
 *
 * The keyboard can play too (Settings, off by default), with Ryujinx's
 * default keys, by place on the keyboard (scancodes - WASD stays WASD on
 * AZERTY): left stick W A S D (L3 F), right stick I J K L (R3 H), D-pad the
 * arrows, A B X Y = Z X C V, L R = E U, ZL ZR = Q O, + Enter or =, - minus,
 * HOME the Home key, Capture End. While it plays (the console asked, the
 * window has focus, Settings is closed), the viewer's letter shortcuts rest;
 * F11, F3 and Esc still work. */

static SDL_GameController *g_pad = NULL;
static SDL_JoystickID g_pad_id = -1;
static char g_pad_name[64] = "";
static SDL_mutex *g_pad_m = NULL;
static SDL_cond *g_pad_cv = NULL;
static SDL_Thread *g_pad_th = NULL;
static volatile int g_pad_stop = 0;
static unsigned char g_pad_msg[32];        /* the latest state, for the sender */
static int g_pad_dirty = 0;
static int g_pad_inited = 0;
static SDL_atomic_t g_pad_live;            /* 1 while the console has our controller (for Settings) */
static int g_pad_test = 0;                 /* --pad-test: SDL's virtual controller, pressed by a script (tests) */
static Uint8 g_pad_test_keys[SDL_NUM_SCANCODES];   /* --pad-test: the keyboard, as the script holds it */

static void pad_open_first(void)
{
    if (g_pad) return;
    for (int i = 0; i < SDL_NumJoysticks(); i++) {
        if (!SDL_IsGameController(i)) continue;
        g_pad = SDL_GameControllerOpen(i);
        if (!g_pad) continue;
        g_pad_id = SDL_JoystickInstanceID(SDL_GameControllerGetJoystick(g_pad));
        const char *n = SDL_GameControllerName(g_pad);
        snprintf(g_pad_name, sizeof(g_pad_name), "%s", n ? n : "Controller");
        fprintf(stderr, "controller: %s\n", g_pad_name);
        return;
    }
}

static void pad_init(void)
{
    if (g_pad_inited) return;
    g_pad_inited = 1;
    /* SDL_CONTROLLER_BUTTON_A is the bottom button on Nintendo controllers too */
    SDL_SetHint(SDL_HINT_GAMECONTROLLER_USE_BUTTON_LABELS, "0");
    /* SDL drops presses while no window has focus - a test window never has it */
    if (g_pad_test) SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");
    if (SDL_InitSubSystem(SDL_INIT_GAMECONTROLLER) != 0) {
        fprintf(stderr, "controller: SDL could not start controller support (%s)\n", SDL_GetError());
        return;
    }
    g_pad_m = SDL_CreateMutex();
    g_pad_cv = SDL_CreateCond();
    if (g_pad_test && SDL_JoystickAttachVirtual(SDL_JOYSTICK_TYPE_GAMECONTROLLER, SDL_CONTROLLER_AXIS_MAX,
                                                SDL_CONTROLLER_BUTTON_MAX, 0) < 0)
        fprintf(stderr, "controller: no virtual controller (%s)\n", SDL_GetError());
    pad_open_first();
    if (g_rd) pad_start();                 /* the connection came first (raw-view --net, no --app) */
}

/* --pad-test: B (the bottom button) for 0.3 s, then the left stick right and
 * the right stick down, then HOME, then ZR, then the keyboard's W + D + Z
 * (up-right, A), then nothing - every 1.6 s */
static void pad_test_script(void)
{
    if (!g_pad) return;
    SDL_Joystick *j = SDL_GameControllerGetJoystick(g_pad);
    const Uint64 t = SDL_GetTicks64() % 1600;
    SDL_JoystickSetVirtualButton(j, SDL_CONTROLLER_BUTTON_A, t < 300);
    SDL_JoystickSetVirtualAxis(j, SDL_CONTROLLER_AXIS_LEFTX, t >= 300 && t < 600 ? 32767 : 0);
    SDL_JoystickSetVirtualAxis(j, SDL_CONTROLLER_AXIS_RIGHTY, t >= 300 && t < 600 ? 32767 : 0);
    SDL_JoystickSetVirtualButton(j, SDL_CONTROLLER_BUTTON_GUIDE, t >= 600 && t < 900);
    /* a virtual trigger's raw axis rests mid-way (half pressed); real ones rest at the bottom */
    SDL_JoystickSetVirtualAxis(j, SDL_CONTROLLER_AXIS_TRIGGERLEFT, -32768);
    SDL_JoystickSetVirtualAxis(j, SDL_CONTROLLER_AXIS_TRIGGERRIGHT, t >= 900 && t < 1200 ? 32767 : -32768);
    SDL_JoystickUpdate();
    const Uint8 k = t >= 1200 && t < 1400;
    g_pad_test_keys[SDL_SCANCODE_W] = g_pad_test_keys[SDL_SCANCODE_D] = g_pad_test_keys[SDL_SCANCODE_Z] = k;
}

/* the keyboard's part: Ryujinx's default keys, by place */
static void pad_keyboard(const Uint8 *ks, uint64_t *buttons, int32_t *lx, int32_t *ly, int32_t *rx, int32_t *ry)
{
    static const struct { SDL_Scancode sc; int bit; } keys[] = {
        { SDL_SCANCODE_Z, 0 }, { SDL_SCANCODE_X, 1 }, { SDL_SCANCODE_C, 2 }, { SDL_SCANCODE_V, 3 },   /* A B X Y */
        { SDL_SCANCODE_F, 4 }, { SDL_SCANCODE_H, 5 },                                                 /* L3 R3 */
        { SDL_SCANCODE_E, 6 }, { SDL_SCANCODE_U, 7 }, { SDL_SCANCODE_Q, 8 }, { SDL_SCANCODE_O, 9 },   /* L R ZL ZR */
        { SDL_SCANCODE_RETURN, 10 }, { SDL_SCANCODE_EQUALS, 10 }, { SDL_SCANCODE_KP_PLUS, 10 },        /* + */
        { SDL_SCANCODE_MINUS, 11 }, { SDL_SCANCODE_KP_MINUS, 11 },                                    /* - */
        { SDL_SCANCODE_LEFT, 12 }, { SDL_SCANCODE_UP, 13 }, { SDL_SCANCODE_RIGHT, 14 }, { SDL_SCANCODE_DOWN, 15 },
        { SDL_SCANCODE_HOME, 18 }, { SDL_SCANCODE_END, 19 },                                          /* HOME, Capture */
    };
    for (size_t i = 0; i < sizeof(keys) / sizeof(keys[0]); i++)
        if (ks[keys[i].sc]) *buttons |= 1ull << keys[i].bit;
    /* a stick held by keys: full tilt, diagonals on the circle */
    const struct { SDL_Scancode up, left, down, right; int32_t *x, *y; } sticks[] = {
        { SDL_SCANCODE_W, SDL_SCANCODE_A, SDL_SCANCODE_S, SDL_SCANCODE_D, lx, ly },
        { SDL_SCANCODE_I, SDL_SCANCODE_J, SDL_SCANCODE_K, SDL_SCANCODE_L, rx, ry },
    };
    for (int i = 0; i < 2; i++) {
        const int x = (ks[sticks[i].right] ? 1 : 0) - (ks[sticks[i].left] ? 1 : 0);
        const int y = (ks[sticks[i].up] ? 1 : 0) - (ks[sticks[i].down] ? 1 : 0);
        if (!x && !y) continue;              /* the controller's stick, if any */
        const int32_t full = x && y ? 23170 : 32767;
        *sticks[i].x = x * full;
        *sticks[i].y = y * full;
    }
}

/* the keyboard plays now: on, the console asked, the window has focus, and
 * nothing else wants the keys (Settings, an address being typed) */
static int pad_keyboard_playing(SDL_Window *win, int keys_free)
{
    return g_pad_kbd && keys_free && SDL_AtomicGet(&g_console_takes_pad) &&
           (g_pad_test || !win || (SDL_GetWindowFlags(win) & SDL_WINDOW_INPUT_FOCUS));
}

/* hot-plug, from app_events */
static void pad_event(const SDL_Event *e)
{
    if (!g_pad_inited) return;
    if (e->type == SDL_CONTROLLERDEVICEADDED) pad_open_first();
    else if (e->type == SDL_CONTROLLERDEVICEREMOVED && g_pad && e->cdevice.which == g_pad_id) {
        fprintf(stderr, "controller: %s disconnected\n", g_pad_name);
        SDL_GameControllerClose(g_pad);
        g_pad = NULL; g_pad_id = -1; g_pad_name[0] = 0;
        pad_open_first();
    }
}

static void pad_put32(unsigned char *p, uint32_t v) { p[0] = (unsigned char)v; p[1] = (unsigned char)(v >> 8); p[2] = (unsigned char)(v >> 16); p[3] = (unsigned char)(v >> 24); }

/* a stick with a small round dead zone (a resting stick drifts by a few
 * percent), the rest stretched to the full range; y up */
static void pad_stick(SDL_GameController *c, SDL_GameControllerAxis ax, SDL_GameControllerAxis ay, int32_t *ox, int32_t *oy)
{
    const double dz = 2500.0, x = SDL_GameControllerGetAxis(c, ax), y = -(double)SDL_GameControllerGetAxis(c, ay);
    const double m = SDL_sqrt(x * x + y * y);
    if (m <= dz) { *ox = *oy = 0; return; }
    double k = (m - dz) / (32767.0 - dz) / m * 32767.0;
    if (m * k > 32767.0) k = 32767.0 / m;
    const double vx = SDL_clamp(x * k, -32767.0, 32767.0), vy = SDL_clamp(y * k, -32767.0, 32767.0);
    *ox = (int32_t)vx;
    *oy = (int32_t)vy;
}

/* the controller's part, by place */
static void pad_controller(SDL_GameController *c, uint64_t *buttons, int32_t *lx, int32_t *ly, int32_t *rx, int32_t *ry)
{
    static const struct { SDL_GameControllerButton b; int bit; } map[] = {
        { SDL_CONTROLLER_BUTTON_B, 0 },             /* right: A */
        { SDL_CONTROLLER_BUTTON_A, 1 },             /* bottom: B */
        { SDL_CONTROLLER_BUTTON_Y, 2 },             /* top: X */
        { SDL_CONTROLLER_BUTTON_X, 3 },             /* left: Y */
        { SDL_CONTROLLER_BUTTON_LEFTSTICK, 4 },
        { SDL_CONTROLLER_BUTTON_RIGHTSTICK, 5 },
        { SDL_CONTROLLER_BUTTON_LEFTSHOULDER, 6 },
        { SDL_CONTROLLER_BUTTON_RIGHTSHOULDER, 7 },
        { SDL_CONTROLLER_BUTTON_START, 10 },        /* + */
        { SDL_CONTROLLER_BUTTON_BACK, 11 },         /* - */
        { SDL_CONTROLLER_BUTTON_DPAD_LEFT, 12 },
        { SDL_CONTROLLER_BUTTON_DPAD_UP, 13 },
        { SDL_CONTROLLER_BUTTON_DPAD_RIGHT, 14 },
        { SDL_CONTROLLER_BUTTON_DPAD_DOWN, 15 },
        { SDL_CONTROLLER_BUTTON_GUIDE, 18 },        /* HOME (Xbox / PS / Switch HOME button) */
        { SDL_CONTROLLER_BUTTON_MISC1, 19 },        /* Capture (Switch Capture, Xbox Share, PS5 mic) */
    };
    for (size_t i = 0; i < sizeof(map) / sizeof(map[0]); i++)
        if (SDL_GameControllerGetButton(c, map[i].b)) *buttons |= 1ull << map[i].bit;
    if (SDL_GameControllerGetAxis(c, SDL_CONTROLLER_AXIS_TRIGGERLEFT) > 10000) *buttons |= 1ull << 8;    /* ZL */
    if (SDL_GameControllerGetAxis(c, SDL_CONTROLLER_AXIS_TRIGGERRIGHT) > 10000) *buttons |= 1ull << 9;   /* ZR */
    pad_stick(c, SDL_CONTROLLER_AXIS_LEFTX, SDL_CONTROLLER_AXIS_LEFTY, lx, ly);
    pad_stick(c, SDL_CONTROLLER_AXIS_RIGHTX, SDL_CONTROLLER_AXIS_RIGHTY, rx, ry);
}

/* the controller and the keyboard now, as the message; 0 in flags = nothing to send */
static void pad_build(unsigned char *msg, SDL_Window *win, int keys_free)
{
    memset(msg, 0, 32);
    memcpy(msg, "SFTI", 4);
    const int pad = g_pad && g_pad_send;
    if (!pad && !g_pad_kbd) return;
    pad_put32(msg + 4, 1);
    if (win && !g_pad_test && !(SDL_GetWindowFlags(win) & SDL_WINDOW_INPUT_FOCUS)) return;   /* attached, nothing pressed */
    uint64_t buttons = 0;
    int32_t lx = 0, ly = 0, rx = 0, ry = 0;
    if (pad) pad_controller(g_pad, &buttons, &lx, &ly, &rx, &ry);
    if (pad_keyboard_playing(win, keys_free))
        pad_keyboard(g_pad_test ? g_pad_test_keys : SDL_GetKeyboardState(NULL), &buttons, &lx, &ly, &rx, &ry);
    pad_put32(msg + 8, (uint32_t)buttons);
    pad_put32(msg + 12, (uint32_t)(buttons >> 32));
    pad_put32(msg + 16, (uint32_t)lx);
    pad_put32(msg + 20, (uint32_t)ly);
    pad_put32(msg + 24, (uint32_t)rx);
    pad_put32(msg + 28, (uint32_t)ry);
}

/* every pass of the main loop: a change goes to the sender at once.
 * keys_free: Settings is closed and no address is being typed */
static void pad_poll(SDL_Window *win, int keys_free)
{
    if (!g_pad_m) return;
    if (g_pad_test) pad_test_script();
    unsigned char msg[32];
    pad_build(msg, win, keys_free);
    SDL_LockMutex(g_pad_m);
    if (memcmp(msg, g_pad_msg, sizeof(msg)) != 0) {
        memcpy(g_pad_msg, msg, sizeof(msg));
        g_pad_dirty = 1;
        SDL_CondSignal(g_pad_cv);
    }
    SDL_UnlockMutex(g_pad_m);
}

static int pad_send_now(const unsigned char *msg)
{
    int ok = 0;
    SDL_LockMutex(g_tx_m);
    if (g_sock != SOCK_BAD) ok = send(g_sock, (const char *)msg, 32, 0) == 32;
    else if (g_usb) {
        int n = 0;
        ok = libusb_bulk_transfer(g_usb, SFT_EP_OUT, (unsigned char *)msg, 32, &n, 50) == 0 && n == 32;
    }
    SDL_UnlockMutex(g_tx_m);
    return ok;
}

static int pad_main(void *arg)
{
    (void)arg;
    int attached = 0;                      /* the console has had a message with a controller */
    unsigned char msg[32];
    while (!g_pad_stop) {
        SDL_LockMutex(g_pad_m);
        if (!g_pad_dirty && !g_pad_stop) SDL_CondWaitTimeout(g_pad_cv, g_pad_m, 100);
        memcpy(msg, g_pad_msg, sizeof(msg));
        g_pad_dirty = 0;
        SDL_UnlockMutex(g_pad_m);
        if (g_pad_stop) break;
        if (!SDL_AtomicGet(&g_console_takes_pad) || (!g_usb && g_sock == SOCK_BAD)) {
            attached = 0;
            SDL_AtomicSet(&g_pad_live, 0);
            continue;
        }
        const int on = msg[4] & 1;
        if (!on && !attached) continue;     /* no controller, and the console has none of ours */
        if (pad_send_now(msg)) attached = on;
        SDL_AtomicSet(&g_pad_live, attached);
    }
    SDL_AtomicSet(&g_pad_live, 0);
    return 0;
}

static void pad_start(void)
{
    if (!g_pad_m || g_pad_th) return;
    g_pad_stop = 0;
    SDL_LockMutex(g_pad_m);
    g_pad_dirty = 1;                       /* the current state, first thing */
    SDL_UnlockMutex(g_pad_m);
    g_pad_th = SDL_CreateThread(pad_main, "pad-sender", NULL);
}

static void pad_stop(void)
{
    if (!g_pad_th) return;
    SDL_LockMutex(g_pad_m);
    g_pad_stop = 1;
    SDL_CondSignal(g_pad_cv);
    SDL_UnlockMutex(g_pad_m);
    SDL_WaitThread(g_pad_th, NULL);
    g_pad_th = NULL;
}

/* Settings: what the row says under "Play the Switch with this controller" */
static const char *pad_status(char *b, size_t n)
{
    if (!g_pad_send) snprintf(b, n, "Off");
    else if (!g_pad_inited || !g_pad_m) snprintf(b, n, "Controllers are not available");
    else if (!g_pad) snprintf(b, n, "No controller connected");
    else if (SDL_AtomicGet(&g_pad_live)) snprintf(b, n, "%s - playing the Switch", g_pad_name);
    else if (g_usb || g_sock != SOCK_BAD) snprintf(b, n, "%s - turn it on in the Switch app (Beta page)", g_pad_name);
    else snprintf(b, n, "%s - ready", g_pad_name);
    return b;
}

static const char *pad_kbd_status(char *b, size_t n)
{
    if (!g_pad_kbd) snprintf(b, n, "Off - the letter keys are the viewer's shortcuts");
    else if (SDL_AtomicGet(&g_pad_live)) snprintf(b, n, "WASD, IJKL, arrows; Z X C V = A B X Y; Esc: Settings");
    else if (g_usb || g_sock != SOCK_BAD) snprintf(b, n, "Turn it on in the Switch app (Beta page)");
    else snprintf(b, n, "WASD, IJKL, arrows; Z X C V = A B X Y");
    return b;
}

static void act_pad_kbd(int on)
{
    g_pad_kbd = on != 0;
    prefs_save();
    hud_toast(g_pad_kbd ? "Keyboard plays the Switch - Esc opens Settings" : "Keyboard shortcuts are back");
    fprintf(stderr, "\nkeyboard to the Switch %s\n", g_pad_kbd ? "on" : "off");
}

static void act_pad_send(int on)
{
    g_pad_send = on != 0;
    prefs_save();
    hud_toast(g_pad_send ? "Controller plays the Switch" : "Controller stays on this computer");
    fprintf(stderr, "\ncontroller to the Switch %s\n", g_pad_send ? "on" : "off");
}
