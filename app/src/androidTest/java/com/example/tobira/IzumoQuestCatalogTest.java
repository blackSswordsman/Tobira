package com.example.tobira;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.tobira.data.*;
import com.example.tobira.data.QuestEntities.Progress;
import com.example.tobira.debug.IzumoQuestCatalog;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class IzumoQuestCatalogTest {
    private Context context;
    private String name;
    private QuestDatabase database;
    private QuestRepository repository;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        name = "izumo-test-" + UUID.randomUUID() + ".db";
        open();
    }

    private void open() {
        database = Room.databaseBuilder(context, QuestDatabase.class, name).build();
        repository = new QuestRepository(database);
    }

    @After public void tearDown() {
        database.close();
        context.deleteDatabase(name);
    }

    private QuestRepository.RouteStatus status() throws Exception {
        return repository.getRouteStatus(IzumoQuestCatalog.ROUTE_ID, 1).get(10, TimeUnit.SECONDS);
    }

    private void complete(int index) throws Exception {
        String id = IzumoQuestCatalog.QUEST_IDS[index];
        repository.startQuest(id, 1).get(10, TimeUnit.SECONDS);
        repository.savePhase(id, 1, Progress.COMPLETED).get(10, TimeUnit.SECONDS);
    }

    @Test public void finaleRequiresAllFourInAnyOrderAndThenCompletesRoute() throws Exception {
        assertTrue(IzumoQuestCatalog.install(repository).get(10, TimeUnit.SECONDS));
        assertEquals(5, database.routes().getMembers(IzumoQuestCatalog.ROUTE_ID, 1).size());
        assertEquals(4, status().required);
        assertFalse(status().finaleUnlocked);
        try {
            repository.startQuest(IzumoQuestCatalog.QUEST_IDS[4], 1).get(10, TimeUnit.SECONDS);
            fail("Locked finale started");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
        complete(2);
        complete(0);
        complete(3);
        assertEquals(3, status().completed);
        assertFalse(status().finaleUnlocked);
        complete(1);
        assertTrue(status().finaleUnlocked);
        assertFalse(status().completedRoute);
        complete(4);
        assertTrue(status().completedRoute);
    }

    @Test public void repeatedInstallAndReopeningPreserveProgressWithoutDoubleCounting() throws Exception {
        IzumoQuestCatalog.install(repository).get(10, TimeUnit.SECONDS);
        complete(1);
        complete(1);
        assertFalse(IzumoQuestCatalog.install(repository).get(10, TimeUnit.SECONDS));
        database.close();
        open();
        assertEquals(1, status().completed);
        assertFalse(status().finaleUnlocked);
        assertNull(repository.getProgress(IzumoQuestCatalog.QUEST_IDS[0], 1)
                .get(10, TimeUnit.SECONDS));
    }

    @Test public void routeDetailsKeepRecommendedOrderWithoutStartingQuests() throws Exception {
        IzumoQuestCatalog.install(repository).get(10, TimeUnit.SECONDS);
        complete(2);
        QuestRepository.RouteDetails details = repository.getRouteDetails(
                IzumoQuestCatalog.ROUTE_ID, 1).get(10, TimeUnit.SECONDS);
        assertEquals(5, details.stops.size());
        assertEquals(1, details.status.completed);
        assertFalse(details.status.finaleUnlocked);
        for (int index = 0; index < details.stops.size(); index++) {
            QuestRepository.RouteStop stop = details.stops.get(index);
            assertEquals(index, stop.member.position);
            assertEquals(IzumoQuestCatalog.QUEST_IDS[index], stop.quest.id);
            assertEquals(index == 4, stop.member.finale);
            assertEquals(2, stop.steps.size());
            if (index == 2) assertEquals(Progress.COMPLETED, stop.progress.phase);
            else assertNull(stop.progress);
        }
    }

    @Test public void catalogPlanIncludesLockedFinaleAndRetainsQuestLinksAfterEditing() throws Exception {
        IzumoQuestCatalog.install(repository).get(10, TimeUnit.SECONDS);
        RoutePlan plan = repository.getRoutePlan(IzumoQuestCatalog.ROUTE_ID, 1)
                .get(10, TimeUnit.SECONDS);
        assertTrue(plan.isComplete());
        assertTrue(plan.useCurrentLocation);
        assertEquals(4, plan.stops.size());
        assertEquals("須佐神社", plan.stops.get(0).name);
        assertEquals(IzumoQuestCatalog.QUEST_IDS[4], plan.destination.questId);
        assertFalse(status().finaleUnlocked);
        java.util.Collections.swap(plan.stops, 0, 2);
        RoutePlan restored = RoutePlan.fromBundle(plan.toBundle());
        assertTrue(plan.hasSameItinerary(restored));
        assertEquals(IzumoQuestCatalog.ROUTE_ID, restored.questRouteId);
        assertEquals(1, restored.questRouteVersion);
        assertEquals(IzumoQuestCatalog.QUEST_IDS[2], restored.stops.get(0).questId);
        assertEquals(1, restored.stops.get(0).questVersion);
        assertEquals(IzumoQuestCatalog.QUEST_IDS[4], restored.destination.questId);
        for (String id : IzumoQuestCatalog.QUEST_IDS)
            assertNull(repository.getProgress(id, 1).get(10, TimeUnit.SECONDS));
        assertFalse(IzumoQuestCatalog.install(repository).get(10, TimeUnit.SECONDS));
        assertEquals(4, repository.getRoutePlan(IzumoQuestCatalog.ROUTE_ID, 1)
                .get(10, TimeUnit.SECONDS).stops.size());
    }

    @Test public void migrationFromV1PreservesQuestAndCheckpoint() throws Exception {
        // The v2 schema only adds these two tables. Remove them to recreate the v1 schema,
        // with its original Room identity, while retaining a real saved checkpoint.
        com.example.tobira.debug.DemoQuestCatalog.install(repository).get(10, TimeUnit.SECONDS);
        String id = com.example.tobira.debug.DemoQuestCatalog.QUEST_ID;
        repository.startQuest(id, 1).get(10, TimeUnit.SECONDS);
        repository.savePhase(id, 1, Progress.VISITING).get(10, TimeUnit.SECONDS);
        database.close();
        try (SQLiteDatabase old = SQLiteDatabase.openDatabase(
                context.getDatabasePath(name).getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            old.execSQL("DROP TABLE quest_route_members");
            old.execSQL("DROP TABLE quest_routes");
            old.execSQL("UPDATE room_master_table SET identity_hash = 'e88d68aac24f6ce15ac019abf3821269' WHERE id = 42");
            old.setVersion(1);
        }
        open();
        repository.initialize().get(10, TimeUnit.SECONDS);
        assertEquals(Progress.VISITING, repository.getProgress(id, 1)
                .get(10, TimeUnit.SECONDS).phase);
        assertNotNull(database.quests().getQuest(id, 1));
        assertTrue(IzumoQuestCatalog.install(repository).get(10, TimeUnit.SECONDS));
    }
}
