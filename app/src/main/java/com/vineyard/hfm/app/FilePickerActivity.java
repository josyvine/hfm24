package com.vineyard.hfm.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FilePickerActivity extends Activity {

    private RecyclerView fileRecyclerView;
    private FilePickerAdapter adapter;
    private TextView pathTextView, selectionCountTextView;
    private ImageButton backButton;
    private Button sendButton;

    private File currentDirectory;
    private Set<File> selectedFiles = new HashSet<File>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_picker);

        initializeViews();
        setupListeners();

        currentDirectory = Environment.getExternalStorageDirectory();
        listFiles(currentDirectory);
    }

    private void initializeViews() {
        pathTextView = findViewById(R.id.path_text_picker);
        selectionCountTextView = findViewById(R.id.selection_count_text_picker);
        fileRecyclerView = findViewById(R.id.file_recycler_view);
        backButton = findViewById(R.id.back_button_picker);
        sendButton = findViewById(R.id.button_send_picker);

        fileRecyclerView.setLayoutManager(new LinearLayoutManager(this));
    }

    private void setupListeners() {
        backButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					File parent = currentDirectory.getParentFile();
					if (parent != null) {
						listFiles(parent);
					}
				}
			});

        sendButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (selectedFiles.isEmpty()) {
						Toast.makeText(FilePickerActivity.this, "No files selected.", Toast.LENGTH_SHORT).show();
						return;
					}
					ArrayList<String> selectedPaths = new ArrayList<String>();
					for (File file : selectedFiles) {
						selectedPaths.add(file.getAbsolutePath());
					}
					Intent resultIntent = new Intent();
					resultIntent.putStringArrayListExtra("picked_files", selectedPaths);
					setResult(Activity.RESULT_OK, resultIntent);
					finish();
				}
			});
    }

    private void listFiles(File directory) {
        currentDirectory = directory;
        pathTextView.setText(directory.getAbsolutePath());

        File[] files = directory.listFiles();
        List<File> fileList = new ArrayList<File>();
        if (files != null) {
            fileList.addAll(Arrays.asList(files));
        }

        Collections.sort(fileList, new Comparator<File>() {
				@Override
				public int compare(File f1, File f2) {
					if (f1.isDirectory() && !f2.isDirectory()) {
						return -1;
					} else if (!f1.isDirectory() && f2.isDirectory()) {
						return 1;
					} else {
						return f1.getName().compareToIgnoreCase(f2.getName());
					}
				}
			});

        adapter = new FilePickerAdapter(this, fileList);
        fileRecyclerView.setAdapter(adapter);
    }

    private void updateSelectionCount() {
        selectionCountTextView.setText(selectedFiles.size() + " files selected");
    }

    // --- RecyclerView Adapter for File Picker ---
    private class FilePickerAdapter extends RecyclerView.Adapter<FilePickerAdapter.ViewHolder> {
        private Context context;
        private List<File> files;

        public FilePickerAdapter(Context context, List<File> files) {
            this.context = context;
            this.files = files;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.list_item_file_picker, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final File file = files.get(position);
            holder.fileName.setText(file.getName());

            if (file.isDirectory()) {
                holder.fileIcon.setImageResource(android.R.drawable.ic_menu_myplaces);
                holder.checkBox.setVisibility(View.GONE);
            } else {
                holder.fileIcon.setImageResource(android.R.drawable.ic_menu_info_details);
                holder.checkBox.setVisibility(View.VISIBLE);
                holder.checkBox.setChecked(selectedFiles.contains(file));
            }

            holder.itemView.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						if (file.isDirectory()) {
							listFiles(file);
						} else {
							holder.checkBox.setChecked(!holder.checkBox.isChecked());
						}
					}
				});

            holder.checkBox.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						if (((CheckBox) v).isChecked()) {
							selectedFiles.add(file);
						} else {
							selectedFiles.remove(file);
						}
						updateSelectionCount();
					}
				});
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        public class ViewHolder extends RecyclerView.ViewHolder {
            ImageView fileIcon;
            TextView fileName;
            CheckBox checkBox;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                fileIcon = itemView.findViewById(R.id.file_icon_picker);
                fileName = itemView.findViewById(R.id.file_name_picker);
                checkBox = itemView.findViewById(R.id.file_checkbox_picker);
            }
        }
    }
}

