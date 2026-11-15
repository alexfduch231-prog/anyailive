package ru.alexv.anyailive.voice;

/** Pure routing policy; independent of the STT transport and Android runtime. */
public final class TtsRoutingPolicy {
    private TtsRoutingPolicy() { }

    public static boolean useSystemTts(String selectedEngine, boolean httpFallback) {
        return httpFallback || (selectedEngine != null && selectedEngine.trim().length() > 0);
    }
}
