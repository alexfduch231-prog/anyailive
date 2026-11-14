package ru.alexv.anyailive.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ru.alexv.anyailive.chat.ChatClient;
import ru.alexv.anyailive.chat.Providers;
import ru.alexv.anyailive.data.ChatStore;
import ru.alexv.anyailive.data.Model;
import ru.alexv.anyailive.json.Json;
import ru.alexv.anyailive.net.SseParser;

/**
 * Тесты чистой логики чата: сборка тела запроса, разбор потока, разбор ошибок провайдера,
 * адреса проверок. Ничего из android.* не трогаем — гоняется на обычной JVM.
 *
 * Запуск: java -cp build/check-ui ru.alexv.anyailive.tests.ChatTests
 */
public final class ChatTests {

    private static int passed;
    private static int failed;

    private ChatTests() {
    }

    public static void run() {
        passed = 0;
        failed = 0;

        bodyBasics();
        bodySystemPrompt();
        bodyRoles();
        chatBodyShape();
        streamParsing();
        doneMarker();
        payloadsSplitting();
        completeResponse();
        providerErrors();
        errorText();
        first200Rule();
        liveModelsParsing();
        urls();
        realtimeUrls();
        serverEvents();
        geminiSetup();

        System.out.println();
        if (failed == 0) {
            System.out.println("PASS " + passed + " checks");
        } else {
            System.out.println("FAILURES " + failed + "/" + (passed + failed));
        }
    }

    public static void main(String[] args) {
        run();
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- тело запроса

    private static void bodyBasics() {
        String body = ChatClient.buildBody("gpt-4o", null, msgs(), true);
        Map<String, Object> root = Json.obj(body);
        check("body: разбирается как JSON", root != null);
        check("body: model", root != null && "gpt-4o".equals(Json.str(root.get("model"))));
        check("body: stream=true", root != null && Boolean.TRUE.equals(root.get("stream")));
        check("body: stream=false в нестримовом",
                Boolean.FALSE.equals(Json.obj(ChatClient.buildBody("m", null, msgs(), false))
                        .get("stream")));
        check("body: пустая история — пустой список сообщений",
                Json.asList(Json.obj(ChatClient.buildBody("m", null, null, true))
                        .get("messages")).isEmpty());
    }

    private static void bodySystemPrompt() {
        String body = ChatClient.buildBody("m", "ты вежливый", msgs(), true);
        List<Object> list = Json.asList(Json.obj(body).get("messages"));
        check("body: system первым сообщением", list.size() == 3);
        Map<String, Object> first = Json.asObj(list.get(0));
        check("body: роль system", "system".equals(Json.str(first.get("role"))));
        check("body: текст system", "ты вежливый".equals(Json.str(first.get("content"))));
    }

    private static void bodyRoles() {
        List<Object> list = Json.asList(Json.obj(ChatClient.buildBody("m", null, msgs(), true))
                .get("messages"));
        Map<String, Object> user = Json.asObj(list.get(0));
        Map<String, Object> ai = Json.asObj(list.get(1));
        check("body: user→user", "user".equals(Json.str(user.get("role"))));
        check("body: assistant→assistant", "assistant".equals(Json.str(ai.get("role"))));
        check("body: текст сообщений на месте",
                "привет".equals(Json.str(user.get("content")))
                        && "здравствуйте".equals(Json.str(ai.get("content"))));
    }

    private static void chatBodyShape() {
        Map<String, Object> root = Json.obj(Providers.chatBody("gpt-4o"));
        check("checkChat body: model", "gpt-4o".equals(Json.str(root.get("model"))));
        check("checkChat body: max_tokens=1",
                Long.valueOf(1).equals(root.get("max_tokens"))
                        || "1".equals(Json.str(root.get("max_tokens"))));
        check("checkChat body: stream=false", Boolean.FALSE.equals(root.get("stream")));
        List<Object> list = Json.asList(root.get("messages"));
        check("checkChat body: одно сообщение ping",
                list.size() == 1 && "ping".equals(
                        Json.str(Json.asObj(list.get(0)).get("content"))));
    }

    // ---------------------------------------------------------------- разбор потока

    private static void streamParsing() {
        check("дельта: choices[0].delta.content",
                "При".equals(ChatClient.extractDelta(
                        "{\"choices\":[{\"delta\":{\"content\":\"При\"}}]}")));
        check("дельта: пустой delta → null",
                ChatClient.extractDelta("{\"choices\":[{\"delta\":{}}]}") == null);
        check("дельта: нет choices → null", ChatClient.extractDelta("{}") == null);
        check("дельта: не JSON → null", ChatClient.extractDelta("не json") == null);
        check("дельта: content списком частей",
                "ab".equals(ChatClient.extractDelta(
                        "{\"choices\":[{\"delta\":{\"content\":"
                                + "[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\","
                                + "\"text\":\"b\"}]}}]}")));
    }

    private static void doneMarker() {
        check("DONE: не даёт дельты", ChatClient.extractDelta("[DONE]") == null);
        check("DONE: SseParser.isDone", new SseParser().isDone("data: [DONE]"));
        check("DONE: isDone ложно для обычного data",
                !new SseParser().isDone("data: {\"choices\":[]}"));
    }

    private static void payloadsSplitting() {
        SseParser p = new SseParser();
        List<String> one = ChatClient.payloads(
                "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}", p);
        check("поток: одна строка → одна нагрузка", one.size() == 1);
        check("поток: нагрузка без префикса data:",
                one.get(0).startsWith("{") && one.get(0).endsWith("}"));

        SseParser p2 = new SseParser();
        List<String> many = ChatClient.payloads("data: {\"a\":1}\n\ndata: {\"b\":2}\n\n", p2);
        check("поток: два события в чанке", many.size() == 2);
        check("поток: порядок сохранён",
                "{\"a\":1}".equals(many.get(0)) && "{\"b\":2}".equals(many.get(1)));
        check("поток: пустой чанк", ChatClient.payloads(null, null).isEmpty());
        check("поток: чанк из переводов строк",
                ChatClient.payloads("\n\n", new SseParser()).isEmpty());
        check("поток: комментарий sse пропускается",
                ChatClient.payloads(": keep-alive\n\n", new SseParser()).isEmpty());
    }

    private static void completeResponse() {
        check("complete: message.content",
                "pong".equals(ChatClient.extractMessage(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                                + "\"content\":\"pong\"}}]}")));
        check("complete: пустой ответ → null",
                ChatClient.extractMessage("{\"choices\":[]}") == null);
        check("complete: не JSON → null", ChatClient.extractMessage("oops") == null);
    }

    // ---------------------------------------------------------------- ошибки провайдера

    private static void providerErrors() {
        check("401: неверный ключ",
                "HTTP 401: неверный ключ".equals(Providers.describeError(401, "")));
        check("404: нет модели",
                Providers.describeError(404, "").startsWith("HTTP 404"));
        check("500: код и тело",
                "HTTP 500: boom".equals(Providers.describeError(500, "boom")));
        check("429: лимит", Providers.describeError(429, "").contains("429"));
    }

    private static void errorText() {
        check("error.message из OpenAI-тела",
                "Incorrect API key provided".equals(Providers.errorMessage(
                        "{\"error\":{\"message\":\"Incorrect API key provided\",\"type\":"
                                + "\"invalid_request_error\"}}")));
        check("message верхнего уровня",
                "bad model".equals(Providers.errorMessage("{\"message\":\"bad model\"}")));
        check("не JSON: отдаём тело как есть",
                "not found".equals(Providers.errorMessage("not found")));
        check("пустое тело → null", Providers.errorMessage("{}") == null);
    }

    private static void first200Rule() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 260; i++) {
            b.append('x');
        }
        String cut = Providers.first200(b.toString());
        check("first200: обрезка до 200 + многоточие",
                cut.length() == 201 && cut.endsWith("\u2026"));
        check("first200: переводы строк схлопнуты",
                "a b c".equals(Providers.first200("a\nb\tc")));
        check("first200: null → пустая строка", Providers.first200(null).length() == 0);
    }

    private static void liveModelsParsing() {
        String json = "{\"models\":["
                + "{\"name\":\"models/gemini-3.8-live\",\"supportedGenerationMethods\":"
                + "[\"bidiGenerateContent\",\"generateContent\"]},"
                + "{\"name\":\"models/gemini-2.5-pro\",\"supportedGenerationMethods\":"
                + "[\"generateContent\"]},"
                + "{\"name\":\"models/gemini-2.5-flash-native-audio\","
                + "\"supportedGenerationMethods\":[\"bidiGenerateContent\"]}]}";
        List<String> live = Providers.parseLiveModels(json);
        check("live-модели: только с bidiGenerateContent", live.size() == 2);
        check("live-модели: префикс models/ срезан",
                "gemini-3.8-live".equals(live.get(0))
                        && "gemini-2.5-flash-native-audio".equals(live.get(1)));
        check("live-модели: мусор → пусто",
                Providers.parseLiveModels("<html>").isEmpty());
    }

    // ---------------------------------------------------------------- адреса

    private static void urls() {
        check("chatUrl: добавляет путь",
                "https://api.openai.com/v1/chat/completions".equals(
                        Providers.chatUrl("https://api.openai.com/v1/")));
        check("chatUrl: не дублирует путь",
                "https://x/v1/chat/completions".equals(
                        Providers.chatUrl("https://x/v1/chat/completions")));
        check("chatUrl: пусто → null", Providers.chatUrl("") == null
                && Providers.chatUrl(null) == null);
        check("geminiModelsUrl: /v1beta срезан",
                "https://generativelanguage.googleapis.com/v1beta/models?key=k".equals(
                        Providers.geminiModelsUrl(
                                "https://generativelanguage.googleapis.com/v1beta", "k")));
        check("geminiModelsUrl: пустой baseUrl → официальный адрес",
                Providers.geminiModelsUrl("", "k").startsWith(
                        "https://generativelanguage.googleapis.com/v1beta/models?key="));
    }

    private static void realtimeUrls() {
        Model gemini = new Model();
        gemini.liveProtocol = Model.LIVE_GEMINI;
        gemini.baseUrl = "https://generativelanguage.googleapis.com";
        String g = Providers.realtimeUrl(gemini, "KEY");
        check("realtime: Gemini идёт на BidiGenerateContent",
                g.startsWith(Providers.GEMINI_LIVE_URL) && g.endsWith("?key=KEY"));

        Model openai = new Model();
        openai.liveProtocol = Model.LIVE_OPENAI;
        openai.baseUrl = "https://api.openai.com/v1";
        openai.modelId = "gpt-4o";
        openai.liveModelId = "gpt-realtime";
        check("realtime: OpenAI из baseUrl",
                "wss://api.openai.com/v1/realtime?model=gpt-realtime".equals(
                        Providers.realtimeUrl(openai, "k")));
        check("realtime: http→wss",
                "wss://api.openai.com/v1/realtime?model=m".equals(
                        Providers.wsScheme("https://api.openai.com/v1/realtime?model=m")));
        check("realtime: liveUrl имеет приоритет",
                Providers.realtimeUrl(withUrl("wss://example.test/live"), "k")
                        .startsWith("wss://example.test/live?model="));
    }

    private static void serverEvents() {
        check("событие OpenAI: type",
                "session.created".equals(
                        Providers.serverEventName("{\"type\":\"session.created\"}")));
        check("событие Gemini: ключ верхнего уровня",
                "setupComplete".equals(Providers.serverEventName("{\"setupComplete\":{}}")));
        check("событие: не JSON", "не JSON".equals(Providers.serverEventName("<x>")));
    }

    private static void geminiSetup() {
        String setup = Providers.geminiSetupBody("gemini-3.8-live");
        Map<String, Object> root = Json.obj(setup);
        Map<String, Object> inner = Json.asObj(root.get("setup"));
        check("setup: есть setup.model", inner != null
                && "models/gemini-3.8-live".equals(Json.str(inner.get("model"))));
        check("setup: без speechConfig на верхнем уровне",
                inner != null && !inner.containsKey("speechConfig"));
        check("setup: пустой id → модель по умолчанию",
                Providers.geminiSetupBody(null).contains("models/gemini-3.8-live"));
        check("setup: models/ не дублируется",
                Providers.geminiSetupBody("models/x").contains("\"models/x\""));
    }

    // ---------------------------------------------------------------- helpers

    private static Model withUrl(String url) {
        Model m = new Model();
        m.liveProtocol = Model.LIVE_OPENAI;
        m.liveUrl = url;
        m.modelId = "m";
        return m;
    }

    private static List<ChatStore.Msg> msgs() {
        List<ChatStore.Msg> list = new ArrayList<ChatStore.Msg>();
        ChatStore.Msg a = new ChatStore.Msg();
        a.role = "user";
        a.content = "привет";
        ChatStore.Msg b = new ChatStore.Msg();
        b.role = "assistant";
        b.content = "здравствуйте";
        list.add(a);
        list.add(b);
        return list;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("ok   " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name);
        }
    }
}
