package com.example.tobira;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DrivingRouteParsingTest {
    @Test
    public void parsesGeometryAndTotalsWithoutSwappingCoordinates() throws Exception {
        DrivingRoute route = DrivingRoute.parse(response(
                "1234.5", "600", "[[139.7,35.6],[139.8,35.7]]"));
        assertEquals(1234.5, route.distanceMeters, 0.001);
        assertEquals(600, route.durationSeconds, 0.001);
        assertEquals(139.7, route.points.get(0).longitude(), 0.001);
        assertEquals(35.6, route.points.get(0).latitude(), 0.001);
        assertEquals(2, route.geometry().coordinates().size());
    }

    @Test(expected = DrivingRoute.NoRouteException.class)
    public void reportsDisconnectedRoads() throws Exception {
        DrivingRoute.parse("{\"code\":\"NoRoute\"}");
    }

    @Test(expected = DrivingRoute.NoRouteException.class)
    public void reportsCoordinatesOutsideRoadNetwork() throws Exception {
        DrivingRoute.parse("{\"code\":\"NoSegment\"}");
    }

    @Test(expected = JSONException.class)
    public void rejectsInvalidGeometry() throws Exception {
        DrivingRoute.parse(response("100", "60", "[[139,95],[140,36]]"));
    }

    @Test(expected = JSONException.class)
    public void rejectsNegativeTravelTime() throws Exception {
        DrivingRoute.parse(response("100", "-1", "[[139,35],[140,36]]"));
    }

    private String response(String distance, String duration, String coordinates) {
        return "{\"code\":\"Ok\",\"routes\":[{\"distance\":" + distance
                + ",\"duration\":" + duration
                + ",\"geometry\":{\"type\":\"LineString\",\"coordinates\":"
                + coordinates + "}}]}";
    }
}
