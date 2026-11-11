package com.example.tobira;

import android.widget.LinearLayout;
import androidx.fragment.app.DialogFragment;
import com.example.tobira.data.QuestRepository;

/** Bench evaluation is absent from release builds. */
public final class SpeedChallengeUi {
    private SpeedChallengeUi() { }
    public static ChallengeValidation attach(DialogFragment fragment, LinearLayout container,
            QuestRepository.ChallengeInfo task, Runnable requestPermission) {
        return null;
    }
}
