package ru.alexv.anyailive.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import java.util.List;

import ru.alexv.anyailive.R;
import ru.alexv.anyailive.data.ChatStore;
import ru.alexv.anyailive.data.SettingsStore;

/**
 * Настройки голоса: движок, язык, модели распознавания и озвучки, голос,
 * перебивание, запись аудио. Здесь же экспорт всей переписки в JSON.
 */
public class SettingsActivity extends Activity {

    /** Значения движка в том же порядке, что и подписи в списке. */
    private static final String[] ENGINES = {"auto", "live", "cascade"};

    private ru.alexv.anyailive.voice.SystemTts preview;
    private ru.alexv.anyailive.voice.AudioOut previewAudio;
    private SettingsStore settings;
    private Spinner engine;
    private Spinner ttsEngine;
    private final java.util.ArrayList<String> ttsPackages = new java.util.ArrayList<String>();
    private EditText language;
    private EditText stt;
    private EditText tts;
    private EditText voice;
    private CheckBox bargeIn;
    private CheckBox saveAudio;
    private CheckBox keepHistory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        settings = new SettingsStore(this);

        engine = (Spinner) findViewById(R.id.set_engine);
        language = (EditText) findViewById(R.id.set_language);
        stt = (EditText) findViewById(R.id.set_stt);
        tts = (EditText) findViewById(R.id.set_tts);
        voice = (EditText) findViewById(R.id.set_voice);
        bargeIn = (CheckBox) findViewById(R.id.set_barge_in);
        saveAudio = (CheckBox) findViewById(R.id.set_save_audio);
        keepHistory = (CheckBox) findViewById(R.id.set_keep_history);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.item_spinner,
                new String[]{getString(R.string.engine_auto), getString(R.string.engine_live),
                        getString(R.string.engine_cascade)});
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        engine.setAdapter(adapter);

        ttsEngine = (Spinner) findViewById(R.id.set_tts_engine);
        java.util.ArrayList<String> labels = new java.util.ArrayList<String>();
        labels.add("По умолчанию в Android");
        ttsPackages.add("");
        String savedTts = settings.get(SettingsStore.TTS_ENGINE, "");
        Intent service = new Intent(android.speech.tts.TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
        for (android.content.pm.ResolveInfo info : getPackageManager().queryIntentServices(service, 0)) {
            if (info.serviceInfo == null || ttsPackages.contains(info.serviceInfo.packageName)) continue;
            ttsPackages.add(info.serviceInfo.packageName);
            labels.add(info.loadLabel(getPackageManager()).toString() + " — " + info.serviceInfo.packageName);
        }
        if (!ttsPackages.contains(savedTts)) {
            ttsPackages.add(savedTts);
            labels.add("Не установлен: " + savedTts);
        }
        ArrayAdapter<String> ttsAdapter = new ArrayAdapter<String>(this, R.layout.item_spinner, labels);
        ttsAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        ttsEngine.setAdapter(ttsAdapter);
        ttsEngine.setSelection(ttsPackages.indexOf(savedTts));
        load();

        ((Button) findViewById(R.id.btn_save_settings)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        findViewById(R.id.btn_test_tts).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (preview != null) { stopPreview(); return; }
                Toast.makeText(SettingsActivity.this, "Используются сохранённые настройки озвучки", Toast.LENGTH_SHORT).show();
                preview = new ru.alexv.anyailive.voice.SystemTts(SettingsActivity.this,
                        settings.get(SettingsStore.LANGUAGE, "ru-RU"), 24000);
                final ru.alexv.anyailive.voice.SystemTts activePreview = preview;
                preview.setCallback(new ru.alexv.anyailive.voice.SystemTts.Callback() {
                    public void onSpoken(String id, byte[] pcm) {
                        if (preview != activePreview) return;
                        if (pcm == null) {
                            Toast.makeText(SettingsActivity.this, "Движок озвучил напрямую, PCM не получен", Toast.LENGTH_LONG).show();
                            stopPreview();
                            return;
                        }
                        android.util.Log.i("AnyAiTtsProbe", "PCM bytes=" + pcm.length + " rate=24000");
                        Toast.makeText(SettingsActivity.this, "PCM получен: " + pcm.length + " байт. Повторное нажатие — стоп.", Toast.LENGTH_LONG).show();
                        previewAudio = new ru.alexv.anyailive.voice.AudioOut(24000, 1);
                        previewAudio.start();
                        previewAudio.write(pcm, pcm.length);
                    }
                    public void onFailed(String id, String reason) { failed(reason); }
                    public void onUnavailable(String reason) { failed(reason); }
                    private void failed(String reason) {
                        if (preview != activePreview) return;
                        Toast.makeText(SettingsActivity.this, reason, Toast.LENGTH_LONG).show();
                        stopPreview();
                    }
                });
                if (preview.start() && preview != null)
                    preview.speak("Привет! Это проверка русского голоса в приложении anyAIlive.", "preview");
            }
        });
        ((Button) findViewById(R.id.btn_export_all)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportAll();
            }
        });
    }

    private void load() {
        engine.setSelection(indexOfEngine(settings.get(SettingsStore.ENGINE, "auto")));
        language.setText(settings.get(SettingsStore.LANGUAGE, getString(R.string.voice_language_default)));
        stt.setHint("Из настроек модели");
        tts.setHint("Из настроек модели");
        voice.setHint("Из настроек модели");
        stt.setText(settings.get(SettingsStore.STT_MODEL, ""));
        tts.setText(settings.get(SettingsStore.TTS_MODEL, ""));
        voice.setText(settings.get(SettingsStore.VOICE, ""));
        bargeIn.setChecked(settings.getBool(SettingsStore.BARGE_IN, true));
        saveAudio.setChecked(settings.getBool(SettingsStore.SAVE_AUDIO, false));
        keepHistory.setChecked(settings.getBool(SettingsStore.KEEP_HISTORY, true));
    }

    private void save() {
        settings.set(SettingsStore.TTS_ENGINE, ttsPackages.get(ttsEngine.getSelectedItemPosition()));
        settings.set(SettingsStore.ENGINE, ENGINES[engine.getSelectedItemPosition()]);
        settings.set(SettingsStore.LANGUAGE, language.getText().toString().trim());
        settings.set(SettingsStore.STT_MODEL, stt.getText().toString().trim());
        settings.set(SettingsStore.TTS_MODEL, tts.getText().toString().trim());
        settings.set(SettingsStore.VOICE, voice.getText().toString().trim());
        settings.setBool(SettingsStore.BARGE_IN, bargeIn.isChecked());
        settings.setBool(SettingsStore.SAVE_AUDIO, saveAudio.isChecked());
        settings.setBool(SettingsStore.KEEP_HISTORY, keepHistory.isChecked());
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
    }

    private void stopPreview() {
        ru.alexv.anyailive.voice.SystemTts old = preview;
        preview = null;
        if (old != null) old.release();
        if (previewAudio != null) {
            previewAudio.release();
            previewAudio = null;
        }
    }

    @Override protected void onPause() {
        stopPreview();
        super.onPause();
    }

    private void exportAll() {
        ChatStore chats = new ChatStore(this);
        List<ChatStore.Chat> all = chats.chats();
        if (all.isEmpty()) {
            Toast.makeText(this, R.string.chats_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String json = Exporter.allJson(all, chats);
        Exporter.beginSave(this, Exporter.fileName(getString(R.string.export_prefix_all)), json);
    }

    private int indexOfEngine(String value) {
        for (int i = 0; i < ENGINES.length; i++) {
            if (ENGINES[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Exporter.finishSave(this, requestCode, resultCode, data);
    }
}
