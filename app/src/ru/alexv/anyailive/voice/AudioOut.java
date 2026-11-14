package ru.alexv.anyailive.voice;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.ArrayDeque;

/**
 * Воспроизведение PCM16 LE потоком: 24 кГц моно по умолчанию (выход OpenAI/Gemini и
 * /audio/speech), плюс конструктор с частотой и каналами для системного TTS.
 *
 * write() только копирует кусок в очередь и будит поток-писатель, поэтому вызывающий
 * (сетевой поток, который читает следующий абзац) не ждёт, пока доиграет предыдущий.
 * flush() мгновенно обрывает звук — это перебивание.
 */
public final class AudioOut {

    public static final int SAMPLE_RATE = 24000;

    private final int sampleRate;
    private final int channels;
    /** Очередь чанков: отдельный монитор, чтобы stop() не дрался с write(). */
    private final Object lock = new Object();
    private final ArrayDeque<byte[]> queue = new ArrayDeque<byte[]>();

    private AudioTrack track;
    private Thread writer;
    private volatile boolean running;
    private long writtenFrames;
    private String lastError;

    public AudioOut() {
        this(SAMPLE_RATE, 1);
    }

    public AudioOut(int sampleRate, int channels) {
        this.sampleRate = sampleRate <= 0 ? SAMPLE_RATE : sampleRate;
        this.channels = channels == 2 ? 2 : 1;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int channels() {
        return channels;
    }

    public String lastError() {
        return lastError;
    }

    /** Открыть AudioTrack в режиме потока и запустить писатель. @return false при отказе. */
    public synchronized boolean start() {
        if (running) {
            return true;
        }
        int chMask = channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int min = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            min = sampleRate * channels;
        }
        int buf = Math.max(min * 2, sampleRate * channels / 2); // не меньше 250 мс
        try {
            track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, chMask,
                    AudioFormat.ENCODING_PCM_16BIT, buf, AudioTrack.MODE_STREAM);
        } catch (RuntimeException e) {
            track = null;
            lastError = "Не удалось открыть динамик: " + e.getMessage();
            return false;
        }
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            try {
                track.release();
            } catch (RuntimeException ignored) {
                // уже освобождён
            }
            track = null;
            lastError = "Динамик не поддерживает " + sampleRate + " Гц";
            return false;
        }
        track.play();
        writtenFrames = 0;
        running = true;
        writer = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "AudioOut");
        writer.setDaemon(true);
        writer.start();
        return true;
    }

    /** Поставить кусок PCM16 в очередь на воспроизведение (не блокирует). */
    public void write(byte[] pcm, int len) {
        if (pcm == null || len <= 0) {
            return;
        }
        int n = Math.min(len, pcm.length) & ~1;
        if (n <= 0) {
            return;
        }
        byte[] copy = new byte[n];
        System.arraycopy(pcm, 0, copy, 0, n);
        synchronized (lock) {
            queue.add(copy);
            lock.notifyAll();
        }
        AudioTrack t = track;
        if (t != null && t.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            try {
                t.play();
            } catch (RuntimeException ignored) {
                // писать некуда, писатель сам разберётся
            }
        }
    }

    /** Мгновенная остановка звука: очистить очередь и сбросить буфер дорожки. */
    public void flush() {
        synchronized (lock) {
            queue.clear();
        }
        AudioTrack t = track;
        if (t != null) {
            try {
                t.pause();
                t.flush();
                t.play();
            } catch (RuntimeException ignored) {
                // дорожка уже остановлена
            }
        }
        writtenFrames = 0;
    }

    /** Идёт ли воспроизведение (очередь не пуста или дорожка ещё не доиграла). */
    public boolean isPlaying() {
        AudioTrack t = track;
        boolean queued;
        synchronized (lock) {
            queued = !queue.isEmpty();
        }
        if (!running || t == null) {
            return false;
        }
        if (queued) {
            return true;
        }
        try {
            return t.getPlaybackHeadPosition() < writtenFrames;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Сколько чанков ещё ждёт в очереди. */
    public int queued() {
        synchronized (lock) {
            return queue.size();
        }
    }

    /** Остановить воспроизведение и освободить дорожку. Идемпотентно. */
    public synchronized void stop() {
        running = false;
        synchronized (lock) {
            queue.clear();
            lock.notifyAll();
        }
        Thread w = writer;
        writer = null;
        if (w != null && w != Thread.currentThread()) {
            try {
                w.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try {
                t.pause();
            } catch (RuntimeException ignored) {
                // дорожка уже не играет
            }
            try {
                t.flush();
            } catch (RuntimeException ignored) {
                // нечего сбрасывать
            }
            try {
                t.stop();
            } catch (RuntimeException ignored) {
                // уже остановлена
            }
            try {
                t.release();
            } catch (RuntimeException ignored) {
                // уже освобождена
            }
        }
    }

    /** Синоним stop() — чтобы имя совпадало с «освободить ресурсы». */
    public void release() {
        stop();
    }

    private void loop() {
        int failures = 0;
        while (running) {
            byte[] chunk;
            synchronized (lock) {
                while (running && queue.isEmpty()) {
                    try {
                        lock.wait(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                chunk = queue.poll();
            }
            if (chunk == null) {
                continue;
            }
            AudioTrack t = track;
            if (t == null) {
                continue;
            }
            int off = 0;
            while (off < chunk.length && running) {
                int w;
                try {
                    w = t.write(chunk, off, chunk.length - off);
                } catch (RuntimeException e) {
                    w = -1;
                }
                if (w <= 0) {
                    // Аудиодорожку мог отобрать звонок или мы сами сделали flush().
                    failures++;
                    if (failures > 200) {
                        lastError = "Динамик перестал принимать звук";
                        running = false;
                        break;
                    }
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                failures = 0;
                off += w;
                writtenFrames += w / (2 * channels);
            }
        }
    }
}
