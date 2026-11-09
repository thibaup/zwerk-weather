package com.zwerk.weather;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;

/** A home-screen plot rendered solely from the app's saved, location-scoped forecast. */
public final class PrecipitationWidgetProvider extends AppWidgetProvider {
    static final String KEY_LOCATION_SCOPE = "precip_location_scope";
    static final String KEY_MINUTE_SCOPE = "precip_minute_scope";
    static final String KEY_MINUTE_JSON = "precip_minute_json";
    static final String KEY_MINUTE_FETCHED_AT_MS = "precip_minute_fetched_at_ms";
    static final String KEY_MINUTE_SOURCE = "precip_minute_source";
    private static final String ACTION_REFRESH = "com.zwerk.weather.action.REFRESH_PRECIP_WIDGET";
    private static final long MINUTE_MAX_AGE_MILLIS = 60L * 60L * 1000L;

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        DiagnosticLog.event(DiagnosticLog.Area.WIDGET, DiagnosticLog.Event.WIDGET_UPDATE);
        WidgetRefreshManager.reconcile(context);
        for (int id : ids) manager.updateAppWidget(id, buildViews(context, id, null));
        scheduleNextUpdate(context);
    }

    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
            int id, android.os.Bundle options) {
        super.onAppWidgetOptionsChanged(context, manager, id, options);
        DiagnosticLog.event(DiagnosticLog.Area.WIDGET, DiagnosticLog.Event.WIDGET_RESIZE);
        WidgetRefreshManager.reconcile(context);
        manager.updateAppWidget(id, buildViews(context, id, options));
        scheduleNextUpdate(context);
    }

    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent == null) return;
        String action = intent.getAction();
        if (ACTION_REFRESH.equals(action) || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_DATE_CHANGED.equals(action)) updateAll(context);
    }

    @Override public void onDisabled(Context context) {
        cancelClockUpdate(context);
        WidgetRefreshManager.reconcile(context);
    }

    @Override public void onDeleted(Context context, int[] ids) {
        WidgetConfiguration.delete(context, ids);
        scheduleNextUpdate(context);
    }

    static void requestRefresh(Context context) {
        context.sendBroadcast(new Intent(context, PrecipitationWidgetProvider.class)
                .setAction(ACTION_REFRESH));
    }

    /** Publish only the needed minute segments, when they belong to the current base forecast. */
    static void publishMinute(Context context, JSONObject response, long fetchedAt,
            double latitude, double longitude) {
        publishMinute(context, response, fetchedAt, latitude, longitude,
                WidgetConfiguration.snapshot(context, ""));
    }

    static void publishMinute(Context context, JSONObject response, long fetchedAt,
            double latitude, double longitude, SharedPreferences prefs) {
        if (response == null || fetchedAt <= 0L) return;
        synchronized (WeatherWidgetSnapshotPublisher.class) {
            String scope = PrecipitationWidgetData.locationScope(latitude, longitude,
                    OpenMeteoConfig.cacheScope(context));
            if (!scope.equals(prefs.getString(KEY_LOCATION_SCOPE, ""))) return;
            String source = OpenMeteoConfig.precipitationCacheScope(context);
            if (scope.equals(prefs.getString(KEY_MINUTE_SCOPE, ""))
                    && source.equals(prefs.getString(KEY_MINUTE_SOURCE, ""))
                    && fetchedAt < prefs.getLong(KEY_MINUTE_FETCHED_AT_MS, 0L)) return;
            JSONArray segments = response.optJSONArray("segments");
            if (segments == null) return;
            JSONArray compact = new JSONArray();
            for (int i = 0; i < Math.min(480, segments.length()); i++) {
                JSONObject segment = segments.optJSONObject(i);
                if (segment == null) continue;
                JSONObject frame = segment.optJSONObject("timeFrame");
                if (frame == null) continue;
                try {
                    JSONObject saved = new JSONObject().put("timeFrame", frame);
                    JSONObject qpf = segment.optJSONObject("qpf");
                    if (!Double.isFinite(PrecipitationWidgetData.quantityMm(qpf))) continue;
                    saved.put("qpf", qpf);
                    compact.put(saved);
                } catch (Exception ignored) { }
            }
            if (compact.length() == 0) return;
            JSONObject saved = new JSONObject();
            try { saved.put("segments", compact); }
            catch (Exception ignored) { return; }
            prefs.edit().putString(KEY_MINUTE_SCOPE, scope)
                    .putString(KEY_MINUTE_SOURCE, source)
                    .putString(KEY_MINUTE_JSON, saved.toString())
                    .putLong(KEY_MINUTE_FETCHED_AT_MS, fetchedAt).apply();
        }
        requestRefresh(context);
    }

    private static void updateAll(Context context) {
        WidgetRefreshManager.reconcile(context);
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int id : widgetIds(context)) manager.updateAppWidget(id, buildViews(context, id, null));
        scheduleNextUpdate(context);
    }

    static int[] widgetIds(Context context) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(
                new ComponentName(context, PrecipitationWidgetProvider.class));
    }

    static RemoteViews buildViews(Context context, int id, Bundle hint) {
        WidgetConfiguration config = WidgetConfiguration.read(context, id);
        CityManagerActivity.LocationSnapshot city = config.city(context);
        Bundle options = hint == null ? AppWidgetManager.getInstance(context).getAppWidgetOptions(id) : hint;
        if (Build.VERSION.SDK_INT >= 31) {
            RemoteViews responsive = Api31Responsive.build(context, options, id, config, city);
            if (responsive != null) return responsive;
        }
        boolean landscape = context.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int width = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320);
        int height = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 144);
        return buildForSize(context, width > 0 ? width : 320, height > 0 ? height : 144,
                id, config, city);
    }

    @androidx.annotation.RequiresApi(31)
    private static final class Api31Responsive {
        static RemoteViews build(Context context, Bundle options, int id,
                WidgetConfiguration config, CityManagerActivity.LocationSnapshot city) {
            java.util.ArrayList<android.util.SizeF> sizes = options.getParcelableArrayList(
                    AppWidgetManager.OPTION_APPWIDGET_SIZES);
            if (sizes == null || sizes.isEmpty()) return null;
            java.util.Map<android.util.SizeF, RemoteViews> variants = new java.util.HashMap<>();
            for (android.util.SizeF size : sizes) {
                if (size == null || size.getWidth() <= 0 || size.getHeight() <= 0) continue;
                variants.put(size, buildForSize(context, Math.round(size.getWidth()), Math.round(size.getHeight()),
                        id, config, city));
                if (variants.size() >= 8) break;
            }
            return variants.isEmpty() ? null : new RemoteViews(variants);
        }
    }

    static RemoteViews buildForSize(Context context, int widthDp, int heightDp, int id,
            WidgetConfiguration config, CityManagerActivity.LocationSnapshot pinned) {
        Context localized = AppLocaleManager.wrap(context);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_precipitation);
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            Intent launch = new Intent(context, MainActivity.class)
                    .setAction(MainActivity.ACTION_WIDGET_PRECIPITATION)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            views.setOnClickPendingIntent(R.id.precip_widget_root,
                    PendingIntent.getActivity(context, id, launch,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }

        SharedPreferences prefs = WidgetConfiguration.snapshot(context, pinned == null ? "" : pinned.id);
        long now = System.currentTimeMillis();
        WeatherWidgetTimeline timeline = new WeatherWidgetTimeline(prefs, now);
        boolean matches = WidgetConfiguration.snapshotMatches(context, prefs, pinned);
        boolean retained = matches && prefs.getBoolean(WeatherWidgetProvider.KEY_HAS_SNAPSHOT, false)
                && timeline.retained;
        String city = pinned == null ? prefs.getString(WeatherWidgetProvider.KEY_CITY, "") : pinned.name;
        config.setText(views, R.id.precip_widget_city, city == null ? "" : city);
        views.setInt(R.id.precip_widget_city, "setMaxWidth", Math.round(
                Math.min(100f, (widthDp - 40) * 0.30f) * context.getResources().getDisplayMetrics().density));
        String condition = retained
                ? prefs.getString(WeatherWidgetProvider.KEY_CONDITION_TYPE, "cloudy") : "cloudy";
        boolean daytime = !retained || prefs.getBoolean(WeatherWidgetProvider.KEY_DAYTIME, true);
        JSONObject currentHour = retained ? timeline.hours.optJSONObject(timeline.currentIndex) : null;
        if (currentHour != null) {
            condition = currentHour.optString("conditionType", condition);
            daytime = currentHour.optBoolean("daytime", daytime);
        }
        WidgetBackground.apply(localized, views, R.id.precip_widget_scene, condition, daytime);

        JSONObject minutes = matches ? freshMinutes(context, prefs, now) : null;
        PrecipitationWidgetData data = new PrecipitationWidgetData(
                retained ? timeline.hours : null, minutes, now);
        String resolution = UiTranslations.text(localized,
                data.hasMinuteDetail ? (data.hasHourlyDetail ? "Minute + hourly" : "Minute") : "Hourly");
        boolean complete = data.knownBins == PrecipitationWidgetData.BIN_COUNT;
        String summary = data.knownBins == 0 ? UiTranslations.text(localized, "No forecast available")
                : PrecipitationWidgetData.amountLabel(data.totalMm) + " mm · "
                        + UiTranslations.text(localized, complete ? "Total" : "Partial forecast")
                        + " · " + UiTranslations.text(localized, "10 min bars");
        String title = UiTranslations.text(localized, "Next 6 hours");
        if (config.showDetails && data.knownBins > 0) {
            String amount = PrecipitationWidgetData.amountLabel(data.totalMm) + " mm";
            title += " · " + (complete ? amount : UiTranslations.text(localized, "Partial") + " " + amount);
        }
        config.setText(views, R.id.precip_widget_title, title);
        ZoneId zone = pinned != null ? timeline.zone : ZoneId.systemDefault();
        String[] labels = config.showForecast ? new String[]{UiTranslations.text(localized, "Now"),
                WeatherTimeFormat.time(localized, Instant.ofEpochMilli(now + 3L * 3_600_000L),
                        zone),
                WeatherTimeFormat.time(localized, Instant.ofEpochMilli(now + 6L * 3_600_000L),
                        zone)} : null;
        try {
            Bitmap chart = PrecipitationWidgetChart.render(data, widthDp - 28, heightDp - 44,
                    labels, UiTranslations.text(localized, "No forecast available"), config.textScalePercent / 100f);
            views.setImageViewBitmap(R.id.precip_widget_chart, chart);
        } catch (OutOfMemoryError ignored) {
            // Never substitute sample rain for a missing real forecast.
            views.setImageViewResource(R.id.precip_widget_chart, 0);
        }
        views.setContentDescription(R.id.precip_widget_root,
                UiTranslations.text(localized, "Next 6 hours") + " · " + (city == null ? "" : city)
                        + " · " + summary + " · " + resolution
                        + (data.knownBins > 0 && !complete
                                ? " · " + UiTranslations.text(localized, "Gaps mean unavailable data") : ""));
        return views;
    }

    static JSONObject freshMinutes(Context context, SharedPreferences prefs, long now) {
        if (!OpenMeteoConfig.precipitationCacheScope(context).equals(
                prefs.getString(KEY_MINUTE_SOURCE, ""))) return null;
        String scope = prefs.getString(KEY_LOCATION_SCOPE, "");
        if (scope == null || scope.isEmpty()
                || !scope.equals(prefs.getString(KEY_MINUTE_SCOPE, ""))) return null;
        long fetchedAt = prefs.getLong(KEY_MINUTE_FETCHED_AT_MS, 0L);
        if (fetchedAt <= 0L || fetchedAt > now || now - fetchedAt >= MINUTE_MAX_AGE_MILLIS)
            return null;
        try { return new JSONObject(prefs.getString(KEY_MINUTE_JSON, "")); }
        catch (Exception ignored) { return null; }
    }

    private static PendingIntent clockUpdateIntent(Context context) {
        return PendingIntent.getBroadcast(context, 4208,
                new Intent(context, PrecipitationWidgetProvider.class).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void cancelClockUpdate(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.cancel(clockUpdateIntent(context));
    }

    private static void scheduleNextUpdate(Context context) {
        cancelClockUpdate(context);
        int[] ids = widgetIds(context);
        if (ids.length == 0) return;
        long now = System.currentTimeMillis();
        long next = (now / PrecipitationWidgetData.BIN_MILLIS + 1L) * PrecipitationWidgetData.BIN_MILLIS;
        for (int id : ids) {
            CityManagerActivity.LocationSnapshot city = WidgetConfiguration.read(context, id).city(context);
            SharedPreferences prefs = WidgetConfiguration.snapshot(context, city == null ? "" : city.id);
            WeatherWidgetTimeline timeline = new WeatherWidgetTimeline(prefs, now);
            if (!prefs.getBoolean(WeatherWidgetProvider.KEY_HAS_SNAPSHOT, false) || !timeline.retained
                    || !WidgetConfiguration.snapshotMatches(context, prefs, city)) {
                next = Math.min(next, now + 5L * 60_000L);
                continue;
            }
            next = Math.min(next, timeline.expiresAt);
            long refreshAt = timeline.fetchedAt + WidgetRefreshPolicy.REFRESH_MILLIS;
            next = Math.min(next, refreshAt > now ? refreshAt : now + 5L * 60_000L);
            if (freshMinutes(context, prefs, now) != null) {
                next = Math.min(next, prefs.getLong(KEY_MINUTE_FETCHED_AT_MS, 0L) + MINUTE_MAX_AGE_MILLIS);
            }
        }
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        // A lightweight, inexact redraw. The provider never starts a network request.
        if (alarms != null && next > now)
            alarms.set(AlarmManager.RTC, next + 1000L, clockUpdateIntent(context));
    }
}
