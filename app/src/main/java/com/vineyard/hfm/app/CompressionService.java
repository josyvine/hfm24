package com.vineyard.hfm.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.progress.ProgressMonitor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.UUID;

// --- UPDATE 1: Add necessary imports for Manifest and ContextCompat ---
import android.Manifest;

public class CompressionService extends Service {

    private static final String TAG = "CompressionService";
    private static final String CHANNEL_ID = "CompressionChannel";
    private static final int NOTIFICATION_ID = 4;

    public static final String ACTION_START_COMPRESSION = "com.vineyard.hfm.app.action.START_COMPRESSION";
    public static final String ACTION_STOP_COMPRESSION = "com.vineyard.hfm.app.action.STOP_COMPRESSION";
    public static final String ACTION_COMPRESSION_COMPLETE = "com.vineyard.hfm.app.action.COMPRESSION_COMPLETE";

    public static final String EXTRA_SOURCE_FILES = "source_files";
    public static final String EXTRA_DESTINATION_DIR = "destination_dir";
    public static final String EXTRA_SUCCESS = "success";

    private volatile boolean isCancelled = false;
    private Thread compressionThread;
    private ZipFile zipFile;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            final String action = intent.getAction();
            if (ACTION_START_COMPRESSION.equals(action)) {
                final ArrayList<File> sourceFiles = (ArrayList<File>) intent.getSerializableExtra(EXTRA_SOURCE_FILES);
                final File destinationDir = (File) intent.getSerializableExtra(EXTRA_DESTINATION_DIR);

                // --- UPDATE 2: Check for notification permission before showing the foreground notification ---
                boolean canShowNotification = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        canShowNotification = true;
                    }
                } else {
                    canShowNotification = true;
                }
                
                if (canShowNotification) {
                    startForeground(NOTIFICATION_ID, buildNotification("Preparing...", 0, true).build());
                }


                isCancelled = false;
                compressionThread = new Thread(new Runnable() {
						@Override
						public void run() {
							performCompression(sourceFiles, destinationDir);
						}
					});
                compressionThread.start();

            } else if (ACTION_STOP_COMPRESSION.equals(action)) {
                isCancelled = true;
                if (zipFile != null && zipFile.getProgressMonitor().getState() == ProgressMonitor.State.BUSY) {
                    zipFile.getProgressMonitor().setCancelAllTasks(true);
                }
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    private void performCompression(ArrayList<File> sourceFiles, File destinationDir) {
        File finalZipFile = getUniqueFile(new File(destinationDir, "Archive.zip"));
        File tempZipFile = new File(getCacheDir(), UUID.randomUUID().toString() + ".zip");
        boolean success = false;

        try {
            zipFile = new ZipFile(tempZipFile);
            zipFile.setRunInThread(true);

            for (File file : sourceFiles) {
                if (isCancelled) break;
                if (file.isDirectory()) {
                    zipFile.addFolder(file);
                } else {
                    zipFile.addFile(file);
                }
            }

            if (!isCancelled) {
                ProgressMonitor progressMonitor = zipFile.getProgressMonitor();
                while (!progressMonitor.getState().equals(ProgressMonitor.State.READY)) {
                    if (isCancelled) {
                        progressMonitor.setCancelAllTasks(true);
                        break;
                    }
                    updateNotification("Compressing...", progressMonitor.getPercentDone(), false);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        break;
                    }
                }

                if (progressMonitor.getResult().equals(ProgressMonitor.Result.SUCCESS)) {
                    updateNotification("Finalizing...", 100, true);
                    if (moveFile(tempZipFile, finalZipFile)) {
                        success = true;
                        scanFile(finalZipFile);
                    } else {
                        Log.e(TAG, "Failed to move temp zip file to final destination.");
                    }
                } else if (progressMonitor.getResult().equals(ProgressMonitor.Result.CANCELLED)) {
                    Log.d(TAG, "Compression was cancelled.");
                } else {
                    Log.e(TAG, "Zip operation failed: " + progressMonitor.getResult());
                    if (progressMonitor.getException() != null) {
                        throw progressMonitor.getException();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Compression failed", e);
            success = false;
        } finally {
            if (tempZipFile.exists()) {
                tempZipFile.delete();
            }
            broadcastCompletion(success, finalZipFile.getName());
            stopSelf();
        }
    }

    private boolean moveFile(File source, File dest) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(source);
            out = StorageUtils.getOutputStream(this, dest);

            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to move file from " + source.getPath() + " to " + dest.getPath(), e);
            return false;
        } finally {
            try {
                if (in != null) in.close();
                if (out != null) out.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing streams during move", e);
            }
        }
    }


    private File getUniqueFile(File file) {
        String parentPath = file.getParent();
        String fileName = file.getName();
        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
        String extension = fileName.substring(fileName.lastIndexOf('.'));

        int count = 1;
        File newFile = file;
        while (newFile.exists()) {
            newFile = new File(parentPath, baseName + " (" + count + ")" + extension);
            count++;
        }
        return newFile;
    }

    private void updateNotification(String content, int progress, boolean indeterminate) {
        // --- UPDATE 3: Check for permission before updating the notification ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // If we don't have permission, do not attempt to show the notification.
                return;
            }
        }
        
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationCompat.Builder builder = buildNotification(content, progress, indeterminate);
        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    private NotificationCompat.Builder buildNotification(String content, int progress, boolean indeterminate) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Compressing Files")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(100, progress, indeterminate);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "Compression Service",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private void broadcastCompletion(boolean success, String finalFileName) {
        if (isCancelled) return;
        
        // --- UPDATE 4: Check for permission before showing the final notification ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // We don't have permission, but we still need to send the broadcast and stop the service.
                Intent intent = new Intent(ACTION_COMPRESSION_COMPLETE);
                intent.putExtra(EXTRA_SUCCESS, success);
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
                return;
            }
        }
        
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (success) {
            NotificationCompat.Builder builder = buildNotification("Compression complete: " + finalFileName, 0, false)
                .setOngoing(false);
            notificationManager.notify(NOTIFICATION_ID, builder.build());
        } else {
            NotificationCompat.Builder builder = buildNotification("Compression failed.", 0, false)
                .setOngoing(false);
            notificationManager.notify(NOTIFICATION_ID, builder.build());
        }

        Intent intent = new Intent(ACTION_COMPRESSION_COMPLETE);
        intent.putExtra(EXTRA_SUCCESS, success);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void scanFile(File file) {
        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        mediaScanIntent.setData(Uri.fromFile(file));
        sendBroadcast(mediaScanIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isCancelled = true;
        if (compressionThread != null) {
            compressionThread.interrupt();
        }
        stopForeground(true);
    }
}