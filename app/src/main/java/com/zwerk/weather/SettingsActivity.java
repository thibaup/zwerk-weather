package com.zwerk.weather;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.Os;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class SettingsActivity extends Activity {
    public static final String EXTRA_ACTION = "com.zwerk.weather.extra.SETTINGS_ACTION";
    public static final String EXTRA_SCENE = "com.zwerk.weather.extra.SETTINGS_SCENE";
    public static final String EXTRA_DAYTIME = "com.zwerk.weather.extra.SETTINGS_DAYTIME";
    public static final String EXTRA_CARD_TOP = "com.zwerk.weather.extra.SETTINGS_CARD_TOP";
    public static final String EXTRA_CARD_BOTTOM = "com.zwerk.weather.extra.SETTINGS_CARD_BOTTOM";
    public static final String EXTRA_TILE_TOP = "com.zwerk.weather.extra.SETTINGS_TILE_TOP";
    public static final String EXTRA_TILE_BOTTOM = "com.zwerk.weather.extra.SETTINGS_TILE_BOTTOM";
    public static final String EXTRA_ACCENT = "com.zwerk.weather.extra.SETTINGS_ACCENT";
    public static final String EXTRA_UNIT_CHANGED = "com.zwerk.weather.extra.UNIT_CHANGED";
    public static final String EXTRA_DISPLAY_UNIT_CHANGED = "com.zwerk.weather.extra.DISPLAY_UNIT_CHANGED";
    static final String EXTRA_AIR_QUALITY_CHANGED = "com.zwerk.weather.extra.AIR_QUALITY_CHANGED";
    static final String EXTRA_POLLEN_CHANGED = "com.zwerk.weather.extra.POLLEN_CHANGED";
    public static final String ACTION_REFRESH = "refresh";
    public static final String ACTION_DEVICE_LOCATION = "device_location";
    public static final String ACTION_ADVANCED_COORDINATES = "advanced_coordinates";
    public static final String ACTION_SELECTED_CITY = "selected_city";
    public static final String ACTION_PREFERENCES_CHANGED = "preferences_changed";
    public static final String ACTION_API_KEY_CHANGED = "api_key_changed";

    private static final String UI_PREFS = "WEATHER_UI";
    private static final String API_KEY_FILE = "weather_api_key";
    private static final String API_KEY_TEMP_FILE = "weather_api_key.tmp";
    private static final String API_KEY_GUIDE_URL =
            "https://developers.google.com/maps/documentation/weather/get-api-key";
    private static final String GOOGLE_CLOUD_CREDENTIALS_URL =
            "https://console.cloud.google.com/apis/credentials";
    private static final String PREF_ANIMATIONS = "weather_animations";
    private static final String PREF_RAIN_ALERTS = "rain_alerts";
    private static final String PREF_SEVERE_ALERTS = "severe_weather_alerts";
    private static final String PREF_TEMPERATURE_UNIT = "temperature_unit";
    private static final String PREF_WIND_UNIT = "wind_unit";
    private static final String PREF_PRESSURE_UNIT = "pressure_unit";
    private static final String PREF_VISIBILITY_UNIT = "visibility_unit";
    private static final String PREF_AIR_QUALITY = "google_air_quality_enabled";
    private static final String PREF_POLLEN = "google_pollen_enabled";
    private static final String PREF_SETTINGS_SCENE = "settings_scene";
    private static final String PREF_SETTINGS_DAYTIME = "settings_daytime";
    private static final String PREF_SETTINGS_CARD_TOP = "settings_card_top";
    private static final String PREF_SETTINGS_CARD_BOTTOM = "settings_card_bottom";
    private static final String PREF_SETTINGS_TILE_TOP = "settings_tile_top";
    private static final String PREF_SETTINGS_TILE_BOTTOM = "settings_tile_bottom";
    private static final String PREF_SETTINGS_ACCENT = "settings_accent";
    private static final String TEMP_CELSIUS = "C";
    private static final String TEMP_FAHRENHEIT = "F";
    private static final String WIND_KMH = "km/h";
    private static final String WIND_MPH = "mph";
    private static final String WIND_MS = "m/s";
    private static final String WIND_KNOTS = "knots";
    private static final String PRESSURE_HPA = "hPa";
    private static final String PRESSURE_INHG = "inHg";
    private static final String PRESSURE_MMHG = "mmHg";
    private static final String VISIBILITY_KM = "km";
    private static final String VISIBILITY_MI = "mi";

    private static final int PRIMARY = Color.rgb(247, 249, 253);
    private static final int SECONDARY = Color.rgb(204, 216, 230);

    private LinearLayout page;
    private SettingsBackdropView backdrop;
    private String scene = "day";
    private boolean daytime = true;
    private int surfaceTop = Color.argb(154, 72, 132, 205);
    private int surfaceBottom = Color.argb(126, 72, 118, 174);
    private int tileTop = Color.argb(146, 73, 133, 198);
    private int tileBottom = Color.argb(118, 67, 112, 167);
    private int accent = Color.rgb(151, 211, 255);
    private int scrimBase = Color.rgb(8, 55, 120);
    private boolean temperatureUnitChanged;
    private boolean displayUnitChanged;
    private boolean initialAirQualityEnabled;
    private boolean initialPollenEnabled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        readSceneStyle();
        normalizeUnitPreferences();
        SharedPreferences prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        initialAirQualityEnabled = prefs.getBoolean(PREF_AIR_QUALITY, false);
        initialPollenEnabled = prefs.getBoolean(PREF_POLLEN, false);
        configureWindow();
        buildUi();
        if (Build.VERSION.SDK_INT >= 33) {
            Api33BackNavigation.register(this);
        }
    }

    private void normalizeUnitPreferences() {
        SharedPreferences prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        String temperature = normalizeTemperatureUnit(
                prefs.getString(PREF_TEMPERATURE_UNIT, TEMP_CELSIUS));
        String wind = prefs.contains(PREF_WIND_UNIT)
                ? normalizeDisplayUnit(PREF_WIND_UNIT, prefs.getString(PREF_WIND_UNIT, WIND_KMH))
                : (TEMP_FAHRENHEIT.equals(temperature) ? WIND_MPH : WIND_KMH);
        String pressure = normalizeDisplayUnit(
                PREF_PRESSURE_UNIT, prefs.getString(PREF_PRESSURE_UNIT, PRESSURE_HPA));
        String visibility = prefs.contains(PREF_VISIBILITY_UNIT)
                ? normalizeDisplayUnit(
                        PREF_VISIBILITY_UNIT, prefs.getString(PREF_VISIBILITY_UNIT, VISIBILITY_KM))
                : (TEMP_FAHRENHEIT.equals(temperature) ? VISIBILITY_MI : VISIBILITY_KM);
        prefs.edit()
                .putString(PREF_TEMPERATURE_UNIT, temperature)
                .putString(PREF_WIND_UNIT, wind)
                .putString(PREF_PRESSURE_UNIT, pressure)
                .putString(PREF_VISIBILITY_UNIT, visibility)
                .apply();
    }

    private void readSceneStyle() {
        SharedPreferences prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        Intent intent = getIntent();
        scene = intent == null ? null : intent.getStringExtra(EXTRA_SCENE);
        if (scene == null || scene.trim().isEmpty()) {
            scene = prefs.getString(PREF_SETTINGS_SCENE, "day");
        }
        if (!isKnownScene(scene)) scene = "day";

        daytime = intent != null && intent.hasExtra(EXTRA_DAYTIME)
                ? intent.getBooleanExtra(EXTRA_DAYTIME, true)
                : prefs.getBoolean(PREF_SETTINGS_DAYTIME, !"night".equals(scene));

        int fallbackTop;
        int fallbackBottom;
        int fallbackTileTop;
        int fallbackTileBottom;
        if ("night".equals(scene)) {
            fallbackTop = Color.argb(164, 18, 44, 84);
            fallbackBottom = Color.argb(136, 8, 24, 52);
            fallbackTileTop = Color.argb(154, 20, 48, 90);
            fallbackTileBottom = Color.argb(124, 9, 27, 56);
            scrimBase = Color.rgb(3, 13, 31);
        } else if ("rain".equals(scene) || "thunder".equals(scene) || "fog".equals(scene)) {
            fallbackTop = Color.argb(166, 49, 70, 89);
            fallbackBottom = Color.argb(138, 23, 40, 56);
            fallbackTileTop = Color.argb(154, 51, 73, 94);
            fallbackTileBottom = Color.argb(124, 24, 43, 60);
            scrimBase = "thunder".equals(scene)
                    ? Color.rgb(8, 16, 30) : Color.rgb(26, 42, 55);
        } else if ("snow".equals(scene)) {
            fallbackTop = Color.argb(158, 82, 112, 138);
            fallbackBottom = Color.argb(130, 52, 80, 105);
            fallbackTileTop = Color.argb(148, 84, 116, 144);
            fallbackTileBottom = Color.argb(120, 54, 83, 110);
            scrimBase = Color.rgb(58, 84, 108);
        } else {
            fallbackTop = Color.argb(154, 72, 132, 205);
            fallbackBottom = Color.argb(126, 72, 118, 174);
            fallbackTileTop = Color.argb(146, 73, 133, 198);
            fallbackTileBottom = Color.argb(118, 67, 112, 167);
            scrimBase = Color.rgb(8, 55, 120);
        }

        surfaceTop = opaqueEnough(readColor(intent, prefs, EXTRA_CARD_TOP, PREF_SETTINGS_CARD_TOP, fallbackTop), 150);
        surfaceBottom = opaqueEnough(readColor(intent, prefs, EXTRA_CARD_BOTTOM, PREF_SETTINGS_CARD_BOTTOM, fallbackBottom), 120);
        tileTop = opaqueEnough(readColor(intent, prefs, EXTRA_TILE_TOP, PREF_SETTINGS_TILE_TOP, fallbackTileTop), 142);
        tileBottom = opaqueEnough(readColor(intent, prefs, EXTRA_TILE_BOTTOM, PREF_SETTINGS_TILE_BOTTOM, fallbackTileBottom), 112);
        accent = readColor(intent, prefs, EXTRA_ACCENT, PREF_SETTINGS_ACCENT, defaultAccent(scene));
    }

    private static boolean isKnownScene(String value) {
        return "day".equals(value)
                || "night".equals(value)
                || "rain".equals(value)
                || "thunder".equals(value)
                || "fog".equals(value)
                || "snow".equals(value);
    }

    private static int readColor(
            Intent intent,
            SharedPreferences prefs,
            String extraKey,
            String prefKey,
            int fallback) {
        if (intent != null && intent.hasExtra(extraKey)) {
            return intent.getIntExtra(extraKey, fallback);
        }
        return prefs.getInt(prefKey, fallback);
    }

    private static int opaqueEnough(int color, int minimumAlpha) {
        return Color.argb(
                Math.max(minimumAlpha, Color.alpha(color)),
                Color.red(color),
                Color.green(color),
                Color.blue(color));
    }

    private static int defaultAccent(String scene) {
        if ("night".equals(scene)) return Color.rgb(154, 202, 255);
        if ("thunder".equals(scene)) return Color.rgb(176, 213, 255);
        if ("rain".equals(scene) || "fog".equals(scene)) return Color.rgb(168, 218, 244);
        if ("snow".equals(scene)) return Color.rgb(220, 242, 255);
        return Color.rgb(151, 211, 255);
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 28) {
            window.setNavigationBarDividerColor(Color.TRANSPARENT);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        View decor = window.getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                        0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(scrimBase);

        backdrop = new SettingsBackdropView(this, scene, daytime);
        backdrop.setAnimationRunning(getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getBoolean(PREF_ANIMATIONS, true));
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        View scrim = new View(this);
        GradientDrawable scrimBackground = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{
                        Color.argb(104, Color.red(scrimBase), Color.green(scrimBase), Color.blue(scrimBase)),
                        Color.argb(142, 4, 12, 25),
                        Color.argb(178, 3, 9, 19)
                });
        scrim.setBackground(scrimBackground);
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), 0, dp(18), dp(32));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));

        SettingsBackButton back = new SettingsBackButton(this);
        back.setContentDescription("Back");
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(v -> handleBack());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(60)));

        TextView title = text("Settings", 25f, PRIMARY, false);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, dp(60), 1f);
        titleLp.leftMargin = dp(4);
        header.addView(title, titleLp);

        addSectionHeading("Zwerk Weather");
        addActionRow(
                "Google API key",
                hasConfiguredApiKey()
                        ? "Configured · tap to replace"
                        : "Not configured · tap to add",
                this::showApiKeyDialog);
        addActionRow(
                "Refresh forecast",
                "",
                () -> returnAction(ACTION_REFRESH));
        addActionRow(
                "Check for updates",
                "Checks GitHub Releases for a newer APK.",
                () -> UpdateChecker.checkForUpdates(this, true));
        addActionRow(
                "Advanced coordinates",
                "Choose a forecast point by latitude and longitude.",
                () -> returnAction(ACTION_ADVANCED_COORDINATES));

        addSectionHeading("Alerts");
        addSwitchRow(
                "Rain alerts",
                "",
                PREF_RAIN_ALERTS,
                false);
        addSwitchRow(
                "Severe weather alerts",
                "",
                PREF_SEVERE_ALERTS,
                false);

        addSectionHeading("Optional Google data");
        addSwitchRow(
                "Air Quality",
                "",
                PREF_AIR_QUALITY,
                false);
        addSwitchRow(
                "Pollen",
                "",
                PREF_POLLEN,
                false);

        addSectionHeading("Display");
        addTemperatureUnitRow();
        addChoiceUnitRow(
                "Wind speed",
                "",
                PREF_WIND_UNIT,
                new String[]{WIND_KMH, WIND_MPH, WIND_MS, WIND_KNOTS},
                new String[]{"km/h", "mph", "m/s", "knots"},
                WIND_KMH);
        addChoiceUnitRow(
                "Air pressure",
                "",
                PREF_PRESSURE_UNIT,
                new String[]{PRESSURE_HPA, PRESSURE_INHG, PRESSURE_MMHG},
                new String[]{"hPa", "inHg", "mmHg"},
                PRESSURE_HPA);
        addChoiceUnitRow(
                "Visibility",
                "",
                PREF_VISIBILITY_UNIT,
                new String[]{VISIBILITY_KM, VISIBILITY_MI},
                new String[]{"km", "mi"},
                VISIBILITY_KM);
        addSwitchRow(
                "Weather animations",
                "",
                PREF_ANIMATIONS,
                true);

        addActionRow(
                "Preview weather scenes",
                "",
                this::showScenePreview);

        TextView footer = text("Zwerk Weather 1.0.1", 12f, Color.argb(170, 210, 222, 236), false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(4), dp(28), dp(4), dp(8));
        page.addView(footer);

        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                top = bars.top;
                bottom = Math.max(bars.bottom, ime.bottom);
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            page.setPadding(dp(18), top, dp(18), bottom + dp(32));
            return insets;
        });
        root.requestApplyInsets();
    }

    private void showApiKeyDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(false);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setClipToPadding(false);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(16));
        panel.setBackground(previewDialogBackground());
        scroller.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Google API key", 22f, PRIMARY, false);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(title);

        TextView explanation = text(
                hasConfiguredApiKey()
                        ? "Enter a new key to replace the saved key. The field starts empty and stays masked; the saved key is never shown."
                        : "Enter a key to add it. It is saved only in this app's private storage on this device and is never shown after saving.",
                13f,
                SECONDARY,
                false);
        explanation.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams explanationLp = new LinearLayout.LayoutParams(-1, -2);
        explanationLp.topMargin = dp(4);
        panel.addView(explanation, explanationLp);

        LinearLayout helpLinks = new LinearLayout(this);
        helpLinks.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams helpLinksLp = new LinearLayout.LayoutParams(-1, dp(42));
        helpLinksLp.topMargin = dp(10);
        panel.addView(helpLinks, helpLinksLp);

        Button guide = previewActionButton("Setup guide ↗");
        guide.setOnClickListener(v -> openExternalUrl(API_KEY_GUIDE_URL));
        helpLinks.addView(guide, new LinearLayout.LayoutParams(0, dp(42), 1f));

        Button cloud = previewActionButton("Key settings ↗");
        cloud.setOnClickListener(v -> openExternalUrl(GOOGLE_CLOUD_CREDENTIALS_URL));
        LinearLayout.LayoutParams cloudLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        cloudLp.leftMargin = dp(8);
        helpLinks.addView(cloud, cloudLp);

        EditText field = apiKeyEditText();
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(-1, dp(52));
        fieldLp.topMargin = dp(16);
        panel.addView(field, fieldLp);

        TextView error = text("", 12f, Color.rgb(255, 207, 207), false);
        error.setVisibility(View.GONE);
        LinearLayout.LayoutParams errorLp = new LinearLayout.LayoutParams(-1, -2);
        errorLp.topMargin = dp(8);
        panel.addView(error, errorLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, dp(48));
        actionsLp.topMargin = dp(16);
        panel.addView(actions, actionsLp);

        Button cancel = previewActionButton("Cancel");
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        actions.addView(cancel, cancelLp);

        Button save = previewActionButton("Save");
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        saveLp.leftMargin = dp(10);
        actions.addView(save, saveLp);

        cancel.setOnClickListener(v -> {
            field.getText().clear();
            dialog.dismiss();
        });
        save.setOnClickListener(v -> {
            String candidate = validatedApiKeyInput(field);
            if (candidate.isEmpty()) {
                showApiKeySaveFailure(error);
                return;
            }
            try {
                writeApiKeyAtomically(candidate);
                field.getText().clear();
                error.setVisibility(View.GONE);
                dialog.dismiss();
                returnAction(ACTION_API_KEY_CHANGED);
            } catch (Exception ignored) {
                showApiKeySaveFailure(error);
            }
        });
        field.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                save.performClick();
                return true;
            }
            return false;
        });
        dialog.setOnDismissListener(ignored -> field.getText().clear());

        dialog.setContentView(scroller);
        dialog.show();

        Window dialogWindow = dialog.getWindow();
        if (dialogWindow != null) {
            dialogWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = dialogWindow.getAttributes();
            attributes.dimAmount = 0.72f;
            dialogWindow.setAttributes(attributes);
            dialogWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            dialogWindow.setGravity(Gravity.CENTER);
            dialogWindow.getDecorView().setPadding(0, 0, 0, 0);

            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int dialogWidth = Math.min(screenWidth - dp(28), dp(440));
            dialogWindow.setLayout(
                    Math.max(1, dialogWidth),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        field.requestFocus();
    }

    private EditText apiKeyEditText() {
        EditText field = new EditText(this);
        field.setHint("API key");
        field.setTextColor(PRIMARY);
        field.setHintTextColor(Color.argb(145, 220, 231, 244));
        field.setTextSize(16f);
        field.setSingleLine(true);
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        int imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        if (Build.VERSION.SDK_INT >= 26) {
            imeOptions |= android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        }
        field.setImeOptions(imeOptions);
        field.setBackground(apiKeyFieldBackground());
        field.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 26) {
            field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            field.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO);
        }
        return field;
    }

    private StateListDrawable apiKeyFieldBackground() {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Color.argb(46, 255, 255, 255));
        focused.setCornerRadius(dp(14));
        focused.setStroke(dp(1), Color.argb(
                220, Color.red(accent), Color.green(accent), Color.blue(accent)));

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.argb(27, 255, 255, 255));
        normal.setCornerRadius(dp(14));
        normal.setStroke(dp(1), Color.argb(58, 255, 255, 255));

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, normal);
        return states;
    }

    private static String validatedApiKeyInput(EditText field) {
        if (field == null || field.getText() == null) return "";
        String value = field.getText().toString().trim();
        if (value.length() < 8 || value.length() > 512) return "";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) return "";
        }
        return value;
    }

    private void showApiKeySaveFailure(TextView error) {
        error.setText("Could not save the key. Check the value and try again.");
        error.setVisibility(View.VISIBLE);
    }

    private void openExternalUrl(String address) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address)));
        } catch (Exception ignored) {
            Toast.makeText(this, "No browser is available to open this link.", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean hasConfiguredApiKey() {
        File keyFile = new File(getFilesDir(), API_KEY_FILE);
        if (!keyFile.isFile()) return false;
        try (BufferedReader reader = new BufferedReader(new FileReader(keyFile))) {
            String line = reader.readLine();
            return line != null && !line.trim().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void writeApiKeyAtomically(String value) throws Exception {
        File target = new File(getFilesDir(), API_KEY_FILE);
        File temp = new File(getFilesDir(), API_KEY_TEMP_FILE);
        if (temp.exists() && !temp.delete()) {
            throw new IOException("Could not prepare private credential file");
        }
        try {
            try (FileOutputStream out = openFileOutput(API_KEY_TEMP_FILE, MODE_PRIVATE)) {
                out.write(value.getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
            Os.rename(temp.getAbsolutePath(), target.getAbsolutePath());
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    private void showScenePreview() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setClipToPadding(false);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(16));
        panel.setBackground(previewDialogBackground());
        scroller.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Preview weather scenes", 22f, PRIMARY, false);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(title);

        boolean animate = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getBoolean(PREF_ANIMATIONS, true);

        ScenePreviewView preview = new ScenePreviewView(this);
        preview.setAnimationRunning(animate);
        preview.setScene("day", "Clear day");
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1, dp(184));
        previewLp.topMargin = dp(14);
        panel.addView(preview, previewLp);

        TextView selectedLabel = text("Clear day", 14f, PRIMARY, true);
        selectedLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams selectedLp = new LinearLayout.LayoutParams(-1, -2);
        selectedLp.topMargin = dp(9);
        panel.addView(selectedLabel, selectedLp);

        String[] sceneKeys = {"day", "night", "rain", "thunder", "fog", "snow"};
        String[] sceneLabels = {"Clear day", "Night", "Rain", "Thunder", "Fog", "Snow"};
        Button[] sceneButtons = new Button[sceneKeys.length];

        LinearLayout sceneGrid = new LinearLayout(this);
        sceneGrid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(-1, -2);
        gridLp.topMargin = dp(10);
        panel.addView(sceneGrid, gridLp);

        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            if (rowIndex > 0) {
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, dp(46));
                rowLp.topMargin = dp(8);
                sceneGrid.addView(row, rowLp);
            } else {
                sceneGrid.addView(row, new LinearLayout.LayoutParams(-1, dp(46)));
            }

            for (int column = 0; column < 3; column++) {
                final int index = rowIndex * 3 + column;
                Button chip = sceneChip(sceneLabels[index]);
                sceneButtons[index] = chip;
                LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
                if (column > 0) chipLp.leftMargin = dp(8);
                row.addView(chip, chipLp);

                chip.setOnClickListener(v -> {
                    preview.setScene(sceneKeys[index], sceneLabels[index]);
                    selectedLabel.setText(sceneLabels[index]);
                    for (int i = 0; i < sceneButtons.length; i++) {
                        updateSceneChip(sceneButtons[i], sceneLabels[i], i == index);
                    }
                });
            }
        }
        for (int i = 0; i < sceneButtons.length; i++) {
            updateSceneChip(sceneButtons[i], sceneLabels[i], i == 0);
        }

        Button close = previewActionButton("Close");
        close.setContentDescription("Close weather scene preview");
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(-1, dp(48));
        closeLp.topMargin = dp(14);
        panel.addView(close, closeLp);
        close.setOnClickListener(v -> dialog.dismiss());

        dialog.setOnDismissListener(ignored -> preview.setAnimationRunning(false));
        dialog.setContentView(scroller);
        dialog.show();

        Window dialogWindow = dialog.getWindow();
        if (dialogWindow != null) {
            dialogWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = dialogWindow.getAttributes();
            attributes.dimAmount = 0.72f;
            dialogWindow.setAttributes(attributes);
            dialogWindow.setGravity(Gravity.CENTER);
            dialogWindow.getDecorView().setPadding(0, 0, 0, 0);

            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int dialogWidth = Math.min(screenWidth - dp(28), dp(440));
            dialogWindow.setLayout(
                    Math.max(1, dialogWidth),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private GradientDrawable previewDialogBackground() {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{
                        opaqueEnough(surfaceTop, 226),
                        opaqueEnough(surfaceBottom, 236)
                });
        background.setCornerRadius(dp(24));
        background.setStroke(dp(1), Color.argb(62, 255, 255, 255));
        return background;
    }

    private Button sceneChip(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12f);
        button.setTextColor(PRIMARY);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setMinHeight(dp(46));
        return button;
    }

    private void updateSceneChip(Button button, String label, boolean selected) {
        if (button == null) return;
        button.setSelected(selected);
        button.setBackground(sceneChipBackground(selected));
        button.setAlpha(selected ? 1f : 0.84f);
        button.setContentDescription(label + " scene preview, "
                + (selected ? "selected" : "not selected"));
        if (Build.VERSION.SDK_INT >= 30) {
            button.setStateDescription(selected ? "Selected" : "Not selected");
        }
    }

    private StateListDrawable sceneChipBackground(boolean selected) {
        int normalColor = selected
                ? Color.argb(108, Color.red(accent), Color.green(accent), Color.blue(accent))
                : Color.argb(32, 255, 255, 255);
        int pressedColor = selected
                ? Color.argb(148, Color.red(accent), Color.green(accent), Color.blue(accent))
                : Color.argb(54, 255, 255, 255);

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(normalColor);
        normal.setCornerRadius(dp(14));
        if (selected) {
            normal.setStroke(dp(1), Color.argb(
                    205, Color.red(accent), Color.green(accent), Color.blue(accent)));
        }

        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(pressedColor);
        pressed.setCornerRadius(dp(14));

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{android.R.attr.state_focused}, pressed);
        states.addState(new int[]{}, normal);
        return states;
    }

    private Button previewActionButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setTextColor(PRIMARY);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setPadding(dp(12), 0, dp(12), 0);

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.argb(
                188, Color.red(accent), Color.green(accent), Color.blue(accent)));
        normal.setCornerRadius(dp(14));

        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(Color.argb(
                225, Color.red(accent), Color.green(accent), Color.blue(accent)));
        pressed.setCornerRadius(dp(14));

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{android.R.attr.state_focused}, pressed);
        states.addState(new int[]{}, normal);
        button.setBackground(states);
        return button;
    }

    private final class ScenePreviewView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect sourceRect = new Rect();
        private final RectF destinationRect = new RectF();
        private final Path clipPath = new Path();
        private final Path fogPath = new Path();
        private final Path stormPath = new Path();
        private final Path lightningPath = new Path();
        private final Bitmap daySky = BitmapFactory.decodeResource(
                getResources(), R.drawable.weather_sky_day);
        private final Bitmap nightSky = BitmapFactory.decodeResource(
                getResources(), R.drawable.weather_sky_night);
        private final Bitmap rainSky = BitmapFactory.decodeResource(
                getResources(), R.drawable.weather_sky_rain);
        private final Bitmap snowSky = BitmapFactory.decodeResource(
                getResources(), R.drawable.weather_sky_snow);
        private final float[] particleX = new float[66];
        private final float[] particleY = new float[66];
        private final float[] particleSpeed = new float[66];
        private final float[] particleSize = new float[66];
        private final Shader[] fogBankShaders = new Shader[4];

        private String scene = "day";
        private boolean animationRunning = true;
        private long frozenTime = SystemClock.uptimeMillis();
        private long thunderStarted = frozenTime;
        private Shader stormDepthShader;
        private Shader fogWashShader;

        ScenePreviewView(Context context) {
            super(context);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            for (int i = 0; i < particleX.length; i++) {
                particleX[i] = ((i * 37) % 97) / 97f;
                particleY[i] = ((i * 53) % 101) / 101f;
                particleSpeed[i] = 0.25f + (((i * 29) % 41) / 82f);
                particleSize[i] = 0.72f + (((i * 17) % 23) / 20f);
            }
        }

        void setScene(String nextScene, String label) {
            String oldScene = scene;
            scene = nextScene == null ? "day" : nextScene;
            if ("thunder".equals(scene) && !"thunder".equals(oldScene)) {
                thunderStarted = visualNow();
            }
            setContentDescription("Previewing " + label + " weather scene");
            invalidate();
            if (animationRunning && hasAnimatedOverlay()) {
                postInvalidateOnAnimation();
            }
        }

        void setAnimationRunning(boolean running) {
            if (animationRunning == running) {
                if (running && hasAnimatedOverlay()) postInvalidateOnAnimation();
                return;
            }
            animationRunning = running;
            if (!running) frozenTime = SystemClock.uptimeMillis();
            if (running && hasAnimatedOverlay()) {
                postInvalidateOnAnimation();
            } else {
                invalidate();
            }
        }

        private boolean hasAnimatedOverlay() {
            return "rain".equals(scene)
                    || "thunder".equals(scene)
                    || "fog".equals(scene)
                    || "snow".equals(scene);
        }

        private long visualNow() {
            return animationRunning ? SystemClock.uptimeMillis() : frozenTime;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w <= 0 || h <= 0) return;
            stormDepthShader = new LinearGradient(
                    0, 0, 0, h,
                    new int[]{Color.argb(82, 4, 10, 24), Color.argb(44, 8, 20, 42), Color.argb(70, 3, 9, 22)},
                    new float[]{0f, 0.52f, 1f},
                    Shader.TileMode.CLAMP);
            fogWashShader = new LinearGradient(
                    0, 0, 0, h,
                    new int[]{Color.argb(8, 232, 241, 248), Color.argb(27, 232, 241, 248), Color.argb(13, 224, 236, 246)},
                    new float[]{0f, 0.57f, 1f},
                    Shader.TileMode.CLAMP);
            buildFogShaders(h);
        }

        private void buildFogShaders(float h) {
            for (int i = 0; i < fogBankShaders.length; i++) {
                float topFraction;
                float heightFraction;
                int alpha;
                switch (i) {
                    case 0:
                        topFraction = 0.08f;
                        heightFraction = 0.22f;
                        alpha = 42;
                        break;
                    case 1:
                        topFraction = 0.28f;
                        heightFraction = 0.25f;
                        alpha = 56;
                        break;
                    case 2:
                        topFraction = 0.50f;
                        heightFraction = 0.20f;
                        alpha = 38;
                        break;
                    default:
                        topFraction = 0.68f;
                        heightFraction = 0.24f;
                        alpha = 48;
                        break;
                }
                float top = h * topFraction;
                float bottom = top + h * heightFraction;
                fogBankShaders[i] = new LinearGradient(
                        0, top, 0, bottom,
                        new int[]{
                                Color.argb(0, 244, 250, 255),
                                Color.argb(alpha, 244, 250, 255),
                                Color.argb(Math.max(1, alpha - 6), 232, 241, 248),
                                Color.argb(0, 232, 241, 248)},
                        new float[]{0f, 0.30f, 0.68f, 1f},
                        Shader.TileMode.CLAMP);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            destinationRect.set(0, 0, w, h);
            clipPath.reset();
            clipPath.addRoundRect(
                    destinationRect,
                    dp(22),
                    dp(22),
                    Path.Direction.CW);
            int save = canvas.save();
            canvas.clipPath(clipPath);

            Bitmap sky = daySky;
            if ("night".equals(scene)) {
                sky = nightSky;
            } else if ("rain".equals(scene) || "thunder".equals(scene) || "fog".equals(scene)) {
                sky = rainSky;
            } else if ("snow".equals(scene)) {
                sky = snowSky;
            }

            if (sky != null) {
                drawCover(canvas, sky, w, h);
            } else {
                canvas.drawColor(Color.rgb(29, 63, 103));
            }

            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor("night".equals(scene)
                    ? Color.argb(42, 0, 7, 20)
                    : Color.argb(22, 0, 26, 70));
            canvas.drawRect(0, 0, w, h, paint);

            long now = visualNow();
            if ("thunder".equals(scene)) {
                drawThunderAtmosphere(canvas, w, h, now);
                drawRain(canvas, w, h, now);
                if (animationRunning) {
                    drawLightningBolt(canvas, w, h, now);
                    drawLightningFlash(canvas, w, h, now);
                }
            } else if ("rain".equals(scene)) {
                drawRain(canvas, w, h, now);
            } else if ("snow".equals(scene)) {
                drawSnow(canvas, w, h, now);
            } else if ("fog".equals(scene)) {
                drawFog(canvas, w, h, now);
            }

            canvas.restoreToCount(save);
            if (animationRunning && hasAnimatedOverlay()) {
                postInvalidateOnAnimation();
            }
        }

        private void drawCover(Canvas canvas, Bitmap bitmap, float w, float h) {
            if (bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return;
            float destinationRatio = w / h;
            float sourceRatio = bitmap.getWidth() / (float) bitmap.getHeight();
            if (sourceRatio > destinationRatio) {
                int sourceWidth = Math.round(bitmap.getHeight() * destinationRatio);
                int left = (bitmap.getWidth() - sourceWidth) / 2;
                sourceRect.set(left, 0, left + sourceWidth, bitmap.getHeight());
            } else {
                int sourceHeight = Math.round(bitmap.getWidth() / destinationRatio);
                int top = (bitmap.getHeight() - sourceHeight) / 2;
                sourceRect.set(0, top, bitmap.getWidth(), top + sourceHeight);
            }
            destinationRect.set(0, 0, w, h);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(255);
            canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint);
        }

        private void drawRain(Canvas canvas, float w, float h, long now) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.1f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(Color.argb(118, 208, 231, 255));
            float seconds = now / 1000f;
            for (int i = 0; i < particleX.length; i++) {
                float x = particleX[i] * w;
                float y = ((particleY[i] + seconds * particleSpeed[i]) % 1.12f) * h - h * 0.08f;
                float length = dp(8f + particleSize[i] * 7f);
                canvas.drawLine(x, y, x - length * 0.27f, y + length, paint);
            }
        }

        private void drawSnow(Canvas canvas, float w, float h, long now) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(205, 255, 255, 255));
            float seconds = now / 1000f;
            for (int i = 0; i < particleX.length; i++) {
                float x = particleX[i] * w
                        + (float) Math.sin(seconds * 0.72f + i) * dp(6f);
                float y = ((particleY[i] + seconds * particleSpeed[i] * 0.16f) % 1.08f) * h
                        - h * 0.04f;
                canvas.drawCircle(x, y, dp(particleSize[i] * 1.2f), paint);
            }
        }

        private void drawThunderAtmosphere(Canvas canvas, float w, float h, long now) {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(stormDepthShader);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            float seconds = now / 1000f;
            for (int i = 0; i < 3; i++) {
                float drift = (float) Math.sin(seconds * (0.12f + i * 0.020f) + i * 1.8f)
                        * w * (0.058f + i * 0.012f);
                float y = h * (0.12f + i * 0.24f)
                        + (float) Math.sin(seconds * (0.08f + i * 0.012f) + i)
                        * h * 0.020f;
                float bandHeight = h * (0.15f + i * 0.018f);
                float left = -w * 0.38f + drift;
                float right = w * 1.38f + drift;

                stormPath.reset();
                stormPath.moveTo(left, y + bandHeight * 0.30f);
                stormPath.cubicTo(
                        left + w * 0.38f, y - bandHeight * 0.10f,
                        left + w * 0.72f, y + bandHeight * 0.10f,
                        left + w, y + bandHeight * 0.20f);
                stormPath.cubicTo(
                        left + w * 1.24f, y + bandHeight * 0.30f,
                        left + w * 1.50f, y - bandHeight * 0.05f,
                        right, y + bandHeight * 0.24f);
                stormPath.lineTo(right, y + bandHeight * 0.86f);
                stormPath.cubicTo(
                        left + w * 1.50f, y + bandHeight * 1.05f,
                        left + w * 1.18f, y + bandHeight * 0.74f,
                        left + w, y + bandHeight * 0.80f);
                stormPath.cubicTo(
                        left + w * 0.66f, y + bandHeight * 0.96f,
                        left + w * 0.34f, y + bandHeight * 0.70f,
                        left, y + bandHeight * 0.84f);
                stormPath.close();

                paint.setColor(Color.argb(22 + i * 7, 4, 12 + i * 4, 28 + i * 7));
                canvas.drawPath(stormPath, paint);
            }
        }

        private void drawFog(Canvas canvas, float w, float h, long now) {
            float seconds = now / 1000f;
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < fogBankShaders.length; i++) {
                float topFraction;
                float heightFraction;
                float speed;
                switch (i) {
                    case 0:
                        topFraction = 0.08f;
                        heightFraction = 0.22f;
                        speed = 0.060f;
                        break;
                    case 1:
                        topFraction = 0.28f;
                        heightFraction = 0.25f;
                        speed = -0.042f;
                        break;
                    case 2:
                        topFraction = 0.50f;
                        heightFraction = 0.20f;
                        speed = 0.034f;
                        break;
                    default:
                        topFraction = 0.68f;
                        heightFraction = 0.24f;
                        speed = -0.027f;
                        break;
                }
                float top = h * topFraction;
                float bankHeight = h * heightFraction;
                float drift = (float) Math.sin(seconds * speed + i * 1.7f) * w * 0.13f;
                float left = -w * 0.48f + drift;
                float right = w * 1.48f + drift;

                fogPath.reset();
                fogPath.moveTo(left, top + bankHeight * 0.34f);
                fogPath.cubicTo(
                        left + w * 0.36f, top - bankHeight * 0.05f,
                        left + w * 0.68f, top + bankHeight * 0.05f,
                        left + w, top + bankHeight * 0.22f);
                fogPath.cubicTo(
                        left + w * 1.24f, top + bankHeight * 0.34f,
                        left + w * 1.58f, top - bankHeight * 0.02f,
                        right, top + bankHeight * 0.26f);
                fogPath.lineTo(right, top + bankHeight * 0.82f);
                fogPath.cubicTo(
                        left + w * 1.56f, top + bankHeight * 1.00f,
                        left + w * 1.28f, top + bankHeight * 0.72f,
                        left + w, top + bankHeight * 0.80f);
                fogPath.cubicTo(
                        left + w * 0.70f, top + bankHeight * 0.96f,
                        left + w * 0.34f, top + bankHeight * 0.73f,
                        left, top + bankHeight * 0.84f);
                fogPath.close();

                paint.setShader(fogBankShaders[i]);
                canvas.drawPath(fogPath, paint);
            }
            paint.setShader(fogWashShader);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            paint.setAlpha(255);
        }

        private float lightningStrength(long now) {
            long elapsed = Math.max(0L, now - thunderStarted);
            long phase = elapsed % 5200L;
            if (phase < 82L) {
                return 1f - phase / 100f;
            }
            if (phase >= 118L && phase < 192L) {
                return 0.54f * (1f - (phase - 118L) / 74f);
            }
            return 0f;
        }

        private void buildLightningPath(float w, float h, long now) {
            long eventIndex = Math.max(0L, now - thunderStarted) / 5200L;
            float anchor = 0.40f + ((eventIndex * 31L) % 19L) / 100f;
            float x0 = w * anchor;

            lightningPath.reset();
            lightningPath.moveTo(x0, h * 0.06f);
            lightningPath.lineTo(x0 - w * 0.035f, h * 0.24f);
            lightningPath.lineTo(x0 + w * 0.012f, h * 0.36f);
            lightningPath.lineTo(x0 - w * 0.050f, h * 0.52f);
            lightningPath.lineTo(x0 - w * 0.018f, h * 0.68f);
            lightningPath.lineTo(x0 - w * 0.072f, h * 0.87f);

            lightningPath.moveTo(x0 + w * 0.002f, h * 0.35f);
            lightningPath.lineTo(x0 + w * 0.105f, h * 0.43f);
            lightningPath.lineTo(x0 + w * 0.145f, h * 0.55f);

            lightningPath.moveTo(x0 - w * 0.045f, h * 0.52f);
            lightningPath.lineTo(x0 - w * 0.145f, h * 0.61f);
            lightningPath.lineTo(x0 - w * 0.188f, h * 0.73f);
        }

        private void drawLightningBolt(Canvas canvas, float w, float h, long now) {
            float strength = lightningStrength(now);
            if (strength <= 0f) return;
            buildLightningPath(w, h, now);

            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(dp(6f));
            paint.setColor(Color.argb(Math.round(62 * strength), 188, 220, 255));
            canvas.drawPath(lightningPath, paint);

            paint.setStrokeWidth(dp(1.5f));
            paint.setColor(Color.argb(Math.round(225 * strength), 238, 248, 255));
            canvas.drawPath(lightningPath, paint);
        }

        private void drawLightningFlash(Canvas canvas, float w, float h, long now) {
            float strength = lightningStrength(now);
            if (strength <= 0f) return;
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(Math.round(105 * strength), 230, 240, 255));
            canvas.drawRect(0, 0, w, h, paint);
        }
    }

    private final class SettingsBackButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        SettingsBackButton(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() * 0.50f;
            float cy = getHeight() * 0.50f;
            if (isPressed()) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(28, 255, 255, 255));
                canvas.drawCircle(cx, cy, dp(18), paint);
            }
            path.reset();
            path.moveTo(cx + dp(4), cy - dp(8));
            path.lineTo(cx - dp(4), cy);
            path.lineTo(cx + dp(4), cy + dp(8));
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(dp(2.4f));
            paint.setColor(PRIMARY);
            canvas.drawPath(path, paint);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }
    }

    private final class SettingsBackdropView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect source = new Rect();
        private final RectF destination = new RectF();
        private final Path path = new Path();
        private final Bitmap daySky = BitmapFactory.decodeResource(
                getResources(), R.drawable.weather_sky_day);
        private final Bitmap nightSky = BitmapFactory.decodeResource(
                getResources(), R.drawable.weather_sky_night);
        private final Bitmap rainSky = BitmapFactory.decodeResource(
                getResources(), R.drawable.weather_sky_rain);
        private final Bitmap snowSky = BitmapFactory.decodeResource(
                getResources(), R.drawable.weather_sky_snow);
        private final float[] particleX = new float[78];
        private final float[] particleY = new float[78];
        private final float[] particleSpeed = new float[78];
        private boolean animationRunning = true;
        private long frozenTime = SystemClock.uptimeMillis();
        private long thunderStarted = frozenTime;
        private final String sceneKey;
        private final boolean sceneDaytime;

        SettingsBackdropView(Context context, String sceneKey, boolean sceneDaytime) {
            super(context);
            this.sceneKey = sceneKey == null ? "day" : sceneKey;
            this.sceneDaytime = sceneDaytime;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            for (int i = 0; i < particleX.length; i++) {
                particleX[i] = ((i * 37) % 97) / 97f;
                particleY[i] = ((i * 53) % 101) / 101f;
                particleSpeed[i] = 0.22f + (((i * 29) % 41) / 82f);
            }
        }

        void setAnimationRunning(boolean running) {
            if (animationRunning == running) {
                if (running && isAnimatedScene()) postInvalidateOnAnimation();
                return;
            }
            animationRunning = running;
            if (!running) {
                frozenTime = SystemClock.uptimeMillis();
                invalidate();
            } else {
                thunderStarted = SystemClock.uptimeMillis();
                if (isAnimatedScene()) postInvalidateOnAnimation();
            }
        }

        private boolean isAnimatedScene() {
            return "rain".equals(sceneKey)
                    || "thunder".equals(sceneKey)
                    || "fog".equals(sceneKey)
                    || "snow".equals(sceneKey);
        }

        private long visualNow() {
            return animationRunning ? SystemClock.uptimeMillis() : frozenTime;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            Bitmap sky;
            if ("night".equals(sceneKey)) {
                sky = nightSky;
            } else if ("snow".equals(sceneKey)) {
                sky = snowSky;
            } else if ("rain".equals(sceneKey)
                    || "thunder".equals(sceneKey)
                    || "fog".equals(sceneKey)) {
                sky = sceneDaytime ? rainSky : nightSky;
            } else {
                sky = daySky;
            }

            if (sky != null) {
                drawCover(canvas, sky, w, h);
            } else {
                canvas.drawColor(scrimBase);
            }

            paint.setShader(new LinearGradient(
                    0, 0, 0, h,
                    new int[]{
                            Color.argb(34, 0, 24, 65),
                            Color.argb(50, 3, 20, 48),
                            Color.argb(88, 3, 10, 24)
                    },
                    new float[]{0f, 0.52f, 1f},
                    Shader.TileMode.CLAMP));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            long now = visualNow();
            if ("thunder".equals(sceneKey)) {
                drawStormDepth(canvas, w, h, now);
                drawRain(canvas, w, h, now);
                if (animationRunning) drawLightning(canvas, w, h, now);
            } else if ("rain".equals(sceneKey)) {
                drawRain(canvas, w, h, now);
            } else if ("snow".equals(sceneKey)) {
                drawSnow(canvas, w, h, now);
            } else if ("fog".equals(sceneKey)) {
                drawFog(canvas, w, h, now);
            }

            if (animationRunning && isAnimatedScene()) {
                postInvalidateOnAnimation();
            }
        }

        private void drawCover(Canvas canvas, Bitmap bitmap, float w, float h) {
            float destinationRatio = w / h;
            float sourceRatio = bitmap.getWidth() / (float) bitmap.getHeight();
            if (sourceRatio > destinationRatio) {
                int sourceWidth = Math.round(bitmap.getHeight() * destinationRatio);
                int left = (bitmap.getWidth() - sourceWidth) / 2;
                source.set(left, 0, left + sourceWidth, bitmap.getHeight());
            } else {
                int sourceHeight = Math.round(bitmap.getWidth() / destinationRatio);
                int top = (bitmap.getHeight() - sourceHeight) / 2;
                source.set(0, top, bitmap.getWidth(), top + sourceHeight);
            }
            destination.set(0, 0, w, h);
            paint.setShader(null);
            paint.setAlpha(255);
            canvas.drawBitmap(bitmap, source, destination, paint);
        }

        private void drawRain(Canvas canvas, float w, float h, long now) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.05f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(Color.argb(86, 208, 231, 255));
            float seconds = now / 1000f;
            for (int i = 0; i < particleX.length; i++) {
                float x = particleX[i] * w;
                float y = ((particleY[i] + seconds * particleSpeed[i]) % 1.10f) * h - h * 0.05f;
                canvas.drawLine(x, y, x - dp(2.5f), y + dp(12f), paint);
            }
        }

        private void drawSnow(Canvas canvas, float w, float h, long now) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(158, 255, 255, 255));
            float seconds = now / 1000f;
            for (int i = 0; i < particleX.length; i++) {
                float x = particleX[i] * w
                        + (float) Math.sin(seconds * 0.68f + i) * dp(6f);
                float y = ((particleY[i] + seconds * particleSpeed[i] * 0.15f) % 1.06f) * h;
                canvas.drawCircle(x, y, dp(1.0f + (i % 4) * 0.28f), paint);
            }
        }

        private void drawFog(Canvas canvas, float w, float h, long now) {
            float seconds = now / 1000f;
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 4; i++) {
                float center = h * (0.18f + i * 0.22f);
                float drift = (float) Math.sin(seconds * (0.05f + i * 0.012f) + i)
                        * w * 0.10f;
                path.reset();
                path.moveTo(-w * 0.35f + drift, center);
                path.cubicTo(
                        w * 0.12f + drift, center - h * 0.06f,
                        w * 0.54f + drift, center + h * 0.05f,
                        w * 1.35f + drift, center - h * 0.01f);
                path.lineTo(w * 1.35f + drift, center + h * 0.11f);
                path.cubicTo(
                        w * 0.80f + drift, center + h * 0.16f,
                        w * 0.24f + drift, center + h * 0.08f,
                        -w * 0.35f + drift, center + h * 0.13f);
                path.close();
                paint.setColor(Color.argb(24 + i * 6, 238, 246, 252));
                canvas.drawPath(path, paint);
            }
            paint.setColor(Color.argb(20, 232, 241, 248));
            canvas.drawRect(0, h * 0.10f, w, h * 0.92f, paint);
        }

        private void drawStormDepth(Canvas canvas, float w, float h, long now) {
            paint.setShader(new LinearGradient(
                    0, 0, 0, h,
                    new int[]{
                            Color.argb(96, 4, 10, 24),
                            Color.argb(52, 8, 20, 42),
                            Color.argb(84, 3, 9, 22)
                    },
                    null,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            float seconds = now / 1000f;
            for (int i = 0; i < 3; i++) {
                float y = h * (0.14f + i * 0.25f)
                        + (float) Math.sin(seconds * 0.08f + i) * h * 0.018f;
                path.reset();
                path.moveTo(-w * 0.2f, y);
                path.cubicTo(w * 0.2f, y - h * 0.05f, w * 0.62f, y + h * 0.04f, w * 1.2f, y);
                path.lineTo(w * 1.2f, y + h * 0.10f);
                path.cubicTo(w * 0.7f, y + h * 0.16f, w * 0.2f, y + h * 0.08f, -w * 0.2f, y + h * 0.12f);
                path.close();
                paint.setColor(Color.argb(24 + i * 7, 4, 12 + i * 4, 30 + i * 7));
                canvas.drawPath(path, paint);
            }
        }

        private void drawLightning(Canvas canvas, float w, float h, long now) {
            long phase = Math.max(0L, now - thunderStarted) % 5200L;
            float strength = 0f;
            if (phase < 86L) strength = 1f - phase / 100f;
            else if (phase >= 122L && phase < 192L) {
                strength = 0.50f * (1f - (phase - 122L) / 70f);
            }
            if (strength <= 0f) return;

            float x = w * 0.54f;
            path.reset();
            path.moveTo(x, h * 0.04f);
            path.lineTo(x - w * 0.04f, h * 0.19f);
            path.lineTo(x + w * 0.01f, h * 0.29f);
            path.lineTo(x - w * 0.05f, h * 0.44f);
            path.lineTo(x - w * 0.01f, h * 0.58f);
            path.moveTo(x + w * 0.005f, h * 0.29f);
            path.lineTo(x + w * 0.10f, h * 0.37f);
            path.lineTo(x + w * 0.14f, h * 0.48f);

            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(dp(5f));
            paint.setColor(Color.argb(Math.round(54 * strength), 190, 222, 255));
            canvas.drawPath(path, paint);
            paint.setStrokeWidth(dp(1.3f));
            paint.setColor(Color.argb(Math.round(215 * strength), 240, 248, 255));
            canvas.drawPath(path, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(Math.round(72 * strength), 230, 240, 255));
            canvas.drawRect(0, 0, w, h, paint);
        }
    }

    private void addSectionHeading(String value) {
        TextView heading = text(value, 13f, SECONDARY, true);
        heading.setPadding(dp(4), dp(page.getChildCount() <= 1 ? 8 : 16), dp(4), dp(7));
        page.addView(heading);
    }

    private void addActionRow(String title, String subtitle, Runnable action) {
        LinearLayout row = surface();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(12), dp(10));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(surfaceBackground(true));
        String helper = subtitle == null ? "" : subtitle.trim();
        row.setContentDescription(helper.isEmpty() ? title : title + ". " + helper);
        row.setOnClickListener(v -> action.run());

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView primary = text(title, 17f, PRIMARY, false);
        labels.addView(primary);
        if (!helper.isEmpty()) {
            TextView secondary = text(helper, 13f, SECONDARY, false);
            secondary.setPadding(0, dp(2), dp(8), 0);
            labels.addView(secondary);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView chevron = text("›", 28f, Color.argb(210, 220, 231, 244), false);
        chevron.setGravity(Gravity.CENTER);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(34), dp(46)));
        page.addView(row, surfaceParams());
    }

    private void addSwitchRow(
            String title,
            String subtitle,
            String preferenceKey,
            boolean defaultValue) {
        LinearLayout row = surface();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(11), dp(10));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView primary = text(title, 17f, PRIMARY, false);
        labels.addView(primary);
        String helper = subtitle == null ? "" : subtitle.trim();
        if (!helper.isEmpty()) {
            TextView secondary = text(helper, 13f, SECONDARY, false);
            secondary.setPadding(0, dp(2), dp(8), 0);
            labels.addView(secondary);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

        Switch toggle = new Switch(this);
        toggle.setShowText(false);
        toggle.setChecked(getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getBoolean(preferenceKey, defaultValue));
        toggle.setContentDescription(title);
        applySwitchPalette(toggle);
        if (Build.VERSION.SDK_INT >= 30) {
            toggle.setStateDescription(toggle.isChecked() ? "On" : "Off");
        }
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(preferenceKey, isChecked)
                    .apply();
            if (Build.VERSION.SDK_INT >= 30) {
                buttonView.setStateDescription(isChecked ? "On" : "Off");
            }
            if (PREF_ANIMATIONS.equals(preferenceKey) && backdrop != null) {
                backdrop.setAnimationRunning(isChecked);
            }
        });
        row.addView(toggle, new LinearLayout.LayoutParams(dp(62), dp(48)));
        page.addView(row, surfaceParams());
    }

    private void applySwitchPalette(Switch toggle) {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        toggle.setThumbTintList(new ColorStateList(
                states,
                new int[]{
                        PRIMARY,
                        Color.argb(230, 224, 232, 242)
                }));
        toggle.setTrackTintList(new ColorStateList(
                states,
                new int[]{
                        Color.argb(205, Color.red(accent), Color.green(accent), Color.blue(accent)),
                        Color.argb(92, 255, 255, 255)
                }));
    }

    private void addTemperatureUnitRow() {
        LinearLayout row = surface();
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(10), dp(14), dp(11));

        TextView title = text("Temperature unit", 17f, PRIMARY, false);
        row.addView(title);

        LinearLayout selector = unitSelector();
        LinearLayout.LayoutParams selectorLp = new LinearLayout.LayoutParams(-1, dp(48));
        selectorLp.topMargin = dp(8);
        row.addView(selector, selectorLp);

        String current = normalizeTemperatureUnit(getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getString(PREF_TEMPERATURE_UNIT, TEMP_CELSIUS));
        TextView celsius = unitOption("°C  Celsius", 13f);
        TextView fahrenheit = unitOption("°F  Fahrenheit", 13f);
        TextView[] options = {celsius, fahrenheit};
        String[] values = {TEMP_CELSIUS, TEMP_FAHRENHEIT};
        String[] labels = {"Celsius", "Fahrenheit"};
        selector.addView(celsius, new LinearLayout.LayoutParams(0, dp(44), 1f));
        selector.addView(fahrenheit, new LinearLayout.LayoutParams(0, dp(44), 1f));
        updateChoiceOptions(options, values, labels, current, "temperature unit");

        celsius.setOnClickListener(v -> selectTemperatureUnit(
                TEMP_CELSIUS, options, values, labels));
        fahrenheit.setOnClickListener(v -> selectTemperatureUnit(
                TEMP_FAHRENHEIT, options, values, labels));

        page.addView(row, surfaceParams());
    }

    private void addChoiceUnitRow(
            String titleText,
            String subtitleText,
            String preferenceKey,
            String[] values,
            String[] labels,
            String defaultValue) {
        LinearLayout row = surface();
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(10), dp(14), dp(11));

        TextView title = text(titleText, 17f, PRIMARY, false);
        row.addView(title);

        LinearLayout selector = unitSelector();
        LinearLayout.LayoutParams selectorLp = new LinearLayout.LayoutParams(-1, dp(48));
        selectorLp.topMargin = dp(8);
        row.addView(selector, selectorLp);

        SharedPreferences prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        String current = normalizeDisplayUnit(preferenceKey, prefs.getString(preferenceKey, defaultValue));
        TextView[] options = new TextView[values.length];
        for (int i = 0; i < values.length; i++) {
            TextView option = unitOption(labels[i], values.length >= 4 ? 11.5f : 13f);
            options[i] = option;
            selector.addView(option, new LinearLayout.LayoutParams(0, dp(44), 1f));
        }
        updateChoiceOptions(options, values, labels, current, titleText.toLowerCase(Locale.ROOT));

        for (int i = 0; i < options.length; i++) {
            final int index = i;
            options[i].setOnClickListener(v -> selectDisplayUnit(
                    preferenceKey,
                    values[index],
                    defaultValue,
                    options,
                    values,
                    labels,
                    titleText.toLowerCase(Locale.ROOT)));
        }
        page.addView(row, surfaceParams());
    }

    private LinearLayout unitSelector() {
        LinearLayout selector = new LinearLayout(this);
        selector.setOrientation(LinearLayout.HORIZONTAL);
        selector.setPadding(dp(2), dp(2), dp(2), dp(2));
        selector.setBackground(tileBackground(dp(18)));
        return selector;
    }

    private TextView unitOption(String label, float sizeSp) {
        TextView option = text(label, sizeSp, PRIMARY, true);
        option.setGravity(Gravity.CENTER);
        option.setClickable(true);
        option.setFocusable(true);
        option.setMinHeight(dp(44));
        option.setPadding(dp(2), 0, dp(2), 0);
        return option;
    }

    private void selectTemperatureUnit(
            String next,
            TextView[] options,
            String[] values,
            String[] labels) {
        SharedPreferences prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        String current = normalizeTemperatureUnit(
                prefs.getString(PREF_TEMPERATURE_UNIT, TEMP_CELSIUS));
        if (!next.equals(current)) {
            prefs.edit().putString(PREF_TEMPERATURE_UNIT, next).apply();
            temperatureUnitChanged = true;
        }
        updateChoiceOptions(options, values, labels, next, "temperature unit");
    }

    private void selectDisplayUnit(
            String preferenceKey,
            String next,
            String defaultValue,
            TextView[] options,
            String[] values,
            String[] labels,
            String accessibilityName) {
        SharedPreferences prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        String current = normalizeDisplayUnit(
                preferenceKey, prefs.getString(preferenceKey, defaultValue));
        String normalizedNext = normalizeDisplayUnit(preferenceKey, next);
        if (!normalizedNext.equals(current)) {
            prefs.edit().putString(preferenceKey, normalizedNext).apply();
            displayUnitChanged = true;
        }
        updateChoiceOptions(options, values, labels, normalizedNext, accessibilityName);
    }

    private void updateChoiceOptions(
            TextView[] options,
            String[] values,
            String[] labels,
            String current,
            String accessibilityName) {
        for (int i = 0; i < options.length; i++) {
            boolean selected = values[i].equals(current);
            TextView option = options[i];
            option.setSelected(selected);
            option.setAlpha(selected ? 1f : 0.80f);
            option.setBackground(unitOptionBackground(selected));
            option.setContentDescription(labels[i] + " " + accessibilityName + ", "
                    + (selected ? "selected" : "not selected"));
            if (Build.VERSION.SDK_INT >= 30) {
                option.setStateDescription(selected ? "Selected" : "Not selected");
            }
        }
    }

    private StateListDrawable unitOptionBackground(boolean selected) {
        StateListDrawable states = new StateListDrawable();
        int normal = selected
                ? Color.argb(102, Color.red(accent), Color.green(accent), Color.blue(accent))
                : Color.TRANSPARENT;
        int pressed = selected
                ? Color.argb(138, Color.red(accent), Color.green(accent), Color.blue(accent))
                : Color.argb(44, 255, 255, 255);
        states.addState(new int[]{android.R.attr.state_pressed},
                roundBackground(pressed, dp(16)));
        states.addState(new int[]{android.R.attr.state_focused},
                roundBackground(pressed, dp(16)));
        states.addState(new int[]{},
                roundBackground(normal, dp(16)));
        return states;
    }

    private static String normalizeTemperatureUnit(String value) {
        return TEMP_FAHRENHEIT.equalsIgnoreCase(value)
                ? TEMP_FAHRENHEIT
                : TEMP_CELSIUS;
    }

    private static String normalizeDisplayUnit(String preferenceKey, String value) {
        String raw = value == null ? "" : value.trim();
        if (PREF_WIND_UNIT.equals(preferenceKey)) {
            if (WIND_MPH.equalsIgnoreCase(raw) || "mi/h".equalsIgnoreCase(raw)) return WIND_MPH;
            if (WIND_MS.equalsIgnoreCase(raw) || "mps".equalsIgnoreCase(raw)) return WIND_MS;
            if (WIND_KNOTS.equalsIgnoreCase(raw) || "kt".equalsIgnoreCase(raw)
                    || "kts".equalsIgnoreCase(raw)) return WIND_KNOTS;
            return WIND_KMH;
        }
        if (PREF_PRESSURE_UNIT.equals(preferenceKey)) {
            if (PRESSURE_INHG.equalsIgnoreCase(raw) || "in hg".equalsIgnoreCase(raw)) {
                return PRESSURE_INHG;
            }
            if (PRESSURE_MMHG.equalsIgnoreCase(raw) || "mm hg".equalsIgnoreCase(raw)) {
                return PRESSURE_MMHG;
            }
            return PRESSURE_HPA;
        }
        if (PREF_VISIBILITY_UNIT.equals(preferenceKey)) {
            if (VISIBILITY_MI.equalsIgnoreCase(raw) || "mile".equalsIgnoreCase(raw)
                    || "miles".equalsIgnoreCase(raw)) return VISIBILITY_MI;
            return VISIBILITY_KM;
        }
        return raw;
    }

    private LinearLayout surface() {
        LinearLayout row = new LinearLayout(this);
        row.setBackground(surfaceBackground(false));
        return row;
    }

    private android.graphics.drawable.Drawable surfaceBackground(boolean interactive) {
        GradientDrawable normal = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{surfaceTop, surfaceBottom});
        normal.setCornerRadius(dp(20));
        normal.setStroke(dp(1), Color.argb(42, 255, 255, 255));
        if (!interactive) return normal;

        GradientDrawable pressed = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{
                        Color.argb(
                                Math.min(255, Color.alpha(surfaceTop) + 30),
                                Color.red(surfaceTop),
                                Color.green(surfaceTop),
                                Color.blue(surfaceTop)),
                        Color.argb(
                                Math.min(255, Color.alpha(surfaceBottom) + 30),
                                Color.red(surfaceBottom),
                                Color.green(surfaceBottom),
                                Color.blue(surfaceBottom))
                });
        pressed.setCornerRadius(dp(20));
        pressed.setStroke(dp(1), Color.argb(72, 255, 255, 255));

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{android.R.attr.state_focused}, pressed);
        states.addState(new int[]{}, normal);
        return states;
    }

    private GradientDrawable tileBackground(int radius) {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{tileTop, tileBottom});
        background.setCornerRadius(radius);
        background.setStroke(dp(1), Color.argb(40, 255, 255, 255));
        return background;
    }

    private GradientDrawable roundBackground(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(radius);
        return background;
    }

    private LinearLayout.LayoutParams surfaceParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(7);
        return lp;
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setIncludeFontPadding(true);
        return view;
    }

    private void returnAction(String action) {
        Intent result = new Intent().putExtra(EXTRA_ACTION, action);
        if (temperatureUnitChanged) result.putExtra(EXTRA_UNIT_CHANGED, true);
        if (displayUnitChanged) result.putExtra(EXTRA_DISPLAY_UNIT_CHANGED, true);
        if (airQualityPreferenceChanged()) result.putExtra(EXTRA_AIR_QUALITY_CHANGED, true);
        if (pollenPreferenceChanged()) result.putExtra(EXTRA_POLLEN_CHANGED, true);
        setResult(RESULT_OK, result);
        finishAfterTransition();
    }

    private boolean airQualityPreferenceChanged() {
        return getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getBoolean(PREF_AIR_QUALITY, false) != initialAirQualityEnabled;
    }

    private boolean pollenPreferenceChanged() {
        return getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getBoolean(PREF_POLLEN, false) != initialPollenEnabled;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (backdrop != null) {
            backdrop.setAnimationRunning(getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                    .getBoolean(PREF_ANIMATIONS, true));
        }
    }

    @Override
    protected void onPause() {
        if (backdrop != null) backdrop.setAnimationRunning(false);
        super.onPause();
    }

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        boolean airQualityChanged = airQualityPreferenceChanged();
        boolean pollenChanged = pollenPreferenceChanged();
        if (temperatureUnitChanged || displayUnitChanged || airQualityChanged || pollenChanged) {
            Intent result = new Intent().putExtra(EXTRA_ACTION, ACTION_PREFERENCES_CHANGED);
            if (temperatureUnitChanged) result.putExtra(EXTRA_UNIT_CHANGED, true);
            if (displayUnitChanged) result.putExtra(EXTRA_DISPLAY_UNIT_CHANGED, true);
            if (airQualityChanged) result.putExtra(EXTRA_AIR_QUALITY_CHANGED, true);
            if (pollenChanged) result.putExtra(EXTRA_POLLEN_CHANGED, true);
            setResult(RESULT_OK, result);
        }
        finishAfterTransition();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Platform predictive-back bridge isolated from API 28-32 class verification. */
    @TargetApi(33)
    private static final class Api33BackNavigation {
        static void register(SettingsActivity activity) {
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    activity::handleBack);
        }
    }
}
