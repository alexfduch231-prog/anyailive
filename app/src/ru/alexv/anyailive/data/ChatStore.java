package ru.alexv.anyailive.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
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
    private final SettingsStore settings;
    /** Каталог записей приложения (filesDir/audio); храним путь, а не Context. */
    private final File audioRoot;
    /**
     * Символическая ссылка? Спрашиваем ядро: java.nio.file доступен только с API 26,
     * а у нас minSdk 24, и java.io-канонизация ссылку не раскрывает.
     */
    private static final ChatAudioFiles.Links LINKS = new ChatAudioFiles.Links() {
        @Override public boolean isLink(File file) {
            try {
                return OsConstants.S_ISLNK(Os.lstat(file.getPath()).st_mode);
            } catch (Exception e) {
                // Не знаем, ссылка ли это (в том числе ENOENT) — считаем ссылкой и не удаляем.
                return true;
            }
        }
    };
    // Shared across Activities/store instances; no Context is retained in this buffer.
    private static final MemoryHistory<Msg> MEMORY = new MemoryHistory<Msg>();
    private final MemoryHistory.Storage<Msg> storage = new MemoryHistory.Storage<Msg>() {
        @Override public List<Msg> load(long chatId) {
            return savedMessages(chatId);
        }
        @Override public Msg save(long chatId, Msg message) {
            message.id = saveMessage(message);
            return message;
        }
    };

    public ChatStore(Context c) {
        helper = Db.get(c);
        settings = new SettingsStore(c);
        audioRoot = new File(c.getFilesDir(), "audio");
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
        synchronized (MEMORY) {
            // Список записей снимаем до удаления строк: приватные реплики живут только
            // в памяти, а строки БД после удаления уже ничего не подскажут.
            List<String> audio = audioPaths(chatId);
            SQLiteDatabase db = helper.getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete(Db.T_MESSAGES, "chat_id=?", new String[]{String.valueOf(chatId)});
                db.delete(Db.T_CHATS, "id=?", new String[]{String.valueOf(chatId)});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            MEMORY.remove(chatId);
            // Файлы удаляем после коммита: падение между шагами оставит недостижимый
            // wav (мусор), но не строку, указывающую на уже удалённое аудио.
            ChatAudioFiles.delete(new File(audioRoot, String.valueOf(chatId)), audio, LINKS);
        }
    }

    /** Аудиофайлы чата: из памяти (включая приватные реплики) и из БД. */
    private List<String> audioPaths(long chatId) {
        List<String> out = new ArrayList<String>();
        for (Msg m : MEMORY.messages(chatId, storage)) {
            if (m != null && m.audioPath != null) out.add(m.audioPath);
        }
        Cursor c = helper.getReadableDatabase().query(Db.T_MESSAGES, new String[]{"audio_path"},
                "chat_id=? AND audio_path IS NOT NULL", new String[]{String.valueOf(chatId)},
                null, null, null);
        try {
            while (c.moveToNext()) {
                String path = c.getString(0);
                if (path != null) out.add(path);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public long addMessage(long chatId, String role, String content) {
        return addMessage(chatId, role, content, "text", null, false);
    }

    public long addMessage(long chatId, String role, String content, String kind,
                           String audioPath, boolean truncated) {
        synchronized (MEMORY) {
            Msg message = new Msg();
            message.chatId = chatId;
            message.role = role;
            message.content = content == null ? "" : content;
            message.kind = kind == null ? "text" : kind;
            message.audioPath = audioPath;
            message.truncated = truncated;
            message.at = System.currentTimeMillis();
            message.id = MEMORY.nextTemporaryId();
            // Read on each append: a settings switch never flushes the private timeline.
            boolean keepHistory = settings.getBool(SettingsStore.KEEP_HISTORY, true);
            return MEMORY.append(chatId, message, keepHistory, storage).id;
        }
    }

    private long saveMessage(Msg message) {
        ContentValues v = new ContentValues();
        v.put("chat_id", message.chatId);
        v.put("role", message.role);
        v.put("content", message.content);
        v.put("kind", message.kind);
        v.put("audio_path", message.audioPath);
        v.put("truncated", message.truncated ? 1 : 0);
        v.put("ts", message.at);
        SQLiteDatabase db = helper.getWritableDatabase();
        long id = db.insertOrThrow(Db.T_MESSAGES, null, v);
        ContentValues u = new ContentValues();
        u.put("updated_at", message.at);
        db.update(Db.T_CHATS, u, "id=?", new String[]{String.valueOf(message.chatId)});
        return id;
    }

    public List<Msg> messages(long chatId) {
        return copyMessages(MEMORY.messages(chatId, storage));
    }

    private List<Msg> savedMessages(long chatId) {
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
        return copyMessages(MEMORY.lastMessages(chatId, n, storage));
    }

    // Msg is mutable UI data; callers must not mutate the process-local source of truth.
    private static List<Msg> copyMessages(List<Msg> source) {
        List<Msg> result = new ArrayList<Msg>();
        for (Msg m : source) {
            Msg copy = new Msg();
            copy.id = m.id;
            copy.chatId = m.chatId;
            copy.role = m.role;
            copy.content = m.content;
            copy.kind = m.kind;
            copy.audioPath = m.audioPath;
            copy.truncated = m.truncated;
            copy.at = m.at;
            result.add(copy);
        }
        return result;
    }
}
