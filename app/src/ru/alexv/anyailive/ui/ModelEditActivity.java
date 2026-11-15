package ru.alexv.anyailive.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import java.util.UUID;

import ru.alexv.anyailive.R;
import ru.alexv.anyailive.chat.Providers;
import ru.alexv.anyailive.data.Model;
import ru.alexv.anyailive.data.ModelsStore;
import ru.alexv.anyailive.data.SecretStore;

/**
 * Создание и правка модели. Сам ключ в базу не попадает: в модели только {@code keyRef},
 * значение — в {@link SecretStore}. Пустое поле ключа при сохранении = оставить прежний.
 */
public class ModelEditActivity extends BaseActivity {

    private static final String[] LIVE_VALUES = {
            Model.LIVE_NONE, Model.LIVE_OPENAI, Model.LIVE_GEMINI
    };

    private ModelsStore store;
    private SecretStore secrets;
    private Model model;
    private long modelId;

    private EditText fName;
    private EditText fBaseUrl;
    private EditText fModelId;
    private EditText fApiKey;
    private Spinner fLive;
    private EditText fLiveUrl;
    private EditText fLiveModel;
    private EditText fVoice;

    public static Intent intent(Activity from, long modelId) {
        Intent i = new Intent(from, ModelEditActivity.class);
        i.putExtra(ModelsActivity.EXTRA_MODEL_ID, modelId);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_edit);
        store = new ModelsStore(this);
        secrets = new SecretStore(this);

        fName = (EditText) findViewById(R.id.f_name);
        fBaseUrl = (EditText) findViewById(R.id.f_base_url);
        fModelId = (EditText) findViewById(R.id.f_model_id);
        fApiKey = (EditText) findViewById(R.id.f_api_key);
        fLive = (Spinner) findViewById(R.id.f_live_protocol);
        fLiveUrl = (EditText) findViewById(R.id.f_live_url);
        fLiveModel = (EditText) findViewById(R.id.f_live_model);
        fVoice = (EditText) findViewById(R.id.f_voice);
        fApiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        ArrayAdapter<CharSequence> protocols = ArrayAdapter.createFromResource(
                this, R.array.live_protocols, android.R.layout.simple_spinner_item);
        protocols.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fLive.setAdapter(protocols);

        modelId = getIntent().getLongExtra(ModelsActivity.EXTRA_MODEL_ID, 0);
        model = modelId > 0 ? store.byId(modelId) : null;
        if (savedInstanceState != null && model == null) {
            modelId = savedInstanceState.getLong("model_id", modelId);
        }
        fill();

        ((Button) findViewById(R.id.btn_save)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        ((Button) findViewById(R.id.btn_check_chat)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                check(0);
            }
        });
        ((Button) findViewById(R.id.btn_check_realtime)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                check(2);
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putLong("model_id", modelId);
    }

    private void fill() {
        if (model == null) {
            ((android.widget.TextView) findViewById(R.id.edit_title))
                    .setText(R.string.edit_title_new);
            fLive.setSelection(0);
            return;
        }
        ((android.widget.TextView) findViewById(R.id.edit_title))
                .setText(R.string.edit_title_edit);
        fName.setText(model.name);
        fBaseUrl.setText(model.baseUrl);
        fModelId.setText(model.modelId);
        fLiveUrl.setText(model.liveUrl);
        fLiveModel.setText(model.liveModelId);
        fVoice.setText(model.voice);
        String live = model.liveProtocol == null ? Model.LIVE_NONE : model.liveProtocol;
        for (int i = 0; i < LIVE_VALUES.length; i++) {
            if (LIVE_VALUES[i].equals(live)) {
                fLive.setSelection(i);
            }
        }
    }

    /** Собирает модель из полей; существующий объект правится на месте. */
    private Model gather() {
        Model m = model == null ? new Model() : model;
        m.name = fName.getText().toString().trim();
        m.baseUrl = fBaseUrl.getText().toString().trim();
        m.modelId = fModelId.getText().toString().trim();
        m.liveProtocol = LIVE_VALUES[fLive.getSelectedItemPosition()];
        m.liveUrl = emptyToNull(fLiveUrl.getText().toString());
        m.liveModelId = emptyToNull(fLiveModel.getText().toString());
        m.voice = emptyToNull(fVoice.getText().toString());
        return m;
    }

    private static String emptyToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() == 0 ? null : t;
    }

    private String typedKey() {
        String k = fApiKey.getText().toString().trim();
        return k.length() == 0 ? null : k;
    }

    private String currentKey() {
        String typed = typedKey();
        if (typed != null) {
            return typed;
        }
        return model == null || model.keyRef == null ? null : secrets.get(model.keyRef);
    }

    private boolean validate(Model m) {
        if (m.name.length() == 0) {
            Toast.makeText(this, R.string.need_name, Toast.LENGTH_SHORT).show();
            return false;
        }
        if (m.baseUrl.length() == 0) {
            Toast.makeText(this, R.string.need_base_url, Toast.LENGTH_SHORT).show();
            return false;
        }
        if (m.modelId.length() == 0) {
            Toast.makeText(this, R.string.need_model_id, Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void save() {
        Model m = gather();
        if (!validate(m)) {
            return;
        }
        boolean isNew = m.id <= 0;
        if (isNew) {
            m.keyRef = "model_" + UUID.randomUUID().toString().substring(0, 8);
            m.createdAt = System.currentTimeMillis();
            long id = store.insert(m);
            if (id <= 0) {
                Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show();
                return;
            }
            modelId = id;
            model = m;
        } else {
            store.update(m);
        }
        String typed = typedKey();
        if (typed != null) {
            if (m.keyRef == null || m.keyRef.length() == 0) {
                m.keyRef = "model_" + m.id;
                store.update(m);
            }
            secrets.put(m.keyRef, typed);
        }
        fApiKey.setText("");
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    /** 0 — чат, 2 — realtime; в фоне, результат в тост, для сохранённой модели — в last_check. */
    private void check(final int kind) {
        Model m = gather();
        final String key = currentKey();
        if (kind == 0 && !validate(m)) {
            return;
        }
        if (kind == 2 && !m.live()) {
            Toast.makeText(this, R.string.no_live_protocol, Toast.LENGTH_SHORT).show();
            return;
        }
        final Model probe = m;
        Toast.makeText(this, R.string.checking, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Providers.Result r = kind == 0
                        ? Providers.checkChat(probe, key)
                        : Providers.checkRealtime(probe, key);
                final Providers.Result result = r;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (modelId > 0) {
                            Model fresh = store.byId(modelId);
                            if (fresh != null) {
                                fresh.lastCheck = Providers.stamp(result);
                                store.update(fresh);
                            }
                        }
                        Toast.makeText(ModelEditActivity.this, result.message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "model-check").start();
    }
}
