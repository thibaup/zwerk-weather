package com.zwerk.weather;

import android.content.Context;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

final class WeatherTimeFormat {
    static final String PREF = "hour_format";
    static final String SYSTEM = "system";
    static final String TWELVE = "12";
    static final String TWENTY_FOUR = "24";

    private WeatherTimeFormat() { }

    static String selected(Context context) {
        String value = context.getSharedPreferences(WeatherPreferences.PREFS_NAME,
                Context.MODE_PRIVATE).getString(PREF, SYSTEM);
        return TWELVE.equals(value) || TWENTY_FOUR.equals(value) ? value : SYSTEM;
    }

    static boolean is24Hour(Context context) {
        String value = selected(context);
        return TWENTY_FOUR.equals(value) || SYSTEM.equals(value)
                && android.text.format.DateFormat.is24HourFormat(context);
    }

    static String label(Context context) {
        String value = selected(context);
        return context.getString(TWELVE.equals(value) ? R.string.settings_hour_format_12
                : TWENTY_FOUR.equals(value) ? R.string.settings_hour_format_24
                : R.string.language_system_default);
    }

    static String time(Context context, Instant instant, ZoneId zone) {
        if (instant == null) return "—";
        return instant.atZone(zone).format(formatter(context, false));
    }

    static String hour(Context context, long epochMillis, ZoneId zone) {
        if (epochMillis <= 0L) return "—";
        LocalTime time = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime();
        return time.format(formatter(context, time.getMinute() == 0));
    }

    static String clock(Context context, String clock) {
        try {
            LocalTime time = LocalTime.parse(clock, DateTimeFormatter.ofPattern("HH:mm",
                    Locale.getDefault()));
            return time.format(formatter(context, time.getMinute() == 0));
        } catch (Exception ignored) {
            return clock;
        }
    }

    static String dated(Context context, Instant instant, ZoneId zone, String datePattern) {
        if (instant == null) return "—";
        return DateTimeFormatter.ofPattern(datePattern, Locale.getDefault()).withZone(zone)
                .format(instant) + " " + time(context, instant, zone);
    }

    static String saved(Context context, long epochMillis, ZoneId zone) {
        Instant instant = Instant.ofEpochMilli(epochMillis);
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
                .withLocale(Locale.getDefault()).withZone(zone).format(instant)
                + " " + time(context, instant, zone);
    }

    private static DateTimeFormatter formatter(Context context, boolean wholeHour) {
        String pattern = is24Hour(context) ? "HH:mm" : wholeHour ? "h a" : "h:mm a";
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault());
    }
}
