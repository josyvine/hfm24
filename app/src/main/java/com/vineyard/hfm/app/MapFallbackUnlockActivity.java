package com.vineyard.hfm.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;

import java.io.Serializable;
import java.util.Locale;

public class MapFallbackUnlockActivity extends Activity {

    // You can adjust this tolerance. 100 meters is a reasonable area.
    private static final float LOCATION_TOLERANCE_METERS = 100.0f;

    private MapView mapView;
    private Button verifyLocationButton;
    private TextView currentCoordsText;
    private TextView matchStatusTextMap;

    private RitualManager.Ritual ritualToVerify;
    private int ritualIndex;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);

        // --- osmdroid Configuration ---
        Context ctx = getApplicationContext();
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));
        // --- End osmdroid Configuration ---

        setContentView(R.layout.activity_map_fallback_unlock);

        // Retrieve data from the previous activity
        Serializable ritualSerializable = getIntent().getSerializableExtra(RitualListActivity.EXTRA_SELECTED_RITUAL);
        ritualIndex = getIntent().getIntExtra(RitualListActivity.EXTRA_SELECTED_RITUAL_INDEX, -1);

        if (!(ritualSerializable instanceof RitualManager.Ritual) || ritualIndex == -1) {
            Toast.makeText(this, "Error: Incomplete data for map verification.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        ritualToVerify = (RitualManager.Ritual) ritualSerializable;

        initializeViews();
        setupMap();
        setupListeners();

        // Perform an initial check in case the map starts at the correct location
        checkLocationMatch();
    }

    private void initializeViews() {
        mapView = findViewById(R.id.map_view_fallback_unlock);
        verifyLocationButton = findViewById(R.id.verify_location_button);
        currentCoordsText = findViewById(R.id.current_coords_text);
        matchStatusTextMap = findViewById(R.id.match_status_text_map);
    }

    private void setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(3.0); // Start zoomed out
        mapView.getController().setCenter(new GeoPoint(20.0, 0.0)); // A neutral starting point

        // Add a listener that triggers after map movement stops
        mapView.addMapListener(new MapListener() {
				@Override
				public boolean onScroll(ScrollEvent event) {
					// The check is now done on ACTION_UP, so this can be empty
					return true;
				}

				@Override
				public boolean onZoom(ZoomEvent event) {
					// The check is now done on ACTION_UP, so this can be empty
					return true;
				}
			});

        // Use a custom touch listener to detect when the user lifts their finger
        mapView.setOnTouchListener(new View.OnTouchListener() {
				@Override
				public boolean onTouch(View v, android.view.MotionEvent event) {
					// When the user stops dragging/zooming, check the location
					if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
						checkLocationMatch();
					}
					// Return false to allow the map's default touch handling (like scrolling) to continue
					return false;
				}
			});
    }

    private void setupListeners() {
        verifyLocationButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					// The button is only enabled if there's a match,
					// so we can proceed directly to decryption.
					performMapFallbackDecryption();
				}
			});
    }

    private void checkLocationMatch() {
        if (ritualToVerify.fallbackLatitude == null || ritualToVerify.fallbackLongitude == null) {
            matchStatusTextMap.setText("Status: No Fallback Set");
            return;
        }

        // Get the location the user has currently selected on the map
        GeoPoint mapCenter = (GeoPoint) mapView.getMapCenter();
        currentCoordsText.setText(String.format(Locale.US, "Lat: %.4f, Lon: %.4f", mapCenter.getLatitude(), mapCenter.getLongitude()));

        Location selectedLocation = new Location("user_selected");
        selectedLocation.setLatitude(mapCenter.getLatitude());
        selectedLocation.setLongitude(mapCenter.getLongitude());

        // Get the stored secret location
        Location secretLocation = new Location("secret_fallback");
        secretLocation.setLatitude(ritualToVerify.fallbackLatitude);
        secretLocation.setLongitude(ritualToVerify.fallbackLongitude);

        // Check if the locations match within the tolerance
        float distance = selectedLocation.distanceTo(secretLocation);
        if (distance <= LOCATION_TOLERANCE_METERS) {
            matchStatusTextMap.setText("Status: MATCH FOUND!");
            matchStatusTextMap.setTextColor(Color.parseColor("#008000")); // Green
            verifyLocationButton.setEnabled(true);
        } else {
            matchStatusTextMap.setText("Status: Not Matching");
            matchStatusTextMap.setTextColor(Color.RED);
            verifyLocationButton.setEnabled(false);
        }
    }


    private void performMapFallbackDecryption() {
        Toast.makeText(this, "Location Verified. Unlocking files...", Toast.LENGTH_SHORT).show();

        // --- CRITICAL FIX: Use the ORIGINAL, UNMODIFIED ritual object for decryption. ---
        // The map check was simply the AUTHORIZATION to use the original key.
        // We do NOT create a "correctedRitual" here.
        RitualManager ritualManager = new RitualManager();
        ritualManager.verifyAndDecryptRitual(getApplicationContext(), ritualToVerify, ritualIndex);

        // Go all the way back to the main activity.
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }


    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
    }
}

