package ru.alexv.anyailive.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** Одна БД на приложение, миграции вручную по PRAGMA user_version. */
public class Db extends SQLiteOpenHelper {

    public static final String DB_NAME = "anyailive.db";
    public static final int VERSION = 1;

    public static final String T_MODELS = "models";
    public static final String T_CHATS = "chats";
    public static final String T_MESSAGES = "messages";
    public static final String T_SETTINGS = "settings";

    private static Db instance;

    public Db(Context c) {
        super(c, DB_NAME, null, VERSION);
    }

    /** Один helper на процесс: иначе каждый стор открывает свою БД. */
    public static synchronized Db get(Context c) {
        if (instance == null) {
            instance = new Db(c.getApplicationContext());
        }
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T_MODELS + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL DEFAULT '',"
                + "base_url TEXT NOT NULL DEFAULT '',"
                + "model_id TEXT NOT NULL DEFAULT '',"
                + "key_ref TEXT NOT NULL DEFAULT '',"
                + "live_protocol TEXT NOT NULL DEFAULT '',"
                + "live_url TEXT NOT NULL DEFAULT '',"
                + "live_model_id TEXT NOT NULL DEFAULT '',"
                + "voice TEXT NOT NULL DEFAULT '',"
                + "stt_model TEXT NOT NULL DEFAULT '',"
                + "tts_model TEXT NOT NULL DEFAULT '',"
                + "headers TEXT NOT NULL DEFAULT '',"
                + "last_check TEXT NOT NULL DEFAULT '',"
                + "created_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE " + T_CHATS + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT NOT NULL DEFAULT '',"
                + "model_id INTEGER NOT NULL DEFAULT 0,"
                + "engine_override TEXT NOT NULL DEFAULT '',"
                + "created_at INTEGER NOT NULL DEFAULT 0,"
                + "updated_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE " + T_MESSAGES + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "chat_id INTEGER NOT NULL,"
                + "role TEXT NOT NULL,"
                + "content TEXT NOT NULL DEFAULT '',"
                + "kind TEXT NOT NULL DEFAULT 'text',"
                + "audio_path TEXT,"
                + "truncated INTEGER NOT NULL DEFAULT 0,"
                + "ts INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_messages_chat ON " + T_MESSAGES + "(chat_id, id)");
        db.execSQL("CREATE TABLE " + T_SETTINGS + " ("
                + "key TEXT PRIMARY KEY,"
                + "value TEXT NOT NULL DEFAULT '')");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // На старте проекта схема одна; при появлении версии 2 здесь будет ALTER TABLE.
    }
}
