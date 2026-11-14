package ru.alexv.voicebridge.voice;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.alexv.voicebridge.data.Model;

/**
 * Каскад на системных API: SpeechRecognizer (вход) → чат через {@link Chat} → системный TTS (выход).
 *
 * Работает без ключей и без сети, кроме самого чата. Полудуплекс: пока говорим — не слушаем
 * (свой микрофон в руках системы), перебивание — через {@link #stop()} / {@link #stopSpeech()}.
 * TTS синтезируется в файл и играется через {@link AudioOut}, чтобы замолкать мгновенно;
 * если файл получить нельзя, остаётся speak() — тогда звуком управляет система.
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

    private SpeechRecognizer recognizer;
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

    /** Модель, для которой запущен движок (может быть null, если чат внешний). */
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
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
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
            return; // onUnavailable уже уйдёт в onError
        }
        if (!createRecognizer()) {
            return;
        }
        listen();
    }

    private boolean createRecognizer() {
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
            recognizer.setRecognitionListener(new RecognizeListener());
            return true;
        } catch (RuntimeException e) {
            fail("Не удалось создать распознавание речи: " + e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------- вход

    private void listen() {
        if (!running || recognizer == null) {
            return;
        }
        setState(LISTENING);
        Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language);
        it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            recognizer.startListening(it);
        } catch (RuntimeException e) {
            fail("Не удалось включить микрофон: " + e.getMessage());
        }
    }

    private void cancelListening() {
        SpeechRecognizer r = recognizer;
        if (r == null) {
            return;
        }
        try {
            r.cancel();
        } catch (RuntimeException ignored) {
            // уже не слушаем
        }
    }

    private final class RecognizeListener implements RecognitionListener {
        @Override
        public void onReadyForSpeech(Bundle params) {
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
            setState(THINKING);
        }

        @Override
        public void onError(int error) {
            if (!running) {
                return;
            }
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    // Тишина — это не ошибка, слушаем дальше.
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            listen();
                        }
                    }, 250);
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    cancelListening();
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            listen();
                        }
                    }, 400);
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    fail("Нет разрешения на микрофон: включите его в настройках приложения");
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER:
                    fail("Сервис распознавания недоступен — проверьте интернет");
                    break;
                case SpeechRecognizer.ERROR_AUDIO:
                    fail("Микрофон не работает: устройство занято другим приложением");
                    break;
                default:
                    fail("Ошибка распознавания речи (код " + error + ")");
                    break;
            }
        }

        @Override
        public void onResults(Bundle results) {
            if (!running) {
                return;
            }
            String text = firstResult(results);
            if (text.length() == 0) {
                listen();
            } else {
                handleUserText(text);
            }
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            // Черновик не показываем: onUserText — только финальный текст.
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    }

    private static String firstResult(Bundle b) {
        if (b == null) {
            return "";
        }
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty() || list.get(0) == null) {
            return "";
        }
        return list.get(0).trim();
    }

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
                cancelListening();
                stopSpeech();
                handleUserText(text.trim());
            }
        });
    }

    private void handleUserText(String text) {
        cancelListening();
        Callback c = cb;
        if (c != null) {
            c.onUserText(text);
        }
        synchronized (history) {
            history.add(new ChatMsg("user", text));
        }
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

    private List<ChatMsg> snapshot() {
        synchronized (history) {
            List<ChatMsg> copy = new ArrayList<ChatMsg>(history.size());
            int from = Math.max(0, history.size() - HISTORY_LIMIT);
            for (int i = from; i < history.size(); i++) {
                copy.add(history.get(i));
            }
            return copy;
        }
    }

    private void onReply(String reply) {
        if (!running) {
            return;
        }
        synchronized (history) {
            history.add(new ChatMsg("assistant", reply));
            while (history.size() > HISTORY_LIMIT * 2) {
                history.remove(0);
            }
        }
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
        if (phrases.isEmpty()) {
            phrases.add(reply);
        }
        synchronized (speech) {
            speech.clear();
            for (String p : phrases) {
                String speakable = Sentences.stripMarkdown(p);
                if (speakable.length() > 0) {
                    speech.add(speakable);
                }
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
        if (phrase == null || tts == null) {
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
                // Озвучивала система — она уже доиграла.
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        speakNext();
                    }
                }, 120);
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
    }

    private void finishTurn() {
        if (!running) {
            return;
        }
        setState(IDLE);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                listen();
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
        cancelListening();
        SpeechRecognizer r = recognizer;
        recognizer = null;
        if (r != null) {
            try {
                r.destroy();
            } catch (RuntimeException ignored) {
                // уже уничтожен
            }
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

    /** Для UI: куда складывать записанное, если нужно сохранить файл реплики. */
    public File cacheDir() {
        return ctx == null ? null : ctx.getCacheDir();
    }
}
