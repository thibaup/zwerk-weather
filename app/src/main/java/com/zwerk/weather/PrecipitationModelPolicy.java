package com.zwerk.weather;

/** Preserve manual models; prefer Météo-France's frequently updated short-term rain. */
final class PrecipitationModelPolicy {
    static String resolve(String chosen, double latitude, double longitude) {
        if (chosen != null && !chosen.isEmpty() && !"auto".equals(chosen)) return chosen;
        // AROME France coverage; seamless fills beyond the hourly short-term run.
        // https://open-meteo.com/en/docs/meteofrance-api
        if (latitude >= 37.5 && latitude < 55.4 && longitude >= -12.0 && longitude < 16.0)
            return "meteofrance_seamless";
        return "auto";
    }
}
