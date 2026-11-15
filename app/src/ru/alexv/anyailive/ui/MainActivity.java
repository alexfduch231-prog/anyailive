package ru.alexv.anyailive.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ru.alexv.anyailive.R;
import ru.alexv.anyailive.data.ChatStore;
import ru.alexv.anyailive.data.Model;
import ru.alexv.anyailive.data.ModelsStore;

/**
 * Главный экран: список чатов, «Новый чат» и «Модели».
 * Долгий тап по чату — переименовать или удалить.
 */
public class MainActivity extends Activity {

    private ChatStore chats;
    private ModelsStore models;
    private final List<ChatStore.Chat> items = new ArrayList<ChatStore.Chat>();
    private ArrayAdapter<ChatStore.Chat> adapter;
    private ListView list;
    private View empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemesRuntime.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        chats = new ChatStore(this);
        models = new ModelsStore(this);

        list = (ListView) findViewById(R.id.chat_list);
        empty = findViewById(R.id.main_empty);

        adapter = new ArrayAdapter<ChatStore.Chat>(this, R.layout.item_chat, items) {
            @Override
            public View getView(int position, View convert, ViewGroup parent) {
                View row = convert;
                if (row == null) {
                    row = getLayoutInflater().inflate(R.layout.item_chat, parent, false);
                }
                ChatStore.Chat c = items.get(position);
                TextView title = (TextView) row.findViewById(R.id.chat_title);
                TextView meta = (TextView) row.findViewById(R.id.chat_meta);
                String name = c.title == null || c.title.length() == 0
                        ? getString(R.string.chat_untitled) : c.title;
                title.setText(name);
                meta.setText(getString(R.string.chat_meta, modelName(c.modelId), when(c.updatedAt)));
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                openChat(items.get(position).id);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                chatMenu(items.get(position));
                return true;
            }
        });

        ((Button) findViewById(R.id.btn_new_chat)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newChat();
            }
        });
        ((Button) findViewById(R.id.btn_models)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ModelsActivity.class));
            }
        });
        ((Button) findViewById(R.id.btn_settings)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
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
        items.addAll(chats.chats());
        adapter.notifyDataSetChanged();
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private String modelName(long modelId) {
        Model m = models.byId(modelId);
        if (m == null) {
            return getString(R.string.no_models);
        }
        return m.name == null || m.name.length() == 0 ? m.modelId : m.name;
    }

    private String when(long at) {
        if (at <= 0) {
            return "";
        }
        return new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(new Date(at));
    }

    private void newChat() {
        final List<Model> all = models.all();
        if (all.isEmpty()) {
            Toast.makeText(this, R.string.no_models, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, ModelsActivity.class));
            return;
        }
        String[] names = new String[all.size()];
        for (int i = 0; i < all.size(); i++) {
            Model m = all.get(i);
            names[i] = m.name == null || m.name.length() == 0 ? m.modelId : m.name;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_model)
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        long id = chats.newChat(all.get(which).id);
                        openChat(id);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openChat(long chatId) {
        startActivity(ChatActivity.intent(this, chatId));
    }

    private void chatMenu(final ChatStore.Chat c) {
        String[] actions = new String[]{getString(R.string.rename), getString(R.string.export_chat),
                getString(R.string.delete)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.chat_actions)
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            renameDialog(c);
                        } else if (which == 1) {
                            exportChat(c);
                        } else {
                            deleteDialog(c);
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Экспорт одного чата в JSON через системный диалог сохранения. */
    private void exportChat(ChatStore.Chat c) {
        java.util.List<ChatStore.Msg> msgs = chats.messages(c.id);
        if (msgs.isEmpty()) {
            Toast.makeText(this, R.string.chat_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String json = Exporter.chatJson(c, msgs);
        Exporter.beginSave(this, Exporter.fileName(getString(R.string.export_prefix_chat)), json);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Exporter.finishSave(this, requestCode, resultCode, data);
    }

    private void renameDialog(final ChatStore.Chat c) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(c.title == null ? "" : c.title);
        input.setSelection(input.getText().length());
        LinearLayout box = new LinearLayout(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.rename_chat_title)
                .setView(box)
                .setPositiveButton(R.string.rename, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        chats.rename(c.id, input.getText().toString().trim());
                        reload();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteDialog(final ChatStore.Chat c) {
        new AlertDialog.Builder(this)
                .setMessage(R.string.delete_chat_confirm)
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        chats.delete(c.id);
                        reload();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
