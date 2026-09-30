package com.zwerk.weather;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;

public final class PrecipitationWidgetDataTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void near(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < 1e-9, message + ": " + actual);
    }
    private static JSONObject hour(long start, double amount) throws Exception {
        JSONObject hour = new JSONObject().put("start", start).put("end", start + 3_600_000L);
        if (amount >= 0) hour.put("precipitationMm", amount);
        return hour;
    }
    private static JSONObject minute(long start, int lengthMinutes, double amount) throws Exception {
        JSONObject segment = new JSONObject().put("timeFrame", new JSONObject()
                .put("startTime", Instant.ofEpochMilli(start).toString())
                .put("endTime", Instant.ofEpochMilli(start + lengthMinutes * 60_000L).toString()));
        if (amount >= 0) segment.put("qpf", new JSONObject().put("quantity", amount).put("unit", "MILLIMETERS"));
        return segment;
    }
    public static void main(String[] args) throws Exception {
        long now = Instant.parse("2026-09-29T16:40:00Z").toEpochMilli();
        long hourStart = now - 40 * 60_000L;
        JSONArray hours = new JSONArray();
        for (int i = 0; i < 7; i++) hours.put(hour(hourStart + i * 3_600_000L, (i + 1) * .6));
        PrecipitationWidgetData data = new PrecipitationWidgetData(hours, null, now);
        check(data.knownBins == 36, "all six hours have complete quantity coverage");
        near(data.amountsMm[0], .1, "hourly amount apportioned across ten-minute bars");
        near(data.amountsMm[1], .1, "current interval remains until its end");
        near(data.amountsMm[2], .2, "hour transition uses next quantity");
        near(data.amountsMm[35], .7, "sixth hour extends to exact end of window");
        near(data.peakMm, .7, "peak is an amount rather than a probability");
        check(!data.hasMinuteDetail && data.hasHourlyDetail, "hourly source labelled honestly");
        // 20 minutes from first hour, five full hours, and 40 minutes from last hour.
        near(data.totalMm, .2 + 1.2 + 1.8 + 2.4 + 3 + 3.6 + 2.8, "cropped hourly totals conserved");

        JSONObject minutes = new JSONObject().put("segments", new JSONArray()
                .put(minute(now, 10, .85)).put(minute(now + 600_000L, 5, .2)));
        PrecipitationWidgetData mixed = new PrecipitationWidgetData(hours, minutes, now);
        near(mixed.amountsMm[0], .85, "minute QPF overrides hourly QPF");
        near(mixed.amountsMm[1], .25, "minute and hourly portions integrated without double counting");
        near(mixed.amountsMm[2], .2, "hourly fallback resumes outside finer interval");
        check(mixed.hasMinuteDetail && mixed.hasHourlyDetail, "mixed source detected");
        near(mixed.totalMm, data.totalMm + .9, "replacement amount changes total only once");

        JSONObject quarterHours = new JSONObject().put("segments", new JSONArray()
                .put(minute(now, 15, .3)).put(minute(now + 900_000L, 15, .6)));
        PrecipitationWidgetData quarter = new PrecipitationWidgetData(null, quarterHours, now);
        near(quarter.amountsMm[0], .2, "15-minute amount is divided by interval duration");
        near(quarter.amountsMm[1], .3, "ten-minute bin can straddle two 15-minute segments");
        near(quarter.amountsMm[2], .4, "remaining 15-minute amount conserved");
        near(quarter.totalMm, .9, "Open-Meteo interval quantities are not multiplied by sample count");
        check(quarter.knownBins == 3 && Double.isNaN(quarter.amountsMm[3]), "missing later intervals stay gaps");

        JSONObject dry = new JSONObject().put("segments", new JSONArray().put(minute(now, 360, 0).put("probability", 0)));
        PrecipitationWidgetData dryData = new PrecipitationWidgetData(hours, dry, now);
        check(dryData.knownBins == 36 && dryData.hasMinuteDetail && !dryData.hasHourlyDetail, "dry finer data overrides wet coarse forecast");
        for (double amount : dryData.amountsMm) near(amount, 0, "zero precipitation remains exactly zero");
        near(dryData.totalMm, 0, "no synthetic amount from chance or missing markers");
        PrecipitationWidgetData probabilityOnly = new PrecipitationWidgetData(
                new JSONArray().put(hour(now, -1).put("precipitation", 100)),
                new JSONObject().put("segments", new JSONArray().put(minute(now, 10, -1).put("probability", 100))), now);
        check(probabilityOnly.knownBins == 0, "probability cannot fabricate a precipitation quantity");
        PrecipitationWidgetData partial = new PrecipitationWidgetData(null,
                new JSONObject().put("segments", new JSONArray().put(minute(now, 9, .9))), now);
        check(partial.knownBins == 0 && Double.isNaN(partial.amountsMm[0]), "incomplete bins never extrapolated");

        near(PrecipitationWidgetData.quantityMm(new JSONObject().put("quantity", .1).put("unit", "INCHES")), 2.54, "inches converted to millimetres");
        check(Double.isNaN(PrecipitationWidgetData.quantityMm(new JSONObject().put("quantity", 1).put("unit", "UNKNOWN"))), "unknown units remain missing");
        check(Double.isNaN(PrecipitationWidgetData.quantityMm(new JSONObject().put("quantity", -1).put("unit", "MM"))), "negative quantities rejected");
        check(Double.isNaN(PrecipitationWidgetData.quantityMm(new JSONObject().put("unit", "MM"))), "absent quantity is not zero");
        near(PrecipitationWidgetData.chartMaximum(0), .1, "dry graph retains meaningful scale");
        near(PrecipitationWidgetData.chartMaximum(.71), 1, "axis contains positive peak");
        near(PrecipitationWidgetData.chartMaximum(1.2), 2, "axis adapts to larger quantities");
        check(!PrecipitationWidgetData.locationScope(51.5, -.1, "google").equals(
                PrecipitationWidgetData.locationScope(51.5, -.1, "open_meteo:auto")), "scope separates sources");
        check(!PrecipitationWidgetData.locationScope(51.5, -.1, "open_meteo:auto").equals(
                PrecipitationWidgetData.locationScope(51.5, -.1, "open_meteo:ecmwf_ifs")), "scope separates models");
        // An unaligned clock must conserve the amount across sub-minute boundaries.
        PrecipitationWidgetData unaligned = new PrecipitationWidgetData(hours, null, now + 30_000L);
        near(unaligned.amountsMm[1], .105, "absolute time boundaries integrated to the millisecond");
        JSONObject duplicate = new JSONObject().put("segments", new JSONArray()
                .put(minute(now, 10, .5)).put(minute(now, 10, .5)));
        near(new PrecipitationWidgetData(null, duplicate, now).totalMm, .5, "duplicate intervals never double counted");
        System.out.println("Passed " + checks + " six-hour quantity widget checks.");
    }
}
