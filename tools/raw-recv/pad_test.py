#!/usr/bin/env python3
"""v0.7.20: raw-view's controller, end to end on the PC.

A fake console on 127.0.0.1:9950 says "send the controller" (header flag 128)
for 2.5 s, then stops saying it; raw-view --pad-test presses SDL's virtual
controller and keyboard on a script (pad.h). Checks that every press arrives as the
console's HidNpadButton bits, sticks with y up, keepalives while nothing
changes, and nothing at all once the console stops asking.

    python3 tools/raw-recv/pad_test.py      # needs a built raw-view
"""
import os, select, socket, struct, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
ON_SECS, TOTAL = 2.8, 4.3

ls = socket.socket()
ls.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
ls.bind(("127.0.0.1", 9950))
ls.listen(1)
env = dict(os.environ, SDL_VIDEODRIVER="dummy", SDL_RENDER_DRIVER="software", SDL_AUDIODRIVER="dummy")
viewer = subprocess.Popen([os.path.join(HERE, "raw-view"), "--net", "127.0.0.1", "--pad-test", "--seconds", str(TOTAL - 0.5)],
                          env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
ls.settimeout(10)
c, _ = ls.accept()
t0 = time.time()
last_hello, buf, states, late, pads = 0.0, b"", set(), 0, 0
while time.time() - t0 < TOTAL:
    now = time.time()
    if now - last_hello >= 0.2:
        try:
            c.sendall(struct.pack("<IHHIIIIII", 0x52544653, 2, 128 if now - t0 < ON_SECS else 0, 0, 0, 0, 0, 0, 0))
        except OSError:
            break
        last_hello = now
    if not select.select([c], [], [], 0.05)[0]:
        continue
    try:
        d = c.recv(4096)
    except ConnectionResetError:           # the viewer closed with our hellos unread
        break
    if not d:
        break
    buf += d
    while len(buf) >= 4:
        if buf[:4] == b"SFTI":
            if len(buf) < 32:
                break
            _, flags, buttons, lx, ly, rx, ry = struct.unpack("<4sIQiiii", buf[:32])
            buf = buf[32:]
            pads += 1
            states.add((flags, buttons, lx, ly, rx, ry))
            if time.time() - t0 > ON_SECS + 0.3:
                late += 1
        elif buf[:3] == b"SFT":
            buf = buf[4:]
        else:
            buf = buf[1:]
viewer.wait(timeout=10)

want = {
    (1, 0x2, 0, 0, 0, 0): "B (the bottom button)",
    (1, 0x0, 32767, 0, 0, -32767): "left stick right, right stick down",
    (1, 0x40000, 0, 0, 0, 0): "HOME",
    (1, 0x200, 0, 0, 0, 0): "ZR (a trigger)",
    (1, 0x1, 23170, 23170, 0, 0): "the keyboard's W + D + Z (up-right on the circle, A)",
}
bad = [name for st, name in want.items() if st not in states]
if bad:
    print("missing:", ", ".join(bad), "- got", sorted(states))
    sys.exit(1)
if pads < 20:
    print("only %d messages in %.1f s - the 100 ms keepalive is missing" % (pads, ON_SECS))
    sys.exit(1)
if late:
    print("%d messages after the console stopped asking" % late)
    sys.exit(1)
print("OK: %d messages, %d states" % (pads, len(states)))
