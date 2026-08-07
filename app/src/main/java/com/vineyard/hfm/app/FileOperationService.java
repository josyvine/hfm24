package com.vineyard.hfm.app;

import android.app.Notification;
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

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// --- UPDATE 1: Add necessary imports for Manifest and ContextCompat ---
import android.Manifest;

public class FileOperationService extends Service {

    private static final String TAG = "FileOperationService";
    private static final String CHANNEL_ID = "FileOperationChannel";
    private static final int NOTIFICATION_ID = 2;

    public static final String ACTION_START_OPERATION = "com.vineyard.hfm.app.action.START_OPERATION";
    public static final String ACTION_STOP_OPERATION = "com.vineyard.hfm.app.action.STOP_OPERATION";

    public static final String ACTION_OPERATION_PROGRESS = "com.vineyard.hfm.app.action.OPERATION_PROGRESS";
    public static final String ACTION_OPERATION_COMPLETE = "com.vineyard.hfm.app.action.OPERATION_COMPLETE";

    public static final String EXTRA_SOURCE_FILES = "source_files";
    public static final String EXTRA_DESTINATION_DIR = "destination_dir";
    public static final String EXTRA_OPERATION_TYPE = "operation_type";
    public static final String EXTRA_PROGRESS_TEXT = "progress_text";
    public static final String EXTRA_PROGRESS_VALUE = "progress_value";
    public static final String EXTRA_SUCCESS = "success";

    private Thread operationThread;
    private volatile boolean isCancelled = false;
    
    // --- UPDATE 2: Add a flag to track if we can show notifications ---
    private boolean canShowNotification = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START_OPERATION.equals(intent.getAction())) {
            createNotificationChannel();
            
            // --- UPDATE 3: Check for notification permission before starting foreground service ---
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    canShowNotification = true;
                }
            } else {
                canShowNotification = true;
            }

            if (canShowNotification) {
                startForeground(NOTIFICATION_ID, createNotification("Preparing...", 0));
            }


            final ArrayList<File> sourceFiles = (ArrayList<File>) intent.getSerializableExtra(EXTRA_SOURCE_FILES);
            final File destinationDir = (File) intent.getSerializableExtra(EXTRA_DESTINATION_DIR);
            final ClipboardManager.Operation operation = (ClipboardManager.Operation) intent.getSerializableExtra(EXTRA_OPERATION_TYPE);

            isCancelled = false;
            operationThread = new Thread(new Runnable() {
					@Override
					public void run() {
						performOperation(sourceFiles, destinationDir, operation);
					}
				});
            operationThread.start();
        } else if (intent != null && ACTION_STOP_OPERATION.equals(intent.getAction())) {
            isCancelled = true;
            if (operationThread != null) {
                operationThread.interrupt();
            }
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void performOperation(List<File> sourceFiles, File destinationDir, ClipboardManager.Operation operation) {
        boolean success = true;
        int totalFiles = countTotalFiles(sourceFiles);
        final int[] processedFiles = {0};

        try {
            for (File sourceFile : sourceFiles) {
                if (isCancelled) {
                    success = false;
                    break;
                }

                if (!sourceFile.exists()) {
                    continue;
                }

                if (operation == ClipboardManager.Operation.COPY) {
                    if (sourceFile.isDirectory()) {
                        copyDirectory(sourceFile, new File(destinationDir, sourceFile.getName()), totalFiles, processedFiles);
                    } else {
                        copyFile(sourceFile, new File(destinationDir, sourceFile.getName()), totalFiles, processedFiles);
                    }
                } else if (operation == ClipboardManager.Operation.MOVE) {
                    moveFile(sourceFile, destinationDir, totalFiles, processedFiles);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "File operation failed", e);
            success = false;
        } finally {
            broadcastCompletion(success);
            stopSelf();
        }
    }


    private int countTotalFiles(List<File> files) {
        int count = 0;
        if (files == null) return 0;
        for (File file : files) {
            if (file.isDirectory()) {
                count += countTotalFiles(Arrays.asList(file.listFiles()));
            }
            count++;
        }
        return count;
    }

    private void updateProgress(String text, int progress) {
        // --- UPDATE 4: Check permission before updating notification ---
        if (canShowNotification) {
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.notify(NOTIFICATION_ID, createNotification(text, progress));
        }

        // Broadcast progress to activity regardless of notification permission
        Intent intent = new Intent(ACTION_OPERATION_PROGRESS);
        intent.putExtra(EXTRA_PROGRESS_TEXT, text);
        intent.putExtra(EXTRA_PROGRESS_VALUE, progress);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastCompletion(boolean success) {
        Intent intent = new Intent(ACTION_OPERATION_COMPLETE);
        intent.putExtra(EXTRA_SUCCESS, success);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void copyFile(File source, File dest, int total, int[] processed) throws IOException {
        if (isCancelled) return;
        processed[0]++;
        int progress = (int) (((float) processed[0] / total) * 100);
        updateProgress("Copying: " + source.getName(), progress);

        if (dest.exists()) dest = getUniqueFile(dest);

        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(source);
            out = StorageUtils.getOutputStream(this, dest);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                if (isCancelled) break;
                out.write(buf, 0, len);
            }
        } finally {
            if (in != null) in.close();
            if (out != null) out.close();
            scanFile(dest);
        }
    }

    private void copyDirectory(File source, File dest, int total, int[] processed) throws IOException {
        if (isCancelled) return;
        processed[0]++;
        int progress = (int) (((float) processed[0] / total) * 100);
        updateProgress("Creating folder: " + source.getName(), progress);

        if (dest.exists()) dest = getUniqueFile(dest);
        if (!StorageUtils.createDirectory(this, dest)) {
            throw new IOException("Failed to create destination directory: " + dest.getAbsolutePath());
        }
        scanFile(dest);

        File[] files = source.listFiles();
        if (files != null) {
            for (File file : files) {
                if (isCancelled) break;
                if (file.isDirectory()) {
                    copyDirectory(file, new File(dest, file.getName()), total, processed);
                } else {
                    copyFile(file, new File(dest, file.getName()), total, processed);
                }
            }
        }
    }

    private void moveFile(File source, File destDir, int total, int[] processed) throws IOException {
        File newFile = new File(destDir, source.getName());
        if (newFile.exists()) newFile = getUniqueFile(newFile);

        if (source.renameTo(newFile)) {
            processed[0]++;
            int progress = (int) (((float) processed[0] / total) * 100);
            updateProgress("Moving: " + source.getName(), progress);
            scanFile(source);
            scanFile(newFile);
        } else {
            if (source.isDirectory()) {
                copyDirectory(source, newFile, total, processed);
            } else {
                copyFile(source, newFile, total, processed);
            }
            if (!isCancelled) {
                deleteRecursive(source);
            }
        }
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        if (StorageUtils.deleteFile(this, fileOrDirectory)) {
            scanFile(fileOrDirectory);
        }
    }

    private File getUniqueFile(File file) {
        String parentPath = file.getParent();
        String fileName = file.getName();
        String extension = "";
        String baseName = fileName;

        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex);
        }

        int count = 1;
        File newFile = file;
        while (newFile.exists()) {
            newFile = new File(parentPath, baseName + " (" + count + ")" + extension);
            count++;
        }
        return newFile;
    }

    private void scanFile(File file) {
        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        Uri contentUri = Uri.fromFile(file);
        mediaScanIntent.setData(contentUri);
        sendBroadcast(mediaScanIntent);
    }

    private Notification createNotification(String content, int progress) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle("File Operation")
			.setContentText(content)
			.setSmallIcon(android.R.drawable.ic_menu_save)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setOngoing(true);
        builder.setProgress(100, progress, false);
        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
				CHANNEL_ID,
				"File Operation Channel",
				NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isCancelled = true;
        if (operationThread != null) {
            operationThread.interrupt();
        }
    }
}