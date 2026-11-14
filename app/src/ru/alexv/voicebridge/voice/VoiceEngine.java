package ru.alexv.voicebridge.voice;

/**
 * Общий контракт голосового движка: нативный Live (WebSocket, аудио потоком)
 * и каскад (STT -> чат -> TTS) выглядят для экрана одинаково.
 */
public interface VoiceEngine {

    int IDLE = 0;
    int LISTENING = 1;
    int THINKING = 2;
    int SPEAKING = 3;

    interface Callback {
        /** Только переходы между IDLE/LISTENING/THINKING/SPEAKING. */
        void onState(int state);

        /** Финальный транскрипт реплики пользователя. */
        void onUserText(String text);

        /** Финальный текст ответа ассистента. */
        void onAssistantText(String text);

        /** Человеческое описание проблемы; движок после этого останавливается. */
        void onError(String message);
    }

    void start(Callback cb);

    /** Остановить сессию и освободить микрофон/динамик. Идемпотентно. */
    void stop();

    /** Отправить текстовую реплику из UI в текущую сессию. */
    void sendText(String text);
}
