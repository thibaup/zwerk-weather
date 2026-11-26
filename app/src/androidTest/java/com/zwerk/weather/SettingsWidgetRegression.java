package com.zwerk.weather;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.LocaleList;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
                    checkWave1And2Regressions(context);
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

        selected[0] = -1;
        chart.setCombinedMode(true, CombinedForecastData.PRECIP_STYLE_LINE);
        chart.measure(View.MeasureSpec.makeMeasureSpec(1100, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY));
        chart.layout(0, 0, 1100, 480);
        Bitmap combinedImg = Bitmap.createBitmap(1100, 480, Bitmap.Config.ARGB_8888);
        try {
            chart.draw(new Canvas(combinedImg));
            chart.setPrecipitationStyle(CombinedForecastData.PRECIP_STYLE_COLUMNS);
            chart.draw(new Canvas(combinedImg));
        } finally {
            combinedImg.recycle();
        }
        event(chart, now, MotionEvent.ACTION_DOWN, 150, 50, 90);
        event(chart, now, MotionEvent.ACTION_UP, 150, 50, 110);
        check(selected[0] == 0, "Combined Line chart column taps open day hourly details");

        double[] hourlyTemps = {12.0, 14.0, 17.0, 15.0};
        double[] hourlyRain = {0.0, 0.1, 2.4, 0.4};
        String[] hourlyTimes = {"12:00", "13:00", "14:00", "15:00"};
        String[] hourlyTempLabels = {"12°", "14°", "17°", "15°"};
        String[] hourlyRainLabels = {"", "0.1", "2.4", "0.4"};
        String[] hourlyKeys = {"h:0", "h:1", "h:2", "h:3"};
        HourlyDayChartView hourlyOverlay = new HourlyDayChartView(context, hourlyTemps, hourlyRain,
                hourlyTimes, hourlyTempLabels, hourlyRainLabels, hourlyKeys, hourlyKeys, 1,
                index -> { }, index -> { });
        hourlyOverlay.setCombinedOverlay(true, CombinedForecastData.PRECIP_STYLE_LINE, false);
        hourlyOverlay.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int overlayHeight = Math.max(200, hourlyOverlay.getMeasuredHeight());
        hourlyOverlay.layout(0, 0, 900, overlayHeight);
        Bitmap hourlyImg = Bitmap.createBitmap(900, overlayHeight, Bitmap.Config.ARGB_8888);
        try {
            hourlyOverlay.draw(new Canvas(hourlyImg));
            hourlyOverlay.setCombinedOverlay(true, CombinedForecastData.PRECIP_STYLE_LINE, true);
            hourlyOverlay.draw(new Canvas(hourlyImg));
            hourlyOverlay.setCombinedOverlay(true, CombinedForecastData.PRECIP_STYLE_COLUMNS);
            hourlyOverlay.draw(new Canvas(hourlyImg));
        } finally {
            hourlyImg.recycle();
        }

        ZoneId zone = ZoneId.of("Europe/Brussels");
        Instant windowStart = Instant.now().minusSeconds(120);
        Instant windowEnd = windowStart.plus(Duration.ofHours(6));
        ArrayList<MinuteSegment> segments = new ArrayList<>();
        double[] segMm = {0.0, 0.2, 0.9, 1.4, 0.6, 0.1, 0.0, 0.0};
        for (int i = 0; i < segMm.length; i++) {
            Instant segStart = windowStart.plus(Duration.ofMinutes(i * 15L));
            Instant segEnd = segStart.plus(Duration.ofMinutes(15));
            segments.add(new MinuteSegment(new JSONObject(), segStart, segEnd,
                    segMm[i] > 0d ? 75 : 5, segMm[i], "MILLIMETERS",
                    null, "", segMm[i] > 0d ? "RAIN" : "", segMm[i] > 0.8d ? "MODERATE" : "LIGHT"));
        }
        MinuteForecastViewsActivity.MinutePrecipitationGraphView minuteGraph =
                host.new MinutePrecipitationGraphView(host, segments, zone, windowStart, windowEnd);
        minuteGraph.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY));
        minuteGraph.layout(0, 0, 900, 420);
        Bitmap minuteImg = Bitmap.createBitmap(900, 420, Bitmap.Config.ARGB_8888);
        try {
            minuteGraph.draw(new Canvas(minuteImg));
            long startMs = windowStart.toEpochMilli();
            long[] tempEpochs = {
                    startMs - 1_800_000L,
                    startMs + 1_800_000L,
                    startMs + 5_400_000L,
                    startMs + 9_000_000L,
                    startMs + 12_600_000L,
                    startMs + 16_200_000L,
                    startMs + 19_800_000L,
                    startMs + 23_400_000L
            };
            double[] tempVals = {14.0, 15.0, 17.0, 18.0, 16.0, 15.0, 14.0, 13.0};
            String[] tempLabels = {"14°", "15°", "17°", "18°", "16°", "15°", "14°", "13°"};
            minuteGraph.setTemperatureSeries(tempEpochs, tempVals, tempLabels, "°C");
            Double midTemp = minuteGraph.temperatureAt(startMs + 3_600_000L);
            check(midTemp != null && Math.abs(midTemp - 16.0d) < 0.05d,
                    "Minute precipitation graph interpolates overlaid hourly temperature");
            minuteGraph.draw(new Canvas(minuteImg));
            minuteGraph.setCompactCombinedLayout(true);
            minuteGraph.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            check(minuteGraph.getMeasuredHeight() == hourlyOverlay.getMeasuredHeight(),
                    "Compact 6h minute graph matches 24h combined hourly chart height");
            minuteGraph.layout(0, 0, 900, minuteGraph.getMeasuredHeight());
            Bitmap compactMinuteImg = Bitmap.createBitmap(
                    900, Math.max(1, minuteGraph.getMeasuredHeight()), Bitmap.Config.ARGB_8888);
            try {
                minuteGraph.draw(new Canvas(compactMinuteImg));
            } finally {
                compactMinuteImg.recycle();
            }
        } finally {
            minuteImg.recycle();
        }
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
        Bitmap inchesBitmap = PrecipitationWidgetChart.render(data, 280, 80,
                new String[]{"Now", "+3h", "+6h"}, "No forecast available", 1f, true, 2f);
        try {
            check(inchesBitmap.getWidth() == 560 && inchesBitmap.getHeight() == 160,
                    "inches chart overload matches allocation");
            // 0.85 mm/h = 0.2125 mm/15m = 0.0084 in on the 0.02 in floor (~42% of plot height -> y 78..126).
            check(Color.alpha(inchesBitmap.getPixel(337, 105)) > 200,
                    "inches chart overload renders visible rain bars");
            check(Color.alpha(inchesBitmap.getPixel(96, 105)) == 0,
                    "inches chart overload keeps zero intervals empty");
        } finally {
            inchesBitmap.recycle();
        }
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

            // Combined multi-day forecast widget rendering across 4x2 sizes and both precipitation styles.
            long day0 = LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
            long dayMs = 24L * 3_600_000L;
            JSONArray daily = new JSONArray();
            int[] highs = {19, 21, 24, 22, 18, 16, 20};
            int[] lows = {11, 12, 13, 12, 9, 8, 10};
            double[] rainMm = {6.4, 0.3, 0.0, 1.8, 12.5, 2.0, 0.0};
            int[] probs = {84, 24, 0, 45, 92, 40, 10};
            String[] conds = {"RAIN", "PARTLY_CLOUDY", "CLEAR", "PARTLY_CLOUDY", "THUNDERSTORM", "CLOUDY", "CLEAR"};
            for (int i = 0; i < 7; i++) {
                daily.put(new JSONObject()
                        .put("start", day0 + i * dayMs)
                        .put("end", day0 + (i + 1) * dayMs)
                        .put("date", LocalDate.now(ZoneId.systemDefault()).plusDays(i).toString())
                        .put("high", highs[i]).put("low", lows[i])
                        .put("precipitationMm", rainMm[i]).put("precipitation", probs[i])
                        .put("conditionType", conds[i]));
            }
            snapshot.edit().putString(WeatherWidgetProvider.KEY_DAILY_JSON, daily.toString())
                    .putInt(WeatherWidgetProvider.KEY_TEMPERATURE, 19)
                    .putString(WeatherWidgetProvider.KEY_UNIT, "°C")
                    .putString(WeatherWidgetProvider.KEY_ZONE, ZoneId.systemDefault().getId())
                    .putBoolean(WeatherWidgetProvider.KEY_OPEN_METEO, true).commit();
            WidgetConfiguration combinedCols = new WidgetConfiguration("", 100, true, true,
                    5, CombinedForecastData.PRECIP_STYLE_COLUMNS,
                    WidgetConfiguration.BG_DARK, 10, 1, WidgetBackground.DEFAULT_COLOUR);
            WidgetConfiguration combinedLine = new WidgetConfiguration("", 95, true, true,
                    7, CombinedForecastData.PRECIP_STYLE_LINE,
                    WidgetConfiguration.BG_BLUE, 15, 1, WidgetBackground.DEFAULT_COLOUR);
            for (WidgetConfiguration cfg : new WidgetConfiguration[]{combinedCols, combinedLine}) {
                for (int[] size : new int[][]{{240, 118}, {280, 144}, {360, 200}}) {
                    RemoteViews remote = CombinedForecastWidgetProvider.buildForSize(
                            context, size[0], size[1], 0, cfg, null);
                    View forecastWidget = remote.apply(context, new LinearLayout(context));
                    float density = context.getResources().getDisplayMetrics().density;
                    int width = Math.round(size[0] * density), height = Math.round(size[1] * density);
                    forecastWidget.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                    forecastWidget.layout(0, 0, width, height);
                    ImageView chartView = forecastWidget.findViewById(R.id.forecast_widget_chart);
                    check(chartView.getHeight() >= Math.round(70 * density),
                            "combined widget chart keeps readable height at 4x2 and above");
                }
            }

            // Dry header check (no bare "mm"/"in"/"%") and inches rendering for CombinedForecastWidgetProvider.
            SharedPreferences uiPrefs = context.getSharedPreferences(WeatherPreferences.PREFS_NAME, Context.MODE_PRIVATE);
            Map<String, ?> originalUi = new HashMap<>(uiPrefs.getAll());
            try {
                JSONArray dryDaily = new JSONArray();
                JSONArray noPrecipDaily = new JSONArray();
                for (int i = 0; i < 5; i++) {
                    dryDaily.put(new JSONObject()
                            .put("start", day0 + i * dayMs)
                            .put("end", day0 + (i + 1) * dayMs)
                            .put("date", LocalDate.now(ZoneId.systemDefault()).plusDays(i).toString())
                            .put("high", 20 + i).put("low", 10 + i)
                            .put("precipitationMm", 0.0).put("precipitation", 0)
                            .put("conditionType", "CLEAR"));
                    noPrecipDaily.put(new JSONObject()
                            .put("start", day0 + i * dayMs)
                            .put("end", day0 + (i + 1) * dayMs)
                            .put("date", LocalDate.now(ZoneId.systemDefault()).plusDays(i).toString())
                            .put("high", 20 + i).put("low", 10 + i)
                            .put("conditionType", "CLEAR"));
                }
                snapshot.edit().putString(WeatherWidgetProvider.KEY_DAILY_JSON, dryDaily.toString()).commit();
                uiPrefs.edit().putString("temperature_unit", WeatherPreferences.TEMP_CELSIUS)
                        .putString(WeatherPreferences.PREF_PRECIPITATION_UNIT, WeatherPreferences.PRECIP_MM).commit();
                View dryMmWidget = CombinedForecastWidgetProvider.buildForSize(
                        context, 280, 144, 0, combinedLine, null).apply(context, new LinearLayout(context));
                TextView dryMmMeta = dryMmWidget.findViewById(R.id.forecast_widget_meta);
                check(dryMmMeta.getVisibility() == View.VISIBLE && "0 mm".equals(dryMmMeta.getText().toString()),
                        "dry metric combined widget shows '0 mm' rather than bare 'mm'");

                uiPrefs.edit().putString(WeatherPreferences.PREF_PRECIPITATION_UNIT, WeatherPreferences.PRECIP_IN).commit();
                View dryInWidget = CombinedForecastWidgetProvider.buildForSize(
                        context, 280, 144, 0, combinedLine, null).apply(context, new LinearLayout(context));
                TextView dryInMeta = dryInWidget.findViewById(R.id.forecast_widget_meta);
                check(dryInMeta.getVisibility() == View.VISIBLE && "0 in".equals(dryInMeta.getText().toString()),
                        "dry imperial combined widget shows '0 in' rather than bare 'in'");

                snapshot.edit().putString(WeatherWidgetProvider.KEY_DAILY_JSON, noPrecipDaily.toString())
                        .putString(WeatherWidgetProvider.KEY_HOURLY_JSON, "[]").commit();
                View noPrecipWidget = CombinedForecastWidgetProvider.buildForSize(
                        context, 280, 144, 0, combinedLine, null).apply(context, new LinearLayout(context));
                TextView noPrecipMeta = noPrecipWidget.findViewById(R.id.forecast_widget_meta);
                check(noPrecipMeta.getVisibility() == View.GONE,
                        "combined widget hides meta header when precipitation data is absent");

                CombinedForecastData inData = CombinedForecastData.fromWidgetTimeline(
                        new WeatherWidgetTimeline(snapshot, now), now, 5, true);
                Bitmap inChart = CombinedForecastWidgetChart.render(inData, 260, 110,
                        new String[]{"Today", "Thu", "Fri", "Sat", "Sun"},
                        new String[]{"T", "T", "F", "S", "S"},
                        CombinedForecastData.PRECIP_STYLE_LINE, true, "Empty", 1f, 2f);
                try {
                    check(inChart.getWidth() > 0 && inChart.getHeight() > 0,
                            "CombinedForecastWidgetChart renders cleanly in inches");
                } finally {
                    inChart.recycle();
                }
            } finally {
                restore(uiPrefs, originalUi);
            }
        } finally { restore(snapshot, original); }
    }

    private void checkWave1And2Regressions(Context context) throws Exception {
        // 1. ApiKeyStore round-trip encryption + legacy plaintext migration using a temporary test file.
        final String tempKeyFile = "_test_regression_api_key";
        Context appContext = context.getApplicationContext();
        if (appContext == null) appContext = context;
        File keyFile = new File(appContext.getFilesDir(), tempKeyFile);
        try {
            ApiKeyStore.delete(context, tempKeyFile);
            check("".equals(ApiKeyStore.read(context, tempKeyFile)), "missing API key reads as empty");
            ApiKeyStore.write(context, tempKeyFile, "  zwerk-test-secret-01  ");
            check("zwerk-test-secret-01".equals(ApiKeyStore.read(context, tempKeyFile)),
                    "ApiKeyStore round-trip returns trimmed secret");
            String storedRaw = new String(Files.readAllBytes(keyFile.toPath()), StandardCharsets.UTF_8).trim();
            if (storedRaw.startsWith("ENC1:")) {
                check(!storedRaw.contains("zwerk-test-secret-01"),
                        "encrypted API key file does not store plaintext");
            }
            ApiKeyStore.delete(context, tempKeyFile);
            check(!keyFile.exists(), "ApiKeyStore.delete removes temp key file");

            Files.write(keyFile.toPath(), "legacy-plain-secret-02\n".getBytes(StandardCharsets.UTF_8));
            check("legacy-plain-secret-02".equals(ApiKeyStore.read(context, tempKeyFile)),
                    "ApiKeyStore reads legacy plaintext key");
            check("legacy-plain-secret-02".equals(ApiKeyStore.read(context, tempKeyFile)),
                    "ApiKeyStore still reads key after transparent migration");
        } finally {
            ApiKeyStore.delete(context, tempKeyFile);
        }

        // 2. UpdateChecker URL validation (HTTPS github.com / *.githubusercontent.com only).
        Method isAllowed = UpdateChecker.class.getDeclaredMethod("isAllowedUpdateUrl", String.class);
        isAllowed.setAccessible(true);
        check(Boolean.TRUE.equals(isAllowed.invoke(null,
                "https://github.com/thiba/zwerk-weather/releases/download/v1.5.2/app.apk")),
                "UpdateChecker accepts HTTPS github.com release URL");
        check(Boolean.TRUE.equals(isAllowed.invoke(null,
                "https://objects.githubusercontent.com/github-production-release-asset/12345")),
                "UpdateChecker accepts HTTPS objects.githubusercontent.com asset URL");
        check(Boolean.FALSE.equals(isAllowed.invoke(null,
                "http://github.com/thiba/zwerk-weather/releases/download/v1.5.2/app.apk")),
                "UpdateChecker rejects plaintext HTTP URL");
        check(Boolean.FALSE.equals(isAllowed.invoke(null, "https://evil.example/app.apk")),
                "UpdateChecker rejects untrusted HTTPS host");
        check(Boolean.FALSE.equals(isAllowed.invoke(null, "https://github.com.evil.example/app.apk")),
                "UpdateChecker rejects spoofed subdomain host");
        check(Boolean.FALSE.equals(isAllowed.invoke(null, "")),
                "UpdateChecker rejects empty URL");
        check(Boolean.FALSE.equals(isAllowed.invoke(null, (Object) null)),
                "UpdateChecker rejects null URL");

        // 3. UiTranslations context-locale lookup & MAP_THEME_LIGHT_KEY homonym fix in French.
        Configuration frConfig = new Configuration(context.getResources().getConfiguration());
        frConfig.setLocales(new LocaleList(Locale.FRENCH));
        Context frContext = context.createConfigurationContext(frConfig);
        check("Clair".equals(UiTranslations.text(frContext, UiTranslations.MAP_THEME_LIGHT_KEY)),
                "French MAP_THEME_LIGHT_KEY translates to 'Clair'");
        check("Faible".equals(UiTranslations.text(frContext, "Light")),
                "French precipitation 'Light' translates to 'Faible' without homonym collision");
        Configuration enConfig = new Configuration(context.getResources().getConfiguration());
        enConfig.setLocales(new LocaleList(Locale.ENGLISH));
        Context enContext = context.createConfigurationContext(enConfig);
        check("Light".equals(UiTranslations.text(enContext, UiTranslations.MAP_THEME_LIGHT_KEY)),
                "English MAP_THEME_LIGHT_KEY resolves to 'Light'");

        // 4. RadarPageView / RadarMapView opacity, speed, fullscreen state, and trimMemory().
        SharedPreferences uiPrefs = context.getSharedPreferences(WeatherPreferences.PREFS_NAME, Context.MODE_PRIVATE);
        Map<String, ?> originalUi = new HashMap<>(uiPrefs.getAll());
        RadarPageView radar = null;
        RadarDataClient mapClient = null;
        try {
            uiPrefs.edit()
                    .putInt(WeatherPreferences.PREF_RADAR_OPACITY, 55)
                    .putFloat(WeatherPreferences.PREF_RADAR_SPEED, 2.0f)
                    .commit();
            WeatherPreferences radarPrefs = new WeatherPreferences(context);
            check(radarPrefs.radarOpacityPercent() == 55, "radar opacity preference persists");
            check(radarPrefs.radarPlaybackSpeed() == 2.0f, "radar 2x speed preference persists");
            uiPrefs.edit().putInt(WeatherPreferences.PREF_RADAR_OPACITY, 5).commit();
            check(radarPrefs.radarOpacityPercent() == 20, "radar opacity clamps to minimum 20%");
            uiPrefs.edit().putInt(WeatherPreferences.PREF_RADAR_OPACITY, 150).commit();
            check(radarPrefs.radarOpacityPercent() == 100, "radar opacity clamps to maximum 100%");
            uiPrefs.edit().putInt(WeatherPreferences.PREF_RADAR_OPACITY, 72)
                    .putFloat(WeatherPreferences.PREF_RADAR_SPEED, 0.5f).commit();
            check(radarPrefs.radarPlaybackSpeed() == 0.5f, "radar 0.5x speed preference persists");

            radar = new RadarPageView(context);
            check(!radar.isFullscreen(), "RadarPageView starts non-fullscreen");
            radar.setFullscreen(true);
            check(radar.isFullscreen(), "RadarPageView enters fullscreen state");
            radar.setFullscreen(false);
            check(!radar.isFullscreen(), "RadarPageView exits fullscreen state");
            radar.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
            radar.layout(0, 0, 900, 1200);
            Bitmap radarBmp = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888);
            try {
                radar.draw(new Canvas(radarBmp));
            } finally {
                radarBmp.recycle();
            }
            radar.applyTileTransparency();
            radar.refreshTimeFormat();
            radar.trimMemory();

            mapClient = new RadarDataClient(context);
            RadarMapView mapView = new RadarMapView(context, mapClient, () -> { });
            mapView.setRadarOpacity(60);
            mapView.setRadarOpacity(5);
            mapView.refreshScaleUnit();
            mapView.trimRetainedLayers();
        } finally {
            if (radar != null) radar.dispose();
            if (mapClient != null) mapClient.dispose();
            restore(uiPrefs, originalUi);
        }
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
            ui.edit().remove(WeatherPreferences.PREF_SHOW_YESTERDAY)
                    .remove(WeatherPreferences.PREF_COMBINED_FORECAST)
                    .remove(WeatherPreferences.PREF_COMBINED_PRECIP_STYLE)
                    .remove(WeatherPreferences.PREF_PRECIPITATION_UNIT)
                    .remove(WeatherPreferences.PREF_RADAR_OPACITY)
                    .remove(WeatherPreferences.PREF_RADAR_SPEED).commit();
            WeatherPreferences initialPrefs = new WeatherPreferences(context);
            check(!initialPrefs.showYesterday(), "Yesterday is hidden by default");
            check(!initialPrefs.combinedForecastMode(), "Combined forecast mode is off by default");
            check(CombinedForecastData.PRECIP_STYLE_LINE.equals(initialPrefs.combinedPrecipitationStyle()),
                    "Combined precipitation defaults to line style");
            check(WeatherPreferences.PRECIP_MM.equals(initialPrefs.precipitationUnit(false))
                    && WeatherPreferences.PRECIP_IN.equals(initialPrefs.precipitationUnit(true)),
                    "Precipitation unit follows temperature unit when unset");
            ui.edit().putString("temperature_unit", WeatherPreferences.TEMP_CELSIUS)
                    .putString(WeatherPreferences.PREF_PRECIPITATION_UNIT, WeatherPreferences.PRECIP_IN).commit();
            check(new WeatherPreferences(context).isPrecipitationInches(),
                    "Inches precipitation works independently of Celsius");
            ui.edit().putString("temperature_unit", WeatherPreferences.TEMP_FAHRENHEIT)
                    .putString(WeatherPreferences.PREF_PRECIPITATION_UNIT, WeatherPreferences.PRECIP_MM).commit();
            check(!new WeatherPreferences(context).isPrecipitationInches(),
                    "Millimeters precipitation works independently of Fahrenheit");
            ui.edit().putInt(WeatherPreferences.PREF_TILE_TRANSPARENCY, 3).putString("temperature_unit", "F")
                    .putBoolean(WeatherPreferences.PREF_SHOW_YESTERDAY, true)
                    .putBoolean(WeatherPreferences.PREF_COMBINED_FORECAST, true)
                    .putString(WeatherPreferences.PREF_COMBINED_PRECIP_STYLE,
                            CombinedForecastData.PRECIP_STYLE_COLUMNS)
                    .putString(WeatherPreferences.PREF_PRECIPITATION_UNIT, WeatherPreferences.PRECIP_IN)
                    .putInt(WeatherPreferences.PREF_RADAR_OPACITY, 45)
                    .putFloat(WeatherPreferences.PREF_RADAR_SPEED, 2.0f)
                    .putString(WeatherPreferences.PREF_RADAR_MAP_THEME, WeatherPreferences.MAP_DARK)
                    .putBoolean("weather_animations", false).putBoolean("radar_page_enabled", false)
                    .putInt("widget_transparency", 88).putString("widget_colour", "custom")
                    .putBoolean("weather_detail_uv_index", false).putBoolean("weather_detail_dew_point", true)
                    .putString("_test_internal_state", "keep").commit();
            check(new WeatherPreferences(context).combinedForecastMode(),
                    "Combined forecast mode can be enabled");
            ApiRequestBudgetManager.applyProfile(context, ApiRequestBudgetManager.PROFILE_CONSERVATIVE);
            OpenMeteoRequestBudgetManager.setLimits(context, 20, 300);
            WeatherSettingsDefaults.restore(context);
            WeatherPreferences defaults = new WeatherPreferences(context);
            check(defaults.tileTransparency() == 70 && "C".equals(defaults.temperatureUnit()), "reset display and units");
            check(defaults.animationsAllowed() && ui.getBoolean("radar_page_enabled", true), "reset animation and pages");
            check(!defaults.showYesterday(), "Reset hides Yesterday");
            check(!defaults.combinedForecastMode(), "Reset disables combined forecast mode");
            check(CombinedForecastData.PRECIP_STYLE_LINE.equals(defaults.combinedPrecipitationStyle()),
                    "Reset restores default combined line precipitation style");
            check(!ui.contains(WeatherPreferences.PREF_PRECIPITATION_UNIT) && !defaults.isPrecipitationInches(),
                    "Reset clears independent precipitation unit preference");
            check(!ui.contains(WeatherPreferences.PREF_RADAR_OPACITY)
                    && defaults.radarOpacityPercent() == WeatherPreferences.DEFAULT_RADAR_OPACITY,
                    "Reset restores default radar opacity");
            check(!ui.contains(WeatherPreferences.PREF_RADAR_SPEED)
                    && defaults.radarPlaybackSpeed() == WeatherPreferences.DEFAULT_RADAR_SPEED,
                    "Reset restores default radar playback speed");
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
