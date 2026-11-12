package com.example.tobira;

/** Exact quest revision and place, independent of labels and itinerary position. */
public final class QuestArrivalTarget {
    public final String questId, placeKey;
    public final int questVersion;
    public final double latitude, longitude;

    private QuestArrivalTarget(Place place) {
        questId = place.questId;
        questVersion = place.questVersion;
        latitude = place.latitude;
        longitude = place.longitude;
        placeKey = Double.toHexString(latitude) + "," + Double.toHexString(longitude);
    }

    public static QuestArrivalTarget from(Place place) {
        return place == null || place.questId == null || place.questId.isEmpty()
                || place.questVersion < 1 ? null : new QuestArrivalTarget(place);
    }

    public boolean matches(QuestArrivalTarget other) {
        return other != null && questId.equals(other.questId)
                && questVersion == other.questVersion && placeKey.equals(other.placeKey);
    }
}
