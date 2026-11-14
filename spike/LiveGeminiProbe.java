import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import ru.alexv.anyailive.data.Model;
import ru.alexv.anyailive.voice.LiveEngineGemini;
import ru.alexv.anyailive.voice.VoiceEngine;

/**
 * Живая проверка LiveEngineGemini на настоящем эндпоинте: setup, ответ аудио, субтитры.
 * Запускается на JVM, потому что движок не тянет android.* (Ws + B64 + Json — чистый Java).
 *
 * java -cp "build/classes-live;app/libs/nv-websocket-client-2.14.jar" LiveGeminiProbe <путь к .env>
 */
public final class LiveGeminiProbe {

    public static void main(String[] args) throws Exception {
        String envPath = args.length > 0 ? args[0]
                : "C:/Users/Alexv/.gemini/config/tools/llm-worker/.env";
        String key = readKey(Paths.get(envPath), "GEMINI_API_KEY");
        if (key == null || key.length() == 0) {
            System.out.println("SKIP: no GEMINI_API_KEY in " + envPath);
            return;
        }

        Model m = new Model();
        m.name = "spike-gemini-live";
        m.liveProtocol = Model.LIVE_GEMINI;
        m.liveModelId = args.length > 1 ? args[1] : "gemini-3.8-live";
        m.voice = "Puck";

        final LiveEngineGemini engine = new LiveEngineGemini(m, key, "Отвечай одной короткой фразой по-русски.");
        engine.setSink(new LiveEngineGemini.Sink() {
            public void onAudio(byte[] pcm24k) {
                audioBytes += pcm24k.length;
            }

            public void onBargeIn() {
                System.out.println("SINK bargeIn");
            }

            public void onState(int state) {
                System.out.println("SINK state=" + state);
                if (state == VoiceEngine.LISTENING) {
                    ready = true;
                }
            }

            public void onUserText(String delta) {
                user.append(delta);
            }

            public void onAssistantText(String delta) {
                say.append(delta);
            }

            public void onError(String message) {
                System.out.println("SINK error: " + message);
                failed = true;
            }
        });

        engine.start(new VoiceEngine.Callback() {
            public void onState(int state) {
            }

            public void onUserText(String t) {
                System.out.println("FINAL user: " + t);
            }

            public void onAssistantText(String t) {
                System.out.println("FINAL assistant: " + t);
            }

            public void onError(String msg) {
                System.out.println("CALLBACK error: " + msg);
            }
        });

        long t0 = System.currentTimeMillis();
        while (!ready && !failed && System.currentTimeMillis() - t0 < 20000) {
            Thread.sleep(100);
        }
        if (!ready) {
            System.out.println("result=NO_SETUP after " + (System.currentTimeMillis() - t0) + " ms");
            engine.stop();
            return;
        }
        long setupMs = System.currentTimeMillis() - t0;
        System.out.println("setupComplete in " + setupMs + " ms");

        engine.sendText("Скажи ровно: проверка связи.");

        long t1 = System.currentTimeMillis();
        while (audioBytes == 0 && !failed && System.currentTimeMillis() - t1 < 25000) {
            Thread.sleep(100);
        }
        long firstAudioMs = System.currentTimeMillis() - t1;
        Thread.sleep(2500);
        engine.stop();

        System.out.println("audioBytes=" + audioBytes + " (" + (audioBytes / 48000) + " s при 24 кГц)");
        System.out.println("firstAudioMs=" + firstAudioMs);
        System.out.println("assistantText=" + say);
        System.out.println("userText=" + user);
        System.out.println("tokens prompt=" + engine.getPromptTokens() + " response=" + engine.getResponseTokens()
                + " thoughts=" + engine.getThoughtsTokens());
        System.out.println("result=" + (audioBytes > 0 && say.length() > 0 ? "LIVE_OK" : "INCOMPLETE"));
    }

    private static volatile boolean ready, failed;
    private static volatile long audioBytes;
    private static final StringBuilder say = new StringBuilder();
    private static final StringBuilder user = new StringBuilder();

    private static String readKey(Path p, String name) throws IOException {
        if (!Files.exists(p)) {
            return null;
        }
        Charset cs = Charset.forName("UTF-8");
        for (String line : Files.readAllLines(p, cs)) {
            String s = line.trim();
            if (s.startsWith(name + "=")) {
                String v = s.substring(name.length() + 1).trim();
                if (v.startsWith("\"") && v.endsWith("\"") && v.length() > 1) {
                    v = v.substring(1, v.length() - 1);
                }
                return v;
            }
        }
        return null;
    }
}
