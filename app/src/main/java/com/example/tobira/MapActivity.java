package com.example.tobira;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.location.LocationManagerCompat;
import androidx.lifecycle.ViewModelProvider;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.location.LocationComponentOptions;
import org.maplibre.android.location.OnCameraTrackingChangedListener;
import org.maplibre.android.location.modes.CameraMode;
import org.maplibre.android.location.modes.RenderMode;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.module.http.HttpRequestUtil;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Point;

import static org.maplibre.android.style.layers.PropertyFactory.*;

import java.io.File;
import okhttp3.Cache;
import okhttp3.OkHttpClient;

public class MapActivity extends AppCompatActivity {
    private static final String STATE_DESTINATION = "destination";
    private static final String STATE_PLAN = "route-plan";
    private static final String START_SOURCE = "start-source";
    private static final String STOPS_SOURCE = "stops-source";
    private RoutePlan routePlan = new RoutePlan();
    private static final String STATE_TRIP = "trip-session";
    private final TripSession trip = new TripSession();
    private final VehiclePosition vehiclePosition = new VehiclePosition();
    private Button tripButton;
    private TextView tripStatus;
    private static final String DESTINATION_SOURCE = "destination-source";
    private static final String DESTINATION_LAYER = "destination-layer";
    private static final String ROUTE_SOURCE = "route-source";
    private static final String ROUTE_LAYER = "route-layer";
    private static final String STATE_ROUTE_CAMERA = "route-camera";
    private RouteViewModel routeViewModel;
    private Button routeButton;
    private TextView routeSummary;
    private Location currentLocation;
    private int fittedRouteId = -1;
    private static boolean httpConfigured;
    private Place destination;
    private boolean centerOnDestination;
    private TextView destinationLabel;
    private View clearDestinationButton;
    private final ActivityResultLauncher<Intent> planEditorLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                RoutePlan selected = RoutePlan.fromBundle(
                        result.getData().getBundleExtra(RouteEditorActivity.EXTRA_PLAN));
                if (!selected.isComplete()) return;
                endTrip();
                routeViewModel.clear();
                routePlan = selected;
                destination = routePlan.destination;
                centerOnNextFix = false;
                centerOnDestination = true;
                renderDestination();
            });
    private MapView mapView;
    private MapLibreMap map;
    private Style mapStyle;
    private TextView status;
    private Button locateButton;
    private View retryButton;
    private LocationManager locationManager;
    private boolean resumed;
    private boolean centerOnNextFix = true;
    private boolean receivedFix;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable locationTimeout = () -> {
        if (!receivedFix) status.setText(R.string.location_waiting);
        renderTripControls();
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (hasLocationPermission()) {
                    startLocationUpdates();
                } else {
                    status.setText(R.string.location_denied);
                    if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
                            && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        showAppSettings();
                    }
                }
            });

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            if (!resumed || mapStyle == null || !hasLocationPermission()) return;
            Location vehicle = vehiclePosition.accept(location);
            if (vehicle == null) return;
            currentLocation = vehicle;
            renderPlanPoints();
            updateRouteControls();
            receivedFix = true;
            handler.removeCallbacks(locationTimeout);
            map.getLocationComponent().forceLocationUpdate(vehicle);
            applyTripMode(false);
            status.setText(hasFinePermission() ? R.string.location_ready : R.string.location_approximate);
            if (centerOnNextFix && !trip.isActive()) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                        new LatLng(location.getLatitude(), location.getLongitude()),
                        hasFinePermission() ? 15 : 12));
                centerOnNextFix = false;
            }
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
                status.setText(R.string.location_disabled);
                currentLocation = null;
                renderTripControls();
            }
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
            startLocationUpdates();
        }

        @Override
        public void onStatusChanged(String provider, int providerStatus, Bundle extras) {
            // Required on devices below API 30.
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MapLibre.getInstance(this);
        configureMapHttp();
        if (savedInstanceState != null) {
            routePlan = RoutePlan.fromBundle(savedInstanceState.getBundle(STATE_PLAN));
            if (!savedInstanceState.containsKey(STATE_PLAN)) {
                routePlan.destination = Place.fromBundle(savedInstanceState.getBundle(STATE_DESTINATION));
            }
            destination = routePlan.destination;
            centerOnNextFix = destination == null;
            fittedRouteId = savedInstanceState.getInt(STATE_ROUTE_CAMERA, -1);
        }
        setContentView(R.layout.activity_map);
        destinationLabel = findViewById(R.id.destination_label);
        routeButton = findViewById(R.id.build_route_button);
        routeSummary = findViewById(R.id.route_summary);
        routeViewModel = new ViewModelProvider(this).get(RouteViewModel.class);
        routeButton.setOnClickListener(view -> buildRoute());
        tripButton = findViewById(R.id.trip_button);
        tripStatus = findViewById(R.id.trip_status);
        if (savedInstanceState != null) {
            trip.restore(savedInstanceState.getBundle(STATE_TRIP),
                    routeViewModel.currentState().status == RouteViewModel.Status.READY);
        }
        tripButton.setOnClickListener(view -> {
            if (trip.isActive()) endTrip();
            else startTrip();
        });
        routeViewModel.getState().observe(this, state -> {
            if (trip.isActive() && state.status != RouteViewModel.Status.READY) endTrip();
            renderRoute();
        });
        clearDestinationButton = findViewById(R.id.clear_destination_button);
        findViewById(R.id.search_places_button).setOnClickListener(view ->
                planEditorLauncher.launch(new Intent(this, RouteEditorActivity.class)
                        .putExtra(RouteEditorActivity.EXTRA_PLAN, routePlan.toBundle())));
        clearDestinationButton.setOnClickListener(view -> {
            endTrip();
            routeViewModel.clear();
            routePlan = new RoutePlan();
            destination = null;
            centerOnDestination = false;
            renderDestination();
        });
        status = findViewById(R.id.map_status);
        locateButton = findViewById(R.id.my_location_button);
        retryButton = findViewById(R.id.retry_button);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        ((TextView) findViewById(R.id.map_attribution)).setMovementMethod(LinkMovementMethod.getInstance());
        findViewById(R.id.back_button).setOnClickListener(view -> finish());
        locateButton.setOnClickListener(view -> {
            if (trip.isActive()) {
                trip.resumeTracking();
                if (!hasFreshLocation()) requestLocation();
                applyTripMode(true);
                renderTripControls();
            } else {
                centerOnNextFix = true;
                requestLocation();
            }
        });
        retryButton.setOnClickListener(view -> loadMap());
        mapView = findViewById(R.id.map_view);
        mapView.onCreate(savedInstanceState);
        mapView.addOnDidFailLoadingMapListener(error -> {
            status.setText(R.string.map_error);
            retryButton.setVisibility(View.VISIBLE);
        });
        mapView.getMapAsync(readyMap -> {
            map = readyMap;
            loadMap();
        });
    }

    private void configureMapHttp() {
        // Use one HTTP cache across Activity recreation; honour server cache headers.
        if (httpConfigured) return;
        OkHttpClient client = new OkHttpClient.Builder()
                .cache(new Cache(new File(getCacheDir(), "map-tiles"), 50L * 1024 * 1024))
                .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                        .header("User-Agent", "Tobira/1.0 (com.example.tobira)")
                        .build()))
                .build();
        HttpRequestUtil.setOkHttpClient(client);
        httpConfigured = true;
    }

    private void loadMap() {
        mapStyle = null;
        stopLocationUpdates();
        locateButton.setEnabled(false);
        retryButton.setVisibility(View.GONE);
        status.setText(R.string.map_loading);
        map.setStyle(new Style.Builder().fromUri(getString(R.string.map_style_url)), style -> {
            if (isDestroyed()) return;
            mapStyle = style;
            mapStyle.addSource(new GeoJsonSource(DESTINATION_SOURCE,
                    FeatureCollection.fromFeatures(new org.maplibre.geojson.Feature[0])));
            mapStyle.addLayer(new CircleLayer(DESTINATION_LAYER, DESTINATION_SOURCE).withProperties(
                    circleColor("#D32F2F"), circleRadius(10f),
                    circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)));
            mapStyle.addSource(new GeoJsonSource(ROUTE_SOURCE,
                    FeatureCollection.fromFeatures(new org.maplibre.geojson.Feature[0])));
            mapStyle.addLayerBelow(new LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                    lineColor("#1565C0"), lineWidth(6f), lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
                    lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)),
                    DESTINATION_LAYER);
            mapStyle.addSource(new GeoJsonSource(START_SOURCE,
                    FeatureCollection.fromFeatures(new org.maplibre.geojson.Feature[0])));
            mapStyle.addLayer(new CircleLayer("start-point", START_SOURCE).withProperties(
                    circleColor("#2E7D32"), circleRadius(9f),
                    circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)));
            mapStyle.addSource(new GeoJsonSource(STOPS_SOURCE,
                    FeatureCollection.fromFeatures(new org.maplibre.geojson.Feature[0])));
            mapStyle.addLayer(new CircleLayer("stop-points", STOPS_SOURCE).withProperties(
                    circleColor("#EF6C00"), circleRadius(8f),
                    circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)));
            renderDestination();
            renderRoute();
            locateButton.setEnabled(true);
            status.setText(R.string.location_denied);
            requestLocation();
        });
    }

    private void renderDestination() {
        boolean selected = destination != null;
        destinationLabel.setVisibility(selected ? View.VISIBLE : View.GONE);
        clearDestinationButton.setVisibility(selected ? View.VISIBLE : View.GONE);
        if (selected) {
            String startName = routePlan.useCurrentLocation ? getString(R.string.current_start)
                    : routePlan.start.name;
            destinationLabel.setText(getString(R.string.route_plan_summary,
                    startName, routePlan.stops.size(), destination.name));
        }
        updateRouteControls();
        renderPlanPoints();
        if (mapStyle == null) return;
        GeoJsonSource source = mapStyle.getSourceAs(DESTINATION_SOURCE);
        if (source == null) return;
        if (!selected) {
            source.setGeoJson(FeatureCollection.fromFeatures(new org.maplibre.geojson.Feature[0]));
            return;
        }
        source.setGeoJson(Point.fromLngLat(destination.longitude, destination.latitude));
        if (centerOnDestination && resumed && !trip.isActive() && mapView.getWidth() > 0) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    new LatLng(destination.latitude, destination.longitude), 15));
            centerOnDestination = false;
        }
    }

    private boolean hasFreshLocation() {
        return currentLocation != null && hasLocationPermission()
                && LocationManagerCompat.isLocationEnabled(locationManager)
                && android.os.SystemClock.elapsedRealtimeNanos()
                - currentLocation.getElapsedRealtimeNanos() <= 120_000_000_000L;
    }

    private void buildRoute() {
        if (!routePlan.isComplete() || mapStyle == null) return;
        if (routePlan.useCurrentLocation && !hasFreshLocation()) {
            currentLocation = null;
            routeSummary.setText(R.string.route_need_location);
            routeSummary.setVisibility(View.VISIBLE);
            requestLocation();
            return;
        }
        Place current = routePlan.useCurrentLocation
                ? new Place(getString(R.string.current_start), "", currentLocation.getLatitude(),
                        currentLocation.getLongitude()) : null;
        centerOnNextFix = false;
        routeViewModel.build(getString(R.string.route_endpoint), routePlan.resolve(current));
    }

    private void renderPlanPoints() {
        if (mapStyle == null) return;
        GeoJsonSource startSource = mapStyle.getSourceAs(START_SOURCE);
        GeoJsonSource stopsSource = mapStyle.getSourceAs(STOPS_SOURCE);
        if (startSource == null || stopsSource == null) return;
        Place start = routePlan.start;
        if (!routePlan.useCurrentLocation && start != null) {
            startSource.setGeoJson(Point.fromLngLat(start.longitude, start.latitude));
        } else {
            startSource.setGeoJson(FeatureCollection.fromFeatures(
                    new org.maplibre.geojson.Feature[0]));
        }
        java.util.List<org.maplibre.geojson.Feature> features = new java.util.ArrayList<>();
        for (Place stop : routePlan.stops) {
            features.add(org.maplibre.geojson.Feature.fromGeometry(
                    Point.fromLngLat(stop.longitude, stop.latitude)));
        }
        stopsSource.setGeoJson(FeatureCollection.fromFeatures(features));
    }

    private void updateRouteControls() {
        if (routeButton == null || routeViewModel == null) return;
        RouteViewModel.State state = routeViewModel.currentState();
        routeButton.setVisibility(destination == null || trip.isActive() ? View.GONE : View.VISIBLE);
        routeButton.setEnabled(destination != null && mapStyle != null
                && !getString(R.string.route_endpoint).isEmpty()
                && state.status != RouteViewModel.Status.LOADING);
        if (destination == null) {
            routeSummary.setVisibility(View.GONE);
        } else if (state.status == RouteViewModel.Status.IDLE
                && routePlan.useCurrentLocation && !hasFreshLocation()) {
            routeSummary.setText(R.string.route_need_location);
            routeSummary.setVisibility(View.VISIBLE);
        } else if (state.status == RouteViewModel.Status.IDLE) {
            routeSummary.setVisibility(View.GONE);
        }
        renderTripControls();
    }

    private void renderRoute() {
        updateRouteControls();
        RouteViewModel.State state = routeViewModel.currentState();
        if (destination == null) {
            if (mapStyle != null) {
                GeoJsonSource source = mapStyle.getSourceAs(ROUTE_SOURCE);
                if (source != null) source.setGeoJson(
                        FeatureCollection.fromFeatures(new org.maplibre.geojson.Feature[0]));
            }
            return;
        }
        switch (state.status) {
            case IDLE -> { }
            case LOADING -> routeSummary.setText(R.string.route_loading);
            case NO_ROUTE -> routeSummary.setText(R.string.route_no_route);
            case ERROR -> routeSummary.setText(R.string.route_error);
            case RATE_LIMITED -> routeSummary.setText(R.string.route_rate_limited);
            case READY -> routeSummary.setText(getString(R.string.route_summary,
                    state.route.distanceMeters / 1000.0,
                    Math.max(1, (int) Math.ceil(state.route.durationSeconds / 60.0))));
        }
        if (state.status != RouteViewModel.Status.IDLE) routeSummary.setVisibility(View.VISIBLE);
        if (mapStyle == null) return;
        GeoJsonSource source = mapStyle.getSourceAs(ROUTE_SOURCE);
        if (source == null) return;
        if (state.route == null) {
            source.setGeoJson(FeatureCollection.fromFeatures(new org.maplibre.geojson.Feature[0]));
            return;
        }
        source.setGeoJson(state.route.geometry());
        if (resumed && !trip.isActive() && mapView.getWidth() > 0 && fittedRouteId != state.requestId) {
            LatLngBounds.Builder bounds = new LatLngBounds.Builder();
            for (Point point : state.route.points) {
                bounds.include(new LatLng(point.latitude(), point.longitude()));
            }
            int padding = (int) (32 * getResources().getDisplayMetrics().density);
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), padding));
            fittedRouteId = state.requestId;
        }
    }

    private void startTrip() {
        if (routeViewModel.currentState().status != RouteViewModel.Status.READY) return;
        if (!hasFreshLocation()) {
            status.setText(R.string.route_need_location);
            requestLocation();
            return;
        }
        trip.start(true, true);
        centerOnNextFix = false;
        centerOnDestination = false;
        map.getLocationComponent().forceLocationUpdate(currentLocation);
        applyTripMode(true);
        renderTripControls();
    }

    private void endTrip() {
        trip.stop();
        applyTripMode(false);
        renderTripControls();
    }

    private void renderTripControls() {
        if (tripButton == null || locateButton == null) return;
        boolean ready = routeViewModel.currentState().status == RouteViewModel.Status.READY;
        boolean active = trip.isActive();
        tripButton.setVisibility(ready || active ? View.VISIBLE : View.GONE);
        tripButton.setText(active ? R.string.end_trip : R.string.start_trip);
        tripStatus.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) {
            tripStatus.setText(!hasFreshLocation() ? R.string.trip_waiting_gps
                    : trip.isFollowing() ? R.string.trip_following : R.string.trip_browsing);
        }
        locateButton.setText(active ? R.string.follow_vehicle : R.string.my_location);
        findViewById(R.id.search_places_button).setEnabled(!active);
        routeButton.setVisibility(destination == null || active ? View.GONE : View.VISIBLE);
        clearDestinationButton.setVisibility(destination == null || active ? View.GONE : View.VISIBLE);
        destinationLabel.setVisibility(destination == null || active ? View.GONE : View.VISIBLE);
        status.setVisibility(active ? View.GONE : View.VISIBLE);
        if (mapView != null) mapView.setKeepScreenOn(active && resumed);
    }

    private void applyTripMode(boolean recenter) {
        if (map == null || mapStyle == null
                || !map.getLocationComponent().isLocationComponentActivated()) return;
        org.maplibre.android.location.LocationComponent component = map.getLocationComponent();
        component.setRenderMode(trip.isActive() ? RenderMode.GPS : RenderMode.NORMAL);
        int mode = trip.isFollowing() ? CameraMode.TRACKING_GPS : CameraMode.NONE;
        if (recenter && trip.isFollowing() && hasFreshLocation()) {
            component.setCameraMode(mode, 750L, 17.0, null, 40.0, null);
        } else if (component.getCameraMode() != mode) {
            component.setCameraMode(mode);
        }
    }

    private boolean hasFinePermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPermission() {
        return hasFinePermission()
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocation() {
        if (!resumed || mapStyle == null) return;
        if (hasLocationPermission()) {
            if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
                status.setText(R.string.location_disabled);
                new AlertDialog.Builder(this)
                        .setMessage(R.string.location_disabled)
                        .setPositiveButton(R.string.settings, (dialog, which) ->
                                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                        .setNegativeButton(R.string.cancel, null).show();
            } else {
                startLocationUpdates();
            }
        } else if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
                || shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.location_permission_title)
                    .setMessage(R.string.location_permission_reason)
                    .setPositiveButton(R.string.continue_action, (dialog, which) -> launchPermissionRequest())
                    .setNegativeButton(R.string.cancel, (dialog, which) ->
                            status.setText(R.string.location_denied)).show();
        } else {
            launchPermissionRequest();
        }
    }

    private void launchPermissionRequest() {
        permissionLauncher.launch(new String[] {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        });
    }

    private void showAppSettings() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.location_settings_reason)
                .setPositiveButton(R.string.settings, (dialog, which) ->
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", getPackageName(), null))))
                .setNegativeButton(R.string.cancel, null).show();
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        if (!resumed || mapStyle == null || !hasLocationPermission()) return;
        stopLocationUpdates();
        if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
            status.setText(R.string.location_disabled);
            return;
        }
        try {
            if (!map.getLocationComponent().isLocationComponentActivated()) {
                map.getLocationComponent().activateLocationComponent(
                        LocationComponentActivationOptions.builder(this, mapStyle)
                                .useDefaultLocationEngine(false)
                                .locationComponentOptions(LocationComponentOptions.builder(this)
                                        .gpsDrawable(R.drawable.ic_vehicle_marker)
                                        .trackingGesturesManagement(true)
                                        .build())
                                .build());
                map.getLocationComponent().addOnCameraTrackingChangedListener(
                        new OnCameraTrackingChangedListener() {
                            @Override
                            public void onCameraTrackingChanged(int mode) { }

                            @Override
                            public void onCameraTrackingDismissed() {
                                if (trip.isActive()) {
                                    trip.dismissTracking();
                                    renderTripControls();
                                }
                            }
                        });
            }
            map.getLocationComponent().setLocationComponentEnabled(true);
            applyTripMode(false);
            receivedFix = false;
            status.setText(R.string.location_searching);
            boolean requested = false;
            for (String provider : locationManager.getProviders(true)) {
                if (LocationManager.PASSIVE_PROVIDER.equals(provider)) continue;
                if (LocationManager.GPS_PROVIDER.equals(provider) && !hasFinePermission()) continue;
                locationManager.requestLocationUpdates(provider, 1000L, 0f,
                        locationListener, Looper.getMainLooper());
                requested = true;
            }
            if (requested) handler.postDelayed(locationTimeout, 20000L);
            else status.setText(R.string.location_waiting);
        } catch (SecurityException exception) {
            stopLocationUpdates();
            status.setText(R.string.location_denied);
        } catch (IllegalArgumentException exception) {
            stopLocationUpdates();
            status.setText(R.string.location_waiting);
        }
    }

    private void stopLocationUpdates() {
        handler.removeCallbacks(locationTimeout);
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {
                // A one-time grant can expire while the Activity is stopped.
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        mapView.onResume();
        if (hasLocationPermission()) startLocationUpdates();
        mapView.post(() -> {
            if (!resumed || isDestroyed()) return;
            renderDestination();
            renderRoute();
            applyTripMode(trip.isFollowing());
            renderTripControls();
        });
    }

    @Override
    protected void onPause() {
        resumed = false;
        mapView.setKeepScreenOn(false);
        stopLocationUpdates();
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        mapView.onStop();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBundle(STATE_TRIP, trip.save());
        outState.putBundle(STATE_PLAN, routePlan.toBundle());
        outState.putInt(STATE_ROUTE_CAMERA, fittedRouteId);
        mapView.onSaveInstanceState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onDestroy() {
        stopLocationUpdates();
        mapView.onDestroy();
        super.onDestroy();
    }
}
