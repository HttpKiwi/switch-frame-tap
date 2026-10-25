/* SPDX-License-Identifier: GPL-2.0-only */
package io.github.switchframetap.tv;

/** v0.7.20-beta2: a controller on this device plays the Switch - the same "SFTI"
 *  message and the same feel as the PC viewer (tools/raw-recv/pad.h). No Android
 *  classes here, so the host tests run it. */
final class PadMath {
    /** HidNpadButton bits, as the console reads them. */
    static final long A = 1L, B = 1L << 1, X = 1L << 2, Y = 1L << 3, L3 = 1L << 4, R3 = 1L << 5,
            L = 1L << 6, R = 1L << 7, ZL = 1L << 8, ZR = 1L << 9, PLUS = 1L << 10, MINUS = 1L << 11,
            LEFT = 1L << 12, UP = 1L << 13, RIGHT = 1L << 14, DOWN = 1L << 15, HOME = 1L << 18, CAPTURE = 1L << 19;
    private static final double FULL = 32767.0, DEAD = 0.08 * FULL, TRIGGER = 0.30 * FULL;
    private static final long[] BY_PLACE = { B, A, Y, X }, BY_LABEL = { A, B, X, Y };

    /** Android's BUTTON_A, B, X, Y (0..3: bottom, right, left, top on an Xbox-style pad) as a Switch
     *  button. By place (the PC viewer's default) the bottom button is the Switch's B; by label, A is A. */
    static long face(int index, boolean labels) { return (labels ? BY_LABEL : BY_PLACE)[index]; }

    /** One stick: x and y as Android reports them (-1..1, y down) become the Switch's (-32767..32767, up +y)
     *  in out[at], out[at + 1]. A small round dead zone (a resting stick drifts a few percent), the rest
     *  stretched to the full range. */
    static void stick(double x, double y, int[] out, int at) {
        final double sx = x * FULL, sy = -y * FULL, m = Math.sqrt(sx * sx + sy * sy);
        if (!(m > DEAD)) { out[at] = out[at + 1] = 0; return; }
        double k = (m - DEAD) / (FULL - DEAD) / m * FULL;
        if (m * k > FULL) k = FULL / m;
        out[at] = (int)Math.max(-FULL, Math.min(FULL, sx * k));
        out[at + 1] = (int)Math.max(-FULL, Math.min(FULL, sy * k));
    }

    /** A trigger (0..1) pressed past 30%, like the PC viewer's press point. */
    static boolean pressed(double trigger) { return trigger * FULL > TRIGGER; }

    /** Everything the console is told: the buttons from key events (face, shoulders, d-pad keys), the d-pad
     *  axes, the triggers, and the sticks. Called from the UI thread and read by the sender: synchronized. */
    static final class State {
        private long keys, hat, trig;
        private final int[] sticks = new int[4];
        private final int[] scratch = new int[2];

        private long buttons() { return keys | hat | trig; }
        private synchronized boolean apply(long keysNow, long hatNow, long trigNow) {
            final long before = buttons();
            keys = keysNow; hat = hatNow; trig = trigNow;
            return buttons() != before;
        }
        /** A button went down or up. True if the console would see a change. */
        synchronized boolean key(long bit, boolean down) { return apply(down ? keys | bit : keys & ~bit, hat, trig); }
        /** The d-pad as an axis pair (Android: x -1 left .. 1 right, y -1 up .. 1 down). */
        synchronized boolean hat(double x, double y) {
            return apply(keys, (x < -0.5 ? LEFT : 0) | (x > 0.5 ? RIGHT : 0) | (y < -0.5 ? UP : 0) | (y > 0.5 ? DOWN : 0), trig);
        }
        synchronized boolean triggers(double l, double r) {
            return apply(keys, hat, (pressed(l) ? ZL : 0) | (pressed(r) ? ZR : 0));
        }
        synchronized boolean sticks(double lx, double ly, double rx, double ry) {
            final int a = sticks[0], b = sticks[1], c = sticks[2], d = sticks[3];
            stick(lx, ly, scratch, 0); sticks[0] = scratch[0]; sticks[1] = scratch[1];
            stick(rx, ry, scratch, 0); sticks[2] = scratch[0]; sticks[3] = scratch[1];
            return a != sticks[0] || b != sticks[1] || c != sticks[2] || d != sticks[3];
        }
        synchronized boolean held(long mask) { return (keys & mask) == mask; }
        synchronized boolean idle() {
            return buttons() == 0 && sticks[0] == 0 && sticks[1] == 0 && sticks[2] == 0 && sticks[3] == 0;
        }
        synchronized void reset() { keys = hat = trig = 0; java.util.Arrays.fill(sticks, 0); }
        /** The message for the console: controller = a controller is here (false: let it go); playing = its
         *  buttons and sticks are passed on (false: attached and untouched, e.g. while a menu is open). */
        synchronized byte[] message(boolean controller, boolean playing) {
            if (!controller || !playing) return SftProtocol.inputMessage(controller, 0, 0, 0, 0, 0);
            return SftProtocol.inputMessage(true, buttons(), sticks[0], sticks[1], sticks[2], sticks[3]);
        }
    }
}
