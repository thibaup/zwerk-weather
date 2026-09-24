package com.zwerk.weather;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;

/** Persisted forecast source, model and optional customer API credential. */
final class OpenMeteoConfig {
    static final String GOOGLE = "google";
    static final String OPEN_METEO = "open_meteo";
    static final String PREF_PROVIDER = "weather_provider";
    static final String PREF_MODEL = "open_meteo_model";
    private static final String PREFS = "WEATHER_UI";
    private static final String KEY_FILE = "open_meteo_customer_key";
    private static final String KEY_TEMP_FILE = "open_meteo_customer_key.tmp";

    // These request IDs have been checked against Open-Meteo's live Forecast API.
    static final String[] MODEL_IDS = {
            "auto", "ecmwf_ifs", "dwd_icon_seamless", "ncep_gfs_seamless",
            "meteofrance_seamless", "ukmo_seamless", "knmi_seamless",
            "dmi_seamless", "jma_seamless", "gem_seamless"
    };
    static final String[] MODEL_LABELS = {
            "Best Match", "ECMWF IFS", "DWD ICON", "NOAA GFS / HRRR",
            "Météo-France", "UK Met Office", "KNMI", "DMI", "JMA", "GEM Canada"
    };

    private OpenMeteoConfig() { }

    static String provider(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return OPEN_METEO.equals(prefs.getString(PREF_PROVIDER, GOOGLE)) ? OPEN_METEO : GOOGLE;
    }

    static boolean isOpenMeteo(Context context) {
        return OPEN_METEO.equals(provider(context));
    }

    static void setProvider(Context context, String provider) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_PROVIDER, OPEN_METEO.equals(provider) ? OPEN_METEO : GOOGLE).apply();
    }

    static String model(Context context) {
        return normalizeModel(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_MODEL, "auto"));
    }

    static String normalizeModel(String model) {
        for (String supported : MODEL_IDS) {
            if (supported.equals(model)) return supported;
        }
        return "auto";
    }

    static String modelLabel(String model) {
        String normalized = normalizeModel(model);
        for (int i = 0; i < MODEL_IDS.length; i++) {
            if (MODEL_IDS[i].equals(normalized)) return MODEL_LABELS[i];
        }
        return MODEL_LABELS[0];
    }

    static void setModel(Context context, String model) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_MODEL, normalizeModel(model)).apply();
    }

    static String cacheScope(Context context) {
        return isOpenMeteo(context) ? OPEN_METEO + ":" + model(context) : GOOGLE;
    }

    static String readCustomerKey(Context context) {
        File file = new File(context.getFilesDir(), KEY_FILE);
        if (!file.isFile()) return "";
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String key = reader.readLine();
            return key == null ? "" : key.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    static boolean hasCustomerKey(Context context) {
        return !readCustomerKey(context).isEmpty();
    }

    static void writeCustomerKey(Context context, String key) throws Exception {
        String value = key == null ? "" : key.trim();
        if (value.isEmpty()) {
            context.deleteFile(KEY_FILE);
            return;
        }
        File target = new File(context.getFilesDir(), KEY_FILE);
        File temp = new File(context.getFilesDir(), KEY_TEMP_FILE);
        try {
            try (FileOutputStream out = context.openFileOutput(KEY_TEMP_FILE, Context.MODE_PRIVATE)) {
                out.write(value.getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
            Os.rename(temp.getAbsolutePath(), target.getAbsolutePath());
        } finally {
            if (temp.exists()) temp.delete();
        }
    }
}
