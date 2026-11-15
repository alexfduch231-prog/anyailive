package ru.alexv.anyailive;

import ru.alexv.anyailive.data.Model;
import ru.alexv.anyailive.net.SseParser;
import ru.alexv.anyailive.tests.AudioTests;
import ru.alexv.anyailive.tests.ChatTests;
import ru.alexv.anyailive.tests.JsonTests;
import ru.alexv.anyailive.tests.LiveTests;

/**
 * Headless-проверки логики: запускается на обычной JVM, без Android.
 * Никаких обращений к android.* здесь быть не должно.
 *   java -cp "build/classes;app/libs/nv-websocket-client-2.14.jar" ru.alexv.anyailive.TestMain
 */
public class TestMain {

    private static int fails = 0;
    private static int total = 0;

    static void check(String name, boolean ok) {
        total++;
        if (ok) {
            System.out.println("ok   " + name);
        } else {
            fails++;
            System.out.println("FAIL " + name);
        }
    }

    static void fail(String name, String why) {
        total++;
        fails++;
        System.out.println("FAIL " + name + " (" + why + ")");
    }

    static void section(String name) {
        System.out.println("--- " + name);
    }

    static void sseTests() {
        section("sse");
        SseParser p = new SseParser();
        check("sse.data", "{\"a\":1}".equals(p.feed("data: {\"a\":1}")));
        check("sse.ignore", p.feed(": keepalive") == null);
        check("sse.done", p.isDone("data: [DONE]"));
        check("sse.multiline", "x".equals(p.feed("data: x")));
        check("sse.empty", p.feed("") == null);
        check("sse.eventLine", p.feed("event: message") == null);
        check("sse.donePayload", p.feed("data: [DONE]") == null);
        check("sse.notDone", !p.isDone("data: {\"a\":1}"));
        check("sse.doneNoSpace", p.isDone("data:[DONE]"));
    }

    static void modelTests() {
        section("model");
        Model m = new Model();
        m.name = "OpenAI";
        check("model.toString", "OpenAI".equals(m.toString()));
        check("model.liveOffByDefault", !m.live());
        m.liveProtocol = Model.LIVE_GEMINI;
        check("model.liveOn", m.live());
        m.liveProtocol = null;
        check("model.liveNullSafe", !m.live());
        Model empty = new Model();
        check("model.emptyToString", "".equals(empty.toString()));
    }

    public static void main(String[] args) throws Exception {
        section("skeleton");
        check("build.smoke", true);
        check("strings.brand", "anyAIlive".equals("anyAIlive"));

        sseTests();
        modelTests();

        JsonTests.run();
        LiveTests.run();
        ChatTests.run();
        AudioTests.run();
        ru.alexv.anyailive.tests.SettingsPolicyTests.run();
        ru.alexv.anyailive.data.MemoryHistoryTests.main(new String[0]);
        ru.alexv.anyailive.voice.AudioOutBufferTests.main(new String[0]);
        ru.alexv.anyailive.ui.LiveSessionCallbacksTests.main(new String[0]);
        ru.alexv.anyailive.tests.ChatSessionCallbacksTests.main(new String[0]);
        ru.alexv.anyailive.tests.VoicePermissionPolicyTests.main(new String[0]);
        ru.alexv.anyailive.tests.ChatAudioFilesTests.main(new String[0]);
        ru.alexv.anyailive.tests.TtsEnginePolicyTests.main(new String[0]);
        ru.alexv.anyailive.tests.TtsRoutingPolicyTests.main(new String[0]);
        ru.alexv.anyailive.tests.PendingSpeechQueueTests.main(new String[0]);
        ru.alexv.anyailive.tests.TranscriptionBodyTests.main(new String[0]);

        System.out.println();
        System.out.println(fails == 0 ? ("PASS " + total + " checks") : ("FAILURES " + fails + "/" + total));
        System.exit(fails == 0 ? 0 : 1);
    }
}
