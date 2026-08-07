package com.vineyard.hfm.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

public class RecycleMetadataDatabase extends SQLiteOpenHelper {

    private static final String TAG = "RecycleMetadataDB";
    private static final String DATABASE_NAME = "hfm_recycle_metadata.db";
    private static final int DATABASE_VERSION = 1;

    private static final String TABLE_NAME = "recycle_metadata";
    private static final String COL_RECYCLED_NAME = "recycled_name";
    private static final String COL_ORIGINAL_PATH = "original_path";

    private static RecycleMetadataDatabase instance;

    /**
     * Singleton instance provider. Ensures a single connection handle is shared
     * across tasks to avoid database locking issues during parallel operations.
     */
    public static synchronized RecycleMetadataDatabase getInstance(Context context) {
        if (instance == null) {
            instance = new RecycleMetadataDatabase(context.getApplicationContext());
        }
        return instance;
    }

    private RecycleMetadataDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTableQuery = "CREATE TABLE " + TABLE_NAME + " (" +
                COL_RECYCLED_NAME + " TEXT PRIMARY KEY, " +
                COL_ORIGINAL_PATH + " TEXT" +
                ")";
        db.execSQL(createTableQuery);
        Log.d(TAG, "Database metadata mapping table created: " + TABLE_NAME);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        onCreate(db);
    }

    /**
     * Persists or replaces a mapping entry.
     *
     * @param recycledName The uniquely suffixed file name saved inside HFMRecycleBin.
     * @param originalPath The absolute system folder path where the file resided before deletion.
     */
    public synchronized void saveRecord(String recycledName, String originalPath) {
        if (recycledName == null || originalPath == null) return;
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_RECYCLED_NAME, recycledName);
        values.put(COL_ORIGINAL_PATH, originalPath);
        try {
            long result = db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            if (result == -1) {
                Log.e(TAG, "Failed to insert/replace metadata record for: " + recycledName);
            } else {
                Log.d(TAG, "Successfully saved metadata mapping: " + recycledName + " -> " + originalPath);
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception saving metadata record", e);
        }
    }

    /**
     * Retrieves the absolute original path associated with a recycled filename.
     *
     * @param recycledName The target filename inside the bin directory.
     * @return The absolute original folder path, or null if no mapping exists.
     */
    public synchronized String getOriginalPath(String recycledName) {
        if (recycledName == null) return null;
        SQLiteDatabase db = this.getReadableDatabase();
        String originalPath = null;
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_NAME, new String[]{COL_ORIGINAL_PATH},
                    COL_RECYCLED_NAME + " = ?", new String[]{recycledName},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                originalPath = cursor.getString(cursor.getColumnIndexOrThrow(COL_ORIGINAL_PATH));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying original path for: " + recycledName, e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return originalPath;
    }

    /**
     * Deletes a mapping entry from the database. Called when files are restored or permanently deleted.
     *
     * @param recycledName The unique file name to remove.
     */
    public synchronized void deleteRecord(String recycledName) {
        if (recycledName == null) return;
        SQLiteDatabase db = this.getWritableDatabase();
        try {
            int rowsDeleted = db.delete(TABLE_NAME, COL_RECYCLED_NAME + " = ?", new String[]{recycledName});
            Log.d(TAG, "Deleted metadata record for: " + recycledName + " | Rows affected: " + rowsDeleted);
        } catch (Exception e) {
            Log.e(TAG, "Error deleting metadata record for: " + recycledName, e);
        }
    }

    /**
     * Clears all database records. Called when emptying the recycle bin.
     */
    public synchronized void clearAll() {
        SQLiteDatabase db = this.getWritableDatabase();
        try {
            db.delete(TABLE_NAME, null, null);
            Log.d(TAG, "Cleared all records from recycle metadata mapping table.");
        } catch (Exception e) {
            Log.e(TAG, "Error clearing recycle metadata table", e);
        }
    }
}