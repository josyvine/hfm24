package com.vineyard.hfm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class RecycleBinActivity extends Activity {

    private static final String RECYCLE_BIN_FOLDER_NAME = "HFMRecycleBin";
    private static final String SD_RECYCLE_BIN_FOLDER_NAME = "HFMRecycleBin";
    private static final String PREFS_NAME = "RecycleBinPrefs";
    private static final String KEY_IS_GRID = "is_grid_view";

    private static final int SORT_BY_NAME = 1;
    private static final int SORT_BY_DATE = 2;
    private static final int SORT_BY_SIZE = 3;
    private static final int SORT_BY_TYPE = 4;

    // View References
    private ImageButton backButton;
    private TextView titleTextView;
    private RecyclerView recyclerView;
    private TextView emptyView;

    // New Enhancement Toolbar & Selection Controls
    private ImageButton toggleLayoutButton;
    private ImageButton sortButton;
    private ImageView recycleBinIcon;
    private LinearLayout selectionHeaderLayout;
    private ImageButton closeSelectionButton;
    private TextView tvSelectionCount;
    private LinearLayout bottomActionBar;
    private Button btnRestore;
    private Button btnDeleteSelected;

    // State Variables
    private File currentDirectory;
    private final File rootStorageDir = Environment.getExternalStorageDirectory();
    private final File phoneRecycleBinDir = new File(rootStorageDir, RECYCLE_BIN_FOLDER_NAME);
    private File sdCardRecycleBinDir = null;

    private boolean isGridView = false;
    private int currentSortOrder = SORT_BY_DATE;
    private final Set<File> selectedFiles = new HashSet<>();
    private boolean isSelectionMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recycle_bin);

        // Load Grid/List View state from preferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isGridView = prefs.getBoolean(KEY_IS_GRID, false);

        initializeViews();
        setupListeners();
        
        // Identify SD Card Recycle Bin path using current StorageUtils logic
        String sdCardPath = StorageUtils.getSdCardPath(this);
        if (sdCardPath != null) {
            sdCardRecycleBinDir = new File(sdCardPath, SD_RECYCLE_BIN_FOLDER_NAME);
        }

        updateLayoutManager();
        updateToggleIcon();
        updateSelectionUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Always start by showing the root view which lists available bins
        currentDirectory = null; 
        exitSelectionMode();
        refreshList();
    }

    private void initializeViews() {
        backButton = findViewById(R.id.back_button_recycle_bin);
        titleTextView = findViewById(R.id.title_recycle_bin);
        recyclerView = findViewById(R.id.recycle_bin_recycler_view);
        emptyView = findViewById(R.id.empty_view_recycle_bin);

        // New Enhancement Bindings
        toggleLayoutButton = findViewById(R.id.btn_toggle_layout_recycle_bin);
        sortButton = findViewById(R.id.btn_sort_recycle_bin);
        recycleBinIcon = findViewById(R.id.recycle_bin_icon);
        selectionHeaderLayout = findViewById(R.id.selection_header_layout_recycle_bin);
        closeSelectionButton = findViewById(R.id.btn_close_selection_recycle_bin);
        tvSelectionCount = findViewById(R.id.tv_selection_count_recycle_bin);
        bottomActionBar = findViewById(R.id.bottom_action_bar_recycle_bin);
        btnRestore = findViewById(R.id.btn_restore_recycle_bin);
        btnDeleteSelected = findViewById(R.id.btn_delete_selected_recycle_bin);
    }

    private void setupListeners() {
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleBackNavigation();
            }
        });

        toggleLayoutButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleLayout();
            }
        });

        sortButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSortMenu(v);
            }
        });

        closeSelectionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exitSelectionMode();
            }
        });

        btnRestore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restoreSelectedFiles();
            }
        });

        btnDeleteSelected.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteSelectedFiles();
            }
        });
    }

    private void toggleLayout() {
        isGridView = !isGridView;
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putBoolean(KEY_IS_GRID, isGridView).apply();
        updateLayoutManager();
        updateToggleIcon();
    }

    private void updateLayoutManager() {
        if (isGridView) {
            recyclerView.setLayoutManager(new GridLayoutManager(this, 3));
        } else {
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
        }
        if (recyclerView.getAdapter() != null) {
            recyclerView.getAdapter().notifyDataSetChanged();
        }
    }

    private void updateToggleIcon() {
        if (toggleLayoutButton != null) {
            if (isGridView) {
                toggleLayoutButton.setImageResource(R.drawable.view_list_24px);
            } else {
                toggleLayoutButton.setImageResource(R.drawable.grid_view_24px);
            }
        }
    }

    private void showSortMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        popup.getMenuInflater().inflate(R.menu.sort_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int itemId = item.getItemId();
                if (itemId == R.id.sort_by_name) {
                    currentSortOrder = SORT_BY_NAME;
                } else if (itemId == R.id.sort_by_date) {
                    currentSortOrder = SORT_BY_DATE;
                } else if (itemId == R.id.sort_by_size) {
                    currentSortOrder = SORT_BY_SIZE;
                } else if (itemId == R.id.sort_by_type) {
                    currentSortOrder = SORT_BY_TYPE;
                }
                refreshList();
                return true;
            }
        });
        popup.show();
    }

    private void refreshList() {
        createRecycleBinIfNeeded();
        if (currentDirectory == null) {
            listRootBins();
        } else {
            listFiles(currentDirectory);
        }
    }

    private void createRecycleBinIfNeeded() {
        if (!phoneRecycleBinDir.exists()) {
            phoneRecycleBinDir.mkdir();
        }
    }

    private void listRootBins() {
        updateSelectionUI();
        List<File> binList = new ArrayList<>();
        
        // Always add Phone Bin
        if (phoneRecycleBinDir.exists()) {
            binList.add(phoneRecycleBinDir);
        }
        
        // Add SD Card Bin if it exists and is not the same as phone bin
        if (sdCardRecycleBinDir != null && sdCardRecycleBinDir.exists()) {
            if (!sdCardRecycleBinDir.getAbsolutePath().equals(phoneRecycleBinDir.getAbsolutePath())) {
                binList.add(sdCardRecycleBinDir);
            }
        }
        
        setupAdapter(binList);
    }

    private void listFiles(File directory) {
        currentDirectory = directory;
        updateSelectionUI();

        File[] files = directory.listFiles();
        List<File> fileList = new ArrayList<>();
        if (files != null) {
            fileList.addAll(Arrays.asList(files));
        }
        
        Collections.sort(fileList, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                if (f1.isDirectory() && !f2.isDirectory()) return -1;
                if (!f1.isDirectory() && f2.isDirectory()) return 1;
                
                switch (currentSortOrder) {
                    case SORT_BY_NAME:
                        return f1.getName().compareToIgnoreCase(f2.getName());
                    case SORT_BY_SIZE:
                        return Long.compare(f2.length(), f1.length());
                    case SORT_BY_TYPE:
                        return getFileExtension(f1).compareToIgnoreCase(getFileExtension(f2));
                    case SORT_BY_DATE:
                    default:
                        return Long.compare(f2.lastModified(), f1.lastModified());
                }
            }
        });
        
        setupAdapter(fileList);
    }

    private String getFileExtension(File file) {
        String name = file.getName();
        int lastIndexOf = name.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return "";
        }
        return name.substring(lastIndexOf).toLowerCase(Locale.ROOT);
    }

    private void setupAdapter(List<File> files) {
        if (files.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }

        RecycleBinAdapter adapter = new RecycleBinAdapter(this, files, new RecycleBinAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(File file) {
                if (isSelectionMode) {
                    toggleSelection(file);
                } else {
                    if (file.isDirectory()) {
                        listFiles(file);
                    } else {
                        Toast.makeText(RecycleBinActivity.this, "Long press to select and restore or permanently delete files.", Toast.LENGTH_LONG).show();
                    }
                }
            }

            @Override
            public void onItemLongClick(File file) {
                if (currentDirectory != null) {
                    startSelectionMode(file);
                } else {
                    showDeleteConfirmationDialog(file);
                }
            }
        });
        recyclerView.setAdapter(adapter);
    }

    // --- SELECTION STATE MANAGEMENT ---

    public void startSelectionMode(File file) {
        isSelectionMode = true;
        selectedFiles.clear();
        selectedFiles.add(file);
        updateSelectionUI();
        if (recyclerView.getAdapter() != null) {
            recyclerView.getAdapter().notifyDataSetChanged();
        }
    }

    public void toggleSelection(File file) {
        if (selectedFiles.contains(file)) {
            selectedFiles.remove(file);
        } else {
            selectedFiles.add(file);
        }
        updateSelectionUI();
        if (recyclerView.getAdapter() != null) {
            recyclerView.getAdapter().notifyDataSetChanged();
        }
    }

    public void exitSelectionMode() {
        isSelectionMode = false;
        selectedFiles.clear();
        updateSelectionUI();
        if (recyclerView.getAdapter() != null) {
            recyclerView.getAdapter().notifyDataSetChanged();
        }
    }

    public boolean isFileSelected(File file) {
        return selectedFiles.contains(file);
    }

    public boolean isSelectionMode() {
        return isSelectionMode;
    }

    public boolean isGridView() {
        return isGridView;
    }

    private void updateSelectionUI() {
        if (isSelectionMode && !selectedFiles.isEmpty()) {
            bottomActionBar.setVisibility(View.VISIBLE);
            selectionHeaderLayout.setVisibility(View.VISIBLE);
            titleTextView.setVisibility(View.GONE);
            recycleBinIcon.setVisibility(View.GONE);
            sortButton.setVisibility(View.GONE);
            toggleLayoutButton.setVisibility(View.GONE);
            tvSelectionCount.setText(selectedFiles.size() + " selected");
        } else {
            isSelectionMode = false;
            selectedFiles.clear();
            bottomActionBar.setVisibility(View.GONE);
            selectionHeaderLayout.setVisibility(View.GONE);
            sortButton.setVisibility(View.VISIBLE);
            toggleLayoutButton.setVisibility(View.VISIBLE);
            
            updateToolbarForCurrentDir();
        }
    }

    private void updateToolbarForCurrentDir() {
        // ALWAYS keep the text title hidden and display only the modern Recycle Bin Icon next to the back arrow
        titleTextView.setVisibility(View.GONE);
        recycleBinIcon.setVisibility(View.VISIBLE);
    }

    // --- RECOVERY AND REMOVAL TASKS ---

    private void restoreSelectedFiles() {
        if (selectedFiles.isEmpty()) return;
        new RestoreTask(this, new ArrayList<>(selectedFiles)).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private class RestoreTask extends AsyncTask<Void, Void, List<File>> {
        private final List<File> filesToRestore;
        private final Context context;

        public RestoreTask(Context context, List<File> filesToRestore) {
            this.context = context;
            this.filesToRestore = filesToRestore;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            Toast.makeText(context, "Restoring files...", Toast.LENGTH_SHORT).show();
        }

        @Override
        protected List<File> doInBackground(Void... voids) {
            List<File> restored = new ArrayList<>();
            RecycleMetadataDatabase db = RecycleMetadataDatabase.getInstance(context);

            for (File file : filesToRestore) {
                String originalPath = db.getOriginalPath(file.getName());
                File destination;

                if (originalPath != null) {
                    destination = new File(originalPath);
                } else {
                    File fallbackDir = new File(Environment.getExternalStorageDirectory(), "HFM_Restored");
                    if (!fallbackDir.exists()) fallbackDir.mkdirs();
                    destination = new File(fallbackDir, file.getName());
                }

                File parent = destination.getParentFile();
                if (parent != null && !parent.exists()) {
                    StorageUtils.createDirectory(context, parent);
                }

                boolean success = false;
                if (StorageUtils.isFileOnSdCard(context, file)) {
                    if (StorageUtils.copyFile(context, file, destination)) {
                        if (StorageUtils.deleteFile(context, file)) {
                            success = true;
                        } else {
                            destination.delete();
                        }
                    }
                } else {
                    if (file.renameTo(destination)) {
                        success = true;
                    } else {
                        if (StorageUtils.copyFile(context, file, destination)) {
                            if (StorageUtils.deleteFile(context, file)) {
                                success = true;
                            } else {
                                destination.delete();
                            }
                        }
                    }
                }

                if (success) {
                    restored.add(file);
                    db.deleteRecord(file.getName());
                    MediaStoreUtils.purgePathFromMediaStore(context, file.getAbsolutePath());
                    MediaStoreUtils.scanNewPath(context, destination);
                }
            }
            return restored;
        }

        @Override
        protected void onPostExecute(List<File> result) {
            super.onPostExecute(result);
            Toast.makeText(context, result.size() + " files successfully restored.", Toast.LENGTH_LONG).show();
            exitSelectionMode();
            refreshList();
        }
    }

    private void deleteSelectedFiles() {
        if (selectedFiles.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Confirm Deletion")
                .setMessage("Permanently delete " + selectedFiles.size() + " selected item(s)? This action cannot be undone.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        new DeleteSelectedTask(RecycleBinActivity.this, new ArrayList<>(selectedFiles)).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private class DeleteSelectedTask extends AsyncTask<Void, Void, Integer> {
        private final Context context;
        private final List<File> filesToDelete;

        public DeleteSelectedTask(Context context, List<File> filesToDelete) {
            this.context = context;
            this.filesToDelete = filesToDelete;
        }

        @Override
        protected Integer doInBackground(Void... voids) {
            int count = 0;
            RecycleMetadataDatabase db = RecycleMetadataDatabase.getInstance(context);
            for (File file : filesToDelete) {
                if (file.isDirectory()) {
                    deleteRecursive(file);
                    count++;
                } else {
                    if (StorageUtils.deleteFile(context, file)) {
                        count++;
                        db.deleteRecord(file.getName());
                        MediaStoreUtils.purgePathFromMediaStore(context, file.getAbsolutePath());
                    }
                }
            }
            return count;
        }

        @Override
        protected void onPostExecute(Integer result) {
            super.onPostExecute(result);
            Toast.makeText(context, result + " files permanently deleted.", Toast.LENGTH_LONG).show();
            exitSelectionMode();
            refreshList();
        }
    }

    private void showDeleteConfirmationDialog(final File fileOrFolder) {
        String message = fileOrFolder.isDirectory() && (fileOrFolder.equals(phoneRecycleBinDir) || fileOrFolder.equals(sdCardRecycleBinDir)) ? 
            "Empty this Recycle Bin? All files inside will be permanently deleted." : 
            "Permanently delete this item?";

        new AlertDialog.Builder(this)
			.setTitle("Confirm Deletion")
			.setMessage(message)
			.setPositiveButton("Delete", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					deleteRecursive(fileOrFolder);
					refreshList();
				}
			})
			.setNegativeButton("Cancel", null)
			.show();
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
            if (!fileOrDirectory.equals(phoneRecycleBinDir) && !fileOrDirectory.equals(sdCardRecycleBinDir)) {
                StorageUtils.deleteFile(this, fileOrDirectory);
            }
        } else {
            StorageUtils.deleteFile(this, fileOrDirectory);
        }
    }

    private void handleBackNavigation() {
        if (currentDirectory != null) {
            currentDirectory = null;
            listRootBins();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        handleBackNavigation();
    }
}