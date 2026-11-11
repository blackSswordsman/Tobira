package com.example.tobira;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Photon suggestions with debounce, cancellation, caching and a process-wide rate limit. */
public class PlaceSearchViewModel extends AndroidViewModel {
    public enum Status { IDLE, LOADING, RESULTS, EMPTY, ERROR, RATE_LIMITED }

    public static final class State {
        public final Status status;
        public final List<Place> places;

        State(Status status, List<Place> places) {
            this.status = status;
            this.places = Collections.unmodifiableList(new ArrayList<>(places));
        }
    }

    private static final String DEFAULT_ENDPOINT = "https://photon.komoot.io/api/";
    private static final ExecutorService SEARCH_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .build();
    private static long nextRequestAt;
    private static final long CACHE_AGE_MS = TimeUnit.DAYS.toMillis(1);
    private final MutableLiveData<State> state =
            new MutableLiveData<>(new State(Status.IDLE, Collections.emptyList()));
    private final SharedPreferences preferences;
    private final SharedPreferences cache;
    private volatile int generation;
    private volatile Call currentCall;
    private Future<?> currentTask;
    private Place selectedPlace;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingSuggestion;
    private String currentQuery = "";

    public void onQueryChanged(String text) {
        String query = text.trim();
        if (query.equals(currentQuery)) return;
        currentQuery = query;
        cancelSearch();
        selectedPlace = null;
        state.setValue(new State(Status.IDLE, Collections.emptyList()));
        if (query.length() < 2 || query.length() > 200) return;
        pendingSuggestion = () -> search(query);
        main.postDelayed(pendingSuggestion, 600);
    }

    public PlaceSearchViewModel(@NonNull Application application) {
        super(application);
        preferences = application.getSharedPreferences("photon-search-settings", 0);
        cache = application.getSharedPreferences("photon-search-cache", 0);
    }

    public LiveData<State> getState() {
        return state;
    }

    public Place getSelectedPlace() {
        return selectedPlace;
    }

    public void select(Place place) {
        if (place != null) cancelSearch();
        selectedPlace = place;
    }

    public String getEndpoint() {
        return preferences.getString("endpoint", DEFAULT_ENDPOINT);
    }

    public boolean setEndpoint(String value) {
        HttpUrl endpoint = HttpUrl.parse(value.trim());
        if (endpoint == null || !endpoint.isHttps() || endpoint.host().isEmpty()
                || !endpoint.username().isEmpty() || !endpoint.password().isEmpty()
                || endpoint.query() != null || endpoint.fragment() != null
                || endpoint.host().equals("nominatim.openstreetmap.org")) return false;
        cancelSearch();
        preferences.edit().putString("endpoint", endpoint.toString()).apply();
        selectedPlace = null;
        state.setValue(new State(Status.IDLE, Collections.emptyList()));
        return true;
    }

    public void search(String query) {
        query = query.trim();
        if (query.length() < 2 || query.length() > 200) return;
        cancelSearch();
        selectedPlace = null;
        int token = generation;
        String searchQuery = query;
        String endpoint = getEndpoint();
        state.setValue(new State(Status.LOADING, Collections.emptyList()));
        currentTask = SEARCH_EXECUTOR.submit(() -> performSearch(token, endpoint, searchQuery));
    }

    private void performSearch(int token, String endpoint, String query) {
        String cacheKey = endpoint + "|ja|" + query;
        String cached = cache.getString(cacheKey, null);
        if (cached != null) {
            try {
                org.json.JSONObject entry = new org.json.JSONObject(cached);
                if (System.currentTimeMillis() - entry.getLong("time") < CACHE_AGE_MS) {
                    publishResults(token, parseResults(entry.getString("body")));
                    return;
                }
            } catch (JSONException | IllegalArgumentException ignored) {
                cache.edit().remove(cacheKey).apply();
            }
        }
        try {
            long delay = nextRequestAt - SystemClock.elapsedRealtime();
            if (delay > 2000) {
                publish(token, Status.RATE_LIMITED, Collections.emptyList());
                return;
            }
            if (delay > 0) Thread.sleep(delay);
            if (token != generation || Thread.currentThread().isInterrupted()) return;
            HttpUrl baseUrl = HttpUrl.parse(endpoint);
            if (baseUrl == null) throw new IOException("Invalid endpoint");
            HttpUrl url = baseUrl.newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("limit", "8")
                    .build();
            Request request = new Request.Builder().url(url)
                    .header("User-Agent", "Tobira/1.0 (com.example.tobira)")
                    .header("Accept", "application/json")
                    .header("Accept-Language", "ja,en;q=0.8").build();
            Call call = CLIENT.newCall(request);
            currentCall = call;
            if (token != generation) {
                call.cancel();
                return;
            }
            nextRequestAt = SystemClock.elapsedRealtime() + 1100;
            try (Response response = call.execute()) {
                if (response.code() == 429) {
                    // Respect server backoff across subsequent searches in this process.
                    long seconds = 60;
                    try {
                        seconds = Math.max(60, Long.parseLong(response.header("Retry-After", "60")));
                    } catch (NumberFormatException ignored) { }
                    nextRequestAt = SystemClock.elapsedRealtime()
                            + TimeUnit.SECONDS.toMillis(Math.min(seconds, 86400));
                    publish(token, Status.RATE_LIMITED, Collections.emptyList());
                    return;
                }
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) throw new IOException("Search failed");
                String json = body.string();
                List<Place> results = parseResults(json);
                if (token != generation) return;
                org.json.JSONObject entry = new org.json.JSONObject();
                entry.put("time", System.currentTimeMillis());
                entry.put("body", json);
                SharedPreferences.Editor editor = cache.edit();
                if (cache.getAll().size() >= 30) editor.clear();
                editor.putString(cacheKey, entry.toString()).apply();
                publishResults(token, results);
            } finally {
                if (currentCall == call) currentCall = null;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | JSONException | IllegalArgumentException exception) {
            publish(token, Status.ERROR, Collections.emptyList());
        }
    }

    static List<Place> parseResults(String json) throws JSONException {
        JSONArray items = new org.json.JSONObject(json).getJSONArray("features");
        List<Place> places = new ArrayList<>();
        for (int index = 0; index < Math.min(items.length(), 8); index++) {
            try {
                places.add(Place.fromPhotonJson(items.getJSONObject(index)));
            } catch (JSONException | IllegalArgumentException ignored) {
                // Skip an invalid item without hiding other valid results.
            }
        }
        return places;
    }

    private void publishResults(int token, List<Place> results) {
        publish(token, results.isEmpty() ? Status.EMPTY : Status.RESULTS, results);
    }

    private void publish(int token, Status status, List<Place> results) {
        // Deliver on the main thread, rechecking after any queued newer search.
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (generation == token) state.setValue(new State(status, results));
        });
    }

    private void cancelSearch() {
        generation++;
        if (pendingSuggestion != null) main.removeCallbacks(pendingSuggestion);
        pendingSuggestion = null;
        if (currentTask != null) currentTask.cancel(true);
        Call call = currentCall;
        if (call != null) call.cancel();
    }

    @Override
    protected void onCleared() {
        cancelSearch();
    }
}
