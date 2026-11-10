package com.example.escaperoomapp;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final String STATE_SCREEN = "screen";

    private enum Screen {
        START, EARTH_SEAL, EARTH_SEAL_UNLOCKED
    }

    private Screen screen = Screen.START;
    private View startScreen;
    private View earthSealScreen;
    private TextView sealStatus;
    private Button simulateSuccessButton;
    private OnBackPressedCallback puzzleBackCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            screen = Screen.valueOf(savedInstanceState.getString(STATE_SCREEN, Screen.START.name()));
        }

        setContentView(R.layout.activity_main);
        startScreen = findViewById(R.id.start_screen);
        earthSealScreen = findViewById(R.id.earth_seal_screen);
        sealStatus = findViewById(R.id.seal_status);
        simulateSuccessButton = findViewById(R.id.simulate_success_button);

        findViewById(R.id.start_game_button).setOnClickListener(view -> showScreen(Screen.EARTH_SEAL));
        simulateSuccessButton.setOnClickListener(view -> showScreen(Screen.EARTH_SEAL_UNLOCKED));
        findViewById(R.id.back_to_start_button).setOnClickListener(view -> showScreen(Screen.START));

        puzzleBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                showScreen(Screen.START);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, puzzleBackCallback);
        showScreen(screen);
    }

    private void showScreen(Screen nextScreen) {
        screen = nextScreen;
        boolean isStart = screen == Screen.START;
        boolean isUnlocked = screen == Screen.EARTH_SEAL_UNLOCKED;
        startScreen.setVisibility(isStart ? View.VISIBLE : View.GONE);
        earthSealScreen.setVisibility(isStart ? View.GONE : View.VISIBLE);
        sealStatus.setVisibility(isUnlocked ? View.VISIBLE : View.GONE);
        simulateSuccessButton.setEnabled(!isUnlocked);
        puzzleBackCallback.setEnabled(!isStart);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(STATE_SCREEN, screen.name());
        super.onSaveInstanceState(outState);
    }
}
