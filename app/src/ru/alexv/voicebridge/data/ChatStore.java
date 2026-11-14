package ru.alexv.voicebridge.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/** Чаты и сообщения. */
public class ChatStore {

    public static class Chat {
        public long id;
        public String title = "";
        public long modelId;
        public String engineOverride = "";
        public long updatedAt;
    }

    public static class Msg {
        public long id;
        public long chatId;
        public String role = "";
        public String content = "";
        public String kind = "text";
        public String audioPath;
        public boolean truncated;
        public long at;

        public boolean isUser() {
            return "user".equals(role);
        }
    }

    private final Db helper;

    public ChatStore(Context c) {
        helper = Db.get(c);
    }

    public long newChat(long modelId) {
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("title", "");
        v.put("model_id", modelId);
        v.put("created_at", now);
        v.put("updated_at", now);
        return helper.getWritableDatabase().insertOrThrow(Db.T_CHATS, null, v);
    }

    public List<Chat> chats() {
        List<Chat> out = new ArrayList<Chat>();
        Cursor c = helper.getReadableDatabase().query(Db.T_CHATS, null, null, null, null, null, "updated_at DESC");
        try {
            while (c.moveToNext()) {
                Chat ch = new Chat();
                ch.id = c.getLong(c.getColumnIndexOrThrow("id"));
                ch.title = c.getString(c.getColumnIndexOrThrow("title"));
                ch.modelId = c.getLong(c.getColumnIndexOrThrow("model_id"));
                ch.engineOverride = c.getString(c.getColumnIndexOrThrow("engine_override"));
                ch.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
                out.add(ch);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void rename(long chatId, String title) {
        ContentValues v = new ContentValues();
        v.put("title", title == null ? "" : title);
        v.put("updated_at", System.currentTimeMillis());
        helper.getWritableDatabase().update(Db.T_CHATS, v, "id=?", new String[]{String.valueOf(chatId)});
    }

    public void delete(long chatId) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.delete(Db.T_MESSAGES, "chat_id=?", new String[]{String.valueOf(chatId)});
        db.delete(Db.T_CHATS, "id=?", new String[]{String.valueOf(chatId)});
    }

    public long addMessage(long chatId, String role, String content) {
        return addMessage(chatId, role, content, "text", null, false);
    }

    public long addMessage(long chatId, String role, String content, String kind,
                           String audioPath, boolean truncated) {
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("chat_id", chatId);
        v.put("role", role);
        v.put("content", content == null ? "" : content);
        v.put("kind", kind == null ? "text" : kind);
        v.put("audio_path", audioPath);
        v.put("truncated", truncated ? 1 : 0);
        v.put("ts", now);
        SQLiteDatabase db = helper.getWritableDatabase();
        long id = db.insertOrThrow(Db.T_MESSAGES, null, v);
        ContentValues u = new ContentValues();
        u.put("updated_at", now);
        db.update(Db.T_CHATS, u, "id=?", new String[]{String.valueOf(chatId)});
        return id;
    }

    public List<Msg> messages(long chatId) {
        List<Msg> out = new ArrayList<Msg>();
        Cursor c = helper.getReadableDatabase().query(Db.T_MESSAGES, null, "chat_id=?",
                new String[]{String.valueOf(chatId)}, null, null, "id ASC");
        try {
            while (c.moveToNext()) {
                Msg m = new Msg();
                m.id = c.getLong(c.getColumnIndexOrThrow("id"));
                m.chatId = c.getLong(c.getColumnIndexOrThrow("chat_id"));
                m.role = c.getString(c.getColumnIndexOrThrow("role"));
                m.content = c.getString(c.getColumnIndexOrThrow("content"));
                m.kind = c.getString(c.getColumnIndexOrThrow("kind"));
                m.audioPath = c.getString(c.getColumnIndexOrThrow("audio_path"));
                m.truncated = c.getInt(c.getColumnIndexOrThrow("truncated")) == 1;
                m.at = c.getLong(c.getColumnIndexOrThrow("ts"));
                out.add(m);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** Последние n реплик — для восстановления контекста после разрыва Live-сессии. */
    public List<Msg> lastMessages(long chatId, int n) {
        List<Msg> all = messages(chatId);
        int from = Math.max(0, all.size() - n);
        return new ArrayList<Msg>(all.subList(from, all.size()));
    }
}
