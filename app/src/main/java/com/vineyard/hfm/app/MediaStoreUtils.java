package com.vineyard.hfm.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.util.List;

public class MediaStoreUtils {

    private static final String TAG = "MediaStoreUtils";

    /**
     * Instantly purges a single file path from Android's MediaStore database.
     * Prevents ghost thumbnails from appearing in search queries after files are moved/recycled.
     */
    public static void purgePathFromMediaStore(Context context, String filePath) {
        if (filePath == null || filePath.isEmpty()) return;

        try {
            ContentResolver resolver = context.getContentResolver();
            Uri contentUri = MediaStore.Files.getContentUri("external");
            String where = MediaStore.Files.FileColumns.DATA + " = ?";
            String[] selectionArgs = new String[]{ filePath };

            int deletedRows = resolver.delete(contentUri, where, selectionArgs);
            Log.d(TAG, "Purged " + deletedRows + " row(s) from MediaStore for: " + filePath);
        } catch (Exception e) {
            Log.e(TAG, "Failed to purge path from MediaStore: " + filePath, e);
        }

        // Trigger system media scan on the deleted source path to force OS cache purge
        try {
            MediaScannerConnection.scanFile(context, new String[]{ filePath }, null, null);
        } catch (Exception e) {
            Log.e(TAG, "Error notifying MediaScanner for purged path", e);
        }
    }

    /**
     * Performs a bulk purge of multiple file paths from MediaStore in a single SQL operation.
     */
    public static void purgePathsFromMediaStore(Context context, List<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) return;

        ContentResolver resolver = context.getContentResolver();
        Uri contentUri = MediaStore.Files.getContentUri("external");

        // Process in chunks of 500 to stay well within SQLite variable parameter limits
        final int chunkSize = 500;
        for (int i = 0; i < filePaths.size(); i += chunkSize) {
            int end = Math.min(i + chunkSize, filePaths.size());
            List<String> chunk = filePaths.subList(i, end);

            StringBuilder where = new StringBuilder(MediaStore.Files.FileColumns.DATA + " IN (");
            String[] selectionArgs = new String[chunk.size()];

            for (int j = 0; j < chunk.size(); j++) {
                where.append("?");
                if (j < chunk.size() - 1) {
                    where.append(",");
                }
                selectionArgs[j] = chunk.get(j);
            }
            where.append(")");

            try {
                int deletedRows = resolver.delete(contentUri, where.toString(), selectionArgs);
                Log.d(TAG, "Batch purged " + deletedRows + " row(s) from MediaStore.");
            } catch (Exception e) {
                Log.e(TAG, "Failed batch MediaStore purge", e);
            }
        }

        // Force MediaScanner update across all paths
        try {
            String[] pathsArray = filePaths.toArray(new String[0]);
            MediaScannerConnection.scanFile(context, pathsArray, null, null);
        } catch (Exception e) {
            Log.e(TAG, "Error triggering MediaScanner batch update", e);
        }
    }

    /**
     * Scans a newly created/moved destination file (such as in the Recycle Bin) so it is properly indexed.
     */
    public static void scanNewPath(Context context, File newFile) {
        if (newFile == null) return;
        try {
            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            mediaScanIntent.setData(Uri.fromFile(newFile));
            context.sendBroadcast(mediaScanIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to scan new path: " + newFile.getAbsolutePath(), e);
        }
    }
}