package com.zwerk.weather;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings screen controlling which secondary values are shown in weather details.
 *
 * <p>Built entirely with Android platform views so it remains compatible with minSdk 28.
 */
public class WeatherDetailSettingsActivity extends Activity {

    // Package-visible preference contract for the rest of com.zwerk.weather.
    static final String WEATHER_UI = "WEATHER_UI";

    static final String PREF_UV_INDEX = "weather_detail_uv_index";
    static final String PREF_FEELS_LIKE = "weather_detail_feels_like";
    static final String PREF_HUMIDITY = "weather_detail_humidity";
    static final String PREF_WIND = "weather_detail_wind";
    static final String PREF_AIR_PRESSURE = "weather_detail_air_pressure";
    static final String PREF_VISIBILITY = "weather_detail_visibility";

    static final String PREF_CLOUD_COVER = "weather_detail_cloud_cover";
    static final String PREF_WIND_GUST = "weather_detail_wind_gust";
    static final String PREF_THUNDERSTORM_CHANCE = "weather_detail_thunderstorm_chance";
    static final String PREF_DEW_POINT = "weather_detail_dew_point";
    static final String PREF_HEAT_INDEX = "weather_detail_heat_index";
    static final String PREF_WIND_CHILL = "weather_detail_wind_chill";
    static final String PREF_TEMPERATURE_CHANGE_24H = "weather_detail_temperature_change_24h";
    static final String PREF_PRECIPITATION_24H = "weather_detail_precipitation_24h";

    static final class DetailOption {
        final String key;
        final boolean defaultValue;

        DetailOption(String key, boolean defaultValue) {
            this.key = key;
            this.defaultValue = defaultValue;
        }
    }

    static final DetailOption[] OPTIONS = new DetailOption[]{
            new DetailOption(PREF_UV_INDEX, true),
            new DetailOption(PREF_FEELS_LIKE, true),
            new DetailOption(PREF_HUMIDITY, true),
            new DetailOption(PREF_WIND, true),
            new DetailOption(PREF_AIR_PRESSURE, true),
            new DetailOption(PREF_VISIBILITY, true),
            new DetailOption(PREF_CLOUD_COVER, true),
            new DetailOption(PREF_WIND_GUST, true),
            new DetailOption(PREF_THUNDERSTORM_CHANCE, true),
            new DetailOption(PREF_DEW_POINT, false),
            new DetailOption(PREF_HEAT_INDEX, false),
            new DetailOption(PREF_WIND_CHILL, false),
            new DetailOption(PREF_TEMPERATURE_CHANGE_24H, false),
            new DetailOption(PREF_PRECIPITATION_24H, false)
    };

    // These mirror SettingsActivity's scene/style hand-off without creating a source dependency.
    private static final String EXTRA_SCENE = "com.zwerk.weather.extra.SETTINGS_SCENE";
    private static final String EXTRA_DAYTIME = "com.zwerk.weather.extra.SETTINGS_DAYTIME";
    private static final String EXTRA_CARD_TOP = "com.zwerk.weather.extra.SETTINGS_CARD_TOP";
    private static final String EXTRA_CARD_BOTTOM = "com.zwerk.weather.extra.SETTINGS_CARD_BOTTOM";
    private static final String EXTRA_ACCENT = "com.zwerk.weather.extra.SETTINGS_ACCENT";

    private static final String PREF_SETTINGS_SCENE = "settings_scene";
    private static final String PREF_SETTINGS_DAYTIME = "settings_daytime";
    private static final String PREF_SETTINGS_CARD_TOP = "settings_card_top";
    private static final String PREF_SETTINGS_CARD_BOTTOM = "settings_card_bottom";
    private static final String PREF_SETTINGS_ACCENT = "settings_accent";

    private static final int PRIMARY = Color.rgb(247, 249, 253);
    private static final int SECONDARY = Color.rgb(204, 216, 230);

    private final Map<String, Switch> switches = new LinkedHashMap<>();

    private SharedPreferences prefs;
    private LinearLayout page;
    private boolean syncingSwitches;

    private String scene = "day";
    private boolean daytime = true;
    private int surfaceTop = Color.argb(154, 72, 132, 205);
    private int surfaceBottom = Color.argb(126, 72, 118, 174);
    private int accent = Color.rgb(151, 211, 255);
    private int scrimBase = Color.rgb(8, 55, 120);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(WEATHER_UI, MODE_PRIVATE);
        readSceneStyle();
        configureWindow();
        buildUi();
        if (Build.VERSION.SDK_INT >= 33) Api33BackNavigation.register(this);
    }

    private void readSceneStyle() {
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
        if ("night".equals(scene)) {
            fallbackTop = Color.argb(164, 18, 44, 84);
            fallbackBottom = Color.argb(136, 8, 24, 52);
            scrimBase = Color.rgb(3, 13, 31);
        } else if ("rain".equals(scene) || "thunder".equals(scene) || "fog".equals(scene)) {
            fallbackTop = Color.argb(166, 49, 70, 89);
            fallbackBottom = Color.argb(138, 23, 40, 56);
            scrimBase = "thunder".equals(scene)
                    ? Color.rgb(8, 16, 30)
                    : Color.rgb(26, 42, 55);
        } else if ("snow".equals(scene)) {
            fallbackTop = Color.argb(158, 82, 112, 138);
            fallbackBottom = Color.argb(130, 52, 80, 105);
            scrimBase = Color.rgb(58, 84, 108);
        } else {
            fallbackTop = Color.argb(154, 72, 132, 205);
            fallbackBottom = Color.argb(126, 72, 118, 174);
            scrimBase = Color.rgb(8, 55, 120);
        }

        surfaceTop = opaqueEnough(readColor(
                intent, EXTRA_CARD_TOP, PREF_SETTINGS_CARD_TOP, fallbackTop), 150);
        surfaceBottom = opaqueEnough(readColor(
                intent, EXTRA_CARD_BOTTOM, PREF_SETTINGS_CARD_BOTTOM, fallbackBottom), 120);
        accent = readColor(intent, EXTRA_ACCENT, PREF_SETTINGS_ACCENT, defaultAccent(scene));
    }

    private int readColor(Intent intent, String extraKey, String prefKey, int fallback) {
        if (intent != null && intent.hasExtra(extraKey)) {
            return intent.getIntExtra(extraKey, fallback);
        }
        return prefs.getInt(prefKey, fallback);
    }

    private static boolean isKnownScene(String value) {
        return "day".equals(value)
                || "night".equals(value)
                || "rain".equals(value)
                || "thunder".equals(value)
                || "fog".equals(value)
                || "snow".equals(value);
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
        window.setNavigationBarDividerColor(Color.TRANSPARENT);
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
        switches.clear();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(scrimBase);

        WeatherGradientView backdrop = new WeatherGradientView(this, scene, daytime);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        View scrim = new View(this);
        GradientDrawable scrimBackground = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{
                        Color.argb(92, Color.red(scrimBase), Color.green(scrimBase), Color.blue(scrimBase)),
                        Color.argb(132, 4, 12, 25),
                        Color.argb(178, 3, 9, 19)
                });
        scrim.setBackground(scrimBackground);
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
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

        addHeader();

        addSectionHeading("Essentials");
        addSwitchRow(
                "UV index",
                "Sun exposure level for the selected time.",
                PREF_UV_INDEX,
                true);
        addSwitchRow(
                "Feels like",
                "Apparent temperature after wind and humidity.",
                PREF_FEELS_LIKE,
                true);
        addSwitchRow(
                "Humidity",
                "Relative moisture level in the air.",
                PREF_HUMIDITY,
                true);
        addSwitchRow(
                "Wind",
                "Sustained wind speed and direction.",
                PREF_WIND,
                true);
        addSwitchRow(
                "Air pressure",
                "Atmospheric pressure at the forecast location.",
                PREF_AIR_PRESSURE,
                true);
        addSwitchRow(
                "Visibility",
                "How far conditions allow you to see.",
                PREF_VISIBILITY,
                true);

        addSectionHeading("Extra detail");
        addSwitchRow(
                "Cloud cover",
                "Estimated percentage of sky covered by clouds.",
                PREF_CLOUD_COVER,
                true);
        addSwitchRow(
                "Wind gust",
                "Short bursts of wind above the sustained speed.",
                PREF_WIND_GUST,
                true);
        addSwitchRow(
                "Thunderstorm chance",
                "Probability of thunderstorms when available.",
                PREF_THUNDERSTORM_CHANCE,
                true);
        addSwitchRow(
                "Dew point",
                "Temperature where air becomes saturated.",
                PREF_DEW_POINT,
                false);
        addSwitchRow(
                "Heat index",
                "Perceived heat when humidity raises the apparent temperature.",
                PREF_HEAT_INDEX,
                false);
        addSwitchRow(
                "Wind chill",
                "Perceived cold caused by wind.",
                PREF_WIND_CHILL,
                false);
        addSwitchRow(
                "24-hour temperature change",
                "Change compared with the same time yesterday.",
                PREF_TEMPERATURE_CHANGE_24H,
                false);
        addSwitchRow(
                "24-hour precipitation",
                "Total precipitation over the previous 24 hours.",
                PREF_PRECIPITATION_24H,
                false);

        addRestoreAction();

        TextView footer = text(
                "Changes are saved automatically.",
                12f,
                Color.argb(170, 210, 222, 236),
                false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(4), dp(22), dp(4), dp(8));
        page.addView(footer);

        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            page.setPadding(dp(18), top, dp(18), bottom + dp(32));
            return insets;
        });
        root.requestApplyInsets();
    }

    private void addHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));

        WeatherBackButton back = new WeatherBackButton(this);
        back.setContentDescription("Back");
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(v -> handleBack());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(60)));

        TextView title = text("Weather details", 25f, PRIMARY, false);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, dp(60), 1f);
        titleLp.leftMargin = dp(4);
        header.addView(title, titleLp);
    }

    private void addSectionHeading(String value) {
        TextView heading = text(value, 13f, SECONDARY, true);
        heading.setPadding(dp(4), dp(page.getChildCount() <= 1 ? 8 : 16), dp(4), dp(7));
        page.addView(heading);
    }

    private void addSwitchRow(
            String title,
            String subtitle,
            String preferenceKey,
            boolean defaultValue) {
        LinearLayout row = surface(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(10), dp(11), dp(10));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView primary = text(title, 17f, PRIMARY, false);
        labels.addView(primary);

        TextView secondary = text(subtitle, 13f, SECONDARY, false);
        secondary.setPadding(0, dp(2), dp(8), 0);
        secondary.setLineSpacing(dp(1), 1f);
        labels.addView(secondary);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setShowText(false);
        toggle.setChecked(prefs.getBoolean(preferenceKey, defaultValue));
        toggle.setContentDescription(title);
        toggle.setMinimumHeight(dp(48));
        applySwitchPalette(toggle);
        if (Build.VERSION.SDK_INT >= 30) {
            toggle.setStateDescription(toggle.isChecked() ? "On" : "Off");
        }
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!syncingSwitches) {
                prefs.edit().putBoolean(preferenceKey, isChecked).apply();
            }
            if (Build.VERSION.SDK_INT >= 30) {
                buttonView.setStateDescription(isChecked ? "On" : "Off");
            }
        });

        switches.put(preferenceKey, toggle);
        row.addView(toggle, new LinearLayout.LayoutParams(dp(62), dp(48)));
        page.addView(row, surfaceParams());
    }

    private void addRestoreAction() {
        LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        page.addView(new View(this), spacerLp);

        LinearLayout row = surface(true);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(11), dp(12), dp(11));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(
                "Restore recommended defaults. Resets only the Weather details switches on this screen.");
        row.setOnClickListener(v -> restoreRecommendedDefaults());

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("Restore recommended defaults", 17f, PRIMARY, false));

        TextView helper = text(
                "Resets only the Weather details switches on this screen.",
                13f,
                SECONDARY,
                false);
        helper.setPadding(0, dp(2), dp(8), 0);
        labels.addView(helper);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView restoreGlyph = text("↺", 24f, Color.argb(230, 220, 235, 249), false);
        restoreGlyph.setGravity(Gravity.CENTER);
        restoreGlyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(restoreGlyph, new LinearLayout.LayoutParams(dp(42), dp(48)));

        page.addView(row, surfaceParams());
    }

    private void restoreRecommendedDefaults() {
        SharedPreferences.Editor editor = prefs.edit();

        editor.putBoolean(PREF_UV_INDEX, true);
        editor.putBoolean(PREF_FEELS_LIKE, true);
        editor.putBoolean(PREF_HUMIDITY, true);
        editor.putBoolean(PREF_WIND, true);
        editor.putBoolean(PREF_AIR_PRESSURE, true);
        editor.putBoolean(PREF_VISIBILITY, true);

        editor.putBoolean(PREF_CLOUD_COVER, true);
        editor.putBoolean(PREF_WIND_GUST, true);
        editor.putBoolean(PREF_THUNDERSTORM_CHANCE, true);
        editor.putBoolean(PREF_DEW_POINT, false);
        editor.putBoolean(PREF_HEAT_INDEX, false);
        editor.putBoolean(PREF_WIND_CHILL, false);
        editor.putBoolean(PREF_TEMPERATURE_CHANGE_24H, false);
        editor.putBoolean(PREF_PRECIPITATION_24H, false);
        editor.apply();

        syncingSwitches = true;
        setSwitch(PREF_UV_INDEX, true);
        setSwitch(PREF_FEELS_LIKE, true);
        setSwitch(PREF_HUMIDITY, true);
        setSwitch(PREF_WIND, true);
        setSwitch(PREF_AIR_PRESSURE, true);
        setSwitch(PREF_VISIBILITY, true);
        setSwitch(PREF_CLOUD_COVER, true);
        setSwitch(PREF_WIND_GUST, true);
        setSwitch(PREF_THUNDERSTORM_CHANCE, true);
        setSwitch(PREF_DEW_POINT, false);
        setSwitch(PREF_HEAT_INDEX, false);
        setSwitch(PREF_WIND_CHILL, false);
        setSwitch(PREF_TEMPERATURE_CHANGE_24H, false);
        setSwitch(PREF_PRECIPITATION_24H, false);
        syncingSwitches = false;

        Toast.makeText(this, "Recommended defaults restored", Toast.LENGTH_SHORT).show();
    }

    private void setSwitch(String key, boolean value) {
        Switch toggle = switches.get(key);
        if (toggle != null) toggle.setChecked(value);
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

    private LinearLayout surface(boolean interactive) {
        LinearLayout row = new LinearLayout(this);
        row.setBackground(surfaceBackground(interactive));
        return row;
    }

    private StateListDrawable surfaceBackground(boolean interactive) {
        GradientDrawable normal = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{surfaceTop, surfaceBottom});
        normal.setCornerRadius(dp(20));
        normal.setStroke(dp(1), Color.argb(42, 255, 255, 255));

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
        if (interactive) {
            states.addState(new int[]{android.R.attr.state_pressed}, pressed);
            states.addState(new int[]{android.R.attr.state_focused}, pressed);
        }
        states.addState(new int[]{}, normal);
        return states;
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

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        setResult(RESULT_OK);
        finishAfterTransition();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @TargetApi(33)
    private static final class Api33BackNavigation {
        static void register(WeatherDetailSettingsActivity activity) {
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    activity::handleBack);
        }
    }

    private final class WeatherBackButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        WeatherBackButton(Context context) {
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

    /** Lightweight resource-free approximation of SettingsActivity's weather backdrop. */
    private final class WeatherGradientView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private final String sceneKey;
        private final boolean sceneDaytime;

        WeatherGradientView(Context context, String sceneKey, boolean sceneDaytime) {
            super(context);
            this.sceneKey = sceneKey == null ? "day" : sceneKey;
            this.sceneDaytime = sceneDaytime;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            int top;
            int middle;
            int bottom;
            if ("night".equals(sceneKey) || !sceneDaytime) {
                top = Color.rgb(13, 35, 74);
                middle = Color.rgb(9, 28, 61);
                bottom = Color.rgb(3, 13, 31);
            } else if ("thunder".equals(sceneKey)) {
                top = Color.rgb(34, 48, 67);
                middle = Color.rgb(20, 33, 50);
                bottom = Color.rgb(7, 15, 29);
            } else if ("rain".equals(sceneKey) || "fog".equals(sceneKey)) {
                top = Color.rgb(54, 79, 101);
                middle = Color.rgb(34, 58, 79);
                bottom = Color.rgb(15, 31, 47);
            } else if ("snow".equals(sceneKey)) {
                top = Color.rgb(89, 122, 151);
                middle = Color.rgb(58, 91, 121);
                bottom = Color.rgb(32, 57, 82);
            } else {
                top = Color.rgb(30, 91, 158);
                middle = Color.rgb(18, 65, 124);
                bottom = Color.rgb(7, 29, 65);
            }

            paint.setShader(new LinearGradient(
                    0,
                    0,
                    0,
                    h,
                    new int[]{top, middle, bottom},
                    new float[]{0f, 0.52f, 1f},
                    Shader.TileMode.CLAMP));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            // Soft atmospheric layers keep the background weather-like without drawable resources.
            paint.setColor(Color.argb(
                    "snow".equals(sceneKey) ? 32 : 18,
                    225,
                    239,
                    250));
            oval.set(-w * 0.30f, h * 0.06f, w * 0.82f, h * 0.29f);
            canvas.drawOval(oval, paint);
            oval.set(w * 0.18f, h * 0.20f, w * 1.28f, h * 0.46f);
            canvas.drawOval(oval, paint);

            if ("rain".equals(sceneKey) || "thunder".equals(sceneKey) || "fog".equals(sceneKey)) {
                paint.setColor(Color.argb(20, 230, 240, 248));
                oval.set(-w * 0.40f, h * 0.46f, w * 0.72f, h * 0.65f);
                canvas.drawOval(oval, paint);
                oval.set(w * 0.25f, h * 0.58f, w * 1.32f, h * 0.78f);
                canvas.drawOval(oval, paint);
            }

            if ("night".equals(sceneKey) || !sceneDaytime) {
                paint.setColor(Color.argb(150, 233, 243, 255));
                for (int i = 0; i < 24; i++) {
                    float x = ((i * 37) % 101) / 101f * w;
                    float y = ((i * 53) % 89) / 89f * h * 0.55f;
                    canvas.drawCircle(x, y, dp((i % 3 == 0) ? 1.0f : 0.65f), paint);
                }
            }
        }
    }
}
