package com.zwerk.weather;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;

/** Regression cases for reopened days, original cache age and superseded workers. */
public final class ForecastCachingTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        ForecastDiskCache cache = new ForecastDiskCache(new Context(
                Files.createTempDirectory("zwerk-expanded-cache-").toFile()));
        long fetchedAt = System.currentTimeMillis() - 120_000L;
        JSONObject current = new JSONObject().put("temperature", new JSONObject().put("degrees", 18));
        JSONObject daily = new JSONObject().put("forecastDays", new JSONArray());
        JSONArray hours = new JSONArray();
        for (int i = 0; i < 72; i++) hours.put(new JSONObject().put("interval", new JSONObject()
                .put("startTime", Instant.ofEpochMilli(fetchedAt + i * 3_600_000L).toString())));
        JSONObject hourly = new JSONObject().put("forecastHours", hours)
                .put(ForecastDiskCache.HOURLY_NEXT_TOKEN, "page-four")
                .put(ForecastDiskCache.HOURLY_TERMINAL, false)
                .put(ForecastDiskCache.HOURLY_RESTART, false);
        cache.persist(50.85, 4.35, "en", "city|google", current, hourly, daily, fetchedAt);
        ForecastDiskCache.Snapshot reopened = cache.readFresh(50.85, 4.35, "en", "city|google");
        check(reopened != null, "expanded forecast survives reopening");
        check(reopened.hourly.getJSONArray("forecastHours").length() == 72,
                "all three fetched pages survive rather than only the first 24 hours");
        check("page-four".equals(reopened.hourly.getString(ForecastDiskCache.HOURLY_NEXT_TOKEN)),
                "continuation resumes after loaded pages");
        check(!reopened.hourly.getBoolean(ForecastDiskCache.HOURLY_RESTART),
                "reopening does not restart page one");
        check(reopened.fetchedAtMillis == fetchedAt, "original fetch timestamp survives");

        hours.put(new JSONObject().put("interval", new JSONObject().put("startTime", "2026-10-02T00:00:00Z")));
        hourly.put(ForecastDiskCache.HOURLY_NEXT_TOKEN, "").put(ForecastDiskCache.HOURLY_TERMINAL, true);
        cache.persistContinuation(50.85, 4.35, "en", "city|google", current, hourly, daily, fetchedAt);
        reopened = cache.readFresh(50.85, 4.35, "en", "city|google");
        check(reopened.hourly.getJSONArray("forecastHours").length() == 73, "continuation is persisted");
        check(reopened.hourly.getBoolean(ForecastDiskCache.HOURLY_TERMINAL), "complete coverage is persisted");
        check(reopened.fetchedAtMillis == fetchedAt, "adding hours does not make old weather fresh");

        long refreshedAt = System.currentTimeMillis() - 1_000L;
        JSONObject refreshed = new JSONObject().put("temperature", new JSONObject().put("degrees", 24));
        JSONObject refreshedHourly = new JSONObject(hourly.toString());
        cache.persist(50.85, 4.35, "en", "city|google", refreshed, refreshedHourly, daily, refreshedAt);
        cache.persistContinuation(50.85, 4.35, "en", "city|google", current, hourly, daily, fetchedAt);
        reopened = cache.readFresh(50.85, 4.35, "en", "city|google");
        check(reopened.current.getJSONObject("temperature").getInt("degrees") == 24,
                "a delayed continuation cannot overwrite a newer base forecast");
        check(reopened.fetchedAtMillis == refreshedAt, "newer base timestamp is preserved");
        JSONObject clockRollbackHourly = new JSONObject(refreshedHourly.toString());
        cache.persist(50.85, 4.35, "en", "city|google", refreshed, clockRollbackHourly, daily, fetchedAt);
        cache.persistContinuation(50.85, 4.35, "en", "city|google", current, refreshedHourly, daily, fetchedAt);
        reopened = cache.readFresh(50.85, 4.35, "en", "city|google");
        check(reopened.current.getJSONObject("temperature").getInt("degrees") == 24,
                "same timestamp cannot let an old revision overwrite a new forecast");
        check(reopened.fetchedAtMillis == fetchedAt, "a new base remains cacheable after a clock adjustment");
        check(reopened.hourly.getString(ForecastDiskCache.BASE_REVISION)
                .equals(clockRollbackHourly.getString(ForecastDiskCache.BASE_REVISION)), "base ownership survives clock changes");
        cache.persist(51.0, 4.35, "en", "expired|google", current, hourly, daily,
                System.currentTimeMillis() - ForecastClock.FRESH_MILLIS - 1L);
        check(cache.readLastKnown(51.0, 4.35, "en", "expired|google") == null,
                "writing a continuation does not extend the retention limit");

        HourlyCoverageQueue queue = new HourlyCoverageQueue();
        Object firstForecast = new Object(), newerForecast = new Object();
        LocalDate monday = LocalDate.of(2026, 9, 28), tuesday = monday.plusDays(1);
        check(queue.request(firstForecast, monday), "first selection starts a worker");
        check(!queue.request(firstForecast, tuesday), "another date coalesces into that worker");
        check(queue.targetFor(firstForecast).equals(tuesday), "latest day takes priority");
        queue.reset();
        check(queue.request(newerForecast, monday), "new forecast starts independently");
        check(!queue.finish(firstForecast, false), "old worker cannot restart itself");
        queue.fail(firstForecast);
        queue.cancel(firstForecast, monday);
        check(queue.isLoading(newerForecast, monday), "old cleanup cannot clear the new loading state");
        check(queue.targetFor(newerForecast).equals(monday), "old cleanup cannot lose the new selection");
        queue.cancel(newerForecast, monday);
        check(!queue.request(newerForecast, tuesday), "selection arriving as worker ends is coalesced");
        check(queue.finish(newerForecast, true), "late selection restarts coverage");
        check(queue.isLoading(newerForecast, tuesday), "late selection retains its loading indicator");
        queue.fail(newerForecast);
        check(!queue.finish(newerForecast, true), "failure ends automatic requests");
        check(!queue.isLoading(newerForecast, tuesday), "failed day is no longer loading");
        check(queue.beginRetry(newerForecast), "manual retry can start after failure");
        check(!queue.beginRetry(newerForecast), "duplicate manual retries coalesce");
        queue.finish(newerForecast, true);
        System.out.println("Passed " + checks + " expanded-cache/worker checks.");
    }
}
