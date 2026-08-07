package com.vineyard.hfm.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class DashboardActivity extends Activity {

    private static final String TAG = "DashboardActivity";

    // UI Elements
    private ImageButton closeButton;
    private ProgressBar internalStorageBar, externalStorageBar, otgStorageBar;
    private TextView internalStorageText, externalStorageText, otgStorageText;
    private LinearLayout categoryListLayout;
    private RelativeLayout loadingOverlay;
    private TextView loadingText;
    private View internalStorageLayout, externalStorageLayout, otgStorageLayout; // Clickable layouts
    private View externalStorageSection, otgStorageSection; // Sections to hide/show

    private static final int FOLDER_LIST_REQUEST_CODE = 456;
    private static final int LEGACY_STORAGE_PERMISSION_CODE = 789;
    private static final int ALL_FILES_ACCESS_REQUEST_CODE = 999;

    // Category constants and definitions
    public static final String EXTRA_CATEGORY_NAME = "category_name";
    public static final String EXTRA_FOLDER_MAP = "folder_map";
    public static final String EXTRA_STORAGE_PATH = "storage_path";
    public static final String EXTRA_STORAGE_NAME = "storage_name";

    private static final int CATEGORY_IMAGES = 1;
    private static final int CATEGORY_VIDEOS = 2;
    private static final int CATEGORY_AUDIO = 3;
    private static final int CATEGORY_DOCS = 4;
    private static final int CATEGORY_SCRIPTS = 5;
    private static final int CATEGORY_OTHER = 6;

    private Map<Integer, String> categoryNames = new HashMap<>();
    private Map<Integer, Integer> categoryIcons = new HashMap<>();

    private boolean isAnalysisRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        initializeViews();
        initializeCategories();
        setupListeners();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAndRequestStoragePermission();
    }

    private void checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30+) check for All Files Access
            if (Environment.isExternalStorageManager()) {
                startStorageAnalysisIfNeeded();
            } else {
                showAllFilesAccessDialog();
            }
        } else {
            // Android 10 and below check for standard Runtime Permissions
            boolean hasRead = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            boolean hasWrite = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;

            if (hasRead && hasWrite) {
                startStorageAnalysisIfNeeded();
            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        LEGACY_STORAGE_PERMISSION_CODE);
            }
        }
    }

    private void showAllFilesAccessDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Storage Permission Required")
                .setMessage("Hybrid File Manager requires 'All Files Access' to display your storage usage and browse files on Android 11+ devices.\n\nPlease enable 'Allow management of all files' on the next screen.")
                .setCancelable(false)
                .setPositiveButton("Grant Permission", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivityForResult(intent, ALL_FILES_ACCESS_REQUEST_CODE);
                        } catch (Exception e) {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            startActivityForResult(intent, ALL_FILES_ACCESS_REQUEST_CODE);
                        }
                    }
                })
                .setNegativeButton("Exit", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Toast.makeText(DashboardActivity.this, "Cannot access files without storage permission.", Toast.LENGTH_LONG).show();
                        finish();
                    }
                })
                .show();
    }

    private void startStorageAnalysisIfNeeded() {
        if (!isAnalysisRunning) {
            new AnalyzeStorageTask().execute();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LEGACY_STORAGE_PERMISSION_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startStorageAnalysisIfNeeded();
            } else {
                Toast.makeText(this, "Storage permission is required to analyze files.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void initializeViews() {
        closeButton = findViewById(R.id.close_button_dashboard);
        internalStorageBar = findViewById(R.id.internal_storage_bar);
        externalStorageBar = findViewById(R.id.external_storage_bar);
        otgStorageBar = findViewById(R.id.otg_storage_bar);
        internalStorageText = findViewById(R.id.internal_storage_text);
        externalStorageText = findViewById(R.id.external_storage_text);
        otgStorageText = findViewById(R.id.otg_storage_text);
        categoryListLayout = findViewById(R.id.category_list);
        loadingOverlay = findViewById(R.id.loading_overlay_dashboard);
        loadingText = findViewById(R.id.loading_text_dashboard);
        internalStorageLayout = findViewById(R.id.internal_storage_layout);
        externalStorageLayout = findViewById(R.id.external_storage_layout);
        otgStorageLayout = findViewById(R.id.otg_storage_layout);
        externalStorageSection = findViewById(R.id.external_storage_section);
        otgStorageSection = findViewById(R.id.otg_storage_section);
    }

    private void setupListeners() {
        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void initializeCategories() {
        categoryNames.put(CATEGORY_IMAGES, "Images received today");
        categoryNames.put(CATEGORY_VIDEOS, "Videos received today");
        categoryNames.put(CATEGORY_AUDIO, "Audio received today");
        categoryNames.put(CATEGORY_DOCS, "Documents received today");
        categoryNames.put(CATEGORY_SCRIPTS, "Scripts and codes received today");
        categoryNames.put(CATEGORY_OTHER, "Other files received today");

        categoryIcons.put(CATEGORY_IMAGES, R.drawable.image_24px);
        categoryIcons.put(CATEGORY_VIDEOS, R.drawable.video_file_24px);
        categoryIcons.put(CATEGORY_AUDIO, R.drawable.audio_file_24px);
        categoryIcons.put(CATEGORY_DOCS, R.drawable.docs_24px);
        categoryIcons.put(CATEGORY_SCRIPTS, R.drawable.code_24px);
        categoryIcons.put(CATEGORY_OTHER, R.drawable.category_24px);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FOLDER_LIST_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            startStorageAnalysisIfNeeded();
        } else if (requestCode == ALL_FILES_ACCESS_REQUEST_CODE) {
            checkAndRequestStoragePermission();
        }
    }

    private void updateStorageViews(List<StorageVolumeInfo> storageInfos) {
        externalStorageSection.setVisibility(View.GONE);
        otgStorageSection.setVisibility(View.GONE);

        for (final StorageVolumeInfo info : storageInfos) {
            if (info.isPrimary) {
                internalStorageText.setText(info.stats.getFormattedString());
                internalStorageBar.setProgress(info.stats.getUsagePercentage());
                setProgressBarColor(internalStorageBar, info.stats.getUsagePercentage());
                internalStorageLayout.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent intent = new Intent(DashboardActivity.this, StorageBrowserActivity.class);
                        intent.putExtra(EXTRA_STORAGE_PATH, info.path.getAbsolutePath());
                        intent.putExtra(EXTRA_STORAGE_NAME, info.name);
                        startActivity(intent);
                    }
                });
            } else if (info.type == StorageVolumeInfo.StorageType.SD_CARD) {
                externalStorageSection.setVisibility(View.VISIBLE);
                externalStorageText.setText(info.stats.getFormattedString());
                externalStorageBar.setProgress(info.stats.getUsagePercentage());
                setProgressBarColor(externalStorageBar, info.stats.getUsagePercentage());
                externalStorageLayout.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent intent = new Intent(DashboardActivity.this, StorageBrowserActivity.class);
                        intent.putExtra(EXTRA_STORAGE_PATH, info.path.getAbsolutePath());
                        intent.putExtra(EXTRA_STORAGE_NAME, info.name);
                        startActivity(intent);
                    }
                });
            } else if (info.type == StorageVolumeInfo.StorageType.OTG) {
                otgStorageSection.setVisibility(View.VISIBLE);
                otgStorageText.setText(info.stats.getFormattedString());
                otgStorageBar.setProgress(info.stats.getUsagePercentage());
                setProgressBarColor(otgStorageBar, info.stats.getUsagePercentage());
                otgStorageLayout.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent intent = new Intent(DashboardActivity.this, StorageBrowserActivity.class);
                        intent.putExtra(EXTRA_STORAGE_PATH, info.path.getAbsolutePath());
                        intent.putExtra(EXTRA_STORAGE_NAME, info.name);
                        startActivity(intent);
                    }
                });
            }
        }
    }

    private String saveFolderMapToCache(Context context, Map<String, List<File>> folderMap) {
        String tempFileName = "temp_map_" + UUID.randomUUID().toString() + ".dat";
        File tempFile = new File(context.getCacheDir(), tempFileName);
        try {
            FileOutputStream fos = new FileOutputStream(tempFile);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject((HashMap<String, List<File>>) folderMap);
            oos.close();
            fos.close();
            return tempFileName;
        } catch (Exception e) {
            Log.e(TAG, "Failed to save folder map to cache", e);
            return null;
        }
    }

    private void populateCategoryList(final Map<Integer, Map<String, List<File>>> categorizedFiles) {
        categoryListLayout.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        int iconTintColor;
        String currentTheme = ThemeManager.getTheme(this);
        if (currentTheme.equals(ThemeManager.THEME_DARK) || currentTheme.equals(ThemeManager.THEME_AMOLED) || currentTheme.equals(ThemeManager.THEME_NORDIC)) {
            iconTintColor = ContextCompat.getColor(this, android.R.color.white);
        } else {
            iconTintColor = ContextCompat.getColor(this, R.color.lt_colorPrimary);
        }

        for (final Integer categoryId : categoryNames.keySet()) {
            final Map<String, List<File>> folders = categorizedFiles.get(categoryId);
            int count = 0;
            boolean hasFolders = folders != null && !folders.isEmpty();
            if (hasFolders) {
                for (List<File> filesInFolder : folders.values()) {
                    count += filesInFolder.size();
                }
            }

            View categoryView = inflater.inflate(R.layout.list_item_category, categoryListLayout, false);
            ImageView icon = categoryView.findViewById(R.id.category_icon);
            TextView name = categoryView.findViewById(R.id.category_name);
            TextView counter = categoryView.findViewById(R.id.category_count);

            icon.setImageResource(categoryIcons.get(categoryId));
            icon.setColorFilter(iconTintColor, PorterDuff.Mode.SRC_IN);
            
            name.setText(categoryNames.get(categoryId));
            counter.setText("(" + count + ")");

            if (count > 0) {
                categoryView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String tempFileName = saveFolderMapToCache(DashboardActivity.this, folders);
                        if (tempFileName != null) {
                            Intent intent = new Intent(DashboardActivity.this, FolderListActivity.class);
                            intent.putExtra(EXTRA_CATEGORY_NAME, categoryNames.get(categoryId));
                            intent.putExtra(FolderListActivity.EXTRA_TEMP_FILE_NAME, tempFileName);
                            startActivityForResult(intent, FOLDER_LIST_REQUEST_CODE);
                        } else {
                            Toast.makeText(DashboardActivity.this, "Error preparing file list.", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            } else {
                categoryView.setAlpha(0.5f);
            }

            categoryListLayout.addView(categoryView);
        }
    }

    private void setProgressBarColor(ProgressBar progressBar, int percentage) {
        int color;
        if (percentage >= 90) {
            color = ContextCompat.getColor(this, android.R.color.holo_red_dark);
        } else if (percentage >= 70) {
            color = ContextCompat.getColor(this, android.R.color.holo_orange_dark);
        } else {
            color = ContextCompat.getColor(this, android.R.color.holo_green_dark);
        }
        LayerDrawable progressBarDrawable = (LayerDrawable) progressBar.getProgressDrawable();
        progressBarDrawable.setColorFilter(color, PorterDuff.Mode.SRC_IN);
    }

    private int getFileCategory(String fileName) {
        String extension = "";
        int i = fileName.lastIndexOf('.');
        if (i > 0) {
            extension = fileName.substring(i + 1).toLowerCase(Locale.ROOT);
        }

        List<String> imageExtensions = Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp");
        List<String> videoExtensions = Arrays.asList("mp4", "3gp", "mkv", "webm", "avi");
        List<String> audioExtensions = Arrays.asList("mp3", "wav", "ogg", "m4a", "aac", "flac");
        List<String> scriptExtensions = Arrays.asList("json", "xml", "html", "js", "css", "java", "kt", "py", "c", "cpp", "h", "cs", "php", "rb", "go", "swift", "sh", "bat", "ps1", "ini", "cfg", "conf", "md", "prop", "gradle", "pro", "sql");
        List<String> docExtensions = Arrays.asList("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv");

        if (imageExtensions.contains(extension)) return CATEGORY_IMAGES;
        if (videoExtensions.contains(extension)) return CATEGORY_VIDEOS;
        if (audioExtensions.contains(extension)) return CATEGORY_AUDIO;
        if (scriptExtensions.contains(extension)) return CATEGORY_SCRIPTS;
        if (docExtensions.contains(extension)) return CATEGORY_DOCS;
        return CATEGORY_OTHER;
    }

    private static class StorageVolumeInfo {
        enum StorageType { INTERNAL, SD_CARD, OTG }
        final File path;
        final String name;
        final boolean isPrimary;
        final StorageType type;
        final StorageStats stats;

        StorageVolumeInfo(File path, String name, boolean isPrimary, StorageType type, Context context) {
            this.path = path;
            this.name = name;
            this.isPrimary = isPrimary;
            this.type = type;
            this.stats = new StorageStats(path, context);
        }
    }

    private class AnalyzeStorageTask extends AsyncTask<Void, String, AnalysisResult> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            isAnalysisRunning = true;
            loadingOverlay.setVisibility(View.VISIBLE);
        }

        @Override
        protected AnalysisResult doInBackground(Void... voids) {
            publishProgress("Calculating storage...");
            List<StorageVolumeInfo> storageInfos = getStorageVolumes();

            publishProgress("Scanning for today's files...");
            Map<Integer, Map<String, List<File>>> categorizedFiles = new HashMap<>();
            for (int key : categoryNames.keySet()) {
                categorizedFiles.put(key, new HashMap<String, List<File>>());
            }

            long todayStartMillis = getStartOfToday();

            for (StorageVolumeInfo info : storageInfos) {
                if (info.path != null && info.path.exists()) {
                    scanDirectory(info.path, todayStartMillis, categorizedFiles);
                }
            }

            return new AnalysisResult(storageInfos, categorizedFiles);
        }

        private List<StorageVolumeInfo> getStorageVolumes() {
            List<StorageVolumeInfo> volumes = new ArrayList<>();
            StorageManager storageManager = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
            if (storageManager == null) {
                return volumes;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                List<StorageVolume> storageVolumes = storageManager.getStorageVolumes();
                boolean sdCardSlotFilled = false;

                for (StorageVolume volume : storageVolumes) {
                    File path = getVolumePath(volume);
                    if (path != null && "mounted".equals(volume.getState())) {
                        if (volume.isPrimary()) {
                            volumes.add(new StorageVolumeInfo(path, "Internal Storage", true, StorageVolumeInfo.StorageType.INTERNAL, DashboardActivity.this));
                        } else if (volume.isRemovable()) {
                            String description = volume.getDescription(DashboardActivity.this);
                            String descLower = (description != null) ? description.toLowerCase(Locale.ROOT) : "";

                            if (descLower.contains("usb")) {
                                volumes.add(new StorageVolumeInfo(path, "OTG Storage", false, StorageVolumeInfo.StorageType.OTG, DashboardActivity.this));
                            } else if (descLower.contains("sd")) {
                                volumes.add(new StorageVolumeInfo(path, "SD Card", false, StorageVolumeInfo.StorageType.SD_CARD, DashboardActivity.this));
                                sdCardSlotFilled = true;
                            } else {
                                if (!sdCardSlotFilled) {
                                    volumes.add(new StorageVolumeInfo(path, "SD Card", false, StorageVolumeInfo.StorageType.SD_CARD, DashboardActivity.this));
                                    sdCardSlotFilled = true;
                                } else {
                                    volumes.add(new StorageVolumeInfo(path, "OTG Storage", false, StorageVolumeInfo.StorageType.OTG, DashboardActivity.this));
                                }
                            }
                        }
                    }
                }
            } else {
                File internal = Environment.getExternalStorageDirectory();
                volumes.add(new StorageVolumeInfo(internal, "Internal Storage", true, StorageVolumeInfo.StorageType.INTERNAL, DashboardActivity.this));
                
                String sdCardPath = getSdCardPath(DashboardActivity.this); 
                if (sdCardPath != null) {
                    volumes.add(new StorageVolumeInfo(new File(sdCardPath), "SD Card", false, StorageVolumeInfo.StorageType.SD_CARD, DashboardActivity.this));
                }
            }
        
            Collections.sort(volumes, new Comparator<StorageVolumeInfo>() {
                @Override
                public int compare(StorageVolumeInfo v1, StorageVolumeInfo v2) {
                    return Integer.compare(v1.type.ordinal(), v2.type.ordinal());
                }
            });
        
            return volumes;
        }

        private String getSdCardPath(Context context) {
            File[] externalDirs = ContextCompat.getExternalFilesDirs(context, null);
            if (externalDirs != null && externalDirs.length > 1 && externalDirs[1] != null) {
                try {
                    String sdCardPath = externalDirs[1].getAbsolutePath().split("/Android/data/")[0];
                    return new File(sdCardPath).getCanonicalPath();
                } catch (Exception e) {
                    Log.e(TAG, "Error getting canonical SD card path", e);
                }
            }
            return null;
        }

        private File getVolumePath(StorageVolume volume) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return volume.getDirectory();
            } else {
                try {
                    Method getPathMethod = volume.getClass().getMethod("getPath");
                    return new File((String) getPathMethod.invoke(volume));
                } catch (Exception e) {
                    Log.e(TAG, "Could not get volume path via reflection", e);
                    return null;
                }
            }
        }

        private void scanDirectory(File directory, long todayStartMillis, Map<Integer, Map<String, List<File>>> categorizedFiles) {
            if (directory == null || !directory.isDirectory() || directory.getName().startsWith(".") || directory.getName().equalsIgnoreCase("HFMRecycleBin")) {
                return;
            }

            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (isCancelled()) {
                        return;
                    }
                    if (file.isDirectory()) {
                        scanDirectory(file, todayStartMillis, categorizedFiles);
                    } else {
                        if (file.lastModified() >= todayStartMillis) {
                            int category = getFileCategory(file.getName());
                            String parentFolderName = getHumanReadableFolderName(file.getParentFile());

                            Map<String, List<File>> folderMap = categorizedFiles.get(category);
                            if (folderMap != null) {
                                List<File> fileList = folderMap.get(parentFolderName);
                                if (fileList == null) {
                                    fileList = new ArrayList<>();
                                    folderMap.put(parentFolderName, fileList);
                                }
                                fileList.add(file);
                            }
                        }
                    }
                }
            }
        }

        private String getHumanReadableFolderName(File folder) {
            if (folder == null) return "Unknown";
            String path = folder.getAbsolutePath().toLowerCase();

            if (path.contains("whatsapp/media/whatsapp images")) return "WhatsApp Images";
            if (path.contains("whatsapp/media/whatsapp video")) return "WhatsApp Videos";
            if (path.contains("whatsapp/media/whatsapp audio")) return "WhatsApp Audio";
            if (path.contains("whatsapp/media/whatsapp documents")) return "WhatsApp Documents";
            if (path.contains("telegram/telegram images")) return "Telegram Images";
            if (path.contains("telegram/telegram video")) return "Telegram Videos";
            if (path.contains("telegram/telegram audio")) return "Telegram Audio";
            if (path.contains("telegram/telegram documents")) return "Telegram Documents";
            if (path.contains("dcim/camera")) return "Camera";
            if (path.contains("download")) return "Downloads";
            if (path.contains("com.gpsmapcamera.geotagginglocationonphoto")) return "GPS Map Camera";

            return folder.getName();
        }

        private long getStartOfToday() {
            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            return calendar.getTimeInMillis();
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            if (values.length > 0) {
                loadingText.setText(values[0]);
            }
        }

        @Override
        protected void onPostExecute(AnalysisResult result) {
            super.onPostExecute(result);
            isAnalysisRunning = false;
            loadingOverlay.setVisibility(View.GONE);

            if (result != null) {
                updateStorageViews(result.storageInfos);
                populateCategoryList(result.categorizedFiles);
            } else {
                Toast.makeText(DashboardActivity.this, "Failed to analyze storage.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static class AnalysisResult {
        List<StorageVolumeInfo> storageInfos;
        Map<Integer, Map<String, List<File>>> categorizedFiles;

        AnalysisResult(List<StorageVolumeInfo> storageInfos, Map<Integer, Map<String, List<File>>> categorizedFiles) {
            this.storageInfos = storageInfos;
            this.categorizedFiles = categorizedFiles;
        }
    }

    private static class StorageStats {
        private boolean available;
        private long totalBytes;
        private long freeBytes;
        private Context context;

        StorageStats(File path, Context context) {
            this.context = context;
            if (path == null) {
                available = false;
                return;
            }
            try {
                StatFs stat = new StatFs(path.getPath());
                totalBytes = stat.getTotalBytes();
                freeBytes = stat.getAvailableBytes();
                available = true;
            } catch (Exception e) {
                available = false;
            }
        }

        boolean isAvailable() {
            return available;
        }

        int getUsagePercentage() {
            if (!available || totalBytes == 0) return 0;
            long usedBytes = totalBytes - freeBytes;
            return (int) ((usedBytes * 100) / totalBytes);
        }

        String getFormattedString() {
            if (!available) return "N/A";
            String used = Formatter.formatFileSize(context, totalBytes - freeBytes);
            String total = Formatter.formatFileSize(context, totalBytes);
            return used + " / " + total;
        }
    }
}