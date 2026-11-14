package ru.alexv.anyailive.voice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import ru.alexv.anyailive.data.Model;

/**
 * Каскад через HTTP OpenAI-совместимого эндпоинта выбранной модели:
 *
 * запись → {@link CascadeStt} (VAD + /audio/transcriptions) → чат через {@link CascadeSystem.Chat}
 * → POST {baseUrl}/audio/speech (response_format=pcm) → динамик.
 *
 * Полудуплекс: пока звучит ответ, микрофон не разбирается (без AEC перебивание голосом
 * ненадёжно) — перебивание кнопкой ({@link #stopSpeech()} / {@link #sendText}).
 *
 * Если /audio/speech нет (400/404/405/501) — один раз переходим на системный TTS и запоминаем
 * это в поле ({@link #usingSystemTts()}), чтобы не тратить время на заведомо неудачные запросы.
 */
public final class CascadeHttp implements VoiceEngine {

    public static final String DEFAULT_TTS_MODEL = "tts-1";
    public static final String DEFAULT_VOICE = "alloy";

    private static final int TTS_TIMEOUT_MS = 45000;
    private static final int CHAT_TIMEOUT_MS = 60000;
    private static final int HISTORY_LIMIT = 20;

    private final Context ctx;
    private final Model model;
    private final CascadeSystem.Chat chat;
    private final String systemPrompt;
    private final String language;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> speech = new ArrayDeque<String>();
    private final List<CascadeSystem.ChatMsg> history = new ArrayList<CascadeSystem.ChatMsg>();

    private CascadeStt stt;
    private AudioOut out;
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

    /** Серверный синтез отказал, озвучиваем системным TTS. */
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
        apiKey = CascadeStt.keyFromStore(ctx, model);
        out = new AudioOut(AudioOut.SAMPLE_RATE, 1);
        if (!out.start()) {
            fail(out.lastError() == null ? "Динамик недоступен" : out.lastError());
            return;
        }
        running = true;
        state = IDLE;
        stt = new CascadeStt(ctx, model, language);
        stt.setApiKey(apiKey);
        if (!stt.start(mic)) {
            stt = null;
            return; // текст ошибки уже ушёл через onError
        }
        setState(LISTENING);
    }

    // ---------------------------------------------------------------- вход

    private final CascadeStt.Listener mic = new CascadeStt.Listener() {
        @Override
        public void onSpeechStart() {
            setState(LISTENING);
        }

        @Override
        public void onText(String text) {
            onUserText(text);
        }

        @Override
        public void onIdle() {
            if (running && state != SPEAKING) {
                setState(LISTENING);
            }
        }

        @Override
        public void onError(String message) {
            fail(message);
        }
    };

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
        while (history.size() > HISTORY_LIMIT) {
            history.remove(0);
        }
        final List<CascadeSystem.ChatMsg> msgs = new ArrayList<CascadeSystem.ChatMsg>(history);
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
        List<String> phrases = Sentences.speechPhrases(reply, 25);
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
                systemTtsFallback = true; // у модели нет /audio/speech — больше не пробуем
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
            t.setCallback(fallbackVoice);
            fallbackTts = t;
            if (!t.start()) {
                return;
            }
        }
        fallbackSpeaking = true;
        t.speak(phrase, "vb" + (++seq));
    }

    private final SystemTts.Callback fallbackVoice = new SystemTts.Callback() {
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
    };

    // ----------------------------------------------------- перебивание/стоп

    /** Оборвать озвучку: очистить очередь и сбросить буфер динамика. */
    /** Перебивание кнопкой: глушим озвучку и продолжаем слушать. */
    @Override
    public void interrupt() {
        stopSpeech();
    }

    public void stopSpeech() {
        generation++;
        synchronized (speech) {
            speech.clear();
        }
        inflight = 0;
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

    /** Всё доиграло: очередь, сеть, системный TTS и динамик пусты. */
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
        CascadeStt s = stt;
        stt = null;
        if (s != null) {
            s.stop();
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
        synchronized (speech) {
            speech.clear();
        }
        inflight = 0;
        fallbackSpeaking = false;
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
        CascadeStt s2 = stt;
        if (s2 != null) {
            // Полудуплекс: микрофон разбирается только когда действительно слушаем —
            // во время ответа и ожидания модели ассистент не подслушивает сам себя.
            s2.setPaused(s != LISTENING);
        }
        if (state == s) {
            return;
        }
        state = s;
        Callback c = cb;
        if (c != null) {
            c.onState(s);
        }
    }

    static boolean empty(String s) {
        return s == null || s.trim().length() == 0;
    }

    /** Эти коды означают «эндпоинта синтеза нет», а не «попробуй ещё раз». */
    public static boolean fallbackToSystemTts(int httpCode) {
        return httpCode == 400 || httpCode == 404 || httpCode == 405 || httpCode == 501;
    }
}
