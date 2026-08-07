package com.vineyard.hfm.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.content.ClipData;
import android.content.ClipboardManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class StorageMapActivity extends Activity {

    private static final String TAG = "StorageMapActivity";
    private static final int FILE_VIEWER_REQUEST_CODE = 101;

    // Category constants
    private static final int CATEGORY_VIDEOS = 0;
    private static final int CATEGORY_IMAGES = 1;
    private static final int CATEGORY_AUDIO = 2;
    private static final int CATEGORY_DOCS = 3;
    private static final int CATEGORY_OTHER = 4;

    private WebView webView;
    private LinearLayout loadingView;
    private TextView scanPathText;
    private RelativeLayout navigationBar;
    private Button prevCategoryButton, nextCategoryButton;
    private TextView categoryTitleText;

    private String mJsonData;
    private List<JSONObject> categorizedData;
    private final List<String> categoryTitles = Arrays.asList("Videos", "Images", "Audio", "Documents", "Other");
    private int currentCategoryIndex = 0;

    private final List<String> imageExtensions = Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp");
    private final List<String> videoExtensions = Arrays.asList("mp4", "3gp", "mkv", "webm", "avi");
    private final List<String> audioExtensions = Arrays.asList("mp3", "wav", "ogg", "m4a", "aac", "flac");
    private final List<String> docExtensions = Arrays.asList(
		"txt", "log", "csv", "json", "xml", "html", "js", "css",
		"java", "kt", "py", "c", "cpp", "h", "cs", "php", "rb", "go",
		"swift", "sh", "bat", "ps1", "ini", "cfg", "conf",
		"md", "rtf", "prop", "gradle", "pro", "sql", "pdf",
		"doc", "docx", "xls", "xlsx", "ppt", "pptx"
    );

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_storage_map);

        initializeViews();
        setupListeners();

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        webView.setBackgroundColor(0x00000000);
        webView.addJavascriptInterface(new WebAppInterface(), "HFM_AndroidInterface");

        startScan();
    }

    private void initializeViews() {
        loadingView = findViewById(R.id.loading_view_treemap);
        scanPathText = findViewById(R.id.scan_path_text_treemap);
        webView = findViewById(R.id.treemap_webview);
        navigationBar = findViewById(R.id.navigation_bar_storage_map);
        prevCategoryButton = findViewById(R.id.prev_category_button);
        nextCategoryButton = findViewById(R.id.next_category_button);
        categoryTitleText = findViewById(R.id.category_title_text);
    }

    private void setupListeners() {
        nextCategoryButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (currentCategoryIndex < categorizedData.size() - 1) {
						currentCategoryIndex++;
						displayCurrentCategory();
					}
				}
			});

        prevCategoryButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (currentCategoryIndex > 0) {
						currentCategoryIndex--;
						displayCurrentCategory();
					}
				}
			});
    }

    private void startScan() {
        webView.setVisibility(View.INVISIBLE);
        navigationBar.setVisibility(View.GONE);
        loadingView.setVisibility(View.VISIBLE);
        new ScanStorageTask().execute(Environment.getExternalStorageDirectory());
    }

    private void displayCurrentCategory() {
        if (categorizedData == null || categorizedData.isEmpty()) {
            return;
        }

        categoryTitleText.setText(categoryTitles.get(currentCategoryIndex));
        prevCategoryButton.setEnabled(currentCategoryIndex > 0);
        nextCategoryButton.setEnabled(currentCategoryIndex < categoryTitles.size() - 1);

        JSONObject currentJson = categorizedData.get(currentCategoryIndex);
        mJsonData = currentJson.toString();

        webView.loadUrl("file:///android_asset/treemap_view.html");
        webView.setWebViewClient(new android.webkit.WebViewClient() {
				@Override
				public void onPageFinished(WebView view, String url) {
					super.onPageFinished(view, url);
					webView.loadUrl("javascript:initializeTreemap()");
				}
			});
    }


    public class WebAppInterface {
        @JavascriptInterface
        public void onFileClicked(final String fileDetailsJson) {
            runOnUiThread(new Runnable() {
					@Override
					public void run() {
						try {
							JSONObject fileDetails = new JSONObject(fileDetailsJson);
                            // FIX: These variables must be declared final to be used in the inner OnClickListener class
							final String name = fileDetails.getString("name");
							final String path = fileDetails.getString("path");

                            final CharSequence[] options = {"Open", "Details"};
                            new AlertDialog.Builder(StorageMapActivity.this)
                                .setTitle(name)
                                .setItems(options, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        if (which == 0) {
                                            openFileViewer(name, path);
                                        } else {
                                            List<File> files = new ArrayList<>();
                                            files.add(new File(path));
                                            showDetailsDialog(files);
                                        }
                                    }
                                })
                                .show();
						} catch (JSONException e) {
							Log.e(TAG, "Could not parse file details from JS", e);
						}
					}
				});
        }

        @JavascriptInterface
        public String getJsonData() {
            return mJsonData;
        }
    }

    private ArrayList<String> getSiblingFiles(String path, final int category) {
        ArrayList<String> siblingFiles = new ArrayList<>();
        File currentFile = new File(path);
        File parentDir = currentFile.getParentFile();
        if (parentDir == null || !parentDir.isDirectory()) {
            siblingFiles.add(path);
            return siblingFiles;
        }

        File[] filesInDir = parentDir.listFiles();
        if (filesInDir != null) {
            List<File> sortedFiles = new ArrayList<>(Arrays.asList(filesInDir));
            Collections.sort(sortedFiles, new Comparator<File>() {
					@Override
					public int compare(File f1, File f2) {
						return f1.getName().compareToIgnoreCase(f2.getName());
					}
				});

            for (File file : sortedFiles) {
                if (file.isFile() && getFileCategory(file.getName()) == category) {
                    siblingFiles.add(file.getAbsolutePath());
                }
            }
        }
        return siblingFiles;
    }


    private void openFileViewer(String name, String path) {
        int category = getFileCategory(name);
        Intent intent = null;

        if (category == CATEGORY_IMAGES || category == CATEGORY_VIDEOS || category == CATEGORY_AUDIO) {
            ArrayList<String> fileList = getSiblingFiles(path, category);
            int currentIndex = fileList.indexOf(path);
            if (currentIndex == -1) {
                Toast.makeText(this, "Error finding file in list.", Toast.LENGTH_SHORT).show();
                return;
            }

            if (category == CATEGORY_IMAGES) {
                intent = new Intent(this, ImageViewerActivity.class);
                intent.putStringArrayListExtra(ImageViewerActivity.EXTRA_FILE_PATH_LIST, fileList);
                intent.putExtra(ImageViewerActivity.EXTRA_CURRENT_INDEX, currentIndex);
            } else if (category == CATEGORY_VIDEOS) {
                intent = new Intent(this, VideoViewerActivity.class);
                intent.putStringArrayListExtra(VideoViewerActivity.EXTRA_FILE_PATH_LIST, fileList);
                intent.putExtra(VideoViewerActivity.EXTRA_CURRENT_INDEX, currentIndex);
            } else if (category == CATEGORY_AUDIO) {
                intent = new Intent(this, AudioPlayerActivity.class);
                intent.putStringArrayListExtra(AudioPlayerActivity.EXTRA_FILE_PATH_LIST, fileList);
                intent.putExtra(AudioPlayerActivity.EXTRA_CURRENT_INDEX, currentIndex);
            }
        } else {
            if (category == CATEGORY_DOCS) {
                if (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                    intent = new Intent(this, PdfViewerActivity.class);
                } else {
                    intent = new Intent(this, TextViewerActivity.class);
                }
                intent.putExtra(TextViewerActivity.EXTRA_FILE_PATH, path);
            } else {
                intent = new Intent(this, TextViewerActivity.class);
                intent.putExtra(TextViewerActivity.EXTRA_FILE_PATH, path);
            }
        }

        if (intent != null) {
            startActivityForResult(intent, FILE_VIEWER_REQUEST_CODE);
        }
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_VIEWER_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                boolean fileWasDeleted = data.getBooleanExtra(VideoViewerActivity.RESULT_FILE_DELETED, false);
                if (fileWasDeleted) {
                    startScan();
                }
            }
        }
    }

    private void showDetailsDialog(final List<File> files) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_details, null);
        builder.setView(dialogView);
        builder.setCancelable(false);

        final TextView basicDetailsText = dialogView.findViewById(R.id.details_text_basic);
        final TextView aiDetailsText = dialogView.findViewById(R.id.details_text_ai);
        final ProgressBar progressBar = dialogView.findViewById(R.id.details_progress_bar);
        final Button moreButton = dialogView.findViewById(R.id.details_button_more);
        final Button copyButton = dialogView.findViewById(R.id.details_button_copy);
        final Button closeButton = dialogView.findViewById(R.id.details_button_close);

        final AlertDialog dialog = builder.create();

        File file = files.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("Name: ").append(file.getName()).append("\n");
        sb.append("Path: ").append(file.getAbsolutePath()).append("\n");
        sb.append("Size: ").append(Formatter.formatFileSize(this, file.length())).append("\n");
        sb.append("Last Modified: ").append(new Date(file.lastModified()).toString());
        basicDetailsText.setText(sb.toString());

        final GeminiAnalyzer analyzer = new GeminiAnalyzer(this, aiDetailsText, progressBar, copyButton);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        moreButton.setEnabled(ApiKeyManager.getApiKey(this) != null && isConnected);

        moreButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					analyzer.analyze(files);
				}
			});

        copyButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
					ClipData clip = ClipData.newPlainText("AI Summary", aiDetailsText.getText());
					clipboard.setPrimaryClip(clip);
					Toast.makeText(StorageMapActivity.this, "Summary copied to clipboard.", Toast.LENGTH_SHORT).show();
				}
			});

        closeButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					dialog.dismiss();
				}
			});

        dialog.show();
    }


    private int getFileCategory(String fileName) {
        String extension = "";
        int i = fileName.lastIndexOf('.');
        if (i > 0) {
            extension = fileName.substring(i + 1).toLowerCase(Locale.ROOT);
        }

        if (videoExtensions.contains(extension)) {
            return CATEGORY_VIDEOS;
        } else if (imageExtensions.contains(extension)) {
            return CATEGORY_IMAGES;
        } else if (audioExtensions.contains(extension)) {
            return CATEGORY_AUDIO;
        } else if (docExtensions.contains(extension)) {
            return CATEGORY_DOCS;
        } else {
            return CATEGORY_OTHER;
        }
    }

    private class ScanStorageTask extends AsyncTask<File, String, List<JSONObject>> {
        private final List<JSONArray> childrenArrays = new ArrayList<>();

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            for (int i = 0; i < categoryTitles.size(); i++) {
                childrenArrays.add(new JSONArray());
            }
        }

        @Override
        protected List<JSONObject> doInBackground(File... files) {
            scanDirectory(files[0]);

            List<JSONObject> finalResults = new ArrayList<>();
            for (int i = 0; i < categoryTitles.size(); i++) {
                try {
                    JSONObject categoryRoot = new JSONObject();
                    categoryRoot.put("name", categoryTitles.get(i));
                    categoryRoot.put("children", childrenArrays.get(i));

                    long totalSize = 0;
                    JSONArray children = childrenArrays.get(i);
                    for (int j = 0; j < children.length(); j++) {
                        totalSize += children.getJSONObject(j).getLong("value");
                    }
                    categoryRoot.put("value", totalSize);

                    finalResults.add(categoryRoot);
                } catch (JSONException e) {
                    Log.e(TAG, "Error building final JSON for category: " + categoryTitles.get(i), e);
                }
            }
            return finalResults;
        }

        private void scanDirectory(File directory) {
            if (directory == null || !directory.isDirectory() || directory.isHidden()) {
                return;
            }

            String path = directory.getAbsolutePath();
            if (path.contains("/Android/data") || path.contains("/Android/obb")) {
                return;
            }

            publishProgress(directory.getAbsolutePath());
            File[] fileList = directory.listFiles();
            if (fileList != null) {
                for (File file : fileList) {
                    if (file.isDirectory()) {
                        scanDirectory(file);
                    } else {
                        long fileSize = file.length();
                        if (fileSize > 0) {
                            try {
                                int category = getFileCategory(file.getName());
                                JSONObject fileObject = new JSONObject();
                                fileObject.put("name", file.getName());
                                fileObject.put("value", fileSize);
                                fileObject.put("path", file.getAbsolutePath());
                                childrenArrays.get(category).put(fileObject);
                            } catch (JSONException e) {
                                Log.e(TAG, "JSON Exception during file scan", e);
                            }
                        }
                    }
                }
            }
        }

        @Override
        protected void onProgressUpdate(String... values) {
            if (values.length > 0) scanPathText.setText(values[0]);
        }

        @Override
        protected void onPostExecute(final List<JSONObject> result) {
            loadingView.setVisibility(View.GONE);
            if (result != null && !result.isEmpty()) {
                categorizedData = result;
                currentCategoryIndex = 0;
                displayCurrentCategory();
                navigationBar.setVisibility(View.VISIBLE);
                webView.setVisibility(View.VISIBLE);
            } else {
                scanPathText.setText("Failed to analyze storage or no files found.");
            }
        }
    }
}
