package com.example.tobira.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.example.tobira.data.QuestEntities.*;

@Database(entities = {Place.class, PlaceKey.class, Quest.class, PlaceQuest.class, Step.class,
        Progress.class, CompletedStep.class}, version = 1, exportSchema = true)
public abstract class QuestDatabase extends RoomDatabase {
    private static volatile QuestDatabase instance;

    public abstract QuestDao quests();

    public static QuestDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (QuestDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(),
                            QuestDatabase.class, "tobira-quests.db").build();
                }
            }
        }
        return instance;
    }
}
