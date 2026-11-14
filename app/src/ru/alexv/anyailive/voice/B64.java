package ru.alexv.anyailive.voice;

/**
 * Base64 (RFC 4648, стандартный алфавит, с '=' на конце) на чистом Java.
 *
 * Почему свой: android.util.Base64 есть только на устройстве (на JVM это заглушка из android.jar,
 * любой вызов бросает "Stub!"), а java.util.Base64 доступен лишь с API 26 при minSdk 24.
 * Аудио в обоих realtime-протоколах ходит именно в base64, так что декодер должен работать
 * и в тестах на JVM, и на телефоне — отсюда этот класс.
 *
 * Декодер терпим к пробельным символам (серверы иногда режут строку) и к отсутствию padding.
 */
public final class B64 {

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** Обратная таблица: индекс = код символа, значение = 6 бит, -1 = недопустимый символ. */
    private static final int[] REVERSE = new int[128];

    static {
        for (int i = 0; i < REVERSE.length; i++) {
            REVERSE[i] = -1;
        }
        for (int i = 0; i < ALPHABET.length; i++) {
            REVERSE[ALPHABET[i]] = i;
        }
        // url-safe вариант встречается в некоторых шлюзах
        REVERSE['-'] = 62;
        REVERSE['_'] = 63;
    }

    private B64() {
    }

    /** @return base64-строка без переводов строки; для null/пустого массива — "". */
    public static String encode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder b = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8) | (data[i + 2] & 0xff);
            b.append(ALPHABET[(n >> 18) & 63]);
            b.append(ALPHABET[(n >> 12) & 63]);
            b.append(ALPHABET[(n >> 6) & 63]);
            b.append(ALPHABET[n & 63]);
            i += 3;
        }
        int rest = data.length - i;
        if (rest == 1) {
            int n = (data[i] & 0xff) << 16;
            b.append(ALPHABET[(n >> 18) & 63]);
            b.append(ALPHABET[(n >> 12) & 63]);
            b.append("==");
        } else if (rest == 2) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8);
            b.append(ALPHABET[(n >> 18) & 63]);
            b.append(ALPHABET[(n >> 12) & 63]);
            b.append(ALPHABET[(n >> 6) & 63]);
            b.append('=');
        }
        return b.toString();
    }

    /**
     * @return декодированные байты; для null/пустой строки — пустой массив.
     * @throws IllegalArgumentException если строка содержит символ вне алфавита base64.
     */
    public static byte[] decode(String text) {
        if (text == null || text.length() == 0) {
            return new byte[0];
        }
        byte[] out = new byte[text.length() / 4 * 3 + 3];
        int len = 0;
        int acc = 0;
        int bits = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '=') {
                break;
            }
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                continue;
            }
            int v = c < 128 ? REVERSE[c] : -1;
            if (v < 0) {
                throw new IllegalArgumentException(
                        "Некорректный base64 в ответе сервера: символ '" + c + "' в позиции " + i);
            }
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[len++] = (byte) ((acc >> bits) & 0xff);
            }
        }
        if (len == out.length) {
            return out;
        }
        byte[] trimmed = new byte[len];
        System.arraycopy(out, 0, trimmed, 0, len);
        return trimmed;
    }
}
