package ru.alexv.anyailive.voice;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/** Asynchronous PCM16 playback. Flush and short nonblocking writes share one monitor. */
public final class AudioOut {
    public static final int SAMPLE_RATE = 24000;
    private final int sampleRate;
    private final int channels;
    private final Object lock = new Object();
    private final AudioOutBuffer buffer;
    // All mutable playback state is protected by lock, including AudioTrack access.
    private AudioTrack track;
    private Thread writer;
    private boolean running;
    private String lastError;

    public AudioOut() { this(SAMPLE_RATE, 1); }

    public AudioOut(int sampleRate, int channels) {
        this.sampleRate = sampleRate <= 0 ? SAMPLE_RATE : sampleRate;
        this.channels = channels == 2 ? 2 : 1;
        buffer = new AudioOutBuffer(this.sampleRate, this.channels);
    }

    public int sampleRate() { return sampleRate; }
    public int channels() { return channels; }
    public String lastError() { synchronized (lock) { return lastError; } }

    public boolean start() {
        synchronized (lock) {
            if (running) return true;
            releaseTrackLocked();
            buffer.flush();
            int mask = channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
            try {
                int min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) min = sampleRate * channels;
                track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, mask,
                        AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(min * 2, sampleRate * channels / 2), AudioTrack.MODE_STREAM);
                if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                    lastError = "Динамик не поддерживает " + sampleRate + " Гц";
                    releaseTrackLocked();
                    return false;
                }
                track.play();
            } catch (RuntimeException e) {
                lastError = "Не удалось открыть динамик: " + e.getMessage();
                releaseTrackLocked();
                return false;
            }
            lastError = null;
            running = true;
            writer = new Thread(new Runnable() {
                @Override public void run() { loop(); }
            }, "AudioOut");
            writer.setDaemon(true);
            writer.start();
            return true;
        }
    }

    /** Copy and queue PCM without waiting for hardware buffer space. */
    public void write(byte[] pcm, int len) {
        if (pcm == null || len <= 0) return;
        int n = Math.min(len, pcm.length);
        n -= n % (2 * channels);
        if (n == 0) return;
        synchronized (lock) {
            if (!running || track == null) return;
            byte[] copy = new byte[n];
            System.arraycopy(pcm, 0, copy, 0, n);
            buffer.enqueue(copy);
            lock.notifyAll();
        }
    }

    /** Invalidate queued AND writer-owned audio before discarding the hardware buffer. */
    public void flush() {
        synchronized (lock) {
            buffer.flush();
            if (track != null) {
                try {
                    track.pause();
                    track.flush(); // pause + flush resets AudioTrack's playback head to zero.
                    if (running) track.play();
                } catch (RuntimeException e) {
                    lastError = "Не удалось сбросить динамик: " + e.getMessage();
                    running = false;
                    releaseTrackLocked();
                }
            }
            lock.notifyAll();
        }
    }

    public boolean isPlaying() {
        synchronized (lock) {
            if (!running || track == null) return false;
            try {
                return buffer.hasAudio(track.getPlaybackHeadPosition());
            } catch (RuntimeException e) {
                return buffer.hasPending();
            }
        }
    }

    /** Actual hardware playback elapsed since the most recent flush/start, not queued duration. */
    public long playedMillis() {
        synchronized (lock) {
            if (track == null) return 0;
            try {
                return buffer.playedMillis(track.getPlaybackHeadPosition());
            } catch (RuntimeException e) {
                return 0;
            }
        }
    }

    /** Number of chunks waiting behind the writer-owned chunk. */
    public int queued() { synchronized (lock) { return buffer.queued(); } }

    public void stop() {
        Thread oldWriter;
        synchronized (lock) {
            running = false;
            buffer.flush();
            oldWriter = writer;
            writer = null;
            releaseTrackLocked();
            lock.notifyAll();
        }
        // Never join while holding lock: the exiting writer needs it. A concurrent start
        // is safe because loop also checks the writer identity, not just running.
        if (oldWriter != null && oldWriter != Thread.currentThread()) {
            try {
                oldWriter.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void release() { stop(); }

    private void releaseTrackLocked() {
        AudioTrack t = track;
        track = null;
        if (t == null) return;
        try { t.pause(); } catch (RuntimeException ignored) { }
        try { t.flush(); } catch (RuntimeException ignored) { }
        try { t.stop(); } catch (RuntimeException ignored) { }
        try { t.release(); } catch (RuntimeException ignored) { }
    }

    private void loop() {
        int failures = 0;
        AudioOutBuffer.Chunk previous = null;
        try {
            for (;;) {
                synchronized (lock) {
                    if (!running || writer != Thread.currentThread()) return;
                    AudioOutBuffer.Chunk chunk = buffer.current();
                    if (chunk == null) {
                        lock.wait();
                        continue;
                    }
                    if (chunk != previous) {
                        previous = chunk;
                        failures = 0;
                    }
                    if (!buffer.isCurrent(chunk)) continue;
                    // At most 10 ms per call, nonblocking even when AudioTrack is full.
                    // Checking the epoch, submitting bytes and accounting are atomic vs flush.
                    int count = Math.min(chunk.data.length - chunk.offset,
                            Math.max(1, sampleRate / 100) * channels * 2);
                    int written;
                    try {
                        written = track.write(chunk.data, chunk.offset, count, AudioTrack.WRITE_NON_BLOCKING);
                    } catch (RuntimeException e) {
                        written = -1;
                    }
                    if (written > 0) {
                        buffer.accept(chunk, written);
                        failures = 0;
                    } else {
                        if (++failures > 200) {
                            lastError = "Динамик перестал принимать звук";
                            running = false;
                            buffer.flush();
                            releaseTrackLocked();
                            return;
                        }
                        lock.wait(5); // releases monitor so flush/stop never wait for buffer space
                    }
                }
                Thread.yield();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
