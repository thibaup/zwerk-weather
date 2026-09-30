package com.zwerk.weather;

import android.content.Context;
import android.content.SharedPreferences;

/** Restores user preferences without deleting locations, credentials or request history. */
final class WeatherSettingsDefaults {
    private WeatherSettingsDefaults() { }

    static void restore(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                WeatherPreferences.PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : new String[]{
                "temperature_unit", "wind_unit", "pressure_unit", "visibility_unit",
                "weather_animations", "rain_alerts", "severe_weather_alerts",
                "precipitation_page_enabled", "radar_page_enabled", "forecast_page_mode",
                "daily_mode", "widget_show_hourly", "widget_show_advice", "widget_show_icon",
                WeatherPreferences.PREF_TILE_TRANSPARENCY, WeatherPreferences.PREF_RADAR_MAP_THEME,
                OpenMeteoConfig.PREF_PROVIDER, OpenMeteoConfig.PREF_PRECIPITATION_PROVIDER,
                OpenMeteoConfig.PREF_MODEL, "google_air_quality_enabled", "google_pollen_enabled",
                WeatherWidgetProvider.PREF_TRANSPARENCY, WeatherWidgetProvider.PREF_GRADIENT,
                WeatherWidgetProvider.PREF_COLOUR, WeatherWidgetProvider.PREF_CUSTOM_COLOUR,
                WeatherOverviewRenderingActivity.PREF_OVERVIEW_TILE_ORDER,
                WeatherOverviewRenderingActivity.PREF_OVERVIEW_BLOCK_ORDER,
                "environment_tiles_top_order_v1"}) {
            edit.remove(key);
        }
        for (WeatherDetailSettingsActivity.DetailOption option : WeatherDetailSettingsActivity.OPTIONS) {
            edit.remove(option.key);
        }
        edit.apply();
        // Also marks the legacy provider migration complete, so it cannot undo the reset.
        OpenMeteoConfig.setProvider(context, OpenMeteoConfig.GOOGLE);
        AppLocaleManager.setSelectedTag(context, AppLocaleManager.SYSTEM_DEFAULT);
        ApiRequestBudgetManager.applyProfile(context, ApiRequestBudgetManager.PROFILE_GOOGLE_FREE);
        OpenMeteoRequestBudgetManager.restoreDefaultLimits(context);
        RainAlertManager.reconcile(context);
        WeatherWidgetProvider.requestRefresh(context);
    }
}
