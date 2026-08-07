package com.vineyard.hfm.app;

import android.app.AlertDialog;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import com.bumptech.glide.Glide;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Centralized High-Performance Recycling & MediaStore Synchronization Engine.
 * 
 * Fixes Glitch 1: Purges MediaStore DB entries by ID & clears Glide memory cache to eliminate ghost thumbnails.
 * Fixes Glitch 2: Runs on parallel thread pool without full disk cache wiping for instant recycling.
 * Logs diagnostic metrics directly to AppLogger (/sdcard/hfm log report/hfm_diagnostic_log.txt).
 */
public class RecycleManager {

    private static final String TAG = "HFM_RecycleManager";

    public interface RecycleCallback {
        void onRecycleProgress(String currentFileName, int processed, int total);
        void onRecycleComplete(List<File> successfullyMovedFiles, int totalCount);
    }

    /**
     * Public method to execute recycling in background.
     * Uses AsyncTask.THREAD_POOL_EXECUTOR to execute immediately on a parallel thread pool.
     */
    public static void recycleFiles(final Context context, final List<File> filesToMove, final boolean useSdCardBin, final RecycleCallback callback) {
        new RecycleTask(context, filesToMove, useSdCardBin, callback).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static class RecycleTask extends AsyncTask<Void, String, List<File>> {
        private final Context context;
        private final List<File> filesToMove;
        private final boolean useSdCardBin;
        private final RecycleCallback callback;
        private AlertDialog progressDialog;

        RecycleTask(Context context, List<File> filesToMove, boolean useSdCardBin, RecycleCallback callback) {
            this.context = context;
            this.filesToMove = filesToMove;
            this.useSdCardBin = useSdCardBin;
            this.callback = callback;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            AppLogger.log(TAG, "========== RECYCLE BATCH STARTED ==========");
            AppLogger.log(TAG, "Batch Size: " + filesToMove.size() + " files | Use SD Bin: " + useSdCardBin);

            try {
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_progress_simple, null);
                builder.setView(dialogView);
                builder.setCancelable(false);
                progressDialog = builder.create();
                progressDialog.show();
            } catch (Exception e) {
                AppLogger.logError(TAG, "Could not show progress dialog", e);
            }
        }

        @Override
        protected List<File> doInBackground(Void... voids) {
            long totalStartTime = System.currentTimeMillis();
            List<File> movedFiles = new ArrayList<>();
            List<String> purgedSourcePaths = new ArrayList<>();
            RecycleMetadataDatabase db = RecycleMetadataDatabase.getInstance(context);

            File phoneRecycleBinDir = new File(Environment.getExternalStorageDirectory(), "HFMRecycleBin");
            if (!useSdCardBin) {
                if (!phoneRecycleBinDir.exists()) {
                    boolean created = phoneRecycleBinDir.mkdirs();
                    AppLogger.log(TAG, "Created Phone Recycle Bin directory: " + phoneRecycleBinDir.getAbsolutePath() + " -> " + created);
                }

                // Create .nomedia file in Phone Recycle Bin so system MediaStore skips indexing it
                File nomediaFile = new File(phoneRecycleBinDir, ".nomedia");
                if (!nomediaFile.exists()) {
                    try {
                        boolean nomediaCreated = nomediaFile.createNewFile();
                        AppLogger.log(TAG, "Created .nomedia in Phone Recycle Bin: " + nomediaCreated);
                    } catch (Exception e) {
                        AppLogger.logError(TAG, "Failed to create .nomedia file in Phone Recycle Bin", e);
                    }
                }
            }

            DocumentFile cachedSdRecycleBin = null;
            if (useSdCardBin) {
                long sdBinStartTime = System.currentTimeMillis();
                cachedSdRecycleBin = StorageUtils.getOrCreateSdCardRecycleBin(context);
                long sdBinDuration = System.currentTimeMillis() - sdBinStartTime;
                AppLogger.logMetric(TAG, "Resolve SD Card Recycle Bin", sdBinDuration, 
                        "Resolved Uri: " + (cachedSdRecycleBin != null ? cachedSdRecycleBin.getUri().toString() : "NULL"));
            }

            // Pre-fetch SD Card path once to eliminate per-file disk query overhead inside loop
            String sdCardPath = StorageUtils.getSdCardPath(context);

            for (int i = 0; i < filesToMove.size(); i++) {
                File sourceFile = filesToMove.get(i);
                if (sourceFile == null || !sourceFile.exists()) {
                    AppLogger.log(TAG, "SKIP | File does not exist on disk: " + (sourceFile != null ? sourceFile.getAbsolutePath() : "NULL"));
                    continue;
                }

                long fileStartTime = System.currentTimeMillis();
                boolean moveSuccess = false;
                File destFile = null;
                String sourcePath = sourceFile.getAbsolutePath();

                publishProgress(sourceFile.getName(), String.valueOf(i + 1), String.valueOf(filesToMove.size()));

                // Fast check if file resides on SD Card
                boolean fileIsOnSd = sdCardPath != null && sourcePath.startsWith(sdCardPath);

                // 1. Try SD Card SAF Move
                if (useSdCardBin && fileIsOnSd) {
                    if (cachedSdRecycleBin != null && StorageUtils.moveFileOnSdCardSafely(context, sourceFile, cachedSdRecycleBin)) {
                        moveSuccess = true;
                        // For SD Card SAF, it retains the source filename.
                        db.saveRecord(sourceFile.getName(), sourcePath);
                        AppLogger.log(TAG, "SAF MOVE SUCCESS | " + sourcePath);
                    } else {
                        AppLogger.log(TAG, "SAF MOVE FAILED | " + sourcePath);
                    }
                } else {
                    // 2. Try Atomic Java File Rename (Internal Phone Storage)
                    destFile = new File(phoneRecycleBinDir, sourceFile.getName());
                    if (destFile.exists()) {
                        String name = sourceFile.getName();
                        String extension = "";
                        int dotIndex = name.lastIndexOf(".");
                        if (dotIndex > 0 && !sourceFile.isDirectory()) {
                            extension = name.substring(dotIndex);
                            name = name.substring(0, dotIndex);
                        }
                        destFile = new File(phoneRecycleBinDir, name + "_" + System.currentTimeMillis() + extension);
                    }

                    long renameStartTime = System.currentTimeMillis();
                    moveSuccess = sourceFile.renameTo(destFile);
                    long renameDuration = System.currentTimeMillis() - renameStartTime;

                    if (moveSuccess) {
                        db.saveRecord(destFile.getName(), sourcePath);
                        AppLogger.logMetric(TAG, "Atomic File.renameTo()", renameDuration, "Moved: " + sourcePath + " -> " + destFile.getAbsolutePath());
                    } else {
                        AppLogger.log(TAG, "RENAME FAILED | File.renameTo() returned false for: " + sourcePath);

                        // Fallback: Use StorageUtils SAF Copy-Delete ONLY if on different volumes
                        boolean isSourceOnSd = fileIsOnSd;
                        boolean isDestOnSd = sdCardPath != null && destFile.getAbsolutePath().startsWith(sdCardPath);

                        if (isSourceOnSd && isDestOnSd) {
                            AppLogger.log(TAG, "BLOCKED | Prevented slow byte-copy on same SD Card volume for: " + sourcePath);
                            moveSuccess = false;
                        } else {
                            AppLogger.log(TAG, "FALLBACK | Attempting copy-delete fallback for cross-volume move...");
                            long copyStartTime = System.currentTimeMillis();
                            if (StorageUtils.copyFile(context, sourceFile, destFile)) {
                                if (StorageUtils.deleteFile(context, sourceFile)) {
                                    moveSuccess = true;
                                    db.saveRecord(destFile.getName(), sourcePath);
                                    AppLogger.logMetric(TAG, "Fallback Copy-Delete", System.currentTimeMillis() - copyStartTime, "Moved: " + sourcePath);
                                } else {
                                    destFile.delete();
                                    AppLogger.log(TAG, "FALLBACK ERROR | Failed to delete source file after copy: " + sourcePath);
                                }
                            } else {
                                AppLogger.log(TAG, "FALLBACK ERROR | Copy failed for: " + sourcePath);
                            }
                        }
                    }
                }

                long fileDuration = System.currentTimeMillis() - fileStartTime;

                if (moveSuccess) {
                    movedFiles.add(sourceFile);
                    purgedSourcePaths.add(sourcePath);
                }

                AppLogger.logMetric(TAG, "Item Recycle Processing", fileDuration, "Result: " + moveSuccess + " | File: " + sourcePath);
            }

            // --- CRITICAL FIX FOR GLITCH 1: PURGE MEDIASTORE DB ENTRIES BY PATH & ID ---
            if (!purgedSourcePaths.isEmpty()) {
                long purgeStartTime = System.currentTimeMillis();
                MediaStoreUtils.purgePathsFromMediaStore(context, purgedSourcePaths);
                long purgeDuration = System.currentTimeMillis() - purgeStartTime;
                AppLogger.logMetric(TAG, "MediaStore System DB Purge", purgeDuration, "Purged " + purgedSourcePaths.size() + " path(s)");
            }

            long totalDuration = System.currentTimeMillis() - totalStartTime;
            AppLogger.logMetric(TAG, "FULL BATCH RECYCLE COMPLETE", totalDuration, 
                    "Successfully moved " + movedFiles.size() + " / " + filesToMove.size() + " files.");
            AppLogger.log(TAG, "========== RECYCLE BATCH ENDED ==========");

            return movedFiles;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            if (callback != null && values.length >= 3) {
                try {
                    int processed = Integer.parseInt(values[1]);
                    int total = Integer.parseInt(values[2]);
                    callback.onRecycleProgress(values[0], processed, total);
                } catch (Exception ignored) {}
            }
        }

        @Override
        protected void onPostExecute(List<File> movedFiles) {
            super.onPostExecute(movedFiles);

            if (progressDialog != null && progressDialog.isShowing()) {
                try {
                    progressDialog.dismiss();
                } catch (Exception ignored) {}
            }

            // Clear Glide memory cache on Main Thread for immediate UI update
            try {
                Glide.get(context).clearMemory();
                AppLogger.log(TAG, "Glide Memory Cache cleared on UI Thread.");
            } catch (Exception e) {
                AppLogger.logError(TAG, "Failed to clear Glide memory cache", e);
            }

            if (movedFiles.isEmpty() && !filesToMove.isEmpty()) {
                Toast.makeText(context, "Failed to move files to Recycle Bin. Check diagnostic log.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(context, movedFiles.size() + " item(s) moved to Recycle Bin.", Toast.LENGTH_SHORT).show();
            }

            if (callback != null) {
                callback.onRecycleComplete(movedFiles, movedFiles.size());
            }
        }
    }
}