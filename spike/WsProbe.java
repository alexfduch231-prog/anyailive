import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

/** Одноразовый инструмент спайка: WSS-соединение с печатью всего, что приходит и уходит. */
public class WsProbe {

    public interface Handler {
        /** Вызывается сразу после установления соединения: здесь шлётся первый пакет. */
        default void onOpen(Session s) {
        }

        /** Вызывается на каждое текстовое сообщение сервера. */
        void onText(Session s, String msg);
    }

    public static class Session {
        final WebSocket ws;
        private final StringWriter log = new StringWriter();
        private final PrintWriter out = new PrintWriter(log);
        public int received = 0;

        Session(WebSocket ws) {
            this.ws = ws;
        }

        public void send(String json) {
            out.println(">>> " + json);
            out.flush();
            ws.sendText(json);
        }

        public void raw(String what) {
            out.println(what);
            out.flush();
        }

        public String logText() {
            return log.toString();
        }
    }

    /**
     * @return null при успехе, иначе текст ошибки (соединение не установлено).
     */
    public static String run(String url, Map<String, String> headers, final Handler handler,
                             int seconds, String logPath) {
        final Session[] holder = new Session[1];
        String error = null;
        try {
            WebSocketFactory f = new WebSocketFactory();
            f.setConnectionTimeout(10000);
            f.setSocketTimeout(20000);
            WebSocket ws = f.createSocket(url);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                ws.addHeader(e.getKey(), e.getValue());
            }
            final Session s = new Session(ws);
            holder[0] = s;
            ws.addListener(new WebSocketAdapter() {
                @Override
                public void onConnected(WebSocket w, Map<String, List<String>> h) {
                    s.raw("=== connected");
                    System.out.println("=== connected");
                    if (handler != null) {
                        handler.onOpen(s);
                    }
                }

                @Override
                public void onTextMessage(WebSocket w, String msg) {
                    s.received++;
                    s.raw("<<< " + msg);
                    System.out.println("<<< " + msg);
                    if (handler != null) {
                        handler.onText(s, msg);
                    }
                }

                @Override
                public void onBinaryMessage(WebSocket w, byte[] data) {
                    // Gemini Live присылает JSON в бинарных фреймах, OpenAI - в текстовых.
                    onTextMessage(w, new String(data, java.nio.charset.Charset.forName("UTF-8")));
                }

                @Override
                public void onError(WebSocket w, WebSocketException cause) {
                    s.raw("!!! error " + cause.getMessage());
                    System.out.println("!!! error " + cause.getMessage());
                }

                @Override
                public void onDisconnected(WebSocket w, WebSocketFrame server,
                                           WebSocketFrame client, boolean closedByServer) {
                    int code = server == null ? -1 : server.getCloseCode();
                    String reason = server == null ? "" : server.getCloseReason();
                    s.raw("=== disconnected code=" + code + " reason=" + reason
                            + " byServer=" + closedByServer);
                    System.out.println("=== disconnected code=" + code
                            + " reason=" + reason + " byServer=" + closedByServer);
                }
            });
            ws.connect();
            long end = System.currentTimeMillis() + seconds * 1000L;
            while (System.currentTimeMillis() < end) {
                if (!ws.isOpen()) {
                    break;
                }
                Thread.sleep(200);
            }
            if (ws.isOpen()) {
                ws.disconnect();
            }
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            System.out.println("!!! connect failed: " + error);
        }

        StringBuilder all = new StringBuilder();
        if (error != null) {
            all.append("!!! connect failed: ").append(error).append('\n');
        }
        all.append(holder[0] == null ? "(no session)\n" : holder[0].logText());
        try {
            java.io.File f = new java.io.File(logPath);
            if (f.getParentFile() != null) {
                f.getParentFile().mkdirs();
            }
            java.nio.file.Files.write(f.toPath(), all.toString().getBytes("UTF-8"));
        } catch (IOException e) {
            System.out.println("!!! cannot write log: " + e.getMessage());
        }
        return error;
    }
}
