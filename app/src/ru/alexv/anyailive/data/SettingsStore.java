package ru.alexv.anyailive.data;

import android.content.ContentValues;
import android.content.Context;

/** Настройки — простая key/value таблица. */
public class SettingsStore {

    public static final String ENGINE = "engine";              // auto | live | cascade
    public static final String STT_MODEL = "stt_model";
    public static final String TTS_MODEL = "tts_model";
    public static final String TTS_ENGINE = "tts_engine_package";
    public static final String VOICE = "voice";
    public static final String LANGUAGE = "language";
    public static final String BARGE_IN = "barge_in";          // "1" | "0"
    public static final String THEME = "theme";                // ключ темы из ui/Themes
    public static final String APP_LANG = "app_lang";          // auto | ru | en
    public static final String LANG_AUTO = "auto";
    public static final String KEEP_HISTORY = "keep_history";  // "1" | "0"
    public static final String SAVE_AUDIO = "save_audio";      // "1" | "0"

    private final Db helper;

    public SettingsStore(Context c) {
        helper = Db.get(c);
    }

    public String get(String key, String def) {
        android.database.Cursor c = helper.getReadableDatabase().query(Db.T_SETTINGS,
                new String[]{"value"}, "key=?", new String[]{key}, null, null, null);
        try {
            return c.moveToFirst() ? c.getString(0) : def;
        } finally {
            c.close();
        }
    }

    public void set(String key, String value) {
        ContentValues v = new ContentValues();
        v.put("key", key);
        v.put("value", value == null ? "" : value);
        helper.getWritableDatabase().insertWithOnConflict(Db.T_SETTINGS, null, v,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
    }

    public int getInt(String key, int def) {
        try {
            return Integer.parseInt(get(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public void setInt(String key, int v) {
        set(key, String.valueOf(v));
    }

    public boolean getBool(String key, boolean def) {
        return "1".equals(get(key, def ? "1" : "0"));
    }

    public void setBool(String key, boolean v) {
        set(key, v ? "1" : "0");
    }
}
