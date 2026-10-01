package com.zwerk.weather;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;

/** Runs the production cache, saved-forecast adapter and widget timeline with Android I/O shims. */
public final class ForecastOfflineWidgetTest {
    private static int checks;
    private static final long HOUR = ForecastClock.FRESH_MILLIS;
    private static final String SCOPE = "city|open-meteo:auto";
    private static final Context FORMAT_CONTEXT = new Context(null);

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static JSONObject hour(long start, int temperature) throws Exception {
        return new JSONObject().put("interval", new JSONObject()
                .put("startTime", Instant.ofEpochMilli(start).toString())
                .put("endTime", Instant.ofEpochMilli(start + HOUR).toString()))
                .put("temperature", new JSONObject().put("degrees", temperature))
                .put("isDaytime", false);
    }

    private static JSONObject compactHour(long start, int temperature) throws Exception {
        return new JSONObject().put("start", start).put("end", start + HOUR)
                .put("temperature", temperature);
    }

    private static JSONObject day(long start, long end) throws Exception {
        return new JSONObject().put("interval", new JSONObject()
                .put("startTime", Instant.ofEpochMilli(start).toString())
                .put("endTime", Instant.ofEpochMilli(end).toString()));
    }

    public static void main(String[] args) throws Exception {
        long now = Instant.parse("2026-09-28T22:30:00Z").toEpochMilli();
        long start = now - HOUR / 2;
        check(ForecastClock.withinAge(now - HOUR + 1, now, HOUR), "fresh just before expiry");
        check(!ForecastClock.withinAge(now - HOUR, now, HOUR), "exact freshness boundary");
        check(!ForecastClock.withinAge(0, now, HOUR), "missing timestamp rejected");
        check(!ForecastClock.withinAge(now + 1, now, HOUR), "clock rollback rejected");
        check(ForecastClock.retentionMillis(false) == HOUR, "Google one-hour retention");
        check(ForecastClock.retentionMillis(true) == 48 * HOUR, "Open-Meteo 48-hour retention");
        long[] starts = {start - HOUR, start, start + HOUR};
        long[] ends = {start, start + HOUR, start + 2 * HOUR};
        check(ForecastClock.currentHour(starts, ends, now) == 1, "current interval selected");
        check(ForecastClock.currentHour(starts, ends, start + HOUR) == 2, "hour boundary advances");
        check(ForecastClock.currentHour(starts, ends, start + 3 * HOUR) == -1, "expired is never Now");
        check(ForecastClock.firstUnexpiredHour(starts, ends, start - 2 * HOUR) == 0,
                "future first hour remains future");

        RadarPlaybackClock playback = new RadarPlaybackClock();
        check(playback.step(0, false, false, true, 3) == RadarPlaybackClock.WAIT,
                "playback holds when current frame is incomplete");
        check(playback.step(30_000, false, false, true, 3) == RadarPlaybackClock.STOP,
                "current-frame wait is bounded");
        playback.reset();
        check(playback.step(0, false, true, false, 3) == RadarPlaybackClock.WAIT,
                "next-frame miss retains current frame");
        check(playback.step(4999, false, true, false, 3) == RadarPlaybackClock.WAIT,
                "no premature frame skip");
        check(playback.step(5000, false, true, false, 3) == RadarPlaybackClock.SKIP,
                "unavailable next frame can be skipped");
        check(playback.candidateOffset() == 2, "skip stays in bounded lookahead");
        check(playback.step(5001, false, true, false, 3) == RadarPlaybackClock.WAIT,
                "second candidate gets a readiness window");
        check(playback.step(10001, false, true, false, 3) == RadarPlaybackClock.STOP,
                "missing candidates stop without infinite polling");
        playback.reset();
        check(playback.step(0, false, true, true, 3) == RadarPlaybackClock.ADVANCE,
                "complete frames advance");
        check(playback.candidateOffset() == 1, "advance resets next candidate");
        check(playback.step(0, false, true, false, 3) == RadarPlaybackClock.WAIT,
                "begin next wait");
        check(playback.step(50_000, true, false, false, 3) == RadarPlaybackClock.WAIT,
                "pan/zoom pauses readiness deadline");
        check(playback.step(50_001, false, true, false, 3) == RadarPlaybackClock.WAIT,
                "settled viewport starts a fresh readiness window");
        playback.reset();
        playback.step(0, false, true, false, 2);
        check(playback.step(5000, false, true, false, 2) == RadarPlaybackClock.STOP,
                "two-frame timeline cannot skip back to current");
        check(playback.step(3000, false, true, true, 1) == RadarPlaybackClock.STOP,
                "single-frame source cannot animate");

        JSONObject current = new JSONObject().put("currentTime", Instant.ofEpochMilli(start - 3 * HOUR))
                .put("temperature", new JSONObject().put("degrees", 99))
                .put("timeZone", new JSONObject().put("id", "Europe/Brussels"))
                .put("_openMeteo", new JSONObject());
        JSONObject hourly = new JSONObject().put("_openMeteo", new JSONObject()).put("forecastHours",
                new JSONArray().put(hour(start - HOUR, 10)).put(hour(start, 12)).put(hour(start + HOUR, 14)));
        JSONObject daily = new JSONObject().put("forecastDays", new JSONArray()
                .put(day(start - 48 * HOUR, start - 24 * HOUR))
                .put(day(start - 24 * HOUR, start))
                .put(day(start, start + 24 * HOUR)));
        JSONObject fallback = SavedForecast.fallbackCurrent(current, hourly, daily, now - 3 * HOUR, now);
        check(fallback.optJSONObject("temperature").getInt("degrees") == 12,
                "offline overview uses this hour rather than old observation");
        check(fallback.optBoolean(SavedForecast.FALLBACK), "fallback marked explicitly");
        check(fallback.getLong(SavedForecast.FETCHED_AT) == now - 3 * HOUR, "save time not reset");
        check(fallback.has("timeZone") && fallback.has("_openMeteo"), "provider and zone preserved");
        check(hourly.getJSONArray("forecastHours").length() == 2, "elapsed offline hours removed");
        check(daily.getJSONArray("forecastDays").length() == 1, "elapsed offline days removed");
        JSONObject empty = SavedForecast.fallbackCurrent(current, new JSONObject(), daily,
                now - 3 * HOUR, now);
        check(!empty.has("temperature"), "missing current forecast not replaced by stale temperature");
        check(SavedForecast.fallbackCurrent(current, new JSONObject(), new JSONObject(),
                now - 3 * HOUR, now) == null, "exhausted offline forecast is not shown as usable");
        JSONObject recent = SavedForecast.fallbackCurrent(current, hourly, daily, now - 5 * 60_000, now);
        check(recent.getJSONObject("temperature").getInt("degrees") == 99, "recent observation retained");

        Prefs prefs = new Prefs();
        prefs.values.put("updated_epoch_ms", now - 2 * HOUR);
        prefs.values.put("open_meteo", true);
        prefs.values.put("zone", "Europe/Brussels");
        prefs.values.put("hourly_json", new JSONArray().put(compactHour(start - HOUR, 10))
                .put(compactHour(start, 12)).put(compactHour(start + HOUR, 14)).toString());
        long midnight = LocalDate.of(2026, 9, 29).atStartOfDay(ZoneId.of("Europe/Brussels"))
                .toInstant().toEpochMilli();
        prefs.values.put("daily_json", new JSONArray().put(new JSONObject().put("start", midnight - 24 * HOUR)
                .put("end", midnight).put("high", 19)).put(new JSONObject().put("start", midnight)
                .put("end", midnight + 24 * HOUR).put("high", 21)).toString());
        WeatherWidgetTimeline timeline = new WeatherWidgetTimeline(prefs, now);
        check(timeline.currentIndex == 1 && timeline.firstIndex == 1, "widget drops elapsed hours");
        check(timeline.currentHour().getInt("temperature") == 12, "widget advances temperature");
        check(timeline.visibleHours(now, 8).length() == 2, "short tail is not repeated");
        check(timeline.today(now).getInt("high") == 21, "daily high rolls over at location midnight");
        check(timeline.nextChange(now) == start + HOUR, "next local redraw is hour boundary");
        WeatherWidgetTimeline later = new WeatherWidgetTimeline(prefs, start + HOUR);
        check(later.currentHour().getInt("temperature") == 14, "next redraw advances to next hour");
        WeatherWidgetTimeline exhausted = new WeatherWidgetTimeline(prefs, start + 3 * HOUR);
        check(exhausted.currentHour() == null && exhausted.visibleHours(start + 3 * HOUR, 8).length() == 0,
                "exhausted forecast never reuses old hours");
        prefs.values.put("hourly_json", "[{\"time\":\"13:00\",\"temperature\":22}]");
        check(new WeatherWidgetTimeline(prefs, now).firstIndex == -1, "legacy labels cannot masquerade as Now");
        prefs.values.put("hourly_json", "corrupt");
        check(new WeatherWidgetTimeline(prefs, now).hours.length() == 0, "corrupt widget data handled");
        prefs.values.put("open_meteo", false);
        check(!new WeatherWidgetTimeline(prefs, now).retained, "Google widget expires after one hour");
        prefs.values.put("open_meteo", true);
        prefs.values.put("updated_epoch_ms", now - 48 * HOUR);
        check(!new WeatherWidgetTimeline(prefs, now).retained, "Open-Meteo widget expiry boundary");

        // Repeated 02:00 at Europe's autumn DST transition must be selected by epoch, not labels.
        long dst = Instant.parse("2026-10-25T00:00:00Z").toEpochMilli();
        prefs.values.put("updated_epoch_ms", dst);
        prefs.values.put("hourly_json", new JSONArray().put(compactHour(dst, 10))
                .put(compactHour(dst + HOUR, 11)).toString());
        WeatherWidgetTimeline dstFirst = new WeatherWidgetTimeline(prefs, dst + 30 * 60_000);
        WeatherWidgetTimeline dstSecond = new WeatherWidgetTimeline(prefs, dst + HOUR + 30 * 60_000);
        check(WeatherTimeFormat.hour(FORMAT_CONTEXT, dstFirst.currentHour().getLong("start"), dstFirst.zone).equals("02:00"), "first DST hour label");
        check(WeatherTimeFormat.hour(FORMAT_CONTEXT, dstSecond.currentHour().getLong("start"), dstSecond.zone).equals("02:00"), "second DST hour label");
        check(dstFirst.currentIndex == 0 && dstSecond.currentIndex == 1, "repeated DST hour advances correctly");

        solarTimelineChecks();
        phoneClockChecks();

        File dir = Files.createTempDirectory("zwerk-cache-test-").toFile();
        dir.deleteOnExit();
        ForecastDiskCache cache = new ForecastDiskCache(new Context(dir));
        cache.persist(50.85, 4.35, "en", SCOPE, current, hourly, daily);
        check(cache.readFresh(50.85, 4.35, "en", SCOPE) != null, "fresh disk cache readable");
        check(cache.readLastKnown(50.85, 4.35, "nl", SCOPE) == null, "language isolation");
        check(cache.readLastKnown(51.0, 4.35, "en", SCOPE) == null, "location isolation");
        check(cache.readLastKnown(50.85, 4.35, "en", "city|open-meteo:ecmwf") == null, "model isolation");
        File file = dir.listFiles()[0];
        long realNow = System.currentTimeMillis();
        ageFile(file, realNow - 2 * HOUR);
        check(cache.readFresh(50.85, 4.35, "en", SCOPE) == null && file.exists(),
                "stale cache remains stored but is not a fresh hit");
        check(cache.readLastKnown(50.85, 4.35, "en", SCOPE) != null, "retained offline cache readable");
        JSONObject wrong = new JSONObject(Files.readString(file.toPath())).put("locationId", "other|open-meteo:auto");
        Files.writeString(file.toPath(), wrong.toString());
        check(cache.readLastKnown(50.85, 4.35, "en", SCOPE) == null, "file identity is validated");
        cache.persist(50.85, 4.35, "en", SCOPE, current, hourly, daily);
        ageFile(file, realNow - 48 * HOUR);
        check(cache.readLastKnown(50.85, 4.35, "en", SCOPE) == null && !file.exists(),
                "expired Open-Meteo disk cache deleted");
        String google = "city|google";
        cache.persist(50.85, 4.35, "en", google, new JSONObject(), new JSONObject(), new JSONObject());
        file = dir.listFiles()[0];
        ageFile(file, realNow - HOUR);
        check(cache.readLastKnown(50.85, 4.35, "en", google) == null && !file.exists(),
                "Google disk cache deleted at one hour");
        System.out.println("Passed " + checks + " offline/cache/widget/radar checks.");
    }

    private static void ageFile(File file, long fetchedAt) throws Exception {
        JSONObject root = new JSONObject(Files.readString(file.toPath())).put("updatedAtMillis", fetchedAt);
        Files.writeString(file.toPath(), root.toString());
        if (!file.setLastModified(fetchedAt)) throw new AssertionError("Could not age fixture");
    }

    private static void solarTimelineChecks() throws Exception {
        long start = Instant.parse("2026-09-29T16:00:00Z").toEpochMilli();
        long now = start + 20 * 60_000;
        long sunset = start + HOUR + 25 * 60_000;
        Prefs prefs = new Prefs();
        prefs.values.put("updated_epoch_ms", now);
        prefs.values.put("open_meteo", true);
        prefs.values.put("zone", "Europe/Brussels");
        JSONArray hours = new JSONArray();
        for (int i = 0; i < 8; i++) hours.put(compactHour(start + i * HOUR, 20 - i));
        String savedHours = hours.toString();
        prefs.values.put("hourly_json", savedHours);
        JSONObject day = new JSONObject().put("start", start - 18 * HOUR)
                .put("end", start + 6 * HOUR).put("sunrise", start - 10 * HOUR)
                .put("sunset", sunset);
        prefs.values.put("daily_json", new JSONArray().put(day).toString());
        WeatherWidgetTimeline timeline = new WeatherWidgetTimeline(prefs, now);
        java.util.List<WeatherWidgetTimeline.Entry> entries = timeline.visibleEntries(now, 6);
        check(entries.size() == 6, "sun event consumes one forecast slot");
        check(!entries.get(0).isSunEvent() && entries.get(0).time == start,
                "Now remains first when sunset is later in the current forecast");
        check("sunset".equals(entries.get(2).sunEvent) && entries.get(2).time == sunset,
                "sunset inserted between its neighbouring forecast hours");
        check(entries.get(2).hour.getInt("temperature") == 19,
                "sunset temperature comes from its actual forecast interval");
        check("19:25".equals(WeatherTimeFormat.hour(FORMAT_CONTEXT, entries.get(2).time, timeline.zone)),
                "sunset preserves exact minutes in the requested display zone");
        check(timeline.hours.toString().equals(savedHours), "event insertion leaves saved forecast untouched");
        check(entries.stream().noneMatch(entry -> "sunrise".equals(entry.sunEvent)),
                "elapsed sunrise is not inserted");

        long beforeSunset = sunset - 10 * 60_000;
        WeatherWidgetTimeline before = new WeatherWidgetTimeline(prefs, beforeSunset);
        check(before.nextChange(beforeSunset) == sunset, "redraw scheduled at sunset before next hour");
        check(new WeatherWidgetTimeline(prefs, sunset).visibleEntries(sunset, 6).stream()
                        .noneMatch(WeatherWidgetTimeline.Entry::isSunEvent),
                "event disappears once its time has passed");

        day.put("sunset", start + 2 * HOUR);
        prefs.values.put("daily_json", new JSONArray().put(day).put(day).toString());
        entries = new WeatherWidgetTimeline(prefs, now).visibleEntries(now, 6);
        check(entries.stream().filter(entry -> entry.time == start + 2 * HOUR).count() == 1,
                "exact-hour and duplicate daily events produce one slot");
        check("sunset".equals(entries.get(2).sunEvent), "exact-hour event replaces the weather icon");

        day.put("sunset", start + 10 * HOUR).remove("sunrise");
        prefs.values.put("daily_json", new JSONArray().put(day).toString());
        check(new WeatherWidgetTimeline(prefs, now).visibleEntries(now, 6).stream()
                        .noneMatch(WeatherWidgetTimeline.Entry::isSunEvent),
                "events beyond saved hourly coverage are omitted");
        day.put("sunset", sunset).put("sunrise", start + 15 * 60_000);
        prefs.values.put("daily_json", new JSONArray().put(day).toString());
        prefs.values.put("hourly_json", new JSONArray().put(compactHour(start, 20))
                .put(compactHour(start + 2 * HOUR, 18)).toString());
        check(new WeatherWidgetTimeline(prefs, now).visibleEntries(now, 6).stream()
                        .noneMatch(WeatherWidgetTimeline.Entry::isSunEvent),
                "missing forecast interval never supplies an event's temperature");

        prefs.values.put("hourly_json", savedHours);
        day.remove("sunrise");
        day.remove("sunset");
        prefs.values.put("daily_json", new JSONArray().put(day).toString());
        entries = new WeatherWidgetTimeline(prefs, now).visibleEntries(now, 6);
        check(entries.size() == 6 && entries.stream().noneMatch(WeatherWidgetTimeline.Entry::isSunEvent),
                "legacy or polar snapshots without sun events retain the normal forecast");
        check(new WeatherWidgetTimeline(prefs, now).visibleEntries(now, 0).isEmpty(),
                "zero available slots is supported");

        // A sunrise inside the repeated clock hour is positioned by epoch, not the label.
        long dst = Instant.parse("2026-10-25T00:00:00Z").toEpochMilli();
        prefs.values.put("updated_epoch_ms", dst);
        prefs.values.put("hourly_json", new JSONArray().put(compactHour(dst, 12))
                .put(compactHour(dst + HOUR, 11)).put(compactHour(dst + 2 * HOUR, 10)).toString());
        prefs.values.put("daily_json", new JSONArray().put(new JSONObject()
                .put("sunrise", dst + 30 * 60_000)).toString());
        entries = new WeatherWidgetTimeline(prefs, dst + 10 * 60_000).visibleEntries(dst + 10 * 60_000, 4);
        check(entries.size() == 4 && "sunrise".equals(entries.get(1).sunEvent),
                "sunrise is ordered correctly across the repeated DST hour");
        check("02:30".equals(WeatherTimeFormat.hour(FORMAT_CONTEXT, entries.get(1).time, timeline.zone)),
                "sunrise label uses the correct DST offset");
        check(new WeatherWidgetTimeline(prefs, dst + 48 * HOUR).visibleEntries(dst + 48 * HOUR, 6).isEmpty(),
                "expired snapshot never shows cached solar events");
    }

    private static void phoneClockChecks() throws Exception {
        java.util.TimeZone previousZone = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Brussels"));
            long now = Instant.parse("2026-09-29T16:30:00Z").toEpochMilli();
            long nextHour = Instant.parse("2026-09-29T17:00:00Z").toEpochMilli();
            Prefs prefs = new Prefs();
            prefs.values.put("updated_epoch_ms", now);
            prefs.values.put("open_meteo", true);
            prefs.values.put("zone", "Europe/London");
            prefs.values.put("hourly_json", new JSONArray().put(compactHour(nextHour - HOUR, 24))
                    .put(compactHour(nextHour, 23)).toString());
            long londonMidnight = Instant.parse("2026-09-29T23:00:00Z").toEpochMilli();
            prefs.values.put("daily_json", new JSONArray().put(new JSONObject()
                    .put("start", londonMidnight - 24 * HOUR).put("end", londonMidnight)
                    .put("high", 25).put("sunset", nextHour + 42 * 60_000)).toString());
            WeatherWidgetTimeline timeline = new WeatherWidgetTimeline(prefs, now);
            check("19:00".equals(WeatherTimeFormat.hour(FORMAT_CONTEXT, nextHour, ZoneId.systemDefault())), "London forecast labels follow Brussels phone time");
            check("19:42".equals(WeatherTimeFormat.hour(FORMAT_CONTEXT, nextHour + 42 * 60_000, ZoneId.systemDefault())), "sunset labels follow phone time too");
            check("Europe/London".equals(timeline.zone.getId()), "display clock preserves the forecast location zone");
            check(timeline.currentIndex == 0 && timeline.nextChange(now) == nextHour,
                    "display clock cannot shift forecast intervals or scheduled updates");
            check(timeline.today(londonMidnight - 30 * 60_000).getInt("high") == 25,
                    "phone midnight does not roll over the forecast location's day early");
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/London"));
            check("18:00".equals(WeatherTimeFormat.hour(FORMAT_CONTEXT, nextHour, ZoneId.systemDefault())), "phone timezone changes apply without refetching");
            check("—".equals(WeatherTimeFormat.hour(FORMAT_CONTEXT, 0L, ZoneId.systemDefault())), "missing time retains its placeholder");
        } finally {
            java.util.TimeZone.setDefault(previousZone);
        }
    }

    private static final class Prefs implements SharedPreferences {
        final HashMap<String, Object> values = new HashMap<>();
        public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        public long getLong(String key, long fallback) { return (Long) values.getOrDefault(key, fallback); }
        public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
    }
}
