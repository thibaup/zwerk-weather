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
import java.util.UUID;

/** Launcher instance preferences and storage for forecasts shared by widgets pinned to one city. */
final class WidgetConfiguration {
    private static final String PREFS = "widget_instance_settings";

    static final String BG_APP_DEFAULT = "";
    static final String BG_WEATHER = "weather";
    static final String BG_BLUE = "blue";
    static final String BG_DARK = "dark";
    static final String BG_CUSTOM = "custom";
    static final int TRANSPARENCY_APP_DEFAULT = -1;
    static final int GRADIENT_APP_DEFAULT = -1;

    final String cityId;
    final int textScalePercent;
    final boolean showDetails;
    final boolean showForecast;
    final int forecastDays;
    final String precipitationStyle;
    final String backgroundMode;
    final int transparencyPercent;
    final int backgroundGradient;
    final int customColor;

    WidgetConfiguration(String cityId, int textScalePercent, boolean showDetails, boolean showForecast) {
        this(cityId, textScalePercent, showDetails, showForecast,
                CombinedForecastData.DEFAULT_WIDGET_DAYS, CombinedForecastData.PRECIP_STYLE_COLUMNS,
                BG_APP_DEFAULT, TRANSPARENCY_APP_DEFAULT, GRADIENT_APP_DEFAULT,
                WidgetBackground.DEFAULT_COLOUR);
    }

    WidgetConfiguration(String cityId, int textScalePercent, boolean showDetails, boolean showForecast,
            int forecastDays, String precipitationStyle, String backgroundMode,
            int transparencyPercent, int backgroundGradient, int customColor) {
        this.cityId = cityId == null ? "" : cityId;
        this.textScalePercent = Math.max(80, Math.min(120, textScalePercent));
        this.showDetails = showDetails;
        this.showForecast = showForecast;
        this.forecastDays = CombinedForecastData.clampWidgetDays(forecastDays);
        this.precipitationStyle = CombinedForecastData.normalizePrecipitationStyle(precipitationStyle);
        this.backgroundMode = normalizeBackgroundMode(backgroundMode);
        this.transparencyPercent = transparencyPercent < 0
                ? TRANSPARENCY_APP_DEFAULT : Math.min(100, transparencyPercent);
        this.backgroundGradient = backgroundGradient < 0
                ? GRADIENT_APP_DEFAULT : (backgroundGradient > 0 ? 1 : 0);
        this.customColor = customColor | 0xff000000;
    }

    static String normalizeBackgroundMode(String value) {
        String raw = value == null ? "" : value.trim();
        if (BG_WEATHER.equals(raw) || BG_BLUE.equals(raw)
                || BG_DARK.equals(raw) || BG_CUSTOM.equals(raw)) {
            return raw;
        }
        return BG_APP_DEFAULT;
    }

    static WidgetConfiguration read(Context context, int id) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new WidgetConfiguration(
                prefs.getString(id + ".city", ""),
                prefs.getInt(id + ".scale", 100),
                prefs.getBoolean(id + ".details", true),
                prefs.getBoolean(id + ".forecast", true),
                prefs.getInt(id + ".days", CombinedForecastData.DEFAULT_WIDGET_DAYS),
                prefs.getString(id + ".precip_style", CombinedForecastData.PRECIP_STYLE_COLUMNS),
                prefs.getString(id + ".bg_mode", BG_APP_DEFAULT),
                prefs.getInt(id + ".bg_transparency", TRANSPARENCY_APP_DEFAULT),
                prefs.getInt(id + ".bg_gradient", GRADIENT_APP_DEFAULT),
                prefs.getInt(id + ".bg_color", WidgetBackground.DEFAULT_COLOUR));
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
                    .putInt(id + ".days", configuration.forecastDays)
                    .putString(id + ".precip_style", configuration.precipitationStyle)
                    .putString(id + ".bg_mode", configuration.backgroundMode)
                    .putInt(id + ".bg_transparency", configuration.transparencyPercent)
                    .putInt(id + ".bg_gradient", configuration.backgroundGradient)
                    .putInt(id + ".bg_color", configuration.customColor)
                    .apply();
            if (!oldCity.equals(configuration.cityId)) removeUnusedCitySnapshot(context, oldCity);
        }
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
        if (info != null) {
            String provider = info.provider.getClassName();
            RemoteViews views = provider.equals(PrecipitationWidgetProvider.class.getName())
                    ? PrecipitationWidgetProvider.buildViews(context, id, null)
                    : provider.equals(CombinedForecastWidgetProvider.class.getName())
                            ? CombinedForecastWidgetProvider.buildViews(context, id, null)
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
                        .remove(id + ".details").remove(id + ".forecast")
                        .remove(id + ".days").remove(id + ".precip_style")
                        .remove(id + ".bg_mode").remove(id + ".bg_transparency")
                        .remove(id + ".bg_gradient").remove(id + ".bg_color");
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
        int[] forecast = CombinedForecastWidgetProvider.widgetIds(context);
        int[] all = new int[weather.length + rain.length + forecast.length];
        System.arraycopy(weather, 0, all, 0, weather.length);
        System.arraycopy(rain, 0, all, weather.length, rain.length);
        System.arraycopy(forecast, 0, all, weather.length + rain.length, forecast.length);
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
                || provider.equals(PrecipitationWidgetProvider.class.getName())
                || provider.equals(CombinedForecastWidgetProvider.class.getName());
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
