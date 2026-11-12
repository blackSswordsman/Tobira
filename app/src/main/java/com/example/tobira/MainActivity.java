package com.example.tobira;

import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    /** Dangerous car permissions that must be granted at runtime. */
    private static final String[] CAR_RUNTIME_PERMISSIONS = {
            "android.car.permission.CAR_SPEED",
            "android.car.permission.CAR_ENERGY",
    };

    private VehicleFlagManager vehicleFlagManager;

    private final ActivityResultLauncher<String[]> carPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        Log.i(TAG, "Car permission result: " + result);
                        startVehicleFlags();
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        com.example.tobira.data.QuestRepository.getInstance(this).initialize()
                .whenComplete((ignored, error) -> {
                    if (error != null) android.util.Log.e("QuestStorage", "Cannot open quest database", error);
                });
        findViewById(R.id.open_quest_routes_button).setOnClickListener(view ->
                startActivity(new android.content.Intent(this, RouteCatalogActivity.class)));
        findViewById(R.id.open_map_button).setOnClickListener(view ->
                startActivity(new android.content.Intent(this, MapActivity.class)));
        findViewById(R.id.open_vehicle_info_button).setOnClickListener(view ->
                startActivity(new android.content.Intent(this, VehicleInfoActivity.class)));

        vehicleFlagManager = new VehicleFlagManager(this);
        vehicleFlagManager.setFlagListener((flag, areaId, value) -> runOnUiThread(() ->
                Toast.makeText(this, flag.name(), Toast.LENGTH_SHORT).show()));
        carPermissionLauncher.launch(CAR_RUNTIME_PERMISSIONS);
    }

    private void startVehicleFlags() {
        if (vehicleFlagManager.start()) {
            vehicleFlagManager.logAllValues();
        }
    }

    @Override
    protected void onDestroy() {
        if (vehicleFlagManager != null) vehicleFlagManager.stop();
        super.onDestroy();
    }
}
