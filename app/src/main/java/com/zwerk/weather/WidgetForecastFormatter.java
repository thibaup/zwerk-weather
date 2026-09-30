package com.zwerk.weather;

import android.content.Context;
import org.json.JSONObject;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;

/** The same display units and JSON adapters used by the activity, without creating an activity. */
final class WidgetForecastFormatter implements WeatherWidgetSnapshotPublisher.Formatter {
    private final WeatherPreferences preferences;
    private final boolean fahrenheit;

    WidgetForecastFormatter(Context context) {
        preferences = new WeatherPreferences(context);
        fahrenheit = WeatherPreferences.TEMP_FAHRENHEIT.equals(preferences.temperatureUnit());
    }

    public Integer degreesOrNull(JSONObject value) {
        Double degrees = WeatherActivityFoundation.numberValue(value, "degrees");
        if (degrees == null) degrees = WeatherActivityFoundation.numberValue(value, "value");
        return degrees == null ? null : (int) Math.round(fahrenheit ? degrees * 9d / 5d + 32d : degrees);
    }
    public String description(JSONObject weather) { return WeatherActivityFoundation.description(weather); }
    public boolean safeBoolean(JSONObject object, String key, boolean fallback) {
        return WeatherActivityFoundation.safeBoolean(object, key, fallback);
    }
    public Instant parseInstant(String value) { return WeatherActivityFoundation.parseInstant(value); }
    public String formatTime(Instant value, ZoneId zone) { return WeatherActivityFoundation.formatTime(value, zone); }
    public String hourLabel(JSONObject hour, ZoneId zone) { return WeatherActivityFoundation.hourLabel(hour, zone); }
    public int probability(JSONObject weather) { return WeatherActivityFoundation.probability(weather); }
    public String temperatureUnitSymbol() { return fahrenheit ? "°F" : "°C"; }
    public String conditionKey(String condition) { return WeatherActivityFoundation.conditionKey(condition); }

    public String formatWindSpeed(JSONObject speed) {
        Double kmh = WeatherOverviewRenderingActivity.speedKilometersPerHour(speed);
        if (kmh == null) return "—";
        String unit = preferences.windUnit(fahrenheit);
        double amount = WeatherPreferences.WIND_MPH.equals(unit) ? kmh / 1.609344d
                : WeatherPreferences.WIND_MS.equals(unit) ? kmh / 3.6d
                : WeatherPreferences.WIND_KNOTS.equals(unit) ? kmh / 1.852d : kmh;
        return WeatherActivityFoundation.trimNumber(amount) + " " + unit;
    }
    public String formatPressure(JSONObject pressure) {
        Double hpa = WeatherOverviewRenderingActivity.pressureHpa(pressure);
        if (hpa == null) return "—";
        String unit = preferences.pressureUnit();
        if (WeatherPreferences.PRESSURE_INHG.equals(unit))
            return String.format(Locale.getDefault(), "%.2f %s", hpa * 0.0295299830714d, unit);
        if (WeatherPreferences.PRESSURE_MMHG.equals(unit))
            return String.format(Locale.getDefault(), "%.1f %s", hpa * 0.750061683d, unit);
        return Math.round(hpa) + " " + unit;
    }
    public String formatVisibility(JSONObject visibility) {
        Double km = WeatherOverviewRenderingActivity.visibilityKilometers(visibility);
        if (km == null) return "—";
        String unit = preferences.visibilityUnit(fahrenheit);
        return WeatherActivityFoundation.trimNumber(WeatherPreferences.VISIBILITY_MI.equals(unit)
                ? km / 1.609344d : km) + " " + unit;
    }
}
