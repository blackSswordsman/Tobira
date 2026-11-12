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
        /** Text of the first CHALLENGE step: the quest's goal. Empty when there is none. */
        public final String goal;
        /** conditionJson of that challenge step (the "Enter Your Command" steps), or null. */
        public final String commands;
        public final List<String> observations;

        private State(Status status, String title, String introduction, String goal, String commands,
                      List<String> observations, String questId, int questVersion) {
            this.goal = goal;
            this.commands = commands;
            this.questId = questId;
            this.questVersion = questVersion;
            this.status = status;
            this.title = title;
            this.introduction = introduction;
            this.observations = Collections.unmodifiableList(new ArrayList<>(observations));
        }
    }

    /** A searched place within this distance of a quest place shows that quest. */
    private static final float MATCH_RADIUS_METERS = 300f;
    private final MediatorLiveData<State> state = new MediatorLiveData<>();
    private final QuestRepository repository;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int generation;
    private int selection;
    private String selectedRevision;
    /** Quest to show; null means the place has no quest (shows EMPTY, never a demo quest). */
    private String desiredQuestId;
    private int desiredVersion;
    private List<Quest> lastCatalog = Collections.emptyList();

    public void selectQuest(Place point) {
        int token = ++selection;
        if (point == null) {
            applySelection(null, 0);
        } else if (point.questId != null) {
            applySelection(point.questId, point.questVersion);
        } else {
            // Place from map search: use the quest of a quest place nearby, if any.
            repository.findQuestNear(point.latitude, point.longitude, MATCH_RADIUS_METERS)
                    .whenComplete((match, error) -> main.post(() -> {
                        if (selection != token) return;
                        if (match == null) applySelection(null, 0);
                        else applySelection(match.questId, match.questVersion);
                    }));
        }
    }

    private void applySelection(String id, int version) {
        if (java.util.Objects.equals(id, desiredQuestId) && version == desiredVersion) return;
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
        if (catalog != null && desiredQuestId != null) {
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
            String goal = "";
            String commands = null;
            for (Step step : steps) {
                if (Step.OBSERVATION.equals(step.kind)) observations.add(step.text);
                else if (goal.isEmpty() && Step.CHALLENGE.equals(step.kind)) {
                    goal = step.text;
                    commands = step.conditionJson;
                }
            }
            state.setValue(new State(Status.CONTENT, quest.title, quest.introduction, goal, commands,
                    observations, quest.id, quest.version));
        }));
    }

    private static State message(Status status) {
        return new State(status, "", "", "", null, Collections.emptyList(), null, 0);
    }

    @Override
    protected void onCleared() {
        generation++;
    }
}
