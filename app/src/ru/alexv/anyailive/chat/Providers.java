package ru.alexv.anyailive.chat;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import ru.alexv.anyailive.data.Model;
import ru.alexv.anyailive.json.Json;
import ru.alexv.anyailive.net.Http;
import ru.alexv.anyailive.voice.Ws;

/**
 * Три проверки модели. Каждая выполняется синхронно и возвращает готовый текст для показа:
 * вызывающий обязан увести вызов в фоновый поток (в UI — Thread + runOnUiThread).
 *
 * Все разборы (тело запроса, адреса, тексты ошибок, список live-моделей) вынесены в статические
 * методы без android.* — они проверяются на обычной JVM в {@code tests/ChatTests.java}.
 *
 * Факты про протоколы — из {@code docs/protocol-review/ground-truth.md} и {@code spike-result.md}.
 */
public final class Providers {

    /** Эндпоинт Gemini Live: проверен спайком 2026-11-14 (ключ в query, никаких заголовков). */
    public static final String GEMINI_LIVE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta"
                    + ".GenerativeService.BidiGenerateContent";

    public static final String GEMINI_DEFAULT_BASE = "https://generativelanguage.googleapis.com";

    /** Сколько ждём первого серверного события realtime-сессии. */
    public static final int REALTIME_WAIT_MS = 6000;

    private Providers() {
    }

    /** Результат проверки: годится и для тоста, и для записи в last_check. */
    public static final class Result {
        public final boolean ok;
        public final String message;

        public Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message == null ? "" : message;
        }

        @Override
        public String toString() {
            return message;
        }
    }

    /** Строка для Model.lastCheck: когда проверяли и чем кончилось. */
    public static String stamp(Result r) {
        String when = new java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
                .format(new java.util.Date());
        return when + " · " + (r == null ? "" : r.message);
    }

    // ---------------------------------------------------------------- проверка 1: чат

    /**
     * POST {baseUrl}/chat/completions с max_tokens=1. Отвечает ли модель и принят ли ключ.
     */
    public static Result checkChat(Model m, String apiKey) {
        if (m == null) return new Result(false, "Модель не задана");
        String url = chatUrl(m.baseUrl);
        if (url == null) return new Result(false, "Не задан адрес API (Base URL)");
        long t0 = System.currentTimeMillis();
        Http.Response r = Http.postJson(url, headers(m, apiKey), chatBody(m.modelId), 25000);
        long ms = System.currentTimeMillis() - t0;
        if (r.error != null) return new Result(false, "Нет связи: " + r.error);
        if (r.code < 200 || r.code >= 300) return new Result(false, describeError(r.code, r.body));
        // Часть шлюзов отдаёт 200 и объект error внутри тела.
        Map<String, Object> root = Json.obj(r.body);
        if (root != null && root.get("error") != null) {
            return new Result(false, "Ответ с ошибкой: " + first200(errorMessage(r.body)));
        }
        return new Result(true, "Чат отвечает (" + ms + " мс)");
    }

    /** {baseUrl}/chat/completions; null, если адрес пуст. /chat/completions второй раз не добавляется. */
    public static String chatUrl(String baseUrl) {
        String b = trimSlash(baseUrl);
        if (b.isEmpty()) return null;
        if (b.endsWith("/chat/completions")) return b;
        return b + "/chat/completions";
    }

    /** Тело проверочного запроса: один токен, без стрима. */
    public static String chatBody(String modelId) {
        String msg = Json.write().put("role", "user").put("content", "ping").done();
        return Json.write()
                .put("model", modelId == null ? "" : modelId)
                .putRaw("messages", "[" + msg + "]")
                .put("max_tokens", 1)
                .put("stream", false)
                .done();
    }

    /** Человеческий текст ошибки провайдера. 401/404 — точные строки, иначе код и первые 200 символов. */
    public static String describeError(int code, String body) {
        if (code == 401) return "HTTP 401: неверный ключ";
        if (code == 404) return "HTTP 404: нет такой модели на этом адресе";
        if (code == 403) return "HTTP 403: ключ не даёт доступа к этому адресу";
        if (code == 429) return "HTTP 429: превышен лимит запросов";
        String tail = first200(body);
        if (tail.isEmpty()) return "HTTP " + code;
        return "HTTP " + code + ": " + tail;
    }

    /** Достаёт error.message / message / detail из тела ошибки. null, если ничего нет. */
    public static String errorMessage(String body) {
        Map<String, Object> root = Json.obj(body);
        if (root == null) return body == null ? null : first200(body);
        String s = Json.pathString(root, "error.message");
        if (s != null) return s;
        s = Json.str(root.get("message"));
        if (s != null) return s;
        s = Json.str(root.get("detail"));
        if (s != null) return s;
        Object err = root.get("error");
        if (err != null) return Json.str(err);
        return null;
    }

    /** Первые 200 символов тела одной строкой — чтобы тост не разъезжался. */
    public static String first200(String body) {
        if (body == null) return "";
        String s = body.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        if (s.length() > 200) {
            s = s.substring(0, 200) + "\u2026";
        }
        return s;
    }

    // ---------------------------------------------------------------- заголовки

    /** Authorization из ключа + дополнительные заголовки модели ("Name: value" построчно). */
    public static Map<String, String> headers(Model m, String apiKey) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        if (apiKey != null && apiKey.length() > 0) {
            h.put("Authorization", "Bearer " + apiKey.trim());
        }
        if (m != null && m.headers != null) {
            for (String line : m.headers.split("\n")) {
                String s = line.trim();
                int c = s.indexOf(':');
                if (c <= 0) continue;
                String k = s.substring(0, c).trim();
                String v = s.substring(c + 1).trim();
                if (k.length() > 0) {
                    h.put(k, v);
                }
            }
        }
        return h;
    }

    // ---------------------------------------------------------------- проверка 2: ключ Gemini

    /**
     * GET {baseUrl без /v1beta}/v1beta/models?key=... — принят ли ключ и есть ли у него live-модели.
     */
    public static Result checkGeminiKey(Model m, String apiKey) {
        String url = geminiModelsUrl(m == null ? "" : m.baseUrl, apiKey);
        if (url == null) return new Result(false, "Не задан адрес API (Base URL)");
        Http.Response r = Http.get(url, headers(m, null), 20000);
        if (r.error != null) return new Result(false, "Нет связи: " + r.error);
        if (r.code >= 200 && r.code < 300) {
            List<String> live = parseLiveModels(r.body);
            if (live.isEmpty()) return new Result(true, "Ключ принят, но live-моделей нет");
            int n = Math.min(5, live.size());
            StringBuilder b = new StringBuilder("Ключ принят. Live-модели: ");
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    b.append(", ");
                }
                b.append(live.get(i));
            }
            if (live.size() > n) {
                b.append(" \u2026");
            }
            return new Result(true, b.toString());
        }
        if (r.code == 400 || r.code == 403) return new Result(false, "Ключ не принят");
        return new Result(false, describeError(r.code, r.body));
    }

    /** Адрес списка моделей Gemini: /v1beta из baseUrl срезается, ключ — в query. */
    public static String geminiModelsUrl(String baseUrl, String apiKey) {
        String b = trimSlash(baseUrl);
        if (b.isEmpty()) {
            b = GEMINI_DEFAULT_BASE;
        }
        if (b.endsWith("/v1beta")) {
            b = b.substring(0, b.length() - "/v1beta".length());
        }
        b = trimSlash(b);
        if (b.isEmpty()) return null;
        return b + "/v1beta/models?key=" + urlEncode(apiKey == null ? "" : apiKey);
    }

    /** id моделей из models.list, у которых supportedGenerationMethods содержит bidiGenerateContent. */
    public static List<String> parseLiveModels(String json) {
        List<String> out = new ArrayList<String>();
        Map<String, Object> root = Json.obj(json);
        if (root == null) return out;
        List<Object> data = Json.asList(root.get("models"));
        if (data == null) {
            data = Json.asList(root.get("data"));
        }
        if (data == null) return out;
        for (Object o : data) {
            Map<String, Object> mm = Json.asObj(o);
            if (mm == null) continue;
            List<Object> methods = Json.asList(mm.get("supportedGenerationMethods"));
            if (methods == null) {
                methods = Json.asList(mm.get("supported_generation_methods"));
            }
            boolean bidi = false;
            if (methods != null) {
                for (Object x : methods) {
                    if ("bidiGenerateContent".equals(Json.str(x))) {
                        bidi = true;
                        break;
                    }
                }
            }
            if (!bidi) continue;
            String name = Json.str(mm.get("name"));
            if (name == null) {
                name = Json.str(mm.get("id"));
            }
            if (name == null) continue;
            if (name.startsWith("models/")) {
                name = name.substring("models/".length());
            }
            out.add(name);
        }
        return out;
    }

    // ---------------------------------------------------------------- проверка 3: realtime

    /**
     * Открывает WSS, ждёт первое серверное событие (OpenAI — session.created, Gemini — setupComplete)
     * и закрывает соединение.
     */
    public static Result checkRealtime(Model m, final String apiKey) {
        if (m == null) return new Result(false, "Модель не задана");
        if (!m.live()) {
            return new Result(false, "Live-протокол не выбран: нет / OpenAI Realtime / Gemini Live");
        }
        final boolean gemini = Model.LIVE_GEMINI.equals(m.liveProtocol);
        final String url = realtimeUrl(m, apiKey);
        if (url == null) return new Result(false, "Нет адреса Live: задайте Live URL или Base URL");
        final String liveModel = liveModelOf(m);
        final String setup = gemini ? geminiSetupBody(liveModel) : null;

        final CountDownLatch first = new CountDownLatch(1);
        final String[] got = new String[]{null};
        final String[] err = new String[]{null};
        final Ws[] sock = new Ws[1];

        Ws.Listener listener = new Ws.Listener() {
            @Override
            public void onOpen() {
                // Gemini сам ничего не говорит, пока не получит setup; OpenAI шлёт session.created сам.
                if (setup != null && sock[0] != null) {
                    sock[0].send(setup);
                }
            }

            @Override
            public void onText(String json) {
                if (got[0] == null) {
                    got[0] = json;
                    first.countDown();
                }
            }

            @Override
            public void onClosed(String reason) {
                if (got[0] == null && err[0] == null) {
                    err[0] = "закрыт сервером (" + reason + ")";
                    first.countDown();
                }
            }

            @Override
            public void onError(String message) {
                if (err[0] == null) {
                    err[0] = message;
                }
                first.countDown();
            }
        };

        Ws ws = new Ws();
        sock[0] = ws;
        String connectError = ws.connect(url, wsHeaders(m, apiKey, gemini), listener);
        if (connectError != null) {
            ws.close();
            return new Result(false, "Live не открылся: " + connectError);
        }
        try {
            first.await(REALTIME_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ws.close();

        String text = got[0];
        if (text == null) {
            if (err[0] != null) return new Result(false, "Live не ответил за 6 с: " + err[0]);
            return new Result(false, "Live не ответил за 6 с: канал открыт, событий нет");
        }
        String event = serverEventName(text);
        if (gemini) {
            boolean ok = text.contains("setupComplete") || text.contains("setup_complete")
                    || text.contains("serverContent") || text.contains("server_content");
            return ok
                    ? new Result(true, "Realtime отвечает: " + event)
                    : new Result(false, "Live ответил не тем, что ждали: " + first200(text));
        }
        if (text.contains("session.created") || text.contains("session.updated")) return new Result(true, "Realtime отвечает: " + event);
        // Пришло что-то другое: чаще всего событие "error" (нет модели / ключ не пускает в realtime).
        String detail = errorMessage(text);
        return new Result(false, "Live ответил ошибкой: " + first200(detail == null ? text : detail));
    }

    /** Имя события сервера: у OpenAI это type, у Gemini — имя единственного ключа верхнего уровня. */
    public static String serverEventName(String json) {
        Map<String, Object> root = Json.obj(json);
        if (root == null) return "не JSON";
        String type = Json.str(root.get("type"));
        if (type != null) return type;
        for (String k : root.keySet()) {
            if (root.get(k) != null) return k;
        }
        return "(пусто)";
    }

    /** WSS-адрес Live-сессии: liveUrl, если задан, иначе выводим из baseUrl (OpenAI) или константа (Gemini). */
    public static String realtimeUrl(Model m, String apiKey) {
        if (m == null) return null;
        String explicit = trimSlash(m.liveUrl);
        if (Model.LIVE_GEMINI.equals(m.liveProtocol)) {
            String base = explicit.isEmpty() ? GEMINI_LIVE_URL : wsScheme(explicit);
            String key = apiKey == null ? "" : apiKey.trim();
            if (key.isEmpty() || base.contains("key=")) return base;
            return base + (base.indexOf('?') >= 0 ? "&" : "?") + "key=" + urlEncode(key);
        }
        String liveModel = liveModelOf(m);
        if (!explicit.isEmpty()) return withModel(wsScheme(explicit), liveModel);
        String base = trimSlash(m.baseUrl);
        if (base.isEmpty()) return null;
        return withModel(wsScheme(base) + "/realtime", liveModel);
    }

    /** Заголовки для WSS: у Gemini ключ в query, у OpenAI — Authorization. */
    public static Map<String, String> wsHeaders(Model m, String apiKey, boolean gemini) {
        if (gemini) return headers(m, null);
        return headers(m, apiKey);
    }

    /** Минимальный setup для Gemini Live: без него сервер молчит (спайк 2026-11-14). */
    public static String geminiSetupBody(String liveModelId) {
        String model = liveModelId == null ? "" : liveModelId.trim();
        if (model.isEmpty()) {
            model = "models/gemini-3.8-live";
        } else if (!model.startsWith("models/")) {
            model = "models/" + model;
        }
        String setup = Json.write().put("model", model).done();
        return Json.write().putRaw("setup", setup).done();
    }

    /** liveModelId, если задан, иначе обычный modelId. */
    public static String liveModelOf(Model m) {
        if (m == null) return "";
        if (m.liveModelId != null && m.liveModelId.trim().length() > 0) return m.liveModelId.trim();
        return m.modelId == null ? "" : m.modelId.trim();
    }

    // ---------------------------------------------------------------- мелочи

    /** http→ws, https→wss; уже ws/wss не трогаем. */
    public static String wsScheme(String url) {
        if (url == null) return "";
        String s = url.trim();
        if (s.startsWith("https://")) return "wss://" + s.substring("https://".length());
        if (s.startsWith("http://")) return "ws://" + s.substring("http://".length());
        return s;
    }

    static String withModel(String url, String model) {
        if (url == null || url.isEmpty() || model == null || model.isEmpty()) return url;
        if (url.contains("model=")) return url;
        return url + (url.indexOf('?') >= 0 ? "&" : "?") + "model=" + urlEncode(model);
    }

    static String trimSlash(String s) {
        if (s == null) return "";
        String r = s.trim();
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    /** Минимальное кодирование для query: ключи и id моделей, спецсимволов в них почти не бывает. */
    static String urlEncode(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || c == '/' || c == ':';
            if (safe) {
                b.append(c);
                continue;
            }
            byte[] bytes;
            try {
                bytes = String.valueOf(c).getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                continue;
            }
            for (byte x : bytes) {
                b.append('%');
                String h = Integer.toHexString(x & 0xFF).toUpperCase();
                if (h.length() == 1) {
                    b.append('0');
                }
                b.append(h);
            }
        }
        return b.toString();
    }
}
