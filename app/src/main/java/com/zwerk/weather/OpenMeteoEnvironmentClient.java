package com.zwerk.weather;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class OpenMeteoEnvironmentClient {
    private static final String[] POLLEN_KEYS = {
            "alder_pollen", "birch_pollen", "grass_pollen", "mugwort_pollen",
            "olive_pollen", "ragweed_pollen"
    };
    private static final String[] POLLEN_LABELS = {
            "Alder", "Birch", "Grass", "Mugwort", "Olive", "Ragweed"
    };
    private static final String AIR_VARS = "european_aqi,us_aqi,pm10,pm2_5,carbon_monoxide,"
            + "nitrogen_dioxide,sulphur_dioxide,ozone";
    private static final String POLLEN_VARS = "alder_pollen,birch_pollen,grass_pollen,"
            + "mugwort_pollen,olive_pollen,ragweed_pollen";

    private OpenMeteoEnvironmentClient() { }

    static OptionalDataState loadAirQuality(Context context, int generation, double lat, double lon,
            String language, String paidKey) throws Exception {
        JSONObject response = request(context, lat, lon, AIR_VARS, 2, paidKey,
                OpenMeteoRequestBudgetManager.Category.AIR_QUALITY);
        JSONObject current = response.optJSONObject("current");
        Double european = number(current, "european_aqi");
        Double us = number(current, "us_aqi");
        if (european == null && us == null) {
            return noData(generation, lat, lon, language,
                    UiTranslations.text(context, "Air quality has no data here"));
        }
        boolean useEuropean = european != null;
        int aqi = (int) Math.round(useEuropean ? european : us);
        String scale = useEuropean ? "European AQI" : "US AQI";
        String category = useEuropean ? europeanCategory(aqi) : usCategory(aqi);
        JSONObject details = new JSONObject();
        details.put("_openMeteo", true);
        details.put("current", current);
        details.put("raw", response);
        details.put("scale", scale);
        String localizedCategory = UiTranslations.text(context, category);
        return new OptionalDataState(generation, lat, lon, language, false, true,
                aqi + "\n" + localizedCategory,
                scale + " " + aqi + ", " + localizedCategory, details);
    }

    static OptionalDataState loadPollen(Context context, int generation, double lat, double lon,
            String language, String paidKey) throws Exception {
        JSONObject response = request(context, lat, lon, POLLEN_VARS, 4, paidKey,
                OpenMeteoRequestBudgetManager.Category.POLLEN);
        JSONObject current = response.optJSONObject("current");
        JSONObject hourly = response.optJSONObject("hourly");
        double highest = -1d;
        String dominant = "";
        for (int i = 0; i < POLLEN_KEYS.length; i++) {
            Double value = number(current, POLLEN_KEYS[i]);
            if (value == null) value = firstAvailable(hourly, POLLEN_KEYS[i]);
            if (value != null && value > highest) {
                highest = value;
                dominant = POLLEN_LABELS[i];
            }
        }
        if (highest < 0d) {
            return noData(generation, lat, lon, language,
                    UiTranslations.text(context, "Pollen unavailable here"));
        }
        JSONObject details = new JSONObject();
        details.put("_openMeteo", true);
        details.put("raw", response);
        String localizedPlant = UiTranslations.text(context, dominant);
        String value = localizedPlant + "\n" + format(highest) + " "
                + UiTranslations.text(context, "grains/m³");
        return new OptionalDataState(generation, lat, lon, language, false, true,
                value, localizedPlant + " " + UiTranslations.text(context, "pollen") + " "
                        + format(highest) + " "
                        + UiTranslations.text(context, "grains per cubic meter"),
                details);
    }

    static String[] pollenKeys() { return POLLEN_KEYS.clone(); }
    static String[] pollenLabels() { return POLLEN_LABELS.clone(); }

    static String europeanCategory(int aqi) {
        if (aqi <= 20) return "Good";
        if (aqi <= 40) return "Fair";
        if (aqi <= 60) return "Moderate";
        if (aqi <= 80) return "Poor";
        if (aqi <= 100) return "Very poor";
        return "Extremely poor";
    }

    static String usCategory(int aqi) {
        if (aqi <= 50) return "Good";
        if (aqi <= 100) return "Moderate";
        if (aqi <= 150) return "Unhealthy for sensitive groups";
        if (aqi <= 200) return "Unhealthy";
        if (aqi <= 300) return "Very unhealthy";
        return "Hazardous";
    }

    static Double number(JSONObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.isNull(key)) return null;
        double value = object.optDouble(key, Double.NaN);
        return Double.isFinite(value) ? value : null;
    }

    static Double numberAt(JSONObject object, String key, int index) {
        JSONArray values = object == null ? null : object.optJSONArray(key);
        if (values == null || index < 0 || index >= values.length() || values.isNull(index)) return null;
        double value = values.optDouble(index, Double.NaN);
        return Double.isFinite(value) ? value : null;
    }

    static String format(double value) {
        return String.format(Locale.getDefault(), value < 10d ? "%.1f" : "%.0f", value);
    }

    private static Double firstAvailable(JSONObject hourly, String key) {
        JSONArray values = hourly == null ? null : hourly.optJSONArray(key);
        if (values == null) return null;
        for (int i = 0; i < Math.min(values.length(), 24); i++) {
            Double value = numberAt(hourly, key, i);
            if (value != null) return value;
        }
        return null;
    }

    private static OptionalDataState noData(int generation, double lat, double lon,
            String language, String message) throws Exception {
        JSONObject marker = new JSONObject();
        marker.put("_noData", true);
        marker.put("_openMeteo", true);
        return new OptionalDataState(generation, lat, lon, language, false, false,
                "Unavailable", message, marker);
    }

    private static JSONObject request(Context context, double lat, double lon, String variables,
            int days, String paidKey, OpenMeteoRequestBudgetManager.Category category)
            throws Exception {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)
                || lat < -90d || lat > 90d || lon < -180d || lon > 180d) {
            throw new IllegalArgumentException("Invalid weather coordinates");
        }
        String key = paidKey == null ? "" : paidKey.trim();
        String host = key.isEmpty() ? "air-quality-api.open-meteo.com"
                : "customer-air-quality-api.open-meteo.com";
        String address = "https://" + host + "/v1/air-quality?latitude=" + lat
                + "&longitude=" + lon + "&current=" + variables + "&hourly=" + variables
                + "&timezone=auto&forecast_days=" + days;
        if (!key.isEmpty()) {
            address += "&apikey=" + URLEncoder.encode(key, StandardCharsets.UTF_8.name());
        }
        OpenMeteoRequestBudgetManager.Decision budget =
                OpenMeteoRequestBudgetManager.tryAcquire(context, category);
        if (!budget.allowed) throw new java.io.IOException(budget.message);
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new java.io.IOException("Open-Meteo air quality HTTP " + status);
            }
            try (InputStream input = connection.getInputStream()) {
                JSONObject result = new JSONObject(
                        WeatherApiActivity.readUtf8Bounded(input, 2 * 1024 * 1024));
                if (result.optBoolean("error", false)) {
                    throw new java.io.IOException("Open-Meteo air quality request failed");
                }
                return result;
            }
        } finally {
            connection.disconnect();
        }
    }
}
