package ru.alexv.anyailive.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ru.alexv.anyailive.R;
import ru.alexv.anyailive.data.ChatStore;
import ru.alexv.anyailive.json.Json;

/**
 * Экспорт переписки в JSON: один чат или все сразу.
 * Файл пишется через системный диалог SAF, поэтому разрешений на хранилище не нужно.
 */
public final class Exporter {

    /** Код запроса для {@link Activity#onActivityResult}. */
    public static final int REQ_EXPORT = 77;

    private static String pending;

    private Exporter() {
    }

    /** JSON одного чата вместе с сообщениями. */
    public static String chatJson(ChatStore.Chat chat, List<ChatStore.Msg> messages) {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            ChatStore.Msg m = messages.get(i);
            if (i > 0) {
                arr.append(',');
            }
            arr.append(Json.write()
                    .put("role", m.role)
                    .put("kind", m.kind)
                    .put("content", m.content)
                    .put("audio", m.audioPath == null ? "" : m.audioPath)
                    .put("truncated", m.truncated)
                    .put("at", m.at)
                    .done());
        }
        arr.append(']');
        return Json.write()
                .put("chat", chat.title == null ? "" : chat.title)
                .put("model_id", chat.modelId)
                .put("updated_at", chat.updatedAt)
                .put("exported_at", System.currentTimeMillis())
                .putRaw("messages", arr.toString())
                .done();
    }

    /** JSON всех чатов: массив объектов того же вида. */
    public static String allJson(List<ChatStore.Chat> chats, ChatStore store) {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < chats.size(); i++) {
            ChatStore.Chat c = chats.get(i);
            if (i > 0) {
                arr.append(',');
            }
            arr.append(chatJson(c, store.messages(c.id)));
        }
        arr.append(']');
        return Json.write()
                .put("app", "anyailive")
                .put("exported_at", System.currentTimeMillis())
                .putRaw("chats", arr.toString())
                .done();
    }

    /** Открывает системный диалог «куда сохранить» и запоминает текст. */
    public static void beginSave(Activity a, String suggestedName, String json) {
        pending = json;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, suggestedName);
        try {
            a.startActivityForResult(i, REQ_EXPORT);
        } catch (RuntimeException e) {
            pending = null;
            Toast.makeText(a, R.string.export_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** Дописывает запомненный текст в файл, выбранный пользователем. */
    public static void finishSave(Activity a, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_EXPORT) {
            return;
        }
        String text = pending;
        pending = null;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null || text == null) {
            return;
        }
        Uri uri = data.getData();
        OutputStream os = null;
        try {
            os = a.getContentResolver().openOutputStream(uri);
            if (os == null) {
                throw new IllegalStateException("no output stream");
            }
            os.write(text.getBytes("UTF-8"));
            os.flush();
            Toast.makeText(a, R.string.export_done, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(a, R.string.export_failed, Toast.LENGTH_LONG).show();
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Exception ignored) {
                    // поток уже не нужен
                }
            }
        }
    }

    /** Имя файла вида anyailive-chat-20261114-1200.json. */
    public static String fileName(String prefix) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US);
        return prefix + "-" + f.format(new Date()) + ".json";
    }
}
