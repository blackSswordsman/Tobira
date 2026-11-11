package com.example.tobira.debug;

import android.car.Car;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

/** Same read-only speed property as bench-test, with a dedicated worker and scoped lifetime. */
public final class BenchSpeedSource {
    public interface Listener {
        void onSpeed(float metersPerSecond, long timestampNs);
        void onUnavailable();
    }

    private final Context context;
    private final Listener listener;
    private HandlerThread thread;
    private Handler worker;
    private Car car;
    private CarPropertyManager manager;
    private volatile boolean active;

    public BenchSpeedSource(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (active) return;
        active = true;
        thread = new HandlerThread("bench-speed");
        thread.start();
        worker = new Handler(thread.getLooper());
        car = Car.createCar(context, worker, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT, (connectedCar, ready) -> {
            if (!active) return;
            worker.post(() -> {
                if (!active) return;
                if (!ready) {
                    manager = null;
                    listener.onUnavailable();
                    return;
                }
                try {
                    manager = (CarPropertyManager) connectedCar.getCarManager(Car.PROPERTY_SERVICE);
                    if (manager == null || manager.getCarPropertyConfig(
                            VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY) == null
                            || !manager.registerCallback(callback,
                            VehiclePropertyIds.PERF_VEHICLE_SPEED_DISPLAY, CarPropertyManager.SENSOR_RATE_NORMAL)) {
                        listener.onUnavailable();
                    }
                } catch (RuntimeException error) {
                    listener.onUnavailable();
                }
            });
        });
        if (car == null) listener.onUnavailable();
    }

    private final CarPropertyManager.CarPropertyEventCallback callback =
            new CarPropertyManager.CarPropertyEventCallback() {
        @Override public void onChangeEvent(CarPropertyValue value) {
            if (!active) return;
            if (value == null || value.getStatus() != CarPropertyValue.STATUS_AVAILABLE
                    || !(value.getValue() instanceof Float)) {
                listener.onUnavailable();
                return;
            }
            listener.onSpeed((Float) value.getValue(), value.getTimestamp());
        }
        @Override public void onErrorEvent(int propertyId, int areaId) {
            if (active) listener.onUnavailable();
        }
    };

    public void stop() {
        active = false;
        if (worker == null) return;
        HandlerThread closingThread = thread;
        Car closingCar = car;
        worker.post(() -> {
            try {
                if (manager != null) manager.unregisterCallback(callback);
            } catch (RuntimeException ignored) {
                // Disconnected service.
            } finally {
                manager = null;
                if (closingCar != null) closingCar.disconnect();
                closingThread.quitSafely();
            }
        });
        car = null;
    }
}
