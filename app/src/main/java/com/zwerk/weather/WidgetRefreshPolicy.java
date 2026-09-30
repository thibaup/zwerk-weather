package com.zwerk.weather;

/** Refresh before Google's one-hour expiry, and retry missing coverage without a UI reload. */
final class WidgetRefreshPolicy {
    static final long REFRESH_MILLIS = 45L * 60L * 1000L;
    private WidgetRefreshPolicy() { }

    static boolean needsRefresh(boolean hasSnapshot, boolean matchingScope,
            boolean hasCoverage, long fetchedAt, long now) {
        return !hasSnapshot || !matchingScope || !hasCoverage
                || !ForecastClock.withinAge(fetchedAt, now, REFRESH_MILLIS);
    }
}
