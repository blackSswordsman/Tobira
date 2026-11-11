package com.example.tobira;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Collections;

/** Edits a draft. Back/cancel never mutates the map's current plan. */
public class RouteEditorActivity extends AppCompatActivity {
    public static final String EXTRA_PLAN = "com.example.tobira.ROUTE_PLAN";
    private static final int START = -1;
    private static final int FINISH = -2;
    private static final int ADD_STOP = -3;
    private RoutePlan plan;
    private int pendingPoint = FINISH;
    private LinearLayout stopRows;

    private final ActivityResultLauncher<Intent> searchLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Place place = Place.fromBundle(
                        result.getData().getBundleExtra(PlaceSearchActivity.EXTRA_DESTINATION));
                if (place == null) return;
                if (pendingPoint == START) {
                    plan.start = place;
                    plan.useCurrentLocation = false;
                } else if (pendingPoint == FINISH) {
                    plan.destination = place;
                } else if (pendingPoint == ADD_STOP && plan.stops.size() < RoutePlan.MAX_STOPS) {
                    plan.stops.add(place);
                } else if (pendingPoint >= 0 && pendingPoint < plan.stops.size()) {
                    plan.stops.set(pendingPoint, place);
                }
                render();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_route_editor);
        plan = RoutePlan.fromBundle(savedInstanceState == null
                ? getIntent().getBundleExtra(EXTRA_PLAN) : savedInstanceState.getBundle(EXTRA_PLAN));
        if (savedInstanceState != null) pendingPoint = savedInstanceState.getInt("pending", FINISH);
        stopRows = findViewById(R.id.stop_rows);
        findViewById(R.id.editor_back).setOnClickListener(view -> finish());
        findViewById(R.id.select_start).setOnClickListener(view -> search(START));
        findViewById(R.id.use_current_start).setOnClickListener(view -> {
            plan.useCurrentLocation = true;
            plan.start = null;
            render();
        });
        findViewById(R.id.select_finish).setOnClickListener(view -> search(FINISH));
        findViewById(R.id.add_stop).setOnClickListener(view -> search(ADD_STOP));
        findViewById(R.id.apply_route).setOnClickListener(view -> {
            if (!plan.isComplete()) return;
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_PLAN, plan.toBundle()));
            finish();
        });
        render();
    }

    private void search(int point) {
        pendingPoint = point;
        searchLauncher.launch(new Intent(this, PlaceSearchActivity.class)
                .putExtra(PlaceSearchActivity.EXTRA_POINT_LABEL, getString(point == START
                        ? R.string.route_start : point == FINISH ? R.string.route_finish
                        : R.string.add_stop)));
    }

    private void render() {
        Button start = findViewById(R.id.select_start);
        start.setText(plan.useCurrentLocation ? getString(R.string.current_start)
                : plan.start == null ? getString(R.string.choose_start) : plan.start.address);
        ((Button) findViewById(R.id.select_finish)).setText(plan.destination == null
                ? getString(R.string.choose_finish) : plan.destination.address);
        findViewById(R.id.apply_route).setEnabled(plan.isComplete());
        findViewById(R.id.add_stop).setEnabled(plan.stops.size() < RoutePlan.MAX_STOPS);
        stopRows.removeAllViews();
        for (int index = 0; index < plan.stops.size(); index++) {
            final int position = index;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button place = button(getString(R.string.route_stop, index + 1) + "："
                    + plan.stops.get(index).address);
            row.addView(place, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            place.setOnClickListener(view -> search(position));
            Button up = button(getString(R.string.move_up));
            up.setEnabled(index > 0);
            up.setOnClickListener(view -> {
                Collections.swap(plan.stops, position, position - 1);
                render();
            });
            row.addView(up);
            Button down = button(getString(R.string.move_down));
            down.setEnabled(index < plan.stops.size() - 1);
            down.setOnClickListener(view -> {
                Collections.swap(plan.stops, position, position + 1);
                render();
            });
            row.addView(down);
            Button remove = button(getString(R.string.remove_stop));
            remove.setOnClickListener(view -> {
                plan.stops.remove(position);
                render();
            });
            row.addView(remove);
            stopRows.addView(row);
        }
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
        return button;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBundle(EXTRA_PLAN, plan.toBundle());
        outState.putInt("pending", pendingPoint);
        super.onSaveInstanceState(outState);
    }
}
