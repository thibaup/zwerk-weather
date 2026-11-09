package com.zwerk.weather;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;

/** Offline fixtures for Google minute-forecast pagination and coverage decisions. */
public final class PrecipitationCoverageTest {
    private static final Instant BASE = Instant.parse("2026-11-08T12:00:00Z");
    private static int checks;

    public static void main(String[] args) throws Exception {
        twoPagesMergeAndDeduplicate();
        paginationBudgetAndRepeatedToken();
        preservePartialResultsWhenLaterPageFails();
        shortHorizonStopsPagination();
        providerSwitchCancelsPagination();
        distinguishDryFromMissingAndUnknown();
        rainEpisodeTiming();
        System.out.println("Passed " + checks + " precipitation coverage checks.");
    }

    private static void twoPagesMergeAndDeduplicate() throws Exception {
        JSONObject first = page("page 2+next",
                segment(0, 5, "NONE", 0d, 0, null),
                segment(5, 10, "RAIN", .4, 85, "LIGHT"),
                segment(10, 15, "NONE", 0d, 0, null));
        JSONObject second = page(null,
                segment(5, 10, "RAIN", 9d, 99, "HEAVY"),
                segment(15, 20, "NONE", 0d, 0, null));
        ArrayList<String> addresses = new ArrayList<>();
        JSONObject merged = PrecipitationForecast.loadGoogle("fixture-key", 50.85, 4.35,
                BASE, Duration.ofMinutes(20), address -> {
                    addresses.add(address);
                    if (addresses.size() == 1) return first;
                    if (addresses.size() == 2) return second;
                    throw new AssertionError("Unexpected third page request");
                });

        check(addresses.size() == 2, "two pages requested for twenty-minute coverage");
        check(addresses.get(0).contains("/v1/forecast/minutes:lookup?key=fixture-key"),
                "Google minute forecast endpoint and key used");
        check(addresses.get(0).contains("location.latitude=50.85&location.longitude=4.35"),
                "request includes expected coordinates");
        check(addresses.get(0).contains("pageSize=180"), "bounded page size requested");
        check(addresses.get(1).contains("&pageToken=page+2%2Bnext"), "page token URL-encoded");
        check(merged.getJSONArray("segments").length() == 4, "duplicate interval merged once");
        check(merged.getJSONArray("segments").getJSONObject(1)
                .getJSONObject("qpf").getDouble("quantity") == .4,
                "first page wins when an interval appears again");
        check(!merged.has("nextPageToken"), "pagination token removed from merged result");
        check(PrecipitationForecast.continuousCoverageEnd(merged, BASE, minute(20)).equals(minute(20)),
                "merged intervals cover the requested window");
        check(!PrecipitationForecast.isDry(merged, BASE, minute(20)),
                "rain on an additional page prevents a dry answer");
    }

    private static void paginationBudgetAndRepeatedToken() throws Exception {
        JSONObject[] pages = {
                page("second", segment(0, 5, "NONE", 0d, 0, null)),
                page("third", segment(5, 10, "NONE", 0d, 0, null)),
                page("fourth", segment(10, 15, "NONE", 0d, 0, null))
        };
        int[] requests = {0};
        JSONObject bounded = PrecipitationForecast.loadGoogle("fixture-key", 50.85, 4.35,
                BASE, Duration.ofHours(1), address -> {
                    if (requests[0] >= pages.length) throw new AssertionError("Page budget exceeded");
                    return pages[requests[0]++];
                });
        check(requests[0] == 3, "at most three Google pages fetched");
        check(bounded.getJSONArray("segments").length() == 3, "last permitted page retained");
        check(PrecipitationForecast.continuousCoverageEnd(bounded, BASE, minute(60)).equals(minute(15)),
                "budget-limited response reports partial coverage");

        requests[0] = 0;
        JSONObject repeated = PrecipitationForecast.loadGoogle("fixture-key", 50.85, 4.35,
                BASE, Duration.ofMinutes(30), address -> {
                    requests[0]++;
                    if (requests[0] == 1) return page("repeat", segment(0, 5, "NONE", 0d, 0, null));
                    if (requests[0] == 2) return page("repeat", segment(5, 10, "NONE", 0d, 0, null));
                    throw new AssertionError("Repeated token must not be requested again");
                });
        check(requests[0] == 2, "duplicate next-page token terminates pagination");
        check(repeated.getJSONArray("segments").length() == 2, "repeated-token pages preserved");
    }

    private static void preservePartialResultsWhenLaterPageFails() throws Exception {
        int[] requests = {0};
        JSONObject partial = PrecipitationForecast.loadGoogle("fixture-key", 50.85, 4.35,
                BASE, Duration.ofMinutes(20), address -> {
                    requests[0]++;
                    if (requests[0] == 1) return page("unavailable", segment(0, 5, "NONE", 0d, 0, null));
                    throw new IllegalStateException("Simulated later-page API failure");
                });
        check(requests[0] == 2, "later page attempted before failure");
        check(partial.getJSONArray("segments").length() == 1, "first page survives later failure");
        check(!partial.has("nextPageToken"), "failed pagination token removed");
        check(PrecipitationForecast.continuousCoverageEnd(partial, BASE, minute(20)).equals(minute(5)),
                "partial result makes no unsupported coverage claim");
        check(!PrecipitationForecast.isDry(partial, BASE, minute(20)),
                "incomplete later-page response cannot be declared dry");
    }

    private static void shortHorizonStopsPagination() throws Exception {
        int[] requests = {0};
        Instant from = minute(2);
        JSONObject response = PrecipitationForecast.loadGoogle("fixture-key", 50.85, 4.35,
                from, Duration.ofMinutes(2), address -> {
                    requests[0]++;
                    if (requests[0] == 1) return page("unneeded", segment(0, 5, "NONE", 0d, 0, null));
                    throw new AssertionError("Short polling window already covered");
                });
        check(requests[0] == 1, "short two-minute polling horizon avoids fetching another page");
        check(PrecipitationForecast.continuousCoverageEnd(response, from, minute(4)).equals(minute(4)),
                "partial overlap of a longer interval covers the polling horizon");
        check(PrecipitationForecast.isDry(response, from, minute(4)), "short covered window is dry");
    }

    private static void providerSwitchCancelsPagination() throws Exception {
        int[] requests = {0};
        try {
            PrecipitationForecast.loadGoogle("fixture-key", 50.85, 4.35,
                    BASE, Duration.ofHours(6), address -> {
                        if (++requests[0] == 1) {
                            return page("second", segment(0, 5, "NONE", 0d, 0, null));
                        }
                        throw new InterruptedException("Precipitation provider changed");
                    });
            throw new AssertionError("Cancelled pagination must not return the first provider's data");
        } catch (InterruptedException expected) {
            check(requests[0] == 2, "provider switch stops pagination and discards partial results");
        }
    }

    private static void distinguishDryFromMissingAndUnknown() {
        JSONObject knownDry = page(null,
                segment(0, 5, "NONE", null, 80, null),
                segment(5, 10, "RAIN", 0d, 75, null));
        check(PrecipitationForecast.continuousCoverageEnd(knownDry, BASE, minute(10)).equals(minute(10)),
                "explicit NONE and zero quantity each provide known coverage");
        check(PrecipitationForecast.isDry(knownDry, BASE, minute(10)),
                "high precipitation chance does not override explicitly dry samples");
        check(!PrecipitationForecast.isDry(knownDry, BASE, BASE),
                "zero-length window cannot establish a dry forecast");

        JSONObject gap = page(null,
                segment(0, 5, "NONE", 0d, 0, null),
                segment(6, 10, "NONE", 0d, 0, null));
        check(PrecipitationForecast.continuousCoverageEnd(gap, BASE, minute(10)).equals(minute(5)),
                "first missing minute stops continuous coverage");
        check(!PrecipitationForecast.isDry(gap, BASE, minute(10)),
                "gap between dry samples means unknown weather");

        JSONObject unknown = page(null,
                segment(0, 5, "NONE", 0d, 0, null),
                segment(5, 10, "RAIN", null, 100, null),
                segment(10, 15, "NONE", 0d, 0, null));
        check(PrecipitationForecast.continuousCoverageEnd(unknown, BASE, minute(15)).equals(minute(5)),
                "probability-only sample cannot extend known coverage");
        check(!PrecipitationForecast.isDry(unknown, BASE, minute(15)),
                "unknown interval between dry intervals is not dry");
        check(PrecipitationForecast.nextRain(unknown, BASE, minute(15)) == null,
                "probability alone does not fabricate a rain episode");
        check(!PrecipitationForecast.isDry(page(null), BASE, minute(5)),
                "absent intervals cannot be called dry");
    }

    private static void rainEpisodeTiming() {
        JSONObject forecast = page(null,
                segment(0, 5, "NONE", 0d, 0, null),
                segment(5, 10, "RAIN", .4, 85, "LIGHT"),
                segment(12, 15, "RAIN", .3, 90, "MODERATE"),
                segment(20, 25, "RAIN", .2, 70, "LIGHT"),
                segment(31, 35, "RAIN", .5, 95, "HEAVY"));
        PrecipitationForecast.RainEpisode first = PrecipitationForecast.nextRain(forecast, BASE, minute(30));
        check(first != null, "first rain episode found");
        check(first.start.equals(minute(5)) && first.end.equals(minute(25)),
                "rain segments merge across gaps up to five minutes");
        check(first.probability == 85 && "LIGHT".equals(first.intensity),
                "episode keeps initial segment probability and intensity");
        check(PrecipitationForecast.nextRain(forecast, BASE, minute(4)) == null,
                "rain starting after latest permitted start is excluded");

        PrecipitationForecast.RainEpisode upcoming = PrecipitationForecast.nextRain(
                forecast, minute(11), minute(13));
        check(upcoming != null && upcoming.start.equals(minute(12)) && upcoming.end.equals(minute(25)),
                "elapsed rain excluded while nearby upcoming segments stay joined");
        PrecipitationForecast.RainEpisode active = PrecipitationForecast.nextRain(
                forecast, minute(24), minute(24));
        check(active != null && active.start.equals(minute(20)) && active.end.equals(minute(25)),
                "already active rain can start before the current instant");
        PrecipitationForecast.RainEpisode later = PrecipitationForecast.nextRain(
                forecast, minute(26), minute(35));
        check(later != null && later.start.equals(minute(31)) && later.end.equals(minute(35)),
                "a gap longer than five minutes starts another episode");

        JSONObject inferred = page(null, segment(0, 5, "RAIN", null, 70, "LIGHT"));
        check(PrecipitationForecast.nextRain(inferred, BASE, minute(5)) != null,
                "strong intensity and probability can identify rain without quantity");
        JSONObject uncertain = page(null, segment(0, 5, "RAIN", null, 69, "LIGHT"));
        check(PrecipitationForecast.nextRain(uncertain, BASE, minute(5)) == null,
                "low-confidence intensity alone cannot identify rain");
        check(!PrecipitationForecast.isDry(uncertain, BASE, minute(5)),
                "known non-dry intensity cannot become an all-clear");
    }

    private static Instant minute(int offset) {
        return BASE.plus(Duration.ofMinutes(offset));
    }

    private static JSONObject page(String token, JSONObject... segments) {
        JSONArray array = new JSONArray();
        for (JSONObject segment : segments) array.put(segment);
        JSONObject page = new JSONObject().put("segments", array);
        if (token != null) page.put("nextPageToken", token);
        return page;
    }

    private static JSONObject segment(int fromMinute, int toMinute, String type,
            Double quantity, int probability, String intensity) {
        JSONObject frame = new JSONObject().put("startTime", minute(fromMinute).toString())
                .put("endTime", minute(toMinute).toString());
        JSONObject segment = new JSONObject().put("timeFrame", frame);
        if (type != null) segment.put("type", type);
        if (quantity != null) segment.put("qpf", new JSONObject().put("quantity", quantity));
        if (probability >= 0) segment.put("probability", probability);
        if (intensity != null) segment.put("intensity", intensity);
        return segment;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
