package ru.alexv.anyailive.ui;

import android.app.Activity;
import android.util.TypedValue;

import ru.alexv.anyailive.data.SettingsStore;

/**
 * Android-часть тем: применяет сохранённую тему к активити до setContentView
 * и разрешает цветовые атрибуты текущей темы. Отделено от {@link Themes},
 * чтобы каталог тем оставался чистым для JVM-тестов.
 */
final class ThemesRuntime {

    private ThemesRuntime() {
    }

    /** Применяет сохранённую тему к активити; вызывать ДО setContentView. */
    static void apply(Activity activity) {
        String key = new SettingsStore(activity).get(SettingsStore.THEME, Themes.DEFAULT);
        activity.setTheme(Themes.styleFor(key));
    }

    /** Разрешает цветовой атрибут текущей темы (например R.attr.colAccent). */
    static int color(Activity activity, int attr) {
        TypedValue tv = new TypedValue();
        activity.getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }
}
