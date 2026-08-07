package com.vineyard.hfm.app;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class ReconstructionEngine {

    private static final String TAG = "ReconstructionEngine";
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA1";
    private static final String SALT = "hfm_morphed_manifest_salt_v1";

    private final Context context;
    private final GoogleDriveManager driveManager;

    // Callbacks for UI updates during the pipeline
    public interface ProgressListener {
        void onProgress(int progress, int max, long bytesProcessed);
        void onStatusUpdate(String minorStatus);
    }

    public ReconstructionEngine(Context context, GoogleDriveManager driveManager) {
        this.context = context;
        this.driveManager = driveManager;
    }

    /**
     * Executes Phase 4: Fetches the manifest, parses the shard map, and rebuilds the file using a sliding window.
     *
     * @param manifestFileId The Google Drive ID of the encrypted manifest file.
     * @param secretNumber   The user-provided PIN to decrypt the manifest.
     * @param vaultFile      The secure destination file provided by SecureVaultManager.
     * @param listener       Callback for real-time UI updates.
     * @return The original file name if successful, throws Exception if reconstruction failed.
     */
    public String executeReconstruction(String manifestFileId, String secretNumber, File vaultFile, ProgressListener listener) throws Exception {
        FileOutputStream fos = null;
        try {
            // 1. Download the Encrypted Manifest
            listener.onStatusUpdate("Locating encrypted dispatch manifest...");
            ByteArrayOutputStream manifestStream = driveManager.downloadShard(manifestFileId);
            byte[] encryptedManifestBytes = manifestStream.toByteArray();
            manifestStream.close();

            // 2. Decrypt the Manifest
            listener.onStatusUpdate("Decrypting manifest blueprint...");
            String manifestJsonString = decryptManifest(encryptedManifestBytes, secretNumber);
            if (manifestJsonString == null) {
                throw new Exception("Manifest decryption returned null. Invalid PIN or corrupted data.");
            }

            JSONObject manifest = new JSONObject(manifestJsonString);
            String originalName = manifest.getString("originalName");
            long originalSize = manifest.getLong("originalSize");

            // Extract the AES-256-GCM file keys
            byte[] dataKeyBytes = Base64.decode(manifest.getString("dataKey"), Base64.NO_WRAP);
            byte[] dataIv = Base64.decode(manifest.getString("dataIv"), Base64.NO_WRAP);

            // 3. Parse and Sort the Shard Map
            listener.onStatusUpdate("Analyzing polymorphic shard topology...");
            JSONArray shardsArray = manifest.getJSONArray("shards");
            List<JSONObject> sortedShards = new ArrayList<>();
            for (int i = 0; i < shardsArray.length(); i++) {
                sortedShards.add(shardsArray.getJSONObject(i));
            }

            // Ensure shards are processed in the exact order they were split
            Collections.sort(sortedShards, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject o1, JSONObject o2) {
                    try {
                        return Integer.compare(o1.getInt("order"), o2.getInt("order"));
                    } catch (Exception e) {
                        return 0;
                    }
                }
            });

            // 4. Setup File Decryption Engine
            SecretKeySpec dataKey = new SecretKeySpec(dataKeyBytes, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(128, dataIv);
            Cipher dataCipher = Cipher.getInstance("AES/GCM/NoPadding");
            dataCipher.init(Cipher.DECRYPT_MODE, dataKey, gcmSpec);

            fos = new FileOutputStream(vaultFile);

            long totalBytesProcessed = 0;
            int totalShards = sortedShards.size();

            // 5. THE SLIDING WINDOW PIPELINE (OOM Prevention)
            // Buffer A: Drive Downloader -> Buffer B: Cipher Update -> Buffer C: FileOutputStream
            for (int i = 0; i < totalShards; i++) {
                JSONObject shardData = sortedShards.get(i);
                String driveFileId = shardData.getString("id");

                listener.onStatusUpdate("Downloading & Decrypting Shard " + (i + 1) + " of " + totalShards);

                // Fetch Shard (Buffer A)
                ByteArrayOutputStream downloadedShard = driveManager.downloadShard(driveFileId);
                byte[] encryptedChunk = downloadedShard.toByteArray();
                downloadedShard.close(); // Immediately release stream

                totalBytesProcessed += encryptedChunk.length;

                // Process Shard (Buffer B)
                byte[] decryptedChunk = dataCipher.update(encryptedChunk);

                // Write Shard (Buffer C)
                if (decryptedChunk != null && decryptedChunk.length > 0) {
                    fos.write(decryptedChunk);
                }

                // Update UI
                listener.onProgress((int) (((float) (i + 1) / totalShards) * 100), 100, totalBytesProcessed);
            }

            // 6. Finalize Decryption Block (GCM Authentication Tag Validation)
            listener.onStatusUpdate("Validating cryptographic integrity...");
            byte[] finalDecryptedChunk = dataCipher.doFinal();
            if (finalDecryptedChunk != null && finalDecryptedChunk.length > 0) {
                fos.write(finalDecryptedChunk);
            }

            fos.flush();
            listener.onProgress(100, 100, totalBytesProcessed);

            Log.d(TAG, "Reconstruction successful for: " + originalName);
            return originalName;

        } catch (Exception e) {
            Log.e(TAG, "Reconstruction Engine Failed", e);
            if (vaultFile != null && vaultFile.exists()) {
                vaultFile.delete(); // Wipe corrupted/partial file on failure
            }
            throw e; // Pass the exact Java error (Drive API or Crypto failure) back to the service
        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Decrypts the JSON Manifest using the Secret Number (PIN) provided by the receiver.
     */
    private String decryptManifest(byte[] encryptedDataWithIv, String secretNumber) throws Exception {
        // The first 12 bytes are the GCM IV
        if (encryptedDataWithIv.length < 12) {
            throw new Exception("Encrypted data is too short to contain IV.");
        }

        // FIX: Critical sanitization of the PIN to ensure key derivation matches the sender's exactly
        String sanitizedPIN = (secretNumber != null) ? secretNumber.trim().replaceAll("\\s+", "") : "";
        if (sanitizedPIN.isEmpty()) {
            throw new Exception("Sanitized Secret Number is empty.");
        }

        byte[] iv = new byte[12];
        System.arraycopy(encryptedDataWithIv, 0, iv, 0, 12);

        byte[] cipherText = new byte[encryptedDataWithIv.length - 12];
        System.arraycopy(encryptedDataWithIv, 12, cipherText, 0, cipherText.length);

        // Derive the key using the sanitized PIN and exact PBKDF2 logic
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
        KeySpec spec = new PBEKeySpec(sanitizedPIN.toCharArray(), SALT.getBytes(StandardCharsets.UTF_8), 65536, 256);
        SecretKey tmp = factory.generateSecret(spec);
        SecretKeySpec secretKey = new SecretKeySpec(tmp.getEncoded(), "AES");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);

        byte[] decryptedBytes = cipher.doFinal(cipherText);
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }
}