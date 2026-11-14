package ru.alexv.anyailive.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ru.alexv.anyailive.data.ChatStore;
import ru.alexv.anyailive.data.Model;
import ru.alexv.anyailive.json.Json;
import ru.alexv.anyailive.net.Http;
import ru.alexv.anyailive.net.SseParser;

/**
 * Текстовый чат через {baseUrl}/chat/completions.
 *
 * Два режима:
 *  - {@link #stream} — потоковый, дельты отдаются в {@link Listener#onDelta} по мере прихода;
 *  - {@link #complete} — синхронный, для каскадного движка (STT → чат → TTS), ждёт весь ответ.
 *
 * Сборка запроса и разбор ответа вынесены в статические методы без android.* — их проверяет
 * {@code tests/ChatTests.java} на обычной JVM.
 */
public class ChatClient {

    public interface Listener {
        void onDelta(String t);

        void onDone(String full);

        void onError(String msg);
    }

    // ---------------------------------------------------------------- потоковый режим

    /** Стрим с системной подсказкой. Возвращает управление, когда поток закрылся. */
    public void stream(Model model, String apiKey, List<ChatStore.Msg> history, Listener l, long timeoutMs) {
        stream(model, apiKey, null, history, l, timeoutMs);
    }

    /** Стрим: дельты копятся и отдаются в onDelta, в конце — onDone с полным текстом. */
    public void stream(Model model, String apiKey, String systemPrompt, List<ChatStore.Msg> history,
                       Listener l, long timeoutMs) {
        if (l == null) {
            return;
        }
        if (model == null) {
            l.onError("Модель не задана");
            return;
        }
        String url = Providers.chatUrl(model.baseUrl);
        if (url == null) {
            l.onError("Не задан адрес API (Base URL)");
            return;
        }
        final Listener cb = l;
        final String body = buildBody(model.modelId, systemPrompt, history, true);
        final StringBuilder full = new StringBuilder();
        final String[] err = new String[]{null};
        final SseParser sse = new SseParser();

        Http.streamJson(url, Providers.headers(model, apiKey), body, new Http.Callback() {
            @Override
            public void onJson(String chunk) {
                List<String> payloads = payloads(chunk, sse);
                for (int i = 0; i < payloads.size(); i++) {
                    String delta = extractDelta(payloads.get(i));
                    if (delta != null && delta.length() > 0) {
                        full.append(delta);
                        cb.onDelta(delta);
                    }
                }
            }

            @Override
            public void onError(String message) {
                err[0] = message;
            }
        }, timeoutMs <= 0 ? 120000 : (int) timeoutMs);

        if (err[0] != null) {
            cb.onError(err[0]);
            return;
        }
        if (full.length() == 0) {
            cb.onError("Пустой ответ от модели");
            return;
        }
        cb.onDone(full.toString());
    }

    // ---------------------------------------------------------------- синхронный режим

    /** Полный ответ ассистента одним куском. null — если запрос не удался. */
    public String complete(Model model, String apiKey, List<ChatStore.Msg> history, long timeoutMs) {
        return complete(model, apiKey, null, history, timeoutMs);
    }

    /** Полный ответ ассистента одним куском (stream:false). null — если запрос не удался. */
    public String complete(Model model, String apiKey, String systemPrompt,
                           List<ChatStore.Msg> history, long timeoutMs) {
        if (model == null) {
            return null;
        }
        String url = Providers.chatUrl(model.baseUrl);
        if (url == null) {
            return null;
        }
        String body = buildBody(model.modelId, systemPrompt, history, false);
        Http.Response r = Http.postJson(url, Providers.headers(model, apiKey), body,
                timeoutMs <= 0 ? 120000 : (int) timeoutMs);
        if (r.error != null || !r.ok()) {
            return null;
        }
        return extractMessage(r.body);
    }

    // ---------------------------------------------------------------- чистые функции

    /**
     * Тело запроса /chat/completions. Роли: system (один раз первым), user, assistant.
     * history — то, что уже лежит в чате; systemPrompt добавляется перед ней, если не пуст.
     */
    public static String buildBody(String modelId, String systemPrompt,
                                   List<ChatStore.Msg> history, boolean stream) {
        StringBuilder msgs = new StringBuilder();
        if (systemPrompt != null && systemPrompt.length() > 0) {
            appendMessage(msgs, "system", systemPrompt);
        }
        if (history != null) {
            for (int i = 0; i < history.size(); i++) {
                ChatStore.Msg m = history.get(i);
                if (m == null) {
                    continue;
                }
                String role = m.isUser() ? "user" : ("system".equals(m.role) ? "system" : "assistant");
                appendMessage(msgs, role, m.content == null ? "" : m.content);
            }
        }
        return Json.write()
                .put("model", modelId == null ? "" : modelId)
                .put("stream", stream)
                .putRaw("messages", "[" + msgs + "]")
                .done();
    }

    private static void appendMessage(StringBuilder b, String role, String content) {
        if (b.length() > 0) {
            b.append(',');
        }
        b.append(Json.write().put("role", role).put("content", content).done());
    }

    /**
     * Разбирает то, что пришло от {@link Http#streamJson}, в список JSON-полезных нагрузок.
     * Http отдаёт уже готовые строки, но если в чанке оказалось несколько строк (или пришёл
     * сырой SSE-блок), делим по '\n' и кормим SseParser.
     */
    public static List<String> payloads(String chunk, SseParser parser) {
        List<String> out = new ArrayList<String>();
        if (chunk == null) {
            return out;
        }
        SseParser p = parser == null ? new SseParser() : parser;
        if (chunk.indexOf('\n') < 0) {
            String s = chunk.trim();
            if (s.isEmpty()) {
                return out;
            }
            String payload = s.startsWith("data:") ? p.feed(s) : s;
            if (payload != null && payload.length() > 0) {
                out.add(payload);
            }
            return out;
        }
        String[] lines = chunk.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].replace("\r", "");
            String payload = p.feed(line);
            if (payload != null && payload.length() > 0) {
                out.add(payload);
            }
        }
        return out;
    }

    /** choices[0].delta.content потокового ответа; null — если дельты с текстом нет. */
    public static String extractDelta(String json) {
        Map<String, Object> first = firstChoice(json);
        if (first == null) {
            return null;
        }
        Map<String, Object> delta = Json.asObj(first.get("delta"));
        if (delta == null) {
            delta = Json.asObj(first.get("message"));
        }
        if (delta == null) {
            return textOf(first.get("text"));
        }
        return textOf(delta.get("content"));
    }

    /** choices[0].message.content обычного (не потокового) ответа. */
    public static String extractMessage(String json) {
        Map<String, Object> first = firstChoice(json);
        if (first == null) {
            return null;
        }
        Map<String, Object> message = Json.asObj(first.get("message"));
        String s = message == null ? null : textOf(message.get("content"));
        if (s == null) {
            s = textOf(first.get("text"));
        }
        if (s == null) {
            Map<String, Object> root = Json.obj(json);
            s = root == null ? null : textOf(root.get("output_text"));
        }
        return s;
    }

    private static Map<String, Object> firstChoice(String json) {
        Map<String, Object> root = Json.obj(json);
        if (root == null) {
            return null;
        }
        List<Object> choices = Json.asList(root.get("choices"));
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        return Json.asObj(choices.get(0));
    }

    /** content бывает строкой либо списком частей ({"type":"text","text":"..."}) у части шлюзов. */
    private static String textOf(Object content) {
        if (content == null) {
            return null;
        }
        if (content instanceof List) {
            StringBuilder b = new StringBuilder();
            List<Object> parts = Json.asList(content);
            for (int i = 0; i < parts.size(); i++) {
                Map<String, Object> part = Json.asObj(parts.get(i));
                String t = part == null ? Json.str(parts.get(i)) : Json.str(part.get("text"));
                if (t != null) {
                    b.append(t);
                }
            }
            return b.length() == 0 ? null : b.toString();
        }
        return Json.str(content);
    }
}
