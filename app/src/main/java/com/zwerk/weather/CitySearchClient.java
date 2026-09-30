package com.zwerk.weather;

import android.location.Address;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Explicit, keyless city searches. No weather requests or weather-provider quota. */
final class CitySearchClient {
    private static final int MAX_BYTES = 512 * 1024;
    private static final long CACHE_MILLIS = 10 * 60_000L;
    private static final LinkedHashMap<String, Cached> CACHE = new LinkedHashMap<>(24, .75f, true);

    private static final class Cached {
        final long at;
        final List<Address> addresses;
        Cached(List<Address> addresses) { this.at = System.currentTimeMillis(); this.addresses = addresses; }
    }

    static List<Address> search(String query, Locale locale) throws IOException {
        String name = LocationNaming.clean(query);
        if (name.length() < 2) return new ArrayList<>();
        Locale language = locale == null ? Locale.getDefault() : locale;
        String cacheKey = language.toLanguageTag() + "|" + name.toLowerCase(Locale.ROOT);
        synchronized (CACHE) {
            Cached prior = CACHE.get(cacheKey);
            long age = prior == null ? -1 : System.currentTimeMillis() - prior.at;
            if (prior != null && age >= 0 && age < CACHE_MILLIS) return new ArrayList<>(prior.addresses);
        }
        if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
        HttpURLConnection connection = (HttpURLConnection) new URL(requestUrl(name, language.getLanguage())).openConnection();
        try {
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "ZwerkWeather/1.5");
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("City search returned HTTP " + status);
            List<Address> results;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                    if (body.size() + read > MAX_BYTES) throw new IOException("City search response too large");
                    body.write(buffer, 0, read);
                }
                results = parse(new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8)), language);
            } catch (org.json.JSONException error) {
                throw new IOException("Invalid city search response", error);
            }
            synchronized (CACHE) {
                CACHE.put(cacheKey, new Cached(results));
                while (CACHE.size() > 24) CACHE.remove(CACHE.keySet().iterator().next());
            }
            return new ArrayList<>(results);
        } finally { connection.disconnect(); }
    }

    static String requestUrl(String name, String language) throws IOException {
        String lang = language == null || !language.matches("[a-z]{2,3}") ? "en" : language;
        return "https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(name, "UTF-8")
                + "&count=10&language=" + URLEncoder.encode(lang, "UTF-8") + "&format=json";
    }

    static List<Address> parse(JSONObject response, Locale locale) throws IOException {
        if (response.optBoolean("error", false)) throw new IOException("City search unavailable");
        ArrayList<Address> results = new ArrayList<>();
        JSONArray array = response.optJSONArray("results");
        if (array == null) return results;
        for (int i = 0; i < array.length() && results.size() < 10; i++) {
            JSONObject result = array.optJSONObject(i);
            if (result == null) continue;
            String name = LocationNaming.clean(result.optString("name", ""));
            double lat = result.optDouble("latitude", Double.NaN), lon = result.optDouble("longitude", Double.NaN);
            if (name.isEmpty() || !Double.isFinite(lat) || !Double.isFinite(lon)
                    || Math.abs(lat) > 90 || Math.abs(lon) > 180) continue;
            Address address = new Address(locale);
            address.setLocality(name);
            address.setFeatureName(name);
            address.setAdminArea(result.optString("admin1", ""));
            address.setSubAdminArea(result.optString("admin2", ""));
            address.setCountryName(result.optString("country", ""));
            address.setCountryCode(result.optString("country_code", ""));
            address.setLatitude(lat);
            address.setLongitude(lon);
            results.add(address);
        }
        return results;
    }
}
