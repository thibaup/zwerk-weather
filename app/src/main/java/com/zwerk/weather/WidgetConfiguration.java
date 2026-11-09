package com.zwerk.weather;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.widget.RemoteViews;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Launcher instance preferences and storage for forecasts shared by widgets pinned to one city. */
final class WidgetConfiguration {
    private static final String PREFS = "widget_instance_settings";

    final String cityId;
    final int textScalePercent;
    final boolean showDetails;
    final boolean showForecast;

    WidgetConfiguration(String cityId, int textScalePercent, boolean showDetails, boolean showForecast) {
        this.cityId = cityId == null ? "" : cityId;
        this.textScalePercent = Math.max(80, Math.min(120, textScalePercent));
        this.showDetails = showDetails;
        this.showForecast = showForecast;
    }

    static WidgetConfiguration read(Context context, int id) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new WidgetConfiguration(prefs.getString(id + ".city", ""),
                prefs.getInt(id + ".scale", 100), prefs.getBoolean(id + ".details", true),
                prefs.getBoolean(id + ".forecast", true));
    }

    static void save(Context context, int id, WidgetConfiguration configuration) {
        if (!owns(context, id)) return;
        synchronized (WeatherWidgetSnapshotPublisher.class) {
            String oldCity = read(context, id).cityId;
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(id + ".city", configuration.cityId)
                    .putInt(id + ".scale", configuration.textScalePercent)
                    .putBoolean(id + ".details", configuration.showDetails)
                    .putBoolean(id + ".forecast", configuration.showForecast)
                    .apply();
            if (!oldCity.equals(configuration.cityId)) removeUnusedCitySnapshot(context, oldCity);
        }
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
        if (info != null) {
            RemoteViews views = info.provider.getClassName().equals(PrecipitationWidgetProvider.class.getName())
                    ? PrecipitationWidgetProvider.buildViews(context, id, null)
                    : WeatherWidgetProvider.buildViews(context, id);
            manager.updateAppWidget(id, views);
        }
        WeatherWidgetProvider.requestRefresh(context);
    }

    static void delete(Context context, int[] ids) {
        synchronized (WeatherWidgetSnapshotPublisher.class) {
            SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            String[] cities = new String[ids.length];
            for (int i = 0; i < ids.length; i++) {
                int id = ids[i];
                cities[i] = read(context, id).cityId;
                editor.remove(id + ".city").remove(id + ".scale")
                        .remove(id + ".details").remove(id + ".forecast");
            }
            editor.apply();
            for (String city : cities) removeUnusedCitySnapshot(context, city);
        }
        WidgetRefreshManager.reconcile(context);
    }

    static void forgetCity(Context context, String cityId) {
        if (cityId.isEmpty()) return;
        boolean changed = false;
        synchronized (WeatherWidgetSnapshotPublisher.class) {
            SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            for (int id : widgetIds(context)) {
                if (cityId.equals(read(context, id).cityId)) {
                    editor.remove(id + ".city");
                    changed = true;
                }
            }
            if (changed) editor.apply();
            context.deleteSharedPreferences(snapshotName(cityId));
        }
        if (changed) WeatherWidgetProvider.requestRefresh(context);
    }

    private static void removeUnusedCitySnapshot(Context context, String cityId) {
        if (cityId.isEmpty()) return;
        for (int id : widgetIds(context)) {
            if (cityId.equals(read(context, id).cityId)) return;
        }
        context.deleteSharedPreferences(snapshotName(cityId));
    }

    static int[] widgetIds(Context context) {
        int[] weather = WeatherWidgetProvider.widgetIds(context);
        int[] rain = PrecipitationWidgetProvider.widgetIds(context);
        int[] all = Arrays.copyOf(weather, weather.length + rain.length);
        System.arraycopy(rain, 0, all, weather.length, rain.length);
        return all;
    }

    static boolean owns(Context context, int id) {
        if (id <= AppWidgetManager.INVALID_APPWIDGET_ID) return false;
        AppWidgetProviderInfo info = AppWidgetManager.getInstance(context).getAppWidgetInfo(id);
        if (info == null || info.provider == null
                || !context.getPackageName().equals(info.provider.getPackageName())) return false;
        String provider = info.provider.getClassName();
        return provider.equals(WeatherWidgetProvider.class.getName())
                || provider.equals(WeatherSmallWidgetProvider.class.getName())
                || provider.equals(WeatherMiniWidgetProvider.class.getName())
                || provider.equals(PrecipitationWidgetProvider.class.getName());
    }

    CityManagerActivity.LocationSnapshot city(Context context) {
        if (cityId.isEmpty()) return null;
        CityManagerActivity.LocationSnapshot city = CityManagerActivity.getLocationById(context, cityId);
        return city == null || city.isDevice ? null : city;
    }

    static SharedPreferences snapshot(Context context, String cityId) {
        return context.getSharedPreferences(snapshotName(cityId), Context.MODE_PRIVATE);
    }

    static boolean snapshotMatches(Context context, SharedPreferences prefs,
            CityManagerActivity.LocationSnapshot city) {
        return city == null || PrecipitationWidgetData.locationScope(
                city.lat, city.lon, OpenMeteoConfig.cacheScope(context))
                .equals(prefs.getString(PrecipitationWidgetProvider.KEY_LOCATION_SCOPE, ""));
    }

    private static String snapshotName(String cityId) {
        return cityId.isEmpty() ? WeatherWidgetProvider.PREFS_NAME : WeatherWidgetProvider.PREFS_NAME
                + "_" + UUID.nameUUIDFromBytes(cityId.getBytes(StandardCharsets.UTF_8));
    }

    void setText(RemoteViews views, int viewId, CharSequence text) {
        if (textScalePercent == 100 || text.length() == 0) {
            views.setTextViewText(viewId, text);
        } else {
            SpannableString scaled = new SpannableString(text);
            scaled.setSpan(new RelativeSizeSpan(textScalePercent / 100f), 0, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            views.setTextViewText(viewId, scaled);
        }
    }
}
