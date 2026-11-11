package com.example.tobira;

import android.location.Location;

/** Keeps stationary heading stable and ignores duplicate/out-of-order provider fixes. */
public final class VehiclePosition {
    private Location previous;
    private float bearing;

    public Location accept(Location fix) {
        if (previous != null && fix.getElapsedRealtimeNanos() <= previous.getElapsedRealtimeNanos()) {
            return null;
        }
        Location next = new Location(fix);
        if (fix.hasBearing() && (!fix.hasSpeed() || fix.getSpeed() > 0.5f)) {
            bearing = fix.getBearing();
        } else if (previous != null && previous.distanceTo(fix) >= 3f) {
            bearing = (previous.bearingTo(fix) + 360f) % 360f;
        } else if (previous == null && fix.hasBearing()) {
            bearing = fix.getBearing();
        }
        next.setBearing(bearing);
        previous = next;
        return new Location(next);
    }
}
