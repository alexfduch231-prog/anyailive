package ru.alexv.voicebridge.voice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

import ru.alexv.voicebridge.json.Json;

/**
 * Транспорт каскада: адрес, заголовки, multipart для распознавания, JSON для синтеза
 * и POST с бинарным ответом (PCM от /audio/speech не проходит через net.Http — там тело
 * читается как строка UTF-8 и портит байты).
 *
 * Класс чистый Java (без android.*) — покрывается тестами на JVM.
 */
public final class HttpWire {

    private HttpWire() {
    }

    /** Результат запроса: код (0 — сеть не ответила), тело как байты и как текст. */
    public static final class Bin {
        public int code;
        public byte[] data = new byte[0];
        public String text = "";

        public boolean ok() {
            return code >= 200 && code < 300;
        }

        @Override
        public String toString() {
            return code + " " + text;
        }
    }

    /** http://host/v1/ + /audio/speech -> http://host/v1/audio/speech. */
    public static String url(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    public static byte[] utf8(String s) {
        try {
            return s == null ? new byte[0] : s.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return new byte[0];
        }
    }

    public static String boundary() {
        return "----voicebridge" + Long.toHexString(System.nanoTime());
    }

    /** multipart/form-data вручную: текстовые поля + файл. */
    public static byte[] multipart(String boundary, String fileField, String filename,
                                   String fileType, byte[] file, Map<String, String> fields) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            if (fields != null) {
                for (Map.Entry<String, String> e : fields.entrySet()) {
                    if (e.getValue() == null) {
                        continue;
                    }
                    bos.write(utf8("--" + boundary + "\r\n"));
                    bos.write(utf8("Content-Disposition: form-data; name=\"" + e.getKey()
                            + "\"\r\n\r\n"));
                    bos.write(utf8(e.getValue()));
                    bos.write(utf8("\r\n"));
                }
            }
            bos.write(utf8("--" + boundary + "\r\n"));
            bos.write(utf8("Content-Disposition: form-data; name=\"" + fileField
                    + "\"; filename=\"" + filename + "\"\r\n"));
            bos.write(utf8("Content-Type: " + fileType + "\r\n\r\n"));
            if (file != null) {
                bos.write(file);
            }
            bos.write(utf8("\r\n--" + boundary + "--\r\n"));
        } catch (IOException e) {
            return new byte[0];
        }
        return bos.toByteArray();
    }

    /** Тело запроса к /audio/speech: pcm 24 кГц, чтобы играть без пересчёта. */
    public static String ttsJson(String model, String voice, String input) {
        return Json.write()
                .put("model", model)
                .put("voice", voice)
                .put("input", input)
                .put("response_format", "pcm")
                .done();
    }

    /** Заголовки: ключ + пользовательские строки "K: V" из настройки модели. */
    public static Map<String, String> headers(String apiKey, String extraLines) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        if (apiKey != null && apiKey.trim().length() > 0) {
            h.put("Authorization", "Bearer " + apiKey.trim());
        }
        if (extraLines != null) {
            for (String line : extraLines.split("\n")) {
                String s = line.trim();
                int i = s.indexOf(':');
                if (i > 0) {
                    h.put(s.substring(0, i).trim(), s.substring(i + 1).trim());
                }
            }
        }
        return h;
    }

    /** POST с произвольным телом; ошибки не вылетают, а приходят в {@link Bin}. */
    public static Bin post(String url, Map<String, String> headers, String contentType, byte[] body,
                           int timeoutMs) {
        Bin r = new Bin();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(Math.min(Math.max(timeoutMs, 1000), 15000));
            c.setReadTimeout(Math.max(timeoutMs, 1000));
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            c.setRequestProperty("Accept", "*/*");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        c.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            OutputStream os = c.getOutputStream();
            try {
                os.write(body == null ? new byte[0] : body);
                os.flush();
            } finally {
                os.close();
            }
            r.code = c.getResponseCode();
            InputStream is = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.data = readAll(is);
            r.text = new String(r.data, "UTF-8");
            return r;
        } catch (IOException e) {
            r.code = 0;
            r.text = e.getMessage() == null ? "сеть недоступна" : e.getMessage();
            return r;
        } catch (RuntimeException e) {
            r.code = 0;
            r.text = e.getMessage() == null ? "ошибка соединения" : e.getMessage();
            return r;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    public static byte[] readAll(InputStream is) {
        if (is == null) {
            return new byte[0];
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (true) {
                int n = is.read(buf);
                if (n < 0) {
                    break;
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        } finally {
            try {
                is.close();
            } catch (IOException ignored) {
                // поток уже закрыт
            }
        }
    }
}
