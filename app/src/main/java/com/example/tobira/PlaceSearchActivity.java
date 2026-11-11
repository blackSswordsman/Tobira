package com.example.tobira;

import android.content.Intent;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import java.util.ArrayList;

/** Returns a confirmed Place bundle to the map; cancelling leaves its destination unchanged. */
public class PlaceSearchActivity extends AppCompatActivity {
    public static final String EXTRA_DESTINATION = "com.example.tobira.DESTINATION";
    public static final String EXTRA_POINT_LABEL = "com.example.tobira.POINT_LABEL";
    private static final String STATE_CANDIDATE = "candidate";
    private PlaceSearchViewModel viewModel;
    private EditText queryInput;
    private Button searchButton;
    private TextView status;
    private ArrayAdapter<Place> adapter;
    private AlertDialog candidateDialog;
    private boolean restoringState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_place_search);
        String pointLabel = getIntent().getStringExtra(EXTRA_POINT_LABEL);
        if (pointLabel != null) ((TextView) findViewById(R.id.search_title)).setText(pointLabel);
        viewModel = new ViewModelProvider(this).get(PlaceSearchViewModel.class);
        queryInput = findViewById(R.id.place_query);
        searchButton = findViewById(R.id.submit_search);
        status = findViewById(R.id.search_status);
        TextView attribution = findViewById(R.id.search_attribution);
        attribution.setText(R.string.photon_attribution);
        attribution.setMovementMethod(LinkMovementMethod.getInstance());
        findViewById(R.id.search_back).setOnClickListener(view -> finish());
        findViewById(R.id.search_settings).setOnClickListener(view -> showSearchSettings());
        searchButton.setOnClickListener(view -> submitSearch());
        queryInput.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch();
                return true;
            }
            return false;
        });
        adapter = new ArrayAdapter<Place>(this, android.R.layout.simple_list_item_2,
                android.R.id.text1, new ArrayList<>()) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                Place place = getItem(position);
                if (place != null) {
                    ((TextView) row.findViewById(android.R.id.text1)).setText(place.name);
                    TextView address = row.findViewById(android.R.id.text2);
                    address.setText(place.address);
                    address.setMaxLines(3);
                    row.setMinimumHeight((int) (72 * getResources().getDisplayMetrics().density));
                }
                return row;
            }
        };
        ListView results = findViewById(R.id.place_results);
        results.setAdapter(adapter);
        results.setOnItemClickListener((parent, view, position, id) -> {
            Place place = adapter.getItem(position);
            if (place != null) confirmDestination(place);
        });
        viewModel.getState().observe(this, state -> {
            searchButton.setEnabled(state.status != PlaceSearchViewModel.Status.LOADING);
            adapter.clear();
            adapter.addAll(state.places);
            switch (state.status) {
                case IDLE -> status.setText(R.string.search_idle);
                case LOADING -> status.setText(R.string.search_loading);
                case EMPTY -> status.setText(R.string.search_empty);
                case ERROR -> status.setText(R.string.search_error);
                case RATE_LIMITED -> status.setText(R.string.search_rate_limited);
                case RESULTS -> status.setText(getString(R.string.search_results, state.places.size()));
            }
        });
        if (savedInstanceState != null && viewModel.getSelectedPlace() == null) {
            viewModel.select(Place.fromBundle(savedInstanceState.getBundle(STATE_CANDIDATE)));
        }
        Place candidate = viewModel.getSelectedPlace();
        if (candidate != null) confirmDestination(candidate);
        queryInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (!restoringState) viewModel.onQueryChanged(text.toString());
            }

            @Override
            public void afterTextChanged(Editable text) { }
        });
    }

    private void submitSearch() {
        String query = queryInput.getText().toString().trim();
        if (query.length() < 2 || query.length() > 200) {
            queryInput.setError(getString(R.string.search_query_invalid));
            return;
        }
        if (!searchButton.isEnabled()) return;
        queryInput.clearFocus();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        keyboard.hideSoftInputFromWindow(queryInput.getWindowToken(), 0);
        viewModel.search(query);
    }

    private void confirmDestination(Place place) {
        viewModel.select(place);
        candidateDialog = new AlertDialog.Builder(this)
                .setTitle(place.name)
                .setMessage(place.address)
                .setPositiveButton(R.string.select_route_point, (dialog, which) -> {
                    setResult(RESULT_OK, new Intent().putExtra(EXTRA_DESTINATION, place.toBundle()));
                    viewModel.select(null);
                    finish();
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> viewModel.select(null))
                .setOnCancelListener(dialog -> viewModel.select(null))
                .show();
    }

    private void showSearchSettings() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.search_endpoint_hint);
        input.setText(viewModel.getEndpoint());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.search_settings_title)
                .setView(input)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    if (viewModel.setEndpoint(input.getText().toString())) dialog.dismiss();
                    else input.setError(getString(R.string.search_endpoint_invalid));
                }));
        dialog.show();
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        restoringState = true;
        super.onRestoreInstanceState(savedInstanceState);
        restoringState = false;
        if (viewModel.getSelectedPlace() == null
                && viewModel.getState().getValue().status == PlaceSearchViewModel.Status.IDLE) {
            viewModel.onQueryChanged(queryInput.getText().toString());
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        Place candidate = viewModel.getSelectedPlace();
        if (candidate != null) outState.putBundle(STATE_CANDIDATE, candidate.toBundle());
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (candidateDialog != null) candidateDialog.dismiss();
        super.onDestroy();
    }
}
