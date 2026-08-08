package com.vineyard.hfm.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MediaPickerActivity extends Activity {

    private static final String TAG = "MediaPickerActivity";

    // UI Elements
    private ImageButton backButton;
    private TextView titleTextView, selectionCountTextView;
    private RecyclerView mediaRecyclerView;
    private Button sendButton;
    private LinearLayout loadingView;

    private MediaPickerAdapter adapter;
    private List<File> mediaFileList = new ArrayList<>();
    private String categoryType;
    private ScanMediaTask mScanTask; // Task reference for cancellation control

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_picker);

        initializeViews();

        categoryType = getIntent().getStringExtra(CategoryPickerActivity.EXTRA_CATEGORY_TYPE);
        if (categoryType == null) {
            Toast.makeText(this, "Error: No category specified.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        setupRecyclerView();
        setupListeners();
        updateTitle();

        mScanTask = new ScanMediaTask();
        mScanTask.execute(categoryType);
    }

    private void initializeViews() {
        backButton = findViewById(R.id.back_button_media_picker);
        titleTextView = findViewById(R.id.title_text_media_picker);
        selectionCountTextView = findViewById(R.id.selection_count_text_media_picker);
        mediaRecyclerView = findViewById(R.id.media_recycler_view);
        sendButton = findViewById(R.id.button_send_media_picker);
        loadingView = findViewById(R.id.loading_view_media_picker);
    }

    private void setupRecyclerView() {
        adapter = new MediaPickerAdapter(this, mediaFileList, new MediaPickerAdapter.OnItemClickListener() {
            @Override
            public void onSelectionChanged() {
                updateSelectionCount();
            }
        });
        mediaRecyclerView.setLayoutManager(new GridLayoutManager(this, 3));
        mediaRecyclerView.setAdapter(adapter);
    }

    private void setupListeners() {
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ArrayList<String> selectedPaths = new ArrayList<>();
                for (MediaPickerAdapter.FileItem item : adapter.getItems()) {
                    if (item.isSelected()) {
                        selectedPaths.add(item.getFile().getAbsolutePath());
                    }
                }

                if (selectedPaths.isEmpty()) {
                    Toast.makeText(MediaPickerActivity.this, "No files selected.", Toast.LENGTH_SHORT).show();
                    return;
                }

                Intent resultIntent = new Intent();
                resultIntent.putStringArrayListExtra("picked_files", selectedPaths);
                setResult(Activity.RESULT_OK, resultIntent);
                finish();
            }
        });
    }

    private void updateTitle() {
        switch (categoryType) {
            case CategoryPickerActivity.CATEGORY_VIDEOS:
                titleTextView.setText("Select Videos");
                break;
            case CategoryPickerActivity.CATEGORY_IMAGES:
                titleTextView.setText("Select Images");
                break;
            case CategoryPickerActivity.CATEGORY_AUDIO:
                titleTextView.setText("Select Audio");
                break;
            case CategoryPickerActivity.CATEGORY_DOCUMENTS:
                titleTextView.setText("Select Documents");
                break;
        }
    }

    private void updateSelectionCount() {
        int count = 0;
        for (MediaPickerAdapter.FileItem item : adapter.getItems()) {
            if (item.isSelected()) {
                count++;
            }
        }
        selectionCountTextView.setText(count + " files selected");
    }

    @Override
    protected void onDestroy() {
        if (mScanTask != null) {
            mScanTask.cancel(true);
            AppLogger.log(TAG, "[THREAD_CANCELLED] MediaPickerActivity onDestroy called, current scan task cancelled.");
        }
        super.onDestroy();
    }

    private class ScanMediaTask extends AsyncTask<String, Void, List<File>> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            loadingView.setVisibility(View.VISIBLE);
            mediaRecyclerView.setVisibility(View.GONE);
        }

        @Override
        protected List<File> doInBackground(String... params) {
            String category = params[0];
            List<File> foundFiles = new ArrayList<>();
            ContentResolver contentResolver = getContentResolver();
            long scanStartTimeMs = System.currentTimeMillis();

            AppLogger.log(TAG, "MediaPicker scan started for category: " + category + " | Manufacturer: " + Build.MANUFACTURER + " | Model: " + Build.MODEL);

            Uri queryUri;
            String[] projection = {MediaStore.MediaColumns.DATA};
            StringBuilder selectionBuilder = new StringBuilder();
            List<String> selectionArgsList = new ArrayList<>();

            switch (category) {
                case CategoryPickerActivity.CATEGORY_VIDEOS:
                    queryUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                    break;
                case CategoryPickerActivity.CATEGORY_IMAGES:
                    queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    break;
                case CategoryPickerActivity.CATEGORY_AUDIO:
                    queryUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                    break;
                case CategoryPickerActivity.CATEGORY_DOCUMENTS:
                    queryUri = MediaStore.Files.getContentUri("external");
                    selectionBuilder.append(MediaStore.Files.FileColumns.MIME_TYPE + " IN (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                    selectionArgsList.addAll(Arrays.asList(
                        "application/pdf",
                        "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 
                        "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation", 
                        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 
                        "text/plain", "text/csv", "text/html"
                    ));
                    break;
                default:
                    return foundFiles;
            }

            // Exclude systemic recycle bin and ColorOS pictorial/offline wallpaper cache
            if (selectionBuilder.length() > 0) selectionBuilder.append(" AND ");
            selectionBuilder.append(MediaStore.Files.FileColumns.DATA + " NOT LIKE ? AND " + MediaStore.Files.FileColumns.DATA + " NOT LIKE ? AND " + MediaStore.Files.FileColumns.DATA + " NOT LIKE ?");
            selectionArgsList.add("%/HFMRecycleBin/%");
            selectionArgsList.add("%/Pictorial/offline/%");
            selectionArgsList.add("%/.cache/%");

            String selection = selectionBuilder.toString();
            String[] selectionArgs = selectionArgsList.toArray(new String[0]);

            Cursor cursor = null;
            try {
                cursor = contentResolver.query(queryUri, projection, selection, selectionArgs, null);

                if (cursor != null) {
                    int dataColumnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
                    while (cursor.moveToNext()) {
                        if (isCancelled()) {
                            AppLogger.log(TAG, "[THREAD_CANCELLED] MediaPicker scan task cancelled during cursor iteration.");
                            break;
                        }
                        try {
                            if (dataColumnIndex != -1) {
                                String path = cursor.getString(dataColumnIndex);
                                if (path != null) {
                                    File file = new File(path);
                                    // GHOST THUMBNAIL SUPPRESSION: Mandatory physical file verification
                                    if (file.exists() && file.length() > 0) {
                                        foundFiles.add(file);
                                    }
                                }
                            }
                        } catch (Exception rowEx) {
                            Log.e(TAG, "Row extraction error safely bypassed: " + rowEx.getMessage());
                        }
                    }
                }

                long durationMs = System.currentTimeMillis() - scanStartTimeMs;
                if (durationMs > 1000) {
                    AppLogger.log(TAG, "[IN_FLIGHT_WARNING] MediaPicker query for category " + category + " executed in " + durationMs + " ms | Returned: " + foundFiles.size() + " items");
                }

                if (foundFiles.size() < 3) {
                    writeErrorLogToDisk("MediaStore query returned sparse/empty records (" + foundFiles.size() + ") for category: " + category + ". Initiating disk fallback.", null);
                    List<File> diskFallbackResults = new ArrayList<>();
                    File externalStorage = Environment.getExternalStorageDirectory();

                    List<File> rootsToScan = new ArrayList<>();
                    rootsToScan.add(new File(externalStorage, "WhatsApp"));
                    rootsToScan.add(new File(externalStorage, "Android/media/com.whatsapp/WhatsApp"));
                    rootsToScan.add(new File(externalStorage, "Download"));
                    rootsToScan.add(new File(externalStorage, "Telegram"));
                    rootsToScan.add(new File(externalStorage, "DCIM"));
                    rootsToScan.add(new File(externalStorage, "Pictures"));
                    rootsToScan.add(new File(externalStorage, "Documents"));
                    rootsToScan.add(new File(externalStorage, "DCIM/Camera"));
                    rootsToScan.add(externalStorage);

                    File dualAppStorage = new File("/storage/emulated/999");
                    if (dualAppStorage.exists() && dualAppStorage.canRead()) {
                         rootsToScan.add(new File(dualAppStorage, "WhatsApp"));
                         rootsToScan.add(new File(dualAppStorage, "Android/media/com.whatsapp/WhatsApp"));
                         rootsToScan.add(new File(dualAppStorage, "DCIM"));
                         rootsToScan.add(new File(dualAppStorage, "Download"));
                         rootsToScan.add(new File(dualAppStorage, "Documents"));
                         rootsToScan.add(dualAppStorage);
                    }

                    File parallelAppStorage = new File("/storage/emulated/10");
                    if (parallelAppStorage.exists() && parallelAppStorage.canRead()) {
                         rootsToScan.add(new File(parallelAppStorage, "WhatsApp"));
                         rootsToScan.add(new File(parallelAppStorage, "DCIM"));
                         rootsToScan.add(parallelAppStorage);
                    }

                    for (File root : rootsToScan) {
                        if (root.exists() && root.isDirectory()) {
                            scanDirectoryFallback(root, category, diskFallbackResults);
                        }
                    }

                    Set<String> matchedPaths = new HashSet<>();
                    for (File file : foundFiles) {
                        matchedPaths.add(file.getAbsolutePath());
                    }

                    for (File fallbackFile : diskFallbackResults) {
                        if (!matchedPaths.contains(fallbackFile.getAbsolutePath())) {
                            foundFiles.add(fallbackFile);
                        }
                    }
                }

            } catch (Throwable t) {
                AppLogger.logError(TAG, "ScanMediaTask background execution encountered an exception", t);
                writeErrorLogToDisk("ScanMediaTask background execution encountered an exception", t);
                Log.e(TAG, "ScanMediaTask background execution encountered an exception. Bypassing safely.", t);
            } finally {
                if (cursor != null) {
                    cursor.close(); // Guarantees SQLite read-lock release on ColorOS
                    if (isCancelled()) {
                        AppLogger.log(TAG, "[THREAD_CANCELLED] MediaPicker cursor closed and SQLite handle released.");
                    }
                }
            }

            Collections.sort(foundFiles, new Comparator<File>() {
                @Override
                public int compare(File f1, File f2) {
                    return Long.compare(f2.lastModified(), f1.lastModified());
                }
            });

            return foundFiles;
        }

        private void scanDirectoryFallback(File directory, String category, List<File> outList) {
            if (directory == null || !directory.exists() || !directory.isDirectory()) {
                return;
            }
            if (directory.getName().equalsIgnoreCase("HFMRecycleBin")) {
                return;
            }
            File[] files = directory.listFiles();
            if (files == null) return;
            for (File file : files) {
                if (isCancelled()) {
                    break;
                }
                if (file.isDirectory()) {
                    if (!file.getName().startsWith(".") && !file.getName().equalsIgnoreCase("Android") && !file.getName().equalsIgnoreCase("HFMRecycleBin")) {
                        scanDirectoryFallback(file, category, outList);
                    }
                } else {
                    if (file.getName().startsWith(".")) continue;
                    if (isCategoryMatch(file.getName(), category)) {
                        if (file.exists() && file.length() > 0) {
                            outList.add(file);
                        }
                    }
                }
            }
        }

        private boolean isCategoryMatch(String filename, String category) {
            String ext = "";
            int dotIdx = filename.lastIndexOf('.');
            if (dotIdx > 0) {
                ext = filename.substring(dotIdx + 1).toLowerCase(Locale.ROOT);
            }
            switch (category) {
                case CategoryPickerActivity.CATEGORY_VIDEOS:
                    return Arrays.asList("mp4", "3gp", "mkv", "webm", "avi", "mov").contains(ext);
                case CategoryPickerActivity.CATEGORY_IMAGES:
                    return Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp").contains(ext);
                case CategoryPickerActivity.CATEGORY_AUDIO:
                    return Arrays.asList("mp3", "wav", "ogg", "m4a", "aac", "flac").contains(ext);
                case CategoryPickerActivity.CATEGORY_DOCUMENTS:
                    return Arrays.asList("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "html", "rtf").contains(ext);
                default:
                    return false;
            }
        }

        @Override
        protected void onPostExecute(List<File> result) {
            super.onPostExecute(result);
            loadingView.setVisibility(View.GONE);
            mediaRecyclerView.setVisibility(View.VISIBLE);

            if (result.isEmpty()) {
                Toast.makeText(MediaPickerActivity.this, "No files found for this category.", Toast.LENGTH_LONG).show();
            } else {
                mediaFileList.clear();
                mediaFileList.addAll(result);
                adapter = new MediaPickerAdapter(MediaPickerActivity.this, mediaFileList, new MediaPickerAdapter.OnItemClickListener() {
                    @Override
                    public void onSelectionChanged() {
                        updateSelectionCount();
                    }
                });
                mediaRecyclerView.setAdapter(adapter);
            }
        }
    }

    private void writeErrorLogToDisk(String message, Throwable throwable) {
        try {
            File logDir = new File(Environment.getExternalStorageDirectory(), "hfm log report");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(new Date());
            File logFile = new File(logDir, "media_picker_log_" + timestamp + ".txt");
            FileOutputStream fos = new FileOutputStream(logFile, true);
            StringBuilder sb = new StringBuilder();
            sb.append("=== HFM DIAGNOSTIC LOG (MediaPicker) ===\n");
            sb.append("Timestamp: ").append(new Date().toString()).append("\n");
            sb.append("Category Type: ").append(categoryType).append("\n");
            sb.append("Device Manufacturer: ").append(Build.MANUFACTURER).append("\n");
            sb.append("Device Model: ").append(Build.MODEL).append("\n");
            sb.append("Device Product: ").append(Build.PRODUCT).append("\n");
            sb.append("Android SDK INT: ").append(Build.VERSION.SDK_INT).append("\n");
            sb.append("Android Release: ").append(Build.VERSION.RELEASE).append("\n");
            sb.append("Display Build: ").append(Build.DISPLAY).append("\n");
            if (message != null) {
                sb.append("Message: ").append(message).append("\n");
            }
            if (throwable != null) {
                sb.append("Exception: ").append(Log.getStackTraceString(throwable)).append("\n");
            }
            sb.append("=========================================\n\n");
            fos.write(sb.toString().getBytes());
            fos.flush();
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to write diagnostic log to disk", e);
        }
    }
}