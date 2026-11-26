package com.zwerk.weather;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;

/** Schedules and evaluates background official severe weather alert checks. */
final class SevereAlertManager {
    private static final String UI_PREFS = WeatherActivityFoundation.UI_PREFS;
    private static final String PREF_SEVERE_ALERTS = WeatherActivityFoundation.PREF_SEVERE_ALERTS;
    private static final String PREF_NOTIFIED_ALERT_IDS = "notified_weather_alert_ids";
    private static final String CHANNEL_ID = "official_weather_alerts";
    private static final int NOTIFICATION_ID = 501;
    private static final int JOB_ID = 0x05A77401;
    private static final int IMMEDIATE_JOB_ID = 0x05A77402;
    private static final long PERIOD_MILLIS = 15L * 60L * 1000L;
    private static final long FLEX_MILLIS = 5L * 60L * 1000L;
    private static final Object ALERT_LOCK = new Object();

    private SevereAlertManager() { }

    static boolean isEnabled(Context context) {
        Context app = context.getApplicationContext();
        return !OpenMeteoConfig.isOpenMeteo(app)
                && app.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
                        .getBoolean(PREF_SEVERE_ALERTS, false);
    }

    static void reconcile(Context context) {
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
                JOB_ID, new ComponentName(app, SevereAlertJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(PERIOD_MILLIS, FLEX_MILLIS)
                .build();
        scheduler.schedule(job);
    }

    static void checkSoon(Context context) {
        Context app = context.getApplicationContext();
        if (!isEnabled(app) || !canPostNotifications(app)) return;
        JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        JobInfo job = new JobInfo.Builder(
                IMMEDIATE_JOB_ID, new ComponentName(app, SevereAlertJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        scheduler.schedule(job);
    }

    static void performBackgroundCheck(Context context) {
        Context app = context.getApplicationContext();
        if (!isEnabled(app) || !canPostNotifications(app)) return;

        LocationTarget location = resolveLocation(app);
        if (location == null) return;

        String key = ApiKeyStore.read(app, WeatherActivityFoundation.API_KEY_FILE);
        if (key.isEmpty()) return;

        Context localized = AppLocaleManager.wrap(app);
        String language = AppLocaleManager.activeLocale(localized).toLanguageTag();

        try {
            SevereAlertCache cache = new SevereAlertCache(app);
            SevereAlertCache.Entry cached = cache.read(location.lat, location.lon, language);
            JSONObject response;
            if (cached != null && cached.response != null) {
                response = cached.response;
            } else {
                response = fetchAlerts(app, location.lat, location.lon, language, key);
                if (!scopeCurrent(app, location)) return;
                cache.write(location.lat, location.lon, language, response);
            }
            if (scopeCurrent(app, location)) {
                notifyIfNew(app, response);
            }
        } catch (Exception ignored) {
            // A failed or superseded refresh never produces a notification.
        }
    }

    static void notifyIfNew(Context context, JSONObject response) {
        Context app = context.getApplicationContext();
        JSONArray alerts = response == null ? null : response.optJSONArray("weatherAlerts");
        if (alerts == null || alerts.length() == 0) return;
        if (!canPostNotifications(app)) return;

        synchronized (ALERT_LOCK) {
            SharedPreferences prefs = app.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE);
            String stored = prefs.getString(PREF_NOTIFIED_ALERT_IDS, "");
            HashSet<String> known = new HashSet<>();
            if (stored != null && !stored.isEmpty()) {
                for (String value : stored.split("\\n")) {
                    if (!value.isEmpty()) known.add(value);
                }
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

            Context localized = AppLocaleManager.wrap(app);
            NotificationManager manager =
                    (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;

            NotificationChannel existing = manager.getNotificationChannel(CHANNEL_ID);
            if (existing == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        UiTranslations.text(localized, "Official weather alerts"),
                        NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription(UiTranslations.text(localized,
                        "Public warnings from official weather authorities"));
                manager.createNotificationChannel(channel);
            } else if (existing.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                return;
            }

            Intent open = new Intent(app, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pending = PendingIntent.getActivity(
                    app,
                    NOTIFICATION_ID,
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            String title = alertTitle(selected);
            String area = selected.optString("areaName", "").trim();
            String severity = prettyEnum(selected.optString("severity", ""));
            String bodyText = !area.isEmpty() && !severity.isEmpty()
                    ? severity + " · " + area
                    : (!area.isEmpty() ? area : severity);

            Notification notification = new Notification.Builder(localized, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notify_alert)
                    .setContentTitle(title.isEmpty()
                            ? UiTranslations.text(localized, "Weather alert") : title)
                    .setContentText(bodyText)
                    .setStyle(new Notification.BigTextStyle().bigText(bodyText))
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setAutoCancel(true)
                    .setContentIntent(pending)
                    .build();
            try {
                manager.notify(NOTIFICATION_ID, notification);
            } catch (SecurityException ignored) {
                return;
            }

            HashSet<String> notified = new HashSet<>();
            for (String id : current) {
                if (known.contains(id)) notified.add(id);
            }
            String selectedId = selected.optString("alertId", "");
            if (!selectedId.isEmpty()) notified.add(selectedId);
            prefs.edit()
                    .putString(PREF_NOTIFIED_ALERT_IDS, TextUtils.join("\n", notified))
                    .apply();
        }
    }

    private static JSONObject fetchAlerts(Context app, double lat, double lon,
            String language, String key) throws Exception {
        StringBuilder address = new StringBuilder(WeatherActivityFoundation.WEATHER_ALERTS_ENDPOINT)
                .append("?location.latitude=").append(lat)
                .append("&location.longitude=").append(lon);
        if (language != null && !language.isEmpty()) {
            address.append("&languageCode=")
                    .append(URLEncoder.encode(language, StandardCharsets.UTF_8.name()));
        }
        String baseAddress = address.toString();
        JSONObject response = null;
        JSONArray combinedAlerts = new JSONArray();
        HashSet<String> seenTokens = new HashSet<>();
        String pageToken = "";
        do {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String pageAddress = pageToken.isEmpty()
                    ? baseAddress
                    : baseAddress + "&pageToken="
                            + URLEncoder.encode(pageToken, StandardCharsets.UTF_8.name());
            JSONObject page = BackgroundWeatherRequest.request(
                    app, pageAddress, DiagnosticLog.Area.OPTIONAL, key);
            if (response == null) response = page;
            JSONArray pageAlerts = page.optJSONArray("weatherAlerts");
            if (pageAlerts != null) {
                for (int i = 0; i < pageAlerts.length(); i++) {
                    JSONObject alert = pageAlerts.optJSONObject(i);
                    if (alert != null) combinedAlerts.put(alert);
                }
            }
            String next = page.optString("nextPageToken", "").trim();
            pageToken = next.isEmpty() || !seenTokens.add(next) ? "" : next;
        } while (!pageToken.isEmpty());
        if (response == null) response = new JSONObject();
        response.put("weatherAlerts", combinedAlerts);
        response.remove("nextPageToken");
        response.put("_fetchedAtMillis", System.currentTimeMillis());
        return response;
    }

    private static boolean scopeCurrent(Context context, LocationTarget requested) {
        if (Thread.currentThread().isInterrupted() || !isEnabled(context)) return false;
        LocationTarget selected = resolveLocation(context);
        return selected != null && selected.lat == requested.lat && selected.lon == requested.lon;
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
                return new LocationTarget(selected.lat, selected.lon);
            }
        } catch (Exception ignored) { }

        SharedPreferences legacy = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
        if (!legacy.contains("lat") || !legacy.contains("lon")) return null;
        try {
            double lat = legacy.getFloat("lat", Float.NaN);
            double lon = legacy.getFloat("lon", Float.NaN);
            return validCoordinates(lat, lon) ? new LocationTarget(lat, lon) : null;
        } catch (ClassCastException ignored) {
            return null;
        }
    }

    private static boolean validCoordinates(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -90d && lat <= 90d && lon >= -180d && lon <= 180d;
    }

    private static int alertSeverityRank(String severity) {
        if ("EXTREME".equalsIgnoreCase(severity)) return 4;
        if ("SEVERE".equalsIgnoreCase(severity)) return 3;
        if ("MODERATE".equalsIgnoreCase(severity)) return 2;
        if ("MINOR".equalsIgnoreCase(severity)) return 1;
        return 0;
    }

    private static String alertTitle(JSONObject alert) {
        if (alert == null) return "";
        JSONObject localized = alert.optJSONObject("alertTitle");
        if (localized != null) return localized.optString("text", "").trim();
        return alert.optString("alertTitle", "").trim();
    }

    private static String prettyEnum(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static final class LocationTarget {
        final double lat;
        final double lon;

        LocationTarget(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }
    }
}
