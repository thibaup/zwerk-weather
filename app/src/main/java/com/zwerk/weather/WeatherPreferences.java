package com.zwerk.weather;

import android.content.Context;
import android.content.SharedPreferences;

/** Centralizes persisted weather-display preferences and value translation. */
final class WeatherPreferences {
    static final String PREFS_NAME = "WEATHER_UI";
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
        return preferences.getBoolean("google_air_quality_enabled", false);
    }

    boolean pollenEnabled() {
        return preferences.getBoolean("google_pollen_enabled", false);
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
