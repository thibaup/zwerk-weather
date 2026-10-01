package com.zwerk.weather;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Native Android regression checks; preferences are restored in finally, including on failure. */
public final class SettingsWidgetRegression extends Instrumentation {
    private int checks;
    private boolean compactWidgets;
    private boolean diagnostics;
    private boolean diagnosticCrash;
    private boolean widgetBackground;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        compactWidgets = arguments != null && Boolean.parseBoolean(arguments.getString("compactWidgets"));
        diagnostics = arguments != null && Boolean.parseBoolean(arguments.getString("diagnostics"));
        diagnosticCrash = arguments != null && Boolean.parseBoolean(arguments.getString("diagnosticCrash"));
        widgetBackground = arguments != null && Boolean.parseBoolean(arguments.getString("widgetBackground"));
        start();
    }

    @Override public void onStart() {
        if (diagnosticCrash) {
            DiagnosticLog.http(DiagnosticLog.Area.APP, 299, 1);
            new Thread(() -> {
                throw new IllegalStateException("DIAGNOSTIC_PRIVATE_MESSAGE https://example.invalid/key");
            }, "DiagnosticCrashCheck").start();
            return;
        }
        if (diagnostics) {
            Bundle result = new Bundle();
            try {
                int passed = new DiagnosticRegression().run(getTargetContext());
                result.putString("stream", "Passed " + passed + " native diagnostic checks.\n");
                finish(-1, result);
            } catch (Throwable error) {
                result.putString("stream", "FAILED: " + android.util.Log.getStackTraceString(error));
                finish(0, result);
            }
            return;
        }
        Bundle result = new Bundle();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                Context context = new ContextThemeWrapper(getTargetContext(), android.R.style.Theme_Material_NoActionBar);
                if (widgetBackground) {
                    checks += new WidgetBackgroundRegression().run(context);
                } else if (compactWidgets) {
                    checks += new WidgetTimeRegression().run(context);
                } else {
                    checkSlider(context);
                    checkCharts(context);
                    checkReset(context);
                }
            } catch (Throwable error) { failure.set(error); }
        });
        Throwable error = failure.get();
        result.putString("stream", error == null ? "Passed " + checks + " native settings/widget checks.\n"
                : "FAILED after " + checks + " checks: " + android.util.Log.getStackTraceString(error));
        finish(error == null ? -1 : 0, result);
    }

    private void check(boolean passed, String message) {
        checks++;
        if (!passed) throw new AssertionError(message);
    }

    private static void event(View view, long start, int action, float x, float y, int elapsed) {
        MotionEvent event = MotionEvent.obtain(start, start + elapsed, action, x, y, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }

    private void checkSlider(Context context) {
        ScrollView scroll = new ScrollView(context);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        WeatherSeekBar slider = new WeatherSeekBar(context);
        slider.setMax(100);
        slider.setProgress(70);
        column.addView(slider, new LinearLayout.LayoutParams(300, 60));
        column.addView(new View(context), new LinearLayout.LayoutParams(300, 1000));
        scroll.addView(column);
        scroll.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY));
        scroll.layout(0, 0, 300, 200);
        long now = SystemClock.uptimeMillis();
        event(scroll, now, MotionEvent.ACTION_DOWN, 40, 30, 0);
        event(scroll, now, MotionEvent.ACTION_MOVE, 42, 0, 20);
        event(scroll, now, MotionEvent.ACTION_MOVE, 44, -100, 40);
        event(scroll, now, MotionEvent.ACTION_UP, 44, -100, 60);
        check(slider.getProgress() == 70, "scroll starting on slider must not change transparency");
        check(scroll.getScrollY() > 0, "a vertical gesture starting on slider must scroll the page");
        scroll.scrollTo(0, 0);
        now += 100;
        event(scroll, now, MotionEvent.ACTION_DOWN, 40, 30, 0);
        event(scroll, now, MotionEvent.ACTION_UP, 40, 30, 20);
        check(slider.getProgress() == 70, "an incidental slider tap must not change transparency");
        now += 100;
        event(scroll, now, MotionEvent.ACTION_DOWN, 210, 30, 0);
        event(scroll, now, MotionEvent.ACTION_MOVE, 130, 31, 20);
        event(scroll, now, MotionEvent.ACTION_UP, 130, 31, 40);
        check(slider.getProgress() != 70, "a deliberate horizontal slider drag must change its value");
        int beforeKey = slider.getProgress();
        slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
        check(slider.getProgress() > beforeKey, "native keyboard adjustments must still work");
    }

    private void checkCharts(Context context) throws Exception {
        long now = System.currentTimeMillis();
        JSONArray hours = new JSONArray();
        double[] amounts = {0, .2, .6, .85, .4, .1};
        for (int i = 0; i < 6; i++) hours.put(new JSONObject().put("start", now + i * 3_600_000L)
                .put("end", now + (i + 1) * 3_600_000L).put("precipitationMm", amounts[i]));
        PrecipitationWidgetData data = new PrecipitationWidgetData(hours, null, now);
        Bitmap bitmap = PrecipitationWidgetChart.render(data, 280, 80,
                new String[]{"Now", "+3h", "+6h"}, "No forecast available");
        check(bitmap.getWidth() == 560 && bitmap.getHeight() == 160, "chart resolution must follow allocation");
        check(Color.alpha(bitmap.getPixel(337, 50)) > 200, "rain bars must be visible above the baseline");
        check(Color.alpha(bitmap.getPixel(96, 50)) == 0, "zero quantity must not become a rain bar");
        bitmap.recycle();
        SharedPreferences snapshot = context.getSharedPreferences(WeatherWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE);
        Map<String, ?> original = new HashMap<>(snapshot.getAll());
        try {
            snapshot.edit().putBoolean(WeatherWidgetProvider.KEY_HAS_SNAPSHOT, true)
                    .putLong(WeatherWidgetProvider.KEY_UPDATED_EPOCH_MS, now)
                    .putString(WeatherWidgetProvider.KEY_CITY, "Chart preview")
                    .putString(WeatherWidgetProvider.KEY_HOURLY_JSON, hours.toString())
                    .putString(PrecipitationWidgetProvider.KEY_LOCATION_SCOPE, "rendering-test")
                    .putString(PrecipitationWidgetProvider.KEY_MINUTE_SCOPE, "")
                    .putBoolean(WeatherWidgetProvider.KEY_DAYTIME, false).commit();
            Method builder = PrecipitationWidgetProvider.class.getDeclaredMethod("buildForSize",
                    Context.class, int.class, int.class);
            builder.setAccessible(true);
            for (int[] size : new int[][]{{240, 118}, {320, 144}, {400, 220}}) {
                RemoteViews remote = (RemoteViews) builder.invoke(null, context, size[0], size[1]);
                View widget = remote.apply(context, new LinearLayout(context));
                float density = context.getResources().getDisplayMetrics().density;
                int width = Math.round(size[0] * density), height = Math.round(size[1] * density);
                widget.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                widget.layout(0, 0, width, height);
                check(widget.getPaddingLeft() == 0 && widget.getPaddingTop() == 0
                        && widget.getPaddingRight() == 0 && widget.getPaddingBottom() == 0,
                        "widget background must fill the allocation");
                ImageView chart = widget.findViewById(R.id.precip_widget_chart);
                check(chart.getHeight() >= Math.round(40 * density), "chart must retain space at every supported size");
                TextView title = widget.findViewById(R.id.precip_widget_title);
                check(title.getText().toString().equals(UiTranslations.text(context, "Next 6 hours")),
                        "widget title must describe the six-hour data window");
                Bitmap preview = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                widget.draw(new Canvas(preview));
                try (FileOutputStream out = new FileOutputStream(new File(context.getCacheDir(),
                        "widget-preview-" + size[0] + ".png"))) { preview.compress(Bitmap.CompressFormat.PNG, 100, out); }
                preview.recycle();
            }
        } finally { restore(snapshot, original); }
    }

    private void checkReset(Context context) throws Exception {
        String[] names = {WeatherPreferences.PREFS_NAME, "APP_LOCALE", "api_request_budgets_v1",
                "open_meteo_request_budgets_v1", CityManagerActivity.PREFS_NAME};
        Map<String, Map<String, ?>> originals = new HashMap<>();
        for (String name : names) originals.put(name, new HashMap<>(context.getSharedPreferences(name, 0).getAll()));
        Map<String, byte[]> credentials = new HashMap<>();
        for (String name : new String[]{"weather_api_key", "open_meteo_customer_key"}) {
            File file = new File(context.getFilesDir(), name);
            if (file.isFile()) credentials.put(name, Files.readAllBytes(file.toPath()));
        }
        try {
            SharedPreferences ui = context.getSharedPreferences(WeatherPreferences.PREFS_NAME, 0);
            ui.edit().putInt(WeatherPreferences.PREF_TILE_TRANSPARENCY, 3).putString("temperature_unit", "F")
                    .putString(WeatherPreferences.PREF_RADAR_MAP_THEME, WeatherPreferences.MAP_DARK)
                    .putBoolean("weather_animations", false).putBoolean("radar_page_enabled", false)
                    .putInt("widget_transparency", 88).putString("widget_colour", "custom")
                    .putBoolean("weather_detail_uv_index", false).putBoolean("weather_detail_dew_point", true)
                    .putString("_test_internal_state", "keep").commit();
            ApiRequestBudgetManager.applyProfile(context, ApiRequestBudgetManager.PROFILE_CONSERVATIVE);
            OpenMeteoRequestBudgetManager.setLimits(context, 20, 300);
            WeatherSettingsDefaults.restore(context);
            WeatherPreferences defaults = new WeatherPreferences(context);
            check(defaults.tileTransparency() == 70 && "C".equals(defaults.temperatureUnit()), "reset display and units");
            check(defaults.animationsAllowed() && ui.getBoolean("radar_page_enabled", true), "reset animation and pages");
            check(defaults.radarMapTheme().equals(WeatherPreferences.MAP_AUTOMATIC), "reset automatic map appearance");
            check(!ui.contains("widget_transparency") && !ui.contains("widget_colour"), "reset widget preferences");
            for (WeatherDetailSettingsActivity.DetailOption option : WeatherDetailSettingsActivity.OPTIONS)
                check(ui.getBoolean(option.key, option.defaultValue) == option.defaultValue, "reset detail " + option.key);
            check(OpenMeteoConfig.GOOGLE.equals(OpenMeteoConfig.provider(context))
                    && OpenMeteoConfig.OPEN_METEO.equals(OpenMeteoConfig.precipitationProvider(context)), "reset forecast sources");
            check(AppLocaleManager.selectedTag(context).isEmpty(), "reset system language");
            check(ApiRequestBudgetManager.PROFILE_GOOGLE_FREE.equals(ApiRequestBudgetManager.profile(context)),
                    "reset Google request limits");
            check(!context.getSharedPreferences("open_meteo_request_budgets_v1", 0).contains("daily_limit")
                    && !context.getSharedPreferences("open_meteo_request_budgets_v1", 0).contains("monthly_limit"),
                    "reset Open-Meteo request limits");
            check("keep".equals(ui.getString("_test_internal_state", "")), "preserve unrelated internal state");
            check(context.getSharedPreferences(CityManagerActivity.PREFS_NAME, 0).getAll()
                    .equals(originals.get(CityManagerActivity.PREFS_NAME)), "preserve saved cities");
            for (String name : new String[]{"api_request_budgets_v1", "open_meteo_request_budgets_v1"}) {
                Map<String, ?> after = context.getSharedPreferences(name, 0).getAll();
                for (Map.Entry<String, ?> entry : originals.get(name).entrySet()) {
                    if (entry.getKey().equals("profile") || entry.getKey().startsWith("limit_")
                            || entry.getKey().endsWith("_limit")) continue;
                    check(entry.getValue().equals(after.get(entry.getKey())), "preserve request history " + entry.getKey());
                }
            }
            for (Map.Entry<String, byte[]> entry : credentials.entrySet())
                check(java.util.Arrays.equals(entry.getValue(), Files.readAllBytes(
                        new File(context.getFilesDir(), entry.getKey()).toPath())), "preserve API credential");
        } finally {
            for (String name : names) restore(context.getSharedPreferences(name, 0), originals.get(name));
            AppLocaleManager.wrap(context);
            RainAlertManager.reconcile(context);
            WeatherWidgetProvider.requestRefresh(context);
        }
    }

    @SuppressWarnings("unchecked")
    private static void restore(SharedPreferences prefs, Map<String, ?> values) {
        SharedPreferences.Editor edit = prefs.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
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
