package com.vineyard.hfm.app;

import android.app.Activity;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ReaderActivity extends Activity {

    private ImageButton closeButton;
    private LinearLayout loadingView;
    private RelativeLayout resultsView;
    private TextView scanPathText;
    private RecyclerView recyclerView;
    private EditText searchInput;

    private ReaderAdapter adapter;
    private List<ReaderAdapter.ReadableFile> fileList = new ArrayList<>();
    private final List<String> readableExtensions = Arrays.asList(
		"txt", "log", "csv", "json", "xml", "html", "js", "css",
		"java", "kt", "py", "c", "cpp", "h", "cs", "php", "rb", "go",
		"swift", "sh", "bat", "ps1", "ini", "cfg", "conf",
		"md", "rtf", "prop", "gradle", "pro", "sql", "pdf"
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        initializeViews();
        setupListeners();

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ReaderAdapter(this, fileList, new ReaderAdapter.OnItemClickListener() {
				@Override
				public void onItemClick(ReaderAdapter.ReadableFile file) {
					openFile(file);
				}
			});
        recyclerView.setAdapter(adapter);

        new ScanFilesTask().execute(Environment.getExternalStorageDirectory());
    }

    private void initializeViews() {
        closeButton = findViewById(R.id.close_button_reader);
        loadingView = findViewById(R.id.loading_view_reader);
        resultsView = findViewById(R.id.results_view_reader);
        scanPathText = findViewById(R.id.scan_path_text_reader);
        recyclerView = findViewById(R.id.reader_files_recycler_view);
        searchInput = findViewById(R.id.search_input_reader);
    }

    private void setupListeners() {
        closeButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					finish();
				}
			});

        searchInput.addTextChangedListener(new TextWatcher() {
				@Override
				public void beforeTextChanged(CharSequence s, int start, int count, int after) {
				}

				@Override
				public void onTextChanged(CharSequence s, int start, int before, int count) {
					if (adapter != null) {
						adapter.getFilter().filter(s);
					}
				}

				@Override
				public void afterTextChanged(Editable s) {
				}
			});
    }

    private void openFile(ReaderAdapter.ReadableFile file) {
        String path = file.getPath();
        Intent intent;

        if (path.toLowerCase().endsWith(".pdf")) {
            intent = new Intent(this, PdfViewerActivity.class);
        } else {
            intent = new Intent(this, TextViewerActivity.class);
        }

        intent.putExtra(TextViewerActivity.EXTRA_FILE_PATH, path); // Use same key for simplicity
        startActivity(intent);
    }

    private class ScanFilesTask extends AsyncTask<File, String, List<ReaderAdapter.ReadableFile>> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            loadingView.setVisibility(View.VISIBLE);
            resultsView.setVisibility(View.GONE);
        }

        @Override
        protected List<ReaderAdapter.ReadableFile> doInBackground(File... roots) {
            List<ReaderAdapter.ReadableFile> foundFiles = new ArrayList<>();
            for (File root : roots) {
                scanDirectory(root, foundFiles);
            }
            return foundFiles;
        }

        private void scanDirectory(File directory, List<ReaderAdapter.ReadableFile> foundFiles) {
            if (directory == null || !directory.isDirectory() || directory.isHidden()) {
                return;
            }

            publishProgress(directory.getAbsolutePath());
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        scanDirectory(file, foundFiles);
                    } else {
                        String name = file.getName();
                        int lastDot = name.lastIndexOf('.');
                        if (lastDot > 0) {
                            String extension = name.substring(lastDot + 1).toLowerCase();
                            if (readableExtensions.contains(extension)) {
                                foundFiles.add(new ReaderAdapter.ReadableFile(file));
                            }
                        }
                    }
                }
            }
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            if (values.length > 0) {
                scanPathText.setText(values[0]);
            }
        }

        @Override
        protected void onPostExecute(List<ReaderAdapter.ReadableFile> result) {
            super.onPostExecute(result);
            loadingView.setVisibility(View.GONE);
            resultsView.setVisibility(View.VISIBLE);

            if (result.isEmpty()) {
                Toast.makeText(ReaderActivity.this, "No readable files found.", Toast.LENGTH_LONG).show();
            } else {
                fileList.clear();
                fileList.addAll(result);
                adapter.notifyDataSetChanged();
            }
        }
    }
}

