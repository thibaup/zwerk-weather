package com.zwerk.weather;

import android.content.Context;
import android.system.Os;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Owns the base forecast cache file identity, schema, serialization and cleanup policy. */
final class ForecastDiskCache {
    private static final String LEGACY_PREFIX = "weather_forecast_cache_v1_";
    private static final String FILE_PREFIX = "weather_forecast_cache_v2_";
    private static final String FILE_SUFFIX = ".json";
    private static final long MAX_AGE_MILLIS = 60L * 60L * 1000L;
    private static final double COORDINATE_TOLERANCE = 0.00001d;
    private static final int HOURLY_PAGE_SIZE = 24;
    private static final String HOURLY_DIAGNOSTIC = "_weatherNextHourlyDiagnostic";
    private static final String HOURLY_PARTIAL = "_weatherNextHourlyPartial";
    private static final String HOURLY_LOAD_ERROR = "_weatherNextHourlyLoadError";

    static final class Snapshot {
        final JSONObject current;
        final JSONObject hourly;
        final JSONObject daily;

        Snapshot(JSONObject current, JSONObject hourly, JSONObject daily) {
            this.current = current;
            this.hourly = hourly;
            this.daily = daily;
        }
    }

    private final Context context;

    ForecastDiskCache(Context context) {
        this.context = context;
    }

    void cleanup() {
        File[] files = context.getFilesDir().listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName();
            if (name.startsWith(LEGACY_PREFIX)) {
                file.delete();
                continue;
            }
            if (name.startsWith(FILE_PREFIX)
                    && (name.endsWith(FILE_SUFFIX) || name.endsWith(FILE_SUFFIX + ".tmp"))) {
                long modified = file.lastModified();
                if (modified <= 0L || now - modified >= MAX_AGE_MILLIS) file.delete();
            }
        }
    }

    Snapshot readFresh(double lat, double lon, String language, String requestLocationId) {
        cleanup();
        File cacheFile = cacheFile(lat, lon, language, requestLocationId);
        if (!cacheFile.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(cacheFile))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            if (body.length() == 0) return null;

            JSONObject root = new JSONObject(body.toString());
            if (root.optInt("schema", -1) != 2) {
                cacheFile.delete();
                return null;
            }
            double cachedLat = root.optDouble("latitude", Double.NaN);
            double cachedLon = root.optDouble("longitude", Double.NaN);
            if (Double.isNaN(cachedLat)
                    || Double.isNaN(cachedLon)
                    || Math.abs(cachedLat - lat) > COORDINATE_TOLERANCE
                    || Math.abs(cachedLon - lon) > COORDINATE_TOLERANCE) return null;
            if (!language.equals(root.optString("language", ""))) return null;
            if (!requestLocationId.equals(root.optString("locationId", ""))) return null;

            long updatedAt = root.optLong("updatedAtMillis", 0L);
            long age = System.currentTimeMillis() - updatedAt;
            if (updatedAt <= 0L || age < 0L || age >= MAX_AGE_MILLIS) {
                cacheFile.delete();
                return null;
            }

            JSONObject current = root.optJSONObject("current");
            JSONObject hourly = root.optJSONObject("hourly");
            JSONObject daily = root.optJSONObject("daily");
            if (current == null || hourly == null || daily == null) return null;
            return new Snapshot(current, hourly, daily);
        } catch (Exception ignored) {
            return null;
        }
    }

    void persist(
            double lat,
            double lon,
            String language,
            String requestLocationId,
            JSONObject current,
            JSONObject hourly,
            JSONObject daily) {
        if (current == null || hourly == null || daily == null) return;
        File cacheFile = cacheFile(lat, lon, language, requestLocationId);
        File tempFile = new File(cacheFile.getParentFile(), cacheFile.getName() + ".tmp");
        try {
            JSONObject root = new JSONObject();
            root.put("schema", 2);
            root.put("latitude", lat);
            root.put("longitude", lon);
            root.put("language", language);
            root.put("locationId", requestLocationId);
            root.put("updatedAtMillis", System.currentTimeMillis());
            root.put("current", current);
            root.put("hourly", hourlyCacheCopy(hourly));
            root.put("daily", daily);
            byte[] encoded = root.toString().getBytes(StandardCharsets.UTF_8);

            if (tempFile.exists() && !tempFile.delete()) return;
            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                out.write(encoded);
                out.flush();
                out.getFD().sync();
            }
            Os.rename(tempFile.getAbsolutePath(), cacheFile.getAbsolutePath());
        } catch (Exception ignored) {
            // Cache persistence is best-effort and must never turn fresh weather into an error.
        } finally {
            if (tempFile.exists()) tempFile.delete();
        }
    }

    private JSONObject hourlyCacheCopy(JSONObject hourly) {
        JSONObject copy = new JSONObject();
        try {
            JSONObject zone = firstJSONObject(hourly, "timeZone", "time_zone", "timezone");
            if (zone != null) copy.put("timeZone", zone);
            JSONArray source = hourly == null ? null : hourly.optJSONArray("forecastHours");
            JSONArray firstPage = new JSONArray();
            if (source != null) {
                for (int i = 0; i < Math.min(HOURLY_PAGE_SIZE, source.length()); i++) {
                    JSONObject hour = source.optJSONObject(i);
                    if (hour != null) firstPage.put(hour);
                }
            }
            copy.put("forecastHours", firstPage);
            copy.put(HOURLY_PARTIAL, true);
            copy.put(HOURLY_LOAD_ERROR, hourly != null && hourly.optBoolean(HOURLY_LOAD_ERROR, false));
            String diagnostic = boundedDiagnostic(hourly == null ? "" : hourly.optString(HOURLY_DIAGNOSTIC, ""));
            if (!diagnostic.isEmpty()) copy.put(HOURLY_DIAGNOSTIC, diagnostic);
        } catch (Exception ignored) { }
        return copy;
    }

    private static String boundedDiagnostic(String value) {
        String safe = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (safe.length() > 220) safe = safe.substring(0, 217) + "…";
        return safe;
    }

    private File cacheFile(double lat, double lon, String language, String requestLocationId) {
        String identity = String.format(Locale.US, "%.5f|%.5f|%s|%s", lat, lon, language, requestLocationId);
        return new File(context.getFilesDir(), FILE_PREFIX + Integer.toHexString(identity.hashCode()) + FILE_SUFFIX);
    }

    private static JSONObject firstJSONObject(JSONObject object, String... keys) {
        if (object == null || keys == null) return null;
        for (String key : keys) {
            JSONObject value = object.optJSONObject(key);
            if (value != null) return value;
        }
        return null;
    }
}
