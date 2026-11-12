package com.example.tobira.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;

/** Arrival is evidence of proximity, never evidence of visiting or completing a task. */
@Entity(tableName = "quest_arrivals", primaryKeys = {"questId", "questVersion", "placeKey"},
        foreignKeys = @ForeignKey(entity = QuestEntities.Quest.class, parentColumns = {"id", "version"},
                childColumns = {"questId", "questVersion"}, onDelete = ForeignKey.RESTRICT))
public final class QuestArrival {
    @NonNull public String questId;
    public int questVersion;
    @NonNull public String placeKey;
    public long arrivedAt;
    public QuestArrival(@NonNull String questId, int questVersion, @NonNull String placeKey, long arrivedAt) {
        this.questId = questId;
        this.questVersion = questVersion;
        this.placeKey = placeKey;
        this.arrivedAt = arrivedAt;
    }
}
