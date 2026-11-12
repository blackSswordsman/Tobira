package com.example.tobira;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

public class UserActivity extends AppCompatActivity {

    private static final String PREFS = "user_prefs";
    private static final String KEY_NAME = "user_name";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user);

        // ── User name: load from prefs, save on Done ──────────────────────────
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        EditText nameEdit = findViewById(R.id.user_name);
        String saved = prefs.getString(KEY_NAME, "");
        if (!saved.isEmpty()) nameEdit.setText(saved);

        nameEdit.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) {
                String name = nameEdit.getText().toString().trim();
                prefs.edit().putString(KEY_NAME, name).apply();
                nameEdit.clearFocus();
                hideKeyboard(nameEdit);
                return true;
            }
            return false;
        });
        // Also save when focus leaves the field
        nameEdit.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                String name = nameEdit.getText().toString().trim();
                prefs.edit().putString(KEY_NAME, name).apply();
                hideKeyboard(nameEdit);
            }
        });

        // ── Tab navigation ───────────────────────────────────────────────────
        // Route: finish back to MapActivity (assumed to be in the back stack)
        findViewById(R.id.tab_route).setOnClickListener(v -> finish());
        // Quest: launch QuestActivity without a plan (shows placeholder)
        findViewById(R.id.tab_quest).setOnClickListener(v -> {
            startActivity(new Intent(this, QuestActivity.class));
        });
        // User: already here, no-op
        findViewById(R.id.tab_user).setOnClickListener(v -> {});

        // ── EXP progress bar width (placeholder 0%) ───────────────────────────
        // The bar is set to 0dp in XML; it will be widened programmatically
        // once a real XP system exists. Nothing to do for now.
    }

    private void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }
}
