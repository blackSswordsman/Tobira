package com.example.tobira;

import android.location.Location;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TripSessionTest {
    @Test
    public void requiresRouteAndCurrentLocationToStart() {
        TripSession trip = new TripSession();
        assertFalse(trip.start(false, true));
        assertFalse(trip.start(true, false));
        assertFalse(trip.isActive());
        assertTrue(trip.start(true, true));
        assertTrue(trip.isActive());
        assertTrue(trip.isFollowing());
    }

    @Test
    public void browsingDoesNotEndTripAndRecenterRestoresFollowing() {
        TripSession trip = new TripSession();
        trip.start(true, true);
        trip.dismissTracking();
        assertTrue(trip.isActive());
        assertFalse(trip.isFollowing());
        trip.resumeTracking();
        assertTrue(trip.isFollowing());
        trip.stop();
        trip.resumeTracking();
        assertFalse(trip.isActive());
        assertFalse(trip.isFollowing());
    }

    @Test
    public void rotationPreservesBrowsingMode() {
        TripSession trip = new TripSession();
        trip.start(true, true);
        trip.dismissTracking();
        TripSession restored = new TripSession();
        restored.restore(trip.save(), true);
        assertTrue(restored.isActive());
        assertFalse(restored.isFollowing());
    }

    @Test
    public void missingRouteAfterProcessDeathDoesNotResumeTrip() {
        TripSession trip = new TripSession();
        trip.start(true, true);
        TripSession restored = new TripSession();
        restored.restore(trip.save(), false);
        assertFalse(restored.isActive());
    }

    @Test
    public void ignoresOlderAndDuplicateProviderFixes() {
        VehiclePosition position = new VehiclePosition();
        assertNotNull(position.accept(fix(35, 132, 2)));
        assertNull(position.accept(fix(36, 133, 1)));
        assertNull(position.accept(fix(36, 133, 2)));
        assertNotNull(position.accept(fix(35, 132.001, 3)));
    }

    @Test
    public void derivesMovementHeadingAndKeepsItWhenStationary() {
        VehiclePosition position = new VehiclePosition();
        position.accept(fix(35, 132, 1));
        Location east = position.accept(fix(35, 132.001, 2));
        assertNotNull(east);
        assertEquals(90f, east.getBearing(), 1f);
        Location stationary = fix(35, 132.001, 3);
        stationary.setSpeed(0);
        stationary.setBearing(0);
        Location accepted = position.accept(stationary);
        assertNotNull(accepted);
        assertEquals(east.getBearing(), accepted.getBearing(), 0.01f);
    }

    @Test
    public void usesMovingGpsBearingAndPreservesOriginalFix() {
        VehiclePosition position = new VehiclePosition();
        Location fix = fix(35, 132, 1);
        fix.setBearing(270);
        fix.setSpeed(10);
        Location accepted = position.accept(fix);
        assertNotNull(accepted);
        assertEquals(270, accepted.getBearing(), 0);
        assertEquals(fix.getElapsedRealtimeNanos(), accepted.getElapsedRealtimeNanos());
        assertEquals(fix.getLatitude(), accepted.getLatitude(), 0);
    }

    private Location fix(double latitude, double longitude, long seconds) {
        Location location = new Location("gps");
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setAccuracy(5);
        location.setElapsedRealtimeNanos(seconds * 1_000_000_000L);
        return location;
    }
}
