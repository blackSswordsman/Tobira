package com.example.tobira;

import android.car.VehiclePropertyIds;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Bench test screen: shows, for every vehicle property, whether it can be read on this car,
 * its latest value and when it last changed, plus the most recent flags.
 * ベンチテスト用画面：各プロパティの取得状態・最新値・更新時刻と、最近のフラグを表示する。
 */
public class VehicleInfoActivity extends AppCompatActivity {

    private static final long REFRESH_MS = 500;
    private static final int MAX_FLAGS = 6;

    private static final int COLOR_OK = Color.rgb(0x2E, 0x7D, 0x32);       // green
    private static final int COLOR_FIXED = Color.rgb(0x15, 0x65, 0xC0);    // blue
    private static final int COLOR_MISSING = Color.rgb(0x75, 0x75, 0x75);  // grey
    private static final int COLOR_DENIED = Color.rgb(0xC6, 0x28, 0x28);   // red
    private static final int COLOR_ERROR = Color.rgb(0xEF, 0x6C, 0x00);    // orange

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> recentFlags = new ArrayDeque<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final int[] propertyIds = VehicleFlagManager.getAllPropertyIds();
    private final View[] rowViews = new View[propertyIds.length];
    private final TextView[] statusViews = new TextView[propertyIds.length];
    private final TextView[] valueViews = new TextView[propertyIds.length];
    private final TextView[] updatedViews = new TextView[propertyIds.length];

    private VehicleFlagManager manager;
    private TextView summaryView;
    private TextView liveView;
    private TextView flagsView;
    private CheckBox onlyReceivedBox;
    private boolean connected;

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vehicle_info);
        summaryView = findViewById(R.id.vehicle_info_summary);
        liveView = findViewById(R.id.vehicle_info_live);
        flagsView = findViewById(R.id.vehicle_info_flags);
        onlyReceivedBox = findViewById(R.id.vehicle_info_only_received);
        findViewById(R.id.vehicle_info_back).setOnClickListener(v -> finish());
        onlyReceivedBox.setOnCheckedChangeListener((box, checked) -> refresh());

        buildRows();

        manager = new VehicleFlagManager(this);
        manager.setFlagListener((flag, areaId, value) -> handler.post(() -> onFlag(flag, areaId, value)));
    }

    @Override
    protected void onStart() {
        super.onStart();
        connected = manager.start();
        handler.post(refreshTask);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(refreshTask);
        manager.stop();
        super.onStop();
    }

    // ---- UI building ----

    private void buildRows() {
        LinearLayout header = findViewById(R.id.vehicle_info_header);
        TextView[] headerCells = addCells(header);
        headerCells[0].setText(R.string.vehicle_info_col_property);
        headerCells[1].setText(R.string.vehicle_info_col_status);
        headerCells[2].setText(R.string.vehicle_info_col_value);
        headerCells[3].setText(R.string.vehicle_info_col_updated);
        for (TextView cell : headerCells) cell.setTypeface(Typeface.DEFAULT_BOLD);

        LinearLayout rows = findViewById(R.id.vehicle_info_rows);
        for (int i = 0; i < propertyIds.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(6), 0, dp(6));
            if (i % 2 == 1) row.setBackgroundColor(Color.argb(0x14, 0x80, 0x80, 0x80));
            TextView[] cells = addCells(row);
            cells[0].setText(VehiclePropertyIds.toString(propertyIds[i]));
            cells[2].setTypeface(Typeface.MONOSPACE);
            statusViews[i] = cells[1];
            valueViews[i] = cells[2];
            updatedViews[i] = cells[3];
            rowViews[i] = row;
            rows.addView(row);
        }
    }

    /** Adds the 4 columns: property, status, value, updated. */
    private TextView[] addCells(LinearLayout row) {
        float[] weights = {3f, 1.2f, 3f, 1f};
        TextView[] cells = new TextView[weights.length];
        for (int c = 0; c < weights.length; c++) {
            TextView cell = new TextView(this);
            cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            cell.setPadding(dp(4), 0, dp(4), 0);
            row.addView(cell, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weights[c]));
            cells[c] = cell;
        }
        return cells;
    }

    // ---- Refresh ----

    private void refresh() {
        boolean onlyReceived = onlyReceivedBox.isChecked();
        long now = System.currentTimeMillis();
        int readable = 0, received = 0, notSupported = 0, denied = 0, errors = 0;

        for (int i = 0; i < propertyIds.length; i++) {
            int id = propertyIds[i];
            VehicleFlagManager.PropertyStatus status = manager.getPropertyStatus(id);
            String value = manager.describeValues(id);

            if (status == null) {
                setStatus(i, R.string.status_pending, COLOR_MISSING);
            } else {
                switch (status) {
                    case SUBSCRIBED:
                        readable++;
                        setStatus(i, R.string.status_subscribed, COLOR_OK);
                        break;
                    case READ_ONCE:
                        readable++;
                        setStatus(i, R.string.status_read_once, COLOR_FIXED);
                        break;
                    case NOT_SUPPORTED:
                        notSupported++;
                        setStatus(i, R.string.status_not_supported, COLOR_MISSING);
                        break;
                    case NO_PERMISSION:
                        denied++;
                        setStatus(i, R.string.status_no_permission, COLOR_DENIED);
                        break;
                    case WRITE_ONLY:
                        notSupported++;
                        setStatus(i, R.string.status_write_only, COLOR_MISSING);
                        break;
                    default:
                        errors++;
                        setStatus(i, R.string.status_error, COLOR_ERROR);
                        break;
                }
            }

            if (value != null) received++;
            valueViews[i].setText(value != null ? value : "—");
            updatedViews[i].setText(updatedText(status, manager.getLastUpdateMs(id), now));
            rowViews[i].setVisibility(onlyReceived && value == null ? View.GONE : View.VISIBLE);
        }

        summaryView.setText(getString(R.string.vehicle_info_summary,
                getString(connected ? R.string.vehicle_info_connected : R.string.vehicle_info_not_connected),
                readable, propertyIds.length, received, notSupported, denied, errors));
        liveView.setText(getString(R.string.vehicle_info_live,
                String.format(Locale.US, "%.1f km/h", manager.getSpeedKmh()),
                gearName(manager.getGear()),
                String.format(Locale.US, "%.1f°", manager.getSteeringAngleDeg())));
    }

    private void setStatus(int row, int textRes, int color) {
        statusViews[row].setText(textRes);
        statusViews[row].setTextColor(color);
    }

    private String updatedText(VehicleFlagManager.PropertyStatus status, long lastMs, long now) {
        if (status == VehicleFlagManager.PropertyStatus.READ_ONCE) return getString(R.string.vehicle_info_fixed);
        if (lastMs == 0) return "—";
        long seconds = (now - lastMs) / 1000;
        return seconds < 1 ? getString(R.string.vehicle_info_now) : getString(R.string.vehicle_info_seconds_ago, (int) seconds);
    }

    private static String gearName(int gear) {
        switch (gear) {
            case 0x0001: return "N";
            case 0x0002: return "R";
            case 0x0004: return "P";
            case 0x0008: return "D";
            case 0: return "—";
            default: return "0x" + Integer.toHexString(gear);
        }
    }

    private void onFlag(VehicleFlag flag, int areaId, Object value) {
        String line = timeFormat.format(new Date()) + "  " + flag.name()
                + (areaId != 0 ? " [area 0x" + Integer.toHexString(areaId) + "]" : "")
                + "  = " + value;
        recentFlags.addFirst(line);
        while (recentFlags.size() > MAX_FLAGS) recentFlags.removeLast();
        flagsView.setText(TextUtils.join("\n", recentFlags));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
