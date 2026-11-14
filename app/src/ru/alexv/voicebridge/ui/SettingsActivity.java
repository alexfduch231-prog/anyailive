package ru.alexv.voicebridge.ui;

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

import ru.alexv.voicebridge.R;
import ru.alexv.voicebridge.data.ChatStore;
import ru.alexv.voicebridge.data.SettingsStore;

/**
 * Настройки голоса: движок, язык, модели распознавания и озвучки, голос,
 * перебивание, запись аудио. Здесь же экспорт всей переписки в JSON.
 */
public class SettingsActivity extends Activity {

    /** Значения движка в том же порядке, что и подписи в списке. */
    private static final String[] ENGINES = {"auto", "live", "cascade"};

    private SettingsStore settings;
    private Spinner engine;
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

        load();

        ((Button) findViewById(R.id.btn_save_settings)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
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
        stt.setText(settings.get(SettingsStore.STT_MODEL, "whisper-1"));
        tts.setText(settings.get(SettingsStore.TTS_MODEL, "tts-1"));
        voice.setText(settings.get(SettingsStore.VOICE, "alloy"));
        bargeIn.setChecked(settings.getBool(SettingsStore.BARGE_IN, true));
        saveAudio.setChecked(settings.getBool(SettingsStore.SAVE_AUDIO, false));
        keepHistory.setChecked(settings.getBool(SettingsStore.KEEP_HISTORY, true));
    }

    private void save() {
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
    protected void onPause() {
        super.onPause();
        save();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Exporter.finishSave(this, requestCode, resultCode, data);
    }
}
