package ru.alexv.anyailive.ui;

import ru.alexv.anyailive.data.Model;
import ru.alexv.anyailive.data.SettingsStore;

/**
 * Накладывает настройки голоса из экрана «Настройки» на запись модели.
 * Каталог при этом не меняется: правки живут только в копии, которую отдают движкам.
 */
public final class VoiceConfig {

    public static final String ENGINE_AUTO = "auto";
    public static final String ENGINE_LIVE = "live";
    public static final String ENGINE_CASCADE = "cascade";

    private VoiceConfig() {
    }

    /** Копия модели с подставленными моделями STT/TTS и голосом из настроек. */
    public static Model apply(SettingsStore settings, Model model) {
        return apply(model, settings.get(SettingsStore.STT_MODEL, ""),
                settings.get(SettingsStore.TTS_MODEL, ""), settings.get(SettingsStore.VOICE, ""));
    }

    /** Model-specific choices win; global settings only fill missing values. */
    public static Model apply(Model model, String stt, String tts, String voice) {
        if (model == null) {
            return null;
        }
        Model m = model.copy();
        m.sttModel = pick(m.sttModel, stt);
        m.ttsModel = pick(m.ttsModel, tts);
        // A global OpenAI voice must not replace Gemini's provider-specific default.
        m.voice = pick(m.voice, Model.LIVE_GEMINI.equals(m.liveProtocol) ? "" : voice);
        return m;
    }

    /** Resolve the same policy before permission prompts and after permission is granted. */
    public static String route(String requested, Model model) {
        boolean live = model != null && (Model.LIVE_OPENAI.equals(model.liveProtocol)
                || Model.LIVE_GEMINI.equals(model.liveProtocol));
        if (ENGINE_CASCADE.equals(requested)) {
            return ENGINE_CASCADE;
        }
        if (ENGINE_LIVE.equals(requested)) {
            return model == null ? "unsupported" : live ? ENGINE_LIVE : ENGINE_CASCADE;
        }
        return live ? ENGINE_LIVE : ENGINE_CASCADE;
    }

    /** HTTP recognition is opt-in; stored model names alone must not select it. */
    public static boolean useHttpStt(String choice) {
        return "http".equals(choice);
    }

    /** Язык распознавания и синтеза; по умолчанию — то, что передали. */
    public static String language(SettingsStore settings, String fallback) {
        return pick(settings.get(SettingsStore.LANGUAGE, ""), fallback);
    }

    public static String engine(SettingsStore settings) {
        String e = settings.get(SettingsStore.ENGINE, ENGINE_AUTO);
        return e == null || e.length() == 0 ? ENGINE_AUTO : e;
    }

    private static String pick(String preferred, String fallback) {
        if (preferred != null && preferred.trim().length() > 0) {
            return preferred.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }
}
