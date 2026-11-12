package com.example.tobira;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.location.LocationManagerCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

/** Foreground-only location source, deliberately independent of MapLibre and layout inflation. */
public final class QuestArrivalTracker implements DefaultLifecycleObserver {
    private final AppCompatActivity activity;
    private final QuestArrivalViewModel model;
    private final LocationManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean resumed, listening;
    private final LocationListener listener = new LocationListener() {
        @Override public void onLocationChanged(@NonNull Location location) {
            if (!resumed || !hasPermission()) return;
            model.onFix(new ArrivalPolicy.Fix(location.getLatitude(), location.getLongitude(),
                    location.hasAccuracy() ? location.getAccuracy() : Float.NaN,
                    location.getElapsedRealtimeNanos()));
        }
        @Override public void onProviderDisabled(@NonNull String provider) { refresh(); }
        @Override public void onProviderEnabled(@NonNull String provider) { refresh(); }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    };
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (!hasPermission()) { refresh(); return; }
            if (manager == null || !LocationManagerCompat.isLocationEnabled(manager)) { refresh(); return; }
            if (!listening) { refresh(); return; }
            model.recheck();
            main.postDelayed(this, 5000);
        }
    };

    public QuestArrivalTracker(AppCompatActivity activity, QuestArrivalViewModel model) {
        this.activity = activity;
        this.model = model;
        manager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
        activity.getLifecycle().addObserver(this);
    }

    private boolean hasPermission() {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    public void refresh() {
        stop();
        if (!resumed) return;
        main.postDelayed(heartbeat, 5000);
        if (!hasPermission()) { model.onUnavailable(QuestArrivalViewModel.Status.NO_PERMISSION); return; }
        if (manager == null || !LocationManagerCompat.isLocationEnabled(manager)) {
            model.onUnavailable(QuestArrivalViewModel.Status.LOCATION_DISABLED);
            return;
        }
        model.onUnavailable(QuestArrivalViewModel.Status.WAITING);
        boolean fine = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        try {
            for (String provider : manager.getProviders(true)) {
                if (LocationManager.PASSIVE_PROVIDER.equals(provider)) continue;
                if (!fine && LocationManager.GPS_PROVIDER.equals(provider)) continue;
                manager.requestLocationUpdates(provider, 1000, 0, listener, Looper.getMainLooper());
                listening = true;
                Location cached = manager.getLastKnownLocation(provider);
                if (cached != null) listener.onLocationChanged(cached);
            }
        } catch (SecurityException denied) {
            stop();
            main.postDelayed(heartbeat, 5000);
            model.onUnavailable(QuestArrivalViewModel.Status.NO_PERMISSION);
        } catch (IllegalArgumentException unavailable) {
            model.onUnavailable(QuestArrivalViewModel.Status.WAITING);
        }
    }

    private void stop() {
        listening = false;
        main.removeCallbacks(heartbeat);
        if (manager != null) {
            try { manager.removeUpdates(listener); } catch (SecurityException ignored) { }
        }
    }

    @Override public void onResume(@NonNull LifecycleOwner owner) { resumed = true; refresh(); }
    @Override public void onPause(@NonNull LifecycleOwner owner) { resumed = false; stop(); }
}
