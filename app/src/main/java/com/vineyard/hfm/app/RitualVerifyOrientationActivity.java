package com.vineyard.hfm.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.RotateAnimation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricPrompt;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RitualVerifyOrientationActivity extends FragmentActivity implements SensorEventListener, LocationListener {

    // Tolerance values for matching the ritual
    private static final float MAGNETOMETER_TOLERANCE = 10.0f; // in microteslas (μT)
    private static final float LOCATION_TOLERANCE = 50.0f; // in meters

    // UI Elements
    private ImageButton closeButton;
    private ImageView compassImage;
    private TextView degreeText, magnetometerText, locationText, titleText, matchStatusText;
    private Button verifyButton;
    private Button useBackupDataButton;
    private Button unlockWithMapFallbackButton;

    // Sensor & Location Managers
    private SensorManager sensorManager;
    private LocationManager locationManager;
    private Sensor accelerometer, magnetometer;

    // Data from previous activities and the ritual to verify
    private RitualManager.Ritual ritualToVerify;
    private int ritualIndex;
    private int verifiedTapCount;
    private int verifiedShakeCount;

    // Sensor data arrays
    private final float[] accelerometerReading = new float[3];
    private final float[] magnetometerReading = new float[3];
    private final float[] rotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];
    private Location lastKnownLocation;

    // State variables
    private float currentDegree = 0f;

    // Biometric variables
    private Executor executor;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.AuthenticationCallback authenticationCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ritual_verify_orientation);

        // Retrieve data from the previous activity
        Serializable ritualSerializable = getIntent().getSerializableExtra(RitualListActivity.EXTRA_SELECTED_RITUAL);
        ritualIndex = getIntent().getIntExtra(RitualListActivity.EXTRA_SELECTED_RITUAL_INDEX, -1);
        verifiedTapCount = getIntent().getIntExtra(RitualVerifyTapsActivity.EXTRA_VERIFIED_TAP_COUNT, 0);
        verifiedShakeCount = getIntent().getIntExtra(RitualVerifyShakesActivity.EXTRA_VERIFIED_SHAKE_COUNT, 0);

        if (!(ritualSerializable instanceof RitualManager.Ritual) || ritualIndex == -1 || verifiedTapCount == 0 || verifiedShakeCount == 0) {
            Toast.makeText(this, "Error: Incomplete data for final verification step.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        ritualToVerify = (RitualManager.Ritual) ritualSerializable;

        initializeViews();
        initializeSensors();
        setupListeners();
        setupBiometricPrompt();

        checkLocationPermission();
    }

    private void initializeViews() {
        closeButton = findViewById(R.id.close_button_ritual_verify_orientation);
        compassImage = findViewById(R.id.compass_image_verify);
        degreeText = findViewById(R.id.degree_text_verify);
        magnetometerText = findViewById(R.id.magnetometer_text_verify);
        locationText = findViewById(R.id.location_text_verify);
        titleText = findViewById(R.id.title_text_verify_orientation);
        matchStatusText = findViewById(R.id.match_status_text);
        verifyButton = findViewById(R.id.verify_button_orientation);
        useBackupDataButton = findViewById(R.id.use_backup_data_button);
        unlockWithMapFallbackButton = findViewById(R.id.unlock_with_map_fallback_button);
        titleText.setText("Unlock Ritual #" + (ritualIndex + 1) + " (Step 3 of 3)");
    }

    private void initializeSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }
    }

    private void setupListeners() {
        closeButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					finish();
				}
			});

        useBackupDataButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					showBackupDataDialog();
				}
			});

        unlockWithMapFallbackButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					handleMapFallbackUnlock();
				}
			});

        verifyButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					// Call the RitualManager to start the decryption process
					RitualManager ritualManager = new RitualManager();
					ritualManager.verifyAndDecryptRitual(getApplicationContext(), ritualToVerify, ritualIndex);

					// Go all the way back to the main activity
					Intent intent = new Intent(RitualVerifyOrientationActivity.this, MainActivity.class);
					intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
					startActivity(intent);
					finish();
				}
			});
    }

    private void setupBiometricPrompt() {
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
                // --- FIX: On success, launch the MapFallbackUnlockActivity instead of decrypting directly ---
                Toast.makeText(getApplicationContext(), "Fingerprint Recognized. Please verify the location.", Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(RitualVerifyOrientationActivity.this, MapFallbackUnlockActivity.class);
                intent.putExtra(RitualListActivity.EXTRA_SELECTED_RITUAL, ritualToVerify);
                intent.putExtra(RitualListActivity.EXTRA_SELECTED_RITUAL_INDEX, ritualIndex);
                startActivity(intent);
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                Toast.makeText(getApplicationContext(), "Fingerprint not recognized.", Toast.LENGTH_SHORT).show();
            }
        };

        biometricPrompt = new BiometricPrompt(this, executor, authenticationCallback);
    }

    private void handleMapFallbackUnlock() {
        // 1. Check if a fallback location has been set
        if (ritualToVerify.fallbackLatitude == null || ritualToVerify.fallbackLongitude == null) {
            Toast.makeText(this, "No Map Fallback has been set for this ritual.", Toast.LENGTH_LONG).show();
            return;
        }

        // 2. If it exists, trigger the biometric prompt
        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
            .setTitle("Authorize Fallback Unlock")
            .setSubtitle("Confirm your fingerprint to use the secret location")
            .setNegativeButtonText("Cancel")
            .build();
        biometricPrompt.authenticate(promptInfo);
    }

    private void showBackupDataDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_enter_backup_data, null);
        final EditText backupDataInput = dialogView.findViewById(R.id.edit_text_backup_data);

        builder.setView(dialogView)
            .setPositiveButton("Unlock", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int id) {
                    String backupData = backupDataInput.getText().toString();
                    parseAndApplyBackupData(backupData);
                }
            })
            .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.cancel();
                }
            });
        builder.create().show();
    }

    private void parseAndApplyBackupData(String backupData) {
        if (backupData == null || backupData.trim().isEmpty()) {
            Toast.makeText(this, "Backup data cannot be empty.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Pattern magPattern = Pattern.compile("Mag:(-?\\d+),(-?\\d+),(-?\\d+)");
            Pattern locPattern = Pattern.compile("Loc:(-?\\d+),(-?\\d+)");

            Matcher magMatcher = magPattern.matcher(backupData);
            Matcher locMatcher = locPattern.matcher(backupData);

            if (!magMatcher.find() || !locMatcher.find()) {
                throw new IllegalArgumentException("Could not find Mag and Loc data in the correct format.");
            }

            // Parse the integer and long bits directly
            int magXBits = Integer.parseInt(magMatcher.group(1));
            int magYBits = Integer.parseInt(magMatcher.group(2));
            int magZBits = Integer.parseInt(magMatcher.group(3));
            long latBits = Long.parseLong(locMatcher.group(1));
            long lonBits = Long.parseLong(locMatcher.group(2));

            // Convert bits back to float/double to create the Location and float[] objects
            float[] backupMagnetometer = new float[]{
                Float.intBitsToFloat(magXBits),
                Float.intBitsToFloat(magYBits),
                Float.intBitsToFloat(magZBits)
            };
            Location backupLocation = new Location("backup");
            backupLocation.setLatitude(Double.longBitsToDouble(latBits));
            backupLocation.setLongitude(Double.longBitsToDouble(lonBits));

            // Create a new Ritual object using the perfectly reconstructed data.
            RitualManager.Ritual correctedRitual = new RitualManager.Ritual(
                ritualToVerify.tapCount,
                ritualToVerify.shakeCount,
                backupMagnetometer,
                backupLocation
            );

            // --- CRITICAL FIX: Transfer the list of files to be unhidden. ---
            correctedRitual.hiddenFiles = ritualToVerify.hiddenFiles;

            // Use this corrected ritual to perform decryption
            Toast.makeText(this, "Backup data applied. Unlocking...", Toast.LENGTH_SHORT).show();
            RitualManager ritualManager = new RitualManager();
            ritualManager.verifyAndDecryptRitual(getApplicationContext(), correctedRitual, ritualIndex);

            // Finish and return to main screen
            Intent intent = new Intent(RitualVerifyOrientationActivity.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();

        } catch (Exception e) {
            Toast.makeText(this, "Error parsing backup data. Please check the format.", Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    private void checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startSensorAndLocationUpdates();
        } else {
            Toast.makeText(this, "Location permission not granted.", Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressLint("MissingPermission")
    private void startSensorAndLocationUpdates() {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME);
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 5, this);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 5, this);
        } catch (Exception e) {
            Toast.makeText(this, "Could not start location updates.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        locationManager.removeUpdates(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.length);
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.length);
        }
        updateOrientationAndCheckMatch();
    }

    @Override
    public void onLocationChanged(Location location) {
        lastKnownLocation = location;
        updateOrientationAndCheckMatch();
    }

    private void updateOrientationAndCheckMatch() {
        // Update UI with current data
        magnetometerText.setText(String.format(Locale.US, "Magnetometer: X:%.2f Y:%.2f Z:%.2f", magnetometerReading[0], magnetometerReading[1], magnetometerReading[2]));
        if (lastKnownLocation != null) {
            locationText.setText(String.format(Locale.US, "Location (GPS): Lat:%.6f Lon:%.6f", lastKnownLocation.getLatitude(), lastKnownLocation.getLongitude()));
        }

        // Update compass
        SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading);
        SensorManager.getOrientation(rotationMatrix, orientationAngles);
        float azimuthInDegrees = (float) (Math.toDegrees(orientationAngles[0]) + 360) % 360;

        RotateAnimation ra = new RotateAnimation(currentDegree, -azimuthInDegrees, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        ra.setDuration(250);
        ra.setFillAfter(true);
        compassImage.startAnimation(ra);
        currentDegree = -azimuthInDegrees;
        degreeText.setText(String.format(Locale.US, "%.0f°", azimuthInDegrees));

        // Check for match
        boolean magMatch = isMagnetometerMatch();
        boolean locMatch = isLocationMatch();

        if (magMatch && locMatch) {
            matchStatusText.setText("Status: MATCH FOUND!");
            matchStatusText.setTextColor(Color.parseColor("#008000")); // Green
            verifyButton.setEnabled(true);
        } else {
            matchStatusText.setText("Status: Not Matching");
            matchStatusText.setTextColor(Color.RED);
            verifyButton.setEnabled(false);
        }
    }

    private boolean isMagnetometerMatch() {
        if (ritualToVerify.magnetometerData == null || magnetometerReading == null) return false;

        float deltaX = Math.abs(ritualToVerify.magnetometerData[0] - magnetometerReading[0]);
        float deltaY = Math.abs(ritualToVerify.magnetometerData[1] - magnetometerReading[1]);
        // --- THIS IS THE FIX FOR THE TYPO ---
        float deltaZ = Math.abs(ritualToVerify.magnetometerData[2] - magnetometerReading[2]);

        return deltaX < MAGNETOMETER_TOLERANCE && deltaY < MAGNETOMETER_TOLERANCE && deltaZ < MAGNETOMETER_TOLERANCE;
    }

    private boolean isLocationMatch() {
        if (lastKnownLocation == null) return false;

        Location targetLocation = new Location("ritualTarget");
        targetLocation.setLatitude(ritualToVerify.latitude);
        targetLocation.setLongitude(ritualToVerify.longitude);

        float distance = lastKnownLocation.distanceTo(targetLocation);
        return distance < LOCATION_TOLERANCE;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override
    public void onProviderEnabled(String provider) {}
    @Override
    public void onProviderDisabled(String provider) {}
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}

