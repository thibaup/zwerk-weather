package com.zwerk.weather;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Standalone host for units and external settings shortcuts; MainActivity embeds the same screen. */
public class SettingsActivity extends Activity {
    public static final String EXTRA_ACTION = SettingsScreen.EXTRA_ACTION;
    public static final String EXTRA_SCENE = SettingsScreen.EXTRA_SCENE;
    public static final String EXTRA_DAYTIME = SettingsScreen.EXTRA_DAYTIME;
    public static final String EXTRA_CARD_TOP = SettingsScreen.EXTRA_CARD_TOP;
    public static final String EXTRA_CARD_BOTTOM = SettingsScreen.EXTRA_CARD_BOTTOM;
    public static final String EXTRA_TILE_TOP = SettingsScreen.EXTRA_TILE_TOP;
    public static final String EXTRA_TILE_BOTTOM = SettingsScreen.EXTRA_TILE_BOTTOM;
    public static final String EXTRA_ACCENT = SettingsScreen.EXTRA_ACCENT;
    public static final String EXTRA_UNIT_CHANGED = SettingsScreen.EXTRA_UNIT_CHANGED;
    public static final String EXTRA_DISPLAY_UNIT_CHANGED = SettingsScreen.EXTRA_DISPLAY_UNIT_CHANGED;
    static final String EXTRA_AIR_QUALITY_CHANGED = SettingsScreen.EXTRA_AIR_QUALITY_CHANGED;
    static final String EXTRA_POLLEN_CHANGED = SettingsScreen.EXTRA_POLLEN_CHANGED;
    static final String EXTRA_SEVERE_ALERTS_CHANGED = SettingsScreen.EXTRA_SEVERE_ALERTS_CHANGED;
    static final String EXTRA_WEATHER_DETAILS_CHANGED = SettingsScreen.EXTRA_WEATHER_DETAILS_CHANGED;
    static final String EXTRA_FORECAST_PAGES_CHANGED = SettingsScreen.EXTRA_FORECAST_PAGES_CHANGED;
    static final String EXTRA_PROVIDER_CHANGED = SettingsScreen.EXTRA_PROVIDER_CHANGED;
    static final String EXTRA_PRECIPITATION_PROVIDER_CHANGED =
            SettingsScreen.EXTRA_PRECIPITATION_PROVIDER_CHANGED;
    public static final String ACTION_REFRESH = SettingsScreen.ACTION_REFRESH;
    public static final String ACTION_DEVICE_LOCATION = SettingsScreen.ACTION_DEVICE_LOCATION;
    public static final String ACTION_ADVANCED_COORDINATES = SettingsScreen.ACTION_ADVANCED_COORDINATES;
    public static final String ACTION_SELECTED_CITY = SettingsScreen.ACTION_SELECTED_CITY;
    public static final String ACTION_PREFERENCES_CHANGED = SettingsScreen.ACTION_PREFERENCES_CHANGED;
    public static final String ACTION_API_KEY_CHANGED = SettingsScreen.ACTION_API_KEY_CHANGED;
    public static final String ACTION_OPEN_METEO_KEY_CHANGED = SettingsScreen.ACTION_OPEN_METEO_KEY_CHANGED;
    static final String PROJECT_GITHUB_URL = SettingsScreen.PROJECT_GITHUB_URL;
    static final String PREF_SUPPORT_PROMPT_HANDLED = SettingsScreen.PREF_SUPPORT_PROMPT_HANDLED;
    static final String PREF_PRECIPITATION_PAGE = SettingsScreen.PREF_PRECIPITATION_PAGE;
    static final String PREF_RADAR_PAGE = SettingsScreen.PREF_RADAR_PAGE;
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
