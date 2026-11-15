package ru.alexv.anyailive.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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

import ru.alexv.anyailive.R;
import ru.alexv.anyailive.chat.ChatClient;
import ru.alexv.anyailive.chat.ChatLink;
import ru.alexv.anyailive.data.ChatStore;
import ru.alexv.anyailive.data.Model;
import ru.alexv.anyailive.data.ModelsStore;
import ru.alexv.anyailive.data.SecretStore;
import ru.alexv.anyailive.voice.CascadeHttp;
import ru.alexv.anyailive.voice.CascadeSystem;
import ru.alexv.anyailive.voice.VoiceEngine;

/**
 * Переписка с моделью: потоковый текстовый чат.
 * Микрофон включает голосовой каскад (STT -> чат -> TTS) прямо в этом экране,
 * кнопка Live открывает {@link LiveActivity} с нативным realtime-протоколом.
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

    /** Шторка истории: панель, затемнение и список чатов. */
    private View drawer, scrim;
    private ListView drawerList;
    private boolean drawerOpen;
    private final List<ChatStore.Chat> drawerItems = new ArrayList<ChatStore.Chat>();
    private DrawerAdapter drawerAdapter;

    private volatile boolean streaming;

    /** Голосовой каскад, пока он включён; null — голосовой режим выключен. */
    private VoiceEngine voice;
    private static final int REQ_MIC = 42;

    /**
     * Номер текущей голосовой сессии: после stopVoice() колбэки старой сессии
     * не должны ни писать в историю, ни глушить ту, что началась вместо неё.
     */
    private final ChatSessionCallbacks fence = new ChatSessionCallbacks();

    /**
     * Запрос разрешения RECORD_AUDIO: системный диалог ставит экран на паузу,
     * поэтому выданное в нём разрешение дожидается onResume и не открывает
     * микрофон на закрытом экране или на новом экземпляре без своего запроса.
     */
    private final VoicePermissionPolicy permission = new VoicePermissionPolicy();

    public static Intent intent(Context from, long chatId) {
        Intent i = new Intent(from, ChatActivity.class);
        i.putExtra(EXTRA_CHAT_ID, chatId);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemesRuntime.apply(this);
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

        drawer = findViewById(R.id.drawer);
        scrim = findViewById(R.id.drawer_scrim);
        drawerList = (ListView) findViewById(R.id.drawer_list);
        drawerAdapter = new DrawerAdapter();
        drawerList.setAdapter(drawerAdapter);

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
                toggleVoice();
            }
        });
        findViewById(R.id.btn_live).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (chat == null) {
                    Toast.makeText(ChatActivity.this, R.string.chat_no_model, Toast.LENGTH_LONG).show();
                    return;
                }
                startActivity(LiveActivity.intent(ChatActivity.this, chat.id));
            }
        });
        findViewById(R.id.btn_drawer).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (drawerOpen) {
                    closeDrawer();
                } else {
                    openDrawer();
                }
            }
        });
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDrawer();
            }
        });
        findViewById(R.id.btn_new_chat).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newChatDialog();
            }
        });
        findViewById(R.id.drawer_new_chat).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDrawer();
                newChatDialog();
            }
        });
        header.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ChatActivity.this, ModelsActivity.class));
            }
        });
        drawerList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                ChatStore.Chat c = drawerItems.get(position);
                closeDrawer();
                if (c.id != chatId) {
                    finish();
                    startActivity(intent(ChatActivity.this, c.id));
                }
            }
        });
        drawerList.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                confirmDelete(drawerItems.get(position));
                return true;
            }
        });
    }

    /** Выездная шторка: подгружаем свежий список чатов и показываем. */
    private void openDrawer() {
        drawerItems.clear();
        drawerItems.addAll(store.chats());
        drawerAdapter.notifyDataSetChanged();
        drawerOpen = true;
        drawer.animate().translationX(0f).setDuration(180).start();
        scrim.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1f).setDuration(180).start();
        hideKeyboard();
    }

    private void closeDrawer() {
        if (!drawerOpen) {
            return;
        }
        drawerOpen = false;
        drawer.animate().translationX(-drawer.getWidth()).setDuration(160).start();
        scrim.animate().alpha(0f).setDuration(160).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (!drawerOpen) {
                    scrim.setVisibility(View.GONE);
                }
            }
        }).start();
    }

    /** Выбор модели и создание нового чата, как на главном экране. */
    private void newChatDialog() {
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
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.choose_model)
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        long id = store.newChat(all.get(which).id);
                        finish();
                        startActivity(intent(ChatActivity.this, id));
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmDelete(final ChatStore.Chat c) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.delete_chat_confirm)
                .setPositiveButton(R.string.delete, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        store.delete(c.id);
                        drawerItems.clear();
                        drawerItems.addAll(store.chats());
                        drawerAdapter.notifyDataSetChanged();
                        if (c.id == chatId) {
                            finish();
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Строка шторки: название чата и модель. */
    private class DrawerAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return drawerItems.size();
        }

        @Override
        public Object getItem(int position) {
            return drawerItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return drawerItems.get(position).id;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            TextView row = (TextView) convert;
            if (row == null) {
                row = new TextView(ChatActivity.this);
                row.setPadding(dp(20), dp(14), dp(16), dp(14));
                row.setTextSize(15);
            }
            ChatStore.Chat c = drawerItems.get(position);
            Model m = models.byId(c.modelId);
            String mName = m == null ? "" : (m.name == null || m.name.length() == 0 ? m.modelId : m.name);
            String title = c.title == null || c.title.length() == 0
                    ? getString(R.string.chat_untitled) : c.title;
            row.setText(title + (mName.length() == 0 ? "" : "\n" + mName));
            boolean current = c.id == chatId;
            row.setTextColor(current ? ThemesRuntime.color(ChatActivity.this, R.attr.colAccent)
                    : ThemesRuntime.color(ChatActivity.this, R.attr.colFg));
            return row;
        }

        private int dp(int v) {
            return Math.round(v * getResources().getDisplayMetrics().density);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        permission.onResume();
        if (permission.consumePendingGrant()) {
            // Разрешение выдали в диалоге, пока экран стоял на паузе.
            startVoiceWithPolicy();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Пауза от системного диалога разрешения не отменяет ожидание результата.
        permission.onPause();
        stopVoice();
    }

    @Override
    protected void onDestroy() {
        permission.onClose();
        super.onDestroy();
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
        // В шапке — подключённая модель; название чата живёт в шторке.
        header.setText(name);
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

    /**
     * Микрофон: первый тап запускает каскад, второй — глушит его.
     * Каскад по HTTP берём, когда у модели заполнены адрес сервера и модель распознавания;
     * иначе работаем на системных STT/TTS телефона.
     */
    private void toggleVoice() {
        if (voice != null) {
            stopVoice();
            return;
        }
        if (chat == null || model == null) {
            Toast.makeText(this, R.string.chat_no_model, Toast.LENGTH_LONG).show();
            return;
        }
        startVoiceWithPolicy();
    }

    private void startVoiceWithPolicy() {
        if (chat == null || model == null || streaming || voice != null) {
            return;
        }
        ru.alexv.anyailive.data.SettingsStore settings =
                new ru.alexv.anyailive.data.SettingsStore(this);
        String route = VoiceConfig.route(VoiceConfig.engine(settings), model);
        if (VoiceConfig.ENGINE_LIVE.equals(route)) {
            startActivity(LiveActivity.intent(this, chat.id));
            return;
        }
        if (!VoiceConfig.ENGINE_CASCADE.equals(route)) {
            Toast.makeText(this, R.string.live_no_live, Toast.LENGTH_LONG).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (permission.request()) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            }
            return;
        }
        startVoice();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_MIC) {
            return;
        }
        boolean granted = results != null && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED;
        int action = permission.onResult(granted);
        if (action == VoicePermissionPolicy.START) {
            startVoiceWithPolicy();
        }
        // DEFER: разрешение выдано, но экран ещё на паузе — запуск в onResume.
    }

    private void startVoice() {
        if (chat == null || model == null || streaming) {
            return;
        }
        ru.alexv.anyailive.data.SettingsStore settings =
                new ru.alexv.anyailive.data.SettingsStore(this);
        Model configured = VoiceConfig.apply(settings, model);
        String prompt = getString(R.string.live_system_prompt);
        String language = VoiceConfig.language(settings, getString(R.string.voice_language_default));
        ChatLink link = new ChatLink(client, configured, apiKey);
        boolean http = configured.baseUrl != null && configured.baseUrl.length() > 0
                && configured.sttModel != null && configured.sttModel.length() > 0;
        voice = http
                ? new CascadeHttp(this, configured, link, prompt, language)
                : new CascadeSystem(this, configured, link, prompt, language);
        setMicLabel(R.string.voice_mode_stop);
        final int session = fence.begin();
        voice.start(new VoiceEngine.Callback() {
            @Override
            public void onState(final int state) {
                onUi(session, new Runnable() {
                    @Override
                    public void run() {
                        setMicLabel(state == VoiceEngine.LISTENING ? R.string.voice_mode_listening
                                : state == VoiceEngine.THINKING ? R.string.voice_mode_thinking
                                : state == VoiceEngine.SPEAKING ? R.string.voice_mode_speaking
                                : R.string.voice_mode_stop);
                    }
                });
            }

            @Override
            public void onUserText(final String text) {
                saveTurn(session, "user", text);
            }

            @Override
            public void onAssistantText(final String text) {
                saveTurn(session, "assistant", text);
            }

            @Override
            public void onError(final String message) {
                onUi(session, new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(ChatActivity.this, message, Toast.LENGTH_LONG).show();
                        stopVoice();
                    }
                });
            }
        });
    }

    /** Выполнить эффект колбэка на UI-потоке, если его сессия ещё жива. */
    private void onUi(final int session, final Runnable effect) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                fence.runIfActive(session, effect);
            }
        });
    }

    /** Реплика каскада: запись в историю только для живой сессии и на UI-потоке. */
    private void saveTurn(final int session, final String role, final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                fence.deliverText(session, text, new ChatSessionCallbacks.TextSink() {
                    @Override
                    public void persist(String clean) {
                        store.addMessage(chat.id, role, clean);
                        reload();
                    }
                });
            }
        });
    }

    private void stopVoice() {
        fence.stop();
        if (voice != null) {
            voice.stop();
            voice = null;
        }
        setMicLabel(R.string.voice_mode_start);
    }

    private void setMicLabel(int res) {
        Button mic = (Button) findViewById(R.id.btn_mic);
        if (mic != null) {
            mic.setText(res);
        }
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
