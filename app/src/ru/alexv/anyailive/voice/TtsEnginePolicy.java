package ru.alexv.anyailive.voice;

import java.util.Collection;

/** Explicit local engine selection must never silently fall back to another provider. */
public final class TtsEnginePolicy {
    private TtsEnginePolicy() {}
    public static String resolve(String saved, Collection<String> installed) {
        String selected = saved == null ? "" : saved.trim();
        if (selected.length() == 0) return null;
        if (installed != null && installed.contains(selected)) return selected;
        throw new IllegalArgumentException("Выбранный TTS-движок не установлен: " + selected);
    }
    public static boolean matches(String requested, String actual) {
        return requested == null || requested.equals(actual);
    }
}
