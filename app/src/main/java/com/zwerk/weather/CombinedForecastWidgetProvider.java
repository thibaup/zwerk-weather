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
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Condensed 4x2 multi-day combined temperature + precipitation home-screen widget. */
public final class CombinedForecastWidgetProvider extends AppWidgetProvider {
    private static final String ACTION_REFRESH =
            "com.zwerk.weather.action.REFRESH_COMBINED_FORECAST_WIDGET";

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        DiagnosticLog.event(DiagnosticLog.Area.WIDGET, DiagnosticLog.Event.WIDGET_UPDATE);
        WidgetRefreshManager.reconcile(context);
        for (int id : ids) manager.updateAppWidget(id, buildViews(context, id, null));
        scheduleNextUpdate(context);
    }

    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
            int id, Bundle options) {
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
                || Intent.ACTION_DATE_CHANGED.equals(action)) {
            updateAll(context);
        }
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
        context.sendBroadcast(new Intent(context, CombinedForecastWidgetProvider.class)
                .setAction(ACTION_REFRESH));
    }

    private static void updateAll(Context context) {
        WidgetRefreshManager.reconcile(context);
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int id : widgetIds(context)) manager.updateAppWidget(id, buildViews(context, id, null));
        scheduleNextUpdate(context);
    }

    static int[] widgetIds(Context context) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(
                new ComponentName(context, CombinedForecastWidgetProvider.class));
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
                : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280);
        int height = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 144);
        return buildForSize(context, width > 0 ? width : 280, height > 0 ? height : 144,
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
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_forecast);
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            Intent launch = new Intent(context, MainActivity.class)
                    .setAction(MainActivity.ACTION_WIDGET_FORECAST)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            views.setOnClickPendingIntent(R.id.forecast_widget_root,
                    PendingIntent.getActivity(context, id + 20000, launch,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }

        SharedPreferences prefs = WidgetConfiguration.snapshot(context, pinned == null ? "" : pinned.id);
        long now = System.currentTimeMillis();
        WeatherWidgetTimeline timeline = new WeatherWidgetTimeline(prefs, now);
        boolean matches = WidgetConfiguration.snapshotMatches(context, prefs, pinned);
        boolean retained = matches && prefs.getBoolean(WeatherWidgetProvider.KEY_HAS_SNAPSHOT, false)
                && timeline.retained;

        WeatherPreferences weatherPreferences = new WeatherPreferences(context);
        String city = pinned == null
                ? clean(prefs.getString(WeatherWidgetProvider.KEY_CITY, "Zwerk Weather"), "Zwerk Weather")
                : pinned.name;
        String unit = retained
                ? clean(prefs.getString(WeatherWidgetProvider.KEY_UNIT, "°C"), "°C")
                : (WeatherPreferences.TEMP_FAHRENHEIT.equals(weatherPreferences.temperatureUnit())
                        ? "°F" : "°C");
        boolean precipInches = weatherPreferences.isPrecipitationInches();

        String conditionType = retained
                ? clean(prefs.getString(WeatherWidgetProvider.KEY_CONDITION_TYPE, "cloudy"), "cloudy")
                : "cloudy";
        boolean daytime = !retained || prefs.getBoolean(WeatherWidgetProvider.KEY_DAYTIME, true);
        int currentTemp = retained ? prefs.getInt(WeatherWidgetProvider.KEY_TEMPERATURE, Integer.MIN_VALUE)
                : Integer.MIN_VALUE;
        long observation = prefs.getLong(WeatherWidgetProvider.KEY_OBSERVATION_EPOCH_MS, timeline.fetchedAt);
        JSONObject currentHour = retained ? timeline.currentHour() : null;
        if (currentHour != null && currentHour.optLong("start", 0L) > observation) {
            currentTemp = currentHour.optInt("temperature", currentTemp);
            conditionType = clean(currentHour.optString("conditionType", conditionType), conditionType);
            daytime = currentHour.optBoolean("daytime", daytime);
        } else if (retained && currentHour == null
                && !ForecastClock.withinAge(observation, now, ForecastClock.FRESH_MILLIS)) {
            currentTemp = Integer.MIN_VALUE;
        }
        WidgetBackground.apply(localized, views, R.id.forecast_widget_scene, conditionType, daytime, config);

        CombinedForecastData data = CombinedForecastData.fromWidgetTimeline(
                retained ? timeline : null, now, config.forecastDays, precipInches);

        String title = city;
        if (config.showDetails && retained && currentTemp != Integer.MIN_VALUE) {
            title = city + " · " + currentTemp + "°";
        }
        config.setText(views, R.id.forecast_widget_title, title);

        String meta = "";
        if (config.showDetails && !data.isEmpty()) {
            if (data.hasPrecipitationAmount && data.totalMm > 0d) {
                meta = CombinedForecastData.formatCompactAmount(data.totalMm, precipInches, true);
            } else if (data.hasPrecipitationAmount) {
                meta = precipInches ? "0 in" : "0 mm";
            } else if (data.hasPrecipitationProbability) {
                meta = "0%";
            }
        }
        if (meta.isEmpty()) {
            views.setViewVisibility(R.id.forecast_widget_meta, View.GONE);
        } else {
            views.setViewVisibility(R.id.forecast_widget_meta, View.VISIBLE);
            config.setText(views, R.id.forecast_widget_meta, meta);
        }

        Locale locale = AppLocaleManager.activeLocale(localized);
        DateTimeFormatter weekdayFormatter = DateTimeFormatter.ofPattern("EEE", locale);
        int dayCount = data.days.size();
        String[] dayLabels = new String[dayCount];
        String[] shortDayLabels = new String[dayCount];
        String todayText = UiTranslations.text(localized, "Today");
        for (int i = 0; i < dayCount; i++) {
            CombinedForecastData.DayPoint day = data.days.get(i);
            String weekday = day.date == null ? "—"
                    : AppLocaleManager.capitalize(day.date.format(weekdayFormatter), locale);
            shortDayLabels[i] = weekday;
            dayLabels[i] = day.today ? todayText : weekday;
        }

        int chartWidthDp = Math.max(180, widthDp - 20);
        int chartHeightDp = Math.max(70, heightDp - 34);
        float density = context.getResources().getDisplayMetrics().density;
        try {
            Bitmap chart = CombinedForecastWidgetChart.render(
                    data, chartWidthDp, chartHeightDp,
                    dayLabels, shortDayLabels,
                    config.precipitationStyle, config.showForecast,
                    UiTranslations.text(localized, "No forecast available"),
                    config.textScalePercent / 100f,
                    density);
            views.setImageViewBitmap(R.id.forecast_widget_chart, chart);
        } catch (OutOfMemoryError ignored) {
            views.setImageViewResource(R.id.forecast_widget_chart, 0);
        }

        views.setContentDescription(R.id.forecast_widget_root,
                accessibilitySummary(localized, city, currentTemp, unit, data, dayLabels));
        return views;
    }

    private static String accessibilitySummary(Context context, String city, int currentTemp,
            String unit, CombinedForecastData data, String[] dayLabels) {
        StringBuilder out = new StringBuilder(city);
        if (currentTemp != Integer.MIN_VALUE) {
            out.append(", ").append(currentTemp).append(unit);
        }
        if (data.isEmpty()) {
            out.append(". ").append(UiTranslations.text(context, "No forecast available"));
            return out.toString();
        }
        for (int i = 0; i < data.days.size(); i++) {
            CombinedForecastData.DayPoint day = data.days.get(i);
            out.append(". ").append(i < dayLabels.length ? dayLabels[i] : "");
            if (day.high != null) out.append(' ').append(day.high).append('°');
            if (day.low != null) out.append('/').append(day.low).append('°');
            if (Double.isFinite(day.precipitationMm) && day.precipitationMm > 0d) {
                out.append(' ').append(CombinedForecastData.formatCompactAmount(
                        day.precipitationMm, data.fahrenheit, true));
            } else if (day.probability > 0) {
                out.append(' ').append(day.probability).append('%');
            }
        }
        return out.toString();
    }

    private static PendingIntent clockUpdateIntent(Context context) {
        return PendingIntent.getBroadcast(context, 4308,
                new Intent(context, CombinedForecastWidgetProvider.class).setAction(ACTION_REFRESH),
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
        long next = Long.MAX_VALUE;
        for (int id : ids) {
            CityManagerActivity.LocationSnapshot city = WidgetConfiguration.read(context, id).city(context);
            SharedPreferences prefs = WidgetConfiguration.snapshot(context, city == null ? "" : city.id);
            WeatherWidgetTimeline timeline = new WeatherWidgetTimeline(prefs, now);
            long nextForWidget = now + 5L * 60_000L;
            if (prefs.getBoolean(WeatherWidgetProvider.KEY_HAS_SNAPSHOT, false) && timeline.retained
                    && WidgetConfiguration.snapshotMatches(context, prefs, city)) {
                long refreshAt = timeline.fetchedAt + WidgetRefreshPolicy.REFRESH_MILLIS;
                nextForWidget = Math.min(timeline.nextChange(now),
                        refreshAt > now ? refreshAt : nextForWidget);
            }
            next = Math.min(next, nextForWidget);
        }
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null && next > now && next < Long.MAX_VALUE) {
            alarms.set(AlarmManager.RTC, next + 1000L, clockUpdateIntent(context));
        }
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
