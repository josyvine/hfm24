package com.vineyard.hfm.app;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.About;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.Permission;

import java.io.IOException;
import java.util.Collections;
import java.util.UUID;

public class GoogleDriveManager {

    private static final String TAG = "GoogleDriveManager";
    private static final String MASTER_FOLDER_NAME = ".app_sys_cache"; // Hidden parent folder
    private final Drive driveService;

    /**
     * Initializes the Google Drive API v3 service.
     */
    public GoogleDriveManager(Context context, GoogleSignInAccount account) {
        // --- CHANGED: DRIVE_FILE to DRIVE ---
        GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                context, Collections.singleton(DriveScopes.DRIVE));
        credential.setSelectedAccount(account.getAccount());

        driveService = new Drive.Builder(
                AndroidHttp.newCompatibleTransport(),
                new GsonFactory(),
                credential)
                .setApplicationName("HFM Drop")
                .build();
    }

    /**
     * Exposes the underlying Drive service if needed by other components.
     */
    public Drive getDriveService() {
        return driveService;
    }

    /**
     * PHASE 1: GATEKEEPER (SMART QUOTA CHECK)
     * Calculates if the user's Drive has enough space for the file + sharding overhead + decoys.
     * Required Space = (File_Size * 1.1) + 5MB (Decoy padding).
     * 
     * @param fileSizeBytes The exact size of the original file.
     * @return true if there is enough quota, false otherwise.
     */
    public boolean hasEnoughQuota(long fileSizeBytes) {
        try {
            About about = driveService.about().get().setFields("storageQuota").execute();
            About.StorageQuota quota = about.getStorageQuota();

            long totalStorage = quota.getLimit();
            long usedStorage = quota.getUsage();
            long freeStorage = totalStorage - usedStorage;

            // 10% overhead for encryption padding/metadata + 5MB for decoy junk
            long requiredSpace = (long) (fileSizeBytes * 1.1) + (5 * 1024 * 1024);

            Log.d(TAG, "Quota Check - Free: " + freeStorage + " bytes, Required: " + requiredSpace + " bytes");
            return freeStorage >= requiredSpace;
        } catch (IOException e) {
            Log.e(TAG, "Failed to retrieve Drive quota", e);
            return false; // Fail safe: block upload if we can't verify quota
        }
    }

    /**
     * PHASE 2: CLOUD DISPERSION - MASTER FOLDER CREATION
     * Finds or creates the hidden `.app_sys_cache` folder in the root of Google Drive.
     * 
     * @return The Google Drive File ID of the master folder.
     */
    public String getOrCreateMasterFolder() throws IOException {
        String query = "name = '" + MASTER_FOLDER_NAME + "' and mimeType = 'application/vnd.google-apps.folder' and 'root' in parents and trashed = false";
        FileList result = driveService.files().list()
                .setQ(query)
                .setFields("files(id)")
                .execute();

        String folderId;
        if (result.getFiles() != null && !result.getFiles().isEmpty()) {
            folderId = result.getFiles().get(0).getId();
        } else {
            File folderMeta = new File();
            folderMeta.setName(MASTER_FOLDER_NAME);
            folderMeta.setMimeType("application/vnd.google-apps.folder");
            folderMeta.setParents(Collections.singletonList("root"));
            File createdFolder = driveService.files().create(folderMeta).setFields("id").execute();
            folderId = createdFolder.getId();
        }

        // --- NEW: Fix the Google Drive Permission Error ---
        // Ensure the folder is readable by anyone with the link so the receiver can download the shards.
        makeFolderPubliclyReadable(folderId);

        return folderId;
    }

    /**
     * Helper method to set the Google Drive permissions of a specific file/folder 
     * to "Anyone with the link can view".
     */
    private void makeFolderPubliclyReadable(String fileId) {
        try {
            Permission permission = new Permission()
                    .setType("anyone")
                    .setRole("reader");
            driveService.permissions().create(fileId, permission).execute();
            Log.d(TAG, "Successfully set public read permission on Google Drive folder: " + fileId);
        } catch (Exception e) {
            // This might trigger if the permission already exists, which is fine.
            Log.w(TAG, "Could not set permission or permission already exists.", e);
        }
    }

    /**
     * PHASE 2: CLOUD DISPERSION - RANDOM HEX SUBFOLDER CREATION
     * Creates a randomized hex subfolder inside the master folder to scatter the shards.
     * 
     * @param masterFolderId The ID of the `.app_sys_cache` folder.
     * @return The Google Drive File ID of the new subfolder.
     */
    public String createRandomHexSubfolder(String masterFolderId) throws IOException {
        String randomHexName = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        
        File folderMeta = new File();
        folderMeta.setName(randomHexName);
        folderMeta.setMimeType("application/vnd.google-apps.folder");
        folderMeta.setParents(Collections.singletonList(masterFolderId));
        
        File createdFolder = driveService.files().create(folderMeta).setFields("id").execute();
        Log.d(TAG, "Created Shard Subfolder: " + randomHexName);
        return createdFolder.getId();
    }

    /**
     * PHASE 2: CLOUD DISPERSION - SHARD UPLOAD
     * Uploads a byte array (a single encrypted shard or decoy) directly to a specified Drive folder.
     * 
     * @param parentFolderId The ID of the folder where this shard belongs.
     * @param fileName The fake, randomized name for the file (e.g., 'sys_cache.tmp').
     * @param shardData The actual byte data of the encrypted chunk.
     * @return The Google Drive File ID of the uploaded shard.
     */
    public String uploadShard(String parentFolderId, String fileName, byte[] shardData) throws IOException {
        File fileMeta = new File();
        fileMeta.setName(fileName);
        fileMeta.setParents(Collections.singletonList(parentFolderId));
        
        // We do not use FileContent because we are operating purely in RAM/Streams for sharding
        ByteArrayContent mediaContent = new ByteArrayContent("application/octet-stream", shardData);

        File uploadedFile = driveService.files().create(fileMeta, mediaContent).setFields("id").execute();
        return uploadedFile.getId();
    }

    /**
     * PHASE 4: RECEIVER - FETCH SHARD
     * Downloads a specific shard directly from Google Drive into memory.
     * Used by the ReconstructionEngine during the Sliding Window pipeline.
     * 
     * @param fileId The exact Google Drive ID of the encrypted shard.
     * @return A ByteArrayOutputStream containing the downloaded encrypted bytes.
     */
    public java.io.ByteArrayOutputStream downloadShard(String fileId) throws IOException {
        java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
        driveService.files().get(fileId).executeMediaAndDownloadTo(outputStream);
        return outputStream;
    }
}