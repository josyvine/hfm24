package com.vineyard.hfm.app;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CacheCleanerActivity extends Activity {

    private static final String TAG = "CacheCleanerActivity";

    private ImageButton backButton;
    private LinearLayout loadingView;
    private TextView scanStatusText;
    private RelativeLayout resultsView;
    private RecyclerView cacheItemsRecyclerView;
    private TextView totalCacheSizeText;
    private Button cleanButton;

    private CacheAdapter adapter;
    private List<CacheItem> cacheItemsList = new ArrayList<>();
    private long totalCacheSize = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cache_cleaner);

        // Initialize Views
        backButton = findViewById(R.id.back_button_cache);
        loadingView = findViewById(R.id.loading_view);
        scanStatusText = findViewById(R.id.scan_status_text);
        resultsView = findViewById(R.id.results_view);
        cacheItemsRecyclerView = findViewById(R.id.cache_items_recycler_view);
        totalCacheSizeText = findViewById(R.id.total_cache_size_text);
        cleanButton = findViewById(R.id.clean_button);

        // Setup RecyclerView
        cacheItemsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CacheAdapter(this, cacheItemsList);
        cacheItemsRecyclerView.setAdapter(adapter);

        // Setup Listeners
        backButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					finish();
				}
			});

        cleanButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					new CleanCacheTask().execute();
				}
			});

        // Start the scan
        new ScanCacheTask().execute();
    }

    // --- Data Model for a Cache Item ---
    public static class CacheItem {
        String name;
        String description;
        long size;
        List<File> filesToDelete;
        int iconResId;

        CacheItem(String name, String description, int iconResId) {
            this.name = name;
            this.description = description;
            this.iconResId = iconResId;
            this.size = 0;
            this.filesToDelete = new ArrayList<>();
        }

        void addFile(File file, long fileSize) {
            this.filesToDelete.add(file);
            this.size += fileSize;
        }
    }

    // --- RecyclerView Adapter ---
    private class CacheAdapter extends RecyclerView.Adapter<CacheAdapter.ViewHolder> {
        private Context context;
        private List<CacheItem> items;

        CacheAdapter(Context context, List<CacheItem> items) {
            this.context = context;
            this.items = items;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.list_item_cache, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            CacheItem item = items.get(position);
            holder.name.setText(item.name);
            holder.description.setText(item.description);
            holder.size.setText(Formatter.formatFileSize(context, item.size));
            holder.icon.setImageResource(item.iconResId);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView name, description, size;
            ImageView icon;

            ViewHolder(View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.cache_item_name);
                description = itemView.findViewById(R.id.cache_item_description);
                size = itemView.findViewById(R.id.cache_item_size);
                icon = itemView.findViewById(R.id.cache_item_icon);
            }
        }
    }


    // --- AsyncTask for Scanning Cache ---
    private class ScanCacheTask extends AsyncTask<Void, String, List<CacheItem>> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            loadingView.setVisibility(View.VISIBLE);
            resultsView.setVisibility(View.GONE);
        }

        @Override
        protected List<CacheItem> doInBackground(Void... voids) {
            List<CacheItem> foundItems = new ArrayList<>();
            totalCacheSize = 0;

            // 1. Scan Application Caches
            CacheItem appCache = new CacheItem("App Cache", "Cached data from installed apps", android.R.drawable.sym_def_app_icon);
            scanAppCaches(appCache);
            if (appCache.size > 0) {
                foundItems.add(appCache);
                totalCacheSize += appCache.size;
            }

            // 2. Scan Thumbnail Caches
            publishProgress("Scanning thumbnails...");
            CacheItem thumbnails = new CacheItem("Thumbnail Cache", "Image and video preview files", android.R.drawable.ic_menu_gallery);
            File thumbnailDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), ".thumbnails");
            scanDirectory(thumbnailDir, thumbnails);
            if (thumbnails.size > 0) {
                foundItems.add(thumbnails);
                totalCacheSize += thumbnails.size;
            }

            // 3. Scan for Log Files
            publishProgress("Scanning log files...");
            CacheItem logFiles = new CacheItem("Log Files", "System and app log files", android.R.drawable.ic_menu_info_details);
            scanForExtension(Environment.getExternalStorageDirectory(), ".log", logFiles);
            if (logFiles.size > 0) {
                foundItems.add(logFiles);
                totalCacheSize += logFiles.size;
            }

            // 4. Scan for Empty Folders
            publishProgress("Scanning for empty folders...");
            CacheItem emptyFolders = new CacheItem("Empty Folders", "Unused and empty directories", android.R.drawable.ic_menu_compass);
            scanForEmptyFolders(Environment.getExternalStorageDirectory(), emptyFolders);
            if (emptyFolders.size > 0) {
                foundItems.add(emptyFolders);
                // Size is 0 but we can still clean them
            }

            return foundItems;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            if (values.length > 0) {
                scanStatusText.setText(values[0]);
            }
        }

        @Override
        protected void onPostExecute(List<CacheItem> result) {
            super.onPostExecute(result);
            loadingView.setVisibility(View.GONE);
            resultsView.setVisibility(View.VISIBLE);

            cacheItemsList.clear();
            if (result.isEmpty() || totalCacheSize == 0) {
                totalCacheSizeText.setText("No Junk Found");
                cleanButton.setEnabled(false);
                cleanButton.setText("Cleaned");
                Toast.makeText(CacheCleanerActivity.this, "Your device is already clean!", Toast.LENGTH_LONG).show();
            } else {
                cacheItemsList.addAll(result);
                adapter.notifyDataSetChanged();
                totalCacheSizeText.setText("Total Junk: " + Formatter.formatFileSize(CacheCleanerActivity.this, totalCacheSize));
            }
        }

        private void scanAppCaches(CacheItem appCacheItem) {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            int count = 0;
            for (ApplicationInfo packageInfo : packages) {
                count++;
                publishProgress("Scanning apps: " + count + "/" + packages.size());
                File externalCacheDir = new File(Environment.getExternalStorageDirectory().getPath() + "/Android/data/" + packageInfo.packageName + "/cache");
                scanDirectory(externalCacheDir, appCacheItem);
            }
        }

        private void scanDirectory(File dir, CacheItem cacheItem) {
            if (dir != null && dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isDirectory()) {
                            scanDirectory(file, cacheItem);
                        } else {
                            cacheItem.addFile(file, file.length());
                        }
                    }
                }
            }
        }

        private void scanForExtension(File dir, String extension, CacheItem cacheItem) {
            if (dir != null && dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isDirectory()) {
                            // Avoid scanning deep into Android data again
                            if (!file.getAbsolutePath().contains("/Android/data")) {
                                scanForExtension(file, extension, cacheItem);
                            }
                        } else {
                            if (file.getName().toLowerCase().endsWith(extension)) {
                                cacheItem.addFile(file, file.length());
                            }
                        }
                    }
                }
            }
        }

        private void scanForEmptyFolders(File dir, CacheItem cacheItem) {
            if (dir == null || !dir.isDirectory() || dir.getAbsolutePath().contains("/Android/data")) return;

            File[] files = dir.listFiles();
            if (files != null) {
                if (files.length == 0) {
                    cacheItem.addFile(dir, 0); // Add the empty directory itself
                } else {
                    for (File file : files) {
                        if (file.isDirectory()) {
                            scanForEmptyFolders(file, cacheItem);
                        }
                    }
                }
            }
        }
    }


    // --- AsyncTask for Cleaning Cache ---
    private class CleanCacheTask extends AsyncTask<Void, Void, Long> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            cleanButton.setEnabled(false);
            cleanButton.setText("Cleaning...");
        }

        @Override
        protected Long doInBackground(Void... voids) {
            long cleanedSize = 0;
            for (CacheItem item : cacheItemsList) {
                for (File file : item.filesToDelete) {
                    long fileSize = file.length();
                    if (deleteRecursively(file)) {
                        cleanedSize += fileSize;
                    }
                }
            }
            return cleanedSize;
        }

        @Override
        protected void onPostExecute(Long cleanedSize) {
            super.onPostExecute(cleanedSize);
            Toast.makeText(CacheCleanerActivity.this, "Cleaned " + Formatter.formatFileSize(CacheCleanerActivity.this, cleanedSize), Toast.LENGTH_LONG).show();

            cacheItemsList.clear();
            adapter.notifyDataSetChanged();
            totalCacheSize = 0;
            totalCacheSizeText.setText("All Clean!");
            cleanButton.setText("Done");
        }

        private boolean deleteRecursively(File fileOrDirectory) {
            if (fileOrDirectory.isDirectory()) {
                File[] children = fileOrDirectory.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteRecursively(child);
                    }
                }
            }
            return fileOrDirectory.delete();
        }
    }
}

