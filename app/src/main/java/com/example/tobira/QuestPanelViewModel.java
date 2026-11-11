package com.example.tobira;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.LiveData;

import com.example.tobira.data.QuestEntities.Quest;
import com.example.tobira.data.QuestEntities.Step;
import com.example.tobira.data.QuestRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Read-only demo preview. It neither starts a quest session nor validates vehicle actions. */
public final class QuestPanelViewModel extends AndroidViewModel {
    public enum Status { LOADING, CONTENT, EMPTY, ERROR }

    public static final class State {
        public final Status status;
        public final String questId;
        public final int questVersion;
        public final String title;
        public final String introduction;
        public final List<String> observations;

        private State(Status status, String title, String introduction, List<String> observations,
                      String questId, int questVersion) {
            this.questId = questId;
            this.questVersion = questVersion;
            this.status = status;
            this.title = title;
            this.introduction = introduction;
            this.observations = Collections.unmodifiableList(new ArrayList<>(observations));
        }
    }

    private static final String DEMO_QUEST_ID = "debug-demo-quest";
    private final MediatorLiveData<State> state = new MediatorLiveData<>();
    private final QuestRepository repository;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int generation;
    private String selectedRevision;
    private String desiredQuestId = DEMO_QUEST_ID;
    private int desiredVersion;
    private List<Quest> lastCatalog = Collections.emptyList();

    public void selectQuest(Place point) {
        String id = point == null || point.questId == null ? DEMO_QUEST_ID : point.questId;
        int version = point == null ? 0 : point.questVersion;
        if (id.equals(desiredQuestId) && version == desiredVersion) return;
        desiredQuestId = id;
        desiredVersion = version;
        selectedRevision = null;
        generation++;
        loadDemo(lastCatalog);
    }

    public QuestPanelViewModel(@NonNull Application application) {
        super(application);
        repository = QuestRepository.getInstance(application);
        boolean debug = (application.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        state.setValue(message(debug ? Status.LOADING : Status.EMPTY));
        if (debug) state.addSource(repository.observeCatalog(), this::loadDemo);
    }

    public LiveData<State> getState() {
        return state;
    }

    private void loadDemo(List<Quest> catalog) {
        lastCatalog = catalog == null ? Collections.emptyList() : catalog;
        Quest selected = null;
        if (catalog != null) {
            for (Quest quest : catalog) {
                if (desiredQuestId.equals(quest.id)
                        && (desiredVersion == 0 || desiredVersion == quest.version)
                        && (selected == null || quest.version > selected.version)) selected = quest;
            }
        }
        if (selected == null) {
            generation++;
            selectedRevision = null;
            state.setValue(message(Status.EMPTY));
            return;
        }
        String revision = selected.id + ":" + selected.version;
        if (revision.equals(selectedRevision)) return;
        selectedRevision = revision;
        int token = ++generation;
        Quest quest = selected;
        state.setValue(message(Status.LOADING));
        repository.getSteps(quest.id, quest.version).whenComplete((steps, error) -> main.post(() -> {
            if (generation != token) return;
            if (error != null) {
                selectedRevision = null;
                state.setValue(message(Status.ERROR));
                return;
            }
            List<String> observations = new ArrayList<>();
            for (Step step : steps) {
                if (Step.OBSERVATION.equals(step.kind)) observations.add(step.text);
            }
            state.setValue(new State(Status.CONTENT, quest.title, quest.introduction, observations, quest.id, quest.version));
        }));
    }

    private static State message(Status status) {
        return new State(status, "", "", Collections.emptyList(), null, 0);
    }

    @Override
    protected void onCleared() {
        generation++;
    }
}
