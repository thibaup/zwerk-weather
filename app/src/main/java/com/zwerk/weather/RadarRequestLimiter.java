package com.zwerk.weather;

import java.util.ArrayDeque;

/** Shared rolling request budget and server cooldown, measured with a monotonic clock. */
final class RadarRequestLimiter {
    private final int maximum;
    private final long windowMillis;
    private final ArrayDeque<Long> requests = new ArrayDeque<>();
    private long serverUntil;

    RadarRequestLimiter(int maximum, long windowMillis) {
        this.maximum = maximum;
        this.windowMillis = windowMillis;
    }

    synchronized long delayMillis(long now) {
        while (!requests.isEmpty() && now - requests.peekFirst() >= windowMillis) requests.removeFirst();
        long local = requests.size() < maximum ? 0L : Math.max(0L, requests.peekFirst() + windowMillis - now);
        return Math.max(local, Math.max(0L, serverUntil - now));
    }

    /** Zero reserves one request; a positive return is the wait and does not consume a slot. */
    synchronized long acquire(long now) {
        long delay = delayMillis(now);
        if (delay == 0L) requests.addLast(now);
        return delay;
    }

    synchronized void serverLimited(long now, long retryAfterMillis) {
        serverUntil = Math.max(serverUntil, now + Math.max(1_000L, retryAfterMillis));
    }

    synchronized boolean isServerLimited(long now) { return now < serverUntil; }

    static long retryAfterMillis(String value, long wallClockMillis) {
        try {
            long seconds = Long.parseLong(value.trim());
            if (seconds >= 0L) return seconds >= 600L ? 600_000L : Math.max(1_000L, seconds * 1000L);
        } catch (Exception ignored) { }
        try {
            long until = java.time.ZonedDateTime.parse(value,
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
            return Math.max(1_000L, Math.min(600_000L, until - wallClockMillis));
        } catch (Exception ignored) { return 60_000L; }
    }
}
