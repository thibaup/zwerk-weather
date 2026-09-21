package com.zwerk.weather;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Local, informational counters for the free radar and map tile providers. */
final class RadarUsageCounter {
    private static final String PREFS = "radar_request_usage_v1";
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private RadarUsageCounter() { }

    static synchronized void record(Context context, boolean rainViewer) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        LocalDate now = LocalDate.now(ZoneOffset.UTC);
        String prefix = rainViewer ? "rainviewer_" : "openstreetmap_";
        String day = prefix + "day_" + now.format(DAY);
        String month = prefix + "month_" + now.format(MONTH);
        prefs.edit().putInt(day, prefs.getInt(day, 0) + 1)
                .putInt(month, prefs.getInt(month, 0) + 1).apply();
    }

    static int today(Context context, boolean rainViewer) {
        String prefix = rainViewer ? "rainviewer_" : "openstreetmap_";
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(
                prefix + "day_" + LocalDate.now(ZoneOffset.UTC).format(DAY), 0);
    }

    static int month(Context context, boolean rainViewer) {
        String prefix = rainViewer ? "rainviewer_" : "openstreetmap_";
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(
                prefix + "month_" + LocalDate.now(ZoneOffset.UTC).format(MONTH), 0);
    }

    static void reset(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
