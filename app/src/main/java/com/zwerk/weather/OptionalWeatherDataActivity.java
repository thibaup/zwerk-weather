package com.zwerk.weather;

import android.animation.ValueAnimator;
import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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


abstract class OptionalWeatherDataActivity extends WeatherApiActivity {
    private static final String ALERT_CHANNEL_ID = "official_weather_alerts";
    private static final String PREF_NOTIFIED_ALERT_IDS = "notified_weather_alert_ids";

    boolean airQualityEnabled() {
        return getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getBoolean(PREF_AIR_QUALITY, false);
    }

    boolean pollenEnabled() {
        return weatherPreferences.pollenEnabled();
    }

    boolean severeAlertsEnabled() {
        return getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getBoolean(PREF_SEVERE_ALERTS, false);
    }

    void requestEnabledOptionalDataForCurrentScope() {
        if (airQualityEnabled()) requestOptionalData(true, false, false);
        if (pollenEnabled()) requestOptionalData(false, true, false);
        if (severeAlertsEnabled()) requestSevereWeatherAlerts();
    }

    void applyOptionalPreferenceChanges(
            boolean airChanged, boolean pollenChanged, boolean alertsChanged,
            boolean requestEnabledData) {
        boolean rerender = false;
        synchronized (optionalDataLock) {
            if (airChanged) {
                airQualityRequestSerial++;
                airQualityState = null;
                rerender = true;
            }
            if (pollenChanged) {
                pollenRequestSerial++;
                pollenState = null;
                rerender = true;
            }
            if (alertsChanged) {
                severeAlertsRequestSerial++;
                severeAlertsState = null;
                rerender = true;
            }
        }
        if (rerender) rerenderOverviewPreservingScroll();
        if (!requestEnabledData) return;
        if (airChanged && airQualityEnabled()) requestOptionalData(true, false, true);
        if (pollenChanged && pollenEnabled()) requestOptionalData(false, true, true);
        if (alertsChanged && severeAlertsEnabled()) requestSevereWeatherAlerts();
    }

    void requestSevereWeatherAlerts() {
        if (!severeAlertsEnabled()) return;
        final int generation = weatherRequestGeneration;
        final double lat = latitude;
        final double lon = longitude;
        final String language = Locale.getDefault().toLanguageTag();
        final long serial;
        synchronized (optionalDataLock) {
            OptionalDataState state = severeAlertsState;
            if (state != null && state.matches(generation, lat, lon, language)) return;
            if (state != null && state.available && state.details != null
                    && Double.doubleToLongBits(state.latitude) == Double.doubleToLongBits(lat)
                    && Double.doubleToLongBits(state.longitude) == Double.doubleToLongBits(lon)
                    && state.language.equals(language)) {
                long fetchedAt = state.details.optLong("_fetchedAtMillis", 0L);
                long age = System.currentTimeMillis() - fetchedAt;
                if (fetchedAt > 0L && age >= 0L && age < 15L * 60L * 1000L) {
                    severeAlertsState = new OptionalDataState(
                            generation, lat, lon, language, false, true,
                            state.value, state.accessibility, state.details);
                    rerenderOverviewPreservingScroll();
                    return;
                }
            }
            serial = ++severeAlertsRequestSerial;
            severeAlertsState = new OptionalDataState(
                    generation, lat, lon, language, true, false, "", "Weather alerts loading");
        }
        optionalExecutor.execute(() -> {
            OptionalDataState result;
            try {
                String key = readApiKey();
                if (key.isEmpty()) {
                    result = optionalUnavailable(
                            generation, lat, lon, language, "Weather alerts unavailable");
                } else {
                    StringBuilder address = new StringBuilder(WEATHER_ALERTS_ENDPOINT)
                            .append("?key=")
                            .append(URLEncoder.encode(key, StandardCharsets.UTF_8.name()))
                            .append("&location.latitude=").append(lat)
                            .append("&location.longitude=").append(lon);
                    if (!language.isEmpty()) {
                        address.append("&languageCode=")
                                .append(URLEncoder.encode(language, StandardCharsets.UTF_8.name()));
                    }
                    String baseAddress = address.toString();
                    JSONObject response = null;
                    JSONArray combinedAlerts = new JSONArray();
                    HashSet<String> seenTokens = new HashSet<>();
                    String pageToken = "";
                    do {
                        String pageAddress = baseAddress;
                        if (!pageToken.isEmpty()) {
                            pageAddress += "&pageToken="
                                    + URLEncoder.encode(pageToken, StandardCharsets.UTF_8.name());
                        }
                        JSONObject page = requestOptionalJson(
                                "weather-alerts", pageAddress, "GET", null);
                        if (response == null) response = page;
                        JSONArray pageAlerts = page.optJSONArray("weatherAlerts");
                        if (pageAlerts != null) {
                            for (int i = 0; i < pageAlerts.length(); i++) {
                                JSONObject alert = pageAlerts.optJSONObject(i);
                                if (alert != null) combinedAlerts.put(alert);
                            }
                        }
                        String next = page.optString("nextPageToken", "").trim();
                        if (next.isEmpty() || !seenTokens.add(next)) pageToken = "";
                        else pageToken = next;
                    } while (!pageToken.isEmpty());
                    if (response == null) response = new JSONObject();
                    response.put("weatherAlerts", combinedAlerts);
                    response.remove("nextPageToken");
                    response.put("_fetchedAtMillis", System.currentTimeMillis());
                    JSONArray alerts = combinedAlerts;
                    int count = alerts == null ? 0 : alerts.length();
                    result = new OptionalDataState(
                            generation, lat, lon, language, false, true,
                            Integer.toString(count), count == 1 ? "1 active weather alert"
                                    : count + " active weather alerts", response);
                }
            } catch (Exception error) {
                result = optionalUnavailable(
                        generation, lat, lon, language, "Weather alerts unavailable");
            }
            final OptionalDataState delivered = result;
            runOnUiThread(() -> {
                if (!optionalScopeCurrent(generation, lat, lon, language)
                        || !severeAlertsEnabled()) return;
                synchronized (optionalDataLock) {
                    if (serial != severeAlertsRequestSerial) return;
                    severeAlertsState = delivered;
                }
                if (delivered.available) notifyNewSevereAlert(delivered.details);
                rerenderOverviewPreservingScroll();
            });
        });
    }

    void requestOptionalData(boolean airQuality, boolean pollen, boolean force) {
        if (airQuality == pollen) return;
        final boolean enabled = airQuality ? airQualityEnabled() : pollenEnabled();
        if (!enabled) return;

        final int generation = weatherRequestGeneration;
        final double lat = latitude;
        final double lon = longitude;
        final String language = Locale.getDefault().toLanguageTag();
        final long requestSerial;
        synchronized (optionalDataLock) {
            OptionalDataState state = airQuality ? airQualityState : pollenState;
            if (state != null && state.matches(generation, lat, lon, language)) {
                if (state.loading) return;
                if (!force) return;
            }
            requestSerial = airQuality ? ++airQualityRequestSerial : ++pollenRequestSerial;
            OptionalDataState loading = new OptionalDataState(
                    generation, lat, lon, language,
                    true, false, "Loading…",
                    airQuality ? "Air quality loading" : "Pollen loading");
            if (airQuality) airQualityState = loading;
            else pollenState = loading;
        }
        rerenderOverviewPreservingScroll();

        optionalExecutor.execute(() -> {
            OptionalDataState result;
            String endpointName = airQuality ? "air-quality-current" : "pollen-forecast";
            try {
                OptionalDataState cached = force
                        ? null
                        : readFreshOptionalDataCache(
                                airQuality, generation, lat, lon, language);
                if (cached != null) {
                    result = cached;
                } else {
                    String key = readApiKey();
                    if (key.isEmpty()) {
                        result = optionalUnavailable(
                                generation, lat, lon, language,
                                airQuality ? "Air quality unavailable" : "Pollen unavailable");
                    } else if (airQuality) {
                        result = loadAirQualityState(generation, lat, lon, language, key);
                    } else {
                        result = loadPollenState(generation, lat, lon, language, key);
                    }
                    if (result.available) {
                        persistOptionalDataCacheQuietly(
                                airQuality, generation, lat, lon, language,
                                result, requestSerial);
                    }
                }
            } catch (OptionalRequestException error) {
                if (isDebugBuild()) {
                    Log.d(LOG_TAG, "optional endpoint=" + endpointName
                            + " status=" + error.statusCode
                            + " reason=" + error.reason);
                }
                boolean keyBlocked = error.statusCode == 403
                        && "API_KEY_SERVICE_BLOCKED".equals(error.reason);
                boolean requestLimited = "APP_REQUEST_LIMIT".equals(error.reason);
                result = optionalUnavailable(
                        generation, lat, lon, language,
                        requestLimited ? "Limit reached" : (keyBlocked ? "Key blocked" : "Unavailable"),
                        requestLimited
                                ? error.detail
                                : keyBlocked
                                ? (airQuality
                                        ? "Air Quality API is not allowed by this API key's restrictions"
                                        : "Pollen API is not allowed by this API key's restrictions")
                                : (airQuality ? "Air quality unavailable" : "Pollen unavailable"));
            } catch (Exception ignored) {
                if (isDebugBuild()) {
                    Log.d(LOG_TAG, "optional endpoint=" + endpointName + " status=local-error");
                }
                result = optionalUnavailable(
                        generation, lat, lon, language,
                        airQuality ? "Air quality unavailable" : "Pollen unavailable");
            }

            final OptionalDataState delivered = result;
            runOnUiThread(() -> {
                if (!optionalScopeCurrent(generation, lat, lon, language)) return;
                if (airQuality && !airQualityEnabled()) return;
                if (pollen && !pollenEnabled()) return;
                synchronized (optionalDataLock) {
                    long currentSerial = airQuality ? airQualityRequestSerial : pollenRequestSerial;
                    if (requestSerial != currentSerial) return;
                    OptionalDataState pending = airQuality ? airQualityState : pollenState;
                    if (pending == null || !pending.matches(generation, lat, lon, language)) return;
                    if (airQuality) airQualityState = delivered;
                    else pollenState = delivered;
                }
                rerenderOverviewPreservingScroll();
            });
        });
    }

    boolean optionalScopeCurrent(
            int generation, double lat, double lon, String language) {
        return generation == weatherRequestGeneration
                && Double.doubleToLongBits(latitude) == Double.doubleToLongBits(lat)
                && Double.doubleToLongBits(longitude) == Double.doubleToLongBits(lon)
                && Locale.getDefault().toLanguageTag().equals(language);
    }

    void requireOptionalScopeCurrent(
            int generation, double lat, double lon, String language)
            throws SupersededWeatherRequestException {
        if (!optionalScopeCurrent(generation, lat, lon, language)) {
            throw new SupersededWeatherRequestException();
        }
    }

    OptionalDataState optionalUnavailable(
            int generation,
            double lat,
            double lon,
            String language,
            String accessibility) {
        return optionalUnavailable(
                generation, lat, lon, language, "Unavailable", accessibility);
    }

    OptionalDataState optionalUnavailable(
            int generation,
            double lat,
            double lon,
            String language,
            String value,
            String accessibility) {
        return new OptionalDataState(
                generation, lat, lon, language,
                false, false, value, accessibility);
    }

    void rerenderOverviewPreservingScroll() {
        if (lastCurrentWeather == null || lastDailyWeather == null) return;
        int scrollY = mainScroll == null ? 0 : mainScroll.getScrollY();
        renderOverviewContent();
        if (!precipitationMode && mainScroll != null) {
            mainScroll.post(() -> mainScroll.scrollTo(0, Math.max(0, scrollY)));
        }
        if (headerGlass != null) headerGlass.requestBlurRefresh();
    }


    void cleanupOptionalCaches() {
        File[] files = getFilesDir().listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName();
            boolean airRoot = name.startsWith(AIR_QUALITY_CACHE_ROOT_PREFIX);
            boolean pollenRoot = name.startsWith(POLLEN_CACHE_ROOT_PREFIX);
            if (!airRoot && !pollenRoot) continue;
            String currentPrefix = airRoot
                    ? AIR_QUALITY_CACHE_FILE_PREFIX : POLLEN_CACHE_FILE_PREFIX;
            long maxAge = airRoot
                    ? AIR_QUALITY_CACHE_MAX_AGE_MILLIS : POLLEN_CACHE_MAX_AGE_MILLIS;
            boolean currentFile = name.startsWith(currentPrefix)
                    && name.endsWith(OPTIONAL_CACHE_FILE_SUFFIX);
            long modified = file.lastModified();
            if (!currentFile || modified <= 0L || now - modified < 0L
                    || now - modified >= maxAge) {
                file.delete();
            }
        }
    }

    File optionalDataCacheFile(
            boolean airQuality, double lat, double lon, String language) {
        String identity = Double.toHexString(lat)
                + "|" + Double.toHexString(lon)
                + "|" + (language == null ? "" : language);
        String prefix = airQuality
                ? AIR_QUALITY_CACHE_FILE_PREFIX : POLLEN_CACHE_FILE_PREFIX;
        return new File(
                getFilesDir(),
                prefix + Integer.toHexString(identity.hashCode()) + OPTIONAL_CACHE_FILE_SUFFIX);
    }

    OptionalDataState readFreshOptionalDataCache(
            boolean airQuality,
            int generation,
            double lat,
            double lon,
            String language) {
        cleanupOptionalCaches();
        File cacheFile = optionalDataCacheFile(airQuality, lat, lon, language);
        if (!cacheFile.isFile()) return null;
        long maxAge = airQuality
                ? AIR_QUALITY_CACHE_MAX_AGE_MILLIS : POLLEN_CACHE_MAX_AGE_MILLIS;
        try (BufferedReader reader = new BufferedReader(new FileReader(cacheFile))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            if (body.length() == 0) {
                cacheFile.delete();
                return null;
            }
            JSONObject root = new JSONObject(body.toString());
            if (root.optInt("schema", -1) != 2
                    || !Boolean.toString(airQuality).equals(root.optString("airQuality", ""))) {
                cacheFile.delete();
                return null;
            }
            double cachedLat = root.optDouble("latitude", Double.NaN);
            double cachedLon = root.optDouble("longitude", Double.NaN);
            String scopeLanguage = language == null ? "" : language;
            if (Double.isNaN(cachedLat)
                    || Double.isNaN(cachedLon)
                    || Double.doubleToLongBits(cachedLat) != Double.doubleToLongBits(lat)
                    || Double.doubleToLongBits(cachedLon) != Double.doubleToLongBits(lon)
                    || !scopeLanguage.equals(root.optString("language", ""))) {
                return null;
            }
            long fetchedAtMillis = root.optLong("fetchedAtMillis", 0L);
            long age = System.currentTimeMillis() - fetchedAtMillis;
            if (fetchedAtMillis <= 0L || age < 0L || age >= maxAge) {
                cacheFile.delete();
                return null;
            }
            String value = root.optString("value", "").trim();
            String accessibility = root.optString("accessibility", "").trim();
            JSONObject details = root.optJSONObject("details");
            if (airQuality && (details == null
                    || details.optJSONObject("current") == null)) {
                cacheFile.delete();
                return null;
            }
            if (value.isEmpty()) {
                cacheFile.delete();
                return null;
            }
            return new OptionalDataState(
                    generation, lat, lon, scopeLanguage,
                    false, true, value, accessibility, details);
        } catch (Exception ignored) {
            cacheFile.delete();
            return null;
        }
    }

    void persistOptionalDataCacheQuietly(
            boolean airQuality,
            int generation,
            double lat,
            double lon,
            String language,
            OptionalDataState state,
            long requestSerial) {
        if (state == null || !state.available || state.value.trim().isEmpty()) return;
        cleanupOptionalCaches();
        File cacheFile = optionalDataCacheFile(airQuality, lat, lon, language);
        File tempFile = new File(cacheFile.getParentFile(), cacheFile.getName() + ".tmp");
        try {
            JSONObject root = new JSONObject();
            root.put("schema", 2);
            root.put("airQuality", Boolean.toString(airQuality));
            root.put("latitude", lat);
            root.put("longitude", lon);
            root.put("language", language == null ? "" : language);
            root.put("fetchedAtMillis", System.currentTimeMillis());
            root.put("value", state.value);
            root.put("accessibility", state.accessibility);
            if (state.details != null) root.put("details", state.details);
            byte[] encoded = root.toString().getBytes(StandardCharsets.UTF_8);
            if (tempFile.exists() && !tempFile.delete()) return;
            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                out.write(encoded);
                out.flush();
                out.getFD().sync();
            }
            synchronized (optionalDataLock) {
                long currentSerial = airQuality ? airQualityRequestSerial : pollenRequestSerial;
                if (requestSerial != currentSerial
                        || !optionalScopeCurrent(generation, lat, lon, language)) return;
                Os.rename(tempFile.getAbsolutePath(), cacheFile.getAbsolutePath());
            }
        } catch (Exception ignored) {
            // Optional environmental caches are best-effort and never affect weather rendering.
        } finally {
            if (tempFile.exists()) tempFile.delete();
        }
    }


    OptionalDataState loadAirQualityState(
            int generation,
            double lat,
            double lon,
            String language,
            String key) throws Exception {
        requireOptionalScopeCurrent(generation, lat, lon, language);
        JSONObject location = new JSONObject();
        location.put("latitude", lat);
        location.put("longitude", lon);
        JSONObject body = new JSONObject();
        body.put("location", location);
        body.put("universalAqi", true);
        JSONArray computations = new JSONArray();
        computations.put("HEALTH_RECOMMENDATIONS");
        computations.put("DOMINANT_POLLUTANT_CONCENTRATION");
        computations.put("POLLUTANT_CONCENTRATION");
        computations.put("POLLUTANT_ADDITIONAL_INFO");
        body.put("extraComputations", computations);
        if (language != null && !language.isEmpty()) body.put("languageCode", language);

        String address = AIR_QUALITY_CURRENT_ENDPOINT + "?key="
                + URLEncoder.encode(key, StandardCharsets.UTF_8.name());
        JSONObject response = requestOptionalJson("air-quality-current", address, "POST", body);
        JSONArray indexes = response.optJSONArray("indexes");
        JSONObject universal = null;
        if (indexes != null) {
            for (int i = 0; i < indexes.length(); i++) {
                JSONObject candidate = indexes.optJSONObject(i);
                if (candidate != null && "uaqi".equalsIgnoreCase(candidate.optString("code", ""))) {
                    universal = candidate;
                    break;
                }
            }
        }
        if (universal == null || !universal.has("aqi") || universal.isNull("aqi")) {
            return optionalUnavailable(generation, lat, lon, language, "Air quality has no data");
        }
        int aqi = universal.optInt("aqi", Integer.MIN_VALUE);
        if (aqi == Integer.MIN_VALUE) {
            return optionalUnavailable(generation, lat, lon, language, "Air quality has no data");
        }
        String category = stringValue(universal, "category");
        String displayAqi = stringValue(universal, "aqiDisplay");
        String value = displayAqi.isEmpty() ? Integer.toString(aqi) : displayAqi;
        if (!category.isEmpty()) value += "\n" + category;
        String accessibility = "Universal AQI " + aqi
                + (category.isEmpty() ? "" : ", " + category);

        JSONObject forecastBody = new JSONObject();
        requireOptionalScopeCurrent(generation, lat, lon, language);
        forecastBody.put("location", location);
        forecastBody.put("universalAqi", true);
        if (language != null && !language.isEmpty()) {
            forecastBody.put("languageCode", language);
        }
        Instant start = Instant.now().plus(Duration.ofHours(1));
        JSONObject period = new JSONObject();
        period.put("startTime", DateTimeFormatter.ISO_INSTANT.format(start));
        period.put("endTime", DateTimeFormatter.ISO_INSTANT.format(start.plus(Duration.ofHours(23))));
        forecastBody.put("period", period);
        forecastBody.put("pageSize", 24);
        JSONArray forecastComputations = new JSONArray();
        forecastComputations.put("DOMINANT_POLLUTANT_CONCENTRATION");
        forecastBody.put("extraComputations", forecastComputations);
        JSONObject forecast;
        boolean forecastComplete;
        try {
            requireOptionalScopeCurrent(generation, lat, lon, language);
            forecast = requestOptionalJson(
                    "air-quality-forecast",
                    AIR_QUALITY_FORECAST_ENDPOINT + "?key="
                            + URLEncoder.encode(key, StandardCharsets.UTF_8.name()),
                    "POST",
                    forecastBody);
            forecastComplete = true;
        } catch (Exception ignored) {
            // Current AQI remains useful when the separate forecast event is unavailable.
            forecast = new JSONObject();
            forecastComplete = false;
        }
        JSONObject details = new JSONObject();
        details.put("current", response);
        details.put("forecast", forecast);
        details.put("forecastComplete", forecastComplete);
        return new OptionalDataState(
                generation, lat, lon, language,
                false, true, value, accessibility, details);
    }

    OptionalDataState loadPollenState(
            int generation,
            double lat,
            double lon,
            String language,
            String key) throws Exception {
        requireOptionalScopeCurrent(generation, lat, lon, language);
        StringBuilder address = new StringBuilder(POLLEN_ENDPOINT)
                .append("?key=")
                .append(URLEncoder.encode(key, StandardCharsets.UTF_8.name()))
                .append("&location.latitude=").append(lat)
                .append("&location.longitude=").append(lon)
                .append("&days=5&pageSize=5&plantsDescription=false");
        if (language != null && !language.isEmpty()) {
            address.append("&languageCode=")
                    .append(URLEncoder.encode(language, StandardCharsets.UTF_8.name()));
        }
        requireOptionalScopeCurrent(generation, lat, lon, language);
        JSONObject response = requestOptionalJson(
                "pollen-forecast", address.toString(), "GET", null);
        JSONArray dailyInfo = response.optJSONArray("dailyInfo");
        JSONObject today = firstObject(dailyInfo);
        JSONArray types = today == null ? null : today.optJSONArray("pollenTypeInfo");
        if (types == null || types.length() == 0) {
            return optionalUnavailable(generation, lat, lon, language, "Pollen has no data");
        }

        int maxValue = Integer.MIN_VALUE;
        String category = "";
        ArrayList<String> dominant = new ArrayList<>();
        for (int i = 0; i < types.length(); i++) {
            JSONObject type = types.optJSONObject(i);
            JSONObject index = type == null ? null : type.optJSONObject("indexInfo");
            if (index == null || !index.has("value") || index.isNull("value")) continue;
            int value = index.optInt("value", Integer.MIN_VALUE);
            if (value == Integer.MIN_VALUE) continue;
            String typeName = firstNonEmpty(
                    stringValue(type, "displayName"),
                    stringValue(type, "code"));
            if (value > maxValue) {
                maxValue = value;
                category = stringValue(index, "category");
                dominant.clear();
                if (!typeName.isEmpty()) dominant.add(typeName);
            } else if (value == maxValue && !typeName.isEmpty()) {
                dominant.add(typeName);
                if (category.isEmpty()) category = stringValue(index, "category");
            }
        }
        if (maxValue == Integer.MIN_VALUE) {
            return optionalUnavailable(generation, lat, lon, language, "Pollen has no data");
        }

        StringBuilder dominantText = new StringBuilder();
        if (maxValue > 0) {
            for (int i = 0; i < dominant.size() && i < 3; i++) {
                if (dominantText.length() > 0) dominantText.append(" · ");
                dominantText.append(dominant.get(i));
            }
        }
        String level = category.isEmpty() ? Integer.toString(maxValue) : category + " " + maxValue;
        String value = level + (dominantText.length() == 0 ? "" : "\n" + dominantText);
        String accessibility = "Today's highest Universal Pollen Index " + maxValue
                + (category.isEmpty() ? "" : ", " + category)
                + (dominantText.length() == 0 ? "" : ". Dominant pollen types " + dominantText);
        return new OptionalDataState(
                generation, lat, lon, language,
                false, true, value, accessibility, response);
    }

    JSONObject requestOptionalJson(
            String endpointName,
            String address,
            String method,
            JSONObject requestBody) throws Exception {
        ApiRequestBudgetManager.Category category = "pollen-forecast".equals(endpointName)
                ? ApiRequestBudgetManager.Category.POLLEN
                : "weather-alerts".equals(endpointName)
                ? ApiRequestBudgetManager.Category.WEATHER
                : ApiRequestBudgetManager.Category.AIR_QUALITY;
        ApiRequestBudgetManager.Decision budget =
                ApiRequestBudgetManager.tryAcquire(this, category);
        if (!budget.allowed) {
            throw new OptionalRequestException(
                    -2, endpointName, "APP_REQUEST_LIMIT", budget.message);
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(18000);
        connection.setRequestProperty("Accept", "application/json");
        applyAndroidApiKeyRestrictionHeaders(connection);
        connection.setRequestMethod(method);
        if (requestBody != null) {
            byte[] encoded = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(encoded.length);
            try (java.io.OutputStream out = connection.getOutputStream()) {
                out.write(encoded);
            }
        }
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String errorBody = readUtf8Bounded(
                        connection.getErrorStream(), ERROR_BODY_LIMIT_BYTES);
                throw new OptionalRequestException(
                        code, endpointName, optionalFailureReason(errorBody, code));
            }
            String payload = readUtf8(connection.getInputStream());
            if (payload.trim().isEmpty()) {
                throw new OptionalRequestException(code, endpointName, "EMPTY_RESPONSE");
            }
            try {
                return new JSONObject(payload);
            } catch (Exception ignored) {
                throw new OptionalRequestException(code, endpointName, "INVALID_RESPONSE");
            }
        } catch (SocketTimeoutException ignored) {
            throw new OptionalRequestException(-1, endpointName, "TIMEOUT");
        } catch (IOException ignored) {
            throw new OptionalRequestException(-1, endpointName, "NETWORK");
        } finally {
            connection.disconnect();
        }
    }

    void notifyNewSevereAlert(JSONObject response) {
        JSONArray alerts = response == null ? null : response.optJSONArray("weatherAlerts");
        if (alerts == null || alerts.length() == 0) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;

        String stored = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getString(PREF_NOTIFIED_ALERT_IDS, "");
        HashSet<String> known = new HashSet<>();
        if (stored != null && !stored.isEmpty()) {
            for (String value : stored.split("\\n")) if (!value.isEmpty()) known.add(value);
        }
        JSONObject selected = null;
        int selectedRank = -1;
        HashSet<String> current = new HashSet<>();
        for (int i = 0; i < alerts.length(); i++) {
            JSONObject alert = alerts.optJSONObject(i);
            if (alert == null) continue;
            String id = alert.optString("alertId", "");
            if (!id.isEmpty()) current.add(id);
            if (id.isEmpty() || known.contains(id)) continue;
            int rank = alertSeverityRank(alert.optString("severity", ""));
            if (selected == null || rank > selectedRank) {
                selected = alert;
                selectedRank = rank;
            }
        }
        if (selected == null) return;

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    ALERT_CHANNEL_ID, "Official weather alerts", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Public warnings from official weather authorities");
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                this, 501, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = alertTitle(selected);
        String area = selected.optString("areaName", "");
        String severity = prettyEnum(selected.optString("severity", ""));
        String bodyText = firstNonEmpty(area, severity);
        if (!area.isEmpty() && !severity.isEmpty()) bodyText = severity + " · " + area;
        Notification notification = new Notification.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title.isEmpty() ? "Weather alert" : title)
                .setContentText(bodyText)
                .setStyle(new Notification.BigTextStyle().bigText(bodyText))
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build();
        manager.notify(501, notification);
        HashSet<String> notified = new HashSet<>();
        for (String id : current) if (known.contains(id)) notified.add(id);
        String selectedId = selected.optString("alertId", "");
        if (!selectedId.isEmpty()) notified.add(selectedId);
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit()
                .putString(PREF_NOTIFIED_ALERT_IDS, android.text.TextUtils.join("\n", notified))
                .apply();
    }

    static int alertSeverityRank(String severity) {
        if ("EXTREME".equalsIgnoreCase(severity)) return 4;
        if ("SEVERE".equalsIgnoreCase(severity)) return 3;
        if ("MODERATE".equalsIgnoreCase(severity)) return 2;
        if ("MINOR".equalsIgnoreCase(severity)) return 1;
        return 0;
    }

    static String alertTitle(JSONObject alert) {
        if (alert == null) return "";
        JSONObject localized = alert.optJSONObject("alertTitle");
        if (localized != null) return localized.optString("text", "").trim();
        return alert.optString("alertTitle", "").trim();
    }

    static String prettyEnum(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        String normalized = value.trim().toLowerCase(Locale.getDefault()).replace('_', ' ');
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }


}
