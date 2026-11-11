package com.example.tobira;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import com.example.tobira.data.QuestEntities.Progress;
import com.example.tobira.data.QuestEntities.Step;
import com.example.tobira.data.QuestRepository;
import com.example.tobira.data.QuestRouteEntities.Route;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Catalog and route detail use separate Activity instances, so Back returns to the catalog. */
public final class RouteCatalogActivity extends AppCompatActivity {
    private static final String EXTRA_ID = "route-id";
    private static final String EXTRA_VERSION = "route-version";
    private RouteCatalogViewModel model;
    private LinearLayout rows;
    private TextView title;
    private TextView description;
    private TextView status;
    private View retry;
    private String routeId;
    private int version;
    private Button startJourney;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_route_catalog);
        rows = findViewById(R.id.catalog_rows);
        title = findViewById(R.id.catalog_title);
        description = findViewById(R.id.catalog_description);
        status = findViewById(R.id.catalog_status);
        retry = findViewById(R.id.catalog_retry);
        startJourney = findViewById(R.id.start_quest_journey);
        routeId = getIntent().getStringExtra(EXTRA_ID);
        version = getIntent().getIntExtra(EXTRA_VERSION, 1);
        model = new ViewModelProvider(this).get(RouteCatalogViewModel.class);
        findViewById(R.id.catalog_back).setOnClickListener(view -> finish());
        startJourney.setOnClickListener(view -> model.prepareRoute(routeId, version));
        model.preparing().observe(this, preparing ->
                startJourney.setEnabled(!Boolean.TRUE.equals(preparing)));
        model.preparedPlan().observe(this, plan -> {
            if (plan == null) return;
            model.consumePlan();
            startActivity(new Intent(this, RouteEditorActivity.class)
                    .putExtra(RouteEditorActivity.EXTRA_PLAN, plan.toBundle())
                    .putExtra(RouteEditorActivity.EXTRA_OPEN_MAP, true));
        });
        retry.setOnClickListener(view -> {
            status.setText(R.string.catalog_loading);
            if (routeId == null) model.initialize();
            else model.load(routeId, version);
        });
        model.failed().observe(this, failed -> {
            retry.setVisibility(Boolean.TRUE.equals(failed) ? View.VISIBLE : View.GONE);
            if (Boolean.TRUE.equals(failed)) {
                rows.removeAllViews();
                startJourney.setVisibility(View.GONE);
                status.setText(R.string.catalog_error);
            }
        });
        if (routeId == null) {
            model.routes().observe(this, this::renderCatalog);
        } else {
            description.setText(R.string.catalog_loading);
            model.details().observe(this, this::renderDetails);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (routeId != null) model.load(routeId, version);
    }

    private void renderCatalog(List<Route> routes) {
        rows.removeAllViews();
        // New playthroughs use the latest route revision; existing ones remain versioned in storage.
        Map<String, Route> latest = new LinkedHashMap<>();
        for (Route route : routes) {
            Route previous = latest.get(route.id);
            if (previous == null || previous.version < route.version) latest.put(route.id, route);
        }
        status.setText(latest.isEmpty() ? R.string.catalog_empty : R.string.catalog_choose);
        for (Route route : latest.values()) {
            Button button = row(route.title + "\n" + route.description);
            button.setOnClickListener(view -> startActivity(
                    new Intent(this, RouteCatalogActivity.class)
                            .putExtra(EXTRA_ID, route.id).putExtra(EXTRA_VERSION, route.version)));
        }
    }

    private void renderDetails(QuestRepository.RouteDetails details) {
        if (details == null) return;
        startJourney.setVisibility(View.VISIBLE);
        title.setText(details.route.title);
        description.setText(details.route.description + "\n\n" + getString(R.string.quest_route_order));
        String summary = getString(R.string.quest_route_progress,
                details.status.completed, details.status.required);
        status.setText(summary + "\n" + getString(details.status.completedRoute
                ? R.string.quest_route_completed : details.status.finaleUnlocked
                ? R.string.quest_finale_unlocked : R.string.quest_finale_locked));
        rows.removeAllViews();
        for (QuestRepository.RouteStop stop : details.stops) {
            boolean locked = stop.member.finale && !details.status.finaleUnlocked;
            boolean done = stop.progress != null && Progress.COMPLETED.equals(stop.progress.phase);
            String state = getString(locked ? R.string.quest_state_locked
                    : done ? R.string.quest_state_completed
                    : stop.progress != null ? R.string.quest_state_in_progress
                    : R.string.quest_state_available);
            String label = getString(R.string.quest_route_member,
                    stop.member.position + 1, stop.quest.title, state);
            if (stop.member.finale) label += "\n" + getString(R.string.quest_finale_label);
            Button button = row(label);
            button.setOnClickListener(view -> showStop(stop, locked, details.status));
        }
    }

    private void showStop(QuestRepository.RouteStop stop, boolean locked,
                          QuestRepository.RouteStatus routeStatus) {
        StringBuilder text = new StringBuilder(stop.quest.introduction);
        if (locked) {
            text.append("\n\n").append(getString(R.string.quest_unlock_requirement,
                    routeStatus.completed, routeStatus.required));
        } else {
            text.append("\n\n").append(getString(R.string.quest_observations_heading));
            for (Step step : stop.steps) {
                if (Step.OBSERVATION.equals(step.kind)) text.append("\n\n• ").append(step.text);
            }
            text.append("\n\n").append(getString(R.string.quest_content_pending));
        }
        new AlertDialog.Builder(this).setTitle(stop.quest.title).setMessage(text)
                .setPositiveButton(R.string.quest_close, null).show();
    }

    private Button row(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        button.setMinHeight((int) (80 * getResources().getDisplayMetrics().density));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        rows.addView(button, params);
        return button;
    }
}
