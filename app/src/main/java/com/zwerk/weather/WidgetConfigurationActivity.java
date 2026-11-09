package com.zwerk.weather;

import android.app.Activity;
import android.app.Dialog;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
    private static final String STATE_PICKER_OPEN = "picker_open";

    private static final int BACKGROUND = Color.rgb(11, 17, 27);
    private static final int PANEL = Color.rgb(25, 39, 57);
    private static final int CONTROL = Color.rgb(34, 51, 72);
    private static final int BORDER = Color.rgb(63, 84, 108);
    private static final int PRIMARY = Color.rgb(247, 250, 255);
    private static final int SECONDARY = Color.rgb(178, 194, 214);
    private static final int ACCENT = Color.rgb(115, 199, 255);

    private enum Kind { WEATHER, SMALL, MINI, RAIN }

    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private Kind kind;
    private Button locationButton;
    private Dialog locationDialog;
    private int selectedLocation;
    private SeekBar sizeSlider;
    private TextView sizeValue;
    private Switch detailsSwitch;
    private Switch forecastSwitch;
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
        if (savedInstanceState != null) {
            cityId = savedInstanceState.getString(STATE_CITY_ID, cityId);
            scale = savedInstanceState.getInt(STATE_TEXT_SCALE, scale);
            details = savedInstanceState.getBoolean(STATE_DETAILS, details);
            forecast = savedInstanceState.getBoolean(STATE_FORECAST, forecast);
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
        state.putBoolean(STATE_PICKER_OPEN, locationDialog != null);
    }

    @Override
    protected void onDestroy() {
        if (locationDialog != null) locationDialog.dismiss();
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
        int defaultHeight = kind == Kind.MINI ? 56 : kind == Kind.RAIN ? 144 : 118;
        preview = new WidgetConfigurationPreview(this, options, kind == Kind.RAIN, defaultMode,
                defaultWidth, defaultHeight);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        previewParams.gravity = Gravity.CENTER_HORIZONTAL;
        previewParams.topMargin = dp(10);
        content.addView(preview, previewParams);

        buildOptions(content, cityId, scale, details, forecast);

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

        detailsSwitch = addSwitch(panel, label(kind == Kind.RAIN
                ? "Show rain amount" : "Show weather details"), details);
        forecastSwitch = addSwitch(panel, label(kind == Kind.RAIN
                ? "Show timeline legend" : "Show forecast"), forecast);
        if (kind != Kind.RAIN && preview.weatherMode == WeatherWidgetProvider.WidgetMode.MINI) {
            detailsSwitch.setVisibility(View.GONE);
        }
        if (kind != Kind.RAIN && !WeatherWidgetProvider.hasHourlyStrip(preview.weatherMode)) {
            forecastSwitch.setVisibility(View.GONE);
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
        return new WidgetConfiguration(cityIds.get(selectedLocation), sizeSlider.getProgress(),
                detailsSwitch.isChecked(), forecastSwitch.isChecked());
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
