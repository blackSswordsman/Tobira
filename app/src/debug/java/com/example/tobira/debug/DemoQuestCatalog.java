package com.example.tobira.debug;

import com.example.tobira.data.QuestEntities.Quest;
import com.example.tobira.data.QuestEntities.Step;
import com.example.tobira.data.QuestRepository;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

/** Debug-only fictional content. No real place or vehicle area is assigned. */
public final class DemoQuestCatalog {
    public static final String QUEST_ID = "debug-demo-quest";
    public static final int VERSION = 1;

    private DemoQuestCatalog() { }

    public static CompletableFuture<Boolean> install(QuestRepository repository) {
        Quest quest = new Quest(QUEST_ID, VERSION, "【テスト用】三つの封印",
                "動作確認用の架空のクエストです。実在の寺社や史実とは関係ありません。"
                        + "三つの手がかりを覚えて、車に戻ってから封印を解く想定です。",
                "ja", false);
        return repository.importQuest(quest, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Arrays.asList(
                        observation("observe-window", 0,
                                "【テスト設定】門には八つの円が描かれています。その数を覚えてください。"),
                        observation("observe-fan", 1,
                                "【テスト設定】風の印には三本の線があります。その数を覚えてください。"),
                        observation("observe-light", 2,
                                "【テスト設定】最後の扉には一つの灯りが描かれています。"),
                        challenge("challenge-window", 3,
                                "門の円の数に対応する窓の位置を選んでください。",
                                "テスト用の答え：8（窓の開度80%）。",
                                "{\"property\":\"WINDOW_POS\",\"target\":8}"),
                        challenge("challenge-fan", 4,
                                "風の印の線の数に対応する風量を選んでください。",
                                "テスト用の答え：風量3。",
                                "{\"property\":\"HVAC_FAN_SPEED\",\"target\":3}"),
                        challenge("challenge-light", 5,
                                "最後の扉の灯りを、車内の照明で表してください。",
                                "テスト用の答え：車内灯をオン。",
                                "{\"property\":\"CABIN_LIGHTS_SWITCH\",\"target\":1}")
                ));
    }

    private static Step observation(String id, int position, String text) {
        return new Step(QUEST_ID, VERSION, id, position, Step.OBSERVATION, text, null, null);
    }

    private static Step challenge(String id, int position, String text, String hint, String condition) {
        return new Step(QUEST_ID, VERSION, id, position, Step.CHALLENGE, text, hint, condition);
    }
}
