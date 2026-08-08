package com.vineyard.hfm.app;

import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class AppLogger {

    private static final String TAG = "AppLogger";
    private static final String LOG_DIR_NAME = "hfm log report";
    private static final String LOG_FILE_NAME = "hfm_diagnostic_log.txt";
    private static final Object LOCK = new Object();

    /**
     * Resolves the log file location using multi-path fallback strategy.
     * Prevents silent write failures on OPPO / ColorOS / Android 10+ Scoped Storage.
     */
    private static File getLogFile() {
        File externalStorage = Environment.getExternalStorageDirectory();
        File primaryLogDir = new File(externalStorage, LOG_DIR_NAME);

        if (!primaryLogDir.exists()) {
            boolean created = primaryLogDir.mkdirs();
            if (!created) {
                Log.e(TAG, "Primary log directory creation failed at: " + primaryLogDir.getAbsolutePath());
            }
        }

        if (primaryLogDir.exists() && primaryLogDir.canWrite()) {
            return new File(primaryLogDir, LOG_FILE_NAME);
        }

        // Fallback 1: Public Documents Directory
        File docsDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), LOG_DIR_NAME);
        if (!docsDir.exists()) {
            boolean createdDocs = docsDir.mkdirs();
            if (!createdDocs) {
                Log.e(TAG, "Documents fallback log directory creation failed at: " + docsDir.getAbsolutePath());
            }
        }
        if (docsDir.exists() && docsDir.canWrite()) {
            return new File(docsDir, LOG_FILE_NAME);
        }

        // Fallback 2: Public Downloads Directory
        File downloadsDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), LOG_DIR_NAME);
        if (!downloadsDir.exists()) {
            boolean createdDownloads = downloadsDir.mkdirs();
            if (!createdDownloads) {
                Log.e(TAG, "Downloads fallback log directory creation failed at: " + downloadsDir.getAbsolutePath());
            }
        }
        if (downloadsDir.exists() && downloadsDir.canWrite()) {
            return new File(downloadsDir, LOG_FILE_NAME);
        }

        // Default to primary file object location
        return new File(primaryLogDir, LOG_FILE_NAME);
    }

    public static void log(String tag, String message) {
        log(tag, message, null);
    }

    public static void logError(String tag, String message, Throwable throwable) {
        log(tag, "ERROR | " + message, throwable);
    }

    public static void logMetric(String tag, String operation, long durationMs, String details) {
        String metricMessage = String.format(Locale.US, "[METRIC] %s executed in %d ms | %s", operation, durationMs, details != null ? details : "");
        log(tag, metricMessage, null);
    }

    public static void logQueryResult(String tag, Uri uri, String filterType, int resultCount, long durationMs, boolean usedFallback) {
        String queryMsg = String.format(Locale.US, "[QUERY_METRIC] URI: %s | Filter: %s | Found: %d items | Time: %d ms | Fallback: %b",
                uri != null ? uri.toString() : "null",
                filterType != null ? filterType : "none",
                resultCount,
                durationMs,
                usedFallback);
        log(tag, queryMsg, null);
    }

    public static void logStorageScan(String tag, String rootPath, String category, int filesFound, long durationMs) {
        String scanMsg = String.format(Locale.US, "[STORAGE_SCAN] Path: %s | Category: %s | Scanned: %d files | Time: %d ms",
                rootPath != null ? rootPath : "unknown",
                category != null ? category : "all",
                filesFound,
                durationMs);
        log(tag, scanMsg, null);
    }

    public static void logSearchStart(String tag, String query, String filterType) {
        String searchStartMsg = String.format(Locale.US, "[SEARCH_START] Query: [%s] | Filter: %s | Device: %s %s (SDK %d)",
                query != null ? query : "",
                filterType != null ? filterType : "all",
                Build.MANUFACTURER,
                Build.MODEL,
                Build.VERSION.SDK_INT);
        log(tag, searchStartMsg, null);
    }

    public static void logSearchDelayWarning(String tag, String query, long durationMs) {
        String warningMsg = String.format(Locale.US, "[IN_FLIGHT_WARNING] Search query [%s] execution time exceeded threshold: %d ms",
                query != null ? query : "",
                durationMs);
        log(tag, warningMsg, null);
    }

    public static void logDatabaseLockError(String tag, Uri uri, Throwable throwable) {
        String dbErrorMsg = String.format(Locale.US, "[SQLITE_LOCK_ERROR] Database/Binder buffer lock encountered on URI: %s",
                uri != null ? uri.toString() : "null");
        logError(tag, dbErrorMsg, throwable);
    }

    public static void logThreadCancellation(String tag, String operation) {
        String cancelMsg = String.format(Locale.US, "[THREAD_CANCELLED] Task cancelled and native cursor handle released for operation: %s",
                operation != null ? operation : "unknown");
        log(tag, cancelMsg, null);
    }

    public static void logSystemInfo(String tag) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== SYSTEM DIAGNOSTIC INFO ===\n");
        sb.append("Manufacturer: ").append(Build.MANUFACTURER).append("\n");
        sb.append("Model: ").append(Build.MODEL).append("\n");
        sb.append("Device: ").append(Build.DEVICE).append("\n");
        sb.append("Brand: ").append(Build.BRAND).append("\n");
        sb.append("Product: ").append(Build.PRODUCT).append("\n");
        sb.append("Hardware: ").append(Build.HARDWARE).append("\n");
        sb.append("Android SDK: ").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("Build Release: ").append(Build.VERSION.RELEASE).append("\n");
        sb.append("Display Build: ").append(Build.DISPLAY).append("\n");
        sb.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            sb.append("Supported ABIs: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append("\n");
        }
        sb.append("==============================");
        log(tag, sb.toString());
    }

    public static String getLogFilePath() {
        synchronized (LOCK) {
            File logFile = getLogFile();
            return (logFile != null) ? logFile.getAbsolutePath() : "Unknown";
        }
    }

    public static void log(String tag, String message, Throwable throwable) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        StringBuilder logBuilder = new StringBuilder();
        logBuilder.append(timestamp)
                .append(" [")
                .append(Thread.currentThread().getName())
                .append("] ")
                .append(tag)
                .append(": ")
                .append(message);

        if (throwable != null) {
            logBuilder.append("\n");
            StringWriter stringWriter = new StringWriter();
            PrintWriter printWriter = new PrintWriter(stringWriter);
            throwable.printStackTrace(printWriter);
            logBuilder.append(stringWriter.toString());
        }
        logBuilder.append("\n");

        String formattedLog = logBuilder.toString();
        Log.d(tag, message, throwable);

        synchronized (LOCK) {
            FileWriter writer = null;
            try {
                File logFile = getLogFile();
                File parentDir = logFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }

                writer = new FileWriter(logFile, true);
                writer.write(formattedLog);
                writer.flush();
            } catch (IOException e) {
                Log.e(TAG, "Error writing entry to diagnostic log file: " + e.getMessage(), e);
            } finally {
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    public static String readLog() {
        synchronized (LOCK) {
            StringBuilder content = new StringBuilder();
            try {
                File logFile = getLogFile();
                if (logFile != null && logFile.exists()) {
                    BufferedReader reader = new BufferedReader(new FileReader(logFile));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line).append("\n");
                    }
                    reader.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error reading diagnostic log file: " + e.getMessage(), e);
            }
            return content.toString();
        }
    }

    public static boolean clearLog() {
        synchronized (LOCK) {
            try {
                File logFile = getLogFile();
                if (logFile != null && logFile.exists()) {
                    return logFile.delete();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error clearing diagnostic log file: " + e.getMessage(), e);
            }
            return false;
        }
    }
}