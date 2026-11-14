package ru.alexv.anyailive.tests;

import java.util.List;
import java.util.Map;

import ru.alexv.anyailive.data.Model;
import ru.alexv.anyailive.json.Json;
import ru.alexv.anyailive.voice.B64;
import ru.alexv.anyailive.voice.LiveEngineGemini;
import ru.alexv.anyailive.voice.LiveEngineOpenAI;

/**
 * Тесты Live-движков без сети: проверяются только чистые функции (тело setup / session.update,
 * классификация событий, base64, ресемплер, сборка URL и заголовков). Сокет, аудиоустройства и
 * колбэки в потоке чтения здесь не проверяются - для них нужен живой ключ.
 * Запуск: java -cp build/check-live ru.alexv.anyailive.tests.LiveTests
 */
public class LiveTests {

    private static int fails;
    private static int checks;

    private static void ok(String name, boolean cond) {
        checks++;
        if (cond) {
            System.out.println("ok   " + name);
        } else {
            fails++;
            System.out.println("FAIL " + name);
        }
    }

    private static Model model(String voice) {
        Model m = new Model();
        m.name = "Тестовая";
        m.baseUrl = "https://api.openai.com/v1/";
        m.modelId = "gpt-realtime";
        m.liveModelId = "gpt-realtime";
        m.voice = voice;
        return m;
    }

    public static void run() {
        openAi();
        gemini();
        b64();
        System.out.println(fails == 0 ? "PASS " + checks + " checks"
                : "FAILED " + fails + " of " + checks + " checks");
    }

    // ------------------------------------------------------------------ OpenAI

    private static void openAi() {
        String body = LiveEngineOpenAI.buildSessionUpdate(model(""));
        ok("oa.sessionBody", body.contains("\"type\":\"session.update\"")
                && body.contains("\"type\":\"realtime\""));
        ok("oa.outputModalities", body.contains("\"output_modalities\":[\"audio\"]")
                && !body.contains("\"modalities\":["));
        ok("oa.noLegacyFields", !body.contains("input_audio_format")
                && !body.contains("output_audio_format") && !body.contains("whisper"));
        Map<String, Object> o = Json.obj(body);
        ok("oa.nestedAudio", "audio/pcm".equals(
                Json.pathString(o, "session.audio.input.format.type"))
                && "audio/pcm".equals(Json.pathString(o, "session.audio.output.format.type"))
                && Long.toString(LiveEngineOpenAI.OUT_RATE).equals(
                        Json.pathString(o, "session.audio.input.format.rate")));
        ok("oa.transcription", "gpt-4o-transcribe".equals(
                Json.pathString(o, "session.audio.input.transcription.model")));
        ok("oa.vad", "server_vad".equals(
                Json.pathString(o, "session.audio.input.turn_detection.type")));
        ok("oa.defaultVoice", "alloy".equals(Json.pathString(o, "session.audio.output.voice")));
        String custom = LiveEngineOpenAI.buildSessionUpdate(model("verse"), "Ты помощник.");
        ok("oa.customVoice", "verse".equals(Json.pathString(Json.obj(custom),
                "session.audio.output.voice")));
        ok("oa.instructions", "Ты помощник.".equals(Json.pathString(Json.obj(custom),
                "session.instructions")));

        ok("oa.acceptsBothFamilies",
                LiveEngineOpenAI.eventKind("{\"type\":\"response.output_audio.delta\",\"delta\":\"AA\"}")
                        == LiveEngineOpenAI.AUDIO_DELTA
                        && LiveEngineOpenAI.eventKind("{\"type\":\"session.output_audio.delta\"}")
                        == LiveEngineOpenAI.AUDIO_DELTA
                        && LiveEngineOpenAI.eventKind("{\"type\":\"response.output_audio_transcript.delta\"}")
                        == LiveEngineOpenAI.ASSISTANT_TEXT
                        && LiveEngineOpenAI.eventKind("{\"type\":\"session.output_transcript.delta\"}")
                        == LiveEngineOpenAI.ASSISTANT_TEXT
                        && LiveEngineOpenAI.eventKind("{\"type\":\"conversation.item.input_audio_transcription.delta\"}")
                        == LiveEngineOpenAI.USER_TEXT
                        && LiveEngineOpenAI.eventKind("{\"type\":\"session.input_transcript.delta\"}")
                        == LiveEngineOpenAI.USER_TEXT);
        ok("oa.audioDelta", LiveEngineOpenAI.eventKind("response.output_audio.delta")
                == LiveEngineOpenAI.AUDIO_DELTA);
        ok("oa.speechEvents",
                LiveEngineOpenAI.eventKind("input_audio_buffer.speech_started") == LiveEngineOpenAI.BARGE_IN
                        && LiveEngineOpenAI.eventKind("input_audio_buffer.speech_stopped")
                        == LiveEngineOpenAI.SPEECH_STOPPED);
        ok("oa.turnAndError", LiveEngineOpenAI.eventKind("response.done") == LiveEngineOpenAI.TURN_DONE
                && LiveEngineOpenAI.eventKind("{\"type\":\"error\",\"error\":{\"message\":\"x\"}}")
                == LiveEngineOpenAI.ERROR
                && LiveEngineOpenAI.eventKind("session.closed") == LiveEngineOpenAI.CLOSED);
        ok("oa.reconnectOnClose", LiveEngineOpenAI.shouldReconnect("code=1006 reason=bye")
                && LiveEngineOpenAI.shouldReconnect(null)
                && !LiveEngineOpenAI.shouldReconnect("code=401 reason=unauthorized")
                && !LiveEngineOpenAI.shouldReconnect("api key not valid"));
        ok("oa.url", "wss://api.openai.com/v1/realtime?model=gpt-realtime".equals(
                LiveEngineOpenAI.realtimeUrl(model(""))));
        Model custom2 = model("");
        custom2.liveUrl = "wss://gateway.example/realtime";
        ok("oa.urlOverride", "wss://gateway.example/realtime".equals(
                LiveEngineOpenAI.realtimeUrl(custom2)));
        Map<String, String> h = LiveEngineOpenAI.headers(model(""), "sk-test");
        ok("oa.headers", "Bearer sk-test".equals(h.get("Authorization"))
                && "realtime=v1".equals(h.get("OpenAI-Beta")));
        Model withHeaders = model("");
        withHeaders.headers = "X-Org: acme\nX-Trace: 42\nмусор";
        Map<String, String> h2 = LiveEngineOpenAI.headers(withHeaders, "k");
        ok("oa.headersFromModel", "acme".equals(h2.get("X-Org")) && "42".equals(h2.get("X-Trace")));

        byte[] in16 = new byte[3200]; // 100 мс при 16 кГц
        for (int i = 0; i < in16.length; i += 2) {
            int v = (i / 2) % 2 == 0 ? 1000 : -1000;
            in16[i] = (byte) (v & 0xff);
            in16[i + 1] = (byte) ((v >> 8) & 0xff);
        }
        byte[] out24 = LiveEngineOpenAI.upsample16to24(in16);
        ok("oa.upsample", out24.length == 4800 && (short) ((out24[0] & 0xff)
                | (out24[1] << 8)) == 1000);
    }

    // ------------------------------------------------------------------ Gemini

    private static void gemini() {
        Model m = model("");
        m.liveModelId = "gemini-3.8-live";
        String setup = LiveEngineGemini.buildSetup(m);
        ok("g.setupModalities", setup.contains("\"responseModalities\":[\"AUDIO\"]"));
        ok("g.setupVoice", "Puck".equals(LiveEngineGemini.DEFAULT_VOICE)
                && setup.contains("\"voiceName\":\"Puck\""));
        Map<String, Object> o = Json.obj(setup);
        ok("g.setupVoiceName", "Puck".equals(
                Json.pathString(o, "setup.generationConfig.speechConfig.voiceConfig"
                        + ".prebuiltVoiceConfig.voiceName")));
        ok("g.speechInsideGeneration", Json.pathString(o, "setup.speechConfig.voiceConfig") == null);
        ok("g.modelPrefix", "models/gemini-3.8-live".equals(Json.pathString(o, "setup.model")));
        ok("g.transcriptions", o != null && setup.contains("\"inputAudioTranscription\":{}")
                && setup.contains("\"outputAudioTranscription\":{}"));
        Model voiced = model("Charon");
        voiced.liveModelId = "gemini-3.8-live";
        ok("g.customVoice", "Charon".equals(Json.pathString(Json.obj(
                LiveEngineGemini.buildSetup(voiced, "Будь краток.", null)),
                "setup.generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName")));
        String resumed = LiveEngineGemini.buildSetup(voiced, "", "handle-1");
        ok("g.resumptionHandle", "handle-1".equals(
                Json.pathString(Json.obj(resumed), "setup.sessionResumption.handle")));

        byte[] pcm = new byte[]{1, 0, 2, 0};
        Map<String, Object> chunk = Json.obj(LiveEngineGemini.buildAudioChunk(pcm));
        Map<String, Object> ri = Json.pathObj(chunk, "realtimeInput");
        List<Object> chunks = ri == null ? null : Json.asList(ri.get("mediaChunks"));
        Map<String, Object> first = chunks == null ? null : Json.asObj(chunks.get(0));
        ok("g.pcmPath", first != null
                && "audio/pcm;rate=16000".equals(Json.str(first.get("mimeType")))
                && pcm.length == B64.decode(Json.str(first.get("data"))).length);

        ok("g.setupComplete", LiveEngineGemini.kind("{\"setupComplete\":{}}")
                == LiveEngineGemini.SETUP_COMPLETE);
        ok("g.interrupted", LiveEngineGemini.kind(
                "{\"serverContent\":{\"interrupted\":true}}") == LiveEngineGemini.INTERRUPTED);
        ok("g.turnComplete", LiveEngineGemini.kind(
                "{\"serverContent\":{\"turnComplete\":true}}") == LiveEngineGemini.TURN_DONE);
        ok("g.goAway", LiveEngineGemini.kind("{\"goAway\":{\"timeLeft\":\"5s\"}}")
                == LiveEngineGemini.GO_AWAY);
        ok("g.audioKind", LiveEngineGemini.kind("{\"serverContent\":{\"modelTurn\":{\"parts\":"
                + "[{\"inlineData\":{\"mimeType\":\"audio/pcm;rate=24000\",\"data\":\"AAA=\"}}]}}}")
                == LiveEngineGemini.AUDIO);
        ok("g.transcriptKinds",
                LiveEngineGemini.kind("{\"serverContent\":{\"outputTranscription\":{\"text\":\"при\"}}}")
                        == LiveEngineGemini.ASSISTANT_TEXT
                        && LiveEngineGemini.kind("{\"serverContent\":{\"inputTranscription\":{\"text\":\"да\"}}}")
                        == LiveEngineGemini.USER_TEXT);
        ok("g.resumption", LiveEngineGemini.kind("{\"sessionResumptionUpdate\":"
                + "{\"newHandle\":\"h\",\"resumable\":true}}") == LiveEngineGemini.RESUMPTION);
        ok("g.usage", LiveEngineGemini.kind("{\"usageMetadata\":{\"totalTokenCount\":7}}")
                == LiveEngineGemini.USAGE);
        ok("g.snakeCase", LiveEngineGemini.kind("{\"server_content\":{\"turn_complete\":true}}")
                == LiveEngineGemini.TURN_DONE && LiveEngineGemini.eventKind("{\"setup_complete\":{}}")
                == LiveEngineGemini.SETUP_COMPLETE);
        ok("g.error", LiveEngineGemini.kind("{\"error\":{\"message\":\"нет квоты\"}}")
                == LiveEngineGemini.ERROR);
        String url = LiveEngineGemini.liveUrl(model(""), "KEY");
        ok("g.url", url.startsWith("wss://generativelanguage.googleapis.com/ws/")
                && url.endsWith("BidiGenerateContent?key=KEY"));
    }

    // ------------------------------------------------------------------ base64

    private static void b64() {
        byte[] data = new byte[]{0, 1, 2, (byte) 0xff, (byte) 0xfe, 65, 66, 67};
        String enc = B64.encode(data);
        ok("b64.roundTrip", java.util.Arrays.equals(data, B64.decode(enc)));
        ok("b64.known", "QUJD".equals(B64.encode(new byte[]{65, 66, 67})));
        ok("b64.noPadding", java.util.Arrays.equals(new byte[]{65, 66}, B64.decode("QUI")));
        ok("b64.whitespace", java.util.Arrays.equals(new byte[]{65, 66, 67},
                B64.decode("QU\r\nJD\n")));
        ok("b64.empty", B64.encode(new byte[0]).length() == 0
                && B64.decode("").length == 0 && B64.decode(null).length == 0);
        ok("b64.urlSafe", java.util.Arrays.equals(B64.decode("-_8="), B64.decode("+/8=")));
        boolean threw = false;
        try {
            B64.decode("QU*D");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        ok("b64.badChar", threw);
    }

    public static void main(String[] args) {
        run();
        System.exit(fails == 0 ? 0 : 1);
    }
}
