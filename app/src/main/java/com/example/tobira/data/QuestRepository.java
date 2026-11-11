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

    public LiveData<List<QuestRouteEntities.Route>> observeRoutes() {
        return database.routes().observeRoutes();
    }

    /** Imports the whole draft atomically. Existing revisions and player progress stay intact. */
    public CompletableFuture<Boolean> importRoute(QuestRouteEntities.Route route,
            List<QuestRouteEntities.Member> members, List<Quest> quests, List<Step> steps) {
        return submit(() -> database.runInTransaction(() -> {
            if (database.routes().getRoute(route.id, route.version) != null) return false;
            if (members.size() < 2 || members.stream().filter(m -> m.finale).count() != 1)
                throw new IllegalArgumentException("Route needs one finale and prerequisites");
            java.util.Set<String> memberIds = new java.util.HashSet<>();
            int lastPosition = members.stream().mapToInt(m -> m.position).max().orElse(-1);
            for (QuestRouteEntities.Member member : members) {
                if (!route.id.equals(member.routeId) || route.version != member.routeVersion)
                    throw new IllegalArgumentException("Member belongs to another route");
                if (!memberIds.add(member.questId))
                    throw new IllegalArgumentException("Duplicate quest in route");
                if (member.finale && member.position != lastPosition)
                    throw new IllegalArgumentException("Finale must be last in recommended order");
            }
            List<Quest> added = new java.util.ArrayList<>();
            for (Quest quest : quests) {
                if (dao.getQuest(quest.id, quest.version) == null) {
                    dao.addQuest(quest);
                    added.add(quest);
                }
            }
            for (Step step : steps) {
                if (quests.stream().noneMatch(q -> q.id.equals(step.questId)
                        && q.version == step.questVersion))
                    throw new IllegalArgumentException("Step belongs to another quest");
            }
            // Content is immutable: only add steps to revisions created by this import.
            for (Quest quest : added) {
                dao.addSteps(steps.stream().filter(s -> s.questId.equals(quest.id)
                        && s.questVersion == quest.version)
                        .collect(java.util.stream.Collectors.toList()));
            }
            database.routes().addRoute(route);
            database.routes().addMembers(members);
            return true;
        }));
    }

    /** A consistent read for the route screen; never starts or changes a playthrough. */
    public CompletableFuture<RouteDetails> getRouteDetails(String id, int version) {
        return submit(() -> database.runInTransaction(() -> {
            QuestRouteEntities.Route route = database.routes().getRoute(id, version);
            if (route == null) throw new IllegalArgumentException("Unknown route revision");
            List<RouteStop> stops = new java.util.ArrayList<>();
            for (QuestRouteEntities.Member member : database.routes().getMembers(id, version)) {
                Quest quest = dao.getQuest(member.questId, member.questVersion);
                stops.add(new RouteStop(member, quest, dao.getProgress(member.questId,
                        member.questVersion), dao.getSteps(member.questId, member.questVersion)));
            }
            return new RouteDetails(route, routeStatus(id, version), stops);
        }));
    }

    public static final class RouteDetails {
        public final QuestRouteEntities.Route route;
        public final RouteStatus status;
        public final List<RouteStop> stops;

        RouteDetails(QuestRouteEntities.Route route, RouteStatus status, List<RouteStop> stops) {
            this.route = route;
            this.status = status;
            this.stops = java.util.Collections.unmodifiableList(stops);
        }
    }

    public static final class RouteStop {
        public final QuestRouteEntities.Member member;
        public final Quest quest;
        public final Progress progress;
        public final List<Step> steps;

        RouteStop(QuestRouteEntities.Member member, Quest quest, Progress progress, List<Step> steps) {
            this.member = member;
            this.quest = quest;
            this.progress = progress;
            this.steps = java.util.Collections.unmodifiableList(steps);
        }
    }

    /** Adds curated locations to existing drafts, including installations created before locations existed. */
    public CompletableFuture<Void> linkRoutePlaces(List<Place> places, List<PlaceQuest> links) {
        return submit(() -> database.runInTransaction(() -> {
            dao.putPlaces(places);
            dao.ensurePlaceLinks(links);
            return null;
        }));
    }

    public CompletableFuture<com.example.tobira.RoutePlan> getRoutePlan(String id, int version) {
        return submit(() -> database.runInTransaction(() -> {
            routeStatus(id, version); // Validate the revision, without unlocking or starting quests.
            com.example.tobira.RoutePlan plan = new com.example.tobira.RoutePlan();
            plan.questRouteId = id;
            plan.questRouteVersion = version;
            for (QuestRouteEntities.Member member : database.routes().getMembers(id, version)) {
                List<Place> places = dao.getPlacesForQuest(member.questId, member.questVersion);
                if (places.size() != 1) throw new IllegalStateException("Route location is not configured");
                Place place = places.get(0);
                com.example.tobira.Place point = new com.example.tobira.Place(place.name, place.name,
                        place.latitude, place.longitude).withQuest(member.questId, member.questVersion);
                if (member.finale) plan.destination = point;
                else plan.stops.add(point);
            }
            if (!plan.isComplete() || plan.stops.size() > com.example.tobira.RoutePlan.MAX_STOPS)
                throw new IllegalStateException("Invalid quest itinerary");
            return plan;
        }));
    }

    public CompletableFuture<RouteStatus> getRouteStatus(String id, int version) {
        return submit(() -> database.runInTransaction(() -> routeStatus(id, version)));
    }

    private RouteStatus routeStatus(String id, int version) {
        if (database.routes().getRoute(id, version) == null)
            throw new IllegalArgumentException("Unknown route revision");
        int required = 0;
        int completed = 0;
        boolean finaleCompleted = false;
        for (QuestRouteEntities.Member member : database.routes().getMembers(id, version)) {
            Progress progress = dao.getProgress(member.questId, member.questVersion);
            boolean done = progress != null && Progress.COMPLETED.equals(progress.phase);
            if (member.finale) finaleCompleted = done;
            else {
                required++;
                if (done) completed++;
            }
        }
        return new RouteStatus(required, completed, finaleCompleted);
    }

    public static final class RouteStatus {
        public final int required;
        public final int completed;
        public final boolean finaleUnlocked;
        public final boolean completedRoute;

        RouteStatus(int required, int completed, boolean finaleCompleted) {
            this.required = required;
            this.completed = completed;
            finaleUnlocked = required > 0 && completed == required;
            completedRoute = finaleUnlocked && finaleCompleted;
        }
    }

    private void requireUnlocked(String id, int version) {
        for (QuestRouteEntities.Member member : database.routes().getFinaleMemberships(id, version)) {
            if (!routeStatus(member.routeId, member.routeVersion).finaleUnlocked)
                throw new IllegalStateException("Final quest is locked");
        }
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

    /** Manual return confirmation. Never completes a task or downgrades a completed quest. */
    public CompletableFuture<ChallengeInfo> openChallenge(String id, int version) {
        return submit(() -> database.runInTransaction(() -> {
            requireQuest(id, version);
            requireUnlocked(id, version);
            List<Step> challenges = dao.getSteps(id, version).stream()
                    .filter(step -> Step.CHALLENGE.equals(step.kind))
                    .collect(java.util.stream.Collectors.toList());
            java.util.Set<String> completed = new java.util.HashSet<>();
            for (CompletedStep step : dao.getCompletedSteps(id, version)) completed.add(step.stepId);
            Progress progress = dao.getProgress(id, version);
            Step next = null;
            int position = 0;
            if (progress == null || !Progress.COMPLETED.equals(progress.phase)) {
                for (int index = 0; index < challenges.size(); index++) {
                    if (!completed.contains(challenges.get(index).id)) {
                        next = challenges.get(index);
                        position = index + 1;
                        break;
                    }
                }
            }
            if (next != null) {
                dao.putProgress(new Progress(id, version, Progress.CHALLENGE, System.currentTimeMillis()));
            }
            return new ChallengeInfo(dao.getQuest(id, version), next, position, challenges.size());
        }));
    }

    public static final class ChallengeInfo {
        public final Quest quest;
        public final Step step;
        public final int position;
        public final int total;

        ChallengeInfo(Quest quest, Step step, int position, int total) {
            this.quest = quest;
            this.step = step;
            this.position = position;
            this.total = total;
        }
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
            requireUnlocked(id, version);
            dao.startProgress(new Progress(id, version, Progress.PLANNED, System.currentTimeMillis()));
            return dao.getProgress(id, version);
        }));
    }

    /** Persists a checkpoint; the future quest engine is responsible for validating transitions. */
    public CompletableFuture<Void> savePhase(String id, int version, String phase) {
        return submit(() -> database.runInTransaction(() -> {
            requireUnlocked(id, version);
            if (dao.getProgress(id, version) == null) throw new IllegalStateException("Quest not started");
            dao.putProgress(new Progress(id, version, phase, System.currentTimeMillis()));
            return null;
        }));
    }

    public CompletableFuture<Void> completeStep(String id, int version, String stepId) {
        return submit(() -> database.runInTransaction(() -> {
            requireUnlocked(id, version);
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
