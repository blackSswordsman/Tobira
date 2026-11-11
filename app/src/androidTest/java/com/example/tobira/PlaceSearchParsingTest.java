package com.example.tobira;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PlaceSearchParsingTest {
    @Test
    public void parsesPhotonCoordinatesAndBuildsAddress() throws JSONException {
        List<Place> places = PlaceSearchViewModel.parseResults(
                "{\"features\":[{\"properties\":{\"name\":\"東京駅\",\"city\":\"東京\","
                        + "\"country\":\"日本\"},\"geometry\":{\"type\":\"Point\","
                        + "\"coordinates\":[139.7671,35.6812]}}]}");
        assertEquals(1, places.size());
        assertEquals("東京駅", places.get(0).name);
        assertEquals("東京駅, 東京, 日本", places.get(0).address);
        assertEquals(35.6812, places.get(0).latitude, 0.000001);
        assertEquals(139.7671, places.get(0).longitude, 0.000001);
    }

    @Test
    public void skipsInvalidCoordinatesButKeepsValidResults() throws JSONException {
        List<Place> places = PlaceSearchViewModel.parseResults(
                "{\"features\":[{\"properties\":{\"name\":\"Broken\"},"
                        + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[0,999]}},"
                        + "{\"properties\":{\"name\":\"Valid\",\"city\":\"City\"},"
                        + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[2,1]}}]}");
        assertEquals(1, places.size());
        assertEquals("Valid", places.get(0).name);
    }

    @Test
    public void usesStreetAndHouseNumberWhenNameIsMissing() throws JSONException {
        List<Place> places = PlaceSearchViewModel.parseResults(
                "{\"features\":[{\"properties\":{\"street\":\"Main Street\",\"housenumber\":\"12\"},"
                        + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[2,1]}}]}");
        assertEquals("Main Street 12", places.get(0).name);
    }

    @Test
    public void emptyResultsStayEmpty() throws JSONException {
        assertTrue(PlaceSearchViewModel.parseResults("{\"features\":[]}").isEmpty());
    }

    @Test(expected = JSONException.class)
    public void rejectsErrorObjectsInsteadOfTreatingThemAsNoResults() throws JSONException {
        PlaceSearchViewModel.parseResults("{\"error\":\"unavailable\"}");
    }

    @Test
    public void destinationSurvivesBundleRoundTrip() {
        Place original = new Place("Museum", "Museum, City", 42.1, -71.2);
        Place restored = Place.fromBundle(original.toBundle());
        assertNotNull(restored);
        assertEquals(original.name, restored.name);
        assertEquals(original.address, restored.address);
        assertEquals(original.latitude, restored.latitude, 0);
        assertEquals(original.longitude, restored.longitude, 0);
        assertNull(Place.fromBundle(new android.os.Bundle()));
    }
}
