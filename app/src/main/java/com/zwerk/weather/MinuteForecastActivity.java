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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.Executors;


abstract class MinuteForecastActivity extends OptionalWeatherDataActivity {
    void cleanupMinuteForecastCaches() {
        File[] files = getFilesDir().listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName();
            if (API_KEY_FILE.equals(name) || API_KEY_TEMP_FILE.equals(name)) continue;
            if (!name.startsWith(MINUTE_CACHE_LEGACY_PREFIX)) continue;
            if (!name.startsWith(MINUTE_CACHE_FILE_PREFIX)) {
                file.delete();
                continue;
            }
            if (name.endsWith(MINUTE_CACHE_FILE_SUFFIX + ".tmp")) {
                file.delete();
                continue;
            }
            if (!name.endsWith(MINUTE_CACHE_FILE_SUFFIX)) {
                file.delete();
                continue;
            }
            long modified = file.lastModified();
            if (modified <= 0L || now - modified >= MINUTE_CACHE_MAX_AGE_MILLIS) {
                file.delete();
            }
        }
    }

    File minuteForecastCacheFile(double lat, double lon, String language) {
        String identity = Double.toHexString(lat)
                + "|" + Double.toHexString(lon)
                + "|" + (language == null ? "" : language);
        String name = MINUTE_CACHE_FILE_PREFIX
                + Integer.toHexString(identity.hashCode())
                + MINUTE_CACHE_FILE_SUFFIX;
        return new File(getFilesDir(), name);
    }

    MinuteCacheSnapshot readFreshMinuteForecastCache(
            double lat,
            double lon,
            String language) {
        cleanupMinuteForecastCaches();
        File cacheFile = minuteForecastCacheFile(lat, lon, language);
        if (!cacheFile.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(cacheFile))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            if (body.length() == 0) return null;
            JSONObject root = new JSONObject(body.toString());
            if (root.optInt("schema", -1) != 1) {
                cacheFile.delete();
                return null;
            }
            double cachedLat = root.optDouble("latitude", Double.NaN);
            double cachedLon = root.optDouble("longitude", Double.NaN);
            if (Double.isNaN(cachedLat)
                    || Double.isNaN(cachedLon)
                    || Double.doubleToLongBits(cachedLat) != Double.doubleToLongBits(lat)
                    || Double.doubleToLongBits(cachedLon) != Double.doubleToLongBits(lon)
                    || !(language == null ? "" : language).equals(root.optString("language", ""))) {
                return null;
            }
            long fetchedAtMillis = root.optLong("fetchedAtMillis", 0L);
            long age = System.currentTimeMillis() - fetchedAtMillis;
            if (fetchedAtMillis <= 0L || age < 0L || age >= MINUTE_CACHE_MAX_AGE_MILLIS) {
                cacheFile.delete();
                return null;
            }
            JSONObject raw = root.optJSONObject("response");
            if (raw == null) return null;
            JSONObject filtered = filterElapsedMinuteResponse(raw, Instant.now());
            return new MinuteCacheSnapshot(fetchedAtMillis, filtered);
        } catch (Exception ignored) {
            return null;
        }
    }

    void persistMinuteForecastCacheQuietly(
            double lat,
            double lon,
            String language,
            long fetchedAtMillis,
            JSONObject rawResponse) {
        if (rawResponse == null || fetchedAtMillis <= 0L) return;
        cleanupMinuteForecastCaches();
        File cacheFile = minuteForecastCacheFile(lat, lon, language);
        File tempFile = new File(cacheFile.getParentFile(), cacheFile.getName() + ".tmp");
        try {
            JSONObject root = new JSONObject();
            root.put("schema", 1);
            root.put("latitude", lat);
            root.put("longitude", lon);
            root.put("language", language == null ? "" : language);
            root.put("fetchedAtMillis", fetchedAtMillis);
            root.put("response", rawResponse);
            byte[] encoded = root.toString().getBytes(StandardCharsets.UTF_8);
            if (tempFile.exists() && !tempFile.delete()) return;
            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                out.write(encoded);
                out.flush();
                out.getFD().sync();
            }
            Os.rename(tempFile.getAbsolutePath(), cacheFile.getAbsolutePath());
        } catch (Exception ignored) {
            // Minute caching is best-effort and never changes the base forecast result.
        } finally {
            if (tempFile.exists()) tempFile.delete();
        }
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
        final double lat = latitude;
        final double lon = longitude;
        final String language = Locale.getDefault().getLanguage();
        final MinuteForecastState requestState;
        synchronized (minuteForecastLock) {
            MinuteForecastState current = minuteForecastState;
            if (current != null && current.matches(generation, lat, lon, language)) {
                if (current.loading) return;
                if (!forceNetwork && current.isFresh(System.currentTimeMillis())) return;
                if (!forceNetwork && current.response == null && !current.errorMessage.isEmpty()) return;
            }
            requestState = new MinuteForecastState(
                    generation, lat, lon, language, true, 0L, null, "", false);
            minuteForecastState = requestState;
        }
        rerenderPrecipitationPreservingScroll();

        executor.execute(() -> {
            try {
                if (!forceNetwork) {
                    MinuteCacheSnapshot cached = readFreshMinuteForecastCache(lat, lon, language);
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
                        runOnUiThread(() -> finishMinuteForecastRequest(requestState, ready));
                        return;
                    }
                }
                if (!minuteRequestScopeCurrent(requestState)) {
                    throw new SupersededWeatherRequestException();
                }
                String key = readApiKey();
                if (key.isEmpty()) throw new IllegalStateException("API key is not configured");
                String address = minuteForecastAddress(key, lat, lon);
                JSONObject raw = requestMinuteLogical(address, requestState);
                long fetchedAtMillis = System.currentTimeMillis();
                persistMinuteForecastCacheQuietly(lat, lon, language, fetchedAtMillis, raw);
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
                runOnUiThread(() -> finishMinuteForecastRequest(requestState, ready));
            } catch (SupersededWeatherRequestException ignored) {
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
                runOnUiThread(() -> finishMinuteForecastRequest(requestState, failed));
            }
        });
    }

    void finishMinuteForecastRequest(
            MinuteForecastState requestState,
            MinuteForecastState resultState) {
        synchronized (minuteForecastLock) {
            if (minuteForecastState != requestState) return;
            if (!resultState.matches(
                    weatherRequestGeneration,
                    latitude,
                    longitude,
                    Locale.getDefault().getLanguage())) {
                return;
            }
            minuteForecastState = resultState;
        }
        scheduleMinuteExpiration(resultState);
        rerenderPrecipitationPreservingScroll();
    }

    void scheduleMinuteExpiration(MinuteForecastState state) {
        if (state == null || state.response == null || state.fetchedAtMillis <= 0L || mainScroll == null) return;
        long expiresAt = state.fetchedAtMillis + MINUTE_CACHE_MAX_AGE_MILLIS;
        long delay = Math.max(1L, expiresAt - System.currentTimeMillis() + 25L);
        mainScroll.postDelayed(() -> {
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
            }
        }, delay);
    }

    boolean minuteRequestScopeCurrent(MinuteForecastState requestState) {
        synchronized (minuteForecastLock) {
            return minuteForecastState == requestState
                    && requestState != null
                    && requestState.matches(
                            weatherRequestGeneration,
                            latitude,
                            longitude,
                            Locale.getDefault().getLanguage());
        }
    }

    String minuteForecastAddress(String key, double lat, double lon) throws Exception {
        return API_ROOT + "forecast/minutes:lookup"
                + "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8.name())
                + "&location.latitude=" + lat
                + "&location.longitude=" + lon
                + "&unitsSystem=METRIC"
                + "&pageSize=" + MINUTE_PAGE_SIZE;
    }

    JSONObject requestMinuteLogical(
            String address,
            MinuteForecastState requestState) throws Exception {
        if (!minuteRequestScopeCurrent(requestState)) {
            throw new SupersededWeatherRequestException();
        }
        if (isDebugBuild()) {
            Log.d(LOG_TAG, "request generation=" + requestState.generation + " endpoint=minute");
        }
        try {
            JSONObject response = request(address);
            if (!minuteRequestScopeCurrent(requestState)) {
                throw new SupersededWeatherRequestException();
            }
            return response;
        } catch (WeatherRequestException error) {
            if (error.statusCode == 408 || error.statusCode >= 500) {
                long jitter = 180L + ThreadLocalRandom.current().nextInt(121);
                SystemClock.sleep(jitter);
                if (!minuteRequestScopeCurrent(requestState)) {
                    throw new SupersededWeatherRequestException();
                }
                if (isDebugBuild()) {
                    Log.d(LOG_TAG, "retry generation=" + requestState.generation + " endpoint=minute");
                }
                JSONObject response = request(address);
                if (!minuteRequestScopeCurrent(requestState)) {
                    throw new SupersededWeatherRequestException();
                }
                return response;
            }
            // Never auto-retry 429; pagination is also never followed automatically.
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
                    Locale.getDefault().getLanguage())) {
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

    void rerenderPrecipitationPreservingScroll() {
        if (!precipitationMode || content == null) return;
        int scrollY = mainScroll == null ? 0 : mainScroll.getScrollY();
        renderPrecipitationContent();
        if (mainScroll != null) {
            mainScroll.post(() -> mainScroll.scrollTo(0, Math.max(0, scrollY)));
        }
    }


}
