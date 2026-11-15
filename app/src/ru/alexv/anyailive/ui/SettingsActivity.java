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
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import ru.alexv.anyailive.R;
import ru.alexv.anyailive.data.ChatStore;
import ru.alexv.anyailive.data.SettingsStore;

/**
 * Настройки голоса: движок, язык, модели распознавания и озвучки, голос,
 * перебивание, запись аудио. Здесь же экспорт всей переписки в JSON.
 */
public class SettingsActivity extends BaseActivity {

    /** Значения движка в том же порядке, что и подписи в списке. */
    private static final String[] ENGINES = {"auto", "live", "cascade"};
    private static final String[] STT_ENGINES = {"system", "http"};

    private ru.alexv.anyailive.voice.SystemTts preview;
    private ru.alexv.anyailive.voice.AudioOut previewAudio;
    private SettingsStore settings;
    private Spinner engine;
    private Spinner sttEngine;
    private Spinner ttsEngine;
    private Spinner systemVoice;
    private final java.util.ArrayList<String> ttsPackages = new java.util.ArrayList<String>();
    private final java.util.ArrayList<String> systemVoiceNames = new java.util.ArrayList<String>();
    private final java.util.ArrayList<String> systemVoiceLocales = new java.util.ArrayList<String>();
    private EditText language;
    private EditText stt;
    private EditText tts;
    private EditText voice;
    private CheckBox bargeIn;
    private CheckBox saveAudio;
    private CheckBox keepHistory;

    /** Индекс значения в массиве; -1 -> 0 (первый элемент как дефолт). */
    private static int indexOf(String[] values, String v) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(v)) {
                return i;
            }
        }
        return 0;
    }

    /** Локализованное имя темы по ключу: i-му ключу Themes соответствует i-я строка ресурса. */
    private static String themeLabel(String key, String[] labels) {
        for (int i = 0; i < Themes.count(); i++) {
            if (Themes.keyAt(i).equals(key)) {
                return labels[Math.min(i, labels.length - 1)];
            }
        }
        return labels[0];
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        settings = new SettingsStore(this);

        engine = (Spinner) findViewById(R.id.set_engine);
        language = (EditText) findViewById(R.id.set_language);
        language.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) refreshSystemVoices();
            }
        });
        stt = (EditText) findViewById(R.id.set_stt);
        tts = (EditText) findViewById(R.id.set_tts);
        voice = (EditText) findViewById(R.id.set_voice);
        bargeIn = (CheckBox) findViewById(R.id.set_barge_in);
        saveAudio = (CheckBox) findViewById(R.id.set_save_audio);
        keepHistory = (CheckBox) findViewById(R.id.set_keep_history);

        final TextView themeRow = (TextView) findViewById(R.id.set_theme);
        final String[] themeLabels = getResources().getStringArray(R.array.theme_labels);
        themeRow.setText(getString(R.string.theme_row) + ": " + themeLabel(settings.get(SettingsStore.THEME, Themes.DEFAULT), themeLabels));
        themeRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String current = settings.get(SettingsStore.THEME, Themes.DEFAULT);
                new android.app.AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(R.string.choose_theme)
                        .setSingleChoiceItems(themeLabels, indexOf(themeLabels, themeLabel(current, themeLabels)),
                                new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface d, int which) {
                                        d.dismiss();
                                        settings.set(SettingsStore.THEME, Themes.keyAt(which));
                                        recreate();
                                    }
                                })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        });

        // Язык интерфейса: авто / русский / английский.
        final String[] langLabels = {getString(R.string.lang_auto), getString(R.string.lang_ru), getString(R.string.lang_en)};
        final String[] langValues = {SettingsStore.LANG_AUTO, "ru", "en"};
        final TextView langRow = (TextView) findViewById(R.id.set_lang);
        String curLang = settings.get(SettingsStore.APP_LANG, SettingsStore.LANG_AUTO);
        langRow.setText(getString(R.string.lang_row) + ": " + langLabels[indexOf(langValues, curLang)]);
        langRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String cur = settings.get(SettingsStore.APP_LANG, SettingsStore.LANG_AUTO);
                new android.app.AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(R.string.choose_lang)
                        .setSingleChoiceItems(langLabels, indexOf(langValues, cur),
                                new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface d, int which) {
                                        d.dismiss();
                                        settings.set(SettingsStore.APP_LANG, langValues[which]);
                                        recreate();
                                    }
                                })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        });

        // Версия приложения — из сборки (version.txt).
        TextView versionRow = (TextView) findViewById(R.id.set_version);
        String versionName;
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionRow.setText(getString(R.string.version_row) + ": " + pi.versionName + " (" + pi.versionCode + ")");
            versionName = pi.versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            versionRow.setText(getString(R.string.version_row) + ": -");
            versionName = null;
        }

        // Проверка обновлений — GitHub Releases.
        final String currentVersion = versionName;
        final TextView updateRow = (TextView) findViewById(R.id.set_update);
        final android.os.Handler main = new android.os.Handler();
        updateRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateRow.setText(R.string.update_checking);
                ru.alexv.anyailive.net.Updater.check(main, currentVersion,
                        new ru.alexv.anyailive.net.Updater.Listener() {
                    @Override
                    public void onUpdate(String tag, String url) {
                        updateRow.setText(R.string.check_updates);
                        new android.app.AlertDialog.Builder(SettingsActivity.this)
                                .setTitle("anyAIlive")
                                .setMessage(getString(R.string.update_available, tag))
                                .setPositiveButton(android.R.string.ok,
                                        new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface d, int w) {
                                        ru.alexv.anyailive.net.Updater.openDownload(
                                                SettingsActivity.this, url);
                                    }
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    }

                    @Override
                    public void onNone(boolean wasError) {
                        updateRow.setText(R.string.check_updates);
                        int msg = wasError ? R.string.update_error : R.string.update_uptodate;
                        android.widget.Toast.makeText(SettingsActivity.this, msg,
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.item_spinner,
                new String[]{getString(R.string.engine_auto), getString(R.string.engine_live),
                        getString(R.string.engine_cascade)});
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        engine.setAdapter(adapter);

        sttEngine = (Spinner) findViewById(R.id.set_stt_engine);
        ArrayAdapter<String> sttAdapter = new ArrayAdapter<String>(this, R.layout.item_spinner,
                new String[]{getString(R.string.stt_engine_system), getString(R.string.stt_engine_http)});
        sttAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
        sttEngine.setAdapter(sttAdapter);
        sttEngine.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                stt.setEnabled("http".equals(STT_ENGINES[position]));
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
                stt.setEnabled(false);
            }
        });

        ttsEngine = (Spinner) findViewById(R.id.set_tts_engine);
        systemVoice = (Spinner) findViewById(R.id.set_system_voice);
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
        ttsEngine.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                refreshSystemVoices();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {
                refreshSystemVoices();
            }
        });
        refreshSystemVoices();
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
        // Missing/unknown provider stays system; legacy model names never opt into HTTP.
        int sttIndex = indexOf(STT_ENGINES, settings.get(SettingsStore.STT_ENGINE, "system"));
        sttEngine.setSelection(sttIndex);
        stt.setEnabled("http".equals(STT_ENGINES[sttIndex]));
        stt.setHint(R.string.settings_model_fallback);
        tts.setHint(R.string.settings_model_fallback);
        voice.setHint(R.string.settings_model_fallback);
        stt.setText(settings.get(SettingsStore.STT_MODEL, ""));
        tts.setText(settings.get(SettingsStore.TTS_MODEL, ""));
        voice.setText(settings.get(SettingsStore.VOICE, ""));
        refreshSystemVoices();
        bargeIn.setChecked(settings.getBool(SettingsStore.BARGE_IN, true));
        saveAudio.setChecked(settings.getBool(SettingsStore.SAVE_AUDIO, false));
        keepHistory.setChecked(settings.getBool(SettingsStore.KEEP_HISTORY, true));
    }

    private void save() {
        settings.set(SettingsStore.TTS_ENGINE, ttsPackages.get(ttsEngine.getSelectedItemPosition()));
        settings.set(SettingsStore.ENGINE, ENGINES[engine.getSelectedItemPosition()]);
        settings.set(SettingsStore.STT_ENGINE, STT_ENGINES[sttEngine.getSelectedItemPosition()]);
        settings.set(SettingsStore.LANGUAGE, language.getText().toString().trim());
        settings.set(SettingsStore.STT_MODEL, stt.getText().toString().trim());
        settings.set(SettingsStore.TTS_MODEL, tts.getText().toString().trim());
        settings.set(SettingsStore.VOICE, voice.getText().toString().trim());
        settings.set(ru.alexv.anyailive.voice.SystemTts.SYSTEM_VOICE_SETTING,
                systemVoice == null || systemVoice.getSelectedItemPosition() < 0
                        || systemVoiceNames.isEmpty() ? ""
                        : systemVoiceNames.get(systemVoice.getSelectedItemPosition()));
        settings.setBool(SettingsStore.BARGE_IN, bargeIn.isChecked());
        settings.setBool(SettingsStore.SAVE_AUDIO, saveAudio.isChecked());
        settings.setBool(SettingsStore.KEEP_HISTORY, keepHistory.isChecked());
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
    }

    private void refreshSystemVoices() {
        final String lang = language == null ? "" : language.getText().toString().trim();
        final String selected = settings == null ? "" : settings.get(
                ru.alexv.anyailive.voice.SystemTts.SYSTEM_VOICE_SETTING, "");
        final java.util.ArrayList<String> labels = new java.util.ArrayList<String>();
        systemVoiceNames.clear();
        systemVoiceLocales.clear();
        labels.add(getString(R.string.settings_system_voice_default));
        systemVoiceNames.add("");
        systemVoiceLocales.add("");
        final String packageName = ttsPackages.isEmpty() ? "" : ttsPackages.get(ttsEngine.getSelectedItemPosition());
        final android.speech.tts.TextToSpeech[] holder = new android.speech.tts.TextToSpeech[1];
        holder[0] = new android.speech.tts.TextToSpeech(this,
                new android.speech.tts.TextToSpeech.OnInitListener() {
                    @Override public void onInit(int status) {
                        android.speech.tts.TextToSpeech probe = holder[0];
                        if (probe == null) return;
                        if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                            for (ru.alexv.anyailive.voice.SystemTts.VoiceInfo v
                                    : ru.alexv.anyailive.voice.SystemTts.voicesForLanguage(probe, lang)) {
                                systemVoiceNames.add(v.name);
                                systemVoiceLocales.add(v.locale);
                                labels.add(v.toString());
                            }
                            if (selected.length() > 0 && !systemVoiceNames.contains(selected)) {
                                labels.add(getString(R.string.settings_system_voice_missing) + ": " + selected);
                                systemVoiceNames.add(selected);
                                systemVoiceLocales.add("");
                            }
                            ArrayAdapter<String> a = new ArrayAdapter<String>(SettingsActivity.this,
                                    R.layout.item_spinner, labels);
                            a.setDropDownViewResource(R.layout.item_spinner_dropdown);
                            systemVoice.setAdapter(a);
                            int index = systemVoiceNames.indexOf(selected);
                            systemVoice.setSelection(index < 0 ? 0 : index);
                        }
                        holder[0].shutdown();
                    }
                }, packageName.length() == 0 ? null : packageName);
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
