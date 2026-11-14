package ru.alexv.anyailive.net;

/**
 * Разбор потока text/event-stream по строкам.
 * Чистый Java: тестируется на обычной JVM, без Android.
 */
public class SseParser {

    /** @return JSON-полезная нагрузка строки, либо null если строку нужно пропустить. */
    public String feed(String line) {
        if (line == null) {
            return null;
        }
        String s = line.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.charAt(0) == ':') {
            return null;
        }
        if (!s.startsWith("data:")) {
            return null;
        }
        String payload = s.substring(5).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return null;
        }
        return payload;
    }

    /** Строка-терминатор потока: {@code data: [DONE]}. */
    public boolean isDone(String line) {
        if (line == null) {
            return false;
        }
        String s = line.trim();
        if (!s.startsWith("data:")) {
            return false;
        }
        return "[DONE]".equals(s.substring(5).trim());
    }
}
