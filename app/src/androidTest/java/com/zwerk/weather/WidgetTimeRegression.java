package com.zwerk.weather;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class WidgetTimeRegression {
    private int checks;

    int run(Context context) throws Exception {
        SharedPreferences ui = context.getSharedPreferences(WeatherPreferences.PREFS_NAME, 0);
        SharedPreferences snapshot = context.getSharedPreferences(WeatherWidgetProvider.PREFS_NAME, 0);
        Map<String, ?> originalUi = new HashMap<>(ui.getAll());
        Map<String, ?> originalSnapshot = new HashMap<>(snapshot.getAll());
        Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            checkTime(context, ui);
            checkWidgets(context, snapshot, ui);
            exportPreviews(context);
            return checks;
        } finally {
            restore(ui, originalUi);
            restore(snapshot, originalSnapshot);
            Locale.setDefault(originalLocale);
            WeatherWidgetProvider.requestRefresh(context);
        }
    }

    private void checkTime(Context context, SharedPreferences ui) throws Exception {
        ZoneId zone = ZoneId.of("UTC");
        Instant afternoon = Instant.parse("2026-09-30T14:00:00Z");
        ui.edit().putString(WeatherTimeFormat.PREF, WeatherTimeFormat.TWELVE).commit();
        check("2:00 PM".equals(WeatherTimeFormat.time(context, afternoon, zone)), "12-hour afternoon");
        check("12:00 AM".equals(WeatherTimeFormat.time(context,
                Instant.parse("2026-09-30T00:00:00Z"), zone)), "12-hour midnight");
        check("12:00 PM".equals(WeatherTimeFormat.time(context,
                Instant.parse("2026-09-30T12:00:00Z"), zone)), "12-hour noon");
        check("2 PM".equals(WeatherTimeFormat.hour(context, afternoon.toEpochMilli(), zone)), "compact hour");
        JSONObject hour = new JSONObject().put("displayDateTime",
                new JSONObject().put("hours", 14).put("minutes", 0));
        check("14:00".equals(WeatherActivityFoundation.rawHourLabel(hour, zone)), "data matching stays in 24-hour time");
        check("2 PM".equals(new WidgetForecastFormatter(context).hourLabel(hour, zone)), "snapshot formatter follows setting");
        ui.edit().putString(WeatherTimeFormat.PREF, WeatherTimeFormat.TWENTY_FOUR).commit();
        check("14:00".equals(WeatherTimeFormat.time(context, afternoon, zone)), "24-hour afternoon");
        check("00:00".equals(WeatherTimeFormat.clock(context, "00:00")), "24-hour midnight");
        ui.edit().putString(WeatherTimeFormat.PREF, WeatherTimeFormat.SYSTEM).commit();
        check(WeatherTimeFormat.is24Hour(context) == android.text.format.DateFormat.is24HourFormat(context),
                "system preference follows Android");
        ui.edit().putString(WeatherTimeFormat.PREF, "invalid").commit();
        check(WeatherTimeFormat.SYSTEM.equals(WeatherTimeFormat.selected(context)), "invalid preference falls back");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void checkWidgets(Context context, SharedPreferences snapshot, SharedPreferences ui) throws Exception {
        long now = System.currentTimeMillis();
        long start = now / 3_600_000L * 3_600_000L;
        JSONArray hours = new JSONArray();
        for (int i = 0; i < 12; i++) hours.put(new JSONObject().put("start", start + i * 3_600_000L)
                .put("end", start + (i + 1) * 3_600_000L).put("temperature", -99)
                .put("condition", "Partly cloudy").put("conditionType", "partly").put("daytime", true));
        snapshot.edit().putBoolean(WeatherWidgetProvider.KEY_HAS_SNAPSHOT, true)
                .putLong(WeatherWidgetProvider.KEY_UPDATED_EPOCH_MS, now)
                .putLong(WeatherWidgetProvider.KEY_OBSERVATION_EPOCH_MS, now)
                .putString(WeatherWidgetProvider.KEY_CITY, "A very long location name")
                .putInt(WeatherWidgetProvider.KEY_TEMPERATURE, -99)
                .putInt(WeatherWidgetProvider.KEY_DAILY_HIGH, -10)
                .putInt(WeatherWidgetProvider.KEY_DAILY_LOW, -99)
                .putString(WeatherWidgetProvider.KEY_UNIT, "°F")
                .putString(WeatherWidgetProvider.KEY_CONDITION_TYPE, "partly")
                .putString(WeatherWidgetProvider.KEY_HOURLY_JSON, hours.toString()).commit();
        ui.edit().putString(WeatherTimeFormat.PREF, WeatherTimeFormat.TWELVE).commit();
        Class<? extends Enum> modes = (Class<? extends Enum>) Class.forName(
                "com.zwerk.weather.WeatherWidgetProvider$WidgetMode");
        Method build = WeatherWidgetProvider.class.getDeclaredMethod("buildMode", Context.class, modes);
        build.setAccessible(true);
        Object[][] sizes = {{"MINI",109,56}, {"MINI",180,90}, {"SMALL",109,118},
                {"SMALL",180,180}, {"NORMAL",240,118}, {"ROOMY",280,152}, {"TALL",280,192}};
        for (Object[] size : sizes) {
            RemoteViews remote = (RemoteViews) build.invoke(null, context, Enum.valueOf(modes, (String) size[0]));
            View widget = render(context, remote, (int) size[1], (int) size[2]);
            TextView temperature = widget.findViewById(R.id.widget_temperature);
            check(temperature.getText().toString().equals("-99°F"), "signed temperature and units survive rendering");
            check(temperature.getLayout().getLineWidth(0) <= temperature.getWidth() + 1,
                    size[0] + " temperature fits at minimum size");
            View icon = widget.findViewById(R.id.widget_glyph);
            check(icon.getVisibility() == View.VISIBLE && icon.getWidth() > 0 && icon.getHeight() > 0,
                    size[0] + " weather icon remains visible");
            Rect textBounds = new Rect(), iconBounds = new Rect();
            temperature.getHitRect(textBounds);
            icon.getHitRect(iconBounds);
            if (size[0].equals("MINI") || size[0].equals("SMALL")) {
                check(!Rect.intersects(textBounds, iconBounds), "compact temperature and icon do not overlap");
                check(widget.findViewById(R.id.widget_city).getVisibility()
                        == (size[0].equals("SMALL") ? View.VISIBLE : View.GONE), "compact information follows size");
            } else {
                TextView time = widget.findViewById(R.id.widget_hour_time_1);
                check(time.getText().toString().contains("M"), "forecast widget honors 12-hour setting");
            }
        }
        snapshot.edit().putBoolean(WeatherWidgetProvider.KEY_HAS_SNAPSHOT, false).commit();
        for (String mode : new String[]{"MINI", "SMALL"}) {
            RemoteViews remote = (RemoteViews) build.invoke(null, context, Enum.valueOf(modes, mode));
            View widget = render(context, remote, 109, mode.equals("MINI") ? 56 : 118);
            check(((TextView) widget.findViewById(R.id.widget_temperature)).getText().toString().startsWith("—"),
                    "empty snapshot shows a safe placeholder");
        }
    }

    private static RemoteViews samplePreview(Context context, int layout) {
        RemoteViews preview = new RemoteViews(context.getPackageName(), layout);
        SpannableString temperature = new SpannableString("24°C");
        temperature.setSpan(new RelativeSizeSpan(.48f), 2, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        preview.setTextViewText(R.id.widget_temperature, temperature);
        return preview;
    }

    private void exportPreviews(Context context) throws Exception {
        int[] layouts = {R.layout.widget_weather_preview, R.layout.widget_weather_small_preview,
                R.layout.widget_weather_mini_preview, R.layout.widget_precipitation_preview};
        String[] names = {"weather", "weather_small", "weather_mini", "precipitation"};
        int[][] sizes = {{280,144},{156,156},{156,72},{280,144}};
        for (int i = 0; i < layouts.length; i++) {
            RemoteViews preview = i == 3 ? new RemoteViews(context.getPackageName(), layouts[i])
                    : samplePreview(context, layouts[i]);
            View view = render(context, preview, sizes[i][0], sizes[i][1]);
            Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(bitmap));
            File directory = new File(context.getFilesDir(), "widget-previews");
            directory.mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(directory,
                    "widget_" + names[i] + "_preview.png"))) {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out), "native preview export");
            } finally { bitmap.recycle(); }
        }
    }

    private static View render(Context context, RemoteViews views, int widthDp, int heightDp) {
        View view = views.apply(context, new LinearLayout(context));
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(widthDp * density), height = Math.round(heightDp * density);
        for (int i = 0; i < 2; i++) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, width, height);
        }
        return view;
    }

    private void check(boolean passed, String message) {
        checks++;
        if (!passed) throw new AssertionError(message);
    }

    @SuppressWarnings("unchecked")
    private static void restore(SharedPreferences prefs, Map<String, ?> values) {
        SharedPreferences.Editor edit = prefs.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else if (value instanceof Set) edit.putStringSet(key, (Set<String>) value);
        }
        edit.commit();
    }
}
