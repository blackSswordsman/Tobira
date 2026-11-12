package com.example.tobira.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Upsert;

import java.util.List;

import com.example.tobira.data.QuestEntities.*;

/** Synchronous methods must be called on the repository's worker; LiveData queries are asynchronous. */
@Dao
public interface QuestDao {
    @Query("SELECT p.* FROM quest_places p INNER JOIN place_quests pq ON p.id = pq.placeId "
            + "WHERE pq.questId = :id AND pq.questVersion = :version ORDER BY p.id")
    List<Place> getPlacesForQuest(String id, int version);

    @Insert(onConflict = OnConflictStrategy.IGNORE) void ensurePlaceLinks(List<PlaceQuest> links);
    @Insert(onConflict = OnConflictStrategy.IGNORE) long recordArrival(QuestArrival arrival);

    @Query("SELECT * FROM quest_arrivals WHERE questId = :id AND questVersion = :version AND placeKey = :key")
    QuestArrival getArrival(String id, int version, String key);

    @Query("SELECT * FROM quest_arrivals WHERE questId = :id AND questVersion = :version AND placeKey = :key")
    LiveData<QuestArrival> observeArrival(String id, int version, String key);

    @Query("DELETE FROM quest_arrivals WHERE questId = :id AND questVersion = :version")
    void resetArrivals(String id, int version);

    @Upsert void putPlaces(List<Place> places);
    @Insert(onConflict = OnConflictStrategy.ABORT) void addPlaceKeys(List<PlaceKey> keys);
    @Insert(onConflict = OnConflictStrategy.ABORT) void addQuest(Quest quest);
    @Insert(onConflict = OnConflictStrategy.ABORT) void addSteps(List<Step> steps);
    @Insert(onConflict = OnConflictStrategy.ABORT) void linkPlaces(List<PlaceQuest> links);

    @Query("SELECT * FROM quests WHERE id = :id AND version = :version")
    Quest getQuest(String id, int version);

    @Query("SELECT * FROM quests ORDER BY title, version DESC")
    LiveData<List<Quest>> observeCatalog();

    @Query("SELECT * FROM quest_steps WHERE questId = :id AND questVersion = :version ORDER BY position")
    List<Step> getSteps(String id, int version);

    @Query("SELECT p.* FROM quest_places p INNER JOIN place_external_keys k ON p.id = k.placeId "
            + "WHERE k.provider = :provider AND k.objectType = :type AND k.externalId = :externalId")
    Place findPlace(String provider, String type, String externalId);

    @Query("SELECT q.* FROM quests q INNER JOIN place_quests pq "
            + "ON q.id = pq.questId AND q.version = pq.questVersion "
            + "WHERE pq.placeId = :placeId AND q.published = 1 "
            + "AND q.version = (SELECT MAX(q2.version) FROM quests q2 INNER JOIN place_quests pq2 "
            + "ON q2.id = pq2.questId AND q2.version = pq2.questVersion "
            + "WHERE pq2.placeId = :placeId AND q2.id = q.id AND q2.published = 1) "
            + "ORDER BY q.title")
    List<Quest> getQuestsForPlace(String placeId);

    @Insert(onConflict = OnConflictStrategy.IGNORE) long startProgress(Progress progress);
    @Upsert void putProgress(Progress progress);

    @Query("SELECT * FROM quest_progress WHERE questId = :id AND questVersion = :version")
    Progress getProgress(String id, int version);

    @Query("SELECT * FROM quest_progress WHERE questId = :id AND questVersion = :version")
    LiveData<Progress> observeProgress(String id, int version);

    @Insert(onConflict = OnConflictStrategy.IGNORE) void completeStep(CompletedStep step);

    @Query("SELECT * FROM completed_quest_steps WHERE questId = :id AND questVersion = :version "
            + "ORDER BY completedAt, stepId")
    List<CompletedStep> getCompletedSteps(String id, int version);

    @Query("DELETE FROM quest_progress WHERE questId = :id AND questVersion = :version")
    void resetProgress(String id, int version);
}
