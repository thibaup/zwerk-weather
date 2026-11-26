package com.zwerk.weather;

import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

abstract class MinuteForecastActivity extends OptionalWeatherDataActivity {
    /** Renders the prepared precipitation page for the current minute-forecast state. */
    protected abstract void rerenderPrecipitationPage();
    /** Notifies subclasses when minute-forecast state changes on the UI thread. */
    protected void onMinuteForecastUpdated() { }
    private Runnable minuteExpirationRunnable;

    void cleanupMinuteForecastCaches() {
        // MinuteCacheStore performs throttled cleanup and one-time legacy cleanup.
    }

    MinuteCacheSnapshot readFreshMinuteForecastCache(
            double lat,
            double lon,
            String language, String source) {
        MinuteCacheStore.Snapshot cached = MinuteCacheStore.readFresh(
                this, lat, lon, language, source, MINUTE_CACHE_MAX_AGE_MILLIS);
        if (cached == null || cached.response == null) return null;
        JSONObject filtered = filterElapsedMinuteResponse(cached.response, Instant.now());
        return new MinuteCacheSnapshot(cached.fetchedAtMillis, filtered);
    }

    void persistMinuteForecastCacheQuietly(
            double lat,
            double lon,
            String language,
            long fetchedAtMillis,
            JSONObject rawResponse, String source) {
        MinuteCacheStore.write(this, lat, lon, language, source, fetchedAtMillis, rawResponse);
    }

    static JSONObject filterElapsedMinuteResponse(JSONObject raw, Instant now) {
        if (raw == null) return null;
        try {
            JSONObject copy = new JSONObject(raw.toString());
            JSONArray input = copy.optJSONArray("segments");
            if (input == null) return copy;
            JSONArray filtered = new JSONArray();
            Instant cutoff = now == null ? Instant.now() : now;
            for (int i = 0; i < input.length(); i++) {
                JSONObject segment = input.optJSONObject(i);
                if (segment == null) continue;
                JSONObject frame = segment.optJSONObject("timeFrame");
                Instant end = parseInstant(frame == null ? "" : frame.optString("endTime", ""));
                if (end != null && end.isAfter(cutoff)) filtered.put(segment);
            }
            copy.put("segments", filtered);
            return copy;
        } catch (Exception ignored) {
            return raw;
        }
    }

    void invalidateMinuteForecastState() {
        cancelMinuteExpiration();
        synchronized (minuteForecastLock) {
            minuteForecastState = null;
        }
        synchronized (optionalDataLock) {
            airQualityRequestSerial++;
            pollenRequestSerial++;
            airQualityState = null;
            pollenState = null;
        }
        minuteSelectedTimeMillis = Long.MIN_VALUE;
        notifyActiveForecastStatusChanged();
    }

    void rebindFreshMinuteStateToGeneration(
            int generation,
            double lat,
            double lon,
            String language) {
        MinuteForecastState rebound = null;
        synchronized (minuteForecastLock) {
            MinuteForecastState state = minuteForecastState;
            if (state == null) return;
            if (!state.sameLocationLanguage(lat, lon, language)) {
                minuteForecastState = null;
                minuteSelectedTimeMillis = Long.MIN_VALUE;
                return;
            }
            if (state.isFresh(System.currentTimeMillis())) {
                rebound = state.rebind(generation);
                minuteForecastState = rebound;
            } else {
                minuteForecastState = null;
            }
        }
        if (rebound != null) scheduleMinuteExpiration(rebound);
    }

    void ensureMinuteForecast(boolean forceNetwork) {
        final int generation = weatherRequestGeneration;
        final String source = OpenMeteoConfig.precipitationCacheScope(this);
        final double lat = latitude;
        final double lon = longitude;
        final String language = AppLocaleManager.activeLocale(this).toLanguageTag();
        MinuteForecastState pendingRequestState = null;
        synchronized (minuteForecastLock) {
            MinuteForecastState current = minuteForecastState;
            boolean keepCurrent = current != null
                    && current.matches(generation, lat, lon, language)
                    && (current.loading
                            || (!forceNetwork && current.isFresh(System.currentTimeMillis()))
                            || (!forceNetwork
                                    && current.response == null
                                    && !current.errorMessage.isEmpty()));
            if (!keepCurrent) {
                pendingRequestState = new MinuteForecastState(
                        generation, lat, lon, language, true, 0L, null, "", false);
                minuteForecastState = pendingRequestState;
            }
        }

        // Synchronize the offscreen page even when the current state needs no new request.
        rerenderPrecipitationPreservingScroll();
        onMinuteForecastUpdated();
        notifyActiveForecastStatusChanged();
        if (pendingRequestState == null) return;
        final MinuteForecastState requestState = pendingRequestState;

        final String model = OpenMeteoConfig.model(this);
        executor.execute(() -> {
            try {
                if (!forceNetwork) {
                    MinuteCacheSnapshot cached = readFreshMinuteForecastCache(lat, lon, language, source);
                    if (cached != null) {
                        MinuteForecastState ready = new MinuteForecastState(
                                generation,
                                lat,
                                lon,
                                language,
                                false,
                                cached.fetchedAtMillis,
                                cached.response,
                                "",
                                false);
                        runOnUiThread(() -> finishMinuteForecastRequest(requestState, ready, source));
                        return;
                    }
                }
                if (!minuteRequestScopeCurrent(requestState, source)) {
                    throw new SupersededWeatherRequestException();
                }
                JSONObject raw;
                if (!OpenMeteoConfig.GOOGLE.equals(source)) {
                    raw = OpenMeteoForecastClient.loadMinute(
                            this, lat, lon, model,
                            OpenMeteoConfig.readCustomerKey(this));
                } else {
                    String key = readApiKey();
                    if (key.isEmpty()) throw new IllegalStateException("API key is not configured");
                    raw = PrecipitationForecast.loadGoogle("", lat, lon, Instant.now(),
                            Duration.ofHours(6), address -> requestMinuteLogical(address, requestState, source));
                }
                if (!minuteRequestScopeCurrent(requestState, source)) {
                    throw new SupersededWeatherRequestException();
                }
                long fetchedAtMillis = System.currentTimeMillis();
                persistMinuteForecastCacheQuietly(lat, lon, language, fetchedAtMillis, raw, source);
                JSONObject filtered = filterElapsedMinuteResponse(raw, Instant.now());
                MinuteForecastState ready = new MinuteForecastState(
                        generation,
                        lat,
                        lon,
                        language,
                        false,
                        fetchedAtMillis,
                        filtered,
                        "",
                        false);
                runOnUiThread(() -> finishMinuteForecastRequest(requestState, ready, source));
            } catch (SupersededWeatherRequestException | InterruptedException ignored) {
                // A newer location/generation owns the screen; discard this result silently.
            } catch (Exception error) {
                boolean unsupported = minuteForecastUnsupported(error);
                MinuteForecastState failed = new MinuteForecastState(
                        generation,
                        lat,
                        lon,
                        language,
                        false,
                        0L,
                        null,
                        minuteForecastErrorMessage(error, unsupported),
                        unsupported);
                runOnUiThread(() -> finishMinuteForecastRequest(requestState, failed, source));
            }
        });
    }

    void finishMinuteForecastRequest(
            MinuteForecastState requestState,
            MinuteForecastState resultState, String source) {
        synchronized (minuteForecastLock) {
            if (!minuteRequestScopeCurrent(requestState, source)) return;
            if (!resultState.matches(
                    weatherRequestGeneration,
                    latitude,
                    longitude,
                    AppLocaleManager.activeLocale(this).toLanguageTag())) {
                return;
            }
            minuteForecastState = resultState;
        }
        if (resultState.response != null && RainAlertManager.isEnabled(this)) {
            RainAlertManager.evaluateMinuteForecast(
                    this, resultState.response, latitude, longitude, locationName);
        }
        if (resultState.response != null) {
            PrecipitationWidgetProvider.publishMinute(this, resultState.response,
                    resultState.fetchedAtMillis, latitude, longitude);
        }
        scheduleMinuteExpiration(resultState);
        rerenderPrecipitationPreservingScroll();
        onMinuteForecastUpdated();
        notifyActiveForecastStatusChanged();
    }

    void scheduleMinuteExpiration(MinuteForecastState state) {
        if (state == null || state.response == null || state.fetchedAtMillis <= 0L || mainScroll == null) return;
        cancelMinuteExpiration();
        long expiresAt = state.fetchedAtMillis + MINUTE_CACHE_MAX_AGE_MILLIS;
        long delay = Math.max(1L, expiresAt - System.currentTimeMillis() + 25L);
        minuteExpirationRunnable = () -> {
            boolean expired = false;
            synchronized (minuteForecastLock) {
                MinuteForecastState current = minuteForecastState;
                if (current != null
                        && current.generation == state.generation
                        && current.fetchedAtMillis == state.fetchedAtMillis
                        && current.response != null
                        && !current.isFresh(System.currentTimeMillis())) {
                    minuteForecastState = null;
                    expired = true;
                }
            }
            if (expired) {
                cleanupMinuteForecastCaches();
                rerenderPrecipitationPreservingScroll();
                onMinuteForecastUpdated();
                notifyActiveForecastStatusChanged();
            }
            minuteExpirationRunnable = null;
        };
        mainScroll.postDelayed(minuteExpirationRunnable, delay);
    }

    void cancelMinuteExpiration() {
        if (mainScroll != null && minuteExpirationRunnable != null) {
            mainScroll.removeCallbacks(minuteExpirationRunnable);
        }
        minuteExpirationRunnable = null;
    }

    boolean minuteRequestScopeCurrent(MinuteForecastState requestState, String source) {
        synchronized (minuteForecastLock) {
            return minuteForecastState == requestState
                    && requestState != null
                    && source.equals(OpenMeteoConfig.precipitationCacheScope(this))
                    && requestState.matches(
                            weatherRequestGeneration,
                            latitude,
                            longitude,
                            AppLocaleManager.activeLocale(this).toLanguageTag());
        }
    }

    JSONObject requestMinuteLogical(
            String address,
            MinuteForecastState requestState, String source) throws Exception {
        if (!minuteRequestScopeCurrent(requestState, source)) throw new InterruptedException();
        if (isDebugBuild()) {
            Log.d(LOG_TAG, "request generation=" + requestState.generation + " endpoint=minute");
        }
        try {
            JSONObject response = request(address, true);
            if (!minuteRequestScopeCurrent(requestState, source)) {
                throw new InterruptedException();
            }
            return response;
        } catch (WeatherRequestException error) {
            if (error.statusCode == 408 || error.statusCode >= 500) {
                long jitter = 180L + ThreadLocalRandom.current().nextInt(121);
                SystemClock.sleep(jitter);
                if (!minuteRequestScopeCurrent(requestState, source)) {
                    throw new InterruptedException();
                }
                if (isDebugBuild()) {
                    Log.d(LOG_TAG, "retry generation=" + requestState.generation + " endpoint=minute");
                }
                DiagnosticLog.event(DiagnosticLog.Area.PRECIPITATION, DiagnosticLog.Event.RETRY);
                JSONObject response = request(address, true);
                if (!minuteRequestScopeCurrent(requestState, source)) {
                    throw new InterruptedException();
                }
                return response;
            }
            // Never auto-retry 429.
            throw error;
        }
    }

    static boolean minuteForecastUnsupported(Exception error) {
        if (!(error instanceof WeatherRequestException)) return false;
        int status = ((WeatherRequestException) error).statusCode;
        return status == 400 || status == 404 || status == 422;
    }

    static String minuteForecastErrorMessage(Exception error, boolean unsupported) {
        if (unsupported) {
            return "Minute precipitation is unavailable for this location.";
        }
        if (error instanceof WeatherRequestException) {
            int status = ((WeatherRequestException) error).statusCode;
            if (status == 401 || status == 403) {
                return "Minute precipitation is unavailable with the current Weather API access.";
            }
            if (status == 429) {
                return "Minute precipitation is temporarily busy. Try again shortly.";
            }
            if (status == 408 || status >= 500) {
                return "Minute precipitation is temporarily unavailable. Try again.";
            }
        }
        if (error instanceof IllegalStateException
                && "API key is not configured".equals(error.getMessage())) {
            return "Minute precipitation requires the configured Google API key.";
        }
        return "Minute precipitation could not be loaded. Try again.";
    }

    MinuteForecastState minuteForecastStateForCurrentScope() {
        boolean stale = false;
        synchronized (minuteForecastLock) {
            MinuteForecastState state = minuteForecastState;
            if (state == null || !state.matches(
                    weatherRequestGeneration,
                    latitude,
                    longitude,
                    AppLocaleManager.activeLocale(this).toLanguageTag())) {
                return null;
            }
            if (state.response != null && !state.isFresh(System.currentTimeMillis())) {
                minuteForecastState = null;
                stale = true;
            } else {
                return state;
            }
        }
        if (stale) cleanupMinuteForecastCaches();
        return null;
    }

    String minuteForecastStatusLabel() {
        MinuteForecastState state = minuteForecastStateForCurrentScope();
        if (state == null) return "Precipitation data not loaded";
        if (state.loading) return "Loading precipitation data…";
        if (state.response == null) return "Precipitation data unavailable";
        return fetchedDataAgeLabel("Precipitation data", state.fetchedAtMillis);
    }

    void rerenderPrecipitationPreservingScroll() {
        if (precipitationPageContent == null || content == null) return;
        boolean precipitationPageActive = precipitationMode
                && activePageContent == precipitationPageContent;
        int scrollY = precipitationPageActive && mainScroll != null
                ? mainScroll.getScrollY() : 0;

        // Update the offscreen page now so switching tabs cannot reveal stale controls.
        rerenderPrecipitationPage();

        if (precipitationPageActive && mainScroll != null) {
            mainScroll.post(() -> {
                if (!precipitationMode || activePageContent != precipitationPageContent) return;
                View scrollChild = mainScroll.getChildCount() == 0 ? null : mainScroll.getChildAt(0);
                int viewportHeight = Math.max(0, mainScroll.getHeight()
                        - mainScroll.getPaddingTop() - mainScroll.getPaddingBottom());
                int maxScroll = scrollChild == null
                        ? 0 : Math.max(0, scrollChild.getHeight() - viewportHeight);
                mainScroll.scrollTo(0, Math.min(Math.max(0, scrollY), maxScroll));
            });
        }
    }

}
