package ru.alexv.voicebridge.tests;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.alexv.voicebridge.voice.CascadeHttp;
import ru.alexv.voicebridge.voice.CascadeStt;
import ru.alexv.voicebridge.voice.HttpWire;
import ru.alexv.voicebridge.voice.Resampler;
import ru.alexv.voicebridge.voice.Sentences;
import ru.alexv.voicebridge.voice.Vad;
import ru.alexv.voicebridge.voice.Wav;

/**
 * Тесты «чистой» части аудио-слоя: Resampler, Vad, Wav, Sentences и статические помощники
 * CascadeHttp (сборка multipart/JSON, разбор ответа). Всё без android.* — гоняется на JVM:
 *
 *   java -cp "voicebridge/build/check-audio;<android.jar>" ru.alexv.voicebridge.tests.AudioTests
 */
public final class AudioTests {

    private static int passed;
    private static int failed;

    private AudioTests() {
    }

    public static void run() {
        passed = 0;
        failed = 0;
        resamplerTests();
        vadTests();
        wavTests();
        sentencesTests();
        cascadeWireTests();
        System.out.println("audio: ok " + passed + ", fail " + failed);
    }

    public static void main(String[] args) {
        run();
    }

    // ----------------------------------------------------------- Resampler

    private static void resamplerTests() {
        byte[] zeros = new byte[320];
        check("Resampler.upsample16to24: 320 байт -> 480",
                Resampler.upsample16to24(zeros, zeros.length).length == 480);
        check("Resampler.downsample24to16: 480 байт -> 320",
                Resampler.downsample24to16(new byte[480], 480).length == 320);

        byte[] dc = dcSignal(1000, 160);
        byte[] up = Resampler.upsample16to24(dc, dc.length);
        boolean flat = true;
        for (int i = 0; i + 1 < up.length; i += 2) {
            int v = (short) ((up[i] & 0xFF) | (up[i + 1] << 8));
            if (Math.abs(v - 1000) > 1) {
                flat = false;
                break;
            }
        }
        check("Resampler: постоянный сигнал не искажается при 16->24", flat);

        // Кусками должно выходить ровно то же, что целиком (иначе щёлкает на границах).
        byte[] all = sine(440, 1600, 16000);
        byte[] a = Resampler.resample(all, all.length, 16000, 24000);
        Resampler chunked = new Resampler(16000, 24000);
        byte[] b1 = chunked.process(all, 640);
        byte[] b2 = chunked.process(Arrays.copyOfRange(all, 640, all.length), all.length - 640);
        byte[] b = concat(concat(b1, b2), chunked.flush());
        check("Resampler: 16->24 кусками == целиком", Arrays.equals(a, b));
        check("Resampler: 16->24 кусками та же длина", a.length == b.length);

        byte[] d1 = new Resampler(44100, 16000).process(sine(300, 4410, 44100), 8820);
        int expect = 4410 * 16000 / 44100;
        check("Resampler: 44100 -> 16000 длина ~" + expect + " (получено " + d1.length / 2 + ")",
                Math.abs(d1.length / 2 - expect) <= 2);

        check("Resampler: rates", new Resampler(16000, 24000).inRate() == 16000
                && new Resampler(16000, 24000).outRate() == 24000);
        check("Resampler: пустой вход -> пустой выход",
                Resampler.resample(new byte[0], 0, 16000, 24000).length == 0);
    }

    // ---------------------------------------------------------------- Vad

    private static void vadTests() {
        Vad v = new Vad(320); // 10 мс
        byte[] silence = new byte[320];
        byte[] loud = tone(320, 3000);

        boolean falseStart = false;
        for (int i = 0; i < 30; i++) { // 300 мс калибровки
            if (v.feed(silence, silence.length)) {
                falseStart = true;
            }
        }
        check("Vad: тишина на калибровке не считается речью", !falseStart);
        check("Vad: речь началась на громком кадре", v.feed(loud, loud.length));
        check("Vad: второй громкий кадр тоже речь", v.feed(loud, loud.length));
        check("Vad: speechStarted() отдаётся один раз",
                v.speechStarted() && !v.speechStarted());
        check("Vad: isSpeech() true", v.isSpeech());

        boolean ended = false;
        for (int i = 0; i < 80 && !ended; i++) { // 800 мс тишины > 700 мс
            v.feed(silence, silence.length);
            ended = v.speechEnded();
        }
        check("Vad: конец речи после 700 мс тишины", ended);
        check("Vad: isSpeech() false после конца", !v.isSpeech());

        Vad adaptive = new Vad(320, 16000, 800, 700);
        byte[] noise = tone(320, 500); // ровный шум 500
        for (int i = 0; i < 30; i++) {
            adaptive.feed(noise, noise.length);
        }
        boolean falsePositive = false;
        for (int i = 0; i < 50; i++) {
            falsePositive |= adaptive.feed(noise, noise.length);
        }
        check("Vad: шум не принимается за речь", !falsePositive);

        Vad zero = new Vad(320);
        zero.setCalibrationMs(0);
        check("Vad: без калибровки речь ловится сразу", zero.feed(loud, loud.length));
        check("Vad: frameMs 10 для 320 байт", new Vad(320).frameMs() == 10);
    }

    // ---------------------------------------------------------------- Wav

    private static void wavTests() {
        byte[] pcm = tone(400, 5000);
        byte[] wav = Wav.toWav16k(pcm, pcm.length);
        check("Wav: заголовок 44 + данные", wav.length == Wav.HEADER_BYTES + pcm.length);
        check("Wav: RIFF", wav[0] == 'R' && wav[1] == 'I' && wav[2] == 'F' && wav[3] == 'F');
        check("Wav: WAVE", wav[8] == 'W' && wav[9] == 'A' && wav[10] == 'V' && wav[11] == 'E');

        Wav.Info info = Wav.parseHeader(wav);
        check("Wav: разбор заголовка", info != null && info.sampleRate == 16000
                && info.channels == 1 && info.bits == 16
                && info.dataOffset == Wav.HEADER_BYTES && info.dataLength == pcm.length);
        byte[] back = Wav.dataBytes(wav);
        check("Wav: данные читаются обратно", back != null && Arrays.equals(back, pcm));
        check("Wav: не-WAV -> null", Wav.parseHeader(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}) == null);

        byte[] stereo = Wav.encode(pcm, 0, pcm.length, 44100, 2);
        Wav.Info s = Wav.parseHeader(stereo);
        check("Wav: 44100/стерео", s != null && s.sampleRate == 44100 && s.channels == 2);
        check("Wav: пустой PCM", Wav.toWav16k(new byte[0], 0).length == Wav.HEADER_BYTES);

        // Wav.pcmFromWav: стерео 44100 -> моно 24000 (как отдаёт synthesizeToFile).
        byte[] ri = sine(300, 4410, 44100);
        byte[] st = new byte[ri.length * 2];
        for (int i = 0; i < ri.length; i += 2) {
            st[2 * i] = ri[i];
            st[2 * i + 1] = ri[i + 1];
            st[2 * i + 2] = ri[i];
            st[2 * i + 3] = ri[i + 1];
        }
        byte[] mono = Wav.pcmFromWav(Wav.encode(st, 0, st.length, 44100, 2), 24000);
        check("Wav.pcmFromWav: стерео 44.1k -> моно 24k", mono != null
                && Math.abs(mono.length / 2 - 4410 * 24000 / 44100) < 8);
        check("Wav.pcmFromWav: мусор -> null", Wav.pcmFromWav(new byte[]{9, 9, 9, 9}, 24000) == null);
    }

    // ---------------------------------------------------------- Sentences

    private static void sentencesTests() {
        String[] two = Sentences.split("Привет. Как дела? Хорошо");
        check("Sentences.split: две фразы", two.length == 2
                && "Привет.".equals(two[0]) && "Как дела?".equals(two[1]));

        check("Sentences.stripMarkdown: ** и `", "Привет мир".equals(
                Sentences.stripMarkdown("**Привет** `мир`")));
        check("Sentences.stripMarkdown: заголовок",
                "Заголовок".equals(Sentences.stripMarkdown("## Заголовок")));
        check("Sentences.stripMarkdown: ссылка -> текст",
                "ссылка".equals(Sentences.stripMarkdown("[ссылка](http://x.y)")));
        check("Sentences.stripMarkdown: блок кода выкинут",
                "Код выше".equals(Sentences.stripMarkdown("Код выше ```java\nint a=1;\n```").trim()));

        Sentences s = new Sentences(25);
        check("Sentences: первый кусок сразу", s.feed("Раз. ").length == 1);
        check("Sentences: короткий : не режет", s.feed("Однако").length == 0);
        check("Sentences: хвост по finish()", s.finish().length == 1);
        check("Sentences: после finish пусто", s.pending().length() == 0);

        check("Sentences: 3.14 не граница", Sentences.split("Число 3.14 важно").length == 0);
        check("Sentences: перевод строки режет",
                Sentences.split("Первая\nВторая").length == 1);
        check("Sentences: speakable", Sentences.speakable("привет")
                && !Sentences.speakable("**"));
    }

    // -------------------------------------------- CascadeHttp: чистая сборка

    private static void cascadeWireTests() {
        check("CascadeHttp.fallbackToSystemTts(404)", CascadeHttp.fallbackToSystemTts(404));
        check("CascadeHttp.fallbackToSystemTts(400)", CascadeHttp.fallbackToSystemTts(400));
        check("CascadeHttp.fallbackToSystemTts(500) == false",
                !CascadeHttp.fallbackToSystemTts(500));

        String url = HttpWire.url("http://host:1/v1/", "/audio/speech");
        check("Wire.url: склейка без двойного слэша", "http://host:1/v1/audio/speech".equals(url));

        String json = HttpWire.ttsJson("tts-1", "alloy", "Привет");
        check("Wire.ttsJson: response_format=pcm",
                json.contains("\"response_format\":\"pcm\"") || json.contains("\"response_format\": \"pcm\""));
        check("Wire.ttsJson: модель и голос", json.contains("tts-1") && json.contains("alloy"));

        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("model", "whisper-1");
        byte[] wav = Wav.toWav16k(tone(200, 2000), 400);
        byte[] body = HttpWire.multipart("BOUND", "file", "a.wav", "audio/wav", wav, fields);
        String head = new String(body, 0, Math.min(400, body.length));
        check("Wire.multipart: поле model", head.contains("name=\"model\"") && head.contains("whisper-1"));
        check("Wire.multipart: файл", head.contains("filename=\"a.wav\"")
                && head.contains("Content-Type: audio/wav"));
        check("Wire.multipart: закрывающая граница",
                new String(body).trim().endsWith("--BOUND--"));

        check("Wire.headers: Bearer", "Bearer sk".equals(
                HttpWire.headers("sk", null).get("Authorization")));
        Map<String, String> extra = HttpWire.headers("sk", "X-Org: me\nX-Empty\n");
        check("Wire.headers: свои строки", "me".equals(extra.get("X-Org")) && !extra.containsKey("X-Empty"));

        check("CascadeStt.transcript", "привет".equals(
                CascadeStt.transcript("{\"text\":\"привет\"}")));
        check("CascadeStt.transcript: пусто на мусор", "".equals(CascadeStt.transcript("<html>")));

        byte[] raw = tone(200, 3000);
        check("CascadeHttp.binaryAudio: WAV -> PCM",
                Arrays.equals(CascadeHttp.binaryAudio(Wav.toWav16k(raw, raw.length)), raw));
        check("CascadeHttp.binaryAudio: сырой PCM как есть",
                Arrays.equals(CascadeHttp.binaryAudio(raw), raw));

        List<String> phr = Sentences.speechPhrases("**Привет**. Как дела?", 25);
        check("Sentences.speechPhrases: нарезка и чистка", phr.size() == 2
                && "Привет.".equals(phr.get(0)) && "Как дела?".equals(phr.get(1)));
    }

    // ------------------------------------------------------------- утилиты

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("ok   " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }

    private static byte[] dcSignal(int value, int samples) {
        byte[] b = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            b[2 * i] = (byte) (value & 0xFF);
            b[2 * i + 1] = (byte) ((value >> 8) & 0xFF);
        }
        return b;
    }

    private static byte[] tone(int bytes, int amplitude) {
        int samples = bytes / 2;
        byte[] b = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            int v = (i % 2 == 0 ? amplitude : -amplitude);
            b[2 * i] = (byte) (v & 0xFF);
            b[2 * i + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return b;
    }

    private static byte[] sine(double hz, int samples, int rate) {
        byte[] b = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            int v = (int) Math.round(8000 * Math.sin(2 * Math.PI * hz * i / rate));
            b[2 * i] = (byte) (v & 0xFF);
            b[2 * i + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return b;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
