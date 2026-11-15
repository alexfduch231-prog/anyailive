package ru.alexv.anyailive.tests;

import ru.alexv.anyailive.ui.ChatSessionCallbacks;

/**
 * Standalone JVM regressions for the voice-callback boundary used by ChatActivity.
 * The chat screen starts and stops the cascade many times, so every session carries
 * its own token: a callback that arrives after stopVoice() (queued UI runnable,
 * synchronous callback from engine.stop(), late permission result) must neither
 * write into the history nor tear down the session that replaced it.
 */
public final class ChatSessionCallbacksTests {
    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        ChatSessionCallbacks fence = new ChatSessionCallbacks();
        Counter persisted = new Counter();
        Counter tornDown = new Counter();

        check("no session rejects effects", !fence.active(0));
        fence.runIfActive(0, persisted.asRunnable());
        check("effect without session is ignored", persisted.value == 0);

        int first = fence.begin();
        check("begin returns a session token", first != 0);
        check("fresh session is active", fence.active(first));
        fence.runIfActive(first, persisted.asRunnable());
        check("active session runs its effects", persisted.value == 1);

        // stopVoice(): queued UI runnables and synchronous engine.stop() callbacks.
        fence.stop();
        check("stop deactivates the session", !fence.active(first));
        fence.runIfActive(first, persisted.asRunnable());
        check("callback queued before stop cannot persist", persisted.value == 1);

        int second = fence.begin();
        check("new session gets another token", second != first);
        check("new session is active", fence.active(second));
        fence.runIfActive(first, persisted.asRunnable());
        check("old session cannot write into the new one's history", persisted.value == 1);
        check("old session cannot tear down the new one", fence.active(second));

        // Late onError of the old session must not stop the running session.
        Runnable teardown = new Runnable() {
            @Override
            public void run() {
                tornDown.value++;
                fence.stop();
            }
        };
        fence.runIfActive(first, teardown);
        check("late error of a stopped session does not tear down silently",
                tornDown.value == 0 && fence.active(second));
        fence.runIfActive(second, teardown);
        check("error of the running session tears down once",
                tornDown.value == 1 && !fence.active(second));

        int third = fence.begin();
        check("screen can start voice again after stop", fence.active(third));
        fence.stop();
        fence.stop();
        check("repeated stop is harmless", !fence.active(third));
        check("session token is never reused", fence.begin() != third);

        // Text delivery: blank finals never reach the history, trimmed text does.
        ChatSessionCallbacks texts = new ChatSessionCallbacks();
        Texts sink = new Texts();
        int voice = texts.begin();
        texts.deliverText(voice, null, sink);
        texts.deliverText(voice, " \t\n ", sink);
        check("blank transcripts are not persisted", sink.count == 0);
        texts.deliverText(voice, "  answer  ", sink);
        check("nonempty transcript is persisted trimmed",
                sink.count == 1 && "answer".equals(sink.last));
        texts.stop();
        texts.deliverText(voice, "late answer", sink);
        check("late transcript after stop is dropped", sink.count == 1);
        int restarted = texts.begin();
        texts.deliverText(voice, "stale answer", sink);
        check("transcript of an old session is dropped after restart", sink.count == 1);
        texts.deliverText(restarted, "next answer", sink);
        check("transcript of the running session is kept",
                sink.count == 2 && "next answer".equals(sink.last));
        texts.deliverText(0, "no session", sink);
        check("transcript without session is ignored", sink.count == 2);

        if (failures != 0) throw new AssertionError(failures + " of " + checks + " checks failed");
        System.out.println("ChatSessionCallbacksTests: " + checks + " passed");
    }

    private static final class Counter {
        int value;

        Runnable asRunnable() {
            return new Runnable() {
                @Override
                public void run() {
                    value++;
                }
            };
        }
    }

    private static final class Texts implements ChatSessionCallbacks.TextSink {
        int count;
        String last;

        @Override
        public void persist(String text) {
            count++;
            last = text;
        }
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.err.println("FAIL: " + label);
        }
    }
}
