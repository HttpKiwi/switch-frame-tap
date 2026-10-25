package io.github.switchframetap.tv;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** Exercises transport fragmentation and reference recovery, not Android mocks. */
public final class ProtocolTest {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name); checks++;
    }
    private static final class Chunks implements SftProtocol.Source {
        final ByteArrayInputStream stream;
        int calls;
        Chunks(byte[] data) { stream = new ByteArrayInputStream(data); }
        @Override public int read(byte[] b, int offset, int length) {
            if (++calls % 4 == 0) return 0; // idle timeout between partial reads
            return stream.read(b, offset, Math.min(length, calls % 17 + 1));
        }
        @Override public void close() { }
    }
    private static byte[] wire(int flags, int width, int height, long seq, byte[] payload) {
        byte[] h = new byte[32 + payload.length];
        put(h,0,0x52544653); h[4] = 2; h[6] = (byte)flags;
        put(h,8,width); put(h,12,height); put(h,16,25000); put(h,20,payload.length); put(h,28,seq);
        System.arraycopy(payload,0,h,32,payload.length); return h;
    }
    private static void put(byte[] h, int i, long value) { for (int j=0;j<4;j++) h[i+j]=(byte)(value >>> (j*8)); }
    private static void invalid(byte[] data, String name) throws Exception {
        try { SftProtocol.readPacket(new Chunks(data), () -> false); throw new AssertionError(name); }
        catch (IOException expected) { checks++; }
    }
    private static SftProtocol.Packet frame(long seq, boolean key, int w, long ns) {
        return new SftProtocol.Packet(2 | (key ? 4 : 0), w,720,seq,20000,new byte[100],ns);
    }
    public static void main(String[] args) throws Exception {
        byte[] nal = {0,0,0,1,0x67,0x64,0,0,1,0x68,0x12,0,0,1,0x65,9};
        byte[] audio = {1,2,3,4,5,6,7,8};
        ByteArrayOutputStream concatenated = new ByteArrayOutputStream();
        concatenated.write(wire(8,48000,2,1,audio)); concatenated.write(wire(6,1280,720,0xffffffffL,nal));
        Chunks source = new Chunks(concatenated.toByteArray());
        SftProtocol.Packet a = SftProtocol.readPacket(source, () -> false);
        SftProtocol.Packet v = SftProtocol.readPacket(source, () -> false);
        check(a.audio() && Arrays.equals(a.data,audio), "interleaved PCM with partial reads and timeouts");
        check(v.key() && v.sequence == 0xffffffffL && v.consoleAgeUs == 25000, "unsigned fields and v2 age");
        check(Arrays.equals(v.data,nal), "exact video boundary");
        byte[][] csd = SftProtocol.parameterSets(nal);
        check(csd[0][4] == 0x67 && csd[1][3] == 0x68, "mixed 3/4 byte Annex-B start codes");
        // The console's USB keep-alive (an empty header) is skipped, not an error.
        ByteArrayOutputStream hello = new ByteArrayOutputStream();
        hello.write(wire(0,0,0,0,new byte[0])); hello.write(wire(0,0,0,0,new byte[0])); hello.write(wire(8,48000,2,2,audio));
        SftProtocol.Packet afterHello = SftProtocol.readPacket(new Chunks(hello.toByteArray()), () -> false);
        check(afterHello.audio() && afterHello.sequence == 2, "empty keep-alive headers skipped");
        // Status: audio + status flags, rate 0; version, reason, 64-byte head, 120-byte detail.
        byte[] st = new byte[192];
        put(st,0,1); put(st,4,1);
        byte[] head = "Connected - no game is running".getBytes("UTF-8"), detail = "Start a game.".getBytes("UTF-8");
        System.arraycopy(head,0,st,8,head.length); System.arraycopy(detail,0,st,72,detail.length);
        SftProtocol.Packet status = SftProtocol.readPacket(new Chunks(wire(24,0,0,3,st)), () -> false);
        check(status.status() && !status.audio() && status.statusReason() == 1, "status packet recognised");
        check(status.statusHead().equals("Connected - no game is running") && status.statusDetail().equals("Start a game."), "status text");
        invalid(wire(18,0,0,0,st), "status only together with audio");
        invalid(wire(24,0,0,0,new byte[4]), "status too short");
        invalid(Arrays.copyOf(wire(6,1280,720,0,nal),37), "truncated payload");
        byte[] bad = wire(6,1280,720,0,nal); bad[0]=0; invalid(bad,"bad magic");
        bad = wire(6,1280,720,0,nal); bad[4]=1; invalid(bad,"old version");
        bad = wire(6,1280,720,0,nal); put(bad,20,0xffffffffL); invalid(bad,"unsigned oversized allocation rejected");
        invalid(wire(8,44100,2,0,audio), "unsupported sample rate");
        invalid(wire(8,48000,2,0,new byte[3]), "unaligned PCM");
        invalid(wire(2,4096,720,0,nal), "invalid dimensions");
        invalid(wire(10,1280,720,0,nal), "audio/video flag collision");
        // v0.7.16 consoles: a crop (32) and a centred crop (64) ride on video frames only
        SftProtocol.Packet cropped = SftProtocol.readPacket(new Chunks(wire(2 | 4 | 32 | 64, 1920, 1080, 5, nal)), () -> false);
        check(cropped.key() && Arrays.equals(cropped.data, nal), "cropped video frames accepted");
        invalid(wire(8 | 32, 48000, 2, 0, audio), "a crop on audio");
        invalid(wire(2 | 1, 1280, 720, 0, nal), "unknown flags still rejected");
        // v0.7.20 consoles with input on add flag 128 to video, status and the empty keep-alive headers
        SftProtocol.Packet inputVideo = SftProtocol.readPacket(new Chunks(wire(2 | 4 | 128, 1280, 720, 7, nal)), () -> false);
        check(inputVideo.key() && inputVideo.width == 1280 && Arrays.equals(inputVideo.data, nal), "flag 128 on video accepted");
        SftProtocol.Packet inputStatus = SftProtocol.readPacket(new Chunks(wire(24 | 128, 0, 0, 3, st)), () -> false);
        check(inputStatus.status() && inputStatus.statusReason() == 1, "flag 128 on a status packet accepted");
        ByteArrayOutputStream inputHello = new ByteArrayOutputStream();
        inputHello.write(wire(128, 0, 0, 0, new byte[0])); inputHello.write(wire(8, 48000, 2, 4, audio));
        check(SftProtocol.readPacket(new Chunks(inputHello.toByteArray()), () -> false).sequence == 4, "flag-128-only keep-alive skipped");
        // v0.7.20-beta2: the flag the console sets while it takes a controller, reported to the app
        final int[] seen = {-1, 0};   // last value, calls
        SftProtocol.InputFlag sink = on -> { seen[0] = on ? 1 : 0; seen[1]++; };
        SftProtocol.readPacket(new Chunks(wire(2 | 128, 1280, 720, 8, nal)), () -> false, sink);
        check(seen[0] == 1 && seen[1] == 1, "video with flag 128: the console takes a controller");
        SftProtocol.readPacket(new Chunks(wire(2 | 4, 1280, 720, 9, nal)), () -> false, sink);
        check(seen[0] == 0 && seen[1] == 2, "video without it: it does not");
        SftProtocol.readPacket(new Chunks(wire(24 | 128, 0, 0, 3, st)), () -> false, sink);
        check(seen[0] == 1 && seen[1] == 3, "a status packet carries it too");
        SftProtocol.readPacket(new Chunks(wire(8, 48000, 2, 5, audio)), () -> false, sink);
        check(seen[1] == 3, "plain audio never says (the console leaves the bit off it)");
        SftProtocol.readPacket(new Chunks(inputHello.toByteArray()), () -> false, sink);
        check(seen[0] == 1 && seen[1] == 4, "a flag-128-only keep-alive says it, then the next packet parses");
        // the message: "SFTI", flags, buttons (u64), lx ly rx ry - little-endian, 32 bytes
        byte[] msg = SftProtocol.inputMessage(true, PadMath.A | PadMath.ZR | PadMath.CAPTURE, 32767, -32767, 1, -2);
        byte[] want = new byte[32];
        want[0] = 'S'; want[1] = 'F'; want[2] = 'T'; want[3] = 'I'; want[4] = 1;
        want[8] = 1; want[9] = 2; want[10] = 8;                     // A (bit 0), ZR (bit 9), Capture (bit 19)
        want[16] = (byte)0xff; want[17] = 0x7f;                    // 32767
        want[20] = 1; want[21] = (byte)0x80; want[22] = (byte)0xff; want[23] = (byte)0xff;   // -32767
        want[24] = 1; want[28] = (byte)0xfe; want[29] = want[30] = want[31] = (byte)0xff;    // 1, -2
        check(Arrays.equals(msg, want), "SFTI message bytes");
        check(SftProtocol.inputMessage(false, 0, 0, 0, 0, 0)[4] == 0, "let go: flags 0");
        long hi = 1L << 40;
        check(SftProtocol.inputMessage(true, hi, 0, 0, 0, 0)[13] == 1, "buttons are 64-bit");
        // sticks: the PC viewer's maths (8% round dead zone, the rest stretched, up = +y)
        int[] s = new int[4];
        PadMath.stick(1, 0, s, 0);   check(s[0] == 32767 && s[1] == 0, "stick full right");
        PadMath.stick(0, -1, s, 0);  check(s[0] == 0 && s[1] == 32767, "stick up is +y (Android's y is down)");
        PadMath.stick(-1, 1, s, 2);  check(s[2] == -23169 && s[3] == -23169, "a diagonal stays on the circle");
        PadMath.stick(0.05, -0.05, s, 0); check(s[0] == 0 && s[1] == 0, "inside the dead zone");
        PadMath.stick(0.081, 0, s, 0); check(s[0] > 0 && s[0] < 200, "just past the dead zone starts from zero");
        PadMath.stick(0.5, 0, s, 0); check(s[0] == (int)((0.5 * 32767 - 2621.36) / (32767 - 2621.36) * 32767), "half tilt");
        PadMath.stick(Double.NaN, 0, s, 0); check(s[0] == 0 && s[1] == 0, "a NaN axis is neutral");
        check(!PadMath.pressed(0.3) && PadMath.pressed(0.31), "triggers press at 30%");
        check(PadMath.face(0, false) == PadMath.B && PadMath.face(1, false) == PadMath.A
              && PadMath.face(2, false) == PadMath.Y && PadMath.face(3, false) == PadMath.X, "face buttons by place");
        check(PadMath.face(0, true) == PadMath.A && PadMath.face(1, true) == PadMath.B
              && PadMath.face(2, true) == PadMath.X && PadMath.face(3, true) == PadMath.Y, "face buttons by label");
        PadMath.State pad = new PadMath.State();
        check(pad.idle() && !pad.key(PadMath.A, false), "a fresh state is idle; a release of nothing changes nothing");
        check(pad.key(PadMath.A, true) && !pad.key(PadMath.A, true) && !pad.idle(), "a press changes once");
        check(pad.hat(1, 0) && pad.hat(1, 0) == false && pad.hat(0, 0), "d-pad axis changes");
        check(pad.triggers(0.2, 0.9) && !pad.triggers(0.25, 1) && pad.triggers(0, 0), "triggers change at the press point only");
        pad.key(PadMath.UP, true); pad.hat(0, -1); pad.hat(0, 0);
        check(pad.held(PadMath.UP), "a d-pad key stays when the d-pad axis lets go");
        check(pad.sticks(1, 0, 0, 0) && !pad.sticks(1, 0, 0, 0) && pad.sticks(0, 0, 0, -1), "sticks change when they move");
        byte[] playing = pad.message(true, true);
        check(playing[0] == 'S' && playing[4] == 1 && (playing[8] & 1) != 0 && (playing[9] & 0x20) != 0   // A, Up (bit 13)
              && playing[24] == 0 && playing[25] == 0 && playing[26] == 0 && playing[27] == 0
              && playing[28] == (byte)0xff && playing[29] == 0x7f, "playing: buttons and sticks pass");
        byte[] resting = pad.message(true, false);
        check(resting[4] == 1 && resting[8] == 0 && resting[9] == 0 && resting[28] == 0 && resting[29] == 0,
              "attached and untouched while a menu is open");
        check(pad.message(false, true)[4] == 0 && pad.message(false, true)[8] == 0, "let go carries nothing");
        pad.key(PadMath.PLUS, true); pad.key(PadMath.MINUS, true);
        check(pad.held(PadMath.PLUS | PadMath.MINUS) && pad.key(PadMath.MINUS, false) && !pad.held(PadMath.PLUS | PadMath.MINUS), "the + and - chord");
        pad.reset();
        check(pad.idle() && pad.message(true, true)[8] == 0, "reset lets go of everything");
        // v0.7.16 recordings: an MP4 sample is the picture NAL units only
        byte[] picOnly = SftProtocol.pictureNals(new byte[]{0,0,0,1,0x09,0x10, 0,0,0,1,0x67,0x64,0, 0,0,1,0x68,0x12, 0,0,1,0x06,5,5, 0,0,1,0x65,9,8, 0,0,0,1,0x41,7});
        check(Arrays.equals(picOnly, new byte[]{0,0,0,1,0x65,9,8, 0,0,0,1,0x41,7}), "AUD, SPS, PPS and SEI left out of a sample");
        check(SftProtocol.pictureNals(new byte[]{0,0,1,0x67,1,2}).length == 0, "no picture NAL unit");
        // v0.7.18: a recording's requests start with the keyframe request (older consoles read only that)
        check(Arrays.equals(Arrays.copyOf(SftProtocol.RECORD_LOCK, 4), SftProtocol.KEYFRAME_REQUEST)
              && Arrays.equals(Arrays.copyOf(SftProtocol.RECORD_UNLOCK, 4), SftProtocol.KEYFRAME_REQUEST), "lock/unlock lead with SFTK");
        try { SftProtocol.parameterSets(new byte[]{0,0,1,0x65,1}); throw new AssertionError("missing SPS"); }
        catch (IOException expected) { checks++; }
        try { SftProtocol.readPacket(new Chunks(new byte[0]), () -> true); throw new AssertionError("cancellation"); }
        catch (IOException expected) { checks++; }
        check(SftProtocol.ipv4("192.168.1.50") && !SftProtocol.ipv4("256.1.1.1")
                && !SftProtocol.ipv4("1.2.3.4x") && !SftProtocol.ipv4("1..3.4"), "strict manual IP");
        check(SftProtocol.beaconPort("SFTAP1 192.168.1.3 9950 Switch Frame Tap") == 9950
                && SftProtocol.beaconPort("SFTAP1 1.2.3.4 65536") == -1
                && SftProtocol.beaconPort("OTHER 1.2.3.4 9950") == -1, "beacon validation");
        VideoQueue q = new VideoQueue();
        q.offer(frame(0,false,1280,0)); check(q.poll(1) == null,"initial P ignored");
        q.offer(frame(1,true,1280,0)); check(q.poll(1).discontinuity,"start at key frame");
        q.offer(frame(3,false,1280,0)); check(q.poll(1) == null,"gap invalidates P references");
        q.offer(frame(4,true,1280,0)); check(q.poll(1).discontinuity,"gap recovers at IDR");
        q.offer(frame(5,false,1920,0)); check(q.poll(1) == null,"resolution change waits for IDR");
        q.offer(frame(6,true,1920,0)); check(q.poll(1).discontinuity,"resolution change recovers");
        for(int i=7;i<=13;i++) q.offer(frame(i,false,1920,0));
        check(q.poll(1)==null,"overflow discards complete pending dependency chain");
        q.offer(frame(14,false,1920,0)); check(q.poll(1)==null,"overflow ignores dependent P");
        q.offer(frame(15,true,1920,0)); check(q.poll(1).discontinuity,"overflow recovers at key");
        q.offer(frame(16,false,1920,0)); q.offer(frame(17,false,1920,110_000_000L));
        check(q.poll(1)==null,"aged queue invalidates references");
        q.offer(frame(0,true,1920,120_000_000L)); check(q.poll(1).discontinuity,"new session sequence restart");
        VideoQueue wrap = new VideoQueue(); wrap.offer(frame(0xffffffffL,true,1280,0)); wrap.poll(1);
        wrap.offer(frame(0,false,1280,0)); check(!wrap.poll(1).discontinuity,"unsigned sequence wrap");
        q.close(); q.offer(frame(1,true,1280,0)); check(q.poll(1)==null,"closed queue");
        Path fixture = Paths.get(args[0], "logs/m83-runI/stream-first10.sft");
        if (Files.exists(fixture)) {
            Chunks real = new Chunks(Files.readAllBytes(fixture));
            int frames = 0;
            while (real.stream.available() > 0) {
                SftProtocol.Packet packet = SftProtocol.readPacket(real, () -> false);
                check(!packet.audio() && packet.width == 1280 && packet.height == 720,"real Switch wire packet");
                SftProtocol.parameterSets(packet.data); frames++;
            }
            check(frames > 0, "real NVENC fixture parsed");
            System.out.println("Real Switch fixture: " + frames + " frames");
        } else System.out.println("Real Switch fixture unavailable; synthetic tests completed");
        System.out.println("PASS: " + checks + " checks");
    }
}
