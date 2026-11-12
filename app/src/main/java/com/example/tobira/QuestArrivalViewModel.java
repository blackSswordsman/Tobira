package com.example.tobira;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import com.example.tobira.data.QuestArrival;
import com.example.tobira.data.QuestRepository;

/** State consumed by any design: no View IDs, strings, map styling or permission dialogs. */
public final class QuestArrivalViewModel extends AndroidViewModel {
    public enum Status { NO_TARGET, CHECKING, NO_PERMISSION, LOCATION_DISABLED, WAITING, OUTSIDE, SAVING, ARRIVED, ERROR }
    public static final class State {
        public final Status status;
        public final double distanceMeters;
        State(Status status, double distanceMeters) { this.status = status; this.distanceMeters = distanceMeters; }
        public boolean canOpenChallenge() { return status == Status.ARRIVED; }
    }

    private final QuestRepository repository;
    private final MediatorLiveData<State> state = new MediatorLiveData<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrivalPolicy policy = new ArrivalPolicy();
    private LiveData<QuestArrival> arrivalSource;
    private QuestArrivalTarget target;
    private ArrivalPolicy.Fix latest;
    private boolean recorded, loaded, saving;
    private Status unavailable = Status.WAITING;
    private int generation;

    public QuestArrivalViewModel(@NonNull Application application) {
        super(application);
        repository = QuestRepository.getInstance(application);
        state.setValue(new State(Status.NO_TARGET, -1));
    }

    public LiveData<State> getState() { return state; }
    public boolean canOpenChallenge() {
        return state.getValue() != null && state.getValue().canOpenChallenge();
    }

    public void selectTarget(Place point) {
        QuestArrivalTarget selected = QuestArrivalTarget.from(point);
        if (target != null && target.matches(selected)) return;
        if (arrivalSource != null) state.removeSource(arrivalSource);
        generation++;
        target = selected;
        recorded = loaded = saving = false;
        if (target == null) { state.setValue(new State(Status.NO_TARGET, -1)); return; }
        state.setValue(new State(Status.CHECKING, -1));
        arrivalSource = repository.observeArrival(target);
        state.addSource(arrivalSource, arrival -> {
            loaded = true;
            recorded = arrival != null;
            evaluate();
        });
    }

    public void onFix(ArrivalPolicy.Fix fix) {
        if (latest != null && fix != null && fix.elapsedNs < latest.elapsedNs) return;
        latest = fix;
        unavailable = Status.WAITING;
        evaluate();
    }

    public void onUnavailable(Status reason) {
        latest = null;
        unavailable = reason;
        evaluate();
    }

    public void recheck() { evaluate(); }

    private void evaluate() {
        if (target == null) { state.setValue(new State(Status.NO_TARGET, -1)); return; }
        if (recorded) { state.setValue(new State(Status.ARRIVED, -1)); return; }
        if (!loaded) { state.setValue(new State(Status.CHECKING, -1)); return; }
        if (saving) { state.setValue(new State(Status.SAVING, -1)); return; }
        if (latest == null) { state.setValue(new State(unavailable, -1)); return; }
        long now = SystemClock.elapsedRealtimeNanos();
        ArrivalPolicy.Check check = policy.check(target.latitude, target.longitude, latest, now);
        if (!check.usable) { state.setValue(new State(Status.WAITING, -1)); return; }
        if (!check.arrived) {
            state.setValue(new State(check.distance <= ArrivalPolicy.RADIUS_METERS
                    ? Status.WAITING : Status.OUTSIDE, check.distance));
            return;
        }
        saving = true;
        state.setValue(new State(Status.SAVING, check.distance));
        int token = generation;
        repository.recordArrival(target, latest, now).whenComplete((saved, error) -> main.post(() -> {
            if (token != generation) return;
            saving = false;
            if (error != null) state.setValue(new State(Status.ERROR, -1));
            else {
                recorded = Boolean.TRUE.equals(saved);
                evaluate();
            }
        }));
    }

    @Override protected void onCleared() {
        generation++;
        main.removeCallbacksAndMessages(null);
    }
}
