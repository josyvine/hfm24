package com.vineyard.hfm.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public class StorageUtils {

    private static final String TAG = "StorageUtils";
    private static final String PREFS_NAME = "SDCardPrefs";
    private static final String KEY_SDCARD_URI = "sdcard_uri";
    public static final int REQUEST_CODE_SDCARD_PERMISSION = 101;
    
    public static final String SD_RECYCLE_BIN_NAME = "HFMRecycleBin";

    // Static cache variables to prevent ANR and IPC Binder freezes on large file selections
    private static String cachedSdCardPath = null;
    private static Boolean cachedHasPermission = null;

    /**
     * Checks full storage access permissions across all Android versions (Android 5.0 through Android 15+).
     */
    public static boolean hasStoragePermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            int read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE);
            int write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED;
        }
    }

    public static void saveSdCardUri(Context context, Uri uri) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (uri != null) {
            prefs.edit().putString(KEY_SDCARD_URI, uri.toString()).apply();
            cachedHasPermission = true;
            AppLogger.log(TAG, "Saved SD Card Uri: " + uri.toString());
        } else {
            prefs.edit().remove(KEY_SDCARD_URI).apply();
            cachedHasPermission = false;
            AppLogger.log(TAG, "Cleared saved SD Card Uri from preferences.");
        }
    }

    public static Uri getSdCardUri(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String uriString = prefs.getString(KEY_SDCARD_URI, null);
        if (uriString != null) {
            return Uri.parse(uriString);
        }
        return null;
    }

    public static boolean hasSdCardPermission(Context context) {
        return hasSdCardPermission(context, false);
    }

    public static boolean hasSdCardPermission(Context context, boolean forceRefresh) {
        if (!forceRefresh && cachedHasPermission != null) {
            return cachedHasPermission;
        }

        Uri sdCardUri = getSdCardUri(context);
        if (sdCardUri == null) {
            cachedHasPermission = false;
            return false;
        }

        try {
            // Verify against system's persisted URI permissions to prevent stale SharedPreferences hangs
            List<UriPermission> persistedPermissions = context.getContentResolver().getPersistedUriPermissions();
            boolean hasPersistedGrant = false;
            for (UriPermission perm : persistedPermissions) {
                if (perm.getUri().equals(sdCardUri) && perm.isWritePermission()) {
                    hasPersistedGrant = true;
                    break;
                }
            }

            if (!hasPersistedGrant) {
                int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                context.getContentResolver().takePersistableUriPermission(sdCardUri, takeFlags);
                hasPersistedGrant = true;
            }
            cachedHasPermission = hasPersistedGrant;
            return hasPersistedGrant;
        } catch (SecurityException e) {
            AppLogger.logError(TAG, "SD Card URI permission was revoked or invalid.", e);
            saveSdCardUri(context, null);
            cachedHasPermission = false;
            return false;
        }
    }

    public static void requestSdCardPermission(Activity activity) {
        StorageManager sm = (StorageManager) activity.getSystemService(Context.STORAGE_SERVICE);
        if (sm == null) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            activity.startActivityForResult(intent, REQUEST_CODE_SDCARD_PERMISSION);
            return;
        }
        StorageVolume sdCardVolume = null;
        for (StorageVolume volume : sm.getStorageVolumes()) {
            if (volume.isRemovable() && volume.getState().equals("mounted")) {
                sdCardVolume = volume;
                break;
            }
        }
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && sdCardVolume != null) {
            intent = sdCardVolume.createAccessIntent(null);
        } else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        }
        if (intent == null) intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try {
            activity.startActivityForResult(intent, REQUEST_CODE_SDCARD_PERMISSION);
        } catch (Exception e) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            activity.startActivityForResult(intent, REQUEST_CODE_SDCARD_PERMISSION);
        }
    }

    public static boolean isFileOnSdCard(Context context, File file) {
        if (file == null) return false;
        String sdCardPath = getSdCardPath(context);
        if (sdCardPath != null) {
            // FAST String path comparison: Avoids blocking synchronous getCanonicalPath() disk I/O
            String absPath = file.getAbsolutePath();
            if (absPath.startsWith(sdCardPath)) {
                return true;
            }
            // Fallback check ONLY if symlinks exist in path
            try {
                return file.getCanonicalPath().startsWith(sdCardPath);
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    public static boolean deleteFile(Context context, File file) {
        if (file == null || !file.exists()) return true;

        // Try standard Java delete first
        if (file.delete()) {
            return true;
        }

        // If it's on SD Card and Java delete failed, use SAF
        if (isFileOnSdCard(context, file)) {
            DocumentFile docFile = getDocumentFile(context, file, file.isDirectory());
            if (docFile != null && docFile.exists()) {
                if (docFile.delete()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Optimized Recursive Delete.
     * Tells the System OS to wipe the folder entry, which is significantly faster 
     * than iterating through every single file in the app logic.
     */
    public static void deleteRecursive(Context context, File fileOrDirectory) {
        if (fileOrDirectory == null || !fileOrDirectory.exists()) return;

        // On modern Android, calling delete() on a directory object via SAF 
        // triggers a recursive wipe at the system level.
        boolean success = deleteFile(context, fileOrDirectory);

        // Fallback: If system wipe didn't work, perform manual leaf-node recursion
        if (!success && fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(context, child);
                }
            }
            deleteFile(context, fileOrDirectory);
        }
    }

    public static String getSdCardPath(Context context) {
        if (cachedSdCardPath != null) {
            return cachedSdCardPath;
        }

        File[] storageVolumes = context.getExternalFilesDirs(null);
        if (storageVolumes != null && storageVolumes.length > 1 && storageVolumes[1] != null) {
            String fullPath = storageVolumes[1].getAbsolutePath();
            if (fullPath.contains("/Android/data")) {
                try {
                    String rootPath = fullPath.substring(0, fullPath.indexOf("/Android/data"));
                    cachedSdCardPath = new File(rootPath).getCanonicalPath();
                    return cachedSdCardPath;
                } catch (IOException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * FAST SAF DOCUMENT FILE RESOLVER
     * Avoids recursive findFile() loops which cause severe freezing during recycling operations.
     */
    public static DocumentFile getDocumentFile(Context context, File file, boolean isDirectory) {
        String sdCardPath = getSdCardPath(context);
        if (sdCardPath == null || file == null) return null;

        Uri sdCardUri = getSdCardUri(context);
        if (sdCardUri == null) return null;

        String canonicalFilePath;
        try {
            canonicalFilePath = file.getCanonicalPath();
            if (!canonicalFilePath.startsWith(sdCardPath)) return null;
        } catch (IOException e) {
            return null;
        }

        String relativePath = canonicalFilePath.substring(sdCardPath.length());
        if (relativePath.startsWith(File.separator)) {
            relativePath = relativePath.substring(1);
        }

        // Fast Direct Document URI Construction (Android 5.0 / API 21+)
        try {
            String treeDocumentId = DocumentsContract.getTreeDocumentId(sdCardUri);
            String targetDocumentId = treeDocumentId + (treeDocumentId.endsWith(":") ? "" : "/") + relativePath;

            Uri directDocumentUri = DocumentsContract.buildDocumentUriUsingTree(sdCardUri, targetDocumentId);
            DocumentFile directDocFile = isDirectory ? 
                    DocumentFile.fromTreeUri(context, directDocumentUri) : 
                    DocumentFile.fromSingleUri(context, directDocumentUri);

            if (directDocFile != null && directDocFile.exists()) {
                return directDocFile;
            }
        } catch (Exception e) {
            AppLogger.log(TAG, "Direct SAF URI construction failed, falling back to segment traversal: " + e.getMessage());
        }

        // Fallback: Segment traversal (used if direct URI construction is unsupported on specific OEM ROMs)
        DocumentFile rootDocFile = DocumentFile.fromTreeUri(context, sdCardUri);
        if (rootDocFile == null) return null;

        String[] pathSegments = relativePath.split(File.separator);
        DocumentFile result = rootDocFile;

        for (int i = 0; i < pathSegments.length; i++) {
            String segment = pathSegments[i];
            if (segment.isEmpty()) continue;

            DocumentFile next = result.findFile(segment);
            if (next == null) {
                if (i < pathSegments.length - 1 || !isDirectory) {
                    return null;
                }
            }
            result = next;
        }
        return result;
    }

    public static OutputStream getOutputStream(Context context, File targetFile) throws IOException {
        if (!isFileOnSdCard(context, targetFile)) {
            File parent = targetFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            return new FileOutputStream(targetFile);
        }

        DocumentFile parentDoc = getDocumentFile(context, targetFile.getParentFile(), true);
        if (parentDoc == null || !parentDoc.canWrite()) {
            throw new IOException("Cannot get writable parent directory on SD card.");
        }

        String mimeType = "application/octet-stream";
        DocumentFile newDocFile = parentDoc.createFile(mimeType, targetFile.getName());
        if (newDocFile == null) {
            newDocFile = parentDoc.findFile(targetFile.getName());
            if (newDocFile == null) throw new IOException("Failed to create file on SD card.");
        }
        return context.getContentResolver().openOutputStream(newDocFile.getUri());
    }

    public static boolean createDirectory(Context context, File dir) {
        if (!isFileOnSdCard(context, dir)) return dir.mkdirs();
        DocumentFile parentDoc = getDocumentFile(context, dir.getParentFile(), true);
        if (parentDoc == null || !parentDoc.canWrite()) return false;
        DocumentFile newDir = parentDoc.createDirectory(dir.getName());
        return newDir != null && newDir.exists();
    }

    public static boolean copyFile(Context context, File source, File destination) {
        InputStream in = null;
        OutputStream out = null;
        try {
            Uri sourceUri = Uri.fromFile(source);
            in = context.getContentResolver().openInputStream(sourceUri);
            if (in == null) return false;
            out = getOutputStream(context, destination);
            if (out == null) return false;

            byte[] buf = new byte[131072]; 
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            return true;
        } catch (IOException e) {
            AppLogger.logError(TAG, "File copy failed", e);
            return false;
        } finally {
            try {
                if (in != null) in.close();
                if (out != null) out.close();
            } catch (IOException e) {}
        }
    }

    /**
     * OPTIMIZED SD CARD RECYCLE BIN RESOLVER
     * Instantly resolves/creates the SD Card Recycle Bin without scanning the entire root directory.
     */
    public static DocumentFile getOrCreateSdCardRecycleBin(Context context) {
        Uri sdCardUri = getSdCardUri(context);
        if (sdCardUri == null) return null;

        String sdCardPath = getSdCardPath(context);
        if (sdCardPath == null) return null;

        File recycleBinDir = new File(sdCardPath, SD_RECYCLE_BIN_NAME);
        if (!recycleBinDir.exists()) {
            recycleBinDir.mkdirs();
        }

        // Try direct URI resolution first to eliminate findFile() scanning lag
        DocumentFile directBinDoc = getDocumentFile(context, recycleBinDir, true);
        if (directBinDoc != null && directBinDoc.exists()) {
            return directBinDoc;
        }

        // Fallback to tree root creation
        DocumentFile rootDocFile = DocumentFile.fromTreeUri(context, sdCardUri);
        if (rootDocFile == null) return null;
        DocumentFile recycleBin = rootDocFile.findFile(SD_RECYCLE_BIN_NAME);
        if (recycleBin == null) {
            recycleBin = rootDocFile.createDirectory(SD_RECYCLE_BIN_NAME);
        }
        return recycleBin;
    }

    public static boolean moveFileOnSdCardSafely(Context context, File sourceFile, DocumentFile recycleBinDoc) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                DocumentFile sourceDoc = getDocumentFile(context, sourceFile, sourceFile.isDirectory());
                if (sourceDoc != null && recycleBinDoc != null) {
                    try {
                        Uri movedUri = DocumentsContract.moveDocument(context.getContentResolver(), 
                                sourceDoc.getUri(), sourceDoc.getParentFile() != null ? sourceDoc.getParentFile().getUri() : recycleBinDoc.getUri(), recycleBinDoc.getUri());
                        return movedUri != null;
                    } catch (Exception e) {
                        AppLogger.log(TAG, "Native moveDocument failed, attempting rename fallback: " + e.getMessage());
                    }
                    if (sourceDoc.renameTo(sourceFile.getName())) {
                        return true; 
                    }
                }
            } catch (Exception e) {
                AppLogger.logError(TAG, "SAF Move failed", e);
            }
        }
        return false;
    }

    public static boolean moveFileOnSdCardSafely(Context context, File sourceFile) {
        return moveFileOnSdCardSafely(context, sourceFile, getOrCreateSdCardRecycleBin(context));
    }
}