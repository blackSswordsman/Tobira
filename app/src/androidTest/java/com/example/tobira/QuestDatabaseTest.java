package com.example.tobira;

import android.content.Context;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.tobira.data.QuestDatabase;
import com.example.tobira.data.QuestEntities.*;
import com.example.tobira.data.QuestEntities.Place;
import com.example.tobira.data.QuestRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class QuestDatabaseTest {
    private Context context;
    private String databaseName;
    private QuestDatabase database;
    private QuestRepository repository;

    @Before
    public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseName = "quest-storage-test-" + UUID.randomUUID() + ".db";
        open();
        repository.initialize().get(10, TimeUnit.SECONDS);
    }

    private void open() {
        database = Room.databaseBuilder(context, QuestDatabase.class, databaseName).build();
        repository = new QuestRepository(database);
    }

    @After
    public void tearDown() {
        if (database != null) database.close();
        if (context != null && databaseName != null) context.deleteDatabase(databaseName);
    }

    private boolean importVersion(int version, boolean published) throws Exception {
        return repository.importQuest(
                new Quest("test-quest", version, "テストクエスト", "試験用データ", "ja", published),
                Collections.singletonList(new Place("test-place", "テスト地点", 35, 132)),
                Collections.singletonList(new PlaceKey("osm", "way", "123", "test-place")),
                Collections.singletonList(new PlaceQuest("test-place", "test-quest", version)),
                Arrays.asList(
                        new Step("test-quest", version, "challenge", 1, Step.CHALLENGE,
                                "試験用の問題", "ヒント", "{\"property\":\"WINDOW_POS\",\"target\":8}"),
                        new Step("test-quest", version, "observation", 0, Step.OBSERVATION,
                                "試験用の観察項目", null, null)))
                .get(10, TimeUnit.SECONDS);
    }

    @Test
    public void matchesExternalKeyAndOrdersObservationsBeforeChallenges() throws Exception {
        importVersion(1, true);
        List<Quest> quests = repository.findQuests("osm", "way", "123").get(10, TimeUnit.SECONDS);
        assertEquals(1, quests.size());
        assertTrue(repository.findQuests("osm", "node", "123").get(10, TimeUnit.SECONDS).isEmpty());
        List<Step> steps = repository.getSteps("test-quest", 1).get(10, TimeUnit.SECONDS);
        assertEquals(Step.OBSERVATION, steps.get(0).kind);
        assertEquals(Step.CHALLENGE, steps.get(1).kind);
    }

    @Test
    public void dataAndCompletedStepsSurviveDatabaseReopen() throws Exception {
        importVersion(1, true);
        repository.startQuest("test-quest", 1).get(10, TimeUnit.SECONDS);
        repository.savePhase("test-quest", 1, Progress.CHALLENGE).get(10, TimeUnit.SECONDS);
        repository.completeStep("test-quest", 1, "challenge").get(10, TimeUnit.SECONDS);
        database.close();
        open();
        assertEquals(Progress.CHALLENGE,
                repository.getProgress("test-quest", 1).get(10, TimeUnit.SECONDS).phase);
        assertEquals("challenge",
                repository.getCompletedSteps("test-quest", 1).get(10, TimeUnit.SECONDS).get(0).stepId);
        assertEquals(1, repository.findQuests("osm", "way", "123").get(10, TimeUnit.SECONDS).size());
    }

    @Test
    public void reimportAndResumeDoNotResetProgress() throws Exception {
        assertTrue(importVersion(1, true));
        repository.startQuest("test-quest", 1).get(10, TimeUnit.SECONDS);
        repository.savePhase("test-quest", 1, Progress.VISITING).get(10, TimeUnit.SECONDS);
        assertFalse(importVersion(1, true));
        assertEquals(Progress.VISITING,
                repository.startQuest("test-quest", 1).get(10, TimeUnit.SECONDS).phase);
    }

    @Test
    public void newRevisionDoesNotOverwriteOldProgressAndDraftIsNotOffered() throws Exception {
        importVersion(1, true);
        repository.startQuest("test-quest", 1).get(10, TimeUnit.SECONDS);
        repository.completeStep("test-quest", 1, "challenge").get(10, TimeUnit.SECONDS);
        importVersion(2, true);
        importVersion(3, false);
        assertEquals(2, repository.findQuests("osm", "way", "123")
                .get(10, TimeUnit.SECONDS).get(0).version);
        assertEquals(1, repository.getCompletedSteps("test-quest", 1).get(10, TimeUnit.SECONDS).size());
        assertNull(repository.getProgress("test-quest", 2).get(10, TimeUnit.SECONDS));
    }

    @Test
    public void completingStepTwiceIsIdempotentAndResetRemovesOnlyProgress() throws Exception {
        importVersion(1, true);
        repository.startQuest("test-quest", 1).get(10, TimeUnit.SECONDS);
        repository.completeStep("test-quest", 1, "challenge").get(10, TimeUnit.SECONDS);
        repository.completeStep("test-quest", 1, "challenge").get(10, TimeUnit.SECONDS);
        assertEquals(1, repository.getCompletedSteps("test-quest", 1).get(10, TimeUnit.SECONDS).size());
        repository.resetProgress("test-quest", 1).get(10, TimeUnit.SECONDS);
        assertNull(repository.getProgress("test-quest", 1).get(10, TimeUnit.SECONDS));
        assertTrue(repository.getCompletedSteps("test-quest", 1).get(10, TimeUnit.SECONDS).isEmpty());
        assertEquals(2, repository.getSteps("test-quest", 1).get(10, TimeUnit.SECONDS).size());
    }

    @Test
    public void failedImportRollsBackPlacesAndQuestTogether() throws Exception {
        try {
            repository.importQuest(new Quest("broken", 1, "Broken", "", "ja", true),
                    Collections.singletonList(new Place("temporary", "Temporary", 1, 2)),
                    Collections.singletonList(new PlaceKey("osm", "node", "456", "temporary")),
                    Collections.singletonList(new PlaceQuest("missing-place", "broken", 1)),
                    Collections.emptyList()).get(10, TimeUnit.SECONDS);
            fail("Expected a foreign-key failure");
        } catch (ExecutionException expected) {
            assertNull(database.quests().getQuest("broken", 1));
            assertNull(database.quests().findPlace("osm", "node", "456"));
        }
    }

    @Test
    public void cannotCompleteMissingStep() throws Exception {
        importVersion(1, true);
        repository.startQuest("test-quest", 1).get(10, TimeUnit.SECONDS);
        try {
            repository.completeStep("test-quest", 1, "not-a-step").get(10, TimeUnit.SECONDS);
            fail("Expected a foreign-key failure");
        } catch (ExecutionException expected) {
            assertTrue(repository.getCompletedSteps("test-quest", 1).get(10, TimeUnit.SECONDS).isEmpty());
        }
    }

    @Test
    public void placeIdentitySurvivesSearchAndBundleRoundTrip() throws Exception {
        com.example.tobira.Place searched = com.example.tobira.Place.fromPhotonJson(
                new org.json.JSONObject("{\"properties\":{\"name\":\"Test\",\"osm_type\":\"W\","
                        + "\"osm_id\":123},\"geometry\":{\"type\":\"Point\",\"coordinates\":[132,35]}}"));
        com.example.tobira.Place restored = com.example.tobira.Place.fromBundle(searched.toBundle());
        assertNotNull(restored);
        assertEquals("way", restored.osmType);
        assertEquals("123", restored.osmId);
        importVersion(1, true);
        assertEquals(1, repository.findQuestsForPlace(restored).get(10, TimeUnit.SECONDS).size());
        assertTrue(repository.findQuestsForPlace(
                new com.example.tobira.Place("Unknown", "", 35, 132)).get(10, TimeUnit.SECONDS).isEmpty());
    }
}
