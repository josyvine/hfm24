package com.vineyard.hfm.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.Serializable;
import java.util.List;

public class RitualRecordTapsActivity extends Activity {

    public static final String EXTRA_FILES_TO_HIDE = "files_to_hide";
    public static final String EXTRA_TAP_COUNT = "tap_count";

    private RelativeLayout tapArea;
    private TextView tapCountText;
    private Button resetButton, nextButton;
    private ImageButton closeButton;

    private int tapCount = 0;
    private List<File> filesToHide;

    // Use a handler to detect when the user has stopped tapping
    private final Handler tapTimeoutHandler = new Handler();
    private final long TAP_TIMEOUT = 1500; // 1.5 seconds

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ritual_record_taps);

        // Retrieve the list of files from the previous activity
        Serializable fileListSerializable = getIntent().getSerializableExtra(EXTRA_FILES_TO_HIDE);
        if (fileListSerializable instanceof List) {
            filesToHide = (List<File>) fileListSerializable;
        } else {
            Toast.makeText(this, "Error: File list not provided.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        initializeViews();
        setupListeners();
    }

    private void initializeViews() {
        tapArea = findViewById(R.id.tap_area);
        tapCountText = findViewById(R.id.tap_count_text);
        resetButton = findViewById(R.id.reset_button_taps);
        nextButton = findViewById(R.id.next_button_taps);
        closeButton = findViewById(R.id.close_button_ritual_taps);
    }

    private void setupListeners() {
        closeButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					// Should show a confirmation dialog before cancelling
					finish();
				}
			});

        tapArea.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					handleTap();
				}
			});

        resetButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					resetTapCount();
				}
			});

        nextButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (tapCount > 0) {
						// --- MODIFICATION: Proceed to the next step of ritual creation (Shakes) ---
						Intent intent = new Intent(RitualRecordTapsActivity.this, RitualRecordShakesActivity.class);
						intent.putExtra(EXTRA_FILES_TO_HIDE, (Serializable) filesToHide);
						intent.putExtra(EXTRA_TAP_COUNT, tapCount);
						startActivity(intent);
					} else {
						Toast.makeText(RitualRecordTapsActivity.this, "You must tap at least once.", Toast.LENGTH_SHORT).show();
					}
				}
			});
    }

    private void handleTap() {
        // Increment count and update UI
        tapCount++;
        tapCountText.setText(String.valueOf(tapCount));

        // Disable the next button while tapping is in progress
        nextButton.setEnabled(false);
        nextButton.setAlpha(0.5f);

        // Reset the timeout timer every time a tap occurs
        tapTimeoutHandler.removeCallbacks(tapTimeoutRunnable);
        tapTimeoutHandler.postDelayed(tapTimeoutRunnable, TAP_TIMEOUT);
    }

    private void resetTapCount() {
        tapCount = 0;
        tapCountText.setText(String.valueOf(tapCount));
        nextButton.setEnabled(false);
        nextButton.setAlpha(0.5f);
        tapTimeoutHandler.removeCallbacks(tapTimeoutRunnable);
    }

    // This runnable will be executed when the user stops tapping for the specified timeout duration
    private final Runnable tapTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (tapCount > 0) {
                // Enable the 'Next' button once tapping has ceased
                nextButton.setEnabled(true);
                nextButton.setAlpha(1.0f);
                Toast.makeText(RitualRecordTapsActivity.this, "Tap sequence recorded. Press Next to continue.", Toast.LENGTH_SHORT).show();
            }
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        // Clean up the handler to prevent memory leaks
        tapTimeoutHandler.removeCallbacks(tapTimeoutRunnable);
    }
}

