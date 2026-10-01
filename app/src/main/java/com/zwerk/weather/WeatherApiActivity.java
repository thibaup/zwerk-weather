package com.zwerk.weather;

import android.content.pm.ApplicationInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CountDownLatch;

abstract class WeatherApiActivity extends MinuteForecastViewsActivity {
    ForecastDiskCache forecastDiskCache;
    volatile HourlyPageState hourlyPageState;
    private static final long IN_MEMORY_FORECAST_MAX_AGE_MILLIS = 60L * 60L * 1000L;
    private static final int MAX_IN_MEMORY_FORECASTS = 8;
    private final Object inMemoryForecastLock = new Object();
    private final HashMap<String, InMemoryForecast> inMemoryForecasts = new HashMap<>();

    void cleanupForecastCaches() { forecastDiskCache.cleanup(); }

    ForecastCacheSnapshot readFreshForecastCache(double lat, double lon, String language, String requestLocationId) {
        ForecastDiskCache.Snapshot cached = forecastDiskCache.readFresh(
                lat, lon, language, scopedCacheLocationId(requestLocationId));
        if (cached != null) OpenMeteoForecastClient.pruneElapsedHourly(cached.hourly, Instant.now());
        return cached == null ? null : new ForecastCacheSnapshot(
                cached.current, cached.hourly, cached.daily, cached.fetchedAtMillis);
    }

    void persistForecastCacheQuietly(double lat, double lon, String language, String requestLocationId,
                                     JSONObject current, JSONObject hourly, JSONObject daily, long fetchedAt) {
        forecastDiskCache.persist(lat, lon, language, scopedCacheLocationId(requestLocationId),
                current, hourly, daily, fetchedAt);
    }

    private ForecastCacheSnapshot readInMemoryForecast(
            double lat, double lon, String language, String requestLocationId) {
        return readInMemoryForecast(lat, lon, language, requestLocationId, false);
    }

    private ForecastCacheSnapshot readInMemoryForecast(
            double lat, double lon, String language, String requestLocationId, boolean allowStale) {
        String key = forecastCacheScopeKey(lat, lon, language, requestLocationId);
        synchronized (inMemoryForecastLock) {
            InMemoryForecast cached = inMemoryForecasts.get(key);
            if (cached == null) return null;
            long now = System.currentTimeMillis();
            boolean openMeteo = cached.snapshot.current.has("_openMeteo");
            if (!ForecastClock.withinAge(cached.updatedAtMillis, now,
                    ForecastClock.retentionMillis(openMeteo))) {
                inMemoryForecasts.remove(key);
                return null;
            }
            if (!allowStale && !ForecastClock.withinAge(cached.updatedAtMillis, now,
                    IN_MEMORY_FORECAST_MAX_AGE_MILLIS)) return null;
            ForecastCacheSnapshot snapshot = copyForecastSnapshot(cached.snapshot);
            if (snapshot != null) {
                OpenMeteoForecastClient.pruneElapsedHourly(snapshot.hourly, Instant.now());
            }
            return snapshot;
        }
    }

    private void rememberInMemoryForecast(
            double lat, double lon, String language, String requestLocationId,
            JSONObject current, JSONObject hourly, JSONObject daily, long fetchedAtMillis) {
        ForecastCacheSnapshot snapshot = copyForecastSnapshot(
                new ForecastCacheSnapshot(current, hourly, daily, fetchedAtMillis));
        if (snapshot == null) return;
        synchronized (inMemoryForecastLock) {
            String scopeKey = forecastCacheScopeKey(lat, lon, language, requestLocationId);
            if (!inMemoryForecasts.containsKey(scopeKey)
                    && inMemoryForecasts.size() >= MAX_IN_MEMORY_FORECASTS) {
                String oldestKey = null;
                long oldestTime = Long.MAX_VALUE;
                for (java.util.Map.Entry<String, InMemoryForecast> entry : inMemoryForecasts.entrySet()) {
                    if (entry.getValue().updatedAtMillis < oldestTime) {
                        oldestTime = entry.getValue().updatedAtMillis;
                        oldestKey = entry.getKey();
                    }
                }
                if (oldestKey != null) inMemoryForecasts.remove(oldestKey);
            }
            inMemoryForecasts.put(scopeKey, new InMemoryForecast(snapshot, fetchedAtMillis));
        }
    }

    private static ForecastCacheSnapshot copyForecastSnapshot(ForecastCacheSnapshot source) {
        if (source == null || source.current == null || source.hourly == null || source.daily == null) {
            return null;
        }
        try {
            return new ForecastCacheSnapshot(
                    new JSONObject(source.current.toString()),
                    new JSONObject(source.hourly.toString()),
                    new JSONObject(source.daily.toString()),
                    source.fetchedAtMillis);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String scopedCacheLocationId(String locationId) {
        return (locationId == null ? "" : locationId) + "|" + OpenMeteoConfig.cacheScope(this);
    }

    private String forecastCacheScopeKey(
            double lat, double lon, String language, String requestLocationId) {
        return String.format(
                Locale.US,
                "%.5f|%.5f|%s|%s",
                lat,
                lon,
                language == null ? "" : language,
                scopedCacheLocationId(requestLocationId));
    }

    void restoreSavedForecastBeforeLocationCheck() {
        final double lat = latitude, lon = longitude;
        final String language = Locale.getDefault().toLanguageTag();
        final String locationId = selectedLocationId == null ? "" : selectedLocationId;
        final String scope = forecastCacheScopeKey(lat, lon, language, locationId);
        cacheExecutor.execute(() -> {
            ForecastDiskCache.Snapshot saved = forecastDiskCache.readLastKnown(
                    lat, lon, language, scopedCacheLocationId(locationId));
            if (saved == null) return;
            ForecastCacheSnapshot snapshot = new ForecastCacheSnapshot(
                    saved.current, saved.hourly, saved.daily, saved.fetchedAtMillis);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || lastCurrentWeather != null
                        || !scope.equals(forecastCacheScopeKey(latitude, longitude,
                                Locale.getDefault().toLanguageTag(), selectedLocationId))) return;
                boolean fresh = ForecastClock.withinAge(snapshot.fetchedAtMillis,
                        System.currentTimeMillis(), ForecastClock.FRESH_MILLIS);
                JSONObject current = fresh ? snapshot.current : SavedForecast.fallbackCurrent(
                        snapshot.current, snapshot.hourly, snapshot.daily,
                        snapshot.fetchedAtMillis, System.currentTimeMillis());
                if (current == null) return;
                try { current.put(SavedForecast.FETCHED_AT, snapshot.fetchedAtMillis); } catch (Exception ignored) { }
                hourlyPageState = cachedHourlyState(snapshot, weatherRequestGeneration,
                        lat, lon, language, locationId);
                rememberInMemoryForecast(lat, lon, language, locationId, snapshot.current,
                        snapshot.hourly, snapshot.daily, snapshot.fetchedAtMillis);
                publishForecastLocation(lat, lon, locationId);
                render(current, snapshot.hourly, snapshot.daily, temperatureUnitPreference());
                progress.setVisibility(View.VISIBLE);
                status.setText(UiTranslations.text(this, "Updating forecast…"));
                notifyActiveForecastStatusChanged();
            });
        });
    }

    private HourlyPageState cachedHourlyState(ForecastCacheSnapshot cached, int generation,
            double lat, double lon, String language, String locationId) {
        JSONObject hourly = cached.hourly;
        if (hourly.optString(ForecastDiskCache.BASE_REVISION, "").isEmpty()) {
            try {
                // Legacy readers of the same saved base must agree on its first revision.
                String identity = forecastCacheScopeKey(lat, lon, language, locationId)
                        + "|" + cached.fetchedAtMillis;
                hourly.put(ForecastDiskCache.BASE_REVISION,
                        UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString());
            } catch (Exception ignored) { }
        }
        boolean openMeteo = cached.current.has("_openMeteo");
        boolean hasCursor = hourly.has(ForecastDiskCache.HOURLY_TERMINAL);
        return new HourlyPageState(generation, lat, lon, language, locationId, hourly,
                hourly.optString(ForecastDiskCache.HOURLY_NEXT_TOKEN, ""),
                openMeteo || hasCursor && hourly.optBoolean(ForecastDiskCache.HOURLY_TERMINAL, false),
                !openMeteo && (!hasCursor || hourly.optBoolean(ForecastDiskCache.HOURLY_RESTART, false)));
    }

    private static void updateHourlyCacheState(HourlyPageState state) {
        try {
            state.aggregate.put(ForecastDiskCache.HOURLY_NEXT_TOKEN, state.nextPageToken);
            state.aggregate.put(ForecastDiskCache.HOURLY_TERMINAL, state.terminal);
            state.aggregate.put(ForecastDiskCache.HOURLY_RESTART, state.needsRestartFromPageOne);
        } catch (Exception ignored) { }
    }

    private void cacheExpandedHourlyForecast(HourlyPageState state) {
        if (!isHourlyStateCurrent(state) || lastCurrentWeather == null || lastDailyWeather == null
                || lastCurrentWeather.optBoolean(SavedForecast.FALLBACK, false)) return;
        long fetchedAt = lastCurrentWeather.optLong(SavedForecast.FETCHED_AT, 0L);
        if (fetchedAt <= 0L) return;
        updateHourlyCacheState(state);
        ForecastCacheSnapshot snapshot = copyForecastSnapshot(new ForecastCacheSnapshot(
                lastCurrentWeather, state.aggregate, lastDailyWeather, fetchedAt));
        if (snapshot == null) return;
        rememberInMemoryForecast(state.latitude, state.longitude, state.language, state.locationId,
                snapshot.current, snapshot.hourly, snapshot.daily, fetchedAt);
        final String scopedLocation = scopedCacheLocationId(state.locationId);
        cacheExecutor.execute(() -> forecastDiskCache.persistContinuation(state.latitude, state.longitude,
                state.language, scopedLocation, snapshot.current, snapshot.hourly, snapshot.daily, fetchedAt));
    }

    void startWeatherLoad(boolean forceNetwork) {
        forecastPreview.restore(false);
        final double lat = latitude;
        final double lon = longitude;
        final String language = Locale.getDefault().toLanguageTag();
        final String requestLocationId = selectedLocationId == null ? "" : selectedLocationId;
        final String providerScope = OpenMeteoConfig.cacheScope(this);
        final boolean networkAvailable = hasForecastNetwork();
        if (weatherLoadActive) {
            boolean sameScope = Math.abs(activeLoadLatitude - lat) <= WEATHER_CACHE_COORDINATE_TOLERANCE
                    && Math.abs(activeLoadLongitude - lon) <= WEATHER_CACHE_COORDINATE_TOLERANCE
                    && activeLoadLanguage.equals(language)
                    && activeLoadLocationId.equals(requestLocationId)
                    && activeLoadProviderScope.equals(providerScope);
            if (sameScope) {
                // Coalesce explicit forced refreshes behind the active request; passive duplicate
                // callbacks still add zero requests.
                if (forceNetwork) {
                    weatherReloadPending = true;
                    weatherReloadForcePending = true;
                }
                return;
            }
            // Supersede the previous location immediately; generation checks reject its late result.
            weatherRequestGeneration++;
            weatherLoadActive = false;
            weatherReloadPending = false;
            weatherReloadForcePending = false;
        }

        weatherLoadActive = true;
        forecastRefreshFailed = false;
        activeLoadLatitude = lat;
        activeLoadLongitude = lon;
        activeLoadLanguage = language;
        activeLoadLocationId = requestLocationId;
        activeLoadProviderScope = providerScope;
        progress.setVisibility(View.VISIBLE);
        status.setText(OpenMeteoConfig.isOpenMeteo(this)
                ? "Loading Open-Meteo forecast…" : "Loading Google Weather data…");
        notifyActiveForecastStatusChanged();
        final int generation = ++weatherRequestGeneration;
        hourlyCoverageQueue.reset();
        hourlyCoverageCoordinators.clear();
        rebindFreshMinuteStateToGeneration(generation, lat, lon, language);

        if (!forceNetwork && networkAvailable) {
            ForecastCacheSnapshot cached = readInMemoryForecast(
                    lat, lon, language, requestLocationId);
            if (cached != null) {
                HourlyPageState cachedState = cachedHourlyState(
                        cached, generation, lat, lon, language, requestLocationId);
                finishWeatherLoadSuccess(
                        cached.current, cached.hourly, cached.daily, cachedState, generation,
                        cached.fetchedAtMillis);
                return;
            }
        }

        Runnable networkLoad = () -> {
            try {
                if (!baseRequestScopeCurrent(generation, lat, lon, language, requestLocationId)) {
                    throw new SupersededWeatherRequestException();
                }
                if (!hasForecastNetwork()) throw new IOException("No network connection");
                if (!forceNetwork) {
                    // A previous request for this location may have completed while this
                    // request waited in the network queue after a quick location switch.
                    ForecastCacheSnapshot cached = readFreshForecastCache(
                            lat, lon, language, requestLocationId);
                    if (cached != null) {
                        HourlyPageState cachedState = cachedHourlyState(
                                cached, generation, lat, lon, language, requestLocationId);
                        runOnUiThread(() -> finishWeatherLoadSuccess(
                                cached.current, cached.hourly, cached.daily,
                                cachedState, generation, cached.fetchedAtMillis));
                        return;
                    }
                }
                if (OpenMeteoConfig.isOpenMeteo(this)) {
                    OpenMeteoForecastClient.Forecast forecast =
                            OpenMeteoForecastClient.loadForecast(
                                    this, lat, lon, OpenMeteoConfig.model(this),
                                    OpenMeteoConfig.readCustomerKey(this));
                    long fetchedAtMillis = System.currentTimeMillis();
                    // A finished response still belongs to the original location if the
                    // user moved elsewhere while HTTP was in flight. Cache it for a return.
                    if (providerScope.equals(OpenMeteoConfig.cacheScope(this))) {
                        persistForecastCacheQuietly(lat, lon, language, requestLocationId,
                                forecast.current, forecast.hourly, forecast.daily, fetchedAtMillis);
                    }
                    if (!baseRequestScopeCurrent(
                            generation, lat, lon, language, requestLocationId)) {
                        throw new SupersededWeatherRequestException();
                    }
                    HourlyPageState pageState = new HourlyPageState(
                            generation, lat, lon, language, requestLocationId,
                            forecast.hourly, "", true, false);
                    runOnUiThread(() -> finishWeatherLoadSuccess(
                            forecast.current, forecast.hourly, forecast.daily,
                            pageState, generation, fetchedAtMillis));
                    return;
                }
                String key = readApiKey();
                if (key.isEmpty()) throw new IllegalStateException("API key is not configured");
                String common = weatherCommonQuery(key, lat, lon, language);

                JSONObject current = requestLogical(
                        API_ROOT + "currentConditions:lookup" + common,
                        generation,
                        "current");
                if (!baseRequestScopeCurrent(generation, lat, lon, language, requestLocationId)) {
                    throw new SupersededWeatherRequestException();
                }
                JSONObject daily = requestLogical(
                        API_ROOT + "forecast/days:lookup" + common + "&days=10&pageSize=10",
                        generation,
                        "daily");
                if (!baseRequestScopeCurrent(generation, lat, lon, language, requestLocationId)) {
                    throw new SupersededWeatherRequestException();
                }
                HourlyPageLoad hourlyLoad = loadHourlyPageOneSafely(common, generation);
                if (!baseRequestScopeCurrent(generation, lat, lon, language, requestLocationId)) {
                    throw new SupersededWeatherRequestException();
                }
                JSONObject hourly = hourlyLoad.aggregate;
                HourlyPageState pageState = new HourlyPageState(
                        generation,
                        lat,
                        lon,
                        language,
                        requestLocationId,
                        hourly,
                        hourlyLoad.nextPageToken,
                        hourlyLoad.nextPageToken.isEmpty(),
                        !hourlyLoad.success);

                long fetchedAtMillis = System.currentTimeMillis();
                persistForecastCacheQuietly(
                        lat, lon, language, requestLocationId, current, hourly, daily, fetchedAtMillis);

                runOnUiThread(() -> finishWeatherLoadSuccess(
                        current, hourly, daily, pageState, generation, fetchedAtMillis));
            } catch (Exception e) {
                if (!baseRequestScopeCurrent(generation, lat, lon, language, requestLocationId)) return;
                ForecastCacheSnapshot saved = readInMemoryForecast(
                        lat, lon, language, requestLocationId, true);
                if (saved == null) {
                    ForecastDiskCache.Snapshot disk = forecastDiskCache.readLastKnown(
                            lat, lon, language, scopedCacheLocationId(requestLocationId));
                    if (disk != null) saved = new ForecastCacheSnapshot(
                            disk.current, disk.hourly, disk.daily, disk.fetchedAtMillis);
                }
                final ForecastCacheSnapshot fallback = saved;
                runOnUiThread(() -> {
                    if (!baseRequestScopeCurrent(generation, lat, lon, language, requestLocationId)) return;
                    JSONObject fallbackCurrent = fallback == null ? null : SavedForecast.fallbackCurrent(
                            fallback.current, fallback.hourly, fallback.daily,
                            fallback.fetchedAtMillis, System.currentTimeMillis());
                    if (fallbackCurrent == null) {
                        finishWeatherLoadFailure(e, generation);
                    } else {
                        // Saved pages are complete for offline display; expanding a day must
                        // not start paid continuation requests after the base load failed.
                        HourlyPageState savedState = new HourlyPageState(generation, lat, lon,
                                language, requestLocationId, fallback.hourly, "", true, false);
                        finishWeatherLoadSuccess(fallbackCurrent, fallback.hourly, fallback.daily,
                                savedState, generation, fallback.fetchedAtMillis);
                    }
                });
            }
        };

        if (!networkAvailable) {
            // Restore offline data independently of any older request still blocked in HTTP.
            cacheExecutor.execute(networkLoad);
        } else if (!forceNetwork) {
            // Keep cache I/O independent so a blocked HTTP request cannot delay location restoration.
            cacheExecutor.execute(() -> {
                ForecastCacheSnapshot cached = readFreshForecastCache(
                        lat, lon, language, requestLocationId);
                if (cached != null) {
                    HourlyPageState cachedState = cachedHourlyState(
                            cached, generation, lat, lon, language, requestLocationId);
                    runOnUiThread(() -> finishWeatherLoadSuccess(
                            cached.current, cached.hourly, cached.daily,
                            cachedState, generation, cached.fetchedAtMillis));
                    return;
                }
                // A quick location swipe can supersede a cold cache miss before any paid
                // Weather call starts. A deliberate refresh still starts immediately.
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (baseRequestScopeCurrent(generation, lat, lon, language,
                            requestLocationId)) {
                        executor.execute(networkLoad);
                    }
                }, 220L);
            });
        } else {
            executor.execute(networkLoad);
        }
    }

    boolean baseRequestScopeCurrent(
            int generation,
            double lat,
            double lon,
            String language,
            String requestLocationId) {
        return generation == weatherRequestGeneration
                && Math.abs(latitude - lat) <= WEATHER_CACHE_COORDINATE_TOLERANCE
                && Math.abs(longitude - lon) <= WEATHER_CACHE_COORDINATE_TOLERANCE
                && Locale.getDefault().toLanguageTag().equals(language)
                && activeLoadProviderScope.equals(OpenMeteoConfig.cacheScope(this))
                && (selectedLocationId == null ? "" : selectedLocationId)
                        .equals(requestLocationId == null ? "" : requestLocationId);
    }

    private boolean hasForecastNetwork() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) return true;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    static final class SupersededWeatherRequestException extends Exception {
        SupersededWeatherRequestException() {
            super("Weather request superseded");
        }
    }

    String weatherCommonQuery(
            String key, double lat, double lon, String language) throws Exception {
        return "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8.name())
                + "&location.latitude=" + lat
                + "&location.longitude=" + lon
                + "&unitsSystem=METRIC"
                + "&languageCode=" + URLEncoder.encode(language, StandardCharsets.UTF_8.name());
    }

    boolean isDebugBuild() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    JSONObject requestLogical(String address, int generation, String endpointKind) throws Exception {
        if (!requestScopeCurrent(generation)) throw new SupersededWeatherRequestException();
        if (isDebugBuild()) {
            Log.d(LOG_TAG, "request generation=" + generation + " endpoint=" + endpointKind);
        }
        try {
            JSONObject response = request(address);
            if (!requestScopeCurrent(generation)) throw new SupersededWeatherRequestException();
            return response;
        } catch (WeatherRequestException e) {
            if (e.statusCode == 408 || e.statusCode >= 500) {
                long jitter = 180L + ThreadLocalRandom.current().nextInt(121);
                SystemClock.sleep(jitter);
                if (!requestScopeCurrent(generation)) {
                    throw new SupersededWeatherRequestException();
                }
                if (isDebugBuild()) {
                    Log.d(LOG_TAG, "retry generation=" + generation + " endpoint=" + endpointKind);
                }
                DiagnosticLog.event(DiagnosticLog.Area.WEATHER, DiagnosticLog.Event.RETRY);
                JSONObject response = request(address);
                if (!requestScopeCurrent(generation)) throw new SupersededWeatherRequestException();
                return response;
            }
            // 429 and documented query-value rejections are deliberately not auto-retried.
            throw e;
        }
    }

    boolean requestScopeCurrent(int generation) {
        if (OpenMeteoConfig.isOpenMeteo(this)) return false;
        if (generation != weatherRequestGeneration) return false;
        String language = Locale.getDefault().toLanguageTag();
        String locationId = selectedLocationId == null ? "" : selectedLocationId;
        if (weatherLoadActive) {
            return Math.abs(activeLoadLatitude - latitude) <= WEATHER_CACHE_COORDINATE_TOLERANCE
                    && Math.abs(activeLoadLongitude - longitude) <= WEATHER_CACHE_COORDINATE_TOLERANCE
                    && activeLoadLanguage.equals(language)
                    && activeLoadLocationId.equals(locationId);
        }
        HourlyPageState state = hourlyPageState;
        return state == null || state.matches(
                generation, latitude, longitude, language, locationId, "METRIC");
    }

    void finishWeatherLoadSuccess(
            JSONObject current,
            JSONObject hourly,
            JSONObject daily,
            HourlyPageState pageState,
            int generation,
            long fetchedAtMillis) {
        if (generation != weatherRequestGeneration) return;
        try { current.put(SavedForecast.FETCHED_AT, fetchedAtMillis); } catch (Exception ignored) { }
        OpenMeteoForecastClient.pruneElapsedHourly(hourly, Instant.now());
        weatherLoadActive = false;
        forecastRefreshFailed = false;
        finishRefreshIndicator();
        double cachedLatitude = pageState == null ? latitude : pageState.latitude;
        double cachedLongitude = pageState == null ? longitude : pageState.longitude;
        String cachedLanguage = pageState == null
                ? Locale.getDefault().toLanguageTag() : pageState.language;
        String cachedLocationId = pageState == null
                ? (selectedLocationId == null ? "" : selectedLocationId) : pageState.locationId;
        if (!current.optBoolean(SavedForecast.FALLBACK, false)) rememberInMemoryForecast(
                cachedLatitude,
                cachedLongitude,
                cachedLanguage,
                cachedLocationId,
                current,
                hourly,
                daily,
                fetchedAtMillis);
        hourlyPageState = pageState;
        publishForecastLocation(cachedLatitude, cachedLongitude, cachedLocationId);
        render(current, hourly, daily, temperatureUnitPreference());
        // Publish this usable result before starting a queued forced refresh.
        if (startPendingWeatherReloadIfNeeded()) return;
        if (!current.optBoolean(SavedForecast.FALLBACK, false)) requestEnabledOptionalDataForCurrentScope();
        notifyActiveForecastStatusChanged();
    }

    private void publishForecastLocation(double lat, double lon, String locationId) {
        displayedForecastLatitude = lat;
        displayedForecastLongitude = lon;
        displayedForecastLocationId = locationId;
        displayedForecastLocationName = locationName;
        if (locationTitle != null) locationTitle.setText(forecastLocationName());
    }

    void finishWeatherLoadFailure(Exception error, int generation) {
        if (generation != weatherRequestGeneration) return;
        weatherLoadActive = false;
        finishRefreshIndicator();
        if (startPendingWeatherReloadIfNeeded()) return;
        showError(error);
    }

    boolean startPendingWeatherReloadIfNeeded() {
        if (!weatherReloadPending) return false;
        boolean forceNetwork = weatherReloadForcePending;
        weatherReloadPending = false;
        weatherReloadForcePending = false;
        refreshWeather(forceNetwork);
        return true;
    }

    static final class ForecastCacheSnapshot {
        final JSONObject current;
        final JSONObject hourly;
        final JSONObject daily;
        final long fetchedAtMillis;

        ForecastCacheSnapshot(JSONObject current, JSONObject hourly, JSONObject daily,
                long fetchedAtMillis) {
            this.current = current;
            this.hourly = hourly;
            this.daily = daily;
            this.fetchedAtMillis = fetchedAtMillis;
        }
    }

    static final class InMemoryForecast {
        final ForecastCacheSnapshot snapshot;
        final long updatedAtMillis;

        InMemoryForecast(ForecastCacheSnapshot snapshot, long updatedAtMillis) {
            this.snapshot = snapshot;
            this.updatedAtMillis = updatedAtMillis;
        }
    }

    static final class HourlyPageLoad {
        final JSONObject aggregate;
        final String nextPageToken;
        final boolean success;

        HourlyPageLoad(JSONObject aggregate, String nextPageToken, boolean success) {
            this.aggregate = aggregate == null ? unavailableHourlyForecast("Hourly unavailable") : aggregate;
            this.nextPageToken = nextPageToken == null ? "" : nextPageToken;
            this.success = success;
        }
    }

    static final class HourlyPageState {
        final int generation;
        final double latitude;
        final double longitude;
        final String language;
        final String locationId;
        final String unitSystem;
        final JSONObject aggregate;
        final JSONArray hours;
        final HashSet<String> identities = new HashSet<>();
        volatile String nextPageToken;
        volatile boolean terminal;
        volatile boolean needsRestartFromPageOne;

        HourlyPageState(
                int generation,
                double latitude,
                double longitude,
                String language,
                String locationId,
                JSONObject aggregate,
                String nextPageToken,
                boolean terminal,
                boolean needsRestartFromPageOne) {
            this.generation = generation;
            this.latitude = latitude;
            this.longitude = longitude;
            this.language = language == null ? "" : language;
            this.locationId = locationId == null ? "" : locationId;
            this.unitSystem = "METRIC";
            this.aggregate = aggregate == null ? new JSONObject() : aggregate;
            ForecastDiskCache.ensureBaseRevision(this.aggregate);
            JSONArray existing = this.aggregate.optJSONArray("forecastHours");
            if (existing == null) {
                existing = new JSONArray();
                try { this.aggregate.put("forecastHours", existing); } catch (Exception ignored) { }
            }
            this.hours = existing;
            for (int i = 0; i < hours.length(); i++) {
                String identity = hourlyIdentity(hours.optJSONObject(i));
                if (!identity.isEmpty()) identities.add(identity);
            }
            this.nextPageToken = nextPageToken == null ? "" : nextPageToken;
            this.terminal = terminal;
            this.needsRestartFromPageOne = needsRestartFromPageOne;
            updateHourlyCacheState(this);
        }

        boolean matches(
                int generation,
                double latitude,
                double longitude,
                String language,
                String locationId,
                String unitSystem) {
            return this.generation == generation
                    && Math.abs(this.latitude - latitude) <= WEATHER_CACHE_COORDINATE_TOLERANCE
                    && Math.abs(this.longitude - longitude) <= WEATHER_CACHE_COORDINATE_TOLERANCE
                    && this.language.equals(language == null ? "" : language)
                    && this.unitSystem.equals(unitSystem == null ? "" : unitSystem)
                    && this.locationId.equals(locationId == null ? "" : locationId);
        }
    }

    void performPullRefresh() {
        if (weatherLoadActive) {
            finishRefreshIndicator();
            refreshWeather(true, precipitationMode);
            return;
        }
        forecastPreview.restore(false);
        if (refreshIndicator != null) refreshIndicator.setRefreshing(true);
        refreshWeather(true, precipitationMode);
    }

    void finishRefreshIndicator() {
        if (refreshIndicator != null) refreshIndicator.finish();
    }

    HourlyPageLoad loadHourlyPageOneSafely(String common, int generation) {
        try {
            return requestHourlyPage(common, "", generation, "hourly-page-1");
        } catch (WeatherRequestException e) {
            return new HourlyPageLoad(
                    unavailableHourlyForecast(hourlyFailureSummary(e)), "", false);
        } catch (Exception ignored) {
            return new HourlyPageLoad(
                    unavailableHourlyForecast("Hourly request failed safely. Retry to try again."),
                    "",
                    false);
        }
    }

    HourlyPageLoad requestHourlyPage(
            String common,
            String pageToken,
            int generation,
            String endpointKind) throws Exception {
        JSONObject page = requestLogical(
                hourlyForecastAddress(common, HOURLY_FORECAST_HOURS, HOURLY_PAGE_SIZE, pageToken),
                generation,
                endpointKind);
        return aggregateHourlyPage(page);
    }

    HourlyPageLoad aggregateHourlyPage(JSONObject page) throws Exception {
        JSONObject aggregate = new JSONObject();
        JSONObject pageZone = firstJSONObject(page, "timeZone", "time_zone", "timezone");
        if (pageZone != null) aggregate.put("timeZone", pageZone);
        HourlyArrayResult result = hourlyArray(page);
        JSONArray output = new JSONArray();
        HashSet<String> seen = new HashSet<>();
        int invalid = 0;
        if (result.hours != null) {
            for (int i = 0; i < Math.min(HOURLY_PAGE_SIZE, result.hours.length()); i++) {
                JSONObject hour = result.hours.optJSONObject(i);
                String identity = hourlyIdentity(hour);
                if (hour == null || identity.isEmpty()) {
                    invalid++;
                    continue;
                }
                if (seen.add(identity)) output.put(hour);
            }
        }
        aggregate.put("forecastHours", output);
        String next = hourlyNextPageToken(page);
        aggregate.put(HOURLY_PARTIAL, !next.isEmpty());
        aggregate.put(HOURLY_LOAD_ERROR, result.hours == null);
        aggregate.put(HOURLY_TOP_KEYS, safeTopLevelKeys(page));
        aggregate.put(HOURLY_FIRST_PAGE_COUNT, result.hours == null ? -1 : result.hours.length());
        aggregate.put(HOURLY_NEXT_TOKEN_PRESENT, !next.isEmpty());
        if (result.hours == null) {
            aggregate.put(HOURLY_DIAGNOSTIC, boundedDiagnostic(result.reason));
        } else if (invalid > 0) {
            aggregate.put(HOURLY_DIAGNOSTIC,
                    "Ignored " + invalid + " malformed hourly record" + (invalid == 1 ? "." : "s."));
        }
        return new HourlyPageLoad(aggregate, next, result.hours != null);
    }

    String hourlyForecastAddress(
            String common,
            int hours,
            int pageSize,
            String pageToken) throws Exception {
        StringBuilder address = new StringBuilder(API_ROOT)
                .append("forecast/hours:lookup")
                .append(common)
                .append("&hours=").append(hours)
                .append("&pageSize=").append(pageSize);
        if (pageToken != null && !pageToken.isEmpty()) {
            address.append("&pageToken=")
                    .append(URLEncoder.encode(pageToken, StandardCharsets.UTF_8.name()));
        }
        return address.toString();
    }

    void retryHourlyPageOne() {
        if (OpenMeteoConfig.isOpenMeteo(this)) {
            refreshWeather(true);
            return;
        }
        final HourlyPageState state = hourlyPageState;
        if (!isHourlyStateCurrent(state) || !hourlyCoverageQueue.beginRetry(state)) return;
        executor.execute(() -> {
            try {
                String key = readApiKey();
                if (key.isEmpty()) throw new IllegalStateException("API key is not configured");
                String common = weatherCommonQuery(key, state.latitude, state.longitude, state.language);
                HourlyPageLoad page = requestHourlyPage(
                        common, "", state.generation, "hourly-page-1-retry");
                publishHourlyPage(state, page, true, true);
                if (!page.success) hourlyCoverageQueue.fail(state);
            } catch (Exception error) {
                markHourlyFailure(state, error);
                hourlyCoverageQueue.fail(state);
                runOnUiThread(() -> {
                    if (!isHourlyStateCurrent(state)) return;
                    hourlyCoverageCoordinators.clear();
                    rerenderLastWeather();
                });
            } finally {
                if (hourlyCoverageQueue.finish(state, isHourlyStateCurrent(state)))
                    executor.execute(() -> runHourlyCoverage(state));
                notifyHourlyCoverageProgress(state);
            }
        });
    }

    void ensureHourlyCoverage(
            LocalDate targetDate,
            HourlyCoverageCoordinator coordinator) {
        ensureHourlyCoverage(targetDate, coordinator, false);
    }

    void ensureHourlyCoverage(LocalDate targetDate, HourlyCoverageCoordinator coordinator, boolean retry) {
        if (targetDate == null) return;
        if (OpenMeteoConfig.isOpenMeteo(this)) return;
        HourlyPageState state = hourlyPageState;
        if (!isHourlyStateCurrent(state)) return;
        ZoneId zone = responseZone(lastCurrentWeather, lastHourlyWeather, lastDailyWeather);
        if (hourlyDateCovered(state, targetDate, zone)) {
            if (coordinator != null) coordinator.onHourlyCoverageChanged();
            return;
        }
        if (!retry && (state.aggregate.optBoolean(HOURLY_LOAD_ERROR, false)
                || state.terminal && !state.needsRestartFromPageOne)) return;
        if (coordinator != null) {
            boolean found = false;
            for (WeakReference<HourlyCoverageCoordinator> ref : hourlyCoverageCoordinators) {
                if (ref.get() == coordinator) { found = true; break; }
            }
            if (!found) hourlyCoverageCoordinators.add(new WeakReference<>(coordinator));
        }
        if (!hourlyCoverageQueue.request(state, targetDate)) return;
        executor.execute(() -> runHourlyCoverage(state));
    }

    void runHourlyCoverage(HourlyPageState state) {
        try {
            String key = readApiKey();
            if (key.isEmpty()) throw new IllegalStateException("API key is not configured");
            String common = weatherCommonQuery(key, state.latitude, state.longitude, state.language);
            ZoneId zone = responseZone(lastCurrentWeather, state.aggregate, lastDailyWeather);
            boolean tokenRestarted = false;
            HashSet<String> requestedTokens = new HashSet<>();
            int remainingRequests = 2 * ((HOURLY_FORECAST_HOURS + HOURLY_PAGE_SIZE - 1) / HOURLY_PAGE_SIZE);

            while (isHourlyStateCurrent(state)) {
                LocalDate target = hourlyCoverageQueue.targetFor(state);
                if (target == null) break;
                if (hourlyDateCovered(state, target, zone)) {
                    hourlyCoverageQueue.cancel(state, target);
                    break;
                }

                if (state.needsRestartFromPageOne) {
                    if (remainingRequests-- <= 0) throw new IllegalStateException("Hourly paging exceeded its horizon");
                    HourlyPageLoad first = requestHourlyPage(
                            common, "", state.generation, "hourly-page-1-coverage");
                    if (!first.success) throw new IllegalStateException("Hourly response was unavailable");
                    // A new cursor represents a new paging session; replace old values atomically.
                    if (!publishHourlyPage(state, first, true, false)) return;
                    requestedTokens.clear();
                    continue;
                }

                if (state.nextPageToken == null || state.nextPageToken.isEmpty()) {
                    state.terminal = true;
                    hourlyCoverageQueue.cancel(state, target);
                    break;
                }

                String token = state.nextPageToken;
                if (!requestedTokens.add(token))
                    throw new IllegalStateException("Hourly pagination repeated a page token");
                try {
                    if (remainingRequests-- <= 0) throw new IllegalStateException("Hourly paging exceeded its horizon");
                    HourlyPageLoad next = requestHourlyPage(
                            common, token, state.generation, "hourly-continuation");
                    if (!next.success) throw new IllegalStateException("Hourly response was unavailable");
                    if (!publishHourlyPage(state, next, false, false)) return;
                } catch (WeatherRequestException e) {
                    if (e.isPageTokenRejection() && !tokenRestarted) {
                        // Allow one cursor restart; repeated rejection must not loop forever.
                        tokenRestarted = true;
                        state.nextPageToken = "";
                        state.terminal = false;
                        state.needsRestartFromPageOne = true;
                        continue;
                    }
                    markHourlyFailure(state, e);
                    hourlyCoverageQueue.fail(state);
                    break;
                }
            }
        } catch (Exception error) {
            markHourlyFailure(state, error);
            hourlyCoverageQueue.fail(state);
        } finally {
            boolean restartForQueuedSelection = hourlyCoverageQueue.finish(state, isHourlyStateCurrent(state));
            notifyHourlyCoverageProgress(state);
            if (restartForQueuedSelection) {
                executor.execute(() -> runHourlyCoverage(state));
            }
        }
    }

    boolean isHourlyCoverageLoadingFor(LocalDate targetDate) {
        return hourlyCoverageQueue.isLoading(hourlyPageState, targetDate);
    }

    private boolean publishHourlyPage(HourlyPageState state, HourlyPageLoad page,
            boolean clearExisting, boolean rerender) throws InterruptedException {
        // JSON arrays and the view coordinators belong to the UI thread. The network
        // worker waits for publication before deciding which page is needed next.
        CountDownLatch published = new CountDownLatch(1);
        boolean[] accepted = {false};
        runOnUiThread(() -> {
            try {
                if (!isHourlyStateCurrent(state)) return;
                mergeHourlyPage(state, page, clearExisting && page.success);
                accepted[0] = true;
                if (rerender) {
                    hourlyCoverageCoordinators.clear();
                    lastHourlyWeather = state.aggregate;
                    cacheExpandedHourlyForecast(state);
                    rerenderLastWeather();
                } else {
                    notifyHourlyCoverageProgress(state);
                }
            } finally {
                published.countDown();
            }
        });
        published.await();
        return accepted[0];
    }

    boolean hourlyRetryPossible(HourlyPageState state) {
        if (state == null || !isHourlyStateCurrent(state)) return false;
        boolean failed = state.aggregate.optBoolean(HOURLY_LOAD_ERROR, false);
        return failed
                || state.needsRestartFromPageOne
                || (state.nextPageToken != null && !state.nextPageToken.isEmpty());
    }

    boolean isHourlyStateCurrent(HourlyPageState state) {
        if (state == null || state != hourlyPageState) return false;
        return state.matches(
                weatherRequestGeneration,
                latitude,
                longitude,
                Locale.getDefault().toLanguageTag(),
                selectedLocationId == null ? "" : selectedLocationId,
                "METRIC");
    }

    boolean hourlyDateCovered(HourlyPageState state, LocalDate target, ZoneId zone) {
        if (state == null || target == null) return false;
        boolean hasTarget = false;
        boolean hasLater = false;
        for (int i = 0; i < state.hours.length(); i++) {
            LocalDate date = hourLocalDate(state.hours.optJSONObject(i), zone);
            if (date == null) continue;
            if (target.equals(date)) hasTarget = true;
            if (date.isAfter(target)) hasLater = true;
        }
        return hasTarget && (hasLater || state.terminal);
    }

    void mergeHourlyPage(HourlyPageState state, HourlyPageLoad page, boolean clearExisting) {
        if (state == null || page == null) return;
        if (clearExisting) {
            while (state.hours.length() > 0) state.hours.remove(state.hours.length() - 1);
            state.identities.clear();
        }
        JSONArray pageHours = page.aggregate.optJSONArray("forecastHours");
        if (pageHours != null) {
            for (int i = 0; i < pageHours.length(); i++) {
                JSONObject hour = pageHours.optJSONObject(i);
                String identity = hourlyIdentity(hour);
                if (hour == null || identity.isEmpty() || !state.identities.add(identity)) continue;
                state.hours.put(hour);
            }
        }
        if (!state.aggregate.has("timeZone")) {
            JSONObject zone = firstJSONObject(page.aggregate, "timeZone", "time_zone", "timezone");
            if (zone != null) {
                try { state.aggregate.put("timeZone", zone); } catch (Exception ignored) { }
            }
        }
        state.nextPageToken = page.nextPageToken;
        state.terminal = page.nextPageToken.isEmpty();
        state.needsRestartFromPageOne = !page.success;
        updateHourlyCacheState(state);
        try {
            state.aggregate.put(HOURLY_PARTIAL, !state.terminal);
            state.aggregate.put(HOURLY_LOAD_ERROR, !page.success);
            if (page.success) state.aggregate.remove(HOURLY_DIAGNOSTIC);
        } catch (Exception ignored) { }
    }

    void markHourlyFailure(HourlyPageState state, Exception error) {
        if (state == null) return;
        String diagnostic = error instanceof WeatherRequestException
                ? hourlyFailureSummary((WeatherRequestException) error)
                : "Hourly continuation failed safely. Retry to try again.";
        runOnUiThread(() -> {
            if (!isHourlyStateCurrent(state)) return;
            try {
                state.aggregate.put(HOURLY_LOAD_ERROR, true);
                state.aggregate.put(HOURLY_PARTIAL, true);
                state.aggregate.put(HOURLY_DIAGNOSTIC, boundedDiagnostic(diagnostic));
                updateHourlyCacheState(state);
            } catch (Exception ignored) { }
            notifyHourlyCoverageProgress(state);
        });
    }

    void notifyHourlyCoverageProgress(HourlyPageState state) {
        runOnUiThread(() -> {
            if (!isHourlyStateCurrent(state)) return;
            lastHourlyWeather = state.aggregate;
            cacheExpandedHourlyForecast(state);
            for (int i = hourlyCoverageCoordinators.size() - 1; i >= 0; i--) {
                HourlyCoverageCoordinator coordinator = hourlyCoverageCoordinators.get(i).get();
                if (coordinator == null) {
                    hourlyCoverageCoordinators.remove(i);
                    continue;
                }
                coordinator.onHourlyCoverageChanged();
            }
        });
    }

    static final class HourlyArrayResult {
        final JSONArray hours;
        final String reason;

        HourlyArrayResult(JSONArray hours, String reason) {
            this.hours = hours;
            this.reason = reason == null ? "" : reason;
        }
    }

    static HourlyArrayResult hourlyArray(JSONObject page) {
        if (page == null) return new HourlyArrayResult(null, "Hourly response was empty");
        String[] keys = {"forecastHours", "forecast_hours", "hourlyForecast", "hourlyForecasts", "hours"};
        boolean foundNamedField = false;
        for (String key : keys) {
            if (!page.has(key) || page.isNull(key)) continue;
            foundNamedField = true;
            JSONArray array = page.optJSONArray(key);
            if (array != null) return new HourlyArrayResult(array, "");
        }
        return new HourlyArrayResult(
                null,
                foundNamedField ? "Hourly records field was not an array" : "No forecastHours array returned");
    }

    static String hourlyNextPageToken(JSONObject page) {
        if (page == null) return "";
        String[] keys = {"nextPageToken", "next_page_token", "nextPage_token"};
        for (String key : keys) {
            String value = page.optString(key, "").trim();
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) return value;
        }
        return "";
    }

    static JSONObject firstJSONObject(JSONObject object, String... keys) {
        if (object == null || keys == null) return null;
        for (String key : keys) {
            JSONObject value = object.optJSONObject(key);
            if (value != null) return value;
        }
        return null;
    }

    static String hourlyIdentity(JSONObject hour) {
        if (hour == null) return "";
        JSONObject interval = firstJSONObject(hour, "interval", "timeInterval", "time_interval");
        if (interval != null) {
            String start = firstNonEmpty(
                    interval.optString("startTime", ""),
                    interval.optString("start_time", ""));
            if (start.isEmpty()) start = interval.optString("start", "").trim();
            if (!start.isEmpty()) return "i:" + start;
        }
        JSONObject display = firstJSONObject(
                hour, "displayDateTime", "display_date_time", "displayTime", "display_time");
        if (display != null) return "d:" + display.toString();
        return "";
    }

    static String safeTopLevelKeys(JSONObject object) {
        if (object == null) return "";
        ArrayList<String> keys = new ArrayList<>();
        java.util.Iterator<String> iterator = object.keys();
        while (iterator.hasNext() && keys.size() < 10) {
            String raw = iterator.next();
            if (raw == null) continue;
            String safe = raw.replaceAll("[^A-Za-z0-9_.-]", "");
            if (safe.length() > 32) safe = safe.substring(0, 32);
            if (!safe.isEmpty()) keys.add(safe);
        }
        java.util.Collections.sort(keys);
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            if (out.length() > 0) out.append(", ");
            out.append(key);
        }
        return out.toString();
    }

    static String hourlyFailureSummary(WeatherRequestException error) {
        if (error == null) return "Hourly request failed";
        StringBuilder out = new StringBuilder();
        if (error.statusCode >= 400) {
            out.append("Request rejected (HTTP ").append(error.statusCode).append(')');
        } else if (error.transientFailure) {
            out.append("Hourly service temporarily unreachable");
        } else {
            out.append("Hourly response could not be used");
        }
        if (!error.serviceStatus.isEmpty()) out.append(" · ").append(error.serviceStatus);
        if (!error.serviceMessage.isEmpty()) out.append(" · ").append(error.serviceMessage);
        else if (!error.parseReason.isEmpty()) out.append(" · ").append(error.parseReason);
        return boundedDiagnostic(out.toString());
    }

    static JSONObject unavailableHourlyForecast(String diagnostic) {
        JSONObject hourly = new JSONObject();
        try {
            hourly.put("forecastHours", new JSONArray());
            hourly.put(HOURLY_PARTIAL, true);
            hourly.put(HOURLY_LOAD_ERROR, true);
            hourly.put(HOURLY_DIAGNOSTIC, boundedDiagnostic(diagnostic));
            hourly.put(HOURLY_TOP_KEYS, "");
            hourly.put(HOURLY_FIRST_PAGE_COUNT, -1);
            hourly.put(HOURLY_NEXT_TOKEN_PRESENT, false);
        } catch (Exception ignored) { }
        return hourly;
    }

    static String boundedDiagnostic(String value) {
        String safe = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (safe.length() > 220) safe = safe.substring(0, 217) + "…";
        return safe;
    }

    String readApiKey() throws Exception {
        File keyFile = new File(getFilesDir(), API_KEY_FILE);
        if (!keyFile.isFile()) return "";
        try (BufferedReader reader = new BufferedReader(new FileReader(keyFile))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        }
    }

    JSONObject request(String address) throws Exception {
        if (OpenMeteoConfig.isOpenMeteo(this)) {
            throw new SupersededWeatherRequestException();
        }
        ApiRequestBudgetManager.Decision budget = ApiRequestBudgetManager.tryAcquire(
                this, ApiRequestBudgetManager.Category.WEATHER);
        if (!budget.allowed) {
            DiagnosticLog.event(DiagnosticLog.Area.WEATHER, DiagnosticLog.Event.REQUEST_LIMIT);
            throw new WeatherRequestException(
                    -2,
                    false,
                    "Weather request limit reached",
                    "APP_REQUEST_LIMIT",
                    budget.message,
                    "");
        }
        long diagnosticStart = android.os.SystemClock.elapsedRealtime();
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(18000);
        connection.setRequestProperty("Accept", "application/json");
        applyAndroidApiKeyRestrictionHeaders(connection);

        try {
            int code = connection.getResponseCode();
            DiagnosticLog.http(DiagnosticLog.Area.WEATHER, code, android.os.SystemClock.elapsedRealtime() - diagnosticStart);
            if (code < 200 || code >= 300) {
                String errorBody = readUtf8Bounded(connection.getErrorStream(), ERROR_BODY_LIMIT_BYTES);
                ServiceErrorInfo info = parseSafeServiceError(errorBody);
                String safeMessage = safeWeatherServiceMessage(code);
                if (!info.message.isEmpty()) {
                    safeMessage = info.message;
                }
                throw new WeatherRequestException(
                        code,
                        code == 408 || code == 429 || code >= 500,
                        safeWeatherServiceMessage(code),
                        info.status,
                        safeMessage,
                        "");
            }

            String body = readUtf8(connection.getInputStream());
            if (body.trim().isEmpty()) {
                throw new WeatherRequestException(
                        code,
                        false,
                        "Weather service returned an empty response",
                        "",
                        "",
                        "Empty response");
            }
            try {
                return new JSONObject(body);
            } catch (Exception ignored) {
                throw new WeatherRequestException(
                        code,
                        false,
                        "Weather service returned invalid data",
                        "",
                        "",
                        "Invalid JSON response");
            }
        } catch (SocketTimeoutException ignored) {
            DiagnosticLog.error(DiagnosticLog.Area.WEATHER, ignored);
            throw new WeatherRequestException(
                    -1,
                    true,
                    "Weather service timed out",
                    "",
                    "Weather service timed out",
                    "Network timeout");
        } catch (IOException ignored) {
            DiagnosticLog.error(DiagnosticLog.Area.WEATHER, ignored);
            throw new WeatherRequestException(
                    -1,
                    true,
                    "Weather service connection failed",
                    "",
                    "Weather service connection failed",
                    "Network connection failure");
        } finally {
            connection.disconnect();
        }
    }

    void applyAndroidApiKeyRestrictionHeaders(HttpURLConnection connection) {
        if (connection == null) return;
        String certificate = signingCertificateSha1();
        if (certificate.isEmpty()) return;
        connection.setRequestProperty("X-Android-Package", getPackageName());
        connection.setRequestProperty("X-Android-Cert", certificate.replace(":", ""));
    }

    static String optionalFailureReason(String body, int statusCode) {
        try {
            JSONObject root = new JSONObject(body == null ? "" : body);
            JSONObject error = root.optJSONObject("error");
            if (error != null) {
                JSONArray details = error.optJSONArray("details");
                if (details != null) {
                    for (int i = 0; i < details.length(); i++) {
                        JSONObject detail = details.optJSONObject(i);
                        String reason = detail == null
                                ? "" : sanitizeServiceStatus(detail.optString("reason", ""));
                        if (!reason.isEmpty()) return reason;
                    }
                }
                String status = sanitizeServiceStatus(error.optString("status", ""));
                if (!status.isEmpty()) return status;
            }
        } catch (Exception ignored) { }
        return statusCode > 0 ? "HTTP_" + statusCode : "UNKNOWN";
    }

    static final class OptionalRequestException extends Exception {
        final int statusCode;
        final String endpointName;
        final String reason;
        final String detail;

        OptionalRequestException(int statusCode, String endpointName, String reason) {
            this(statusCode, endpointName, reason, "");
        }

        OptionalRequestException(
                int statusCode, String endpointName, String reason, String detail) {
            super(detail == null || detail.trim().isEmpty()
                    ? "Optional Google data request failed" : detail);
            this.statusCode = statusCode;
            this.endpointName = endpointName == null ? "optional" : endpointName;
            this.reason = reason == null || reason.trim().isEmpty() ? "UNKNOWN" : reason;
            this.detail = detail == null ? "" : detail;
        }
    }

    static ServiceErrorInfo parseSafeServiceError(String body) {
        if (body == null || body.trim().isEmpty()) return new ServiceErrorInfo("", "");
        try {
            JSONObject root = new JSONObject(body);
            JSONObject error = root.optJSONObject("error");
            if (error == null) return new ServiceErrorInfo("", "");
            String status = sanitizeServiceStatus(error.optString("status", ""));
            String message = sanitizeServiceMessage(error.optString("message", ""));
            return new ServiceErrorInfo(status, message);
        } catch (Exception ignored) {
            return new ServiceErrorInfo("", "");
        }
    }

    static String sanitizeServiceStatus(String value) {
        if (value == null) return "";
        String safe = value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_-]", "");
        return safe.length() > 48 ? safe.substring(0, 48) : safe;
    }

    static String sanitizeServiceMessage(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        String safe = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        safe = safe.replaceAll("(?i)https?://\\S+", "[redacted]");
        safe = safe.replaceAll("\\?\\S+", "[redacted]");
        safe = safe.replaceAll(
                "(?i)(api[_ -]?key|key|page[_ -]?token|token|authorization)\\s*[:=]\\s*[^ ,;]+",
                "$1=[redacted]");
        safe = safe.replaceAll(
                "(?i)(location(?:\\.latitude|\\.longitude)?|latitude|longitude|lat|lon)\\s*[:=]?\\s*[-+]?\\d{1,3}(?:\\.\\d{2,})?",
                "$1=[redacted]");
        safe = safe.replaceAll(
                "(?i)(address|formatted[_ -]?address)\\s*[:=]\\s*[^,;]+",
                "$1=[redacted]");
        safe = safe.replaceAll("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b", "[redacted]");
        safe = safe.replaceAll("[-+]?\\d{1,3}\\.\\d{4,}", "[redacted]");
        safe = safe.replaceAll("[A-Za-z0-9_\\-=]{32,}", "[redacted]");
        safe = safe.replaceAll("\\s+", " ").trim();
        if (safe.length() > 150) safe = safe.substring(0, 147).trim() + "…";
        return safe;
    }

    static String safeWeatherServiceMessage(int statusCode) {
        if (statusCode == 401 || statusCode == 403) {
            return "Weather service authorization failed";
        }
        if (statusCode == 408) return "Weather service timed out";
        if (statusCode == 429) return "Weather service is busy. Try again shortly";
        if (statusCode >= 500) return "Weather service is temporarily unavailable";
        if (statusCode >= 400) return "Weather request was not accepted";
        return "Weather service request failed";
    }

    static final class ServiceErrorInfo {
        final String status;
        final String message;

        ServiceErrorInfo(String status, String message) {
            this.status = status == null ? "" : status;
            this.message = message == null ? "" : message;
        }
    }

    static final class WeatherRequestException extends Exception {
        final int statusCode;
        final boolean transientFailure;
        final String serviceStatus;
        final String serviceMessage;
        final String parseReason;

        WeatherRequestException(
                int statusCode,
                boolean transientFailure,
                String message,
                String serviceStatus,
                String serviceMessage,
                String parseReason) {
            super(message);
            this.statusCode = statusCode;
            this.transientFailure = transientFailure;
            this.serviceStatus = serviceStatus == null ? "" : serviceStatus;
            this.serviceMessage = serviceMessage == null ? "" : serviceMessage;
            this.parseReason = parseReason == null ? "" : parseReason;
        }

        boolean isPageTokenRejection() {
            if (statusCode != 400 && statusCode != 422) return false;
            String detail = (serviceStatus + " " + serviceMessage).toLowerCase(Locale.ROOT);
            return detail.contains("pagetoken") || detail.contains("page_token")
                    || detail.contains("page token") || detail.contains("cursor");
        }
    }

    static String readUtf8Bounded(InputStream stream, int limitBytes) throws IOException {
        if (stream == null || limitBytes <= 0) return "";
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int remaining = limitBytes;
            while (remaining > 0) {
                int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) break;
                out.write(buffer, 0, read);
                remaining -= read;
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    static String readUtf8(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

}
