package ru.alexv.anyailive.tests;

import ru.alexv.anyailive.voice.TtsRoutingPolicy;

/** JVM-only regression tests for the policy used by CascadeHttp.speakPhrase. */
public final class TtsRoutingPolicyTests {
    public static void main(String[] args) {
        check(TtsRoutingPolicy.useSystemTts("org.example.piper", false),
                "explicit app-local engine must bypass HTTP speech even before fallback");
        check(TtsRoutingPolicy.useSystemTts(" org.example.piper ", false),
                "surrounding whitespace must not erase an explicit engine");
        check(!TtsRoutingPolicy.useSystemTts("", false), "empty keeps HTTP speech");
        check(!TtsRoutingPolicy.useSystemTts(null, false), "null keeps HTTP speech");
        check(!TtsRoutingPolicy.useSystemTts(" \t\n ", false), "blank keeps HTTP speech");
        check(TtsRoutingPolicy.useSystemTts("", true), "empty keeps sticky HTTP fallback");
        check(TtsRoutingPolicy.useSystemTts(null, true), "null keeps sticky HTTP fallback");
        check(TtsRoutingPolicy.useSystemTts(" \t ", true), "blank keeps sticky HTTP fallback");
        check(TtsRoutingPolicy.useSystemTts("org.example.piper", true),
                "explicit engine remains system TTS after HTTP fallback");
        System.out.println("PASS TtsRoutingPolicy 9 checks");
    }

    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }
}
