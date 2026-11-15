package ru.alexv.anyailive.tests;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import ru.alexv.anyailive.voice.HttpWire;
import ru.alexv.anyailive.voice.TranscriptionBody;

/** Standalone JVM regression test; no microphone, Android runtime or network. */
public final class TranscriptionBodyTests {
    private static final String BOUNDARY = "stt-wav-regression";

    private TranscriptionBodyTests() {
    }

    public static void main(String[] args) throws Exception {
        multipartUsesExactMimeBytes();
        multipartContainsWavInsteadOfRawPcm();
        modelAndLanguageFieldsArePreserved();
        System.out.println("TranscriptionBodyTests: PASS (WAV payload + model/language fields)");
    }

    private static void multipartContainsWavInsteadOfRawPcm() throws Exception {
        byte[] pcm = new byte[3200];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (i * 37 + 11);
        }
        byte[] original = pcm.clone();
        byte[] body = TranscriptionBody.build(BOUNDARY, pcm, "whisper-1", "ru");
        byte[] file = filePayload(body);

        check("file payload begins with RIFF, not raw PCM", tag(file, 0, "RIFF"));
        check("RIFF size is 3236", littleEndian(file, 4, 4) == 3236);
        check("WAVE container", tag(file, 8, "WAVE"));
        check("fmt chunk", tag(file, 12, "fmt "));
        check("PCM encoding", littleEndian(file, 20, 2) == 1);
        check("mono", littleEndian(file, 22, 2) == 1);
        check("16000 Hz", littleEndian(file, 24, 4) == 16000);
        check("32000 bytes per second", littleEndian(file, 28, 4) == 32000);
        check("2-byte block alignment", littleEndian(file, 32, 2) == 2);
        check("16-bit samples", littleEndian(file, 34, 2) == 16);
        check("data chunk", tag(file, 36, "data"));
        check("data length is 3200", littleEndian(file, 40, 4) == 3200);
        check("file length is 3244", file.length == 3244);
        check("PCM bytes preserved exactly", Arrays.equals(original, Arrays.copyOfRange(file, 44, file.length)));
        check("input PCM not mutated", Arrays.equals(original, pcm));
    }

    private static void modelAndLanguageFieldsArePreserved() throws Exception {
        String custom = wire(TranscriptionBody.build(BOUNDARY, new byte[0], "  custom-stt  ", "en"));
        check("custom model is trimmed", custom.contains(field("model", "custom-stt")));
        check("English language preserved", custom.contains(field("language", "en")));
        for (String model : new String[]{null, "", "   "}) {
            String body = wire(TranscriptionBody.build(BOUNDARY, new byte[0], model, "ru"));
            check("missing model defaults to whisper-1", body.contains(field("model", "whisper-1")));
            check("Russian language preserved", body.contains(field("language", "ru")));
        }
    }

    private static void multipartUsesExactMimeBytes() throws Exception {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("model", "whisper-1");
        fields.put("language", "ru");
        byte[] binary = new byte[]{0, (byte) 255, 13, 10, 34, 92};
        byte[] body = HttpWire.multipart(BOUNDARY, "file", "audio.wav", "audio/wav", binary, fields);
        String prefix = "--stt-wav-regression\r\n"
                + "Content-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1\r\n"
                + "--stt-wav-regression\r\n"
                + "Content-Disposition: form-data; name=\"language\"\r\n\r\nru\r\n"
                + "--stt-wav-regression\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n";
        String suffix = "\r\n--stt-wav-regression--\r\n";
        byte[] before = prefix.getBytes("UTF-8");
        byte[] after = suffix.getBytes("UTF-8");
        byte[] expected = new byte[before.length + binary.length + after.length];
        System.arraycopy(before, 0, expected, 0, before.length);
        System.arraycopy(binary, 0, expected, before.length, binary.length);
        System.arraycopy(after, 0, expected, before.length + binary.length, after.length);
        check("multipart uses exact CRLF, quoted headers, binary bytes and terminal boundary",
                Arrays.equals(expected, body));
    }

    private static byte[] filePayload(byte[] body) throws Exception {
        String text = wire(body);
        String newline = "\r\n";
        String quote = "\"";
        check("file field and WAV filename", text.contains("name=" + quote + "file" + quote
                + "; filename=" + quote + "audio.wav" + quote));
        String marker = "Content-Type: audio/wav" + newline + newline;
        int start = text.indexOf(marker);
        check("WAV content type", start >= 0);
        start += marker.length();
        String closing = newline + "--" + BOUNDARY + "--" + newline;
        check("closing multipart boundary", text.endsWith(closing));
        return Arrays.copyOfRange(body, start, body.length - closing.length());
    }

    private static String field(String name, String value) {
        return "name=\"" + name + "\"\r\n\r\n" + value + "\r\n";
    }

    private static String wire(byte[] body) throws Exception {
        return new String(body, "ISO-8859-1");
    }

    private static boolean tag(byte[] bytes, int offset, String tag) {
        if (offset + tag.length() > bytes.length) return false;
        for (int i = 0; i < tag.length(); i++) {
            if (bytes[offset + i] != (byte) tag.charAt(i)) return false;
        }
        return true;
    }

    private static int littleEndian(byte[] bytes, int offset, int length) {
        int value = 0;
        for (int i = 0; i < length; i++) value |= (bytes[offset + i] & 255) << (i * 8);
        return value;
    }

    private static void check(String label, boolean condition) {
        if (!condition) throw new AssertionError(label);
    }
}
