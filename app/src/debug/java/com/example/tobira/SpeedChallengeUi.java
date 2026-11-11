package com.example.tobira;

import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import com.example.tobira.data.QuestRepository;
import com.example.tobira.debug.BenchSpeedSource;
import com.example.tobira.debug.SpeedHoldCondition;

/** Debug bench evaluator embedded in the actual place task dialog. */
public final class SpeedChallengeUi implements ChallengeValidation {
    public static final String PERMISSION = "android.car.permission.CAR_SPEED";
    private final DialogFragment fragment;
    private final QuestRepository.ChallengeInfo task;
    private final Runnable requestPermission;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView status;
    private final TextView speed;
    private final ProgressBar progress;
    private final Button start;
    private BenchSpeedSource source;
    private SpeedHoldCondition condition;
    private long lastReceiptNs;
    private boolean closed;
    private boolean running;

    public static ChallengeValidation attach(DialogFragment fragment, LinearLayout container,
            QuestRepository.ChallengeInfo task, Runnable requestPermission) {
        return new SpeedChallengeUi(fragment, container, task, requestPermission);
    }

    private SpeedChallengeUi(DialogFragment fragment, LinearLayout container,
            QuestRepository.ChallengeInfo task, Runnable requestPermission) {
        this.fragment = fragment;
        this.task = task;
        this.requestPermission = requestPermission;
        TextView instructions = new TextView(fragment.requireContext());
        instructions.setText(R.string.bench_speed_instructions);
        container.addView(instructions);
        speed = new TextView(fragment.requireContext());
        speed.setTextSize(26);
        speed.setText(R.string.bench_speed_unknown);
        container.addView(speed);
        progress = new ProgressBar(fragment.requireContext(), null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        container.addView(progress, new LinearLayout.LayoutParams(-1, -2));
        status = new TextView(fragment.requireContext());
        status.setText(R.string.bench_ready);
        container.addView(status);
        start = new Button(fragment.requireContext());
        start.setText(R.string.bench_start);
        start.setMinHeight((int) (64 * fragment.getResources().getDisplayMetrics().density));
        container.addView(start, new LinearLayout.LayoutParams(-1, -2));
        start.setOnClickListener(view -> {
            if (!fragment.requireContext().getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                status.setText(R.string.bench_unavailable);
            } else if (ContextCompat.checkSelfPermission(fragment.requireContext(), PERMISSION)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermission.run();
            } else begin();
        });
    }

    @Override public void onPermissionResult(boolean granted) {
        if (closed) return;
        if (granted) begin();
        else status.setText(R.string.bench_permission_denied);
    }

    private void begin() {
        if (closed || running) return;
        start.setEnabled(false);
        condition = new SpeedHoldCondition(SystemClock.elapsedRealtimeNanos());
        lastReceiptNs = SystemClock.elapsedRealtimeNanos();
        progress.setProgress(0);
        running = true;
        status.setText(R.string.bench_waiting);
        source = new BenchSpeedSource(fragment.requireContext(), new BenchSpeedSource.Listener() {
            @Override public void onSpeed(float value, long timestampNs) {
                main.post(() -> {
                    if (closed || !running) return;
                    long now = SystemClock.elapsedRealtimeNanos();
                    lastReceiptNs = now;
                    speed.setText(fragment.getString(R.string.bench_speed_value, value * 3.6f));
                    boolean done = condition.accept(value, timestampNs, now);
                    progress.setProgress(condition.progressPercent());
                    status.setText(R.string.bench_running);
                    if (done) complete();
                });
            }
            @Override public void onUnavailable() {
                main.post(() -> {
                    if (closed || !running) return;
                    condition.resetHold();
                    progress.setProgress(0);
                    speed.setText(R.string.bench_speed_unknown);
                    status.setText(R.string.bench_unavailable);
                });
            }
        });
        try {
            source.start();
            main.postDelayed(watchdog, 500);
        } catch (RuntimeException | LinkageError error) {
            stopSource();
            status.setText(R.string.bench_unavailable);
            start.setEnabled(true);
        }
    }

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (closed || !running) return;
            if (SystemClock.elapsedRealtimeNanos() - lastReceiptNs > SpeedHoldCondition.MAX_GAP_NS) {
                condition.resetHold();
                progress.setProgress(0);
                speed.setText(R.string.bench_speed_unknown);
                status.setText(R.string.bench_waiting);
            }
            main.postDelayed(this, 500);
        }
    };

    private void complete() {
        stopSource();
        status.setText(R.string.bench_saving);
        QuestRepository.getInstance(fragment.requireContext())
                .completeChallenge(task.quest.id, task.quest.version, task.step.id)
                .whenComplete((ignored, error) -> main.post(() -> {
                    if (closed) return;
                    status.setText(error == null ? R.string.bench_task_success : R.string.bench_unavailable);
                    start.setEnabled(error != null);
                    if (error == null) progress.setProgress(100);
                }));
    }

    private void stopSource() {
        running = false;
        main.removeCallbacks(watchdog);
        if (source != null) source.stop();
        source = null;
    }

    @Override public void close() {
        closed = true;
        stopSource();
        main.removeCallbacksAndMessages(null);
    }
}
