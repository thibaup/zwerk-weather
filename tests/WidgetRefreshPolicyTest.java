package com.zwerk.weather;

public final class WidgetRefreshPolicyTest {
    public static void main(String[] args) {
        long now = 1_800_000_000_000L;
        int checks = 0;
        if (WidgetRefreshPolicy.needsRefresh(true, true, true, now, now)) throw new AssertionError("fresh snapshot causes extra requests"); checks++;
        if (!WidgetRefreshPolicy.needsRefresh(false, true, true, now, now)) throw new AssertionError("missing snapshot cannot recover"); checks++;
        if (!WidgetRefreshPolicy.needsRefresh(true, false, true, now, now)) throw new AssertionError("location or provider switch ignored"); checks++;
        if (!WidgetRefreshPolicy.needsRefresh(true, true, false, now, now)) throw new AssertionError("empty hourly response cannot recover"); checks++;
        if (WidgetRefreshPolicy.needsRefresh(true, true, true, now - WidgetRefreshPolicy.REFRESH_MILLIS + 1L, now)) throw new AssertionError("unnecessary early refresh"); checks++;
        if (!WidgetRefreshPolicy.needsRefresh(true, true, true, now - WidgetRefreshPolicy.REFRESH_MILLIS, now)) throw new AssertionError("refresh boundary ignored"); checks++;
        if (!WidgetRefreshPolicy.needsRefresh(true, true, true, now + 1L, now)) throw new AssertionError("future cache timestamp can wedge widget"); checks++;
        if (!WidgetRefreshPolicy.needsRefresh(true, true, true, 0L, now)) throw new AssertionError("invalid fetch timestamp can wedge widget"); checks++;
        if (WidgetRefreshPolicy.REFRESH_MILLIS >= ForecastClock.FRESH_MILLIS) throw new AssertionError("refresh must precede Google expiry"); checks++;
        System.out.println("Passed " + checks + " widget recovery policy checks.");
    }
}
