package com.example.tobira;

import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/** User-ordered itinerary. GPS is resolved only when building a route. */
public final class RoutePlan {
    public static final int MAX_STOPS = 8;
    public boolean useCurrentLocation = true;
    public Place start;
    public Place destination;
    public final ArrayList<Place> stops = new ArrayList<>();

    public boolean isComplete() {
        return destination != null && (useCurrentLocation || start != null);
    }

    public List<Place> resolve(Place currentLocation) {
        if (!isComplete() || (useCurrentLocation && currentLocation == null)) {
            throw new IllegalStateException("Incomplete route");
        }
        ArrayList<Place> points = new ArrayList<>();
        points.add(useCurrentLocation ? currentLocation : start);
        points.addAll(stops);
        points.add(destination);
        return points;
    }

    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putBoolean("current", useCurrentLocation);
        if (start != null) bundle.putBundle("start", start.toBundle());
        if (destination != null) bundle.putBundle("destination", destination.toBundle());
        ArrayList<Bundle> savedStops = new ArrayList<>();
        for (Place stop : stops) savedStops.add(stop.toBundle());
        bundle.putParcelableArrayList("stops", savedStops);
        return bundle;
    }

    public static RoutePlan fromBundle(Bundle bundle) {
        RoutePlan plan = new RoutePlan();
        if (bundle == null) return plan;
        plan.useCurrentLocation = bundle.getBoolean("current", true);
        plan.start = Place.fromBundle(bundle.getBundle("start"));
        plan.destination = Place.fromBundle(bundle.getBundle("destination"));
        ArrayList<Bundle> savedStops = bundle.getParcelableArrayList("stops");
        if (savedStops != null) {
            for (Bundle saved : savedStops) {
                Place stop = Place.fromBundle(saved);
                if (stop != null && plan.stops.size() < MAX_STOPS) plan.stops.add(stop);
            }
        }
        return plan;
    }
}
