package com.example.tobira;

import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import com.example.tobira.data.QuestRepository;

/** Place task dialog. Debug builds can evaluate its answer using the bench speed signal. */
public final class QuestChallengeDialog extends DialogFragment {
    public static final String TAG = "quest-challenge";
    private static final String SPEED_PERMISSION = "android.car.permission.CAR_SPEED";
    private ChallengeValidation validation;
    private TextView body;
    private LinearLayout taskContainer;
    private int generation;

    private final ActivityResultLauncher<String> permission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (validation != null) validation.onPermissionResult(granted);
                if (!granted && isAdded() && !shouldShowRequestPermissionRationale(SPEED_PERMISSION)) {
                    new AlertDialog.Builder(requireContext()).setMessage(R.string.challenge_car_permission)
                            .setPositiveButton(R.string.settings, (dialog, which) ->
                                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", requireContext().getPackageName(), null))))
                            .setNegativeButton(R.string.cancel, null).show();
                }
            });

    public static QuestChallengeDialog create(String id, int version) {
        QuestChallengeDialog fragment = new QuestChallengeDialog();
        Bundle args = new Bundle();
        args.putString("quest", id);
        args.putInt("version", version);
        fragment.setArguments(args);
        return fragment;
    }

    @NonNull @Override public Dialog onCreateDialog(Bundle savedInstanceState) {
        ScrollView scroll = new ScrollView(requireContext());
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        body = new TextView(requireContext());
        body.setTextSize(18);
        body.setText(R.string.catalog_loading);
        content.addView(body);
        taskContainer = new LinearLayout(requireContext());
        taskContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(taskContainer);
        scroll.addView(content);
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.challenge_title)
                .setView(scroll)
                .setPositiveButton(R.string.challenge_hint, null)
                .setNegativeButton(R.string.quest_close, null);
        if ((requireContext().getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            builder.setNeutralButton(R.string.challenge_test_reset, null);
        }
        return builder.create();
    }

    private void requestSpeedPermission() {
        if (shouldShowRequestPermissionRationale(SPEED_PERMISSION)) {
            new AlertDialog.Builder(requireContext()).setMessage(R.string.challenge_car_permission)
                    .setPositiveButton(R.string.continue_action,
                            (dialog, which) -> permission.launch(SPEED_PERMISSION))
                    .setNegativeButton(R.string.cancel, null).show();
        } else permission.launch(SPEED_PERMISSION);
    }

    @Override public void onStart() {
        super.onStart();
        AlertDialog dialog = (AlertDialog) requireDialog();
        if (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> resetForTest());
        }
        loadChallenge();
    }

    private void resetForTest() {
        if ((requireContext().getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        int token = ++generation;
        if (validation != null) validation.close();
        validation = null;
        taskContainer.removeAllViews();
        body.setText(R.string.catalog_loading);
        AlertDialog dialog = (AlertDialog) requireDialog();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        Bundle args = requireArguments();
        QuestRepository.getInstance(requireContext())
                .resetProgress(args.getString("quest"), args.getInt("version"))
                .whenComplete((ignored, error) -> new Handler(Looper.getMainLooper()).post(() -> {
                    if (token != generation || !isAdded() || getDialog() != dialog) return;
                    if (error != null) {
                        body.setText(R.string.challenge_test_reset_error);
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
                    } else loadChallenge();
                }));
    }

    private void loadChallenge() {
        int token = ++generation;
        if (validation != null) validation.close();
        validation = null;
        AlertDialog dialog = (AlertDialog) requireDialog();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        if (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
        taskContainer.removeAllViews();
        Bundle args = requireArguments();
        QuestRepository.getInstance(requireContext()).openChallenge(args.getString("quest"),
                args.getInt("version")).whenComplete((value, error) ->
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (token != generation || !isAdded() || getDialog() != dialog) return;
                    if (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null)
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
                    if (error != null) {
                        body.setText(R.string.challenge_unavailable);
                        return;
                    }
                    dialog.setTitle(value.quest.title);
                    if (value.step == null) {
                        body.setText(R.string.challenge_no_remaining);
                        return;
                    }
                    String text = getString(R.string.challenge_number, value.position, value.total)
                            + "\n\n" + value.step.text;
                    validation = SpeedChallengeUi.attach(this, taskContainer, value, this::requestSpeedPermission);
                    if (validation == null) text += "\n\n" + getString(R.string.challenge_vehicle_pending);
                    final String taskText = text;
                    body.setText(taskText);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(
                            value.step.hint != null && !value.step.hint.trim().isEmpty());
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
                            body.setText(taskText + "\n\n" + value.step.hint));
                }));
    }

    @Override public void onStop() {
        generation++;
        if (validation != null) validation.close();
        validation = null;
        super.onStop();
    }
}
