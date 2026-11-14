package ru.alexv.voicebridge.net;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * Тонкая обёртка над HttpURLConnection: без библиотек, работает и на Android, и на JVM.
 * Все методы возвращают Response с кодом, телом и текстом ошибки — исключения наружу не летят.
 */
public final class Http {

    public static class Response {
        public int code;
        public String body = "";
        public String error;

        public boolean ok() {
            return error == null && code >= 200 && code < 300;
        }

        public String toString() {
            return error != null ? ("error " + error) : ("HTTP " + code + " " + body);
        }
    }

    public interface Callback {
        void onJson(String json);

        void onError(String message);
    }

    private Http() {
    }

    public static Response postJson(String url, Map<String, String> headers, String body, int timeoutMs) {
        byte[] bytes;
        try {
            bytes = body == null ? new byte[0] : body.getBytes("UTF-8");
        } catch (IOException e) {
            Response r = new Response();
            r.error = "encoding: " + e.getMessage();
            return r;
        }
        return postBytes(url, headers, "application/json", bytes, timeoutMs);
    }

    public static Response postBytes(String url, Map<String, String> headers, String contentType,
                                     byte[] bytes, int timeoutMs) {
        return request("POST", url, headers, contentType, bytes, timeoutMs);
    }

    public static Response get(String url, Map<String, String> headers, int timeoutMs) {
        return request("GET", url, headers, null, null, timeoutMs);
    }

    private static Response request(String method, String url, Map<String, String> headers,
                                    String contentType, byte[] body, int timeoutMs) {
        Response r = new Response();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("Accept", "application/json");
            if (contentType != null) {
                c.setRequestProperty("Content-Type", contentType);
            }
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                OutputStream os = c.getOutputStream();
                os.write(body);
                os.flush();
                os.close();
            }
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = readAll(in);
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
        return r;
    }

    /** POST и построчная отдача SSE-событий. Таймаут чтения — общий для всего потока. */
    public static void streamJson(String url, Map<String, String> headers, String body,
                                  Callback cb, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            byte[] bytes = body == null ? new byte[0] : body.getBytes("UTF-8");
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("Accept", "text/event-stream");
            c.setRequestProperty("Content-Type", "application/json");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = c.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();

            int code = c.getResponseCode();
            if (code >= 400) {
                String err = readAll(c.getErrorStream());
                cb.onError("HTTP " + code + " " + err);
                return;
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            SseParser parser = new SseParser();
            String line;
            while ((line = br.readLine()) != null) {
                if (parser.isDone(line)) {
                    break;
                }
                String payload = parser.feed(line);
                if (payload != null) {
                    cb.onJson(payload);
                }
            }
        } catch (Exception e) {
            cb.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }
}
