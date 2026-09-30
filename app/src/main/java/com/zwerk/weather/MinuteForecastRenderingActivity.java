package com.zwerk.weather;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;


import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Locale;


abstract class MinuteForecastRenderingActivity extends MinuteForecastActivity {
    void installOverviewTopLevelBoard(LinearLayout staging) {
        if (staging == null || overviewPageContent == null) return;
        while (staging.getChildCount() > 0) {
            View child = staging.getChildAt(0);
            staging.removeViewAt(0);
            overviewPageContent.addView(child);
        }
    }

    void renderCurrentMode() {
        if (lastCurrentWeather == null || lastDailyWeather == null) {
            clearDynamicContent();
            return;
        }
        renderAllForecastPages();
    }

    void renderAllForecastPages() {
        if (renderingAllForecastPages) return;
        if (this instanceof MainActivity) {
            ((MainActivity) this).cancelForecastSwipe();
        }
        clearDynamicContent();
        renderingAllForecastPages = true;
        try {
            activePageContent = overviewPageContent;
            renderOverviewContent();
            activePageContent = dailyPageContent;
            renderDailyContent();
            activePageContent = precipitationPageContent;
            renderPrecipitationContent();
        } finally {
            renderingAllForecastPages = false;
            activePageContent = this instanceof MainActivity
                    ? ((MainActivity) this).activeForecastPageContent()
                    : (precipitationMode ? precipitationPageContent : overviewPageContent);
        }
        if (forecastPageHost != null) forecastPageHost.requestLayout();
    }

    LinearLayout pageContent() {
        if (activePageContent != null) return activePageContent;
        return content;
    }

    void renderOverviewContent() {
        if (!renderingAllForecastPages) {
            rerenderOverviewPage();
            return;
        }
        ZoneId zone = responseZone(lastCurrentWeather, lastHourlyWeather, lastDailyWeather);
        JSONArray days = lastDailyWeather == null
                ? null : lastDailyWeather.optJSONArray("forecastDays");
        JSONObject today = firstObject(days);

        LinearLayout destination = activePageContent;
        LinearLayout staging = new LinearLayout(this);
        staging.setOrientation(LinearLayout.VERTICAL);
        staging.setClipChildren(false);
        staging.setClipToPadding(false);
        activePageContent = staging;
        try {
            int before = staging.getChildCount();
            addHero(lastCurrentWeather, today);
            markOverviewTopLevelBlock(staging, before, "hero");

            before = staging.getChildCount();
            addWeatherAlertsCard();
            markOverviewTopLevelBlock(staging, before, "alerts");

            before = staging.getChildCount();
            addHourlyCard(lastHourlyWeather, zone);
            markOverviewTopLevelBlock(staging, before, "hourly");

            addDetailTiles(lastCurrentWeather);

            before = staging.getChildCount();
            addSunCard(days, lastCurrentWeather, zone);
            markOverviewTopLevelBlock(staging, before, "solar");

            before = staging.getChildCount();
            addMoonCard(today, zone);
            markOverviewTopLevelBlock(staging, before, "moon");

            before = staging.getChildCount();
            addAttribution(true);
            markOverviewTopLevelBlock(staging, before, "attribution");
        } finally {
            activePageContent = destination;
        }
        installOverviewTopLevelBoard(staging);
    }

    void markOverviewTopLevelBlock(LinearLayout staging, int childCountBefore, String stableId) {
        if (staging == null || stableId == null || stableId.isEmpty()) return;
        int added = staging.getChildCount() - childCountBefore;
        if (added <= 0) return;
        for (int i = childCountBefore; i < staging.getChildCount(); i++) {
            View child = staging.getChildAt(i);
            child.setTag(added == 1 ? stableId : stableId + "_" + (i - childCountBefore));
        }
    }

    void rerenderOverviewPage() {
        if (overviewPageContent == null || lastCurrentWeather == null || lastDailyWeather == null) {
            return;
        }
        if (globalErrorView != null) return;
        if (this instanceof MainActivity) ((MainActivity) this).cancelForecastSwipe();
        forecastPreview.removeViewBindings(overviewPageContent);
        removePageGlassDrawables(overviewPageContent);
        overviewPageContent.removeAllViews();
        sunTrackViews.clear();
        boolean previous = renderingAllForecastPages;
        LinearLayout previousContent = activePageContent;
        renderingAllForecastPages = true;
        activePageContent = overviewPageContent;
        try {
            renderOverviewContent();
        } finally {
            renderingAllForecastPages = previous;
            activePageContent = previousContent;
        }
        if (forecastPageHost != null) forecastPageHost.requestLayout();
    }

    void renderPrecipitationContent() {
        if (!renderingAllForecastPages) {
            rerenderPrecipitationPage();
            return;
        }
        if (precipitationMode) forecastPreview.restore(false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        precipitationBody = page;

        MinuteForecastState state = minuteForecastStateForCurrentScope();
        if (state == null) {
            addMinuteStateCard(
                    page,
                    "Minute forecast ready",
                    "Precipitation loads only when this view is opened.",
                    "Load precipitation",
                    () -> ensureMinuteForecast(false),
                    false);
        } else if (state.loading) {
            addMinuteLoadingCard(page);
        } else if (state.response == null) {
            addMinuteStateCard(
                    page,
                    state.unsupported ? "Minute forecast unavailable" : "Couldn’t load precipitation",
                    state.errorMessage.isEmpty()
                            ? "Minute precipitation is unavailable right now."
                            : state.errorMessage,
                    "Retry minute forecast",
                    () -> ensureMinuteForecast(true),
                    state.unsupported);
        } else {
            renderMinuteForecastReady(page, state);
        }

        addMinuteAttribution(page);
        pageContent().addView(page, new LinearLayout.LayoutParams(-1, -2));
    }

    @Override
    protected void rerenderPrecipitationPage() {
        if (precipitationPageContent == null) return;
        if (globalErrorView != null) return;
        if (this instanceof MainActivity && precipitationMode) {
            ((MainActivity) this).cancelForecastSwipe();
        }
        removePageGlassDrawables(precipitationPageContent);
        precipitationPageContent.removeAllViews();
        precipitationBody = null;
        boolean previous = renderingAllForecastPages;
        LinearLayout previousContent = activePageContent;
        renderingAllForecastPages = true;
        activePageContent = precipitationPageContent;
        try {
            renderPrecipitationContent();
        } finally {
            renderingAllForecastPages = previous;
            activePageContent = previousContent;
        }
        if (forecastPageHost != null) forecastPageHost.requestLayout();
    }

    void rerenderDailyPage() {
        if (dailyPageContent == null || lastDailyWeather == null || globalErrorView != null) return;
        forecastPreview.removeViewBindings(dailyPageContent);
        removePageGlassDrawables(dailyPageContent);
        dailyPageContent.removeAllViews();
        LinearLayout previousContent = activePageContent;
        activePageContent = dailyPageContent;
        try { renderDailyContent(); }
        finally { activePageContent = previousContent; }
        if (forecastPageHost != null) forecastPageHost.requestLayout();
    }

    void removePageGlassDrawables(View view) {
        if (view == null) return;
        if (view.getBackground() instanceof GlassDrawable) {
            glassDrawables.remove(view.getBackground());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                removePageGlassDrawables(group.getChildAt(i));
            }
        }
    }

    void addMinuteLoadingCard(LinearLayout page) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(dp(18), dp(22), dp(18), dp(22));
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.setContentDescription(UiTranslations.text(this, "Loading minute precipitation"));
        body.addView(spinner, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView headline = text("Loading precipitation…", 17, true, WHITE);
        headline.setGravity(Gravity.CENTER);
        headline.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams headlineLp = new LinearLayout.LayoutParams(-1, -2);
        headlineLp.topMargin = dp(12);
        body.addView(headline, headlineLp);
        TextView detail = text(
                OpenMeteoConfig.isPrecipitationOpenMeteo(this)
                        ? "Loading Open-Meteo precipitation…"
                        : "Using the returned segment timing exactly as provided by Google Weather.",
                13,
                false,
                SOFT_WHITE);
        detail.setGravity(Gravity.CENTER);
        detail.setLineSpacing(0, 1.08f);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = dp(5);
        body.addView(detail, detailLp);
        addMinutePageCard(page, body, 14);
    }

    void addMinuteStateCard(
            LinearLayout page,
            String headlineText,
            String detailText,
            String actionLabel,
            Runnable action,
            boolean unsupported) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(18), dp(18), dp(18));
        TextView headline = text(headlineText, 18, true, WHITE);
        body.addView(headline);
        TextView detail = text(detailText, 14, false, SOFT_WHITE);
        detail.setLineSpacing(0, 1.1f);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = dp(6);
        body.addView(detail, detailLp);
        if (unsupported) {
            TextView support = text(
                    "Overview remains available, and this state does not change current weather values.",
                    12,
                    false,
                    FAINT_WHITE);
            LinearLayout.LayoutParams supportLp = new LinearLayout.LayoutParams(-1, -2);
            supportLp.topMargin = dp(9);
            body.addView(support, supportLp);
        }
        if (action != null && actionLabel != null && !actionLabel.isEmpty()) {
            Button actionButton = button(actionLabel);
            actionButton.setMinHeight(dp(48));
            actionButton.setContentDescription(actionLabel);
            actionButton.setOnClickListener(v -> action.run());
            LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(-2, dp(48));
            actionLp.topMargin = dp(14);
            body.addView(actionButton, actionLp);
        }
        addMinutePageCard(page, body, 14);
    }

    void renderMinuteForecastReady(LinearLayout page, MinuteForecastState state) {
        ZoneId zone = minuteResponseZone(state.response);
        Instant windowStart = Instant.now();
        Instant windowEnd = windowStart.plus(Duration.ofHours(minuteRangeHours));
        ArrayList<MinuteSegment> allSegments = minuteSegments(state.response, windowStart, 0);

        addMinuteRangeSelector(page);

        if (allSegments.isEmpty()) {
            addMinuteStateCard(
                    page,
                    "No minute segments available",
                    "No unelapsed minute-level precipitation segments were returned for this location.",
                    null,
                    null,
                    false);
            return;
        }

        ArrayList<MinuteSegment> visible = minuteSegments(state.response, windowStart, minuteRangeHours);
        if (visible.isEmpty()) {
            addMinuteStateCard(
                    page,
                    "No returned segments in this range",
                    minuteRangeHours == MINUTE_RANGE_TWO_HOURS
                            ? "No returned segments fall inside the next 2 hours. The 6h range may contain later returned coverage."
                            : "No returned segments fall inside the selected six-hour range.",
                    null,
                    null,
                    false);
            return;
        }

        LinearLayout graphBody = new LinearLayout(this);
        graphBody.setOrientation(LinearLayout.VERTICAL);
        graphBody.setPadding(dp(12), dp(12), dp(12), dp(13));

        TextView graphTitle = text(
                minuteRangeHours == MINUTE_RANGE_TWO_HOURS ? "Next 2 hours" : "Next 6 hours",
                17,
                true,
                WHITE);
        graphBody.addView(graphTitle);

        LinearLayout metricRow = new LinearLayout(this);
        metricRow.setOrientation(LinearLayout.HORIZONTAL);
        metricRow.setGravity(Gravity.CENTER_VERTICAL);
        metricRow.setPadding(0, dp(9), 0, dp(4));
        metricRow.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        TextView selectedTime = text("—", 15, true, WHITE);
        TextView selectedRate = text("—", 15, true, WHITE);
        TextView selectedChance = text("—", 15, true, WHITE);
        TextView selectedType = text("—", 11, true, WHITE);
        selectedType.setMaxLines(2);
        selectedType.setEllipsize(android.text.TextUtils.TruncateAt.END);

        metricRow.addView(minuteMetricCell("TIME", selectedTime), new LinearLayout.LayoutParams(0, dp(58), 0.9f));
        metricRow.addView(minuteMetricCell("RATE", selectedRate), new LinearLayout.LayoutParams(0, dp(58), 1.15f));
        if (visible.stream().anyMatch(segment -> segment.probability != null)) {
            metricRow.addView(minuteMetricCell("CHANCE", selectedChance), new LinearLayout.LayoutParams(0, dp(58), 0.9f));
        }
        metricRow.addView(minuteMetricCell("TYPE", selectedType), new LinearLayout.LayoutParams(0, dp(58), 1.35f));
        graphBody.addView(metricRow, new LinearLayout.LayoutParams(-1, dp(62)));

        MinutePrecipitationGraphView graph = new MinutePrecipitationGraphView(
                this, visible, zone, windowStart, windowEnd);
        graph.setPreferredSelection(minuteSelectedTimeMillis);
        graph.setSelectionListener((segment, selectedMillis) -> {
            minuteSelectedTimeMillis = selectedMillis;
            Instant selectedInstant = Instant.ofEpochMilli(selectedMillis);
            selectedTime.setText(formatTime(selectedInstant, zone));
            selectedRate.setText(minuteRateDisplay(segment));
            selectedChance.setText(segment.probability == null ? "—" : segment.probability + "%");
            selectedType.setText(UiTranslations.text(this, minuteTypeIntensityLabel(segment)));
            String detail = localizedMinuteSelectionDetail(this, segment, selectedInstant, zone);
            metricRow.setContentDescription(UiTranslations.text(this, "Selected precipitation. ") + detail);
        });

        LinearLayout.LayoutParams graphLp = new LinearLayout.LayoutParams(-1, dp(205));
        graphLp.topMargin = dp(3);
        graphBody.addView(graph, graphLp);
        addMinutePageCard(page, graphBody, 10);
    }

    LinearLayout minuteMetricCell(String label, TextView value) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        cell.setPadding(dp(4), dp(2), dp(4), dp(2));
        TextView caption = text(label, 9, true, FAINT_WHITE);
        caption.setLetterSpacing(0.08f);
        cell.addView(caption, new LinearLayout.LayoutParams(-1, dp(18)));
        value.setSingleLine(false);
        cell.addView(value, new LinearLayout.LayoutParams(-1, -2));
        return cell;
    }

    void addMinuteRangeSelector(LinearLayout page) {
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.HORIZONTAL);
        holder.setGravity(Gravity.CENTER);
        holder.setPadding(dp(5), dp(4), dp(5), dp(4));
        holder.setBackground(newGlassDrawable(dp(22), true));

        TextView two = dailyModeButton("2h", "Two hour precipitation range");
        TextView six = dailyModeButton("6h", "Six hour precipitation range");
        updateDailyModeButton(two, minuteRangeHours == MINUTE_RANGE_TWO_HOURS, "Two hour precipitation range");
        updateDailyModeButton(six, minuteRangeHours == MINUTE_RANGE_SIX_HOURS, "Six hour precipitation range");
        two.setOnClickListener(v -> setMinuteRangeHours(MINUTE_RANGE_TWO_HOURS));
        six.setOnClickListener(v -> setMinuteRangeHours(MINUTE_RANGE_SIX_HOURS));
        holder.addView(two, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams sixLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        sixLp.leftMargin = dp(4);
        holder.addView(six, sixLp);
        LinearLayout.LayoutParams holderLp = new LinearLayout.LayoutParams(-1, dp(56));
        holderLp.topMargin = dp(12);
        page.addView(holder, holderLp);
    }

    void setMinuteRangeHours(int hours) {
        if (hours != MINUTE_RANGE_TWO_HOURS && hours != MINUTE_RANGE_SIX_HOURS) return;
        if (minuteRangeHours == hours) return;
        minuteRangeHours = hours;
        final int scrollY = mainScroll == null ? 0 : mainScroll.getScrollY();
        View oldBody = precipitationBody;
        if (!animationsAllowed() || oldBody == null) {
            renderPrecipitationContent();
            if (mainScroll != null) mainScroll.post(() -> mainScroll.scrollTo(0, scrollY));
            return;
        }
        oldBody.animate().cancel();
        oldBody.animate()
                .alpha(0f)
                .setDuration(120L)
                .withEndAction(() -> {
                    if (!precipitationMode) return;
                    renderPrecipitationContent();
                    View newBody = precipitationBody;
                    if (newBody != null) {
                        newBody.setAlpha(0f);
                        newBody.animate().alpha(1f).setDuration(150L).start();
                    }
                    if (mainScroll != null) {
                        mainScroll.post(() -> mainScroll.scrollTo(0, scrollY));
                    }
                })
                .start();
    }

    void addMinutePageCard(LinearLayout page, View child, int topMarginDp) {
        View panel = card(child, dp(24));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(topMarginDp);
        page.addView(panel, lp);
    }

    void addMinuteAttribution(LinearLayout page) {
        boolean openMeteo = OpenMeteoConfig.isPrecipitationOpenMeteo(this);
        MinuteForecastState state = minuteForecastStateForCurrentScope();
        JSONObject model = state == null || state.response == null ? null : state.response.optJSONObject("_openMeteo");
        String modelLabel = model == null ? OpenMeteoConfig.modelLabel(
                PrecipitationModelPolicy.resolve(OpenMeteoConfig.model(this), latitude, longitude))
                : model.optString("modelLabel", "Best Match");
        addDataAttribution(page, openMeteo, true, false, false,
                "Open-Meteo · " + modelLabel + " · 15 min");
    }

    void addDataAttribution(LinearLayout page, boolean openMeteo, boolean weather,
            boolean airQuality, boolean pollen, String openMeteoLabel) {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(dp(4), dp(12), dp(4), dp(8));
        if (openMeteo) {
            String label = openMeteoLabel == null ? "Open-Meteo / CAMS ENSEMBLE" : openMeteoLabel;
            StringBuilder notice = new StringBuilder(label).append(" · CC BY 4.0");
            if (weather && (airQuality || pollen)) notice.append("\nOpen-Meteo / CAMS ENSEMBLE");
            notice.append("\nhttps://open-meteo.com/");
            TextView attribution = text(notice.toString(), 12, false, SOFT_WHITE);
            attribution.setGravity(Gravity.CENTER);
            attribution.setLinkTextColor(Color.WHITE);
            android.text.util.Linkify.addLinks(attribution, android.text.util.Linkify.WEB_URLS);
            footer.addView(attribution);
        } else {
            // Keep the existing Google source credits under the attribution transition:
            // https://developers.google.com/maps/documentation/weather/policies
            StringBuilder notice = new StringBuilder();
            if (weather) appendGoogleSource(notice, "Includes weather data from Google");
            if (airQuality) appendGoogleSource(notice, "Includes air quality data from Google");
            if (pollen) appendGoogleSource(notice, "Includes pollen data from Google");
            TextView attribution = text(notice.toString(), 12, false, SOFT_WHITE);
            attribution.setGravity(Gravity.CENTER);
            footer.addView(attribution);
        }
        page.addView(footer, new LinearLayout.LayoutParams(-1, -2));
    }

    private void appendGoogleSource(StringBuilder notice, String dataset) {
        if (notice.length() > 0) notice.append('\n');
        notice.append(UiTranslations.text(this, "Source")).append(": ")
                .append(UiTranslations.text(this, dataset));
    }

    static ZoneId minuteResponseZone(JSONObject response) {
        String id = timeZoneId(response);
        if (!id.isEmpty()) {
            try {
                return ZoneId.of(id);
            } catch (Exception ignored) { }
        }
        return ZoneId.systemDefault();
    }

    static ArrayList<MinuteSegment> minuteSegments(
            JSONObject response,
            Instant now,
            int rangeHours) {
        ArrayList<MinuteSegment> output = new ArrayList<>();
        if (response == null) return output;
        JSONArray source = response.optJSONArray("segments");
        if (source == null) return output;
        Instant cutoff = now == null ? Instant.now() : now;
        Instant rangeEnd = rangeHours > 0 ? cutoff.plus(Duration.ofHours(rangeHours)) : null;
        for (int i = 0; i < source.length(); i++) {
            JSONObject raw = source.optJSONObject(i);
            MinuteSegment segment = minuteSegment(raw);
            if (segment == null || !segment.end.isAfter(cutoff)) continue;
            if (rangeEnd != null && PrecipitationWindow.overlap(
                    segment.start.toEpochMilli(), segment.end.toEpochMilli(),
                    cutoff.toEpochMilli(), rangeEnd.toEpochMilli()) == 0) continue;
            output.add(segment);
        }
        output.sort((a, b) -> a.start.compareTo(b.start));
        return output;
    }

    static MinuteSegment minuteSegment(JSONObject raw) {
        if (raw == null) return null;
        JSONObject frame = raw.optJSONObject("timeFrame");
        Instant start = parseInstant(frame == null ? "" : frame.optString("startTime", ""));
        Instant end = parseInstant(frame == null ? "" : frame.optString("endTime", ""));
        if (start == null || end == null || !end.isAfter(start)) return null;
        Integer probability = null;
        if (raw.has("probability") && !raw.isNull("probability")) {
            try { probability = raw.getInt("probability"); } catch (Exception ignored) { }
        }
        JSONObject qpf = raw.optJSONObject("qpf");
        JSONObject snowfall = raw.optJSONObject("snowfallAmount");
        return new MinuteSegment(
                raw,
                start,
                end,
                probability,
                optionalJsonNumber(qpf, "quantity"),
                qpf == null ? "" : qpf.optString("unit", ""),
                optionalJsonNumber(snowfall, "quantity"),
                snowfall == null ? "" : snowfall.optString("unit", ""),
                raw.has("type") && !raw.isNull("type") ? raw.optString("type", "") : "",
                raw.has("intensity") && !raw.isNull("intensity") ? raw.optString("intensity", "") : "");
    }

    static Double optionalJsonNumber(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) return null;
        try {
            return object.getDouble(key);
        } catch (Exception ignored) {
            return null;
        }
    }

    static String minuteSelectionDetail(
            MinuteSegment segment,
            Instant selectedTime,
            ZoneId zone) {
        if (segment == null) return "No selected segment";
        StringBuilder detail = new StringBuilder();
        detail.append(formatTime(selectedTime == null ? segment.start : selectedTime, zone));
        appendPart(detail, "returned interval " + formatTime(segment.start, zone)
                + " to " + formatTime(segment.end, zone));
        Double rate = minuteRateMmPerHour(segment);
        if (rate != null) {
            appendPart(detail, "Rate " + formatMinuteRate(rate) + " millimeters per hour");
        } else {
            appendPart(detail, "Rate unavailable");
        }
        if (segment.probability != null) {
            appendPart(detail, "Chance " + segment.probability + "%");
        }
        String typeIntensity = minuteTypeIntensityLabel(segment);
        if (!"—".equals(typeIntensity)) appendPart(detail, typeIntensity);
        if (segment.snowfallQuantity != null) {
            appendPart(detail, "Snowfall " + formatMinuteQuantity(segment.snowfallQuantity)
                    + minuteUnitLabel(segment.snowfallUnit));
        }
        return detail.toString();
    }

    static Double minuteRateMmPerHour(MinuteSegment segment) {
        if (segment == null || segment.qpfQuantity == null) return null;
        long durationMillis;
        try {
            durationMillis = Duration.between(segment.start, segment.end).toMillis();
        } catch (Exception ignored) {
            return null;
        }
        if (durationMillis <= 0L) return null;

        String unit = segment.qpfUnit == null
                ? "" : segment.qpfUnit.trim().toUpperCase(Locale.ROOT);
        double millimeters;
        if ("MILLIMETERS".equals(unit) || "MILLIMETER".equals(unit) || "MM".equals(unit)) {
            millimeters = segment.qpfQuantity;
        } else if ("INCHES".equals(unit) || "INCH".equals(unit) || "IN".equals(unit)) {
            millimeters = segment.qpfQuantity * 25.4d;
        } else {
            return null;
        }
        if (!Double.isFinite(millimeters)) return null;
        double rate = Math.max(0d, millimeters) * 3_600_000d / durationMillis;
        return Double.isFinite(rate) ? rate : null;
    }

    static String minuteRateDisplay(MinuteSegment segment) {
        Double rate = minuteRateMmPerHour(segment);
        return rate == null ? "— mm/h" : formatMinuteRate(rate) + " mm/h";
    }

    static String formatMinuteRate(double rate) {
        double safe = Math.max(0d, rate);
        String text = String.format(Locale.US, safe < 10d ? "%.2f" : "%.1f", safe);
        while (text.contains(".") && text.endsWith("0")) text = text.substring(0, text.length() - 1);
        if (text.endsWith(".")) text = text.substring(0, text.length() - 1);
        return text;
    }

    static String minuteTypeIntensityLabel(MinuteSegment segment) {
        if (segment == null) return "—";
        String type = segment.type == null || segment.type.trim().isEmpty()
                ? "" : prettyEnum(segment.type);
        String intensity = segment.intensity == null || segment.intensity.trim().isEmpty()
                ? "" : prettyEnum(segment.intensity);
        if (type.isEmpty() && intensity.isEmpty()) return "—";
        if (type.isEmpty()) return intensity;
        if (intensity.isEmpty()) return type;
        return type + " · " + intensity;
    }

    static String formatMinuteQuantity(double value) {
        String text = String.format(Locale.US, "%.3f", value);
        while (text.contains(".") && (text.endsWith("0") || text.endsWith("."))) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    static String minuteUnitLabel(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String unit = raw.trim().toUpperCase(Locale.ROOT);
        if ("MILLIMETERS".equals(unit)) return " mm";
        if ("INCHES".equals(unit)) return " in";
        return " " + prettyEnum(raw);
    }


}
