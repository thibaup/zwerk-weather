package com.zwerk.weather;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class RadarDataClient {
    private static final String TIMELINE_URL =
            "https://api.rainviewer.com/public/weather-maps.json";
    private static final String OSM_TILE_ROOT = "https://tile.openstreetmap.org/";
    private static final String USER_AGENT =
            "ZwerkWeather/1.5 (+https://github.com/thibaup/zwerk-weather)";
    private static final long TIMELINE_AGE_MS = 10L * 60L * 1000L;
    private static final long RADAR_TILE_AGE_MS = 3L * 60L * 60L * 1000L;
    // OSM requires at least seven days if HTTP cache headers are not interpreted.
    private static final long OSM_TILE_AGE_MS = 14L * 24L * 60L * 60L * 1000L;
    private static final int MAX_RADAR_REQUESTS_PER_MINUTE = 80;
    private static final RadarRequestLimiter RADAR_LIMITER =
            new RadarRequestLimiter(MAX_RADAR_REQUESTS_PER_MINUTE, 60_000L);
    private static final int MAX_IMAGE_BYTES = 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 6_000;
    private static final int READ_TIMEOUT_MS = 9_000;
    private static final long TRANSIENT_IMAGE_BACKOFF_MS = 3_000L;
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
        int generation;
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
    private final TileRequestScheduler radarIo =
            new TileRequestScheduler("RadarTiles", 2, IMAGE_QUEUE_CAPACITY);
    private final TileRequestScheduler baseIo =
            new TileRequestScheduler("MapTiles", 2, IMAGE_QUEUE_CAPACITY);
    private final ExecutorService metadataIo =
            newBoundedExecutor(1, METADATA_QUEUE_CAPACITY);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(24 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            return Math.max(1, bitmap.getByteCount() / 1024);
        }
    };
    // Radar animation must not evict the map underneath it. Both caches are bounded.
    private final LruCache<String, Bitmap> baseMemory = new LruCache<String, Bitmap>(24 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) {
            return Math.max(1, bitmap.getByteCount() / 1024);
        }
    };
    private final HashMap<String, PendingImageRequest> pendingImages = new HashMap<>();
    private final HashMap<String, Long> failedImagesUntil = new HashMap<>();
    private final ArrayList<TimelineCallback> pendingTimelines = new ArrayList<>();
    private final File tileDirectory;
    private final File timelineFile;
    private Timeline timeline;
    private boolean timelineLoading;
    private volatile boolean active;
    private volatile int viewportGeneration;
    private double locationLatitude = Double.NaN;
    private double locationLongitude = Double.NaN;
    private int sourceGeneration;
    private volatile String lastRadarError = "";

    RadarDataClient(Context context) {
        this.context = context.getApplicationContext();
        this.context.getSharedPreferences("WEATHER_UI", Context.MODE_PRIVATE).edit().remove("radar_source").apply();
        tileDirectory = new File(this.context.getFilesDir(), "radar_tiles_v1");
        timelineFile = new File(this.context.getFilesDir(), "radar_timeline_v1.json");
        try {
            metadataIo.execute(this::cleanupTiles);
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
                setTileDemand(new HashMap<>(), new HashMap<>());
            }
        }
    }

    void setLocation(double latitude, double longitude) {
        synchronized (lock) {
            boolean moved = Double.isNaN(locationLatitude)
                    || Math.abs(locationLatitude - latitude) > 0.00001d
                    || Math.abs(locationLongitude - longitude) > 0.00001d;
            if (!moved) return;
            locationLatitude = latitude;
            locationLongitude = longitude;
            if (moved) {
                viewportGeneration++;
                setTileDemand(new HashMap<>(), new HashMap<>());
            }
        }
    }

    String lastRadarError() {
        String wait = radarWaitMessage();
        if (!wait.isEmpty()) return wait;
        return lastRadarError;
    }

    int viewportGeneration() { return viewportGeneration; }

    long radarCooldownMillis() { return RADAR_LIMITER.delayMillis(SystemClock.elapsedRealtime()); }

    String radarWaitMessage() {
        long now = SystemClock.elapsedRealtime();
        long delay = RADAR_LIMITER.delayMillis(now);
        if (delay <= 0L) return "";
        String message = RADAR_LIMITER.isServerLimited(now)
                ? "Radar service rate limit · retrying in %d s"
                : "Radar request limit · retrying in %d s";
        return String.format(Locale.getDefault(), UiTranslations.text(context, message), (delay + 999L) / 1000L);
    }

    void setTileDemand(Map<String, Integer> base, Map<String, Integer> radar) {
        synchronized (lock) {
            for (String url : baseIo.setDemand(base)) pendingImages.remove(url);
            for (String url : radarIo.setDemand(radar)) pendingImages.remove(url);
            failedImagesUntil.keySet().removeIf(url -> !base.containsKey(url) && !radar.containsKey(url));
        }
    }

    void retryFailedRadarTiles() {
        synchronized (lock) {
            failedImagesUntil.keySet().removeIf(url -> url.contains("rainviewer.com"));
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
            baseMemory.evictAll();
        }
        baseIo.shutdownNow();
        radarIo.shutdownNow();
        metadataIo.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    void loadTimeline(boolean force, TimelineCallback callback) {
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
                    error = radarWaitMessage();
                    if (error.isEmpty()) error = "Radar frames could not be loaded. Tap retry to try again.";
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
        String url = baseTileUrl(zoom, tileX, tileY);
        return url == null ? null : image(url, OSM_TILE_AGE_MS, false, onReady, allowLoad);
    }

    static String baseTileUrl(int zoom, int tileX, int tileY) {
        int count = 1 << zoom;
        if (tileY < 0 || tileY >= count) return null;
        int wrappedX = ((tileX % count) + count) % count;
        return OSM_TILE_ROOT + zoom + "/" + wrappedX + "/" + tileY + ".png";
    }

    Bitmap radarTile(Timeline timeline, Frame frame, int zoom,
            int tileX, int tileY, Runnable onReady, boolean allowLoad) {
        String url = radarTileUrl(timeline, frame, zoom, tileX, tileY);
        return url == null ? null : image(url, RADAR_TILE_AGE_MS, true, onReady, allowLoad);
    }

    static String radarTileUrl(Timeline timeline, Frame frame, int zoom, int tileX, int tileY) {
        if (timeline == null || frame == null) return null;
        int count = 1 << zoom;
        if (tileY < 0 || tileY >= count) return null;
        int wrappedX = ((tileX % count) + count) % count;
        return timeline.host + frame.path + "/512/" + zoom + "/"
                + wrappedX + "/" + tileY + "/2/1_1.png";
    }

    private Bitmap image(String url, long maxAge, boolean radar, Runnable onReady) {
        return image(url, maxAge, radar, onReady, true);
    }

    private Bitmap image(String url, long maxAge, boolean radar, Runnable onReady,
            boolean allowLoad) {
        final int requestGeneration;
        final PendingImageRequest request;
        synchronized (lock) {
            Bitmap cached = (radar ? memory : baseMemory).get(url);
            if (cached != null && !cached.isRecycled()) return cached;
            if (!active || !allowLoad || !(radar ? radarIo : baseIo).isNeeded(url)) return null;

            long now = System.currentTimeMillis();
            Long blockedUntil = failedImagesUntil.get(url);
            if (blockedUntil != null) {
                if (now < blockedUntil) return null;
                failedImagesUntil.remove(url);
            }

            requestGeneration = viewportGeneration;
            PendingImageRequest pending = pendingImages.get(url);
            if (pending != null) {
                if (pending.generation != requestGeneration) {
                    pending.generation = requestGeneration;
                    pending.callbacks.clear();
                }
                if (onReady != null && pending.callbacks.size() < 4 && !pending.callbacks.contains(onReady)) {
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
                if (active && (radar ? radarIo : baseIo).isNeeded(url)) {
                    File file = imageFile(url, radar);
                    byte[] bytes = readFreshImage(file, maxAge);
                    if (bytes == null && radar) {
                        bitmap = readLegacyRadarTile(url, maxAge);
                        if (bitmap != null) {
                            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded))
                                saveImage(file, encoded.toByteArray());
                        }
                    }
                    if (bytes == null && bitmap == null && active && (radar ? radarIo : baseIo).isNeeded(url)) {
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
                    if (bitmap != null) bitmap.prepareToDraw();
                }
            } catch (Exception | OutOfMemoryError ignored) {
                failed = true;
            }

            if (failed && radar && active && radarCooldownMillis() == 0L) {
                if (lastRadarError.isEmpty()) {
                    lastRadarError = "Radar tiles could not load. Tap retry to try again.";
                }
            }
            completeImageRequest(
                    url, radar, request, bitmap, failed, retryDelayMillis);
        };

        if (!(radar ? radarIo : baseIo).submit(url, load)) {
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

            // Tiles are keyed by their complete URL, so a successful old viewport
            // request remains useful after a pan. Only its callbacks are stale.
            if (active && loaded != null && !loaded.isRecycled()) {
                (radar ? memory : baseMemory).put(url, loaded);
            }

            if (!active || !ownsPendingEntry || !currentGeneration) return;

            if (loaded != null && !loaded.isRecycled()) {
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
        StringBuilder name = new StringBuilder(radar ? "radar_" : "osm_");
        for (byte value : hash) name.append(String.format(Locale.US, "%02x", value & 0xff));
        return new File(tileDirectory, name + ".png");
    }

    private byte[] readFreshImage(File file, long maxAge) {
        if (!file.isFile()) return null;
        long age = System.currentTimeMillis() - file.lastModified();
        if (age < 0L || age >= maxAge) return null;
        return readCachedImage(file);
    }

    /** Four cached 256px children contain exactly the pixels needed for their 512px parent. */
    private Bitmap readLegacyRadarTile(String url, long maxAge) {
        int split = url.lastIndexOf("/512/");
        if (split < 0) return null;
        Bitmap[] children = new Bitmap[4];
        Bitmap combined = null;
        boolean complete = false;
        try {
            String[] coordinates = url.substring(split + 5).split("/", 4);
            int zoom = Integer.parseInt(coordinates[0]);
            int x = Integer.parseInt(coordinates[1]);
            int y = Integer.parseInt(coordinates[2]);
            if (zoom >= 7) return null;
            String root = url.substring(0, split) + "/256/" + (zoom + 1) + "/";
            for (int i = 0; i < 4; i++) {
                String childUrl = root + (x * 2 + i % 2) + "/" + (y * 2 + i / 2)
                        + "/" + coordinates[3];
                byte[] bytes = readFreshImage(imageFile(childUrl, true), maxAge);
                if (bytes == null) return null;
                children[i] = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (children[i] == null) return null;
            }
            combined = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(combined);
            for (int i = 0; i < 4; i++) {
                int left = i % 2 * 256;
                int top = i / 2 * 256;
                canvas.drawBitmap(children[i], null, new Rect(left, top, left + 256, top + 256), null);
            }
            complete = true;
            return combined;
        } catch (Exception | OutOfMemoryError ignored) {
            return null;
        } finally {
            for (Bitmap child : children) if (child != null) child.recycle();
            if (!complete && combined != null) combined.recycle();
        }
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
            if (file.getName().startsWith("google_")) { file.delete(); continue; }
            long maxAge = file.getName().startsWith("osm_")
                    ? OSM_TILE_AGE_MS : RADAR_TILE_AGE_MS;
            long age = now - file.lastModified();
            if (age < 0L || age >= maxAge) file.delete();
        }
    }

    private byte[] fetch(String urlText, int maxBytes, boolean radar) throws Exception {
        if (radar) acquireRadarSlot();
        if (!active) throw new IllegalStateException("Radar page closed");
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", radar && urlText.endsWith(".json")
                ? "application/json" : "image/png");
        try {
            RadarUsageCounter.record(context, radar);
            int responseCode = connection.getResponseCode();
            if (radar && responseCode == 429) {
                long delay = RadarRequestLimiter.retryAfterMillis(
                        connection.getHeaderField("Retry-After"), System.currentTimeMillis());
                RADAR_LIMITER.serverLimited(SystemClock.elapsedRealtime(), delay);
                throw new RadarRateLimitException(delay);
            }
            if (responseCode != 200) {
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
        long delay = RADAR_LIMITER.acquire(SystemClock.elapsedRealtime());
        if (delay > 0L) throw new RadarRateLimitException(delay);
    }

}
