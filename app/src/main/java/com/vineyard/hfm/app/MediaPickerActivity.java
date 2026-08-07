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

            Uri queryUri;
            String[] projection = {MediaStore.MediaColumns.DATA};
            String selection = null;
            String[] selectionArgs = null;

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
                    selection = MediaStore.Files.FileColumns.MIME_TYPE + " IN (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                    selectionArgs = new String[]{
                        "application/pdf",
                        "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", // .doc, .docx
                        "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation", // .ppt, .pptx
                        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // .xls, .xlsx
                        "text/plain", "text/csv", "text/html"
                    };
                    break;
                default:
                    return foundFiles; // Return empty for unknown category
            }

            try {
                // FIX: Pass null for sortOrder to bypass OPPO/ColorOS/Vivo/Xiaomi SQL view parser bugs
                Cursor cursor = contentResolver.query(queryUri, projection, selection, selectionArgs, null);

                if (cursor != null) {
                    try {
                        int dataColumnIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
                        while (cursor.moveToNext()) {
                            if (isCancelled()) {
                                break;
                            }
                            String path = cursor.getString(dataColumnIndex);
                            if (path != null) {
                                File file = new File(path);
                                if (file.exists() && file.length() > 0) { // Check if file exists and is not empty
                                    foundFiles.add(file);
                                }
                            }
                        }
                    } finally {
                        cursor.close();
                    }
                }

                // Mechanism 2 Fall-Safe Switch: If MediaStore query returns sparse or empty records, 
                // perform a fallback manual filesystem sweep of key storage roots.
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
                    rootsToScan.add(new File(externalStorage, "DCIM/Camera"));
                    rootsToScan.add(externalStorage); // Direct storage fallback

                    File dualAppStorage = new File("/storage/emulated/999");
                    if (dualAppStorage.exists() && dualAppStorage.canRead()) {
                         rootsToScan.add(new File(dualAppStorage, "WhatsApp"));
                         rootsToScan.add(new File(dualAppStorage, "Android/media/com.whatsapp/WhatsApp"));
                         rootsToScan.add(new File(dualAppStorage, "DCIM"));
                         rootsToScan.add(new File(dualAppStorage, "Download"));
                    }

                    File parallelAppStorage = new File("/storage/emulated/10");
                    if (parallelAppStorage.exists() && parallelAppStorage.canRead()) {
                         rootsToScan.add(new File(parallelAppStorage, "WhatsApp"));
                         rootsToScan.add(new File(parallelAppStorage, "DCIM"));
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
                // Catch-all block ensures database exception thrown on ColorOS never terminates the background thread.
                writeErrorLogToDisk("ScanMediaTask background execution encountered an exception", t);
                Log.e("MediaPickerActivity", "ScanMediaTask background execution encountered an exception. Bypassing safely.", t);
            }

            // Sort 100% in Java memory (OPPO / ColorOS safe)
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
                return; // Recycle Bin exclusion preservation
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
            sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
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
            Log.e("MediaPickerActivity", "Failed to write diagnostic log to disk", e);
        }
    }
}