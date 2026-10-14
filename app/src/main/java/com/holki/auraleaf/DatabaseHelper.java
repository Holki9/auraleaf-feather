package com.holki.auraleaf;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "auraleaf_pro.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_BOOKS = "books";
    public static final String COL_ID = "id";
    public static final String COL_TITLE = "title";
    public static final String COL_URI = "uri";
    public static final String COL_TOTAL_PAGES = "total_pages";
    public static final String COL_LAST_PAGE = "last_page";
    public static final String COL_COVER_PATH = "cover_path";
    public static final String COL_UPDATED_AT = "updated_at";
    public static final String COL_GROUP_ID = "group_id";

    public static final String TABLE_GROUPS = "book_groups";
    public static final String COL_GROUP_TITLE = "title";
    public static final String COL_GROUP_CREATED_AT = "created_at";

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createBooksTable = "CREATE TABLE " + TABLE_BOOKS + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_TITLE + " TEXT, " +
                COL_URI + " TEXT, " +
                COL_TOTAL_PAGES + " INTEGER, " +
                COL_LAST_PAGE + " INTEGER, " +
                COL_COVER_PATH + " TEXT, " +
                COL_UPDATED_AT + " INTEGER, " +
                COL_GROUP_ID + " INTEGER DEFAULT 0)";
        db.execSQL(createBooksTable);

        String createGroupsTable = "CREATE TABLE " + TABLE_GROUPS + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_GROUP_TITLE + " TEXT NOT NULL, " +
                COL_GROUP_CREATED_AT + " INTEGER)";
        db.execSQL(createGroupsTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_BOOKS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_GROUPS);
        onCreate(db);
    }

    public long insertGroup(String title) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_GROUP_TITLE, title.trim());
        cv.put(COL_GROUP_CREATED_AT, System.currentTimeMillis());
        return db.insert(TABLE_GROUPS, null, cv);
    }

    public void renameGroup(long groupId, String newTitle) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_GROUP_TITLE, newTitle.trim());
        db.update(TABLE_GROUPS, cv, COL_ID + "=?", new String[]{String.valueOf(groupId)});
    }

    public void deleteGroup(long groupId, boolean deleteBooks) {
        SQLiteDatabase db = getWritableDatabase();
        if (deleteBooks) {
            db.delete(TABLE_BOOKS, COL_GROUP_ID + "=?", new String[]{String.valueOf(groupId)});
        } else {
            ContentValues cv = new ContentValues();
            cv.put(COL_GROUP_ID, 0);
            db.update(TABLE_BOOKS, cv, COL_GROUP_ID + "=?", new String[]{String.valueOf(groupId)});
        }
        db.delete(TABLE_GROUPS, COL_ID + "=?", new String[]{String.valueOf(groupId)});
    }

    public List<GroupItem> getGroups() {
        List<GroupItem> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_GROUPS, null, null, null, null, null, COL_GROUP_CREATED_AT + " DESC");
        if (cursor != null && cursor.moveToFirst()) {
            do {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID));
                String title = cursor.getString(cursor.getColumnIndexOrThrow(COL_GROUP_TITLE));
                long createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_GROUP_CREATED_AT));

                // Get book count and top 4 covers for preview collage
                int count = 0;
                List<String> covers = new ArrayList<>();
                Cursor cBooks = db.rawQuery(
                        "SELECT " + COL_COVER_PATH + " FROM " + TABLE_BOOKS +
                        " WHERE " + COL_GROUP_ID + "=? ORDER BY " + COL_UPDATED_AT + " DESC",
                        new String[]{String.valueOf(id)}
                );
                if (cBooks != null) {
                    count = cBooks.getCount();
                    int added = 0;
                    while (cBooks.moveToNext() && added < 4) {
                        String cp = cBooks.getString(0);
                        if (cp != null && !cp.isEmpty()) {
                            covers.add(cp);
                            added++;
                        }
                    }
                    cBooks.close();
                }

                list.add(new GroupItem(id, title, count, covers, createdAt));
            } while (cursor.moveToNext());
            cursor.close();
        }
        return list;
    }

    public void setBookGroup(long bookId, long groupId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_GROUP_ID, groupId);
        cv.put(COL_UPDATED_AT, System.currentTimeMillis());
        db.update(TABLE_BOOKS, cv, COL_ID + "=?", new String[]{String.valueOf(bookId)});
    }

    public long insertBook(String title, String uri, int totalPages, int lastPage, String coverPath) {
        return insertBook(title, uri, totalPages, lastPage, coverPath, 0);
    }

    public long insertBook(String title, String uri, int totalPages, int lastPage, String coverPath, long groupId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_TITLE, title);
        cv.put(COL_URI, uri);
        cv.put(COL_TOTAL_PAGES, totalPages);
        cv.put(COL_LAST_PAGE, lastPage);
        cv.put(COL_COVER_PATH, coverPath);
        cv.put(COL_UPDATED_AT, System.currentTimeMillis());
        cv.put(COL_GROUP_ID, groupId);
        return db.insert(TABLE_BOOKS, null, cv);
    }

    public void updateProgress(long id, int lastPage) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_LAST_PAGE, lastPage);
        cv.put(COL_UPDATED_AT, System.currentTimeMillis());
        db.update(TABLE_BOOKS, cv, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    public void renameBook(long id, String newTitle) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_TITLE, newTitle);
        cv.put(COL_UPDATED_AT, System.currentTimeMillis());
        db.update(TABLE_BOOKS, cv, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    public void deleteBook(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_BOOKS, COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    public List<BookItem> getRootBooks() {
        return getBooksByCondition(COL_GROUP_ID + "=0 OR " + COL_GROUP_ID + " IS NULL", null);
    }

    public List<BookItem> getBooksInGroup(long groupId) {
        return getBooksByCondition(COL_GROUP_ID + "=?", new String[]{String.valueOf(groupId)});
    }

    public List<BookItem> getAllBooks() {
        return getBooksByCondition(null, null);
    }

    private List<BookItem> getBooksByCondition(String selection, String[] selectionArgs) {
        List<BookItem> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_BOOKS, null, selection, selectionArgs, null, null, COL_UPDATED_AT + " DESC");
        if (cursor != null && cursor.moveToFirst()) {
            do {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID));
                String title = cursor.getString(cursor.getColumnIndexOrThrow(COL_TITLE));
                String uri = cursor.getString(cursor.getColumnIndexOrThrow(COL_URI));
                int total = cursor.getInt(cursor.getColumnIndexOrThrow(COL_TOTAL_PAGES));
                int last = cursor.getInt(cursor.getColumnIndexOrThrow(COL_LAST_PAGE));
                String cover = cursor.getString(cursor.getColumnIndexOrThrow(COL_COVER_PATH));
                long updated = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT));
                long groupId = 0;
                int gCol = cursor.getColumnIndex(COL_GROUP_ID);
                if (gCol != -1) {
                    groupId = cursor.getLong(gCol);
                }

                list.add(new BookItem(id, title, uri, total, last, cover, updated, groupId));
            } while (cursor.moveToNext());
            cursor.close();
        }
        return list;
    }
}
