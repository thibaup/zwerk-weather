package com.zwerk.weather;

import android.location.Address;
import java.util.ArrayList;
import java.util.Locale;

/** Short place names and disambiguating regions; never replace a user's saved name. */
final class LocationNaming {
    static String cityName(Address address, String fallback) {
        if (address == null) return clean(fallback);
        return choose(address.getLocality(), address.getSubAdminArea(), address.getAdminArea(),
                address.getFeatureName(), fallback);
    }

    static String choose(String locality, String district, String region, String feature, String fallback) {
        for (String value : new String[]{locality, district, region}) if (usable(value)) return clean(value);
        String value = clean(feature);
        // Feature names from reverse geocoders frequently describe a building or road.
        if (usable(value) && !value.matches("(?iu).*(?:\\b(?:road|rd|street|st|avenue|ave|boulevard|blvd|lane|ln|drive|dr|highway|hwy|rue|route|strasse|straße|weg|gasse)\\b|(?:straat|strasse|straße)$).*")
                && !value.matches(".*\\d.*")) return value;
        return clean(fallback);
    }

    static String regionLabel(Address address, String primary) {
        if (address == null) return "";
        ArrayList<String> parts = new ArrayList<>();
        for (String value : new String[]{address.getAdminArea(), address.getSubAdminArea(), address.getCountryName()}) {
            String item = clean(value);
            if (item.isEmpty() || item.equalsIgnoreCase(clean(primary))) continue;
            boolean duplicate = false;
            for (String part : parts) if (part.equalsIgnoreCase(item)) duplicate = true;
            if (!duplicate) parts.add(item);
        }
        return String.join(" · ", parts);
    }

    private static boolean usable(String value) {
        String name = clean(value);
        if (name.isEmpty() || name.matches("[-+\\d.,°;\\s]+")) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return !lower.equals("unknown") && !lower.equals("unnamed road")
                && !lower.equals("onbekend") && !lower.equals("sans nom");
    }

    static String clean(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " "); }
}
