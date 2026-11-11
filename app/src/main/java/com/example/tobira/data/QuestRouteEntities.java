package com.example.tobira.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

/** Versioned main quests. Member order is recommended, not an unlock dependency. */
public final class QuestRouteEntities {
    private QuestRouteEntities() { }

    @Entity(tableName = "quest_routes", primaryKeys = {"id", "version"})
    public static class Route {
        @NonNull public String id;
        public int version;
        @NonNull public String title;
        @NonNull public String description;

        public Route(@NonNull String id, int version, @NonNull String title,
                     @NonNull String description) {
            if (id.trim().isEmpty() || version < 1 || title.trim().isEmpty())
                throw new IllegalArgumentException("Invalid route");
            this.id = id;
            this.version = version;
            this.title = title;
            this.description = description;
        }
    }

    @Entity(tableName = "quest_route_members",
            primaryKeys = {"routeId", "routeVersion", "questId", "questVersion"},
            foreignKeys = {
                    @ForeignKey(entity = Route.class, parentColumns = {"id", "version"},
                            childColumns = {"routeId", "routeVersion"}, onDelete = ForeignKey.RESTRICT),
                    @ForeignKey(entity = QuestEntities.Quest.class, parentColumns = {"id", "version"},
                            childColumns = {"questId", "questVersion"}, onDelete = ForeignKey.RESTRICT)
            }, indices = {
                    @Index(value = {"routeId", "routeVersion", "position"}, unique = true),
                    @Index(value = {"questId", "questVersion"})
            })
    public static class Member {
        @NonNull public String routeId;
        public int routeVersion;
        @NonNull public String questId;
        public int questVersion;
        public int position;
        public boolean finale;

        public Member(@NonNull String routeId, int routeVersion, @NonNull String questId,
                      int questVersion, int position, boolean finale) {
            if (routeId.trim().isEmpty() || questId.trim().isEmpty()
                    || routeVersion < 1 || questVersion < 1 || position < 0)
                throw new IllegalArgumentException("Invalid route member");
            this.routeId = routeId;
            this.routeVersion = routeVersion;
            this.questId = questId;
            this.questVersion = questVersion;
            this.position = position;
            this.finale = finale;
        }
    }
}
