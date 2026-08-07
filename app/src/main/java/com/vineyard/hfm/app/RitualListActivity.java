package com.vineyard.hfm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class RitualListActivity extends FragmentActivity {

    public static final String EXTRA_SELECTED_RITUAL = "selected_ritual";
    public static final String EXTRA_SELECTED_RITUAL_INDEX = "selected_ritual_index";

    private ImageButton closeButton;
    private RecyclerView recyclerView;
    private TextView emptyView;
    private Button hideMoreFilesButton; // NEW

    private List<RitualManager.Ritual> ritualList;
    private RitualListAdapter adapter;

    // --- NEW: Biometric variables ---
    private Executor executor;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.AuthenticationCallback authenticationCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ritual_list);

        initializeViews();
        setupListeners();
        setupRecyclerView();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Load or reload rituals every time the activity is shown
        loadRituals();
    }

    private void initializeViews() {
        closeButton = findViewById(R.id.close_button_ritual_list);
        recyclerView = findViewById(R.id.rituals_recycler_view);
        emptyView = findViewById(R.id.empty_view_ritual_list);
        hideMoreFilesButton = findViewById(R.id.hide_more_files_button); // NEW
    }

    private void setupListeners() {
        closeButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					finish();
				}
			});

        // NEW: Listener for the "Hide More Files" button
        hideMoreFilesButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					// This will launch the file selection screen, which now handles the choice
					// of creating a new ritual or adding to an existing one.
					Intent intent = new Intent(RitualListActivity.this, FileHiderActivity.class);
					startActivity(intent);
				}
			});
    }

    private void setupRecyclerView() {
        ritualList = new ArrayList<>();
        adapter = new RitualListAdapter(this, ritualList, new RitualListAdapter.OnRitualClickListener() {
				@Override
				public void onRitualClick(final int position) {
					// MODIFIED: Show a choice dialog instead of starting verification directly
					showRitualOptionsDialog(position);
				}
			});
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }

    private void showRitualOptionsDialog(final int position) {
        final CharSequence[] options = {"Unlock this Ritual", "Set/Change Map Fallback", "Cancel"};

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Ritual Options");
        builder.setItems(options, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					if (options[which].equals("Unlock this Ritual")) {
						startUnlockProcess(position);
					} else if (options[which].equals("Set/Change Map Fallback")) {
						startMapFallbackSetup(position);
					} else {
						dialog.dismiss();
					}
				}
			});
        builder.show();
    }

    private void startUnlockProcess(int position) {
        RitualManager.Ritual selectedRitual = ritualList.get(position);
        Intent intent = new Intent(RitualListActivity.this, RitualVerifyTapsActivity.class);
        intent.putExtra(EXTRA_SELECTED_RITUAL, selectedRitual);
        intent.putExtra(EXTRA_SELECTED_RITUAL_INDEX, position);
        startActivity(intent);
    }

    private void startMapFallbackSetup(final int position) {
        // For security, require fingerprint to change the fallback location
        executor = ContextCompat.getMainExecutor(this);
        authenticationCallback = new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                Toast.makeText(getApplicationContext(), "Authentication error: " + errString, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                // On success, launch the MapFallbackSetupActivity
                Intent intent = new Intent(RitualListActivity.this, MapFallbackSetupActivity.class);
                intent.putExtra(MapFallbackSetupActivity.EXTRA_RITUAL_INDEX_TO_UPDATE, position);
                startActivity(intent);
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                Toast.makeText(getApplicationContext(), "Authentication failed.", Toast.LENGTH_SHORT).show();
            }
        };

        biometricPrompt = new BiometricPrompt(this, executor, authenticationCallback);

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
            .setTitle("Authorize Change")
            .setSubtitle("Confirm fingerprint to set the secret location")
            .setNegativeButtonText("Cancel")
            .build();

        biometricPrompt.authenticate(promptInfo);
    }

    private void loadRituals() {
        RitualManager ritualManager = new RitualManager();
        List<RitualManager.Ritual> loadedRituals = ritualManager.loadRituals(this);

        if (loadedRituals == null || loadedRituals.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
            ritualList.clear();
            ritualList.addAll(loadedRituals);
            adapter.notifyDataSetChanged();
        }
    }
}

