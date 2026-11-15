package ru.alexv.anyailive.tests;

import java.util.HashSet;
import java.util.Set;

/**
 * Каталог тем: ключи уникальны, у каждого ключа есть стиль и имя,
 * неизвестные ключи схлопываются в тёмную тему.
 */
public class ThemeTests {

    private static int failed = 0;

    public static int run() {
        failed = 0;
        check("themes-count", ru.alexv.anyailive.ui.Themes.count() == 8);

        Set<String> keys = new HashSet<String>();
        for (int i = 0; i < ru.alexv.anyailive.ui.Themes.count(); i++) {
            String k = ru.alexv.anyailive.ui.Themes.keyAt(i);
            check("unique-key:" + k, keys.add(k));
            check("style:" + k, ru.alexv.anyailive.ui.Themes.styleFor(k) != 0);
            check("label:" + k, ru.alexv.anyailive.ui.Themes.labelFor(k) != null
                    && ru.alexv.anyailive.ui.Themes.labelFor(k).length() > 0);
        }

        // Неизвестный и null ключи откатываются к дефолту.
        check("fallback-null", ru.alexv.anyailive.ui.Themes.styleFor(null)
                == ru.alexv.anyailive.ui.Themes.styleFor(ru.alexv.anyailive.ui.Themes.DEFAULT));
        check("fallback-junk", ru.alexv.anyailive.ui.Themes.styleFor("no-such-theme")
                == ru.alexv.anyailive.ui.Themes.styleFor(ru.alexv.anyailive.ui.Themes.DEFAULT));
        check("label-fallback", ru.alexv.anyailive.ui.Themes.labelFor("junk").equals(
                ru.alexv.anyailive.ui.Themes.labelFor(ru.alexv.anyailive.ui.Themes.DEFAULT)));

        if (failed > 0) {
            throw new AssertionError("ThemeTests failed checks: " + failed);
        }
        return 8;
    }

    private static void check(String name, boolean ok) {
        if (!ok) {
            failed++;
            System.out.println("FAIL " + name);
        }
    }
}
