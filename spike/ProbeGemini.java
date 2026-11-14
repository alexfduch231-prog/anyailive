import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Спайк Gemini Live: setup -> setupComplete -> 1 с тишины -> текстовый ход -> ответ. */
public class ProbeGemini {

    static final String MODEL = System.getenv("GEMINI_LIVE_MODEL") == null
            ? "gemini-3.8-live" : System.getenv("GEMINI_LIVE_MODEL");
    static final String LOG = System.getenv("SPIKE_LOG") == null
            ? "docs/protocol-review/spike-gemini.log" : System.getenv("SPIKE_LOG");

    static String setupBody(String model) {
        return "{\"setup\":{"
                + "\"model\":\"models/" + model + "\","
                + "\"generationConfig\":{\"responseModalities\":[\"AUDIO\"],"
                + "\"speechConfig\":{\"voiceConfig\":{\"prebuiltVoiceConfig\":{\"voiceName\":\"Puck\"}}}},"
                + "\"systemInstruction\":{\"parts\":[{\"text\":\"Отвечай одним словом.\"}]},"
                + "\"inputAudioTranscription\":{},"
                + "\"outputAudioTranscription\":{},"
                + "\"sessionResumption\":{}"
                + "}}";
    }

    public static void main(String[] args) {
        String key = System.getenv("GEMINI_API_KEY");
        if (key == null || key.isEmpty()) {
            System.out.println("SKIP: no GEMINI_API_KEY");
            return;
        }
        final String url = "wss://generativelanguage.googleapis.com/ws/"
                + "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + key;
        final Map<String, String> headers = new LinkedHashMap<String, String>();
        final boolean[] flags = new boolean[8]; // setupComplete, audio, turnComplete, interrupted, goAway, transcription
        final long[] audioBytes = new long[1];
        final int[] sentTurns = new int[1];

        String err = WsProbe.run(url, headers, new WsProbe.Handler() {
            @Override
            public void onOpen(WsProbe.Session s) {
                s.send(setupBody(MODEL));
            }

            @Override
            public void onText(WsProbe.Session s, String msg) {
                if (msg.contains("setupComplete")) {
                    flags[0] = true;
                    s.raw("### setupComplete");
                    // 1 с тишины по 200 мс, 16 кГц, PCM16 mono
                    byte[] chunk = new byte[6400];
                    for (int i = 0; i < 5; i++) {
                        s.send("{\"realtimeInput\":{\"mediaChunks\":[{\"mimeType\":\"audio/pcm;rate=16000\","
                                + "\"data\":\"" + Base64.getEncoder().encodeToString(chunk) + "\"}]}}");
                    }
                    s.send("{\"clientContent\":{\"turns\":[{\"role\":\"user\","
                            + "\"parts\":[{\"text\":\"Скажи ровно одно слово: привет\"}]}],"
                            + "\"turnComplete\":true}}");
                    sentTurns[0]++;
                }
                if (msg.contains("modelTurn") || msg.contains("model_turn")) {
                    int i = msg.indexOf("\"data\":\"");
                    if (i < 0) {
                        i = msg.indexOf("\"data\": \"");
                    }
                    if (i > 0) {
                        int from = msg.indexOf('"', i + 7) + 1;
                        int to = msg.indexOf('"', from);
                        if (to > from) {
                            audioBytes[0] += Base64.getDecoder().decode(msg.substring(from, to)).length;
                            flags[1] = true;
                        }
                    }
                }
                if (msg.contains("turnComplete") || msg.contains("turn_complete")) {
                    flags[2] = true;
                    s.raw("### turnComplete, audio=" + audioBytes[0] + " B");
                }
                if (msg.contains("interrupted")) {
                    flags[3] = true;
                }
                if (msg.contains("goAway") || msg.contains("go_away")) {
                    flags[4] = true;
                }
                if (msg.contains("inputTranscription") || msg.contains("outputTranscription")
                        || msg.contains("input_transcription") || msg.contains("output_transcription")) {
                    flags[5] = true;
                }
            }
        }, 25, LOG);

        System.out.println();
        System.out.println("GEMINI model=" + MODEL);
        System.out.println("GEMINI connect=" + (err == null ? "ok" : "FAIL " + err));
        System.out.println("GEMINI setupComplete=" + flags[0]);
        System.out.println("GEMINI audioBytes=" + audioBytes[0]);
        System.out.println("GEMINI turnComplete=" + flags[2]);
        System.out.println("GEMINI transcriptionEvents=" + flags[5]);
        System.out.println("GEMINI goAway=" + flags[4]);
        System.out.println("GEMINI log=" + LOG);
        System.out.println("GEMINI result=" + (!flags[0] ? "NO_SETUP_COMPLETE"
                : (audioBytes[0] > 0 ? "AUDIO_OK" : "NO_AUDIO")));
    }
}
