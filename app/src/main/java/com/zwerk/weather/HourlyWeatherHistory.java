package com.zwerk.weather;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.function.Function;

/** Lazily loads the Google history shared by the Yesterday and Today details. */
final class HourlyWeatherHistory {
    private static final long CACHE_AGE_MILLIS = 5 * 60_000L;
    private static final String UNAVAILABLE = "Weather history is unavailable right now.";
    private final WeatherApiActivity activity;
    private Result result;

    HourlyWeatherHistory(WeatherApiActivity activity) {
        this.activity = activity;
    }

    Result load(boolean retry, Runnable onChanged) {
        if (OpenMeteoConfig.isOpenMeteo(activity)) return null;
        Scope scope = scope();
        long now = System.currentTimeMillis();
        if (result != null && result.scope.key.equals(scope.key)) {
            if (result.loading && result.scope.generation == scope.generation) {
                result.onChanged = onChanged;
                return result;
            }
            if (!retry && !result.loading && now >= result.receivedAt
                    && now - result.receivedAt < CACHE_AGE_MILLIS
                    && now / 3_600_000L == result.requestedAt / 3_600_000L) {
                return result;
            }
        }

        Result next = new Result(scope, onChanged);
        result = next;
        try {
            activity.executor.submit(() -> fetch(next));
        } catch (RuntimeException failure) {
            next.finish(null, UNAVAILABLE);
        }
        return next;
    }

    private void fetch(Result pending) {
        Scope scope = pending.scope;
        JSONArray hours = null;
        String message = null;
        try {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            String key = activity.readApiKey();
            if (key.isEmpty()) {
                message = "Configure a Google Weather API key to view history.";
            } else {
                String query = activity.weatherCommonQuery(key, scope.latitude, scope.longitude,
                        scope.language);
                JSONObject response = activity.requestLogical(activity.API_ROOT
                        + "history/hours:lookup" + query + "&hours=24&pageSize=24",
                        scope.generation, "history-hours");
                hours = response.optJSONArray("historyHours");
                if (hours == null) message = UNAVAILABLE;
            }
        } catch (WeatherApiActivity.SupersededWeatherRequestException ignored) {
            return;
        } catch (Exception failure) {
            message = failureMessage(failure);
        }

        JSONArray loaded = hours;
        String error = message;
        activity.runOnUiThread(() -> {
            if (pending != result || activity.isFinishing() || activity.isDestroyed()
                    || !activity.requestScopeCurrent(scope.generation)
                    || !scope().key.equals(scope.key)) return;
            Runnable onChanged = pending.onChanged;
            pending.finish(loaded, error);
            onChanged.run();
        });
    }

    private Scope scope() {
        String language = Locale.getDefault().toLanguageTag();
        String key = OpenMeteoConfig.cacheScope(activity) + "|" + activity.selectedLocationId
                + "|" + activity.latitude + "|" + activity.longitude + "|" + language;
        return new Scope(key, activity.weatherRequestGeneration,
                activity.latitude, activity.longitude, language);
    }

    static List<JSONObject> dayHours(JSONArray forecast, JSONArray history,
            LocalDate date, ZoneId zone, Instant now) {
        TreeMap<Instant, JSONObject> timeline = new TreeMap<>();
        ArrayList<JSONObject> undated = new ArrayList<>();
        appendDayHours(timeline, undated, forecast, date, zone, null);
        appendDayHours(timeline, undated, history, date, zone, now);
        ArrayList<JSONObject> hours = new ArrayList<>(timeline.values());
        hours.addAll(undated);
        return hours;
    }

    private static void appendDayHours(TreeMap<Instant, JSONObject> timeline,
            List<JSONObject> undated, JSONArray source, LocalDate date, ZoneId zone, Instant now) {
        if (source == null || date == null) return;
        for (int i = 0; i < source.length(); i++) {
            JSONObject hour = source.optJSONObject(i);
            if (!date.equals(WeatherActivityFoundation.hourLocalDate(hour, zone))) continue;
            Instant start = intervalBoundary(hour, "startTime", "start_time", "start");
            if (start == null) {
                if (now == null) undated.add(hour);
            } else if (now == null || !endOfHour(hour, start).isAfter(now)) {
                // Completed observations take precedence over cached forecast hours.
                timeline.put(start, hour);
            }
        }
    }

    record Summary(Integer high, Integer low, JSONObject representativeHour) {
        static final Summary EMPTY = new Summary(null, null, null);
    }

    static Summary summarize(List<JSONObject> hours, Function<JSONObject, Integer> degrees) {
        Integer high = null;
        Integer low = null;
        JSONObject representative = null;
        HashMap<String, Integer> conditionCounts = new HashMap<>();
        int mostFrequent = 0;
        for (JSONObject hour : hours) {
            Integer temperature = degrees.apply(hour.optJSONObject("temperature"));
            if (temperature != null) {
                if (high == null || temperature > high) high = temperature;
                if (low == null || temperature < low) low = temperature;
            }
            String condition = SceneSpec.glyphCondition(hour);
            if (condition.isEmpty() || "Unknown".equalsIgnoreCase(condition)
                    || condition.endsWith("UNSPECIFIED")) continue;
            int count = conditionCounts.merge(condition, 1, Integer::sum);
            if (count > mostFrequent) {
                mostFrequent = count;
                representative = hour;
            }
        }
        return new Summary(high, low, representative);
    }

    static int currentHour(List<JSONObject> hours, Instant now) {
        for (int i = 0; i < hours.size(); i++) {
            JSONObject hour = hours.get(i);
            Instant start = intervalBoundary(hour, "startTime", "start_time", "start");
            if (start != null && !start.isAfter(now) && endOfHour(hour, start).isAfter(now)) {
                return i;
            }
        }
        return -1;
    }

    private static Instant endOfHour(JSONObject hour, Instant start) {
        Instant end = intervalBoundary(hour, "endTime", "end_time", "end");
        return end != null && end.isAfter(start) ? end : start.plus(1, ChronoUnit.HOURS);
    }

    private static Instant intervalBoundary(JSONObject hour, String name, String snakeCase,
            String alias) {
        JSONObject interval = WeatherApiActivity.firstJSONObject(
                hour, "interval", "timeInterval", "time_interval");
        if (interval == null) return null;
        return WeatherActivityFoundation.parseInstant(WeatherActivityFoundation.firstNonEmpty(
                interval.optString(name), WeatherActivityFoundation.firstNonEmpty(
                        interval.optString(snakeCase), interval.optString(alias))));
    }

    private static String failureMessage(Exception failure) {
        if (failure instanceof WeatherApiActivity.WeatherRequestException) {
            WeatherApiActivity.WeatherRequestException request =
                    (WeatherApiActivity.WeatherRequestException) failure;
            if (request.statusCode == -2 || request.statusCode == 429) {
                return "Weather request limit reached. Try again later.";
            }
        }
        return "Couldn’t load weather history. Check your connection and try again.";
    }

    private record Scope(String key, int generation, double latitude, double longitude,
            String language) { }

    static final class Result {
        private final Scope scope;
        private final long requestedAt = System.currentTimeMillis();
        private Runnable onChanged;
        private long receivedAt;
        boolean loading = true;
        JSONArray hours;
        String error;

        private Result(Scope scope, Runnable onChanged) {
            this.scope = scope;
            this.onChanged = onChanged;
        }

        private void finish(JSONArray hours, String error) {
            this.hours = hours;
            this.error = error;
            receivedAt = System.currentTimeMillis();
            onChanged = null;
            loading = false;
        }
    }
}
