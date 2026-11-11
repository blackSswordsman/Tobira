package com.example.tobira;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.example.tobira.data.QuestRepository;
import com.example.tobira.data.QuestRouteEntities.Route;
import java.util.List;

/** Keeps asynchronous reads out of the Activity and ignores obsolete responses. */
public final class RouteCatalogViewModel extends AndroidViewModel {
    private final QuestRepository repository;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MutableLiveData<QuestRepository.RouteDetails> details = new MutableLiveData<>();
    private final MutableLiveData<Boolean> failed = new MutableLiveData<>(false);
    private final MutableLiveData<RoutePlan> preparedPlan = new MutableLiveData<>();
    private final MutableLiveData<Boolean> preparing = new MutableLiveData<>(false);
    public LiveData<RoutePlan> preparedPlan() { return preparedPlan; }
    public LiveData<Boolean> preparing() { return preparing; }
    public void consumePlan() { preparedPlan.setValue(null); }

    public void prepareRoute(String id, int version) {
        if (Boolean.TRUE.equals(preparing.getValue())) return;
        preparing.setValue(true);
        repository.getRoutePlan(id, version).whenComplete((plan, error) -> main.post(() -> {
            if (cleared) return;
            preparing.setValue(false);
            if (error != null) failed.setValue(true);
            else preparedPlan.setValue(plan);
        }));
    }

    private int request;
    private boolean cleared;

    public RouteCatalogViewModel(@NonNull Application application) {
        super(application);
        repository = QuestRepository.getInstance(application);
        initialize();
    }

    public LiveData<List<Route>> routes() { return repository.observeRoutes(); }
    public LiveData<QuestRepository.RouteDetails> details() { return details; }
    public LiveData<Boolean> failed() { return failed; }

    public void initialize() {
        failed.setValue(false);
        repository.initialize().whenComplete((ignored, error) -> main.post(() -> {
            if (!cleared && error != null) failed.setValue(true);
        }));
    }

    public void load(String id, int version) {
        int current = ++request;
        failed.setValue(false);
        repository.getRouteDetails(id, version).whenComplete((value, error) -> main.post(() -> {
            if (cleared || current != request) return;
            if (error != null) failed.setValue(true);
            else details.setValue(value);
        }));
    }

    @Override protected void onCleared() {
        cleared = true;
        main.removeCallbacksAndMessages(null);
    }
}
