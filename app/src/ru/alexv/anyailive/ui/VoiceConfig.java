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
        if (model == null) {
            return null;
        }
        Model m = model.copy();
        m.sttModel = pick(settings.get(SettingsStore.STT_MODEL, ""), m.sttModel);
        m.ttsModel = pick(settings.get(SettingsStore.TTS_MODEL, ""), m.ttsModel);
        m.voice = pick(settings.get(SettingsStore.VOICE, ""), m.voice);
        return m;
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
        return fallback == null ? "" : fallback;
    }
}
