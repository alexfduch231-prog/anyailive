package ru.alexv.anyailive.voice;

/** Pure policy for cascade duplex behavior. */
public final class BargeInPolicy {
    private BargeInPolicy() {}

    /** Voice barge-in is safe only when explicitly enabled and AEC is active. */
    public static boolean enabled(boolean aecEnabled, boolean requested) {
        return aecEnabled && requested;
    }

    /** Capture may stay active while TTS speaks only in safe duplex mode. */
    public static boolean captureDuringSpeech(boolean aecEnabled, boolean requested) {
        return enabled(aecEnabled, requested);
    }
}
