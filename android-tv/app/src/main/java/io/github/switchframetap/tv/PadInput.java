/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.util.Arrays;

/** v0.7.20-beta3: a game controller on this device plays the Switch, the same "SFTI" message as the PC
 *  viewer. Nothing runs while the option is off, no controller is here, or the console does not ask for
 *  one (its headers carry flag 128 only with "Controls from the viewer" on); the video path is never
 *  touched - the events are read on the UI thread and a small thread of its own sends them. */
final class PadInput implements InputManager.InputDeviceListener {
    interface Host {
        StreamEngine engine();
        /** Called from the sender thread. */
        void openSettings();
    }
    private static final long REPEAT_NS = 100_000_000L, CHORD_NS = 1_500_000_000L;
    private final Host host;
    private final Handler ui;
    private final InputManager manager;
    private final PadMath.State state = new PadMath.State();
    /** Waits for the next pad button, to bind it: called on the UI thread with the Android key code pressed. */
    interface Want { void got(int keyCode); }
    private static final long CAPTURE_GUARD_NS = 250_000_000L;
    private static final int[] FACE_KEYS = { KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y };
    /** The Android key each Switch button in PadMath.REMAP takes unless the user bound another one (the
     *  face buttons depend on "Match the button labels"; see defaultKey). */
    private static final int[] FIXED_KEYS = { 0, 0, 0, 0, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_Z };
    private final PadMath.State probe = new PadMath.State();   // what the Controller menu's test box shows
    private final int[] custom = new int[PadMath.REMAP.length];   // UI thread only: 0 = the default key
    private volatile boolean enabled = true, labels, playing, resumed, seen, probing;
    private Want capture;           // UI thread only
    private long captureNs;
    private int swallow;           // the key whose events end with the capture's, UI thread only
    private volatile int pads, seenDevice = -2;
    private volatile String padName = "";
    private boolean dirty;         // guarded by this
    private Thread thread;         // guarded by this
    private int rightDevice = -2;  // UI thread only: which pad the right-stick axes below were read for
    private boolean rightZ = true;

    PadInput(Context context, Handler ui, Host host) {
        this.host = host; this.ui = ui;
        manager = (InputManager)context.getSystemService(Context.INPUT_SERVICE);
    }

    /* ---- what the UI tells it ----------------------------------------------- */

    void attach() {
        resumed = true;
        if (manager != null) { manager.registerInputDeviceListener(this, ui); scan(); }
        sync();
    }
    void detach() {
        resumed = false;
        if (manager != null) manager.unregisterInputDeviceListener(this);
        capture = null; swallow = 0; probing = false; probe.reset();
        state.reset(); poke();
    }
    void enable(boolean value) {
        enabled = value;
        if (!value) state.reset();
        poke(); sync();
    }
    void labels(boolean value) { labels = value; state.reset(); probe.reset(); poke(); }
    /** Whether the buttons now go to the console: this screen is in front and no menu is over it. */
    void playing(boolean value) {
        if (value == playing) return;
        playing = value;
        if (!value) state.reset();
        poke();
    }
    /** Starts the sender when there is something to send to; it ends by itself when there is not. */
    synchronized void sync() {
        if (thread == null && shouldRun()) {
            Thread t = new Thread(this::run, "pad-input");
            t.setDaemon(true);
            thread = t; dirty = true;
            t.start();
        }
    }
    String status() {
        if (!enabled) return "Off. The controller keeps working in this app's menus.";
        String who = pads > 0 ? padName : seen ? "A controller" : "";
        if (who.isEmpty()) return "No controller found. Connect one by Bluetooth or USB.";
        StreamEngine e = host.engine();
        if (e == null) return who + " is ready. Connect to the Switch to play.";
        if (!e.consoleTakesInput())
            return who + " is ready, but the Switch is not taking a controller. Turn on \"Controls from the viewer\" in the Switch Frame Tap app on the Switch.";
        return who + " plays the Switch. Hold + and − for 1.5 seconds to open Settings while playing.";
    }

    /* ---- events (UI thread) ------------------------------------------------- */

    boolean key(KeyEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) return false;
        final int code = e.getKeyCode(), action = e.getAction();
        final Want c = capture;
        if (c != null) {   // a button is being bound: every pad button goes to it, none to the menu or the console
            if (!KeyEvent.isGamepadButton(code)) return false;
            if (action == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && System.nanoTime() - captureNs >= CAPTURE_GUARD_NS) {
                capture = null; swallow = code;
                c.got(code);
            }
            return true;
        }
        if (swallow != 0) {
            if (code == swallow) { if (action == KeyEvent.ACTION_UP) swallow = 0; return true; }
            if (action == KeyEvent.ACTION_UP || action == KeyEvent.ACTION_DOWN) swallow = 0;
        }
        final long bit = bit(code);
        if (bit == 0) return false;
        final boolean down = action == KeyEvent.ACTION_DOWN, change = down || action == KeyEvent.ACTION_UP;
        if (probing && change) probe.key(bit, down);
        if (!enabled || !takes()) return false;
        noticed(e.getDeviceId());
        if (change && state.key(bit, down)) poke();
        return true;
    }
    boolean motion(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_MOVE
                || (e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return false;
        final boolean play = enabled && takes();
        if (!play && !probing) return false;
        final int id = e.getDeviceId();
        if (id != rightDevice) {   // the right stick is Z/RZ on most pads and RX/RY on the rest
            rightDevice = id;
            InputDevice d = e.getDevice();
            rightZ = d == null || d.getMotionRange(MotionEvent.AXIS_Z, InputDevice.SOURCE_JOYSTICK) != null;
        }
        final double lx = e.getAxisValue(MotionEvent.AXIS_X), ly = e.getAxisValue(MotionEvent.AXIS_Y),
                rx = e.getAxisValue(rightZ ? MotionEvent.AXIS_Z : MotionEvent.AXIS_RX),
                ry = e.getAxisValue(rightZ ? MotionEvent.AXIS_RZ : MotionEvent.AXIS_RY),
                hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y),
                tl = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)),
                tr = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
        if (probing) { probe.sticks(lx, ly, rx, ry); probe.hat(hx, hy); probe.triggers(tl, tr); }
        if (!play) return false;   // a hat move still moves the menu focus
        noticed(id);
        boolean changed = state.sticks(lx, ly, rx, ry);
        changed |= state.hat(hx, hy);
        changed |= state.triggers(tl, tr);
        if (changed) poke();
        return true;
    }
    private boolean takes() {
        if (!playing) return false;
        StreamEngine e = host.engine();
        return e != null && e.consoleTakesInput();
    }
    /** Events from a pad Android did not list (an injected one): it is here now. */
    private void noticed(int id) {
        if (id == seenDevice) return;
        seenDevice = id;
        if (!seen) { seen = true; sync(); }
    }
    private long bit(int code) {
        for (int i = 0; i < custom.length; i++) if (key(i) == code) return PadMath.REMAP[i];
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return PadMath.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return PadMath.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return PadMath.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return PadMath.RIGHT;
            default: return 0;
        }
    }

    /* ---- the Controller menu (UI thread) ------------------------------------ */

    /** The Android key that is the default for Switch button PadMath.REMAP[i]: the face buttons go by place
     *  (the bottom one is B) or, with the labels option, by name. */
    private int defaultKey(int i) {
        if (i >= FACE_KEYS.length) return FIXED_KEYS[i];
        for (int k = 0; k < FACE_KEYS.length; k++) if (PadMath.face(k, labels) == PadMath.REMAP[i]) return FACE_KEYS[k];
        return 0;
    }
    /** The Android key now bound to Switch button PadMath.REMAP[i]. */
    int key(int i) { return custom[i] != 0 ? custom[i] : defaultKey(i); }
    /** Binds a key to Switch button i; the button that had the key gets the one i had (so none is lost). */
    void assign(int i, int keyCode) {
        final int old = key(i);
        for (int j = 0; j < custom.length; j++) if (j != i && key(j) == keyCode) custom[j] = old;
        custom[i] = keyCode;
        state.reset(); probe.reset(); poke();
    }
    void resetMap() { Arrays.fill(custom, 0); state.reset(); probe.reset(); poke(); }
    boolean customised() { for (int c : custom) if (c != 0) return true; return false; }
    /** The binding as a preference string, "index:keycode,..." for the keys that are not the default. */
    String mapping() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < custom.length; i++) if (custom[i] != 0) { if (out.length() > 0) out.append(','); out.append(i).append(':').append(custom[i]); }
        return out.toString();
    }
    void mapping(String saved) {
        Arrays.fill(custom, 0);
        if (saved != null) for (String part : saved.split(",")) {
            final int at = part.indexOf(':');
            if (at < 1) continue;
            try {
                final int i = Integer.parseInt(part.substring(0, at)), code = Integer.parseInt(part.substring(at + 1));
                if (i >= 0 && i < custom.length && KeyEvent.isGamepadButton(code)) custom[i] = code;
            } catch (NumberFormatException ignored) { }
        }
        state.reset(); probe.reset(); poke();
    }
    /** Stick dead zone and trigger press point, in percent. */
    void tune(int deadPercent, int triggerPercent) {
        state.tune(deadPercent / 100.0, triggerPercent / 100.0);
        probe.tune(deadPercent / 100.0, triggerPercent / 100.0);
    }
    /** Asks for the next pad button (null: stop asking). */
    void listen(Want c) {
        capture = c; captureNs = System.nanoTime(); swallow = 0;
    }
    boolean listening() { return capture != null; }
    /** While the Controller menu is open, the pad's buttons and sticks are also read for its test box, whether
     *  or not the console takes a controller. */
    void probing(boolean on) {
        if (probing == on) return;
        probing = on;
        probe.reset();
    }
    String pressing() { return probe.describe(); }
    /** A short name for an Android key code, "L1", "Left stick click". */
    static String keyName(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: return "A";
            case KeyEvent.KEYCODE_BUTTON_B: return "B";
            case KeyEvent.KEYCODE_BUTTON_X: return "X";
            case KeyEvent.KEYCODE_BUTTON_Y: return "Y";
            case KeyEvent.KEYCODE_BUTTON_L1: return "L1";
            case KeyEvent.KEYCODE_BUTTON_R1: return "R1";
            case KeyEvent.KEYCODE_BUTTON_L2: return "L2";
            case KeyEvent.KEYCODE_BUTTON_R2: return "R2";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "Left stick click";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "Right stick click";
            case KeyEvent.KEYCODE_BUTTON_START: return "Start";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "Select";
            case KeyEvent.KEYCODE_BUTTON_MODE: return "Mode";
            default:
                String n = KeyEvent.keyCodeToString(code);
                if (n.startsWith("KEYCODE_BUTTON_")) n = n.substring(15);
                else if (n.startsWith("KEYCODE_")) n = n.substring(8);
                return n;
        }
    }

    /* ---- which controllers are here ----------------------------------------- */

    private static boolean pad(InputDevice d) {
        if (d == null || d.isVirtual()) return false;
        final int s = d.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }
    private void scan() {
        int n = 0;
        String name = "";
        for (int id : manager.getInputDeviceIds()) {
            InputDevice d = manager.getInputDevice(id);
            if (pad(d) && n++ == 0) name = d.getName();
        }
        pads = n; padName = name == null ? "" : name;
    }
    @Override public void onInputDeviceAdded(int id) { scan(); sync(); poke(); }
    @Override public void onInputDeviceChanged(int id) { scan(); sync(); poke(); }
    @Override public void onInputDeviceRemoved(int id) {
        scan();
        if (id == seenDevice) { seenDevice = -2; seen = false; }
        state.reset(); poke();
    }

    /* ---- the sender --------------------------------------------------------- */

    private boolean present() { return pads > 0 || seen; }
    private boolean shouldRun() { return resumed && enabled && present() && host.engine() != null; }
    private synchronized void poke() { dirty = true; notifyAll(); }

    /** Send on a change and repeat the last state every 100 ms (the console lets go after half a second
     *  of silence); say "no controller" once if it had one. */
    private void run() {
        final Thread me = Thread.currentThread();
        StreamEngine last = null;
        boolean attached = false;
        byte[] sent = null;
        long sentNs = 0, chordNs = 0;
        boolean chordDone = false;
        while (true) {
            synchronized (this) {
                if (thread != me) return;
                if (!dirty) {
                    try { wait(100); } catch (InterruptedException e) { thread = null; return; }
                }
                dirty = false;
            }
            final StreamEngine e = host.engine();
            if (e != last) { last = e; attached = false; sent = null; }
            if (e == null || !resumed || !enabled || !present() || !e.consoleTakesInput()) {
                if (attached && e != null) e.sendInput(state.message(false, false));
                attached = false; sent = null; chordNs = 0; chordDone = false;
                synchronized (this) {
                    if (!shouldRun()) { if (thread == me) thread = null; return; }
                }
                continue;
            }
            final boolean live = playing;
            final long now = System.nanoTime();
            if (live && state.held(PadMath.PLUS | PadMath.MINUS)) {
                if (chordNs == 0) chordNs = now;
                else if (!chordDone && now - chordNs >= CHORD_NS) { chordDone = true; state.reset(); host.openSettings(); }
            } else { chordNs = 0; chordDone = false; }
            final byte[] m = state.message(true, live);
            if (sent == null || now - sentNs >= REPEAT_NS || !Arrays.equals(m, sent)) {
                if (e.sendInput(m)) { sent = m; sentNs = now; attached = true; }
                else sent = null;
            }
        }
    }
}
