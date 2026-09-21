package com.zwerk.weather;

import android.content.Context;
import android.util.AtomicFile;

import java.io.File;
import java.nio.charset.StandardCharsets;

/** The radar source is selected locally; Google reuses the app's existing key. */
final class RadarProviderConfig {
    static final String RAINVIEWER = "rainviewer";
    static final String GOOGLE = "google";
    static final String SOURCE_PREF = "radar_source";
    private static final String UI_PREFS = "WEATHER_UI";

    private RadarProviderConfig() { }

    static String source(Context context) {
        String selected = context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
                .getString(SOURCE_PREF, RAINVIEWER);
        return GOOGLE.equals(selected) ? GOOGLE : RAINVIEWER;
    }

    static void setSource(Context context, String source) {
        context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).edit()
                .putString(SOURCE_PREF, GOOGLE.equals(source) ? GOOGLE : RAINVIEWER).apply();
    }

    static String readGoogleKey(Context context) {
        try {
            byte[] bytes = new AtomicFile(new File(context.getFilesDir(),
                    "weather_api_key")).readFully();
            if (bytes.length > 512) return "";
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            return "";
        }
    }
}
