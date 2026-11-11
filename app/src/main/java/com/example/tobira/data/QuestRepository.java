package com.example.tobira.data;

import android.content.Context;

import androidx.lifecycle.LiveData;

import com.example.tobira.data.QuestEntities.*;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** All writes and one-shot reads run off the UI thread and report failures through futures. */
public final class QuestRepository {
    private static volatile QuestRepository instance;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "quest-storage"));
    private final QuestDatabase database;
    private final QuestDao dao;

    public QuestRepository(QuestDatabase database) {
        this.database = database;
        this.dao = database.quests();
    }

    public static QuestRepository getInstance(Context context) {
        if (instance == null) {
            synchronized (QuestRepository.class) {
                if (instance == null) instance = new QuestRepository(QuestDatabase.getInstance(context));
            }
        }
        return instance;
    }

    public CompletableFuture<Void> initialize() {
        return submit(() -> {
            database.getOpenHelper().getWritableDatabase();
            return null;
        });
    }

    public LiveData<List<Quest>> observeCatalog() {
        return dao.observeCatalog();
    }

    public LiveData<Progress> observeProgress(String id, int version) {
        return dao.observeProgress(id, version);
    }

    /** Imports one new revision atomically; importing it again never overwrites content or progress. */
    public CompletableFuture<Boolean> importQuest(Quest quest, List<Place> places, List<PlaceKey> keys,
                                                  List<PlaceQuest> links, List<Step> steps) {
        return submit(() -> database.runInTransaction(() -> {
            if (dao.getQuest(quest.id, quest.version) != null) return false;
            for (Step step : steps) {
                if (!quest.id.equals(step.questId) || quest.version != step.questVersion) {
                    throw new IllegalArgumentException("Step belongs to another quest");
                }
            }
            for (PlaceQuest link : links) {
                if (!quest.id.equals(link.questId) || quest.version != link.questVersion) {
                    throw new IllegalArgumentException("Link belongs to another quest");
                }
            }
            dao.putPlaces(places);
            for (PlaceKey key : keys) {
                Place existing = dao.findPlace(key.provider, key.objectType, key.externalId);
                if (existing == null) {
                    dao.addPlaceKeys(Collections.singletonList(key));
                } else if (!existing.id.equals(key.placeId)) {
                    throw new IllegalArgumentException("External place key already assigned");
                }
            }
            dao.addQuest(quest);
            dao.addSteps(steps);
            dao.linkPlaces(links);
            return true;
        }));
    }

    public CompletableFuture<List<Quest>> findQuestsForPlace(com.example.tobira.Place place) {
        if (place.osmType == null || place.osmId == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        return findQuests("osm", place.osmType, place.osmId);
    }

    public CompletableFuture<List<Quest>> findQuests(String provider, String type, String externalId) {
        return submit(() -> {
            Place place = dao.findPlace(provider, type, externalId);
            return place == null ? Collections.emptyList() : dao.getQuestsForPlace(place.id);
        });
    }

    public CompletableFuture<List<Step>> getSteps(String id, int version) {
        return submit(() -> dao.getSteps(id, version));
    }

    public CompletableFuture<Progress> getProgress(String id, int version) {
        return submit(() -> dao.getProgress(id, version));
    }

    /** Starting/resuming never resets an existing session. */
    public CompletableFuture<Progress> startQuest(String id, int version) {
        return submit(() -> database.runInTransaction(() -> {
            requireQuest(id, version);
            dao.startProgress(new Progress(id, version, Progress.PLANNED, System.currentTimeMillis()));
            return dao.getProgress(id, version);
        }));
    }

    /** Persists a checkpoint; the future quest engine is responsible for validating transitions. */
    public CompletableFuture<Void> savePhase(String id, int version, String phase) {
        return submit(() -> database.runInTransaction(() -> {
            if (dao.getProgress(id, version) == null) throw new IllegalStateException("Quest not started");
            dao.putProgress(new Progress(id, version, phase, System.currentTimeMillis()));
            return null;
        }));
    }

    public CompletableFuture<Void> completeStep(String id, int version, String stepId) {
        return submit(() -> database.runInTransaction(() -> {
            Progress progress = dao.getProgress(id, version);
            if (progress == null) throw new IllegalStateException("Quest not started");
            long now = System.currentTimeMillis();
            dao.completeStep(new CompletedStep(id, version, stepId, now));
            dao.putProgress(new Progress(id, version, progress.phase, now));
            return null;
        }));
    }

    public CompletableFuture<List<CompletedStep>> getCompletedSteps(String id, int version) {
        return submit(() -> dao.getCompletedSteps(id, version));
    }

    public CompletableFuture<Void> resetProgress(String id, int version) {
        return submit(() -> {
            dao.resetProgress(id, version);
            return null;
        });
    }

    private void requireQuest(String id, int version) {
        if (dao.getQuest(id, version) == null) throw new IllegalArgumentException("Unknown quest revision");
    }

    private <T> CompletableFuture<T> submit(Supplier<T> operation) {
        return CompletableFuture.supplyAsync(operation, IO);
    }
}
