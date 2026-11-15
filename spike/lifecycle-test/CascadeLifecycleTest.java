import android.os.Handler;
import java.lang.reflect.Field;
import java.io.IOException;
import java.util.List;
import ru.alexv.anyailive.voice.*;

/** Executes production CascadeHttp with a deterministic main queue; no microphone or network. */
public final class CascadeLifecycleTest {
    private static int failures;
    private static final class Events implements VoiceEngine.Callback {
        int replies, errors, state;
        public void onState(int s) { state = s; }
        public void onUserText(String s) {}
        public void onAssistantText(String s) { replies++; }
        public void onError(String s) { errors++; }
    }
    private static void field(Object target, String name, Object value) throws Exception {
        Field f = CascadeHttp.class.getDeclaredField(name);
        f.setAccessible(true); f.set(target, value);
    }
    private static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }
    private static CascadeHttp engine(final boolean error, Events events) throws Exception {
        Handler.queue.clear();
        CascadeHttp e = new CascadeHttp(null, null, new CascadeSystem.Chat() {
            public String complete(String prompt, List<CascadeSystem.ChatMsg> messages, int timeout) throws IOException {
                if (error) throw new IOException("old request failed");
                return "old answer";
            }
        });
        field(e, "running", true); field(e, "cb", events);
        field(e, "selectedTtsEngine", "fixture.system.tts");
        return e;
    }
    private static void sttField(CascadeStt s, String name, Object value) throws Exception {
        Field f = CascadeStt.class.getDeclaredField(name); f.setAccessible(true); f.set(s, value);
    }
    private static void sttRestart() throws Exception {
        final int[] calls = new int[2];
        CascadeStt s = new CascadeStt(null, null, "ru");
        sttField(s, "running", true);
        sttField(s, "listener", new CascadeStt.Listener() {
            public void onSpeechStart() {}
            public void onIdle() {}
            public void onText(String t) { calls[0]++; }
            public void onError(String t) { calls[1]++; }
        });
        java.lang.reflect.Method deliver = CascadeStt.class.getDeclaredMethod("deliver", String.class, int.class);
        deliver.setAccessible(true);
        deliver.invoke(s, "old transcript", 0);
        s.onError("old microphone failure");
        s.stop(); sttField(s, "running", true);
        Handler.next().run(); Handler.next().run();
        check("STT queued transcript after restart discarded", calls[0] == 0);
        check("STT queued error after restart discarded", calls[1] == 0 && s.isRunning());
        deliver.invoke(s, "current transcript", 1); Handler.next().run();
        check("STT current transcript still delivered", calls[0] == 1);
    }
    private static void sttSingleFlight() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audio/transcriptions", exchange -> {
            requests.incrementAndGet(); entered.countDown();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            byte[] body = "{\"text\":\"one\"}".getBytes("UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            ru.alexv.anyailive.data.Model m = new ru.alexv.anyailive.data.Model();
            m.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            CascadeStt s = new CascadeStt(null, m, "ru"); sttField(s, "running", true);
            java.lang.reflect.Method transcribe = CascadeStt.class.getDeclaredMethod("transcribe", byte[].class);
            transcribe.setAccessible(true); transcribe.invoke(s, new byte[16000]);
            if (!entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("no STT request");
            Field paused = CascadeStt.class.getDeclaredField("paused"); paused.setAccessible(true);
            check("STT pauses microphone while HTTP pending", paused.getBoolean(s));
            transcribe.invoke(s, new byte[16000]);
            release.countDown(); Handler.next().run();
            java.util.concurrent.CountDownLatch barrier = new java.util.concurrent.CountDownLatch(1);
            server.createContext("/barrier", exchange -> {
                exchange.sendResponseHeaders(204, -1); exchange.close(); barrier.countDown();
            });
            new java.net.URL(m.baseUrl + "/barrier").openConnection().getInputStream().close();
            barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
            check("STT admits only one HTTP request while pending", requests.get() == 1);
            s.stop();
        } finally { release.countDown(); server.stop(0); }
    }
    public static void main(String[] args) throws Exception {
        Events success = new Events();
        CascadeHttp e = engine(false, success);
        e.sendText("hello"); Handler.next().run();
        Runnable reply = Handler.next();
        e.interrupt();
        check("interrupt while thinking resumes listening", e.state() == VoiceEngine.LISTENING);
        reply.run();
        check("queued reply after interrupt discarded", success.replies == 0);

        Events error = new Events();
        e = engine(true, error);
        e.sendText("hello"); Handler.next().run();
        Runnable fail = Handler.next();
        e.interrupt(); fail.run();
        check("queued error after interrupt discarded", error.errors == 0 && e.isRunning());

        Handler.queue.clear();
        Events idleEvents = new Events();
        e = engine(false, idleEvents);
        field(e, "state", VoiceEngine.THINKING);
        Field mic = CascadeHttp.class.getDeclaredField("mic"); mic.setAccessible(true);
        ((CascadeStt.Listener) mic.get(e)).onIdle();
        check("late STT idle cannot unpause model request", e.state() == VoiceEngine.THINKING);
        Handler.queue.clear();
        Events stoppedStart = new Events();
        CascadeHttp pendingStart = new CascadeHttp(null, null, null);
        pendingStart.start(stoppedStart); pendingStart.stop(); Handler.next().run();
        check("queued engine start after stop discarded", stoppedStart.errors == 0);
        sttRestart();
        sttSingleFlight();
        Events current = new Events();
        e = engine(false, current);
        e.sendText("hello"); Handler.next().run(); Handler.next().run();
        check("current reply still delivered", current.replies == 1);
        e.stop(); Handler.queue.clear();
        if (failures != 0) throw new AssertionError(failures + " lifecycle regressions");
    }
}
