package com.example.tobira;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** OSRM distances are metres, durations are seconds, coordinates are longitude/latitude. */
public final class DrivingRoute {
    public final double distanceMeters;
    public final double durationSeconds;
    public final List<Point> points;

    private DrivingRoute(double distanceMeters, double durationSeconds, List<Point> points) {
        this.distanceMeters = distanceMeters;
        this.durationSeconds = durationSeconds;
        this.points = Collections.unmodifiableList(points);
    }

    public LineString geometry() {
        return LineString.fromLngLats(points);
    }

    static DrivingRoute parse(String json) throws JSONException, NoRouteException {
        JSONObject root = new JSONObject(json);
        String code = root.optString("code");
        if ("NoRoute".equals(code) || "NoSegment".equals(code)) throw new NoRouteException();
        if (!"Ok".equals(code)) throw new JSONException("Unexpected route response");
        JSONArray routes = root.getJSONArray("routes");
        if (routes.length() == 0) throw new NoRouteException();
        JSONObject first = routes.getJSONObject(0);
        double distance = first.getDouble("distance");
        double duration = first.getDouble("duration");
        if (!Double.isFinite(distance) || !Double.isFinite(duration) || distance < 0 || duration < 0) {
            throw new JSONException("Invalid route totals");
        }
        JSONObject geometry = first.getJSONObject("geometry");
        if (!"LineString".equals(geometry.getString("type"))) {
            throw new JSONException("Invalid route geometry");
        }
        JSONArray coordinates = geometry.getJSONArray("coordinates");
        if (coordinates.length() < 2 || coordinates.length() > 100000) {
            throw new JSONException("Invalid geometry size");
        }
        List<Point> points = new ArrayList<>(coordinates.length());
        for (int index = 0; index < coordinates.length(); index++) {
            JSONArray coordinate = coordinates.getJSONArray(index);
            double longitude = coordinate.getDouble(0);
            double latitude = coordinate.getDouble(1);
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
                throw new JSONException("Invalid route coordinate");
            }
            points.add(Point.fromLngLat(longitude, latitude));
        }
        return new DrivingRoute(distance, duration, points);
    }

    static final class NoRouteException extends Exception { }
}
