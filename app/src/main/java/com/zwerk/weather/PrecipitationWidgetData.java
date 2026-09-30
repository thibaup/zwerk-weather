package com.zwerk.weather;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.Locale;

/** Accumulated precipitation per ten-minute bin; missing coverage is never treated as dry. */
final class PrecipitationWidgetData {
    static final int HOURS = 6;
    static final int BIN_COUNT = HOURS * 6;
    static final long MINUTE_MILLIS = 60_000L;
    static final long BIN_MILLIS = 10L * MINUTE_MILLIS;

    final double[] amountsMm = new double[BIN_COUNT];
    final int knownBins;
    final double totalMm;
    final double peakMm;
    final boolean hasMinuteDetail;
    final boolean hasHourlyDetail;

    private static final class Interval {
        final long start;
        final long end;
        final double amountMm;

        Interval(long start, long end, double amountMm) {
            this.start = start;
            this.end = end;
            this.amountMm = amountMm;
        }

        boolean contains(long time) { return start <= time && time < end; }
    }

    PrecipitationWidgetData(JSONArray savedHours, JSONObject savedMinutes, long now) {
        Arrays.fill(amountsMm, Double.NaN);
        List<Interval> hourly = parseHourly(savedHours);
        List<Interval> minute = parseMinute(savedMinutes);
        int count = 0;
        double peak = 0d;
        double total = 0d;
        boolean fine = false;
        boolean coarse = false;
        for (int bin = 0; bin < BIN_COUNT; bin++) {
            long start = now + bin * BIN_MILLIS;
            long end = start + BIN_MILLIS;
            TreeSet<Long> boundaries = new TreeSet<>();
            boundaries.add(start);
            boundaries.add(end);
            addBoundaries(boundaries, hourly, start, end);
            addBoundaries(boundaries, minute, start, end);
            double amount = 0d;
            long covered = 0L;
            boolean binFine = false;
            boolean binCoarse = false;
            Long previous = null;
            for (long boundary : boundaries) {
                if (previous != null) {
                    Interval interval = intervalAt(minute, previous);
                    if (interval != null) binFine = true;
                    else {
                        interval = intervalAt(hourly, previous);
                        if (interval != null) binCoarse = true;
                    }
                    if (interval != null) {
                        long duration = boundary - previous;
                        amount += interval.amountMm * (duration / (double) (interval.end - interval.start));
                        covered += duration;
                    }
                }
                previous = boundary;
            }
            // Do not extrapolate a partial interval into a full bar or six-hour total.
            if (covered != BIN_MILLIS) continue;
            fine |= binFine;
            coarse |= binCoarse;
            amountsMm[bin] = amount;
            total += amount;
            peak = Math.max(peak, amount);
            count++;
        }
        knownBins = count;
        totalMm = total;
        peakMm = peak;
        hasMinuteDetail = fine;
        hasHourlyDetail = coarse;
    }

    static String locationScope(double latitude, double longitude, boolean openMeteo) {
        return locationScope(latitude, longitude, openMeteo ? "open_meteo" : "google");
    }

    static String locationScope(double latitude, double longitude, String providerAndModel) {
        return Double.toHexString(latitude) + '|' + Double.toHexString(longitude)
                + '|' + providerAndModel;
    }

    private static void addBoundaries(TreeSet<Long> boundaries, List<Interval> intervals,
            long start, long end) {
        for (Interval interval : intervals) {
            if (interval.start > start && interval.start < end) boundaries.add(interval.start);
            if (interval.end > start && interval.end < end) boundaries.add(interval.end);
        }
    }

    private static Interval intervalAt(List<Interval> intervals, long time) {
        for (Interval interval : intervals) {
            if (interval.contains(time)) return interval;
            if (interval.start > time) break;
        }
        return null;
    }

    private static List<Interval> parseHourly(JSONArray hours) {
        List<Interval> output = new ArrayList<>();
        if (hours == null) return output;
        for (int i = 0; i < Math.min(96, hours.length()); i++) {
            JSONObject hour = hours.optJSONObject(i);
            if (hour == null) continue;
            double amount = hour.optDouble("precipitationMm", Double.NaN);
            long start = hour.optLong("start", 0L);
            long end = hour.optLong("end", 0L);
            if (!Double.isFinite(amount) || amount < 0d || start <= 0L || end <= start) continue;
            output.add(new Interval(start, end, amount));
        }
        output.sort(Comparator.comparingLong(interval -> interval.start));
        return output;
    }

    private static List<Interval> parseMinute(JSONObject response) {
        List<Interval> output = new ArrayList<>();
        JSONArray segments = response == null ? null : response.optJSONArray("segments");
        if (segments == null) return output;
        for (int i = 0; i < Math.min(480, segments.length()); i++) {
            JSONObject segment = segments.optJSONObject(i);
            if (segment == null) continue;
            double amount = quantityMm(segment.optJSONObject("qpf"));
            JSONObject frame = segment.optJSONObject("timeFrame");
            if (frame == null || !Double.isFinite(amount)) continue;
            long start = epoch(frame.optString("startTime", ""));
            long end = epoch(frame.optString("endTime", ""));
            if (start <= 0L || end <= start) continue;
            output.add(new Interval(start, end, amount));
        }
        output.sort(Comparator.comparingLong(interval -> interval.start));
        return output;
    }

    private static long epoch(String value) {
        try { return Instant.parse(value).toEpochMilli(); }
        catch (Exception ignored) { return 0L; }
    }

    static double quantityMm(JSONObject qpf) {
        if (qpf == null) return Double.NaN;
        double quantity = qpf.optDouble("quantity", Double.NaN);
        if (!Double.isFinite(quantity) || quantity < 0d) return Double.NaN;
        String unit = qpf.optString("unit", "").toUpperCase(Locale.ROOT);
        if ("MILLIMETERS".equals(unit) || "MILLIMETER".equals(unit) || "MM".equals(unit)) return quantity;
        if ("INCHES".equals(unit) || "INCH".equals(unit)) return quantity * 25.4d;
        return Double.NaN;
    }

    static String amountLabel(double amount) {
        if (amount > 0d && amount < 0.01d) return "<0.01";
        return String.format(Locale.getDefault(), amount < 0.1d ? "%.2f" : "%.1f", amount);
    }

    static double chartMaximum(double peak) {
        double value = Math.max(0.1d, peak);
        double magnitude = Math.pow(10d, Math.floor(Math.log10(value)));
        double scaled = value / magnitude;
        return (scaled <= 1d ? 1d : scaled <= 2d ? 2d : scaled <= 5d ? 5d : 10d) * magnitude;
    }
}
