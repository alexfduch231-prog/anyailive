package ru.alexv.voicebridge.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ru.alexv.voicebridge.R;
import ru.alexv.voicebridge.data.ChatStore;
import ru.alexv.voicebridge.data.Model;
import ru.alexv.voicebridge.data.ModelsStore;
import ru.alexv.voicebridge.data.SecretStore;
import ru.alexv.voicebridge.data.SettingsStore;
import ru.alexv.voicebridge.voice.AudioIn;
import ru.alexv.voicebridge.voice.AudioOut;
import ru.alexv.voicebridge.voice.LiveEngineGemini;
import ru.alexv.voicebridge.voice.LiveEngineOpenAI;
import ru.alexv.voicebridge.voice.VoiceEngine;
import ru.alexv.voicebridge.voice.Wav;

/**
 * Живой голос: микрофон льётся в движок потоком, ответ звучит сразу, экран показывает субтитры.
 *
 * Экран ничего не знает про протоколы: он выбирает движок по флагу модели и перекладывает
 * PCM между {@link AudioIn}, движком и {@link AudioOut}.
 */
public class LiveActivity extends Activity {

    public static final String EXTRA_CHAT_ID = "chat_id";
    private static final int REQ_MIC = 41;

    private ChatStore store;
    private ModelsStore models;
    private SecretStore secrets;

    private long chatId;
    private ChatStore.Chat chat;
    private Model model;
    private String apiKey;

    private TextView title, stateText, userText, aiText;
    private View circle;
    private EditText input;
    private Button send, interrupt, hangup;

    private VoiceEngine engine;
    private AudioIn mic;
    private AudioOut out;

    private volatile boolean micPaused;
    private volatile boolean running;
    private boolean duplex;
    private int lastState = VoiceEngine.IDLE;

    /** Писать ли аудио реплик модели в wav (настройка). */
    private boolean saveAudio;

    /** Накопитель PCM текущей реплики модели, если запись включена. */
    private ByteArrayOutputStream clip;

    private final StringBuilder heard = new StringBuilder();
    private final StringBuilder said = new StringBuilder();
    private Animation pulse;

    public static Intent intent(Context from, long chatId) {
        Intent i = new Intent(from, LiveActivity.class);
        i.putExtra(EXTRA_CHAT_ID, chatId);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live);
        store = new ChatStore(this);
        models = new ModelsStore(this);
        secrets = new SecretStore(this);

        chatId = getIntent().getLongExtra(EXTRA_CHAT_ID, 0);
        title = (TextView) findViewById(R.id.live_title);
        stateText = (TextView) findViewById(R.id.live_state);
        userText = (TextView) findViewById(R.id.live_user);
        aiText = (TextView) findViewById(R.id.live_ai);
        circle = findViewById(R.id.live_circle);
        input = (EditText) findViewById(R.id.live_input);
        send = (Button) findViewById(R.id.btn_live_send);
        interrupt = (Button) findViewById(R.id.btn_live_interrupt);
        hangup = (Button) findViewById(R.id.btn_live_hangup);

        pulse = new AlphaAnimation(1f, 0.35f);
        pulse.setDuration(1100);
        pulse.setRepeatMode(Animation.REVERSE);
        pulse.setRepeatCount(Animation.INFINITE);

        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendTyped();
            }
        });
        interrupt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (engine != null) {
                    engine.interrupt();
                }
                if (out != null) {
                    out.flush();
                }
            }
        });
        hangup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopAll();
                finish();
            }
        });

        load();
        if (chat == null || model == null) {
            toast(getString(R.string.live_no_model));
            finish();
            return;
        }
        title.setText(chat.title == null || chat.title.length() == 0 ? model.name : chat.title);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        } else {
            connect();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_MIC) {
            return;
        }
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            connect();
        } else {
            toast(getString(R.string.live_mic_denied));
            finish();
        }
    }

    private void load() {
        chat = findChat(chatId);
        model = chat == null ? null : models.byId(chat.modelId);
        apiKey = model == null || model.keyRef == null ? null : secrets.get(model.keyRef);
    }

    private ChatStore.Chat findChat(long id) {
        List<ChatStore.Chat> all = store.chats();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id == id) {
                return all.get(i);
            }
        }
        return null;
    }

    private void connect() {
        String protocol = model.liveProtocol == null ? Model.LIVE_NONE : model.liveProtocol;
        String prompt = getString(R.string.live_system_prompt);
        if (Model.LIVE_OPENAI.equals(protocol)) {
            LiveEngineOpenAI e = new LiveEngineOpenAI(model, apiKey, prompt);
            e.setSink(new OpenAiSink());
            engine = e;
        } else if (Model.LIVE_GEMINI.equals(protocol)) {
            LiveEngineGemini g = new LiveEngineGemini(model, apiKey, prompt);
            g.setSink(new GeminiSink());
            engine = g;
        } else {
            toast(getString(R.string.live_no_live));
            finish();
            return;
        }

        out = new AudioOut(AudioOut.SAMPLE_RATE, 1);
        if (!out.start()) {
            toast(getString(R.string.voice_speaker_failed));
        }
        duplex = AudioIn.aecAvailable();
        if (!duplex) {
            toast(getString(R.string.live_half_duplex));
        }
        saveAudio = new SettingsStore(this).getBool(SettingsStore.SAVE_AUDIO, false);
        clip = saveAudio ? new ByteArrayOutputStream() : null;
        mic = new AudioIn(AudioIn.SAMPLE_RATE);
        running = true;
        engine.start(new EngineCallback());
        if (!mic.start(new MicListener())) {
            toast(getString(R.string.voice_mic_busy));
            stopAll();
            finish();
            return;
        }
        showState(VoiceEngine.LISTENING);
    }

    private void sendTyped() {
        String text = input.getText().toString().trim();
        if (text.length() == 0 || engine == null) {
            return;
        }
        input.setText("");
        store.addMessage(chat.id, "user", text);
        heard.setLength(0);
        heard.append(text);
        userText.setText(text);
        engine.sendText(text);
    }

    private void stopAll() {
        running = false;
        if (mic != null) {
            mic.stop();
            mic = null;
        }
        if (engine != null) {
            engine.stop();
            engine = null;
        }
        if (out != null) {
            out.flush();
            out.stop();
            out.release();
            out = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAll();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAll();
    }

    private void showState(final int state) {
        lastState = state;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                int text;
                switch (state) {
                    case VoiceEngine.LISTENING: text = R.string.live_listening; break;
                    case VoiceEngine.THINKING: text = R.string.live_thinking; break;
                    case VoiceEngine.SPEAKING: text = R.string.live_speaking; break;
                    default: text = R.string.live_idle; break;
                }
                stateText.setText(text);
                if (state == VoiceEngine.LISTENING || state == VoiceEngine.SPEAKING) {
                    circle.startAnimation(pulse);
                } else {
                    circle.clearAnimation();
                }
                if (!duplex) {
                    micPaused = state == VoiceEngine.SPEAKING;
                }
            }
        });
    }

    private void appendAi(final String piece) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                said.append(piece);
                aiText.setText(said.toString());
            }
        });
    }

    private void appendUser(final String piece) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                heard.append(piece);
                userText.setText(heard.toString());
            }
        });
    }

    private void play(byte[] pcm) {
        if (pcm == null || pcm.length == 0) {
            return;
        }
        AudioOut o = out;
        if (o != null) {
            o.write(pcm, pcm.length);
        }
        ByteArrayOutputStream c = clip;
        if (c != null) {
            c.write(pcm, 0, pcm.length);
        }
    }

    /** Реплика пользователя: пишем одной строкой и чистим субтитр. */
    private void persistUser(String text) {
        final String clean = text == null ? "" : text.trim();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                heard.setLength(0);
                userText.setText("");
            }
        });
        if (clean.length() > 0) {
            store.addMessage(chat.id, "user", clean);
        }
    }

    /** Реплика модели: пишем одной строкой, аудио — в wav рядом с сообщением. */
    private void persistAssistant(String text) {
        final String clean = text == null ? "" : text.trim();
        String path = saveClip();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                said.setLength(0);
                aiText.setText("");
            }
        });
        if (clean.length() == 0) {
            return;
        }
        store.addMessage(chat.id, "assistant", clean,
                path == null ? "text" : "voice", path, false);
    }

    /** Сбрасывает накопленный PCM реплики в wav; null — писать нечего или нельзя. */
    private String saveClip() {
        ByteArrayOutputStream c = clip;
        if (c == null || c.size() == 0) {
            return null;
        }
        byte[] pcm = c.toByteArray();
        c.reset();
        try {
            File dir = new File(getFilesDir(), "audio/" + chat.id);
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File file = new File(dir, System.currentTimeMillis() + ".wav");
            if (!Wav.writeFile(file, pcm, AudioOut.SAMPLE_RATE, 1)) {
                return null;
            }
            return file.getAbsolutePath();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    /** Микрофон 16 кГц -> движок. */
    private class MicListener implements AudioIn.Listener {
        @Override
        public void onPcm(byte[] data, int len) {
            VoiceEngine e = engine;
            if (e == null || !running || micPaused || len <= 0) {
                return;
            }
            byte[] copy = new byte[len];
            System.arraycopy(data, 0, copy, 0, len);
            e.sendAudio(copy);
        }

        @Override
        public void onError(String message) {
            if (running) {
                toast(message);
            }
        }
    }

    /** Общая часть колбэков движка для экрана. */
    private abstract class UiSink {
        void audio(byte[] pcm) { play(pcm); }

        void bargeIn() {
            if (out != null) {
                out.flush();
            }
            if (clip != null) {
                clip.reset();
            }
            micPaused = false;
        }

        void state(int state) { showState(state); }

        void user(String delta) { appendUser(delta); }

        void assistant(String delta) { appendAi(delta); }

        void error(String message) {
            if (!isFinishing()) {
                toast(message);
            }
            stopAll();
        }
    }

    private class OpenAiSink extends UiSink implements LiveEngineOpenAI.Sink {
        @Override
        public void onAudio(byte[] pcm24k) { audio(pcm24k); }

        @Override
        public void onBargeIn() { bargeIn(); }

        @Override
        public void onState(int state) { state(state); }

        @Override
        public void onUserText(String delta) { user(delta); }

        @Override
        public void onAssistantText(String delta) { assistant(delta); }

        @Override
        public void onError(String message) { error(message); }
    }

    private class GeminiSink extends UiSink implements LiveEngineGemini.Sink {
        @Override
        public void onAudio(byte[] pcm24k) { audio(pcm24k); }

        @Override
        public void onBargeIn() { bargeIn(); }

        @Override
        public void onState(int state) { state(state); }

        @Override
        public void onUserText(String delta) { user(delta); }

        @Override
        public void onAssistantText(String delta) { assistant(delta); }

        @Override
        public void onError(String message) { error(message); }
    }

    private class EngineCallback implements VoiceEngine.Callback {
        @Override
        public void onState(int state) { showState(state); }

        @Override
        public void onUserText(final String text) {
            persistUser(text);
        }

        @Override
        public void onAssistantText(final String text) {
            persistAssistant(text);
        }

        @Override
        public void onError(final String message) {
            if (!isFinishing()) {
                toast(message);
            }
        }
    }
}
