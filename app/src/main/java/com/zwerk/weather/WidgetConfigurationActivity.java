package com.zwerk.weather;

import android.app.Activity;
import android.app.Dialog;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Launcher-facing configuration for a single instance of any Zwerk home-screen widget. */
public final class WidgetConfigurationActivity extends Activity {
    private static final String STATE_CITY_ID = "city_id";
    private static final String STATE_TEXT_SCALE = "text_scale";
    private static final String STATE_DETAILS = "details";
    private static final String STATE_FORECAST = "forecast";
    private static final String STATE_FORECAST_DAYS = "forecast_days";
    private static final String STATE_PRECIP_STYLE = "precip_style";
    private static final String STATE_BG_MODE = "bg_mode";
    private static final String STATE_BG_TRANSPARENCY = "bg_transparency";
    private static final String STATE_BG_GRADIENT = "bg_gradient";
    private static final String STATE_BG_COLOR = "bg_color";
    private static final String STATE_PICKER_OPEN = "picker_open";

    private static final int BACKGROUND = Color.rgb(11, 17, 27);
    private static final int PANEL = Color.rgb(25, 39, 57);
    private static final int CONTROL = Color.rgb(34, 51, 72);
    private static final int CONTROL_SELECTED = Color.rgb(46, 86, 122);
    private static final int BORDER = Color.rgb(63, 84, 108);
    private static final int PRIMARY = Color.rgb(247, 250, 255);
    private static final int SECONDARY = Color.rgb(178, 194, 214);
    private static final int ACCENT = Color.rgb(115, 199, 255);

    private enum Kind { WEATHER, SMALL, MINI, RAIN, FORECAST }

    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private Kind kind;
    private Button locationButton;
    private Dialog locationDialog;
    private Dialog colorDialog;
    private int selectedLocation;
    private SeekBar sizeSlider;
    private TextView sizeValue;
    private Switch detailsSwitch;
    private Switch forecastSwitch;
    private int forecastDays = CombinedForecastData.DEFAULT_WIDGET_DAYS;
    private String precipitationStyle = CombinedForecastData.PRECIP_STYLE_COLUMNS;
    private final List<Button> dayButtons = new ArrayList<>();
    private Button columnsStyleButton;
    private Button lineStyleButton;
    private String backgroundMode = WidgetConfiguration.BG_APP_DEFAULT;
    private int transparencyPercent = WidgetConfiguration.TRANSPARENCY_APP_DEFAULT;
    private int backgroundGradient = WidgetConfiguration.GRADIENT_APP_DEFAULT;
    private int customColor = WidgetBackground.DEFAULT_COLOUR;
    private Button backgroundButton;
    private SeekBar transparencySlider;
    private TextView transparencyValue;
    private Button transparencyResetButton;
    private Button gradientDefaultButton;
    private Button gradientOnButton;
    private Button gradientOffButton;
    private WidgetConfigurationPreview preview;
    private final Runnable renderPreview = () -> preview.render(currentConfiguration());
    private final List<String> cityIds = new ArrayList<>();
    private final List<String> cityNames = new ArrayList<>();

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLocaleManager.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        widgetId = getIntent() == null ? AppWidgetManager.INVALID_APPWIDGET_ID
                : getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                        AppWidgetManager.INVALID_APPWIDGET_ID);

        // Android's widget picker expects cancellation until an explicit Save succeeds.
        setResult(RESULT_CANCELED, resultIntent());
        kind = widgetKind(widgetId);
        if (kind == null) {
            Toast.makeText(this, label("This widget is no longer available."), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        WidgetConfiguration stored = WidgetConfiguration.read(this, widgetId);
        String cityId = stored.cityId;
        int scale = stored.textScalePercent;
        boolean details = stored.showDetails;
        boolean forecast = stored.showForecast;
        forecastDays = stored.forecastDays;
        precipitationStyle = stored.precipitationStyle;
        backgroundMode = stored.backgroundMode;
        transparencyPercent = stored.transparencyPercent;
        backgroundGradient = stored.backgroundGradient;
        customColor = stored.customColor;
        if (savedInstanceState != null) {
            cityId = savedInstanceState.getString(STATE_CITY_ID, cityId);
            scale = savedInstanceState.getInt(STATE_TEXT_SCALE, scale);
            details = savedInstanceState.getBoolean(STATE_DETAILS, details);
            forecast = savedInstanceState.getBoolean(STATE_FORECAST, forecast);
            forecastDays = CombinedForecastData.clampWidgetDays(
                    savedInstanceState.getInt(STATE_FORECAST_DAYS, forecastDays));
            precipitationStyle = CombinedForecastData.normalizePrecipitationStyle(
                    savedInstanceState.getString(STATE_PRECIP_STYLE, precipitationStyle));
            backgroundMode = WidgetConfiguration.normalizeBackgroundMode(
                    savedInstanceState.getString(STATE_BG_MODE, backgroundMode));
            transparencyPercent = savedInstanceState.getInt(STATE_BG_TRANSPARENCY, transparencyPercent);
            backgroundGradient = savedInstanceState.getInt(STATE_BG_GRADIENT, backgroundGradient);
            customColor = savedInstanceState.getInt(STATE_BG_COLOR, customColor);
        }
        buildScreen(cityId, Math.max(80, Math.min(120, scale)), details, forecast);
        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_PICKER_OPEN)) {
            showLocationPicker();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (locationButton == null) return;
        state.putString(STATE_CITY_ID, cityIds.get(selectedLocation));
        state.putInt(STATE_TEXT_SCALE, sizeSlider.getProgress());
        state.putBoolean(STATE_DETAILS, detailsSwitch.isChecked());
        state.putBoolean(STATE_FORECAST, forecastSwitch.isChecked());
        state.putInt(STATE_FORECAST_DAYS, forecastDays);
        state.putString(STATE_PRECIP_STYLE, precipitationStyle);
        state.putString(STATE_BG_MODE, backgroundMode);
        state.putInt(STATE_BG_TRANSPARENCY, transparencyPercent);
        state.putInt(STATE_BG_GRADIENT, backgroundGradient);
        state.putInt(STATE_BG_COLOR, customColor);
        state.putBoolean(STATE_PICKER_OPEN, locationDialog != null);
    }

    @Override
    protected void onDestroy() {
        if (locationDialog != null) locationDialog.dismiss();
        if (colorDialog != null) colorDialog.dismiss();
        if (preview != null) preview.removeCallbacks(renderPreview);
        super.onDestroy();
    }

    private Intent resultIntent() {
        return new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
    }

    private Kind widgetKind(int id) {
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return null;
        AppWidgetProviderInfo info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id);
        if (info == null || info.provider == null) return null;
        ComponentName provider = info.provider;
        if (provider.equals(new ComponentName(this, CombinedForecastWidgetProvider.class))) {
            return Kind.FORECAST;
        }
        if (provider.equals(new ComponentName(this, PrecipitationWidgetProvider.class))) {
            return Kind.RAIN;
        }
        if (provider.equals(new ComponentName(this, WeatherMiniWidgetProvider.class))) {
            return Kind.MINI;
        }
        if (provider.equals(new ComponentName(this, WeatherSmallWidgetProvider.class))) {
            return Kind.SMALL;
        }
        if (provider.equals(new ComponentName(this, WeatherWidgetProvider.class))) {
            return Kind.WEATHER;
        }
        return null;
    }

    private void buildScreen(String cityId, int scale, boolean details, boolean forecast) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(21, 38, 57), BACKGROUND}));
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            Rect safe = WeatherSystemBars.safeInsets(insets);
            view.setPadding(dp(20) + safe.left, safe.top, dp(20) + safe.right, safe.bottom);
            return insets;
        });
        setContentView(page);
        WeatherSystemBars.applyAppearance(getWindow());
        page.requestApplyInsets();

        TextView heading = text(label("Configure widget"), 25, PRIMARY, true);
        LinearLayout.LayoutParams headingParams = fullWidth();
        headingParams.topMargin = dp(18);
        page.addView(heading, headingParams);

        TextView subtitle = text(widgetName(), 14, SECONDARY, false);
        LinearLayout.LayoutParams subtitleParams = fullWidth();
        subtitleParams.topMargin = dp(2);
        subtitleParams.bottomMargin = dp(18);
        page.addView(subtitle, subtitleParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(20));
        scroll.addView(content);

        TextView previewLabel = text(label("Live preview"), 14, ACCENT, true);
        previewLabel.setAccessibilityHeading(true);
        content.addView(previewLabel, fullWidth());

        Bundle options = AppWidgetManager.getInstance(this).getAppWidgetOptions(widgetId);
        WeatherWidgetProvider.WidgetMode defaultMode = kind == Kind.MINI
                ? WeatherWidgetProvider.WidgetMode.MINI : kind == Kind.SMALL
                ? WeatherWidgetProvider.WidgetMode.SMALL : WeatherWidgetProvider.WidgetMode.NORMAL;
        int defaultWidth = kind == Kind.MINI || kind == Kind.SMALL ? 109 : 280;
        int defaultHeight = kind == Kind.MINI ? 56
                : (kind == Kind.RAIN || kind == Kind.FORECAST) ? 144 : 118;
        WidgetConfigurationPreview.Mode previewMode = kind == Kind.RAIN
                ? WidgetConfigurationPreview.Mode.RAIN
                : kind == Kind.FORECAST ? WidgetConfigurationPreview.Mode.FORECAST
                        : WidgetConfigurationPreview.Mode.WEATHER;
        preview = new WidgetConfigurationPreview(this, options, previewMode, defaultMode,
                defaultWidth, defaultHeight);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        previewParams.gravity = Gravity.CENTER_HORIZONTAL;
        previewParams.topMargin = dp(10);
        content.addView(preview, previewParams);

        buildOptions(content, cityId, scale, details, forecast);
        buildAppearancePanel(content);

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(0, dp(12), 0, dp(12));
        page.addView(footer, fullWidth());

        Button cancel = button(label("Cancel"), PANEL, PRIMARY);
        footer.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1f));
        cancel.setOnClickListener(view -> finish());

        Button save = button(label("Save"), ACCENT, BACKGROUND);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, -2, 1f);
        saveParams.setMarginStart(dp(12));
        footer.addView(save, saveParams);
        save.setOnClickListener(view -> saveConfiguration());
        renderPreview.run();
    }

    private void buildOptions(LinearLayout parent, String cityId, int scale,
                              boolean details, boolean forecast) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(surface(PANEL, 20));
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams panelParams = fullWidth();
        panelParams.topMargin = dp(18);
        parent.addView(panel, panelParams);

        TextView cityLabel = text(label("Location"), 15, PRIMARY, true);
        panel.addView(cityLabel, fullWidth());
        cityNames.add(label("Use app location"));
        cityIds.add("");
        for (CityManagerActivity.LocationSnapshot city : CityManagerActivity.getStoredLocations(this)) {
            if (city.isDevice || city.id == null || city.id.isEmpty()) continue;
            cityIds.add(city.id);
            cityNames.add(city.name);
        }
        selectedLocation = Math.max(0, cityIds.indexOf(cityId));
        locationButton = button("", CONTROL, PRIMARY);
        locationButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        locationButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, 0, R.drawable.widget_config_chevron, 0);
        locationButton.setCompoundDrawablePadding(dp(12));
        updateLocationButton();
        LinearLayout.LayoutParams locationParams = fullWidth();
        locationParams.topMargin = dp(6);
        locationParams.bottomMargin = dp(16);
        panel.addView(locationButton, locationParams);
        locationButton.setOnClickListener(view -> showLocationPicker());

        if (kind == Kind.FORECAST) {
            TextView daysLabel = text(label("Forecast days"), 15, PRIMARY, true);
            panel.addView(daysLabel, fullWidth());
            LinearLayout daysRow = new LinearLayout(this);
            daysRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams daysRowParams = fullWidth();
            daysRowParams.topMargin = dp(6);
            daysRowParams.bottomMargin = dp(14);
            panel.addView(daysRow, daysRowParams);
            dayButtons.clear();
            for (int d = CombinedForecastData.MIN_WIDGET_DAYS; d <= CombinedForecastData.MAX_WIDGET_DAYS; d++) {
                final int dayCount = d;
                Button chip = button(Integer.toString(d), CONTROL, PRIMARY);
                chip.setGravity(Gravity.CENTER);
                chip.setContentDescription(d + " " + label("days"));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
                if (d > CombinedForecastData.MIN_WIDGET_DAYS) lp.setMarginStart(dp(6));
                daysRow.addView(chip, lp);
                dayButtons.add(chip);
                chip.setOnClickListener(view -> {
                    forecastDays = dayCount;
                    updateForecastOptionChips();
                    updatePreview();
                });
            }

            TextView styleLabel = text(label("Precipitation style"), 15, PRIMARY, true);
            panel.addView(styleLabel, fullWidth());
            LinearLayout styleRow = new LinearLayout(this);
            styleRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams styleRowParams = fullWidth();
            styleRowParams.topMargin = dp(6);
            styleRowParams.bottomMargin = dp(16);
            panel.addView(styleRow, styleRowParams);
            columnsStyleButton = button(label("Columns"), CONTROL, PRIMARY);
            columnsStyleButton.setGravity(Gravity.CENTER);
            styleRow.addView(columnsStyleButton, new LinearLayout.LayoutParams(0, dp(46), 1f));
            lineStyleButton = button(label("Line"), CONTROL, PRIMARY);
            lineStyleButton.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
            lineLp.setMarginStart(dp(8));
            styleRow.addView(lineStyleButton, lineLp);
            columnsStyleButton.setOnClickListener(view -> {
                precipitationStyle = CombinedForecastData.PRECIP_STYLE_COLUMNS;
                updateForecastOptionChips();
                updatePreview();
            });
            lineStyleButton.setOnClickListener(view -> {
                precipitationStyle = CombinedForecastData.PRECIP_STYLE_LINE;
                updateForecastOptionChips();
                updatePreview();
            });
            updateForecastOptionChips();
        }

        LinearLayout sizeHeading = new LinearLayout(this);
        sizeHeading.setGravity(Gravity.CENTER_VERTICAL);
        sizeHeading.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(sizeHeading, fullWidth());
        TextView sizeLabel = text(label("Text size"), 15, PRIMARY, true);
        sizeHeading.addView(sizeLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sizeValue = text(sizeLabel(scale), 14, ACCENT, true);
        sizeHeading.addView(sizeValue);

        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sizeRowParams = fullWidth();
        sizeRowParams.topMargin = dp(6);
        panel.addView(sizeRow, sizeRowParams);
        addSizeButton(sizeRow, "−", label("Decrease text size"), -5);

        sizeSlider = new WeatherSeekBar(this, true, true);
        sizeSlider.setMin(80);
        sizeSlider.setMax(120);
        sizeSlider.setProgress(scale);
        sizeSlider.setKeyProgressIncrement(1);
        sizeSlider.setProgressTintList(ColorStateList.valueOf(ACCENT));
        sizeSlider.setProgressBackgroundTintList(ColorStateList.valueOf(BORDER));
        sizeSlider.setThumbTintList(ColorStateList.valueOf(ACCENT));
        sizeSlider.setContentDescription(label("Text size"));
        sizeSlider.setFocusable(true);
        if (Build.VERSION.SDK_INT >= 30) sizeSlider.setStateDescription(sizeLabel(scale));
        sizeRow.addView(sizeSlider, new LinearLayout.LayoutParams(0, dp(48), 1f));
        sizeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                String formatted = sizeLabel(progress);
                sizeValue.setText(formatted);
                if (Build.VERSION.SDK_INT >= 30) seekBar.setStateDescription(formatted);
                updatePreview();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        addSizeButton(sizeRow, "+", label("Increase text size"), 5);

        String detailsTitle = kind == Kind.RAIN
                ? "Show rain amount"
                : kind == Kind.FORECAST ? "Show current weather" : "Show weather details";
        String forecastTitle = kind == Kind.RAIN
                ? "Show timeline legend"
                : kind == Kind.FORECAST ? "Show weather icons" : "Show forecast";
        detailsSwitch = addSwitch(panel, label(detailsTitle), details);
        forecastSwitch = addSwitch(panel, label(forecastTitle), forecast);
        if (kind != Kind.RAIN && kind != Kind.FORECAST
                && preview.weatherMode == WeatherWidgetProvider.WidgetMode.MINI) {
            detailsSwitch.setVisibility(View.GONE);
        }
        if (kind != Kind.RAIN && kind != Kind.FORECAST
                && !WeatherWidgetProvider.hasHourlyStrip(preview.weatherMode)) {
            forecastSwitch.setVisibility(View.GONE);
        }
    }

    private void buildAppearancePanel(LinearLayout parent) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(surface(PANEL, 20));
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams panelParams = fullWidth();
        panelParams.topMargin = dp(14);
        parent.addView(panel, panelParams);

        TextView sectionTitle = text(label("Appearance"), 15, ACCENT, true);
        sectionTitle.setAccessibilityHeading(true);
        panel.addView(sectionTitle, fullWidth());

        TextView bgLabel = text(label("Widget background"), 15, PRIMARY, true);
        LinearLayout.LayoutParams bgLabelParams = fullWidth();
        bgLabelParams.topMargin = dp(10);
        panel.addView(bgLabel, bgLabelParams);

        backgroundButton = button("", CONTROL, PRIMARY);
        backgroundButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        backgroundButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, 0, R.drawable.widget_config_chevron, 0);
        backgroundButton.setCompoundDrawablePadding(dp(12));
        updateBackgroundButton();
        LinearLayout.LayoutParams bgBtnParams = fullWidth();
        bgBtnParams.topMargin = dp(6);
        bgBtnParams.bottomMargin = dp(14);
        panel.addView(backgroundButton, bgBtnParams);
        backgroundButton.setOnClickListener(view -> showBackgroundPicker());

        SharedPreferences appPrefs = getSharedPreferences(WeatherPreferences.PREFS_NAME, MODE_PRIVATE);
        int appTransparency = Math.max(0, Math.min(100,
                appPrefs.getInt(WeatherWidgetProvider.PREF_TRANSPARENCY, 15)));

        LinearLayout transHeader = new LinearLayout(this);
        transHeader.setOrientation(LinearLayout.HORIZONTAL);
        transHeader.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(transHeader, fullWidth());
        transHeader.addView(text(label("Widget transparency"), 15, PRIMARY, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        transparencyValue = text("", 14, ACCENT, true);
        transHeader.addView(transparencyValue);

        transparencyResetButton = button(label("App default"), CONTROL, SECONDARY);
        transparencyResetButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        transparencyResetButton.setMinHeight(dp(34));
        transparencyResetButton.setPaddingRelative(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(-2, dp(34));
        resetLp.setMarginStart(dp(8));
        transHeader.addView(transparencyResetButton, resetLp);
        transparencyResetButton.setOnClickListener(view -> {
            transparencyPercent = WidgetConfiguration.TRANSPARENCY_APP_DEFAULT;
            transparencySlider.setProgress(appTransparency);
            updateTransparencyUi(appTransparency);
            updatePreview();
        });

        transparencySlider = new WeatherSeekBar(this, true, true);
        transparencySlider.setMin(0);
        transparencySlider.setMax(100);
        transparencySlider.setProgress(transparencyPercent >= 0 ? transparencyPercent : appTransparency);
        transparencySlider.setKeyProgressIncrement(5);
        transparencySlider.setProgressTintList(ColorStateList.valueOf(ACCENT));
        transparencySlider.setProgressBackgroundTintList(ColorStateList.valueOf(BORDER));
        transparencySlider.setThumbTintList(ColorStateList.valueOf(ACCENT));
        transparencySlider.setContentDescription(label("Widget transparency"));
        transparencySlider.setFocusable(true);
        LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        sliderParams.topMargin = dp(4);
        panel.addView(transparencySlider, sliderParams);
        transparencySlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) transparencyPercent = progress;
                updateTransparencyUi(appTransparency);
                updatePreview();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        updateTransparencyUi(appTransparency);

        TextView gradientLabel = text(label("Background gradient"), 15, PRIMARY, true);
        LinearLayout.LayoutParams gradientLabelParams = fullWidth();
        gradientLabelParams.topMargin = dp(8);
        panel.addView(gradientLabel, gradientLabelParams);

        LinearLayout gradientRow = new LinearLayout(this);
        gradientRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams gradientRowParams = fullWidth();
        gradientRowParams.topMargin = dp(6);
        panel.addView(gradientRow, gradientRowParams);

        gradientDefaultButton = button(label("App default"), CONTROL, PRIMARY);
        gradientDefaultButton.setGravity(Gravity.CENTER);
        gradientRow.addView(gradientDefaultButton, new LinearLayout.LayoutParams(0, dp(46), 1.3f));

        gradientOnButton = button(label("On"), CONTROL, PRIMARY);
        gradientOnButton.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams onLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        onLp.setMarginStart(dp(6));
        gradientRow.addView(gradientOnButton, onLp);

        gradientOffButton = button(label("Off"), CONTROL, PRIMARY);
        gradientOffButton.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams offLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        offLp.setMarginStart(dp(6));
        gradientRow.addView(gradientOffButton, offLp);

        gradientDefaultButton.setOnClickListener(view -> {
            backgroundGradient = WidgetConfiguration.GRADIENT_APP_DEFAULT;
            updateGradientChips();
            updatePreview();
        });
        gradientOnButton.setOnClickListener(view -> {
            backgroundGradient = 1;
            updateGradientChips();
            updatePreview();
        });
        gradientOffButton.setOnClickListener(view -> {
            backgroundGradient = 0;
            updateGradientChips();
            updatePreview();
        });
        updateGradientChips();
    }

    private void updateGradientChips() {
        if (gradientDefaultButton == null) return;
        styleChip(gradientDefaultButton, backgroundGradient < 0);
        styleChip(gradientOnButton, backgroundGradient > 0);
        styleChip(gradientOffButton, backgroundGradient == 0);
    }

    private void updateForecastOptionChips() {
        for (int i = 0; i < dayButtons.size(); i++) {
            int dayCount = CombinedForecastData.MIN_WIDGET_DAYS + i;
            styleChip(dayButtons.get(i), dayCount == forecastDays);
        }
        if (columnsStyleButton != null && lineStyleButton != null) {
            boolean line = CombinedForecastData.PRECIP_STYLE_LINE.equals(precipitationStyle);
            styleChip(columnsStyleButton, !line);
            styleChip(lineStyleButton, line);
        }
    }

    private void styleChip(Button chip, boolean selected) {
        chip.setSelected(selected);
        chip.setBackground(controlBackground(selected ? CONTROL_SELECTED : CONTROL, 12));
        chip.setTextColor(selected ? Color.WHITE : SECONDARY);
        chip.setTypeface(Typeface.create(selected ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        if (Build.VERSION.SDK_INT >= 30) {
            chip.setStateDescription(getString(selected ? R.string.settings_selected : R.string.settings_not_selected));
        }
    }

    private void updateTransparencyUi(int appTransparency) {
        boolean custom = transparencyPercent >= 0;
        int shown = custom ? transparencyPercent : appTransparency;
        String text = custom ? sizeLabel(shown) : label("App default") + " (" + sizeLabel(shown) + ")";
        transparencyValue.setText(text);
        if (Build.VERSION.SDK_INT >= 30) transparencySlider.setStateDescription(text);
        transparencyResetButton.setVisibility(custom ? View.VISIBLE : View.GONE);
    }

    private void updateBackgroundButton() {
        String text;
        if (WidgetConfiguration.BG_WEATHER.equals(backgroundMode)) text = label("Weather");
        else if (WidgetConfiguration.BG_BLUE.equals(backgroundMode)) text = label("Blue");
        else if (WidgetConfiguration.BG_DARK.equals(backgroundMode)) text = label("Dark");
        else if (WidgetConfiguration.BG_CUSTOM.equals(backgroundMode)) text = label("Custom colour");
        else text = label("App default");
        backgroundButton.setText(text);
        backgroundButton.setContentDescription(label("Widget background") + ", " + text);
    }

    private void showBackgroundPicker() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setTitle(label("Widget background"));
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(14));
        panel.setBackground(surface(PANEL, 24));
        TextView title = text(label("Widget background"), 22, PRIMARY, true);
        title.setAccessibilityHeading(true);
        panel.addView(title, fullWidth());

        String[] modes = {
                WidgetConfiguration.BG_APP_DEFAULT,
                WidgetConfiguration.BG_WEATHER,
                WidgetConfiguration.BG_BLUE,
                WidgetConfiguration.BG_DARK,
                WidgetConfiguration.BG_CUSTOM
        };
        String[] titles = {
                label("App default"),
                label("Weather"),
                label("Blue"),
                label("Dark"),
                label("Custom colour")
        };

        RadioGroup choices = new RadioGroup(this);
        LinearLayout.LayoutParams listParams = fullWidth();
        listParams.topMargin = dp(12);
        panel.addView(choices, listParams);
        for (int i = 0; i < modes.length; i++) {
            final String mode = modes[i];
            boolean selected = mode.equals(backgroundMode);
            RadioButton choice = new RadioButton(this);
            choice.setId(View.generateViewId());
            choice.setText(titles[i]);
            choice.setTextColor(PRIMARY);
            choice.setTextSize(16);
            choice.setButtonTintList(checkedTint(ACCENT, SECONDARY));
            choice.setCompoundDrawablePadding(dp(8));
            choice.setMinimumHeight(dp(50));
            choice.setPaddingRelative(dp(12), dp(8), dp(12), dp(8));
            choice.setBackground(controlBackground(selected ? Color.rgb(43, 71, 95) : CONTROL, 12));
            RadioGroup.LayoutParams choiceParams = new RadioGroup.LayoutParams(-1, -2);
            choiceParams.bottomMargin = dp(6);
            choices.addView(choice, choiceParams);
            if (selected) choices.check(choice.getId());
            choice.setOnClickListener(view -> {
                dialog.dismiss();
                if (WidgetConfiguration.BG_CUSTOM.equals(mode)) {
                    showCustomColorPicker();
                } else {
                    backgroundMode = mode;
                    updateBackgroundButton();
                    updatePreview();
                }
            });
        }

        Button cancel = button(label("Cancel"), CONTROL, PRIMARY);
        LinearLayout.LayoutParams cancelParams = fullWidth();
        cancelParams.topMargin = dp(8);
        panel.addView(cancel, cancelParams);
        cancel.setOnClickListener(view -> dialog.dismiss());

        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.66f);
        }
        dialog.show();
        if (window != null) {
            int width = Math.min(dp(getResources().getConfiguration().screenWidthDp - 32), dp(440));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void showCustomColorPicker() {
        if (colorDialog != null) colorDialog.dismiss();
        Dialog dialog = new Dialog(this);
        colorDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setTitle(label("Custom colour"));
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(ignored -> colorDialog = null);

        ScrollView scroller = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(14));
        panel.setBackground(surface(PANEL, 24));
        scroller.addView(panel, new ScrollView.LayoutParams(-1, -2));

        panel.addView(text(label("Custom colour"), 22, PRIMARY, true), fullWidth());
        boolean appGradient = getSharedPreferences(WeatherPreferences.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(WeatherWidgetProvider.PREF_GRADIENT, true);
        boolean gradient = backgroundGradient >= 0 ? backgroundGradient > 0 : appGradient;
        WidgetColourPicker picker = new WidgetColourPicker(this, customColor, ACCENT, gradient);
        LinearLayout.LayoutParams pickerLp = fullWidth();
        pickerLp.topMargin = dp(14);
        panel.addView(picker, pickerLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLp = fullWidth();
        actionsLp.topMargin = dp(14);
        panel.addView(actions, actionsLp);

        Button cancel = button(label("Cancel"), CONTROL, PRIMARY);
        actions.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1f));
        cancel.setOnClickListener(view -> dialog.dismiss());

        Button apply = button(label("Apply"), ACCENT, BACKGROUND);
        LinearLayout.LayoutParams applyLp = new LinearLayout.LayoutParams(0, -2, 1f);
        applyLp.setMarginStart(dp(10));
        actions.addView(apply, applyLp);
        apply.setOnClickListener(view -> {
            Integer chosen = picker.selectedColour();
            if (chosen == null) return;
            customColor = chosen | 0xff000000;
            backgroundMode = WidgetConfiguration.BG_CUSTOM;
            updateBackgroundButton();
            updatePreview();
            dialog.dismiss();
        });

        dialog.setContentView(scroller);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.66f);
        }
        dialog.show();
        if (window != null) {
            int width = Math.min(dp(getResources().getConfiguration().screenWidthDp - 32), dp(440));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void addSizeButton(LinearLayout row, String symbol, String description, int step) {
        Button control = button(symbol, CONTROL, PRIMARY);
        control.setPadding(0, 0, 0, 0);
        control.setTextSize(21);
        control.setContentDescription(description);
        control.setOnClickListener(view -> sizeSlider.setProgress(sizeSlider.getProgress() + step));
        row.addView(control, new LinearLayout.LayoutParams(dp(48), dp(48)));
    }

    private Switch addSwitch(LinearLayout panel, String title, boolean checked) {
        Switch control = new Switch(this);
        control.setText(title);
        control.setTextColor(PRIMARY);
        control.setTextSize(15);
        control.setMinimumHeight(dp(52));
        control.setPaddingRelative(dp(10), dp(6), dp(10), dp(6));
        control.setSwitchPadding(dp(12));
        control.setChecked(checked);
        control.setFocusable(true);
        control.setBackground(controlBackground(PANEL, 12));
        control.setThumbTintList(checkedTint(ACCENT, SECONDARY));
        control.setTrackTintList(checkedTint(Color.rgb(57, 115, 155), BORDER));
        LinearLayout.LayoutParams params = fullWidth();
        params.topMargin = dp(6);
        panel.addView(control, params);
        control.setOnCheckedChangeListener((button, enabled) -> updatePreview());
        return control;
    }

    private static ColorStateList checkedTint(int checked, int normal) {
        return new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{checked, normal});
    }

    private void updateLocationButton() {
        String city = cityNames.get(selectedLocation);
        locationButton.setText(city);
        locationButton.setContentDescription(label("Location") + ", " + city);
    }

    private void showLocationPicker() {
        if (locationDialog != null) return;
        Dialog dialog = new Dialog(this);
        locationDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setTitle(label("Location"));
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(ignored -> locationDialog = null);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(14));
        panel.setBackground(surface(PANEL, 24));
        TextView title = text(label("Location"), 22, PRIMARY, true);
        title.setAccessibilityHeading(true);
        panel.addView(title, fullWidth());

        ScrollView scroller = new ScrollView(this);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, -2, 1f);
        listParams.topMargin = dp(12);
        panel.addView(scroller, listParams);
        RadioGroup choices = new RadioGroup(this);
        scroller.addView(choices, new ScrollView.LayoutParams(-1, -2));
        for (int i = 0; i < cityNames.size(); i++) {
            int index = i;
            boolean selected = i == selectedLocation;
            RadioButton choice = new RadioButton(this);
            choice.setId(View.generateViewId());
            choice.setText(cityNames.get(i));
            choice.setTextColor(PRIMARY);
            choice.setTextSize(16);
            choice.setButtonTintList(checkedTint(ACCENT, SECONDARY));
            choice.setCompoundDrawablePadding(dp(8));
            choice.setMinimumHeight(dp(52));
            choice.setPaddingRelative(dp(12), dp(8), dp(12), dp(8));
            choice.setBackground(controlBackground(selected ? Color.rgb(43, 71, 95) : CONTROL, 12));
            RadioGroup.LayoutParams choiceParams = new RadioGroup.LayoutParams(-1, -2);
            choiceParams.bottomMargin = dp(6);
            choices.addView(choice, choiceParams);
            if (selected) choices.check(choice.getId());
            choice.setOnClickListener(view -> {
                selectedLocation = index;
                updateLocationButton();
                updatePreview();
                dialog.dismiss();
            });
        }

        Button cancel = button(label("Cancel"), CONTROL, PRIMARY);
        LinearLayout.LayoutParams cancelParams = fullWidth();
        cancelParams.topMargin = dp(8);
        panel.addView(cancel, cancelParams);
        cancel.setOnClickListener(view -> dialog.dismiss());
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.66f);
        dialog.show();
        int width = Math.min(dp(getResources().getConfiguration().screenWidthDp - 32), dp(440));
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        View selected = choices.getChildAt(selectedLocation);
        if (!scroller.isInTouchMode()) {
            selected.requestFocus();
        } else {
            scroller.post(() -> scroller.scrollTo(0,
                    Math.max(0, selected.getBottom() - scroller.getHeight())));
        }
    }

    private WidgetConfiguration currentConfiguration() {
        return new WidgetConfiguration(
                cityIds.get(selectedLocation),
                sizeSlider.getProgress(),
                detailsSwitch.isChecked(),
                forecastSwitch.isChecked(),
                forecastDays,
                precipitationStyle,
                backgroundMode,
                transparencyPercent,
                backgroundGradient,
                customColor);
    }

    private void updatePreview() {
        preview.removeCallbacks(renderPreview);
        preview.postOnAnimation(renderPreview);
    }

    private void saveConfiguration() {
        // A widget can be removed by the launcher while its configuration is open.
        if (widgetKind(widgetId) != kind) {
            Toast.makeText(this, label("This widget is no longer available."), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        try {
            // save() persists this widget's preferences, redraws it and reconciles its schedule.
            WidgetConfiguration.save(this, widgetId, currentConfiguration());
        } catch (RuntimeException failure) {
            Toast.makeText(this, label("Unable to save widget settings."), Toast.LENGTH_LONG).show();
            return;
        }
        setResult(RESULT_OK, resultIntent());
        finish();
    }

    private String widgetName() {
        switch (kind) {
            case MINI: return getString(R.string.weather_mini_widget_name);
            case SMALL: return getString(R.string.weather_small_widget_name);
            case RAIN: return getString(R.string.precipitation_widget_name);
            case FORECAST: return getString(R.string.combined_forecast_widget_name);
            default: return getString(R.string.weather_widget_name);
        }
    }

    private String label(String value) {
        return UiTranslations.text(this, value);
    }

    private String sizeLabel(int percent) {
        return java.text.NumberFormat.getPercentInstance(
                getResources().getConfiguration().getLocales().get(0)).format(percent / 100d);
    }

    private TextView text(String content, float size, int color, boolean medium) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif",
                Typeface.NORMAL));
        return view;
    }

    private Button button(String label, int fill, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(color);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setPaddingRelative(dp(12), dp(8), dp(12), dp(8));
        button.setBackground(controlBackground(fill, 12));
        return button;
    }

    private RippleDrawable controlBackground(int fill, int radius) {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable focused = rounded(fill, radius);
        focused.setStroke(dp(2), fill == ACCENT ? BACKGROUND : ACCENT);
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, rounded(fill, radius));
        int ripple = fill == ACCENT ? 0x330B111B : 0x3373C7FF;
        return new RippleDrawable(ColorStateList.valueOf(ripple), states, null);
    }

    private GradientDrawable surface(int fill, int radius) {
        GradientDrawable background = rounded(fill, radius);
        background.setStroke(dp(1), BORDER);
        return background;
    }

    private GradientDrawable rounded(int fill, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
