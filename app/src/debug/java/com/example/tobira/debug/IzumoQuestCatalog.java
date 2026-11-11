package com.example.tobira.debug;

import com.example.tobira.data.QuestEntities.*;
import com.example.tobira.data.QuestRouteEntities.*;
import com.example.tobira.data.QuestRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Draft content with landmark coordinates; driving entrances/parking still need field validation. */
public final class IzumoQuestCatalog {
    public static final String ROUTE_ID = "debug-izumo-myths";
    public static final int VERSION = 1;
    public static final String[] QUEST_IDS = {
            "izumo-susa", "izumo-inasa", "izumo-taisha", "izumo-hinomisaki", "izumo-lighthouse"
    };

    private IzumoQuestCatalog() { }

    public static CompletableFuture<Boolean> install(QuestRepository repository) {
        String[] names = {"須佐神社", "稲佐の浜", "出雲大社", "日御碕神社", "出雲日御碕灯台・日御碕"};
        double[][] coordinates = {
                {35 + 14 / 60.0 + 4.9 / 3600.0, 132 + 44 / 60.0 + 12.9 / 3600.0},
                {35.400314, 132.672366},
                {35 + 24 / 60.0 + 7.5 / 3600.0, 132 + 41 / 60.0 + 8.0 / 3600.0},
                {35 + 25 / 60.0 + 46.370 / 3600.0, 132 + 37 / 60.0 + 46.450 / 3600.0},
                {35 + 26 / 60.0 + 2 / 3600.0, 132 + 37 / 60.0 + 45 / 3600.0}
        };
        List<Place> places = new ArrayList<>();
        List<PlaceQuest> links = new ArrayList<>();
        List<Quest> quests = new ArrayList<>();
        List<Member> members = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        for (int index = 0; index < names.length; index++) {
            String id = QUEST_IDS[index];
            places.add(new Place(id + "-place", names[index],
                    coordinates[index][0], coordinates[index][1]));
            links.add(new PlaceQuest(id + "-place", id, VERSION));
            quests.add(new Quest(id, VERSION, "【テスト用】" + names[index],
                    index == 4 ? "四つのクエストをクリアすると、最後の試練が開きます。"
                            : "この場所の物語と課題は準備中です。好きな順番で訪問できます。",
                    "ja", false));
            members.add(new Member(ROUTE_ID, VERSION, id, VERSION, index, index == 4));
            steps.add(new Step(id, VERSION, "observation", 0, Step.OBSERVATION,
                    "【準備中】訪問時に注目する内容をここに表示します。", null, null));
            steps.add(new Step(id, VERSION, "challenge", 1, Step.CHALLENGE,
                    "【準備中】停車中に取り組む課題をここに表示します。", null, null));
        }
        return repository.importRoute(new Route(ROUTE_ID, VERSION,
                "【テスト用】出雲神話をたどる旅",
                "四つの場所を好きな順番で巡り、日御碕の最後の試練へ。"),
                members, quests, steps).thenCompose(added ->
                repository.linkRoutePlaces(places, links).thenApply(ignored -> added));
    }
}
