package ru.alexv.anyailive.voice;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;

/**
 * Захват с микрофона: 16 кГц, моно, PCM16 — единственный входной формат приложения.
 *
 * Источник: VOICE_COMMUNICATION (там работает эхоподавление, без него модель слышит сама себя),
 * при отказе — MIC. Буфер — 2 × getMinBufferSize, читаем кусками по ~20 мс, чтобы не копить
 * задержку. Чтение идёт в отдельном потоке; буфер переиспользуется, поэтому слушатель обязан
 * скопировать данные, если собирается их хранить.
 *
 * Эхоподавление: VOICE_COMMUNICATION + платформенный AcousticEchoCanceler (и NoiseSuppressor,
 * если есть). Доступно не на всех устройствах, поэтому {@link #aecEnabled()} нужно проверять:
 * при выключенном AEC дуплекс (одновременная речь и ответ) ненадёжен — ассистент слышит сам себя,
 * и такой режим нужно вести полудуплексом.
 *
 * Разрешения проверяются не ContextCompat (androidx нет), а try/catch SecurityException.
 */
public final class AudioIn {

    public interface Listener {
        /** @param chunk переиспользуемый буфер: скопировать, если нужно хранить */
        void onPcm(byte[] chunk, int len);

        /** Понятный человеку текст; поток после этого завершается. */
        void onError(String message);
    }

    public static final int SAMPLE_RATE = 16000;
    /** 20 мс при 16 кГц — компромисс между задержкой и числом системных вызовов. */
    private static final int READ_MS = 20;

    private final int sampleRate;
    private final int readBytes;

    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;
    private Listener listener;
    private int bufferBytes;
    private int source = -1;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor suppressor;
    private boolean aecWanted = true;

    public AudioIn() {
        this(SAMPLE_RATE);
    }

    public AudioIn(int sampleRate) {
        this.sampleRate = sampleRate <= 0 ? SAMPLE_RATE : sampleRate;
        this.readBytes = Math.max(2, (this.sampleRate * READ_MS / 1000) * 2);
    }

    public int sampleRate() {
        return sampleRate;
    }

    public boolean isRunning() {
        return running;
    }

    /** Выбран ли VOICE_COMMUNICATION (то есть доступно ли системное эхоподавление). */
    public boolean voiceCommunication() {
        return source == MediaRecorder.AudioSource.VOICE_COMMUNICATION;
    }

    public String sourceName() {
        if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
            return "VOICE_COMMUNICATION";
        }
        if (source == MediaRecorder.AudioSource.MIC) {
            return "MIC";
        }
        return "none";
    }

    /** Размер буфера записи в байтах (после успешного start). */
    public int bufferBytes() {
        return bufferBytes;
    }

    /**
     * Открыть микрофон и начать отдавать куски.
     *
     * @return false, если микрофон недоступен (слушателю уже пришёл onError)
     */
    public synchronized boolean start(Listener l) {
        if (running) {
            return true;
        }
        if (l == null) {
            return false;
        }
        listener = l;
        try {
            int min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                min = sampleRate; // 500 мс на 16 кГц — разумный запас, если платформа молчит
            }
            bufferBytes = Math.max(min * 2, readBytes * 4);
            bufferBytes &= ~1;

            record = open(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
            if (record == null) {
                record = open(MediaRecorder.AudioSource.MIC);
            }
            if (record == null) {
                notifyError("Не удалось открыть микрофон: устройство не поддерживает запись");
                return false;
            }
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                notifyError("Микрофон не начал запись (возможно, занят другим приложением)");
                releaseRecord();
                return false;
            }
            if (aecWanted) {
                enableEffects();
            }
        } catch (SecurityException e) {
            notifyError("Нет разрешения на запись с микрофона");
            releaseRecord();
            return false;
        } catch (RuntimeException e) {
            notifyError("Микрофон недоступен: " + e.getMessage());
            releaseRecord();
            return false;
        }

        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "AudioIn");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    /**
     * Включить/выключить эхоподавление. Работает и на ходу: при выключении эффекты
     * освобождаются, при включении создаются заново.
     */
    public synchronized void setAec(boolean on) {
        aecWanted = on;
        if (!on) {
            releaseEffects();
        } else if (record != null && running) {
            enableEffects();
        }
    }

    /** Действует ли эхоподавление прямо сейчас (не на всех устройствах бывает). */
    public boolean aecEnabled() {
        AcousticEchoCanceler c = aec;
        if (c == null) {
            return false;
        }
        try {
            return c.getEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Доступен ли платформенный AEC на этом устройстве вообще. */
    public static boolean aecAvailable() {
        try {
            return AcousticEchoCanceler.isAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Доступно ли платформенное подавление шума. */
    public static boolean noiseSuppressorAvailable() {
        try {
            return NoiseSuppressor.isAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void enableEffects() {
        AudioRecord r = record;
        if (r == null) {
            return;
        }
        int session = r.getAudioSessionId();
        if (aec == null) {
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(session);
                    if (aec != null) {
                        aec.setEnabled(true);
                    }
                }
            } catch (RuntimeException e) {
                aec = null; // эффект отдал ошибку — работаем без него
            }
        }
        if (suppressor == null) {
            try {
                if (NoiseSuppressor.isAvailable()) {
                    suppressor = NoiseSuppressor.create(session);
                    if (suppressor != null) {
                        suppressor.setEnabled(true);
                    }
                }
            } catch (RuntimeException e) {
                suppressor = null;
            }
        }
    }

    private void releaseEffects() {
        AcousticEchoCanceler c = aec;
        aec = null;
        if (c != null) {
            try {
                c.setEnabled(false);
            } catch (RuntimeException ignored) {
                // эффект уже неактивен
            }
            try {
                c.release();
            } catch (RuntimeException ignored) {
                // уже освобождён
            }
        }
        NoiseSuppressor s = suppressor;
        suppressor = null;
        if (s != null) {
            try {
                s.setEnabled(false);
            } catch (RuntimeException ignored) {
                // эффект уже неактивен
            }
            try {
                s.release();
            } catch (RuntimeException ignored) {
                // уже освобождён
            }
        }
    }

    private AudioRecord open(int src) {
        try {
            AudioRecord r = new AudioRecord(src, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                try {
                    r.release();
                } catch (RuntimeException ignored) {
                    // нечего освобождать
                }
                return null;
            }
            source = src;
            return r;
        } catch (SecurityException e) {
            throw e;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void loop() {
        byte[] buf = new byte[readBytes];
        while (running) {
            AudioRecord r = record;
            if (r == null) {
                break;
            }
            int n;
            try {
                n = r.read(buf, 0, buf.length);
            } catch (SecurityException e) {
                notifyError("Разрешение на микрофон отозвано");
                break;
            } catch (RuntimeException e) {
                n = -1;
            }
            if (n < 0) {
                if (running) {
                    notifyError("Ошибка чтения с микрофона");
                }
                break;
            }
            if (n == 0) {
                continue;
            }
            Listener l = listener;
            if (l != null) {
                try {
                    l.onPcm(buf, n);
                } catch (RuntimeException ignored) {
                    // сбой в обработчике не должен убивать захват
                }
            }
        }
    }

    private void notifyError(String msg) {
        Listener l = listener;
        if (l != null) {
            try {
                l.onError(msg);
            } catch (RuntimeException ignored) {
                // слушатель уже развалился — не мешаем остановке
            }
        }
    }

    /** Остановить и освободить микрофон. Идемпотентно. */
    public synchronized void stop() {
        running = false;
        boolean self = thread == Thread.currentThread();
        releaseRecord();
        Thread t = thread;
        thread = null;
        if (t != null && !self) {
            try {
                t.join(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        listener = null;
    }

    private void releaseRecord() {
        releaseEffects();
        AudioRecord r = record;
        record = null;
        if (r != null) {
            try {
                if (r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    r.stop();
                }
            } catch (RuntimeException ignored) {
                // запись уже остановлена
            }
            try {
                r.release();
            } catch (RuntimeException ignored) {
                // уже освобождён
            }
        }
        source = -1;
    }
}
