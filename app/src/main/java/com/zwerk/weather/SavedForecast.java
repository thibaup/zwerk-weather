package com.zwerk.weather;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;

/** Adapts a saved forecast to the live clock without pretending an old observation is current. */
final class SavedForecast {
    static final String FETCHED_AT = "_forecastFetchedAtMillis";
    static final String FALLBACK = "_offlineFallback";

    private SavedForecast() { }

    static long epoch(JSONObject item, String key) {
        JSONObject interval = item == null ? null : item.optJSONObject("interval");
        try {
            return Instant.parse(interval == null ? "" : interval.optString(key, "")).toEpochMilli();
        } catch (Exception ignored) { return 0L; }
    }

    static JSONObject fallbackCurrent(JSONObject current, JSONObject hourly, JSONObject daily,
            long fetchedAt, long now) {
        try {
            JSONObject result = new JSONObject(current.toString());
            JSONArray hours = hourly.optJSONArray("forecastHours");
            if (!ForecastClock.withinAge(fetchedAt, now, ForecastClock.FRESH_MILLIS)) {
                result = new JSONObject();
                if (hours != null) {
                    for (int i = 0; i < hours.length(); i++) {
                        JSONObject hour = hours.optJSONObject(i);
                        long start = epoch(hour, "startTime");
                        if (ForecastClock.contains(start, epoch(hour, "endTime"), now)) {
                            result = new JSONObject(hour.toString());
                            result.remove("interval");
                            result.put("currentTime", Instant.ofEpochMilli(start).toString());
                            break;
                        }
                    }
                }
                if (current.has("timeZone")) result.put("timeZone", current.get("timeZone"));
                if (current.has("_openMeteo")) result.put("_openMeteo", current.get("_openMeteo"));
            }
            pruneExpired(hours, now);
            JSONArray days = daily.optJSONArray("forecastDays");
            pruneExpired(days, now);
            if (!ForecastClock.withinAge(fetchedAt, now, ForecastClock.FRESH_MILLIS)
                    && (hours == null || hours.length() == 0)
                    && (days == null || days.length() == 0)) return null;
            result.put(FETCHED_AT, fetchedAt);
            result.put(FALLBACK, true);
            DiagnosticLog.event(DiagnosticLog.Area.WEATHER, DiagnosticLog.Event.CACHE_FALLBACK);
            return result;
        } catch (Exception ignored) { return null; }
    }

    private static void pruneExpired(JSONArray items, long now) {
        if (items == null) return;
        for (int i = items.length() - 1; i >= 0; i--) {
            long end = epoch(items.optJSONObject(i), "endTime");
            if (end > 0L && end <= now) items.remove(i);
        }
    }
}
