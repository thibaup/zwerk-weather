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
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
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
                    checkDayHistory(context);
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

    private void checkDayHistory(Context context) throws Exception {
        ZoneId zone = ZoneId.of("Europe/Brussels");
        LocalDate today = LocalDate.of(2026, 10, 25);
        Instant now = Instant.parse("2026-10-25T02:20:00Z");
        JSONObject yesterday = historyHour("2026-10-24T21:00:00Z");
        JSONObject firstRepeatedHour = historyHour("2026-10-25T00:00:00Z");
        JSONObject secondRepeatedHour = historyHour("2026-10-25T01:00:00Z");
        JSONObject current = historyHour("2026-10-25T02:00:00Z");
        JSONObject next = historyHour("2026-10-25T03:00:00Z");
        JSONArray history = new JSONArray().put(historyHour("2026-10-25T02:00:00Z")).put(secondRepeatedHour)
                .put(firstRepeatedHour).put(yesterday);
        JSONArray forecast = new JSONArray().put(next).put(current)
                .put(historyHour("2026-10-25T01:00:00Z"));
        List<JSONObject> hours = HourlyWeatherHistory.dayHours(forecast, history, today, zone, now);
        check(hours.size() == 4 && hours.get(0) == firstRepeatedHour && hours.get(1) == secondRepeatedHour,
                "Keep both fall-back hours in UTC order and prefer completed observations");
        check(hours.get(2) == current && hours.get(3) == next,
                "The incomplete history record must not replace the current forecast");
        List<JSONObject> previous = HourlyWeatherHistory.dayHours(null, history, today.minusDays(1), zone, now);
        check(previous.size() == 1 && previous.get(0) == yesterday,
                "Yesterday contains only records on the location’s previous date");
        int selected = HourlyWeatherHistory.currentHour(hours, now);
        check(selected == 2 && HourlyWeatherHistory.currentHour(hours,
                Instant.parse("2026-10-25T03:00:00Z")) == 3, "Select the interval containing now");
        check(HourlyWeatherHistory.currentHour(previous, now) == -1, "Missing current hour has no selection");

        firstRepeatedHour.put("temperature", new JSONObject().put("degrees", 5));
        secondRepeatedHour.put("temperature", new JSONObject().put("degrees", -2));
        next.put("temperature", new JSONObject().put("degrees", 11));
        JSONObject cloudy = new JSONObject().put("type", "CLOUDY");
        firstRepeatedHour.put("weatherCondition", cloudy);
        secondRepeatedHour.put("weatherCondition", cloudy).put("isDaytime", false);
        next.put("weatherCondition", new JSONObject().put("type", "CLEAR"));
        HourlyWeatherHistory.Summary summary = HourlyWeatherHistory.summarize(hours,
                temperature -> temperature == null ? null : temperature.optInt("degrees"));
        check(summary.high() == 11 && summary.low() == -2, "Summarize the observed temperature range");
        check(SceneSpec.glyphCondition(summary.representativeHour()).equals("CLOUDY"),
                "Use the most common recorded condition for the icon");
        check(HourlyWeatherHistory.summarize(previous, temperature -> null).representativeHour() == null,
                "Missing observations do not produce a weather icon");
        checkDailyLineHistory(context, summary);

        String[] keys = {"hour:0", "hour:1", "hour:2", "hour:3"};
        int[] changed = {-1};
        HourlyDayChartView chart = new HourlyDayChartView(context, new double[4], new double[4],
                keys, keys, keys, keys, keys, selected, index -> { }, index -> changed[0] = index);
        chart.syncPreview("day:0");
        check(changed[0] == selected && chart.getChildAt(selected).isSelected(),
                "Today opens with its current chart column selected");
        chart.syncPreview(keys[1]);
        check(changed[0] == 1 && chart.getChildAt(1).isSelected(),
                "An explicit hour overrides the default");
        chart.syncPreview("");
        check(changed[0] == selected && chart.getChildAt(selected).isSelected(),
                "Clearing the preview returns to the current hour");
    }

    private void checkDailyLineHistory(Context context, HourlyWeatherHistory.Summary summary)
            throws Exception {
        MainActivity host = new MainActivity();
        host.attachBaseContext(context);
        JSONArray forecast = new JSONArray();
        for (int i = 0; i < ForecastViewsActivity.ForecastChartView.MAX_FORECAST_DAYS; i++) {
            forecast.put(new JSONObject().put("displayDate",
                    new JSONObject().put("year", 2026).put("month", 10).put("day", 25 + i % 6)));
        }
        int[] selected = {-1};
        ForecastViewsActivity.ForecastChartView chart = host.new ForecastChartView(
                host, forecast, ZoneId.of("Europe/Brussels"), "Yesterday", "24 Oct", 1100, 520,
                index -> selected[0] = index);
        chart.updateYesterday(summary, "Partial");
        check(chart.getContentDescription().toString().contains("Yesterday, Partial")
                && chart.getContentDescription().toString().contains(UiTranslations.text(host, "High") + " 11"),
                "The historical range is described on the Line chart");
        chart.measure(View.MeasureSpec.makeMeasureSpec(1100, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(520, View.MeasureSpec.EXACTLY));
        chart.layout(0, 0, 1100, 520);
        Bitmap image = Bitmap.createBitmap(1100, 520, Bitmap.Config.ARGB_8888);
        try {
            chart.draw(new Canvas(image));
        } finally {
            image.recycle();
        }
        long now = SystemClock.uptimeMillis();
        event(chart, now, MotionEvent.ACTION_DOWN, 50, 50, 0);
        event(chart, now, MotionEvent.ACTION_UP, 50, 50, 20);
        check(selected[0] == WeatherOverviewRenderingActivity.YESTERDAY_INDEX, "First chart column opens yesterday");
        event(chart, now, MotionEvent.ACTION_DOWN, 150, 50, 30);
        event(chart, now, MotionEvent.ACTION_UP, 150, 50, 50);
        check(selected[0] == 0, "Second chart column still opens today");
        event(chart, now, MotionEvent.ACTION_DOWN, 1050, 50, 60);
        event(chart, now, MotionEvent.ACTION_UP, 1050, 50, 80);
        check(selected[0] == 9, "The tenth forecast day remains available");
    }

    private static JSONObject historyHour(String start) throws Exception {
        return new JSONObject().put("interval", new JSONObject().put("startTime", start)
                .put("endTime", Instant.parse(start).plusSeconds(3600).toString()));
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
                new String[]{"Now", "+3h", "+6h"}, "No forecast available", 1f);
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
            WidgetConfiguration defaults = new WidgetConfiguration("", 100, true, true);
            for (int[] size : new int[][]{{240, 118}, {320, 144}, {400, 220}}) {
                RemoteViews remote = PrecipitationWidgetProvider.buildForSize(
                        context, size[0], size[1], 0, defaults, null);
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
                check(title.getText().toString().startsWith(UiTranslations.text(context, "Next 6 hours")),
                        "widget title must describe the six-hour data window");
                Bitmap preview = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                widget.draw(new Canvas(preview));
                try (FileOutputStream out = new FileOutputStream(new File(context.getCacheDir(),
                        "widget-preview-" + size[0] + ".png"))) { preview.compress(Bitmap.CompressFormat.PNG, 100, out); }
                preview.recycle();
            }
            WidgetConfiguration compact = new WidgetConfiguration("", 120, false, false);
            View widget = PrecipitationWidgetProvider.buildForSize(context, 320, 144, 0, compact, null)
                    .apply(context, new LinearLayout(context));
            TextView title = widget.findViewById(R.id.precip_widget_title);
            check(title.getText().toString().equals(UiTranslations.text(context, "Next 6 hours")),
                    "rain amount can be hidden independently of the chart");
            check(widget.findViewById(R.id.precip_widget_chart).getVisibility() == View.VISIBLE,
                    "rain chart stays visible when the legend and details are hidden");
            check(title.getText() instanceof android.text.Spanned
                    && ((android.text.Spanned) title.getText()).getSpans(0, title.length(),
                            android.text.style.RelativeSizeSpan.class).length == 1,
                    "widget text scaling reaches the launcher view");
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
            ui.edit().remove(WeatherPreferences.PREF_SHOW_YESTERDAY).commit();
            check(!new WeatherPreferences(context).showYesterday(), "Yesterday is hidden by default");
            ui.edit().putInt(WeatherPreferences.PREF_TILE_TRANSPARENCY, 3).putString("temperature_unit", "F")
                    .putBoolean(WeatherPreferences.PREF_SHOW_YESTERDAY, true)
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
            check(!defaults.showYesterday(), "Reset hides Yesterday");
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
