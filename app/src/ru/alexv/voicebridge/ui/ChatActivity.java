package ru.alexv.voicebridge.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import ru.alexv.voicebridge.R;
import ru.alexv.voicebridge.chat.ChatClient;
import ru.alexv.voicebridge.data.ChatStore;
import ru.alexv.voicebridge.data.Model;
import ru.alexv.voicebridge.data.ModelsStore;
import ru.alexv.voicebridge.data.SecretStore;

/**
 * Переписка с моделью: потоковый текстовый чат.
 * Микрофон и Live — заглушки, их движки делают другие части приложения.
 */
public class ChatActivity extends Activity {

    /** id чата, который надо открыть. */
    public static final String EXTRA_CHAT_ID = "chat_id";

    private ChatStore store;
    private ModelsStore models;
    private SecretStore secrets;
    private ChatClient client;

    private long chatId;
    private ChatStore.Chat chat;
    private Model model;
    private String apiKey;

    private final List<ChatStore.Msg> rows = new ArrayList<ChatStore.Msg>();
    private MsgAdapter adapter;
    private ListView list;
    private TextView header;
    private EditText input;
    private Button send;
    private View empty;

    private volatile boolean streaming;

    public static Intent intent(Context from, long chatId) {
        Intent i = new Intent(from, ChatActivity.class);
        i.putExtra(EXTRA_CHAT_ID, chatId);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        store = new ChatStore(this);
        models = new ModelsStore(this);
        secrets = new SecretStore(this);
        client = new ChatClient();

        chatId = getIntent().getLongExtra(EXTRA_CHAT_ID, 0);
        header = (TextView) findViewById(R.id.chat_header);
        list = (ListView) findViewById(R.id.chat_messages);
        input = (EditText) findViewById(R.id.chat_input);
        send = (Button) findViewById(R.id.btn_send);
        empty = findViewById(R.id.chat_empty);

        adapter = new MsgAdapter();
        list.setAdapter(adapter);

        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit();
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submit();
                    return true;
                }
                return false;
            }
        });
        findViewById(R.id.btn_mic).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(ChatActivity.this, R.string.later, Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btn_live).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(ChatActivity.this, R.string.later, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        chat = chatId > 0 ? findChat(chatId) : null;
        model = chat == null ? null : models.byId(chat.modelId);
        apiKey = model == null || model.keyRef == null ? null : secrets.get(model.keyRef);
        String name = model == null
                ? getString(R.string.chat_no_model)
                : (model.name == null || model.name.length() == 0 ? model.modelId : model.name);
        String title = chat == null || chat.title == null || chat.title.length() == 0
                ? getString(R.string.chat_untitled) : chat.title;
        header.setText(title + " · " + name);
        rows.clear();
        if (chat != null) {
            rows.addAll(store.messages(chat.id));
        }
        adapter.notifyDataSetChanged();
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        scrollBottom();
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

    private void submit() {
        if (streaming) {
            return;
        }
        String text = input.getText().toString().trim();
        if (text.length() == 0) {
            return;
        }
        if (chat == null) {
            Toast.makeText(this, R.string.chat_no_model, Toast.LENGTH_LONG).show();
            return;
        }
        if (model == null) {
            Toast.makeText(this, R.string.chat_no_model, Toast.LENGTH_LONG).show();
            return;
        }
        input.setText("");
        hideKeyboard();

        store.addMessage(chat.id, "user", text);
        rows.clear();
        rows.addAll(store.messages(chat.id));

        final ChatStore.Msg pending = new ChatStore.Msg();
        pending.role = "assistant";
        pending.content = getString(R.string.chat_streaming);
        pending.chatId = chat.id;
        rows.add(pending);
        adapter.notifyDataSetChanged();
        empty.setVisibility(View.GONE);
        scrollBottom();
        setBusy(true);

        final List<ChatStore.Msg> history = store.messages(chat.id);
        new Thread(new Runnable() {
            @Override
            public void run() {
                client.stream(model, apiKey, history, new ChatClient.Listener() {
                    @Override
                    public void onDelta(final String t) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (pending.content.equals(getString(R.string.chat_streaming))) {
                                    pending.content = "";
                                }
                                pending.content = pending.content + t;
                                adapter.notifyDataSetChanged();
                                scrollBottom();
                            }
                        });
                    }

                    @Override
                    public void onDone(final String full) {
                        store.addMessage(chat.id, "assistant", full);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                setBusy(false);
                                reload();
                            }
                        });
                    }

                    @Override
                    public void onError(final String msg) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                setBusy(false);
                                pending.content = getString(R.string.chat_error, msg);
                                adapter.notifyDataSetChanged();
                                scrollBottom();
                            }
                        });
                    }
                }, 120000L);
            }
        }, "chat-stream").start();
    }

    private void setBusy(boolean busy) {
        streaming = busy;
        send.setEnabled(!busy);
        input.setEnabled(!busy);
    }

    private void scrollBottom() {
        list.post(new Runnable() {
            @Override
            public void run() {
                if (adapter.getCount() > 0) {
                    list.setSelection(adapter.getCount() - 1);
                }
            }
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }

    /** Два типа строк: пользователь справа, ассистент слева. */
    private class MsgAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).isUser() ? 1 : 0;
        }

        @Override
        public boolean isEnabled(int position) {
            return false;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            ChatStore.Msg m = rows.get(position);
            boolean user = m.isUser();
            View row = convert;
            if (row == null) {
                row = getLayoutInflater().inflate(
                        user ? R.layout.item_message_user : R.layout.item_message_ai, parent, false);
            }
            TextView text = (TextView) row.findViewById(R.id.msg_text);
            text.setText(m.content == null ? "" : m.content);
            return row;
        }
    }
}
