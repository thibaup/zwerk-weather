package com.zwerk.weather;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Saved forecast intervals, with widget labels following the phone's clock. */
final class WeatherWidgetTimeline {
    final JSONArray hours;
    final JSONArray days;
    final ZoneId zone;
    final long fetchedAt;
    final long expiresAt;
    final boolean retained;
    final int currentIndex;
    final int firstIndex;

    private final long[] starts;
    private final long[] ends;

    WeatherWidgetTimeline(SharedPreferences prefs, long now) {
        hours = array(prefs.getString(WeatherWidgetProvider.KEY_HOURLY_JSON, ""));
        days = array(prefs.getString(WeatherWidgetProvider.KEY_DAILY_JSON, ""));
        ZoneId parsedZone;
        try { parsedZone = ZoneId.of(prefs.getString(WeatherWidgetProvider.KEY_ZONE, "")); }
        catch (Exception ignored) { parsedZone = ZoneId.systemDefault(); }
        zone = parsedZone;
        fetchedAt = prefs.getLong(WeatherWidgetProvider.KEY_UPDATED_EPOCH_MS, 0L);
        long retention = ForecastClock.retentionMillis(
                prefs.getBoolean(WeatherWidgetProvider.KEY_OPEN_METEO, false));
        expiresAt = fetchedAt + retention;
        retained = ForecastClock.withinAge(fetchedAt, now, retention);
        starts = new long[hours.length()];
        ends = new long[hours.length()];
        for (int i = 0; i < hours.length(); i++) {
            JSONObject hour = hours.optJSONObject(i);
            if (hour == null) continue;
            starts[i] = hour.optLong("start", 0L);
            ends[i] = hour.optLong("end", 0L);
        }
        currentIndex = retained ? ForecastClock.currentHour(starts, ends, now) : -1;
        firstIndex = retained ? ForecastClock.firstUnexpiredHour(starts, ends, now) : -1;
    }

    JSONObject currentHour() { return currentIndex < 0 ? null : hours.optJSONObject(currentIndex); }

    JSONArray visibleHours(long now, int count) {
        JSONArray visible = new JSONArray();
        if (!retained || firstIndex < 0) return visible;
        for (int i = firstIndex; i < hours.length() && visible.length() < count; i++) {
            if (starts[i] > 0L && ends[i] > starts[i] && ends[i] > now) {
                visible.put(hours.optJSONObject(i));
            }
        }
        return visible;
    }

    /** Solar events share the forecast row, in time order, without modifying saved hours. */
    List<Entry> visibleEntries(long now, int count) {
        List<Entry> entries = new ArrayList<>();
        JSONArray visible = visibleHours(now, count);
        for (int i = 0; i < visible.length(); i++) {
            JSONObject hour = visible.optJSONObject(i);
            entries.add(new Entry(hour, hour.optLong("start", 0L), ""));
        }
        if (entries.isEmpty()) return entries;
        long horizon = visible.optJSONObject(visible.length() - 1).optLong("end", 0L);
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            if (day == null) continue;
            addSunEvent(entries, day, "sunrise", now, horizon);
            addSunEvent(entries, day, "sunset", now, horizon);
        }
        entries.sort(Comparator.comparingLong(entry -> entry.time));
        return new ArrayList<>(entries.subList(0, Math.min(count, entries.size())));
    }

    private void addSunEvent(List<Entry> entries, JSONObject day, String kind, long now, long horizon) {
        long time = day.optLong(kind, 0L);
        if (time <= now || time >= horizon) return;
        int hourIndex = ForecastClock.currentHour(starts, ends, time);
        if (hourIndex < 0) return;
        for (Entry entry : entries) {
            if (entry.time == time && kind.equals(entry.sunEvent)) return;
        }
        // At an exact hour, the event icon replaces that hour rather than repeating its time.
        entries.removeIf(entry -> !entry.isSunEvent() && entry.time == time);
        entries.add(new Entry(hours.optJSONObject(hourIndex), time, kind));
    }

    static final class Entry {
        final JSONObject hour;
        final long time;
        final String sunEvent;

        Entry(JSONObject hour, long time, String sunEvent) {
            this.hour = hour;
            this.time = time;
            this.sunEvent = sunEvent;
        }

        boolean isSunEvent() { return !sunEvent.isEmpty(); }
    }

    JSONObject today(long now) {
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            if (day != null && ForecastClock.contains(day.optLong("start", 0L),
                    day.optLong("end", 0L), now)) return day;
        }
        return null;
    }

    long nextChange(long now) {
        if (!retained) return 0L;
        long next = expiresAt;
        for (int i = 0; i < starts.length; i++) {
            if (starts[i] > now) next = Math.min(next, starts[i]);
            if (ends[i] > now) next = Math.min(next, ends[i]);
        }
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            if (day == null) continue;
            long end = day.optLong("end", 0L);
            if (end > now) next = Math.min(next, end);
            long sunrise = day.optLong("sunrise", 0L);
            long sunset = day.optLong("sunset", 0L);
            if (sunrise > now) next = Math.min(next, sunrise);
            if (sunset > now) next = Math.min(next, sunset);
        }
        return next;
    }

    private static JSONArray array(String json) {
        try { return new JSONArray(json); }
        catch (Exception ignored) { return new JSONArray(); }
    }
}
