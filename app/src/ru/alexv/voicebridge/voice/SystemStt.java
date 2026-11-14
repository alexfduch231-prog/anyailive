package ru.alexv.voicebridge.voice;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * Распознавание речи системным SpeechRecognizer (без ключей и без своего микрофона:
 * микрофон в этом режиме держит сам сервис распознавания).
 *
 * Молчание и «не разобрал» — не ошибки: движок просто слушает дальше. Фатальные ошибки
 * (нет разрешения, нет сети, занятый микрофон) уходят в {@link Listener#onError}, после чего
 * объект нужно закрыть.
 */
public final class SystemStt {

    /** Куда сообщать о ходе распознавания. Колбэки приходят в главный поток. */
    public interface Listener {
        /** Финальный текст реплики. */
        void onText(String text);

        /** Речь закончилась, ждём результат. */
        void onThinking();

        /** Слушаем заново (тишина, отмена, повтор). */
        void onListening();

        /** Дальше работать нельзя — нужен {@link #stop()}. */
        void onError(String message);
    }

    private static final int RETRY_MS = 250;
    private static final int BUSY_RETRY_MS = 400;

    private final Context ctx;
    private final String language;
    private final Handler main = new Handler(Looper.getMainLooper());

    private SpeechRecognizer recognizer;
    private Listener listener;
    private volatile boolean running;

    public SystemStt(Context ctx, String language) {
        this.ctx = ctx;
        this.language = language == null || language.length() == 0
                ? CascadeSystem.DEFAULT_LANGUAGE : language;
    }

    /** Есть ли на устройстве сервис распознавания (проверять до запуска сессии). */
    public static boolean available(Context ctx) {
        if (ctx == null) {
            return false;
        }
        try {
            return SpeechRecognizer.isRecognitionAvailable(ctx);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public String language() {
        return language;
    }

    /** Создать распознаватель. @return false — onError уже вызван */
    public boolean start(Listener l) {
        if (recognizer != null) {
            return true;
        }
        listener = l;
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
            recognizer.setRecognitionListener(new Events());
            running = true;
            return true;
        } catch (RuntimeException e) {
            recognizer = null;
            failWith("Не удалось создать распознавание речи: " + e.getMessage());
            return false;
        }
    }

    /** Начать слушать (можно звать повторно — предыдущее распознавание отменяется). */
    public void listen() {
        final SpeechRecognizer r = recognizer;
        if (!running || r == null) {
            return;
        }
        Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language);
        it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        Listener l = listener;
        if (l != null) {
            l.onListening();
        }
        try {
            r.startListening(it);
        } catch (RuntimeException e) {
            failWith("Не удалось включить микрофон: " + e.getMessage());
        }
    }

    /** Отменить текущее распознавание (например, пришла текстовая реплика). */
    public void cancel() {
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

    /** Отменить и освободить распознаватель. Идемпотентно. */
    public void stop() {
        running = false;
        cancel();
        SpeechRecognizer r = recognizer;
        recognizer = null;
        if (r != null) {
            try {
                r.destroy();
            } catch (RuntimeException ignored) {
                // уже уничтожен
            }
        }
    }

    private void retry(final int delayMs) {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                listen();
            }
        }, delayMs);
    }

    private void failWith(String message) {
        Listener l = listener;
        if (l != null) {
            l.onError(message);
        }
    }

    private final class Events implements RecognitionListener {
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
            Listener l = listener;
            if (l != null) {
                l.onThinking();
            }
        }

        @Override
        public void onError(int error) {
            if (!running) {
                return;
            }
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    retry(RETRY_MS); // просто тишина
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    cancel();
                    retry(BUSY_RETRY_MS);
                    break;
                default:
                    failWith(reasonFor(error));
                    break;
            }
        }

        @Override
        public void onResults(Bundle results) {
            if (!running) {
                return;
            }
            String text = firstResult(results);
            Listener l = listener;
            if (text.length() == 0) {
                if (l != null) {
                    l.onListening();
                }
                listen();
            } else if (l != null) {
                l.onText(text);
            }
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            // Черновик не показываем: наружу идёт только финальный текст.
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    }

    /** Человеческое объяснение кода ошибки распознавания. */
    public static String reasonFor(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Нет разрешения на микрофон: включите его в настройках приложения";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                return "Сервис распознавания недоступен — проверьте интернет";
            case SpeechRecognizer.ERROR_AUDIO:
                return "Микрофон не работает: устройство занято другим приложением";
            case SpeechRecognizer.ERROR_CLIENT:
                return "Распознавание речи сбилось — попробуйте ещё раз";
            default:
                return "Ошибка распознавания речи (код " + error + ")";
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
}
