package ru.alexv.anyailive.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import ru.alexv.anyailive.R;
import ru.alexv.anyailive.chat.Providers;
import ru.alexv.anyailive.data.Model;
import ru.alexv.anyailive.data.ModelsStore;
import ru.alexv.anyailive.data.SecretStore;

/**
 * Каталог моделей: список, добавление, правка по тапу, проверки по долгому тапу.
 * Ключи лежат в {@link SecretStore} под {@code model_<id>}, в самой модели только keyRef.
 */
public class ModelsActivity extends BaseActivity {

    /** id модели, которую надо открыть в редакторе (0 — новая). */
    public static final String EXTRA_MODEL_ID = "model_id";

    private ModelsStore store;
    private SecretStore secrets;
    private List<Model> items = new ArrayList<Model>();
    private ArrayAdapter<Model> adapter;
    private ListView list;
    private View empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_models);
        store = new ModelsStore(this);
        secrets = new SecretStore(this);

        list = (ListView) findViewById(R.id.models_list);
        empty = findViewById(R.id.models_empty);

        adapter = new ArrayAdapter<Model>(this, R.layout.item_model, items) {
            @Override
            public View getView(int position, View convert, ViewGroup parent) {
                View row = convert;
                if (row == null) {
                    row = getLayoutInflater().inflate(R.layout.item_model, parent, false);
                }
                Model m = items.get(position);
                TextView name = (TextView) row.findViewById(R.id.model_name);
                TextView badge = (TextView) row.findViewById(R.id.model_live);
                TextView meta = (TextView) row.findViewById(R.id.model_meta);
                name.setText(m.name == null || m.name.length() == 0 ? m.modelId : m.name);
                badge.setVisibility(m.live() ? View.VISIBLE : View.GONE);
                String check = m.lastCheck == null || m.lastCheck.length() == 0
                        ? getString(R.string.model_no_check) : m.lastCheck;
                meta.setText((m.modelId == null ? "" : m.modelId) + "\n" + check);
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                startActivity(ModelEditActivity.intent(ModelsActivity.this, items.get(position).id));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                modelMenu(items.get(position));
                return true;
            }
        });
        ((Button) findViewById(R.id.btn_add_model)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(ModelEditActivity.intent(ModelsActivity.this, 0));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        items.clear();
        items.addAll(store.all());
        adapter.notifyDataSetChanged();
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void modelMenu(final Model m) {
        String[] actions = new String[]{
                getString(R.string.check_chat),
                getString(R.string.check_gemini),
                getString(R.string.check_realtime),
                getString(R.string.delete)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.model_actions)
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                check(m, 0);
                                break;
                            case 1:
                                check(m, 1);
                                break;
                            case 2:
                                check(m, 2);
                                break;
                            default:
                                confirmDelete(m);
                                break;
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmDelete(final Model m) {
        new AlertDialog.Builder(this)
                .setMessage(R.string.delete_model_confirm)
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (m.keyRef != null && m.keyRef.length() > 0) {
                            secrets.delete(m.keyRef);
                        }
                        store.delete(m.id);
                        reload();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 0 — чат, 1 — ключ Gemini, 2 — realtime. Всё в фоне, результат в тост и в last_check. */
    private void check(final Model m, final int kind) {
        final String key = m.keyRef == null ? null : secrets.get(m.keyRef);
        Toast.makeText(this, R.string.checking, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Providers.Result r;
                if (kind == 0) {
                    r = Providers.checkChat(m, key);
                } else if (kind == 1) {
                    r = Providers.checkGeminiKey(m, key);
                } else {
                    r = Providers.checkRealtime(m, key);
                }
                final Providers.Result result = r;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Model fresh = store.byId(m.id);
                        if (fresh != null) {
                            fresh.lastCheck = Providers.stamp(result);
                            store.update(fresh);
                            reload();
                        }
                        Toast.makeText(ModelsActivity.this, result.message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "model-check").start();
    }
}
