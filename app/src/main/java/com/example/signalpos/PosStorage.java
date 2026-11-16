package com.example.signalpos;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;

/** Durable receipt outbox, drafts, and tab snapshots. Never discard pending receipts automatically. */
final class PosStorage extends SQLiteOpenHelper {
    static final class Snapshot {
        final JSONObject body;
        final long updated;
        Snapshot(JSONObject body, long updated) { this.body = body; this.updated = updated; }
    }

    PosStorage(Context context) { super(context, "signal-native.db", null, 2); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE receipts (id TEXT PRIMARY KEY, server TEXT NOT NULL, staff TEXT NOT NULL, sale TEXT NOT NULL, synced INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE drafts (id TEXT PRIMARY KEY, body TEXT NOT NULL)");
        db.execSQL("CREATE TABLE snapshots (key TEXT PRIMARY KEY, body TEXT NOT NULL, updated INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("CREATE TABLE IF NOT EXISTS snapshots (key TEXT PRIMARY KEY, body TEXT NOT NULL, updated INTEGER NOT NULL)");
    }
    synchronized void saveSale(String server, JSONObject sale, boolean demo, String draftKey) throws Exception {
        ContentValues values = new ContentValues();
        values.put("id", sale.getString("id")); values.put("server", server);
        values.put("staff", sale.getString("staffId")); values.put("sale", sale.toString());
        values.put("synced", demo ? 1 : 0);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.insertOrThrow("receipts", null, values);
            db.delete("drafts", "id=?", new String[]{draftKey});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    synchronized JSONArray receipts(String server, String staff, boolean pendingOnly) throws Exception {
        JSONArray result = new JSONArray();
        String where = "server=? AND staff=?" + (pendingOnly ? " AND synced=0" : "");
        try (Cursor cursor = getReadableDatabase().query("receipts", null, where,
                new String[]{server, staff}, null, null, "rowid DESC")) {
            while (cursor.moveToNext()) {
                JSONObject sale = new JSONObject(cursor.getString(cursor.getColumnIndexOrThrow("sale")));
                sale.put("_pending", cursor.getInt(cursor.getColumnIndexOrThrow("synced")) == 0);
                sale.put("_error", cursor.getString(cursor.getColumnIndexOrThrow("error")));
                result.put(sale);
            }
        }
        return result;
    }
    synchronized void acknowledge(String id) {
        ContentValues values = new ContentValues(); values.put("synced", 1); values.put("error", "");
        getWritableDatabase().update("receipts", values, "id=?", new String[]{id});
    }
    synchronized void syncError(String id, String error) {
        ContentValues values = new ContentValues(); values.put("error", error);
        getWritableDatabase().update("receipts", values, "id=? AND synced=0", new String[]{id});
    }
    synchronized int pendingCount() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT count(*) FROM receipts WHERE synced=0", null)) {
            cursor.moveToFirst(); return cursor.getInt(0);
        }
    }
    synchronized void draft(String key, JSONObject body) {
        ContentValues values = new ContentValues(); values.put("id", key); values.put("body", body.toString());
        getWritableDatabase().insertWithOnConflict("drafts", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }
    synchronized JSONObject draft(String key) throws Exception {
        try (Cursor cursor = getReadableDatabase().query("drafts", new String[]{"body"}, "id=?", new String[]{key}, null, null, null)) {
            return cursor.moveToFirst() ? new JSONObject(cursor.getString(0)) : new JSONObject();
        }
    }
    synchronized Snapshot snapshot(String key) {
        try (Cursor cursor = getReadableDatabase().query("snapshots", new String[]{"body", "updated"}, "key=?", new String[]{key}, null, null, null)) {
            if (!cursor.moveToFirst()) return null;
            return new Snapshot(new JSONObject(cursor.getString(0)), cursor.getLong(1));
        } catch (Exception error) { return null; }
    }
    synchronized void saveSnapshot(String key, JSONObject body) {
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("body", body.toString());
        values.put("updated", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("snapshots", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }
    synchronized void deleteSnapshot(String key) {
        getWritableDatabase().delete("snapshots", "key=?", new String[]{key});
    }
    synchronized void deleteSnapshots(String prefix) {
        String like = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        getWritableDatabase().delete("snapshots", "key LIKE ? ESCAPE '\\'", new String[]{like});
    }
}
