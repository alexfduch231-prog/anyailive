package ru.alexv.anyailive.tests;

import java.util.List;
import java.util.Map;

import ru.alexv.anyailive.data.AppUpdate;

/**
 * Чистая логика проверки обновлений по GitHub Releases:
 * выбор свежего стабильного релиза, ссылка на APK, сравнение версий.
 */
public class AppUpdateTests {

    private static int failed = 0;

    private static final String RELEASES_JSON = "["
        + "{\"tag_name\":\"v0.2.0\",\"name\":\"anyAIlive 0.2.0\",\"draft\":false,\"prerelease\":false,"
        + "\"assets\":[{\"name\":\"anyailive.apk\",\"browser_download_url\":\"https://x/anyailive.apk\"}]},"
        + "{\"tag_name\":\"v0.3.0-rc1\",\"draft\":false,\"prerelease\":true,\"assets\":[]},"
        + "{\"tag_name\":\"v0.1.5\",\"draft\":true,\"prerelease\":false,\"assets\":[]}"
        + "]";

    public static int run() {
        failed = 0;

        Map<String, Object> r = AppUpdate.latestStable(RELEASES_JSON);
        check("latest-found", r != null);
        check("latest-tag", r != null && "v0.2.0".equals(AppUpdate.tag(r)));

        check("empty-null", AppUpdate.latestStable("[]") == null);
        check("junk-null", AppUpdate.latestStable("not json") == null);

        check("apk-url", r != null && "https://x/anyailive.apk".equals(AppUpdate.apkUrl(r)));
        check("apk-url-null-release", AppUpdate.apkUrl(null) == null);
        check("apk-url-empty", AppUpdate.apkUrl(AppUpdate.latestStable("[]")) == null);

        String noApk = "[{\"tag_name\":\"v1.0\",\"draft\":false,\"prerelease\":false,"
            + "\"assets\":[{\"name\":\"src.zip\",\"browser_download_url\":\"https://x/src.zip\"}]}]";
        check("apk-url-skips-non-apk", AppUpdate.apkUrl(AppUpdate.latestStable(noApk)) == null);

        check("newer-minor", AppUpdate.isNewer("v0.2.0", "0.1.0"));
        check("newer-tenth", AppUpdate.isNewer("0.10.0", "0.9.9"));
        check("newer-major", AppUpdate.isNewer("v1.0", "0.1.0"));

        check("same-not-newer", !AppUpdate.isNewer("v0.1.0", "0.1.0"));
        check("older-not-newer", !AppUpdate.isNewer("v0.1.0", "0.2.0"));
        check("empty-tag-not-newer", !AppUpdate.isNewer("", "0.1.0"));
        check("null-tag-not-newer", !AppUpdate.isNewer(null, "0.1.0"));
        check("null-current-newer", AppUpdate.isNewer("v0.2.0", null));

        check("numeric-not-lexicographic", AppUpdate.isNewer("v0.10.0", "0.2.0"));
        check("numeric-not-lexicographic-2", !AppUpdate.isNewer("v0.2.0", "0.10.0"));

        String single = "{\"tag_name\":\"v0.9.0\",\"draft\":false,\"prerelease\":false,"
            + "\"assets\":[{\"name\":\"anyailive.apk\",\"browser_download_url\":\"u1\"}]}";
        Map<String, Object> s = AppUpdate.latestStable(single);
        check("object-shape", s != null && "u1".equals(AppUpdate.apkUrl(s)));

        if (failed > 0) throw new AssertionError("AppUpdateTests: " + failed + " failed");
        return failed;
    }

    private static void check(String name, boolean ok) {
        if (!ok) { failed++; System.out.println("FAIL AppUpdateTests." + name); }
    }
}
