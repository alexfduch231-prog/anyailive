package ru.alexv.anyailive.voice;

/**
 * Определение речи по энергии (RMS) для PCM16 LE.
 *
 * Логика:
 * - первые {@link #CALIBRATION_MS} мс считаются калибровкой: кадры не считаются речью,
 *   а оценка шума берётся как минимум энергии за этот отрезок (минимум устойчив к тому,
 *   что человек начал говорить сразу);
 * - порог = max(threshold, 3 × оценка шума), оценка шума подтягивается скользящим средним,
 *   пока речи нет;
 * - речь начинается с первого кадра выше порога (без задержки — нужно для перебивания)
 *   и заканчивается после silenceMs тишины.
 *
 * Класс чистый Java (без android.*) и полностью проверяется на JVM.
 */
public final class Vad {

    public static final int DEFAULT_THRESHOLD = 800;
    public static final int DEFAULT_SILENCE_MS = 700;
    public static final int CALIBRATION_MS = 300;

    private final int frameBytes;
    private final int sampleRate;
    private final int frameMs;

    private int threshold = DEFAULT_THRESHOLD;
    private int silenceMs = DEFAULT_SILENCE_MS;
    private int calibrationMs = CALIBRATION_MS;

    private int noise;
    private int calibrationFrames;
    private boolean calibrating = true;
    private boolean inSpeech;
    private int silenceAcc;
    private int lastRms;

    private boolean startedFlag;
    private boolean endedFlag;

    public Vad(int frameBytes) {
        this(frameBytes, 16000);
    }

    public Vad(int frameBytes, int sampleRate) {
        if (frameBytes < 2) {
            throw new IllegalArgumentException("frameBytes too small: " + frameBytes);
        }
        this.frameBytes = frameBytes & ~1;
        this.sampleRate = sampleRate <= 0 ? 16000 : sampleRate;
        this.frameMs = Math.max(1, (this.frameBytes / 2) * 1000 / this.sampleRate);
    }

    public Vad(int frameBytes, int sampleRate, int threshold, int silenceMs) {
        this(frameBytes, sampleRate);
        this.threshold = threshold;
        this.silenceMs = silenceMs;
    }

    public int frameBytes() {
        return frameBytes;
    }

    public int frameMs() {
        return frameMs;
    }

    public int threshold() {
        return threshold;
    }

    public void setThreshold(int threshold) {
        this.threshold = Math.max(1, threshold);
    }

    public int silenceMs() {
        return silenceMs;
    }

    public void setSilenceMs(int silenceMs) {
        this.silenceMs = Math.max(1, silenceMs);
    }

    public void setCalibrationMs(int calibrationMs) {
        this.calibrationMs = Math.max(0, calibrationMs);
        this.calibrating = calibrationMs > 0;
        this.calibrationFrames = 0;
        this.noise = 0;
    }

    /** Текущая оценка шума (RMS). */
    public int noise() {
        return noise;
    }

    /** Фактический порог с учётом шума. */
    public int effectiveThreshold() {
        return Math.max(threshold, noise * 3);
    }

    /** RMS последнего кадра. */
    public int rms() {
        return lastRms;
    }

    /** Идёт ли речь прямо сейчас. */
    public boolean isSpeech() {
        return inSpeech;
    }

    /** true один раз в момент начала речи. */
    public boolean speechStarted() {
        boolean v = startedFlag;
        startedFlag = false;
        return v;
    }

    /** true один раз в момент конца реплики (тишина дольше silenceMs). */
    public boolean speechEnded() {
        boolean v = endedFlag;
        endedFlag = false;
        return v;
    }

    /** Сбросить состояние речи и флаги; оценку шума и калибровку сохранить. */
    public void reset() {
        inSpeech = false;
        silenceAcc = 0;
        startedFlag = false;
        endedFlag = false;
        lastRms = 0;
    }

    /** Полный сброс, включая калибровку и оценку шума. */
    public void resetAll() {
        reset();
        calibrating = calibrationMs > 0;
        calibrationFrames = 0;
        noise = 0;
    }

    /**
     * Скормить очередной кусок PCM (может быть любой длины, кадры нарезаются внутри).
     *
     * @return true, если в куске был хотя бы один кадр речи
     */
    public boolean feed(byte[] pcm, int len) {
        if (pcm == null || len < 2) {
            return false;
        }
        int n = Math.min(len, pcm.length);
        boolean anySpeech = false;
        int off = 0;
        while (off + 2 <= n) {
            int take = Math.min(frameBytes, n - off);
            take &= ~1;
            if (take < 2) {
                break;
            }
            if (frame(rms(pcm, off, take))) {
                anySpeech = true;
            }
            off += take;
        }
        return anySpeech;
    }

    private boolean frame(int r) {
        lastRms = r;
        if (calibrating) {
            calibrationFrames++;
            noise = noise == 0 ? r : Math.min(noise, r);
            if (calibrationFrames * frameMs >= calibrationMs) {
                calibrating = false;
            }
            return false;
        }
        boolean voice = r >= effectiveThreshold();
        if (voice) {
            silenceAcc = 0;
            if (!inSpeech) {
                inSpeech = true;
                startedFlag = true;
            }
        } else if (inSpeech) {
            silenceAcc += frameMs;
            if (silenceAcc >= silenceMs) {
                inSpeech = false;
                silenceAcc = 0;
                endedFlag = true;
            }
        } else {
            silenceAcc = 0;
            noise = (noise * 7 + r) / 8;
        }
        return voice;
    }

    private static int rms(byte[] b, int off, int len) {
        int samples = len / 2;
        if (samples <= 0) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < samples; i++) {
            int s = (short) ((b[off + i * 2] & 0xFF) | (b[off + i * 2 + 1] << 8));
            sum += (long) s * s;
        }
        double mean = (double) sum / samples;
        return (int) Math.sqrt(mean);
    }
}
