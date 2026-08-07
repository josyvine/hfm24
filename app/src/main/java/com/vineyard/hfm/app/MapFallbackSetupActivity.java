package com.vineyard.hfm.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import java.util.List;

public class MapFallbackSetupActivity extends Activity {

    public static final String EXTRA_RITUAL_INDEX_TO_UPDATE = "ritual_index_to_update";

    private MapView mapView;
    private Button saveLocationButton;

    private int ritualIndexToUpdate = -1;
    private RitualManager ritualManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);

        // --- osmdroid Configuration ---
        Context ctx = getApplicationContext();
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));
        // --- End osmdroid Configuration ---

        setContentView(R.layout.activity_map_fallback_setup);

        ritualManager = new RitualManager();
        Intent intent = getIntent();
        ritualIndexToUpdate = intent.getIntExtra(EXTRA_RITUAL_INDEX_TO_UPDATE, -1);

        if (ritualIndexToUpdate == -1) {
            Toast.makeText(this, "Error: No ritual specified to update.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        initializeViews();
        setupMap();
        setupListeners();
    }

    private void initializeViews() {
        mapView = findViewById(R.id.map_view_fallback);
        saveLocationButton = findViewById(R.id.save_location_button);
    }

    private void setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(9.5);
        // Default to a central point if no location is available
        mapView.getController().setCenter(new GeoPoint(48.8583, 2.2944));
    }

    private void setupListeners() {
        saveLocationButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					saveSecretLocation();
				}
			});
    }

    private void saveSecretLocation() {
        GeoPoint mapCenter = (GeoPoint) mapView.getMapCenter();
        double latitude = mapCenter.getLatitude();
        double longitude = mapCenter.getLongitude();

        List<RitualManager.Ritual> rituals = ritualManager.loadRituals(this);

        if (rituals != null && ritualIndexToUpdate >= 0 && ritualIndexToUpdate < rituals.size()) {
            RitualManager.Ritual ritualToUpdate = rituals.get(ritualIndexToUpdate);
            ritualToUpdate.fallbackLatitude = latitude;
            ritualToUpdate.fallbackLongitude = longitude;

            ritualManager.saveRituals(this, rituals);

            Toast.makeText(this, "Secret Location Saved.", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            Toast.makeText(this, "Error: Could not find the ritual to update.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume(); //needed for compass, my location overlays, v6.0.0 and up
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();  //needed for compass, my location overlays, v6.0.0 and up
    }
}

