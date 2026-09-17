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


abstract class OptionalWeatherDataActivity extends WeatherApiActivity {
    boolean airQualityEnabled() {
        return getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getBoolean(PREF_AIR_QUALITY, false);
    }

    boolean pollenEnabled() {
        return weatherPreferences.pollenEnabled();
    }

    void requestEnabledOptionalDataForCurrentScope() {
        if (airQualityEnabled()) requestOptionalData(true, false, false);
        if (pollenEnabled()) requestOptionalData(false, true, false);
    }

    void applyOptionalPreferenceChanges(
            boolean airChanged, boolean pollenChanged, boolean requestEnabledData) {
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
        }
        if (rerender) rerenderOverviewPreservingScroll();
        if (!requestEnabledData) return;
        if (airChanged && airQualityEnabled()) requestOptionalData(true, false, true);
        if (pollenChanged && pollenEnabled()) requestOptionalData(false, true, true);
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
                                airQuality, lat, lon, language, result);
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
        if (precipitationMode || lastCurrentWeather == null || lastDailyWeather == null) return;
        int scrollY = mainScroll == null ? 0 : mainScroll.getScrollY();
        renderOverviewContent();
        if (mainScroll != null) {
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
            if (root.optInt("schema", -1) != 1
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
            if (value.isEmpty()) {
                cacheFile.delete();
                return null;
            }
            return new OptionalDataState(
                    generation, lat, lon, scopeLanguage,
                    false, true, value, accessibility);
        } catch (Exception ignored) {
            cacheFile.delete();
            return null;
        }
    }

    void persistOptionalDataCacheQuietly(
            boolean airQuality,
            double lat,
            double lon,
            String language,
            OptionalDataState state) {
        if (state == null || !state.available || state.value.trim().isEmpty()) return;
        cleanupOptionalCaches();
        File cacheFile = optionalDataCacheFile(airQuality, lat, lon, language);
        File tempFile = new File(cacheFile.getParentFile(), cacheFile.getName() + ".tmp");
        try {
            JSONObject root = new JSONObject();
            root.put("schema", 1);
            root.put("airQuality", Boolean.toString(airQuality));
            root.put("latitude", lat);
            root.put("longitude", lon);
            root.put("language", language == null ? "" : language);
            root.put("fetchedAtMillis", System.currentTimeMillis());
            root.put("value", state.value);
            root.put("accessibility", state.accessibility);
            byte[] encoded = root.toString().getBytes(StandardCharsets.UTF_8);
            if (tempFile.exists() && !tempFile.delete()) return;
            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                out.write(encoded);
                out.flush();
                out.getFD().sync();
            }
            Os.rename(tempFile.getAbsolutePath(), cacheFile.getAbsolutePath());
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
        JSONObject location = new JSONObject();
        location.put("latitude", lat);
        location.put("longitude", lon);
        JSONObject body = new JSONObject();
        body.put("location", location);
        body.put("universalAqi", true);
        if (language != null && !language.isEmpty()) body.put("languageCode", language);

        String address = AIR_QUALITY_ENDPOINT + "?key="
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
        return new OptionalDataState(
                generation, lat, lon, language,
                false, true, value, accessibility);
    }

    OptionalDataState loadPollenState(
            int generation,
            double lat,
            double lon,
            String language,
            String key) throws Exception {
        StringBuilder address = new StringBuilder(POLLEN_ENDPOINT)
                .append("?key=")
                .append(URLEncoder.encode(key, StandardCharsets.UTF_8.name()))
                .append("&location.latitude=").append(lat)
                .append("&location.longitude=").append(lon)
                .append("&days=1&pageSize=1&plantsDescription=false");
        if (language != null && !language.isEmpty()) {
            address.append("&languageCode=")
                    .append(URLEncoder.encode(language, StandardCharsets.UTF_8.name()));
        }
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
                false, true, value, accessibility);
    }

    JSONObject requestOptionalJson(
            String endpointName,
            String address,
            String method,
            JSONObject requestBody) throws Exception {
        ApiRequestBudgetManager.Category category = "pollen-forecast".equals(endpointName)
                ? ApiRequestBudgetManager.Category.POLLEN
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


}
