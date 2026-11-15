package ru.alexv.anyailive.ui;

import ru.alexv.anyailive.R;

/**
 * Каталог тем приложения: ключи, названия и стили. Чистые данные без
 * android-классов, чтобы JVM-тесты могли проверять каталог напрямую.
 * Android-зависимое применение темы — в {@link ThemesRuntime}.
 */
public final class Themes {

    private Themes() {
    }

    public static final String DEFAULT = "dark";

    private static final String[] KEYS = {
            "dark", "light", "midnight", "ash", "cyber", "matrix", "sunset", "aurora"
    };

    /** Имена стилей тем; разрешаются в R.style лениво, чтобы класс грузился без R. */
    private static final String[] STYLE_NAMES = {
            "ThemeDark", "ThemeLight", "ThemeMidnight", "ThemeAsh",
            "ThemeCyber", "ThemeMatrix", "ThemeSunset", "ThemeAurora"
    };

    /** Названия для выбора в настройках; порядок совпадает с KEYS. */
    public static final String[] LABELS = {
            "Тёмная (Gemini)",
            "Светлая",
            "Полночь",
            "Пепел",
            "Киберпанк",
            "Матрица",
            "Закат",
            "Аврора"
    };

    /** Возвращает идентификатор стиля темы по ключу; неизвестный ключ — тёмная. */
    public static int styleFor(String key) {
        int i = indexOf(key);
        if (i < 0) {
            i = 0;
        }
        try {
            java.lang.reflect.Field f = R.style.class.getField(STYLE_NAMES[i]);
            return f.getInt(null);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    /** Человекочитаемое имя темы по ключу; неизвестный ключ — имя тёмной. */
    public static String labelFor(String key) {
        int i = indexOf(key);
        return i < 0 ? LABELS[0] : LABELS[i];
    }

    /** Количество тем в каталоге (для тестов). */
    public static int count() {
        return KEYS.length;
    }

    /** Ключ по индексу (для тестов и диалога выбора). */
    public static String keyAt(int i) {
        return KEYS[i];
    }

    private static int indexOf(String key) {
        if (key == null) {
            return -1;
        }
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }
}
