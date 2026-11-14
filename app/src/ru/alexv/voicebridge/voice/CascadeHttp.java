package ru.alexv.voicebridge.voice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.alexv.voicebridge.data.Model;
import ru.alexv.voicebridge.data.SecretStore;
import ru.alexv.voicebridge.json.Json;

/**
 * Каскад через HTTP OpenAI-совместимого эндпоинта выбранной модели:
 *
 * запись → VAD-сегмент (700 мс тишины = конец реплики) → WAV → POST /audio/transcriptions
 * → чат через {@link CascadeSystem.Chat} → POST /audio/speech (response_format=pcm) → динамик.
 *
 * Полудуплекс: пока звучит ответ, микрофон не слушаем (без AEC перебивание голосом ненадёжно),
 * поэтому перебивание — кнопкой (stop/sendText).
 *
 * Если /audio/speech нет (400/404/405/501) — один раз переключаемся на системный TTS
 * ({@link #usingSystemTts()}) и больше не тратим время на заведомо неудачные запросы.
 */
public final class CascadeHttp implements VoiceEngine {

    public static final int SAMPLE_RATE = 16000;
    /** 640 байт = 20 мс при 16 кГц: VAD замечает начало речи за 0–40 мс. */
    private static final int VAD_FRAME_BYTES = 640;
    private static final int END_SILENCE_MS = 700;
    private static final int MIN_UTTERANCE_BYTES = SAMPLE_RATE;      // короче 0.5 с не отправляем
    private static final int MAX_UTTERANCE_BYTES = SAMPLE_RATE * 2 * 30;

    private static final int STT_TIMEOUT_MS = 45000;
    private static final int TTS_TIMEOUT_MS = 45000;
    private static final int CHAT_TIMEOUT_MS = 60000;
    private static final int HISTORY_LIMIT = 20;

    public static final String DEFAULT_STT_MODEL = "whisper-1";
    public static final String DEFAULT_TTS_MODEL = "tts-1";
    public static final String DEFAULT_VOICE = "alloy";

    private final Context ctx;
    private final Model model;
    private final CascadeSystem.Chat chat;
    private final String systemPrompt;
    private final String language;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> speech = new ArrayDeque<String>();
    private final Object captureLock = new Object();
    private final ByteArrayOutputStream capture = new ByteArrayOutputStream();
    private final List<CascadeSystem.ChatMsg> history = new ArrayList<CascadeSystem.ChatMsg>();

    private AudioIn in;
    private AudioOut out;
    private Vad vad;
    private SystemTts fallbackTts;
    private Callback cb;
    private volatile boolean running;
    private int state = IDLE;
    private int inflight;
    private int generation;
    private int seq;
    private String apiKey = "";
    private boolean systemTtsFallback;
    private boolean fallbackSpeaking;

    public CascadeHttp(Context ctx, Model model, CascadeSystem.Chat chat) {
        this(ctx, model, chat, CascadeSystem.DEFAULT_SYSTEM_PROMPT, CascadeSystem.DEFAULT_LANGUAGE);
    }

    public CascadeHttp(Context ctx, Model model, CascadeSystem.Chat chat, String systemPrompt,
                       String language) {
        this.ctx = ctx;
        this.model = model;
        this.chat = chat;
        this.systemPrompt = systemPrompt == null ? CascadeSystem.DEFAULT_SYSTEM_PROMPT : systemPrompt;
        this.language = language == null || language.length() == 0
                ? CascadeSystem.DEFAULT_LANGUAGE : language;
    }

    public int state() {
        return state;
    }

    public boolean isRunning() {
        return running;
    }

    /** Серверный TTS отказал, озвучиваем системным. */
    public boolean usingSystemTts() {
        return systemTtsFallback;
    }

    // ------------------------------------------------------------- запуск

    @Override
    public void start(final Callback callback) {
        if (callback == null) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                startNow(callback);
            }
        });
    }

    private void startNow(Callback callback) {
        if (running) {
            return;
        }
        cb = callback;
        if (ctx == null) {
            fail("Нет доступа к системе: приложение запущено неправильно");
            return;
        }
        if (empty(model == null ? "" : model.baseUrl)) {
            fail("У модели не заполнен адрес сервера (baseUrl)");
            return;
        }
        apiKey = key();
        out = new AudioOut(AudioOut.SAMPLE_RATE, 1);
        if (!out.start()) {
            fail(out.lastError() == null ? "Динамик недоступен" : out.lastError());
            return;
        }
        vad = new Vad(VAD_FRAME_BYTES, SAMPLE_RATE);
        vad.setSilenceMs(END_SILENCE_MS);
        running = true;
        state = IDLE;
        in = new AudioIn(SAMPLE_RATE);
        if (!in.start(new Mic())) {
            in = null; // текст ошибки уже ушёл через onError из AudioIn
            return;
        }
        setState(LISTENING);
    }

    private String key() {
        if (model == null || empty(model.keyRef)) {
            return "";
        }
        try {
            String k = new SecretStore(ctx).get(model.keyRef);
            return k == null ? "" : k;
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- вход

    private final class Mic implements AudioIn.Listener {
        @Override
        public void onPcm(byte[] chunk, int len) {
            Vad v = vad;
            if (v == null || !running) {
                return;
            }
            if (state == SPEAKING) {
                // Полудуплекс: пока говорим — микрофон не слушаем (иначе слышим свой динамик).
                v.reset();
                return;
            }
            boolean speechFrame = v.feed(chunk, len);
            if (v.speechStarted()) {
                synchronized (captureLock) {
                    capture.reset();
                }
                setState(LISTENING);
            }
            if (speechFrame) {
                synchronized (captureLock) {
                    if (capture.size() < MAX_UTTERANCE_BYTES) {
                        capture.write(chunk, 0, len);
                    }
                }
            }
            if (v.speechEnded()) {
                onSpeechEnd();
            }
        }

        @Override
        public void onError(final String message) {
            postFail(message);
        }
    }

    private void onSpeechEnd() {
        byte[] pcm;
        synchronized (captureLock) {
            pcm = capture.toByteArray();
            capture.reset();
        }
        if (pcm.length < MIN_UTTERANCE_BYTES) {
            return; // щелчок/кашель — остаёмся слушать
        }
        transcribe(pcm);
    }

    // ---------------------------------------------------------------- STT

    private void transcribe(final byte[] pcm) {
        setState(THINKING);
        final int gen = generation;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String boundary = HttpWire.boundary();
                Map<String, String> fields = new LinkedHashMap<String, String>();
                fields.put("model", empty(model.sttModel) ? DEFAULT_STT_MODEL : model.sttModel.trim());
                fields.put("language", shortLang());
                byte[] body = HttpWire.multipart(boundary, "file", "audio.wav", "audio/wav", pcm, fields);
                HttpWire.Bin r = HttpWire.post(
                        HttpWire.url(model.baseUrl, "/audio/transcriptions"),
                        HttpWire.headers(apiKey, model.headers),
                        "multipart/form-data; boundary=" + boundary, body, STT_TIMEOUT_MS);
                if (gen != generation || !running) {
                    return;
                }
                if (r.code <= 0) {
                    postFail("Не удалось отправить запись на распознавание: " + r.text);
                    return;
                }
                if (r.code >= 400) {
                    postFail(sttError(r.code));
                    return;
                }
                final String text = transcript(r.text);
                if (text.length() == 0) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (running) {
                                setState(LISTENING);
                            }
                        }
                    });
                    return;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        onUserText(text);
                    }
                });
            }
        }, "CascadeStt");
        t.setDaemon(true);
        t.start();
    }

    private static String sttError(int code) {
        if (code == 401 || code == 403) {
            return "Сервер не принял ключ распознавания (код " + code + ")";
        }
        if (code == 404) {
            return "На сервере нет /audio/transcriptions — распознавание недоступно";
        }
        return "Сервис распознавания вернул ошибку " + code;
    }

    /** Разбор ответа /audio/transcriptions ({"text":"..."} или {"transcript":"..."}). */
    public static String transcript(String json) {
        if (json == null || json.length() == 0) {
            return "";
        }
        try {
            Map<String, Object> o = Json.obj(json);
            if (o == null) {
                return "";
            }
            String t = Json.pathString(o, "text");
            if (t == null) {
                t = Json.pathString(o, "transcript");
            }
            return t == null ? "" : t.trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- чат

    private void onUserText(String text) {
        setState(THINKING);
        Callback c = cb;
        if (c != null) {
            c.onUserText(text);
        }
        if (chat == null) {
            onReply("Чат не подключён — ответить нечем.");
            return;
        }
        history.add(new CascadeSystem.ChatMsg("user", text));
        final List<CascadeSystem.ChatMsg> msgs = new ArrayList<CascadeSystem.ChatMsg>(history);
        while (history.size() > HISTORY_LIMIT) {
            history.remove(0);
        }
        final int gen = generation;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String reply;
                try {
                    reply = chat.complete(systemPrompt, msgs, CHAT_TIMEOUT_MS);
                } catch (IOException e) {
                    postFail("Нет ответа от модели: " + e.getMessage());
                    return;
                } catch (RuntimeException e) {
                    postFail("Сбой при запросе к модели: " + e.getMessage());
                    return;
                }
                if (reply == null || reply.trim().length() == 0) {
                    reply = "Модель вернула пустой ответ.";
                }
                final String r = reply.trim();
                if (gen != generation) {
                    return;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        onReply(r);
                    }
                });
            }
        }, "CascadeChat");
        t.setDaemon(true);
        t.start();
    }

    private void onReply(String reply) {
        if (!running) {
            return;
        }
        Callback c = cb;
        if (c != null) {
            c.onAssistantText(reply);
        }
        history.add(new CascadeSystem.ChatMsg("assistant", reply));
        final List<String> phrases = phrases(reply);
        if (phrases.isEmpty()) {
            setState(LISTENING);
            return;
        }
        final int gen = generation;
        synchronized (speech) {
            speech.clear();
            speech.addAll(phrases);
        }
        inflight = phrases.size();
        Vad v = vad;
        if (v != null) {
            v.reset();
        }
        setState(SPEAKING);
        main.postDelayed(watch, 150);
        // Один поток на реплику: фразы уходят в синтез по очереди, иначе звук перемешается.
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running && gen == generation) {
                    String next;
                    synchronized (speech) {
                        next = speech.poll();
                    }
                    if (next == null) {
                        break;
                    }
                    speakPhrase(next, gen);
                }
            }
        }, "CascadeTts");
        t.setDaemon(true);
        t.start();
    }

    /** Ответ режется на фразы и чистится от markdown: озвучивать «**» незачем. */
    public static List<String> phrases(String reply) {
        List<String> out = new ArrayList<String>();
        Sentences s = new Sentences(25);
        ArrayDeque<String> raw = new ArrayDeque<String>();
        for (String p : s.feed(reply)) {
            raw.add(p);
        }
        for (String p : s.finish()) {
            raw.add(p);
        }
        if (raw.isEmpty()) {
            raw.add(reply);
        }
        for (String p : raw) {
            String say = Sentences.stripMarkdown(p);
            if (say.length() > 0) {
                out.add(say);
            }
        }
        return out;
    }

    // --------------------------------------------------------------- TTS

    private void speakPhrase(String phrase, int gen) {
        if (systemTtsFallback) {
            speakSystem(phrase);
            done();
            return;
        }
        String body = HttpWire.ttsJson(ttsModel(), voice(), phrase);
        HttpWire.Bin r = HttpWire.post(HttpWire.url(model.baseUrl, "/audio/speech"),
                HttpWire.headers(apiKey, model.headers), "application/json",
                HttpWire.utf8(body), TTS_TIMEOUT_MS);
        if (gen != generation || !running) {
            done();
            return;
        }
        byte[] pcm = r.code >= 400 || r.code <= 0 ? null : binaryAudio(r.data);
        if (pcm == null || pcm.length == 0) {
            if (fallbackToSystemTts(r.code)) {
                systemTtsFallback = true; // у этой модели нет /audio/speech — больше не пробуем
            }
            speakSystem(phrase); // хоть какой-то звук лучше молчания
        } else {
            AudioOut o = out;
            if (o != null) {
                o.write(pcm, pcm.length);
            }
        }
        done();
    }

    private void done() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (inflight > 0) {
                    inflight--;
                }
            }
        });
    }

    /** Ответ /audio/speech иногда приходит в WAV-контейнере — вырезаем тело. */
    public static byte[] binaryAudio(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        if (data.length > 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F') {
            byte[] pcm = Wav.dataBytes(data);
            if (pcm != null) {
                return pcm;
            }
        }
        return data;
    }

    private String ttsModel() {
        return model != null && !empty(model.ttsModel) ? model.ttsModel.trim() : DEFAULT_TTS_MODEL;
    }

    private String voice() {
        return model != null && !empty(model.voice) ? model.voice.trim() : DEFAULT_VOICE;
    }

    private void speakSystem(String phrase) {
        SystemTts t = fallbackTts;
        if (t == null) {
            t = new SystemTts(ctx, language, 0); // 0 — играет сама система
            t.setCallback(new SystemTts.Callback() {
                @Override
                public void onSpoken(String utteranceId, byte[] pcm) {
                    fallbackSpeaking = false;
                }

                @Override
                public void onFailed(String utteranceId, String reason) {
                    fallbackSpeaking = false;
                }

                @Override
                public void onUnavailable(String message) {
                    fallbackSpeaking = false;
                    if (running) {
                        fail(message);
                    }
                }
            });
            fallbackTts = t;
            if (!t.start()) {
                return;
            }
        }
        fallbackSpeaking = true;
        t.speak(phrase, "vb" + (++seq));
    }

    // ----------------------------------------------------- перебивание/стоп

    /** Оборвать озвучку: очистить очередь и сбросить буфер динамика. */
    public void stopSpeech() {
        AudioOut o = out;
        if (o != null) {
            o.flush();
        }
        SystemTts t = fallbackTts;
        if (t != null) {
            t.stop();
        }
        fallbackSpeaking = false;
    }

    /** Дождались ли конца ответа: очередь, сеть, системный TTS и динамик пусты. */
    private boolean quiet() {
        AudioOut o = out;
        boolean queueEmpty;
        synchronized (speech) {
            queueEmpty = speech.isEmpty();
        }
        return queueEmpty && inflight <= 0 && !fallbackSpeaking && (o == null || !o.isPlaying());
    }

    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            if (!running || state != SPEAKING) {
                return;
            }
            if (quiet()) {
                Vad v = vad;
                if (v != null) {
                    v.reset(); // начинаем слушать с чистого состояния
                }
                setState(LISTENING);
            } else {
                main.postDelayed(this, 150);
            }
        }
    };

    @Override
    public void sendText(final String text) {
        if (text == null || text.trim().length() == 0) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }
                generation++;
                stopSpeech();
                onUserText(text.trim());
            }
        });
    }

    @Override
    public void stop() {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                releaseAll();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            main.post(r);
        }
    }

    private void releaseAll() {
        running = false;
        state = IDLE;
        generation++;
        stopSpeech();
        AudioIn i = in;
        in = null;
        if (i != null) {
            i.stop();
        }
        AudioOut a = out;
        out = null;
        if (a != null) {
            a.stop();
        }
        SystemTts t = fallbackTts;
        fallbackTts = null;
        if (t != null) {
            t.release();
        }
        vad = null;
        cb = null;
    }

    private void postFail(final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                fail(msg);
            }
        });
    }

    private void fail(String msg) {
        Callback c = cb;
        releaseAll();
        if (c != null) {
            c.onError(msg);
        }
    }

    private void setState(int s) {
        if (state == s) {
            return;
        }
        state = s;
        Callback c = cb;
        if (c != null) {
            c.onState(s);
        }
    }

    private String shortLang() {
        return language.length() >= 2 ? language.substring(0, 2) : "ru";
    }

    static boolean empty(String s) {
        return s == null || s.trim().length() == 0;
    }

    /** Эти коды означают «эндпоинта TTS нет», а не «попробуй ещё раз». */
    public static boolean fallbackToSystemTts(int httpCode) {
        return httpCode == 400 || httpCode == 404 || httpCode == 405 || httpCode == 501;
    }

    /** Каталог для временных WAV (нужен тестам и отладке). */
    public File cacheDir() {
        return ctx == null ? null : ctx.getCacheDir();
    }
}
