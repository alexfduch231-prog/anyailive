package ru.alexv.anyailive.voice;

/** Standalone JVM regressions; no Android runtime required. */
public final class AudioOutBufferTests {
    private static int checks;

    public static void main(String[] args) {
        AudioOutBuffer b = new AudioOutBuffer(24000, 1);
        b.enqueue(new byte[4800]);
        AudioOutBuffer.Chunk old = b.current();
        check("writer-owned chunk remains pending", b.hasPending());
        b.accept(old, 480);
        check("partial chunk remains pending", b.hasPending());
        check("elapsed uses playback, not accepted bytes", b.playedMillis(120) == 5);
        b.flush();
        b.accept(old, 480);
        check("flush invalidates writer-owned chunk", !b.hasPending() && b.writtenFrames() == 0);
        check("elapsed resets on flush", b.playedMillis(0) == 0);
        b.enqueue(new byte[960]);
        AudioOutBuffer.Chunk fresh = b.current();
        b.accept(old, 480);
        check("stale completion cannot corrupt fresh chunk", fresh.offset == 0 && b.writtenFrames() == 0);
        b.accept(fresh, 960);
        check("submitted data waits for hardware playback", b.hasAudio(0));
        check("drained track is idle", !b.hasAudio(480));
        check("elapsed is capped at submitted audio", b.playedMillis(960) == 20);

        AudioOutBuffer stereo = new AudioOutBuffer(48000, 2);
        stereo.enqueue(new byte[8]);
        AudioOutBuffer.Chunk c = stereo.current();
        stereo.accept(c, 2);
        stereo.accept(c, 2);
        check("partial stereo writes retain fractional frame bytes", stereo.writtenFrames() == 1);
        stereo.accept(c, 4);
        check("stereo complete frame count", stereo.writtenFrames() == 2);

        AudioOutBuffer wrap = new AudioOutBuffer(24000, 1);
        check("head is unsigned", wrap.playedFrames(0x80000000) == 0x80000000L);
        check("head before rollover", wrap.playedFrames(0xfffffff0) == 0xfffffff0L);
        check("head rollover unwrapped", wrap.playedFrames(16) == 0x100000010L);
        wrap.flush();
        check("flush resets rollover state", wrap.playedFrames(3) == 3);
        System.out.println("AudioOutBufferTests: " + checks + " passed");
    }

    private static void check(String label, boolean ok) {
        if (!ok) throw new AssertionError(label);
        checks++;
    }
}
