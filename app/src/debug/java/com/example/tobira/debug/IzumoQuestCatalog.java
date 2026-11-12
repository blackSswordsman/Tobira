package com.example.tobira.debug;

import com.example.tobira.data.QuestEntities.*;
import com.example.tobira.data.QuestRouteEntities.*;
import com.example.tobira.data.QuestRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Draft content with landmark coordinates; driving entrances/parking still need field validation.
 * Stories follow "出雲ルートの物語 / Izumo route stories" (seals: 風・大地・絆・光 → final rite).
 * Place facts are drafts and must be checked against each site's official information.
 */
public final class IzumoQuestCatalog {
    public static final String ROUTE_ID = "debug-izumo-myths";
    public static final int VERSION = 1;
    public static final String[] QUEST_IDS = {
            "izumo-susa", "izumo-inasa", "izumo-taisha", "izumo-hinomisaki", "izumo-lighthouse"
    };

    /**
     * Quest.introduction, shown under "Enemy" on the quest screen:
     * enemy name and difficulty, the story, and how to weaken it while driving.
     */
    private static final String[] INTRODUCTIONS = {
            "ヤマタノオロチ・風の首　★★☆"
                    + "\n\n雨壺に眠る嵐の力を狙い、山あいに潜む。"
                    + "\n\n道中：滑らかに走ると弱まる",
            "ヤマタノオロチ・大地の首　★★☆"
                    + "\n\n神々の浜で、大地を揺らしている。"
                    + "\n\n道中：ナビどおりに進むと弱まる",
            "ヤマタノオロチ・絆の首　★☆☆"
                    + "\n\n縁結びの社で、人と人の絆を断とうとしている。"
                    + "\n\n道中：曲がるたび合図を出すと弱まる",
            "ヤマタノオロチ・光の首　★★☆"
                    + "\n\n夜を守る「日沈の宮」に、闇を広げようとしている。"
                    + "\n\n道中：海沿いを滑らかに走ると弱まる",
            "ヤマタノオロチ（本体）　★★★"
                    + "\n\n四つの封印がそろうと、岬の海から現れる。"
                    + "\n\n道中：灯台の駐車場で完全に停止せよ"
    };

    /** OBSERVATION step: what to look for on arrival. */
    private static final String[] OBSERVATIONS = {
            "境内の七不思議の案内で「雨壺」を探し、大杉の前で風の音を聞け。",
            "弁天島を見つけ、神々がどの方角（海）から来るかを確かめよ。",
            "神楽殿の大しめ縄を見上げ、境内のうさぎ像を3つ見つけよ。",
            "下の宮「日沈宮」と上の宮「神の宮」の二つの社殿を確かめ、経島の方角を見よ。",
            "灯台の前で、これまでの四つの場所の方角を振り返れ。"
    };

    /**
     * CHALLENGE step text: the quest's short goal. Shown in the Quest card on the quest
     * screen and in the challenge dialog.
     */
    private static final String[] CHALLENGES = {
            "雨壺の力で暴風を起こそう",
            "国引きの綱を引こう",
            "全員で縁を結ぼう",
            "光を灯して闇を払おう",
            "四つの封印を順に解き放とう"
    };

    /** CHALLENGE hint (ヒント button): the car operations, in order. */
    private static final String[] HINTS = {
            "ヒント：①パーキングブレーキをかけ直す → ②左ウインカー → ③右ウインカー"
                    + " → ④ハンドルを右いっぱい",
            "ヒント：①パーキングブレーキをかけ直す → ②ハンドルを左いっぱい"
                    + " → ③ハンドルを右いっぱい → ④左ウインカー",
            "ヒント：①助手席の人が一度立つ → ②もう一度座る → ③右ウインカー"
                    + " → ④左ウインカー",
            "ヒント：①左ウインカー → ②ウインカーを戻す → ③右ウインカー"
                    + " → ④パーキングブレーキをかけ直す",
            "ヒント：①大地＝パーキングブレーキをかけ直す → ②風＝ハンドルを左いっぱい"
                    + " → ③絆＝助手席に座り直す → ④光＝右ウインカー"
    };

    /**
     * CHALLENGE conditionJson: the 4 "Enter Your Command" steps, done in order while parked
     * (see com.example.tobira.QuestCommand). Each step waits for a change the real car reports
     * (VehicleFlag): parking brake, turn signals, steering and seat occupancy.
     */
    private static final String[] CONDITIONS = {
            commands(
                    flag("PARKING_BRAKE_ON", "大地に足を据えよ：パーキングブレーキをかけ直す"),
                    flag("TURN_LEFT_ON", "左の風を呼べ：左ウインカーを出す"),
                    flag("TURN_RIGHT_ON", "右の風を呼べ：右ウインカーを出す"),
                    flag("STEER_RIGHT_HARD", "嵐を巻き起こせ：ハンドルを右いっぱいに")),
            commands(
                    flag("PARKING_BRAKE_ON", "車を大地に据えよ：パーキングブレーキをかけ直す"),
                    flag("STEER_LEFT_HARD", "綱を左へ引け：ハンドルを左いっぱいに"),
                    flag("STEER_RIGHT_HARD", "綱を右へ引け：ハンドルを右いっぱいに"),
                    flag("TURN_LEFT_ON", "神々を迎えよ：左ウインカーを出す")),
            commands(
                    flag("SEAT_VACATED", "縁をほどけ：助手席の人が一度立つ"),
                    flag("SEAT_OCCUPIED", "縁を結べ：もう一度座る"),
                    flag("TURN_RIGHT_ON", "縁の糸を引け：右ウインカーを出す"),
                    flag("TURN_LEFT_ON", "縁結びの合図：左ウインカーを出す")),
            commands(
                    flag("TURN_LEFT_ON", "光を灯せ：左ウインカーを出す"),
                    flag("TURN_SIGNAL_OFF", "闇を誘い出せ：ウインカーを戻す"),
                    flag("TURN_RIGHT_ON", "再び光を：右ウインカーを出す"),
                    flag("PARKING_BRAKE_ON", "夜を守れ：パーキングブレーキをかけ直す")),
            commands(
                    flag("PARKING_BRAKE_ON", "大地の封印：パーキングブレーキをかけ直す"),
                    flag("STEER_LEFT_HARD", "風の封印：ハンドルを左いっぱいに"),
                    flag("SEAT_OCCUPIED", "絆の封印：助手席に座り直す"),
                    flag("TURN_RIGHT_ON", "光の封印：右ウインカーを出す"))
    };

    private static String commands(String... items) {
        return "{\"commands\":[" + String.join(",", items) + "]}";
    }

    /** A step that succeeds when the car reports this operation (com.example.tobira.VehicleFlag). */
    private static String flag(String vehicleFlag, String label) {
        try {
            return new org.json.JSONObject().put("kind", "FLAG").put("flag", vehicleFlag)
                    .put("label", label).toString();
        } catch (org.json.JSONException error) {
            throw new IllegalStateException(error);
        }
    }

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
                    INTRODUCTIONS[index], "ja", false));
            members.add(new Member(ROUTE_ID, VERSION, id, VERSION, index, index == 4));
            steps.add(new Step(id, VERSION, "observation", 0, Step.OBSERVATION,
                    OBSERVATIONS[index], null, null));
            steps.add(new Step(id, VERSION, "challenge", 1, Step.CHALLENGE,
                    CHALLENGES[index], HINTS[index], CONDITIONS[index]));
        }
        return repository.importRoute(new Route(ROUTE_ID, VERSION,
                "【テスト用】出雲神話をたどる旅",
                "オロチが出雲で目を覚ました。四つの場所を好きな順番で巡って"
                        + "風・大地・絆・光の封印を集め、日御碕の最後の試練へ。"),
                members, quests, steps).thenCompose(added ->
                repository.linkRoutePlaces(places, links).thenApply(ignored -> added));
    }
}
