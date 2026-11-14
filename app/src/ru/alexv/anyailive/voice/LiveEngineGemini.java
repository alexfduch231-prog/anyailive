package ru.alexv.anyailive.voice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.alexv.anyailive.data.Model;
import ru.alexv.anyailive.json.Json;

/**
 * Gemini Live (BidiGenerateContent) поверх WebSocket.
 * <p><b>Ключ.</b> Передаётся строкой в конструктор: вызывающая сторона достаёт его из
 * data.SecretStore по model.keyRef. android.content.Context намеренно не используется - класс
 * остаётся чистым Java и проверяется в JVM-тестах. Пустой ключ = ошибка до открытия сокета.
 * <p><b>Потоки.</b> start() проверяет аргументы в вызывающем потоке, соединение открывает фоновый
 * поток "vb-live-gemini", события приходят из потока чтения сокета (один поток) - состояние движка
 * меняется без блокировок; writeLock защищает только буфер микрофона. Handler/Looper не нужны
 * (движок обязан работать в JVM-тестах), UI сам перекидывает колбэки в главный поток.
 * <p><b>Аудио.</b> Вход - PCM16 моно 16 кГц как есть (Gemini принимает 16 кГц, ресемплер не нужен),
 * чанки по 100 мс, слать разрешено только после setupComplete. Выход - PCM16 моно 24 кГц в {@link Sink}.
 * <p><b>Тишина.</b> Сокет умирает примерно на 20-й секунде тишины (спайк: Read timed out), keepalive
 * не предусмотрен - поэтому сторож при простое &gt; 15 с переподключается по sessionResumption.handle.
 */
public class LiveEngineGemini implements VoiceEngine {
    public static final int OTHER = 0, SETUP_COMPLETE = 1, AUDIO = 2, ASSISTANT_TEXT = 3, USER_TEXT = 4;
    public static final int INTERRUPTED = 5, TURN_DONE = 6, GO_AWAY = 7, RESUMPTION = 8, USAGE = 9;
    public static final int ERROR = 10, TOOL_CALL = 11;
    public static final int OUT_RATE = 24000, IN_RATE = 16000;
    public static final String DEFAULT_VOICE = "Puck";
    public static final String DEFAULT_URL = "wss://generativelanguage.googleapis.com/ws/"
            + "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";
    private static final int CHUNK_16K = IN_RATE / 10 * 2, MAX_RECONNECT = 3;
    private static final long IDLE_MS = 15000L, WATCH_MS = 4000L;
    /** mimeType входящего аудио: Gemini принимает 16 кГц без преобразования. */
    public static final String IN_MIME = "audio/pcm;rate=" + IN_RATE;
    /** Приёмник звука и субтитров: тексты - ДЕЛЬТЫ, финальный текст реплики идёт в Callback. */
    public interface Sink {
        void onAudio(byte[] pcm24k);
        /** Пора глушить воспроизведение: модель перебили или ответ отменён. */
        void onBargeIn();
        void onState(int state);
        void onUserText(String delta);
        void onAssistantText(String delta);
        void onError(String message);
    }
    private final Model model;
    private final String apiKey, systemPrompt;
    private final Object writeLock = new Object();
    private final byte[] pending = new byte[CHUNK_16K];
    private final StringBuilder userTurn = new StringBuilder(), assistantTurn = new StringBuilder();
    private volatile Sink sink;
    private volatile Callback cb;
    private volatile Ws ws;
    private volatile boolean running, ready;
    private volatile int state = IDLE;
    private volatile String lastAssistantText = "";
    private volatile long lastActivity = System.currentTimeMillis();
    private int pendingLen, attempts;
    private String handle;
    private long promptTokens, responseTokens, totalTokens, thoughtsTokens;
    public LiveEngineGemini(Model model, String apiKey, String systemPrompt) {
        this.model = model == null ? new Model() : model;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
    }
    public void setSink(Sink s) {
        this.sink = s;
    }
    public String getLastAssistantText() {
        return lastAssistantText;
    }
    /** @return сколько токенов пришло в последнем usageMetadata (prompt). */
    public long getPromptTokens() {
        return promptTokens;
    }
    public long getResponseTokens() {
        return responseTokens;
    }
    public long getTotalTokens() {
        return totalTokens;
    }
    public long getThoughtsTokens() {
        return thoughtsTokens;
    }
    public boolean isRunning() {
        return running;
    }
    // ------------------------------------------------------------------ VoiceEngine

    @Override
    public void start(Callback callback) {
        this.cb = callback;
        if (running) return;
        if (apiKey.length() == 0) {
            fail("Не задан ключ API для Gemini: добавьте его в каталоге моделей.");
            return;
        }
        running = true;
        spawn(0, "vb-live-gemini");
        Thread watch = new Thread(new Runnable() {
            @Override
            public void run() {
                watchIdle();
            }
        }, "vb-live-gemini-watch");
        watch.setDaemon(true);
        watch.start();
    }
    @Override
    public void stop() {
        running = false;
        ready = false;
        synchronized (writeLock) {
            pendingLen = 0;
        }
        Ws w = ws;
        ws = null;
        if (w != null) w.close();
        emitState(IDLE);
    }
    /** Текст в разговор: clientContent с turnComplete - модель отвечает голосом. */
    @Override
    public void sendText(String text) {
        if (text == null || text.trim().length() == 0) return;
        String turn = Json.write().put("role", "user").putRaw("parts",
                "[" + Json.write().put("text", text.trim()).done() + "]").done();
        send(Json.write().putRaw("clientContent", Json.write().putRaw("turns", "[" + turn + "]")
                .put("turnComplete", true).done()).done());
        emitState(THINKING);
    }
    /** Порция микрофона PCM16 моно 16 кГц: копится до 100 мс, уходит как mediaChunks. */
    public void sendAudio(byte[] pcm16k) {
        if (pcm16k == null || pcm16k.length == 0 || !ready) return;
        synchronized (writeLock) {
            int off = 0;
            while (off < pcm16k.length) {
                int n = Math.min(CHUNK_16K - pendingLen, pcm16k.length - off);
                System.arraycopy(pcm16k, off, pending, pendingLen, n);
                pendingLen += n;
                off += n;
                if (pendingLen == CHUNK_16K) flushPending();
            }
        }
    }
    /** Детерминированный перебой: закрываем текущую активность, модель замолкает. */
    public void interrupt() {
        if (ws == null) return;
        send(Json.write().putRaw("realtimeInput", "{\"activityStart\":{}}").done());
        send(Json.write().putRaw("realtimeInput", "{\"activityEnd\":{}}").done());
        assistantTurn.setLength(0);
        emitState(LISTENING);
        Sink s = sink;
        if (s != null) s.onBargeIn();
    }
    // ------------------------------------------------------------------ протокол (static, тестируемое)

    public static String buildSetup(Model m) {
        return buildSetup(m, "", null);
    }
    /**
     * setup строго по спайку: speechConfig ОБЯЗАТЕЛЬНО внутри generationConfig (на верхнем уровне
     * сервер рвёт соединение с 1007), имена camelCase.
     */
    public static String buildSetup(Model m, String systemPrompt, String handle) {
        String gc = Json.write().putRaw("responseModalities", "[\"AUDIO\"]")
                .putRaw("speechConfig", Json.write().putRaw("voiceConfig", Json.write()
                        .putRaw("prebuiltVoiceConfig",
                                Json.write().put("voiceName", voiceOf(m)).done()).done()).done())
                .done();
        Json.Writer setup = Json.write().put("model", modelIdOf(m)).putRaw("generationConfig", gc)
                .putRaw("inputAudioTranscription", "{}")
                .putRaw("outputAudioTranscription", "{}")
                .putRaw("realtimeInputConfig", Json.write().putRaw("automaticActivityDetection",
                        Json.write().put("disabled", false).done()).done());
        String prompt = systemPrompt == null ? "" : systemPrompt.trim();
        if (prompt.length() > 0) {
            setup.putRaw("systemInstruction", Json.write().putRaw("parts",
                    "[" + Json.write().put("text", prompt).done() + "]").done());
        }
        if (handle != null && handle.length() > 0) {
            setup.putRaw("sessionResumption", Json.write().put("handle", handle).done());
        }
        return Json.write().putRaw("setup", setup.done()).done();
    }
    /** Сообщение с аудио: {"realtimeInput":{"mediaChunks":[{mimeType,data}]}}. */
    public static String buildAudioChunk(byte[] pcm16k) {
        String chunk = Json.write().put("mimeType", IN_MIME)
                .put("data", B64.encode(pcm16k == null ? new byte[0] : pcm16k)).done();
        return Json.write().putRaw("realtimeInput", Json.write().putRaw("mediaChunks",
                "[" + chunk + "]").done()).done();
    }
    /** Классификация сообщения сервера: setupComplete, serverContent-*, goAway, usage и т.д. */
    public static int kind(String json) {
        if (json == null) return OTHER;
        Map<String, Object> o = Json.obj(json.trim());
        if (o == null) return OTHER;
        if (pick(o, "setupComplete", "setup_complete") != null) return SETUP_COMPLETE;
        if (pick(o, "goAway", "go_away") != null) return GO_AWAY;
        if (pick(o, "sessionResumptionUpdate", "session_resumption_update") != null) return RESUMPTION;
        if (pick(o, "usageMetadata", "usage_metadata") != null) return USAGE;
        if (pick(o, "toolCall", "tool_call") != null) return TOOL_CALL;
        if (o.get("error") != null) return ERROR;
        Map<String, Object> sc = Json.asObj(pick(o, "serverContent", "server_content"));
        if (sc == null) return OTHER;
        if (isTrue(pick(sc, "interrupted", "interrupted"))) return INTERRUPTED;
        if (isTrue(pick(sc, "turnComplete", "turn_complete"))) return TURN_DONE;
        if (pick(sc, "outputTranscription", "output_transcription") != null) return ASSISTANT_TEXT;
        if (pick(sc, "inputTranscription", "input_transcription") != null) return USER_TEXT;
        if (pick(sc, "modelTurn", "model_turn") != null) return AUDIO;
        return OTHER;
    }
    /** Псевдоним под имя из плана: тесты вызывают и kind(), и eventKind(). */
    public static int eventKind(String json) {
        return kind(json);
    }
    /** liveUrl, если задан, иначе официальный эндпоинт v1beta с ключом в query. */
    public static String liveUrl(Model m, String apiKey) {
        if (m != null) {
            String custom = m.liveUrl == null ? "" : m.liveUrl.trim();
            if (custom.length() > 0) return custom;
        }
        return DEFAULT_URL + "?key=" + (apiKey == null ? "" : apiKey.trim());
    }
    /** Дополнительные заголовки из model.headers ("K: V" построчно); ключ идёт в URL. */
    public static Map<String, String> headers(Model m) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        if (m != null && m.headers != null) {
            for (String line : m.headers.split("\n")) {
                String s = line.trim();
                int i = s.indexOf(':');
                if (i > 0) h.put(s.substring(0, i).trim(), s.substring(i + 1).trim());
            }
        }
        return h;
    }
    // ------------------------------------------------------------------ соединение

    private void connect() {
        if (!running) return;
        Ws old = ws;
        if (old != null) old.close();
        ready = false;
        synchronized (writeLock) {
            pendingLen = 0;
        }
        final Ws w = new Ws();
        ws = w;
        final String url = liveUrl(model, apiKey);
        String err = w.connect(url, headers(model), new Ws.Listener() {
            @Override
            public void onOpen() {
                lastActivity = System.currentTimeMillis();
                w.send(buildSetup(model, systemPrompt, handle));
            }
            @Override
            public void onText(String json) {
                dispatch(json);
            }
            @Override
            public void onClosed(String reason) {
                ready = false;
                if (!running) return;
                if (attempts >= MAX_RECONNECT) {
                    fail("Соединение с Gemini Live закрыто: " + reason);
                } else {
                    resume("закрытие: " + reason);
                }
            }
            @Override
            public void onError(String message) {
                ready = false;
                if (running) fail("Ошибка соединения с Gemini Live: " + message);
            }
        });
        if (err != null && running) {
            // Первый коннект иногда падает на проверке имени в сертификате (сеть/прокси),
            // со второй попытки проходит — не пугаем человека сразу.
            boolean tlsBlip = err.contains("HostnameUnverified") || err.contains("SSLHandshake");
            if (tlsBlip && attempts < MAX_RECONNECT) {
                resume("TLS: " + err);
            } else {
                fail("Не удалось подключиться к Gemini Live (" + url + "): " + err);
            }
        }
    }
    private void spawn(final long delay, String name) {
        final LiveEngineGemini self = this;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                self.connect();
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }
    /** Переподключение с сохранением сессии (sessionResumption.handle), максимум 3 попытки. */
    private void resume(String why) {
        if (!running) return;
        if (attempts >= MAX_RECONNECT) {
            fail("Не удалось восстановить сессию Gemini Live: " + why);
            return;
        }
        attempts++;
        ready = false;
        spawn(400L * attempts, "vb-live-gemini-resume");
    }
    private void watchIdle() {
        while (running) {
            try {
                Thread.sleep(WATCH_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (!running) return;
            if (ready && System.currentTimeMillis() - lastActivity > IDLE_MS) {
                resume("тишина больше 15 с");
            }
        }
    }
    // ------------------------------------------------------------------ разбор событий

    private void dispatch(String json) {
        Map<String, Object> o = Json.obj(json);
        if (o == null) return;
        lastActivity = System.currentTimeMillis();
        int k = kind(json);
        if (k == SETUP_COMPLETE) {
            ready = true;
            attempts = 0;
            emitState(LISTENING);
        } else if (k == AUDIO) {
            onAudio(o);
        } else if (k == ASSISTANT_TEXT) {
            String t = transcript(o, "outputTranscription", "output_transcription");
            if (t.length() > 0) {
                assistantTurn.append(t);
                Sink s = sink;
                if (s != null) s.onAssistantText(t);
            }
        } else if (k == USER_TEXT) {
            String t = transcript(o, "inputTranscription", "input_transcription");
            if (t.length() > 0) {
                userTurn.append(t);
                Sink s = sink;
                if (s != null) s.onUserText(t);
            }
        } else if (k == INTERRUPTED) {
            assistantTurn.setLength(0);
            emitState(LISTENING);
            Sink s = sink;
            if (s != null) s.onBargeIn();
        } else if (k == TURN_DONE) {
            flushTurns();
            emitState(LISTENING);
        } else if (k == GO_AWAY) {
            resume("goAway");
        } else if (k == RESUMPTION) {
            Map<String, Object> u = Json.asObj(pick(o, "sessionResumptionUpdate",
                    "session_resumption_update"));
            String h = u == null ? null : Json.str(pick(u, "newHandle", "new_handle"));
            if (h != null && h.length() > 0) handle = h;
        } else if (k == USAGE) {
            Map<String, Object> u = Json.asObj(pick(o, "usageMetadata", "usage_metadata"));
            if (u != null) {
                promptTokens = num(u, "promptTokenCount", "prompt_token_count");
                responseTokens = num(u, "responseTokenCount", "response_token_count");
                totalTokens = num(u, "totalTokenCount", "total_token_count");
                thoughtsTokens = num(u, "thoughtsTokenCount", "thoughts_token_count");
            }
        } else if (k == ERROR) {
            String m = Json.pathString(o, "error.message");
            if (m == null) m = Json.str(o.get("error"));
            fail(m == null ? "Сервер вернул ошибку без описания." : m);
        }
    }
    /** Аудио модели: serverContent.modelTurn.parts[].inlineData.data (base64, PCM16 24 кГц). */
    private void onAudio(Map<String, Object> o) {
        Map<String, Object> sc = Json.asObj(pick(o, "serverContent", "server_content"));
        Map<String, Object> mt = sc == null ? null : Json.asObj(pick(sc, "modelTurn", "model_turn"));
        List<Object> parts = mt == null ? null : Json.asList(mt.get("parts"));
        if (parts == null) return;
        for (int i = 0; i < parts.size(); i++) {
            Map<String, Object> p = Json.asObj(parts.get(i));
            Map<String, Object> in = p == null ? null
                    : Json.asObj(pick(p, "inlineData", "inline_data"));
            String data = in == null ? null : Json.str(in.get("data"));
            if (data == null || data.length() == 0) continue;
            byte[] pcm;
            try {
                pcm = B64.decode(data);
            } catch (RuntimeException e) {
                continue; // битый base64 одного куска не должен ронять сессию
            }
            if (pcm.length == 0) continue;
            emitState(SPEAKING);
            Sink s = sink;
            if (s != null && running) s.onAudio(pcm);
        }
    }
    private void flushPending() {
        if (pendingLen == 0) return;
        byte[] chunk = new byte[pendingLen];
        System.arraycopy(pending, 0, chunk, 0, pendingLen);
        pendingLen = 0;
        Ws w = ws;
        if (w == null) return;
        w.send(buildAudioChunk(chunk));
        lastActivity = System.currentTimeMillis();
    }
    private void send(String json) {
        Ws w = ws;
        if (w != null) w.send(json);
    }
    // ------------------------------------------------------------------ текст и состояние

    /** Конец хода: финальные реплики уходят в Callback (в историю попадают только они). */
    private void flushTurns() {
        Callback c = cb;
        String u = userTurn.toString().trim();
        userTurn.setLength(0);
        if (u.length() > 0 && c != null) c.onUserText(u);
        String a = assistantTurn.toString().trim();
        assistantTurn.setLength(0);
        if (a.length() > 0) {
            lastAssistantText = a;
            if (c != null) c.onAssistantText(a);
        }
    }
    private void emitState(int s) {
        if (state == s) return;
        state = s;
        Sink k = sink;
        if (k != null) k.onState(s);
        Callback c = cb;
        if (c != null) c.onState(s);
    }
    /** Ошибка для человека: сообщаем в Sink и в Callback и останавливаемся. */
    private void fail(String message) {
        message = scrub(message);
        running = false;
        ready = false;
        Ws w = ws;
        ws = null;
        if (w != null) w.close();
        Sink k = sink;
        if (k != null) k.onError(message);
        Callback c = cb;
        if (c != null) c.onError(message);
    }
    /** Убирает ключ из текста ошибки: он уходит в UI, тосты и логи. */
    private String scrub(String message) {
        if (message == null) return "";
        String out = message;
        if (apiKey != null && apiKey.length() > 8) out = out.replace(apiKey, "***");
        return out.replaceAll("key=[^&\\s)\\]]+", "key=***");
    }

    // ------------------------------------------------------------------ мелочи

    private static String transcript(Map<String, Object> o, String camel, String snake) {
        Map<String, Object> sc = Json.asObj(pick(o, "serverContent", "server_content"));
        Map<String, Object> t = sc == null ? null : Json.asObj(pick(sc, camel, snake));
        String text = t == null ? null : Json.str(t.get("text"));
        return text == null ? "" : text;
    }
    /** Первое непустое значение из двух вариантов имени (camelCase и snake_case). */
    private static Object pick(Map<String, Object> o, String camel, String snake) {
        if (o == null) return null;
        Object v = o.get(camel);
        return v != null ? v : (snake == null ? null : o.get(snake));
    }
    private static boolean isTrue(Object v) {
        return v instanceof Boolean ? ((Boolean) v).booleanValue() : "true".equals(String.valueOf(v));
    }
    private static long num(Map<String, Object> o, String camel, String snake) {
        Object v = pick(o, camel, snake);
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return v == null ? 0L : Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
    private static String voiceOf(Model m) {
        String v = m == null || m.voice == null ? "" : m.voice.trim();
        return v.length() == 0 ? DEFAULT_VOICE : v;
    }
    private static String modelIdOf(Model m) {
        String id = m == null ? "" : (m.liveModelId == null ? "" : m.liveModelId.trim());
        if (id.length() == 0) id = m == null || m.modelId == null ? "" : m.modelId.trim();
        return id.startsWith("models/") ? id : "models/" + id;
    }
}