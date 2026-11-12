package com.example.tobira;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.location.LocationManagerCompat;
import androidx.lifecycle.ViewModelProvider;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.location.LocationComponentOptions;
import org.maplibre.android.location.modes.CameraMode;
import org.maplibre.android.location.modes.RenderMode;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.FeatureCollection;

import static org.maplibre.android.style.layers.PropertyFactory.*;

public class QuestActivity extends AppCompatActivity {

    public static final String EXTRA_PLAN = "quest_route_plan";
    public static final String EXTRA_NEXT_PLACE = "quest_next_place";

    private static final String ROUTE_SOURCE = "quest-route-source";
    private static final String ROUTE_LAYER  = "quest-route-layer";

    private MapView mapView;
    private MapLibreMap map;
    private Style mapStyle;
    private LocationManager locationManager;
    private Location currentLocation;
    private boolean resumed;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private RoutePlan routePlan;
    private Place nextPlace;

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            if (!resumed || mapStyle == null) return;
            currentLocation = location;
            if (map.getLocationComponent().isLocationComponentActivated()) {
                map.getLocationComponent().forceLocationUpdate(location);
            }
        }
        @Override public void onProviderDisabled(@NonNull String provider) {}
        @Override public void onProviderEnabled(@NonNull String provider) {}
        @Override public void onStatusChanged(String p, int s, Bundle e) {}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MapLibre.getInstance(this);

        routePlan = RoutePlan.fromBundle(getIntent().getBundleExtra(EXTRA_PLAN));
        nextPlace  = Place.fromBundle(getIntent().getBundleExtra(EXTRA_NEXT_PLACE));

        setContentView(R.layout.activity_quest);

        // ── Quest content ────────────────────────────────────────────────────
        TextView placeNameView = findViewById(R.id.quest_place_name);
        placeNameView.setText(nextPlace != null ? nextPlace.name : "—");

        QuestPanelViewModel questVm = new ViewModelProvider(this).get(QuestPanelViewModel.class);
        questVm.selectQuest(nextPlace);
        questVm.getState().observe(this, state -> {
            TextView desc = findViewById(R.id.quest_description);
            TextView obs  = findViewById(R.id.quest_observations);
            Button returnBtn = findViewById(R.id.quest_return_button);

            if (state.status == QuestPanelViewModel.Status.CONTENT) {
                desc.setText(state.introduction);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < state.observations.size(); i++) {
                    if (i > 0) sb.append("\n\n");
                    sb.append(i + 1).append(". ").append(state.observations.get(i));
                }
                obs.setText(sb.length() == 0
                        ? getString(R.string.quest_placeholder_observations) : sb.toString());
                returnBtn.setEnabled(true);
            } else {
                desc.setText(state.status == QuestPanelViewModel.Status.LOADING
                        ? R.string.quest_panel_loading : R.string.quest_placeholder_intro);
                obs.setText(R.string.quest_placeholder_observations);
                returnBtn.setEnabled(false);
            }
        });

        // Return-to-car: opens QuestChallengeDialog
        Button returnBtn = findViewById(R.id.quest_return_button);
        returnBtn.setOnClickListener(view -> {
            QuestPanelViewModel.State s = questVm.getState().getValue();
            if (s == null || s.status != QuestPanelViewModel.Status.CONTENT) return;
            if (getSupportFragmentManager().isStateSaved()) return;
            if (getSupportFragmentManager().findFragmentByTag(QuestChallengeDialog.TAG) != null) return;
            QuestChallengeDialog.create(s.questId, s.questVersion)
                    .showNow(getSupportFragmentManager(), QuestChallengeDialog.TAG);
        });

        // ── Tab navigation ───────────────────────────────────────────────────
        // Route tab: return to MapActivity
        findViewById(R.id.tab_route).setOnClickListener(v -> finish());
        // Quest tab: already here, no-op
        findViewById(R.id.tab_quest).setOnClickListener(v -> {});
        // User tab: open UserActivity
        findViewById(R.id.tab_user).setOnClickListener(v ->
                startActivity(new Intent(this, UserActivity.class)));

        // ── Navigation map (right half) ──────────────────────────────────────
        ImageButton locateBtn = findViewById(R.id.current_location);
        locateBtn.setOnClickListener(v -> {
            if (currentLocation != null && map != null) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                        new LatLng(currentLocation.getLatitude(), currentLocation.getLongitude()), 15));
            }
        });

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        mapView = findViewById(R.id.quest_map_view);
        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(readyMap -> {
            map = readyMap;
            map.setStyle(new Style.Builder().fromUri(getString(R.string.map_style_url)), style -> {
                if (isDestroyed()) return;
                mapStyle = style;
                // Route overlay
                mapStyle.addSource(new GeoJsonSource(ROUTE_SOURCE,
                        FeatureCollection.fromFeatures(new org.maplibre.geojson.Feature[0])));
                mapStyle.addLayer(new LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                        lineColor("#1565C0"), lineWidth(6f),
                        lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
                        lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)));

                // Draw route from parent RouteViewModel if available
                RouteViewModel routeVm = new ViewModelProvider(this).get(RouteViewModel.class);
                RouteViewModel.State rs = routeVm.currentState();
                if (rs.status == RouteViewModel.Status.READY && rs.route != null) {
                    GeoJsonSource src = mapStyle.getSourceAs(ROUTE_SOURCE);
                    if (src != null) src.setGeoJson(rs.route.geometry());
                }

                activateLocation();

                // Center on next waypoint
                if (nextPlace != null) {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(
                            new LatLng(nextPlace.latitude, nextPlace.longitude), 13));
                }
            });
        });
    }

    @SuppressLint("MissingPermission")
    private void activateLocation() {
        if (!hasLocationPermission() || mapStyle == null) return;
        if (!LocationManagerCompat.isLocationEnabled(locationManager)) return;
        try {
            if (!map.getLocationComponent().isLocationComponentActivated()) {
                map.getLocationComponent().activateLocationComponent(
                        LocationComponentActivationOptions.builder(this, mapStyle)
                                .useDefaultLocationEngine(false)
                                .locationComponentOptions(LocationComponentOptions.builder(this)
                                        .gpsDrawable(R.drawable.ic_vehicle_marker)
                                        .build())
                                .build());
            }
            map.getLocationComponent().setLocationComponentEnabled(true);
            map.getLocationComponent().setCameraMode(CameraMode.NONE);
            map.getLocationComponent().setRenderMode(RenderMode.GPS);

            for (String provider : locationManager.getProviders(true)) {
                if (LocationManager.PASSIVE_PROVIDER.equals(provider)) continue;
                if (LocationManager.GPS_PROVIDER.equals(provider) && !hasFinePermission()) continue;
                locationManager.requestLocationUpdates(provider, 2000L, 0f,
                        locationListener, Looper.getMainLooper());
            }
        } catch (Exception ignored) {}
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasFinePermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override protected void onStart()   { super.onStart();   mapView.onStart(); }
    @Override protected void onResume()  {
        super.onResume();
        resumed = true;
        mapView.onResume();
        if (hasLocationPermission() && mapStyle != null) activateLocation();
    }
    @Override protected void onPause()   {
        resumed = false;
        if (locationManager != null) {
            try { locationManager.removeUpdates(locationListener); } catch (SecurityException ignored) {}
        }
        mapView.onPause();
        super.onPause();
    }
    @Override protected void onStop()    { mapView.onStop();    super.onStop(); }
    @Override protected void onDestroy() { mapView.onDestroy(); super.onDestroy(); }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        mapView.onSaveInstanceState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }
}
