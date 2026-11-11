package com.example.tobira.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Local catalog and single-player progress. Catalog revisions are immutable once published. */
public final class QuestEntities {
    private QuestEntities() { }

    @Entity(tableName = "quest_places")
    public static class Place {
        @PrimaryKey @NonNull public String id;
        @NonNull public String name;
        public double latitude;
        public double longitude;

        public Place(@NonNull String id, @NonNull String name, double latitude, double longitude) {
            this.id = required(id);
            this.name = required(name);
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
                throw new IllegalArgumentException("Invalid coordinates");
            }
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    @Entity(tableName = "place_external_keys", primaryKeys = {"provider", "objectType", "externalId"},
            foreignKeys = @ForeignKey(entity = Place.class, parentColumns = "id",
                    childColumns = "placeId", onDelete = ForeignKey.RESTRICT),
            indices = @Index("placeId"))
    public static class PlaceKey {
        @NonNull public String provider;
        @NonNull public String objectType;
        @NonNull public String externalId;
        @NonNull public String placeId;

        public PlaceKey(@NonNull String provider, @NonNull String objectType,
                        @NonNull String externalId, @NonNull String placeId) {
            this.provider = required(provider);
            this.objectType = required(objectType);
            this.externalId = required(externalId);
            this.placeId = required(placeId);
        }
    }

    @Entity(tableName = "quests", primaryKeys = {"id", "version"})
    public static class Quest {
        @NonNull public String id;
        public int version;
        @NonNull public String title;
        @NonNull public String introduction;
        @NonNull public String language;
        public boolean published;

        public Quest(@NonNull String id, int version, @NonNull String title,
                     @NonNull String introduction, @NonNull String language, boolean published) {
            this.id = required(id);
            if (version < 1) throw new IllegalArgumentException("Invalid quest version");
            this.version = version;
            this.title = required(title);
            this.introduction = introduction;
            this.language = required(language);
            this.published = published;
        }
    }

    @Entity(tableName = "place_quests", primaryKeys = {"placeId", "questId", "questVersion"},
            foreignKeys = {
                    @ForeignKey(entity = Place.class, parentColumns = "id", childColumns = "placeId",
                            onDelete = ForeignKey.RESTRICT),
                    @ForeignKey(entity = Quest.class, parentColumns = {"id", "version"},
                            childColumns = {"questId", "questVersion"}, onDelete = ForeignKey.RESTRICT)
            }, indices = @Index(value = {"questId", "questVersion"}))
    public static class PlaceQuest {
        @NonNull public String placeId;
        @NonNull public String questId;
        public int questVersion;

        public PlaceQuest(@NonNull String placeId, @NonNull String questId, int questVersion) {
            this.placeId = required(placeId);
            this.questId = required(questId);
            this.questVersion = questVersion;
        }
    }

    @Entity(tableName = "quest_steps", primaryKeys = {"questId", "questVersion", "id"},
            foreignKeys = @ForeignKey(entity = Quest.class, parentColumns = {"id", "version"},
                    childColumns = {"questId", "questVersion"}, onDelete = ForeignKey.RESTRICT),
            indices = @Index(value = {"questId", "questVersion", "position"}, unique = true))
    public static class Step {
        public static final String OBSERVATION = "OBSERVATION";
        public static final String CHALLENGE = "CHALLENGE";

        @NonNull public String questId;
        public int questVersion;
        @NonNull public String id;
        public int position;
        @NonNull public String kind;
        @NonNull public String text;
        @Nullable public String hint;
        /** Optional structured conditions; evaluated by a future quest engine, never by the DB. */
        @Nullable public String conditionJson;

        public Step(@NonNull String questId, int questVersion, @NonNull String id, int position,
                    @NonNull String kind, @NonNull String text, @Nullable String hint,
                    @Nullable String conditionJson) {
            this.questId = required(questId);
            this.questVersion = questVersion;
            this.id = required(id);
            if (position < 0 || (!OBSERVATION.equals(kind) && !CHALLENGE.equals(kind))) {
                throw new IllegalArgumentException("Invalid quest step");
            }
            this.position = position;
            this.kind = kind;
            this.text = required(text);
            this.hint = hint;
            this.conditionJson = conditionJson;
        }
    }

    @Entity(tableName = "quest_progress", primaryKeys = {"questId", "questVersion"},
            foreignKeys = @ForeignKey(entity = Quest.class, parentColumns = {"id", "version"},
                    childColumns = {"questId", "questVersion"}, onDelete = ForeignKey.RESTRICT))
    public static class Progress {
        public static final String PLANNED = "PLANNED";
        public static final String TRAVELLING = "TRAVELLING";
        public static final String VISITING = "VISITING";
        public static final String CHALLENGE = "CHALLENGE";
        public static final String COMPLETED = "COMPLETED";

        @NonNull public String questId;
        public int questVersion;
        @NonNull public String phase;
        public long updatedAt;

        public Progress(@NonNull String questId, int questVersion, @NonNull String phase, long updatedAt) {
            this.questId = required(questId);
            this.questVersion = questVersion;
            if (!PLANNED.equals(phase) && !TRAVELLING.equals(phase) && !VISITING.equals(phase)
                    && !CHALLENGE.equals(phase) && !COMPLETED.equals(phase)) {
                throw new IllegalArgumentException("Invalid quest phase");
            }
            this.phase = phase;
            this.updatedAt = updatedAt;
        }
    }

    @Entity(tableName = "completed_quest_steps", primaryKeys = {"questId", "questVersion", "stepId"},
            foreignKeys = {
                    @ForeignKey(entity = Progress.class, parentColumns = {"questId", "questVersion"},
                            childColumns = {"questId", "questVersion"}, onDelete = ForeignKey.CASCADE),
                    @ForeignKey(entity = Step.class, parentColumns = {"questId", "questVersion", "id"},
                            childColumns = {"questId", "questVersion", "stepId"},
                            onDelete = ForeignKey.RESTRICT)
            })
    public static class CompletedStep {
        @NonNull public String questId;
        public int questVersion;
        @NonNull public String stepId;
        public long completedAt;

        public CompletedStep(@NonNull String questId, int questVersion, @NonNull String stepId,
                             long completedAt) {
            this.questId = required(questId);
            this.questVersion = questVersion;
            this.stepId = required(stepId);
            this.completedAt = completedAt;
        }
    }

    private static String required(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Empty value");
        return value;
    }
}
