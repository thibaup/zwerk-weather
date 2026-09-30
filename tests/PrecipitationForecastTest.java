package com.zwerk.weather;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;

public final class PrecipitationForecastTest {
    private static int checks;
    private static final long HOUR = 3_600_000L;

    public static void main(String[] args) throws Exception {
        long now = Instant.parse("2026-09-28T12:07:00Z").toEpochMilli();
        long until = PrecipitationWindow.end(now, 6);
        check(until - now == 6 * HOUR, "six hours are exact, including arbitrary minutes");
        check(PrecipitationWindow.end(now, 2) - now == 2 * HOUR, "two-hour range");
        long base = now - 7 * 60_000L;
        check(PrecipitationWindow.overlap(base, base + 15 * 60_000L, now, until) == 8 * 60_000L,
                "partial first fifteen-minute interval");
        check(PrecipitationWindow.overlap(until, until + HOUR, now, until) == 0,
                "bucket starting at six hours is excluded");
        check(PrecipitationWindow.overlap(now - HOUR, now, now, until) == 0,
                "elapsed interval excluded");
        check(PrecipitationWindow.overlap(until - 60_000, until + 14 * 60_000, now, until) == 60_000,
                "partial final interval stops at exact cutoff");
        JSONArray fortyFiveHours = new JSONArray();
        for (int i = 0; i < 45; i++) fortyFiveHours.put(hour(base + i * HOUR, 1d, 80));
        PrecipitationWindow.Summary summary = PrecipitationWindow.hourly(
                new JSONObject().put("forecastHours", fortyFiveHours), now, 6);
        check(summary.samples.size() == 7, "45-hour API response clipped to seven overlapping hourly buckets");
        check(Math.abs(summary.amountMm - 6d) < 0.000001, "overlap-weighted accumulation is six hours");
        check(summary.samples.get(0).start == now, "overview begins at now");
        check(summary.samples.get(6).end == until, "overview ends at exact six hours");
        check(summary.maxProbability == 80, "peak hourly probability, not summed probabilities");

        JSONObject missing = new JSONObject().put("forecastHours", new JSONArray().put(hour(base, null, 80)));
        check(PrecipitationWindow.hourly(missing, now, 6).amountMm == null, "missing amount never becomes zero");
        check(PrecipitationWindow.hourly(null, now, 6).amountMm == null, "no response is unavailable");
        JSONArray zeros = new JSONArray();
        for (int i = 0; i < 8; i++) zeros.put(hour(base + i * HOUR, 0d, 70));
        summary = PrecipitationWindow.hourly(new JSONObject().put("forecastHours", zeros), now, 6);
        check(summary.amountMm == 0 && summary.maxProbability == 70,
                "zero deterministic rain with nonzero ensemble chance stays truthful");
        JSONArray gap = new JSONArray();
        for (int i = 0; i < 8; i++) if (i != 3) gap.put(hour(base + i * HOUR, 1d, 10));
        check(PrecipitationWindow.hourly(new JSONObject().put("forecastHours", gap), now, 6).amountMm == null,
                "coverage gaps do not masquerade as a complete accumulation");
        fortyFiveHours.put(hour(base, 1d, 80));
        check(Math.abs(PrecipitationWindow.hourly(new JSONObject().put("forecastHours", fortyFiveHours), now, 6)
                .amountMm - 6d) < 0.000001, "duplicate intervals aren't counted twice");
        long[] hourEnds = {base, base + HOUR, base + 2 * HOUR};
        check(PrecipitationWindow.precedingProbabilityIndex(hourEnds, base + HOUR) == 1,
                "exact hour's probability describes its preceding hour");
        check(PrecipitationWindow.precedingProbabilityIndex(hourEnds, base + HOUR + 1) == 2,
                "next fifteen-minute bucket uses following hour's probability");
        check(PrecipitationWindow.precedingProbabilityIndex(hourEnds, base + 3 * HOUR) == -1,
                "missing probability coverage remains absent");
        check(PrecipitationModelPolicy.resolve("auto", 50.85, 4.35).equals("meteofrance_seamless"),
                "Belgium automatic precipitation gets Météo-France feed");
        check(PrecipitationModelPolicy.resolve("auto", 52.37, 4.9).equals("meteofrance_seamless"),
                "Netherlands automatic precipitation gets Météo-France feed");
        check(PrecipitationModelPolicy.resolve("auto", 35.68, 139.69).equals("auto"), "global automatic default preserved");
        check(PrecipitationModelPolicy.resolve("knmi_seamless", 50.85, 4.35).equals("knmi_seamless"),
                "manual model preserved");
        long dst = Instant.parse("2026-10-25T00:30:00Z").toEpochMilli();
        check(PrecipitationWindow.end(dst, 6) - dst == 6 * HOUR, "six real hours over daylight-saving change");
        System.out.println("PASS: " + checks + " precipitation forecast checks");
    }

    private static JSONObject hour(long start, Double quantity, int chance) throws Exception {
        JSONObject precipitation = new JSONObject().put("probability", new JSONObject().put("percent", chance));
        if (quantity != null) precipitation.put("qpf", new JSONObject().put("quantity", quantity).put("unit", "MILLIMETERS"));
        return new JSONObject().put("interval", new JSONObject().put("startTime", Instant.ofEpochMilli(start).toString())
                .put("endTime", Instant.ofEpochMilli(start + HOUR).toString())).put("precipitation", precipitation);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
