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

import java.io.Serializable;

public class RitualVerifyTapsActivity extends Activity {

    public static final String EXTRA_VERIFIED_TAP_COUNT = "verified_tap_count";

    private RelativeLayout tapArea;
    private TextView tapCountText;
    private Button resetButton, verifyButton;
    private ImageButton closeButton;
    private TextView titleText;

    private int performedTapCount = 0;
    private RitualManager.Ritual ritualToVerify;
    private int ritualIndex;

    private final Handler tapTimeoutHandler = new Handler();
    private final long TAP_TIMEOUT = 1500; // 1.5 seconds

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ritual_verify_taps);

        // Retrieve the ritual object from the list activity
        Serializable ritualSerializable = getIntent().getSerializableExtra(RitualListActivity.EXTRA_SELECTED_RITUAL);
        ritualIndex = getIntent().getIntExtra(RitualListActivity.EXTRA_SELECTED_RITUAL_INDEX, -1);

        if (ritualSerializable instanceof RitualManager.Ritual && ritualIndex != -1) {
            ritualToVerify = (RitualManager.Ritual) ritualSerializable;
        } else {
            Toast.makeText(this, "Error: No ritual selected for verification.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        initializeViews();
        setupListeners();
    }

    private void initializeViews() {
        tapArea = findViewById(R.id.tap_area_verify);
        tapCountText = findViewById(R.id.tap_count_text_verify);
        resetButton = findViewById(R.id.reset_button_verify_taps);
        verifyButton = findViewById(R.id.verify_button_taps);
        closeButton = findViewById(R.id.close_button_ritual_verify_taps);
        titleText = findViewById(R.id.title_text_verify_taps);

        titleText.setText("Unlock Ritual #" + (ritualIndex + 1) + " (Step 1 of 3)");
    }

    private void setupListeners() {
        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
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

        verifyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                verifyTaps();
            }
        });
    }

    private void handleTap() {
        performedTapCount++;
        tapCountText.setText(String.valueOf(performedTapCount));

        verifyButton.setEnabled(false);
        verifyButton.setAlpha(0.5f);

        tapTimeoutHandler.removeCallbacks(tapTimeoutRunnable);
        tapTimeoutHandler.postDelayed(tapTimeoutRunnable, TAP_TIMEOUT);
    }

    private void resetTapCount() {
        performedTapCount = 0;
        tapCountText.setText(String.valueOf(performedTapCount));
        verifyButton.setEnabled(false);
        verifyButton.setAlpha(0.5f);
        tapTimeoutHandler.removeCallbacks(tapTimeoutRunnable);
    }

    private void verifyTaps() {
        if (performedTapCount == ritualToVerify.tapCount) {
            Toast.makeText(this, "Step 1 Correct. Proceeding to Step 2.", Toast.LENGTH_SHORT).show();

            // --- MODIFICATION: Launch the shakes verification activity ---
            Intent intent = new Intent(RitualVerifyTapsActivity.this, RitualVerifyShakesActivity.class);
            intent.putExtra(RitualListActivity.EXTRA_SELECTED_RITUAL, ritualToVerify);
            intent.putExtra(RitualListActivity.EXTRA_SELECTED_RITUAL_INDEX, ritualIndex);
            intent.putExtra(EXTRA_VERIFIED_TAP_COUNT, performedTapCount);
            startActivity(intent);

        } else {
            Toast.makeText(this, "Incorrect number of taps. Please try again.", Toast.LENGTH_LONG).show();
            resetTapCount();
        }
    }

    private final Runnable tapTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (performedTapCount > 0) {
                verifyButton.setEnabled(true);
                verifyButton.setAlpha(1.0f);
            }
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        tapTimeoutHandler.removeCallbacks(tapTimeoutRunnable);
    }
}

