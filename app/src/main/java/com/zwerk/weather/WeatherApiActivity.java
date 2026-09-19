package com.zwerk.weather;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.system.Os;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.Executors;


abstract class WeatherApiActivity extends MinuteForecastViewsActivity {
    ForecastDiskCache forecastDiskCache;
    HourlyPageState hourlyPageState;
    private static final long IN_MEMORY_FORECAST_MAX_AGE_MILLIS = 60L * 60L * 1000L;
    private static final int MAX_IN_MEMORY_FORECASTS = 8;
    private final Object inMemoryForecastLock = new Object();
    private final HashMap<String, InMemoryForecast> inMemoryForecasts = new HashMap<>();

    void cleanupForecastCaches() { forecastDiskCache.cleanup(); }

    ForecastCacheSnapshot readFreshForecastCache(double lat, double lon, String language, String requestLocationId) {
        ForecastDiskCache.Snapshot cached = forecastDiskCache.readFresh(lat, lon, language, requestLocationId);
        return cached == null ? null : new ForecastCacheSnapshot(cached.current, cached.hourly, cached.daily);
    }

    void persistForecastCacheQuietly(double lat, double lon, String language, String requestLocationId,
                                     JSONObject current, JSONObject hourly, JSONObject daily) {
        forecastDiskCache.persist(lat, lon, language, requestLocationId, current, hourly, daily);
    }

    private ForecastCacheSnapshot readInMemoryForecast(
            double lat, double lon, String language, String requestLocationId) {
        String key = forecastCacheScopeKey(lat, lon, language, requestLocationId);
        synchronized (inMemoryForecastLock) {
            InMemoryForecast cached = inMemoryForecasts.get(key);
            if (cached == null) return null;
            if (System.currentTimeMillis() - cached.updatedAtMillis >= IN_MEMORY_FORECAST_MAX_AGE_MILLIS) {
                inMemoryForecasts.remove(key);
                return null;
            }
            return copyForecastSnapshot(cached.snapshot);
        }
    }

    private void rememberInMemoryForecast(
            double lat, double lon, String language, String requestLocationId,
            JSONObject current, JSONObject hourly, JSONObject daily) {
        ForecastCacheSnapshot snapshot = copyForecastSnapshot(
                new ForecastCacheSnapshot(current, hourly, daily));
        if (snapshot == null) return;
        synchronized (inMemoryForecastLock) {
            if (inMemoryForecasts.size() >= MAX_IN_MEMORY_FORECASTS) {
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
            inMemoryForecasts.put(
                    forecastCacheScopeKey(lat, lon, language, requestLocationId),
                    new InMemoryForecast(snapshot, System.currentTimeMillis()));
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
                    new JSONObject(source.daily.toString()));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String forecastCacheScopeKey(
            double lat, double lon, String language, String requestLocationId) {
        return String.format(
                Locale.US,
                "%.5f|%.5f|%s|%s",
                lat,
                lon,
                language == null ? "" : language,
                requestLocationId == null ? "" : requestLocationId);
    }

    void startWeatherLoad(boolean forceNetwork) {
        forecastPreview.restore(false);
        final double lat = latitude;
        final double lon = longitude;
        final String language = Locale.getDefault().getLanguage();
        final String requestLocationId = selectedLocationId == null ? "" : selectedLocationId;
        if (weatherLoadActive) {
            boolean sameScope = Math.abs(activeLoadLatitude - lat) <= WEATHER_CACHE_COORDINATE_TOLERANCE
                    && Math.abs(activeLoadLongitude - lon) <= WEATHER_CACHE_COORDINATE_TOLERANCE
                    && activeLoadLanguage.equals(language)
                    && activeLoadLocationId.equals(requestLocationId);
            if (sameScope) {
                // Coalesce explicit forced refreshes behind the active request; passive duplicate
                // callbacks still add zero requests.
                if (forceNetwork) {
                    weatherReloadPending = true;
                    weatherReloadForcePending = true;
                }
                return;
            }
            // A location change must supersede the old request immediately. Waiting for the
            // previous network call can leave its data on screen and delays the new location's
            // disk/in-memory cache lookup. The old worker observes the generation change and
            // exits without publishing a result.
            weatherRequestGeneration++;
            weatherLoadActive = false;
            weatherReloadPending = false;
            weatherReloadForcePending = false;
        }

        weatherLoadActive = true;
        activeLoadLatitude = lat;
        activeLoadLongitude = lon;
        activeLoadLanguage = language;
        activeLoadLocationId = requestLocationId;
        progress.setVisibility(View.VISIBLE);
        status.setText("Loading Google Weather data…");
        final int generation = ++weatherRequestGeneration;
        synchronized (hourlyCoverageLock) {
            pendingHourlyCoverageTarget = null;
            hourlyCoverageLoadingTarget = null;
            hourlyCoverageCoordinators.clear();
        }
        rebindFreshMinuteStateToGeneration(generation, lat, lon, language);

        if (!forceNetwork) {
            ForecastCacheSnapshot cached = readInMemoryForecast(
                    lat, lon, language, requestLocationId);
            if (cached != null) {
                HourlyPageState cachedState = new HourlyPageState(
                        generation, lat, lon, language, requestLocationId,
                        cached.hourly, "", false, true);
                finishWeatherLoadSuccess(
                        cached.current, cached.hourly, cached.daily, cachedState, generation);
                return;
            }
        }

        Runnable networkLoad = () -> {
            try {
                if (!baseRequestScopeCurrent(generation, lat, lon, language, requestLocationId)) {
                    throw new SupersededWeatherRequestException();
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

                persistForecastCacheQuietly(
                        lat, lon, language, requestLocationId, current, hourly, daily);

                runOnUiThread(() -> finishWeatherLoadSuccess(
                        current, hourly, daily, pageState, generation));
            } catch (Exception e) {
                runOnUiThread(() -> finishWeatherLoadFailure(e, generation));
            }
        };

        if (!forceNetwork) {
            // Keep disk I/O independent from the serialized network executor. A previous
            // location request may still be blocked in HTTP, but a saved location's cache can
            // be restored immediately while that request is being superseded.
            cacheExecutor.execute(() -> {
                ForecastCacheSnapshot cached = readFreshForecastCache(
                        lat, lon, language, requestLocationId);
                if (cached != null) {
                    HourlyPageState cachedState = new HourlyPageState(
                            generation, lat, lon, language, requestLocationId,
                            cached.hourly, "", false, true);
                    runOnUiThread(() -> finishWeatherLoadSuccess(
                            cached.current, cached.hourly, cached.daily,
                            cachedState, generation));
                    return;
                }
                executor.execute(networkLoad);
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
                && Locale.getDefault().getLanguage().equals(language)
                && (selectedLocationId == null ? "" : selectedLocationId)
                        .equals(requestLocationId == null ? "" : requestLocationId);
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
                JSONObject response = request(address);
                if (!requestScopeCurrent(generation)) throw new SupersededWeatherRequestException();
                return response;
            }
            // 429 and documented query-value rejections are deliberately not auto-retried.
            throw e;
        }
    }

    boolean requestScopeCurrent(int generation) {
        if (generation != weatherRequestGeneration) return false;
        String language = Locale.getDefault().getLanguage();
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
            int generation) {
        if (generation != weatherRequestGeneration) return;
        weatherLoadActive = false;
        finishRefreshIndicator();
        double cachedLatitude = pageState == null ? latitude : pageState.latitude;
        double cachedLongitude = pageState == null ? longitude : pageState.longitude;
        String cachedLanguage = pageState == null
                ? Locale.getDefault().getLanguage() : pageState.language;
        String cachedLocationId = pageState == null
                ? (selectedLocationId == null ? "" : selectedLocationId) : pageState.locationId;
        rememberInMemoryForecast(
                cachedLatitude,
                cachedLongitude,
                cachedLanguage,
                cachedLocationId,
                current,
                hourly,
                daily);
        if (startPendingWeatherReloadIfNeeded()) return;
        hourlyPageState = pageState;
        render(current, hourly, daily, temperatureUnitPreference());
        requestEnabledOptionalDataForCurrentScope();
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

        ForecastCacheSnapshot(JSONObject current, JSONObject hourly, JSONObject daily) {
            this.current = current;
            this.hourly = hourly;
            this.daily = daily;
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
        String nextPageToken;
        boolean terminal;
        boolean needsRestartFromPageOne;

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
        final HourlyPageState state = hourlyPageState;
        if (state == null || state.generation != weatherRequestGeneration) return;
        synchronized (hourlyCoverageLock) {
            if (hourlyCoverageLoadActive) return;
            hourlyCoverageLoadActive = true;
            hourlyCoverageLoadingTarget = null;
        }
        executor.execute(() -> {
            try {
                String key = readApiKey();
                if (key.isEmpty()) throw new IllegalStateException("API key is not configured");
                String common = weatherCommonQuery(key, state.latitude, state.longitude, state.language);
                HourlyPageLoad page = requestHourlyPage(
                        common, "", state.generation, "hourly-page-1-retry");
                if (!isHourlyStateCurrent(state)) return;
                mergeHourlyPage(state, page, true);
                runOnUiThread(() -> {
                    if (!isHourlyStateCurrent(state)) return;
                    lastHourlyWeather = state.aggregate;
                    rerenderLastWeather();
                });
            } catch (Exception error) {
                markHourlyFailure(state, error);
                runOnUiThread(() -> {
                    if (isHourlyStateCurrent(state)) rerenderLastWeather();
                });
            } finally {
                synchronized (hourlyCoverageLock) {
                    hourlyCoverageLoadActive = false;
                    hourlyCoverageLoadingTarget = null;
                    // This explicit page-one retry triggers a full weather rerender. Any detail
                    // selection/coordinator queued against the pre-rerender hierarchy is stale.
                    pendingHourlyCoverageTarget = null;
                    hourlyCoverageCoordinators.clear();
                }
            }
        });
    }

    void ensureHourlyCoverage(
            LocalDate targetDate,
            HourlyCoverageCoordinator coordinator) {
        if (targetDate == null) return;
        HourlyPageState state = hourlyPageState;
        if (state == null || state.generation != weatherRequestGeneration) return;
        ZoneId zone = responseZone(lastCurrentWeather, lastHourlyWeather, lastDailyWeather);
        if (hourlyDateCovered(state, targetDate, zone)) {
            if (coordinator != null) coordinator.onHourlyCoverageChanged();
            return;
        }
        synchronized (hourlyCoverageLock) {
            if (coordinator != null) {
                boolean found = false;
                for (WeakReference<HourlyCoverageCoordinator> ref : hourlyCoverageCoordinators) {
                    if (ref.get() == coordinator) { found = true; break; }
                }
                if (!found) hourlyCoverageCoordinators.add(new WeakReference<>(coordinator));
            }
            // Track the most recently selected date. The loading target is date-scoped so an
            // unrelated expanded day never inherits a global "Loading…" state.
            pendingHourlyCoverageTarget = targetDate;
            hourlyCoverageLoadingTarget = targetDate;
            if (hourlyCoverageLoadActive) return;
            hourlyCoverageLoadActive = true;
        }
        executor.execute(() -> runHourlyCoverage(state));
    }

    void runHourlyCoverage(HourlyPageState state) {
        try {
            String key = readApiKey();
            if (key.isEmpty()) throw new IllegalStateException("API key is not configured");
            String common = weatherCommonQuery(key, state.latitude, state.longitude, state.language);
            ZoneId zone = responseZone(lastCurrentWeather, state.aggregate, lastDailyWeather);

            while (isHourlyStateCurrent(state)) {
                LocalDate target;
                synchronized (hourlyCoverageLock) {
                    target = pendingHourlyCoverageTarget;
                    if (target != null) hourlyCoverageLoadingTarget = target;
                }
                if (target == null) break;
                if (hourlyDateCovered(state, target, zone)) {
                    clearPendingHourlyTargetIf(target);
                    break;
                }

                if (state.needsRestartFromPageOne) {
                    HourlyPageLoad first = requestHourlyPage(
                            common, "", state.generation, "hourly-page-1-coverage");
                    if (!isHourlyStateCurrent(state)) return;
                    mergeHourlyPage(state, first, false);
                    state.needsRestartFromPageOne = false;
                    notifyHourlyCoverageProgress(state);
                    continue;
                }

                if (state.nextPageToken == null || state.nextPageToken.isEmpty()) {
                    state.terminal = true;
                    clearPendingHourlyTargetIf(target);
                    break;
                }

                String token = state.nextPageToken;
                try {
                    HourlyPageLoad next = requestHourlyPage(
                            common, token, state.generation, "hourly-continuation");
                    if (!isHourlyStateCurrent(state)) return;
                    mergeHourlyPage(state, next, false);
                    notifyHourlyCoverageProgress(state);
                } catch (WeatherRequestException e) {
                    if (e.statusCode == 400 || e.statusCode == 422) {
                        // Page-token lifetime is undocumented. Restart page one, then walk only as far as needed.
                        state.nextPageToken = "";
                        state.terminal = false;
                        state.needsRestartFromPageOne = true;
                        continue;
                    }
                    markHourlyFailure(state, e);
                    clearPendingHourlyTargetIf(target);
                    break;
                }
            }
        } catch (Exception error) {
            markHourlyFailure(state, error);
            synchronized (hourlyCoverageLock) {
                pendingHourlyCoverageTarget = null;
            }
        } finally {
            boolean restartForQueuedSelection = false;
            synchronized (hourlyCoverageLock) {
                hourlyCoverageLoadActive = false;
                if (pendingHourlyCoverageTarget != null && isHourlyStateCurrent(state)) {
                    // A selection can arrive after the worker decided it was done. Preserve it
                    // and hand the same generation back to the executor instead of dropping it.
                    hourlyCoverageLoadActive = true;
                    hourlyCoverageLoadingTarget = pendingHourlyCoverageTarget;
                    restartForQueuedSelection = true;
                } else {
                    hourlyCoverageLoadingTarget = null;
                    if (!isHourlyStateCurrent(state)) pendingHourlyCoverageTarget = null;
                }
            }
            notifyHourlyCoverageProgress(state);
            if (restartForQueuedSelection) {
                executor.execute(() -> runHourlyCoverage(state));
            }
        }
    }

    void clearPendingHourlyTargetIf(LocalDate target) {
        if (target == null) return;
        synchronized (hourlyCoverageLock) {
            if (target.equals(pendingHourlyCoverageTarget)) {
                pendingHourlyCoverageTarget = null;
            }
        }
    }

    boolean isHourlyCoverageLoadingFor(LocalDate targetDate) {
        if (targetDate == null) return false;
        synchronized (hourlyCoverageLock) {
            return hourlyCoverageLoadActive
                    && targetDate.equals(hourlyCoverageLoadingTarget);
        }
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
                Locale.getDefault().getLanguage(),
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
        try {
            state.aggregate.put(HOURLY_LOAD_ERROR, true);
            state.aggregate.put(HOURLY_PARTIAL, true);
            state.aggregate.put(HOURLY_DIAGNOSTIC, boundedDiagnostic(diagnostic));
        } catch (Exception ignored) { }
    }

    void notifyHourlyCoverageProgress(HourlyPageState state) {
        runOnUiThread(() -> {
            if (!isHourlyStateCurrent(state)) return;
            lastHourlyWeather = state.aggregate;
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
        ApiRequestBudgetManager.Decision budget = ApiRequestBudgetManager.tryAcquire(
                this, ApiRequestBudgetManager.Category.WEATHER);
        if (!budget.allowed) {
            throw new WeatherRequestException(
                    -2,
                    false,
                    "Weather request limit reached",
                    "APP_REQUEST_LIMIT",
                    budget.message,
                    "");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(18000);
        connection.setRequestProperty("Accept", "application/json");
        applyAndroidApiKeyRestrictionHeaders(connection);

        try {
            int code = connection.getResponseCode();
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
            throw new WeatherRequestException(
                    -1,
                    true,
                    "Weather service timed out",
                    "",
                    "Weather service timed out",
                    "Network timeout");
        } catch (IOException ignored) {
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

        boolean isParameterRejection() {
            if (statusCode != 400 && statusCode != 422) return false;
            String detail = (serviceStatus + " " + serviceMessage).toLowerCase(Locale.ROOT);
            if (detail.contains("location")
                    || detail.contains("latitude")
                    || detail.contains("longitude")
                    || detail.contains("language")
                    || detail.contains("unitssystem")
                    || detail.contains("units system")
                    || detail.contains("api key")
                    || detail.contains("credential")
                    || detail.contains("quota")) {
                return false;
            }
            return detail.trim().isEmpty()
                    || detail.contains("hour")
                    || detail.contains("page")
                    || detail.contains("invalid_argument");
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
