package ru.alexv.anyailive.voice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.alexv.anyailive.data.Model;

/**
 * Каскад на системных API: {@link SystemStt} (вход) → чат через {@link Chat} → {@link SystemTts} (выход).
 *
 * Работает без ключей и без сети, кроме самого чата. Полудуплексный: пока говорим — не слушаем.
 * Перебивание — {@link #stop()} / {@link #stopSpeech()} (во время озвучки распознавание не идёт,
 * поэтому голосом не перебить). TTS синтезируется в файл и играется через {@link AudioOut},
 * чтобы замолкать мгновенно; если файл получить нельзя, звуком управляет система.
 */
public final class CascadeSystem implements VoiceEngine {

    /** Минимальный контракт чата: движок не знает, откуда берётся ответ. */
    public interface Chat {
        String complete(String systemPrompt, List<ChatMsg> messages, int timeoutMs) throws IOException;
    }

    /** Одна реплика переписки (роль + текст), без зависимостей от data/*. */
    public static class ChatMsg {
        public final String role;
        public final String text;

        public ChatMsg(String role, String text) {
            this.role = role == null ? "user" : role;
            this.text = text == null ? "" : text;
        }
    }

    public static final String DEFAULT_LANGUAGE = "ru-RU";
    public static final String DEFAULT_SYSTEM_PROMPT =
            "Ты голосовой помощник. Отвечай кратко: 1-3 предложения, без списков, markdown и эмодзи — "
                    + "ответ будет прочитан вслух.";

    private static final int CHAT_TIMEOUT_MS = 60000;
    private static final int HISTORY_LIMIT = 20;
    private static final int MIN_PHRASE_CHARS = 20;

    private final Context ctx;
    private final Model model;
    private final Chat chat;
    private final String systemPrompt;
    private final String language;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<ChatMsg> history = new ArrayList<ChatMsg>();
    private final ArrayDeque<String> speech = new ArrayDeque<String>();

    private SystemStt stt;
    private SystemTts tts;
    private AudioOut audioOut;
    private ExecutorService chatWorker;
    private Callback cb;
    private volatile boolean running;
    private int state = IDLE;
    private int seq;

    public CascadeSystem(Context ctx, Model model, Chat chat) {
        this(ctx, model, chat, DEFAULT_SYSTEM_PROMPT, DEFAULT_LANGUAGE);
    }

    public CascadeSystem(Context ctx, Model model, Chat chat, String systemPrompt) {
        this(ctx, model, chat, systemPrompt, DEFAULT_LANGUAGE);
    }

    public CascadeSystem(Context ctx, Model model, Chat chat, String systemPrompt, String language) {
        this.ctx = ctx;
        this.model = model;
        this.chat = chat;
        this.systemPrompt = systemPrompt == null || systemPrompt.length() == 0
                ? DEFAULT_SYSTEM_PROMPT : systemPrompt;
        this.language = language == null || language.length() == 0 ? DEFAULT_LANGUAGE : language;
    }

    public int state() {
        return state;
    }

    public boolean isRunning() {
        return running;
    }

    public String language() {
        return language;
    }

    /** Модель, под которую настроен движок (может быть null, если чат внешний). */
    public Model model() {
        return model;
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
        if (!SystemStt.available(ctx)) {
            fail("Распознавание речи недоступно на этом устройстве");
            return;
        }
        running = true;
        state = IDLE;
        chatWorker = Executors.newSingleThreadExecutor();
        audioOut = new AudioOut(AudioOut.SAMPLE_RATE, 1);
        if (!audioOut.start()) {
            audioOut = null; // динамик не открылся — пусть озвучивает сама система
        }
        tts = new SystemTts(ctx, language, audioOut == null ? 0 : AudioOut.SAMPLE_RATE);
        tts.setCallback(voice);
        if (!tts.start()) {
            return; // причина уже ушла в onUnavailable → onError
        }
        stt = new SystemStt(ctx, language);
        if (!stt.start(ears)) {
            return;
        }
        stt.listen();
    }

    // ---------------------------------------------------------------- вход

    private final SystemStt.Listener ears = new SystemStt.Listener() {
        @Override
        public void onText(String text) {
            handleUserText(text);
        }

        @Override
        public void onThinking() {
            setState(THINKING);
        }

        @Override
        public void onListening() {
            if (running && state != SPEAKING) {
                setState(LISTENING);
            }
        }

        @Override
        public void onError(String message) {
            fail(message);
        }
    };

    // --------------------------------------------------------------- чат

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
                if (stt != null) {
                    stt.cancel();
                }
                stopSpeech();
                handleUserText(text.trim());
            }
        });
    }

    private void handleUserText(String text) {
        if (stt != null) {
            stt.cancel();
        }
        Callback c = cb;
        if (c != null) {
            c.onUserText(text);
        }
        record(new ChatMsg("user", text));
        setState(THINKING);
        if (chat == null) {
            onReply("Чат не подключён — ответить нечем.");
            return;
        }
        final List<ChatMsg> msgs = snapshot();
        ExecutorService w = chatWorker;
        if (w == null) {
            return;
        }
        w.execute(new Runnable() {
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
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        onReply(r);
                    }
                });
            }
        });
    }

    private void record(ChatMsg msg) {
        synchronized (history) {
            history.add(msg);
            while (history.size() > HISTORY_LIMIT * 2) {
                history.remove(0);
            }
        }
    }

    private List<ChatMsg> snapshot() {
        synchronized (history) {
            int from = Math.max(0, history.size() - HISTORY_LIMIT);
            return new ArrayList<ChatMsg>(history.subList(from, history.size()));
        }
    }

    private void onReply(String reply) {
        if (!running) {
            return;
        }
        record(new ChatMsg("assistant", reply));
        Callback c = cb;
        if (c != null) {
            c.onAssistantText(reply);
        }
        enqueue(reply);
        speakNext();
    }

    // ------------------------------------------------------------- озвучка

    private void enqueue(String reply) {
        Sentences s = new Sentences(MIN_PHRASE_CHARS);
        ArrayDeque<String> phrases = new ArrayDeque<String>();
        for (String p : s.feed(reply)) {
            phrases.add(p);
        }
        for (String p : s.finish()) {
            phrases.add(p);
        }
        synchronized (speech) {
            speech.clear();
            for (String p : phrases) {
                String say = Sentences.stripMarkdown(p);
                if (say.length() > 0) {
                    speech.add(say);
                }
            }
            if (speech.isEmpty()) {
                speech.add(Sentences.stripMarkdown(reply));
            }
        }
    }

    private void speakNext() {
        if (!running) {
            return;
        }
        String phrase;
        synchronized (speech) {
            phrase = speech.poll();
        }
        if (phrase == null || phrase.length() == 0 || tts == null) {
            finishTurn();
            return;
        }
        setState(SPEAKING);
        tts.speak(phrase, "vb" + (++seq));
    }

    private final SystemTts.Callback voice = new SystemTts.Callback() {
        @Override
        public void onSpoken(String utteranceId, byte[] pcm) {
            if (!running) {
                return;
            }
            AudioOut out = audioOut;
            if (pcm == null || out == null) {
                // Озвучивала система — она уже доиграла, берём следующую фразу.
                main.postDelayed(speakNextRun, 120);
                return;
            }
            out.write(pcm, pcm.length);
            main.post(watchPlayback);
        }

        @Override
        public void onFailed(String utteranceId, String reason) {
            if (running) {
                speakNext();
            }
        }

        @Override
        public void onUnavailable(String message) {
            if (running) {
                fail(message);
            }
        }
    };

    private final Runnable speakNextRun = new Runnable() {
        @Override
        public void run() {
            speakNext();
        }
    };

    private final Runnable watchPlayback = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            AudioOut out = audioOut;
            if (out != null && out.isPlaying()) {
                main.postDelayed(this, 60);
            } else {
                speakNext();
            }
        }
    };

    /** Оборвать озвучку (перебивание) и очистить очередь фраз. */
    /** Перебивание кнопкой: глушим озвучку и продолжаем слушать. */
    @Override
    public void interrupt() {
        stopSpeech();
    }

    public void stopSpeech() {
        synchronized (speech) {
            speech.clear();
        }
        SystemTts t = tts;
        if (t != null) {
            t.stop();
        }
        AudioOut out = audioOut;
        if (out != null) {
            out.flush();
        }
        if (running) {
            setState(LISTENING);
            final SystemStt s = stt;
            if (s != null) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (running && stt == s) {
                            s.listen();
                        }
                    }
                });
            }
        }
    }

    private void finishTurn() {
        if (!running) {
            return;
        }
        setState(IDLE);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (running && stt != null) {
                    stt.listen();
                }
            }
        }, 150);
    }

    // ------------------------------------------------------------ состояние

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

    private void releaseAll() {
        running = false;
        state = IDLE;
        SystemStt s = stt;
        stt = null;
        if (s != null) {
            s.stop();
        }
        SystemTts t = tts;
        tts = null;
        if (t != null) {
            t.release();
        }
        AudioOut out = audioOut;
        audioOut = null;
        if (out != null) {
            out.stop();
        }
        synchronized (speech) {
            speech.clear();
        }
        ExecutorService w = chatWorker;
        chatWorker = null;
        if (w != null) {
            w.shutdownNow();
        }
        cb = null;
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

    /** Куда складываются временные файлы синтеза (для отладки и сохранения реплик). */
    public File cacheDir() {
        return ctx == null ? null : ctx.getCacheDir();
    }
}
