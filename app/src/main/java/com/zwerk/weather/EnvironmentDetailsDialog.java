package com.zwerk.weather;

import android.app.Dialog;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Air-quality and pollen details dialog and provider-aware optional-data help dialog. */
final class EnvironmentDetailsDialog {
    private final WeatherOverviewRenderingActivity host;

    private EnvironmentDetailsDialog(WeatherOverviewRenderingActivity host) {
        this.host = host;
    }

    static void show(WeatherOverviewRenderingActivity host, String label,
            boolean airQuality, OptionalDataState state) {
        new EnvironmentDetailsDialog(host).showDetails(label, airQuality, state);
    }

    static void showHelp(WeatherOverviewRenderingActivity host, String label,
            boolean airQuality, OptionalDataState state) {
        new EnvironmentDetailsDialog(host).showHelpDialog(label, airQuality, state);
    }

    static String aqiDisplayValue(JSONObject index) {
        if (index == null) return "";
        String display = WeatherActivityFoundation.stringValue(index, "aqiDisplay");
        if (!display.isEmpty()) return display;
        Double value = WeatherActivityFoundation.numberValue(index, "aqi");
        return value == null ? "" : Integer.toString((int) Math.round(value));
    }

    static JSONObject universalAqiIndex(JSONArray indexes) {
        if (indexes == null) return null;
        for (int i = 0; i < indexes.length(); i++) {
            JSONObject candidate = indexes.optJSONObject(i);
            if (candidate == null) continue;
            if ("uaqi".equalsIgnoreCase(WeatherActivityFoundation.stringValue(candidate, "code"))) {
                return candidate;
            }
        }
        return null;
    }

    private void showDetails(String label, boolean airQuality, OptionalDataState state) {
        Dialog dialog = new Dialog(host);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        ScrollView scroller = new ScrollView(host);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setFillViewport(false);
        scroller.setClipToPadding(false);
        scroller.setAccessibilityPaneTitle(UiTranslations.text(host, label) + " "
                + UiTranslations.text(host, "details"));

        int horizontalPadding = host.getResources().getConfiguration().screenWidthDp < 360
                ? dp(14) : dp(18);
        LinearLayout panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(horizontalPadding, dp(17), horizontalPadding, dp(16));
        panel.setBackground(environmentDialogBackground());
        scroller.addView(panel, new ScrollView.LayoutParams(-1, -2));

        LinearLayout titleRow = new LinearLayout(host);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(label, 22, true, WeatherActivityFoundation.WHITE);
        title.setAccessibilityHeading(true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        boolean openMeteo = state.details != null
                && state.details.optBoolean("_openMeteo", false);
        TextView source = environmentBadge(openMeteo ? "OPEN-METEO" : "GOOGLE",
                environmentAccentColor());
        titleRow.addView(source);
        panel.addView(titleRow);
        TextView subtitle = text(airQuality
                ? "Current conditions and hourly outlook"
                : "Daily levels and pollen types", 11, false, WeatherActivityFoundation.SOFT_WHITE);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(-1, -2);
        subtitleLp.topMargin = dp(3);
        subtitleLp.bottomMargin = dp(8);
        panel.addView(subtitle, subtitleLp);

        int sceneAccent = environmentAccentColor();

        if (openMeteo) {
            if (airQuality) addOpenMeteoAirQualityDetails(panel, state.details);
            else addOpenMeteoPollenDetails(panel, state.details);
        } else if (airQuality) {
            addAirQualityDialogContent(panel, state.details);
        } else {
            addPollenDialogContent(panel, state.details);
        }

        host.addDataAttribution(panel, openMeteo, false, airQuality, !airQuality, null);

        Button close = host.button("Close");
        close.setContentDescription(UiTranslations.text(host, "Close") + " "
                + UiTranslations.text(host, label) + " "
                + UiTranslations.text(host, "details"));
        GradientDrawable closeBackground = host.roundedBg(Color.argb(
                44, Color.red(sceneAccent), Color.green(sceneAccent), Color.blue(sceneAccent)),
                dp(22));
        closeBackground.setStroke(dp(1), Color.argb(
                76, Color.red(sceneAccent), Color.green(sceneAccent), Color.blue(sceneAccent)));
        close.setBackground(closeBackground);
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(-1, dp(44));
        closeLp.topMargin = dp(13);
        panel.addView(close, closeLp);

        dialog.setOnDismissListener(ignored -> host.removePageGlassDrawables(scroller));
        dialog.setContentView(scroller);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.72f;
            window.setAttributes(attributes);
            window.getDecorView().setPadding(0, 0, 0, 0);
            int screenWidth = host.getResources().getDisplayMetrics().widthPixels;
            int screenHeight = host.getResources().getDisplayMetrics().heightPixels;
            int dialogWidth = Math.max(1, Math.min(screenWidth - dp(20), dp(480)));
            int dialogHeight = Math.max(1, Math.min(screenHeight - dp(36), dp(760)));
            window.setLayout(dialogWidth, dialogHeight);
        }
    }

    private void showHelpDialog(String label, boolean airQuality, OptionalDataState state) {
        Dialog dialog = new Dialog(host);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        ScrollView scroller = new ScrollView(host);
        scroller.setVerticalScrollBarEnabled(false);

        LinearLayout panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(20), dp(20), dp(18));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(250, 12, 23, 39));
        background.setCornerRadius(dp(24));
        background.setStroke(dp(1), Color.argb(58, 255, 255, 255));
        panel.setBackground(background);
        scroller.addView(panel, new ScrollView.LayoutParams(-1, -2));

        boolean openMeteo = OpenMeteoConfig.isOpenMeteo(host)
                || (state != null && state.details != null
                        && state.details.optBoolean("_openMeteo", false));
        boolean keyBlocked = !openMeteo && state != null && "Key blocked".equals(state.value);
        String helpTitle = airQuality
                ? (keyBlocked ? "Air quality needs key access" : "Air quality help")
                : (keyBlocked ? "Pollen needs key access" : "Pollen help");
        scroller.setAccessibilityPaneTitle(UiTranslations.text(host, helpTitle));
        TextView title = text(helpTitle, 22, false, WeatherActivityFoundation.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(title);

        String message;
        if (openMeteo) {
            message = airQuality
                    ? UiTranslations.text(host,
                            "Open-Meteo air quality data is unavailable for this location right now. Check your connection and pull down to refresh, or review your provider settings.")
                    : UiTranslations.text(host,
                            "Open-Meteo pollen forecasts cover Europe only.")
                            + "\n\n" + UiTranslations.text(host,
                                    "For locations outside Europe, or when no readings are returned, pull down to refresh later or switch weather provider in Settings.");
        } else {
            message = keyBlocked
                    ? UiTranslations.text(host,
                            "Google returned API_KEY_SERVICE_BLOCKED. The service is either not enabled for this key's project or this credential is not allowed to call it.")
                            + "\n\n" + UiTranslations.text(host, "Fix it in Google Cloud:")
                            + "\n" + UiTranslations.text(host,
                                    "1. Open APIs & Services → Credentials.")
                            + "\n" + UiTranslations.text(host, "2. Select this API key.")
                            + "\n" + UiTranslations.text(host,
                                    "3. Under API restrictions, choose Restrict key.")
                            + "\n" + UiTranslations.text(host,
                                    "4. Enable the needed APIs, then add Weather API, Air Quality API, and Pollen API under API restrictions.")
                            + "\n" + UiTranslations.text(host,
                                    "5. Save, wait a few minutes, then pull down to refresh.")
                    : UiTranslations.text(host,
                            "No usable data was returned. Check that the API is enabled, allowed in this key's API restrictions, and available for the selected location. Then refresh.");
        }
        TextView explanation = text(message, 13, false, WeatherActivityFoundation.SOFT_WHITE);
        explanation.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams explanationLp = new LinearLayout.LayoutParams(-1, -2);
        explanationLp.topMargin = dp(7);
        panel.addView(explanation, explanationLp);

        if (!openMeteo) {
            TextView identity = text(host.androidRestrictionIdentity(), 11, false,
                    WeatherActivityFoundation.FAINT_WHITE);
            identity.setTextIsSelectable(true);
            LinearLayout.LayoutParams identityLp = new LinearLayout.LayoutParams(-1, -2);
            identityLp.topMargin = dp(12);
            panel.addView(identity, identityLp);

            Button credentials = host.coordinateDialogButton("Open key settings ↗", false);
            credentials.setOnClickListener(v -> host.openExternalUrl(
                    WeatherActivityFoundation.GOOGLE_CLOUD_CREDENTIALS_URL));
            LinearLayout.LayoutParams credentialsLp = new LinearLayout.LayoutParams(-1, dp(46));
            credentialsLp.topMargin = dp(14);
            panel.addView(credentials, credentialsLp);

            Button docs = host.coordinateDialogButton("Setup guide ↗", false);
            docs.setOnClickListener(v -> host.openExternalUrl(
                    airQuality ? WeatherActivityFoundation.AIR_QUALITY_GUIDE_URL
                            : WeatherActivityFoundation.POLLEN_GUIDE_URL));
            LinearLayout.LayoutParams docsLp = new LinearLayout.LayoutParams(-1, dp(46));
            docsLp.topMargin = dp(8);
            panel.addView(docs, docsLp);
        }

        Button close = host.coordinateDialogButton("Done", true);
        close.setContentDescription(UiTranslations.text(host, "Done") + " "
                + UiTranslations.text(host, label));
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
            int width = host.getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.max(1, Math.min(width - dp(30), dp(430))),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private GradientDrawable environmentDialogBackground() {
        String scene = host.displayedScene == null ? "" : host.displayedScene;
        int top = "night".equals(scene) ? Color.rgb(27, 51, 89)
                : "rain".equals(scene) || "thunder".equals(scene)
                ? Color.rgb(43, 67, 91) : Color.rgb(45, 93, 151);
        int bottom = "night".equals(scene) ? Color.rgb(12, 28, 55)
                : "rain".equals(scene) || "thunder".equals(scene)
                ? Color.rgb(24, 42, 65) : Color.rgb(24, 57, 102);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        background.setCornerRadius(dp(26));
        background.setStroke(dp(1), Color.argb(78, 255, 255, 255));
        return background;
    }

    private void addOpenMeteoAirQualityDetails(LinearLayout panel, JSONObject details) {
        JSONObject raw = details.optJSONObject("raw");
        JSONObject current = raw == null ? null : raw.optJSONObject("current");
        JSONObject hourly = raw == null ? null : raw.optJSONObject("hourly");
        Double european = OpenMeteoEnvironmentClient.number(current, "european_aqi");
        Double us = OpenMeteoEnvironmentClient.number(current, "us_aqi");
        boolean useEuropean = european != null;
        Double score = useEuropean ? european : us;
        String scale = useEuropean ? "European AQI" : "US AQI";
        String category = score == null ? "Unavailable" : useEuropean
                ? OpenMeteoEnvironmentClient.europeanCategory((int) Math.round(score))
                : OpenMeteoEnvironmentClient.usCategory((int) Math.round(score));
        panel.addView(airQualityHero(score == null ? "—" : Long.toString(Math.round(score)),
                category, scale, "", airQualityCategoryColor(category)));

        String[] keys = {"pm2_5", "pm10", "ozone", "nitrogen_dioxide",
                "sulphur_dioxide", "carbon_monoxide"};
        String[] labels = {"PM2.5", "PM10", "Ozone", "Nitrogen dioxide",
                "Sulphur dioxide", "Carbon monoxide"};
        ArrayList<View> pollutants = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            Double amount = OpenMeteoEnvironmentClient.number(current, keys[i]);
            if (amount != null) {
                pollutants.add(environmentMetricRow(labels[i],
                        OpenMeteoEnvironmentClient.format(amount) + " µg/m³"));
            }
        }
        if (!pollutants.isEmpty()) {
            panel.addView(sectionHeading("POLLUTANTS"));
            addEnvironmentResponsiveCards(panel, pollutants);
        }

        JSONArray times = hourly == null ? null : hourly.optJSONArray("time");
        String currentTime = current == null ? "" : current.optString("time", "");
        if (times == null) return;
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int count = 0;
        String aqiKey = useEuropean ? "european_aqi" : "us_aqi";
        for (int i = 0; i < times.length() && count < 24; i++) {
            String time = times.optString(i, "");
            if (!currentTime.isEmpty() && time.compareTo(currentTime) < 0) continue;
            Double value = OpenMeteoEnvironmentClient.numberAt(hourly, aqiKey, i);
            if (value == null) continue;
            int rounded = (int) Math.round(value);
            JSONObject index = new JSONObject();
            try {
                index.put("aqi", rounded);
                index.put("category", useEuropean
                        ? OpenMeteoEnvironmentClient.europeanCategory(rounded)
                        : OpenMeteoEnvironmentClient.usCategory(rounded));
            } catch (Exception ignored) { }
            String label = time.length() >= 16 ? time.substring(11, 16) : time;
            row.addView(airQualityForecastCell(label, index));
            count++;
        }
        if (count > 0) {
            panel.addView(sectionHeading("NEXT 24 HOURS"));
            HorizontalScrollView scroller = host.new GestureHorizontalScrollView(host);
            scroller.setHorizontalScrollBarEnabled(false);
            scroller.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
            panel.addView(scroller, new LinearLayout.LayoutParams(-1, dp(118)));
        }
    }

    private void addOpenMeteoPollenDetails(LinearLayout panel, JSONObject details) {
        JSONObject raw = details.optJSONObject("raw");
        JSONObject hourly = raw == null ? null : raw.optJSONObject("hourly");
        JSONArray times = hourly == null ? null : hourly.optJSONArray("time");
        if (times == null || times.length() == 0) {
            panel.addView(environmentDetailBlock("Pollen", "No forecast available."));
            return;
        }
        String[] keys = OpenMeteoEnvironmentClient.pollenKeys();
        String[] labels = OpenMeteoEnvironmentClient.pollenLabels();
        LinkedHashMap<String, double[]> days = new LinkedHashMap<>();
        for (int i = 0; i < times.length(); i++) {
            String time = times.optString(i, "");
            if (time.length() < 10) continue;
            String date = time.substring(0, 10);
            double[] maxima = days.get(date);
            if (maxima == null) {
                if (days.size() >= 4) break;
                maxima = new double[keys.length];
                Arrays.fill(maxima, Double.NaN);
                days.put(date, maxima);
            }
            for (int j = 0; j < keys.length; j++) {
                Double value = OpenMeteoEnvironmentClient.numberAt(hourly, keys[j], i);
                if (value != null && (Double.isNaN(maxima[j]) || value > maxima[j])) {
                    maxima[j] = value;
                }
            }
        }
        Locale locale = AppLocaleManager.activeLocale(host);
        int dayIndex = 0;
        for (Map.Entry<String, double[]> day : days.entrySet()) {
            String label = day.getKey();
            try {
                label = AppLocaleManager.capitalize(
                        LocalDate.parse(label).format(DateTimeFormatter.ofPattern("EEE d MMM", locale)),
                        locale);
            } catch (Exception ignored) { }
            double[] maxima = day.getValue();
            double peak = Double.NaN;
            String dominant = "";
            for (int i = 0; i < keys.length; i++) {
                if (!Double.isNaN(maxima[i]) && (Double.isNaN(peak) || maxima[i] > peak)) {
                    peak = maxima[i];
                    dominant = labels[i];
                }
            }
            LinearLayout dayCard = new LinearLayout(host);
            dayCard.setOrientation(LinearLayout.VERTICAL);
            dayCard.setPadding(dp(13), dp(12), dp(13), dp(12));
            dayCard.setBackground(host.newEnvironmentalGlassDrawable(dp(19), false));
            LinearLayout header = new LinearLayout(host);
            header.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout copy = new LinearLayout(host);
            copy.setOrientation(LinearLayout.VERTICAL);
            TextView dateView = text(label, 16, true, WeatherActivityFoundation.WHITE);
            dateView.setAccessibilityHeading(true);
            copy.addView(dateView);
            TextView summary = text(dominant.isEmpty()
                    ? "No pollen reading" : UiTranslations.text(host, "Highest reading")
                            + " · " + UiTranslations.text(host, dominant),
                    10, false, WeatherActivityFoundation.SOFT_WHITE);
            summary.setPadding(0, dp(2), 0, 0);
            copy.addView(summary);
            TextView toggleHint = text(dayIndex == 0 ? "Hide details ↑" : "Show details ↓",
                    10, false, environmentAccentColor());
            toggleHint.setPadding(0, dp(4), 0, 0);
            copy.addView(toggleHint);
            header.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
            header.addView(environmentBadge(Double.isNaN(peak) ? "No data"
                    : OpenMeteoEnvironmentClient.format(peak) + " "
                            + UiTranslations.text(host, "grains/m³"),
                    environmentAccentColor()));
            dayCard.addView(header);

            LinearLayout dayDetails = new LinearLayout(host);
            dayDetails.setOrientation(LinearLayout.VERTICAL);
            dayCard.addView(dayDetails);
            ArrayList<View> cards = new ArrayList<>();
            for (int i = 0; i < keys.length; i++) {
                if (!Double.isNaN(maxima[i])) {
                    cards.add(environmentMetricRow(labels[i],
                            OpenMeteoEnvironmentClient.format(maxima[i]) + " "
                                    + UiTranslations.text(host, "grains/m³")));
                }
            }
            if (cards.isEmpty()) {
                dayDetails.addView(environmentDetailBlock("Pollen", "No data."));
            } else {
                LinearLayout.LayoutParams detailsLp = new LinearLayout.LayoutParams(-1, -2);
                detailsLp.topMargin = dp(10);
                LinearLayout metrics = new LinearLayout(host);
                metrics.setOrientation(LinearLayout.VERTICAL);
                dayDetails.addView(metrics, detailsLp);
                addEnvironmentResponsiveCards(metrics, cards);
            }
            dayDetails.setVisibility(dayIndex == 0 ? View.VISIBLE : View.GONE);
            String dayDescription = label + (Double.isNaN(peak)
                    ? ", " + UiTranslations.text(host, "no data")
                    : ", " + UiTranslations.text(host, "highest reading") + " "
                            + OpenMeteoEnvironmentClient.format(peak) + " "
                            + UiTranslations.text(host, "grains per cubic meter") + ", "
                            + UiTranslations.text(host, dominant));
            header.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            header.setAccessibilityHeading(true);
            host.makeChildrenUnimportant(header);
            header.setClickable(true);
            header.setFocusable(true);
            header.setContentDescription(dayDescription + ", "
                    + UiTranslations.text(host, dayIndex == 0
                            ? "tap to hide details" : "tap to show details"));
            header.setOnClickListener(v -> {
                boolean expanded = dayDetails.getVisibility() != View.VISIBLE;
                dayDetails.setVisibility(expanded ? View.VISIBLE : View.GONE);
                toggleHint.setText(UiTranslations.text(host,
                        expanded ? "Hide details ↑" : "Show details ↓"));
                header.setContentDescription(dayDescription + ", "
                        + UiTranslations.text(host, expanded
                                ? "tap to hide details" : "tap to show details"));
            });
            LinearLayout.LayoutParams dayLp = new LinearLayout.LayoutParams(-1, -2);
            dayLp.topMargin = dp(dayIndex == 0 ? 6 : 9);
            panel.addView(dayCard, dayLp);
            dayIndex++;
        }
    }

    private void addAirQualityDialogContent(LinearLayout panel, JSONObject details) {
        JSONObject current = details == null ? null : details.optJSONObject("current");
        JSONObject index = universalAqiIndex(current == null ? null : current.optJSONArray("indexes"));
        panel.addView(airQualityHero(index));

        JSONObject recommendations = current == null
                ? null : current.optJSONObject("healthRecommendations");
        View guidance = airQualityGuidanceCard(recommendations);
        if (guidance != null) {
            panel.addView(sectionHeading("HEALTH GUIDANCE"));
            panel.addView(guidance);
        }

        JSONArray pollutants = current == null ? null : current.optJSONArray("pollutants");
        if (pollutants != null) {
            ArrayList<View> pollutantCards = new ArrayList<>();
            for (int i = 0; i < pollutants.length(); i++) {
                JSONObject pollutant = pollutants.optJSONObject(i);
                JSONObject concentration = pollutant == null
                        ? null : pollutant.optJSONObject("concentration");
                Double concentrationValue = WeatherActivityFoundation.numberValue(concentration, "value");
                if (pollutant == null || concentrationValue == null) continue;
                String pollutantName = WeatherActivityFoundation.firstNonEmpty(
                        WeatherActivityFoundation.stringValue(pollutant, "displayName"),
                        WeatherActivityFoundation.stringValue(pollutant, "code").toUpperCase(Locale.ROOT));
                String units = environmentConcentrationUnit(
                        WeatherActivityFoundation.stringValue(concentration, "units"));
                String value = WeatherActivityFoundation.trimNumber(concentrationValue)
                        + (units.isEmpty() ? "" : " " + units);
                View metric = environmentMetricRow(pollutantName, value);
                String code = WeatherActivityFoundation.stringValue(pollutant, "code").toUpperCase(Locale.ROOT);
                metric.setContentDescription((pollutantName.isEmpty() ? code : pollutantName)
                        + ", " + value);
                pollutantCards.add(metric);
            }
            if (!pollutantCards.isEmpty()) {
                panel.addView(sectionHeading("POLLUTANTS"));
                addEnvironmentResponsiveCards(panel, pollutantCards);
            }
        }

        JSONObject forecast = details == null ? null : details.optJSONObject("forecast");
        JSONArray hours = forecast == null ? null : forecast.optJSONArray("hourlyForecasts");
        if (hours == null || hours.length() == 0) {
            panel.addView(sectionHeading("NEXT 24 HOURS"));
            panel.addView(environmentDetailBlock(
                    "Hourly outlook", "No hourly forecast was returned."));
            return;
        }

        panel.addView(sectionHeading("NEXT 24 HOURS"));

        ZoneId zone = WeatherActivityFoundation.responseZone(
                host.lastCurrentWeather, host.lastHourlyWeather, host.lastDailyWeather);
        HorizontalScrollView scroller = host.new GestureHorizontalScrollView(host);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        scroller.setContentDescription(
                "24-hour air quality forecast. Swipe horizontally for later hours.");
        LinearLayout forecastRow = new LinearLayout(host);
        forecastRow.setOrientation(LinearLayout.HORIZONTAL);
        forecastRow.setPadding(0, dp(1), dp(2), dp(4));
        for (int i = 0; i < hours.length(); i++) {
            JSONObject hour = hours.optJSONObject(i);
            if (hour == null) continue;
            JSONObject hourIndex = universalAqiIndex(hour.optJSONArray("indexes"));
            String time = formatEnvironmentalHour(
                    WeatherActivityFoundation.stringValue(hour, "dateTime"), zone);
            forecastRow.addView(airQualityForecastCell(time, hourIndex));
        }
        scroller.addView(forecastRow, new HorizontalScrollView.LayoutParams(-2, -2));
        panel.addView(scroller, new LinearLayout.LayoutParams(-1, dp(118)));
    }

    private View airQualityGuidanceCard(JSONObject recommendations) {
        if (recommendations == null) return null;
        String[][] guidance = new String[][]{
                {"General guidance", WeatherActivityFoundation.stringValue(recommendations, "generalPopulation")}
        };
        boolean hasGuidance = false;
        for (String[] row : guidance) {
            if (!row[1].isEmpty()) {
                hasGuidance = true;
                break;
            }
        }
        if (!hasGuidance) return null;

        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(11), dp(13), dp(11));
        card.setBackground(host.newEnvironmentalGlassDrawable(dp(17), true));
        StringBuilder accessibility = new StringBuilder("Health guidance. ");
        boolean added = false;
        for (String[] row : guidance) {
            if (row[1].isEmpty()) continue;
            if (added) {
                View divider = environmentDivider();
                LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(-1, dp(1));
                dividerLp.topMargin = dp(9);
                dividerLp.bottomMargin = dp(9);
                card.addView(divider, dividerLp);
                accessibility.append(". ");
            }
            card.addView(environmentGuidanceRow(row[0], row[1]));
            accessibility.append(row[0]).append(": ").append(row[1]);
            added = true;
        }
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        card.setContentDescription(accessibility.toString());
        host.makeChildrenUnimportant(card);
        return card;
    }

    private View environmentGuidanceRow(String titleValue, String bodyValue) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(titleValue, 11, true, environmentAccentColor());
        row.addView(title);
        TextView body = text(bodyValue, 12, false, WeatherActivityFoundation.SOFT_WHITE);
        body.setLineSpacing(0f, 1.08f);
        body.setPadding(0, dp(3), 0, 0);
        row.addView(body);
        return row;
    }

    private void addEnvironmentResponsiveCards(LinearLayout panel, ArrayList<View> cards) {
        if (panel == null || cards == null || cards.isEmpty()) return;
        Configuration configuration = host.getResources().getConfiguration();
        boolean twoColumns = configuration.screenWidthDp >= 360
                && configuration.fontScale <= 1.2f;
        if (!twoColumns) {
            for (int i = 0; i < cards.size(); i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) lp.topMargin = dp(6);
                panel.addView(cards.get(i), lp);
            }
            return;
        }

        for (int i = 0; i < cards.size();) {
            int remaining = cards.size() - i;
            if (remaining == 1) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) lp.topMargin = dp(6);
                panel.addView(cards.get(i), lp);
                i++;
                continue;
            }
            LinearLayout row = new LinearLayout(host);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBaselineAligned(false);
            LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, -2, 1f);
            leftLp.rightMargin = dp(3);
            LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, -2, 1f);
            rightLp.leftMargin = dp(3);
            row.addView(cards.get(i), leftLp);
            row.addView(cards.get(i + 1), rightLp);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) rowLp.topMargin = dp(6);
            panel.addView(row, rowLp);
            i += 2;
        }
    }

    private void addPollenDialogContent(LinearLayout panel, JSONObject details) {
        JSONArray days = details == null ? null : details.optJSONArray("dailyInfo");
        if (days == null || days.length() == 0) {
            panel.addView(environmentDetailBlock(
                    "Five-day outlook", "No pollen forecast was returned."));
            return;
        }
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            if (day == null) continue;
            JSONObject date = day.optJSONObject("date");
            String dateLabel = pollenDateLabel(date, i);
            JSONArray types = day.optJSONArray("pollenTypeInfo");
            String recommendation = "";
            String recommendationType = "";
            int recommendationIndex = -1;

            int highestValue = -1;
            String highestCategory = "";
            if (types != null) {
                for (int j = 0; j < types.length(); j++) {
                    JSONObject type = types.optJSONObject(j);
                    JSONObject typeIndex = type == null ? null : type.optJSONObject("indexInfo");
                    int value = typeIndex == null ? -1 : typeIndex.optInt("value", -1);
                    if (value > highestValue) {
                        highestValue = value;
                        highestCategory = typeIndex.optString("category", "");
                    }
                }
            }

            int severityColor = pollenColor(highestValue);
            LinearLayout dayCard = new LinearLayout(host);
            dayCard.setOrientation(LinearLayout.VERTICAL);
            dayCard.setPadding(dp(13), dp(12), dp(13), dp(12));
            dayCard.setBackground(host.newEnvironmentalGlassDrawable(dp(19), false));

            LinearLayout dayHeader = new LinearLayout(host);
            dayHeader.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout headerCopy = new LinearLayout(host);
            headerCopy.setOrientation(LinearLayout.VERTICAL);
            TextView dateView = text(dateLabel, 16, true, WeatherActivityFoundation.WHITE);
            dateView.setAccessibilityHeading(true);
            headerCopy.addView(dateView);
            String dailySummary = highestValue < 0
                    ? "Daily index unavailable"
                    : WeatherActivityFoundation.firstNonEmpty(highestCategory,
                            UiTranslations.text(host, "Universal Pollen Index"))
                            + " · " + UiTranslations.text(host, "highest daily level");
            TextView summary = text(dailySummary, 10, false, WeatherActivityFoundation.SOFT_WHITE);
            summary.setPadding(0, dp(2), dp(6), 0);
            headerCopy.addView(summary);
            TextView toggleHint = text(i == 0 ? "Hide details ↑" : "Show details ↓",
                    10, false, environmentAccentColor());
            toggleHint.setPadding(0, dp(4), 0, 0);
            headerCopy.addView(toggleHint);
            dayHeader.addView(headerCopy, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView badge = environmentBadge(
                    highestValue < 0 ? "No index" : "UPI " + highestValue,
                    severityColor);
            dayHeader.addView(badge);
            String dayDescription = dateLabel + ", "
                    + (highestValue < 0
                            ? UiTranslations.text(host, "pollen index unavailable")
                            : UiTranslations.text(host, "highest Universal Pollen Index")
                                    + " " + highestValue
                                    + (highestCategory.isEmpty()
                                            ? "" : ", " + highestCategory));
            dayHeader.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            dayHeader.setAccessibilityHeading(true);
            host.makeChildrenUnimportant(dayHeader);
            dayCard.addView(dayHeader);

            LinearLayout.LayoutParams severityLp = new LinearLayout.LayoutParams(-1, dp(4));
            severityLp.topMargin = dp(9);
            dayCard.addView(environmentSeverityTrack(highestValue, 5, severityColor), severityLp);
            LinearLayout dayDetails = new LinearLayout(host);
            dayDetails.setOrientation(LinearLayout.VERTICAL);
            dayCard.addView(dayDetails);

            if (types != null && types.length() > 0) {
                dayDetails.addView(environmentMiniHeading("POLLEN TYPES"));
                boolean typeAdded = false;
                for (int j = 0; j < types.length(); j++) {
                    JSONObject type = types.optJSONObject(j);
                    if (type == null) continue;
                    String typeName = WeatherActivityFoundation.firstNonEmpty(
                            WeatherActivityFoundation.stringValue(type, "displayName"),
                            WeatherActivityFoundation.prettyEnum(
                                    WeatherActivityFoundation.stringValue(type, "code")));
                    JSONObject typeIndex = type.optJSONObject("indexInfo");
                    if (typeIndex == null) continue;
                    int value = typeIndex.optInt("value", -1);
                    String category = WeatherActivityFoundation.stringValue(typeIndex, "category");
                    if (typeAdded) dayDetails.addView(environmentDivider());
                    dayDetails.addView(pollenTypeRow(typeName, value, category));
                    typeAdded = true;

                    JSONArray recs = type.optJSONArray("healthRecommendations");
                    String candidateRecommendation = recs == null || recs.isNull(0)
                            ? "" : recs.optString(0, "").trim();
                    if (!candidateRecommendation.isEmpty() && value > recommendationIndex) {
                        recommendation = candidateRecommendation;
                        recommendationType = typeName;
                        recommendationIndex = value;
                    }
                }
            }

            JSONArray plants = day.optJSONArray("plantInfo");
            int activePlantCount = 0;
            if (plants != null) {
                ArrayList<View> plantRows = new ArrayList<>();
                for (int j = 0; j < plants.length() && plantRows.size() < 5; j++) {
                    JSONObject plant = plants.optJSONObject(j);
                    JSONObject plantIndex = plant == null ? null : plant.optJSONObject("indexInfo");
                    if (plant == null || plantIndex == null) continue;
                    int plantValue = plantIndex.optInt("value", -1);
                    boolean inSeason = plant.optBoolean("inSeason", false);
                    if (plantValue <= 0 && !inSeason) continue;
                    String plantName = WeatherActivityFoundation.firstNonEmpty(
                            WeatherActivityFoundation.stringValue(plant, "displayName"),
                            WeatherActivityFoundation.prettyEnum(
                                    WeatherActivityFoundation.stringValue(plant, "code")));
                    if (plantName.isEmpty()) continue;
                    String category = WeatherActivityFoundation.stringValue(plantIndex, "category");
                    plantRows.add(pollenPlantRow(
                            plantName, plantValue, category, inSeason));
                }
                if (!plantRows.isEmpty()) {
                    dayDetails.addView(environmentMiniHeading("ACTIVE PLANTS"));
                    for (int j = 0; j < plantRows.size(); j++) {
                        if (j > 0) dayDetails.addView(environmentDivider());
                        dayDetails.addView(plantRows.get(j));
                    }
                    activePlantCount = plantRows.size();
                }
            }

            if (!recommendation.isEmpty()) {
                dayDetails.addView(environmentInset(
                        recommendationType.isEmpty() ? "Health guidance"
                                : UiTranslations.text(host, "Health guidance") + " · "
                                        + recommendationType,
                        recommendation));
            }
            if ((types == null || types.length() == 0) && activePlantCount == 0) {
                TextView empty = text("No in-season pollen index.", 12, false,
                        WeatherActivityFoundation.SOFT_WHITE);
                LinearLayout.LayoutParams emptyLp = new LinearLayout.LayoutParams(-1, -2);
                emptyLp.topMargin = dp(10);
                dayDetails.addView(empty, emptyLp);
            }

            dayDetails.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            dayHeader.setClickable(true);
            dayHeader.setFocusable(true);
            dayHeader.setContentDescription(dayDescription + ", "
                    + UiTranslations.text(host, i == 0
                            ? "tap to hide details" : "tap to show details"));
            dayHeader.setOnClickListener(v -> {
                boolean expanded = dayDetails.getVisibility() != View.VISIBLE;
                dayDetails.setVisibility(expanded ? View.VISIBLE : View.GONE);
                toggleHint.setText(UiTranslations.text(host,
                        expanded ? "Hide details ↑" : "Show details ↓"));
                dayHeader.setContentDescription(dayDescription + ", "
                        + UiTranslations.text(host, expanded
                                ? "tap to hide details" : "tap to show details"));
            });

            LinearLayout.LayoutParams dayLp = new LinearLayout.LayoutParams(-1, -2);
            dayLp.topMargin = dp(i == 0 ? 7 : 9);
            panel.addView(dayCard, dayLp);
        }
    }

    private View airQualityHero(JSONObject index) {
        String dominant = index == null ? ""
                : WeatherActivityFoundation.prettyEnum(
                        WeatherActivityFoundation.stringValue(index, "dominantPollutant"));
        String category = index == null ? "Current air quality unavailable"
                : WeatherActivityFoundation.firstNonEmpty(
                        WeatherActivityFoundation.stringValue(index, "category"),
                        "Current air quality");
        return airQualityHero(aqiDisplayValue(index), category,
                "Universal AQI", dominant, airQualityColor(index));
    }

    private View airQualityHero(String scoreValue, String category, String scale,
            String dominant, int accent) {
        LinearLayout hero = new LinearLayout(host);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(13), dp(13), dp(13), dp(13));
        hero.setBackground(host.newEnvironmentalGlassDrawable(dp(19), false));

        LinearLayout score = new LinearLayout(host);
        score.setOrientation(LinearLayout.VERTICAL);
        score.setGravity(Gravity.CENTER);
        GradientDrawable scoreBackground = host.roundedBg(Color.argb(
                62, Color.red(accent), Color.green(accent), Color.blue(accent)), dp(16));
        score.setBackground(scoreBackground);
        String displayScore = scoreValue == null || scoreValue.isEmpty() ? "—" : scoreValue;
        TextView value = text(displayScore, 30, true, WeatherActivityFoundation.WHITE);
        value.setGravity(Gravity.CENTER);
        value.setIncludeFontPadding(false);
        score.addView(value);
        String shortScale = "European AQI".equals(scale) ? "EU AQI"
                : "US AQI".equals(scale) ? "US AQI" : "UAQI";
        TextView aqi = text(shortScale, 9, true, WeatherActivityFoundation.WHITE);
        aqi.setAlpha(0.86f);
        aqi.setGravity(Gravity.CENTER);
        score.addView(aqi);
        hero.addView(score, new LinearLayout.LayoutParams(dp(80), dp(76)));

        LinearLayout copy = new LinearLayout(host);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView statusLabel = text("CURRENT STATUS", 9, true, environmentAccentColor());
        statusLabel.setLetterSpacing(0.08f);
        copy.addView(statusLabel);

        String safeCategory = WeatherActivityFoundation.firstNonEmpty(
                category, "Current air quality unavailable");
        View status = environmentStatusLine(safeCategory, accent, 16, true);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.topMargin = dp(3);
        copy.addView(status, statusLp);

        TextView detail = text(dominant.isEmpty()
                ? scale
                : UiTranslations.text(host, "Main pollutant") + " · " + dominant,
                11, false, WeatherActivityFoundation.SOFT_WHITE);
        detail.setPadding(0, dp(4), 0, 0);
        detail.setMaxLines(2);
        copy.addView(detail);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, -2, 1f);
        copyLp.leftMargin = dp(12);
        hero.addView(copy, copyLp);

        hero.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        hero.setContentDescription(UiTranslations.text(host, scale) + " "
                + ("—".equals(displayScore)
                        ? UiTranslations.text(host, "unavailable") : displayScore)
                + ", " + UiTranslations.text(host, safeCategory)
                + (dominant.isEmpty() ? "" : ", "
                        + UiTranslations.text(host, "main pollutant") + " " + dominant));
        host.makeChildrenUnimportant(hero);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(2);
        hero.setLayoutParams(lp);
        return hero;
    }

    private View airQualityForecastCell(String time, JSONObject index) {
        LinearLayout cell = new LinearLayout(host);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(7), dp(7), dp(7), dp(7));
        cell.setBackground(host.newEnvironmentalGlassDrawable(dp(15), true));
        int accent = airQualityColor(index);

        View accentBar = new View(host);
        accentBar.setBackground(host.roundedBg(accent, dp(2)));
        cell.addView(accentBar, new LinearLayout.LayoutParams(-1, dp(3)));

        String safeTime = time == null ? "" : time.trim();
        int split = safeTime.lastIndexOf(' ');
        String dayLabel = split > 0 ? safeTime.substring(0, split) : "";
        String clockLabel = split > 0 ? safeTime.substring(split + 1) : safeTime;
        if (!dayLabel.isEmpty()) {
            TextView day = text(dayLabel, 9, false, WeatherActivityFoundation.FAINT_WHITE);
            day.setGravity(Gravity.CENTER);
            day.setPadding(0, dp(5), 0, 0);
            cell.addView(day);
        }
        TextView timeView = text(clockLabel.isEmpty() ? "—" : clockLabel, 11, true,
                WeatherActivityFoundation.SOFT_WHITE);
        timeView.setGravity(Gravity.CENTER);
        if (dayLabel.isEmpty()) timeView.setPadding(0, dp(5), 0, 0);
        cell.addView(timeView);

        String aqiValue = aqiDisplayValue(index);
        TextView value = text(aqiValue.isEmpty() ? "—" : aqiValue,
                21, true, WeatherActivityFoundation.WHITE);
        value.setGravity(Gravity.CENTER);
        value.setPadding(0, dp(3), 0, 0);
        cell.addView(value);

        String category = index == null ? "Unavailable"
                : WeatherActivityFoundation.stringValue(index, "category");
        TextView categoryView = text(category, 9, false, WeatherActivityFoundation.SOFT_WHITE);
        categoryView.setGravity(Gravity.CENTER);
        categoryView.setMaxLines(2);
        categoryView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams categoryLp = new LinearLayout.LayoutParams(-1, dp(27));
        categoryLp.topMargin = dp(2);
        cell.addView(categoryView, categoryLp);

        LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(dp(84), dp(110));
        cellLp.rightMargin = dp(6);
        cell.setLayoutParams(cellLp);
        cell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        cell.setContentDescription((safeTime.isEmpty()
                ? UiTranslations.text(host, "Forecast hour") : safeTime)
                + ", " + UiTranslations.text(host, "air quality index") + " "
                + (aqiValue.isEmpty()
                        ? UiTranslations.text(host, "unavailable") : aqiValue)
                + (category.isEmpty() ? "" : ", " + UiTranslations.text(host, category)));
        host.makeChildrenUnimportant(cell);
        return cell;
    }

    private View environmentMetricRow(String label, String value) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(host.newEnvironmentalGlassDrawable(dp(15), true));
        TextView metricLabel = text(label, 10, true, WeatherActivityFoundation.SOFT_WHITE);
        metricLabel.setMaxLines(2);
        metricLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(metricLabel);
        TextView amount = text(value, 15, true, WeatherActivityFoundation.WHITE);
        amount.setPadding(0, dp(3), 0, 0);
        amount.setMaxLines(2);
        row.addView(amount);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        row.setContentDescription(metricLabel.getText() + ", " + amount.getText());
        host.makeChildrenUnimportant(row);
        return row;
    }

    private View pollenTypeRow(String name, int value, String category) {
        LinearLayout block = new LinearLayout(host);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(8), 0, dp(8));

        LinearLayout labels = new LinearLayout(host);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labelCopy = new LinearLayout(host);
        labelCopy.setOrientation(LinearLayout.VERTICAL);
        TextView nameView = text(name, 12, true, WeatherActivityFoundation.WHITE);
        labelCopy.addView(nameView);
        if (category != null && !category.isEmpty()) {
            TextView categoryView = text(category, 10, false, WeatherActivityFoundation.SOFT_WHITE);
            categoryView.setPadding(0, dp(1), dp(6), 0);
            labelCopy.addView(categoryView);
        }
        labels.addView(labelCopy, new LinearLayout.LayoutParams(0, -2, 1f));
        labels.addView(environmentBadge(
                value < 0 ? "No index" : "UPI " + value, pollenColor(value)));
        block.addView(labels);

        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(-1, dp(4));
        trackLp.topMargin = dp(6);
        block.addView(environmentSeverityTrack(value, 5, pollenColor(value)), trackLp);
        block.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        block.setContentDescription(UiTranslations.text(host, name) + ", "
                + (value < 0 ? UiTranslations.text(host, "pollen index unavailable")
                        : UiTranslations.text(host, "Universal Pollen Index") + " " + value)
                + (category == null || category.isEmpty()
                        ? "" : ", " + UiTranslations.text(host, category)));
        host.makeChildrenUnimportant(block);
        return block;
    }

    private View pollenPlantRow(String name, int value, String category, boolean inSeason) {
        LinearLayout row = new LinearLayout(host);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        LinearLayout copy = new LinearLayout(host);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(text(name, 11, true, WeatherActivityFoundation.WHITE));
        if (inSeason) {
            TextView season = text("In season", 9, false, WeatherActivityFoundation.FAINT_WHITE);
            season.setPadding(0, dp(1), 0, 0);
            copy.addView(season);
        }
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

        String level = value < 0 ? "Index unavailable" : "UPI " + value;
        if (category != null && !category.isEmpty()) level += " · " + category;
        LinearLayout levelLine = new LinearLayout(host);
        levelLine.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(host);
        dot.setBackground(host.roundedBg(pollenColor(value), dp(4)));
        levelLine.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));
        TextView levelView = text(level, 10, false, WeatherActivityFoundation.SOFT_WHITE);
        levelView.setGravity(Gravity.END);
        levelView.setMaxLines(2);
        LinearLayout.LayoutParams levelLp = new LinearLayout.LayoutParams(-2, -2);
        levelLp.leftMargin = dp(5);
        levelLine.addView(levelView, levelLp);
        row.addView(levelLine);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        row.setContentDescription(UiTranslations.text(host, name)
                + (inSeason ? ", " + UiTranslations.text(host, "in season") : "")
                + ", " + level);
        host.makeChildrenUnimportant(row);
        return row;
    }

    private View environmentInset(String titleValue, String bodyValue) {
        LinearLayout inset = new LinearLayout(host);
        inset.setOrientation(LinearLayout.HORIZONTAL);
        inset.setGravity(Gravity.TOP);
        inset.setPadding(0, dp(10), 0, 0);
        int accent = environmentAccentColor();
        View rail = new View(host);
        rail.setBackground(host.roundedBg(accent, dp(2)));
        inset.addView(rail, new LinearLayout.LayoutParams(dp(3), -1));

        LinearLayout copy = new LinearLayout(host);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(titleValue, 10, true, accent);
        copy.addView(title);
        TextView body = text(bodyValue, 11, false, WeatherActivityFoundation.SOFT_WHITE);
        body.setLineSpacing(0f, 1.08f);
        body.setPadding(0, dp(3), 0, 0);
        copy.addView(body);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, -2, 1f);
        copyLp.leftMargin = dp(9);
        inset.addView(copy, copyLp);
        inset.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        inset.setContentDescription(titleValue + ". " + bodyValue);
        host.makeChildrenUnimportant(inset);
        return inset;
    }

    private TextView environmentBadge(String value, int color) {
        TextView badge = text(value, 10, true, WeatherActivityFoundation.WHITE);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(9), dp(4), dp(9), dp(4));
        GradientDrawable background = host.roundedBg(Color.argb(
                78, Color.red(color), Color.green(color), Color.blue(color)), dp(13));
        badge.setBackground(background);
        badge.setContentDescription(badge.getText());
        return badge;
    }

    private TextView sectionHeading(String value) {
        TextView heading = text(value, 10, true, environmentAccentColor());
        heading.setLetterSpacing(0.08f);
        heading.setAccessibilityHeading(true);
        heading.setPadding(dp(1), dp(13), 0, dp(7));
        return heading;
    }

    private TextView environmentMiniHeading(String value) {
        TextView heading = text(value, 9, true, environmentAccentColor());
        heading.setLetterSpacing(0.08f);
        heading.setAccessibilityHeading(true);
        heading.setPadding(0, dp(11), 0, dp(2));
        return heading;
    }

    private View environmentStatusLine(String value, int color, int textSizeSp, boolean bold) {
        LinearLayout line = new LinearLayout(host);
        line.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(host);
        dot.setBackground(host.roundedBg(color, dp(5)));
        line.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        TextView label = text(value, textSizeSp, bold, WeatherActivityFoundation.WHITE);
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, -2, 1f);
        labelLp.leftMargin = dp(6);
        line.addView(label, labelLp);
        return line;
    }

    private View environmentSeverityTrack(int value, int maximum, int color) {
        LinearLayout track = new LinearLayout(host);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(host.roundedBg(Color.argb(28, 255, 255, 255), dp(3)));
        int max = Math.max(1, maximum);
        int clamped = Math.max(0, Math.min(max, value));
        View fill = new View(host);
        fill.setBackground(host.roundedBg(color, dp(3)));
        track.addView(fill, new LinearLayout.LayoutParams(0, -1, clamped));
        View remainder = new View(host);
        track.addView(remainder, new LinearLayout.LayoutParams(
                0, -1, Math.max(0.001f, max - clamped)));
        track.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return track;
    }

    private View environmentDivider() {
        View divider = new View(host);
        divider.setBackground(host.roundedBg(Color.argb(28, 255, 255, 255), dp(1)));
        divider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        divider.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return divider;
    }

    private int environmentAccentColor() {
        String scene = host.displayedScene == null ? "" : host.displayedScene;
        if (scene.trim().isEmpty()) {
            scene = WeatherOverviewRenderingActivity.settingsSceneKey(
                    host.lastCurrentWeather, host.displayedDaytime);
        }
        return WeatherOverviewRenderingActivity.settingsAccent(scene);
    }

    private int airQualityColor(JSONObject index) {
        String category = index == null ? ""
                : WeatherActivityFoundation.stringValue(index, "category");
        if (!category.isEmpty()) return airQualityCategoryColor(category);
        int value = index == null ? -1 : index.optInt("aqi", -1);
        if (value < 0) return Color.rgb(157, 177, 196);
        if (value <= 20) return Color.rgb(74, 222, 128);
        if (value <= 40) return Color.rgb(163, 230, 53);
        if (value <= 60) return Color.rgb(250, 204, 21);
        if (value <= 80) return Color.rgb(251, 146, 60);
        if (value <= 100) return Color.rgb(248, 113, 113);
        return Color.rgb(192, 132, 252);
    }

    private int airQualityCategoryColor(String category) {
        String level = category == null ? "" : category.toLowerCase(Locale.ROOT);
        if (level.contains("hazard") || level.contains("extremely"))
            return Color.rgb(192, 132, 252);
        if (level.contains("very poor") || level.contains("very unhealthy"))
            return Color.rgb(248, 113, 113);
        if (level.contains("poor") || level.contains("unhealthy"))
            return Color.rgb(251, 146, 60);
        if (level.contains("moderate") || level.contains("fair"))
            return Color.rgb(250, 204, 21);
        if (level.contains("good") || level.contains("excellent"))
            return Color.rgb(74, 222, 128);
        return Color.rgb(157, 177, 196);
    }

    private String environmentConcentrationUnit(String raw) {
        String unit = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (unit.equals("MICROGRAMS_PER_CUBIC_METER")) return "µg/m³";
        if (unit.equals("PARTS_PER_BILLION")) return "ppb";
        if (unit.equals("PARTS_PER_MILLION")) return "ppm";
        if (unit.equals("MILLIGRAMS_PER_CUBIC_METER")) return "mg/m³";
        return unit.isEmpty() ? "" : unit.replace('_', ' ').toLowerCase(AppLocaleManager.activeLocale(host));
    }

    private int pollenColor(int value) {
        if (value < 0) return Color.rgb(148, 163, 184);
        if (value <= 1) return Color.rgb(74, 222, 128);
        if (value == 2) return Color.rgb(163, 230, 53);
        if (value == 3) return Color.rgb(250, 204, 21);
        if (value == 4) return Color.rgb(251, 146, 60);
        return Color.rgb(248, 113, 113);
    }

    private View environmentDetailBlock(String titleValue, String bodyValue) {
        LinearLayout block = new LinearLayout(host);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(12), dp(10), dp(12), dp(10));
        block.setBackground(host.newEnvironmentalGlassDrawable(dp(15), true));
        block.addView(text(titleValue, 12, true, WeatherActivityFoundation.WHITE));
        TextView body = text(bodyValue, 11, false, WeatherActivityFoundation.SOFT_WHITE);
        body.setLineSpacing(0f, 1.08f);
        body.setPadding(0, dp(3), 0, 0);
        block.addView(body);
        block.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        block.setContentDescription(titleValue + ". " + bodyValue);
        host.makeChildrenUnimportant(block);
        return block;
    }

    private String formatEnvironmentalHour(String value, ZoneId zone) {
        Instant instant = WeatherActivityFoundation.parseInstant(value);
        if (instant == null) return "";
        try {
            Locale locale = AppLocaleManager.activeLocale(host);
            return AppLocaleManager.capitalize(WeatherTimeFormat.dated(host, instant, zone, "EEE"), locale);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String pollenDateLabel(JSONObject date, int index) {
        if (date != null) {
            try {
                LocalDate local = LocalDate.of(
                        date.optInt("year"), date.optInt("month"), date.optInt("day"));
                Locale locale = AppLocaleManager.activeLocale(host);
                return AppLocaleManager.capitalize(
                        DateTimeFormatter.ofPattern("EEEE d MMM", locale).format(local), locale);
            } catch (Exception ignored) { }
        }
        return index == 0 ? UiTranslations.text(host, "Today")
                : UiTranslations.text(host, "Day") + " " + (index + 1);
    }

    private int dp(int value) {
        return host.dp(value);
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        return host.text(value, sp, bold, color);
    }
}
