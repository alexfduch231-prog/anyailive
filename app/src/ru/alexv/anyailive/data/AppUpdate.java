package ru.alexv.anyailive.data;

import java.util.List;
import java.util.Map;

import ru.alexv.anyailive.json.Json;

/**
 * Чистая логика проверки обновлений по GitHub Releases.
 * Никаких зависимостей от android — гоняется в JVM-тестах.
 */
public final class AppUpdate {

    private AppUpdate() { }

    /** Первый стабильный (не draft, не prerelease) релиз из ответа /releases или /releases/latest. */
    public static Map<String, Object> latestStable(String json) {
        Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (Exception e) {
            return null;
        }
        List<Object> items = asList(parsed);
        if (items == null) {
            // /releases/latest возвращает объект, а не массив.
            Map<String, Object> one = Json.asObj(parsed);
            if (one == null || truthy(one.get("draft")) || truthy(one.get("prerelease"))) return null;
            return one;
        }
        for (Object o : items) {
            Map<String, Object> rel = Json.asObj(o);
            if (rel == null || truthy(rel.get("draft")) || truthy(rel.get("prerelease"))) continue;
            return rel;
        }
        return null;
    }

    /** tag_name релиза, null если нет. */
    public static String tag(Map<String, Object> release) {
        if (release == null) return null;
        Object t = release.get("tag_name");
        return t instanceof String ? (String) t : null;
    }

    /** Ссылка на anyailive.apk в ассетах релиза, null если ассета нет. */
    public static String apkUrl(Map<String, Object> release) {
        if (release == null) return null;
        List<Object> assets = asList(release.get("assets"));
        if (assets == null) return null;
        for (Object o : assets) {
            Map<String, Object> a = Json.asObj(o);
            if (a == null) continue;
            Object name = a.get("name");
            Object url = a.get("browser_download_url");
            if (name instanceof String && url instanceof String
                    && ((String) name).toLowerCase().endsWith(".apk")) {
                return (String) url;
            }
        }
        return null;
    }

    /**
     * Тег новее текущей версии? Сравнение численное по сегментам, ведущий "v" игнорируется.
     * "v0.10.0" > "0.2.0"; некорректные входы дают false (кроме null-текущей — обновление доступно).
     */
    public static boolean isNewer(String tag, String currentVersionName) {
        long[] a = segments(tag);
        long[] b = segments(currentVersionName);
        if (a == null) return false;
        if (b == null) return true;
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            long x = i < a.length ? a[i] : 0;
            long y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    /** "v0.1.0-rc" → {0,1,0}; мусор → null. */
    private static long[] segments(String version) {
        if (version == null) return null;
        String v = version.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        if (v.isEmpty()) return null;
        String head = v;
        int dash = head.indexOf('-');
        if (dash >= 0) head = head.substring(0, dash);
        String[] parts = head.split("\\.");
        long[] out = new long[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i].trim();
                if (p.isEmpty()) return null;
                out[i] = Long.parseLong(p);
                if (out[i] < 0) return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        if (!(o instanceof List)) return null;
        return (List<Object>) o;
    }

    private static boolean truthy(Object o) {
        return Boolean.TRUE.equals(o);
    }
}
