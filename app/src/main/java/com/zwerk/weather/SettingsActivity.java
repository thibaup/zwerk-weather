package com.zwerk.weather;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Standalone host for units and external settings shortcuts; MainActivity embeds the same screen. */
public class SettingsActivity extends Activity {
    public static final String EXTRA_ACTION = "com.zwerk.weather.extra.SETTINGS_ACTION";
    public static final String EXTRA_SCENE = "com.zwerk.weather.extra.SETTINGS_SCENE";
    public static final String EXTRA_DAYTIME = "com.zwerk.weather.extra.SETTINGS_DAYTIME";
    public static final String EXTRA_CARD_TOP = "com.zwerk.weather.extra.SETTINGS_CARD_TOP";
    public static final String EXTRA_CARD_BOTTOM = "com.zwerk.weather.extra.SETTINGS_CARD_BOTTOM";
    public static final String EXTRA_TILE_TOP = "com.zwerk.weather.extra.SETTINGS_TILE_TOP";
    public static final String EXTRA_TILE_BOTTOM = "com.zwerk.weather.extra.SETTINGS_TILE_BOTTOM";
    public static final String EXTRA_ACCENT = "com.zwerk.weather.extra.SETTINGS_ACCENT";
    public static final String EXTRA_UNIT_CHANGED = "com.zwerk.weather.extra.UNIT_CHANGED";
    public static final String EXTRA_DISPLAY_UNIT_CHANGED = "com.zwerk.weather.extra.DISPLAY_UNIT_CHANGED";
    static final String EXTRA_AIR_QUALITY_CHANGED = "com.zwerk.weather.extra.AIR_QUALITY_CHANGED";
    static final String EXTRA_POLLEN_CHANGED = "com.zwerk.weather.extra.POLLEN_CHANGED";
    static final String EXTRA_SEVERE_ALERTS_CHANGED = "com.zwerk.weather.extra.SEVERE_ALERTS_CHANGED";
    static final String EXTRA_WEATHER_DETAILS_CHANGED = "com.zwerk.weather.extra.WEATHER_DETAILS_CHANGED";
    static final String EXTRA_FORECAST_PAGES_CHANGED = "com.zwerk.weather.extra.FORECAST_PAGES_CHANGED";
    static final String EXTRA_PROVIDER_CHANGED = "com.zwerk.weather.extra.PROVIDER_CHANGED";
    static final String EXTRA_PRECIPITATION_PROVIDER_CHANGED =
            "com.zwerk.weather.extra.PRECIPITATION_PROVIDER_CHANGED";
    public static final String ACTION_REFRESH = "refresh";
    public static final String ACTION_DEVICE_LOCATION = "device_location";
    public static final String ACTION_ADVANCED_COORDINATES = "advanced_coordinates";
    public static final String ACTION_SELECTED_CITY = "selected_city";
    public static final String ACTION_PREFERENCES_CHANGED = "preferences_changed";
    public static final String ACTION_API_KEY_CHANGED = "api_key_changed";
    public static final String ACTION_OPEN_METEO_KEY_CHANGED = "open_meteo_key_changed";
    static final String PROJECT_GITHUB_URL =
            "https://github.com/thibaup/zwerk-weather";
    static final String PREF_SUPPORT_PROMPT_HANDLED = "support_prompt_handled";
    static final String PREF_PRECIPITATION_PAGE = "precipitation_page_enabled";
    static final String PREF_RADAR_PAGE = "radar_page_enabled";
    private SettingsScreen screen;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLocaleManager.wrap(base));
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        screen = new SettingsScreen(this, null, null);
        screen.initialize(state);
    }
    @Override protected void onResume() {
        super.onResume();
        if (screen != null) screen.onResume();
    }
    @Override protected void onPause() {
        if (screen != null) screen.onPause();
        super.onPause();
    }
    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (screen != null) screen.onActivityResult(code, result, data);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (screen != null) screen.onSaveInstanceState(state);
        super.onSaveInstanceState(state);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (screen != null) screen.onRequestPermissionsResult(code, permissions, results);
    }
    // SettingsScreen registers the API 33+ callback; this handles API 28-32.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { screen.onBackPressed(); }
}
