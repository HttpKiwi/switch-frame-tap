/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.util.Arrays;

/** v0.7.20-beta2: a game controller on this device plays the Switch, the same "SFTI" message as the PC
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
    private volatile boolean enabled = true, labels, playing, resumed, seen;
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
        state.reset(); poke();
    }
    void enable(boolean value) {
        enabled = value;
        if (!value) state.reset();
        poke(); sync();
    }
    void labels(boolean value) { labels = value; state.reset(); poke(); }
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
        if (!enabled || (e.getSource() & InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD) return false;
        final long bit = bit(e.getKeyCode());
        if (bit == 0 || !takes()) return false;
        noticed(e.getDeviceId());
        final int action = e.getAction();
        if ((action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) && state.key(bit, action == KeyEvent.ACTION_DOWN)) poke();
        return true;
    }
    boolean motion(MotionEvent e) {
        if (!enabled || e.getAction() != MotionEvent.ACTION_MOVE
                || (e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK || !takes()) return false;
        final int id = e.getDeviceId();
        noticed(id);
        if (id != rightDevice) {   // the right stick is Z/RZ on most pads and RX/RY on the rest
            rightDevice = id;
            InputDevice d = e.getDevice();
            rightZ = d == null || d.getMotionRange(MotionEvent.AXIS_Z, InputDevice.SOURCE_JOYSTICK) != null;
        }
        boolean changed = state.sticks(e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y),
                e.getAxisValue(rightZ ? MotionEvent.AXIS_Z : MotionEvent.AXIS_RX),
                e.getAxisValue(rightZ ? MotionEvent.AXIS_RZ : MotionEvent.AXIS_RY));
        changed |= state.hat(e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y));
        changed |= state.triggers(Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)),
                Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)));
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
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: return PadMath.face(0, labels);
            case KeyEvent.KEYCODE_BUTTON_B: return PadMath.face(1, labels);
            case KeyEvent.KEYCODE_BUTTON_X: return PadMath.face(2, labels);
            case KeyEvent.KEYCODE_BUTTON_Y: return PadMath.face(3, labels);
            case KeyEvent.KEYCODE_BUTTON_L1: return PadMath.L;
            case KeyEvent.KEYCODE_BUTTON_R1: return PadMath.R;
            case KeyEvent.KEYCODE_BUTTON_L2: return PadMath.ZL;
            case KeyEvent.KEYCODE_BUTTON_R2: return PadMath.ZR;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return PadMath.L3;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return PadMath.R3;
            case KeyEvent.KEYCODE_BUTTON_START: return PadMath.PLUS;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return PadMath.MINUS;
            case KeyEvent.KEYCODE_BUTTON_MODE: return PadMath.HOME;
            case KeyEvent.KEYCODE_BUTTON_Z: return PadMath.CAPTURE;   // a Pro Controller's Capture button, on most layouts
            case KeyEvent.KEYCODE_DPAD_UP: return PadMath.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return PadMath.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return PadMath.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return PadMath.RIGHT;
            default: return 0;
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
