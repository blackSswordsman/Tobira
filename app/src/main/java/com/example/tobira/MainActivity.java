package com.example.tobira;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.open_map_button).setOnClickListener(view ->
                startActivity(new android.content.Intent(this, MapActivity.class)));
    }
}
