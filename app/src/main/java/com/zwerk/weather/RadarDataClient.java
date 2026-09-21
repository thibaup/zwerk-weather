package com.zwerk.weather;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** On-demand RainViewer frames and viewport-only OpenStreetMap tiles. */
final class RadarDataClient {
    private static final String TIMELINE_URL =
            "https://api.rainviewer.com/public/weather-maps.json";
    private static final String OSM_TILE_ROOT = "https://tile.openstreetmap.org/";
    private static final String GOOGLE_TILE_ROOT = "https://weather.googleapis.com/v1/mapTypes/";
    private static final String USER_AGENT =
            "ZwerkWeather/1.2.0 (+https://github.com/thibaup/zwerk-weather)";
    private static final long TIMELINE_AGE_MS = 10L * 60L * 1000L;
    private static final long RADAR_TILE_AGE_MS = 3L * 60L * 60L * 1000L;
    private static final long GOOGLE_TILE_AGE_MS = 10L * 60L * 1000L;
    // OSM requires at least seven days if HTTP cache headers are not interpreted.
    private static final long OSM_TILE_AGE_MS = 14L * 24L * 60L * 60L * 1000L;
    private static final int MAX_RADAR_REQUESTS_PER_MINUTE = 80;
    private static final int MAX_IMAGE_BYTES = 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 6_000;
    private static final int READ_TIMEOUT_MS = 9_000;
    private static final long TRANSIENT_IMAGE_BACKOFF_MS = 3_000L;
    private static final int IMAGE_WORKERS = 4;
    private static final int IMAGE_QUEUE_CAPACITY = 64;
    private static final int METADATA_QUEUE_CAPACITY = 2;

    interface TimelineCallback {
        void onTimeline(Timeline timeline, String error);
    }

    static final class Frame {
        final long timeSeconds;
        final String path;

        Frame(long timeSeconds, String path) {
            this.timeSeconds = timeSeconds;
            this.path = path;
        }
    }

    static final class Timeline {
        final String host;
        final ArrayList<Frame> frames;
        final long fetchedAtMillis;

        Timeline(String host, ArrayList<Frame> frames, long fetchedAtMillis) {
            this.host = host;
            this.frames = frames;
            this.fetchedAtMillis = fetchedAtMillis;
        }

        boolean fresh() {
            long age = System.currentTimeMillis() - fetchedAtMillis;
            return age >= 0L && age < TIMELINE_AGE_MS;
        }
    }

    private static final class PendingImageRequest {
        final int generation;
        final ArrayList<Runnable> callbacks = new ArrayList<>();

        PendingImageRequest(int generation) {
            this.generation = generation;
        }
    }

    private static final class RadarRateLimitException extends Exception {
        final long retryAfterMillis;

        RadarRateLimitException(long retryAfterMillis) {
            super("Radar request budget is temporarily exhausted");
            this.retryAfterMillis = retryAfterMillis;
        }
    }

    private final Context context;
    private final String androidCertificate;
    private final ExecutorService imageIo =
            newBoundedExecutor(IMAGE_WORKERS, IMAGE_QUEUE_CAPACITY);
    private final ExecutorService metadataIo =
            newBoundedExecutor(1, METADATA_QUEUE_CAPACITY);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(24 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            return Math.max(1, bitmap.getByteCount() / 1024);
        }
    };
    private final HashMap<String, PendingImageRequest> pendingImages = new HashMap<>();
    private final HashMap<String, Long> failedImagesUntil = new HashMap<>();
    private final ArrayList<TimelineCallback> pendingTimelines = new ArrayList<>();
    private final ArrayDeque<Long> radarRequestTimes = new ArrayDeque<>();
    private final File tileDirectory;
    private final File timelineFile;
    private Timeline timeline;
    private boolean timelineLoading;
    private volatile boolean active;
    private volatile int viewportGeneration;
    private double locationLatitude = Double.NaN;
    private double locationLongitude = Double.NaN;
    private String source = RadarProviderConfig.RAINVIEWER;
    private String googleMapType = "";
    private long googleCacheEpoch;
    private int sourceGeneration;
    private volatile String lastRadarError = "";

    RadarDataClient(Context context) {
        this.context = context.getApplicationContext();
        androidCertificate = signingCertificateSha1(this.context);
        tileDirectory = new File(this.context.getFilesDir(), "radar_tiles_v1");
        timelineFile = new File(this.context.getFilesDir(), "radar_timeline_v1.json");
        try {
            imageIo.execute(this::cleanupTiles);
        } catch (RejectedExecutionException ignored) {
            // The cache cleanup is opportunistic; normal reads remain safe without it.
        }
    }

    private static ExecutorService newBoundedExecutor(int threads, int queueCapacity) {
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.AbortPolicy());
    }

    void setActive(boolean active) {
        synchronized (lock) {
            if (this.active == active) return;
            this.active = active;
            if (!active) {
                viewportGeneration++;
                pendingImages.clear();
            }
        }
    }

    void setSource(String next) {
        String normalized = RadarProviderConfig.GOOGLE.equals(next)
                ? RadarProviderConfig.GOOGLE : RadarProviderConfig.RAINVIEWER;
        synchronized (lock) {
            if (source.equals(normalized)) return;
            source = normalized;
            sourceGeneration++;
            viewportGeneration++;
            timeline = null;
            timelineLoading = false;
            pendingImages.clear();
            pendingTimelines.clear();
        }
    }

    void setLocation(double latitude, double longitude) {
        String next = googleMapType(latitude, longitude);
        synchronized (lock) {
            boolean moved = Double.isNaN(locationLatitude)
                    || Math.abs(locationLatitude - latitude) > 0.00001d
                    || Math.abs(locationLongitude - longitude) > 0.00001d;
            if (!moved && googleMapType.equals(next)) return;
            locationLatitude = latitude;
            locationLongitude = longitude;
            if (moved) {
                viewportGeneration++;
                pendingImages.clear();
            }
            googleMapType = next;
            if (RadarProviderConfig.GOOGLE.equals(source)) timeline = null;
        }
    }

    void invalidateViewportRequests() {
        synchronized (lock) {
            viewportGeneration++;
            pendingImages.clear();
        }
    }

    String lastRadarError() {
        return lastRadarError;
    }

    void retryFailedRadarTiles() {
        synchronized (lock) {
            failedImagesUntil.keySet().removeIf(url -> url.contains("rainviewer.com")
                    || url.startsWith(GOOGLE_TILE_ROOT));
            lastRadarError = "";
        }
    }

    void retryFailedTiles() {
        synchronized (lock) {
            failedImagesUntil.clear();
            lastRadarError = "";
        }
    }

    void dispose() {
        synchronized (lock) {
            active = false;
            viewportGeneration++;
            sourceGeneration++;
            pendingImages.clear();
            pendingTimelines.clear();
            memory.evictAll();
        }
        imageIo.shutdownNow();
        metadataIo.shutdownNow();
    }

    void loadTimeline(boolean force, TimelineCallback callback) {
        if (RadarProviderConfig.GOOGLE.equals(source)) {
            lastRadarError = "";
            if (googleMapType.isEmpty()) {
                callback.onTimeline(null,
                        "Google precipitation maps currently cover supported US and European areas.");
                return;
            }
            if (RadarProviderConfig.readGoogleKey(context).isEmpty()) {
                callback.onTimeline(null, "Add your Google API key in Settings to use this radar.");
                return;
            }
            if (force) googleCacheEpoch = System.currentTimeMillis();
            ArrayList<Frame> frames = new ArrayList<>();
            frames.add(new Frame(System.currentTimeMillis() / 1000L,
                    "/" + googleMapType));
            Timeline current = new Timeline("https://weather.googleapis.com",
                    frames, System.currentTimeMillis());
            callback.onTimeline(current, null);
            return;
        }
        final int generation;
        synchronized (lock) {
            if (!force && timeline != null && timeline.fresh()) {
                callback.onTimeline(timeline, null);
                return;
            }
            pendingTimelines.add(callback);
            if (timelineLoading) return;
            timelineLoading = true;
            generation = sourceGeneration;
        }
        Runnable load = () -> {
            Timeline result = force ? null : readTimeline(false);
            String error = null;
            if (result == null) {
                try {
                    byte[] bytes = fetch(TIMELINE_URL, 128 * 1024, true);
                    JSONObject raw = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                    result = parseTimeline(raw, System.currentTimeMillis());
                    saveTimeline(raw, result.fetchedAtMillis);
                } catch (Exception ignored) {
                    error = "Radar frames could not be loaded. Tap retry to try again.";
                    synchronized (lock) {
                        if (generation == sourceGeneration && timeline != null) {
                            result = timeline;
                        }
                    }
                    if (result == null) result = readTimeline(true);
                }
            }
            completeTimelineLoad(generation, result, error);
        };
        try {
            metadataIo.execute(load);
        } catch (RejectedExecutionException ignored) {
            Timeline cached;
            synchronized (lock) {
                cached = timeline;
            }
            if (cached == null) cached = readTimeline(true);
            completeTimelineLoad(
                    generation,
                    cached,
                    "Radar is busy right now. Tap retry to try again.");
        }
    }

    private void completeTimelineLoad(int generation, Timeline delivered, String error) {
        ArrayList<TimelineCallback> callbacks;
        synchronized (lock) {
            if (generation != sourceGeneration) return;
            if (delivered != null) timeline = delivered;
            timelineLoading = false;
            callbacks = new ArrayList<>(pendingTimelines);
            pendingTimelines.clear();
        }
        if (callbacks.isEmpty()) return;
        main.post(() -> {
            for (TimelineCallback item : callbacks) item.onTimeline(delivered, error);
        });
    }

    Bitmap baseTile(int zoom, int tileX, int tileY, Runnable onReady,
            boolean allowLoad) {
        int count = 1 << zoom;
        if (tileY < 0 || tileY >= count) return null;
        int wrappedX = ((tileX % count) + count) % count;
        String url = OSM_TILE_ROOT + zoom + "/" + wrappedX + "/" + tileY + ".png";
        return image(url, OSM_TILE_AGE_MS, false, onReady, allowLoad);
    }

    Bitmap radarTile(Timeline timeline, Frame frame, int zoom,
            int tileX, int tileY, Runnable onReady, boolean allowLoad) {
        if (timeline == null || frame == null) return null;
        int count = 1 << zoom;
        if (tileY < 0 || tileY >= count) return null;
        int wrappedX = ((tileX % count) + count) % count;
        if ("https://weather.googleapis.com".equals(timeline.host)) {
            String key = RadarProviderConfig.readGoogleKey(context);
            if (!key.matches("[A-Za-z0-9_-]+")) return null;
            String url = GOOGLE_TILE_ROOT + frame.path.substring(1)
                    + "/mapTiles/" + zoom + "/" + wrappedX + "/" + tileY
                    + "?key=" + key
                    + "#" + googleCacheEpoch;
            return image(url, GOOGLE_TILE_AGE_MS, true, onReady, allowLoad);
        }
        String url = timeline.host + frame.path + "/256/" + zoom + "/"
                + wrappedX + "/" + tileY + "/2/1_1.png";
        return image(url, RADAR_TILE_AGE_MS, true, onReady, allowLoad);
    }

    private static String googleMapType(double lat, double lon) {
        if (lat >= 34d && lat <= 72d && lon >= -12d && lon <= 40d) {
            return "EU_PRECIPITATION_CURRENT";
        }
        if (lat >= 15d && lat <= 72d && lon >= -170d && lon <= -50d) {
            return "US_PRECIPITATION_CURRENT";
        }
        return "";
    }

    private Bitmap image(String url, long maxAge, boolean radar, Runnable onReady) {
        return image(url, maxAge, radar, onReady, true);
    }

    private Bitmap image(String url, long maxAge, boolean radar, Runnable onReady,
            boolean allowLoad) {
        final int requestGeneration;
        final PendingImageRequest request;
        synchronized (lock) {
            Bitmap cached = memory.get(url);
            if (cached != null && !cached.isRecycled()) return cached;
            if (!active || !allowLoad) return null;

            long now = System.currentTimeMillis();
            Long blockedUntil = failedImagesUntil.get(url);
            if (blockedUntil != null) {
                if (now < blockedUntil) return null;
                failedImagesUntil.remove(url);
            }

            requestGeneration = viewportGeneration;
            PendingImageRequest pending = pendingImages.get(url);
            if (pending != null && pending.generation == requestGeneration) {
                if (onReady != null && pending.callbacks.size() < 4) {
                    pending.callbacks.add(onReady);
                }
                return null;
            }

            request = new PendingImageRequest(requestGeneration);
            if (onReady != null) request.callbacks.add(onReady);
            pendingImages.put(url, request);
        }

        Runnable load = () -> {
            Bitmap bitmap = null;
            long retryDelayMillis = TRANSIENT_IMAGE_BACKOFF_MS;
            boolean failed = false;
            try {
                if (active && requestGeneration == viewportGeneration) {
                    File file = imageFile(url, radar);
                    byte[] bytes = readFreshImage(file, maxAge);
                    if (bytes == null && active
                            && requestGeneration == viewportGeneration) {
                        try {
                            bytes = fetch(url, MAX_IMAGE_BYTES, radar);
                            saveImage(file, bytes);
                        } catch (RadarRateLimitException rateLimited) {
                            failed = true;
                            retryDelayMillis = Math.max(
                                    TRANSIENT_IMAGE_BACKOFF_MS,
                                    rateLimited.retryAfterMillis);
                            bytes = readCachedImage(file);
                        } catch (Exception networkError) {
                            failed = true;
                            bytes = readCachedImage(file);
                        }
                    }
                    if (bytes != null) {
                        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        if (bitmap == null) {
                            failed = true;
                            file.delete();
                        }
                    }
                }
            } catch (Exception ignored) {
                failed = true;
            }

            if (failed && radar && active && requestGeneration == viewportGeneration) {
                if (url.startsWith(GOOGLE_TILE_ROOT)) {
                    if (lastRadarError.isEmpty()) {
                        lastRadarError =
                                "Google radar could not load. Check your connection and key.";
                    }
                } else if (lastRadarError.isEmpty()) {
                    lastRadarError = "Radar tiles could not load. Tap retry to try again.";
                }
            }
            completeImageRequest(
                    url, radar, request, bitmap, failed, retryDelayMillis);
        };

        try {
            imageIo.execute(load);
        } catch (RejectedExecutionException ignored) {
            completeImageRequest(
                    url, radar, request, null, true, TRANSIENT_IMAGE_BACKOFF_MS);
        }
        return null;
    }

    private void completeImageRequest(
            String url,
            boolean radar,
            PendingImageRequest request,
            Bitmap loaded,
            boolean failed,
            long retryDelayMillis) {
        ArrayList<Runnable> callbacks = null;
        long callbackDelayMillis = 0L;
        synchronized (lock) {
            PendingImageRequest current = pendingImages.get(url);
            boolean ownsPendingEntry = current == request;
            boolean currentGeneration = request.generation == viewportGeneration;
            if (ownsPendingEntry) pendingImages.remove(url);

            if (!active || !ownsPendingEntry || !currentGeneration) return;

            if (loaded != null && !loaded.isRecycled()) {
                memory.put(url, loaded);
                failedImagesUntil.remove(url);
                if (radar) lastRadarError = "";
            } else if (failed) {
                callbackDelayMillis = Math.max(250L, retryDelayMillis);
                failedImagesUntil.put(
                        url, System.currentTimeMillis() + callbackDelayMillis);
            }

            if (!request.callbacks.isEmpty()) {
                callbacks = new ArrayList<>(request.callbacks);
            }
        }

        if (callbacks == null) return;
        final ArrayList<Runnable> deliveredCallbacks = callbacks;
        final int generation = request.generation;
        Runnable deliver = () -> {
            if (!active || generation != viewportGeneration) return;
            for (Runnable item : deliveredCallbacks) item.run();
        };
        if (callbackDelayMillis > 0L) main.postDelayed(deliver, callbackDelayMillis + 50L);
        else main.post(deliver);
    }

    private Timeline readTimeline(boolean allowStale) {
        if (!timelineFile.isFile()) return null;
        try {
            JSONObject root = new JSONObject(new String(
                    new AtomicFile(timelineFile).readFully(), StandardCharsets.UTF_8));
            long fetchedAt = root.optLong("fetchedAtMillis");
            Timeline result = parseTimeline(root.optJSONObject("response"), fetchedAt);
            long age = System.currentTimeMillis() - fetchedAt;
            return result.fresh() || allowStale && age >= 0L
                    && age < RADAR_TILE_AGE_MS ? result : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Timeline parseTimeline(JSONObject raw, long fetchedAt) throws Exception {
        if (raw == null) throw new IllegalArgumentException("Missing radar metadata");
        String host = raw.optString("host", "").trim();
        URL hostUrl = new URL(host);
        if (!"https".equalsIgnoreCase(hostUrl.getProtocol())
                || !hostUrl.getHost().endsWith(".rainviewer.com")) {
            throw new IllegalArgumentException("Unsupported radar tile host");
        }
        JSONArray past = raw.optJSONObject("radar") == null ? null
                : raw.optJSONObject("radar").optJSONArray("past");
        if (past == null || past.length() == 0) throw new IllegalArgumentException("No radar frames");
        ArrayList<Frame> frames = new ArrayList<>();
        for (int i = 0; i < past.length(); i++) {
            JSONObject item = past.optJSONObject(i);
            if (item == null) continue;
            long time = item.optLong("time");
            String path = item.optString("path", "");
            if (time > 0L && path.startsWith("/v2/radar/")
                    && !path.contains("..") && !path.contains("?")) {
                frames.add(new Frame(time, path));
            }
        }
        if (frames.isEmpty()) throw new IllegalArgumentException("No usable radar frames");
        return new Timeline(host, frames, fetchedAt);
    }

    private void saveTimeline(JSONObject raw, long fetchedAt) {
        AtomicFile atomic = new AtomicFile(timelineFile);
        FileOutputStream out = null;
        try {
            JSONObject root = new JSONObject();
            root.put("fetchedAtMillis", fetchedAt);
            root.put("response", raw);
            out = atomic.startWrite();
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(out);
        } catch (Exception ignored) {
            if (out != null) atomic.failWrite(out);
        }
    }

    private File imageFile(String url, boolean radar) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(url.getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder(url.startsWith(GOOGLE_TILE_ROOT)
                ? "google_" : radar ? "radar_" : "osm_");
        for (byte value : hash) name.append(String.format(Locale.US, "%02x", value & 0xff));
        return new File(tileDirectory, name + ".png");
    }

    private byte[] readFreshImage(File file, long maxAge) {
        if (!file.isFile()) return null;
        long age = System.currentTimeMillis() - file.lastModified();
        if (age < 0L || age >= maxAge) return null;
        return readCachedImage(file);
    }

    private byte[] readCachedImage(File file) {
        if (!file.isFile()) return null;
        try {
            return new AtomicFile(file).readFully();
        } catch (Exception ignored) {
            file.delete();
            return null;
        }
    }

    private void saveImage(File file, byte[] bytes) {
        if (!tileDirectory.isDirectory() && !tileDirectory.mkdirs()) return;
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            out.write(bytes);
            atomic.finishWrite(out);
        } catch (Exception ignored) {
            if (out != null) atomic.failWrite(out);
        }
    }

    private void cleanupTiles() {
        File[] files = tileDirectory.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (!file.isFile()) continue;
            long maxAge = file.getName().startsWith("osm_")
                    ? OSM_TILE_AGE_MS : file.getName().startsWith("google_")
                    ? GOOGLE_TILE_AGE_MS : RADAR_TILE_AGE_MS;
            long age = now - file.lastModified();
            if (age < 0L || age >= maxAge) file.delete();
        }
    }

    private byte[] fetch(String urlText, int maxBytes, boolean radar) throws Exception {
        boolean google = urlText.startsWith(GOOGLE_TILE_ROOT);
        if (radar && !google && !TIMELINE_URL.equals(urlText)) acquireRadarSlot();
        if (!active) throw new IllegalStateException("Radar page closed");
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        if (google && !androidCertificate.isEmpty()) {
            connection.setRequestProperty("X-Android-Package", context.getPackageName());
            connection.setRequestProperty("X-Android-Cert", androidCertificate.replace(":", ""));
        }
        connection.setRequestProperty("Accept", radar && urlText.endsWith(".json")
                ? "application/json" : "image/png");
        try {
            if (google) {
                ApiRequestBudgetManager.Decision budget = ApiRequestBudgetManager.tryAcquire(
                        context, ApiRequestBudgetManager.Category.WEATHER);
                if (!budget.allowed) {
                    lastRadarError = "Weather API request cap reached. Check API limits.";
                    throw new IllegalStateException("Weather API request cap reached");
                }
            } else {
                RadarUsageCounter.record(context, radar);
            }
            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                if (google) {
                    lastRadarError = responseCode == 401 || responseCode == 403
                            ? "Google radar access was denied. Check the Weather API, key restrictions, and billing."
                            : responseCode == 404
                            ? "Google precipitation tiles are unavailable for this area."
                            : "Google radar returned HTTP " + responseCode + ". Tap refresh to retry.";
                }
                throw new IllegalStateException("Radar/map HTTP " + responseCode);
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (output.size() + n > maxBytes) throw new IllegalStateException("Map image too large");
                    output.write(buffer, 0, n);
                }
                return output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private void acquireRadarSlot() throws RadarRateLimitException {
        synchronized (radarRequestTimes) {
            long now = System.currentTimeMillis();
            while (!radarRequestTimes.isEmpty()
                    && now - radarRequestTimes.peekFirst() >= 60_000L) {
                radarRequestTimes.removeFirst();
            }
            if (radarRequestTimes.size() < MAX_RADAR_REQUESTS_PER_MINUTE) {
                radarRequestTimes.addLast(now);
                return;
            }
            long retryAfter = Math.max(
                    500L,
                    60_000L - (now - radarRequestTimes.peekFirst()));
            throw new RadarRateLimitException(retryAfter);
        }
    }

    private static String signingCertificateSha1(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null) return "";
            android.content.pm.Signature[] signatures = info.signingInfo.getApkContentsSigners();
            if (signatures == null || signatures.length == 0) return "";
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(signatures[0].toByteArray());
            StringBuilder value = new StringBuilder();
            for (byte part : digest) {
                if (value.length() > 0) value.append(':');
                value.append(String.format(Locale.US, "%02X", part & 0xff));
            }
            return value.toString();
        } catch (Exception ignored) {
            return "";
        }
    }
}
