package com.zwerk.weather;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Adapts Open-Meteo responses to the shared forecast JSON schema. */
final class OpenMeteoForecastClient {
    private static final String FREE_ENDPOINT = "https://api.open-meteo.com/v1/forecast";
    private static final String CUSTOMER_ENDPOINT = "https://customer-api.open-meteo.com/v1/forecast";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_SUCCESS_BODY_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ERROR_BODY_BYTES = 32 * 1024;
    // Eight hours cover an exact six-hour view through the one-hour cache lifetime.
    private static final int MINUTE_FORECAST_STEPS = 32;
    private static final int HOURLY_FORECAST_STEPS = 240; // Ten days from the current hour.

    private static final String META_KEY = "_openMeteo";

    private static final String CURRENT_VARIABLES = join(
            "temperature_2m",
            "relative_humidity_2m",
            "dew_point_2m",
            "apparent_temperature",
            "precipitation_probability",
            "precipitation",
            "rain",
            "showers",
            "snowfall",
            "weather_code",
            "cloud_cover",
            "pressure_msl",
            "surface_pressure",
            "visibility",
            "wind_speed_10m",
            "wind_direction_10m",
            "wind_gusts_10m",
            "uv_index",
            "is_day");

    private static final String HOURLY_VARIABLES = CURRENT_VARIABLES;

    private static final String DAILY_VARIABLES = join(
            "weather_code",
            "temperature_2m_max",
            "temperature_2m_min",
            "apparent_temperature_max",
            "apparent_temperature_min",
            "uv_index_max",
            "sunrise",
            "sunset",
            "moonrise",
            "moonset",
            "moon_phase",
            "precipitation_sum",
            "rain_sum",
            "showers_sum",
            "snowfall_sum",
            "precipitation_probability_max",
            "wind_speed_10m_max",
            "wind_gusts_10m_max",
            "wind_direction_10m_dominant");

    private static final String MINUTELY_VARIABLES = join(
            "precipitation",
            "rain",
            "showers",
            "snowfall",
            "weather_code");

    private OpenMeteoForecastClient() { }

    static final class Forecast {
        final JSONObject current;
        final JSONObject hourly;
        final JSONObject daily;

        Forecast(JSONObject current, JSONObject hourly, JSONObject daily) {
            this.current = current;
            this.hourly = hourly;
            this.daily = daily;
        }
    }

    static Forecast loadForecast(
            Context context,
            double lat,
            double lon,
            String modelId,
            String paidApiKey) throws Exception {
        validateCoordinates(lat, lon);
        ModelChoice model = modelChoice(modelId);
        Endpoint endpoint = endpoint(paidApiKey);

        StringBuilder url = baseQuery(endpoint, lat, lon, model);
        append(url, "current", CURRENT_VARIABLES);
        append(url, "hourly", HOURLY_VARIABLES);
        append(url, "daily", DAILY_VARIABLES);
        append(url, "temperature_unit", "celsius");
        append(url, "wind_speed_unit", "kmh");
        append(url, "precipitation_unit", "mm");
        append(url, "timeformat", "iso8601");
        append(url, "timezone", "auto");
        append(url, "forecast_days", "10");
        append(url, "forecast_hours", Integer.toString(HOURLY_FORECAST_STEPS));

        JSONObject raw = requestJson(context, url.toString(), "forecast",
                OpenMeteoRequestBudgetManager.Category.FORECAST);
        ResponseClock clock = responseClock(raw);
        JSONObject metadata = forecastMetadata(model, endpoint, raw);
        metadata.put("requestedForecastDays", 10);
        metadata.put("requestedForecastHours", HOURLY_FORECAST_STEPS);
        metadata.put("singleResponse", true);

        JSONObject current = adaptCurrent(raw.optJSONObject("current"), clock, metadata);
        JSONObject hourly = adaptHourly(raw.optJSONObject("hourly"), clock, metadata);
        pruneElapsedHourly(hourly, Instant.now());
        JSONObject daily = adaptDaily(raw.optJSONObject("daily"), raw.optJSONObject("hourly"), clock, metadata);
        if (!current.has("temperature") && !current.has("weatherCondition")) {
            throw new IOException("This model has no forecast for this location");
        }
        return new Forecast(current, hourly, daily);
    }

    static JSONObject loadMinute(
            Context context,
            double lat,
            double lon,
            String modelId,
            String paidApiKey) throws Exception {
        validateCoordinates(lat, lon);
        ModelChoice model = modelChoice(PrecipitationModelPolicy.resolve(modelId, lat, lon));
        Endpoint endpoint = endpoint(paidApiKey);

        StringBuilder url = baseQuery(endpoint, lat, lon, model);
        append(url, "minutely_15", MINUTELY_VARIABLES);
        // Météo-France's deterministic seamless reader has no probability feed.
        // Keep missing chance unavailable; amounts do not imply a percentage.
        boolean requestProbability = !"meteofrance_seamless".equals(model.apiId);
        if (requestProbability) {
            append(url, "hourly", "precipitation_probability");
            append(url, "forecast_hours", "9");
        }
        append(url, "temperature_unit", "celsius");
        append(url, "wind_speed_unit", "kmh");
        append(url, "precipitation_unit", "mm");
        append(url, "timeformat", "iso8601");
        append(url, "timezone", "auto");
        append(url, "forecast_minutely_15", Integer.toString(MINUTE_FORECAST_STEPS));

        JSONObject raw = requestJson(context, url.toString(), "15-minute forecast",
                OpenMeteoRequestBudgetManager.Category.PRECIPITATION);
        ResponseClock clock = responseClock(raw);
        JSONObject source = raw.optJSONObject("minutely_15");

        JSONObject out = new JSONObject();
        out.put("timeZone", timeZoneJson(clock));
        JSONArray segments = new JSONArray();
        out.put("segments", segments);

        JSONObject metadata = forecastMetadata(model, endpoint, raw);
        metadata.put("dataKind", "MODEL_FORECAST_15_MINUTE");
        metadata.put("temporalResolutionMinutes", 15);
        metadata.put("radar", false);
        metadata.put("observation", false);
        metadata.put("resolutionSource", "OPEN_METEO_NATIVE_OR_INTERPOLATED");
        metadata.put("requested15MinuteSteps", MINUTE_FORECAST_STEPS);
        if (requestProbability) metadata.put("probabilityResolutionMinutes", 60);
        metadata.put("requestedModel", modelId == null ? "auto" : modelId);
        metadata.put("complete", true);
        out.put(META_KEY, metadata);
        out.put("forecastKind", "MODEL_FORECAST_15_MINUTE");
        out.put("isRadar", false);
        out.put("isObservation", false);
        out.put("complete", true);

        if (source == null) return out;
        JSONArray times = source.optJSONArray("time");
        if (times == null) return out;

        Instant firstStart = null;
        Instant lastEnd = null;
        for (int i = 0; i < times.length(); i++) {
            String localTime = stringAt(times, i);
            // Open-Meteo's 15-minute precipitation at this timestamp is the sum
            // over the preceding interval, so the timestamp is its end.
            Instant end = localTimestampToInstant(localTime, clock);
            if (end == null) continue;
            Instant start = end.minus(Duration.ofMinutes(15));

            Double qpfMm = numberAt(source, "precipitation", i);
            Double rainMm = numberAt(source, "rain", i);
            Double showersMm = numberAt(source, "showers", i);
            Double snowfallCm = numberAt(source, "snowfall", i);
            Double probabilityValue = precedingHourlyProbability(raw.optJSONObject("hourly"), end, clock);
            Integer code = integerAt(source, "weather_code", i);
            if (qpfMm == null && probabilityValue == null && code == null) continue;

            JSONObject segment = new JSONObject();
            JSONObject frame = new JSONObject();
            frame.put("startTime", start.toString());
            frame.put("endTime", end.toString());
            segment.put("timeFrame", frame);

            if (probabilityValue != null) {
                segment.put("probability", boundedPercent(probabilityValue));
            }
            if (qpfMm != null) {
                segment.put("qpf", precipitationQuantity(Math.max(0d, qpfMm)));
            }
            Double snowWaterMm = snowWaterEquivalentMm(qpfMm, rainMm, showersMm, snowfallCm);
            if (snowWaterMm != null) {
                segment.put("snowfallAmount", precipitationQuantity(snowWaterMm));
            }

            String type = minutePrecipitationType(code, qpfMm, rainMm, showersMm, snowWaterMm);
            segment.put("type", type);
            segment.put("intensity", minuteIntensity(qpfMm, start, end));
            segments.put(segment);

            if (firstStart == null || start.isBefore(firstStart)) firstStart = start;
            if (lastEnd == null || end.isAfter(lastEnd)) lastEnd = end;
        }

        if (firstStart != null && lastEnd != null && lastEnd.isAfter(firstStart)) {
            JSONObject overall = new JSONObject();
            overall.put("startTime", firstStart.toString());
            overall.put("endTime", lastEnd.toString());
            out.put("overallPredictionTimeframe", overall);
        }
        return out;
    }

    private static Double precedingHourlyProbability(JSONObject hourly, Instant intervalEnd, ResponseClock clock) {
        if (hourly == null || intervalEnd == null) return null;
        JSONArray times = hourly.optJSONArray("time");
        if (times == null) return null;
        long[] hourEnds = new long[times.length()];
        for (int i = 0; i < times.length(); i++) {
            Instant hourEnd = localTimestampToInstant(stringAt(times, i), clock);
            hourEnds[i] = hourEnd == null ? Long.MIN_VALUE : hourEnd.toEpochMilli();
        }
        int index = PrecipitationWindow.precedingProbabilityIndex(hourEnds, intervalEnd.toEpochMilli());
        return index < 0 ? null : numberAt(hourly, "precipitation_probability", index);
    }

    /** Keep cached and displayed Open-Meteo hours anchored to the live clock. */
    static boolean pruneElapsedHourly(JSONObject hourly, Instant now) {
        if (hourly == null || !hourly.has(META_KEY) || now == null) return false;
        JSONArray hours = hourly.optJSONArray("forecastHours");
        if (hours == null) return false;
        boolean changed = false;
        while (hours.length() > 0) {
            JSONObject first = hours.optJSONObject(0);
            JSONObject interval = first == null ? null : first.optJSONObject("interval");
            String endText = interval == null ? "" : interval.optString("endTime", "");
            Instant end;
            try {
                end = Instant.parse(endText);
            } catch (Exception ignored) {
                break;
            }
            if (end.isAfter(now)) break;
            hours.remove(0);
            changed = true;
        }
        if (changed) {
            try {
                hourly.put("_weatherNextHourlyFirstPageCount", hours.length());
            } catch (Exception ignored) { }
        }
        return changed;
    }

    static String modelLabel(String modelId) {
        return modelChoice(modelId).label;
    }

    private static JSONObject adaptCurrent(
            JSONObject source,
            ResponseClock clock,
            JSONObject metadata) throws Exception {
        JSONObject out = new JSONObject();
        out.put("timeZone", timeZoneJson(clock));
        out.put(META_KEY, copy(metadata));
        if (source == null) return out;

        String localTime = source.optString("time", "");
        Instant instant = localTimestampToInstant(localTime, clock);
        if (instant != null) out.put("currentTime", instant.toString());

        putTemperature(out, "temperature", finiteNumber(source, "temperature_2m"));
        putTemperature(out, "dewPoint", finiteNumber(source, "dew_point_2m"));
        putTemperature(out, "feelsLikeTemperature", finiteNumber(source, "apparent_temperature"));

        Double humidity = finiteNumber(source, "relative_humidity_2m");
        if (humidity != null) out.put("relativeHumidity", boundedPercent(humidity));
        Double uv = finiteNumber(source, "uv_index");
        if (uv != null) out.put("uvIndex", roundedNonNegativeInt(uv));
        Double cloud = finiteNumber(source, "cloud_cover");
        if (cloud != null) out.put("cloudCover", boundedPercent(cloud));

        Integer code = integerValue(source, "weather_code");
        if (code != null) out.put("weatherCondition", weatherCondition(code));

        Double isDay = finiteNumber(source, "is_day");
        if (isDay != null) out.put("isDaytime", isDay >= 0.5d);

        JSONObject precipitation = precipitation(
                finiteNumber(source, "precipitation_probability"),
                finiteNumber(source, "precipitation"),
                finiteNumber(source, "rain"),
                finiteNumber(source, "showers"),
                finiteNumber(source, "snowfall"));
        if (precipitation.length() > 0) out.put("precipitation", precipitation);

        JSONObject wind = wind(
                finiteNumber(source, "wind_speed_10m"),
                finiteNumber(source, "wind_gusts_10m"),
                finiteNumber(source, "wind_direction_10m"));
        if (wind.length() > 0) out.put("wind", wind);

        JSONObject pressure = pressure(
                finiteNumber(source, "pressure_msl"),
                finiteNumber(source, "surface_pressure"));
        if (pressure.length() > 0) out.put("airPressure", pressure);

        JSONObject visibility = visibility(finiteNumber(source, "visibility"));
        if (visibility != null) out.put("visibility", visibility);
        return out;
    }

    private static JSONObject adaptHourly(
            JSONObject source,
            ResponseClock clock,
            JSONObject metadata) throws Exception {
        JSONObject out = new JSONObject();
        out.put("timeZone", timeZoneJson(clock));
        out.put(META_KEY, copy(metadata));
        JSONArray forecastHours = new JSONArray();
        out.put("forecastHours", forecastHours);

        if (source != null) {
            JSONArray times = source.optJSONArray("time");
            if (times != null) {
                for (int i = 0; i < times.length(); i++) {
                    String localTime = stringAt(times, i);
                    LocalDateTime local = parseLocalDateTime(localTime);
                    Instant start = localTimestampToInstant(localTime, clock);
                    if (local == null || start == null) continue;

                    Instant end = nextHourlyBoundary(times, i, start, clock);
                    if (end == null || !end.isAfter(start)) end = start.plus(Duration.ofHours(1));
                    if (numberAt(source, "temperature_2m", i) == null
                            && integerAt(source, "weather_code", i) == null) continue;

                    JSONObject hour = new JSONObject();
                    JSONObject interval = new JSONObject();
                    interval.put("startTime", start.toString());
                    interval.put("endTime", end.toString());
                    hour.put("interval", interval);
                    hour.put("displayDateTime", displayDateTime(local));

                    putTemperature(hour, "temperature", numberAt(source, "temperature_2m", i));
                    putTemperature(hour, "dewPoint", numberAt(source, "dew_point_2m", i));
                    putTemperature(hour, "feelsLikeTemperature", numberAt(source, "apparent_temperature", i));

                    Double humidity = numberAt(source, "relative_humidity_2m", i);
                    if (humidity != null) hour.put("relativeHumidity", boundedPercent(humidity));
                    Double uv = numberAt(source, "uv_index", i);
                    if (uv != null) hour.put("uvIndex", roundedNonNegativeInt(uv));
                    Double cloud = numberAt(source, "cloud_cover", i);
                    if (cloud != null) hour.put("cloudCover", boundedPercent(cloud));

                    Integer code = integerAt(source, "weather_code", i);
                    if (code != null) hour.put("weatherCondition", weatherCondition(code));
                    Double isDay = numberAt(source, "is_day", i);
                    if (isDay != null) hour.put("isDaytime", isDay >= 0.5d);

                    // Accumulations and probability at T describe the preceding
                    // hour. This UI interval starts at T, so use its end's value.
                    JSONObject precipitation = precipitation(
                            numberAt(source, "precipitation_probability", i + 1),
                            numberAt(source, "precipitation", i + 1),
                            numberAt(source, "rain", i + 1),
                            numberAt(source, "showers", i + 1),
                            numberAt(source, "snowfall", i + 1));
                    if (precipitation.length() > 0) hour.put("precipitation", precipitation);

                    JSONObject wind = wind(
                            numberAt(source, "wind_speed_10m", i),
                            numberAt(source, "wind_gusts_10m", i),
                            numberAt(source, "wind_direction_10m", i));
                    if (wind.length() > 0) hour.put("wind", wind);

                    JSONObject pressure = pressure(
                            numberAt(source, "pressure_msl", i),
                            numberAt(source, "surface_pressure", i));
                    if (pressure.length() > 0) hour.put("airPressure", pressure);

                    JSONObject visibility = visibility(numberAt(source, "visibility", i));
                    if (visibility != null) hour.put("visibility", visibility);
                    forecastHours.put(hour);
                }
            }
        }

        // Match the existing hourly paging diagnostics: Open-Meteo returns this time series
        // in one response, so there is no continuation page to fetch.
        out.put("complete", true);
        out.put("forecastHoursComplete", true);
        out.put("_weatherNextHourlyPartial", false);
        out.put("_weatherNextHourlyLoadError", false);
        out.put("_weatherNextHourlyNextTokenPresent", false);
        out.put("_weatherNextHourlyFirstPageCount", forecastHours.length());
        out.put("_weatherNextHourlyTopKeys", "forecastHours, timeZone");
        return out;
    }

    private static JSONObject adaptDaily(
            JSONObject source,
            JSONObject hourlySource,
            ResponseClock clock,
            JSONObject metadata) throws Exception {
        JSONObject out = new JSONObject();
        out.put("timeZone", timeZoneJson(clock));
        out.put(META_KEY, copy(metadata));
        JSONArray forecastDays = new JSONArray();
        out.put("forecastDays", forecastDays);
        out.put("complete", true);

        if (source == null) return out;
        JSONArray times = source.optJSONArray("time");
        if (times == null) return out;

        for (int i = 0; i < times.length(); i++) {
            String dateText = stringAt(times, i);
            LocalDate date = parseLocalDate(dateText);
            if (date == null) continue;
            if (numberAt(source, "temperature_2m_max", i) == null
                    && numberAt(source, "temperature_2m_min", i) == null
                    && integerAt(source, "weather_code", i) == null) continue;

            JSONObject day = new JSONObject();
            day.put("displayDate", displayDate(date));
            Instant dayStart = localMidnightToInstant(date, clock);
            Instant dayEnd = localMidnightToInstant(date.plusDays(1), clock);
            if (dayStart != null && dayEnd != null && dayEnd.isAfter(dayStart)) {
                JSONObject interval = new JSONObject();
                interval.put("startTime", dayStart.toString());
                interval.put("endTime", dayEnd.toString());
                day.put("interval", interval);
            }

            putTemperature(day, "maxTemperature", numberAt(source, "temperature_2m_max", i));
            putTemperature(day, "minTemperature", numberAt(source, "temperature_2m_min", i));
            putTemperature(day, "maxFeelsLikeTemperature", numberAt(source, "apparent_temperature_max", i));
            putTemperature(day, "minFeelsLikeTemperature", numberAt(source, "apparent_temperature_min", i));

            Double uv = numberAt(source, "uv_index_max", i);
            if (uv != null) day.put("uvIndex", roundedNonNegativeInt(uv));
            Integer dailyCode = integerAt(source, "weather_code", i);
            if (dailyCode != null) day.put("weatherCondition", weatherCondition(dailyCode));

            JSONObject dailyPrecip = precipitation(
                    numberAt(source, "precipitation_probability_max", i),
                    numberAt(source, "precipitation_sum", i),
                    numberAt(source, "rain_sum", i),
                    numberAt(source, "showers_sum", i),
                    numberAt(source, "snowfall_sum", i));
            if (dailyPrecip.length() > 0) day.put("precipitation", dailyPrecip);

            JSONObject dailyWind = wind(
                    numberAt(source, "wind_speed_10m_max", i),
                    numberAt(source, "wind_gusts_10m_max", i),
                    numberAt(source, "wind_direction_10m_dominant", i));
            if (dailyWind.length() > 0) day.put("wind", dailyWind);

            JSONObject sunEvents = new JSONObject();
            Instant sunrise = localTimestampToInstant(stringAt(source, "sunrise", i), clock);
            Instant sunset = localTimestampToInstant(stringAt(source, "sunset", i), clock);
            if (sunrise != null) sunEvents.put("sunriseTime", sunrise.toString());
            if (sunset != null) sunEvents.put("sunsetTime", sunset.toString());
            if (sunEvents.length() > 0) day.put("sunEvents", sunEvents);

            JSONObject moonEvents = new JSONObject();
            Instant moonrise = localTimestampToInstant(stringAt(source, "moonrise", i), clock);
            Instant moonset = localTimestampToInstant(stringAt(source, "moonset", i), clock);
            if (moonrise != null) moonEvents.put("moonriseTimes", new JSONArray().put(moonrise.toString()));
            if (moonset != null) moonEvents.put("moonsetTimes", new JSONArray().put(moonset.toString()));
            Double moonPhase = numberAt(source, "moon_phase", i);
            if (moonPhase != null) {
                moonEvents.put("moonPhase", moonPhaseName(moonPhase));
                moonEvents.put("moonPhaseFraction", normalizedFraction(moonPhase));
            }
            if (moonEvents.length() > 0) day.put("moonEvents", moonEvents);

            PeriodAggregate daytime = aggregatePeriod(hourlySource, date, true);
            PeriodAggregate nighttime = aggregatePeriod(hourlySource, date, false);
            JSONObject dayForecast = periodForecast(daytime, true);
            JSONObject nightForecast = periodForecast(nighttime, false);
            if (dayForecast.length() > 1) day.put("daytimeForecast", dayForecast);
            if (nightForecast.length() > 1) day.put("nighttimeForecast", nightForecast);

            forecastDays.put(day);
        }
        return out;
    }

    private static PeriodAggregate aggregatePeriod(JSONObject hourly, LocalDate date, boolean daytime) {
        PeriodAggregate aggregate = new PeriodAggregate();
        if (hourly == null || date == null) return aggregate;
        JSONArray times = hourly.optJSONArray("time");
        if (times == null) return aggregate;

        for (int i = 0; i < times.length(); i++) {
            LocalDateTime local = parseLocalDateTime(stringAt(times, i));
            if (local == null || !date.equals(local.toLocalDate())) continue;
            Double isDay = numberAt(hourly, "is_day", i);
            if (isDay == null || (isDay >= 0.5d) != daytime) continue;

            aggregate.any = true;
            Integer code = integerAt(hourly, "weather_code", i);
            if (code != null && weatherSeverity(code) >= aggregate.weatherSeverity) {
                aggregate.weatherSeverity = weatherSeverity(code);
                aggregate.weatherCode = code;
            }
            Double probability = numberAt(hourly, "precipitation_probability", i);
            if (probability != null) {
                int p = boundedPercent(probability);
                aggregate.maxProbability = aggregate.maxProbability == null
                        ? p : Math.max(aggregate.maxProbability, p);
            }
            Double qpf = numberAt(hourly, "precipitation", i);
            if (qpf != null) aggregate.qpfMm = sum(aggregate.qpfMm, Math.max(0d, qpf));
            Double rain = numberAt(hourly, "rain", i);
            if (rain != null) aggregate.rainMm = sum(aggregate.rainMm, Math.max(0d, rain));
            Double showers = numberAt(hourly, "showers", i);
            if (showers != null) aggregate.showersMm = sum(aggregate.showersMm, Math.max(0d, showers));
            Double snow = numberAt(hourly, "snowfall", i);
            if (snow != null) aggregate.snowfallCm = sum(aggregate.snowfallCm, Math.max(0d, snow));
        }
        return aggregate;
    }

    private static JSONObject periodForecast(PeriodAggregate a, boolean isDaytime) throws Exception {
        JSONObject out = new JSONObject();
        if (a == null || !a.any) return out;
        out.put("isDaytime", isDaytime);
        if (a.weatherCode != null) out.put("weatherCondition", weatherCondition(a.weatherCode));
        JSONObject precipitation = precipitation(
                a.maxProbability == null ? null : a.maxProbability.doubleValue(),
                a.qpfMm,
                a.rainMm,
                a.showersMm,
                a.snowfallCm);
        if (precipitation.length() > 0) out.put("precipitation", precipitation);
        return out;
    }

    private static JSONObject precipitation(
            Double probabilityPercent,
            Double qpfMm,
            Double rainMm,
            Double showersMm,
            Double snowfallCm) throws Exception {
        JSONObject out = new JSONObject();
        if (probabilityPercent != null) {
            JSONObject probability = new JSONObject();
            probability.put("percent", boundedPercent(probabilityPercent));
            out.put("probability", probability);
        }
        if (qpfMm != null) out.put("qpf", precipitationQuantity(Math.max(0d, qpfMm)));
        if (rainMm != null) out.put("rainQpf", precipitationQuantity(Math.max(0d, rainMm)));
        if (showersMm != null) out.put("showersQpf", precipitationQuantity(Math.max(0d, showersMm)));
        if (snowfallCm != null) {
            JSONObject snow = new JSONObject();
            snow.put("quantity", Math.max(0d, snowfallCm));
            snow.put("unit", "CENTIMETERS");
            out.put("snowfallAmount", snow);
        }
        return out;
    }

    private static JSONObject precipitationQuantity(double millimeters) throws Exception {
        JSONObject out = new JSONObject();
        out.put("quantity", millimeters);
        out.put("unit", "MILLIMETERS");
        return out;
    }

    private static JSONObject wind(Double speedKmh, Double gustKmh, Double directionDegrees) throws Exception {
        JSONObject out = new JSONObject();
        if (speedKmh != null) out.put("speed", speedQuantity(speedKmh));
        if (gustKmh != null) out.put("gust", speedQuantity(gustKmh));
        if (directionDegrees != null) {
            JSONObject direction = new JSONObject();
            double normalized = normalizeDegrees(directionDegrees);
            direction.put("degrees", normalized);
            direction.put("cardinal", cardinalName(normalized));
            out.put("direction", direction);
        }
        return out;
    }

    private static JSONObject speedQuantity(double kmh) throws Exception {
        JSONObject out = new JSONObject();
        out.put("value", Math.max(0d, kmh));
        out.put("unit", "KILOMETERS_PER_HOUR");
        return out;
    }

    private static JSONObject pressure(Double meanSeaLevelHpa, Double surfaceHpa) throws Exception {
        JSONObject out = new JSONObject();
        if (meanSeaLevelHpa != null) {
            out.put("meanSeaLevelMillibars", meanSeaLevelHpa);
            out.put("meanSeaLevelHectopascals", meanSeaLevelHpa);
            out.put("value", meanSeaLevelHpa);
            out.put("unit", "HECTOPASCALS");
        }
        if (surfaceHpa != null) out.put("surfaceHectopascals", surfaceHpa);
        return out;
    }

    private static JSONObject visibility(Double meters) throws Exception {
        if (meters == null) return null;
        JSONObject out = new JSONObject();
        out.put("distance", Math.max(0d, meters) / 1000d);
        out.put("value", Math.max(0d, meters) / 1000d);
        out.put("unit", "KILOMETERS");
        return out;
    }

    private static void putTemperature(JSONObject target, String key, Double celsius) throws Exception {
        if (target == null || celsius == null) return;
        JSONObject value = new JSONObject();
        value.put("degrees", celsius);
        value.put("value", celsius);
        value.put("unit", "CELSIUS");
        target.put(key, value);
    }

    private static JSONObject weatherCondition(int code) throws Exception {
        WeatherText text = weatherText(code);
        JSONObject out = new JSONObject();
        out.put("type", text.type);
        JSONObject description = new JSONObject();
        description.put("text", text.description);
        out.put("description", description);
        out.put("wmoCode", code);
        return out;
    }

    private static WeatherText weatherText(int code) {
        switch (code) {
            case 0: return new WeatherText("CLEAR", "Clear sky");
            case 1: return new WeatherText("MOSTLY_CLEAR", "Mainly clear");
            case 2: return new WeatherText("PARTLY_CLOUDY", "Partly cloudy");
            case 3: return new WeatherText("CLOUDY", "Overcast");
            case 45: return new WeatherText("FOG", "Fog");
            case 48: return new WeatherText("FOG", "Depositing rime fog");
            case 51: return new WeatherText("LIGHT_DRIZZLE", "Light drizzle");
            case 53: return new WeatherText("DRIZZLE", "Moderate drizzle");
            case 55: return new WeatherText("HEAVY_DRIZZLE", "Dense drizzle");
            case 56: return new WeatherText("FREEZING_DRIZZLE", "Light freezing drizzle");
            case 57: return new WeatherText("FREEZING_DRIZZLE", "Dense freezing drizzle");
            case 61: return new WeatherText("LIGHT_RAIN", "Slight rain");
            case 63: return new WeatherText("RAIN", "Moderate rain");
            case 65: return new WeatherText("HEAVY_RAIN", "Heavy rain");
            case 66: return new WeatherText("FREEZING_RAIN", "Light freezing rain");
            case 67: return new WeatherText("FREEZING_RAIN", "Heavy freezing rain");
            case 71: return new WeatherText("LIGHT_SNOW", "Slight snowfall");
            case 73: return new WeatherText("SNOW", "Moderate snowfall");
            case 75: return new WeatherText("HEAVY_SNOW", "Heavy snowfall");
            case 77: return new WeatherText("SNOW", "Snow grains");
            case 80: return new WeatherText("RAIN_SHOWERS", "Slight rain showers");
            case 81: return new WeatherText("RAIN_SHOWERS", "Moderate rain showers");
            case 82: return new WeatherText("HEAVY_RAIN_SHOWERS", "Violent rain showers");
            case 85: return new WeatherText("SNOW_SHOWERS", "Slight snow showers");
            case 86: return new WeatherText("HEAVY_SNOW_SHOWERS", "Heavy snow showers");
            case 95: return new WeatherText("THUNDERSTORM", "Thunderstorm");
            case 96: return new WeatherText("THUNDERSTORM_WITH_HAIL", "Thunderstorm with slight hail");
            case 99: return new WeatherText("THUNDERSTORM_WITH_HAIL", "Thunderstorm with heavy hail");
            default: return new WeatherText("WEATHER_CONDITION_UNSPECIFIED", "Unknown");
        }
    }

    private static String minutePrecipitationType(
            Integer weatherCode,
            Double qpfMm,
            Double rainMm,
            Double showersMm,
            Double snowWaterMm) {
        if (weatherCode != null && (weatherCode == 96 || weatherCode == 99)) return "HAIL";
        double snow = snowWaterMm == null ? 0d : Math.max(0d, snowWaterMm);
        double rainWaterMm = (rainMm == null ? 0d : Math.max(0d, rainMm))
                + (showersMm == null ? 0d : Math.max(0d, showersMm));
        if (snow > 0d && snow >= rainWaterMm) return "SNOW";
        if (rainWaterMm > 0d || (qpfMm != null && qpfMm > 0d)) return "RAIN";
        return "NONE";
    }

    private static Double snowWaterEquivalentMm(
            Double totalPrecipitationMm,
            Double rainMm,
            Double showersMm,
            Double snowfallCm) {
        // Prefer the API's water-balance quantities when all components are present:
        // precipitation is rain + showers + snow water equivalent. This avoids imposing
        // a snow-density assumption on the Google-shaped snowfallAmount field.
        if (totalPrecipitationMm != null && rainMm != null && showersMm != null) {
            return Math.max(0d, totalPrecipitationMm - Math.max(0d, rainMm) - Math.max(0d, showersMm));
        }
        if (snowfallCm != null) {
            // Open-Meteo documents 7 cm snowfall ~= 10 mm precipitation water equivalent.
            return Math.max(0d, snowfallCm) * (10d / 7d);
        }
        return null;
    }

    private static String minuteIntensity(Double qpfMm, Instant start, Instant end) {
        if (qpfMm == null || start == null || end == null || !end.isAfter(start)) {
            return "PRECIPITATION_INTENSITY_UNSPECIFIED";
        }
        long millis = Duration.between(start, end).toMillis();
        if (millis <= 0L) return "PRECIPITATION_INTENSITY_UNSPECIFIED";
        double rateMmPerHour = Math.max(0d, qpfMm) * 3_600_000d / millis;
        if (rateMmPerHour <= 0d) return "NO_INTENSITY";
        if (rateMmPerHour <= 2.5d) return "LIGHT";
        if (rateMmPerHour <= 7.5d) return "MODERATE";
        return "HEAVY";
    }

    private static JSONObject forecastMetadata(
            ModelChoice model,
            Endpoint endpoint,
            JSONObject raw) throws Exception {
        JSONObject meta = new JSONObject();
        meta.put("source", "Open-Meteo Weather Forecast API");
        meta.put("modelId", model.apiId);
        meta.put("modelLabel", model.label);
        if (!model.requestedId.equals(model.apiId)) meta.put("requestedModelId", model.requestedId);
        meta.put("automaticModelSelection", "auto".equals(model.apiId) || "best_match".equals(model.apiId));
        meta.put("customerEndpoint", endpoint.paid);
        meta.put("complete", true);
        Double generationMs = finiteNumber(raw, "generationtime_ms");
        if (generationMs != null) meta.put("generationTimeMs", generationMs);
        Double sourceLatitude = finiteNumber(raw, "latitude");
        Double sourceLongitude = finiteNumber(raw, "longitude");
        Double elevation = finiteNumber(raw, "elevation");
        if (sourceLatitude != null) meta.put("gridLatitude", sourceLatitude);
        if (sourceLongitude != null) meta.put("gridLongitude", sourceLongitude);
        if (elevation != null) meta.put("gridElevationMeters", elevation);
        return meta;
    }

    private static ResponseClock responseClock(JSONObject raw) {
        String timeZone = raw == null ? "" : raw.optString("timezone", "").trim();
        String abbreviation = raw == null ? "" : raw.optString("timezone_abbreviation", "").trim();
        int offsetSeconds = raw == null ? 0 : raw.optInt("utc_offset_seconds", 0);

        ZoneId zone = null;
        if (!timeZone.isEmpty()) {
            try {
                zone = ZoneId.of(timeZone);
            } catch (Exception ignored) { }
        }
        if (zone == null) {
            try {
                zone = ZoneOffset.ofTotalSeconds(offsetSeconds);
            } catch (Exception ignored) {
                zone = ZoneOffset.UTC;
                offsetSeconds = 0;
            }
        }
        String id = timeZone.isEmpty() ? zone.getId() : timeZone;
        return new ResponseClock(zone, offsetSeconds, id, abbreviation);
    }

    private static JSONObject timeZoneJson(ResponseClock clock) throws Exception {
        JSONObject out = new JSONObject();
        out.put("id", clock.id);
        out.put("utcOffsetSeconds", clock.utcOffsetSeconds);
        if (!clock.abbreviation.isEmpty()) out.put("abbreviation", clock.abbreviation);
        return out;
    }

    private static Instant localTimestampToInstant(String value, ResponseClock clock) {
        LocalDateTime local = parseLocalDateTime(value);
        if (local == null || clock == null) return null;
        try {
            return local.atZone(clock.zone).toInstant();
        } catch (Exception ignored) {
            try {
                return local.toInstant(ZoneOffset.ofTotalSeconds(clock.utcOffsetSeconds));
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private static Instant localMidnightToInstant(LocalDate date, ResponseClock clock) {
        if (date == null || clock == null) return null;
        try {
            return date.atStartOfDay(clock.zone).toInstant();
        } catch (Exception ignored) {
            try {
                return date.atStartOfDay().toInstant(ZoneOffset.ofTotalSeconds(clock.utcOffsetSeconds));
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private static Instant nextHourlyBoundary(
            JSONArray times,
            int index,
            Instant start,
            ResponseClock clock) {
        if (times != null && index + 1 < times.length()) {
            Instant next = localTimestampToInstant(stringAt(times, index + 1), clock);
            if (next != null && next.isAfter(start)) return next;
        }
        return start.plus(Duration.ofHours(1));
    }

    private static LocalDateTime parseLocalDateTime(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) return null;
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static LocalDate parseLocalDate(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) return null;
        try {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JSONObject displayDateTime(LocalDateTime local) throws Exception {
        JSONObject out = new JSONObject();
        out.put("year", local.getYear());
        out.put("month", local.getMonthValue());
        out.put("day", local.getDayOfMonth());
        out.put("hours", local.getHour());
        out.put("minutes", local.getMinute());
        return out;
    }

    private static JSONObject displayDate(LocalDate date) throws Exception {
        JSONObject out = new JSONObject();
        out.put("year", date.getYear());
        out.put("month", date.getMonthValue());
        out.put("day", date.getDayOfMonth());
        return out;
    }

    private static String moonPhaseName(double phase) {
        double p = normalizedFraction(phase);
        if (p < 0.0625d || p >= 0.9375d) return "NEW_MOON";
        if (p < 0.1875d) return "WAXING_CRESCENT";
        if (p < 0.3125d) return "FIRST_QUARTER";
        if (p < 0.4375d) return "WAXING_GIBBOUS";
        if (p < 0.5625d) return "FULL_MOON";
        if (p < 0.6875d) return "WANING_GIBBOUS";
        if (p < 0.8125d) return "LAST_QUARTER";
        return "WANING_CRESCENT";
    }

    private static double normalizedFraction(double value) {
        double result = value % 1d;
        if (result < 0d) result += 1d;
        return result;
    }

    private static int weatherSeverity(int code) {
        switch (code) {
            case 99: return 120;
            case 96: return 115;
            case 95: return 110;
            case 82: return 100;
            case 86: return 98;
            case 67: return 96;
            case 65: return 94;
            case 75: return 92;
            case 57: return 90;
            case 85: return 86;
            case 81: return 84;
            case 66: return 82;
            case 63: return 80;
            case 73: return 78;
            case 56: return 76;
            case 55: return 74;
            case 80: return 72;
            case 61: return 70;
            case 71: return 68;
            case 53: return 66;
            case 51: return 64;
            case 77: return 62;
            case 48: return 54;
            case 45: return 52;
            case 3: return 30;
            case 2: return 20;
            case 1: return 10;
            case 0: return 0;
            default: return 1;
        }
    }

    private static String cardinalName(double degrees) {
        final String[] names = {
                "NORTH", "NORTH_NORTHEAST", "NORTHEAST", "EAST_NORTHEAST",
                "EAST", "EAST_SOUTHEAST", "SOUTHEAST", "SOUTH_SOUTHEAST",
                "SOUTH", "SOUTH_SOUTHWEST", "SOUTHWEST", "WEST_SOUTHWEST",
                "WEST", "WEST_NORTHWEST", "NORTHWEST", "NORTH_NORTHWEST"
        };
        int index = (int) Math.floor((normalizeDegrees(degrees) + 11.25d) / 22.5d) % 16;
        return names[index];
    }

    private static double normalizeDegrees(double value) {
        double result = value % 360d;
        return result < 0d ? result + 360d : result;
    }

    private static int boundedPercent(double value) {
        return (int) Math.round(Math.max(0d, Math.min(100d, value)));
    }

    private static int roundedNonNegativeInt(double value) {
        return (int) Math.round(Math.max(0d, value));
    }

    private static Double sum(Double existing, double value) {
        return (existing == null ? 0d : existing) + value;
    }

    private static Double finiteNumber(JSONObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.isNull(key)) return null;
        double value = object.optDouble(key, Double.NaN);
        return Double.isFinite(value) ? value : null;
    }

    private static Integer integerValue(JSONObject object, String key) {
        Double value = finiteNumber(object, key);
        return value == null ? null : (int) Math.round(value);
    }

    private static Double numberAt(JSONObject section, String key, int index) {
        if (section == null) return null;
        JSONArray array = section.optJSONArray(key);
        if (array == null || index < 0 || index >= array.length() || array.isNull(index)) return null;
        double value = array.optDouble(index, Double.NaN);
        return Double.isFinite(value) ? value : null;
    }

    private static Integer integerAt(JSONObject section, String key, int index) {
        Double value = numberAt(section, key, index);
        return value == null ? null : (int) Math.round(value);
    }

    private static String stringAt(JSONObject section, String key, int index) {
        if (section == null) return "";
        return stringAt(section.optJSONArray(key), index);
    }

    private static String stringAt(JSONArray array, int index) {
        if (array == null || index < 0 || index >= array.length() || array.isNull(index)) return "";
        String value = array.optString(index, "");
        return "null".equalsIgnoreCase(value) ? "" : value.trim();
    }

    private static JSONObject copy(JSONObject source) throws Exception {
        return source == null ? new JSONObject() : new JSONObject(source.toString());
    }

    private static void validateCoordinates(double lat, double lon) {
        if (!Double.isFinite(lat) || lat < -90d || lat > 90d) {
            throw new IllegalArgumentException("Latitude must be finite and between -90 and 90 degrees");
        }
        if (!Double.isFinite(lon) || lon < -180d || lon > 180d) {
            throw new IllegalArgumentException("Longitude must be finite and between -180 and 180 degrees");
        }
    }

    private static ModelChoice modelChoice(String raw) {
        String requested = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (requested.isEmpty() || "auto".equals(requested)) {
            return new ModelChoice(requested.isEmpty() ? "auto" : requested,
                    "auto", "Best Match (automatic)");
        }
        switch (requested) {
            case "best_match":
                return new ModelChoice(requested, "best_match", "Best Match (automatic)");
            case "ecmwf_ifs":
                return new ModelChoice(requested, "ecmwf_ifs", "ECMWF IFS HRES 9 km");
            case "dwd_icon_seamless":
            case "icon_seamless":
                return new ModelChoice(requested, "icon_seamless", "DWD ICON Seamless");
            case "ncep_gfs_seamless":
                return new ModelChoice(requested, "ncep_gfs_seamless", "NCEP GFS Seamless");
            case "meteofrance_seamless":
                return new ModelChoice(requested, "meteofrance_seamless", "Météo-France Seamless");
            case "ukmo_seamless":
                return new ModelChoice(requested, "ukmo_seamless", "UK Met Office Seamless");
            case "knmi_seamless":
                return new ModelChoice(requested, "knmi_seamless", "KNMI Seamless");
            case "dmi_seamless":
                return new ModelChoice(requested, "dmi_seamless", "DMI Seamless");
            case "jma_seamless":
                return new ModelChoice(requested, "jma_seamless", "JMA Seamless");
            case "gem_seamless":
                return new ModelChoice(requested, "gem_seamless", "GEM Seamless");
            default:
                throw new IllegalArgumentException(
                        "Unsupported Open-Meteo model id: " + safeIdentifier(requested));
        }
    }

    private static Endpoint endpoint(String paidApiKey) {
        String key = paidApiKey == null ? "" : paidApiKey.trim();
        if (key.length() > 4096) throw new IllegalArgumentException("Open-Meteo API key is too long");
        return key.isEmpty()
                ? new Endpoint(FREE_ENDPOINT, "", false)
                : new Endpoint(CUSTOMER_ENDPOINT, key, true);
    }

    private static StringBuilder baseQuery(
            Endpoint endpoint,
            double lat,
            double lon,
            ModelChoice model) throws Exception {
        StringBuilder url = new StringBuilder(endpoint.baseUrl);
        append(url, "latitude", Double.toString(lat));
        append(url, "longitude", Double.toString(lon));
        if (!"auto".equals(model.apiId)) append(url, "models", model.apiId);
        if (endpoint.paid) append(url, "apikey", endpoint.apiKey);
        return url;
    }

    private static void append(StringBuilder url, String key, String value) throws Exception {
        if (url.indexOf("?") < 0) url.append('?');
        else url.append('&');
        url.append(URLEncoder.encode(key, StandardCharsets.UTF_8.name()));
        url.append('=');
        url.append(URLEncoder.encode(value, StandardCharsets.UTF_8.name()));
    }

    private static JSONObject requestJson(Context context, String address, String kind,
            OpenMeteoRequestBudgetManager.Category category) throws Exception {
        OpenMeteoRequestBudgetManager.Decision budget =
                OpenMeteoRequestBudgetManager.tryAcquire(context, category);
        if (!budget.allowed) {
            DiagnosticLog.event(DiagnosticLog.Area.OPEN_METEO, DiagnosticLog.Event.REQUEST_LIMIT);
            throw new IOException(budget.message);
        }
        long diagnosticStart = android.os.SystemClock.elapsedRealtime();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(address).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "ZwerkWeather-OpenMeteo/1");

            int status = connection.getResponseCode();
            DiagnosticLog.http(DiagnosticLog.Area.OPEN_METEO, status, android.os.SystemClock.elapsedRealtime() - diagnosticStart);
            boolean success = status >= 200 && status < 300;
            int limit = success ? MAX_SUCCESS_BODY_BYTES : MAX_ERROR_BODY_BYTES;
            long declaredLength = connection.getContentLength();
            if (declaredLength > limit) {
                throw new IOException("Open-Meteo " + kind + " response exceeded the allowed size");
            }

            InputStream stream = success ? connection.getInputStream() : connection.getErrorStream();
            String body = readBounded(stream, limit);
            if (!success) {
                String reason = serviceReason(body);
                String message = "Open-Meteo " + kind + " request failed (HTTP " + status + ")";
                if (!reason.isEmpty()) message += ": " + reason;
                throw new IOException(message);
            }
            if (body.trim().isEmpty()) {
                throw new IOException("Open-Meteo " + kind + " returned an empty response");
            }

            JSONObject json;
            try {
                json = new JSONObject(body);
            } catch (Exception parseError) {
                throw new IOException("Open-Meteo " + kind + " returned invalid JSON", parseError);
            }
            if (json.optBoolean("error", false)) {
                String reason = sanitizeMessage(json.optString("reason", ""));
                throw new IOException("Open-Meteo " + kind + " returned an error"
                        + (reason.isEmpty() ? "" : ": " + reason));
            }
            return json;
        } catch (Exception failure) {
            DiagnosticLog.error(DiagnosticLog.Area.OPEN_METEO, failure);
            throw failure;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readBounded(InputStream stream, int maxBytes) throws IOException {
        if (stream == null) return "";
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new IOException("Open-Meteo response exceeded the allowed size");
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String serviceReason(String body) {
        if (body == null || body.trim().isEmpty()) return "";
        try {
            JSONObject json = new JSONObject(body);
            String reason = sanitizeMessage(json.optString("reason", ""));
            if (!reason.isEmpty()) return reason;
            return sanitizeMessage(json.optString("message", ""));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String sanitizeMessage(String value) {
        if (value == null) return "";
        String safe = value.replaceAll("[\r\n\t]+", " ").trim();
        // Never propagate a returned URL or key-like token into an exception message.
        safe = safe.replaceAll("(?i)https?://\\S+", "[url omitted]");
        safe = safe.replaceAll(
                "(?i)(apikey|api[_ -]?key|key)\\s*[=:]\\s*[^,; ]+",
                "$1=[redacted]");
        if (safe.length() > 240) safe = safe.substring(0, 240);
        return safe;
    }

    private static String safeIdentifier(String value) {
        if (value == null) return "";
        String safe = value.replaceAll("[^a-zA-Z0-9_.-]", "");
        return safe.length() <= 64 ? safe : safe.substring(0, 64);
    }

    private static String join(String... values) {
        StringBuilder out = new StringBuilder();
        if (values == null) return "";
        for (String value : values) {
            if (value == null || value.isEmpty()) continue;
            if (out.length() > 0) out.append(',');
            out.append(value);
        }
        return out.toString();
    }

    private static final class Endpoint {
        final String baseUrl;
        final String apiKey;
        final boolean paid;

        Endpoint(String baseUrl, String apiKey, boolean paid) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.paid = paid;
        }
    }

    private static final class ModelChoice {
        final String requestedId;
        final String apiId;
        final String label;

        ModelChoice(String requestedId, String apiId, String label) {
            this.requestedId = requestedId;
            this.apiId = apiId;
            this.label = label;
        }
    }

    private static final class ResponseClock {
        final ZoneId zone;
        final int utcOffsetSeconds;
        final String id;
        final String abbreviation;

        ResponseClock(ZoneId zone, int utcOffsetSeconds, String id, String abbreviation) {
            this.zone = zone;
            this.utcOffsetSeconds = utcOffsetSeconds;
            this.id = id;
            this.abbreviation = abbreviation == null ? "" : abbreviation;
        }
    }

    private static final class WeatherText {
        final String type;
        final String description;

        WeatherText(String type, String description) {
            this.type = type;
            this.description = description;
        }
    }

    private static final class PeriodAggregate {
        boolean any;
        Integer weatherCode;
        int weatherSeverity = Integer.MIN_VALUE;
        Integer maxProbability;
        Double qpfMm;
        Double rainMm;
        Double showersMm;
        Double snowfallCm;
    }
}
