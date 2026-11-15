package ru.alexv.anyailive.voice;

import java.util.ArrayDeque;

/** Pure playback bookkeeping. Caller must hold AudioOut's monitor for every access. */
final class AudioOutBuffer {
    static final class Chunk {
        final byte[] data;
        final long epoch;
        int offset;
        Chunk(byte[] data, long epoch) { this.data = data; this.epoch = epoch; }
    }

    private final int sampleRate;
    private final int frameBytes;
    private final ArrayDeque<Chunk> queue = new ArrayDeque<Chunk>();
    private Chunk current;
    private long epoch;
    private long writtenBytes;
    private long previousHead;
    private long headWraps;

    AudioOutBuffer(int sampleRate, int channels) {
        this.sampleRate = sampleRate;
        this.frameBytes = channels * 2;
    }

    void enqueue(byte[] data) { queue.add(new Chunk(data, epoch)); }
    int queued() { return queue.size(); }
    Chunk current() {
        if (current == null) current = queue.poll();
        return current;
    }
    boolean hasPending() { return current != null || !queue.isEmpty(); }
    boolean isCurrent(Chunk chunk) {
        return chunk != null && chunk == current && chunk.epoch == epoch;
    }
    void accept(Chunk chunk, int count) {
        if (!isCurrent(chunk) || count <= 0) return;
        chunk.offset += count;
        writtenBytes += count;
        if (chunk.offset == chunk.data.length) current = null;
    }
    void flush() {
        epoch++;
        queue.clear();
        current = null;
        writtenBytes = 0;
        previousHead = 0;
        headWraps = 0;
    }
    long writtenFrames() { return writtenBytes / frameBytes; }
    long playedFrames(int head) {
        long unsigned = head & 0xffffffffL;
        if (unsigned < previousHead) headWraps++;
        previousHead = unsigned;
        return (headWraps << 32) + unsigned;
    }
    boolean hasAudio(int head) {
        long played = playedFrames(head);
        return hasPending() || played < writtenFrames();
    }
    long playedMillis(int head) {
        return Math.min(playedFrames(head), writtenFrames()) * 1000 / sampleRate;
    }
}
