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

/** Shared disk cache for minute/15-minute precipitation responses across UI, widgets, and alerts. */
final class MinuteCacheStore {
    private static final String PREFIX = "weather_minute_cache_v2_";
    private static final String LEGACY_PREFIX = "weather_minute_cache_";
    private static final String SUFFIX = ".json";
    private static final double COORDINATE_TOLERANCE = 0.00001d;
    private static final long CLEANUP_INTERVAL_MS = 60L * 60L * 1000L;
    private static final long FILE_RETENTION_MS = 60L * 60L * 1000L;
    private static final long TMP_RETENTION_MS = 5L * 60L * 1000L;
    private static final Object LOCK = new Object();
    private static volatile boolean legacyCleaned;
    private static volatile long lastCleanupAtMs;

    private MinuteCacheStore() { }

    static final class Snapshot {
        final JSONObject response;
        final long fetchedAtMillis;

        Snapshot(JSONObject response, long fetchedAtMillis) {
            this.response = response;
            this.fetchedAtMillis = fetchedAtMillis;
        }
    }

    static Snapshot readFresh(Context context, double lat, double lon, String language,
            String source, long maxAgeMs) {
        if (context == null || maxAgeMs <= 0L) return null;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        String normLang = language == null ? "" : language;
        String normSource = source == null ? "" : source;
        synchronized (LOCK) {
            maybeCleanup(app);
            File file = cacheFile(app, lat, lon, normLang, normSource);
            if (!file.isFile()) return null;
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
                if (body.length() == 0) {
                    file.delete();
                    return null;
                }
                JSONObject root = new JSONObject(body.toString());
                if (root.optInt("schema", -1) != 2) {
                    file.delete();
                    return null;
                }
                double cachedLat = root.optDouble("latitude", Double.NaN);
                double cachedLon = root.optDouble("longitude", Double.NaN);
                if (!Double.isFinite(cachedLat) || !Double.isFinite(cachedLon)
                        || Math.abs(cachedLat - lat) > COORDINATE_TOLERANCE
                        || Math.abs(cachedLon - lon) > COORDINATE_TOLERANCE) {
                    return null;
                }
                if (!normLang.equals(root.optString("language", ""))
                        || !normSource.equals(root.optString("source", ""))) {
                    return null;
                }
                long fetchedAt = root.optLong("fetchedAtMillis", 0L);
                long now = System.currentTimeMillis();
                long age = now - fetchedAt;
                if (fetchedAt <= 0L || age < 0L || age >= maxAgeMs) {
                    if (fetchedAt <= 0L || age < 0L || age >= FILE_RETENTION_MS) file.delete();
                    return null;
                }
                JSONObject response = root.optJSONObject("response");
                if (response == null) {
                    file.delete();
                    return null;
                }
                return new Snapshot(response, fetchedAt);
            } catch (Exception ignored) {
                file.delete();
                return null;
            }
        }
    }

    static void write(Context context, double lat, double lon, String language, String source,
            long fetchedAtMillis, JSONObject rawResponse) {
        if (context == null || rawResponse == null || fetchedAtMillis <= 0L) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        String normLang = language == null ? "" : language;
        String normSource = source == null ? "" : source;
        synchronized (LOCK) {
            maybeCleanup(app);
            File target = cacheFile(app, lat, lon, normLang, normSource);
            File temp = new File(target.getParentFile(), target.getName() + ".tmp");
            try {
                JSONObject root = new JSONObject();
                root.put("schema", 2);
                root.put("latitude", lat);
                root.put("longitude", lon);
                root.put("language", normLang);
                root.put("source", normSource);
                root.put("fetchedAtMillis", fetchedAtMillis);
                root.put("response", rawResponse);
                byte[] encoded = root.toString().getBytes(StandardCharsets.UTF_8);
                if (temp.exists() && !temp.delete()) return;
                try (FileOutputStream out = new FileOutputStream(temp)) {
                    out.write(encoded);
                    out.flush();
                    out.getFD().sync();
                }
                Os.rename(temp.getAbsolutePath(), target.getAbsolutePath());
            } catch (Exception ignored) {
                // Disk caching is best-effort.
            } finally {
                if (temp.exists()) temp.delete();
            }
        }
    }

    private static void maybeCleanup(Context app) {
        if (!legacyCleaned) {
            legacyCleaned = true;
            CacheKeys.deleteLegacyFiles(app.getFilesDir(), LEGACY_PREFIX);
        }
        long now = System.currentTimeMillis();
        if (lastCleanupAtMs > 0L && now >= lastCleanupAtMs
                && now - lastCleanupAtMs < CLEANUP_INTERVAL_MS) {
            return;
        }
        lastCleanupAtMs = now;
        File[] files = app.getCacheDir().listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName();
            if (!name.startsWith(PREFIX)) continue;
            long modified = file.lastModified();
            boolean tmp = name.endsWith(SUFFIX + ".tmp");
            long retention = tmp ? TMP_RETENTION_MS : FILE_RETENTION_MS;
            if ((!tmp && !name.endsWith(SUFFIX))
                    || modified <= 0L || modified > now || now - modified >= retention) {
                file.delete();
            }
        }
    }

    private static File cacheFile(Context app, double lat, double lon, String language, String source) {
        String identity = String.format(Locale.US, "%.5f|%.5f|%s|%s", lat, lon, language, source);
        return new File(app.getCacheDir(), PREFIX + CacheKeys.sha256Hex16(identity) + SUFFIX);
    }
}
