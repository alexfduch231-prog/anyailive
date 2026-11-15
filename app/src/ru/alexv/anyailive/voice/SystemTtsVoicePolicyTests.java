package ru.alexv.anyailive.voice;

/** Headless checks for app-local system voice selection policy. */
public final class SystemTtsVoicePolicyTests {
    public static void main(String[] args) {
        check(SystemTts.voiceLocaleCompatible("ru-RU", "ru-RU"), "exact locale matches");
        check(SystemTts.voiceLocaleCompatible("ru", "ru-RU"), "language-only voice matches requested region");
        check(!SystemTts.voiceLocaleCompatible("en-US", "ru-RU"), "different language rejected");
        check(!SystemTts.voiceLocaleCompatible("ru-RU", "en-US"), "different requested language rejected");
        check(!SystemTts.voiceLocaleCompatible("", "ru-RU"), "missing voice locale rejected");
        check(!SystemTts.voiceLocaleCompatible("ru-RU", ""), "missing requested language rejected");
        System.out.println("PASS SystemTtsVoicePolicy 6 checks");
    }

    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }
}
