package com.gummytranslate.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public final class AppStorage extends SQLiteOpenHelper {
    public static final class SessionSummary {
        public final long id;
        public final long startedAt;
        public final long endedAt;
        public final String title;
        public final int captionCount;
        public final String preview;

        SessionSummary(long id, long startedAt, long endedAt, String title, int captionCount, String preview) {
            this.id = id;
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.title = title == null ? "" : title;
            this.captionCount = captionCount;
            this.preview = preview == null ? "" : preview;
        }
    }

    public static final class Caption {
        public final String english;
        public final String chinese;

        public Caption(String english, String chinese) {
            this.english = english == null ? "" : english;
            this.chinese = chinese == null ? "" : chinese;
        }
    }

    public AppStorage(Context context) {
        super(context, "gummy_classes.db", null, 2);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL DEFAULT 0, title TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE captions (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL, created_at INTEGER NOT NULL, english TEXT, chinese TEXT)");
        db.execSQL("CREATE INDEX captions_session_idx ON captions(session_id)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE sessions ADD COLUMN title TEXT NOT NULL DEFAULT ''");
    }

    public long startSession() {
        ContentValues values = new ContentValues();
        values.put("started_at", System.currentTimeMillis());
        return getWritableDatabase().insertOrThrow("sessions", null, values);
    }

    public void finishSession(long id) {
        long captions;
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM captions WHERE session_id=?", new String[]{String.valueOf(id)})) {
            captions = cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
        if (captions == 0) {
            deleteSession(id);
            return;
        }
        ContentValues values = new ContentValues();
        values.put("ended_at", System.currentTimeMillis());
        getWritableDatabase().update("sessions", values, "id=?", new String[]{String.valueOf(id)});
    }

    public void addCaption(long sessionId, String english, String chinese) {
        if ((english == null || english.isEmpty()) && (chinese == null || chinese.isEmpty())) return;
        ContentValues values = new ContentValues();
        values.put("session_id", sessionId);
        values.put("created_at", System.currentTimeMillis());
        values.put("english", english);
        values.put("chinese", chinese);
        getWritableDatabase().insert("captions", null, values);
    }

    public List<SessionSummary> listSessions() {
        List<SessionSummary> result = new ArrayList<>();
        String sql = "SELECT s.id,s.started_at,s.ended_at,s.title,COUNT(c.id)," +
                "COALESCE((SELECT chinese FROM captions WHERE session_id=s.id AND chinese<>'' ORDER BY id DESC LIMIT 1),'') " +
                "FROM sessions s LEFT JOIN captions c ON c.session_id=s.id GROUP BY s.id ORDER BY s.id DESC";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                result.add(new SessionSummary(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2), cursor.getString(3), cursor.getInt(4), cursor.getString(5)));
            }
        }
        return result;
    }

    public SessionSummary getSession(long id) {
        String sql = "SELECT s.id,s.started_at,s.ended_at,s.title,COUNT(c.id)," +
                "COALESCE((SELECT chinese FROM captions WHERE session_id=s.id AND chinese<>'' ORDER BY id DESC LIMIT 1),'') " +
                "FROM sessions s LEFT JOIN captions c ON c.session_id=s.id WHERE s.id=? GROUP BY s.id";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(id)})) {
            if (cursor.moveToFirst()) {
                return new SessionSummary(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2), cursor.getString(3), cursor.getInt(4), cursor.getString(5));
            }
        }
        return null;
    }

    public void deleteSession(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("captions", "session_id=?", new String[]{String.valueOf(id)});
            db.delete("sessions", "id=?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void renameSession(long id, String title) {
        ContentValues values = new ContentValues();
        values.put("title", title == null ? "" : title.trim());
        getWritableDatabase().update("sessions", values, "id=?", new String[]{String.valueOf(id)});
    }

    public static String formatDuration(long startedAt, long endedAt) {
        long end = endedAt > 0 ? endedAt : System.currentTimeMillis();
        long seconds = Math.max(1, (end - startedAt) / 1000);
        if (seconds < 60) return seconds + " 秒";
        long minutes = seconds / 60;
        long remainder = seconds % 60;
        if (minutes < 60) return remainder == 0 ? minutes + " 分钟" : minutes + " 分 " + remainder + " 秒";
        long hours = minutes / 60;
        long minuteRemainder = minutes % 60;
        return minuteRemainder == 0 ? hours + " 小时" : hours + " 小时 " + minuteRemainder + " 分";
    }

    public void clearSessions() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("captions", null, null);
            db.delete("sessions", null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<Caption> getCaptions(long sessionId) {
        List<Caption> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("captions", new String[]{"english", "chinese"},
                "session_id=?", new String[]{String.valueOf(sessionId)}, null, null, "id ASC")) {
            while (cursor.moveToNext()) result.add(new Caption(cursor.getString(0), cursor.getString(1)));
        }
        return result;
    }

    public String exportText(long sessionId) {
        SessionSummary session = getSession(sessionId);
        String heading = session != null && !session.title.isEmpty() ? session.title : "外教课双语记录";
        StringBuilder text = new StringBuilder(heading).append("\n\n");
        for (Caption caption : getCaptions(sessionId)) {
            if (!caption.english.isEmpty()) text.append(caption.english).append('\n');
            if (!caption.chinese.isEmpty()) text.append(caption.chinese).append("\n\n");
        }
        return text.toString();
    }
}
