package ru.alexv.voicebridge.tests;

import java.util.List;
import java.util.Map;

import ru.alexv.voicebridge.json.Json;

/** Проверки своего JSON: разбор реальных ответов серверов и запись запросов. Чистый Java, идёт на JVM. */
public final class JsonTests {

    private static int bad;
    private static int total;

    private static void ok(String name, boolean cond) {
        total++;
        if (cond) {
            System.out.println("ok   " + name);
        } else {
            System.out.println("FAIL " + name);
            bad++;
        }
    }

    public static void run() {
        // 1. Ответ Gemini: вложенность до base64-аудио.
        String gemini = "{\"serverContent\":{\"modelTurn\":{\"parts\":[{\"inlineData\":"
                + "{\"mimeType\":\"audio/pcm;rate=24000\",\"data\":\"AAAA\"}}]}}}";
        Map<String, Object> g = Json.obj(gemini);
        ok("json.gemini.root", g != null);
        Map<String, Object> sc = Json.pathObj(g, "serverContent.modelTurn");
        ok("json.gemini.path", sc != null);
        List<Object> parts = Json.asList(sc.get("parts"));
        ok("json.gemini.parts", parts != null && parts.size() == 1);
        Map<String, Object> in = Json.asObj(Json.asObj(parts.get(0)).get("inlineData"));
        ok("json.gemini.data", "AAAA".equals(Json.str(in.get("data"))));
        ok("json.gemini.mime", "audio/pcm;rate=24000".equals(Json.str(in.get("mimeType"))));

        // 2. Ответ OpenAI-совместимого чата: выбор строки из массива.
        String chat = "{\"choices\":[{\"delta\":{\"content\":\"при\"},\"index\":0}]}";
        Map<String, Object> c = Json.obj(chat);
        List<Object> choices = Json.asList(c.get("choices"));
        ok("json.chat.choices", choices != null && choices.size() == 1);
        ok("json.chat.delta",
                "при".equals(Json.pathString(Json.asObj(choices.get(0)), "delta.content")));

        // 3. Ошибка провайдера: вложенное сообщение.
        Map<String, Object> err = Json.obj("{\"error\":{\"message\":\"Incorrect API key\",\"code\":401}}");
        ok("json.error.message", "Incorrect API key".equals(Json.pathString(err, "error.message")));

        // 4. Экранирование и обратный разбор.
        String text = "он сказал \"да\"\nи\tушёл\\...";
        String json = Json.write().put("text", text).put("n", 42).put("b", true).done();
        Map<String, Object> back = Json.obj(json);
        ok("json.write.roundtrip", back != null && text.equals(Json.str(back.get("text"))));
        ok("json.write.number", back != null && "42".equals(Json.str(back.get("n"))));
        ok("json.write.bool", back != null && Boolean.TRUE.equals(back.get("b")));

        // 5. Вложенный сырой JSON (как session.update с audio внутри).
        String inner = Json.write().put("type", "realtime").done();
        String outer = Json.write().put("type", "session.update").putRaw("session", inner).done();
        ok("json.write.raw", outer.contains("\"session\":{\"type\":\"realtime\"}"));

        // 6. Мусор не должен валить приложение.
        ok("json.garbage", Json.obj("не json") == null);
        ok("json.empty", Json.asObj(Json.parse("null")) == null);

        // 7. Юникод-эскейпы из ответа сервера.
        Map<String, Object> uni = Json.obj("{\"t\":\"\\u043f\\u0440\\u0438\\u0432\\u0435\\u0442\"}");
        ok("json.unicode", "привет".equals(Json.str(uni.get("t"))));

        if (bad > 0) {
            System.out.println("FAILED " + bad + " checks");
            System.exit(1);
        }
        System.out.println("PASS " + total + " checks");
    }

    public static void main(String[] args) {
        run();
    }
}
