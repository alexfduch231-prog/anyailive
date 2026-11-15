package ru.alexv.anyailive.net;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;

import java.util.Map;
import java.util.TreeMap;

import ru.alexv.anyailive.data.AppUpdate;

/**
 * Проверка обновлений через GitHub Releases: в фоне читает
 * api.github.com/.../releases/latest и предлагает открыть страницу загрузки.
 */
public final class Updater {

    public static final String RELEASES_LATEST =
            "https://api.github.com/repos/alexfduch231-prog/anyailive/releases/latest";
    public static final String RELEASES_PAGE =
            "https://github.com/alexfduch231-prog/anyailive/releases/latest";

    public interface Listener {
        /** Вызывается в UI-потоке. tag — тег нового релиза, url — прямая ссылка на APK. */
        void onUpdate(String tag, String url);

        /** Вызывается в UI-потоке. Обновлений нет или проверка не удалась. */
        void onNone(boolean wasError);
    }

    private Updater() { }

    /**
     * Проверить в фоне и доложить в UI-поток.
     * @param currentVersionName версия текущей сборки (versionName из PackageInfo).
     */
    public static void check(final Handler main, final String currentVersionName,
                             final Listener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String tag = null;
                String url = null;
                boolean error = false;
                try {
                    Map<String, String> headers = new TreeMap<String, String>();
                    headers.put("Accept", "application/vnd.github+json");
                    headers.put("User-Agent", "anyailive");
                    Http.Response r = Http.get(RELEASES_LATEST, headers, 10000);
                    if (r.ok()) {
                        Map<String, Object> rel = AppUpdate.latestStable(r.body);
                        String t = AppUpdate.tag(rel);
                        String u = AppUpdate.apkUrl(rel);
                        if (t != null && AppUpdate.isNewer(t, currentVersionName)) {
                            tag = t;
                            url = u != null ? u : RELEASES_PAGE;
                        }
                    } else {
                        error = true;
                    }
                } catch (Exception e) {
                    error = true;
                }
                final String fTag = tag;
                final String fUrl = url;
                final boolean fError = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fTag != null) listener.onUpdate(fTag, fUrl);
                        else listener.onNone(fError);
                    }
                });
            }
        }, "anyailive-update-check").start();
    }

    /** Открыть страницу релизов / прямую ссылку на APK в браузере. */
    public static void openDownload(Activity act, String url) {
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
            // нет браузера — молча
        }
    }
}
