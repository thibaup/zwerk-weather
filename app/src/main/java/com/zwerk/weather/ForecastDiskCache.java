package com.zwerk.weather;

import android.content.Context;
import android.system.Os;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

final class ForecastDiskCache {
    private static final String LEGACY_PREFIX = "weather_forecast_cache_v1_";
    private static final String FILE_PREFIX = "weather_forecast_cache_v2_";
    private static final String FILE_SUFFIX = ".json";
    private static final double COORDINATE_TOLERANCE = 0.00001d;
    static final String HOURLY_NEXT_TOKEN = "_cacheHourlyNextPageToken";
    static final String HOURLY_TERMINAL = "_cacheHourlyTerminal";
    static final String HOURLY_RESTART = "_cacheHourlyRestart";
    static final String BASE_REVISION = "_cacheForecastRevision";

    static final class Snapshot {
        final JSONObject current;
        final JSONObject hourly;
        final JSONObject daily;
        final long fetchedAtMillis;

        Snapshot(JSONObject current, JSONObject hourly, JSONObject daily, long fetchedAtMillis) {
            this.current = current;
            this.hourly = hourly;
            this.daily = daily;
            this.fetchedAtMillis = fetchedAtMillis;
        }
    }

    private final Context context;

    ForecastDiskCache(Context context) {
        this.context = context;
    }

    synchronized void cleanup() {
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
                if (modified <= 0L || modified > now
                        || now - modified >= ForecastClock.OFFLINE_MILLIS
                        || (name.endsWith(".tmp") && now - modified >= ForecastClock.FRESH_MILLIS)) {
                    file.delete();
                } else if (now - modified >= ForecastClock.FRESH_MILLIS) {
                    try {
                        JSONObject root = readRoot(file);
                        boolean openMeteo = root.optString("locationId", "")
                                .contains("|" + OpenMeteoConfig.OPEN_METEO + ":");
                        if (!ForecastClock.withinAge(root.optLong("updatedAtMillis", 0L),
                                now, ForecastClock.retentionMillis(openMeteo))) file.delete();
                    } catch (Exception ignored) { file.delete(); }
                }
            }
        }
    }

    Snapshot readFresh(double lat, double lon, String language, String requestLocationId) {
        return read(lat, lon, language, requestLocationId, false);
    }

    Snapshot readLastKnown(double lat, double lon, String language, String requestLocationId) {
        return read(lat, lon, language, requestLocationId, true);
    }

    private synchronized Snapshot read(double lat, double lon, String language, String requestLocationId,
            boolean allowStale) {
        cleanup();
        File cacheFile = cacheFile(lat, lon, language, requestLocationId);
        if (!cacheFile.isFile()) return null;
        try {
            JSONObject root = readRoot(cacheFile);
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
            long now = System.currentTimeMillis();
            boolean openMeteo = requestLocationId.contains("|" + OpenMeteoConfig.OPEN_METEO + ":");
            if (!ForecastClock.withinAge(updatedAt, now, ForecastClock.retentionMillis(openMeteo))) {
                cacheFile.delete();
                return null;
            }
            if (!allowStale && !ForecastClock.withinAge(updatedAt, now, ForecastClock.FRESH_MILLIS)) {
                return null;
            }

            JSONObject current = root.optJSONObject("current");
            JSONObject hourly = root.optJSONObject("hourly");
            JSONObject daily = root.optJSONObject("daily");
            if (current == null || hourly == null || daily == null) return null;
            if (requestLocationId.contains("|" + OpenMeteoConfig.OPEN_METEO + ":")
                    && !hourly.has("_openMeteo")) {
                // Older cache copies discarded Open-Meteo metadata and all but 24 hours.
                cacheFile.delete();
                return null;
            }
            return new Snapshot(current, hourly, daily, updatedAt);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JSONObject readRoot(File file) throws Exception {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            return new JSONObject(body.toString());
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
        persist(lat, lon, language, requestLocationId, current, hourly, daily,
                System.currentTimeMillis());
    }

    synchronized void persist(double lat, double lon, String language, String requestLocationId,
            JSONObject current, JSONObject hourly, JSONObject daily, long fetchedAtMillis) {
        if (current == null || hourly == null || daily == null) return;
        try { hourly.put(BASE_REVISION, UUID.randomUUID().toString()); } catch (Exception ignored) { }
        writeSnapshot(lat, lon, language, requestLocationId, current, hourly, daily, fetchedAtMillis);
    }

    private void writeSnapshot(double lat, double lon, String language, String requestLocationId,
            JSONObject current, JSONObject hourly, JSONObject daily, long fetchedAtMillis) {
        File cacheFile = cacheFile(lat, lon, language, requestLocationId);
        File tempFile = new File(cacheFile.getParentFile(), cacheFile.getName() + ".tmp");
        try {
            JSONObject root = new JSONObject();
            root.put("schema", 2);
            root.put("latitude", lat);
            root.put("longitude", lon);
            root.put("language", language);
            root.put("locationId", requestLocationId);
            root.put("updatedAtMillis", fetchedAtMillis);
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

    synchronized void persistContinuation(double lat, double lon, String language, String locationId,
            JSONObject current, JSONObject hourly, JSONObject daily, long fetchedAt) {
        if (current == null || hourly == null || daily == null) return;
        File file = cacheFile(lat, lon, language, locationId);
        try {
            JSONObject existing = readRoot(file);
            JSONObject oldHourly = existing.optJSONObject("hourly");
            String revision = oldHourly == null ? "" : oldHourly.optString(BASE_REVISION, "");
            String expected = hourly.optString(BASE_REVISION, "");
            if (revision.isEmpty()) {
                // Accept a legacy cache once, without using its timestamp as a new base version.
                if (existing.optLong("updatedAtMillis", 0L) != fetchedAt) return;
            } else if (!revision.equals(expected)) return;
            boolean openMeteo = locationId.contains("|" + OpenMeteoConfig.OPEN_METEO + ":");
            if (!ForecastClock.withinAge(fetchedAt, System.currentTimeMillis(),
                    ForecastClock.retentionMillis(openMeteo))) return;
            writeSnapshot(lat, lon, language, locationId, current, hourly, daily, fetchedAt);
        } catch (Exception ignored) { }
    }

    static void ensureBaseRevision(JSONObject hourly) {
        if (hourly == null || !hourly.optString(BASE_REVISION, "").isEmpty()) return;
        try { hourly.put(BASE_REVISION, UUID.randomUUID().toString()); } catch (Exception ignored) { }
    }

    private JSONObject hourlyCacheCopy(JSONObject hourly) {
        try {
            // Keep all fetched pages and their cursor so reopening a day reuses them.
            return new JSONObject(hourly.toString());
        } catch (Exception ignored) { return new JSONObject(); }
    }

    private File cacheFile(double lat, double lon, String language, String requestLocationId) {
        String identity = String.format(Locale.US, "%.5f|%.5f|%s|%s", lat, lon, language, requestLocationId);
        return new File(context.getFilesDir(), FILE_PREFIX + Integer.toHexString(identity.hashCode()) + FILE_SUFFIX);
    }

}
