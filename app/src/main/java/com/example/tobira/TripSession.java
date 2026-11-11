package com.example.tobira;

import android.os.Bundle;

/** Trip display state, independent of the map renderer. */
public final class TripSession {
    private boolean active;
    private boolean following;

    public boolean isActive() {
        return active;
    }

    public boolean isFollowing() {
        return active && following;
    }

    public boolean start(boolean routeReady, boolean freshLocation) {
        if (!routeReady || !freshLocation) return false;
        active = true;
        following = true;
        return true;
    }

    public void stop() {
        active = false;
        following = false;
    }

    public void dismissTracking() {
        following = false;
    }

    public void resumeTracking() {
        if (active) following = true;
    }

    public Bundle save() {
        Bundle bundle = new Bundle();
        bundle.putBoolean("active", active);
        bundle.putBoolean("following", following);
        return bundle;
    }

    public void restore(Bundle bundle, boolean routeReady) {
        stop();
        if (bundle != null && routeReady && bundle.getBoolean("active")) {
            active = true;
            following = bundle.getBoolean("following");
        }
    }
}
