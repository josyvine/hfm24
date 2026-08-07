package com.vineyard.hfm.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Updated FolderListActivity (Enhancement 1).
 * Uses RecyclerView and expandable headers instead of ListView.
 */
public class FolderListActivity extends Activity implements FolderListExpandableAdapter.OnHeaderClickListener, FolderListExpandableAdapter.OnItemClickListener {

    public static final String EXTRA_FOLDER_NAME = "folder_name";
    public static final String EXTRA_FILE_LIST = "file_list";
    public static final String EXTRA_TEMP_FILE_NAME = "temp_file_name";
    private static final int FILE_DELETE_REQUEST_CODE = 123;

    private TextView titleTextView;
    private ImageButton backButton;
    private RecyclerView folderRecyclerView; // Changed from ListView
    private TextView emptyView;

    private String categoryName;
    private Map<String, List<File>> folderMap;
    
    // NEW: Lists for RecyclerView Adapter logic
    private List<Object> masterList = new ArrayList<>();
    private List<Object> displayList = new ArrayList<>();
    private FolderListExpandableAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_folder_list);

        initializeViews();

        Intent intent = getIntent();
        categoryName = intent.getStringExtra(DashboardActivity.EXTRA_CATEGORY_NAME);
        String tempFileName = intent.getStringExtra(EXTRA_TEMP_FILE_NAME);

        if (categoryName == null || tempFileName == null) {
            Toast.makeText(this, "Error: Invalid data received.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        folderMap = loadFolderMapFromCache(this, tempFileName);

        if (folderMap == null) {
            Toast.makeText(this, "Error: Could not load the file list.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        titleTextView.setText(categoryName);
        setupListeners();
        
        // NEW: Populate RecyclerView instead of ListView
        setupRecyclerView();
        populateFolderList();
    }

    private Map<String, List<File>> loadFolderMapFromCache(Context context, String tempFileName) {
        File tempFile = new File(context.getCacheDir(), tempFileName);
        if (!tempFile.exists()) {
            return null;
        }
        Map<String, List<File>> loadedMap = null;
        try {
            FileInputStream fis = new FileInputStream(tempFile);
            ObjectInputStream ois = new ObjectInputStream(fis);
            loadedMap = (HashMap<String, List<File>>) ois.readObject();
            ois.close();
            fis.close();
        } catch (Exception e) {
            Log.e("FolderListActivity", "Failed to load folder map from cache", e);
        } finally {
            tempFile.delete();
        }
        return loadedMap;
    }

    private void initializeViews() {
        titleTextView = findViewById(R.id.title_folder_list);
        backButton = findViewById(R.id.back_button_folder_list);
        // Using RecyclerView id as defined in the updated XML
        folderRecyclerView = findViewById(R.id.folder_list_view);
        emptyView = findViewById(R.id.empty_view_folder_list);
    }

    private void setupRecyclerView() {
        folderRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FolderListExpandableAdapter(this, displayList, this, this);
        folderRecyclerView.setAdapter(adapter);
    }

    private void setupListeners() {
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        
        // Item click listener is now handled via the Adapter interface methods below
    }

    // Handled by Adapter Interface
    @Override
    public void onItemClick(File file) {
        // Logic to delete single file context from this list is tricky because we usually delete via folder context in previous app logic.
        // Replicating original behavior: Open FileDeleteActivity for the folder containing this file.
        // We find the parent folder name from our map.
        
        String parentFolder = "Unknown";
        List<File> siblings = new ArrayList<>();
        
        for (Map.Entry<String, List<File>> entry : folderMap.entrySet()) {
            if (entry.getValue().contains(file)) {
                parentFolder = entry.getKey();
                siblings = entry.getValue();
                break;
            }
        }
        
        Intent intent = new Intent(FolderListActivity.this, FileDeleteActivity.class);
        intent.putExtra(EXTRA_FOLDER_NAME, parentFolder);
        intent.putExtra(EXTRA_FILE_LIST, (ArrayList<File>) siblings);
        startActivityForResult(intent, FILE_DELETE_REQUEST_CODE);
    }

    // Handled by Adapter Interface (Enhancement 1)
    @Override
    public void onHeaderClick(FolderHeader header) {
        header.setExpanded(!header.isExpanded());
        rebuildDisplayList();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_DELETE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            Intent resultIntent = new Intent();
            setResult(Activity.RESULT_OK, resultIntent);
            finish();
        }
    }

    private void populateFolderList() {
        masterList.clear();
        
        List<String> folderNames = new ArrayList<>(folderMap.keySet());
        Collections.sort(folderNames, String.CASE_INSENSITIVE_ORDER);

        for (String name : folderNames) {
            List<File> files = folderMap.get(name);
            if (files != null && !files.isEmpty()) {
                masterList.add(new FolderHeader(name, files.size()));
                masterList.addAll(files);
            }
        }

        if (masterList.isEmpty()) {
            folderRecyclerView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
        } else {
            folderRecyclerView.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
            rebuildDisplayList();
        }
    }
    
    // Core logic for Expand/Collapse (Enhancement 1)
    private void rebuildDisplayList() {
        displayList.clear();
        boolean isCurrentGroupExpanded = true;

        for (Object item : masterList) {
            if (item instanceof FolderHeader) {
                FolderHeader header = (FolderHeader) item;
                displayList.add(header);
                isCurrentGroupExpanded = header.isExpanded();
            } else {
                if (isCurrentGroupExpanded) {
                    displayList.add(item);
                }
            }
        }
        adapter.updateData(displayList);
    }

    // Data class for Headers
    public static class FolderHeader {
        private String folderName;
        private int fileCount;
        private boolean isExpanded;

        public FolderHeader(String folderName, int fileCount) {
            this.folderName = folderName;
            this.fileCount = fileCount;
            this.isExpanded = true; // Default expanded
        }

        public String getFolderName() { return folderName; }
        public int getFileCount() { return fileCount; }
        public boolean isExpanded() { return isExpanded; }
        public void setExpanded(boolean expanded) { isExpanded = expanded; }
    }
}