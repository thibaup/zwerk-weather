package com.zwerk.weather;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;

/** Bounded Google pagination and shared interpretation of provider precipitation segments. */
final class PrecipitationForecast {
    private static final int PAGE_SIZE = 180;
    private static final int MAX_PAGES = 3;
    private static final Duration EPISODE_JOIN_GAP = Duration.ofMinutes(5);

    interface Request {
        JSONObject get(String address) throws Exception;
    }

    static JSONObject loadGoogle(String key, double lat, double lon, Instant from,
            Duration horizon, Request request) throws Exception {
        String address = "https://weather.googleapis.com/v1/forecast/minutes:lookup"
                + "?key=" + URLEncoder.encode(key, "UTF-8")
                + "&location.latitude=" + lat + "&location.longitude=" + lon
                + "&unitsSystem=METRIC&pageSize=" + PAGE_SIZE;
        JSONObject result = request.get(address);
        JSONArray merged = new JSONArray();
        HashSet<String> intervals = new HashSet<>();
        appendSegments(merged, intervals, result);
        result.put("segments", merged);

        HashSet<String> tokens = new HashSet<>();
        String token = result.optString("nextPageToken", "");
        Instant until = from.plus(horizon);
        for (int page = 1; page < MAX_PAGES && !token.isEmpty() && tokens.add(token); page++) {
            if (!continuousCoverageEnd(result, from, until).isBefore(until)) break;
            JSONObject next;
            try {
                next = request.get(address + "&pageToken=" + URLEncoder.encode(token, "UTF-8"));
            } catch (InterruptedException stopped) {
                throw stopped;
            } catch (Exception unavailable) {
                if (merged.length() == 0) throw unavailable;
                break; // Preserve usable intervals when a later request hits a limit or fails.
            }
            appendSegments(merged, intervals, next);
            token = next.optString("nextPageToken", "");
        }
        result.remove("nextPageToken");
        return result;
    }

    private static void appendSegments(JSONArray merged, HashSet<String> intervals, JSONObject page) {
        JSONArray segments = page.optJSONArray("segments");
        for (int i = 0; segments != null && i < segments.length(); i++) {
            JSONObject raw = segments.optJSONObject(i);
            Segment segment = segment(raw);
            if (segment != null && intervals.add(segment.start + "|" + segment.end)) {
                merged.put(raw);
            }
        }
    }

    static Instant continuousCoverageEnd(JSONObject response, Instant from, Instant until) {
        Instant end = from;
        for (Segment sample : samples(response)) {
            if (sample.start.isAfter(end)) break;
            if (!sample.known || !sample.end.isAfter(end)) continue;
            end = sample.end;
            if (!end.isBefore(until)) return until;
        }
        return end;
    }

    static RainEpisode nextRain(JSONObject response, Instant from, Instant latestStart) {
        RainEpisode episode = null;
        for (Segment sample : samples(response)) {
            if (!sample.rain || !sample.end.isAfter(from)) continue;
            if (episode == null) {
                if (sample.start.isAfter(latestStart)) break;
                episode = new RainEpisode(sample.start, sample.end, sample.probability, sample.intensity);
            } else if (sample.start.isAfter(episode.end.plus(EPISODE_JOIN_GAP))) {
                break;
            } else if (sample.end.isAfter(episode.end)) {
                episode.end = sample.end;
            }
        }
        return episode;
    }

    static boolean isDry(JSONObject response, Instant from, Instant until) {
        if (!until.isAfter(from) || continuousCoverageEnd(response, from, until).isBefore(until)) {
            return false;
        }
        for (Segment sample : samples(response)) {
            if (sample.start.isBefore(until) && sample.end.isAfter(from) && !sample.dry) return false;
        }
        return true;
    }

    private static ArrayList<Segment> samples(JSONObject response) {
        ArrayList<Segment> result = new ArrayList<>();
        JSONArray raw = response == null ? null : response.optJSONArray("segments");
        for (int i = 0; raw != null && i < raw.length(); i++) {
            Segment sample = segment(raw.optJSONObject(i));
            if (sample != null) result.add(sample);
        }
        result.sort(Comparator.comparing(sample -> sample.start));
        return result;
    }

    private static Segment segment(JSONObject raw) {
        if (raw == null) return null;
        JSONObject frame = raw.optJSONObject("timeFrame");
        if (frame == null) return null;
        Instant start;
        Instant end;
        try {
            start = Instant.parse(frame.optString("startTime"));
            end = Instant.parse(frame.optString("endTime"));
        } catch (java.time.format.DateTimeParseException invalid) {
            return null;
        }
        if (!end.isAfter(start)) return null;

        JSONObject qpf = raw.optJSONObject("qpf");
        double amount = qpf == null || qpf.isNull("quantity")
                ? Double.NaN : qpf.optDouble("quantity", Double.NaN);
        boolean hasAmount = Double.isFinite(amount) && amount >= 0;
        int chance = raw.optInt("probability", -1);
        Integer probability = chance >= 0 && chance <= 100 ? chance : null;
        String type = raw.optString("type", "");
        String intensity = raw.optString("intensity", "").trim();
        boolean meaningfulIntensity = "LIGHT".equalsIgnoreCase(intensity)
                || "MODERATE".equalsIgnoreCase(intensity) || "HEAVY".equalsIgnoreCase(intensity);
        boolean dry = !(amount > 0) && ("NONE".equalsIgnoreCase(type)
                || (amount == 0 && !meaningfulIntensity));
        boolean rain = "RAIN".equalsIgnoreCase(type) && (amount > 0
                || (!hasAmount && meaningfulIntensity && probability != null && probability >= 70));
        boolean known = hasAmount || dry || meaningfulIntensity;
        return new Segment(start, end, probability, intensity, known, dry, rain);
    }

    private static final class Segment {
        final Instant start;
        final Instant end;
        final Integer probability;
        final String intensity;
        final boolean known;
        final boolean dry;
        final boolean rain;

        Segment(Instant start, Instant end, Integer probability, String intensity,
                boolean known, boolean dry, boolean rain) {
            this.start = start;
            this.end = end;
            this.probability = probability;
            this.intensity = intensity;
            this.known = known;
            this.dry = dry;
            this.rain = rain;
        }
    }

    static final class RainEpisode {
        final Instant start;
        Instant end;
        final Integer probability;
        final String intensity;

        RainEpisode(Instant start, Instant end, Integer probability, String intensity) {
            this.start = start;
            this.end = end;
            this.probability = probability;
            this.intensity = intensity;
        }
    }
}
