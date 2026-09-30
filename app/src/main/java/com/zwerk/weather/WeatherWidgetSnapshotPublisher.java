package com.zwerk.weather;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;

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

    boolean publish(JSONObject current, JSONObject today, JSONObject hourly, JSONObject daily,
            ZoneId zone, String locationName, double latitude, double longitude) {
        Integer temperature = formatter.degreesOrNull(current == null ? null : current.optJSONObject("temperature"));
        if (temperature == null) return false;

        String condition = formatter.description(current);
        String conditionType = SceneSpec.glyphCondition(current);
        boolean daytime = formatter.safeBoolean(current, "isDaytime", true);
        Instant observation = formatter.parseInstant(current == null ? null : current.optString("currentTime", null));
        if (observation == null) observation = Instant.now();
        String updated = "Updated " + formatter.formatTime(observation, zone);
        String advice = widgetAdvice(conditionType, updated);
        Integer high = formatter.degreesOrNull(today == null ? null : today.optJSONObject("maxTemperature"));
        Integer low = formatter.degreesOrNull(today == null ? null : today.optJSONObject("minTemperature"));

        JSONArray compactHours = new JSONArray();
        JSONArray hours = hourly == null ? null : hourly.optJSONArray("forecastHours");
        if (hours != null) {
            for (int i = 0; i < Math.min(96, hours.length()); i++) {
                JSONObject hour = hours.optJSONObject(i);
                if (hour == null) continue;
                Integer hourTemperature = formatter.degreesOrNull(hour.optJSONObject("temperature"));
                try {
                    JSONObject compact = new JSONObject();
                    long start = SavedForecast.epoch(hour, "startTime");
                    long end = SavedForecast.epoch(hour, "endTime");
                    if (start <= 0L || end <= start) continue;
                    compact.put("start", start);
                    compact.put("end", end);
                    compact.put("time", formatter.hourLabel(hour, zone));
                    if (hourTemperature != null) compact.put("temperature", hourTemperature);
                    compact.put("condition", formatter.description(hour));
                    compact.put("conditionType", SceneSpec.glyphCondition(hour));
                    compact.put("daytime", formatter.safeBoolean(hour, "isDaytime", true));
                    int precipitation = formatter.probability(hour);
                    if (precipitation >= 0) compact.put("precipitation", precipitation);
                    JSONObject rain = hour.optJSONObject("precipitation");
                    double amountMm = PrecipitationWidgetData.quantityMm(
                            rain == null ? null : rain.optJSONObject("qpf"));
                    if (Double.isFinite(amountMm)) compact.put("precipitationMm", amountMm);
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

        JSONArray compactDays = new JSONArray();
        JSONArray days = daily == null ? null : daily.optJSONArray("forecastDays");
        if (days != null) {
            for (int i = 0; i < Math.min(10, days.length()); i++) {
                JSONObject day = days.optJSONObject(i);
                if (day == null) continue;
                try {
                    JSONObject compact = new JSONObject();
                    compact.put("start", SavedForecast.epoch(day, "startTime"));
                    compact.put("end", SavedForecast.epoch(day, "endTime"));
                    Integer dayHigh = formatter.degreesOrNull(day.optJSONObject("maxTemperature"));
                    Integer dayLow = formatter.degreesOrNull(day.optJSONObject("minTemperature"));
                    if (dayHigh != null) compact.put("high", dayHigh);
                    if (dayLow != null) compact.put("low", dayLow);
                    JSONObject sun = day.optJSONObject("sunEvents");
                    if (sun != null) {
                        Instant sunrise = formatter.parseInstant(sun.optString("sunriseTime", null));
                        Instant sunset = formatter.parseInstant(sun.optString("sunsetTime", null));
                        if (sunrise != null) compact.put("sunrise", sunrise.toEpochMilli());
                        if (sunset != null) compact.put("sunset", sunset.toEpochMilli());
                    }
                    compactDays.put(compact);
                } catch (Exception ignored) { }
            }
        }
        long fetchedAt = current.optLong(SavedForecast.FETCHED_AT, observation.toEpochMilli());

        synchronized (WeatherWidgetSnapshotPublisher.class) {
            SharedPreferences prefs = context.getSharedPreferences(
                    WeatherWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE);
            String locationScope = PrecipitationWidgetData.locationScope(
                    latitude, longitude, current.has("_openMeteo")
                            ? OpenMeteoConfig.cacheScope(context) : OpenMeteoConfig.GOOGLE);
            boolean validExisting = prefs.getBoolean(WeatherWidgetProvider.KEY_HAS_SNAPSHOT, false)
                    && new WeatherWidgetTimeline(prefs, System.currentTimeMillis()).retained;
            // After expiry or a clock rollback, an invalidated watermark must not
            // reject a newly fetched response and leave recovery permanently blank.
            if (validExisting && locationScope.equals(prefs.getString(PrecipitationWidgetProvider.KEY_LOCATION_SCOPE, ""))
                    && fetchedAt < prefs.getLong(WeatherWidgetProvider.KEY_UPDATED_EPOCH_MS, 0L)) return true;
            // A rerender of the same cached response can temporarily lack optional pages.
            // Preserve its intervals; never renew older data using a new fetch timestamp.
                if (validExisting && locationScope.equals(prefs.getString(PrecipitationWidgetProvider.KEY_LOCATION_SCOPE, ""))
                        && fetchedAt == prefs.getLong(WeatherWidgetProvider.KEY_UPDATED_EPOCH_MS, 0L)
                        && formatter.temperatureUnitSymbol().equals(prefs.getString(WeatherWidgetProvider.KEY_UNIT, ""))) {
                    try {
                        if (compactHours.length() == 0) compactHours = new JSONArray(prefs.getString(
                                WeatherWidgetProvider.KEY_HOURLY_JSON, "[]"));
                        if (compactDays.length() == 0) compactDays = new JSONArray(prefs.getString(
                                WeatherWidgetProvider.KEY_DAILY_JSON, "[]"));
                    } catch (Exception ignored) { }
            }
            SharedPreferences.Editor editor = prefs.edit()
                    .putBoolean(WeatherWidgetProvider.KEY_HAS_SNAPSHOT, true)
                    .putString(WeatherWidgetProvider.KEY_CITY, locationName == null ? "Zwerk Weather" : locationName)
                    .putInt(WeatherWidgetProvider.KEY_TEMPERATURE, temperature)
                    .putString(WeatherWidgetProvider.KEY_UNIT, formatter.temperatureUnitSymbol())
                    .putString(WeatherWidgetProvider.KEY_CONDITION, condition)
                    .putString(WeatherWidgetProvider.KEY_CONDITION_TYPE, conditionType)
                    .putBoolean(WeatherWidgetProvider.KEY_DAYTIME, daytime)
                    .putString(WeatherWidgetProvider.KEY_ADVICE, advice)
                    .putString(WeatherWidgetProvider.KEY_HOURLY_JSON, compactHours.toString())
                    .putString(WeatherWidgetProvider.KEY_DAILY_JSON, compactDays.toString())
                    .putString(WeatherWidgetProvider.KEY_ZONE, zone.getId())
                    .putBoolean(WeatherWidgetProvider.KEY_OPEN_METEO, current.has("_openMeteo"))
                    .putBoolean(WeatherWidgetProvider.KEY_FALLBACK, current.optBoolean(SavedForecast.FALLBACK, false))
                    .putLong(WeatherWidgetProvider.KEY_OBSERVATION_EPOCH_MS, observation.toEpochMilli())
                    .putLong(WeatherWidgetProvider.KEY_UPDATED_EPOCH_MS, fetchedAt)
                    .putString(PrecipitationWidgetProvider.KEY_LOCATION_SCOPE, locationScope);
            if (!locationScope.equals(prefs.getString(
                    PrecipitationWidgetProvider.KEY_LOCATION_SCOPE, ""))) {
                editor.remove(PrecipitationWidgetProvider.KEY_MINUTE_SCOPE)
                        .remove(PrecipitationWidgetProvider.KEY_MINUTE_JSON)
                        .remove(PrecipitationWidgetProvider.KEY_MINUTE_SOURCE)
                        .remove(PrecipitationWidgetProvider.KEY_MINUTE_FETCHED_AT_MS);
            }
            if (high == null) editor.remove(WeatherWidgetProvider.KEY_DAILY_HIGH);
            else editor.putInt(WeatherWidgetProvider.KEY_DAILY_HIGH, high);
            if (low == null) editor.remove(WeatherWidgetProvider.KEY_DAILY_LOW);
            else editor.putInt(WeatherWidgetProvider.KEY_DAILY_LOW, low);
            editor.apply();
        }
        WeatherWidgetProvider.requestRefresh(context);
        return true;
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
