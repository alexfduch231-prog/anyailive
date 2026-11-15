package ru.alexv.anyailive.voice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.alexv.anyailive.data.Model;
import ru.alexv.anyailive.json.Json;

/**
 * OpenAI Realtime (GA-схема) поверх WebSocket. Поля и события - по docs/protocol-review/ground-truth.md:
 * схема вложенная (audio.input.format / audio.output.format), модальность - output_modalities;
 * плоских modalities / input_audio_format здесь нет намеренно.
 * <p><b>Потоки.</b> start() проверяет аргументы в вызывающем потоке (там же приходит onError про пустой
 * ключ), соединение открывает фоновый поток "vb-live-openai". Дальше все события приходят из потока
 * чтения сокета nv-websocket-client (один поток), поэтому состояние движка меняется без блокировок;
 * writeLock защищает только буфер микрофона. Handler/Looper не используются (движок обязан работать
 * в JVM-тестах) - UI сам перекидывает колбэки в главный поток через runOnUiThread.
 * <p><b>Аудио.</b> Наружу (в {@link Sink}) идёт PCM16 моно 24 кГц - то, что играет AudioOut; внутрь
 * принимается PCM16 моно 16 кГц (AudioRecord), ресемплинг 16-&gt;24 кГц делает {@link #upsample16to24}.
 */
public class LiveEngineOpenAI implements VoiceEngine {
    public static final int OTHER = 0, AUDIO_DELTA = 1, ASSISTANT_TEXT = 2, ASSISTANT_TEXT_DONE = 3;
    public static final int USER_TEXT = 4, USER_TEXT_DONE = 5, BARGE_IN = 6, SPEECH_STOPPED = 7;
    public static final int TURN_DONE = 8, CLOSED = 9, ERROR = 10;
    public static final int OUT_RATE = 24000, IN_RATE = 16000;
    /** {"type":"audio/pcm","rate":24000} - одинаковый формат на вход и на выход. */
    public static final String PCM_FORMAT = "{\"type\":\"audio/pcm\",\"rate\":" + OUT_RATE + "}";
    private static final int CHUNK_16K = IN_RATE / 10 * 2; // 100 мс PCM16 моно
    private static final int MAX_RECONNECT = 3, HISTORY_LIMIT = 20;
    private static final String DEFAULT_VOICE = "alloy", TRANSCRIBE_MODEL = "gpt-4o-transcribe";
    /** Приёмник звука и субтитров: тексты - ДЕЛЬТЫ (живая строка); финальный текст реплики идёт
     * в {@link VoiceEngine.Callback#onAssistantText(String)}. */

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
    private final List<String> history = new ArrayList<String>();
    private final Object writeLock = new Object();
    private final byte[] pending = new byte[CHUNK_16K];
    private final StringBuilder userTurn = new StringBuilder(), assistantTurn = new StringBuilder();
    private volatile Sink sink;
    private volatile Callback cb;
    private volatile Ws ws;
    private volatile boolean running, ready;
    private volatile int state = IDLE;
    private volatile String lastUserText = "", lastAssistantText = "";
    private volatile PlaybackClock clock;
    private int pendingLen, attempts;
    private String itemId;
    private long itemMs, itemBaselineMs = -1;
    public LiveEngineOpenAI(Model model, String apiKey, String systemPrompt) {
        this.model = model == null ? new Model() : model;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
    }
    public void setSink(Sink s) { this.sink = s; }
    /** Часы AudioOut (обычно out::playedMillis). Без них перебивание честно сообщает 0 мс. */
    public void setPlaybackClock(PlaybackClock c) { this.clock = c; }
    /** @return последняя финальная реплика ассистента (то, что успел услышать человек). */
    public String getLastAssistantText() { return lastAssistantText; }
    public boolean isRunning() { return running; }
    // ------------------------------------------------------------------ VoiceEngine

    @Override
    public void start(Callback callback) {
        this.cb = callback;
        if (running) return;
        if (apiKey.length() == 0) {
            fail("Не задан ключ API для этой модели - добавьте его в каталоге моделей."); return;
        }
        if (realtimeUrl(model).length() == 0) {
            fail("Не задан адрес realtime-эндпоинта: заполните baseUrl или liveUrl у модели."); return;
        }
        running = true;
        spawn(0, "vb-live-openai");
    }
    @Override
    public void stop() {
        running = false;
        ready = false;
        resetItem();
        synchronized (writeLock) { pendingLen = 0; }
        Ws w = ws;
        ws = null;
        if (w != null) w.close();
        emitState(IDLE);
    }
    /** Текст в разговор: реплика пользователя, затем просьба ответить. */
    @Override
    public void sendText(String text) {
        if (text == null || text.trim().length() == 0) return;
        String t = text.trim();
        String item = Json.write().put("type", "message").put("role", "user")
                .putRaw("content", "[" + Json.write().put("type", "input_text").put("text", t)
                        .done() + "]").done();
        send(Json.write().put("type", "conversation.item.create").putRaw("item", item).done());
        send(Json.write().put("type", "response.create").done());
        remember("Пользователь: " + t);
        emitState(THINKING);
    }
    /** Порция микрофона PCM16 моно 16 кГц: копится до 100 мс, ресемплится в 24 кГц и уходит как
     * input_audio_buffer.append. Пока сессия не готова, данные отбрасываются. */
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
    /** Перебивание снаружи (кнопка UI): отменяем ответ и обрезаем недоигранное аудио. */
    public void interrupt() { cut(true); }
    // ------------------------------------------------------------------ протокол (static, тестируемое)

    /** session.update строго по GA-схеме: вложенный audio.input/audio.output, output_modalities. */
    public static String buildSessionUpdate(Model m, String systemPrompt) {
        String in = Json.write().putRaw("format", PCM_FORMAT)
                .putRaw("transcription", Json.write().put("model", TRANSCRIBE_MODEL).done())
                .putRaw("turn_detection", Json.write().put("type", "server_vad")
                        .put("silence_duration_ms", 500L).put("prefix_padding_ms", 300L).done())
                .done();
        String out = Json.write().putRaw("format", PCM_FORMAT).put("voice", voiceOf(m)).done();
        Json.Writer session = Json.write().put("type", "realtime").put("model", liveModelOf(m))
                .putRaw("output_modalities", "[\"audio\"]")
                .putRaw("audio", Json.write().putRaw("input", in).putRaw("output", out).done());
        String prompt = systemPrompt == null ? "" : systemPrompt.trim();
        if (prompt.length() > 0) session.put("instructions", prompt);
        return Json.write().put("type", "session.update").putRaw("session", session.done()).done();
    }
    public static String buildSessionUpdate(Model m) {
        return buildSessionUpdate(m, "");
    }
    /** Классификация события: принимает полный JSON, голое имя или имя в кавычках. Обе семьи имён
     * (response.* / input_audio_buffer.* и session.*) сводятся к одному виду по суффиксу - иначе на
     * части шлюзов приложение просто молчит. */
    public static int eventKind(String json) {
        String type = eventType(json);
        if (type == null || type.length() == 0) return OTHER;
        if (type.endsWith("output_audio_transcript.delta")) return ASSISTANT_TEXT;
        if (type.endsWith("output_audio_transcript.done")) return ASSISTANT_TEXT_DONE;
        if (type.endsWith("output_transcript.delta")) return ASSISTANT_TEXT;
        if (type.endsWith("input_audio_transcription.delta")) return USER_TEXT;
        if (type.endsWith("input_audio_transcription.completed")) return USER_TEXT_DONE;
        if (type.endsWith("input_transcript.delta")) return USER_TEXT;
        if (type.endsWith("audio.delta")) return AUDIO_DELTA;
        if (type.endsWith("speech_started")) return BARGE_IN;
        if (type.endsWith("speech_stopped")) return SPEECH_STOPPED;
        if (type.equals("response.done") || type.equals("response.completed")) return TURN_DONE;
        if (type.endsWith("session.closed") || type.endsWith("session.close")) return CLOSED;
        if (type.equals("error") || type.endsWith(".error")) return ERROR;
        return OTHER;
    }
    /** @return имя события (поле type) либо, если на входе уже имя, - само имя. */
    public static String eventType(String json) {
        if (json == null) return null;
        String t = json.trim();
        if (t.length() > 1 && t.charAt(0) == '{') {
            Map<String, Object> o = Json.obj(t);
            return o == null ? null : Json.str(o.get("type"));
        }
        if (t.length() > 1 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            t = t.substring(1, t.length() - 1);
        }
        return t.length() == 0 ? null : t;
    }
    /** Переподключаться ли после закрытия: при ошибках доступа/адреса/модели повтор не поможет. */
    public static boolean shouldReconnect(String reason) {
        if (reason == null) return true;
        String r = reason.toLowerCase();
        String[] fatal = {"api key", "unauthorized", "forbidden", "invalid", "401", "403", "404",
                "429", "model_not_found", "not supported", "unsupported", "unknown", "user"};
        for (int i = 0; i < fatal.length; i++) {
            if (r.contains(fatal[i])) return false;
        }
        return true;
    }
    /** liveUrl, если задан, иначе baseUrl с http-&gt;ws и добавлением /realtime?model=... */
    public static String realtimeUrl(Model m) {
        if (m == null) return "";
        String custom = m.liveUrl == null ? "" : m.liveUrl.trim();
        if (custom.length() > 0) return custom;
        String base = m.baseUrl == null ? "" : m.baseUrl.trim();
        if (base.length() == 0) base = "https://api.openai.com/v1";
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.startsWith("https://")) base = "wss://" + base.substring(8);
        else if (base.startsWith("http://")) base = "ws://" + base.substring(7);
        return base + "/realtime?model=" + query(liveModelOf(m));
    }
    /** Заголовки соединения: ключ, бета-флаг OpenAI и строки "K: V" из model.headers. */
    public static Map<String, String> headers(Model m, String apiKey) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Authorization", "Bearer " + (apiKey == null ? "" : apiKey.trim()));
        h.put("OpenAI-Beta", "realtime=v1");
        if (m != null && m.headers != null) {
            for (String line : m.headers.split("\n")) {
                String s = line.trim();
                int i = s.indexOf(':');
                if (i > 0) h.put(s.substring(0, i).trim(), s.substring(i + 1).trim());
            }
        }
        return h;
    }
    /** Линейный ресемплер 16-&gt;24 кГц (PCM16 моно LE): 3 выхода на 2 входа, позиция j-го выхода =
     * 2*j/3; целочисленная арифметика - одинаковый результат на телефоне и в тестах. */
    public static byte[] upsample16to24(byte[] pcm16k) {
        int inSamples = pcm16k.length / 2;
        if (inSamples == 0) return new byte[0];
        byte[] out = new byte[(inSamples * 3 / 2) * 2];
        for (int j = 0; j < out.length / 2; j++) {
            int i0 = (2 * j) / 3;
            int frac = (2 * j) % 3;
            int a = sample(pcm16k, i0);
            int b = sample(pcm16k, Math.min(i0 + 1, inSamples - 1));
            int v = Math.max(-32768, Math.min(32767, a + (b - a) * frac / 3));
            out[2 * j] = (byte) (v & 0xff);
            out[2 * j + 1] = (byte) ((v >> 8) & 0xff);
        }
        return out;
    }
    // ------------------------------------------------------------------ соединение

    private void connect() {
        if (!running) return;
        Ws old = ws;
        if (old != null) old.close();
        ready = false;
        resetItem();
        synchronized (writeLock) { pendingLen = 0; }
        final Ws w = new Ws();
        ws = w;
        final String url = realtimeUrl(model);
        String err = w.connect(url, headers(model, apiKey), new Ws.Listener() {
            @Override
            public void onOpen() {
                ready = true;
                attempts = 0;
                w.send(buildSessionUpdate(model, instructionsWithHistory()));
                emitState(LISTENING);
            }
            @Override public void onText(String json) { dispatch(json); }
            @Override
            public void onClosed(String reason) {
                ready = false;
                if (!running) return;
                if (shouldReconnect(reason)) scheduleReconnect(reason);
                else fail("Соединение с realtime-эндпоинтом закрыто: " + reason);
            }
            @Override
            public void onError(String message) {
                ready = false;
                if (running) fail("Ошибка соединения с realtime-эндпоинтом: " + message);
            }
        });
        if (err != null && running) {
            boolean tlsBlip = err.contains("HostnameUnverified") || err.contains("SSLHandshake");
            if (tlsBlip && attempts < MAX_RECONNECT) {
                scheduleReconnect("TLS: " + err);
            } else {
                fail("Не удалось подключиться к realtime-эндпоинту (" + url + "): " + err);
            }
        }
    }
    private void spawn(final long delay, String name) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                if (delay > 0) try { Thread.sleep(delay); } catch (InterruptedException e) { return; }
                connect();
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }
    private void scheduleReconnect(String why) {
        if (attempts >= MAX_RECONNECT) {
            fail("Не удалось восстановить соединение с моделью: " + why);
            return;
        }
        attempts++;
        spawn(700L * attempts, "vb-live-openai-reconnect");
    }
    // ------------------------------------------------------------------ разбор событий

    private void dispatch(String json) {
        Map<String, Object> o = Json.obj(json);
        if (o == null) return;
        Sink s = sink;
        int k = eventKind(json);
        if (k == AUDIO_DELTA) {
            onAudio(o);
        } else if (k == ASSISTANT_TEXT) {
            String d = textOf(o, "delta");
            if (d.length() > 0) {
                assistantTurn.append(d);
                if (s != null) s.onAssistantText(d);
            }
        } else if (k == ASSISTANT_TEXT_DONE) {
            if (assistantTurn.length() == 0) assistantTurn.append(textOf(o, "transcript"));
        } else if (k == USER_TEXT) {
            String d = textOf(o, "delta");
            if (d.length() > 0) {
                userTurn.append(d);
                if (s != null) s.onUserText(d);
            }
        } else if (k == USER_TEXT_DONE) {
            String t = textOf(o, "transcript");
            if (t.length() > 0) { // completed приносит финальный текст целиком
                userTurn.setLength(0);
                userTurn.append(t);
            }
            finishUserTurn();
        } else if (k == BARGE_IN) {
            bargeIn();
        } else if (k == SPEECH_STOPPED) {
            emitState(THINKING);
        } else if (k == TURN_DONE) {
            flushAssistantTurn(); // itemId остаётся: пока очередь доигрывает, кнопка ещё может обрезать
            emitState(LISTENING);
        } else if (k == CLOSED) {
            ready = false;
            if (running) scheduleReconnect("closed");
        } else if (k == ERROR) {
            String msg = Json.pathString(o, "error.message");
            if (msg == null) msg = Json.str(o.get("message"));
            fail(msg == null ? "Сервер вернул ошибку без описания." : msg);
        }
    }
    /** Человек заговорил поверх ответа: гасим звук, модель узнаёт, что её не дослушали. */
    private void bargeIn() { cut(false); flushAssistantTurn(); } // ответ сервер отменил сам
    /** Перебивание: cancel (если нужен), truncate по ФАКТИЧЕСКИ проигранному, сброс item, LISTENING.
     * Truncate уходит до onBargeIn: вызывающий делает AudioOut.flush(), а тот обнуляет часы. */
    private void cut(boolean cancel) {
        Ws w = ws;
        if (w == null) return;
        if (cancel) w.send(Json.write().put("type", "response.cancel").done());
        if (itemId != null) w.send(truncateMessage(itemId, playedSince(itemBaselineMs, clockMs(), itemMs)));
        resetItem();
        emitState(LISTENING);
        Sink s = sink;
        if (s != null) s.onBargeIn();
    }
    private void onAudio(Map<String, Object> o) {
        String id = Json.str(o.get("item_id")); // response_id - не item_id: обрезать по нему нельзя
        if (id != null && !id.equals(itemId)) { // новый item: свой отсчёт принятого и проигранного
            itemId = id;
            itemMs = 0;
            itemBaselineMs = -1;
        }
        String data = Json.str(o.get("delta"));
        if (data == null) data = Json.str(o.get("audio"));
        byte[] pcm;
        try {
            pcm = B64.decode(data);
        } catch (RuntimeException e) {
            return; // битый base64 одного фрейма не должен ронять сессию
        }
        if (pcm.length == 0) return;
        if (itemId != null) {
            if (itemBaselineMs < 0) itemBaselineMs = clockMs(); // проигранное на момент старта item
            itemMs += pcm.length / 48; // 24 кГц * 2 байта = 48 байт на мс
        }
        emitState(SPEAKING);
        Sink s = sink;
        if (s != null && running) s.onAudio(pcm);
    }
    private void flushPending() {
        if (pendingLen == 0) return;
        byte[] chunk = new byte[pendingLen];
        System.arraycopy(pending, 0, chunk, 0, pendingLen);
        pendingLen = 0;
        Ws w = ws;
        if (w == null) return;
        w.send(Json.write().put("type", "input_audio_buffer.append")
                .put("audio", B64.encode(upsample16to24(chunk))).done());
    }
    private void send(String json) { Ws w = ws; if (w != null) w.send(json); }
    // ------------------------------------------------------------------ текст и состояние

    private void finishUserTurn() {
        String t = userTurn.toString().trim();
        userTurn.setLength(0);
        if (t.length() == 0) return;
        lastUserText = t;
        remember("Пользователь: " + t);
        Callback c = cb;
        if (c != null) c.onUserText(t);
    }
    private void flushAssistantTurn() {
        String t = assistantTurn.toString().trim();
        assistantTurn.setLength(0);
        if (t.length() == 0) return;
        lastAssistantText = t;
        remember("Ассистент: " + t);
        Callback c = cb;
        if (c != null) c.onAssistantText(t);
    }
    private void remember(String line) {
        history.add(line); while (history.size() > HISTORY_LIMIT) history.remove(0);
    }
    /** instructions новой сессии: системная подсказка + последние 20 реплик локальной истории. */
    private String instructionsWithHistory() {
        if (history.isEmpty()) return systemPrompt;
        StringBuilder b = new StringBuilder(systemPrompt);
        if (b.length() > 0) b.append("\n\n");
        b.append("Ранее в этом разговоре:\n");
        for (int i = 0; i < history.size(); i++) b.append(history.get(i)).append('\n');
        return b.toString();
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

    private static String textOf(Map<String, Object> o, String field) {
        String t = Json.str(o.get(field)); if (t == null) t = Json.str(o.get("text"));
        return t == null ? "" : t;
    }
    private static int sample(byte[] pcm, int i) {
        return (short) (((pcm[2 * i + 1] & 0xff) << 8) | (pcm[2 * i] & 0xff)); }
    private static String voiceOf(Model m) {
        String v = m == null || m.voice == null ? "" : m.voice.trim(); return v.length() == 0 ? DEFAULT_VOICE : v;
    }
    private static String liveModelOf(Model m) {
        if (m == null) return "";
        String id = m.liveModelId == null ? "" : m.liveModelId.trim();
        if (id.length() == 0) id = m.modelId == null ? "" : m.modelId.trim();
        return id;
    }
    /** Экранирование значения в query, чтобы экзотика в id модели не ломала URL. */
    private static String query(String id) {
        String s = id == null ? "" : id;
        return s.replace("%", "%25").replace(" ", "%20").replace("&", "%26")
                .replace("+", "%2B").replace("#", "%23").replace("?", "%3F");
    }

    // ------------------------------------------------------------------ часы воспроизведения

    /** Часы AudioOut: сколько мс реально проиграно с последнего flush (AudioOut.playedMillis()). */
    public interface PlaybackClock {
        long playedMs();
    }

    /** -1 = часов нет: перебивание тогда сообщает 0, а не «сколько приняли». */
    private long clockMs() { PlaybackClock c = clock; return c == null ? -1 : c.playedMs(); }
    private void resetItem() { itemId = null; itemMs = 0; itemBaselineMs = -1; }
    /** Сколько мс ответа реально проиграно: 0 без часов/отметки и не больше принятого. */
    public static long playedSince(long baselineMs, long clockMs, long receivedMs) {
        if (baselineMs < 0 || clockMs < 0 || clockMs <= baselineMs || receivedMs <= 0) return 0;
        return Math.min(clockMs - baselineMs, receivedMs);
    }
    /** Тело conversation.item.truncate: audio_end_ms — проигранное, не принятое. */
    public static String truncateMessage(String itemId, long audioEndMs) {
        long ms = audioEndMs < 0 ? 0 : audioEndMs;
        return Json.write().put("type", "conversation.item.truncate").put("item_id", itemId)
                .put("content_index", 0L).put("audio_end_ms", ms).done();
    }
}
