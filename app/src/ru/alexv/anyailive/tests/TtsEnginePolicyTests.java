package ru.alexv.anyailive.tests;

import java.util.Arrays;
import ru.alexv.anyailive.voice.TtsEnginePolicy;

public final class TtsEnginePolicyTests {
    public static void main(String[] args) {
        check(TtsEnginePolicy.resolve("", Arrays.asList("piper")) == null, "default remains default");
        check("piper".equals(TtsEnginePolicy.resolve(" piper ", Arrays.asList("piper"))), "explicit installed engine");
        boolean rejected = false;
        try { TtsEnginePolicy.resolve("missing", Arrays.asList("piper")); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "missing explicit engine must not silently use cloud default");
        check(TtsEnginePolicy.matches(null, "default"), "default may resolve freely");
        check(TtsEnginePolicy.matches("piper", "piper"), "explicit engine matches");
        check(!TtsEnginePolicy.matches("piper", "google"), "Android fallback rejected");
        System.out.println("PASS TtsEnginePolicy 6 checks");
    }
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }
}
