package com.vineyard.hfm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import android.Manifest;

public class DeleteService extends Service {

    private static final String TAG = "DeleteService";
    
    // Broadcast constants for the Monitor Popup
    public static final String ACTION_DELETE_LOG = "com.vineyard.hfm.app.action.DELETE_LOG";
    public static final String EXTRA_LOG_MESSAGE = "extra_log_message";

    public static final String ACTION_DELETE_COMPLETE = "com.vineyard.hfm.app.action.DELETE_COMPLETE";
    public static final String EXTRA_FILES_TO_DELETE = "com.vineyard.hfm.app.extra.FILES_TO_DELETE";
    public static final String EXTRA_DELETED_COUNT = "com.vineyard.hfm.app.extra.DELETED_COUNT";

    private static final String NOTIFICATION_CHANNEL_ID = "DeleteServiceChannel";
    private static final int FOREGROUND_ID = 9999; 

    private NotificationManager notificationManager;
    private ExecutorService executorService;
    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final AtomicInteger idGenerator = new AtomicInteger(100); 
    private boolean isServiceForeground = false;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        // Thread pool set to 8 for high-concurrency parallel tasks
        executorService = Executors.newFixedThreadPool(8);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            checkAndStop();
            return START_NOT_STICKY;
        }

        final ArrayList<String> fullList;
        ArrayList<String> bridgedFiles = FileBridge.mFilesToDelete;
        
        if (bridgedFiles != null && !bridgedFiles.isEmpty()) {
            fullList = new ArrayList<>(bridgedFiles);
            FileBridge.mFilesToDelete = new ArrayList<>(); 
        } else {
            ArrayList<String> extraFiles = intent.getStringArrayListExtra(EXTRA_FILES_TO_DELETE);
            fullList = (extraFiles != null) ? new ArrayList<>(extraFiles) : new ArrayList<String>();
        }

        if (fullList.isEmpty()) {
            checkAndStop();
            return START_NOT_STICKY;
        }

        // Get the chosen master batch size (e.g. 50 or 100)
        int batchSize = intent.getIntExtra("batch_size", 10);
        if (batchSize < 1) batchSize = 1;

        if (!isServiceForeground) {
            startForeground(FOREGROUND_ID, createNotification("HFM Delete Engine", "Spawning parallel threads...", 0, 0, true));
            isServiceForeground = true;
        }

        // Auto-open monitor popup
        Intent monitorIntent = new Intent(this, DeletionMonitorActivity.class);
        monitorIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(monitorIntent);

        // Splitting master list into chunks for parallel sub-jobs (IDM STYLE)
        for (int i = 0; i < fullList.size(); i += batchSize) {
            int end = Math.min(i + batchSize, fullList.size());
            final List<String> chunk = new ArrayList<>(fullList.subList(i, end));
            final int subJobId = idGenerator.incrementAndGet();
            
            activeTasks.incrementAndGet();
            
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        performDeletionTask(chunk, subJobId);
                    } finally {
                        if (activeTasks.decrementAndGet() <= 0) {
                            checkAndStop();
                        }
                    }
                }
            });
        }

        return START_STICKY;
    }

    private void performDeletionTask(List<String> filePaths, int jobId) {
        int totalInBatch = filePaths.size();
        int deletedCount = 0;
        ContentResolver resolver = getContentResolver();

        sendLog("Job #" + jobId + ": Initialising parallel thread for " + totalInBatch + " items.");
        notificationManager.notify(jobId, createNotification("Deleting Batch #" + jobId, "Starting...", 0, totalInBatch, true));

        // Process in sub-groups of 3 to optimize MediaStore transactions
        for (int i = 0; i < totalInBatch; i += 3) {
            int groupEnd = Math.min(i + 3, totalInBatch);
            List<String> subGroup = filePaths.subList(i, groupEnd);
            
            // 1. TURBO FOLDER DETECTION & WIPE
            for (String path : subGroup) {
                File file = new File(path);
                String fileName = file.getName();
                boolean deleted = false;

                if (!file.exists()) {
                    deletedCount++;
                    continue;
                }

                if (file.isDirectory()) {
                    sendLog("[Job " + jobId + "] Folder Detected: " + fileName + ". Executing Turbo Wipe...");
                    StorageUtils.deleteRecursive(DeleteService.this, file);
                    deleted = !file.exists();
                } else {
                    // It's a file, use fast-path deletion
                    deleted = file.delete();
                    if (!deleted) {
                        sendLog("[Job " + jobId + "] [SAF] Requesting deletion for: " + fileName);
                        deleted = StorageUtils.deleteFile(DeleteService.this, file);
                    }
                }

                if (deleted) {
                    deletedCount++;
                    // Immediate database removal for each item
                    try {
                        if (file.isDirectory()) {
                            // If a folder was wiped, remove the folder and everything inside it from the DB
                            resolver.delete(MediaStore.Files.getContentUri("external"), 
                                MediaStore.Files.FileColumns.DATA + " LIKE ?", new String[]{path + "%"});
                        } else {
                            resolver.delete(MediaStore.Files.getContentUri("external"), 
                                MediaStore.Files.FileColumns.DATA + "=?", new String[]{path});
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "DB removal failed for " + fileName);
                    }
                }
            }

            // Update individual notification bar progress
            String progressText = "Processed " + groupEnd + " of " + totalInBatch;
            notificationManager.notify(jobId, createNotification("Deleting Batch #" + jobId, progressText, groupEnd, totalInBatch, true));
        }

        sendLog("Job #" + jobId + " complete. Items removed: " + deletedCount);

        // Broadcast overall completion
        Intent broadcastIntent = new Intent(ACTION_DELETE_COMPLETE);
        broadcastIntent.putExtra(EXTRA_DELETED_COUNT, deletedCount);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);

        // Individual Job Done State
        notificationManager.notify(jobId, createNotification("Batch #" + jobId + " Done", "Finished " + deletedCount + " items", 100, 100, false));
        
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        notificationManager.cancel(jobId);
    }

    private void sendLog(String msg) {
        Intent intent = new Intent(ACTION_DELETE_LOG);
        intent.putExtra(EXTRA_LOG_MESSAGE, msg);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Log.d(TAG, msg);
    }

    private void checkAndStop() {
        if (activeTasks.get() <= 0) {
            isServiceForeground = false;
            stopForeground(true);
            stopSelf();
        }
    }

    private Notification createNotification(String title, String content, int progress, int max, boolean ongoing) {
        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_delete)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setProgress(max, progress, max == 0 && ongoing)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID, "File Deletion", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (executorService != null) executorService.shutdownNow();
        super.onDestroy();
    }
}