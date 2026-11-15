package ru.alexv.anyailive.ui;

/** Standalone JVM regressions for the callback boundary used by LiveActivity. */
public final class LiveSessionCallbacksTests {
    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        LiveSessionCallbacks gate = new LiveSessionCallbacks();
        final int[] effects = new int[1];
        Runnable callback = new Runnable() {
            @Override public void run() { effects[0]++; }
        };
        gate.runIfActive(callback);
        check("callbacks before start have no effects", effects[0] == 0);
        check("first start succeeds", gate.start());
        gate.runIfActive(callback);
        check("active callback executes", effects[0] == 1);
        gate.stop();
        // Models queued callbacks and synchronous final callbacks from engine.stop().
        gate.runIfActive(callback);
        check("stop rejects queued and engine.stop callbacks", effects[0] == 1);
        gate.stop();
        check("closed screen cannot restart from late permission result", !gate.start());
        gate.runIfActive(callback);
        check("repeated stop keeps callbacks rejected", effects[0] == 1);

        LiveSessionCallbacks active = new LiveSessionCallbacks();
        active.start();
        Turn turn = new Turn();
        active.finishAssistant(null, turn);
        check("null text discards audio without writing WAV or message", turn.discards == 1 && turn.messages == 0);
        active.finishAssistant(" \t\n ", turn);
        check("blank text discards audio without writing WAV or message", turn.discards == 2 && turn.messages == 0);
        active.finishAssistant("  answer  ", turn);
        check("nonempty assistant turn persists trimmed text", turn.messages == 1 && "answer".equals(turn.text));
        active.stop();
        active.finishAssistant("late answer", turn);
        check("late final text cannot persist message or WAV", turn.messages == 1 && turn.discards == 2);
        if (failures != 0) throw new AssertionError(failures + " of " + checks + " checks failed");
        System.out.println("LiveSessionCallbacksTests: " + checks + " passed");
    }

    private static final class Turn implements LiveSessionCallbacks.AssistantTurn {
        int messages, discards;
        String text;
        @Override public void discardAudio() { discards++; }
        @Override public void persist(String text) { messages++; this.text = text; }
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) { failures++; System.err.println("FAIL: " + label); }
    }
}
