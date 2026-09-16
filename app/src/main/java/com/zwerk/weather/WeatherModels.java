package com.zwerk.weather;

import org.json.JSONObject;

import java.time.Instant;

/** Package-private DTOs shared across request, cache, minute-forecast and rendering boundaries. */
final class WeatherModels {
    private WeatherModels() { }
}

final class SceneSpec {
    final String base;
    final String effect;
    final boolean daytime;
    final String condition;

    SceneSpec(String base, String effect, boolean daytime, String condition) {
        this.base = base == null ? "day" : base;
        this.effect = effect == null ? "none" : effect;
        this.daytime = daytime;
        this.condition = condition == null || condition.trim().isEmpty() ? "Unknown" : condition.trim();
    }

    static SceneSpec fromWeather(JSONObject weather, boolean fallbackDaytime) {
        String condition = description(weather);
        String key = conditionKey(condition);
        boolean daytime = safeBoolean(weather, "isDaytime", fallbackDaytime);
        if ("snow".equals(key)) return new SceneSpec("snow", "snow", daytime, condition);
        if ("thunder".equals(key)) return new SceneSpec("rain", "thunder", daytime, condition);
        if ("rain".equals(key)) return new SceneSpec("rain", "rain", daytime, condition);
        if ("fog".equals(key)) return new SceneSpec(daytime ? "rain" : "night", "fog", daytime, condition);
        return new SceneSpec(daytime ? "day" : "night", "none", daytime, condition);
    }

    String paletteScene() {
        if ("snow".equals(effect)) return "snow";
        if ("rain".equals(effect) || "thunder".equals(effect) || "fog".equals(effect)) return "rain";
        return daytime ? "day" : "night";
    }

    String headerScene() {
        if ("fog".equals(effect) || "thunder".equals(effect)) return effect;
        if ("rain".equals(effect)) return "rain";
        if ("snow".equals(effect)) return "snow";
        return daytime ? "day" : "night";
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof SceneSpec)) return false;
        SceneSpec that = (SceneSpec) other;
        return daytime == that.daytime && base.equals(that.base) && effect.equals(that.effect) && condition.equals(that.condition);
    }

    @Override public int hashCode() {
        int result = base.hashCode();
        result = 31 * result + effect.hashCode();
        result = 31 * result + (daytime ? 1 : 0);
        result = 31 * result + condition.hashCode();
        return result;
    }

    private static String description(JSONObject weather) {
        if (weather == null) return "Unknown";
        JSONObject condition = weather.optJSONObject("weatherCondition");
        if (condition == null) return "Unknown";
        JSONObject description = condition.optJSONObject("description");
        String text = description == null ? "" : description.optString("text", "").trim();
        if (!text.isEmpty()) return text;
        String type = condition.optString("type", "Unknown").trim();
        return type.isEmpty() ? "Unknown" : prettyEnum(type);
    }

    private static String conditionKey(String condition) {
        String c = condition == null ? "" : condition.toLowerCase(java.util.Locale.ROOT);
        if (c.contains("thunder") || c.contains("storm")) return "thunder";
        if (c.contains("snow") || c.contains("flurr") || c.contains("sleet") || c.contains("ice")) return "snow";
        if (c.contains("rain") || c.contains("drizzle") || c.contains("shower")) return "rain";
        if (c.contains("fog") || c.contains("mist") || c.contains("haze") || c.contains("smoke")) return "fog";
        if (c.contains("cloud") || c.contains("overcast")) {
            return (c.contains("part") || c.contains("mostly") || c.contains("scattered")) ? "partly" : "cloud";
        }
        if (c.contains("clear") || c.contains("sun")) return "clear";
        return "partly";
    }

    private static String prettyEnum(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String[] parts = raw.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder b = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return b.toString();
    }

    private static boolean safeBoolean(JSONObject object, String key, boolean fallback) {
        if (object == null || !object.has(key) || object.isNull(key)) return fallback;
        try {
            return object.optBoolean(key, fallback);
        } catch (Exception ignored) {
            return fallback;
        }
    }
}

final class MinuteForecastState {
    private static final long CACHE_MAX_AGE_MILLIS = 60L * 60L * 1000L;
    final int generation;
    final double latitude;
    final double longitude;
    final String language;
    final boolean loading;
    final long fetchedAtMillis;
    final JSONObject response;
    final String errorMessage;
    final boolean unsupported;

    MinuteForecastState(int generation, double latitude, double longitude, String language,
                        boolean loading, long fetchedAtMillis, JSONObject response,
                        String errorMessage, boolean unsupported) {
        this.generation = generation;
        this.latitude = latitude;
        this.longitude = longitude;
        this.language = language == null ? "" : language;
        this.loading = loading;
        this.fetchedAtMillis = fetchedAtMillis;
        this.response = response;
        this.errorMessage = errorMessage == null ? "" : errorMessage;
        this.unsupported = unsupported;
    }

    boolean matches(int generation, double latitude, double longitude, String language) {
        return this.generation == generation
                && Double.doubleToLongBits(this.latitude) == Double.doubleToLongBits(latitude)
                && Double.doubleToLongBits(this.longitude) == Double.doubleToLongBits(longitude)
                && this.language.equals(language == null ? "" : language);
    }

    boolean sameLocationLanguage(double latitude, double longitude, String language) {
        return Double.doubleToLongBits(this.latitude) == Double.doubleToLongBits(latitude)
                && Double.doubleToLongBits(this.longitude) == Double.doubleToLongBits(longitude)
                && this.language.equals(language == null ? "" : language);
    }

    boolean isFresh(long nowMillis) {
        if (loading || response == null || fetchedAtMillis <= 0L) return false;
        long age = nowMillis - fetchedAtMillis;
        return age >= 0L && age < CACHE_MAX_AGE_MILLIS;
    }

    MinuteForecastState rebind(int generation) {
        return new MinuteForecastState(generation, latitude, longitude, language,
                false, fetchedAtMillis, response, "", false);
    }
}

final class OptionalDataState {
    final int generation;
    final double latitude;
    final double longitude;
    final String language;
    final boolean loading;
    final boolean available;
    final String value;
    final String accessibility;

    OptionalDataState(int generation, double latitude, double longitude, String language,
                      boolean loading, boolean available, String value, String accessibility) {
        this.generation = generation;
        this.latitude = latitude;
        this.longitude = longitude;
        this.language = language == null ? "" : language;
        this.loading = loading;
        this.available = available;
        this.value = value == null ? "" : value;
        this.accessibility = accessibility == null ? "" : accessibility;
    }

    boolean matches(int generation, double latitude, double longitude, String language) {
        return this.generation == generation
                && Double.doubleToLongBits(this.latitude) == Double.doubleToLongBits(latitude)
                && Double.doubleToLongBits(this.longitude) == Double.doubleToLongBits(longitude)
                && this.language.equals(language == null ? "" : language);
    }
}

final class MinuteCacheSnapshot {
    final long fetchedAtMillis;
    final JSONObject response;

    MinuteCacheSnapshot(long fetchedAtMillis, JSONObject response) {
        this.fetchedAtMillis = fetchedAtMillis;
        this.response = response;
    }
}

final class MinuteSegment {
    final JSONObject raw;
    final Instant start;
    final Instant end;
    final Integer probability;
    final Double qpfQuantity;
    final String qpfUnit;
    final Double snowfallQuantity;
    final String snowfallUnit;
    final String type;
    final String intensity;

    MinuteSegment(JSONObject raw, Instant start, Instant end, Integer probability,
                  Double qpfQuantity, String qpfUnit, Double snowfallQuantity,
                  String snowfallUnit, String type, String intensity) {
        this.raw = raw;
        this.start = start;
        this.end = end;
        this.probability = probability;
        this.qpfQuantity = qpfQuantity;
        this.qpfUnit = qpfUnit == null ? "" : qpfUnit;
        this.snowfallQuantity = snowfallQuantity;
        this.snowfallUnit = snowfallUnit == null ? "" : snowfallUnit;
        this.type = type == null ? "" : type;
        this.intensity = intensity == null ? "" : intensity;
    }
}

final class MinuteCoverage {
    final Instant start;
    final Instant end;

    MinuteCoverage(Instant start, Instant end) {
        this.start = start;
        this.end = end;
    }
}
