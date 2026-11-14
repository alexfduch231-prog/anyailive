package ru.alexv.anyailive.voice;

import java.util.Arrays;

/**
 * Линейный ресемплер PCM16 LE (моно; многоканальный поток считается подряд идущими отсчётами).
 *
 * Состояние между вызовами сохраняется целиком: позиция следующего выходного отсчёта считается
 * в абсолютных номерах входных отсчётов, а последний входной отсчёт придерживается до следующего
 * вызова (lookahead в один отсчёт). Поэтому кусками получается ровно то же, что одним длинным
 * буфером — на границах чанков нет ни щелчков, ни «плывущей» частоты.
 *
 * Хвост, для которого уже нет следующего входного отсчёта, отдаёт {@link #flush()} — его нужно
 * вызвать только когда поток закончился (одноразовые помощники делают это сами).
 *
 * Интерполяция линейная (как зафиксировано в спеке). Для 44100 -&gt; 16000 это без
 * антиалиасингового фильтра: частоты выше 8 кГц заворачиваются в слышимый диапазон.
 * Для распознавания речи это допустимо (там всё равно 300–3400 Гц), для музыки — нет.
 */
public final class Resampler {

    private static final byte[] EMPTY = new byte[0];

    private final int inRate;
    private final int outRate;
    /** Шаг по входным отсчётам на один выходной: inRate / outRate. */
    private final double step;

    /** Абсолютная позиция (во входных отсчётах) следующего выходного отсчёта. */
    private double nextPos;
    /** Абсолютный номер первого ещё не поданного входного отсчёта. */
    private long nextIndex;
    /** Придержанные входные отсчёты (не больше двух) — для интерполяции через границу. */
    private short[] carry = new short[2];
    private int carryLen;

    public Resampler(int inRate, int outRate) {
        if (inRate <= 0 || outRate <= 0) {
            throw new IllegalArgumentException("sample rate must be positive: " + inRate + " -> " + outRate);
        }
        this.inRate = inRate;
        this.outRate = outRate;
        this.step = (double) inRate / (double) outRate;
    }

    /** 16 кГц -> 24 кГц: вход OpenAI Realtime. */
    public static Resampler up16to24() {
        return new Resampler(16000, 24000);
    }

    /** 24 кГц -> 16 кГц: если провайдер отдал выход не в 24 кГц. */
    public static Resampler down24to16() {
        return new Resampler(24000, 16000);
    }

    /** 44100 -> 16000: запись с «музыкального» микрофона или WAV от TTS. */
    public static Resampler down44kTo16() {
        return new Resampler(44100, 16000);
    }

    public int inRate() {
        return inRate;
    }

    public int outRate() {
        return outRate;
    }

    /** Сбросить состояние: следующий вызов начнёт новый поток с нуля. */
    public void reset() {
        nextPos = 0;
        nextIndex = 0;
        carryLen = 0;
    }

    /**
     * Пересчитать очередной кусок.
     *
     * @param pcm буфер PCM16 little-endian
     * @param len сколько байт брать (нечётный хвост отбрасывается)
     * @return новый буфер PCM16 little-endian; последний отсчёт может быть придержан до
     *         следующего вызова или {@link #flush()}
     */
    public byte[] process(byte[] pcm, int len) {
        if (pcm == null) {
            return EMPTY;
        }
        int n = Math.min(len, pcm.length) / 2;
        if (n <= 0) {
            return EMPTY;
        }
        int total = carryLen + n;
        short[] w = new short[total];
        System.arraycopy(carry, 0, w, 0, carryLen);
        for (int i = 0; i < n; i++) {
            w[carryLen + i] = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));
        }
        long wStart = nextIndex - carryLen;
        long lastAbs = wStart + total - 1;

        int cap = (int) (n * (double) outRate / inRate) + 8;
        byte[] out = new byte[cap * 2];
        int k = 0;
        double p = nextPos;
        while (k < cap) {
            if (p < wStart) {
                // После сильного прореживания позиция ушла вперёд: догоняем без вывода.
                p += step;
                continue;
            }
            if (p >= lastAbs) {
                break; // следующего отсчёта ещё нет — ждём (lookahead в один отсчёт)
            }
            int i0 = (int) (Math.floor(p) - wStart);
            if (i0 < 0 || i0 + 1 >= total) {
                break;
            }
            double fl = Math.floor(p);
            int frac = (int) Math.round((p - fl) * 256.0);
            int a = w[i0];
            int b = w[i0 + 1];
            int v = (a * 256 + (b - a) * frac) / 256;
            if (v > 32767) {
                v = 32767;
            } else if (v < -32768) {
                v = -32768;
            }
            out[k * 2] = (byte) (v & 0xFF);
            out[k * 2 + 1] = (byte) ((v >> 8) & 0xFF);
            k++;
            p += step;
        }
        nextPos = p;
        nextIndex = wStart + total;

        // Придерживаем последний отсчёт: он понадобится как i0+1 на следующем вызове.
        int fromLocal = (int) (Math.floor(nextPos) - wStart);
        if (fromLocal < total - 1) {
            fromLocal = total - 1;
        }
        if (fromLocal > total) {
            fromLocal = total;
        }
        if (fromLocal < 0) {
            fromLocal = 0;
        }
        carryLen = total - fromLocal;
        carry = new short[Math.max(2, carryLen)];
        System.arraycopy(w, fromLocal, carry, 0, carryLen);

        return k * 2 == out.length ? out : Arrays.copyOf(out, k * 2);
    }

    /**
     * Последний придержанный отсчёт: вызывать один раз, когда поток входных данных закончился.
     *
     * @return PCM16 из 0 или 1 отсчёта
     */
    public byte[] flush() {
        if (carryLen <= 0) {
            return EMPTY;
        }
        long carryStart = nextIndex - carryLen;
        int i0 = (int) (Math.floor(nextPos) - carryStart);
        if (i0 < 0 || i0 >= carryLen) {
            return EMPTY;
        }
        int a = carry[i0];
        int b = i0 + 1 < carryLen ? carry[i0 + 1] : a;
        double fl = Math.floor(nextPos);
        int frac = (int) Math.round((nextPos - fl) * 256.0);
        byte[] out = new byte[2];
        int v = (a * 256 + (b - a) * frac) / 256;
        out[0] = (byte) (v & 0xFF);
        out[1] = (byte) ((v >> 8) & 0xFF);
        nextPos += step;
        return out;
    }

    /** Разовый пересчёт без сохранения состояния (с финальным хвостом). */
    public static byte[] resample(byte[] pcm, int len, int fromRate, int toRate) {
        Resampler r = new Resampler(fromRate, toRate);
        byte[] head = r.process(pcm, len);
        byte[] tail = r.flush();
        if (tail.length == 0) {
            return head;
        }
        byte[] out = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }

    /** 16 кГц -> 24 кГц (вход OpenAI Realtime). */
    public static byte[] upsample16to24(byte[] pcm, int len) {
        return resample(pcm, len, 16000, 24000);
    }

    /** 24 кГц -> 16 кГц. */
    public static byte[] downsample24to16(byte[] pcm, int len) {
        return resample(pcm, len, 24000, 16000);
    }

    /** 44100 -> 16000. */
    public static byte[] downsample44100to16000(byte[] pcm, int len) {
        return resample(pcm, len, 44100, 16000);
    }
}
