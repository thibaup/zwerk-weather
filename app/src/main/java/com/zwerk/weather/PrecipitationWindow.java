package com.zwerk.weather;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class PrecipitationWindow {
    static final long HOUR_MILLIS = 3_600_000L;

    static long end(long now, int hours) {
        return Math.addExact(now, Math.multiplyExact(Math.max(0, hours), HOUR_MILLIS));
    }

    static long overlap(long start, long end, long windowStart, long windowEnd) {
        if (end <= start || windowEnd <= windowStart) return 0;
        return Math.max(0L, Math.min(end, windowEnd) - Math.max(start, windowStart));
    }

    static int precedingProbabilityIndex(long[] hourEnds, long intervalEnd) {
        if (hourEnds == null) return -1;
        for (int i = 0; i < hourEnds.length; i++) {
            long end = hourEnds[i];
            if (end != Long.MIN_VALUE && intervalEnd > end - HOUR_MILLIS && intervalEnd <= end) return i;
        }
        return -1;
    }

    static final class Sample {
        final long start, end;
        final Double rateMmPerHour;
        final Integer probability;
        Sample(long start, long end, Double rate, Integer probability) {
            this.start = start; this.end = end; this.rateMmPerHour = rate; this.probability = probability;
        }
    }

    static final class Summary {
        final long start, end;
        final List<Sample> samples;
        final Double amountMm;
        final Integer maxProbability;
        Summary(long start, long end, List<Sample> samples, Double amount, Integer probability) {
            this.start = start; this.end = end; this.samples = samples;
            this.amountMm = amount; this.maxProbability = probability;
        }
    }

    static Summary hourly(JSONObject response, long now, int hours) {
        long until = end(now, hours);
        ArrayList<Sample> samples = new ArrayList<>();
        JSONArray array = response == null ? null : response.optJSONArray("forecastHours");
        if (array != null) for (int i = 0; i < array.length(); i++) {
            JSONObject hour = array.optJSONObject(i);
            if (hour == null) continue;
            JSONObject interval = hour.optJSONObject("interval");
            long start = timestamp(interval, "startTime"), end = timestamp(interval, "endTime");
            if (start == Long.MIN_VALUE || end == Long.MIN_VALUE) continue;
            if (overlap(start, end, now, until) == 0) continue;
            JSONObject precipitation = hour.optJSONObject("precipitation");
            JSONObject qpf = precipitation == null ? null : precipitation.optJSONObject("qpf");
            Double amount = millimeters(qpf);
            Double rate = amount == null ? null : amount * HOUR_MILLIS / (end - start);
            JSONObject probability = precipitation == null ? null : precipitation.optJSONObject("probability");
            Double percent = finite(probability, "percent");
            Integer chance = percent == null ? null : (int) Math.round(Math.max(0, Math.min(100, percent)));
            samples.add(new Sample(Math.max(now, start), Math.min(until, end), rate, chance));
        }
        samples.sort(Comparator.comparingLong(sample -> sample.start));
        long cursor = now;
        double total = 0;
        boolean complete = !samples.isEmpty();
        Integer maxProbability = null;
        for (Sample sample : samples) {
            if (sample.probability != null) maxProbability = maxProbability == null
                    ? sample.probability : Math.max(maxProbability, sample.probability);
            if (sample.start > cursor) complete = false;
            long start = Math.max(cursor, sample.start);
            if (sample.end <= start) continue; // Duplicate or overlapping provider intervals.
            if (sample.rateMmPerHour == null) complete = false;
            else total += sample.rateMmPerHour * (sample.end - start) / HOUR_MILLIS;
            cursor = sample.end;
        }
        if (cursor < until) complete = false;
        return new Summary(now, until, samples, complete ? total : null, maxProbability);
    }

    private static long timestamp(JSONObject object, String key) {
        try { return Instant.parse(object == null ? "" : object.optString(key)).toEpochMilli(); }
        catch (Exception ignored) { return Long.MIN_VALUE; }
    }

    private static Double finite(JSONObject object, String key) {
        if (object == null || object.isNull(key)) return null;
        double value = object.optDouble(key, Double.NaN);
        return Double.isFinite(value) ? value : null;
    }

    private static Double millimeters(JSONObject qpf) {
        Double amount = finite(qpf, "quantity");
        if (amount == null || amount < 0) return null;
        String unit = qpf.optString("unit", "").toUpperCase(Locale.ROOT);
        if ("MILLIMETERS".equals(unit) || "MM".equals(unit)) return amount;
        if ("INCHES".equals(unit) || "IN".equals(unit)) return amount * 25.4;
        return null;
    }
}
