package com.example.tobira;

import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/** Thin UI binding. Designers may replace this or its layouts without changing arrival rules. */
public final class QuestArrivalUi {
    private QuestArrivalUi() { }
    public static void render(TextView status, Button start, QuestArrivalViewModel.State state, boolean hasContent) {
        QuestArrivalViewModel.Status value = state == null ? QuestArrivalViewModel.Status.CHECKING : state.status;
        int message = switch (value) {
            case NO_TARGET -> R.string.arrival_no_target;
            case CHECKING, SAVING -> R.string.arrival_checking;
            case NO_PERMISSION -> R.string.arrival_permission;
            case LOCATION_DISABLED -> R.string.location_disabled;
            case WAITING -> R.string.arrival_waiting;
            case OUTSIDE -> R.string.arrival_outside;
            case ARRIVED -> R.string.arrival_ready;
            case ERROR -> R.string.arrival_save_error;
        };
        status.setText(value == QuestArrivalViewModel.Status.OUTSIDE
                ? status.getContext().getString(message, (int) Math.ceil(state.distanceMeters))
                : status.getContext().getString(message));
        boolean ready = hasContent && state != null && state.canOpenChallenge();
        start.setEnabled(ready);
        start.setVisibility(ready ? View.VISIBLE : View.GONE);
    }
}
