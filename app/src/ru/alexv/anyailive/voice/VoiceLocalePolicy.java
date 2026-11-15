package ru.alexv.anyailive.voice;

import java.util.Locale;

/** Pure locale matching used by the Android TTS picker and headless tests. */
public final class VoiceLocalePolicy {
    private VoiceLocalePolicy() {}

    public static boolean compatible(String voiceLocaleTag, String requestedLanguageTag) {
        if (voiceLocaleTag == null || voiceLocaleTag.length() == 0
                || requestedLanguageTag == null || requestedLanguageTag.length() == 0) return false;
        Locale voice = Locale.forLanguageTag(voiceLocaleTag.replace('_', '-'));
        Locale requested = Locale.forLanguageTag(requestedLanguageTag.replace('_', '-'));
        String voiceLanguage = voice.getLanguage();
        String requestedLanguage = requested.getLanguage();
        return voiceLanguage.length() > 0 && voiceLanguage.equalsIgnoreCase(requestedLanguage);
    }
}
