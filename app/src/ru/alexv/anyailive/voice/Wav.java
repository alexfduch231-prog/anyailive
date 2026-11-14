package ru.alexv.anyailive.voice;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * WAV (RIFF/fmt/data) для PCM16 — чистая Java, без android.*.
 *
 * Нужен каскаду: /audio/transcriptions хочет файл, а не голый поток байт;
 * и обратная задача — распознать WAV в ответе /audio/speech, если провайдер
 * вернул не «сырой» PCM, а контейнер.
 */
public final class Wav {

    /** 44 байта: RIFF(12) + fmt chunk(24) + data header(8). */
    public static final int HEADER_BYTES = 44;

    private Wav() {
    }

    /** Описание разобранного файла. */
    public static final class Info {
        public int sampleRate;
        public int channels;
        public int bits = 16;
        public int dataOffset;
        public int dataLength;

        @Override
        public String toString() {
            return sampleRate + " Hz, " + channels + " ch, " + bits + " bit, data@" + dataOffset
                    + " (" + dataLength + " байт)";
        }
    }

    /** Собрать WAV из всего буфера. */
    public static byte[] encode(byte[] pcm, int sampleRate, int channels) {
        int len = pcm == null ? 0 : pcm.length;
        return encode(pcm, 0, len, sampleRate, channels);
    }

    /** Собрать WAV из куска буфера. */
    public static byte[] encode(byte[] pcm, int off, int len, int sampleRate, int channels) {
        int rate = sampleRate <= 0 ? 16000 : sampleRate;
        int ch = channels <= 0 ? 1 : channels;
        int dataLen = pcm == null ? 0 : Math.max(0, Math.min(len, pcm.length - off));
        byte[] out = new byte[HEADER_BYTES + dataLen];
        int byteRate = rate * ch * 2;
        putTag(out, 0, "RIFF");
        putInt(out, 4, 36 + dataLen);
        putTag(out, 8, "WAVE");
        putTag(out, 12, "fmt ");
        putInt(out, 16, 16);            // размер fmt-блока
        putShort(out, 20, 1);           // PCM
        putShort(out, 22, ch);
        putInt(out, 24, rate);
        putInt(out, 28, byteRate);
        putShort(out, 32, ch * 2);      // blockAlign
        putShort(out, 34, 16);          // bits per sample
        putTag(out, 36, "data");
        putInt(out, 40, dataLen);
        if (dataLen > 0) {
            System.arraycopy(pcm, off, out, HEADER_BYTES, dataLen);
        }
        return out;
    }

    /** WAV 16 кГц моно — формат для /audio/transcriptions. */
    public static byte[] toWav16k(byte[] pcm, int len) {
        return encode(pcm, 0, len, 16000, 1);
    }

    /** Записать WAV в файл. @return false, если не вышло (каталог недоступен, нет места). */
    public static boolean writeFile(File file, byte[] pcm, int sampleRate, int channels) {
        if (file == null) {
            return false;
        }
        FileOutputStream os = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            os = new FileOutputStream(file);
            os.write(encode(pcm, sampleRate, channels));
            os.flush();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (IOException ignored) {
                    // закрываем молча: файл уже не нужен
                }
            }
        }
    }

    /**
     * Разобрать заголовок: идём по чанкам, поэтому переживаем и лишние блоки (LIST, fact),
     * и «потоковые» data с размером 0xFFFFFFFF.
     *
     * @return null, если это не RIFF/WAVE
     */
    public static Info parseHeader(byte[] data) {
        if (data == null || data.length < 12) {
            return null;
        }
        if (!tag(data, 0, "RIFF") || !tag(data, 8, "WAVE")) {
            return null;
        }
        Info info = new Info();
        int p = 12;
        while (p + 8 <= data.length) {
            String id = string(data, p, 4);
            long size = u32(data, p + 4);
            int body = p + 8;
            if ("fmt ".equals(id) && body + 16 <= data.length) {
                info.channels = (int) u16(data, body + 2);
                info.sampleRate = (int) u32(data, body + 4);
                info.bits = (int) u16(data, body + 14);
            } else if ("data".equals(id)) {
                info.dataOffset = body;
                long avail = data.length - body;
                long take = size > 0 && size <= avail ? size : avail;
                info.dataLength = (int) Math.max(0, take);
                return info.sampleRate > 0 ? info : null;
            }
            long advance = size + (size & 1);
            if (size <= 0 || advance > data.length) {
                break;
            }
            p = (int) (body + advance);
        }
        return info.sampleRate > 0 ? info : null;
    }

    /** Тело data-чанка из WAV, либо null. */
    public static byte[] dataBytes(byte[] wav) {
        Info info = parseHeader(wav);
        if (info == null || info.dataLength <= 0) {
            return null;
        }
        byte[] out = new byte[info.dataLength];
        System.arraycopy(wav, info.dataOffset, out, 0, info.dataLength);
        return out;
    }


    /** Прочитать файл целиком (null, если не вышло или он неправдоподобно большой). */
    public static byte[] readFile(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        long size = file.length();
        if (size <= 0 || size > 32L * 1024 * 1024) {
            return null;
        }
        byte[] raw = new byte[(int) size];
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            int off = 0;
            while (off < raw.length) {
                int n = in.read(raw, off, raw.length - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            if (off == raw.length) {
                return raw;
            }
            byte[] cut = new byte[off];
            System.arraycopy(raw, 0, cut, 0, off);
            return cut;
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // файл уже прочитан или не нужен
                }
            }
        }
    }

    /**
     * Тело WAV в PCM16 моно с приведением к нужной частоте — то, что можно отдать в AudioOut.
     *
     * @return null, если это не WAV или данных нет
     */
    public static byte[] pcmFromWav(byte[] wav, int targetRate) {
        Info info = parseHeader(wav);
        if (info == null) {
            return null;
        }
        byte[] data = dataBytes(wav);
        if (data == null) {
            return null;
        }
        if (info.channels == 2) {
            int frames = data.length / 4;
            byte[] mono = new byte[frames * 2];
            for (int i = 0; i < frames; i++) {
                int l = (short) ((data[4 * i] & 0xFF) | (data[4 * i + 1] << 8));
                int r = (short) ((data[4 * i + 2] & 0xFF) | (data[4 * i + 3] << 8));
                int m = (l + r) / 2;
                mono[2 * i] = (byte) (m & 0xFF);
                mono[2 * i + 1] = (byte) ((m >> 8) & 0xFF);
            }
            data = mono;
        }
        if (targetRate > 0 && info.sampleRate > 0 && info.sampleRate != targetRate) {
            data = Resampler.resample(data, data.length, info.sampleRate, targetRate);
        }
        return data;
    }

    /** Удалить файл, не падая, если его уже нет. */
    public static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static boolean tag(byte[] b, int off, String tag) {
        return tag.equals(string(b, off, 4));
    }

    private static String string(byte[] b, int off, int len) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < len; i++) {
            s.append((char) (b[off + i] & 0xFF));
        }
        return s.toString();
    }

    private static long u32(byte[] b, int off) {
        return (b[off] & 0xFFL) | (b[off + 1] & 0xFFL) << 8 | (b[off + 2] & 0xFFL) << 16
                | (b[off + 3] & 0xFFL) << 24;
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
    }

    private static void putTag(byte[] b, int off, String s) {
        for (int i = 0; i < 4 && i < s.length(); i++) {
            b[off + i] = (byte) s.charAt(i);
        }
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }
}
