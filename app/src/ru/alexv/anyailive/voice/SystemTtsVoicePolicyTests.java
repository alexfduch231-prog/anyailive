package ru.alexv.anyailive.voice;

/** Headless tests for app-local system voice locale policy. */
public final class SystemTtsVoicePolicyTests {
    public static void main(String[] args) {
        check(VoiceLocalePolicy.compatible("ru-RU", "ru-RU"), "exact locale matches");
        check(VoiceLocalePolicy.compatible("ru", "ru-RU"), "language-only voice matches requested region");
        check(!VoiceLocalePolicy.compatible("en-US", "ru-RU"), "different language rejected");
        check(!VoiceLocalePolicy.compatible("ru-RU", "en-US"), "different requested language rejected");
        check(!VoiceLocalePolicy.compatible("", "ru-RU"), "missing voice locale rejected");
        check(!VoiceLocalePolicy.compatible("ru-RU", ""), "missing requested language rejected");
        System.out.println("PASS VoiceLocalePolicy 6 checks");
    }

    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }
}
