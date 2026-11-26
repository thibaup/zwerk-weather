package com.zwerk.weather;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;

/** Pure-Java checks for combined multi-day temperature + precipitation aggregation and scaling. */
public final class CombinedForecastWidgetDataTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void near(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < 1e-6, message + ": " + actual + " vs " + expected);
    }

    private static JSONObject compactDay(long start, long end, String date, int high, int low,
            Double mm, Integer prob, String condType) throws Exception {
        JSONObject day = new JSONObject()
                .put("start", start)
                .put("end", end)
                .put("date", date)
                .put("high", high)
                .put("low", low);
        if (mm != null) day.put("precipitationMm", mm);
        if (prob != null) day.put("precipitation", prob);
        if (condType != null) day.put("conditionType", condType);
        return day;
    }

    public static void main(String[] args) throws Exception {
        // Day-count clamping and style normalization.
        check(CombinedForecastData.clampWidgetDays(1) == 4, "minimum widget day count is 4");
        check(CombinedForecastData.clampWidgetDays(4) == 4, "4 days preserved");
        check(CombinedForecastData.clampWidgetDays(7) == 7, "7 days preserved");
        check(CombinedForecastData.clampWidgetDays(10) == 7, "maximum widget day count is 7");
        check(CombinedForecastData.PRECIP_STYLE_COLUMNS.equals(
                CombinedForecastData.normalizePrecipitationStyle(null)), "null style defaults to columns");
        check(CombinedForecastData.PRECIP_STYLE_COLUMNS.equals(
                CombinedForecastData.normalizePrecipitationStyle("bars")), "unknown style defaults to columns");
        check(CombinedForecastData.PRECIP_STYLE_LINE.equals(
                CombinedForecastData.normalizePrecipitationStyle("LINE")), "line style normalized case-insensitively");

        // Open-Meteo daily precipitation QPF + probability extraction.
        JSONObject openMeteoDay = new JSONObject()
                .put("displayDate", new JSONObject().put("year", 2026).put("month", 10).put("day", 1))
                .put("weatherCondition", new JSONObject().put("type", "RAIN"))
                .put("precipitation", new JSONObject()
                        .put("probability", new JSONObject().put("percent", 84))
                        .put("qpf", new JSONObject().put("quantity", 6.4).put("unit", "MILLIMETERS")));
        near(CombinedForecastData.dayPrecipitationMm(openMeteoDay), 6.4, "Open-Meteo top-level daily QPF");
        check(CombinedForecastData.dayProbability(openMeteoDay) == 84, "Open-Meteo top-level probability");
        check("RAIN".equals(CombinedForecastData.dayConditionType(openMeteoDay)), "Open-Meteo top-level condition");
        check("2026-10-01".equals(CombinedForecastData.dayIsoDate(openMeteoDay, ZoneId.of("UTC"))), "ISO date from displayDate");

        // Google Weather daytime + nighttime precipitation sum & inches conversion.
        JSONObject googleDay = new JSONObject()
                .put("daytimeForecast", new JSONObject()
                        .put("weatherCondition", new JSONObject().put("type", "PARTLY_CLOUDY"))
                        .put("precipitation", new JSONObject()
                                .put("probability", new JSONObject().put("percent", 30))
                                .put("qpf", new JSONObject().put("quantity", 0.1).put("unit", "INCHES"))))
                .put("nighttimeForecast", new JSONObject()
                        .put("precipitation", new JSONObject()
                                .put("probability", new JSONObject().put("percent", 65))
                                .put("qpf", new JSONObject().put("quantity", 2.0).put("unit", "MILLIMETERS"))));
        near(CombinedForecastData.dayPrecipitationMm(googleDay), 4.54, "Google daytime + nighttime QPF summed in mm");
        check(CombinedForecastData.dayProbability(googleDay) == 65, "daily probability takes max across day and night");
        check("PARTLY_CLOUDY".equals(CombinedForecastData.dayConditionType(googleDay)), "daytime condition preferred");

        // Missing precipitation never fabricates zero.
        JSONObject missingDay = new JSONObject().put("maxTemperature", new JSONObject().put("degrees", 20));
        check(Double.isNaN(CombinedForecastData.dayPrecipitationMm(missingDay)), "missing QPF stays NaN");
        check(CombinedForecastData.dayProbability(missingDay) == -1, "missing probability stays -1");

        // Timeline parsing, elapsed day drop, day count slicing (4 vs 7), and scale calculations.
        ZoneId zone = ZoneId.of("Europe/Brussels");
        long day0 = LocalDate.of(2026, 9, 30).atStartOfDay(zone).toInstant().toEpochMilli();
        long dayMs = 24L * 3_600_000L;
        long now = day0 + 10L * 3_600_000L; // 10:00 on 30 Sep

        JSONArray days = new JSONArray()
                // Elapsed yesterday should be dropped.
                .put(compactDay(day0 - dayMs, day0, "2026-09-29", 18, 10, 5.0, 90, "RAIN"))
                .put(compactDay(day0, day0 + dayMs, "2026-09-30", 19, 11, 6.4, 84, "RAIN"))
                .put(compactDay(day0 + dayMs, day0 + 2 * dayMs, "2026-10-01", 21, 12, 0.3, 24, "PARTLY_CLOUDY"))
                .put(compactDay(day0 + 2 * dayMs, day0 + 3 * dayMs, "2026-10-02", 24, 13, 0.0, 0, "CLEAR"))
                .put(compactDay(day0 + 3 * dayMs, day0 + 4 * dayMs, "2026-10-03", 22, 12, 1.8, 45, "PARTLY_CLOUDY"))
                .put(compactDay(day0 + 4 * dayMs, day0 + 5 * dayMs, "2026-10-04", 17, 9, 12.5, 92, "THUNDERSTORM"))
                .put(compactDay(day0 + 5 * dayMs, day0 + 6 * dayMs, "2026-10-05", 16, 8, 2.1, 40, "CLOUDY"))
                .put(compactDay(day0 + 6 * dayMs, day0 + 7 * dayMs, "2026-10-06", 20, 10, 0.0, 10, "CLEAR"))
                .put(compactDay(day0 + 7 * dayMs, day0 + 8 * dayMs, "2026-10-07", 23, 12, 0.0, 5, "CLEAR"));

        Prefs prefs = new Prefs();
        prefs.values.put("updated_epoch_ms", now - 30 * 60_000L);
        prefs.values.put("open_meteo", true);
        prefs.values.put("zone", "Europe/Brussels");
        prefs.values.put("daily_json", days.toString());
        prefs.values.put("hourly_json", "[]");

        WeatherWidgetTimeline timeline = new WeatherWidgetTimeline(prefs, now);
        CombinedForecastData fiveDays = CombinedForecastData.fromWidgetTimeline(timeline, now, 5, false);
        check(fiveDays.days.size() == 5, "5-day slice drops yesterday and keeps 5 days");
        check(fiveDays.days.get(0).today && "2026-09-30".equals(fiveDays.days.get(0).date.toString()), "first day is Today");
        check(!fiveDays.days.get(1).today, "subsequent days are not Today");
        check(fiveDays.minTemperature == 9 && fiveDays.maxTemperature == 24, "unified temperature range across 5 days");
        check(fiveDays.hasPrecipitationAmount && !fiveDays.usingProbabilityScale, "uses amount scale when mm present");
        near(fiveDays.peakMm, 12.5, "peak daily amount computed");
        near(fiveDays.totalMm, 21.0, "total daily amount summed accurately");
        near(fiveDays.scaleMaxDisplay, 20.0, "12.5 mm scales to clean 20 mm ceiling");
        near(fiveDays.precipitationFraction(fiveDays.days.get(4)), 12.5 / 20.0, "peak fraction matches scale");
        near(fiveDays.precipitationFraction(fiveDays.days.get(2)), 0.0, "dry day fraction stays exactly 0");

        CombinedForecastData sevenDays = CombinedForecastData.fromWidgetTimeline(timeline, now, 7, false);
        check(sevenDays.days.size() == 7, "7-day slice supported");
        check(sevenDays.minTemperature == 8 && sevenDays.maxTemperature == 24, "7-day range updates min temp");

        // Probability-only fallback when daily amounts are absent.
        JSONArray probOnlyDays = new JSONArray()
                .put(compactDay(day0, day0 + dayMs, "2026-09-30", 19, 11, null, 80, "RAIN"))
                .put(compactDay(day0 + dayMs, day0 + 2 * dayMs, "2026-10-01", 21, 12, null, 20, "CLOUDY"));
        prefs.values.put("daily_json", probOnlyDays.toString());
        CombinedForecastData probOnly = CombinedForecastData.fromWidgetTimeline(
                new WeatherWidgetTimeline(prefs, now), now, 4, false);
        check(!probOnly.hasPrecipitationAmount && probOnly.usingProbabilityScale, "falls back to probability scale honestly");
        near(probOnly.scaleMaxDisplay, 100.0, "probability scale ceiling is 100");
        near(probOnly.precipitationFraction(probOnly.days.get(0)), 0.8, "80% probability maps to 0.8");

        // Legacy daily snapshot (high/low only) enriched from hourly intervals in the same snapshot.
        JSONArray legacyDays = new JSONArray()
                .put(new JSONObject().put("start", day0).put("end", day0 + dayMs).put("high", 19).put("low", 11));
        JSONArray hours = new JSONArray()
                .put(new JSONObject().put("start", day0 + 11 * 3_600_000L).put("end", day0 + 12 * 3_600_000L)
                        .put("precipitationMm", 1.2).put("precipitation", 60).put("conditionType", "RAIN").put("daytime", true))
                .put(new JSONObject().put("start", day0 + 12 * 3_600_000L).put("end", day0 + 13 * 3_600_000L)
                        .put("precipitationMm", 0.8).put("precipitation", 75).put("conditionType", "CLOUDY").put("daytime", true));
        prefs.values.put("daily_json", legacyDays.toString());
        prefs.values.put("hourly_json", hours.toString());
        CombinedForecastData legacy = CombinedForecastData.fromWidgetTimeline(
                new WeatherWidgetTimeline(prefs, now), now, 5, false);
        check(legacy.days.size() == 1, "legacy daily snapshot still parsed");
        near(legacy.days.get(0).precipitationMm, 2.0, "legacy day sums hourly precipitation");
        check(legacy.days.get(0).probability == 75, "legacy day takes max hourly probability");
        check("RAIN".equals(legacy.days.get(0).conditionType), "legacy day derives most severe hourly condition");

        // Expired snapshot yields empty data.
        prefs.values.put("open_meteo", false);
        prefs.values.put("updated_epoch_ms", now - 2L * 3_600_000L);
        CombinedForecastData expired = CombinedForecastData.fromWidgetTimeline(
                new WeatherWidgetTimeline(prefs, now), now, 5, false);
        check(expired.isEmpty(), "expired snapshot never renders stale multi-day forecast");

        // Realistic meteorological floors: tiny 0.1 mm drizzle stays low near baseline.
        near(CombinedForecastData.dailyRainScaleMax(0.0, false), 10.0, "dry metric scale keeps 10 mm minimum");
        near(CombinedForecastData.dailyRainScaleMax(0.1, false), 10.0, "0.1 mm drizzle uses 10 mm floor");
        near(CombinedForecastData.dailyRainScaleMax(10.0, false), 10.0, "10 mm stays at 10 mm ceiling");
        JSONArray drizzleDays = new JSONArray()
                .put(compactDay(day0, day0 + dayMs, "2026-09-30", 18, 11, 0.1, 20, "RAIN"))
                .put(compactDay(day0 + dayMs, day0 + 2 * dayMs, "2026-10-01", 20, 12, 0.0, 0, "CLEAR"));
        prefs.values.put("open_meteo", true);
        prefs.values.put("updated_epoch_ms", now - 30 * 60_000L);
        prefs.values.put("daily_json", drizzleDays.toString());
        prefs.values.put("hourly_json", "[]");
        CombinedForecastData drizzleMetric = CombinedForecastData.fromWidgetTimeline(
                new WeatherWidgetTimeline(prefs, now), now, 4, false);
        near(drizzleMetric.scaleMaxDisplay, 10.0, "drizzle timeline enforces 10 mm ceiling");
        float drizzleFrac = drizzleMetric.precipitationFraction(drizzleMetric.days.get(0));
        near(drizzleFrac, 0.01, "0.1 mm daily drizzle maps to 1% height");
        check(drizzleFrac <= 0.02f, "0.1 mm daily drizzle stays tiny on chart");

        CombinedForecastData drizzleImperial = CombinedForecastData.fromWidgetTimeline(
                new WeatherWidgetTimeline(prefs, now), now, 4, true);
        near(drizzleImperial.scaleMaxDisplay, 0.4, "drizzle timeline enforces 0.4 in ceiling");
        check(drizzleImperial.precipitationFraction(drizzleImperial.days.get(0)) <= 0.02f,
                "0.1 mm daily drizzle stays tiny on imperial chart");

        // Imperial inch scaling & formatting.
        near(CombinedForecastData.dailyRainScaleMax(0.0, true), 0.4, "dry inch scale keeps 0.4 in minimum");
        near(CombinedForecastData.dailyRainScaleMax(12.7, true), 1.0, "0.5 in peak maps to 1.0 in ceiling");
        check(CombinedForecastData.formatCompactAmount(12.7, true, true).endsWith("in"), "inch unit formatted for Fahrenheit");
        check(CombinedForecastData.formatCompactAmount(6.4, false, true).endsWith("mm"), "mm unit formatted for Celsius");

        System.out.println("Passed " + checks + " combined multi-day forecast checks.");
    }

    private static final class Prefs implements SharedPreferences {
        final HashMap<String, Object> values = new HashMap<>();
        public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        public long getLong(String key, long fallback) { return (Long) values.getOrDefault(key, fallback); }
        public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
    }
}
