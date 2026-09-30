package com.zwerk.weather;

import android.location.Address;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

public final class CityRadarTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Address place = new Address(Locale.ENGLISH);
        place.setLocality("  Gent  "); place.setAdminArea("Flanders"); place.setSubAdminArea("Gent");
        place.setCountryName("Belgium"); place.setFeatureName("123 Main Street");
        check(LocationNaming.cityName(place, "manual").equals("Gent"), "municipality before a road feature");
        check(LocationNaming.regionLabel(place, "Gent").equals("Flanders · Belgium"), "distinct region and country");
        check(LocationNaming.cityName(null, "My cabin").equals("My cabin"), "custom fallback preserved");
        place.setLocality(null); place.setAdminArea(null); place.setSubAdminArea(null);
        check(LocationNaming.cityName(place, "My cabin").equals("My cabin"), "street cannot replace custom name");
        place.setFeatureName("50.8, 4.3");
        check(LocationNaming.cityName(place, "").isEmpty(), "coordinate cannot become a GPS place name");
        check(LocationNaming.choose("Unknown", "", "", "Baker Street", "Home").equals("Home"), "unknown and road rejected");
        check(LocationNaming.choose("", "", "", "Hoofdstraat", "Home").equals("Home"), "Dutch road feature rejected");
        JSONObject valid = new JSONObject().put("name", "Gent").put("latitude", 51.05).put("longitude", 3.72)
                .put("admin1", "Flanders").put("country", "Belgium").put("country_code", "BE");
        JSONArray results = new JSONArray().put(valid)
                .put(new JSONObject().put("name", "Bad").put("latitude", 999).put("longitude", 4))
                .put(new JSONObject().put("name", "No coordinates"))
                .put(new JSONObject().put("latitude", 51).put("longitude", 4));
        List<Address> parsed = CitySearchClient.parse(new JSONObject().put("results", results), Locale.forLanguageTag("nl"));
        check(parsed.size() == 1, "malformed place results are excluded");
        check(parsed.get(0).getCountryCode().equals("BE") && parsed.get(0).getLatitude() == 51.05,
                "coordinates and context retained");
        check(CitySearchClient.parse(new JSONObject(), Locale.ENGLISH).isEmpty(), "empty search is handled");
        boolean failed = false;
        try { CitySearchClient.parse(new JSONObject().put("error", true), Locale.ENGLISH); }
        catch (IOException expected) { failed = true; }
        check(failed, "API error triggers platform fallback");
        String url = CitySearchClient.requestUrl("São Paulo & Gent", "pt");
        check(url.contains("S%C3%A3o+Paulo+%26+Gent") && url.contains("language=pt"), "search and locale encoded");
        check(CitySearchClient.requestUrl("Paris", "en&bad=1").contains("language=en&format=json"), "language input bounded");
        JSONArray many = new JSONArray(); for (int i = 0; i < 20; i++) many.put(valid);
        check(CitySearchClient.parse(new JSONObject().put("results", many), Locale.ENGLISH).size() == 10, "result count bounded");

        RadarMapTransform old = new RadarMapTransform(50.85, 4.35, 6, 512);
        RadarMapTransform same = new RadarMapTransform(50.85, 4.35, 6, 512);
        near(same.scaleFrom(old), 1, "unchanged camera scale");
        near(same.translationX(old, 1200), 0, "unchanged X");
        near(same.translationY(old, 1000), 0, "unchanged Y");
        RadarMapTransform zoom = new RadarMapTransform(50.85, 4.35, 7, 512);
        near(zoom.scaleFrom(old), 2, "one zoom level doubles pixels");
        near(zoom.translationX(old, 1200), -600, "zoom centered horizontally");
        near(zoom.translationY(old, 1000), -500, "zoom centered vertically");
        RadarMapTransform east = new RadarMapTransform(50.85, 5.35, 6, 512);
        near(east.translationX(old, 1200), -old.worldSize / 360d, "pan moves retained tiles opposite camera");
        RadarMapTransform across = new RadarMapTransform(0, -179, 6, 512);
        near(across.translationX(new RadarMapTransform(0, 179, 6, 512), 1200),
                -old.worldSize * 2 / 360d, "dateline takes short path");
        check(Double.isFinite(new RadarMapTransform(90, 0, 7, 512).centerY), "Mercator poles clamped");
        System.out.println("City search and radar projection: " + checks + " checks passed.");
    }
    private static void check(boolean value, String label) {
        checks++; if (!value) throw new AssertionError(label);
    }
    private static void near(double actual, double expected, String label) {
        check(Math.abs(actual - expected) < 0.000001, label + ": " + actual);
    }
}
