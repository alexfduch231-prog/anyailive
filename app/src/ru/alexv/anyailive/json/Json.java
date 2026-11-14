package ru.alexv.anyailive.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Минимальный JSON-парсер и писатель на чистом Java.
 *
 * Почему свой, а не org.json: org.json есть в android.jar, но в тестах на JVM это заглушка
 * (любой вызов бросает RuntimeException("Stub!")), то есть разбор ответов сервера нельзя
 * было бы проверить без устройства. Здесь один код на устройстве и в тестах.
 *
 * Разбирает в Map<String,Object>, List<Object>, String, Long (целые), Double (дробные), Boolean, null.
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        if (text == null) {
            return null;
        }
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        return v;
    }

    /** Удобно, когда нужен объект: при несоответствии вернёт null, а не бросит исключение. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(String text) {
        try {
            Object v = parse(text);
            return v instanceof Map ? (Map<String, Object>) v : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** Достать строку по пути вида "serverContent.modelTurn". null, если путь не сходится. */
    public static String pathString(Map<String, Object> root, String path) {
        Object cur = root;
        for (String part : path.split("\\.")) {
            Map<String, Object> m = asObj(cur);
            if (m == null) {
                return null;
            }
            cur = m.get(part);
            if (cur == null) {
                return null;
            }
        }
        return str(cur);
    }

    public static Map<String, Object> pathObj(Map<String, Object> root, String path) {
        Object cur = root;
        for (String part : path.split("\\.")) {
            Map<String, Object> m = asObj(cur);
            if (m == null) {
                return null;
            }
            cur = m.get(part);
            if (cur == null) {
                return null;
            }
        }
        return asObj(cur);
    }

    private Object value() {
        char c = peek();
        if (c == '{') {
            return object();
        }
        if (c == '[') {
            return array();
        }
        if (c == '"') {
            return string();
        }
        if (c == 't' || c == 'f') {
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            i += 5;
            return Boolean.FALSE;
        }
        if (c == 'n') {
            i += 4;
            return null;
        }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++; // {
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            String k = string();
            ws();
            if (peek() == ':') {
                i++;
            }
            ws();
            m.put(k, value());
            ws();
            char c = peek();
            i++;
            if (c == '}') {
                return m;
            }
            ws();
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<Object>();
        i++; // [
        ws();
        if (peek() == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = peek();
            i++;
            if (c == ']') {
                return l;
            }
            ws();
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++; // "
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n':
                    b.append('\n');
                    break;
                case 't':
                    b.append('\t');
                    break;
                case 'r':
                    b.append('\r');
                    break;
                case 'b':
                    b.append('\b');
                    break;
                case 'f':
                    b.append('\f');
                    break;
                case 'u':
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default:
                    b.append(e);
            }
        }
        return b.toString();
    }

    private Object number() {
        int start = i;
        boolean fractional = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            } else if (c == '-' || c == '+') {
                i++;
            } else if (c == '.' || c == 'e' || c == 'E') {
                fractional = true;
                i++;
            } else {
                break;
            }
        }
        if (i == start) {
            throw new IllegalArgumentException("unexpected char at " + start + ": " + s.charAt(start));
        }
        String num = s.substring(start, i);
        if (!fractional) {
            try {
                return Long.valueOf(num);
            } catch (NumberFormatException ignored) {
                // слишком длинное для long — отдадим как double
            }
        }
        return Double.valueOf(num);
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    // ---------- запись ----------

    /** Собирает JSON-объект: Json.write().put("a", 1).put("b", "x").done(). */
    public static Writer write() {
        return new Writer();
    }

    public static final class Writer {
        private final StringBuilder b = new StringBuilder();

        private Writer() {
            b.append('{');
        }

        private Writer key(String k) {
            if (b.charAt(b.length() - 1) != '{') {
                b.append(',');
            }
            quote(b, k);
            b.append(':');
            return this;
        }

        public Writer put(String k, String v) {
            key(k);
            if (v == null) {
                b.append("null");
            } else {
                quote(b, v);
            }
            return this;
        }

        public Writer put(String k, long v) {
            key(k);
            b.append(v);
            return this;
        }

        public Writer put(String k, boolean v) {
            key(k);
            b.append(v);
            return this;
        }

        public Writer putRaw(String k, String rawJson) {
            key(k);
            b.append(rawJson == null ? "null" : rawJson);
            return this;
        }

        public String done() {
            return b.toString() + "}";
        }
    }

    static void quote(StringBuilder b, String v) {
        b.append('"');
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        String h = Integer.toHexString(c);
                        b.append("\\u");
                        for (int z = h.length(); z < 4; z++) {
                            b.append('0');
                        }
                        b.append(h);
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }
}
