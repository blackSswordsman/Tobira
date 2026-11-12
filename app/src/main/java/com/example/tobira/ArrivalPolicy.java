package com.example.tobira;

/** UI-independent foreground arrival rule. Units: metres and monotonic nanoseconds. */
public final class ArrivalPolicy {
    public static final double RADIUS_METERS = 500;
    public static final float MAX_ACCURACY_METERS = 100;
    public static final long MAX_AGE_NS = 30_000_000_000L;

    public static final class Fix {
        public final double latitude, longitude;
        public final float accuracy;
        public final long elapsedNs;
        public Fix(double latitude, double longitude, float accuracy, long elapsedNs) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracy = accuracy;
            this.elapsedNs = elapsedNs;
        }
    }

    public static final class Check {
        public final boolean usable, arrived;
        public final double distance;
        private Check(boolean usable, boolean arrived, double distance) {
            this.usable = usable;
            this.arrived = arrived;
            this.distance = distance;
        }
    }

    public Check check(double targetLat, double targetLon, Fix fix, long nowNs) {
        if (fix == null || !validCoordinates(targetLat, targetLon)
                || !validCoordinates(fix.latitude, fix.longitude)
                || !Float.isFinite(fix.accuracy) || fix.accuracy < 0
                || fix.accuracy > MAX_ACCURACY_METERS || fix.elapsedNs <= 0
                || fix.elapsedNs > nowNs || nowNs - fix.elapsedNs > MAX_AGE_NS)
            return new Check(false, false, -1);
        double lat1 = Math.toRadians(targetLat), lat2 = Math.toRadians(fix.latitude);
        double dLat = lat2 - lat1, dLon = Math.toRadians(fix.longitude - targetLon);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dLon / 2), 2);
        double distance = 6_371_000 * 2 * Math.asin(Math.sqrt(Math.max(0, Math.min(1, a))));
        // Require the accuracy circle to fit inside the arrival radius.
        return new Check(true, distance + fix.accuracy <= RADIUS_METERS, distance);
    }

    private boolean validCoordinates(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
    }
}
