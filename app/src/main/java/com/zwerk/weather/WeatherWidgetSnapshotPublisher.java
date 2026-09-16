package com.zwerk.weather;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;

/** Publishes the exact snapshot schema consumed by {@link WeatherWidgetProvider}. */
final class WeatherWidgetSnapshotPublisher {
    interface Formatter {
        Integer degreesOrNull(JSONObject value);
        String description(JSONObject weather);
        boolean safeBoolean(JSONObject object, String key, boolean fallback);
        Instant parseInstant(String value);
        String formatTime(Instant instant, ZoneId zone);
        String hourLabel(JSONObject hour, ZoneId zone);
        int probability(JSONObject weather);
        String formatWindSpeed(JSONObject speed);
        String formatPressure(JSONObject pressure);
        String formatVisibility(JSONObject visibility);
        String temperatureUnitSymbol();
        String conditionKey(String condition);
    }

    private final Context context;
    private final Formatter formatter;

    WeatherWidgetSnapshotPublisher(Context context, Formatter formatter) {
        this.context = context;
        this.formatter = formatter;
    }

    void publish(JSONObject current, JSONObject today, JSONObject hourly, ZoneId zone, String locationName) {
        Integer temperature = formatter.degreesOrNull(current == null ? null : current.optJSONObject("temperature"));
        if (temperature == null) return;

        String condition = formatter.description(current);
        boolean daytime = formatter.safeBoolean(current, "isDaytime", true);
        Instant observation = formatter.parseInstant(current == null ? null : current.optString("currentTime", null));
        if (observation == null) observation = Instant.now();
        String updated = "Updated " + formatter.formatTime(observation, zone);
        String advice = widgetAdvice(condition, updated);
        Integer high = formatter.degreesOrNull(today == null ? null : today.optJSONObject("maxTemperature"));
        Integer low = formatter.degreesOrNull(today == null ? null : today.optJSONObject("minTemperature"));

        JSONArray compactHours = new JSONArray();
        JSONArray hours = hourly == null ? null : hourly.optJSONArray("forecastHours");
        if (hours != null) {
            for (int i = 0; i < Math.min(8, hours.length()); i++) {
                JSONObject hour = hours.optJSONObject(i);
                if (hour == null) continue;
                Integer hourTemperature = formatter.degreesOrNull(hour.optJSONObject("temperature"));
                try {
                    JSONObject compact = new JSONObject();
                    compact.put("time", formatter.hourLabel(hour, zone));
                    if (hourTemperature != null) compact.put("temperature", hourTemperature);
                    compact.put("condition", formatter.description(hour));
                    compact.put("daytime", formatter.safeBoolean(hour, "isDaytime", true));
                    int precipitation = formatter.probability(hour);
                    if (precipitation >= 0) compact.put("precipitation", precipitation);
                    JSONObject compactWind = hour.optJSONObject("wind");
                    String wind = formatter.formatWindSpeed(
                            compactWind == null ? null : compactWind.optJSONObject("speed"));
                    String pressure = formatter.formatPressure(hour.optJSONObject("airPressure"));
                    String visibility = formatter.formatVisibility(hour.optJSONObject("visibility"));
                    if (!"—".equals(wind)) compact.put("wind", wind);
                    if (!"—".equals(pressure)) compact.put("pressure", pressure);
                    if (!"—".equals(visibility)) compact.put("visibility", visibility);
                    compactHours.put(compact);
                } catch (Exception ignored) { }
            }
        }

        SharedPreferences.Editor editor = context.getSharedPreferences(
                WeatherWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(WeatherWidgetProvider.KEY_HAS_SNAPSHOT, true)
                .putString(WeatherWidgetProvider.KEY_CITY, locationName == null ? "Zwerk Weather" : locationName)
                .putInt(WeatherWidgetProvider.KEY_TEMPERATURE, temperature)
                .putString(WeatherWidgetProvider.KEY_UNIT, formatter.temperatureUnitSymbol())
                .putString(WeatherWidgetProvider.KEY_CONDITION, condition)
                .putBoolean(WeatherWidgetProvider.KEY_DAYTIME, daytime)
                .putString(WeatherWidgetProvider.KEY_ADVICE, advice)
                .putString(WeatherWidgetProvider.KEY_HOURLY_JSON, compactHours.toString())
                .putLong(WeatherWidgetProvider.KEY_UPDATED_EPOCH_MS, System.currentTimeMillis());
        if (high == null) editor.remove(WeatherWidgetProvider.KEY_DAILY_HIGH);
        else editor.putInt(WeatherWidgetProvider.KEY_DAILY_HIGH, high);
        if (low == null) editor.remove(WeatherWidgetProvider.KEY_DAILY_LOW);
        else editor.putInt(WeatherWidgetProvider.KEY_DAILY_LOW, low);
        editor.apply();
        WeatherWidgetProvider.requestRefresh(context);
    }

    private String widgetAdvice(String condition, String updated) {
        String key = formatter.conditionKey(condition);
        if ("thunder".equals(key)) return "Storm conditions • " + updated;
        if ("rain".equals(key)) return "Rain possible • " + updated;
        if ("snow".equals(key)) return "Snow possible • " + updated;
        if ("fog".equals(key)) return "Reduced visibility • " + updated;
        return updated;
    }
}
