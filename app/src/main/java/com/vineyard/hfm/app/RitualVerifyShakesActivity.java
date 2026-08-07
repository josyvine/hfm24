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

import java.io.Serializable;

public class RitualVerifyShakesActivity extends Activity implements SensorEventListener {

    public static final String EXTRA_VERIFIED_SHAKE_COUNT = "verified_shake_count";

    private TextView shakeCountText, titleText;
    private Button resetButton, verifyButton;
    private ImageButton closeButton;

    private SensorManager sensorManager;
    private Sensor accelerometer;

    private int performedShakeCount = 0;
    private int verifiedTapCount = 0;
    private RitualManager.Ritual ritualToVerify;
    private int ritualIndex;

    // Shake detection parameters
    private static final float SHAKE_THRESHOLD_GRAVITY = 2.7F;
    private static final int SHAKE_SLOP_TIME_MS = 500;
    private long lastShakeTime;

    private final Handler shakeTimeoutHandler = new Handler();
    private final long SHAKE_TIMEOUT = 2000; // 2 seconds

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ritual_verify_shakes);

        // Retrieve data from the previous activity
        Serializable ritualSerializable = getIntent().getSerializableExtra(RitualListActivity.EXTRA_SELECTED_RITUAL);
        ritualIndex = getIntent().getIntExtra(RitualListActivity.EXTRA_SELECTED_RITUAL_INDEX, -1);
        verifiedTapCount = getIntent().getIntExtra(RitualVerifyTapsActivity.EXTRA_VERIFIED_TAP_COUNT, 0);

        if (ritualSerializable instanceof RitualManager.Ritual && ritualIndex != -1 && verifiedTapCount > 0) {
            ritualToVerify = (RitualManager.Ritual) ritualSerializable;
        } else {
            Toast.makeText(this, "Error: Incomplete data for verification.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        initializeViews();
        setupListeners();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer == null) {
                Toast.makeText(this, "Device accelerometer is not available.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void initializeViews() {
        shakeCountText = findViewById(R.id.shake_count_text_verify);
        resetButton = findViewById(R.id.reset_button_verify_shakes);
        verifyButton = findViewById(R.id.verify_button_shakes);
        closeButton = findViewById(R.id.close_button_ritual_verify_shakes);
        titleText = findViewById(R.id.title_text_verify_shakes);

        titleText.setText("Unlock Ritual #" + (ritualIndex + 1) + " (Step 2 of 3)");
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

        verifyButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					verifyShakes();
				}
			});
    }

    private void resetShakeCount() {
        performedShakeCount = 0;
        shakeCountText.setText(String.valueOf(performedShakeCount));
        verifyButton.setEnabled(false);
        verifyButton.setAlpha(0.5f);
        shakeTimeoutHandler.removeCallbacks(shakeTimeoutRunnable);
    }

    private void verifyShakes() {
        if (performedShakeCount == ritualToVerify.shakeCount) {
            Toast.makeText(this, "Step 2 Correct. Proceeding to final step.", Toast.LENGTH_SHORT).show();

            // --- MODIFICATION: Launch the orientation verification activity ---
            Intent intent = new Intent(RitualVerifyShakesActivity.this, RitualVerifyOrientationActivity.class);
            intent.putExtra(RitualListActivity.EXTRA_SELECTED_RITUAL, ritualToVerify);
            intent.putExtra(RitualListActivity.EXTRA_SELECTED_RITUAL_INDEX, ritualIndex);
            intent.putExtra(RitualVerifyTapsActivity.EXTRA_VERIFIED_TAP_COUNT, verifiedTapCount);
            intent.putExtra(EXTRA_VERIFIED_SHAKE_COUNT, performedShakeCount);
            startActivity(intent);

        } else {
            Toast.makeText(this, "Incorrect number of shakes. Please try again.", Toast.LENGTH_LONG).show();
            resetShakeCount();
        }
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
            float gForce = (float) Math.sqrt((x * x + y * y + z * z) / (SensorManager.GRAVITY_EARTH * SensorManager.GRAVITY_EARTH));

            if (gForce > SHAKE_THRESHOLD_GRAVITY) {
                final long now = System.currentTimeMillis();
                if (lastShakeTime + SHAKE_SLOP_TIME_MS > now) {
                    return;
                }
                lastShakeTime = now;
                handleShake();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void handleShake() {
        performedShakeCount++;
        shakeCountText.setText(String.valueOf(performedShakeCount));
        verifyButton.setEnabled(false);
        verifyButton.setAlpha(0.5f);
        shakeTimeoutHandler.removeCallbacks(shakeTimeoutRunnable);
        shakeTimeoutHandler.postDelayed(shakeTimeoutRunnable, SHAKE_TIMEOUT);
    }

    private final Runnable shakeTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (performedShakeCount > 0) {
                verifyButton.setEnabled(true);
                verifyButton.setAlpha(1.0f);
            }
        }
    };
}

