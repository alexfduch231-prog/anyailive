import android.os.Handler;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import ru.alexv.anyailive.voice.CascadeStt;

/** Focused deterministic tests of queued CascadeStt callbacks. */
public final class CascadeSttRaceTest {
    private static void set(Object target, String name, Object value) throws Exception {
        Field f = CascadeStt.class.getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    private static boolean bool(Object target, String name) throws Exception {
        Field f = CascadeStt.class.getDeclaredField(name); f.setAccessible(true); return f.getBoolean(target);
    }
    private static void invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = CascadeStt.class.getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(target, args);
    }
    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        System.out.println("PASS " + name);
    }
    public static void main(String[] args) throws Exception {
        Handler.queue.clear();
        final int[] events = new int[2];
        CascadeStt s = new CascadeStt(null, null, "ru");
        set(s, "running", true);
        set(s, "listener", new CascadeStt.Listener() {
            public void onSpeechStart() {}
            public void onIdle() { events[0]++; }
            public void onText(String text) { events[1]++; }
            public void onError(String message) { throw new AssertionError(message); }
        });
        invoke(s, "postIdle", new Class<?>[0]);
        s.setPaused(true);
        Handler.next().run();
        check("queued idle does not undo newer pause", bool(s, "paused"));
        check("queued idle does not report stale idle", events[0] == 0);
        s.setPaused(false);
        set(s, "transcribing", true);
        invoke(s, "deliver", new Class<?>[]{String.class, int.class}, "old", 0);
        try {
            CascadeStt.class.getMethod("cancelPendingTranscription").invoke(s);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("missing explicit transcription cancellation", e);
        }
        Handler.next().run();
        check("cancelled transcription is not delivered", events[1] == 0);
        check("cancel releases single flight", !bool(s, "transcribing"));
        invoke(s, "deliver", new Class<?>[]{String.class, int.class}, "current", 1);
        Handler.next().run();
        check("current transcription is delivered", events[1] == 1);
        invoke(s, "postIdle", new Class<?>[0]);
        Handler.next().run();
        check("current idle is delivered", events[0] == 1);
        set(s, "transcribing", true);
        invoke(s, "postIdle", new Class<?>[]{int.class, long.class, boolean.class}, 1, 3L, true);
        s.cancelPendingTranscription();
        s.setPaused(true);
        Handler.next().run();
        check("cancelled idle keeps new pause", bool(s, "paused") && events[0] == 1);
        s.stop();
    }
}
