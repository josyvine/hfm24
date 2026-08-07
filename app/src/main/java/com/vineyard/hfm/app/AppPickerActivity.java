package com.vineyard.hfm.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class AppPickerActivity extends Activity {

    // UI Elements
    private ImageButton backButton;
    private TextView selectionCountTextView;
    private RecyclerView appRecyclerView;
    private Button sendButton;
    private LinearLayout loadingView;

    private AppPickerAdapter adapter;
    private List<ApplicationInfo> appList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_picker);

        initializeViews();
        setupRecyclerView();
        setupListeners();

        new LoadAppsTask().execute();
    }

    private void initializeViews() {
        backButton = findViewById(R.id.back_button_app_picker);
        selectionCountTextView = findViewById(R.id.selection_count_text_app_picker);
        appRecyclerView = findViewById(R.id.app_recycler_view);
        sendButton = findViewById(R.id.button_send_app_picker);
        loadingView = findViewById(R.id.loading_view_app_picker);
    }

    private void setupRecyclerView() {
        adapter = new AppPickerAdapter(this, appList, new AppPickerAdapter.OnItemClickListener() {
				@Override
				public void onSelectionChanged() {
					updateSelectionCount();
				}
			});
        appRecyclerView.setLayoutManager(new GridLayoutManager(this, 4)); // Using 4 columns for apps
        appRecyclerView.setAdapter(adapter);
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
					for (AppPickerAdapter.AppItem item : adapter.getItems()) {
						if (item.isSelected()) {
							// For apps, we send the path to their APK file
							selectedPaths.add(item.getAppInfo().sourceDir);
						}
					}

					if (selectedPaths.isEmpty()) {
						Toast.makeText(AppPickerActivity.this, "No apps selected.", Toast.LENGTH_SHORT).show();
						return;
					}

					Intent resultIntent = new Intent();
					resultIntent.putStringArrayListExtra("picked_files", selectedPaths);
					setResult(Activity.RESULT_OK, resultIntent);
					finish();
				}
			});
    }

    private void updateSelectionCount() {
        int count = 0;
        for (AppPickerAdapter.AppItem item : adapter.getItems()) {
            if (item.isSelected()) {
                count++;
            }
        }
        selectionCountTextView.setText(count + " apps selected");
    }

    private class LoadAppsTask extends AsyncTask<Void, Void, List<ApplicationInfo>> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            loadingView.setVisibility(View.VISIBLE);
            appRecyclerView.setVisibility(View.GONE);
        }

        @Override
        protected List<ApplicationInfo> doInBackground(Void... voids) {
            // --- THIS IS THE FIX ---
            // The PackageManager variable must be declared final to be used in the inner Comparator class.
            final PackageManager pm = getPackageManager();
            List<ApplicationInfo> allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<ApplicationInfo> installedApps = new ArrayList<>();

            for (ApplicationInfo appInfo : allApps) {
                // Filter out system apps
                if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    installedApps.add(appInfo);
                }
            }

            // Sort the apps alphabetically by name
            Collections.sort(installedApps, new Comparator<ApplicationInfo>() {
					@Override
					public int compare(ApplicationInfo a1, ApplicationInfo a2) {
						String label1 = pm.getApplicationLabel(a1).toString();
						String label2 = pm.getApplicationLabel(a2).toString();
						return label1.compareToIgnoreCase(label2);
					}
				});

            return installedApps;
        }

        @Override
        protected void onPostExecute(List<ApplicationInfo> result) {
            super.onPostExecute(result);
            loadingView.setVisibility(View.GONE);
            appRecyclerView.setVisibility(View.VISIBLE);

            if (result.isEmpty()) {
                Toast.makeText(AppPickerActivity.this, "No installed apps found.", Toast.LENGTH_LONG).show();
            } else {
                appList.clear();
                appList.addAll(result);
                adapter = new AppPickerAdapter(AppPickerActivity.this, appList, new AppPickerAdapter.OnItemClickListener() {
						@Override
						public void onSelectionChanged() {
							updateSelectionCount();
						}
					});
                appRecyclerView.setAdapter(adapter);
            }
        }
    }
}

