package com.example.tobira;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import okhttp3.HttpUrl;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RoutePlanTest {
    private Place place(String name, double latitude, double longitude) {
        return new Place(name, name + " address", latitude, longitude);
    }

    @Test
    public void manualStartDoesNotNeedGpsAndPreservesStopOrder() {
        RoutePlan plan = new RoutePlan();
        plan.useCurrentLocation = false;
        plan.start = place("Start", 35.3, 132.7);
        plan.stops.add(place("Museum", 35.4, 132.8));
        plan.stops.add(place("Cafe", 35.5, 132.9));
        plan.destination = place("Finish", 35.6, 133.0);
        List<Place> points = plan.resolve(null);
        assertEquals("Start", points.get(0).name);
        assertEquals("Museum", points.get(1).name);
        assertEquals("Cafe", points.get(2).name);
        assertEquals("Finish", points.get(3).name);
    }

    @Test
    public void gpsModeUsesCurrentFixInsteadOfPreviousManualStart() {
        RoutePlan plan = new RoutePlan();
        plan.start = place("Old start", 1, 2);
        plan.destination = place("Finish", 3, 4);
        Place current = place("GPS", 5, 6);
        assertSame(current, plan.resolve(current).get(0));
    }

    @Test(expected = IllegalStateException.class)
    public void missingGpsCannotSilentlyUseZeroCoordinates() {
        RoutePlan plan = new RoutePlan();
        plan.destination = place("Finish", 3, 4);
        plan.resolve(null);
    }

    @Test
    public void itinerarySurvivesActivityRecreation() {
        RoutePlan plan = new RoutePlan();
        plan.useCurrentLocation = false;
        plan.start = place("Start", 1, 2);
        plan.destination = place("Finish", 3, 4);
        plan.stops.add(place("Stop one", 5, 6));
        plan.stops.add(place("Stop two", 7, 8));
        RoutePlan restored = RoutePlan.fromBundle(plan.toBundle());
        assertTrue(restored.isComplete());
        assertFalse(restored.useCurrentLocation);
        assertEquals("Start", restored.start.name);
        assertEquals("Finish", restored.destination.name);
        assertEquals("Stop one", restored.stops.get(0).name);
        assertEquals("Stop two", restored.stops.get(1).name);
    }

    @Test
    public void osrmRequestContainsEveryPointAndRadiusInOrderAcrossLocales() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("ru", "RU"));
            HttpUrl url = RouteViewModel.routeUrl("https://router.project-osrm.org/route/v1/driving",
                    Arrays.asList(place("A", 35.1, 132.1), place("B", 35.2, 132.2),
                            place("C", 35.3, 132.3)));
            List<String> segments = url.pathSegments();
            assertEquals("132.100000,35.100000;132.200000,35.200000;132.300000,35.300000",
                    segments.get(segments.size() - 1));
            assertEquals("1000;1000;1000", url.queryParameter("radiuses"));
            assertEquals("geojson", url.queryParameter("geometries"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesRouteWithOnlyOnePoint() {
        RouteViewModel.routeUrl("https://router.project-osrm.org/route/v1/driving",
                Arrays.asList(place("A", 35.1, 132.1)));
    }
}
