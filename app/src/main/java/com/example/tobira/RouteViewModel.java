package com.example.tobira;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.json.JSONException;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Owns a single explicit route request across Activity recreation. */
public class RouteViewModel extends ViewModel {
    public enum Status { IDLE, LOADING, READY, NO_ROUTE, ERROR, RATE_LIMITED }

    public static final class State {
        public final Status status;
        public final DrivingRoute route;
        public final int requestId;

        private State(Status status, DrivingRoute route, int requestId) {
            this.status = status;
            this.route = route;
            this.requestId = requestId;
        }
    }

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .build();
    private static long nextRequestAt;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MutableLiveData<State> state =
            new MutableLiveData<>(new State(Status.IDLE, null, 0));
    private Call currentCall;
    private int generation;

    public LiveData<State> getState() {
        return state;
    }

    public State currentState() {
        return state.getValue();
    }

    public void clear() {
        generation++;
        if (currentCall != null) currentCall.cancel();
        currentCall = null;
        state.setValue(new State(Status.IDLE, null, generation));
    }

    public void build(String endpoint, java.util.List<Place> points) {
        if (state.getValue().status == Status.LOADING) return;
        clear();
        int requestId = generation;
        if (SystemClock.elapsedRealtime() < nextRequestAt) {
            state.setValue(new State(Status.RATE_LIMITED, null, requestId));
            return;
        }
        HttpUrl url;
        try {
            url = routeUrl(endpoint, points);
        } catch (IllegalArgumentException exception) {
            state.setValue(new State(Status.ERROR, null, requestId));
            return;
        }
        Request request = new Request.Builder().url(url)
                .header("User-Agent", "Tobira/1.0 (com.example.tobira)")
                .header("Accept", "application/json").build();
        nextRequestAt = SystemClock.elapsedRealtime() + 1500;
        state.setValue(new State(Status.LOADING, null, requestId));
        currentCall = CLIENT.newCall(request);
        currentCall.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException exception) {
                deliver(requestId, Status.ERROR, null);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (response) {
                    if (response.code() == 429) {
                        main.post(() -> {
                            nextRequestAt = Math.max(nextRequestAt, SystemClock.elapsedRealtime() + 60000);
                        });
                        deliver(requestId, Status.RATE_LIMITED, null);
                        return;
                    }
                    ResponseBody body = response.body();
                    if (body == null) throw new IOException("Empty response");
                    String json = body.string();
                    // OSRM can report NoSegment/NoRoute with an HTTP 400 response.
                    DrivingRoute route = DrivingRoute.parse(json);
                    if (!response.isSuccessful()) throw new IOException("Route server error");
                    deliver(requestId, Status.READY, route);
                } catch (DrivingRoute.NoRouteException exception) {
                    deliver(requestId, Status.NO_ROUTE, null);
                } catch (IOException | JSONException | IllegalArgumentException exception) {
                    deliver(requestId, Status.ERROR, null);
                }
            }
        });
    }

    static HttpUrl routeUrl(String endpoint, java.util.List<Place> points) {
        if (points == null || points.size() < 2 || points.size() > RoutePlan.MAX_STOPS + 2) {
            throw new IllegalArgumentException("Invalid number of points");
        }
        StringBuilder coordinates = new StringBuilder();
        StringBuilder radiuses = new StringBuilder();
        for (Place point : points) {
            if (point == null) throw new IllegalArgumentException("Missing route point");
            if (coordinates.length() > 0) {
                coordinates.append(';');
                radiuses.append(';');
            }
            coordinates.append(String.format(Locale.US, "%.6f,%.6f",
                    point.longitude, point.latitude));
            radiuses.append("1000");
        }
        HttpUrl base = HttpUrl.parse(endpoint);
        if (base == null || !base.isHttps()) throw new IllegalArgumentException("Invalid server");
        return base.newBuilder().addPathSegment(coordinates.toString())
                .addQueryParameter("overview", "full")
                .addQueryParameter("geometries", "geojson")
                .addQueryParameter("steps", "false")
                .addQueryParameter("alternatives", "false")
                .addQueryParameter("radiuses", radiuses.toString())
                .build();
    }

    private void deliver(int token, Status status, DrivingRoute route) {
        main.post(() -> {
            if (token != generation) return;
            currentCall = null;
            state.setValue(new State(status, route, token));
        });
    }

    @Override
    protected void onCleared() {
        generation++;
        if (currentCall != null) currentCall.cancel();
    }
}
