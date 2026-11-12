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
    /** True when opened during a trip (GO pressed): the map follows the car like navigation. */
    public static final String EXTRA_NAVIGATING = "quest_navigating";
    private static final String STOPS_SOURCE = "quest-stops-source";
    private static final String DESTINATION_SOURCE = "quest-destination-source";

    private static final String ROUTE_SOURCE = "quest-route-source";
    private static final String ROUTE_LAYER  = "quest-route-layer";
    /** Pause on the "封印完了" message before opening the next place's quest. */
    private static final long NEXT_QUEST_DELAY_MS = 2500;

    private MapView mapView;
    private MapLibreMap map;
    private Style mapStyle;
    private LocationManager locationManager;
    private Location currentLocation;
    private boolean resumed;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private RoutePlan routePlan;
    private Place nextPlace;
    private QuestArrivalViewModel arrivalModel;
    private boolean questHasContent;
    private QuestCommandPanel commandPanel;
    private boolean navigating;

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
        navigating = getIntent().getBooleanExtra(EXTRA_NAVIGATING, false);
        nextPlace  = Place.fromBundle(getIntent().getBundleExtra(EXTRA_NEXT_PLACE));

        setContentView(R.layout.activity_quest);
        arrivalModel = new ViewModelProvider(this).get(QuestArrivalViewModel.class);
        arrivalModel.selectTarget(nextPlace);
        arrivalModel.getState().observe(this, ignored -> renderArrival());
        new QuestArrivalTracker(this, arrivalModel);

        // ── Quest content ────────────────────────────────────────────────────
        TextView placeNameView = findViewById(R.id.quest_place_name);
        placeNameView.setText(nextPlace != null ? nextPlace.name : "—");

        QuestPanelViewModel questVm = new ViewModelProvider(this).get(QuestPanelViewModel.class);
        commandPanel = new QuestCommandPanel(this, () -> onQuestCommandsDone(questVm.getState().getValue()));
        questVm.selectQuest(nextPlace);
        questVm.getState().observe(this, state -> {
            TextView desc = findViewById(R.id.quest_description);
            TextView obs  = findViewById(R.id.quest_observations);
            TextView enemyStory = findViewById(R.id.enemy_story);

            if (state.status == QuestPanelViewModel.Status.CONTENT) {
                // Quest card = goal (first challenge); Enemy = enemy and story (introduction).
                if (state.goal.isEmpty()) {
                    desc.setText(compactParagraphs(state.introduction));
                    enemyStory.setText("");
                } else {
                    desc.setText(compactParagraphs(state.goal));
                    enemyStory.setText(compactParagraphs(state.introduction));
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < state.observations.size(); i++) {
                    if (i > 0) sb.append("\n\n");
                    sb.append(i + 1).append(". ").append(state.observations.get(i));
                }
                obs.setText(sb.length() == 0
                        ? getString(R.string.quest_placeholder_observations) : sb.toString());
                commandPanel.setCommands(state.commands);
                questHasContent = true;
                renderArrival();
            } else {
                desc.setText(state.status == QuestPanelViewModel.Status.LOADING
                        ? R.string.quest_panel_loading
                        : state.status == QuestPanelViewModel.Status.ERROR
                        ? R.string.catalog_error : R.string.quest_none);
                enemyStory.setText("");
                obs.setText(R.string.quest_placeholder_observations);
                commandPanel.setCommands(null);
                questHasContent = false;
                renderArrival();
            }
        });

        // Return-to-car: opens QuestChallengeDialog
        Button returnBtn = findViewById(R.id.quest_return_button);
        returnBtn.setOnClickListener(view -> {
            QuestPanelViewModel.State s = questVm.getState().getValue();
            if (s == null || s.status != QuestPanelViewModel.Status.CONTENT || !arrivalModel.canOpenChallenge()) return;
            if (getSupportFragmentManager().isStateSaved()) return;
            if (getSupportFragmentManager().findFragmentByTag(QuestChallengeDialog.TAG) != null) return;
            QuestChallengeDialog.create(s.questId, s.questVersion, nextPlace)
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
            if (navigating) {
                startNavigationCamera();
            } else if (currentLocation != null && map != null) {
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
                        lineColor("#42A5F5"), lineWidth(10f),
                        lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
                        lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)));

                // Draw the route built on the map screen (shared by RouteViewModel).
                DrivingRoute route = RouteViewModel.lastReadyRoute();
                if (route != null) {
                    GeoJsonSource src = mapStyle.getSourceAs(ROUTE_SOURCE);
                    if (src != null) src.setGeoJson(route.geometry());
                }
                addPlaceMarkers();

                activateLocation();

                // During a trip, follow the car (navigation). Otherwise show the whole route
                // (and the next waypoint); without a route, centre on the waypoint.
                if (navigating) {
                    startNavigationCamera();
                } else if (route != null && !route.points.isEmpty()) {
                    org.maplibre.android.geometry.LatLngBounds.Builder bounds =
                            new org.maplibre.android.geometry.LatLngBounds.Builder();
                    for (org.maplibre.geojson.Point point : route.points) {
                        bounds.include(new LatLng(point.latitude(), point.longitude()));
                    }
                    if (nextPlace != null) bounds.include(new LatLng(nextPlace.latitude, nextPlace.longitude));
                    int padding = (int) (32 * getResources().getDisplayMetrics().density);
                    mapView.post(() -> {
                        if (map != null && !isDestroyed()) {
                            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), padding));
                        }
                    });
                } else if (nextPlace != null) {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(
                            new LatLng(nextPlace.latitude, nextPlace.longitude), 13));
                }
            });
        });
    }

    /** Orange dots for the route's stops and a red dot for its destination (same as the map screen). */
    private void addPlaceMarkers() {
        if (mapStyle == null || routePlan == null) return;
        java.util.List<org.maplibre.geojson.Feature> stops = new java.util.ArrayList<>();
        for (Place stop : routePlan.stops) {
            stops.add(org.maplibre.geojson.Feature.fromGeometry(
                    org.maplibre.geojson.Point.fromLngLat(stop.longitude, stop.latitude)));
        }
        mapStyle.addSource(new GeoJsonSource(STOPS_SOURCE, FeatureCollection.fromFeatures(stops)));
        mapStyle.addLayer(new org.maplibre.android.style.layers.CircleLayer("quest-stop-points", STOPS_SOURCE)
                .withProperties(circleColor("#EF6C00"), circleRadius(8f),
                        circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)));
        java.util.List<org.maplibre.geojson.Feature> destination = new java.util.ArrayList<>();
        if (routePlan.destination != null) {
            destination.add(org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(
                    routePlan.destination.longitude, routePlan.destination.latitude)));
        }
        mapStyle.addSource(new GeoJsonSource(DESTINATION_SOURCE, FeatureCollection.fromFeatures(destination)));
        mapStyle.addLayer(new org.maplibre.android.style.layers.CircleLayer("quest-destination", DESTINATION_SOURCE)
                .withProperties(circleColor("#D32F2F"), circleRadius(10f),
                        circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)));
    }

    /** Navigation view: follow the car, zoomed in and tilted (same as the map screen's trip mode). */
    @SuppressLint("MissingPermission")
    private void startNavigationCamera() {
        if (map == null || mapStyle == null
                || !map.getLocationComponent().isLocationComponentActivated()) return;
        org.maplibre.android.location.LocationComponent component = map.getLocationComponent();
        if (component.getLastKnownLocation() == null && hasLocationPermission()) {
            Location last = null;
            for (String provider : locationManager.getProviders(true)) {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate != null && (last == null || candidate.getTime() > last.getTime())) last = candidate;
            }
            if (last != null) {
                currentLocation = last;
                component.forceLocationUpdate(last);
            }
        }
        component.setRenderMode(RenderMode.GPS);
        // Zoom 15 (one step wider than the full-width map screen) so the half-width map shows the
        // road ahead and the next stop's marker.
        component.setCameraMode(CameraMode.TRACKING_GPS, 750L, 15.0, null, 40.0, null);
    }

    /** All commands done: save the quest as cleared, then open the next place's quest screen. */
    private void onQuestCommandsDone(QuestPanelViewModel.State state) {
        if (state != null && state.questId != null) {
            com.example.tobira.data.QuestRepository repository =
                    com.example.tobira.data.QuestRepository.getInstance(this);
            repository.startQuest(state.questId, state.questVersion)
                    .thenCompose(ignored -> repository.savePhase(state.questId, state.questVersion,
                            com.example.tobira.data.QuestEntities.Progress.COMPLETED))
                    .whenComplete((ignored, error) -> {
                        if (error != null) android.util.Log.w("QuestActivity", "Cannot save cleared quest", error);
                    });
        }
        Place next = nextPlaceAfter(nextPlace);
        TextView instruction = findViewById(R.id.command_instruction);
        if (next == null) {
            instruction.setText(R.string.command_route_clear);
            return;
        }
        instruction.setText(getString(R.string.command_next_quest, next.name));
        handler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            startActivity(new Intent(this, QuestActivity.class)
                    .putExtra(EXTRA_PLAN, routePlan.toBundle())
                    .putExtra(EXTRA_NEXT_PLACE, next.toBundle())
                    .putExtra(EXTRA_NAVIGATING, navigating));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }, NEXT_QUEST_DELAY_MS);
    }

    /** The place after {@code current} in the route (stops in order, then the destination), or null. */
    private Place nextPlaceAfter(Place current) {
        if (routePlan == null || current == null) return null;
        java.util.List<Place> order = new java.util.ArrayList<>(routePlan.stops);
        if (routePlan.destination != null) order.add(routePlan.destination);
        for (int position = 0; position < order.size() - 1; position++) {
            Place place = order.get(position);
            boolean same = current.questId != null ? current.questId.equals(place.questId)
                    : place.latitude == current.latitude && place.longitude == current.longitude;
            if (same) return order.get(position + 1);
        }
        return null;
    }

    private void renderArrival() {
        QuestArrivalUi.render(findViewById(R.id.quest_arrival_status),
                findViewById(R.id.quest_return_button), arrivalModel.getState().getValue(), questHasContent);
    }

    /** Shrinks the blank line between paragraphs ("\n\n") to a small gap so less scrolling is needed. */
    private static CharSequence compactParagraphs(String text) {
        android.text.SpannableString spannable = new android.text.SpannableString(text);
        int index = text.indexOf("\n\n");
        while (index >= 0) {
            spannable.setSpan(new android.text.style.RelativeSizeSpan(0.35f), index + 1, index + 2,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            index = text.indexOf("\n\n", index + 2);
        }
        return spannable;
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
            if (!navigating) map.getLocationComponent().setCameraMode(CameraMode.NONE);
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
        commandPanel.resume();
    }
    @Override protected void onPause()   {
        resumed = false;
        if (locationManager != null) {
            try { locationManager.removeUpdates(locationListener); } catch (SecurityException ignored) {}
        }
        commandPanel.pause();
        mapView.onPause();
        super.onPause();
    }
    @Override protected void onStop()    { mapView.onStop();    super.onStop(); }
    @Override protected void onDestroy() {
        commandPanel.destroy();
        mapView.onDestroy();
        super.onDestroy();
    }

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
