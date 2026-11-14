package ru.alexv.anyailive.voice;

import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;

import java.util.List;
import java.util.Map;

/**
 * Тонкая обёртка над nv-websocket-client.
 *
 * Важно (проверено спайком 2026-11-14): Gemini Live присылает JSON бинарными фреймами,
 * OpenAI Realtime - текстовыми. Поэтому оба вида фреймов декодируются как UTF-8 JSON
 * и отдаются в один и тот же onText.
 */
public class Ws {

    public interface Listener {
        void onOpen();

        void onText(String json);

        void onClosed(String reason);

        void onError(String message);
    }

    private final WebSocketFactory factory = new WebSocketFactory();
    private WebSocket ws;
    private volatile boolean closed;

    public Ws() {
        factory.setConnectionTimeout(15000);
        factory.setSocketTimeout(60000);
    }

    /** Блокирующее соединение: возвращает null при успехе, иначе текст ошибки. */
    public String connect(String url, Map<String, String> headers, final Listener l) {
        try {
            ws = factory.createSocket(url);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    ws.addHeader(e.getKey(), e.getValue());
                }
            }
            ws.addListener(new WebSocketAdapter() {
                @Override
                public void onConnected(WebSocket w, Map<String, List<String>> h) {
                    if (l != null) {
                        l.onOpen();
                    }
                }

                @Override
                public void onTextMessage(WebSocket w, String text) {
                    if (l != null) {
                        l.onText(text);
                    }
                }

                @Override
                public void onBinaryMessage(WebSocket w, byte[] data) {
                    String text;
                    try {
                        text = new String(data, "UTF-8");
                    } catch (Exception e) {
                        return;
                    }
                    if (l != null) {
                        l.onText(text);
                    }
                }

                @Override
                public void onError(WebSocket w, WebSocketException cause) {
                    if (l != null) {
                        l.onError(cause == null ? "websocket error" : cause.getMessage());
                    }
                }

                @Override
                public void onDisconnected(WebSocket w, WebSocketFrame server,
                                           WebSocketFrame client, boolean closedByServer) {
                    if (closed) {
                        return;
                    }
                    String reason = server == null ? "" : server.getCloseReason();
                    int code = server == null ? -1 : server.getCloseCode();
                    if (l != null) {
                        l.onClosed("code=" + code + " " + reason);
                    }
                }
            });
            ws.connect();
            return null;
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    public boolean isOpen() {
        return ws != null && ws.isOpen();
    }

    public void send(String json) {
        if (isOpen()) {
            ws.sendText(json);
        }
    }

    public void close() {
        closed = true;
        if (ws != null) {
            try {
                ws.disconnect();
            } catch (Exception ignored) {
                // соединение уже могло умереть — это не ошибка
            }
        }
    }
}
