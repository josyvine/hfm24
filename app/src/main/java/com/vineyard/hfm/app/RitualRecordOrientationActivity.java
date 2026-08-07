package com.vineyard.hfm.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.RotateAnimation;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.Serializable;
import java.util.List;
import java.util.Locale;

public class RitualRecordOrientationActivity extends Activity implements SensorEventListener, LocationListener {

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 101;

    // UI Elements
    private ImageButton closeButton;
    private ImageView compassImage;
    private TextView degreeText, magnetometerText, locationText, lockStatusText;
    private Button copyDataButton, lockButton, finishButton;

    // Sensor & Location Managers
    private SensorManager sensorManager;
    private LocationManager locationManager;
    private Sensor accelerometer, magnetometer;

    // Data from previous activities
    private int tapCount;
    private int shakeCount;
    private List<File> filesToHide;

    // --- LIVE Sensor data arrays ---
    private final float[] accelerometerReading = new float[3];
    private final float[] magnetometerReading = new float[3];
    private final float[] rotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];
    private Location lastKnownLocation;

    // --- LOCKED Sensor data arrays (for encryption and copying) ---
    private final float[] lockedMagnetometerReading = new float[3];
    private Location lockedLocation;


    // State variables
    private float currentDegree = 0f;
    private boolean hasLocation = false;
    private boolean hasMagnetometer = false;
    private boolean isLocked = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ritual_record_orientation);

        // Retrieve data from the previous activities
        Serializable fileListSerializable = getIntent().getSerializableExtra(RitualRecordTapsActivity.EXTRA_FILES_TO_HIDE);
        tapCount = getIntent().getIntExtra(RitualRecordTapsActivity.EXTRA_TAP_COUNT, 0);
        shakeCount = getIntent().getIntExtra(RitualRecordShakesActivity.EXTRA_SHAKE_COUNT, 0);

        if (!(fileListSerializable instanceof List) || tapCount == 0 || shakeCount == 0) {
            Toast.makeText(this, "Error: Incomplete ritual data received.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        filesToHide = (List<File>) fileListSerializable;

        initializeViews();
        initializeSensors();
        setupListeners();

        checkAndRequestPermissions();
    }

    private void initializeViews() {
        closeButton = findViewById(R.id.close_button_ritual_orientation);
        compassImage = findViewById(R.id.compass_image);
        degreeText = findViewById(R.id.degree_text);
        magnetometerText = findViewById(R.id.magnetometer_text);
        locationText = findViewById(R.id.location_text);
        lockStatusText = findViewById(R.id.lock_status_text);
        copyDataButton = findViewById(R.id.copy_data_button);
        lockButton = findViewById(R.id.lock_button_orientation);
        finishButton = findViewById(R.id.finish_button_orientation);
    }

    private void initializeSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }

        if (accelerometer == null || magnetometer == null) {
            Toast.makeText(this, "Device does not support required orientation sensors.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void setupListeners() {
        closeButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					finish();
				}
			});

        lockButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					isLocked = true;

					// --- CRITICAL: Save a snapshot of the current sensor data ---
					System.arraycopy(magnetometerReading, 0, lockedMagnetometerReading, 0, magnetometerReading.length);
					lockedLocation = new Location(lastKnownLocation); // Create a copy

					// Stop listening to further sensor changes
					sensorManager.unregisterListener(RitualRecordOrientationActivity.this);
					locationManager.removeUpdates(RitualRecordOrientationActivity.this);

					// Update UI to reflect the locked state
					lockButton.setEnabled(false);
					lockButton.setText("Orientation Locked");
					lockStatusText.setText("Status: Locked");
					lockStatusText.setTextColor(Color.parseColor("#008000")); // Green

					// Show and enable the final buttons
					finishButton.setVisibility(View.VISIBLE);
					finishButton.setEnabled(true);
					copyDataButton.setEnabled(true);
					Toast.makeText(RitualRecordOrientationActivity.this, "Orientation Locked!", Toast.LENGTH_SHORT).show();
				}
			});

        copyDataButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					// --- FIX: Copy the LOCKED bit data, not the live display string ---
					if (!isLocked) {
						Toast.makeText(RitualRecordOrientationActivity.this, "You must lock the orientation first.", Toast.LENGTH_SHORT).show();
						return;
					}

					// Convert the LOCKED sensor data into their stable integer bit representations.
					int magXBits = Float.floatToIntBits(lockedMagnetometerReading[0]);
					int magYBits = Float.floatToIntBits(lockedMagnetometerReading[1]);
					int magZBits = Float.floatToIntBits(lockedMagnetometerReading[2]);
					long latBits = Double.doubleToLongBits(lockedLocation.getLatitude());
					long lonBits = Double.doubleToLongBits(lockedLocation.getLongitude());

					// Create a machine-readable string with this precise data.
					String dataToCopy = String.format(Locale.US, "Ritual Backup Bits:\nMag:%d,%d,%d\nLoc:%d,%d",
													  magXBits, magYBits, magZBits, latBits, lonBits);

					ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
					ClipData clip = ClipData.newPlainText("RitualBitData", dataToCopy);
					if (clipboard != null) {
						clipboard.setPrimaryClip(clip);
						Toast.makeText(RitualRecordOrientationActivity.this, "Precise backup data copied.", Toast.LENGTH_SHORT).show();
					}
				}
			});


        finishButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
                    if (!isLocked) {
                        Toast.makeText(RitualRecordOrientationActivity.this, "Please lock the orientation first.", Toast.LENGTH_SHORT).show();
                        return;
                    }

					RitualManager ritualManager = new RitualManager();
                    // --- FIX: Use the LOCKED data for creating the ritual ---
					ritualManager.createAndSaveRitual(
						getApplicationContext(),
						tapCount,
						shakeCount,
						lockedMagnetometerReading,
						lockedLocation,
						filesToHide
					);

					// Return to the main activity after starting the background task
					Intent intent = new Intent(RitualRecordOrientationActivity.this, MainActivity.class);
					intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
					startActivity(intent);
					finish();
				}
			});
    }

    private void checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST_CODE);
        } else {
            startLocationUpdates();
        }
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        if (locationManager != null) {
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 10, this);
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 10, this);
                Toast.makeText(this, "Acquiring location...", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Location provider is unavailable.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            } else {
                Toast.makeText(this, "Location permission is required for this feature.", Toast.LENGTH_LONG).show();
                hasLocation = false;
                updateButtonStates();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isLocked && sensorManager != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME);
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
        if (isLocked) return; // Do not update if locked

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.length);
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, magnetometerReading, 0, magnetometerReading.length);
            hasMagnetometer = true;
            magnetometerText.setText(String.format(Locale.US, "Magnetometer: X:%.2f Y:%.2f Z:%.2f", event.values[0], event.values[1], event.values[2]));
        }
        updateOrientationAngles();
        updateButtonStates();
    }

    public void updateOrientationAngles() {
        SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading);
        SensorManager.getOrientation(rotationMatrix, orientationAngles);

        float azimuthInRadians = orientationAngles[0];
        float azimuthInDegrees = (float) (Math.toDegrees(azimuthInRadians) + 360) % 360;

        RotateAnimation ra = new RotateAnimation(
            currentDegree,
            -azimuthInDegrees,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        );
        ra.setDuration(250);
        ra.setFillAfter(true);
        compassImage.startAnimation(ra);
        currentDegree = -azimuthInDegrees;

        String direction = getDirection(azimuthInDegrees);
        degreeText.setText(String.format(Locale.US, "%.0f° %s", azimuthInDegrees, direction));
    }

    private String getDirection(float degree) {
        if (degree >= 337.5 || degree < 22.5) return "N";
        if (degree >= 22.5 && degree < 67.5) return "NE";
        if (degree >= 67.5 && degree < 112.5) return "E";
        if (degree >= 112.5 && degree < 157.5) return "SE";
        if (degree >= 157.5 && degree < 202.5) return "S";
        if (degree >= 202.5 && degree < 247.5) return "SW";
        if (degree >= 247.5 && degree < 292.5) return "W";
        if (degree >= 292.5 && degree < 337.5) return "NW";
        return "";
    }


    @Override
    public void onLocationChanged(Location location) {
        if (isLocked) return; // Do not update if locked

        hasLocation = true;
        lastKnownLocation = location;
        locationText.setText(String.format(Locale.US, "Location (GPS): Lat:%.6f Lon:%.6f", location.getLatitude(), location.getLongitude()));
        updateButtonStates();
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onProviderEnabled(String provider) {
        Toast.makeText(this, provider + " enabled.", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onProviderDisabled(String provider) {
        Toast.makeText(this, provider + " disabled. Please enable location services.", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void updateButtonStates() {
        // Only enable the lock button when we have live data and are not yet locked
        if (hasLocation && hasMagnetometer && !isLocked) {
            lockButton.setEnabled(true);
        } else {
            lockButton.setEnabled(false);
        }
    }
}

