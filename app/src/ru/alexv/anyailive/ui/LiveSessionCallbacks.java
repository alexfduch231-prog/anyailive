package ru.alexv.anyailive.ui;

/** Android-free boundary for callbacks owned by a single Live screen. */
final class LiveSessionCallbacks {
    interface AssistantTurn {
        void discardAudio();
        void persist(String text);
    }

    // Confined to the Activity UI thread, including execution of callback effects.
    private boolean active;
    private boolean closed;

    boolean start() {
        if (closed || active) return false;
        active = true;
        return true;
    }

    void stop() {
        active = false;
        closed = true;
    }

    void runIfActive(Runnable action) {
        if (active) action.run();
    }

    void finishAssistant(String text, AssistantTurn turn) {
        if (!active) return;
        String clean = text == null ? "" : text.trim();
        if (clean.length() == 0) turn.discardAudio();
        else turn.persist(clean);
    }
}
