package com.vineyard.hfm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.text.format.Formatter;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

// --- UPDATE 1: Add necessary imports for Manifest and ContextCompat ---
import android.Manifest;

public class FileTransferService extends Service {

    private static final String TAG = "FileTransferService";
    private static final int SOCKET_TIMEOUT = 5000;
    public static final int PORT = 8988;

    // --- Actions ---
    public static final String ACTION_SEND_FILES = "com.vineyard.hfm.app.SEND_FILES";
    public static final String ACTION_RECEIVE_FILES = "com.vineyard.hfm.app.RECEIVE_FILES";
    public static final String ACTION_PAUSE_TRANSFER = "com.vineyard.hfm.app.PAUSE_TRANSFER";
    public static final String ACTION_RESUME_TRANSFER = "com.vineyard.hfm.app.RESUME_TRANSFER";
    public static final String ACTION_CANCEL_TRANSFER = "com.vineyard.hfm.app.CANCEL_TRANSFER";
    public static final String ACTION_UPDATE_PROGRESS = "com.vineyard.hfm.app.UPDATE_PROGRESS";
    public static final String ACTION_TRANSFER_COMPLETE = "com.vineyard.hfm.app.TRANSFER_COMPLETE";
    public static final String ACTION_TRANSFER_ERROR = "com.vineyard.hfm.app.TRANSFER_ERROR";

    // --- Extras ---
    public static final String EXTRA_FILE_PATHS = "file_paths";
    public static final String EXTRA_GROUP_OWNER_ADDRESS = "go_host";
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String EXTRA_BYTES_TRANSFERRED = "bytes_transferred";
    public static final String EXTRA_TOTAL_BYTES = "total_bytes";
    public static final String EXTRA_TRANSFER_SPEED = "transfer_speed";
    public static final String EXTRA_FILE_INDEX = "file_index";
    public static final String EXTRA_ERROR_MESSAGE = "error_message";


    private static final int NOTIFICATION_ID = 101;
    private static final String CHANNEL_ID = "FileTransferChannel";
    public static final String PUBLIC_SAVE_FOLDER_NAME = "Hfm Shared";

    private volatile boolean isPaused = false;
    private volatile boolean isCancelled = false;
    private Thread transferThread;
    private long lastPosition = 0;
    
    // --- UPDATE 2: Add a flag to track if we can show notifications ---
    private boolean canShowNotification = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        if (action != null) {
            // --- UPDATE 3: Check for notification permission early ---
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    canShowNotification = true;
                }
            } else {
                canShowNotification = true;
            }

            switch (action) {
                case ACTION_SEND_FILES:
                    if (canShowNotification) {
                        startForeground(NOTIFICATION_ID, createNotification("Preparing to send...", 0));
                    }
                    final ArrayList<String> filesToSend = intent.getStringArrayListExtra(EXTRA_FILE_PATHS);
                    final String host = intent.getStringExtra(EXTRA_GROUP_OWNER_ADDRESS);
                    startTransferThread(new ClientRunnable(host, filesToSend));
                    break;
                case ACTION_RECEIVE_FILES:
                    if (canShowNotification) {
                        startForeground(NOTIFICATION_ID, createNotification("Waiting to receive...", 0));
                    }
                    startTransferThread(new ServerRunnable());
                    break;
                case ACTION_PAUSE_TRANSFER:
                    isPaused = true;
                    break;
                case ACTION_RESUME_TRANSFER:
                    isPaused = false;
                    if (transferThread != null && transferThread.getState() == Thread.State.WAITING) {
                        synchronized (transferThread) {
                            transferThread.notify();
                        }
                    }
                    break;
                case ACTION_CANCEL_TRANSFER:
                    isCancelled = true;
                    if (transferThread != null) {
                        transferThread.interrupt();
                    }
                    stopSelf();
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    private void startTransferThread(Runnable runnable) {
        if (transferThread != null && transferThread.isAlive()) {
            Log.w(TAG, "Transfer already in progress.");
            return;
        }
        isCancelled = false;
        isPaused = false;
        lastPosition = 0;
        transferThread = new Thread(runnable);
        transferThread.start();
    }

    private String getStackTraceAsString(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }


    private class ServerRunnable implements Runnable {
        @Override
        public void run() {
            ServerSocketChannel serverSocketChannel = null;
            SocketChannel clientChannel = null;
            Map<File, String> receivedFiles = new HashMap<>();

            try {
                serverSocketChannel = ServerSocketChannel.open();
                serverSocketChannel.socket().bind(new InetSocketAddress(PORT));
                clientChannel = serverSocketChannel.accept();
                clientChannel.configureBlocking(true);
                int fileIndex = 0;

                while (!isCancelled && clientChannel.isConnected()) {
                    ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
                    boolean isStreamFinished = false;
                    while (lengthBuffer.hasRemaining()) {
                        int read = clientChannel.read(lengthBuffer);
                        if (read == -1) {
                            isStreamFinished = true;
                            break;
                        } else if (read == 0) {
                            Thread.sleep(10);
                            continue;
                        }
                    }
                    if (isStreamFinished) {
                        break;
                    }
                    lengthBuffer.flip();
                    int metadataLength = lengthBuffer.getInt();

                    if (metadataLength == 0) {
                        Log.i(TAG, "End of transmission marker received. Closing connection.");
                        break;
                    }

                    if (metadataLength < 0 || metadataLength > 10000) { // Sanity check
                        throw new IOException("Invalid metadata length received: " + metadataLength);
                    }

                    ByteBuffer metadataBuffer = ByteBuffer.allocate(metadataLength);
                    while (metadataBuffer.hasRemaining()) {
                        int read = clientChannel.read(metadataBuffer);
                        if (read == -1) {
                            throw new IOException("Connection closed while reading metadata.");
                        }
                        if (read == 0) {
                            Thread.sleep(10);
                            continue;
                        }
                    }
                    metadataBuffer.flip();
                    String metadataJson = StandardCharsets.UTF_8.decode(metadataBuffer).toString();

                    JSONObject metadata = new JSONObject(metadataJson);
                    String fileName = metadata.getString("fileName");

                    File tempFile = File.createTempFile("hfm_received_", ".lz4", getCacheDir());

                    receiveFile(clientChannel, tempFile, fileName, fileIndex);
                    if (isCancelled) {
                        break;
                    }

                    receivedFiles.put(tempFile, fileName);
                    Log.i(TAG, "File " + fileName + " received successfully. Waiting for next file...");
                    fileIndex++;
                }

                if (!isCancelled) {
                    for (Map.Entry<File, String> entry : receivedFiles.entrySet()) {
                        File tempFile = entry.getKey();
                        String finalFileName = entry.getValue();
                        File publicDir = new File(Environment.getExternalStorageDirectory(), PUBLIC_SAVE_FOLDER_NAME);
                        if (!publicDir.exists()) {
                            publicDir.mkdirs();
                        }
                        File finalFile = new File(publicDir, finalFileName);
                        if (finalFile.exists()) {
                            finalFile.delete();
                        }

                        updateNotification("Decompressing " + finalFileName, -1);
                        decompressFile(tempFile, finalFile);
                    }
                }

                if (!isCancelled && !receivedFiles.isEmpty()) {
                    LocalBroadcastManager.getInstance(FileTransferService.this).sendBroadcast(new Intent(ACTION_TRANSFER_COMPLETE));
                }

            } catch (Exception e) {
                Log.e(TAG, "Server error", e);
                Intent errorIntent = new Intent(ACTION_TRANSFER_ERROR);
                errorIntent.putExtra(EXTRA_ERROR_MESSAGE, getStackTraceAsString(e));
                LocalBroadcastManager.getInstance(FileTransferService.this).sendBroadcast(errorIntent);
            } finally {
                for (File tempFile : receivedFiles.keySet()) {
                    if (tempFile.exists()) {
                        tempFile.delete();
                    }
                }
                try {
                    if (clientChannel != null) clientChannel.close();
                    if (serverSocketChannel != null) serverSocketChannel.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing server sockets", e);
                }
                stopSelf();
            }
        }
    }

    private class ClientRunnable implements Runnable {
        private String host;
        private ArrayList<String> filePaths;

        public ClientRunnable(String host, ArrayList<String> filePaths) {
            this.host = host;
            this.filePaths = filePaths;
        }

        @Override
        public void run() {
            SocketChannel socketChannel = null;
            try {
                socketChannel = SocketChannel.open();
                socketChannel.connect(new InetSocketAddress(host, PORT));
                socketChannel.configureBlocking(true);

                for (int i = 0; i < filePaths.size(); i++) {
                    if (isCancelled) break;
                    String path = filePaths.get(i);
                    File originalFile = new File(path);
                    File tempFile = null;

                    try {
                        tempFile = File.createTempFile("hfm_sending_", ".lz4", getCacheDir());

                        if (!originalFile.exists()) {
                            Log.w(TAG, "File to send does not exist: " + path);
                            continue;
                        }

                        updateNotification("Compressing " + originalFile.getName(), -1);
                        compressFile(originalFile, tempFile);

                        JSONObject metadata = new JSONObject();
                        metadata.put("fileName", originalFile.getName());
                        byte[] metadataBytes = metadata.toString().getBytes(StandardCharsets.UTF_8);

                        ByteBuffer lengthBuffer = ByteBuffer.allocate(4).putInt(metadataBytes.length);
                        lengthBuffer.flip();
                        while (lengthBuffer.hasRemaining()) {
                            socketChannel.write(lengthBuffer);
                        }

                        ByteBuffer metadataBuffer = ByteBuffer.wrap(metadataBytes);
                        while (metadataBuffer.hasRemaining()) {
                            socketChannel.write(metadataBuffer);
                        }

                        sendFile(socketChannel, tempFile, originalFile.getName(), i);
                    } finally {
                        if (tempFile != null && tempFile.exists()) {
                            tempFile.delete();
                        }
                    }
                }

                if (!isCancelled) {
                    ByteBuffer goodbyeBuffer = ByteBuffer.allocate(4).putInt(0);
                    goodbyeBuffer.flip();
                    while (goodbyeBuffer.hasRemaining()) {
                        socketChannel.write(goodbyeBuffer);
                    }
                    Log.i(TAG, "All files sent. Goodbye signal sent.");
                    LocalBroadcastManager.getInstance(FileTransferService.this).sendBroadcast(new Intent(ACTION_TRANSFER_COMPLETE));
                }

            } catch (Exception e) {
                Log.e(TAG, "Client error", e);
                Intent errorIntent = new Intent(ACTION_TRANSFER_ERROR);
                errorIntent.putExtra(EXTRA_ERROR_MESSAGE, getStackTraceAsString(e));
                LocalBroadcastManager.getInstance(FileTransferService.this).sendBroadcast(errorIntent);
            } finally {
                if (socketChannel != null) {
                    try {
                        socketChannel.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                stopSelf();
            }
        }
    }

    private void sendFile(SocketChannel socketChannel, File file, String displayName, int fileIndex) throws Exception {
        FileInputStream fis = new FileInputStream(file);
        long fileSize = file.length();

        ByteBuffer lengthBuffer = ByteBuffer.allocate(8).putLong(fileSize);
        lengthBuffer.flip();
        while (lengthBuffer.hasRemaining()) {
            socketChannel.write(lengthBuffer);
        }

        long position = 0;
        long startTime = System.currentTimeMillis();
        long bytesSentInSecond = 0;

        byte[] buffer = new byte[8192];
        int len;
        while ((len = fis.read(buffer)) > 0 && !isCancelled) {
            while (isPaused) {
                synchronized (transferThread) {
                    transferThread.wait(100);
                }
            }
            ByteBuffer writeBuffer = ByteBuffer.wrap(buffer, 0, len);
            while (writeBuffer.hasRemaining()) {
                socketChannel.write(writeBuffer);
            }

            position += len;
            bytesSentInSecond += len;

            long currentTime = System.currentTimeMillis();
            if (currentTime - startTime >= 1000) {
                double speed = (double) bytesSentInSecond / (1024 * 1024);
                broadcastProgress(displayName, position, fileSize, speed, fileIndex);
                updateNotification("Sending: " + displayName, (int) ((position * 100) / fileSize));
                startTime = currentTime;
                bytesSentInSecond = 0;
            }
        }
        broadcastProgress(displayName, position, fileSize, 0, fileIndex);
        fis.close();
    }

    private void receiveFile(SocketChannel socketChannel, File file, String displayName, int fileIndex) throws Exception {
        ByteBuffer lengthBuffer = ByteBuffer.allocate(8);
        while (lengthBuffer.hasRemaining()) {
            int read = socketChannel.read(lengthBuffer);
            if (read == -1) {
                throw new IOException("Connection closed prematurely while reading file length.");
            } else if (read == 0) {
                Thread.sleep(10);
                continue;
            }
        }
        lengthBuffer.flip();
        long fileSize = lengthBuffer.getLong();

        if (fileSize < 0) { // Sanity check
			throw new IOException("Invalid file size received: " + fileSize);
        }

        FileOutputStream fos = new FileOutputStream(file);
        long position = 0;
        long startTime = System.currentTimeMillis();
        long bytesReceivedInSecond = 0;

        byte[] buffer = new byte[8192];
        while (position < fileSize && !isCancelled) {
            while (isPaused) {
                synchronized (transferThread) {
                    transferThread.wait(100);
                }
            }

            int bytesToRead = (int) Math.min(buffer.length, fileSize - position);
            int read = socketChannel.read(ByteBuffer.wrap(buffer, 0, bytesToRead));

            if (read == -1) {
                break; // Connection closed
            }

            fos.write(buffer, 0, read);

            position += read;
            bytesReceivedInSecond += read;

            long currentTime = System.currentTimeMillis();
            if (currentTime - startTime >= 1000) {
                double speed = (double) bytesReceivedInSecond / (1024 * 1024);
                broadcastProgress(displayName, position, fileSize, speed, fileIndex);
                updateNotification("Receiving: " + displayName, (int) ((position * 100) / fileSize));
                startTime = currentTime;
                bytesReceivedInSecond = 0;
            }
        }
        broadcastProgress(displayName, position, fileSize, 0, fileIndex);
        fos.close();
        if (position < fileSize) {
            throw new IOException("File transfer was incomplete. Expected " + fileSize + " bytes but got " + position);
        }
    }

    private void compressFile(File source, File dest) throws Exception {
        FileInputStream fis = new FileInputStream(source);
        FileOutputStream fos = new FileOutputStream(dest);
        CompressionUtils.compress(fis, fos);
        fis.close();
        fos.close();
    }

    private void decompressFile(File source, File dest) throws Exception {
        FileInputStream fis = new FileInputStream(source);
        FileOutputStream fos = new FileOutputStream(dest);
        CompressionUtils.decompress(fis, fos);
        fis.close();
        fos.close();
    }

    private void broadcastProgress(String fileName, long transferred, long total, double speed, int fileIndex) {
        Intent intent = new Intent(ACTION_UPDATE_PROGRESS);
        intent.putExtra(EXTRA_FILE_NAME, fileName);
        intent.putExtra(EXTRA_BYTES_TRANSFERRED, transferred);
        intent.putExtra(EXTRA_TOTAL_BYTES, total);
        intent.putExtra(EXTRA_TRANSFER_SPEED, speed);
        intent.putExtra(EXTRA_FILE_INDEX, fileIndex);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void updateNotification(String content, int progress) {
        // --- UPDATE 4: Check permission before updating notification ---
        if (!canShowNotification) {
            return;
        }
        Notification notification = createNotification(content, progress);
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(NOTIFICATION_ID, notification);
    }

    private Notification createNotification(String content, int progress) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "File Transfer", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle("HFM File Share")
			.setContentText(content)
			.setSmallIcon(android.R.drawable.stat_sys_upload)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setOngoing(true);
        if (progress >= 0) {
            builder.setProgress(100, progress, false);
        } else {
            builder.setProgress(0, 0, true);
        }
        return builder.build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isCancelled = true;
        if (transferThread != null) {
            transferThread.interrupt();
        }
    }
}