package ru.alexv.anyailive.voice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Системный TTS с двумя режимами:
 *
 * - файл: synthesizeToFile() в кэш, оттуда WAV → PCM нужной частоты. Такой звук идёт через
 *   {@link AudioOut}, а значит глушится мгновенно (перебивание, stop()) и не смешивается
 *   с системным микшером;
 * - прямой: speak() — если файл получить нельзя (старый Android, TTS без synthesizeToFile).
 *
 * Кто озвучивает сам (прямой режим), возвращает {@code pcm == null}: звук уже играет система.
 * Колбэки приходят в главный поток.
 */
public final class SystemTts {

    /** Что делать после озвучки. */
    public interface Callback {
        /**
         * @param utteranceId тот же, что передан в {@link #speak}
         * @param pcm         PCM16 моно targetRate (null — озвучивал сам системный TTS)
         */
        void onSpoken(String utteranceId, byte[] pcm);

        /** Фраза не озвучилась (нет данных языка, сбой движка). */
        void onFailed(String utteranceId, String reason);

        /** Синтез вообще недоступен — дальше говорить нечем. */
        void onUnavailable(String message);
    }

    /** Ниже этого значения считаем, что файлового режима нет (Android < 5). */
    private static final int MIN_FILE_MODE_SDK = 21;

    public static final String SYSTEM_VOICE_SETTING = "tts_system_voice";

    /** App-local descriptor used by the settings picker; no system setting is changed. */
    public static final class VoiceInfo {
        public final String name;
        public final String locale;

        public VoiceInfo(String name, String locale) {
            this.name = name == null ? "" : name;
            this.locale = locale == null ? "" : locale;
        }

        @Override public String toString() {
            return name + " — " + locale;
        }
    }

    /** Enumerate voices exposed by this engine and retain only voices matching the requested language. */
    public static List<VoiceInfo> voicesForLanguage(TextToSpeech engine, String languageTag) {
        List<VoiceInfo> result = new java.util.ArrayList<VoiceInfo>();
        if (engine == null) return result;
        try {
            for (Voice voice : engine.getVoices()) {
                if (voice == null || voice.getName() == null || voice.getName().length() == 0) continue;
                Locale locale = voice.getLocale();
                String localeTag = locale == null ? "" : locale.toLanguageTag();
                if (voiceLocaleCompatible(localeTag, languageTag)) {
                    result.add(new VoiceInfo(voice.getName(), localeTag));
                }
            }
        } catch (RuntimeException ignored) {
            // Some old engines throw while querying voices; the picker then stays empty.
        }
        return result;
    }

    /** Language-aware matching: requested ru also accepts ru-RU, but never en-US. */
    public static boolean voiceLocaleCompatible(String voiceLocaleTag, String requestedLanguageTag) {
        if (voiceLocaleTag == null || voiceLocaleTag.length() == 0
                || requestedLanguageTag == null || requestedLanguageTag.length() == 0) return false;
        Locale voice = Locale.forLanguageTag(voiceLocaleTag.replace('_', '-'));
        Locale requested = Locale.forLanguageTag(requestedLanguageTag.replace('_', '-'));
        String voiceLanguage = voice.getLanguage();
        String requestedLanguage = requested.getLanguage();
        return voiceLanguage.length() > 0 && voiceLanguage.equalsIgnoreCase(requestedLanguage);
    }


    private final Context ctx;
    private final String language;
    private final int targetRate;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, File> files = new java.util.concurrent.ConcurrentHashMap<String, File>();
    private final PendingSpeechQueue<Pending> backlog = new PendingSpeechQueue<Pending>(20);

    private TextToSpeech tts;
    private String requestedEngine;
    private Callback cb;
    private boolean ready;
    private boolean fileMode;
    private boolean released;
    private int seq;
    private ExecutorService worker;

    private static final class Pending {
        final String text;
        final String id;

        Pending(String text, String id) {
            this.text = text;
            this.id = id;
        }
    }

    /** Озвучивать через AudioOut в 24 кГц (каскад на системных API). */
    public SystemTts(Context ctx, String language) {
        this(ctx, language, AudioOut.SAMPLE_RATE);
    }

    /**
     * @param targetRate 0 или меньше — только speak(), без файлов и AudioOut
     */
    public SystemTts(Context ctx, String language, int targetRate) {
        this.ctx = ctx;
        this.language = language == null || language.length() == 0
                ? CascadeSystem.DEFAULT_LANGUAGE : language;
        this.targetRate = targetRate;
    }

    public void setCallback(Callback cb) {
        this.cb = cb;
    }

    /** true, если озвучка идёт в PCM через файл (иначе системный микшер). */
    public boolean fileMode() {
        return fileMode;
    }

    public boolean ready() {
        return ready;
    }

    /** Создать TTS. @return false — синтез недоступен (onUnavailable уже вызван) */
    public boolean start() {
        if (tts != null) {
            return true;
        }
        if (ctx == null) {
            unavailable("Синтез речи недоступен на этом устройстве");
            return false;
        }
        try {
            java.util.List<String> installed = new java.util.ArrayList<String>();
            android.content.Intent query = new android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
            for (android.content.pm.ResolveInfo info : ctx.getPackageManager().queryIntentServices(query, 0)) {
                if (info.serviceInfo != null) installed.add(info.serviceInfo.packageName);
            }
            String saved = new ru.alexv.anyailive.data.SettingsStore(ctx).get(
                    ru.alexv.anyailive.data.SettingsStore.TTS_ENGINE, "");
            requestedEngine = TtsEnginePolicy.resolve(saved, installed);
            tts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            onInitDone(status);
                        }
                    });
                }
            }, requestedEngine);
        } catch (RuntimeException e) {
            tts = null;
            unavailable("Не удалось запустить синтез речи: " + e.getMessage());
            return false;
        }
        return true;
    }

    private void onInitDone(int status) {
        if (released) {
            return;
        }
        if (status != TextToSpeech.SUCCESS || tts == null) {
            unavailable("Синтез речи недоступен на этом устройстве");
            return;
        }
        try {
            tts.setOnUtteranceProgressListener(new Progress());
            int r = tts.setLanguage(locale(language));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault());
            }
            String selectedVoice = new ru.alexv.anyailive.data.SettingsStore(ctx).get(
                    SYSTEM_VOICE_SETTING, "");
            if (selectedVoice != null && selectedVoice.length() > 0) {
                boolean applied = false;
                try {
                    for (Voice candidate : tts.getVoices()) {
                        if (candidate != null && selectedVoice.equals(candidate.getName())
                                && voiceLocaleCompatible(candidate.getLocale() == null ? "" : candidate.getLocale().toLanguageTag(), language)) {
                            tts.setVoice(candidate);
                            applied = true;
                            break;
                        }
                    }
                } catch (RuntimeException ignored) {
                    // Missing/unavailable voice: keep the engine's language default.
                }
                if (!applied) {
                    // Keep the app-local selection so SettingsActivity can report it as missing.
                }
            }
            tts.setSpeechRate(1.0f);
        } catch (RuntimeException e) {
            unavailable("Синтез речи не настроился: " + e.getMessage());
            return;
        }
        fileMode = targetRate > 0 && android.os.Build.VERSION.SDK_INT >= MIN_FILE_MODE_SDK
                && ctx != null;
        ready = true;
        while (!backlog.isEmpty()) {
            Pending p = backlog.poll();
            speak(p.text, p.id);
        }
    }

    static Locale locale(String tag) {
        if (tag == null || tag.length() == 0) {
            return Locale.getDefault();
        }
        if (tag.length() >= 4 && (tag.charAt(2) == '-' || tag.charAt(2) == '_')) {
            return new Locale(tag.substring(0, 2), tag.substring(3));
        }
        return new Locale(tag);
    }

    /**
     * Озвучить фразу. Идентификатор придумывает вызывающий — по нему потом придёт onSpoken.
     */
    public void speak(String text, String utteranceId) {
        if (text == null || text.length() == 0) {
            return;
        }
        String id = utteranceId == null ? "vb" + (++seq) : utteranceId;
        if (!ready) {
            if (!backlog.offer(new Pending(text, id))) {
                postFailed(id, "Очередь синтеза заполнена: фраза не принята");
            }
            return;
        }
        if (fileMode && synthesize(text, id)) {
            return;
        }
        speakDirect(text, id);
    }

    private boolean synthesize(String text, String id) {
        File file = new File(ctx.getCacheDir(), id + "-" + (++seq) + ".wav");
        // Register before dispatch: completion may arrive from the Binder thread immediately.
        files.put(id, file);
        try {
            int r = tts.synthesizeToFile(text, null, file, id);
            if (r != TextToSpeech.SUCCESS) {
                files.remove(id, file);
                Wav.deleteQuietly(file);
                fileMode = false;
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            files.remove(id, file);
            Wav.deleteQuietly(file);
            fileMode = false;
            return false;
        }
    }

    private void speakDirect(String text, String id) {
        try {
            if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
                postFailed(id, "движок синтеза отказал");
            }
        } catch (RuntimeException e) {
            postFailed(id, "движок синтеза отказал");
        }
    }

    private final class Progress extends UtteranceProgressListener {
        @Override
        public void onStart(String utteranceId) {
        }

        @Override
        public void onDone(final String utteranceId) {
            final File file = files.remove(utteranceId);
            if (file == null) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        Callback c = cb;
                        if (c != null && !released) {
                            c.onSpoken(utteranceId, null);
                        }
                    }
                });
                return;
            }
            worker().execute(new Runnable() {
                @Override
                public void run() {
                    byte[] wav = Wav.readFile(file);
                    Wav.deleteQuietly(file);
                    final byte[] pcm = wav == null ? null : Wav.pcmFromWav(wav, targetRate);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            Callback c = cb;
                            if (c == null || released) {
                                return;
                            }
                            if (pcm == null) {
                                c.onFailed(utteranceId, "файл синтеза не читается");
                            } else {
                                c.onSpoken(utteranceId, pcm);
                            }
                        }
                    });
                }
            });
        }

        @Override
        public void onError(String utteranceId) {
            if (utteranceId != null) Wav.deleteQuietly(files.remove(utteranceId));
            postFailed(utteranceId, "ошибка синтеза");
        }
    }

    private void postFailed(final String id, final String reason) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Callback c = cb;
                if (c != null && !released) {
                    c.onFailed(id, reason);
                }
            }
        });
    }

    private void unavailable(final String message) {
        ready = false;
        final Callback c = cb;
        if (c != null) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    c.onUnavailable(message);
                }
            });
        }
    }

    private synchronized ExecutorService worker() {
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor();
        }
        return worker;
    }

    /** Замолчать: обрывает текущую фразу и выбрасывает очередь. */
    public void stop() {
        backlog.clear();
        for (File f : files.values()) {
            Wav.deleteQuietly(f);
        }
        files.clear();
        TextToSpeech t = tts;
        if (t != null) {
            try {
                t.stop();
            } catch (RuntimeException ignored) {
                // не инициализирован
            }
        }
    }

    /** Замолчать и освободить движок. После этого объект не используется. */
    public void release() {
        released = true;
        ready = false;
        stop();
        TextToSpeech t = tts;
        tts = null;
        if (t != null) {
            try {
                t.shutdown();
            } catch (RuntimeException ignored) {
                // уже выключен
            }
        }
        synchronized (this) {
            if (worker != null) {
                worker.shutdownNow();
                worker = null;
            }
        }
    }

}
