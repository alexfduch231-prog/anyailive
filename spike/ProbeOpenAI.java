import java.util.LinkedHashMap;
import java.util.Map;

/** Спайк OpenAI Realtime: session.update по GA-схеме -> текстовый ход -> ответ. */
public class ProbeOpenAI {

    static final String MODEL = System.getenv("OPENAI_REALTIME_MODEL") == null
            ? "gpt-realtime" : System.getenv("OPENAI_REALTIME_MODEL");
    static final String LOG = System.getenv("SPIKE_LOG") == null
            ? "docs/protocol-review/spike-openai.log" : System.getenv("SPIKE_LOG");

    static String sessionUpdate(String model) {
        return "{\"type\":\"realtime\",\"model\":\"" + model + "\","
                + "\"output_modalities\":[\"audio\"],"
                + "\"audio\":{"
                + "\"input\":{\"format\":{\"type\":\"audio/pcm\",\"rate\":24000},"
                + "\"transcription\":{\"model\":\"gpt-4o-transcribe\"},"
                + "\"turn_detection\":{\"type\":\"server_vad\",\"threshold\":0.5,\"prefix_padding_ms\":300,"
                + "\"silence_duration_ms\":500}},"
                + "\"output\":{\"format\":{\"type\":\"audio/pcm\",\"rate\":24000},\"voice\":\"alloy\"}"
                + "}}";
    }

    public static void main(String[] args) {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isEmpty()) {
            System.out.println("SKIP: no OPENAI_API_KEY");
            return;
        }
        final Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + key);
        final boolean[] created = new boolean[1];
        final boolean[] audio = new boolean[1];
        final boolean[] done = new boolean[1];
        final long[] bytes = new long[1];

        String err = WsProbe.run("wss://api.openai.com/v1/realtime?model=" + MODEL, headers,
                new WsProbe.Handler() {
                    @Override
                    public void onText(WsProbe.Session s, String msg) {
                        if (msg.contains("session.created")) {
                            created[0] = true;
                            s.raw("### session.created");
                            s.send("{\"type\":\"session.update\",\"session\":" + sessionUpdate(MODEL) + "}");
                            s.send("{\"type\":\"conversation.item.create\",\"item\":{\"type\":\"message\","
                                    + "\"role\":\"user\",\"content\":[{\"type\":\"input_text\","
                                    + "\"text\":\"Say exactly one word: hello\"}]}}");
                            s.send("{\"type\":\"response.create\"}");
                        }
                        if (msg.contains("response.output_audio.delta")) {
                            audio[0] = true;
                            bytes[0] += 512;
                        }
                        if (msg.contains("response.done")) {
                            done[0] = true;
                            s.raw("### response.done");
                        }
                    }
                }, 25, LOG);

        System.out.println();
        System.out.println("OPENAI model=" + MODEL);
        System.out.println("OPENAI connect=" + (err == null ? "ok" : "FAIL " + err));
        System.out.println("OPENAI sessionCreated=" + created[0]);
        System.out.println("OPENAI audioDeltas=" + audio[0] + " (~bytes " + bytes[0] + ")");
        System.out.println("OPENAI responseDone=" + done[0]);
        System.out.println("OPENAI log=" + LOG);
    }
}
