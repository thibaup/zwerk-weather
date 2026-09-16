package com.zwerk.weather;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Snapshot-only home-screen widget.
 *
 * The provider deliberately never reads the Weather API key and never performs network I/O. The
 * main activity owns forecast loading and stores the existing compact snapshot schema in
 * {@link #PREFS_NAME}; this provider only renders that snapshot.
 */
public class WeatherWidgetProvider extends AppWidgetProvider {
    public static final String PREFS_NAME = "WEATHER_WIDGET_SNAPSHOT";
    public static final String KEY_HAS_SNAPSHOT = "has_snapshot";
    public static final String KEY_CITY = "city";
    public static final String KEY_TEMPERATURE = "temperature";
    public static final String KEY_UNIT = "unit";
    public static final String KEY_CONDITION = "condition";
    public static final String KEY_DAYTIME = "daytime";
    public static final String KEY_ADVICE = "advice";
    public static final String KEY_UPDATED_EPOCH_MS = "updated_epoch_ms";
    public static final String KEY_DAILY_HIGH = "daily_high";
    public static final String KEY_DAILY_LOW = "daily_low";
    public static final String KEY_HOURLY_JSON = "hourly_json";

    private static final String ACTION_REFRESH =
            "com.zwerk.weather.action.REFRESH_WEATHER_WIDGET";

    // Two rows on launchers using the traditional cell formula are close to 110dp. The normal
    // layout itself is designed to remain non-overlapping at 118dp; taller allocations get a
    // dedicated layout instead of stretching a tiny fixed card through empty space.
    private static final int NORMAL_HEIGHT_DP = 118;
    private static final int TALL_HEIGHT_DP = 176;

    private enum WidgetMode {
        COMPACT,
        NORMAL,
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
        for (int appWidgetId : appWidgetIds) {
            appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId));
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
                                          int appWidgetId, Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        appWidgetManager.updateAppWidget(
                appWidgetId,
                buildViews(context, appWidgetId, newOptions));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            updateAll(context);
        }
    }

    public static void requestRefresh(Context context) {
        Intent refresh = new Intent(context, WeatherWidgetProvider.class)
                .setAction(ACTION_REFRESH);
        context.sendBroadcast(refresh);
    }

    private static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName provider = new ComponentName(context, WeatherWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(provider);
        for (int id : ids) {
            manager.updateAppWidget(id, buildViews(context, id));
        }
    }

    /**
     * API 31+ receives responsive variants in one RemoteViews. Older releases select from the
     * launcher option range and repaint on every option change.
     */
    private static RemoteViews buildViews(Context context, int appWidgetId) {
        return buildViews(context, appWidgetId, null);
    }

    private static RemoteViews buildViews(Context context, int appWidgetId, Bundle optionsHint) {
        RemoteViews compact = buildMode(context, WidgetMode.COMPACT);
        RemoteViews normal = buildMode(context, WidgetMode.NORMAL);
        RemoteViews tall = buildMode(context, WidgetMode.TALL);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return Api31Responsive.remoteViews(compact, normal, tall);
            } catch (Throwable ignored) {
                // A launcher may reject responsive RemoteViews; the options fallback is complete.
            }
        }

        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        Bundle options = optionsHint != null ? optionsHint : manager.getAppWidgetOptions(appWidgetId);
        WidgetMode mode = modeForOptions(options);
        if (mode == WidgetMode.TALL) return tall;
        if (mode == WidgetMode.NORMAL) return normal;
        return compact;
    }

    private static WidgetMode modeForOptions(Bundle options) {
        if (options == null) return WidgetMode.NORMAL;
        int min = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        int max = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        int available = Math.max(min, max);
        if (available <= 0) return WidgetMode.NORMAL;
        if (available >= TALL_HEIGHT_DP) return WidgetMode.TALL;
        if (available >= NORMAL_HEIGHT_DP) return WidgetMode.NORMAL;
        return WidgetMode.COMPACT;
    }

    private static RemoteViews buildMode(Context context, WidgetMode mode) {
        int layout;
        if (mode == WidgetMode.COMPACT) layout = R.layout.widget_weather_compact;
        else if (mode == WidgetMode.TALL) layout = R.layout.widget_weather_tall;
        else layout = R.layout.widget_weather;

        RemoteViews views = new RemoteViews(context.getPackageName(), layout);
        bindLaunchIntent(context, views);

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_HAS_SNAPSHOT, false)) {
            renderPlaceholder(views, mode);
            return views;
        }

        String city = clean(prefs.getString(KEY_CITY, "Zwerk Weather"), "Zwerk Weather");
        int temperature = prefs.getInt(KEY_TEMPERATURE, Integer.MIN_VALUE);
        String unit = clean(prefs.getString(KEY_UNIT, "°C"), "°C");
        String condition = clean(prefs.getString(KEY_CONDITION, "Forecast"), "Forecast");
        boolean daytime = prefs.getBoolean(KEY_DAYTIME, true);
        int high = prefs.getInt(KEY_DAILY_HIGH, Integer.MIN_VALUE);
        int low = prefs.getInt(KEY_DAILY_LOW, Integer.MIN_VALUE);
        String advice = clean(prefs.getString(KEY_ADVICE, "Open Zwerk Weather for details"),
                "Open Zwerk Weather for details");

        views.setTextViewText(R.id.widget_city, city);
        views.setTextViewText(R.id.widget_temperature,
                temperature == Integer.MIN_VALUE ? "—" : Integer.toString(temperature));
        views.setTextViewText(R.id.widget_unit, compactUnit(unit));
        views.setTextViewText(R.id.widget_condition, condition);
        views.setImageViewResource(R.id.widget_glyph, iconFor(condition, daytime));
        views.setTextViewText(R.id.widget_high_low, highLowLabel(high, low));
        views.setImageViewResource(R.id.widget_scene, sceneFor(condition, daytime));
        views.setViewVisibility(R.id.widget_hour_strip,
                mode == WidgetMode.COMPACT ? View.GONE : View.VISIBLE);
        views.setViewVisibility(R.id.widget_glyph,
                mode == WidgetMode.COMPACT ? View.GONE : View.VISIBLE);

        if (mode == WidgetMode.NORMAL) {
            bindHourlyStrip(
                    views,
                    prefs.getString(KEY_HOURLY_JSON, ""),
                    NORMAL_HOUR_TIME_IDS,
                    NORMAL_HOUR_GLYPH_IDS,
                    NORMAL_HOUR_TEMP_IDS);
        } else if (mode == WidgetMode.TALL) {
            views.setTextViewText(R.id.widget_advice, advice);
            bindHourlyStrip(
                    views,
                    prefs.getString(KEY_HOURLY_JSON, ""),
                    TALL_HOUR_TIME_IDS,
                    TALL_HOUR_GLYPH_IDS,
                    TALL_HOUR_TEMP_IDS);
        }

        String range = highLowLabel(high, low);
        views.setContentDescription(R.id.widget_root,
                String.format(Locale.getDefault(), "%s, %s%s, %s, %s. %s",
                        city,
                        temperature == Integer.MIN_VALUE ? "" : Integer.toString(temperature),
                        temperature == Integer.MIN_VALUE ? "" : unit,
                        condition,
                        range,
                        advice));
        return views;
    }

    private static void bindHourlyStrip(
            RemoteViews views,
            String json,
            int[] timeIds,
            int[] glyphIds,
            int[] tempIds) {
        JSONArray hours;
        try {
            hours = json == null || json.trim().isEmpty() ? new JSONArray() : new JSONArray(json);
        } catch (Exception ignored) {
            hours = new JSONArray();
        }

        int count = Math.min(timeIds.length, Math.min(glyphIds.length, tempIds.length));
        for (int i = 0; i < count; i++) {
            JSONObject hour = hours.optJSONObject(i);
            if (hour == null) {
                views.setViewVisibility(timeIds[i], View.INVISIBLE);
                views.setViewVisibility(glyphIds[i], View.INVISIBLE);
                views.setViewVisibility(tempIds[i], View.INVISIBLE);
                continue;
            }

            String time = clean(hour.optString("time", "—"), "—");
            int temperature = hour.optInt("temperature", Integer.MIN_VALUE);
            String condition = clean(hour.optString("condition", "Forecast"), "Forecast");
            boolean daytime = hour.optBoolean("daytime", true);

            views.setViewVisibility(timeIds[i], View.VISIBLE);
            views.setViewVisibility(glyphIds[i], View.VISIBLE);
            views.setViewVisibility(tempIds[i], View.VISIBLE);
            views.setTextViewText(timeIds[i], i == 0 ? "Now" : time);
            views.setImageViewResource(glyphIds[i], iconFor(condition, daytime));
            views.setTextViewText(tempIds[i],
                    temperature == Integer.MIN_VALUE ? "—" : temperature + "°");
        }
    }

    private static void renderPlaceholder(RemoteViews views, WidgetMode mode) {
        views.setTextViewText(R.id.widget_city, "Zwerk Weather");
        views.setTextViewText(R.id.widget_temperature, "—");
        views.setTextViewText(R.id.widget_unit, "°");
        views.setTextViewText(R.id.widget_condition, "Forecast not cached");
        views.setImageViewResource(R.id.widget_glyph, R.drawable.widget_ic_cloud);
        views.setTextViewText(R.id.widget_high_low, "Open app to refresh");
        views.setImageViewResource(R.id.widget_scene, R.drawable.widget_scene_day);
        views.setViewVisibility(R.id.widget_hour_strip,
                mode == WidgetMode.COMPACT ? View.GONE : View.VISIBLE);
        views.setViewVisibility(R.id.widget_glyph,
                mode == WidgetMode.COMPACT ? View.GONE : View.VISIBLE);

        if (mode == WidgetMode.NORMAL) {
            bindPlaceholderHours(views, NORMAL_HOUR_TIME_IDS, NORMAL_HOUR_GLYPH_IDS, NORMAL_HOUR_TEMP_IDS);
        } else if (mode == WidgetMode.TALL) {
            views.setTextViewText(R.id.widget_advice, "Open Zwerk Weather to refresh");
            bindPlaceholderHours(views, TALL_HOUR_TIME_IDS, TALL_HOUR_GLYPH_IDS, TALL_HOUR_TEMP_IDS);
        }
        views.setContentDescription(R.id.widget_root,
                "Zwerk Weather widget. Open Zwerk Weather to refresh.");
    }

    private static void bindPlaceholderHours(
            RemoteViews views,
            int[] timeIds,
            int[] glyphIds,
            int[] tempIds) {
        int count = Math.min(timeIds.length, Math.min(glyphIds.length, tempIds.length));
        for (int i = 0; i < count; i++) {
            views.setViewVisibility(timeIds[i], View.VISIBLE);
            views.setViewVisibility(glyphIds[i], View.VISIBLE);
            views.setViewVisibility(tempIds[i], View.VISIBLE);
            views.setTextViewText(timeIds[i], i == 0 ? "Now" : "—");
            views.setImageViewResource(glyphIds[i], R.drawable.widget_ic_cloud);
            views.setTextViewText(tempIds[i], "—");
        }
    }

    private static void bindLaunchIntent(Context context, RemoteViews views) {
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
    }

    private static int sceneFor(String condition, boolean daytime) {
        String key = conditionKey(condition);
        if ("rain".equals(key) || "thunder".equals(key) || "fog".equals(key)) {
            return R.drawable.widget_scene_rain;
        }
        if ("snow".equals(key)) return R.drawable.widget_scene_snow;
        return daytime ? R.drawable.widget_scene_day : R.drawable.widget_scene_night;
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

    private static String conditionKey(String condition) {
        String c = condition == null ? "" : condition.toLowerCase(Locale.ROOT);
        if (c.contains("thunder") || c.contains("storm") || c.contains("lightning")) return "thunder";
        if (c.contains("snow") || c.contains("sleet") || c.contains("ice") || c.contains("flurr")) return "snow";
        if (c.contains("rain") || c.contains("drizzle") || c.contains("shower")) return "rain";
        if (c.contains("fog") || c.contains("mist") || c.contains("haze") || c.contains("smoke")) return "fog";
        if (c.contains("partly") || c.contains("mostly") || c.contains("scattered")) return "partly";
        if (c.contains("clear") || c.contains("sunny")) return "clear";
        return "cloud";
    }

    private static String highLowLabel(int high, int low) {
        if (high == Integer.MIN_VALUE && low == Integer.MIN_VALUE) return "High / low —";
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

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    /** API-31-only references stay isolated from the minSdk 28 provider class verifier. */
    private static final class Api31Responsive {
        private Api31Responsive() {}

        @TargetApi(31)
        static RemoteViews remoteViews(RemoteViews compact, RemoteViews normal, RemoteViews tall) {
            Map<SizeF, RemoteViews> variants = new HashMap<>();
            variants.put(new SizeF(40f, 40f), compact);
            variants.put(new SizeF(180f, NORMAL_HEIGHT_DP), normal);
            variants.put(new SizeF(180f, TALL_HEIGHT_DP), tall);
            return new RemoteViews(variants);
        }
    }
}
