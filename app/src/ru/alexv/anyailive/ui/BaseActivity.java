package ru.alexv.anyailive.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;

import ru.alexv.anyailive.data.SettingsStore;

/**
 * База всех экранов: применяет выбранную тему и язык интерфейса
 * (авто / русский / английский) до создания разметки.
 * Отдельный класс, чтобы JVM-тесты грузили только чистый {@link Themes}.
 */
public abstract class BaseActivity extends Activity {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(localized(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        String theme = new SettingsStore(this).get(SettingsStore.THEME, Themes.DEFAULT);
        setTheme(Themes.styleFor(theme));
        super.onCreate(savedInstanceState);
    }

    /** Возвращает контекст с локалью из настроек; "auto" — системная. */
    private static Context localized(Context base) {
        String lang = new SettingsStore(base).get(SettingsStore.APP_LANG, SettingsStore.LANG_AUTO);
        if (SettingsStore.LANG_AUTO.equals(lang)) {
            return base;
        }
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(new java.util.Locale(lang));
        return base.createConfigurationContext(cfg);
    }

    /** Удобное разрешение атрибута темы для наследников. */
    protected int themedColor(int attr) {
        return ThemesRuntime.color(this, attr);
    }
}
