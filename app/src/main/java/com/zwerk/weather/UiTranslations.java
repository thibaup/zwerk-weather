package com.zwerk.weather;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Translates static forecast UI labels while keeping English as a safe fallback. */
final class UiTranslations {
    private static String loadedTag = "";
    private static JSONObject translations = new JSONObject();

    private UiTranslations() { }

    static String text(Context context, String english) {
        if (english == null || english.isEmpty() || context == null) return english;
        String tag = catalogTag(Locale.getDefault());
        if ("en".equals(tag)) return english;
        synchronized (UiTranslations.class) {
            if (!tag.equals(loadedTag)) {
                translations = load(context, tag);
                loadedTag = tag;
            }
            String translated = translations.optString(english, "");
            return translated.isEmpty()
                    ? UiResourceFallback.text(context, english) : translated;
        }
    }

    private static String catalogTag(Locale locale) {
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
