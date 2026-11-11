package com.example.tobira;

import android.app.Dialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import com.example.tobira.data.QuestRepository;

/** Manual entry into a place's task. Vehicle validation is deliberately delegated to a future evaluator. */
public final class QuestChallengeDialog extends DialogFragment {
    public static final String TAG = "quest-challenge";
    private QuestRepository.ChallengeInfo info;

    public static QuestChallengeDialog create(String id, int version) {
        QuestChallengeDialog fragment = new QuestChallengeDialog();
        Bundle args = new Bundle();
        args.putString("quest", id);
        args.putInt("version", version);
        fragment.setArguments(args);
        return fragment;
    }

    @NonNull @Override public Dialog onCreateDialog(Bundle savedInstanceState) {
        return new AlertDialog.Builder(requireContext())
                .setTitle(R.string.challenge_title)
                .setMessage(R.string.catalog_loading)
                .setPositiveButton(R.string.challenge_hint, null)
                .setNegativeButton(R.string.quest_close, null)
                .create();
    }

    @Override public void onStart() {
        super.onStart();
        AlertDialog dialog = (AlertDialog) requireDialog();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        Bundle args = requireArguments();
        QuestRepository.getInstance(requireContext()).openChallenge(args.getString("quest"),
                args.getInt("version")).whenComplete((value, error) ->
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded() || getDialog() != dialog) return;
                    if (error != null) {
                        dialog.setMessage(getString(R.string.challenge_unavailable));
                        return;
                    }
                    info = value;
                    dialog.setTitle(value.quest.title);
                    if (value.step == null) {
                        dialog.setMessage(getString(R.string.challenge_no_remaining));
                        return;
                    }
                    String body = getString(R.string.challenge_number, value.position, value.total)
                            + "\n\n" + value.step.text + "\n\n"
                            + getString(R.string.challenge_vehicle_pending);
                    dialog.setMessage(body);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(
                            value.step.hint != null && !value.step.hint.trim().isEmpty());
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                        if (info != null && info.step != null)
                            dialog.setMessage(body + "\n\n" + info.step.hint);
                    });
                }));
    }
}
