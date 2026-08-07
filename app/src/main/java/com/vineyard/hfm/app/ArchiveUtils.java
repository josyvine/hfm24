package com.vineyard.hfm.app;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import  androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.github.junrar.Archive;
import com.github.junrar.exception.RarException;
// --- FIX 1: This import is removed because the class no longer exists in the new library version ---
// import com.github.junrar.impl.FileVolumeManager;
import com.github.junrar.rarfile.FileHeader;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.progress.ProgressMonitor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class ArchiveUtils {

    private static final String TAG = "ArchiveUtils";
    private static final String NOTIFICATION_CHANNEL_ID = "ArchiveExtractionChannel";
    private static final int NOTIFICATION_ID = 3;

    public static void extractArchive(Context context, File archiveFile, File destinationDir) {
        String fileName = archiveFile.getName().toLowerCase();
        if (fileName.endsWith(".zip")) {
            new UnzipTask(context, destinationDir).execute(archiveFile);
        } else if (fileName.endsWith(".rar")) {
            new UnrarTask(context, destinationDir).execute(archiveFile);
        } else {
            Toast.makeText(context, "Unsupported archive format", Toast.LENGTH_SHORT).show();
        }
    }

    public static void startCompression(Context context, List<File> sourceFiles, File destinationDir) {
        Intent serviceIntent = new Intent(context, CompressionService.class);
        serviceIntent.setAction(CompressionService.ACTION_START_COMPRESSION);

        serviceIntent.putExtra(CompressionService.EXTRA_SOURCE_FILES, new ArrayList<File>(sourceFiles));
        serviceIntent.putExtra(CompressionService.EXTRA_DESTINATION_DIR, (Serializable) destinationDir);

        ContextCompat.startForegroundService(context, serviceIntent);
    }

    private static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null && manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Archive Extraction",
                    NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Shows progress of archive extraction");
                manager.createNotificationChannel(channel);
            }
        }
    }

    private static NotificationCompat.Builder buildNotification(Context context, String contentText, int progress) {
        return new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Extracting Archive")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_set_as)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(100, progress, false);
    }

    private static void scanFile(Context context, File file) {
        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        Uri contentUri = Uri.fromFile(file);
        mediaScanIntent.setData(contentUri);
        context.sendBroadcast(mediaScanIntent);
    }

    private static class UnzipTask extends AsyncTask<File, Integer, Boolean> {
        private Context context;
        private File destinationDir;
        private AlertDialog progressDialog;

        UnzipTask(Context context, File destinationDir) {
            this.context = context;
            this.destinationDir = destinationDir;
        }

        @Override
        protected void onPreExecute() {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setView(R.layout.dialog_extraction_progress);
            builder.setCancelable(false);
            progressDialog = builder.create();
            progressDialog.show();
        }

        @Override
        protected Boolean doInBackground(File... files) {
            File archiveFile = files[0];
            try {
                ZipFile zipFile = new ZipFile(archiveFile);
                ProgressMonitor progressMonitor = zipFile.getProgressMonitor();
                zipFile.setRunInThread(true);
                zipFile.extractAll(destinationDir.getAbsolutePath());

                while (!progressMonitor.getState().equals(ProgressMonitor.State.READY)) {
                    publishProgress(progressMonitor.getPercentDone());
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                }

                return progressMonitor.getResult().equals(ProgressMonitor.Result.SUCCESS);
            } catch (Exception e) {
                Log.e(TAG, "Error during zip extraction", e);
                return false;
            }
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            if (progressDialog != null && progressDialog.isShowing()) {
                ProgressBar bar = progressDialog.findViewById(R.id.extraction_progress_bar);
                TextView text = progressDialog.findViewById(R.id.extraction_status_text);
                bar.setIndeterminate(false);
                bar.setProgress(values[0]);
                text.setText("Extracting... " + values[0] + "%");
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
            if (success) {
                Toast.makeText(context, "Archive extracted successfully.", Toast.LENGTH_SHORT).show();
                scanFile(context, destinationDir);
                if (context instanceof StorageBrowserActivity) {
                    ((StorageBrowserActivity) context).refreshCurrentDirectory();
                }
            } else {
                Toast.makeText(context, "Failed to extract archive.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private static class UnrarTask extends AsyncTask<File, String, Boolean> {
        private Context context;
        private File destinationDir;
        private AlertDialog progressDialog;

        UnrarTask(Context context, File destinationDir) {
            this.context = context;
            this.destinationDir = destinationDir;
        }

        @Override
        protected void onPreExecute() {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setView(R.layout.dialog_extraction_progress);
            builder.setCancelable(false);
            progressDialog = builder.create();
            progressDialog.show();
        }

        @Override
        protected Boolean doInBackground(File... files) {
            File archiveFile = files[0];
            Archive archive = null;
            try {
                // --- FIX 2: The new version of the library is simpler. ---
                // It no longer requires FileVolumeManager. You pass the File object directly.
                archive = new Archive(archiveFile);
                if (archive != null) {
                    FileHeader fh = archive.nextFileHeader();
                    while (fh != null) {
                        String fileName = fh.getFileName().trim(); // Use getFileName() instead of getFileNameString()
                        publishProgress(fileName);
                        File destFile = new File(destinationDir, fileName);
                        if (fh.isDirectory()) {
                            if (!destFile.exists()) {
                                StorageUtils.createDirectory(context, destFile);
                            }
                        } else {
                            OutputStream out = null;
                            try {
                                out = StorageUtils.getOutputStream(context, destFile);
                                archive.extractFile(fh, out);
                            } finally {
                                if (out != null) {
                                    out.close();
                                }
                            }
                        }
                        fh = archive.nextFileHeader();
                    }
                }
                return true;
            } catch (RarException | IOException e) {
                Log.e(TAG, "Error during rar extraction", e);
                return false;
            } finally {
                if (archive != null) {
                    try {
                        archive.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Error closing rar archive", e);
                    }
                }
            }
        }

        @Override
        protected void onProgressUpdate(String... values) {
            if (progressDialog != null && progressDialog.isShowing()) {
                TextView text = progressDialog.findViewById(R.id.extraction_status_text);
                text.setText("Extracting: " + values[0]);
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
            if (success) {
                Toast.makeText(context, "Archive extracted successfully.", Toast.LENGTH_SHORT).show();
                scanFile(context, destinationDir);
                if (context instanceof StorageBrowserActivity) {
                    ((StorageBrowserActivity) context).refreshCurrentDirectory();
                }
            } else {
                Toast.makeText(context, "Failed to extract archive.", Toast.LENGTH_LONG).show();
            }
        }
    }
}