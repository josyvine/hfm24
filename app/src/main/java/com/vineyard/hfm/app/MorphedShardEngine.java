package com.vineyard.hfm.app;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class MorphedShardEngine {

    private static final String TAG = "MorphedShardEngine";
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA1";
    private static final String SALT = "hfm_morphed_manifest_salt_v1";
    
    private final Context context;
    private final GoogleDriveManager driveManager;

    // Callbacks for UI updates during the heavy processing
    public interface ProgressListener {
        void onProgress(int progress, int max, long bytesProcessed);
        void onStatusUpdate(String minorStatus);
    }

    public MorphedShardEngine(Context context, GoogleDriveManager driveManager) {
        this.context = context;
        this.driveManager = driveManager;
    }

    /**
     * Executes Phase 2: Polymorphic Sharding, Encryption, and Dispersion.
     * 
     * FIX: Implemented strict RAM buffer management to prevent OutOfMemoryError on files > 100MB.
     * 
     * @param inputFile The local file to send.
     * @param secretNumber The user-provided PIN used to encrypt the Manifest.
     * @param listener Callback for real-time UI updates.
     * @return The Google Drive File ID of the Encrypted Manifest, or null if failed.
     */
    public String executeShardingAndUpload(File inputFile, String secretNumber, ProgressListener listener) {
        FileInputStream fis = null;
        try {
            long fileSize = inputFile.length();
            
            // 1. Setup Cloud Folders
            listener.onStatusUpdate("Preparing cloud dispersion matrix...");
            String masterFolderId = driveManager.getOrCreateMasterFolder();
            
            // Generate random subfolders (3 to 10 folders depending on file size)
            int numSubfolders = (fileSize > 500 * 1024 * 1024) ? 10 : 3; 
            List<String> subfolderIds = new ArrayList<>();
            for (int i = 0; i < numSubfolders; i++) {
                subfolderIds.add(driveManager.createRandomHexSubfolder(masterFolderId));
            }

            // 2. Generate Random AES-256-GCM Data Key & IV for the File
            listener.onStatusUpdate("Generating cryptographic keys...");
            byte[] dataKeyBytes = new byte[32]; // 256-bit
            SecureRandom random = new SecureRandom();
            random.nextBytes(dataKeyBytes);
            SecretKey dataKey = new SecretKeySpec(dataKeyBytes, "AES");

            byte[] dataIv = new byte[12]; // 96-bit IV recommended for GCM
            random.nextBytes(dataIv);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(128, dataIv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, dataKey, gcmSpec);

            // 3. Initialize Manifest Tracker
            JSONArray manifestShardsArray = new JSONArray();
            
            // Determine base shard size based on total file size to balance API calls vs RAM
            // Files < 100MB = ~500KB shards. Files > 1GB = ~4MB shards.
            int baseShardSize = (fileSize > 1024 * 1024 * 1024) ? 4 * 1024 * 1024 : 500 * 1024;

            fis = new FileInputStream(inputFile);
            ByteArrayOutputStream shardBuffer = new ByteArrayOutputStream();
            byte[] readBuffer = new byte[16384]; // 16KB read buffer
            int bytesRead;
            long totalBytesProcessed = 0;
            int shardOrder = 0;

            // Define target size for the first shard (add ±20% randomness)
            int currentTargetShardSize = getRandomizedShardSize(baseShardSize, random);

            listener.onStatusUpdate("Streaming encryption and dispatching shards...");

            // 4. The Streaming & Sharding Loop (RAM SAFE)
            while ((bytesRead = fis.read(readBuffer)) != -1) {
                // Encrypt chunk on the fly
                byte[] encryptedChunk = cipher.update(readBuffer, 0, bytesRead);
                if (encryptedChunk != null) {
                    shardBuffer.write(encryptedChunk);
                }

                totalBytesProcessed += bytesRead;

                // Update UI every 50 iterations or so to prevent UI thread flooding
                if (totalBytesProcessed % (16384 * 50) == 0) {
                    listener.onProgress((int) ((totalBytesProcessed * 100) / fileSize), 100, totalBytesProcessed);
                }

                // FIX: Strict check to ensure RAM is cleared before expansion
                if (shardBuffer.size() >= currentTargetShardSize) {
                    uploadShardAndRecord(shardBuffer.toByteArray(), shardOrder, subfolderIds, random, manifestShardsArray);
                    shardOrder++;
                    
                    // CRITICAL FIX: Reset buffer and trigger GC hint to keep heap usage low
                    shardBuffer.reset(); 
                    
                    currentTargetShardSize = getRandomizedShardSize(baseShardSize, random);
                    
                    // Decoy injection (10% chance to upload a junk file)
                    if (random.nextInt(100) < 10) {
                        injectDecoyFile(subfolderIds, random);
                    }
                }
            }

            // 5. Finalize Encryption
            byte[] finalEncryptedChunk = cipher.doFinal();
            if (finalEncryptedChunk != null && finalEncryptedChunk.length > 0) {
                shardBuffer.write(finalEncryptedChunk);
            }

            // Upload the last remaining buffer
            if (shardBuffer.size() > 0) {
                uploadShardAndRecord(shardBuffer.toByteArray(), shardOrder, subfolderIds, random, manifestShardsArray);
                shardBuffer.reset();
            }

            listener.onProgress(100, 100, fileSize);

            // 6. Build and Encrypt the Manifest
            listener.onStatusUpdate("Finalizing and encrypting dispatch manifest...");
            JSONObject manifest = new JSONObject();
            manifest.put("originalName", inputFile.getName());
            manifest.put("originalSize", fileSize);
            manifest.put("dataKey", Base64.encodeToString(dataKeyBytes, Base64.NO_WRAP));
            manifest.put("dataIv", Base64.encodeToString(dataIv, Base64.NO_WRAP));
            manifest.put("shards", manifestShardsArray);

            byte[] encryptedManifestData = encryptManifest(manifest.toString(), secretNumber);
            
            // Upload Manifest as a hidden file in the root of the master folder
            return driveManager.uploadShard(masterFolderId, ".hfm_manifest_" + UUID.randomUUID().toString(), encryptedManifestData);

        } catch (Exception e) {
            Log.e(TAG, "Morphed Shard Engine Failed", e);
            return null;
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (IOException ignored) {}
            }
        }
    }

    private int getRandomizedShardSize(int baseSize, SecureRandom random) {
        // Add ±20% variation to defeat size-based pattern matching
        int variation = (int) (baseSize * 0.2);
        return baseSize + (random.nextInt(variation * 2) - variation);
    }

    private void uploadShardAndRecord(byte[] data, int order, List<String> subfolderIds, SecureRandom random, JSONArray manifestArray) throws Exception {
        // Pick a random subfolder
        String targetFolderId = subfolderIds.get(random.nextInt(subfolderIds.size()));
        
        // Generate fake extension container
        String[] fakeExtensions = {".tmp", ".cache", ".sys", ".bin", ".log", ".dat"};
        String fakeExt = fakeExtensions[random.nextInt(fakeExtensions.length)];
        String fakeName = "sys_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + fakeExt;

        String driveFileId = driveManager.uploadShard(targetFolderId, fakeName, data);

        // Record in manifest
        JSONObject shardRecord = new JSONObject();
        shardRecord.put("order", order);
        shardRecord.put("id", driveFileId);
        manifestArray.put(shardRecord);

        Log.d(TAG, "Uploaded Shard #" + order + " as " + fakeName);
    }

    private void injectDecoyFile(List<String> subfolderIds, SecureRandom random) {
        try {
            String targetFolderId = subfolderIds.get(random.nextInt(subfolderIds.size()));
            String fakeName = "junk_" + UUID.randomUUID().toString().substring(0, 8) + ".tmp";
            
            // Random junk data between 1KB and 50KB
            byte[] junk = new byte[1024 + random.nextInt(49 * 1024)];
            random.nextBytes(junk);

            driveManager.uploadShard(targetFolderId, fakeName, junk);
            Log.d(TAG, "Injected Decoy File: " + fakeName);
        } catch (Exception ignored) {
            // Decoy failures shouldn't crash the main process
        }
    }

    /**
     * Encrypts the JSON Manifest using the Secret Number (PIN) provided by the user.
     * This ensures only the receiver with the PIN can decrypt the blueprint required to assemble the shards.
     */
    private byte[] encryptManifest(String manifestJson, String secretNumber) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
        KeySpec spec = new PBEKeySpec(secretNumber.toCharArray(), SALT.getBytes(StandardCharsets.UTF_8), 65536, 256);
        SecretKey tmp = factory.generateSecret(spec);
        SecretKeySpec secretKey = new SecretKeySpec(tmp.getEncoded(), "AES");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
        
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);
        byte[] encryptedData = cipher.doFinal(manifestJson.getBytes(StandardCharsets.UTF_8));

        // Prepend IV to the encrypted data so the receiver can extract it for decryption
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(iv);
        outputStream.write(encryptedData);
        return outputStream.toByteArray();
    }
}