package com.example.tobira;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
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
    }
}
