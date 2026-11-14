package ru.alexv.anyailive.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/** Каталог моделей пользователя. */
public class ModelsStore {

    private final Db helper;

    public ModelsStore(Context c) {
        helper = Db.get(c);
    }

    public long insert(Model m) {
        SQLiteDatabase db = helper.getWritableDatabase();
        long id = db.insertOrThrow(Db.T_MODELS, null, values(m));
        m.id = id;
        return id;
    }

    public void update(Model m) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.update(Db.T_MODELS, values(m), "id=?", new String[]{String.valueOf(m.id)});
    }

    public void delete(long id) {
        helper.getWritableDatabase().delete(Db.T_MODELS, "id=?", new String[]{String.valueOf(id)});
    }

    public List<Model> all() {
        List<Model> out = new ArrayList<Model>();
        Cursor c = helper.getReadableDatabase().query(Db.T_MODELS, null, null, null, null, null, "name COLLATE NOCASE");
        try {
            while (c.moveToNext()) {
                out.add(read(c));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public Model byId(long id) {
        Cursor c = helper.getReadableDatabase().query(Db.T_MODELS, null, "id=?",
                new String[]{String.valueOf(id)}, null, null, null);
        try {
            return c.moveToFirst() ? read(c) : null;
        } finally {
            c.close();
        }
    }

    private static ContentValues values(Model m) {
        ContentValues v = new ContentValues();
        v.put("name", nz(m.name));
        v.put("base_url", nz(m.baseUrl));
        v.put("model_id", nz(m.modelId));
        v.put("key_ref", nz(m.keyRef));
        v.put("live_protocol", nz(m.liveProtocol));
        v.put("live_url", nz(m.liveUrl));
        v.put("live_model_id", nz(m.liveModelId));
        v.put("voice", nz(m.voice));
        v.put("stt_model", nz(m.sttModel));
        v.put("tts_model", nz(m.ttsModel));
        v.put("headers", nz(m.headers));
        v.put("last_check", nz(m.lastCheck));
        v.put("created_at", m.createdAt == 0 ? System.currentTimeMillis() : m.createdAt);
        return v;
    }

    private static Model read(Cursor c) {
        Model m = new Model();
        m.id = c.getLong(c.getColumnIndexOrThrow("id"));
        m.name = c.getString(c.getColumnIndexOrThrow("name"));
        m.baseUrl = c.getString(c.getColumnIndexOrThrow("base_url"));
        m.modelId = c.getString(c.getColumnIndexOrThrow("model_id"));
        m.keyRef = c.getString(c.getColumnIndexOrThrow("key_ref"));
        m.liveProtocol = c.getString(c.getColumnIndexOrThrow("live_protocol"));
        m.liveUrl = c.getString(c.getColumnIndexOrThrow("live_url"));
        m.liveModelId = c.getString(c.getColumnIndexOrThrow("live_model_id"));
        m.voice = c.getString(c.getColumnIndexOrThrow("voice"));
        m.sttModel = c.getString(c.getColumnIndexOrThrow("stt_model"));
        m.ttsModel = c.getString(c.getColumnIndexOrThrow("tts_model"));
        m.headers = c.getString(c.getColumnIndexOrThrow("headers"));
        m.lastCheck = c.getString(c.getColumnIndexOrThrow("last_check"));
        m.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return m;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
