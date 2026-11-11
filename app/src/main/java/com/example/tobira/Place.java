package com.example.tobira;

import android.os.Bundle;

import org.json.JSONException;
import org.json.JSONObject;

/** A geocoding result that can also be saved as a selected destination. */
public final class Place {
    public final String name;
    public final String address;
    public final double latitude;
    public final double longitude;

    public Place(String name, String address, double latitude, double longitude) {
        if (name == null || name.trim().isEmpty() || address == null
                || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Invalid place");
        }
        this.name = name;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    static Place fromJson(JSONObject json) throws JSONException {
        String address = json.getString("display_name");
        String name = json.optString("name", "").trim();
        if (name.isEmpty()) name = address.split(",", 2)[0].trim();
        return new Place(name, address, json.getDouble("lat"), json.getDouble("lon"));
    }

    static Place fromPhotonJson(JSONObject feature) throws JSONException {
        JSONObject geometry = feature.getJSONObject("geometry");
        if (!"Point".equals(geometry.getString("type"))) throw new JSONException("Expected a point");
        org.json.JSONArray coordinates = geometry.getJSONArray("coordinates");
        JSONObject properties = feature.getJSONObject("properties");
        java.util.LinkedHashSet<String> parts = new java.util.LinkedHashSet<>();
        String name = properties.optString("name", "").trim();
        if (!name.isEmpty()) parts.add(name);
        String street = properties.optString("street", "").trim();
        String house = properties.optString("housenumber", "").trim();
        if (!street.isEmpty()) parts.add((street + " " + house).trim());
        for (String key : new String[] {"district", "city", "state", "country"}) {
            String value = properties.optString(key, "").trim();
            if (!value.isEmpty()) parts.add(value);
        }
        if (name.isEmpty() && !parts.isEmpty()) name = parts.iterator().next();
        return new Place(name, android.text.TextUtils.join(", ", parts),
                coordinates.getDouble(1), coordinates.getDouble(0));
    }

    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putString("name", name);
        bundle.putString("address", address);
        bundle.putDouble("latitude", latitude);
        bundle.putDouble("longitude", longitude);
        return bundle;
    }

    public static Place fromBundle(Bundle bundle) {
        if (bundle == null || !bundle.containsKey("latitude") || !bundle.containsKey("longitude")) return null;
        try {
            return new Place(bundle.getString("name"), bundle.getString("address"),
                    bundle.getDouble("latitude"), bundle.getDouble("longitude"));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
