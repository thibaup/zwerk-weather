package com.zwerk.weather;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Translates static forecast UI labels while keeping English as a safe fallback. */
final class UiTranslations {
    static final String MAP_THEME_LIGHT_KEY = "Map theme: Light";
    private static final ConcurrentHashMap<String, JSONObject> CACHE = new ConcurrentHashMap<>();

    private UiTranslations() { }

    static String text(Context context, String english) {
        if (english == null || english.isEmpty()) return english;
        boolean isMapThemeLight = MAP_THEME_LIGHT_KEY.equals(english);
        if (context == null) return isMapThemeLight ? "Light" : english;
        String tag = catalogTag(AppLocaleManager.activeLocale(context));
        if ("en".equals(tag)) return isMapThemeLight ? "Light" : english;
        JSONObject catalog = CACHE.get(tag);
        if (catalog == null) {
            JSONObject loaded = load(context, tag);
            JSONObject existing = CACHE.putIfAbsent(tag, loaded);
            catalog = existing != null ? existing : loaded;
        }
        String translated = catalog.optString(english, "");
        if (!translated.isEmpty()) return translated;
        if (isMapThemeLight) return "Light";
        return UiResourceFallback.text(context, english);
    }

    private static String catalogTag(Locale locale) {
        if (locale == null) return "en";
        String language = locale.getLanguage();
        if ("pt".equals(language)) {
            return "BR".equalsIgnoreCase(locale.getCountry()) ? "pt-BR" : "pt-PT";
        }
        if ("no".equals(language)) return "nb";
        return language;
    }

    private static JSONObject load(Context context, String tag) {
        JSONObject merged = new JSONObject();
        for (String catalog : new String[] {
                "ui_forecast_", "ui_secondary_", "ui_conditions_", "ui_notifications_",
                "ui_details_", "ui_setup_", "ui_misc_", "ui_final_", "ui_tail_",
                "ui_support_"}) {
            try (InputStream stream = context.getAssets().open(catalog + tag + ".json");
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                JSONObject entries = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
                for (java.util.Iterator<String> keys = entries.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    merged.put(key, entries.optString(key, key));
                }
            } catch (Exception ignored) {
                // The English label remains usable if a catalog is absent or malformed.
            }
        }
        return merged;
    }
}
