package com.zwerk.weather;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.app.AlarmManager;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Snapshot-rendering home-screen widget.
 *
 * The provider deliberately never reads the Weather API key and never performs network I/O. The
 * activity and network-constrained background worker publish the compact snapshot schema in
 * {@link #PREFS_NAME}; this provider only renders it and schedules recovery.
 */
public class WeatherWidgetProvider extends AppWidgetProvider {
    public static final String PREFS_NAME = "WEATHER_WIDGET_SNAPSHOT";
    public static final String KEY_HAS_SNAPSHOT = "has_snapshot";
    public static final String KEY_CITY = "city";
    public static final String KEY_TEMPERATURE = "temperature";
    public static final String KEY_UNIT = "unit";
    public static final String KEY_CONDITION = "condition";
    public static final String KEY_CONDITION_TYPE = "condition_type";
    public static final String KEY_DAYTIME = "daytime";
    public static final String KEY_ADVICE = "advice";
    public static final String KEY_UPDATED_EPOCH_MS = "updated_epoch_ms";
    public static final String KEY_DAILY_HIGH = "daily_high";
    public static final String KEY_DAILY_LOW = "daily_low";
    public static final String KEY_HOURLY_JSON = "hourly_json";
    public static final String KEY_DAILY_JSON = "daily_json";
    public static final String KEY_ZONE = "zone";
    public static final String KEY_OPEN_METEO = "open_meteo";
    public static final String KEY_FALLBACK = "fallback";
    public static final String KEY_OBSERVATION_EPOCH_MS = "observation_epoch_ms";
    static final String PREF_TRANSPARENCY = "widget_transparency";
    static final String PREF_GRADIENT = "widget_gradient";
    static final String PREF_COLOUR = "widget_colour";
    static final String PREF_CUSTOM_COLOUR = "widget_custom_colour";

    private static final String ACTION_REFRESH =
            "com.zwerk.weather.action.REFRESH_WEATHER_WIDGET";

    private static final int NORMAL_HEIGHT_DP = 118;
    private static final int ROOMY_HEIGHT_DP = 152;
    private static final int TALL_HEIGHT_DP = 192;

    private enum WidgetMode {
        MINI,
        SMALL,
        NORMAL,
        ROOMY,
        TALL
    }

    private static final int[] NORMAL_HOUR_TIME_IDS = {
            R.id.widget_hour_time_0,
            R.id.widget_hour_time_1,
            R.id.widget_hour_time_2,
            R.id.widget_hour_time_3,
            R.id.widget_hour_time_4,
            R.id.widget_hour_time_5
    };
    private static final int[] NORMAL_HOUR_GLYPH_IDS = {
            R.id.widget_hour_glyph_0,
            R.id.widget_hour_glyph_1,
            R.id.widget_hour_glyph_2,
            R.id.widget_hour_glyph_3,
            R.id.widget_hour_glyph_4,
            R.id.widget_hour_glyph_5
    };
    private static final int[] NORMAL_HOUR_TEMP_IDS = {
            R.id.widget_hour_temp_0,
            R.id.widget_hour_temp_1,
            R.id.widget_hour_temp_2,
            R.id.widget_hour_temp_3,
            R.id.widget_hour_temp_4,
            R.id.widget_hour_temp_5
    };

    private static final int[] TALL_HOUR_TIME_IDS = {
            R.id.widget_hour_time_0,
            R.id.widget_hour_time_1,
            R.id.widget_hour_time_2,
            R.id.widget_hour_time_3,
            R.id.widget_hour_time_4,
            R.id.widget_hour_time_5,
            R.id.widget_hour_time_6,
            R.id.widget_hour_time_7
    };
    private static final int[] TALL_HOUR_GLYPH_IDS = {
            R.id.widget_hour_glyph_0,
            R.id.widget_hour_glyph_1,
            R.id.widget_hour_glyph_2,
            R.id.widget_hour_glyph_3,
            R.id.widget_hour_glyph_4,
            R.id.widget_hour_glyph_5,
            R.id.widget_hour_glyph_6,
            R.id.widget_hour_glyph_7
    };
    private static final int[] TALL_HOUR_TEMP_IDS = {
            R.id.widget_hour_temp_0,
            R.id.widget_hour_temp_1,
            R.id.widget_hour_temp_2,
            R.id.widget_hour_temp_3,
            R.id.widget_hour_temp_4,
            R.id.widget_hour_temp_5,
            R.id.widget_hour_temp_6,
            R.id.widget_hour_temp_7
    };

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        DiagnosticLog.event(DiagnosticLog.Area.WIDGET, DiagnosticLog.Event.WIDGET_UPDATE);
        WidgetRefreshManager.reconcile(context);
        for (int appWidgetId : appWidgetIds) {
            appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId));
        }
        scheduleNextUpdate(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
                                          int appWidgetId, Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        DiagnosticLog.event(DiagnosticLog.Area.WIDGET, DiagnosticLog.Event.WIDGET_RESIZE);
        WidgetRefreshManager.reconcile(context);
        appWidgetManager.updateAppWidget(
                appWidgetId,
                buildViews(context, appWidgetId, newOptions));
        scheduleNextUpdate(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent != null && (ACTION_REFRESH.equals(intent.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())
                || Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_TIME_CHANGED.equals(intent.getAction())
                || Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())
                || Intent.ACTION_DATE_CHANGED.equals(intent.getAction()))) {
            updateAll(context);
        }
    }

    public static void requestRefresh(Context context) {
        Intent refresh = new Intent(context, WeatherWidgetProvider.class)
                .setAction(ACTION_REFRESH);
        context.sendBroadcast(refresh);
        PrecipitationWidgetProvider.requestRefresh(context);
    }

    @Override
    public void onDisabled(Context context) {
        scheduleNextUpdate(context);
        WidgetRefreshManager.reconcile(context);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) { scheduleNextUpdate(context); }

    private static void updateAll(Context context) {
        WidgetRefreshManager.reconcile(context);
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int id : widgetIds(context)) {
            manager.updateAppWidget(id, buildViews(context, id));
        }
        scheduleNextUpdate(context);
    }

    static int[] widgetIds(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        Class<?>[] providers = {WeatherWidgetProvider.class, WeatherSmallWidgetProvider.class,
                WeatherMiniWidgetProvider.class};
        int[][] groups = new int[providers.length][];
        int total = 0;
        for (int i = 0; i < providers.length; i++) {
            groups[i] = manager.getAppWidgetIds(new ComponentName(context, providers[i]));
            total += groups[i].length;
        }
        int[] ids = new int[total];
        int offset = 0;
        for (int[] group : groups) {
            System.arraycopy(group, 0, ids, offset, group.length);
            offset += group.length;
        }
        return ids;
    }

    private static PendingIntent clockUpdateIntent(Context context) {
        return PendingIntent.getBroadcast(context, 4108,
                new Intent(context, WeatherWidgetProvider.class).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void cancelClockUpdate(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.cancel(clockUpdateIntent(context));
    }

    private static void scheduleNextUpdate(Context context) {
        cancelClockUpdate(context);
        int[] ids = widgetIds(context);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (ids.length == 0) return;
        long now = System.currentTimeMillis();
        WeatherWidgetTimeline timeline = new WeatherWidgetTimeline(prefs, now);
        long next = now + 5L * 60_000L;
        if (prefs.getBoolean(KEY_HAS_SNAPSHOT, false) && timeline.retained) {
            next = timeline.nextChange(now);
            long refreshAt = timeline.fetchedAt + WidgetRefreshPolicy.REFRESH_MILLIS;
            next = Math.min(next, refreshAt > now ? refreshAt : now + 5L * 60_000L);
        }
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        // A non-wakeup, inexact local redraw. Android may defer it while the device sleeps;
        // the standard widget update and clock-change broadcasts also rebase the snapshot.
        if (alarms != null && next > 0L) alarms.set(AlarmManager.RTC, next + 1000L, clockUpdateIntent(context));
    }

    /**
     * API 31+ receives responsive variants in one RemoteViews. Older releases select from the
     * launcher option range and repaint on every option change.
     */
    private static RemoteViews buildViews(Context context, int appWidgetId) {
        return buildViews(context, appWidgetId, null);
    }

    private static RemoteViews buildViews(Context context, int appWidgetId, Bundle optionsHint) {
        SharedPreferences snapshot = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!snapshot.getBoolean(KEY_HAS_SNAPSHOT, false)) {
            DiagnosticLog.event(DiagnosticLog.Area.WIDGET, DiagnosticLog.Event.SNAPSHOT_MISSING);
        } else if (!ForecastClock.withinAge(snapshot.getLong(KEY_UPDATED_EPOCH_MS, 0L),
                System.currentTimeMillis(), ForecastClock.retentionMillis(snapshot.getBoolean(KEY_OPEN_METEO, false)))) {
            DiagnosticLog.event(DiagnosticLog.Area.WIDGET, DiagnosticLog.Event.CACHE_EXPIRED);
        }
        Context localized = AppLocaleManager.wrap(context);
        RemoteViews mini = buildMode(localized, WidgetMode.MINI);
        RemoteViews small = buildMode(localized, WidgetMode.SMALL);
        RemoteViews normal = buildMode(localized, WidgetMode.NORMAL);
        RemoteViews roomy = buildMode(localized, WidgetMode.ROOMY);
        RemoteViews tall = buildMode(localized, WidgetMode.TALL);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return Api31Responsive.remoteViews(mini, small, normal, roomy, tall);
            } catch (Throwable ignored) {
                // A launcher may reject responsive RemoteViews; the options fallback is complete.
            }
        }

        Bundle options = optionsHint != null ? optionsHint
                : AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId);
        WidgetMode mode = modeForOptions(options);
        if (mode == WidgetMode.TALL) return tall;
        if (mode == WidgetMode.ROOMY) return roomy;
        if (mode == WidgetMode.NORMAL) return normal;
        if (mode == WidgetMode.SMALL) return small;
        return mini;
    }

    private static WidgetMode modeForOptions(Bundle options) {
        if (options == null) return WidgetMode.NORMAL;
        int min = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        int max = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        int available = min > 0 && max > 0 ? Math.min(min, max) : Math.max(min, max);
        int minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0);
        int width = minWidth > 0 && maxWidth > 0 ? Math.min(minWidth, maxWidth) : Math.max(minWidth, maxWidth);
        if (available <= 0) return WidgetMode.NORMAL;
        if (available >= TALL_HEIGHT_DP && (width <= 0 || width >= 280)) return WidgetMode.TALL;
        if (available >= ROOMY_HEIGHT_DP && (width <= 0 || width >= 280)) return WidgetMode.ROOMY;
        if (available >= NORMAL_HEIGHT_DP && (width <= 0 || width >= 240)) return WidgetMode.NORMAL;
        return available >= NORMAL_HEIGHT_DP ? WidgetMode.SMALL : WidgetMode.MINI;
    }

    private static RemoteViews buildMode(Context context, WidgetMode mode) {
        int layout;
        if (mode == WidgetMode.MINI) layout = R.layout.widget_weather_mini;
        else if (mode == WidgetMode.SMALL) layout = R.layout.widget_weather_small;
        else if (mode == WidgetMode.TALL) layout = R.layout.widget_weather_tall;
        else if (mode == WidgetMode.ROOMY) layout = R.layout.widget_weather_roomy;
        else layout = R.layout.widget_weather;

        RemoteViews views = new RemoteViews(context.getPackageName(), layout);
        bindLaunchIntent(context, views, mode);

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        WeatherWidgetTimeline timeline = new WeatherWidgetTimeline(prefs, now);
        if (!prefs.getBoolean(KEY_HAS_SNAPSHOT, false) || !timeline.retained) {
            renderPlaceholder(context, views, mode);
            applyAppearance(context, views, mode, "cloudy", true);
            return views;
        }

        String city = clean(prefs.getString(KEY_CITY, "Zwerk Weather"), "Zwerk Weather");
        int temperature = prefs.getInt(KEY_TEMPERATURE, Integer.MIN_VALUE);
        String unit = clean(prefs.getString(KEY_UNIT, "°C"), "°C");
        String condition = clean(prefs.getString(KEY_CONDITION, "Forecast"), "Forecast");
        String conditionType = clean(prefs.getString(KEY_CONDITION_TYPE, condition), condition);
        boolean daytime = prefs.getBoolean(KEY_DAYTIME, true);
        int high = prefs.getInt(KEY_DAILY_HIGH, Integer.MIN_VALUE);
        int low = prefs.getInt(KEY_DAILY_LOW, Integer.MIN_VALUE);
        long observation = prefs.getLong(KEY_OBSERVATION_EPOCH_MS, timeline.fetchedAt);
        JSONObject currentHour = timeline.currentHour();
        boolean advanced = currentHour != null && currentHour.optLong("start", 0L) > observation;
        if (advanced) {
            temperature = currentHour.optInt("temperature", Integer.MIN_VALUE);
            condition = clean(currentHour.optString("condition", "Forecast"), "Forecast");
            conditionType = clean(currentHour.optString("conditionType", condition), condition);
            daytime = currentHour.optBoolean("daytime", true);
        } else if (currentHour == null && !ForecastClock.withinAge(observation, now, ForecastClock.FRESH_MILLIS)) {
            temperature = Integer.MIN_VALUE;
            condition = "Forecast";
            conditionType = "cloudy";
        }
        JSONObject today = timeline.today(now);
        if (today != null) {
            high = today.optInt("high", Integer.MIN_VALUE);
            low = today.optInt("low", Integer.MIN_VALUE);
        } else if (!Instant.ofEpochMilli(observation).atZone(timeline.zone).toLocalDate()
                .equals(Instant.ofEpochMilli(now).atZone(timeline.zone).toLocalDate())) {
            high = Integer.MIN_VALUE;
            low = Integer.MIN_VALUE;
        }
        String advice = clean(prefs.getString(KEY_ADVICE, "Open Zwerk Weather for details"),
                "Open Zwerk Weather for details");
        String localizedAdvice = localizeAdvice(context, advice, observation, timeline.zone);
        String savedAt = WeatherTimeFormat.saved(context, timeline.fetchedAt, ZoneId.systemDefault());
        if (advanced || prefs.getBoolean(KEY_FALLBACK, false)
                || !ForecastClock.withinAge(timeline.fetchedAt, now, ForecastClock.FRESH_MILLIS)) {
            localizedAdvice = context.getString(R.string.forecast_saved_status, savedAt);
        }

        views.setTextViewText(R.id.widget_city, city);
        views.setTextViewText(R.id.widget_temperature, temperatureLabel(temperature, unit));
        String localizedCondition = UiTranslations.text(context, condition);
        views.setTextViewText(R.id.widget_condition, localizedCondition);
        views.setImageViewResource(R.id.widget_glyph, iconFor(conditionType, daytime));
        views.setTextViewText(R.id.widget_high_low, highLowLabel(context, high, low));
        views.setViewVisibility(R.id.widget_hour_strip,
                hasHourlyStrip(mode) ? View.VISIBLE : View.GONE);

        if (mode == WidgetMode.NORMAL || mode == WidgetMode.ROOMY) {
            bindHourlyStrip(
                    context,
                    views,
                    timeline, now,
                    NORMAL_HOUR_TIME_IDS,
                    NORMAL_HOUR_GLYPH_IDS,
                    NORMAL_HOUR_TEMP_IDS);
        } else if (mode == WidgetMode.TALL) {
            views.setTextViewText(R.id.widget_advice, localizedAdvice);
            bindHourlyStrip(
                    context,
                    views,
                    timeline, now,
                    TALL_HOUR_TIME_IDS,
                    TALL_HOUR_GLYPH_IDS,
                    TALL_HOUR_TEMP_IDS);
        }

        String range = highLowLabel(context, high, low);
        views.setContentDescription(R.id.widget_root,
                String.format(Locale.getDefault(), "%s, %s%s, %s, %s. %s",
                        city,
                        temperature == Integer.MIN_VALUE ? "" : Integer.toString(temperature),
                        temperature == Integer.MIN_VALUE ? "" : unit,
                        localizedCondition,
                        range,
                        mode == WidgetMode.TALL ? localizedAdvice : ""));
        applyAppearance(context, views, mode, conditionType, daytime);
        return views;
    }

    private static void applyAppearance(Context context, RemoteViews views, WidgetMode mode,
            String conditionType, boolean daytime) {
        WidgetBackground.apply(context, views, R.id.widget_scene, conditionType, daytime);
        views.setInt(R.id.widget_hour_strip, "setBackgroundColor", Color.TRANSPARENT);
        views.setViewVisibility(R.id.widget_hour_strip,
                hasHourlyStrip(mode) ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.widget_glyph, View.VISIBLE);
        if (mode == WidgetMode.TALL) views.setViewVisibility(R.id.widget_advice,
                View.VISIBLE);
    }

    private static boolean hasHourlyStrip(WidgetMode mode) {
        return mode != WidgetMode.MINI && mode != WidgetMode.SMALL;
    }

    private static String localizeAdvice(Context context, String advice, long observation, ZoneId zone) {
        String marker = " • Updated ";
        int split = advice.indexOf(marker);
        String time = WeatherTimeFormat.time(context, Instant.ofEpochMilli(observation), zone);
        if (split >= 0) {
            return UiTranslations.text(context, advice.substring(0, split)) + " • "
                    + UiTranslations.text(context, "Updated") + " " + time;
        }
        if (advice.startsWith("Updated ")) {
            return UiTranslations.text(context, "Updated") + " " + time;
        }
        return UiTranslations.text(context, advice);
    }

    private static void bindHourlyStrip(
            Context context,
            RemoteViews views,
            WeatherWidgetTimeline timeline, long now,
            int[] timeIds,
            int[] glyphIds,
            int[] tempIds) {
        List<WeatherWidgetTimeline.Entry> entries = timeline.visibleEntries(now, timeIds.length);

        int count = Math.min(timeIds.length, Math.min(glyphIds.length, tempIds.length));
        for (int i = 0; i < count; i++) {
            WeatherWidgetTimeline.Entry entry = i < entries.size() ? entries.get(i) : null;
            if (entry == null) {
                views.setViewVisibility(timeIds[i], View.INVISIBLE);
                views.setViewVisibility(glyphIds[i], View.INVISIBLE);
                views.setViewVisibility(tempIds[i], View.INVISIBLE);
                continue;
            }

            JSONObject hour = entry.hour;
            String time = WeatherTimeFormat.hour(context, entry.time, ZoneId.systemDefault());
            int temperature = hour.optInt("temperature", Integer.MIN_VALUE);
            String condition = clean(hour.optString("condition", "Forecast"), "Forecast");
            String conditionType = clean(hour.optString("conditionType", condition), condition);
            boolean daytime = hour.optBoolean("daytime", true);

            views.setViewVisibility(timeIds[i], View.VISIBLE);
            views.setViewVisibility(glyphIds[i], View.VISIBLE);
            views.setViewVisibility(tempIds[i], View.VISIBLE);
            views.setTextViewText(timeIds[i], !entry.isSunEvent() && ForecastClock.contains(hour.optLong("start", 0L),
                    hour.optLong("end", 0L), now)
                    ? UiTranslations.text(context, "Now") : time);
            views.setImageViewResource(glyphIds[i], entry.isSunEvent()
                    ? ("sunrise".equals(entry.sunEvent) ? R.drawable.widget_ic_sunrise : R.drawable.widget_ic_sunset)
                    : iconFor(conditionType, daytime));
            views.setContentDescription(timeIds[i], entry.isSunEvent()
                    ? UiTranslations.text(context, "sunrise".equals(entry.sunEvent) ? "Sunrise" : "Sunset")
                            + " " + time
                    : null);
            views.setTextViewText(tempIds[i],
                    temperature == Integer.MIN_VALUE ? "—" : temperature + "°");
        }
    }

    private static void renderPlaceholder(Context context, RemoteViews views, WidgetMode mode) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String city = clean(prefs.getString(KEY_CITY, "Zwerk Weather"), "Zwerk Weather");
        views.setTextViewText(R.id.widget_city, city);
        views.setTextViewText(R.id.widget_temperature, temperatureLabel(Integer.MIN_VALUE, "°"));
        views.setTextViewText(R.id.widget_condition, UiTranslations.text(context, "Refreshing…"));
        views.setImageViewResource(R.id.widget_glyph, R.drawable.widget_ic_cloud);
        views.setTextViewText(R.id.widget_high_low, "—");
        views.setViewVisibility(R.id.widget_hour_strip,
                hasHourlyStrip(mode) ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.widget_glyph, View.VISIBLE);

        if (mode == WidgetMode.NORMAL || mode == WidgetMode.ROOMY) {
            bindPlaceholderHours(context, views, NORMAL_HOUR_TIME_IDS,
                    NORMAL_HOUR_GLYPH_IDS, NORMAL_HOUR_TEMP_IDS);
        } else if (mode == WidgetMode.TALL) {
            views.setTextViewText(R.id.widget_advice,
                    UiTranslations.text(context, "Refreshing forecast…"));
            bindPlaceholderHours(context, views, TALL_HOUR_TIME_IDS,
                    TALL_HOUR_GLYPH_IDS, TALL_HOUR_TEMP_IDS);
        }
        views.setContentDescription(R.id.widget_root, city + ". "
                + UiTranslations.text(context, "Refreshing forecast…"));
    }

    private static void bindPlaceholderHours(
            Context context,
            RemoteViews views,
            int[] timeIds,
            int[] glyphIds,
            int[] tempIds) {
        int count = Math.min(timeIds.length, Math.min(glyphIds.length, tempIds.length));
        for (int i = 0; i < count; i++) {
            views.setViewVisibility(timeIds[i], View.VISIBLE);
            views.setViewVisibility(glyphIds[i], View.VISIBLE);
            views.setViewVisibility(tempIds[i], View.VISIBLE);
            views.setTextViewText(timeIds[i],
                    i == 0 ? UiTranslations.text(context, "Now") : "—");
            views.setImageViewResource(glyphIds[i], R.drawable.widget_ic_cloud);
            views.setTextViewText(tempIds[i], "—");
        }
    }

    private static void bindLaunchIntent(Context context, RemoteViews views, WidgetMode mode) {
        Intent launch = new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                context,
                4107,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pending);
        if (mode == WidgetMode.TALL) {
            Intent refresh = new Intent(launch).setAction(MainActivity.ACTION_WIDGET_REFRESH);
            PendingIntent refreshPending = PendingIntent.getActivity(context, 4109, refresh,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widget_advice, refreshPending);
        }
    }

    private static int iconFor(String condition, boolean daytime) {
        String key = conditionKey(condition);
        switch (key) {
            case "thunder":
                return R.drawable.widget_ic_thunder;
            case "rain":
                return R.drawable.widget_ic_rain;
            case "snow":
                return R.drawable.widget_ic_snow;
            case "fog":
                return R.drawable.widget_ic_fog;
            case "partly":
                return daytime ? R.drawable.widget_ic_partly_day
                        : R.drawable.widget_ic_partly_night;
            case "clear":
                return daytime ? R.drawable.widget_ic_clear_day
                        : R.drawable.widget_ic_clear_night;
            case "cloud":
            default:
                return R.drawable.widget_ic_cloud;
        }
    }

    static String conditionKey(String condition) {
        String c = condition == null ? "" : condition.toLowerCase(Locale.ROOT);
        if (c.contains("thunder") || c.contains("storm") || c.contains("lightning")) return "thunder";
        if (c.contains("snow") || c.contains("sleet") || c.contains("ice") || c.contains("flurr")) return "snow";
        if (c.contains("rain") || c.contains("drizzle") || c.contains("shower")) return "rain";
        if (c.contains("fog") || c.contains("mist") || c.contains("haze") || c.contains("smoke")) return "fog";
        if (c.contains("partly") || c.contains("mostly") || c.contains("scattered")) return "partly";
        if (c.contains("clear") || c.contains("sunny")) return "clear";
        return "cloud";
    }

    private static String highLowLabel(Context context, int high, int low) {
        if (high == Integer.MIN_VALUE && low == Integer.MIN_VALUE) {
            return UiTranslations.text(context, "High") + " / "
                    + UiTranslations.text(context, "Low") + " —";
        }
        String highPart = high == Integer.MIN_VALUE ? "—" : high + "°";
        String lowPart = low == Integer.MIN_VALUE ? "—" : low + "°";
        return "↑ " + highPart + "   ↓ " + lowPart;
    }

    private static String compactUnit(String unit) {
        if (unit == null) return "°";
        String normalized = unit.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("F")) return "°F";
        if (normalized.contains("C")) return "°C";
        return unit.trim().isEmpty() ? "°" : unit.trim();
    }

    private static CharSequence temperatureLabel(int temperature, String unit) {
        String number = temperature == Integer.MIN_VALUE ? "—" : Integer.toString(temperature);
        SpannableString label = new SpannableString(number + compactUnit(unit));
        // One text run shares a baseline and keeps the unit next to the number at every size.
        label.setSpan(new RelativeSizeSpan(0.48f),
                number.length(), label.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new TypefaceSpan("sans-serif-medium"), number.length(), label.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new ForegroundColorSpan(Color.argb(232, 255, 255, 255)),
                number.length(), label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return label;
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    /** API-31-only references stay isolated from the minSdk 28 provider class verifier. */
    private static final class Api31Responsive {
        private Api31Responsive() {}

        @TargetApi(31)
        static RemoteViews remoteViews(RemoteViews mini, RemoteViews small, RemoteViews normal,
                RemoteViews roomy, RemoteViews tall) {
            Map<SizeF, RemoteViews> variants = new HashMap<>();
            variants.put(new SizeF(109f, 56f), mini);
            variants.put(new SizeF(109f, NORMAL_HEIGHT_DP), small);
            variants.put(new SizeF(240f, NORMAL_HEIGHT_DP), normal);
            variants.put(new SizeF(280f, ROOMY_HEIGHT_DP), roomy);
            variants.put(new SizeF(280f, TALL_HEIGHT_DP), tall);
            return new RemoteViews(variants);
        }
    }
}
