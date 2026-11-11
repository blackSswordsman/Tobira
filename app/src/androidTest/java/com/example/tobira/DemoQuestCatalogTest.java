package com.example.tobira;

import android.content.Context;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.tobira.data.QuestDatabase;
import com.example.tobira.data.QuestEntities.Progress;
import com.example.tobira.data.QuestEntities.Quest;
import com.example.tobira.data.QuestEntities.Step;
import com.example.tobira.data.QuestRepository;
import com.example.tobira.debug.DemoQuestCatalog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DemoQuestCatalogTest {
    private Context context;
    private String databaseName;
    private QuestDatabase database;
    private QuestRepository repository;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseName = "demo-quest-test-" + UUID.randomUUID() + ".db";
        database = Room.databaseBuilder(context, QuestDatabase.class, databaseName).build();
        repository = new QuestRepository(database);
    }

    @After
    public void tearDown() {
        database.close();
        context.deleteDatabase(databaseName);
    }

    @Test
    public void installsClearlyMarkedDraftWithThreeObservationsAndThreeChallenges() throws Exception {
        assertTrue(DemoQuestCatalog.install(repository).get(10, TimeUnit.SECONDS));
        Quest quest = database.quests().getQuest(DemoQuestCatalog.QUEST_ID, DemoQuestCatalog.VERSION);
        assertNotNull(quest);
        assertFalse(quest.published);
        assertTrue(quest.title.contains("テスト用"));
        List<Step> steps = repository.getSteps(quest.id, quest.version).get(10, TimeUnit.SECONDS);
        assertEquals(6, steps.size());
        for (int index = 0; index < 3; index++) assertEquals(Step.OBSERVATION, steps.get(index).kind);
        for (int index = 3; index < 6; index++) {
            assertEquals(Step.CHALLENGE, steps.get(index).kind);
            assertNotNull(steps.get(index).hint);
            assertNotNull(steps.get(index).conditionJson);
        }
        assertNull(repository.getProgress(quest.id, quest.version).get(10, TimeUnit.SECONDS));
    }

    @Test
    public void startupReimportPreservesSavedProgressAndCompletions() throws Exception {
        DemoQuestCatalog.install(repository).get(10, TimeUnit.SECONDS);
        String id = DemoQuestCatalog.QUEST_ID;
        int version = DemoQuestCatalog.VERSION;
        repository.startQuest(id, version).get(10, TimeUnit.SECONDS);
        repository.savePhase(id, version, Progress.CHALLENGE).get(10, TimeUnit.SECONDS);
        repository.completeStep(id, version, "challenge-window").get(10, TimeUnit.SECONDS);
        assertFalse(DemoQuestCatalog.install(repository).get(10, TimeUnit.SECONDS));
        assertEquals(Progress.CHALLENGE,
                repository.getProgress(id, version).get(10, TimeUnit.SECONDS).phase);
        assertEquals(1, repository.getCompletedSteps(id, version).get(10, TimeUnit.SECONDS).size());
    }
}
