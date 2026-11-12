package com.example.tobira;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
    private QuestArrivalViewModel arrivalModel;
    private QuestArrivalTracker arrivalTracker;
    private boolean questHasContent;
    private View questPanel;
    private boolean questPreview;
    private static final String STATE_QUEST_PREVIEW = "quest-preview";
    private TextView questPanelTitle;
    private TextView questPanelIntro;
    private TextView questPanelObservations;
    private static final String DESTINATION_SOURCE = "destination-source";
    private static final String DESTINATION_LAYER = "destination-layer";
    private static final String ROUTE_SOURCE = "route-source";
    private static final String ROUTE_LAYER = "route-layer";
    private static final String STATE_ROUTE_CAMERA = "route-camera";
    private RouteViewModel routeViewModel;
    private Button routeButton;
    private TextView routeSummary;
    // Overlay UI
    private LinearLayout stopRows;
    private LinearLayout placeResults;
    private View searchResultsBubble;
    private EditText placeQuery;
    private PlaceSearchViewModel searchViewModel;
    private int dragFrom = -1;
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
                if (!selected.isComplete() || routePlan.hasSameItinerary(selected)) return;
                endTrip();
                routeViewModel.clear();
                routePlan = selected;
                selectQuestPreview();
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
                if (arrivalTracker != null) arrivalTracker.refresh();
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
            questPreview = savedInstanceState.getBoolean(STATE_QUEST_PREVIEW, false);
            destination = routePlan.destination;
            centerOnNextFix = destination == null;
            fittedRouteId = savedInstanceState.getInt(STATE_ROUTE_CAMERA, -1);
        } else if (getIntent().hasExtra(RouteEditorActivity.EXTRA_PLAN)) {
            routePlan = RoutePlan.fromBundle(getIntent().getBundleExtra(RouteEditorActivity.EXTRA_PLAN));
            destination = routePlan.destination;
            centerOnNextFix = false;
            centerOnDestination = true;
        }
        setContentView(R.layout.activity_map);
        arrivalModel = new ViewModelProvider(this).get(QuestArrivalViewModel.class);
        arrivalTracker = new QuestArrivalTracker(this, arrivalModel);
        arrivalModel.getState().observe(this, ignored -> renderArrival());
        destinationLabel = findViewById(R.id.destination_label);
        questPanel = findViewById(R.id.quest_panel);
        findViewById(R.id.quest_preview_button).setOnClickListener(view -> {
            if (!trip.isActive()) return;
            questPreview = !questPreview;
            renderTripControls();
        });
        questPanelTitle = findViewById(R.id.quest_panel_title);
        questPanelIntro = findViewById(R.id.quest_panel_intro);
        questPanelObservations = findViewById(R.id.quest_panel_observations);
        findViewById(R.id.quest_return_button).setOnClickListener(view -> {
            if (!trip.isActive() || !arrivalModel.canOpenChallenge() || getSupportFragmentManager().isStateSaved()
                    || getSupportFragmentManager().findFragmentByTag(QuestChallengeDialog.TAG) != null) return;
            QuestPanelViewModel.State selected = new ViewModelProvider(this)
                    .get(QuestPanelViewModel.class).getState().getValue();
            if (selected == null || selected.status != QuestPanelViewModel.Status.CONTENT) return;
            QuestChallengeDialog.create(selected.questId, selected.questVersion, resolveNextPlace())
                    .showNow(getSupportFragmentManager(), QuestChallengeDialog.TAG);
        });
        selectQuestPreview();
        new ViewModelProvider(this).get(QuestPanelViewModel.class).getState()
                .observe(this, this::renderQuestPanel);
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
            // ⑧: update GO button label based on route state
            updateGoButton(state.status);
        });
        clearDestinationButton = findViewById(R.id.clear_destination_button);
        findViewById(R.id.search_places_button).setOnClickListener(view -> openRouteEditor());
        findViewById(R.id.route_points_button).setOnClickListener(view -> openRouteEditor());
        clearDestinationButton.setOnClickListener(view -> {
            endTrip();
            routeViewModel.clear();
            routePlan = new RoutePlan();
            selectQuestPreview();
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
        // Overlay: waypoint list
        stopRows = findViewById(R.id.stop_rows);
        renderWaypoints();

        // ⑧: GO button — build route or start trip depending on state
        View applyRouteBtn = findViewById(R.id.apply_route);
        if (applyRouteBtn != null) applyRouteBtn.setOnClickListener(view -> {
            RouteViewModel.State rs = routeViewModel.currentState();
            if (rs.status == RouteViewModel.Status.READY) {
                startTrip();
            } else {
                buildRoute();
            }
        });

        // Overlay: current location button
        View currentLocationBtn = findViewById(R.id.current_location);
        if (currentLocationBtn != null) currentLocationBtn.setOnClickListener(view -> {
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

        // ㉒: cancel trip button — visible only after GO is pressed
        View cancelBtn = findViewById(R.id.cancel_trip_button);
        if (cancelBtn != null) cancelBtn.setOnClickListener(view -> {
            endTrip();
            routeViewModel.clear();
            cancelBtn.setVisibility(View.GONE);
        });

        // Overlay: quest tab → open QuestActivity
        View tabQuest = findViewById(R.id.tab_quest);
        if (tabQuest != null) tabQuest.setOnClickListener(view -> {
            Place nextPlace = resolveNextPlace();
            Intent questIntent = new Intent(this, QuestActivity.class);
            questIntent.putExtra(QuestActivity.EXTRA_PLAN, routePlan.toBundle());
            if (nextPlace != null) questIntent.putExtra(QuestActivity.EXTRA_NEXT_PLACE, nextPlace.toBundle());
            startActivity(questIntent);
        });

        // Overlay: user tab → open UserActivity
        View tabUser = findViewById(R.id.tab_user);
        if (tabUser != null) tabUser.setOnClickListener(view ->
                startActivity(new Intent(this, UserActivity.class)));

        // Overlay: search field + results bubble
        searchResultsBubble = findViewById(R.id.search_results_bubble);
        placeResults = findViewById(R.id.place_results);
        placeQuery = findViewById(R.id.place_query);
        searchViewModel = new ViewModelProvider(this).get(PlaceSearchViewModel.class);

        if (placeQuery != null && searchResultsBubble != null) {
            placeQuery.setOnFocusChangeListener((v, hasFocus) -> {
                if (searchResultsBubble != null)
                    searchResultsBubble.setVisibility(hasFocus ? View.VISIBLE : View.GONE);
            });
            placeQuery.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                    searchViewModel.onQueryChanged(s.toString());
                }
                @Override public void afterTextChanged(Editable s) {}
            });
            placeQuery.setOnEditorActionListener((v, action, event) -> {
                if (action == EditorInfo.IME_ACTION_SEARCH) {
                    searchViewModel.search(placeQuery.getText().toString());
                    return true;
                }
                return false;
            });
        }

        searchViewModel.getState().observe(this, state -> renderSearchResults(state));

        mapView = findViewById(R.id.map_view);
        mapView.onCreate(savedInstanceState);
        mapView.addOnLayoutChangeListener((view, left, top, right, bottom,
                                          oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft && resumed && trip.isFollowing()) {
                mapView.post(() -> {
                    if (resumed && trip.isFollowing()) applyTripMode(true);
                });
            }
        });
        mapView.addOnDidFailLoadingMapListener(error -> {
            status.setText(R.string.map_error);
            retryButton.setVisibility(View.VISIBLE);
        });
        mapView.getMapAsync(readyMap -> {
            map = readyMap;
            loadMap();
        });
    }

    // ── Overlay: waypoint list ───────────────────────────────────────────────

    private void renderWaypoints() {
        if (stopRows == null) return;
        stopRows.removeAllViews();

        // ⑯ fix: show stops only (destination mirrors last stop — no double entry)
        java.util.List<Place> waypoints = new java.util.ArrayList<>(routePlan.stops);

        int dp = (int) getResources().getDisplayMetrics().density;
        int nodeCount = Math.max(1, waypoints.size());

        for (int i = 0; i < nodeCount; i++) {
            final int index = i;
            Place place = i < waypoints.size() ? waypoints.get(i) : null;
            String label = place != null ? place.name : getString(R.string.unset_waypoint);

            // ─── Node row ───────────────────────────────────────────
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 72 * dp));

            View circle = new View(this);
            circle.setLayoutParams(new LinearLayout.LayoutParams(44 * dp, 44 * dp));
            circle.setBackground(getDrawable(R.drawable.bg_waypoint_circle));
            row.addView(circle);

            TextView nameView = new TextView(this);
            LinearLayout.LayoutParams nameParams =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nameParams.setMarginStart(16 * dp);
            nameView.setLayoutParams(nameParams);
            nameView.setText(label);
            nameView.setTextColor(place != null ? Color.WHITE : Color.parseColor("#FF888888"));
            nameView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 28);
            nameView.setMaxLines(1);
            nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            // ⑭: tapping an unset (placeholder) node opens search to fill that slot
            if (place == null) {
                row.setClickable(true);
                row.setFocusable(true);
                row.setOnClickListener(v -> focusSearchAt(index));
            }
            row.addView(nameView);

            // ③㉓ Reorder handle: drag to reorder; only shown for registered stops
            if (place != null) {
                ImageButton handle = new ImageButton(this);
                handle.setLayoutParams(new LinearLayout.LayoutParams(52 * dp, 52 * dp));
                handle.setImageDrawable(getDrawable(R.drawable.ic_reorder));
                handle.setBackground(null);
                handle.setAlpha(routePlan.stops.size() > 1 ? 1f : 0.3f);
                handle.setContentDescription(getString(R.string.drag_handle_desc));
                // ㉓: drag to reorder — track Y position across all rows in stopRows
                handle.setOnTouchListener(new View.OnTouchListener() {
                    private float startY;
                    private int dragIndex;
                    @SuppressLint("ClickableViewAccessibility")
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        if (routePlan.stops.size() <= 1) return false;
                        switch (event.getAction()) {
                            case MotionEvent.ACTION_DOWN:
                                startY = event.getRawY();
                                dragIndex = index;
                                return true;
                            case MotionEvent.ACTION_MOVE: {
                                float dy = event.getRawY() - startY;
                                int rowH = 72 * dp; // node row height
                                int steps = (int) (dy / rowH);
                                int newIndex = Math.max(0, Math.min(routePlan.stops.size() - 1,
                                        dragIndex + steps));
                                if (newIndex != dragIndex) {
                                    // slide one step at a time for smooth feedback
                                    int dir = newIndex > dragIndex ? 1 : -1;
                                    java.util.Collections.swap(routePlan.stops, dragIndex, dragIndex + dir);
                                    dragIndex += dir;
                                    startY += dir * rowH;
                                    routePlan.destination = routePlan.stops.get(routePlan.stops.size() - 1);
                                    destination = routePlan.destination;
                                    routeViewModel.clear();
                                    renderWaypoints();
                                    renderDestination();
                                }
                                return true;
                            }
                            case MotionEvent.ACTION_UP:
                            case MotionEvent.ACTION_CANCEL:
                                return true;
                        }
                        return false;
                    }
                });
                row.addView(handle);
            }
            stopRows.addView(row);

            // ─── Segment row (after every node including last = ⑨) ──
            LinearLayout seg = new LinearLayout(this);
            seg.setOrientation(LinearLayout.HORIZONTAL);
            seg.setGravity(android.view.Gravity.CENTER_VERTICAL);
            seg.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 48 * dp));

            View line = new View(this);
            LinearLayout.LayoutParams lineParams =
                    new LinearLayout.LayoutParams(2 * dp, ViewGroup.LayoutParams.MATCH_PARENT);
            lineParams.setMarginStart(21 * dp);
            lineParams.setMarginEnd(8 * dp);
            line.setLayoutParams(lineParams);
            line.setBackgroundColor(Color.parseColor("#FF43CBC7"));
            seg.addView(line);

            boolean hasNext = i < waypoints.size() - 1;
            if (hasNext) {
                // Segment time/distance info
                TextView segInfo = new TextView(this);
                segInfo.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                segInfo.setTextColor(Color.parseColor("#FF43CBC7"));
                segInfo.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
                // ⑬: show total time/distance when route is ready (single-segment route)
                RouteViewModel.State rs = routeViewModel != null ? routeViewModel.currentState() : null;
                if (rs != null && rs.status == RouteViewModel.Status.READY && rs.route != null) {
                    if (waypoints.size() == 2) {
                        long mins = Math.max(1, (long) Math.ceil(rs.route.durationSeconds / 60.0));
                        double km = rs.route.distanceMeters / 1000.0;
                        segInfo.setText(String.format(java.util.Locale.getDefault(), "%d分 / %.1fkm", mins, km));
                    } else {
                        // multi-stop: show total on last segment
                        if (i == waypoints.size() - 2) {
                            long mins = Math.max(1, (long) Math.ceil(rs.route.durationSeconds / 60.0));
                            double km = rs.route.distanceMeters / 1000.0;
                            segInfo.setText(String.format(java.util.Locale.getDefault(), "合計 %d分 / %.1fkm", mins, km));
                        }
                    }
                }
                seg.addView(segInfo);

                // ⑰ simple + button (no bubble)
                ImageButton addBetween = new ImageButton(this);
                LinearLayout.LayoutParams abp =
                        new LinearLayout.LayoutParams(44 * dp, 44 * dp);
                abp.setMarginEnd(4 * dp);
                addBetween.setLayoutParams(abp);
                addBetween.setImageDrawable(getDrawable(R.drawable.ic_add));
                addBetween.setBackground(getDrawable(R.drawable.bg_add_bubble));
                addBetween.setPadding(10 * dp, 10 * dp, 10 * dp, 10 * dp);
                addBetween.setContentDescription(getString(R.string.add_stop_between));
                final int insertHere = i + 1;
                addBetween.setOnClickListener(v -> focusSearchAt(insertHere));
                seg.addView(addBetween);
            } else {
                // ⑨ trailing segment: just the vertical line + ⑱ trailing + button inline
                View spacer = new View(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
                seg.addView(spacer);

                ImageButton addEnd = new ImageButton(this);
                LinearLayout.LayoutParams aep =
                        new LinearLayout.LayoutParams(44 * dp, 44 * dp);
                aep.setMarginEnd(4 * dp);
                addEnd.setLayoutParams(aep);
                addEnd.setImageDrawable(getDrawable(R.drawable.ic_add));
                addEnd.setBackground(getDrawable(R.drawable.bg_add_bubble));
                addEnd.setPadding(10 * dp, 10 * dp, 10 * dp, 10 * dp);
                addEnd.setContentDescription(getString(R.string.add_stop));
                addEnd.setOnClickListener(v -> focusSearchAt(-1));
                seg.addView(addEnd);
            }
            stopRows.addView(seg);
        }
        // removed separate addRow — ⑱ + button is now inline in the last segment row
    }

    private void updateGoButton(RouteViewModel.Status status) {
        Button goBtn = findViewById(R.id.apply_route);
        View cancelView = findViewById(R.id.cancel_trip_button);
        if (goBtn == null) return;

        goBtn.clearAnimation();
        if (trip.isActive()) {
            // ㉔: hide GO while trip is running
            goBtn.setVisibility(View.INVISIBLE);
        } else if (status == RouteViewModel.Status.READY) {
            // Route confirmed but trip not yet started: show blinking GO
            goBtn.setVisibility(View.VISIBLE);
            android.view.animation.AlphaAnimation blink =
                    new android.view.animation.AlphaAnimation(1f, 0.3f);
            blink.setDuration(600);
            blink.setRepeatMode(android.view.animation.Animation.REVERSE);
            blink.setRepeatCount(android.view.animation.Animation.INFINITE);
            goBtn.startAnimation(blink);
        } else {
            goBtn.setVisibility(View.VISIBLE);
        }
        // ㉒: cancel button visible only when trip is active
        if (cancelView != null) {
            cancelView.setVisibility(trip.isActive() ? View.VISIBLE : View.GONE);
        }
    }

    private void focusSearchAt(int insertIndex) {
        if (placeQuery == null) return;
        placeQuery.setTag(insertIndex);
        placeQuery.requestFocus();
        // ⑤: explicitly show keyboard
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(placeQuery, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        if (searchResultsBubble != null) searchResultsBubble.setVisibility(View.VISIBLE);
    }

    // ── Overlay: search results ──────────────────────────────────────────────

    private void renderSearchResults(PlaceSearchViewModel.State state) {
        if (placeResults == null || searchResultsBubble == null) return;
        TextView statusView = searchResultsBubble.findViewById(R.id.search_status);
        if (statusView != null) {
            switch (state.status) {
                case IDLE -> statusView.setVisibility(View.GONE);
                case LOADING -> { statusView.setText(R.string.search_loading); statusView.setVisibility(View.VISIBLE); }
                case EMPTY -> { statusView.setText(R.string.search_empty); statusView.setVisibility(View.VISIBLE); }
                case ERROR -> { statusView.setText(R.string.search_error); statusView.setVisibility(View.VISIBLE); }
                case RATE_LIMITED -> { statusView.setText(R.string.search_rate_limited); statusView.setVisibility(View.VISIBLE); }
                case RESULTS -> statusView.setVisibility(View.GONE);
            }
        }

        placeResults.removeAllViews();
        int dp = (int) getResources().getDisplayMetrics().density;

        for (Place place : state.places) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 96 * dp));
            // ⑥: entire row is clickable to add the place
            row.setClickable(true);
            row.setFocusable(false);
            row.setOnClickListener(v -> addPlaceToRoute(place));

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView nameView = new TextView(this);
            nameView.setText(place.name);
            nameView.setTextColor(Color.parseColor("#FF111111"));
            nameView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20);
            nameView.setMaxLines(1);
            nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(nameView);

            TextView addrView = new TextView(this);
            addrView.setText(place.address);
            addrView.setTextColor(Color.parseColor("#FF444444"));
            addrView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            addrView.setMaxLines(2);
            info.addView(addrView);

            row.addView(info);

            ImageButton addBtn = new ImageButton(this);
            addBtn.setLayoutParams(new LinearLayout.LayoutParams(48 * dp, 48 * dp));
            addBtn.setImageDrawable(getDrawable(R.drawable.ic_add));
            addBtn.setBackground(getDrawable(R.drawable.bg_route_node));
            addBtn.setPadding(12 * dp, 12 * dp, 12 * dp, 12 * dp);
            addBtn.setContentDescription(place.name);
            addBtn.setOnClickListener(v -> addPlaceToRoute(place));
            row.addView(addBtn);
            placeResults.addView(row);
        }
    }

    private void addPlaceToRoute(Place place) {
        Object tag = placeQuery != null ? placeQuery.getTag() : null;
        int insertAt = (tag instanceof Integer) ? (int) tag : -1;

        // ④: always append to stops in order; display is stops only (no separate destination row).
        //     Destination is kept in sync as the last stop so isComplete() returns true when ≥1 stop.
        if (insertAt >= 0 && insertAt <= routePlan.stops.size()) {
            routePlan.stops.add(insertAt, place);
        } else {
            routePlan.stops.add(place);
        }
        // ⑯ fix: destination mirrors only the LAST stop — do NOT put it in the waypoints list again.
        routePlan.destination = routePlan.stops.get(routePlan.stops.size() - 1);
        destination = routePlan.destination;

        // ⑦: hide bubble and clear search field
        if (placeQuery != null) {
            placeQuery.setTag(-1);
            placeQuery.setText("");
        }
        if (searchResultsBubble != null) searchResultsBubble.setVisibility(View.GONE);
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && placeQuery != null)
            imm.hideSoftInputFromWindow(placeQuery.getWindowToken(), 0);

        endTrip();
        routeViewModel.clear();
        selectQuestPreview();
        renderDestination();
        renderWaypoints();
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
                    lineColor("#42A5F5"), lineWidth(10f), lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
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
        renderWaypoints();
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

    /** Returns the next stop on the route after the current position: first stop if any, else destination. */
    private Place resolveNextPlace() {
        if (!routePlan.stops.isEmpty()) return routePlan.stops.get(0);
        return routePlan.destination;
    }

    private void selectQuestPreview() {
        Place point = routePlan.stops.isEmpty() ? routePlan.destination : routePlan.stops.get(0);
        Place selected = routePlan.isQuestRoute() ? point : null;
        if (arrivalModel != null) arrivalModel.selectTarget(selected);
        new ViewModelProvider(this).get(QuestPanelViewModel.class).selectQuest(selected);
    }

    private void openRouteEditor() {
        planEditorLauncher.launch(new Intent(this, RouteEditorActivity.class)
                .putExtra(RouteEditorActivity.EXTRA_PLAN, routePlan.toBundle()));
    }

    private void startTrip() {
        if (routeViewModel.currentState().status != RouteViewModel.Status.READY) return;
        if (!hasFreshLocation()) {
            status.setText(R.string.route_need_location);
            requestLocation();
            return;
        }
        trip.start(true, true);
        questPreview = false;
        centerOnNextFix = false;
        centerOnDestination = false;
        map.getLocationComponent().forceLocationUpdate(currentLocation);
        applyTripMode(true);
        renderTripControls();
        updateGoButton(routeViewModel.currentState().status); // ㉔: hide GO
    }

    private void endTrip() {
        trip.stop();
        questPreview = false;
        applyTripMode(false);
        renderTripControls();
        updateGoButton(routeViewModel.currentState().status); // ㉔: restore GO
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
        findViewById(R.id.search_places_button).setVisibility(active ? View.GONE : View.VISIBLE);
        findViewById(R.id.route_points_button).setVisibility(active ? View.VISIBLE : View.GONE);
        routeButton.setVisibility(destination == null || active ? View.GONE : View.VISIBLE);
        clearDestinationButton.setVisibility(destination == null || active ? View.GONE : View.VISIBLE);
        destinationLabel.setVisibility(destination == null || active ? View.GONE : View.VISIBLE);
        status.setVisibility(active ? View.GONE : View.VISIBLE);
        if (!active) questPreview = false;
        questPanel.setVisibility(active && questPreview ? View.VISIBLE : View.GONE);
        Button previewButton = findViewById(R.id.quest_preview_button);
        previewButton.setVisibility(active ? View.VISIBLE : View.GONE);
        previewButton.setText(questPreview ? R.string.hide_quest_panel : R.string.show_quest_panel);
        if (mapView != null) mapView.setKeepScreenOn(active && resumed);
    }

    private void renderArrival() {
        QuestArrivalUi.render(findViewById(R.id.quest_arrival_status),
                findViewById(R.id.quest_return_button), arrivalModel.getState().getValue(), questHasContent);
    }

    private void renderQuestPanel(QuestPanelViewModel.State state) {
        questHasContent = state.status == QuestPanelViewModel.Status.CONTENT;
        renderArrival();
        if (state.status == QuestPanelViewModel.Status.CONTENT) {
            questPanelTitle.setText(state.title);
            questPanelIntro.setText(state.introduction);
            StringBuilder observations = new StringBuilder();
            for (int index = 0; index < state.observations.size(); index++) {
                if (index > 0) observations.append("\n\n");
                observations.append(index + 1).append(". ").append(state.observations.get(index));
            }
            questPanelObservations.setText(observations.length() == 0
                    ? getString(R.string.quest_placeholder_observations) : observations.toString());
        } else {
            questPanelTitle.setText(state.status == QuestPanelViewModel.Status.LOADING
                    ? R.string.quest_panel_loading : R.string.quest_placeholder_title);
            questPanelIntro.setText(state.status == QuestPanelViewModel.Status.ERROR
                    ? R.string.quest_panel_error : R.string.quest_placeholder_intro);
            questPanelObservations.setText(R.string.quest_placeholder_observations);
        }
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
        outState.putBoolean(STATE_QUEST_PREVIEW, questPreview);
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
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN
                && searchResultsBubble != null
                && searchResultsBubble.getVisibility() == View.VISIBLE) {
            if (!isTouchInsideView(ev, searchResultsBubble) && !isTouchInsideView(ev, placeQuery)) {
                placeQuery.clearFocus();
                searchResultsBubble.setVisibility(View.GONE);
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private boolean isTouchInsideView(MotionEvent ev, View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return ev.getRawX() >= loc[0] && ev.getRawX() <= loc[0] + v.getWidth()
                && ev.getRawY() >= loc[1] && ev.getRawY() <= loc[1] + v.getHeight();
    }

    @Override
    protected void onDestroy() {
        stopLocationUpdates();
        mapView.onDestroy();
        super.onDestroy();
    }
}
