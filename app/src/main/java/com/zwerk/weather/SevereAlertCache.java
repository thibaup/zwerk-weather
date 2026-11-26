package com.zwerk.weather;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** A short-lived, per-location cache for official alerts, including an empty alert list. */
final class SevereAlertCache {
    static final long MAX_AGE_MILLIS = 15L * 60L * 1000L;
    private static final String PREFIX = "weather_alert_cache_v1_";
    private static final long CLEANUP_INTERVAL_MS = 60L * 60L * 1000L;
    private static volatile boolean legacyCleaned;
    private static volatile long lastCleanupAtMs;
    private final Context context;

    SevereAlertCache(Context context) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
    }

    static String key(double lat, double lon, String language) {
        return Double.toHexString(lat) + "|" + Double.toHexString(lon) + "|"
                + (language == null ? "" : language);
    }

    static final class Entry {
        final JSONObject response;
        final long fetchedAtMillis;

        Entry(JSONObject response, long fetchedAtMillis) {
            this.response = response;
            this.fetchedAtMillis = fetchedAtMillis;
        }

        boolean fresh() {
            long age = System.currentTimeMillis() - fetchedAtMillis;
            return fetchedAtMillis > 0L && age >= 0L && age < MAX_AGE_MILLIS;
        }
    }

    synchronized void cleanup() {
        if (!legacyCleaned) {
            legacyCleaned = true;
            File filesDir = context.getFilesDir();
            File cacheDir = context.getCacheDir();
            if (filesDir != null && !filesDir.equals(cacheDir)) {
                CacheKeys.deleteLegacyFiles(filesDir, PREFIX);
            }
        }
        long now = System.currentTimeMillis();
        if (lastCleanupAtMs > 0L && now >= lastCleanupAtMs
                && now - lastCleanupAtMs < CLEANUP_INTERVAL_MS) {
            return;
        }
        lastCleanupAtMs = now;
        File[] files = context.getCacheDir().listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName();
            if (!name.startsWith(PREFIX)) continue;
            long modified = file.lastModified();
            if (modified <= 0L || modified > now || now - modified >= MAX_AGE_MILLIS) {
                file.delete();
            }
        }
    }

    Entry read(double lat, double lon, String language) {
        cleanup();
        File file = fileFor(lat, lon, language);
        if (!file.isFile()) return null;
        try {
            JSONObject root = new JSONObject(new String(
                    new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
            if (root.optInt("schema") != 1
                    || !key(lat, lon, language).equals(root.optString("key"))) return null;
            Entry entry = new Entry(root.optJSONObject("response"),
                    root.optLong("fetchedAtMillis"));
            if (entry.response != null && entry.fresh()) return entry;
        } catch (Exception ignored) { }
        file.delete();
        return null;
    }

    void write(double lat, double lon, String language, JSONObject response) {
        if (response == null) return;
        cleanup();
        File file = fileFor(lat, lon, language);
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream out = null;
        try {
            JSONObject root = new JSONObject();
            root.put("schema", 1);
            root.put("key", key(lat, lon, language));
            root.put("fetchedAtMillis", System.currentTimeMillis());
            root.put("response", response);
            out = atomic.startWrite();
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(out);
        } catch (Exception ignored) {
            if (out != null) atomic.failWrite(out);
        }
    }

    private File fileFor(double lat, double lon, String language) {
        String identity = key(lat, lon, language);
        return new File(context.getCacheDir(), PREFIX + CacheKeys.sha256Hex16(identity) + ".json");
    }
}
