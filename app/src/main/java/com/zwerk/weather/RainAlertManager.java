package com.zwerk.weather;

import android.Manifest;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;

/** Background scheduling, fetching, deduplication and notification support for imminent rain. */
public final class RainAlertManager {
    private static final String UI_PREFS = "WEATHER_UI";
    private static final String PREF_RAIN_ALERTS = "rain_alerts";
    private static final String STATE_PREFS = "rain_alert_state_v1";
    private static final String API_KEY_FILE = "weather_api_key";
    private static final String API_ROOT = "https://weather.googleapis.com/v1/";
    private static final int MINUTE_PAGE_SIZE = 180;
    private static final int JOB_ID = 0x05A772A1;
    private static final int IMMEDIATE_JOB_ID = 0x05A772A2;
    private static final long PERIOD_MILLIS = 15L * 60L * 1000L;
    private static final long FLEX_MILLIS = 5L * 60L * 1000L;
    private static final long ALERT_WINDOW_MILLIS = 30L * 60L * 1000L;
    private static final long EPISODE_JOIN_GAP_MILLIS = 5L * 60L * 1000L;
    private static final long EPISODE_MATCH_TOLERANCE_MILLIS = 20L * 60L * 1000L;
    private static final long ALERT_COOLDOWN_MILLIS = 60L * 60L * 1000L;
    private static final int RESPONSE_LIMIT_BYTES = 2 * 1024 * 1024;
    private static final String CHANNEL_ID = "rain_alerts";
    private static final int NOTIFICATION_ID = 0x5241494E;
    private static final Object ALERT_LOCK = new Object();

    private RainAlertManager() { }

    public static boolean isEnabled(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_RAIN_ALERTS, false);
    }

    /** Reconciles the persisted periodic job with the current rain-alert preference. */
    public static void reconcile(Context context) {
        Context app = context.getApplicationContext();
        JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        if (!isEnabled(app)) {
            scheduler.cancel(JOB_ID);
            scheduler.cancel(IMMEDIATE_JOB_ID);
            return;
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return;

        JobInfo job = new JobInfo.Builder(
                JOB_ID, new ComponentName(app, RainAlertJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(PERIOD_MILLIS, FLEX_MILLIS)
                .build();
        scheduler.schedule(job);
    }

    /** Starts one check soon after the switch is enabled, without waiting for the periodic job. */
    public static void checkSoon(Context context) {
        Context app = context.getApplicationContext();
        if (!isEnabled(app) || !canPostNotifications(app)) return;
        JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        JobInfo job = new JobInfo.Builder(
                IMMEDIATE_JOB_ID, new ComponentName(app, RainAlertJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        scheduler.schedule(job);
    }

    static void performBackgroundCheck(Context context) {
        Context app = context.getApplicationContext();
        if (!isEnabled(app) || !canPostNotifications(app)) return;

        LocationTarget location = resolveLocation(app);
        if (location == null) return;

        String key = readApiKey(app);
        if (key.isEmpty() || Thread.currentThread().isInterrupted()) return;

        ApiRequestBudgetManager.Decision budget = ApiRequestBudgetManager.tryAcquire(
                app, ApiRequestBudgetManager.Category.WEATHER);
        if (!budget.allowed || Thread.currentThread().isInterrupted()) return;

        HttpURLConnection connection = null;
        try {
            String address = minuteForecastAddress(key, location.lat, location.lon);
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(18000);
            connection.setRequestProperty("Accept", "application/json");
            applyAndroidApiKeyRestrictionHeaders(app, connection);

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                closeQuietly(connection.getErrorStream());
                return;
            }

            JSONObject response;
            try (InputStream input = connection.getInputStream()) {
                String body = readUtf8Bounded(input, RESPONSE_LIMIT_BYTES);
                if (body.isEmpty()) return;
                response = new JSONObject(body);
            }
            if (!Thread.currentThread().isInterrupted()) {
                evaluateMinuteForecast(app, response, location.lat, location.lon, location.name);
            }
        } catch (Exception ignored) {
            // Background rain checks are best-effort. Unsupported and transient responses are silent.
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Evaluates an already-fetched minute response and posts at most one deduplicated alert. */
    public static boolean evaluateMinuteForecast(
            Context context,
            JSONObject response,
            double lat,
            double lon,
            String locationName) {
        Context app = context.getApplicationContext();
        if (!isEnabled(app) || !canPostNotifications(app) || response == null) return false;
        if (!validCoordinates(lat, lon)) return false;

        Instant now = Instant.now();
        RainEpisode episode = imminentEpisode(response, now);
        if (episode == null) return false;

        synchronized (ALERT_LOCK) {
            String locationKey = locationStateKey(lat, lon);
            SharedPreferences state = app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
            long nowMillis = now.toEpochMilli();
            long lastAt = state.getLong("last_at_" + locationKey, 0L);
            long lastStart = state.getLong("last_start_" + locationKey, 0L);
            long lastEnd = state.getLong("last_end_" + locationKey, 0L);
            long startMillis = episode.start.toEpochMilli();
            long endMillis = episode.end.toEpochMilli();

            boolean overlappingEpisode = lastStart > 0L && lastEnd > 0L
                    && startMillis <= lastEnd + EPISODE_MATCH_TOLERANCE_MILLIS
                    && endMillis >= lastStart - EPISODE_MATCH_TOLERANCE_MILLIS;
            boolean coolingDown = lastAt > 0L
                    && nowMillis >= lastAt
                    && nowMillis - lastAt < ALERT_COOLDOWN_MILLIS;
            if (overlappingEpisode || coolingDown) return false;

            if (!postNotification(app, episode, now, displayLocation(locationName, lat, lon))) {
                return false;
            }

            state.edit()
                    .putLong("last_at_" + locationKey, nowMillis)
                    .putLong("last_start_" + locationKey, startMillis)
                    .putLong("last_end_" + locationKey, endMillis)
                    .apply();
            return true;
        }
    }

    private static RainEpisode imminentEpisode(JSONObject response, Instant now) {
        JSONArray rawSegments = response.optJSONArray("segments");
        if (rawSegments == null || rawSegments.length() == 0) return null;

        ArrayList<RainSegment> rain = new ArrayList<>();
        for (int i = 0; i < rawSegments.length(); i++) {
            RainSegment segment = parseCredibleRain(rawSegments.optJSONObject(i));
            if (segment != null) rain.add(segment);
        }
        if (rain.isEmpty()) return null;
        rain.sort(Comparator.comparing(segment -> segment.start));

        ArrayList<RainEpisode> episodes = new ArrayList<>();
        RainEpisode current = null;
        for (RainSegment segment : rain) {
            if (current == null
                    || segment.start.toEpochMilli()
                    > current.end.toEpochMilli() + EPISODE_JOIN_GAP_MILLIS) {
                current = new RainEpisode(
                        segment.start, segment.end, segment.probability, segment.intensity);
                episodes.add(current);
            } else if (segment.end.isAfter(current.end)) {
                current.end = segment.end;
            }
        }

        Instant latestStart = now.plusMillis(ALERT_WINDOW_MILLIS);
        for (RainEpisode episode : episodes) {
            if (!episode.end.isAfter(now)) continue;
            if (episode.start.isAfter(latestStart)) continue;
            return episode;
        }
        return null;
    }

    private static RainSegment parseCredibleRain(JSONObject raw) {
        if (raw == null || !"RAIN".equalsIgnoreCase(raw.optString("type", ""))) return null;
        JSONObject frame = raw.optJSONObject("timeFrame");
        Instant start = parseInstant(frame == null ? "" : frame.optString("startTime", ""));
        Instant end = parseInstant(frame == null ? "" : frame.optString("endTime", ""));
        if (start == null || end == null || !end.isAfter(start)) return null;

        JSONObject qpf = raw.optJSONObject("qpf");
        Double qpfQuantity = optionalFiniteDouble(qpf, "quantity");
        boolean positiveQpf = qpfQuantity != null && qpfQuantity > 0d;

        Integer probability = null;
        if (raw.has("probability") && !raw.isNull("probability")) {
            try {
                probability = raw.getInt("probability");
                if (probability < 0 || probability > 100) probability = null;
            } catch (Exception ignored) { }
        }
        String intensity = raw.optString("intensity", "").trim();
        boolean meaningfulIntensity = "LIGHT".equalsIgnoreCase(intensity)
                || "MODERATE".equalsIgnoreCase(intensity)
                || "HEAVY".equalsIgnoreCase(intensity);

        // Positive returned QPF is the preferred evidence. If QPF is absent entirely,
        // require both a meaningful rain intensity and a strong probability; probability alone never fires.
        boolean credible = positiveQpf
                || (qpfQuantity == null && meaningfulIntensity
                && probability != null && probability >= 70);
        if (!credible) return null;
        return new RainSegment(start, end, probability, intensity);
    }

    private static boolean postNotification(
            Context context, RainEpisode episode, Instant now, String location) {
        if (!canPostNotifications(context)) return false;
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;

        NotificationChannel existing = manager.getNotificationChannel(CHANNEL_ID);
        if (existing == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Rain alerts", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Imminent rain alerts for the selected Zwerk Weather location.");
            manager.createNotificationChannel(channel);
        } else if (existing.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            return false;
        }

        Intent openApp = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                0,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        long seconds = Math.max(0L, Duration.between(now, episode.start).getSeconds());
        long minutes = (seconds + 59L) / 60L;
        boolean underway = episode.start.isBefore(now);
        String timing = underway ? "now" : minutes <= 1L ? "now" : "in about " + minutes + " min";
        String intensity = friendlyIntensity(episode.intensity);
        StringBuilder body = new StringBuilder();
        if (!intensity.isEmpty()) body.append(intensity).append(' ');
        if (underway) body.append("rain in progress");
        else body.append("rain expected ").append(timing);
        body.append(" · ").append(location);
        if (episode.probability != null) {
            body.append(" · ").append(episode.probability).append("% chance");
        }

        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(underway ? "Rain nearby now"
                        : minutes <= 1L ? "Rain starting now" : "Rain starting soon")
                .setContentText(body.toString())
                .setStyle(new Notification.BigTextStyle().bigText(body.toString()))
                .setCategory(Notification.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setWhen(episode.start.toEpochMilli())
                .setShowWhen(true)
                .build();
        try {
            manager.notify(NOTIFICATION_ID, notification);
            return true;
        } catch (SecurityException ignored) {
            return false;
        }
    }

    private static boolean canPostNotifications(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private static LocationTarget resolveLocation(Context context) {
        try {
            CityManagerActivity.LocationSnapshot selected =
                    CityManagerActivity.getSelectedLocation(context);
            if (selected != null && validCoordinates(selected.lat, selected.lon)) {
                return new LocationTarget(selected.lat, selected.lon, selected.name);
            }
        } catch (Exception ignored) { }

        SharedPreferences legacy = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        if (!legacy.contains("lat") || !legacy.contains("lon")) return null;
        try {
            double lat = legacy.getFloat("lat", Float.NaN);
            double lon = legacy.getFloat("lon", Float.NaN);
            if (!validCoordinates(lat, lon)) return null;
            return new LocationTarget(lat, lon, legacy.getString("name", ""));
        } catch (ClassCastException ignored) {
            return null;
        }
    }

    private static boolean validCoordinates(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -90d && lat <= 90d && lon >= -180d && lon <= 180d;
    }

    private static String readApiKey(Context context) {
        File keyFile = new File(context.getFilesDir(), API_KEY_FILE);
        if (!keyFile.isFile()) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(keyFile), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String minuteForecastAddress(String key, double lat, double lon) throws Exception {
        return API_ROOT + "forecast/minutes:lookup"
                + "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8.name())
                + "&location.latitude=" + lat
                + "&location.longitude=" + lon
                + "&unitsSystem=METRIC"
                + "&pageSize=" + MINUTE_PAGE_SIZE;
    }

    private static void applyAndroidApiKeyRestrictionHeaders(
            Context context, HttpURLConnection connection) {
        if (connection == null) return;
        String certificate = signingCertificateSha1(context);
        if (certificate.isEmpty()) return;
        connection.setRequestProperty("X-Android-Package", context.getPackageName());
        connection.setRequestProperty("X-Android-Cert", certificate.replace(":", ""));
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

    private static String readUtf8Bounded(InputStream input, int limitBytes) throws Exception {
        if (input == null) return "";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limitBytes) return "";
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name()).trim();
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) return;
        try { input.close(); } catch (Exception ignored) { }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try { return Instant.parse(value.trim()); } catch (Exception ignored) { return null; }
    }

    private static Double optionalFiniteDouble(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) return null;
        try {
            double value = object.getDouble(key);
            return Double.isFinite(value) ? value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String locationStateKey(double lat, double lon) {
        return Math.round(lat * 10000d) + "_" + Math.round(lon * 10000d);
    }

    private static String displayLocation(String name, double lat, double lon) {
        String safe = name == null ? "" : name.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (safe.length() > 72) safe = safe.substring(0, 69) + "…";
        if (!safe.isEmpty()) return safe;
        return String.format(Locale.getDefault(), "%.3f, %.3f", lat, lon);
    }

    private static String friendlyIntensity(String intensity) {
        if (intensity == null) return "";
        String value = intensity.trim();
        if (!"LIGHT".equalsIgnoreCase(value)
                && !"MODERATE".equalsIgnoreCase(value)
                && !"HEAVY".equalsIgnoreCase(value)) {
            return "";
        }
        String lower = value.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static final class LocationTarget {
        final double lat;
        final double lon;
        final String name;

        LocationTarget(double lat, double lon, String name) {
            this.lat = lat;
            this.lon = lon;
            this.name = name == null ? "" : name;
        }
    }

    private static final class RainSegment {
        final Instant start;
        final Instant end;
        final Integer probability;
        final String intensity;

        RainSegment(Instant start, Instant end, Integer probability, String intensity) {
            this.start = start;
            this.end = end;
            this.probability = probability;
            this.intensity = intensity == null ? "" : intensity;
        }
    }

    private static final class RainEpisode {
        final Instant start;
        Instant end;
        final Integer probability;
        final String intensity;

        RainEpisode(Instant start, Instant end, Integer probability, String intensity) {
            this.start = start;
            this.end = end;
            this.probability = probability;
            this.intensity = intensity == null ? "" : intensity;
        }
    }
}
