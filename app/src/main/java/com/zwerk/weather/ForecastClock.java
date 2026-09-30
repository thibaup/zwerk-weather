package com.zwerk.weather;

final class ForecastClock {
    static final long FRESH_MILLIS = 60L * 60L * 1000L;
    static final long OFFLINE_MILLIS = 48L * FRESH_MILLIS;

    private ForecastClock() { }

    static long retentionMillis(boolean openMeteo) {
        // Google Weather current conditions and hourly forecasts may be cached for one hour.
        return openMeteo ? OFFLINE_MILLIS : FRESH_MILLIS;
    }

    static boolean withinAge(long fetchedAt, long now, long maxAge) {
        return fetchedAt > 0L && now >= fetchedAt && now - fetchedAt < maxAge;
    }

    static boolean contains(long start, long end, long now) {
        return start > 0L && end > start && start <= now && now < end;
    }

    static int currentHour(long[] starts, long[] ends, long now) {
        for (int i = 0; i < Math.min(starts.length, ends.length); i++) {
            if (contains(starts[i], ends[i], now)) return i;
        }
        return -1;
    }

    static int firstUnexpiredHour(long[] starts, long[] ends, long now) {
        for (int i = 0; i < Math.min(starts.length, ends.length); i++) {
            if (starts[i] > 0L && ends[i] > starts[i] && ends[i] > now) return i;
        }
        return -1;
    }
}
