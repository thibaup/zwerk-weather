package com.zwerk.weather;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.system.Os;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.Executors;


abstract class WeatherOverviewRenderingActivity extends MinuteForecastRenderingActivity {
    void render(JSONObject current, JSONObject hourly, JSONObject daily, String responseUnit) {
        activeTemperatureUnit = normalizeTemperatureUnit(responseUnit);
        lastCurrentWeather = current;
        lastHourlyWeather = hourly;
        lastDailyWeather = daily;
        progress.setVisibility(View.GONE);
        status.setText(dataAgeLabel(current));

        displayedDaytime = safeBoolean(current, "isDaytime", true);
        SceneSpec currentScene = SceneSpec.fromWeather(current, displayedDaytime);
        forecastPreview.setCurrentScene(currentScene);
        if (skyLayout != null) skyLayout.setScene(currentScene);
        if (headerGlass != null) headerGlass.setScene(currentScene, animationsAllowed());
        applyScenePalette(currentScene.paletteScene());
        displayedScene = settingsSceneKey(current, displayedDaytime);
        persistSettingsSceneSnapshot();

        ZoneId zone = responseZone(current, hourly, daily);
        JSONArray days = daily == null ? null : daily.optJSONArray("forecastDays");
        JSONObject today = firstObject(days);
        Integer snapshotTemp = degreesOrNull(current == null ? null : current.optJSONObject("temperature"));
        CityManagerActivity.updateSelectedLocationSnapshot(
                this,
                snapshotTemp == null ? "" : snapshotTemp + temperatureUnitSymbol(),
                description(current));
        persistWidgetSnapshot(current, today, hourly, zone);

        renderCurrentMode();
        if (headerGlass != null) headerGlass.requestBlurRefresh();
    }


    void addHero(JSONObject current, JSONObject today) {
        String condition = description(current);
        Integer temp = degreesOrNull(current == null ? null : current.optJSONObject("temperature"));
        Integer min = degreesOrNull(today == null ? null : today.optJSONObject("minTemperature"));
        Integer max = degreesOrNull(today == null ? null : today.optJSONObject("maxTemperature"));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(4), dp(18), dp(4), dp(62));

        TextView temperature = text(temp == null ? "—°" : temp + "°", 100, false, WHITE);
        temperature.setContentDescription(temp == null
                ? "Temperature unavailable"
                : temp + " degrees " + temperatureUnitWord());
        temperature.setGravity(Gravity.CENTER);
        temperature.setIncludeFontPadding(false);
        temperature.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        temperature.setLetterSpacing(-0.025f);
        hero.addView(temperature);

        String range = (min == null || max == null) ? "" : "  " + min + "° / " + max + "°";
        String summary = condition + range;
        TextView subtitle = text(summary, 18, false, WHITE);
        StringBuilder heroSummaryDescription = new StringBuilder(condition);
        if (min != null) {
            heroSummaryDescription.append(", low ").append(min)
                    .append(" degrees ").append(temperatureUnitWord());
        }
        if (max != null) {
            heroSummaryDescription.append(", high ").append(max)
                    .append(" degrees ").append(temperatureUnitWord());
        }
        subtitle.setContentDescription(heroSummaryDescription.toString());
        subtitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setMaxLines(3);
        subtitle.setLineSpacing(0, 1.06f);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(-1, -2);
        subtitleLp.topMargin = dp(8);
        hero.addView(subtitle, subtitleLp);

        content.addView(hero);
    }

    static String stringValue(JSONObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.isNull(key)) return "";
        String value = object.optString(key, "").trim();
        return "null".equalsIgnoreCase(value) ? "" : value;
    }

    static String firstNonEmpty(String first, String second) {
        if (first != null && !first.trim().isEmpty()) return first.trim();
        return second == null ? "" : second.trim();
    }

    void addHourlyCard(JSONObject hourly, ZoneId zone) {
        JSONArray hours = hourly == null ? null : hourly.optJSONArray("forecastHours");
        String diagnostic = hourlyDiagnostic(hourly);

        LinearLayout cardBody = new LinearLayout(this);
        cardBody.setOrientation(LinearLayout.VERTICAL);

        GestureHorizontalScrollView scroller = new GestureHorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        scroller.setFillViewport(true);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(5), dp(12), dp(5), dp(12));

        if (hours != null && hours.length() > 0) {
            for (int i = 0; i < Math.min(TOP_HOURLY_STRIP_HOURS, hours.length()); i++) {
                JSONObject hour = hours.optJSONObject(i);
                if (hour == null) continue;

                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(dp(5), dp(4), dp(5), dp(4));

                TextView when = text(i == 0 ? "Now" : hourLabel(hour, zone), 13, false, SOFT_WHITE);
                when.setGravity(Gravity.CENTER);
                cell.addView(when, new LinearLayout.LayoutParams(-1, dp(24)));

                WeatherGlyphView icon = new WeatherGlyphView(
                        this, description(hour), safeBoolean(hour, "isDaytime", true));
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(42), dp(42));
                iconLp.topMargin = dp(5);
                cell.addView(icon, iconLp);

                Integer temperature = degreesOrNull(hour.optJSONObject("temperature"));
                TextView t = text(temperature == null ? "—°" : temperature + "°", 19, true, WHITE);
                t.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-1, -2);
                tLp.topMargin = dp(4);
                cell.addView(t, tLp);

                int p = probability(hour);
                TextView precip = text(p > 0 ? p + "%" : " ", 11, false, ACCENT_BLUE);
                precip.setGravity(Gravity.CENTER);
                cell.addView(precip);
                StringBuilder hourlyDescription = new StringBuilder(
                        i == 0 ? "Now" : hourLabel(hour, zone));
                if (temperature != null) {
                    hourlyDescription.append(", ").append(temperature)
                            .append(" degrees ").append(temperatureUnitWord());
                }
                if (p > 0) hourlyDescription.append(", ").append(p).append(" percent precipitation");
                configureHourPreviewCell(
                        cell,
                        hour,
                        zone,
                        i == 0,
                        hourlyDescription.toString());

                row.addView(cell, new LinearLayout.LayoutParams(dp(74), -2));
            }
        } else {
            LinearLayout fallback = new LinearLayout(this);
            fallback.setOrientation(LinearLayout.HORIZONTAL);
            fallback.setGravity(Gravity.CENTER_VERTICAL);
            fallback.setPadding(dp(12), dp(8), dp(8), dp(8));

            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView empty = text("Hourly unavailable", 15, false, SOFT_WHITE);
            labels.addView(empty);
            String reason = diagnostic.isEmpty()
                    ? "No forecastHours array returned"
                    : diagnostic;
            TextView detail = text(reason, 10, false, FAINT_WHITE);
            detail.setMaxLines(3);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            detail.setPadding(0, dp(2), dp(8), 0);
            labels.addView(detail);
            fallback.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

            Button retry = button("Retry");
            retry.setContentDescription("Retry hourly forecast");
            retry.setOnClickListener(v -> retryHourlyPageOne());
            fallback.addView(retry, new LinearLayout.LayoutParams(dp(78), dp(44)));

            int fallbackWidth = Math.max(
                    dp(292), getResources().getDisplayMetrics().widthPixels - dp(58));
            row.addView(fallback, new LinearLayout.LayoutParams(fallbackWidth, -2));
        }

        scroller.addView(row);
        cardBody.addView(scroller, new LinearLayout.LayoutParams(-1, -2));

        if (hours != null && hours.length() > 0
                && hourly != null
                && hourly.optBoolean(HOURLY_LOAD_ERROR, false)
                && !diagnostic.isEmpty()) {
            TextView partial = text("Hourly data is partial · " + diagnostic, 10, false, FAINT_WHITE);
            partial.setPadding(dp(14), 0, dp(14), dp(10));
            partial.setMaxLines(3);
            partial.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cardBody.addView(partial);
        }

        LinearLayout.LayoutParams cardLp = defaultCardParams();
        cardLp.topMargin = dp(8);
        View card = card(cardBody, dp(24), cardColor);
        card.setLayoutParams(cardLp);
        content.addView(card);
    }

    static String hourlyDiagnostic(JSONObject hourly) {
        if (hourly == null) return "";
        return boundedDiagnostic(hourly.optString(HOURLY_DIAGNOSTIC, ""));
    }

    interface DaySelectionListener {
        void onDaySelected(int dayIndex);
    }

    void addMultiDayCard(JSONArray days, JSONObject hourly, ZoneId zone) {
        JSONArray hours = hourly == null ? null : hourly.optJSONArray("forecastHours");

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(14), dp(14), dp(14));

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Multi-day forecast", 15, false, SOFT_WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout segmented = new LinearLayout(this);
        segmented.setOrientation(LinearLayout.HORIZONTAL);
        segmented.setPadding(dp(2), dp(2), dp(2), dp(2));
        segmented.setBackground(roundedBg(Color.argb(30, 255, 255, 255), dp(22)));
        segmented.setContentDescription("Forecast display mode");

        TextView lineButton = dailyModeButton("Line", "Line forecast");
        segmented.addView(lineButton, new LinearLayout.LayoutParams(dp(72), dp(48)));

        TextView listButton = dailyModeButton("List", "List forecast");
        segmented.addView(listButton, new LinearLayout.LayoutParams(dp(72), dp(48)));
        heading.addView(segmented);
        body.addView(heading);

        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(22, 255, 255, 255));
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(-1, 1);
        dividerLp.topMargin = dp(8);
        body.addView(divider, dividerLp);

        int dayCount = days == null ? 0 : Math.min(10, days.length());
        int viewportWidth = Math.max(dp(280), getResources().getDisplayMetrics().widthPixels - dp(64));
        int chartWidth = Math.max(viewportWidth, dp(86) * Math.max(1, dayCount));
        int chartHeight = dp(330);

        LinearLayout lineDetailHost = new LinearLayout(this);
        lineDetailHost.setOrientation(LinearLayout.VERTICAL);
        lineDetailHost.setVisibility(View.GONE);

        DayDetailCoordinator details = new DayDetailCoordinator(
                days, hours, zone, hourlyDiagnostic(hourly), lineDetailHost);
        ForecastChartView chart = new ForecastChartView(
                this, days, zone, chartWidth, chartHeight, details::toggleDay);
        chart.setLayoutParams(new FrameLayout.LayoutParams(chartWidth, chartHeight));
        forecastPreview.registerChart(chart);

        GestureHorizontalScrollView chartScroller = new GestureHorizontalScrollView(this);
        chartScroller.setHorizontalScrollBarEnabled(false);
        chartScroller.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        chartScroller.setFillViewport(true);
        chartScroller.setClipToPadding(false);
        chartScroller.setMinimumHeight(chartHeight);
        chartScroller.setContentDescription("Scrollable 10-day line forecast in "
                + temperatureUnitWord() + ". Select a day for hourly details and scene preview.");
        chartScroller.addView(chart, new FrameLayout.LayoutParams(chartWidth, chartHeight));

        GestureVerticalScrollView list = buildTenDayList(days, zone, details);
        FrameLayout modeHost = new FrameLayout(this);
        FrameLayout.LayoutParams chartHostLp = new FrameLayout.LayoutParams(-1, chartHeight);
        chartHostLp.topMargin = dp(4);
        modeHost.addView(chartScroller, chartHostLp);
        FrameLayout.LayoutParams listHostLp = new FrameLayout.LayoutParams(-1, chartHeight);
        listHostLp.topMargin = dp(4);
        modeHost.addView(list, listHostLp);
        body.addView(modeHost, new LinearLayout.LayoutParams(-1, chartHeight + dp(4)));

        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = dp(8);
        body.addView(lineDetailHost, detailLp);

        String savedMode = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getString(PREF_DAILY_MODE, "line");
        boolean listMode = "list".equals(savedMode);
        applyDailyMode(lineButton, listButton, chartScroller, list, listMode);
        details.setListMode(listMode);

        lineButton.setOnClickListener(v -> switchDailyMode(
                lineButton, listButton, chartScroller, list, details, false));
        listButton.setOnClickListener(v -> switchDailyMode(
                lineButton, listButton, chartScroller, list, details, true));

        LinearLayout.LayoutParams cardLp = defaultCardParams();
        cardLp.topMargin = dp(12);
        View card = card(body, dp(24), cardColor);
        card.setLayoutParams(cardLp);
        content.addView(card);
    }

    TextView dailyModeButton(String label, String accessibilityLabel) {
        TextView button = text(label, 12, true, WHITE);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setMinWidth(dp(72));
        button.setMinHeight(dp(48));
        button.setContentDescription(accessibilityLabel);
        return button;
    }

    void switchDailyMode(
            TextView lineButton,
            TextView listButton,
            GestureHorizontalScrollView chartScroller,
            View list,
            DayDetailCoordinator details,
            boolean listMode) {
        int pageScrollY = mainScroll == null ? 0 : mainScroll.getScrollY();
        int chartScrollX = chartScroller.getScrollX();
        getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_DAILY_MODE, listMode ? "list" : "line")
                .apply();
        applyDailyMode(lineButton, listButton, chartScroller, list, listMode);
        details.setListMode(listMode);
        chartScroller.post(() -> chartScroller.scrollTo(chartScrollX, 0));
        if (mainScroll != null) {
            mainScroll.post(() -> mainScroll.scrollTo(0, pageScrollY));
        }
    }

    void applyDailyMode(
            TextView lineButton,
            TextView listButton,
            View chart,
            View list,
            boolean listMode) {
        // INVISIBLE (not GONE) keeps the custom chart measured while List mode is active.
        // That preserves the API-36 line-chart measurement fix and its horizontal scroll position.
        chart.setVisibility(listMode ? View.INVISIBLE : View.VISIBLE);
        list.setVisibility(listMode ? View.VISIBLE : View.GONE);
        updateDailyModeButton(lineButton, !listMode, "Line forecast");
        updateDailyModeButton(listButton, listMode, "List forecast");
    }

    void updateDailyModeButton(TextView button, boolean selected, String label) {
        button.setSelected(selected);
        button.setAlpha(selected ? 1f : 0.76f);
        button.setTextColor(selected ? WHITE : Color.argb(210, 255, 255, 255));
        button.setBackground(segmentedButtonBackground(selected));
        button.setContentDescription(label + (selected ? ", selected" : ", not selected"));
        if (Build.VERSION.SDK_INT >= 30) {
            button.setStateDescription(selected ? "Selected" : "Not selected");
        }
    }

    StateListDrawable segmentedButtonBackground(boolean selected) {
        StateListDrawable states = new StateListDrawable();
        int pressed = selected
                ? Color.argb(92, 255, 255, 255)
                : Color.argb(34, 255, 255, 255);
        int normal = selected
                ? Color.argb(66, 255, 255, 255)
                : Color.TRANSPARENT;
        states.addState(new int[]{android.R.attr.state_pressed}, roundedBg(pressed, dp(19)));
        states.addState(new int[]{android.R.attr.state_focused}, roundedBg(pressed, dp(19)));
        states.addState(new int[]{}, roundedBg(normal, dp(19)));
        return states;
    }

    GestureVerticalScrollView buildTenDayList(
            JSONArray days,
            ZoneId zone,
            DayDetailCoordinator details) {
        GestureVerticalScrollView scroller = new GestureVerticalScrollView(this);
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(true);
        scroller.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        scroller.setClipToPadding(false);
        scroller.setPadding(0, dp(3), 0, dp(3));
        scroller.setContentDescription("Scrollable 10-day forecast list");

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(list, new ScrollView.LayoutParams(-1, -2));

        if (days == null || days.length() == 0) {
            TextView empty = text("Daily forecast unavailable", 14, false, SOFT_WHITE);
            empty.setPadding(dp(4), dp(18), dp(4), dp(18));
            list.addView(empty);
            return scroller;
        }

        int count = Math.min(10, days.length());
        for (int i = 0; i < count; i++) {
            JSONObject day = days.optJSONObject(i);
            if (day == null) continue;
            final int dayIndex = i;

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(64));
            row.setPadding(dp(6), dp(8), dp(4), dp(8));
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackground(previewTargetBackground(dp(16)));

            String dayText = i == 0 ? "Today" : dayLabel(day, zone);
            TextView label = text(dayText, 14, i == 0, WHITE);
            TextView date = text(dayDateLabel(day, zone), 10, false, FAINT_WHITE);
            LinearLayout dayLabels = new LinearLayout(this);
            dayLabels.setOrientation(LinearLayout.VERTICAL);
            dayLabels.addView(label);
            dayLabels.addView(date);
            row.addView(dayLabels, new LinearLayout.LayoutParams(0, -2, 1));

            JSONObject daytime = day.optJSONObject("daytimeForecast");
            WeatherGlyphView glyph = new WeatherGlyphView(this, description(daytime), true);
            row.addView(glyph, new LinearLayout.LayoutParams(dp(34), dp(34)));

            int p = dayProbability(day);
            TextView rain = text(p >= 0 ? p + "%" : "—", 12, false, ACCENT_BLUE);
            rain.setGravity(Gravity.END);
            row.addView(rain, new LinearLayout.LayoutParams(dp(48), -2));

            Integer hi = degreesOrNull(day.optJSONObject("maxTemperature"));
            Integer lo = degreesOrNull(day.optJSONObject("minTemperature"));
            String temperatures = (hi == null ? "—" : hi) + "°  " + (lo == null ? "—" : lo) + "°";
            TextView temps = text(temperatures, 14, true, WHITE);
            temps.setGravity(Gravity.END);
            row.addView(temps, new LinearLayout.LayoutParams(dp(88), -2));

            TextView disclosure = text("›", 24, false, SOFT_WHITE);
            disclosure.setGravity(Gravity.CENTER);
            row.addView(disclosure, new LinearLayout.LayoutParams(dp(24), dp(40)));

            StringBuilder rowDescription = new StringBuilder(dayText);
            String condition = sceneForForecastDay(day, i).condition;
            if (!"Unknown".equals(condition)) rowDescription.append(", ").append(condition);
            if (hi != null) {
                rowDescription.append(", high ").append(hi)
                        .append(" degrees ").append(temperatureUnitWord());
            }
            if (lo != null) {
                rowDescription.append(", low ").append(lo)
                        .append(" degrees ").append(temperatureUnitWord());
            }
            if (p >= 0) rowDescription.append(", ").append(p).append(" percent precipitation");
            rowDescription.append(". Tap for hourly details and to preview this day; tap again to return to now.");
            row.setContentDescription(rowDescription.toString());
            row.setOnClickListener(v -> details.toggleDay(dayIndex));
            makeChildrenUnimportant(row);
            forecastPreview.registerTarget(row, dayPreviewKey(dayIndex));
            item.addView(row);

            LinearLayout detailHost = new LinearLayout(this);
            detailHost.setOrientation(LinearLayout.VERTICAL);
            item.addView(detailHost, new LinearLayout.LayoutParams(-1, -2));
            details.registerListDetailHost(dayIndex, detailHost);

            list.addView(item);

            if (i < count - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(Color.argb(28, 255, 255, 255));
                list.addView(divider, new LinearLayout.LayoutParams(-1, 1));
            }
        }
        return scroller;
    }

    final class DayDetailCoordinator implements HourlyCoverageCoordinator {
        private final JSONArray days;
        private final JSONArray hours;
        private final ZoneId zone;
        private final String hourlyDiagnostic;
        private final LinearLayout lineDetailHost;
        private final ArrayList<LinearLayout> listDetailHosts = new ArrayList<>();
        private int expandedDay = -1;
        private boolean listMode;

        DayDetailCoordinator(
                JSONArray days,
                JSONArray hours,
                ZoneId zone,
                String hourlyDiagnostic,
                LinearLayout lineDetailHost) {
            this.days = days;
            this.hours = hours;
            this.zone = zone;
            this.hourlyDiagnostic = hourlyDiagnostic == null ? "" : hourlyDiagnostic;
            this.lineDetailHost = lineDetailHost;
        }

        void registerListDetailHost(int index, LinearLayout host) {
            while (listDetailHosts.size() <= index) listDetailHosts.add(null);
            listDetailHosts.set(index, host);
        }

        void setListMode(boolean listMode) {
            this.listMode = listMode;
            renderExpandedDay();
        }

        void toggleDay(int index) {
            if (days == null || index < 0 || index >= days.length()) return;
            boolean sameDay = expandedDay == index;
            LocalDate previousTarget = expandedDay >= 0
                    ? displayDate(days.optJSONObject(expandedDay), zone)
                    : null;
            expandedDay = sameDay ? -1 : index;
            if (sameDay) {
                forecastPreview.restore(true);
                synchronized (hourlyCoverageLock) {
                    pendingHourlyCoverageTarget = null;
                    if (previousTarget != null && previousTarget.equals(hourlyCoverageLoadingTarget)) {
                        hourlyCoverageLoadingTarget = null;
                    }
                }
                renderExpandedDay();
                return;
            }

            JSONObject day = days.optJSONObject(index);
            String label = index == 0 ? "Today" : dayLabel(day, zone);
            forecastPreview.select(
                    dayPreviewKey(index),
                    label,
                    sceneForForecastDay(day, index));

            // Mark/queue the target before the first detail render. That prevents a one-frame
            // unavailable + disabled-Loading-button contradiction while lazy continuation starts.
            LocalDate target = displayDate(day, zone);
            ensureHourlyCoverage(target, this);
            renderExpandedDay();
        }

        public void onHourlyCoverageChanged() {
            if (expandedDay < 0) return;
            int pageScrollY = mainScroll == null ? 0 : mainScroll.getScrollY();
            renderExpandedDay();
            if (mainScroll != null) {
                mainScroll.post(() -> mainScroll.scrollTo(0, pageScrollY));
            }
        }

        private void renderExpandedDay() {
            lineDetailHost.removeAllViews();
            lineDetailHost.setVisibility(View.GONE);
            for (LinearLayout host : listDetailHosts) {
                if (host != null) host.removeAllViews();
            }
            if (expandedDay < 0) return;

            View detail = buildDayHourlyDetail(
                    expandedDay, days, hours, zone, hourlyDiagnostic(lastHourlyWeather), this);
            if (listMode && expandedDay < listDetailHosts.size()) {
                LinearLayout host = listDetailHosts.get(expandedDay);
                if (host != null) {
                    host.addView(detail, new LinearLayout.LayoutParams(-1, -2));
                    return;
                }
            }
            if (!listMode) {
                lineDetailHost.addView(detail, new LinearLayout.LayoutParams(-1, -2));
                lineDetailHost.setVisibility(View.VISIBLE);
            }
        }
    }

    View buildDayHourlyDetail(
            int dayIndex,
            JSONArray days,
            JSONArray hours,
            ZoneId zone,
            String hourlyDiagnostic,
            DayDetailCoordinator coordinator) {
        LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setPadding(dp(10), dp(10), dp(10), dp(11));
        detail.setBackground(roundedBg(Color.argb(28, 255, 255, 255), dp(18)));

        JSONObject day = days == null ? null : days.optJSONObject(dayIndex);
        String dayName = dayIndex == 0 ? "Today" : dayLabel(day, zone);
        String date = dayDateLabel(day, zone);
        TextView title = text(dayName + (date.isEmpty() ? "" : "  " + date) + "  •  Hourly", 13, true, WHITE);
        detail.addView(title);

        TextView collapseHint = text("Tap the day again to collapse", 10, false, FAINT_WHITE);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(2);
        detail.addView(collapseHint, hintLp);

        LocalDate targetDate = displayDate(day, zone);
        HourlyPageState state = hourlyPageState;
        boolean covered = state != null
                && isHourlyStateCurrent(state)
                && hourlyDateCovered(state, targetDate, zone);
        boolean loadingTarget = !covered && isHourlyCoverageLoadingFor(targetDate);

        ArrayList<JSONObject> matches = new ArrayList<>();
        if (targetDate != null && hours != null) {
            for (int i = 0; i < hours.length(); i++) {
                JSONObject hour = hours.optJSONObject(i);
                LocalDate hourDate = hourLocalDate(hour, zone);
                if (hour != null && targetDate.equals(hourDate)) matches.add(hour);
            }
        }

        if (loadingTarget) {
            LinearLayout loading = new LinearLayout(this);
            loading.setOrientation(LinearLayout.HORIZONTAL);
            loading.setGravity(Gravity.CENTER_VERTICAL);
            loading.setPadding(0, dp(11), 0, dp(3));

            ProgressBar spinner = new ProgressBar(this);
            spinner.setIndeterminate(true);
            spinner.setContentDescription("Loading hourly details for " + dayName);
            loading.addView(spinner, new LinearLayout.LayoutParams(dp(22), dp(22)));

            TextView label = text("Loading hourly details…", 12, false, SOFT_WHITE);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-2, -2);
            labelLp.leftMargin = dp(9);
            loading.addView(label, labelLp);
            loading.setContentDescription("Loading hourly details for " + dayName);
            detail.addView(loading, new LinearLayout.LayoutParams(-1, dp(46)));
            return detail;
        }

        if (matches.isEmpty()) {
            boolean failed = state != null
                    && isHourlyStateCurrent(state)
                    && state.aggregate.optBoolean(HOURLY_LOAD_ERROR, false);
            boolean terminal = state != null
                    && isHourlyStateCurrent(state)
                    && state.terminal
                    && !failed;

            String headline = failed
                    ? "Couldn’t load hourly details."
                    : "No returned hourly data covers this day.";
            TextView unavailable = text(headline, 12, false, SOFT_WHITE);
            unavailable.setPadding(0, dp(10), 0, dp(2));
            detail.addView(unavailable);

            String diagnostic = hourlyDiagnostic == null ? "" : hourlyDiagnostic.trim();
            if (failed && !diagnostic.isEmpty()) {
                TextView reason = text(diagnostic, 10, false, FAINT_WHITE);
                reason.setMaxLines(4);
                reason.setEllipsize(android.text.TextUtils.TruncateAt.END);
                detail.addView(reason);
            } else if (terminal) {
                detail.addView(text("The returned hourly pages ended before this date.", 10, false, FAINT_WHITE));
            }

            if (hourlyRetryPossible(state)) {
                Button retry = button("Retry hourly details");
                retry.setContentDescription("Retry hourly details for " + dayName);
                retry.setOnClickListener(v -> ensureHourlyCoverage(targetDate, coordinator));
                LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(-2, dp(40));
                retryLp.topMargin = dp(8);
                detail.addView(retry, retryLp);
            }
            return detail;
        }

        GestureHorizontalScrollView scroller = new GestureHorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        scroller.setFillViewport(false);
        scroller.setContentDescription("Hourly forecast for " + dayName);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);

        for (JSONObject hour : matches) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(4), dp(3), dp(4), dp(4));

            TextView time = text(hourLabel(hour, zone), 11, true, SOFT_WHITE);
            time.setGravity(Gravity.CENTER);
            cell.addView(time, new LinearLayout.LayoutParams(-1, dp(22)));

            WeatherGlyphView glyph = new WeatherGlyphView(
                    this, description(hour), safeBoolean(hour, "isDaytime", true));
            cell.addView(glyph, new LinearLayout.LayoutParams(dp(32), dp(32)));

            Integer temperature = degreesOrNull(hour.optJSONObject("temperature"));
            TextView temp = text(temperature == null ? "—°" : temperature + "°", 15, true, WHITE);
            temp.setGravity(Gravity.CENTER);
            cell.addView(temp);

            int p = probability(hour);
            TextView rain = text(p >= 0 ? p + "%" : "—", 10, false, ACCENT_BLUE);
            rain.setGravity(Gravity.CENTER);
            cell.addView(rain);

            JSONObject hourWind = hour.optJSONObject("wind");
            String hourWindValue = formatWindSpeed(
                    hourWind == null ? null : hourWind.optJSONObject("speed"));
            String hourPressureValue = formatPressure(hour.optJSONObject("airPressure"));
            String hourVisibilityValue = formatVisibility(hour.optJSONObject("visibility"));
            addHourlyMetricLine(cell, "Wind", hourWindValue);
            addHourlyMetricLine(cell, "Pressure", hourPressureValue);
            addHourlyMetricLine(cell, "Visibility", hourVisibilityValue);

            TextView condition = text(description(hour), 9, false, FAINT_WHITE);
            condition.setGravity(Gravity.CENTER);
            condition.setMaxLines(2);
            condition.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cell.addView(condition, new LinearLayout.LayoutParams(-1, dp(30)));
            StringBuilder hourDescription = new StringBuilder(hourLabel(hour, zone));
            if (temperature != null) {
                hourDescription.append(", ").append(temperature)
                        .append(" degrees ").append(temperatureUnitWord());
            }
            if (p >= 0) hourDescription.append(", ").append(p).append(" percent precipitation");
            if (!"—".equals(hourWindValue)) hourDescription.append(", wind ").append(hourWindValue);
            if (!"—".equals(hourPressureValue)) hourDescription.append(", pressure ").append(hourPressureValue);
            if (!"—".equals(hourVisibilityValue)) hourDescription.append(", visibility ").append(hourVisibilityValue);
            configureHourPreviewCell(
                    cell,
                    hour,
                    zone,
                    false,
                    hourDescription.toString());

            row.addView(cell, new LinearLayout.LayoutParams(dp(116), -2));
        }
        scroller.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, -2);
        scrollLp.topMargin = dp(2);
        detail.addView(scroller, scrollLp);

        if (!covered && state != null && isHourlyStateCurrent(state)) {
            boolean failed = state.aggregate.optBoolean(HOURLY_LOAD_ERROR, false);
            TextView partial = text(
                    failed
                            ? "Returned hours are partial because hourly continuation stopped."
                            : "Only the returned hours for this day are available.",
                    10,
                    false,
                    FAINT_WHITE);
            LinearLayout.LayoutParams partialLp = new LinearLayout.LayoutParams(-1, -2);
            partialLp.topMargin = dp(6);
            detail.addView(partial, partialLp);
            if (hourlyRetryPossible(state)) {
                Button retry = button("Retry hourly details");
                retry.setContentDescription("Retry remaining hourly details for " + dayName);
                retry.setOnClickListener(v -> ensureHourlyCoverage(targetDate, coordinator));
                LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(-2, dp(40));
                retryLp.topMargin = dp(7);
                detail.addView(retry, retryLp);
            }
        }
        return detail;
    }

    void addHourlyMetricLine(LinearLayout cell, String label, String value) {
        if (cell == null || value == null || "—".equals(value)) return;
        TextView metric = text(label + " " + value, 8, false, FAINT_WHITE);
        metric.setGravity(Gravity.CENTER);
        metric.setSingleLine(true);
        metric.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cell.addView(metric, new LinearLayout.LayoutParams(-1, dp(17)));
    }

    void addDetailTiles(JSONObject current) {
        Integer feels = degreesOrNull(current == null ? null : current.optJSONObject("feelsLikeTemperature"));
        int humidity = safeInt(current, "relativeHumidity", -1);
        int uv = safeInt(current, "uvIndex", -1);

        JSONObject wind = current == null ? null : current.optJSONObject("wind");
        JSONObject speed = wind == null ? null : wind.optJSONObject("speed");
        JSONObject direction = wind == null ? null : wind.optJSONObject("direction");
        String cardinal = direction == null ? "" : direction.optString("cardinal", "");
        Double degrees = direction == null ? null : numberValue(direction, "degrees");
        String windLabel = shortCardinal(cardinal, degrees);
        String windValue = formatWindSpeed(speed);

        JSONObject pressureObject = current == null ? null : current.optJSONObject("airPressure");
        String pressureValue = formatPressure(pressureObject);
        String visibilityValue = formatVisibility(
                current == null ? null : current.optJSONObject("visibility"));

        LinearLayout first = tileRow(
                detailTile("UV", uv < 0 ? "—" : uv + "\n" + uvHint(uv).trim(), "uv"),
                detailTile("Feels like", feels == null ? "—" : feels + temperatureUnitSymbol(), "temperature"),
                detailTile("Humidity", humidity < 0 ? "—" : humidity + "%", "humidity"));

        LinearLayout second = tileRow(
                detailTile(windLabel.isEmpty() ? "Wind" : windLabel + " wind", windValue, "wind"),
                detailTile("Air pressure", pressureValue, "pressure"),
                detailTile("Visibility", visibilityValue, "visibility"));

        LinearLayout.LayoutParams firstLp = new LinearLayout.LayoutParams(-1, -2);
        firstLp.topMargin = dp(12);
        content.addView(first, firstLp);

        LinearLayout.LayoutParams secondLp = new LinearLayout.LayoutParams(-1, -2);
        secondLp.topMargin = dp(8);
        content.addView(second, secondLp);

        ArrayList<View> optionalTiles = new ArrayList<>();
        if (airQualityEnabled()) {
            OptionalDataState state = optionalDataStateForCurrentScope(true);
            optionalTiles.add(optionalDetailTile(
                    "Air quality", state, "air", "Universal AQI loading", true));
        }
        if (pollenEnabled()) {
            OptionalDataState state = optionalDataStateForCurrentScope(false);
            optionalTiles.add(optionalDetailTile(
                    "Pollen", state, "pollen", "Pollen forecast loading", false));
        }
        if (!optionalTiles.isEmpty()) {
            LinearLayout optionalRow = new LinearLayout(this);
            optionalRow.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < optionalTiles.size(); i++) {
                LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(0, dp(138), 1f);
                if (i > 0) tileLp.leftMargin = dp(4);
                if (i < optionalTiles.size() - 1) tileLp.rightMargin = dp(4);
                optionalRow.addView(optionalTiles.get(i), tileLp);
            }
            LinearLayout.LayoutParams optionalLp = new LinearLayout.LayoutParams(-1, -2);
            optionalLp.topMargin = dp(8);
            content.addView(optionalRow, optionalLp);
        }
    }

    OptionalDataState optionalDataStateForCurrentScope(boolean airQuality) {
        synchronized (optionalDataLock) {
            OptionalDataState state = airQuality ? airQualityState : pollenState;
            if (state == null) return null;
            String language = Locale.getDefault().toLanguageTag();
            return state.matches(weatherRequestGeneration, latitude, longitude, language) ? state : null;
        }
    }

    View optionalDetailTile(
            String label,
            OptionalDataState state,
            String glyph,
            String loadingDescription,
            boolean airQuality) {
        String value = state == null || state.loading ? "Loading…" : state.value;
        if (value == null || value.trim().isEmpty()) value = "Unavailable";
        boolean actionableError = state != null && !state.loading && !state.available;
        boolean keyBlocked = actionableError && "Key blocked".equals(state.value);
        if (keyBlocked) value = "Key blocked\nTap to fix";
        else if (actionableError) value = "No data\nTap for help";
        View tile = detailTile(label, value, glyph);
        if (tile instanceof LinearLayout) {
            View valueView = ((LinearLayout) tile).getChildAt(2);
            if (valueView instanceof TextView) {
                ((TextView) valueView).setTextSize(actionableError ? 12.5f : 14f);
                ((TextView) valueView).setEllipsize(
                        android.text.TextUtils.TruncateAt.END);
            }
        }
        if (state == null || state.loading) {
            tile.setContentDescription(loadingDescription);
        } else if (state.accessibility != null && !state.accessibility.trim().isEmpty()) {
            tile.setContentDescription(label + ", " + state.accessibility);
        }
        if (actionableError) {
            tile.setClickable(true);
            tile.setFocusable(true);
            tile.setContentDescription(label + ", " + state.accessibility + ". Tap for help.");
            tile.setOnClickListener(v -> showOptionalDataHelpDialog(label, airQuality, state));
        }
        return tile;
    }

    void showOptionalDataHelpDialog(
            String label, boolean airQuality, OptionalDataState state) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(20), dp(20), dp(18));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(250, 12, 23, 39));
        background.setCornerRadius(dp(24));
        background.setStroke(dp(1), Color.argb(58, 255, 255, 255));
        panel.setBackground(background);
        scroller.addView(panel, new ScrollView.LayoutParams(-1, -2));

        boolean keyBlocked = state != null && "Key blocked".equals(state.value);
        TextView title = text(keyBlocked ? label + " needs key access" : label + " help",
                22, false, WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(title);

        String message = keyBlocked
                ? "Google returned API_KEY_SERVICE_BLOCKED. The service is either not enabled "
                        + "for this key's project or this credential is not allowed to call it.\n\n"
                        + "Fix it in Google Cloud:\n"
                        + "1. Open APIs & Services → Credentials.\n"
                        + "2. Select this API key.\n"
                        + "3. Under API restrictions, choose Restrict key.\n"
                        + "4. Enable the needed APIs, then add Weather API, Air Quality API, and Pollen API under API restrictions.\n"
                        + "5. Save, wait a few minutes, then pull down to refresh."
                : "No usable data was returned. Check that the API is enabled, allowed in this "
                        + "key's API restrictions, and available for the selected location. Then refresh.";
        TextView explanation = text(message, 13, false, SOFT_WHITE);
        explanation.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams explanationLp = new LinearLayout.LayoutParams(-1, -2);
        explanationLp.topMargin = dp(7);
        panel.addView(explanation, explanationLp);

        TextView identity = text(androidRestrictionIdentity(), 11, false, FAINT_WHITE);
        identity.setTextIsSelectable(true);
        LinearLayout.LayoutParams identityLp = new LinearLayout.LayoutParams(-1, -2);
        identityLp.topMargin = dp(12);
        panel.addView(identity, identityLp);

        Button credentials = coordinateDialogButton("Open key settings ↗", false);
        credentials.setOnClickListener(v -> openExternalUrl(GOOGLE_CLOUD_CREDENTIALS_URL));
        LinearLayout.LayoutParams credentialsLp = new LinearLayout.LayoutParams(-1, dp(46));
        credentialsLp.topMargin = dp(14);
        panel.addView(credentials, credentialsLp);

        Button docs = coordinateDialogButton("Open " + label + " setup guide ↗", false);
        docs.setOnClickListener(v -> openExternalUrl(
                airQuality ? AIR_QUALITY_GUIDE_URL : POLLEN_GUIDE_URL));
        LinearLayout.LayoutParams docsLp = new LinearLayout.LayoutParams(-1, dp(46));
        docsLp.topMargin = dp(8);
        panel.addView(docs, docsLp);

        Button close = coordinateDialogButton("Done", true);
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(-1, dp(46));
        closeLp.topMargin = dp(12);
        panel.addView(close, closeLp);

        dialog.setContentView(scroller);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.76f;
            window.setAttributes(attributes);
            window.setGravity(Gravity.CENTER);
            window.getDecorView().setPadding(0, 0, 0, 0);
            int width = getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.max(1, Math.min(width - dp(30), dp(430))),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    LinearLayout tileRow(View a, View b, View c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        addWeightedTile(row, a, 0, dp(4));
        addWeightedTile(row, b, dp(4), dp(4));
        addWeightedTile(row, c, dp(4), 0);
        return row;
    }

    void addWeightedTile(LinearLayout row, View tile, int left, int right) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(138), 1);
        lp.leftMargin = left;
        lp.rightMargin = right;
        row.addView(tile, lp);
    }

    View detailTile(String label, String value, String glyphName) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.START);
        box.setPadding(dp(14), dp(15), dp(10), dp(12));
        box.setBackground(newGlassDrawable(dp(20), true));

        DetailGlyphView icon = new DetailGlyphView(this, glyphName);
        box.addView(icon, new LinearLayout.LayoutParams(dp(31), dp(31)));

        TextView l = text(label, 12, false, SOFT_WHITE);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(-1, -2);
        lLp.topMargin = dp(9);
        box.addView(l, lLp);

        TextView v = text(value, 17, false, WHITE);
        v.setMaxLines(2);
        v.setIncludeFontPadding(false);
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(-1, -2);
        vLp.topMargin = dp(3);
        box.addView(v, vLp);
        box.setContentDescription(label + ", " + (value == null || value.isEmpty() ? "unavailable" : value));
        return box;
    }

    void addSunCard(JSONArray days, JSONObject current, ZoneId zone) {
        Instant currentTime = parseInstant(current == null ? null : current.optString("currentTime", null));
        if (currentTime == null) currentTime = Instant.now();

        LocalDate currentDate;
        try {
            currentDate = currentTime.atZone(zone).toLocalDate();
        } catch (Exception ignored) {
            currentDate = LocalDate.now();
        }

        int currentIndex = findForecastDayIndex(days, currentDate, zone);
        if (currentIndex < 0 && days != null && days.length() > 0) currentIndex = 0;
        JSONObject currentDay = days == null || currentIndex < 0
                ? null : days.optJSONObject(currentIndex);
        Instant sunrise = sunEvent(currentDay, "sunriseTime");
        Instant sunset = sunEvent(currentDay, "sunsetTime");

        Instant nextSunrise = null;
        LocalDate nextSunriseDate = null;
        if (days != null) {
            for (int i = Math.max(0, currentIndex + 1); i < days.length(); i++) {
                JSONObject day = days.optJSONObject(i);
                Instant candidate = sunEvent(day, "sunriseTime");
                if (candidate != null && candidate.isAfter(currentTime)) {
                    nextSunrise = candidate;
                    nextSunriseDate = displayDate(day, zone);
                    if (nextSunriseDate == null) {
                        try {
                            nextSunriseDate = candidate.atZone(zone).toLocalDate();
                        } catch (Exception ignored) { }
                    }
                    break;
                }
            }
        }

        if (sunrise == null && sunset == null && nextSunrise == null) return;

        String nextLabel;
        Instant nextEvent;
        LocalDate nextEventDate;
        if (sunrise != null && currentTime.isBefore(sunrise)) {
            nextLabel = "Sunrise";
            nextEvent = sunrise;
            nextEventDate = currentDate;
        } else if (sunset != null && currentTime.isBefore(sunset)) {
            nextLabel = "Sunset";
            nextEvent = sunset;
            nextEventDate = currentDate;
        } else if (nextSunrise != null) {
            nextLabel = "Sunrise";
            nextEvent = nextSunrise;
            nextEventDate = nextSunriseDate;
        } else if (sunset != null && !currentTime.isBefore(sunset)) {
            nextLabel = "Sunrise";
            nextEvent = null;
            nextEventDate = currentDate.plusDays(1);
        } else {
            nextLabel = sunrise != null ? "Sunrise" : "Sunset";
            nextEvent = sunrise != null ? sunrise : sunset;
            nextEventDate = currentDate;
        }

        Instant trackStart = null;
        Instant trackEnd = null;
        boolean moonTrack = false;
        if (sunrise != null && sunset != null
                && !currentTime.isBefore(sunrise) && currentTime.isBefore(sunset)) {
            trackStart = sunrise;
            trackEnd = sunset;
        } else if (sunset != null && nextSunrise != null && !currentTime.isBefore(sunset)) {
            trackStart = sunset;
            trackEnd = nextSunrise;
            moonTrack = true;
        } else if (sunrise != null && currentTime.isBefore(sunrise)) {
            trackEnd = sunrise;
            if (sunset != null) {
                trackStart = sunset.minus(Duration.ofDays(1));
            } else {
                trackStart = sunrise.minus(Duration.ofHours(12));
            }
            moonTrack = true;
        } else if (sunrise != null && sunset != null) {
            trackStart = sunrise;
            trackEnd = sunset;
        }

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(16), dp(18), dp(18));

        TextView eyebrow = text("Next solar event", 12, true, SOFT_WHITE);
        body.addView(eyebrow);

        LinearLayout prominent = new LinearLayout(this);
        prominent.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams prominentLp = new LinearLayout.LayoutParams(-1, -2);
        prominentLp.topMargin = dp(3);
        body.addView(prominent, prominentLp);

        LinearLayout eventLabels = new LinearLayout(this);
        eventLabels.setOrientation(LinearLayout.VERTICAL);
        TextView eventName = text(nextLabel, 20, true, WHITE);
        eventLabels.addView(eventName);
        String context = solarDateContext(nextEventDate, currentDate, zone);
        TextView eventContext = text(context, 11, false, FAINT_WHITE);
        if (!context.isEmpty()) {
            LinearLayout.LayoutParams contextLp = new LinearLayout.LayoutParams(-1, -2);
            contextLp.topMargin = dp(1);
            eventLabels.addView(eventContext, contextLp);
        }
        prominent.addView(eventLabels, new LinearLayout.LayoutParams(0, -2, 1));

        TextView eventTime = text(formatTime(nextEvent, zone), 27, false, WHITE);
        eventTime.setGravity(Gravity.END);
        eventTime.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        eventTime.setContentDescription(
                nextLabel + " " + formatTime(nextEvent, zone)
                        + (context.isEmpty() ? "" : ", " + context));
        prominent.addView(eventTime, new LinearLayout.LayoutParams(dp(112), -2));

        if (trackStart != null && trackEnd != null && trackEnd.isAfter(trackStart)) {
            boolean animate = animationsAllowed();
            SunTrackView track = new SunTrackView(
                    this, trackStart, trackEnd, currentTime, moonTrack, animate);
            sunTrackViews.add(track);
            LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(-1, dp(54));
            trackLp.topMargin = dp(9);
            body.addView(track, trackLp);
        }

        String staticTimes = solarStaticText(
                sunrise, sunset, nextSunrise, currentTime, zone);
        if (!staticTimes.isEmpty()) {
            TextView times = text(staticTimes, 12, false, SOFT_WHITE);
            times.setGravity(Gravity.CENTER_HORIZONTAL);
            times.setContentDescription(staticTimes);
            LinearLayout.LayoutParams timesLp = new LinearLayout.LayoutParams(-1, -2);
            timesLp.topMargin = dp(6);
            body.addView(times, timesLp);
        }

        LinearLayout.LayoutParams lp = defaultCardParams();
        lp.topMargin = dp(12);
        View card = card(body, dp(24), cardColor);
        card.setLayoutParams(lp);
        content.addView(card);
    }

    int findForecastDayIndex(JSONArray days, LocalDate target, ZoneId zone) {
        if (days == null || target == null) return -1;
        for (int i = 0; i < days.length(); i++) {
            if (target.equals(displayDate(days.optJSONObject(i), zone))) return i;
        }
        return -1;
    }

    static Instant sunEvent(JSONObject day, String key) {
        if (day == null) return null;
        JSONObject sunEvents = day.optJSONObject("sunEvents");
        return sunEvents == null ? null : parseInstant(sunEvents.optString(key, null));
    }

    static String solarDateContext(LocalDate eventDate, LocalDate currentDate, ZoneId zone) {
        if (eventDate == null || currentDate == null) return "";
        if (eventDate.equals(currentDate)) return "Today";
        if (eventDate.equals(currentDate.plusDays(1))) return "Tomorrow";
        try {
            return eventDate.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()));
        } catch (Exception ignored) {
            return "";
        }
    }

    static String solarStaticText(
            Instant sunrise,
            Instant sunset,
            Instant nextSunrise,
            Instant currentTime,
            ZoneId zone) {
        if (currentTime != null && sunset != null && !currentTime.isBefore(sunset)
                && nextSunrise != null) {
            return "Sunset " + formatTime(sunset, zone)
                    + "  •  Sunrise tomorrow " + formatTime(nextSunrise, zone);
        }
        if (sunrise != null && sunset != null) {
            return "Sunrise " + formatTime(sunrise, zone)
                    + "  •  Sunset " + formatTime(sunset, zone);
        }
        if (sunrise != null) return "Sunrise " + formatTime(sunrise, zone);
        if (sunset != null) return "Sunset " + formatTime(sunset, zone);
        if (nextSunrise != null) return "Sunrise tomorrow " + formatTime(nextSunrise, zone);
        return "";
    }

    void addMoonCard(JSONObject today, ZoneId zone) {
        JSONObject moon = today == null ? null : today.optJSONObject("moonEvents");
        if (moon == null) return;

        String phase = moon.optString("moonPhase", "");
        Instant rise = firstInstant(moon.optJSONArray("moonriseTimes"));
        Instant set = firstInstant(moon.optJSONArray("moonsetTimes"));
        if (phase.isEmpty() && rise == null && set == null) return;

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(18), dp(17), dp(14), dp(17));

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.addView(text(prettyPhase(phase), 14, false, SOFT_WHITE));

        if (rise != null) {
            textColumn.addView(moonEventRow("Moonrise", formatTime(rise, zone)));
        }
        if (set != null) {
            textColumn.addView(moonEventRow("Moonset", formatTime(set, zone)));
        }
        body.addView(textColumn, new LinearLayout.LayoutParams(0, -2, 1));

        MoonPhaseView moonView = new MoonPhaseView(this, phase);
        body.addView(moonView, new LinearLayout.LayoutParams(dp(110), dp(110)));

        LinearLayout.LayoutParams lp = defaultCardParams();
        lp.topMargin = dp(12);
        View card = card(body, dp(24), cardColor);
        card.setLayoutParams(lp);
        content.addView(card);
    }

    View moonEventRow(String label, String time) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(13), dp(2), 0);
        TextView l = text(label, 16, false, WHITE);
        TextView t = text(time, 16, false, WHITE);
        t.setGravity(Gravity.END);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(t, new LinearLayout.LayoutParams(dp(76), -2));
        return row;
    }

    void addAttribution() {
        StringBuilder source = new StringBuilder("Source: Includes weather data from Google");
        if (airQualityEnabled()) {
            source.append("\nSource: Includes air quality data from Google");
        }
        if (pollenEnabled()) {
            source.append("\nSource: Includes pollen data from Google");
        }
        if (airQualityEnabled() || pollenEnabled()) {
            source.append("\nGoogle Maps");
        }
        TextView attribution = text(source.toString(), 12, false, FAINT_WHITE);
        attribution.setGravity(Gravity.CENTER);
        attribution.setPadding(dp(4), dp(24), dp(4), dp(10));
        content.addView(attribution);
    }


    void makeChildrenUnimportant(ViewGroup parent) {
        if (parent == null) return;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (child instanceof ViewGroup) makeChildrenUnimportant((ViewGroup) child);
        }
    }

    SceneSpec sceneForForecastDay(JSONObject day, int index) {
        if (day == null) return forecastPreview.currentScene();
        boolean currentDaytime = safeBoolean(lastCurrentWeather, "isDaytime", true);
        JSONObject daytime = day.optJSONObject("daytimeForecast");
        JSONObject nighttime = day.optJSONObject("nighttimeForecast");
        JSONObject selected;
        boolean fallbackDaytime;
        if (index == 0) {
            if (currentDaytime) {
                selected = daytime != null ? daytime : nighttime;
                fallbackDaytime = daytime != null;
            } else {
                selected = nighttime != null ? nighttime : daytime;
                fallbackDaytime = nighttime == null;
            }
        } else {
            selected = daytime != null ? daytime : nighttime;
            fallbackDaytime = daytime != null;
        }
        return selected == null
                ? forecastPreview.currentScene()
                : SceneSpec.fromWeather(selected, fallbackDaytime);
    }

    String dayPreviewKey(int index) {
        return "day:" + index;
    }

    String hourPreviewKey(JSONObject hour) {
        String identity = hourlyIdentity(hour);
        return identity.isEmpty() ? "hour:" + System.identityHashCode(hour) : "hour:" + identity;
    }

    String hourPreviewLabel(JSONObject hour, ZoneId zone) {
        LocalDate date = hourLocalDate(hour, zone);
        String day = "";
        if (date != null) {
            try {
                day = date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()));
            } catch (Exception ignored) { }
        }
        String time = hourLabel(hour, zone);
        return day.isEmpty() ? time : day + " " + time;
    }

    void configureHourPreviewCell(
            LinearLayout cell,
            JSONObject hour,
            ZoneId zone,
            boolean nowCell,
            String detailDescription) {
        if (cell == null) return;
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setBackground(previewTargetBackground(dp(14)));
        makeChildrenUnimportant(cell);
        String condition = description(hour);
        if (nowCell) {
            cell.setContentDescription((detailDescription == null ? "Now" : detailDescription)
                    + ", " + condition + ". Tap to restore current weather.");
            cell.setOnClickListener(v -> forecastPreview.restore(true));
            applyPreviewSelectionVisual(cell, false);
            return;
        }
        String key = hourPreviewKey(hour);
        String label = hourPreviewLabel(hour, zone);
        SceneSpec scene = SceneSpec.fromWeather(hour, safeBoolean(hour, "isDaytime", true));
        cell.setContentDescription((detailDescription == null ? label : detailDescription)
                + ", " + condition + ". Tap to preview this hour; tap again to return to now.");
        cell.setOnClickListener(v -> forecastPreview.select(key, label, scene));
        forecastPreview.registerTarget(cell, key);
    }

    void applyScenePalette(String scene) {
        if ("night".equals(scene)) {
            cardColor = Color.argb(104, 18, 47, 96);
            tileColor = Color.argb(90, 18, 51, 104);
            glassCardTop = Color.argb(102, 18, 44, 84);
            glassCardBottom = Color.argb(58, 8, 24, 52);
            glassTileTop = Color.argb(90, 20, 48, 90);
            glassTileBottom = Color.argb(48, 9, 27, 56);
            glassEdge = Color.TRANSPARENT;
        } else if ("rain".equals(scene)) {
            cardColor = Color.argb(104, 40, 65, 87);
            tileColor = Color.argb(90, 42, 69, 92);
            glassCardTop = Color.argb(96, 49, 70, 89);
            glassCardBottom = Color.argb(54, 23, 40, 56);
            glassTileTop = Color.argb(84, 51, 73, 94);
            glassTileBottom = Color.argb(46, 24, 43, 60);
            glassEdge = Color.TRANSPARENT;
        } else if ("snow".equals(scene)) {
            cardColor = Color.argb(98, 67, 101, 132);
            tileColor = Color.argb(84, 70, 106, 139);
            glassCardTop = Color.argb(90, 82, 112, 138);
            glassCardBottom = Color.argb(50, 52, 80, 105);
            glassTileTop = Color.argb(78, 84, 116, 144);
            glassTileBottom = Color.argb(44, 54, 83, 110);
            glassEdge = Color.TRANSPARENT;
        } else {
            cardColor = Color.argb(86, 38, 103, 190);
            tileColor = Color.argb(68, 42, 103, 181);
            glassCardTop = Color.argb(86, 72, 132, 205);
            glassCardBottom = Color.argb(48, 72, 118, 174);
            glassTileTop = Color.argb(74, 73, 133, 198);
            glassTileBottom = Color.argb(42, 67, 112, 167);
            glassEdge = Color.TRANSPARENT;
        }
        for (GlassDrawable drawable : glassDrawables) {
            applyGlassPalette(drawable);
        }
        if (refreshIndicator != null) {
            refreshIndicator.setPalette(glassCardTop, glassCardBottom, settingsAccent(scene));
        }
    }

    void applyGlassPalette(GlassDrawable drawable) {
        if (drawable == null) return;
        if (drawable.isTile()) {
            drawable.setColors(glassTileTop, glassTileBottom, glassEdge);
        } else {
            drawable.setColors(glassCardTop, glassCardBottom, glassEdge);
        }
    }

    void showError(Exception e) {
        clearDynamicContent();
        progress.setVisibility(View.GONE);
        status.setText("Could not load forecast");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(18), dp(18), dp(18));

        String message;
        if (e instanceof WeatherRequestException) {
            message = e.getMessage();
        } else if (e instanceof IllegalStateException
                && "API key is not configured".equals(e.getMessage())) {
            message = "API key is not configured";
        } else {
            message = "Weather data could not be loaded. Please retry.";
        }
        if (message == null || message.trim().isEmpty()) {
            message = "Weather data could not be loaded. Please retry.";
        }
        box.addView(text(message, 14, false, WHITE));

        Button retry = button("Retry");
        retry.setOnClickListener(v -> refreshWeather(true));
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(-2, dp(44));
        retryLp.topMargin = dp(14);
        box.addView(retry, retryLp);

        content.addView(card(box, dp(24), cardColor));
    }

    void clearDynamicContent() {
        while (content.getChildCount() > dynamicStartIndex) {
            content.removeViewAt(dynamicStartIndex);
        }
        glassDrawables.clear();
        if (modeSwitchGlass != null) {
            applyGlassPalette(modeSwitchGlass);
            glassDrawables.add(modeSwitchGlass);
        }
        sunTrackViews.clear();
        precipitationBody = null;
    }

    View card(View child, int radiusPx, int color) {
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setBackground(newGlassDrawable(radiusPx, false));
        holder.addView(child, new LinearLayout.LayoutParams(-1, -2));
        return holder;
    }

    GlassDrawable newGlassDrawable(int radiusPx, boolean tile) {
        GlassDrawable drawable = new GlassDrawable(
                radiusPx,
                Math.max(1f, getResources().getDisplayMetrics().density),
                tile);
        applyGlassPalette(drawable);
        glassDrawables.add(drawable);
        return drawable;
    }

    LinearLayout.LayoutParams defaultCardParams() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    GradientDrawable roundedBg(int color, int radiusPx) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(radiusPx);
        return bg;
    }

    Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(WHITE);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setPadding(dp(15), 0, dp(15), 0);
        b.setBackground(roundedBg(Color.argb(36, 255, 255, 255), dp(22)));
        return b;
    }

    TextView text(String value, int sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }


    String windUnitPreference() {
        return weatherPreferences.windUnit(isFahrenheitUnit());
    }

    String pressureUnitPreference() {
        return weatherPreferences.pressureUnit();
    }

    String visibilityUnitPreference() {
        return weatherPreferences.visibilityUnit(isFahrenheitUnit());
    }

    static String normalizeWindUnit(String value) {
        return WeatherPreferences.normalizeWindUnit(value);
    }

    static String normalizePressureUnit(String value) {
        return WeatherPreferences.normalizePressureUnit(value);
    }

    static String normalizeVisibilityUnit(String value) {
        return WeatherPreferences.normalizeVisibilityUnit(value);
    }

    String formatWindSpeed(JSONObject speed) {
        Double kmh = speedKilometersPerHour(speed);
        if (kmh == null) return "—";
        String unit = windUnitPreference();
        double value;
        if (WIND_MPH.equals(unit)) value = kmh / 1.609344d;
        else if (WIND_MS.equals(unit)) value = kmh / 3.6d;
        else if (WIND_KNOTS.equals(unit)) value = kmh / 1.852d;
        else value = kmh;
        return trimNumber(value) + " " + unit;
    }

    Double speedKilometersPerHour(JSONObject speed) {
        Double value = numberValue(speed, "value");
        if (value == null) return null;
        String unit = safeUnitString(speed);
        if (unit.contains("MILE") || "MPH".equals(unit) || "MI/H".equals(unit)) {
            return value * 1.609344d;
        }
        if (unit.contains("METER") && unit.contains("SECOND")
                || "M/S".equals(unit) || "MPS".equals(unit)) {
            return value * 3.6d;
        }
        if (unit.contains("KNOT") || "KT".equals(unit) || "KTS".equals(unit)) {
            return value * 1.852d;
        }
        if (unit.contains("KILOMETER") || "KM/H".equals(unit) || "KPH".equals(unit)) {
            return value;
        }
        // Unit metadata is optional. WeatherNext always requests METRIC from Google Weather.
        return value;
    }

    String formatPressure(JSONObject pressure) {
        Double hpa = pressureHpa(pressure);
        if (hpa == null) return "—";
        String unit = pressureUnitPreference();
        if (PRESSURE_INHG.equals(unit)) {
            return String.format(Locale.getDefault(), "%.2f %s", hpa * 0.0295299830714d, unit);
        }
        if (PRESSURE_MMHG.equals(unit)) {
            return String.format(Locale.getDefault(), "%.1f %s", hpa * 0.750061683d, unit);
        }
        return Math.round(hpa) + " " + PRESSURE_HPA;
    }

    static Double pressureHpa(JSONObject pressure) {
        if (pressure == null) return null;
        Double value = numberValue(pressure, "meanSeaLevelMillibars");
        if (value != null) return value;
        value = numberValue(pressure, "meanSeaLevelHectopascals");
        if (value != null) return value;
        value = numberValue(pressure, "value");
        if (value == null) return null;
        String unit = safeUnitString(pressure);
        if (unit.contains("INHG") || unit.contains("INCH")) return value / 0.0295299830714d;
        if (unit.contains("MMHG") || unit.contains("MILLIMETER")) return value / 0.750061683d;
        if (unit.contains("KILOPASCAL") || "KPA".equals(unit)) return value * 10d;
        if ((unit.contains("PASCAL") || "PA".equals(unit))
                && !unit.contains("HECTO")) return value / 100d;
        return value;
    }

    String formatVisibility(JSONObject visibility) {
        Double km = visibilityKilometers(visibility);
        if (km == null) return "—";
        String unit = visibilityUnitPreference();
        double value = VISIBILITY_MI.equals(unit) ? km / 1.609344d : km;
        return trimNumber(value) + " " + unit;
    }

    Double visibilityKilometers(JSONObject visibility) {
        if (visibility == null) return null;
        Double value = numberValue(visibility, "distance");
        if (value == null) value = numberValue(visibility, "value");
        if (value == null) return null;
        String unit = safeUnitString(visibility);
        if (unit.contains("MILE") || "MI".equals(unit)) return value * 1.609344d;
        if (unit.contains("KILOMETER") || "KM".equals(unit)) return value;
        // Missing unit metadata follows the METRIC API request.
        return value;
    }

    static String safeUnitString(JSONObject object) {
        if (object == null) return "";
        String value = object.optString("unit", "");
        if (value == null) return "";
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    static int settingsAccent(String scene) {
        if ("night".equals(scene)) return Color.rgb(154, 202, 255);
        if ("thunder".equals(scene)) return Color.rgb(176, 213, 255);
        if ("rain".equals(scene) || "fog".equals(scene)) return Color.rgb(168, 218, 244);
        if ("snow".equals(scene)) return Color.rgb(220, 242, 255);
        return Color.rgb(151, 211, 255);
    }

    static String settingsSceneKey(JSONObject current, boolean daytime) {
        String key = conditionKey(description(current));
        if ("thunder".equals(key) || "rain".equals(key)
                || "fog".equals(key) || "snow".equals(key)) {
            return key;
        }
        return daytime ? "day" : "night";
    }


    String dataAgeLabel(JSONObject current) {
        Instant published = parseInstant(current == null ? null : current.optString("currentTime", null));
        if (published == null) return "Google Weather data loaded";
        long minutes;
        try {
            minutes = Duration.between(published, Instant.now()).toMinutes();
        } catch (Exception ignored) {
            return "Google Weather data loaded";
        }
        if (minutes < 0) minutes = 0;
        if (minutes == 0) return "Data published just now";
        if (minutes == 1) return "Data published 1 minute ago";
        return "Data published " + minutes + " minutes ago";
    }


}
