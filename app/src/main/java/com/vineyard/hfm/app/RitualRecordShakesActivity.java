package com.vineyard.hfm.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;

public class RitualRecordShakesActivity extends Activity implements SensorEventListener {

    public static final String EXTRA_SHAKE_COUNT = "shake_count";

    private TextView shakeCountText;
    private Button resetButton, nextButton;
    private ImageButton closeButton;

    private SensorManager sensorManager;
    private Sensor accelerometer;

    private int tapCount = 0;
    private int shakeCount = 0;
    private List<File> filesToHide;

    // Shake detection parameters
    private static final float SHAKE_THRESHOLD_GRAVITY = 2.7F;
    private static final int SHAKE_SLOP_TIME_MS = 500;
    private long lastShakeTime;

    // Handler to detect when shaking has stopped
    private final Handler shakeTimeoutHandler = new Handler();
    private final long SHAKE_TIMEOUT = 2000; // 2 seconds

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ritual_record_shakes);

        // Retrieve data from the previous activity (Taps)
        Serializable fileListSerializable = getIntent().getSerializableExtra(RitualRecordTapsActivity.EXTRA_FILES_TO_HIDE);
        tapCount = getIntent().getIntExtra(RitualRecordTapsActivity.EXTRA_TAP_COUNT, 0);

        if (fileListSerializable instanceof List && tapCount > 0) {
            filesToHide = (List<File>) fileListSerializable;
        } else {
            Toast.makeText(this, "Error: Ritual data not provided correctly.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        initializeViews();
        setupListeners();

        // Initialize sensor manager
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer == null) {
                Toast.makeText(this, "Device does not have an accelerometer. This feature is unavailable.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void initializeViews() {
        shakeCountText = findViewById(R.id.shake_count_text);
        resetButton = findViewById(R.id.reset_button_shakes);
        nextButton = findViewById(R.id.next_button_shakes);
        closeButton = findViewById(R.id.close_button_ritual_shakes);
    }

    private void setupListeners() {
        closeButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					finish();
				}
			});

        resetButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					resetShakeCount();
				}
			});

        nextButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (shakeCount > 0) {
						// --- MODIFICATION: Proceed to the next step of ritual creation (Orientation) ---
						Intent intent = new Intent(RitualRecordShakesActivity.this, RitualRecordOrientationActivity.class);
						intent.putExtra(RitualRecordTapsActivity.EXTRA_FILES_TO_HIDE, (Serializable) filesToHide);
						intent.putExtra(RitualRecordTapsActivity.EXTRA_TAP_COUNT, tapCount);
						intent.putExtra(EXTRA_SHAKE_COUNT, shakeCount);
						startActivity(intent);
					} else {
						Toast.makeText(RitualRecordShakesActivity.this, "You must shake the device at least once.", Toast.LENGTH_SHORT).show();
					}
				}
			});
    }

    private void resetShakeCount() {
        shakeCount = 0;
        shakeCountText.setText(String.valueOf(shakeCount));
        nextButton.setEnabled(false);
        nextButton.setAlpha(0.5f);
        shakeTimeoutHandler.removeCallbacks(shakeTimeoutRunnable);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        shakeTimeoutHandler.removeCallbacks(shakeTimeoutRunnable);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            float gX = x / SensorManager.GRAVITY_EARTH;
            float gY = y / SensorManager.GRAVITY_EARTH;
            float gZ = z / SensorManager.GRAVITY_EARTH;

            // gForce will be close to 1 when there is no movement.
            float gForce = (float) Math.sqrt(gX * gX + gY * gY + gZ * gZ);

            if (gForce > SHAKE_THRESHOLD_GRAVITY) {
                final long now = System.currentTimeMillis();
                // ignore shake events too close to each other (500ms)
                if (lastShakeTime + SHAKE_SLOP_TIME_MS > now) {
                    return;
                }
                lastShakeTime = now;

                handleShake();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used for this implementation
    }

    private void handleShake() {
        shakeCount++;
        shakeCountText.setText(String.valueOf(shakeCount));

        nextButton.setEnabled(false);
        nextButton.setAlpha(0.5f);

        // Reset the timeout handler
        shakeTimeoutHandler.removeCallbacks(shakeTimeoutRunnable);
        shakeTimeoutHandler.postDelayed(shakeTimeoutRunnable, SHAKE_TIMEOUT);
    }

    private final Runnable shakeTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (shakeCount > 0) {
                nextButton.setEnabled(true);
                nextButton.setAlpha(1.0f);
                Toast.makeText(RitualRecordShakesActivity.this, "Shake sequence recorded. Press Next to continue.", Toast.LENGTH_SHORT).show();
            }
        }
    };
}

