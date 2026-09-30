package com.zwerk.weather;

import android.content.Context;
import android.content.SharedPreferences;

final class WeatherPreferences {
    static final String PREFS_NAME = "WEATHER_UI";
    static final String PREF_TILE_TRANSPARENCY = "tile_transparency";
    static final int DEFAULT_TILE_TRANSPARENCY = 70;
    static final String PREF_RADAR_MAP_THEME = "radar_map_theme";
    static final String MAP_AUTOMATIC = "automatic";
    static final String MAP_LIGHT = "light";
    static final String MAP_DARK = "dark";
    static final String TEMP_CELSIUS = "C";
    static final String TEMP_FAHRENHEIT = "F";
    static final String WIND_KMH = "km/h";
    static final String WIND_MPH = "mph";
    static final String WIND_MS = "m/s";
    static final String WIND_KNOTS = "knots";
    static final String PRESSURE_HPA = "hPa";
    static final String PRESSURE_INHG = "inHg";
    static final String PRESSURE_MMHG = "mmHg";
    static final String VISIBILITY_KM = "km";
    static final String VISIBILITY_MI = "mi";

    private final SharedPreferences preferences;

    WeatherPreferences(Context context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    String temperatureUnit() {
        return normalizeTemperatureUnit(preferences.getString("temperature_unit", TEMP_CELSIUS));
    }

    String windUnit(boolean fahrenheit) {
        if (!preferences.contains("wind_unit")) return fahrenheit ? WIND_MPH : WIND_KMH;
        return normalizeWindUnit(preferences.getString("wind_unit", WIND_KMH));
    }

    String pressureUnit() {
        return normalizePressureUnit(preferences.getString("pressure_unit", PRESSURE_HPA));
    }

    String visibilityUnit(boolean fahrenheit) {
        if (!preferences.contains("visibility_unit")) return fahrenheit ? VISIBILITY_MI : VISIBILITY_KM;
        return normalizeVisibilityUnit(preferences.getString("visibility_unit", VISIBILITY_KM));
    }

    boolean animationsAllowed() {
        return preferences.getBoolean("weather_animations", true);
    }

    boolean airQualityEnabled() {
        return preferences.getBoolean("google_air_quality_enabled", true);
    }

    boolean pollenEnabled() {
        return preferences.getBoolean("google_pollen_enabled", true);
    }

    int tileTransparency() {
        return Math.max(0, Math.min(100, preferences.getInt(
                PREF_TILE_TRANSPARENCY, DEFAULT_TILE_TRANSPARENCY)));
    }

    String radarMapTheme() {
        String value = preferences.getString(PREF_RADAR_MAP_THEME, MAP_AUTOMATIC);
        return MAP_LIGHT.equals(value) || MAP_DARK.equals(value) ? value : MAP_AUTOMATIC;
    }

    static int surfaceColor(int color, int transparency) {
        int base = color >>> 24;
        int value = Math.max(0, Math.min(100, transparency));
        // Preserve each scene's existing gradient at the default; both ends reach
        // fully opaque at 0 and fully transparent at 100.
        int alpha = value <= DEFAULT_TILE_TRANSPARENCY
                ? Math.round(base + (255 - base)
                        * (DEFAULT_TILE_TRANSPARENCY - value) / (float) DEFAULT_TILE_TRANSPARENCY)
                : Math.round(base * (100 - value) / (float) (100 - DEFAULT_TILE_TRANSPARENCY));
        return (color & 0x00ffffff) | (alpha << 24);
    }

    static String normalizeTemperatureUnit(String value) {
        return TEMP_FAHRENHEIT.equalsIgnoreCase(value) ? TEMP_FAHRENHEIT : TEMP_CELSIUS;
    }

    static String normalizeWindUnit(String value) {
        String raw = value == null ? "" : value.trim();
        if (WIND_MPH.equalsIgnoreCase(raw) || "mi/h".equalsIgnoreCase(raw)) return WIND_MPH;
        if (WIND_MS.equalsIgnoreCase(raw) || "mps".equalsIgnoreCase(raw)) return WIND_MS;
        if (WIND_KNOTS.equalsIgnoreCase(raw) || "kt".equalsIgnoreCase(raw)
                || "kts".equalsIgnoreCase(raw)) return WIND_KNOTS;
        return WIND_KMH;
    }

    static String normalizePressureUnit(String value) {
        String raw = value == null ? "" : value.trim();
        if (PRESSURE_INHG.equalsIgnoreCase(raw) || "in hg".equalsIgnoreCase(raw)) return PRESSURE_INHG;
        if (PRESSURE_MMHG.equalsIgnoreCase(raw) || "mm hg".equalsIgnoreCase(raw)) return PRESSURE_MMHG;
        return PRESSURE_HPA;
    }

    static String normalizeVisibilityUnit(String value) {
        String raw = value == null ? "" : value.trim();
        if (VISIBILITY_MI.equalsIgnoreCase(raw) || "mile".equalsIgnoreCase(raw)
                || "miles".equalsIgnoreCase(raw)) return VISIBILITY_MI;
        return VISIBILITY_KM;
    }

}
