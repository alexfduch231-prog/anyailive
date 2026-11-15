package ru.alexv.anyailive.voice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.util.Map;

import ru.alexv.anyailive.data.Model;
import ru.alexv.anyailive.data.SecretStore;
import ru.alexv.anyailive.json.Json;

/**
 * Распознавание речи по HTTP: свой микрофон → VAD (энергия RMS) → сегмент реплики → WAV →
 * POST {baseUrl}/audio/transcriptions (multipart: file + model) → текст.
 *
 * Конец реплики — 700 мс тишины (бюджет задержки в спеке: endpointing 700 мс). Пока
 * {@link #setPaused(boolean) включён пауза}, микрофон читается, но не разбирается: это
 * полудуплекс — во время ответа ассистент не слышит сам себя.
 */
public final class CascadeStt implements AudioIn.Listener {

    public static final int SAMPLE_RATE = 16000;
    public static final String DEFAULT_MODEL = "whisper-1";

    /** 640 байт = 20 мс при 16 кГц: начало речи замечается за 0–40 мс. */
    private static final int VAD_FRAME_BYTES = 640;
    private static final int END_SILENCE_MS = 700;
    private static final int MIN_UTTERANCE_BYTES = SAMPLE_RATE;       // короче 0.5 с не отправляем
    private static final int MAX_UTTERANCE_BYTES = SAMPLE_RATE * 2 * 30;
    private static final int TIMEOUT_MS = 45000;

    /** Куда отдавать результат. Колбэки приходят в главный поток. */
    public interface Listener {
        /** Речь началась — можно переключить интерфейс в «слушаю». */
        void onSpeechStart();

        /** Распознанный текст реплики. */
        void onText(String text);

        /** Реплику не разобрали или отправили — снова слушаем. */
        void onIdle();

        /** Дальше работать нельзя — нужен {@link #stop()}. */
        void onError(String message);
    }

    private final Context ctx;
    private final Model model;
    private final String language;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ByteArrayOutputStream capture = new ByteArrayOutputStream();
    private final Object lock = new Object();

    private AudioIn in;
    private Vad vad;
    private Listener listener;
    private volatile boolean running;
    private volatile boolean paused;
    private volatile int epoch;
    private boolean transcribing; // guarded by lock; held through queued delivery
    private String apiKey = "";

    public CascadeStt(Context ctx, Model model, String language) {
        this.ctx = ctx;
        this.model = model;
        this.language = language == null || language.length() == 0
                ? CascadeSystem.DEFAULT_LANGUAGE : language;
    }

    public void setApiKey(String key) {
        apiKey = key == null ? "" : key;
    }

    /** Ключ из хранилища по model.keyRef (пусто, если ссылки нет). */
    public String keyFromStore() {
        return keyFromStore(ctx, model);
    }

    /** То же, но без создания объекта — нужно тому, кто только берёт ключ. */
    public static String keyFromStore(Context ctx, Model model) {
        if (ctx == null || model == null || model.keyRef == null || model.keyRef.length() == 0) {
            return "";
        }
        try {
            String k = new SecretStore(ctx).get(model.keyRef);
            return k == null ? "" : k;
        } catch (RuntimeException e) {
            return "";
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** Включён ли аппаратный AEC на текущем захвате. */
    public boolean aecEnabled() {
        AudioIn a = in;
        return a != null && a.aecEnabled();
    }

    /** Включить/выключить полудуплекс (true — микрофон не разбирается). */
    public void setPaused(boolean p) {
        paused = p;
        if (p) {
            Vad v = vad;
            if (v != null) {
                v.reset();
            }
            synchronized (lock) {
                capture.reset();
            }
        }
    }

    /** @return false — onError уже вызван */
    public boolean start(Listener l) {
        if (running) {
            return true;
        }
        listener = l;
        paused = false;
        if (apiKey.length() == 0) {
            apiKey = keyFromStore();
        }
        vad = new Vad(VAD_FRAME_BYTES, SAMPLE_RATE);
        vad.setSilenceMs(END_SILENCE_MS);
        in = new AudioIn(SAMPLE_RATE);
        running = true;
        if (!in.start(this)) {
            in = null;
            running = false;
            return false; // текст ошибки уже ушёл через onError
        }
        return true;
    }

    public void stop() {
        running = false;
        epoch++;
        AudioIn a = in;
        in = null;
        if (a != null) {
            a.stop();
        }
        vad = null;
        synchronized (lock) {
            transcribing = false;
            capture.reset();
        }
    }

    // ------------------------------------------------------------- микрофон

    @Override
    public void onPcm(byte[] chunk, int len) {
        Vad v = vad;
        if (v == null || !running) {
            return;
        }
        if (paused) {
            v.reset();
            return;
        }
        boolean speechFrame = v.feed(chunk, len);
        if (v.speechStarted()) {
            synchronized (lock) {
                capture.reset();
            }
            post(new Runnable() {
                @Override
                public void run() {
                    Listener l = listener;
                    if (l != null && running) {
                        l.onSpeechStart();
                    }
                }
            });
        }
        if (speechFrame) {
            synchronized (lock) {
                if (capture.size() < MAX_UTTERANCE_BYTES) {
                    capture.write(chunk, 0, len);
                }
            }
        }
        if (v.speechEnded()) {
            byte[] pcm;
            synchronized (lock) {
                pcm = capture.toByteArray();
                capture.reset();
            }
            if (pcm.length < MIN_UTTERANCE_BYTES) {
                postIdle();
            } else {
                transcribe(pcm);
            }
        }
    }

    @Override
    public void onError(final String message) {
        fail(message, epoch);
    }

    private void fail(final String message, final int gen) {
        post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                running = false;
                synchronized (lock) { transcribing = false; }
                if (l != null) {
                    l.onError(message);
                }
            }
        }, gen);
    }

    // ----------------------------------------------------------------- STT

    private void transcribe(final byte[] pcm) {
        final int gen;
        synchronized (lock) {
            if (!running || transcribing) return;
            transcribing = true;
            gen = epoch;
        }
        // Half-duplex: do not capture a second utterance while this request is pending.
        setPaused(true);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String boundary = HttpWire.boundary();
                byte[] body = TranscriptionBody.build(boundary, pcm,
                        model == null ? null : model.sttModel, shortLang());
                HttpWire.Bin r = HttpWire.post(
                        HttpWire.url(model == null ? "" : model.baseUrl, "/audio/transcriptions"),
                        HttpWire.headers(apiKey, model == null ? null : model.headers),
                        "multipart/form-data; boundary=" + boundary, body, TIMEOUT_MS);
                if (gen != epoch || !running) {
                    return;
                }
                if (r.code <= 0) {
                    fail("Не удалось отправить запись на распознавание: " + r.text, gen);
                    return;
                }
                if (r.code >= 400) {
                    fail(errorFor(r.code), gen);
                    return;
                }
                String text = transcript(r.text);
                if (text.length() == 0) {
                    postIdle(gen);
                    return;
                }
                deliver(text, gen);
            }
        }, "CascadeStt");
        t.setDaemon(true);
        t.start();
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

    /** Понятное объяснение кода ошибки распознавания. */
    public static String errorFor(int code) {
        if (code == 401 || code == 403) {
            return "Сервер не принял ключ распознавания (код " + code + ")";
        }
        if (code == 404) {
            return "На сервере нет /audio/transcriptions — распознавание недоступно";
        }
        return "Сервис распознавания вернул ошибку " + code;
    }

    private String shortLang() {
        return language.length() >= 2 ? language.substring(0, 2) : "ru";
    }

    private void postIdle() { postIdle(epoch); }

    private void postIdle(final int gen) {
        post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                synchronized (lock) { transcribing = false; }
                if (running) setPaused(false);
                if (l != null && running) {
                    l.onIdle();
                }
            }
        }, gen);
    }

    private void deliver(final String text, final int gen) {
        post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                synchronized (lock) { transcribing = false; }
                if (l != null && running) {
                    l.onText(text);
                }
            }
        }, gen);
    }

    private void post(final Runnable r) { post(r, epoch); }

    private void post(final Runnable r, final int gen) {
        main.post(new Runnable() {
            @Override public void run() {
                if (gen == epoch) r.run();
            }
        });
    }
}
