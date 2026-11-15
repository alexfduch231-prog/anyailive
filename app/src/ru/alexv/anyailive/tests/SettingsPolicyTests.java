package ru.alexv.anyailive.tests;

import java.lang.reflect.Method;
import ru.alexv.anyailive.data.Model;
import ru.alexv.anyailive.ui.VoiceConfig;

/** Pure JVM policy checks; failures always produce a non-zero process exit. */
public final class SettingsPolicyTests {
    private static int checks;

    public static void main(String[] args) throws Exception {
        run();
        System.out.println("SettingsPolicyTests: " + checks + " passed");
    }

    public static void run() throws Exception {
        Model specific = new Model();
        specific.sttModel = "custom-stt";
        specific.ttsModel = "custom-tts";
        specific.voice = "custom-voice";
        Model configured = apply(specific, "whisper-1", "tts-1", "alloy");
        equal("model STT wins", "custom-stt", configured.sttModel);
        equal("model TTS wins", "custom-tts", configured.ttsModel);
        equal("model voice wins", "custom-voice", configured.voice);
        equal("copy leaves original unchanged", "custom-voice", specific.voice);
        if (configured == specific) throw new AssertionError("apply must return a copy");
        checks++;
        Model empty = new Model();
        Model fallback = apply(empty, " whisper-1 ", " tts-1 ", " alloy ");
        equal("global STT fallback trimmed", "whisper-1", fallback.sttModel);
        equal("global TTS fallback trimmed", "tts-1", fallback.ttsModel);
        equal("global voice fallback trimmed", "alloy", fallback.voice);
        Model gemini = new Model();
        gemini.liveProtocol = Model.LIVE_GEMINI;
        equal("Gemini ignores provider-agnostic voice", "", apply(gemini, "", "", "alloy").voice);
        gemini.voice = "Charon";
        equal("Gemini keeps configured voice", "Charon", apply(gemini, "", "", "alloy").voice);
        equal("blank overrides stay blank", "", apply(empty, " ", "", "").sttModel);
        equal("auto nonlive", "cascade", route("auto", empty));
        equal("auto live", "live", route("auto", gemini));
        equal("explicit cascade", "cascade", route("cascade", gemini));
        equal("explicit live", "live", route("live", gemini));
        equal("ordinary model remains voice-capable with legacy live preference", "cascade", route("live", empty));
        equal("null setting defaults to auto", "live", route(null, gemini));
        equal("invalid setting defaults to auto", "live", route("invalid", gemini));
        Model unknown = new Model();
        unknown.liveProtocol = "unknown";
        equal("unknown protocol uses ordinary conversation", "cascade", route("live", unknown));
        Method sttRoute = VoiceConfig.class.getMethod("useHttpStt", String.class);
        if ((Boolean) sttRoute.invoke(null, new Object[]{null})) throw new AssertionError("unset STT must use phone");
        if ((Boolean) sttRoute.invoke(null, "whisper-1")) throw new AssertionError("model id must not enable HTTP STT");
        if (!(Boolean) sttRoute.invoke(null, "http")) throw new AssertionError("explicit HTTP STT must be honored");
        equal("missing model rejects live", "unsupported", route("live", null));
    }

    private static Model apply(Model model, String stt, String tts, String voice) throws Exception {
        Method method;
        try {
            method = VoiceConfig.class.getMethod("apply", Model.class, String.class, String.class, String.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Missing pure model-first voice policy", e);
        }
        return (Model) method.invoke(null, model, stt, tts, voice);
    }

    private static String route(String engine, Model model) throws Exception {
        Method method;
        try {
            method = VoiceConfig.class.getMethod("route", String.class, Model.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Missing shared engine routing policy", e);
        }
        return (String) method.invoke(null, engine, model);
    }

    private static void equal(String name, String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError(name + ": expected " + expected + ", got " + actual);
        checks++;
    }
}
