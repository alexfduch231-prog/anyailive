import java.util.LinkedHashMap;
import java.util.Map;

import ru.alexv.anyailive.net.Http;

/** Живая проверка Http-слоя на реальном эндпоинте (тот же код, что пойдёт в apk). */
public class ProbeHttp {
    public static void main(String[] args) {
        String key = System.getenv("GEMINI_API_KEY");
        if (key == null || key.isEmpty()) {
            System.out.println("SKIP: no GEMINI_API_KEY");
            return;
        }
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("x-goog-api-key", key);
        Http.Response r = Http.get("https://generativelanguage.googleapis.com/v1beta/models", h, 20000);
        System.out.println("HTTP get code=" + r.code + " err=" + r.error + " bodyLen=" + r.body.length());
        System.out.println("HTTP hasLiveModels=" + r.body.contains("bidiGenerateContent"));

        // ошибка 4xx должна приходить телом, а не исключением
        Map<String, String> bad = new LinkedHashMap<String, String>();
        bad.put("x-goog-api-key", "wrong-key");
        Http.Response r2 = Http.get("https://generativelanguage.googleapis.com/v1beta/models", bad, 20000);
        System.out.println("HTTP badKey code=" + r2.code + " err=" + r2.error + " bodyHasError=" + r2.body.contains("error"));

        // POST с JSON-телом
        Map<String, String> h3 = new LinkedHashMap<String, String>();
        h3.put("x-goog-api-key", key);
        String body = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"Скажи ровно: ок\"}]}]}";
        Http.Response r3 = Http.postJson("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent", h3, body, 40000);
        System.out.println("HTTP postJson code=" + r3.code + " err=" + r3.error + " hasCandidates=" + r3.body.contains("candidates"));
        System.out.println("HTTP postBody=" + (r3.body.length() > 300 ? r3.body.substring(0, 300) : r3.body));
    }
}
