package com.zwerk.weather;

import android.Manifest;
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
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
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

public class MainActivity extends Activity {
    private static final int LOCATION_REQUEST = 40;
    private static final int CITY_MANAGER_REQUEST = 41;
    private static final int SETTINGS_REQUEST = 42;
    private static final String UI_PREFS = "WEATHER_UI";
    private static final String PREF_DAILY_MODE = "daily_mode";
    private static final String PREF_ANIMATIONS = "weather_animations";
    private static final String PREF_TEMPERATURE_UNIT = "temperature_unit";
    private static final String PREF_WIND_UNIT = "wind_unit";
    private static final String PREF_PRESSURE_UNIT = "pressure_unit";
    private static final String PREF_VISIBILITY_UNIT = "visibility_unit";
    private static final String PREF_AIR_QUALITY = "google_air_quality_enabled";
    private static final String PREF_POLLEN = "google_pollen_enabled";
    private static final String PREF_EXPLICIT_NON_DEVICE_LOCATION = "explicit_non_device_location";
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

    private static final String API_ROOT = "https://weather.googleapis.com/v1/";
    private static final String AIR_QUALITY_ENDPOINT = "https://airquality.googleapis.com/v1/currentConditions:lookup";
    private static final String POLLEN_ENDPOINT = "https://pollen.googleapis.com/v1/forecast:lookup";
    private static final String API_KEY_GUIDE_URL =
            "https://developers.google.com/maps/documentation/weather/get-api-key";
    private static final String GOOGLE_CLOUD_CREDENTIALS_URL =
            "https://console.cloud.google.com/apis/credentials";
    private static final String AIR_QUALITY_GUIDE_URL =
            "https://developers.google.com/maps/documentation/air-quality/get-api-key";
    private static final String POLLEN_GUIDE_URL =
            "https://developers.google.com/maps/documentation/pollen/get-api-key";
    private static final String API_KEY_FILE = "weather_api_key";
    private static final String API_KEY_TEMP_FILE = "weather_api_key.tmp";
    private static final String WEATHER_CACHE_V1_PREFIX = "weather_forecast_cache_v1_";
    private static final String WEATHER_CACHE_FILE_PREFIX = "weather_forecast_cache_v2_";
    private static final String WEATHER_CACHE_FILE_SUFFIX = ".json";
    private static final long WEATHER_CACHE_MAX_AGE_MILLIS = 60L * 60L * 1000L;
    private static final String OPTIONAL_CACHE_FILE_SUFFIX = ".json";
    private static final String AIR_QUALITY_CACHE_ROOT_PREFIX = "google_air_quality_cache_";
    private static final String POLLEN_CACHE_ROOT_PREFIX = "google_pollen_cache_";
    private static final String AIR_QUALITY_CACHE_FILE_PREFIX = "google_air_quality_cache_v1_";
    private static final String POLLEN_CACHE_FILE_PREFIX = "google_pollen_cache_v1_";
    private static final long AIR_QUALITY_CACHE_MAX_AGE_MILLIS = 60L * 60L * 1000L;
    private static final long POLLEN_CACHE_MAX_AGE_MILLIS = 6L * 60L * 60L * 1000L;
    private static final String MINUTE_CACHE_FILE_PREFIX = "weather_minute_cache_v1_";
    private static final String MINUTE_CACHE_LEGACY_PREFIX = "weather_minute_cache_";
    private static final String MINUTE_CACHE_FILE_SUFFIX = ".json";
    private static final long MINUTE_CACHE_MAX_AGE_MILLIS = 60L * 60L * 1000L;
    private static final int MINUTE_PAGE_SIZE = 180;
    private static final int MINUTE_RANGE_TWO_HOURS = 2;
    private static final int MINUTE_RANGE_SIX_HOURS = 6;
    private static final long MINUTE_SELECTION_STEP_MILLIS = 2L * 60L * 1000L;
    private static final double PRECIP_LIGHT_MAX_MM_H = 2.5d;
    private static final double PRECIP_MODERATE_MAX_MM_H = 7.5d;
    private static final double PRECIP_VISUAL_CAP_MM_H = 15d;
    private static final double WEATHER_CACHE_COORDINATE_TOLERANCE = 0.00001d;
    private static final int HOURLY_FORECAST_HOURS = 240;
    private static final int HOURLY_PAGE_SIZE = 24;
    private static final int TOP_HOURLY_STRIP_HOURS = 24;
    private static final long SCENE_TRANSITION_MILLIS = 550L;
    private static final String LOG_TAG = "ZwerkWeather";
    private static final int ERROR_BODY_LIMIT_BYTES = 32768;
    private static final String HOURLY_DIAGNOSTIC = "_weatherNextHourlyDiagnostic";
    private static final String HOURLY_PARTIAL = "_weatherNextHourlyPartial";
    private static final String HOURLY_LOAD_ERROR = "_weatherNextHourlyLoadError";
    private static final String HOURLY_TOP_KEYS = "_weatherNextHourlyTopKeys";
    private static final String HOURLY_FIRST_PAGE_COUNT = "_weatherNextHourlyFirstPageCount";
    private static final String HOURLY_NEXT_TOKEN_PRESENT = "_weatherNextHourlyNextTokenPresent";

    private static final int WHITE = Color.WHITE;
    private static final int SOFT_WHITE = Color.argb(205, 255, 255, 255);
    private static final int FAINT_WHITE = Color.argb(145, 255, 255, 255);
    private static final int ACCENT_YELLOW = Color.rgb(255, 194, 24);
    private static final int ACCENT_BLUE = Color.rgb(176, 226, 255);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService optionalExecutor = Executors.newFixedThreadPool(2);

    private LinearLayout content;
    private ProgressBar progress;
    private TextView status;
    private TextView locationTitle;
    private SkyLayout skyLayout;
    private RefreshScrollView mainScroll;
    private RefreshIndicatorView refreshIndicator;
    private LinearLayout toolbar;
    private HeaderGlassView headerGlass;
    private LinearLayout locationArea;
    private TextView previewSubtitle;
    private HeaderScrimDrawable headerScrim;
    private final ArrayList<GlassDrawable> glassDrawables = new ArrayList<>();
    private int cardColor = Color.argb(92, 31, 102, 211);
    private int tileColor = Color.argb(72, 31, 102, 211);
    private int glassCardTop = Color.argb(94, 48, 116, 207);
    private int glassCardBottom = Color.argb(56, 70, 126, 188);
    private int glassTileTop = Color.argb(84, 50, 117, 202);
    private int glassTileBottom = Color.argb(48, 66, 119, 178);
    private int glassEdge = Color.TRANSPARENT;
    private int dynamicStartIndex;
    private TextView overviewModeButton;
    private TextView precipitationModeButton;
    private GlassDrawable modeSwitchGlass;
    private boolean precipitationMode;
    private int overviewScrollY;
    private int precipitationScrollY;
    private int minuteRangeHours = MINUTE_RANGE_TWO_HOURS;
    private long minuteSelectedTimeMillis = Long.MIN_VALUE;
    private View precipitationBody;
    private final Object minuteForecastLock = new Object();
    private MinuteForecastState minuteForecastState;
    private boolean weatherLoadActive;
    private double activeLoadLatitude = Double.NaN;
    private double activeLoadLongitude = Double.NaN;
    private String activeLoadLanguage = "";
    private String activeLoadLocationId = "";
    private int weatherRequestGeneration;
    private HourlyPageState hourlyPageState;
    private final Object hourlyCoverageLock = new Object();
    private boolean hourlyCoverageLoadActive;
    private LocalDate pendingHourlyCoverageTarget;
    private LocalDate hourlyCoverageLoadingTarget;
    private final ArrayList<WeakReference<DayDetailCoordinator>> hourlyCoverageCoordinators = new ArrayList<>();
    private boolean weatherReloadPending;
    private boolean weatherReloadForcePending;
    private boolean forceNextWeatherLoad;
    private boolean hasResumedOnce;
    private boolean suppressNextResumeWeatherLoad;
    private boolean deviceLocationRequestExplicit;
    private int locationSelectionGeneration;
    private int deviceLocationRequestGeneration;
    private String activeTemperatureUnit = TEMP_CELSIUS;
    private String displayedScene = "day";
    private boolean displayedDaytime = true;
    private JSONObject lastCurrentWeather;
    private JSONObject lastHourlyWeather;
    private JSONObject lastDailyWeather;
    private final ArrayList<SunTrackView> sunTrackViews = new ArrayList<>();
    private Bitmap sunTrackMarkerBitmap;
    private Bitmap moonTrackMarkerBitmap;
    private final Object optionalDataLock = new Object();
    private OptionalDataState airQualityState;
    private OptionalDataState pollenState;
    private long airQualityRequestSerial;
    private long pollenRequestSerial;
    private Dialog apiKeySetupDialog;
    private final ForecastPreviewController forecastPreview = new ForecastPreviewController();

    private double latitude = 50.8503;
    private double longitude = 4.3517;
    private String locationName = "Brussels";
    private String selectedLocationId = "";
    private boolean usingDeviceLocation;

    /** Immutable visual scene description. Forecast previews never mutate persisted current weather. */
    private static final class SceneSpec {
        final String base;
        final String effect;
        final boolean daytime;
        final String condition;

        SceneSpec(String base, String effect, boolean daytime, String condition) {
            this.base = base == null ? "day" : base;
            this.effect = effect == null ? "none" : effect;
            this.daytime = daytime;
            this.condition = condition == null || condition.trim().isEmpty() ? "Unknown" : condition.trim();
        }

        static SceneSpec fromWeather(JSONObject weather, boolean fallbackDaytime) {
            String condition = description(weather);
            String key = conditionKey(condition);
            boolean daytime = safeBoolean(weather, "isDaytime", fallbackDaytime);
            if ("snow".equals(key)) return new SceneSpec("snow", "snow", daytime, condition);
            if ("thunder".equals(key)) return new SceneSpec("rain", "thunder", daytime, condition);
            if ("rain".equals(key)) return new SceneSpec("rain", "rain", daytime, condition);
            if ("fog".equals(key)) return new SceneSpec(daytime ? "rain" : "night", "fog", daytime, condition);
            return new SceneSpec(daytime ? "day" : "night", "none", daytime, condition);
        }

        String paletteScene() {
            if ("snow".equals(effect)) return "snow";
            if ("rain".equals(effect) || "thunder".equals(effect) || "fog".equals(effect)) return "rain";
            return daytime ? "day" : "night";
        }

        String headerScene() {
            if ("fog".equals(effect) || "thunder".equals(effect)) return effect;
            if ("rain".equals(effect)) return "rain";
            if ("snow".equals(effect)) return "snow";
            return daytime ? "day" : "night";
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof SceneSpec)) return false;
            SceneSpec that = (SceneSpec) other;
            return daytime == that.daytime
                    && base.equals(that.base)
                    && effect.equals(that.effect)
                    && condition.equals(that.condition);
        }

        @Override
        public int hashCode() {
            int result = base.hashCode();
            result = 31 * result + effect.hashCode();
            result = 31 * result + (daytime ? 1 : 0);
            result = 31 * result + condition.hashCode();
            return result;
        }
    }


    private static final class MinuteForecastState {
        final int generation;
        final double latitude;
        final double longitude;
        final String language;
        final boolean loading;
        final long fetchedAtMillis;
        final JSONObject response;
        final String errorMessage;
        final boolean unsupported;

        MinuteForecastState(
                int generation,
                double latitude,
                double longitude,
                String language,
                boolean loading,
                long fetchedAtMillis,
                JSONObject response,
                String errorMessage,
                boolean unsupported) {
            this.generation = generation;
            this.latitude = latitude;
            this.longitude = longitude;
            this.language = language == null ? "" : language;
            this.loading = loading;
            this.fetchedAtMillis = fetchedAtMillis;
            this.response = response;
            this.errorMessage = errorMessage == null ? "" : errorMessage;
            this.unsupported = unsupported;
        }

        boolean matches(int generation, double latitude, double longitude, String language) {
            return this.generation == generation
                    && Double.doubleToLongBits(this.latitude) == Double.doubleToLongBits(latitude)
                    && Double.doubleToLongBits(this.longitude) == Double.doubleToLongBits(longitude)
                    && this.language.equals(language == null ? "" : language);
        }

        boolean sameLocationLanguage(double latitude, double longitude, String language) {
            return Double.doubleToLongBits(this.latitude) == Double.doubleToLongBits(latitude)
                    && Double.doubleToLongBits(this.longitude) == Double.doubleToLongBits(longitude)
                    && this.language.equals(language == null ? "" : language);
        }

        boolean isFresh(long nowMillis) {
            if (loading || response == null || fetchedAtMillis <= 0L) return false;
            long age = nowMillis - fetchedAtMillis;
            return age >= 0L && age < MINUTE_CACHE_MAX_AGE_MILLIS;
        }

        MinuteForecastState rebind(int generation) {
            return new MinuteForecastState(
                    generation, latitude, longitude, language, false, fetchedAtMillis, response, "", false);
        }
    }

    private static final class OptionalDataState {
        final int generation;
        final double latitude;
        final double longitude;
        final String language;
        final boolean loading;
        final boolean available;
        final String value;
        final String accessibility;

        OptionalDataState(
                int generation,
                double latitude,
                double longitude,
                String language,
                boolean loading,
                boolean available,
                String value,
                String accessibility) {
            this.generation = generation;
            this.latitude = latitude;
            this.longitude = longitude;
            this.language = language == null ? "" : language;
            this.loading = loading;
            this.available = available;
            this.value = value == null ? "" : value;
            this.accessibility = accessibility == null ? "" : accessibility;
        }

        boolean matches(int generation, double latitude, double longitude, String language) {
            return this.generation == generation
                    && Double.doubleToLongBits(this.latitude) == Double.doubleToLongBits(latitude)
                    && Double.doubleToLongBits(this.longitude) == Double.doubleToLongBits(longitude)
                    && this.language.equals(language == null ? "" : language);
        }
    }

    private static final class MinuteCacheSnapshot {
        final long fetchedAtMillis;
        final JSONObject response;

        MinuteCacheSnapshot(long fetchedAtMillis, JSONObject response) {
            this.fetchedAtMillis = fetchedAtMillis;
            this.response = response;
        }
    }

    private static final class MinuteSegment {
        final JSONObject raw;
        final Instant start;
        final Instant end;
        final Integer probability;
        final Double qpfQuantity;
        final String qpfUnit;
        final Double snowfallQuantity;
        final String snowfallUnit;
        final String type;
        final String intensity;

        MinuteSegment(
                JSONObject raw,
                Instant start,
                Instant end,
                Integer probability,
                Double qpfQuantity,
                String qpfUnit,
                Double snowfallQuantity,
                String snowfallUnit,
                String type,
                String intensity) {
            this.raw = raw;
            this.start = start;
            this.end = end;
            this.probability = probability;
            this.qpfQuantity = qpfQuantity;
            this.qpfUnit = qpfUnit == null ? "" : qpfUnit;
            this.snowfallQuantity = snowfallQuantity;
            this.snowfallUnit = snowfallUnit == null ? "" : snowfallUnit;
            this.type = type == null ? "" : type;
            this.intensity = intensity == null ? "" : intensity;
        }
    }

    private static final class MinuteCoverage {
        final Instant start;
        final Instant end;

        MinuteCoverage(Instant start, Instant end) {
            this.start = start;
            this.end = end;
        }
    }

    private static final class PreviewBinding {
        final WeakReference<View> view;
        final String key;

        PreviewBinding(View view, String key) {
            this.view = new WeakReference<>(view);
            this.key = key == null ? "" : key;
        }
    }

    private final class ForecastPreviewController {
        private SceneSpec currentScene = new SceneSpec("day", "none", true, "Clear");
        private SceneSpec previewScene;
        private String selectionKey = "";
        private String previewLabel = "";
        private final ArrayList<PreviewBinding> bindings = new ArrayList<>();
        private final ArrayList<WeakReference<ForecastChartView>> charts = new ArrayList<>();

        void setCurrentScene(SceneSpec scene) {
            currentScene = scene == null ? new SceneSpec("day", "none", true, "Clear") : scene;
            previewScene = null;
            selectionKey = "";
            previewLabel = "";
            bindings.clear();
            charts.clear();
            updatePreviewSubtitle();
        }

        SceneSpec currentScene() {
            return currentScene;
        }

        boolean isPreviewing() {
            return previewScene != null && !selectionKey.isEmpty();
        }

        String selectionKey() {
            return selectionKey;
        }

        boolean select(String key, String label, SceneSpec scene) {
            if (key == null || key.isEmpty() || scene == null) return false;
            if (key.equals(selectionKey)) {
                restore(true);
                return false;
            }
            selectionKey = key;
            previewLabel = label == null ? "" : label;
            previewScene = scene;
            if (skyLayout != null) skyLayout.setScene(scene);
            if (headerGlass != null) headerGlass.setScene(scene, animationsAllowed());
            updatePreviewSubtitle();
            refreshSelectionVisuals();
            announcePreview(previewLabel + ", " + scene.condition + ". Previewing forecast. Tap again or the location to return to now.");
            return true;
        }

        void restore(boolean announce) {
            boolean hadPreview = isPreviewing();
            previewScene = null;
            selectionKey = "";
            previewLabel = "";
            if (skyLayout != null) skyLayout.setScene(currentScene);
            if (headerGlass != null) headerGlass.setScene(currentScene, animationsAllowed());
            updatePreviewSubtitle();
            refreshSelectionVisuals();
            if (announce && hadPreview) announcePreview("Back to current weather");
        }

        void registerTarget(View view, String key) {
            if (view == null || key == null) return;
            bindings.add(new PreviewBinding(view, key));
            applyPreviewSelectionVisual(view, key.equals(selectionKey));
        }

        void registerChart(ForecastChartView chart) {
            if (chart == null) return;
            charts.add(new WeakReference<>(chart));
            chart.syncPreviewFromController();
        }

        void dispose() {
            previewScene = null;
            selectionKey = "";
            previewLabel = "";
            bindings.clear();
            charts.clear();
        }

        private void refreshSelectionVisuals() {
            for (int i = bindings.size() - 1; i >= 0; i--) {
                PreviewBinding binding = bindings.get(i);
                View view = binding.view.get();
                if (view == null) {
                    bindings.remove(i);
                    continue;
                }
                applyPreviewSelectionVisual(view, binding.key.equals(selectionKey));
            }
            for (int i = charts.size() - 1; i >= 0; i--) {
                ForecastChartView chart = charts.get(i).get();
                if (chart == null) {
                    charts.remove(i);
                    continue;
                }
                chart.syncPreviewFromController();
            }
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        configureWindow();

        latitude = getPreferences(MODE_PRIVATE).getFloat("lat", (float) latitude);
        longitude = getPreferences(MODE_PRIVATE).getFloat("lon", (float) longitude);
        locationName = getPreferences(MODE_PRIVATE).getString("name", locationName);
        CityManagerActivity.migrateLegacyMainActivityPreferences(this);
        CityManagerActivity.LocationSnapshot selected = CityManagerActivity.getSelectedLocation(this);
        if (selected != null) {
            selectedLocationId = selected.id;
            latitude = selected.lat;
            longitude = selected.lon;
            locationName = selected.name;
            usingDeviceLocation = selected.isDevice;
            getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_EXPLICIT_NON_DEVICE_LOCATION, !selected.isDevice)
                    .apply();
        }
        activeTemperatureUnit = temperatureUnitPreference();
        cleanupForecastCaches();
        cleanupMinuteForecastCaches();
        cleanupOptionalCaches();

        buildShell();
        if (!hasConfiguredApiKey()) {
            status.setText("Google API key required");
            progress.setVisibility(View.GONE);
            showApiKeySetupDialog();
            return;
        }
        continueStartupAfterApiKey();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }

        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                        0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            window.setFlags(
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private void buildShell() {
        skyLayout = new SkyLayout(this);
        SkyLayout root = skyLayout;

        mainScroll = new RefreshScrollView(this);
        RefreshScrollView scroll = mainScroll;
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        scroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            float depth = Math.min(1f, scrollY / (float) dp(72));
            if (headerGlass != null) {
                headerGlass.setScrollDepth(depth);
                headerGlass.requestBlurRefresh();
            }
        });

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(76), dp(18), dp(44));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        headerGlass = new HeaderGlassView(this, scroll);
        headerGlass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        headerGlass.setClickable(false);
        headerGlass.setFocusable(false);
        FrameLayout.LayoutParams glassLp = new FrameLayout.LayoutParams(-1, dp(68), Gravity.TOP);
        root.addView(headerGlass, glassLp);

        refreshIndicator = new RefreshIndicatorView(this);
        refreshIndicator.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams refreshLp = new FrameLayout.LayoutParams(
                Math.min(dp(196), getResources().getDisplayMetrics().widthPixels - dp(48)),
                dp(42),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        refreshLp.topMargin = dp(72);
        root.addView(refreshIndicator, refreshLp);

        toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(18), dp(2), dp(18), 0);
        toolbar.setBackgroundColor(Color.TRANSPARENT);

        locationArea = new LinearLayout(this);
        locationArea.setOrientation(LinearLayout.VERTICAL);
        locationArea.setGravity(Gravity.CENTER_VERTICAL);
        locationArea.setClickable(true);
        locationArea.setFocusable(true);
        locationArea.setOnClickListener(v -> {
            if (forecastPreview.isPreviewing()) forecastPreview.restore(true);
        });

        locationTitle = text(locationName, 20, false, WHITE);
        locationTitle.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        locationTitle.setGravity(Gravity.CENTER_VERTICAL);
        locationTitle.setSingleLine(true);
        locationTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        locationArea.addView(locationTitle, new LinearLayout.LayoutParams(-1, dp(29)));

        previewSubtitle = text("", 10, true, Color.argb(220, 255, 255, 255));
        previewSubtitle.setSingleLine(true);
        previewSubtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        previewSubtitle.setGravity(Gravity.CENTER_VERTICAL);
        previewSubtitle.setVisibility(View.GONE);
        locationArea.addView(previewSubtitle, new LinearLayout.LayoutParams(-1, dp(17)));
        makeChildrenUnimportant(locationArea);
        toolbar.addView(locationArea, new LinearLayout.LayoutParams(0, dp(52), 1));

        HeaderGlyphButton places = new HeaderGlyphButton(this, HeaderGlyphButton.MENU_PLUS);
        places.setContentDescription("Choose forecast location");
        places.setOnClickListener(v -> openCityManager());
        toolbar.addView(places, new LinearLayout.LayoutParams(dp(44), dp(44)));

        HeaderGlyphButton settings = new HeaderGlyphButton(this, HeaderGlyphButton.SETTINGS);
        settings.setContentDescription("Weather options");
        settings.setOnClickListener(v -> openSettings());
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        settingsLp.leftMargin = dp(2);
        toolbar.addView(settings, settingsLp);

        FrameLayout.LayoutParams toolbarLp = new FrameLayout.LayoutParams(-1, dp(68), Gravity.TOP);
        root.addView(toolbar, toolbarLp);
        setContentView(root);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            int pinnedHeaderBottom = top + dp(68);
            content.setPadding(dp(18), pinnedHeaderBottom + dp(8), dp(18), bottom + dp(38));
            toolbar.setPadding(dp(18), top, dp(18), 0);
            FrameLayout.LayoutParams toolbarParams = (FrameLayout.LayoutParams) toolbar.getLayoutParams();
            toolbarParams.height = pinnedHeaderBottom;
            toolbar.setLayoutParams(toolbarParams);
            if (headerGlass != null) {
                FrameLayout.LayoutParams glassParams = (FrameLayout.LayoutParams) headerGlass.getLayoutParams();
                glassParams.height = pinnedHeaderBottom;
                headerGlass.setLayoutParams(glassParams);
                headerGlass.requestBlurRefresh();
            }
            if (refreshIndicator != null) {
                FrameLayout.LayoutParams refreshParams =
                        (FrameLayout.LayoutParams) refreshIndicator.getLayoutParams();
                refreshParams.topMargin = pinnedHeaderBottom + dp(6);
                refreshIndicator.setLayoutParams(refreshParams);
            }
            return insets;
        });

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(2), 0, dp(7));
        StatusGlyphView statusGlyph = new StatusGlyphView(this);
        statusGlyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        statusRow.addView(statusGlyph, new LinearLayout.LayoutParams(dp(24), dp(24)));
        status = text("Preparing Zwerk Weather…", 13, false, SOFT_WHITE);
        status.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(0, dp(28), 1f);
        statusLp.leftMargin = dp(6);
        statusRow.addView(status, statusLp);
        content.addView(statusRow);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setAlpha(0.58f);
        content.addView(progress, new LinearLayout.LayoutParams(-1, dp(1)));

        addForecastModeSwitch();
        dynamicStartIndex = content.getChildCount();
        updatePreviewSubtitle();
    }

    private void addForecastModeSwitch() {
        LinearLayout switchHolder = new LinearLayout(this);
        switchHolder.setOrientation(LinearLayout.HORIZONTAL);
        switchHolder.setGravity(Gravity.CENTER);
        switchHolder.setPadding(dp(5), dp(4), dp(5), dp(4));
        modeSwitchGlass = new GlassDrawable(
                dp(23),
                Math.max(1f, getResources().getDisplayMetrics().density),
                true);
        applyGlassPalette(modeSwitchGlass);
        glassDrawables.add(modeSwitchGlass);
        switchHolder.setBackground(modeSwitchGlass);

        overviewModeButton = dailyModeButton("Overview", "Overview forecast view");
        precipitationModeButton = dailyModeButton("Precipitation", "Minute precipitation view");
        overviewModeButton.setTextSize(13);
        precipitationModeButton.setTextSize(13);
        overviewModeButton.setOnClickListener(v -> switchForecastMode(false));
        precipitationModeButton.setOnClickListener(v -> switchForecastMode(true));
        switchHolder.addView(overviewModeButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams precipLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        precipLp.leftMargin = dp(4);
        switchHolder.addView(precipitationModeButton, precipLp);

        LinearLayout.LayoutParams holderLp = new LinearLayout.LayoutParams(-1, dp(56));
        holderLp.topMargin = dp(11);
        holderLp.bottomMargin = dp(8);
        content.addView(switchHolder, holderLp);
        updateForecastModeButtons();
    }

    private void updateForecastModeButtons() {
        if (overviewModeButton == null || precipitationModeButton == null) return;
        updateDailyModeButton(overviewModeButton, !precipitationMode, "Overview forecast view");
        updateDailyModeButton(precipitationModeButton, precipitationMode, "Minute precipitation view");
    }

    private void switchForecastMode(boolean precipitation) {
        if (precipitationMode == precipitation) return;
        int currentScroll = mainScroll == null ? 0 : mainScroll.getScrollY();
        if (precipitation) {
            overviewScrollY = currentScroll;
        } else {
            precipitationScrollY = currentScroll;
        }
        forecastPreview.restore(false);
        precipitationMode = precipitation;
        updateForecastModeButtons();
        renderCurrentMode();
        int targetScroll = precipitation ? precipitationScrollY : overviewScrollY;
        if (mainScroll != null) {
            mainScroll.post(() -> {
                mainScroll.scrollTo(0, Math.max(0, targetScroll));
                if (precipitationMode && precipitation) ensureMinuteForecast(false);
            });
        } else if (precipitation) {
            ensureMinuteForecast(false);
        }
        if (!precipitation) forecastPreview.restore(false);
    }

    private void continueStartupAfterApiKey() {
        progress.setVisibility(View.VISIBLE);
        CityManagerActivity.LocationSnapshot selected = CityManagerActivity.getSelectedLocation(this);
        if (selected != null) {
            loadWeather();
        } else {
            requestDeviceLocationOrLoad();
        }
    }

    private void showApiKeySetupDialog() {
        if (apiKeySetupDialog != null && apiKeySetupDialog.isShowing()) return;

        final Dialog dialog = new Dialog(this);
        apiKeySetupDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setClipToPadding(false);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(20), dp(20), dp(18));
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(Color.argb(246, 12, 23, 39));
        panelBackground.setCornerRadius(dp(24));
        panelBackground.setStroke(dp(1), Color.argb(54, 255, 255, 255));
        panel.setBackground(panelBackground);
        scroller.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Set up Zwerk Weather", 23, false, WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(title);

        TextView explanation = text(
                "Zwerk Weather uses Google Weather. Your key is saved only in this app's private storage and is never shown after saving.",
                13,
                false,
                SOFT_WHITE);
        explanation.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams explanationLp = new LinearLayout.LayoutParams(-1, -2);
        explanationLp.topMargin = dp(5);
        panel.addView(explanation, explanationLp);

        addApiKeySetupGuide(panel);

        TextView label = text("Google API key", 13, true, WHITE);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-1, -2);
        labelLp.topMargin = dp(18);
        panel.addView(label, labelLp);

        EditText field = apiKeyEditText();
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(-1, dp(52));
        fieldLp.topMargin = dp(7);
        panel.addView(field, fieldLp);

        TextView error = text("", 12, false, Color.rgb(255, 191, 191));
        error.setVisibility(View.GONE);
        LinearLayout.LayoutParams errorLp = new LinearLayout.LayoutParams(-1, -2);
        errorLp.topMargin = dp(8);
        panel.addView(error, errorLp);

        Button save = coordinateDialogButton("Save & continue", true);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(-1, dp(48));
        saveLp.topMargin = dp(16);
        panel.addView(save, saveLp);

        View.OnClickListener saveAction = v -> {
            String candidate = validatedApiKeyInput(field);
            if (candidate.isEmpty()) {
                showApiKeySaveFailure(error);
                return;
            }
            try {
                writeApiKeyAtomically(candidate);
                field.getText().clear();
                error.setVisibility(View.GONE);
                status.setText("Preparing Zwerk Weather…");
                progress.setVisibility(View.VISIBLE);
                forceNextWeatherLoad = true;
                dialog.dismiss();
                continueStartupAfterApiKey();
            } catch (Exception ignored) {
                showApiKeySaveFailure(error);
            }
        };
        save.setOnClickListener(saveAction);
        field.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                save.performClick();
                return true;
            }
            return false;
        });

        dialog.setOnDismissListener(ignored -> {
            if (apiKeySetupDialog == dialog) apiKeySetupDialog = null;
        });
        dialog.setContentView(scroller);
        dialog.show();

        Window dialogWindow = dialog.getWindow();
        if (dialogWindow != null) {
            dialogWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = dialogWindow.getAttributes();
            attributes.dimAmount = 0.78f;
            dialogWindow.setAttributes(attributes);
            dialogWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            dialogWindow.setGravity(Gravity.CENTER);
            dialogWindow.getDecorView().setPadding(0, 0, 0, 0);

            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int dialogWidth = Math.min(screenWidth - dp(32), dp(420));
            dialogWindow.setLayout(
                    Math.max(1, dialogWidth),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void addApiKeySetupGuide(LinearLayout panel) {
        TextView heading = text("Get a key in 3 steps", 14, true, WHITE);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(-1, -2);
        headingLp.topMargin = dp(16);
        panel.addView(heading, headingLp);

        TextView steps = text(
                "1. Create or select a Google Cloud project and enable billing.\n"
                        + "2. Enable Weather API. Enable Air Quality API and Pollen API too if you want those optional tiles.\n"
                        + "3. Create a key. Add an Android app restriction with the package and SHA-1 below. Under API restrictions, allow every API you enabled, then paste the key below.",
                12,
                false,
                SOFT_WHITE);
        steps.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams stepsLp = new LinearLayout.LayoutParams(-1, -2);
        stepsLp.topMargin = dp(5);
        panel.addView(steps, stepsLp);

        TextView identity = text(androidRestrictionIdentity(), 11, false, FAINT_WHITE);
        identity.setTextIsSelectable(true);
        identity.setContentDescription("Android API key restriction identity. "
                + androidRestrictionIdentity().replace("\n", ". "));
        LinearLayout.LayoutParams identityLp = new LinearLayout.LayoutParams(-1, -2);
        identityLp.topMargin = dp(8);
        panel.addView(identity, identityLp);

        LinearLayout links = new LinearLayout(this);
        links.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams linksLp = new LinearLayout.LayoutParams(-1, dp(44));
        linksLp.topMargin = dp(10);
        panel.addView(links, linksLp);

        Button guide = coordinateDialogButton("Setup guide ↗", false);
        guide.setOnClickListener(v -> openExternalUrl(API_KEY_GUIDE_URL));
        links.addView(guide, new LinearLayout.LayoutParams(0, dp(44), 1f));

        Button console = coordinateDialogButton("Cloud Console ↗", false);
        console.setOnClickListener(v -> openExternalUrl(GOOGLE_CLOUD_CREDENTIALS_URL));
        LinearLayout.LayoutParams consoleLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        consoleLp.leftMargin = dp(8);
        links.addView(console, consoleLp);
    }

    private void openExternalUrl(String address) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address)));
        } catch (Exception ignored) {
            Toast.makeText(this, "No browser is available to open this link.", Toast.LENGTH_SHORT).show();
        }
    }

    private String androidRestrictionIdentity() {
        String fingerprint = signingCertificateSha1();
        return "Android restriction\nPackage: " + getPackageName()
                + (fingerprint.isEmpty() ? "" : "\nSHA-1: " + fingerprint);
    }

    private String signingCertificateSha1() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(
                    getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null) return "";
            android.content.pm.Signature[] signatures = info.signingInfo.getApkContentsSigners();
            if (signatures == null || signatures.length == 0) return "";
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(signatures[0].toByteArray());
            StringBuilder value = new StringBuilder();
            for (byte part : digest) {
                if (value.length() > 0) value.append(':');
                value.append(String.format(Locale.US, "%02X", part & 0xff));
            }
            return value.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private EditText apiKeyEditText() {
        EditText field = new EditText(this);
        field.setHint("API key");
        field.setTextColor(WHITE);
        field.setHintTextColor(Color.argb(110, 255, 255, 255));
        field.setTextSize(16);
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
        field.setBackground(coordinateFieldBackground());
        field.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 26) {
            field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            field.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO);
        }
        return field;
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

    private boolean hasConfiguredApiKey() {
        try {
            return !readApiKey().isEmpty();
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

    private void requestDeviceLocationOrLoad() {
        requestDeviceLocationOrLoad(false);
    }

    private void requestDeviceLocationOrLoad(boolean explicitRequest) {
        if (!explicitRequest && shouldProtectSelectedLocation()) {
            loadWeather();
            return;
        }
        deviceLocationRequestExplicit = explicitRequest;
        deviceLocationRequestGeneration = locationSelectionGeneration;
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_REQUEST);
            loadWeather();
            return;
        }
        updateFromDeviceLocation(explicitRequest);
    }

    private boolean shouldProtectSelectedLocation() {
        if (getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getBoolean(PREF_EXPLICIT_NON_DEVICE_LOCATION, false)) {
            return true;
        }
        CityManagerActivity.LocationSnapshot selected = CityManagerActivity.getSelectedLocation(this);
        return selected != null && !selected.isDevice;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == LOCATION_REQUEST) {
            boolean explicitRequest = deviceLocationRequestExplicit;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                updateFromDeviceLocation(explicitRequest);
            } else {
                deviceLocationRequestExplicit = false;
                loadWeather();
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    private void updateFromDeviceLocation(boolean explicitRequest) {
        if (!explicitRequest && shouldProtectSelectedLocation()) {
            deviceLocationRequestExplicit = false;
            loadWeather();
            return;
        }

        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        status.setText("Finding your location…");
        if (manager == null) {
            deviceLocationRequestExplicit = false;
            loadWeather();
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                String provider;
                if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    provider = LocationManager.NETWORK_PROVIDER;
                } else if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    provider = LocationManager.GPS_PROVIDER;
                } else {
                    deviceLocationRequestExplicit = false;
                    loadWeather();
                    return;
                }
                manager.getCurrentLocation(provider, new CancellationSignal(), getMainExecutor(), location -> {
                    if (location != null) {
                        acceptLocation(
                                location.getLatitude(),
                                location.getLongitude(),
                                null,
                                true,
                                explicitRequest);
                    } else {
                        deviceLocationRequestExplicit = false;
                        loadWeather();
                    }
                });
            } else {
                Location last = null;
                if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    last = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }
                if (last == null && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    last = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                }
                if (last != null) {
                    acceptLocation(
                            last.getLatitude(),
                            last.getLongitude(),
                            null,
                            true,
                            explicitRequest);
                    return;
                }

                String provider = manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                        ? LocationManager.NETWORK_PROVIDER : LocationManager.GPS_PROVIDER;
                manager.requestSingleUpdate(provider, new LocationListener() {
                    @Override
                    public void onLocationChanged(Location location) {
                        acceptLocation(
                                location.getLatitude(),
                                location.getLongitude(),
                                null,
                                true,
                                explicitRequest);
                    }

                    @Override public void onProviderEnabled(String provider) { }
                    @Override public void onProviderDisabled(String provider) {
                        deviceLocationRequestExplicit = false;
                        loadWeather();
                    }
                    @Override public void onStatusChanged(String provider, int value, Bundle extras) { }
                }, null);
            }
        } catch (Exception ignored) {
            deviceLocationRequestExplicit = false;
            loadWeather();
        }
    }

    private void openCityManager() {
        startActivityForResult(new Intent(this, CityManagerActivity.class), CITY_MANAGER_REQUEST);
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_SCENE, displayedScene)
                .putExtra(SettingsActivity.EXTRA_DAYTIME, displayedDaytime)
                .putExtra(SettingsActivity.EXTRA_CARD_TOP, glassCardTop)
                .putExtra(SettingsActivity.EXTRA_CARD_BOTTOM, glassCardBottom)
                .putExtra(SettingsActivity.EXTRA_TILE_TOP, glassTileTop)
                .putExtra(SettingsActivity.EXTRA_TILE_BOTTOM, glassTileBottom)
                .putExtra(SettingsActivity.EXTRA_ACCENT, settingsAccent(displayedScene));
        startActivityForResult(intent, SETTINGS_REQUEST);
    }

    private void showCoordinateDialog() {
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
        panel.setPadding(dp(20), dp(20), dp(20), dp(18));
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(Color.argb(244, 14, 23, 34));
        panelBackground.setCornerRadius(dp(24));
        panelBackground.setStroke(dp(1), Color.argb(42, 255, 255, 255));
        panel.setBackground(panelBackground);
        scroller.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Forecast location", 23, false, WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setContentDescription("Forecast location");
        panel.addView(title);

        TextView explanation = text(
                "Enter a precise forecast point. Signed decimal coordinates are supported.",
                13,
                false,
                SOFT_WHITE);
        explanation.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams explanationLp = new LinearLayout.LayoutParams(-1, -2);
        explanationLp.topMargin = dp(5);
        panel.addView(explanation, explanationLp);

        TextView latLabel = text("Latitude", 13, true, WHITE);
        LinearLayout.LayoutParams firstLabelLp = new LinearLayout.LayoutParams(-1, -2);
        firstLabelLp.topMargin = dp(18);
        panel.addView(latLabel, firstLabelLp);

        TextView latExample = text("Example 50.8503  •  Range −90 to 90", 11, false, FAINT_WHITE);
        LinearLayout.LayoutParams helperLp = new LinearLayout.LayoutParams(-1, -2);
        helperLp.topMargin = dp(2);
        panel.addView(latExample, helperLp);

        EditText lat = coordinateEditText(
                String.format(Locale.US, "%.4f", latitude),
                "50.8503",
                "Latitude. Range minus 90 to 90 degrees.");
        lat.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(-1, dp(52));
        fieldLp.topMargin = dp(7);
        panel.addView(lat, fieldLp);

        TextView lonLabel = text("Longitude", 13, true, WHITE);
        LinearLayout.LayoutParams secondLabelLp = new LinearLayout.LayoutParams(-1, -2);
        secondLabelLp.topMargin = dp(14);
        panel.addView(lonLabel, secondLabelLp);

        TextView lonExample = text("Example 4.3517  •  Range −180 to 180", 11, false, FAINT_WHITE);
        LinearLayout.LayoutParams lonHelperLp = new LinearLayout.LayoutParams(-1, -2);
        lonHelperLp.topMargin = dp(2);
        panel.addView(lonExample, lonHelperLp);

        EditText lon = coordinateEditText(
                String.format(Locale.US, "%.4f", longitude),
                "4.3517",
                "Longitude. Range minus 180 to 180 degrees.");
        lon.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        LinearLayout.LayoutParams lonFieldLp = new LinearLayout.LayoutParams(-1, dp(52));
        lonFieldLp.topMargin = dp(7);
        panel.addView(lon, lonFieldLp);

        Button useLocation = coordinateDialogButton("Use my location", false);
        useLocation.setContentDescription("Use my location for the forecast");
        LinearLayout.LayoutParams locationLp = new LinearLayout.LayoutParams(-1, dp(48));
        locationLp.topMargin = dp(18);
        panel.addView(useLocation, locationLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, dp(48));
        actionsLp.topMargin = dp(10);
        panel.addView(actions, actionsLp);

        Button cancel = coordinateDialogButton("Cancel", false);
        cancel.setContentDescription("Cancel coordinate entry");
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        actions.addView(cancel, cancelLp);

        Button load = coordinateDialogButton("Load", true);
        load.setContentDescription("Load forecast for these coordinates");
        LinearLayout.LayoutParams loadLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        loadLp.leftMargin = dp(10);
        actions.addView(load, loadLp);

        cancel.setOnClickListener(v -> dialog.dismiss());
        useLocation.setOnClickListener(v -> {
            dialog.dismiss();
            requestDeviceLocationOrLoad(true);
        });
        load.setOnClickListener(v -> {
            try {
                double newLat = Double.parseDouble(lat.getText().toString().trim());
                double newLon = Double.parseDouble(lon.getText().toString().trim());
                if (newLat < -90 || newLat > 90 || newLon < -180 || newLon > 180) {
                    throw new NumberFormatException();
                }
                dialog.dismiss();
                acceptLocation(newLat, newLon, null, false);
            } catch (NumberFormatException e) {
                dialog.dismiss();
                Toast.makeText(this, "Invalid coordinates", Toast.LENGTH_SHORT).show();
            }
        });
        lon.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                load.performClick();
                return true;
            }
            return false;
        });

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
            int dialogWidth = Math.min(screenWidth - dp(32), dp(420));
            dialogWindow.setLayout(
                    Math.max(1, dialogWidth),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private EditText coordinateEditText(String value, String hint, String accessibilityLabel) {
        EditText field = new EditText(this);
        field.setText(value);
        field.setHint(hint);
        field.setTextColor(WHITE);
        field.setHintTextColor(Color.argb(110, 255, 255, 255));
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setSelectAllOnFocus(true);
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        field.setBackground(coordinateFieldBackground());
        field.setContentDescription(accessibilityLabel + " Current value " + value + ".");
        return field;
    }

    private StateListDrawable coordinateFieldBackground() {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Color.argb(44, 255, 255, 255));
        focused.setCornerRadius(dp(14));
        focused.setStroke(dp(1), Color.argb(220, 151, 211, 255));

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.argb(25, 255, 255, 255));
        normal.setCornerRadius(dp(14));
        normal.setStroke(dp(1), Color.argb(50, 255, 255, 255));

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, normal);
        return states;
    }

    private Button coordinateDialogButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(WHITE);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setMinHeight(dp(48));

        int normalColor = primary
                ? Color.rgb(45, 112, 214)
                : Color.argb(34, 255, 255, 255);
        int pressedColor = primary
                ? Color.rgb(58, 132, 232)
                : Color.argb(58, 255, 255, 255);

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(normalColor);
        normal.setCornerRadius(dp(14));

        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(pressedColor);
        pressed.setCornerRadius(dp(14));

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{android.R.attr.state_focused}, pressed);
        states.addState(new int[]{}, normal);
        button.setBackground(states);
        return button;
    }

    private void acceptLocation(double lat, double lon, String explicitName, boolean isDevice) {
        acceptLocation(lat, lon, explicitName, isDevice, !isDevice);
    }

    private void acceptLocation(
            double lat,
            double lon,
            String explicitName,
            boolean isDevice,
            boolean explicitSelection) {
        if (isDevice
                && shouldProtectSelectedLocation()
                && (!explicitSelection
                        || locationSelectionGeneration != deviceLocationRequestGeneration)) {
            deviceLocationRequestExplicit = false;
            loadWeather();
            return;
        }

        if (explicitSelection) {
            locationSelectionGeneration++;
        }
        invalidateMinuteForecastState();
        latitude = lat;
        longitude = lon;
        usingDeviceLocation = isDevice;
        locationName = explicitName != null
                ? explicitName
                : String.format(Locale.US, "%.3f°, %.3f°", lat, lon);
        locationTitle.setText(locationName);
        getPreferences(MODE_PRIVATE).edit()
                .putFloat("lat", (float) lat)
                .putFloat("lon", (float) lon)
                .putString("name", locationName)
                .apply();

        android.content.SharedPreferences.Editor selectionEditor =
                getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit();
        if (!isDevice) {
            selectionEditor.putBoolean(PREF_EXPLICIT_NON_DEVICE_LOCATION, true);
        } else if (explicitSelection) {
            selectionEditor.putBoolean(PREF_EXPLICIT_NON_DEVICE_LOCATION, false);
        }
        selectionEditor.apply();

        selectedLocationId = isDevice
                ? CityManagerActivity.upsertDeviceLocation(
                        this, locationName, lat, lon, "", "", true)
                : CityManagerActivity.upsertLocation(
                        this, null, locationName, lat, lon, false, "", "", true);
        deviceLocationRequestExplicit = false;
        loadWeather();
        resolvePlaceName(lat, lon);
    }

    private void resolvePlaceName(double lat, double lon) {
        executor.execute(() -> {
            try {
                List<Address> results = new Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1);
                if (results == null || results.isEmpty()) return;
                Address a = results.get(0);
                String name = a.getLocality();
                if (name == null || name.isEmpty()) name = a.getSubAdminArea();
                if (name == null || name.isEmpty()) name = a.getAdminArea();
                if (name == null || name.isEmpty()) return;

                final String resolved = name;
                locationName = resolved;
                getPreferences(MODE_PRIVATE).edit().putString("name", resolved).apply();
                selectedLocationId = CityManagerActivity.updateSelectedLocationSnapshot(
                        this, resolved, lat, lon, usingDeviceLocation, "", "");
                android.content.SharedPreferences widgetPrefs = getSharedPreferences(
                        WeatherWidgetProvider.PREFS_NAME, MODE_PRIVATE);
                if (widgetPrefs.getBoolean(WeatherWidgetProvider.KEY_HAS_SNAPSHOT, false)) {
                    widgetPrefs.edit()
                            .putString(WeatherWidgetProvider.KEY_CITY, resolved)
                            .apply();
                    WeatherWidgetProvider.requestRefresh(this);
                }
                runOnUiThread(() -> {
                    locationTitle.setText(resolved);
                    updatePreviewSubtitle();
                });
            } catch (Exception ignored) {
                // The coordinate label remains usable if reverse geocoding is unavailable.
            }
        });
    }

    private void loadWeather() {
        boolean forceNetwork = forceNextWeatherLoad;
        forceNextWeatherLoad = false;
        loadWeather(forceNetwork);
    }

    private void loadWeather(boolean forceNetwork) {
        forecastPreview.restore(false);
        final double lat = latitude;
        final double lon = longitude;
        final String language = Locale.getDefault().getLanguage();
        final String requestLocationId = selectedLocationId == null ? "" : selectedLocationId;
        if (weatherLoadActive) {
            boolean sameScope = Math.abs(activeLoadLatitude - lat) <= WEATHER_CACHE_COORDINATE_TOLERANCE
                    && Math.abs(activeLoadLongitude - lon) <= WEATHER_CACHE_COORDINATE_TOLERANCE
                    && activeLoadLanguage.equals(language)
                    && activeLoadLocationId.equals(requestLocationId);
            if (sameScope) {
                // Duplicate taps/callbacks while this exact base load is running add zero requests.
                return;
            }
            // A location/language change is not a duplicate. Coalesce it to one follow-up load.
            weatherReloadPending = true;
            weatherReloadForcePending |= forceNetwork;
            return;
        }

        weatherLoadActive = true;
        activeLoadLatitude = lat;
        activeLoadLongitude = lon;
        activeLoadLanguage = language;
        activeLoadLocationId = requestLocationId;
        progress.setVisibility(View.VISIBLE);
        status.setText("Loading Google Weather data…");
        final int generation = ++weatherRequestGeneration;
        synchronized (hourlyCoverageLock) {
            pendingHourlyCoverageTarget = null;
            hourlyCoverageLoadingTarget = null;
            hourlyCoverageCoordinators.clear();
        }
        rebindFreshMinuteStateToGeneration(generation, lat, lon, language);

        executor.execute(() -> {
            try {
                if (!forceNetwork) {
                    ForecastCacheSnapshot cached = readFreshForecastCache(
                            lat, lon, language, requestLocationId);
                    if (cached != null) {
                        HourlyPageState cachedState = new HourlyPageState(
                                generation, lat, lon, language, requestLocationId,
                                cached.hourly, "", false, true);
                        runOnUiThread(() -> finishWeatherLoadSuccess(
                                cached.current, cached.hourly, cached.daily,
                                cachedState, generation));
                        return;
                    }
                }

                if (!baseRequestScopeCurrent(generation, lat, lon, language, requestLocationId)) {
                    throw new SupersededWeatherRequestException();
                }
                String key = readApiKey();
                if (key.isEmpty()) throw new IllegalStateException("API key is not configured");
                String common = weatherCommonQuery(key, lat, lon, language);

                JSONObject current = requestLogical(
                        API_ROOT + "currentConditions:lookup" + common,
                        generation,
                        "current");
                if (!baseRequestScopeCurrent(generation, lat, lon, language, requestLocationId)) {
                    throw new SupersededWeatherRequestException();
                }
                JSONObject daily = requestLogical(
                        API_ROOT + "forecast/days:lookup" + common + "&days=10&pageSize=10",
                        generation,
                        "daily");
                if (!baseRequestScopeCurrent(generation, lat, lon, language, requestLocationId)) {
                    throw new SupersededWeatherRequestException();
                }
                HourlyPageLoad hourlyLoad = loadHourlyPageOneSafely(common, generation);
                if (!baseRequestScopeCurrent(generation, lat, lon, language, requestLocationId)) {
                    throw new SupersededWeatherRequestException();
                }
                JSONObject hourly = hourlyLoad.aggregate;
                HourlyPageState pageState = new HourlyPageState(
                        generation,
                        lat,
                        lon,
                        language,
                        requestLocationId,
                        hourly,
                        hourlyLoad.nextPageToken,
                        hourlyLoad.nextPageToken.isEmpty(),
                        !hourlyLoad.success);

                persistForecastCacheQuietly(
                        lat, lon, language, requestLocationId, current, hourly, daily);

                runOnUiThread(() -> finishWeatherLoadSuccess(
                        current, hourly, daily, pageState, generation));
            } catch (Exception e) {
                runOnUiThread(() -> finishWeatherLoadFailure(e, generation));
            }
        });
    }

    private boolean baseRequestScopeCurrent(
            int generation,
            double lat,
            double lon,
            String language,
            String requestLocationId) {
        return generation == weatherRequestGeneration
                && Math.abs(latitude - lat) <= WEATHER_CACHE_COORDINATE_TOLERANCE
                && Math.abs(longitude - lon) <= WEATHER_CACHE_COORDINATE_TOLERANCE
                && Locale.getDefault().getLanguage().equals(language)
                && (selectedLocationId == null ? "" : selectedLocationId)
                        .equals(requestLocationId == null ? "" : requestLocationId);
    }

    private static final class SupersededWeatherRequestException extends Exception {
        SupersededWeatherRequestException() {
            super("Weather request superseded");
        }
    }

    private String weatherCommonQuery(
            String key, double lat, double lon, String language) throws Exception {
        return "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8.name())
                + "&location.latitude=" + lat
                + "&location.longitude=" + lon
                + "&unitsSystem=METRIC"
                + "&languageCode=" + URLEncoder.encode(language, StandardCharsets.UTF_8.name());
    }

    private boolean isDebugBuild() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private JSONObject requestLogical(String address, int generation, String endpointKind) throws Exception {
        if (!requestScopeCurrent(generation)) throw new SupersededWeatherRequestException();
        if (isDebugBuild()) {
            Log.d(LOG_TAG, "request generation=" + generation + " endpoint=" + endpointKind);
        }
        try {
            JSONObject response = request(address);
            if (!requestScopeCurrent(generation)) throw new SupersededWeatherRequestException();
            return response;
        } catch (WeatherRequestException e) {
            if (e.statusCode == 408 || e.statusCode >= 500) {
                long jitter = 180L + ThreadLocalRandom.current().nextInt(121);
                SystemClock.sleep(jitter);
                if (!requestScopeCurrent(generation)) {
                    throw new SupersededWeatherRequestException();
                }
                if (isDebugBuild()) {
                    Log.d(LOG_TAG, "retry generation=" + generation + " endpoint=" + endpointKind);
                }
                JSONObject response = request(address);
                if (!requestScopeCurrent(generation)) throw new SupersededWeatherRequestException();
                return response;
            }
            // 429 and documented query-value rejections are deliberately not auto-retried.
            throw e;
        }
    }

    private boolean requestScopeCurrent(int generation) {
        if (generation != weatherRequestGeneration) return false;
        String language = Locale.getDefault().getLanguage();
        String locationId = selectedLocationId == null ? "" : selectedLocationId;
        if (weatherLoadActive) {
            return Math.abs(activeLoadLatitude - latitude) <= WEATHER_CACHE_COORDINATE_TOLERANCE
                    && Math.abs(activeLoadLongitude - longitude) <= WEATHER_CACHE_COORDINATE_TOLERANCE
                    && activeLoadLanguage.equals(language)
                    && activeLoadLocationId.equals(locationId);
        }
        HourlyPageState state = hourlyPageState;
        return state == null || state.matches(
                generation, latitude, longitude, language, locationId, "METRIC");
    }

    private void finishWeatherLoadSuccess(
            JSONObject current,
            JSONObject hourly,
            JSONObject daily,
            HourlyPageState pageState,
            int generation) {
        if (generation != weatherRequestGeneration) return;
        weatherLoadActive = false;
        finishRefreshIndicator();
        if (startPendingWeatherReloadIfNeeded()) return;
        hourlyPageState = pageState;
        render(current, hourly, daily, temperatureUnitPreference());
        requestEnabledOptionalDataForCurrentScope();
    }

    private void finishWeatherLoadFailure(Exception error, int generation) {
        if (generation != weatherRequestGeneration) return;
        weatherLoadActive = false;
        finishRefreshIndicator();
        if (startPendingWeatherReloadIfNeeded()) return;
        showError(error);
    }

    private boolean startPendingWeatherReloadIfNeeded() {
        if (!weatherReloadPending) return false;
        boolean forceNetwork = weatherReloadForcePending;
        weatherReloadPending = false;
        weatherReloadForcePending = false;
        loadWeather(forceNetwork);
        return true;
    }

    private boolean airQualityEnabled() {
        return getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getBoolean(PREF_AIR_QUALITY, false);
    }

    private boolean pollenEnabled() {
        return getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getBoolean(PREF_POLLEN, false);
    }

    private void requestEnabledOptionalDataForCurrentScope() {
        if (airQualityEnabled()) requestOptionalData(true, false, false);
        if (pollenEnabled()) requestOptionalData(false, true, false);
    }

    private void applyOptionalPreferenceChanges(
            boolean airChanged, boolean pollenChanged, boolean requestEnabledData) {
        boolean rerender = false;
        synchronized (optionalDataLock) {
            if (airChanged) {
                airQualityRequestSerial++;
                airQualityState = null;
                rerender = true;
            }
            if (pollenChanged) {
                pollenRequestSerial++;
                pollenState = null;
                rerender = true;
            }
        }
        if (rerender) rerenderOverviewPreservingScroll();
        if (!requestEnabledData) return;
        if (airChanged && airQualityEnabled()) requestOptionalData(true, false, true);
        if (pollenChanged && pollenEnabled()) requestOptionalData(false, true, true);
    }

    private void requestOptionalData(boolean airQuality, boolean pollen, boolean force) {
        if (airQuality == pollen) return;
        final boolean enabled = airQuality ? airQualityEnabled() : pollenEnabled();
        if (!enabled) return;

        final int generation = weatherRequestGeneration;
        final double lat = latitude;
        final double lon = longitude;
        final String language = Locale.getDefault().toLanguageTag();
        final long requestSerial;
        synchronized (optionalDataLock) {
            OptionalDataState state = airQuality ? airQualityState : pollenState;
            if (state != null && state.matches(generation, lat, lon, language)) {
                if (state.loading) return;
                if (!force) return;
            }
            requestSerial = airQuality ? ++airQualityRequestSerial : ++pollenRequestSerial;
            OptionalDataState loading = new OptionalDataState(
                    generation, lat, lon, language,
                    true, false, "Loading…",
                    airQuality ? "Air quality loading" : "Pollen loading");
            if (airQuality) airQualityState = loading;
            else pollenState = loading;
        }
        rerenderOverviewPreservingScroll();

        optionalExecutor.execute(() -> {
            OptionalDataState result;
            String endpointName = airQuality ? "air-quality-current" : "pollen-forecast";
            try {
                OptionalDataState cached = force
                        ? null
                        : readFreshOptionalDataCache(
                                airQuality, generation, lat, lon, language);
                if (cached != null) {
                    result = cached;
                } else {
                    String key = readApiKey();
                    if (key.isEmpty()) {
                        result = optionalUnavailable(
                                generation, lat, lon, language,
                                airQuality ? "Air quality unavailable" : "Pollen unavailable");
                    } else if (airQuality) {
                        result = loadAirQualityState(generation, lat, lon, language, key);
                    } else {
                        result = loadPollenState(generation, lat, lon, language, key);
                    }
                    if (result.available) {
                        persistOptionalDataCacheQuietly(
                                airQuality, lat, lon, language, result);
                    }
                }
            } catch (OptionalRequestException error) {
                if (isDebugBuild()) {
                    Log.d(LOG_TAG, "optional endpoint=" + endpointName
                            + " status=" + error.statusCode
                            + " reason=" + error.reason);
                }
                boolean keyBlocked = error.statusCode == 403
                        && "API_KEY_SERVICE_BLOCKED".equals(error.reason);
                result = optionalUnavailable(
                        generation, lat, lon, language,
                        keyBlocked ? "Key blocked" : "Unavailable",
                        keyBlocked
                                ? (airQuality
                                        ? "Air Quality API is not allowed by this API key's restrictions"
                                        : "Pollen API is not allowed by this API key's restrictions")
                                : (airQuality ? "Air quality unavailable" : "Pollen unavailable"));
            } catch (Exception ignored) {
                if (isDebugBuild()) {
                    Log.d(LOG_TAG, "optional endpoint=" + endpointName + " status=local-error");
                }
                result = optionalUnavailable(
                        generation, lat, lon, language,
                        airQuality ? "Air quality unavailable" : "Pollen unavailable");
            }

            final OptionalDataState delivered = result;
            runOnUiThread(() -> {
                if (!optionalScopeCurrent(generation, lat, lon, language)) return;
                if (airQuality && !airQualityEnabled()) return;
                if (pollen && !pollenEnabled()) return;
                synchronized (optionalDataLock) {
                    long currentSerial = airQuality ? airQualityRequestSerial : pollenRequestSerial;
                    if (requestSerial != currentSerial) return;
                    OptionalDataState pending = airQuality ? airQualityState : pollenState;
                    if (pending == null || !pending.matches(generation, lat, lon, language)) return;
                    if (airQuality) airQualityState = delivered;
                    else pollenState = delivered;
                }
                rerenderOverviewPreservingScroll();
            });
        });
    }

    private boolean optionalScopeCurrent(
            int generation, double lat, double lon, String language) {
        return generation == weatherRequestGeneration
                && Double.doubleToLongBits(latitude) == Double.doubleToLongBits(lat)
                && Double.doubleToLongBits(longitude) == Double.doubleToLongBits(lon)
                && Locale.getDefault().toLanguageTag().equals(language);
    }

    private OptionalDataState optionalUnavailable(
            int generation,
            double lat,
            double lon,
            String language,
            String accessibility) {
        return optionalUnavailable(
                generation, lat, lon, language, "Unavailable", accessibility);
    }

    private OptionalDataState optionalUnavailable(
            int generation,
            double lat,
            double lon,
            String language,
            String value,
            String accessibility) {
        return new OptionalDataState(
                generation, lat, lon, language,
                false, false, value, accessibility);
    }

    private void rerenderOverviewPreservingScroll() {
        if (precipitationMode || lastCurrentWeather == null || lastDailyWeather == null) return;
        int scrollY = mainScroll == null ? 0 : mainScroll.getScrollY();
        renderOverviewContent();
        if (mainScroll != null) {
            mainScroll.post(() -> mainScroll.scrollTo(0, Math.max(0, scrollY)));
        }
        if (headerGlass != null) headerGlass.requestBlurRefresh();
    }

    private void cleanupForecastCaches() {
        File[] files = getFilesDir().listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName();
            if (name.startsWith(WEATHER_CACHE_V1_PREFIX)) {
                file.delete();
                continue;
            }
            if (name.startsWith(WEATHER_CACHE_FILE_PREFIX)
                    && (name.endsWith(WEATHER_CACHE_FILE_SUFFIX)
                    || name.endsWith(WEATHER_CACHE_FILE_SUFFIX + ".tmp"))) {
                long modified = file.lastModified();
                if (modified <= 0L || now - modified >= WEATHER_CACHE_MAX_AGE_MILLIS) {
                    file.delete();
                }
            }
        }
    }

    private void cleanupOptionalCaches() {
        File[] files = getFilesDir().listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName();
            boolean airRoot = name.startsWith(AIR_QUALITY_CACHE_ROOT_PREFIX);
            boolean pollenRoot = name.startsWith(POLLEN_CACHE_ROOT_PREFIX);
            if (!airRoot && !pollenRoot) continue;
            String currentPrefix = airRoot
                    ? AIR_QUALITY_CACHE_FILE_PREFIX : POLLEN_CACHE_FILE_PREFIX;
            long maxAge = airRoot
                    ? AIR_QUALITY_CACHE_MAX_AGE_MILLIS : POLLEN_CACHE_MAX_AGE_MILLIS;
            boolean currentFile = name.startsWith(currentPrefix)
                    && name.endsWith(OPTIONAL_CACHE_FILE_SUFFIX);
            long modified = file.lastModified();
            if (!currentFile || modified <= 0L || now - modified < 0L
                    || now - modified >= maxAge) {
                file.delete();
            }
        }
    }

    private File optionalDataCacheFile(
            boolean airQuality, double lat, double lon, String language) {
        String identity = Double.toHexString(lat)
                + "|" + Double.toHexString(lon)
                + "|" + (language == null ? "" : language);
        String prefix = airQuality
                ? AIR_QUALITY_CACHE_FILE_PREFIX : POLLEN_CACHE_FILE_PREFIX;
        return new File(
                getFilesDir(),
                prefix + Integer.toHexString(identity.hashCode()) + OPTIONAL_CACHE_FILE_SUFFIX);
    }

    private OptionalDataState readFreshOptionalDataCache(
            boolean airQuality,
            int generation,
            double lat,
            double lon,
            String language) {
        cleanupOptionalCaches();
        File cacheFile = optionalDataCacheFile(airQuality, lat, lon, language);
        if (!cacheFile.isFile()) return null;
        long maxAge = airQuality
                ? AIR_QUALITY_CACHE_MAX_AGE_MILLIS : POLLEN_CACHE_MAX_AGE_MILLIS;
        try (BufferedReader reader = new BufferedReader(new FileReader(cacheFile))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            if (body.length() == 0) {
                cacheFile.delete();
                return null;
            }
            JSONObject root = new JSONObject(body.toString());
            if (root.optInt("schema", -1) != 1
                    || !Boolean.toString(airQuality).equals(root.optString("airQuality", ""))) {
                cacheFile.delete();
                return null;
            }
            double cachedLat = root.optDouble("latitude", Double.NaN);
            double cachedLon = root.optDouble("longitude", Double.NaN);
            String scopeLanguage = language == null ? "" : language;
            if (Double.isNaN(cachedLat)
                    || Double.isNaN(cachedLon)
                    || Double.doubleToLongBits(cachedLat) != Double.doubleToLongBits(lat)
                    || Double.doubleToLongBits(cachedLon) != Double.doubleToLongBits(lon)
                    || !scopeLanguage.equals(root.optString("language", ""))) {
                return null;
            }
            long fetchedAtMillis = root.optLong("fetchedAtMillis", 0L);
            long age = System.currentTimeMillis() - fetchedAtMillis;
            if (fetchedAtMillis <= 0L || age < 0L || age >= maxAge) {
                cacheFile.delete();
                return null;
            }
            String value = root.optString("value", "").trim();
            String accessibility = root.optString("accessibility", "").trim();
            if (value.isEmpty()) {
                cacheFile.delete();
                return null;
            }
            return new OptionalDataState(
                    generation, lat, lon, scopeLanguage,
                    false, true, value, accessibility);
        } catch (Exception ignored) {
            cacheFile.delete();
            return null;
        }
    }

    private void persistOptionalDataCacheQuietly(
            boolean airQuality,
            double lat,
            double lon,
            String language,
            OptionalDataState state) {
        if (state == null || !state.available || state.value.trim().isEmpty()) return;
        cleanupOptionalCaches();
        File cacheFile = optionalDataCacheFile(airQuality, lat, lon, language);
        File tempFile = new File(cacheFile.getParentFile(), cacheFile.getName() + ".tmp");
        try {
            JSONObject root = new JSONObject();
            root.put("schema", 1);
            root.put("airQuality", Boolean.toString(airQuality));
            root.put("latitude", lat);
            root.put("longitude", lon);
            root.put("language", language == null ? "" : language);
            root.put("fetchedAtMillis", System.currentTimeMillis());
            root.put("value", state.value);
            root.put("accessibility", state.accessibility);
            byte[] encoded = root.toString().getBytes(StandardCharsets.UTF_8);
            if (tempFile.exists() && !tempFile.delete()) return;
            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                out.write(encoded);
                out.flush();
                out.getFD().sync();
            }
            Os.rename(tempFile.getAbsolutePath(), cacheFile.getAbsolutePath());
        } catch (Exception ignored) {
            // Optional environmental caches are best-effort and never affect weather rendering.
        } finally {
            if (tempFile.exists()) tempFile.delete();
        }
    }

    private ForecastCacheSnapshot readFreshForecastCache(
            double lat,
            double lon,
            String language,
            String requestLocationId) {
        cleanupForecastCaches();
        File cacheFile = forecastCacheFile(lat, lon, language, requestLocationId);
        if (!cacheFile.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(cacheFile))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            if (body.length() == 0) return null;

            JSONObject root = new JSONObject(body.toString());
            if (root.optInt("schema", -1) != 2) {
                cacheFile.delete();
                return null;
            }
            double cachedLat = root.optDouble("latitude", Double.NaN);
            double cachedLon = root.optDouble("longitude", Double.NaN);
            if (Double.isNaN(cachedLat)
                    || Double.isNaN(cachedLon)
                    || Math.abs(cachedLat - lat) > WEATHER_CACHE_COORDINATE_TOLERANCE
                    || Math.abs(cachedLon - lon) > WEATHER_CACHE_COORDINATE_TOLERANCE) {
                return null;
            }
            if (!language.equals(root.optString("language", ""))) return null;
            if (!requestLocationId.equals(root.optString("locationId", ""))) return null;

            long updatedAt = root.optLong("updatedAtMillis", 0L);
            long age = System.currentTimeMillis() - updatedAt;
            if (updatedAt <= 0L || age < 0L || age >= WEATHER_CACHE_MAX_AGE_MILLIS) {
                cacheFile.delete();
                return null;
            }

            JSONObject current = root.optJSONObject("current");
            JSONObject hourly = root.optJSONObject("hourly");
            JSONObject daily = root.optJSONObject("daily");
            if (current == null || hourly == null || daily == null) return null;
            return new ForecastCacheSnapshot(current, hourly, daily);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void persistForecastCacheQuietly(
            double lat,
            double lon,
            String language,
            String requestLocationId,
            JSONObject current,
            JSONObject hourly,
            JSONObject daily) {
        if (current == null || hourly == null || daily == null) return;
        File cacheFile = forecastCacheFile(lat, lon, language, requestLocationId);
        File tempFile = new File(cacheFile.getParentFile(), cacheFile.getName() + ".tmp");
        try {
            JSONObject root = new JSONObject();
            root.put("schema", 2);
            root.put("latitude", lat);
            root.put("longitude", lon);
            root.put("language", language);
            root.put("locationId", requestLocationId);
            root.put("updatedAtMillis", System.currentTimeMillis());
            root.put("current", current);
            root.put("hourly", hourlyCacheCopy(hourly));
            root.put("daily", daily);
            byte[] encoded = root.toString().getBytes(StandardCharsets.UTF_8);

            if (tempFile.exists() && !tempFile.delete()) return;
            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                out.write(encoded);
                out.flush();
                out.getFD().sync();
            }
            Os.rename(tempFile.getAbsolutePath(), cacheFile.getAbsolutePath());
        } catch (Exception ignored) {
            // Cache persistence is best-effort and must never turn fresh weather into an error.
        } finally {
            if (tempFile.exists()) tempFile.delete();
        }
    }

    private JSONObject hourlyCacheCopy(JSONObject hourly) {
        JSONObject copy = new JSONObject();
        try {
            JSONObject zone = firstJSONObject(hourly, "timeZone", "time_zone", "timezone");
            if (zone != null) copy.put("timeZone", zone);
            JSONArray source = hourly == null ? null : hourly.optJSONArray("forecastHours");
            JSONArray firstPage = new JSONArray();
            if (source != null) {
                for (int i = 0; i < Math.min(HOURLY_PAGE_SIZE, source.length()); i++) {
                    JSONObject hour = source.optJSONObject(i);
                    if (hour != null) firstPage.put(hour);
                }
            }
            copy.put("forecastHours", firstPage);
            copy.put(HOURLY_PARTIAL, true);
            copy.put(HOURLY_LOAD_ERROR, hourly != null && hourly.optBoolean(HOURLY_LOAD_ERROR, false));
            String diagnostic = hourlyDiagnostic(hourly);
            if (!diagnostic.isEmpty()) copy.put(HOURLY_DIAGNOSTIC, diagnostic);
        } catch (Exception ignored) { }
        return copy;
    }

    private File forecastCacheFile(
            double lat,
            double lon,
            String language,
            String requestLocationId) {
        String identity = String.format(
                Locale.US,
                "%.5f|%.5f|%s|%s",
                lat,
                lon,
                language,
                requestLocationId);
        String name = WEATHER_CACHE_FILE_PREFIX
                + Integer.toHexString(identity.hashCode())
                + WEATHER_CACHE_FILE_SUFFIX;
        return new File(getFilesDir(), name);
    }

    private void cleanupMinuteForecastCaches() {
        File[] files = getFilesDir().listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            String name = file.getName();
            if (API_KEY_FILE.equals(name) || API_KEY_TEMP_FILE.equals(name)) continue;
            if (!name.startsWith(MINUTE_CACHE_LEGACY_PREFIX)) continue;
            if (!name.startsWith(MINUTE_CACHE_FILE_PREFIX)) {
                file.delete();
                continue;
            }
            if (name.endsWith(MINUTE_CACHE_FILE_SUFFIX + ".tmp")) {
                file.delete();
                continue;
            }
            if (!name.endsWith(MINUTE_CACHE_FILE_SUFFIX)) {
                file.delete();
                continue;
            }
            long modified = file.lastModified();
            if (modified <= 0L || now - modified >= MINUTE_CACHE_MAX_AGE_MILLIS) {
                file.delete();
            }
        }
    }

    private File minuteForecastCacheFile(double lat, double lon, String language) {
        String identity = Double.toHexString(lat)
                + "|" + Double.toHexString(lon)
                + "|" + (language == null ? "" : language);
        String name = MINUTE_CACHE_FILE_PREFIX
                + Integer.toHexString(identity.hashCode())
                + MINUTE_CACHE_FILE_SUFFIX;
        return new File(getFilesDir(), name);
    }

    private MinuteCacheSnapshot readFreshMinuteForecastCache(
            double lat,
            double lon,
            String language) {
        cleanupMinuteForecastCaches();
        File cacheFile = minuteForecastCacheFile(lat, lon, language);
        if (!cacheFile.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(cacheFile))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            if (body.length() == 0) return null;
            JSONObject root = new JSONObject(body.toString());
            if (root.optInt("schema", -1) != 1) {
                cacheFile.delete();
                return null;
            }
            double cachedLat = root.optDouble("latitude", Double.NaN);
            double cachedLon = root.optDouble("longitude", Double.NaN);
            if (Double.isNaN(cachedLat)
                    || Double.isNaN(cachedLon)
                    || Double.doubleToLongBits(cachedLat) != Double.doubleToLongBits(lat)
                    || Double.doubleToLongBits(cachedLon) != Double.doubleToLongBits(lon)
                    || !(language == null ? "" : language).equals(root.optString("language", ""))) {
                return null;
            }
            long fetchedAtMillis = root.optLong("fetchedAtMillis", 0L);
            long age = System.currentTimeMillis() - fetchedAtMillis;
            if (fetchedAtMillis <= 0L || age < 0L || age >= MINUTE_CACHE_MAX_AGE_MILLIS) {
                cacheFile.delete();
                return null;
            }
            JSONObject raw = root.optJSONObject("response");
            if (raw == null) return null;
            JSONObject filtered = filterElapsedMinuteResponse(raw, Instant.now());
            return new MinuteCacheSnapshot(fetchedAtMillis, filtered);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void persistMinuteForecastCacheQuietly(
            double lat,
            double lon,
            String language,
            long fetchedAtMillis,
            JSONObject rawResponse) {
        if (rawResponse == null || fetchedAtMillis <= 0L) return;
        cleanupMinuteForecastCaches();
        File cacheFile = minuteForecastCacheFile(lat, lon, language);
        File tempFile = new File(cacheFile.getParentFile(), cacheFile.getName() + ".tmp");
        try {
            JSONObject root = new JSONObject();
            root.put("schema", 1);
            root.put("latitude", lat);
            root.put("longitude", lon);
            root.put("language", language == null ? "" : language);
            root.put("fetchedAtMillis", fetchedAtMillis);
            root.put("response", rawResponse);
            byte[] encoded = root.toString().getBytes(StandardCharsets.UTF_8);
            if (tempFile.exists() && !tempFile.delete()) return;
            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                out.write(encoded);
                out.flush();
                out.getFD().sync();
            }
            Os.rename(tempFile.getAbsolutePath(), cacheFile.getAbsolutePath());
        } catch (Exception ignored) {
            // Minute caching is best-effort and never changes the base forecast result.
        } finally {
            if (tempFile.exists()) tempFile.delete();
        }
    }

    private static JSONObject filterElapsedMinuteResponse(JSONObject raw, Instant now) {
        if (raw == null) return null;
        try {
            JSONObject copy = new JSONObject(raw.toString());
            JSONArray input = copy.optJSONArray("segments");
            if (input == null) return copy;
            JSONArray filtered = new JSONArray();
            Instant cutoff = now == null ? Instant.now() : now;
            for (int i = 0; i < input.length(); i++) {
                JSONObject segment = input.optJSONObject(i);
                if (segment == null) continue;
                JSONObject frame = segment.optJSONObject("timeFrame");
                Instant end = parseInstant(frame == null ? "" : frame.optString("endTime", ""));
                if (end != null && end.isAfter(cutoff)) filtered.put(segment);
            }
            copy.put("segments", filtered);
            return copy;
        } catch (Exception ignored) {
            return raw;
        }
    }

    private void invalidateMinuteForecastState() {
        synchronized (minuteForecastLock) {
            minuteForecastState = null;
        }
        synchronized (optionalDataLock) {
            airQualityRequestSerial++;
            pollenRequestSerial++;
            airQualityState = null;
            pollenState = null;
        }
        minuteSelectedTimeMillis = Long.MIN_VALUE;
    }

    private void rebindFreshMinuteStateToGeneration(
            int generation,
            double lat,
            double lon,
            String language) {
        MinuteForecastState rebound = null;
        synchronized (minuteForecastLock) {
            MinuteForecastState state = minuteForecastState;
            if (state == null) return;
            if (!state.sameLocationLanguage(lat, lon, language)) {
                minuteForecastState = null;
                minuteSelectedTimeMillis = Long.MIN_VALUE;
                return;
            }
            if (state.isFresh(System.currentTimeMillis())) {
                rebound = state.rebind(generation);
                minuteForecastState = rebound;
            } else {
                minuteForecastState = null;
            }
        }
        if (rebound != null) scheduleMinuteExpiration(rebound);
    }

    private void ensureMinuteForecast(boolean forceNetwork) {
        final int generation = weatherRequestGeneration;
        final double lat = latitude;
        final double lon = longitude;
        final String language = Locale.getDefault().getLanguage();
        final MinuteForecastState requestState;
        synchronized (minuteForecastLock) {
            MinuteForecastState current = minuteForecastState;
            if (current != null && current.matches(generation, lat, lon, language)) {
                if (current.loading) return;
                if (!forceNetwork && current.isFresh(System.currentTimeMillis())) return;
                if (!forceNetwork && current.response == null && !current.errorMessage.isEmpty()) return;
            }
            requestState = new MinuteForecastState(
                    generation, lat, lon, language, true, 0L, null, "", false);
            minuteForecastState = requestState;
        }
        rerenderPrecipitationPreservingScroll();

        executor.execute(() -> {
            try {
                if (!forceNetwork) {
                    MinuteCacheSnapshot cached = readFreshMinuteForecastCache(lat, lon, language);
                    if (cached != null) {
                        MinuteForecastState ready = new MinuteForecastState(
                                generation,
                                lat,
                                lon,
                                language,
                                false,
                                cached.fetchedAtMillis,
                                cached.response,
                                "",
                                false);
                        runOnUiThread(() -> finishMinuteForecastRequest(requestState, ready));
                        return;
                    }
                }
                if (!minuteRequestScopeCurrent(requestState)) {
                    throw new SupersededWeatherRequestException();
                }
                String key = readApiKey();
                if (key.isEmpty()) throw new IllegalStateException("API key is not configured");
                String address = minuteForecastAddress(key, lat, lon);
                JSONObject raw = requestMinuteLogical(address, requestState);
                long fetchedAtMillis = System.currentTimeMillis();
                persistMinuteForecastCacheQuietly(lat, lon, language, fetchedAtMillis, raw);
                JSONObject filtered = filterElapsedMinuteResponse(raw, Instant.now());
                MinuteForecastState ready = new MinuteForecastState(
                        generation,
                        lat,
                        lon,
                        language,
                        false,
                        fetchedAtMillis,
                        filtered,
                        "",
                        false);
                runOnUiThread(() -> finishMinuteForecastRequest(requestState, ready));
            } catch (SupersededWeatherRequestException ignored) {
                // A newer location/generation owns the screen; discard this result silently.
            } catch (Exception error) {
                boolean unsupported = minuteForecastUnsupported(error);
                MinuteForecastState failed = new MinuteForecastState(
                        generation,
                        lat,
                        lon,
                        language,
                        false,
                        0L,
                        null,
                        minuteForecastErrorMessage(error, unsupported),
                        unsupported);
                runOnUiThread(() -> finishMinuteForecastRequest(requestState, failed));
            }
        });
    }

    private void finishMinuteForecastRequest(
            MinuteForecastState requestState,
            MinuteForecastState resultState) {
        synchronized (minuteForecastLock) {
            if (minuteForecastState != requestState) return;
            if (!resultState.matches(
                    weatherRequestGeneration,
                    latitude,
                    longitude,
                    Locale.getDefault().getLanguage())) {
                return;
            }
            minuteForecastState = resultState;
        }
        scheduleMinuteExpiration(resultState);
        rerenderPrecipitationPreservingScroll();
    }

    private void scheduleMinuteExpiration(MinuteForecastState state) {
        if (state == null || state.response == null || state.fetchedAtMillis <= 0L || mainScroll == null) return;
        long expiresAt = state.fetchedAtMillis + MINUTE_CACHE_MAX_AGE_MILLIS;
        long delay = Math.max(1L, expiresAt - System.currentTimeMillis() + 25L);
        mainScroll.postDelayed(() -> {
            boolean expired = false;
            synchronized (minuteForecastLock) {
                MinuteForecastState current = minuteForecastState;
                if (current != null
                        && current.generation == state.generation
                        && current.fetchedAtMillis == state.fetchedAtMillis
                        && current.response != null
                        && !current.isFresh(System.currentTimeMillis())) {
                    minuteForecastState = null;
                    expired = true;
                }
            }
            if (expired) {
                cleanupMinuteForecastCaches();
                rerenderPrecipitationPreservingScroll();
            }
        }, delay);
    }

    private boolean minuteRequestScopeCurrent(MinuteForecastState requestState) {
        synchronized (minuteForecastLock) {
            return minuteForecastState == requestState
                    && requestState != null
                    && requestState.matches(
                            weatherRequestGeneration,
                            latitude,
                            longitude,
                            Locale.getDefault().getLanguage());
        }
    }

    private String minuteForecastAddress(String key, double lat, double lon) throws Exception {
        return API_ROOT + "forecast/minutes:lookup"
                + "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8.name())
                + "&location.latitude=" + lat
                + "&location.longitude=" + lon
                + "&unitsSystem=METRIC"
                + "&pageSize=" + MINUTE_PAGE_SIZE;
    }

    private JSONObject requestMinuteLogical(
            String address,
            MinuteForecastState requestState) throws Exception {
        if (!minuteRequestScopeCurrent(requestState)) {
            throw new SupersededWeatherRequestException();
        }
        if (isDebugBuild()) {
            Log.d(LOG_TAG, "request generation=" + requestState.generation + " endpoint=minute");
        }
        try {
            JSONObject response = request(address);
            if (!minuteRequestScopeCurrent(requestState)) {
                throw new SupersededWeatherRequestException();
            }
            return response;
        } catch (WeatherRequestException error) {
            if (error.statusCode == 408 || error.statusCode >= 500) {
                long jitter = 180L + ThreadLocalRandom.current().nextInt(121);
                SystemClock.sleep(jitter);
                if (!minuteRequestScopeCurrent(requestState)) {
                    throw new SupersededWeatherRequestException();
                }
                if (isDebugBuild()) {
                    Log.d(LOG_TAG, "retry generation=" + requestState.generation + " endpoint=minute");
                }
                JSONObject response = request(address);
                if (!minuteRequestScopeCurrent(requestState)) {
                    throw new SupersededWeatherRequestException();
                }
                return response;
            }
            // Never auto-retry 429; pagination is also never followed automatically.
            throw error;
        }
    }

    private static boolean minuteForecastUnsupported(Exception error) {
        if (!(error instanceof WeatherRequestException)) return false;
        int status = ((WeatherRequestException) error).statusCode;
        return status == 400 || status == 404 || status == 422;
    }

    private static String minuteForecastErrorMessage(Exception error, boolean unsupported) {
        if (unsupported) {
            return "Minute precipitation is unavailable for this location.";
        }
        if (error instanceof WeatherRequestException) {
            int status = ((WeatherRequestException) error).statusCode;
            if (status == 401 || status == 403) {
                return "Minute precipitation is unavailable with the current Weather API access.";
            }
            if (status == 429) {
                return "Minute precipitation is temporarily busy. Try again shortly.";
            }
            if (status == 408 || status >= 500) {
                return "Minute precipitation is temporarily unavailable. Try again.";
            }
        }
        if (error instanceof IllegalStateException
                && "API key is not configured".equals(error.getMessage())) {
            return "Minute precipitation requires the configured Google API key.";
        }
        return "Minute precipitation could not be loaded. Try again.";
    }

    private MinuteForecastState minuteForecastStateForCurrentScope() {
        boolean stale = false;
        synchronized (minuteForecastLock) {
            MinuteForecastState state = minuteForecastState;
            if (state == null || !state.matches(
                    weatherRequestGeneration,
                    latitude,
                    longitude,
                    Locale.getDefault().getLanguage())) {
                return null;
            }
            if (state.response != null && !state.isFresh(System.currentTimeMillis())) {
                minuteForecastState = null;
                stale = true;
            } else {
                return state;
            }
        }
        if (stale) cleanupMinuteForecastCaches();
        return null;
    }

    private void rerenderPrecipitationPreservingScroll() {
        if (!precipitationMode || content == null) return;
        int scrollY = mainScroll == null ? 0 : mainScroll.getScrollY();
        renderPrecipitationContent();
        if (mainScroll != null) {
            mainScroll.post(() -> mainScroll.scrollTo(0, Math.max(0, scrollY)));
        }
    }

    private static final class ForecastCacheSnapshot {
        final JSONObject current;
        final JSONObject hourly;
        final JSONObject daily;

        ForecastCacheSnapshot(JSONObject current, JSONObject hourly, JSONObject daily) {
            this.current = current;
            this.hourly = hourly;
            this.daily = daily;
        }
    }

    private static final class HourlyPageLoad {
        final JSONObject aggregate;
        final String nextPageToken;
        final boolean success;

        HourlyPageLoad(JSONObject aggregate, String nextPageToken, boolean success) {
            this.aggregate = aggregate == null ? unavailableHourlyForecast("Hourly unavailable") : aggregate;
            this.nextPageToken = nextPageToken == null ? "" : nextPageToken;
            this.success = success;
        }
    }

    private static final class HourlyPageState {
        final int generation;
        final double latitude;
        final double longitude;
        final String language;
        final String locationId;
        final String unitSystem;
        final JSONObject aggregate;
        final JSONArray hours;
        final HashSet<String> identities = new HashSet<>();
        String nextPageToken;
        boolean terminal;
        boolean needsRestartFromPageOne;

        HourlyPageState(
                int generation,
                double latitude,
                double longitude,
                String language,
                String locationId,
                JSONObject aggregate,
                String nextPageToken,
                boolean terminal,
                boolean needsRestartFromPageOne) {
            this.generation = generation;
            this.latitude = latitude;
            this.longitude = longitude;
            this.language = language == null ? "" : language;
            this.locationId = locationId == null ? "" : locationId;
            this.unitSystem = "METRIC";
            this.aggregate = aggregate == null ? new JSONObject() : aggregate;
            JSONArray existing = this.aggregate.optJSONArray("forecastHours");
            if (existing == null) {
                existing = new JSONArray();
                try { this.aggregate.put("forecastHours", existing); } catch (Exception ignored) { }
            }
            this.hours = existing;
            for (int i = 0; i < hours.length(); i++) {
                String identity = hourlyIdentity(hours.optJSONObject(i));
                if (!identity.isEmpty()) identities.add(identity);
            }
            this.nextPageToken = nextPageToken == null ? "" : nextPageToken;
            this.terminal = terminal;
            this.needsRestartFromPageOne = needsRestartFromPageOne;
        }

        boolean matches(
                int generation,
                double latitude,
                double longitude,
                String language,
                String locationId,
                String unitSystem) {
            return this.generation == generation
                    && Math.abs(this.latitude - latitude) <= WEATHER_CACHE_COORDINATE_TOLERANCE
                    && Math.abs(this.longitude - longitude) <= WEATHER_CACHE_COORDINATE_TOLERANCE
                    && this.language.equals(language == null ? "" : language)
                    && this.unitSystem.equals(unitSystem == null ? "" : unitSystem)
                    && this.locationId.equals(locationId == null ? "" : locationId);
        }
    }

    private void performPullRefresh() {
        if (weatherLoadActive) {
            finishRefreshIndicator();
            if (precipitationMode) ensureMinuteForecast(true);
            return;
        }
        forecastPreview.restore(false);
        if (refreshIndicator != null) refreshIndicator.setRefreshing(true);
        loadWeather(true);
        if (precipitationMode) ensureMinuteForecast(true);
    }

    private void finishRefreshIndicator() {
        if (refreshIndicator != null) refreshIndicator.finish();
    }

    private HourlyPageLoad loadHourlyPageOneSafely(String common, int generation) {
        try {
            return requestHourlyPage(common, "", generation, "hourly-page-1");
        } catch (WeatherRequestException e) {
            return new HourlyPageLoad(
                    unavailableHourlyForecast(hourlyFailureSummary(e)), "", false);
        } catch (Exception ignored) {
            return new HourlyPageLoad(
                    unavailableHourlyForecast("Hourly request failed safely. Retry to try again."),
                    "",
                    false);
        }
    }

    private HourlyPageLoad requestHourlyPage(
            String common,
            String pageToken,
            int generation,
            String endpointKind) throws Exception {
        JSONObject page = requestLogical(
                hourlyForecastAddress(common, HOURLY_FORECAST_HOURS, HOURLY_PAGE_SIZE, pageToken),
                generation,
                endpointKind);
        return aggregateHourlyPage(page);
    }

    private HourlyPageLoad aggregateHourlyPage(JSONObject page) throws Exception {
        JSONObject aggregate = new JSONObject();
        JSONObject pageZone = firstJSONObject(page, "timeZone", "time_zone", "timezone");
        if (pageZone != null) aggregate.put("timeZone", pageZone);
        HourlyArrayResult result = hourlyArray(page);
        JSONArray output = new JSONArray();
        HashSet<String> seen = new HashSet<>();
        int invalid = 0;
        if (result.hours != null) {
            for (int i = 0; i < Math.min(HOURLY_PAGE_SIZE, result.hours.length()); i++) {
                JSONObject hour = result.hours.optJSONObject(i);
                String identity = hourlyIdentity(hour);
                if (hour == null || identity.isEmpty()) {
                    invalid++;
                    continue;
                }
                if (seen.add(identity)) output.put(hour);
            }
        }
        aggregate.put("forecastHours", output);
        String next = hourlyNextPageToken(page);
        aggregate.put(HOURLY_PARTIAL, !next.isEmpty());
        aggregate.put(HOURLY_LOAD_ERROR, result.hours == null);
        aggregate.put(HOURLY_TOP_KEYS, safeTopLevelKeys(page));
        aggregate.put(HOURLY_FIRST_PAGE_COUNT, result.hours == null ? -1 : result.hours.length());
        aggregate.put(HOURLY_NEXT_TOKEN_PRESENT, !next.isEmpty());
        if (result.hours == null) {
            aggregate.put(HOURLY_DIAGNOSTIC, boundedDiagnostic(result.reason));
        } else if (invalid > 0) {
            aggregate.put(HOURLY_DIAGNOSTIC,
                    "Ignored " + invalid + " malformed hourly record" + (invalid == 1 ? "." : "s."));
        }
        return new HourlyPageLoad(aggregate, next, result.hours != null);
    }

    private String hourlyForecastAddress(
            String common,
            int hours,
            int pageSize,
            String pageToken) throws Exception {
        StringBuilder address = new StringBuilder(API_ROOT)
                .append("forecast/hours:lookup")
                .append(common)
                .append("&hours=").append(hours)
                .append("&pageSize=").append(pageSize);
        if (pageToken != null && !pageToken.isEmpty()) {
            address.append("&pageToken=")
                    .append(URLEncoder.encode(pageToken, StandardCharsets.UTF_8.name()));
        }
        return address.toString();
    }

    private void retryHourlyPageOne() {
        final HourlyPageState state = hourlyPageState;
        if (state == null || state.generation != weatherRequestGeneration) return;
        synchronized (hourlyCoverageLock) {
            if (hourlyCoverageLoadActive) return;
            hourlyCoverageLoadActive = true;
            hourlyCoverageLoadingTarget = null;
        }
        executor.execute(() -> {
            try {
                String key = readApiKey();
                if (key.isEmpty()) throw new IllegalStateException("API key is not configured");
                String common = weatherCommonQuery(key, state.latitude, state.longitude, state.language);
                HourlyPageLoad page = requestHourlyPage(
                        common, "", state.generation, "hourly-page-1-retry");
                if (!isHourlyStateCurrent(state)) return;
                mergeHourlyPage(state, page, true);
                runOnUiThread(() -> {
                    if (!isHourlyStateCurrent(state)) return;
                    lastHourlyWeather = state.aggregate;
                    rerenderLastWeather();
                });
            } catch (Exception error) {
                markHourlyFailure(state, error);
                runOnUiThread(() -> {
                    if (isHourlyStateCurrent(state)) rerenderLastWeather();
                });
            } finally {
                synchronized (hourlyCoverageLock) {
                    hourlyCoverageLoadActive = false;
                    hourlyCoverageLoadingTarget = null;
                    // This explicit page-one retry triggers a full weather rerender. Any detail
                    // selection/coordinator queued against the pre-rerender hierarchy is stale.
                    pendingHourlyCoverageTarget = null;
                    hourlyCoverageCoordinators.clear();
                }
            }
        });
    }

    private void ensureHourlyCoverage(
            LocalDate targetDate,
            DayDetailCoordinator coordinator) {
        if (targetDate == null) return;
        HourlyPageState state = hourlyPageState;
        if (state == null || state.generation != weatherRequestGeneration) return;
        ZoneId zone = responseZone(lastCurrentWeather, lastHourlyWeather, lastDailyWeather);
        if (hourlyDateCovered(state, targetDate, zone)) {
            if (coordinator != null) coordinator.onHourlyCoverageChanged();
            return;
        }
        synchronized (hourlyCoverageLock) {
            if (coordinator != null) {
                boolean found = false;
                for (WeakReference<DayDetailCoordinator> ref : hourlyCoverageCoordinators) {
                    if (ref.get() == coordinator) { found = true; break; }
                }
                if (!found) hourlyCoverageCoordinators.add(new WeakReference<>(coordinator));
            }
            // Track the most recently selected date. The loading target is date-scoped so an
            // unrelated expanded day never inherits a global "Loading…" state.
            pendingHourlyCoverageTarget = targetDate;
            hourlyCoverageLoadingTarget = targetDate;
            if (hourlyCoverageLoadActive) return;
            hourlyCoverageLoadActive = true;
        }
        executor.execute(() -> runHourlyCoverage(state));
    }

    private void runHourlyCoverage(HourlyPageState state) {
        try {
            String key = readApiKey();
            if (key.isEmpty()) throw new IllegalStateException("API key is not configured");
            String common = weatherCommonQuery(key, state.latitude, state.longitude, state.language);
            ZoneId zone = responseZone(lastCurrentWeather, state.aggregate, lastDailyWeather);

            while (isHourlyStateCurrent(state)) {
                LocalDate target;
                synchronized (hourlyCoverageLock) {
                    target = pendingHourlyCoverageTarget;
                    if (target != null) hourlyCoverageLoadingTarget = target;
                }
                if (target == null) break;
                if (hourlyDateCovered(state, target, zone)) {
                    clearPendingHourlyTargetIf(target);
                    break;
                }

                if (state.needsRestartFromPageOne) {
                    HourlyPageLoad first = requestHourlyPage(
                            common, "", state.generation, "hourly-page-1-coverage");
                    if (!isHourlyStateCurrent(state)) return;
                    mergeHourlyPage(state, first, false);
                    state.needsRestartFromPageOne = false;
                    notifyHourlyCoverageProgress(state);
                    continue;
                }

                if (state.nextPageToken == null || state.nextPageToken.isEmpty()) {
                    state.terminal = true;
                    clearPendingHourlyTargetIf(target);
                    break;
                }

                String token = state.nextPageToken;
                try {
                    HourlyPageLoad next = requestHourlyPage(
                            common, token, state.generation, "hourly-continuation");
                    if (!isHourlyStateCurrent(state)) return;
                    mergeHourlyPage(state, next, false);
                    notifyHourlyCoverageProgress(state);
                } catch (WeatherRequestException e) {
                    if (e.statusCode == 400 || e.statusCode == 422) {
                        // Page-token lifetime is undocumented. Restart page one, then walk only as far as needed.
                        state.nextPageToken = "";
                        state.terminal = false;
                        state.needsRestartFromPageOne = true;
                        continue;
                    }
                    markHourlyFailure(state, e);
                    clearPendingHourlyTargetIf(target);
                    break;
                }
            }
        } catch (Exception error) {
            markHourlyFailure(state, error);
            synchronized (hourlyCoverageLock) {
                pendingHourlyCoverageTarget = null;
            }
        } finally {
            boolean restartForQueuedSelection = false;
            synchronized (hourlyCoverageLock) {
                hourlyCoverageLoadActive = false;
                if (pendingHourlyCoverageTarget != null && isHourlyStateCurrent(state)) {
                    // A selection can arrive after the worker decided it was done. Preserve it
                    // and hand the same generation back to the executor instead of dropping it.
                    hourlyCoverageLoadActive = true;
                    hourlyCoverageLoadingTarget = pendingHourlyCoverageTarget;
                    restartForQueuedSelection = true;
                } else {
                    hourlyCoverageLoadingTarget = null;
                    if (!isHourlyStateCurrent(state)) pendingHourlyCoverageTarget = null;
                }
            }
            notifyHourlyCoverageProgress(state);
            if (restartForQueuedSelection) {
                executor.execute(() -> runHourlyCoverage(state));
            }
        }
    }

    private void clearPendingHourlyTargetIf(LocalDate target) {
        if (target == null) return;
        synchronized (hourlyCoverageLock) {
            if (target.equals(pendingHourlyCoverageTarget)) {
                pendingHourlyCoverageTarget = null;
            }
        }
    }

    private boolean isHourlyCoverageLoadingFor(LocalDate targetDate) {
        if (targetDate == null) return false;
        synchronized (hourlyCoverageLock) {
            return hourlyCoverageLoadActive
                    && targetDate.equals(hourlyCoverageLoadingTarget);
        }
    }

    private boolean hourlyRetryPossible(HourlyPageState state) {
        if (state == null || !isHourlyStateCurrent(state)) return false;
        boolean failed = state.aggregate.optBoolean(HOURLY_LOAD_ERROR, false);
        return failed
                || state.needsRestartFromPageOne
                || (state.nextPageToken != null && !state.nextPageToken.isEmpty());
    }

    private boolean isHourlyStateCurrent(HourlyPageState state) {
        if (state == null || state != hourlyPageState) return false;
        return state.matches(
                weatherRequestGeneration,
                latitude,
                longitude,
                Locale.getDefault().getLanguage(),
                selectedLocationId == null ? "" : selectedLocationId,
                "METRIC");
    }

    private boolean hourlyDateCovered(HourlyPageState state, LocalDate target, ZoneId zone) {
        if (state == null || target == null) return false;
        boolean hasTarget = false;
        boolean hasLater = false;
        for (int i = 0; i < state.hours.length(); i++) {
            LocalDate date = hourLocalDate(state.hours.optJSONObject(i), zone);
            if (date == null) continue;
            if (target.equals(date)) hasTarget = true;
            if (date.isAfter(target)) hasLater = true;
        }
        return hasTarget && (hasLater || state.terminal);
    }

    private void mergeHourlyPage(HourlyPageState state, HourlyPageLoad page, boolean clearExisting) {
        if (state == null || page == null) return;
        if (clearExisting) {
            while (state.hours.length() > 0) state.hours.remove(state.hours.length() - 1);
            state.identities.clear();
        }
        JSONArray pageHours = page.aggregate.optJSONArray("forecastHours");
        if (pageHours != null) {
            for (int i = 0; i < pageHours.length(); i++) {
                JSONObject hour = pageHours.optJSONObject(i);
                String identity = hourlyIdentity(hour);
                if (hour == null || identity.isEmpty() || !state.identities.add(identity)) continue;
                state.hours.put(hour);
            }
        }
        if (!state.aggregate.has("timeZone")) {
            JSONObject zone = firstJSONObject(page.aggregate, "timeZone", "time_zone", "timezone");
            if (zone != null) {
                try { state.aggregate.put("timeZone", zone); } catch (Exception ignored) { }
            }
        }
        state.nextPageToken = page.nextPageToken;
        state.terminal = page.nextPageToken.isEmpty();
        state.needsRestartFromPageOne = !page.success;
        try {
            state.aggregate.put(HOURLY_PARTIAL, !state.terminal);
            state.aggregate.put(HOURLY_LOAD_ERROR, !page.success);
            if (page.success) state.aggregate.remove(HOURLY_DIAGNOSTIC);
        } catch (Exception ignored) { }
    }

    private void markHourlyFailure(HourlyPageState state, Exception error) {
        if (state == null) return;
        String diagnostic = error instanceof WeatherRequestException
                ? hourlyFailureSummary((WeatherRequestException) error)
                : "Hourly continuation failed safely. Retry to try again.";
        try {
            state.aggregate.put(HOURLY_LOAD_ERROR, true);
            state.aggregate.put(HOURLY_PARTIAL, true);
            state.aggregate.put(HOURLY_DIAGNOSTIC, boundedDiagnostic(diagnostic));
        } catch (Exception ignored) { }
    }

    private void notifyHourlyCoverageProgress(HourlyPageState state) {
        runOnUiThread(() -> {
            if (!isHourlyStateCurrent(state)) return;
            lastHourlyWeather = state.aggregate;
            for (int i = hourlyCoverageCoordinators.size() - 1; i >= 0; i--) {
                DayDetailCoordinator coordinator = hourlyCoverageCoordinators.get(i).get();
                if (coordinator == null) {
                    hourlyCoverageCoordinators.remove(i);
                    continue;
                }
                coordinator.onHourlyCoverageChanged();
            }
        });
    }

    private static final class HourlyArrayResult {
        final JSONArray hours;
        final String reason;

        HourlyArrayResult(JSONArray hours, String reason) {
            this.hours = hours;
            this.reason = reason == null ? "" : reason;
        }
    }

    private static HourlyArrayResult hourlyArray(JSONObject page) {
        if (page == null) return new HourlyArrayResult(null, "Hourly response was empty");
        String[] keys = {"forecastHours", "forecast_hours", "hourlyForecast", "hourlyForecasts", "hours"};
        boolean foundNamedField = false;
        for (String key : keys) {
            if (!page.has(key) || page.isNull(key)) continue;
            foundNamedField = true;
            JSONArray array = page.optJSONArray(key);
            if (array != null) return new HourlyArrayResult(array, "");
        }
        return new HourlyArrayResult(
                null,
                foundNamedField ? "Hourly records field was not an array" : "No forecastHours array returned");
    }

    private static String hourlyNextPageToken(JSONObject page) {
        if (page == null) return "";
        String[] keys = {"nextPageToken", "next_page_token", "nextPage_token"};
        for (String key : keys) {
            String value = page.optString(key, "").trim();
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) return value;
        }
        return "";
    }

    private static JSONObject firstJSONObject(JSONObject object, String... keys) {
        if (object == null || keys == null) return null;
        for (String key : keys) {
            JSONObject value = object.optJSONObject(key);
            if (value != null) return value;
        }
        return null;
    }

    private static String hourlyIdentity(JSONObject hour) {
        if (hour == null) return "";
        JSONObject interval = firstJSONObject(hour, "interval", "timeInterval", "time_interval");
        if (interval != null) {
            String start = firstNonEmpty(
                    interval.optString("startTime", ""),
                    interval.optString("start_time", ""));
            if (start.isEmpty()) start = interval.optString("start", "").trim();
            if (!start.isEmpty()) return "i:" + start;
        }
        JSONObject display = firstJSONObject(
                hour, "displayDateTime", "display_date_time", "displayTime", "display_time");
        if (display != null) return "d:" + display.toString();
        return "";
    }

    private static String safeTopLevelKeys(JSONObject object) {
        if (object == null) return "";
        ArrayList<String> keys = new ArrayList<>();
        java.util.Iterator<String> iterator = object.keys();
        while (iterator.hasNext() && keys.size() < 10) {
            String raw = iterator.next();
            if (raw == null) continue;
            String safe = raw.replaceAll("[^A-Za-z0-9_.-]", "");
            if (safe.length() > 32) safe = safe.substring(0, 32);
            if (!safe.isEmpty()) keys.add(safe);
        }
        java.util.Collections.sort(keys);
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            if (out.length() > 0) out.append(", ");
            out.append(key);
        }
        return out.toString();
    }

    private static String hourlyFailureSummary(WeatherRequestException error) {
        if (error == null) return "Hourly request failed";
        StringBuilder out = new StringBuilder();
        if (error.statusCode >= 400) {
            out.append("Request rejected (HTTP ").append(error.statusCode).append(')');
        } else if (error.transientFailure) {
            out.append("Hourly service temporarily unreachable");
        } else {
            out.append("Hourly response could not be used");
        }
        if (!error.serviceStatus.isEmpty()) out.append(" · ").append(error.serviceStatus);
        if (!error.serviceMessage.isEmpty()) out.append(" · ").append(error.serviceMessage);
        else if (!error.parseReason.isEmpty()) out.append(" · ").append(error.parseReason);
        return boundedDiagnostic(out.toString());
    }

    private static JSONObject unavailableHourlyForecast(String diagnostic) {
        JSONObject hourly = new JSONObject();
        try {
            hourly.put("forecastHours", new JSONArray());
            hourly.put(HOURLY_PARTIAL, true);
            hourly.put(HOURLY_LOAD_ERROR, true);
            hourly.put(HOURLY_DIAGNOSTIC, boundedDiagnostic(diagnostic));
            hourly.put(HOURLY_TOP_KEYS, "");
            hourly.put(HOURLY_FIRST_PAGE_COUNT, -1);
            hourly.put(HOURLY_NEXT_TOKEN_PRESENT, false);
        } catch (Exception ignored) { }
        return hourly;
    }

    private static String boundedDiagnostic(String value) {
        String safe = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (safe.length() > 220) safe = safe.substring(0, 217) + "…";
        return safe;
    }

    private String readApiKey() throws Exception {
        File keyFile = new File(getFilesDir(), API_KEY_FILE);
        if (!keyFile.isFile()) return "";
        try (BufferedReader reader = new BufferedReader(new FileReader(keyFile))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        }
    }


    private JSONObject request(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(18000);
        connection.setRequestProperty("Accept", "application/json");
        applyAndroidApiKeyRestrictionHeaders(connection);

        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String errorBody = readUtf8Bounded(connection.getErrorStream(), ERROR_BODY_LIMIT_BYTES);
                ServiceErrorInfo info = parseSafeServiceError(errorBody);
                String safeMessage = safeWeatherServiceMessage(code);
                if (!info.message.isEmpty()) {
                    safeMessage = info.message;
                }
                throw new WeatherRequestException(
                        code,
                        code == 408 || code == 429 || code >= 500,
                        safeWeatherServiceMessage(code),
                        info.status,
                        safeMessage,
                        "");
            }

            String body = readUtf8(connection.getInputStream());
            if (body.trim().isEmpty()) {
                throw new WeatherRequestException(
                        code,
                        false,
                        "Weather service returned an empty response",
                        "",
                        "",
                        "Empty response");
            }
            try {
                return new JSONObject(body);
            } catch (Exception ignored) {
                throw new WeatherRequestException(
                        code,
                        false,
                        "Weather service returned invalid data",
                        "",
                        "",
                        "Invalid JSON response");
            }
        } catch (SocketTimeoutException ignored) {
            throw new WeatherRequestException(
                    -1,
                    true,
                    "Weather service timed out",
                    "",
                    "Weather service timed out",
                    "Network timeout");
        } catch (IOException ignored) {
            throw new WeatherRequestException(
                    -1,
                    true,
                    "Weather service connection failed",
                    "",
                    "Weather service connection failed",
                    "Network connection failure");
        } finally {
            connection.disconnect();
        }
    }

    private OptionalDataState loadAirQualityState(
            int generation,
            double lat,
            double lon,
            String language,
            String key) throws Exception {
        JSONObject location = new JSONObject();
        location.put("latitude", lat);
        location.put("longitude", lon);
        JSONObject body = new JSONObject();
        body.put("location", location);
        body.put("universalAqi", true);
        if (language != null && !language.isEmpty()) body.put("languageCode", language);

        String address = AIR_QUALITY_ENDPOINT + "?key="
                + URLEncoder.encode(key, StandardCharsets.UTF_8.name());
        JSONObject response = requestOptionalJson("air-quality-current", address, "POST", body);
        JSONArray indexes = response.optJSONArray("indexes");
        JSONObject universal = null;
        if (indexes != null) {
            for (int i = 0; i < indexes.length(); i++) {
                JSONObject candidate = indexes.optJSONObject(i);
                if (candidate != null && "uaqi".equalsIgnoreCase(candidate.optString("code", ""))) {
                    universal = candidate;
                    break;
                }
            }
        }
        if (universal == null || !universal.has("aqi") || universal.isNull("aqi")) {
            return optionalUnavailable(generation, lat, lon, language, "Air quality has no data");
        }
        int aqi = universal.optInt("aqi", Integer.MIN_VALUE);
        if (aqi == Integer.MIN_VALUE) {
            return optionalUnavailable(generation, lat, lon, language, "Air quality has no data");
        }
        String category = stringValue(universal, "category");
        String displayAqi = stringValue(universal, "aqiDisplay");
        String value = displayAqi.isEmpty() ? Integer.toString(aqi) : displayAqi;
        if (!category.isEmpty()) value += "\n" + category;
        String accessibility = "Universal AQI " + aqi
                + (category.isEmpty() ? "" : ", " + category);
        return new OptionalDataState(
                generation, lat, lon, language,
                false, true, value, accessibility);
    }

    private OptionalDataState loadPollenState(
            int generation,
            double lat,
            double lon,
            String language,
            String key) throws Exception {
        StringBuilder address = new StringBuilder(POLLEN_ENDPOINT)
                .append("?key=")
                .append(URLEncoder.encode(key, StandardCharsets.UTF_8.name()))
                .append("&location.latitude=").append(lat)
                .append("&location.longitude=").append(lon)
                .append("&days=1&pageSize=1&plantsDescription=false");
        if (language != null && !language.isEmpty()) {
            address.append("&languageCode=")
                    .append(URLEncoder.encode(language, StandardCharsets.UTF_8.name()));
        }
        JSONObject response = requestOptionalJson(
                "pollen-forecast", address.toString(), "GET", null);
        JSONArray dailyInfo = response.optJSONArray("dailyInfo");
        JSONObject today = firstObject(dailyInfo);
        JSONArray types = today == null ? null : today.optJSONArray("pollenTypeInfo");
        if (types == null || types.length() == 0) {
            return optionalUnavailable(generation, lat, lon, language, "Pollen has no data");
        }

        int maxValue = Integer.MIN_VALUE;
        String category = "";
        ArrayList<String> dominant = new ArrayList<>();
        for (int i = 0; i < types.length(); i++) {
            JSONObject type = types.optJSONObject(i);
            JSONObject index = type == null ? null : type.optJSONObject("indexInfo");
            if (index == null || !index.has("value") || index.isNull("value")) continue;
            int value = index.optInt("value", Integer.MIN_VALUE);
            if (value == Integer.MIN_VALUE) continue;
            String typeName = firstNonEmpty(
                    stringValue(type, "displayName"),
                    stringValue(type, "code"));
            if (value > maxValue) {
                maxValue = value;
                category = stringValue(index, "category");
                dominant.clear();
                if (!typeName.isEmpty()) dominant.add(typeName);
            } else if (value == maxValue && !typeName.isEmpty()) {
                dominant.add(typeName);
                if (category.isEmpty()) category = stringValue(index, "category");
            }
        }
        if (maxValue == Integer.MIN_VALUE) {
            return optionalUnavailable(generation, lat, lon, language, "Pollen has no data");
        }

        StringBuilder dominantText = new StringBuilder();
        if (maxValue > 0) {
            for (int i = 0; i < dominant.size() && i < 3; i++) {
                if (dominantText.length() > 0) dominantText.append(" · ");
                dominantText.append(dominant.get(i));
            }
        }
        String level = category.isEmpty() ? Integer.toString(maxValue) : category + " " + maxValue;
        String value = level + (dominantText.length() == 0 ? "" : "\n" + dominantText);
        String accessibility = "Today's highest Universal Pollen Index " + maxValue
                + (category.isEmpty() ? "" : ", " + category)
                + (dominantText.length() == 0 ? "" : ". Dominant pollen types " + dominantText);
        return new OptionalDataState(
                generation, lat, lon, language,
                false, true, value, accessibility);
    }

    private JSONObject requestOptionalJson(
            String endpointName,
            String address,
            String method,
            JSONObject requestBody) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(18000);
        connection.setRequestProperty("Accept", "application/json");
        applyAndroidApiKeyRestrictionHeaders(connection);
        connection.setRequestMethod(method);
        if (requestBody != null) {
            byte[] encoded = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(encoded.length);
            try (java.io.OutputStream out = connection.getOutputStream()) {
                out.write(encoded);
            }
        }
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String errorBody = readUtf8Bounded(
                        connection.getErrorStream(), ERROR_BODY_LIMIT_BYTES);
                throw new OptionalRequestException(
                        code, endpointName, optionalFailureReason(errorBody, code));
            }
            String payload = readUtf8(connection.getInputStream());
            if (payload.trim().isEmpty()) {
                throw new OptionalRequestException(code, endpointName, "EMPTY_RESPONSE");
            }
            try {
                return new JSONObject(payload);
            } catch (Exception ignored) {
                throw new OptionalRequestException(code, endpointName, "INVALID_RESPONSE");
            }
        } catch (SocketTimeoutException ignored) {
            throw new OptionalRequestException(-1, endpointName, "TIMEOUT");
        } catch (IOException ignored) {
            throw new OptionalRequestException(-1, endpointName, "NETWORK");
        } finally {
            connection.disconnect();
        }
    }

    private void applyAndroidApiKeyRestrictionHeaders(HttpURLConnection connection) {
        if (connection == null) return;
        String certificate = signingCertificateSha1();
        if (certificate.isEmpty()) return;
        connection.setRequestProperty("X-Android-Package", getPackageName());
        connection.setRequestProperty("X-Android-Cert", certificate.replace(":", ""));
    }

    private static String optionalFailureReason(String body, int statusCode) {
        try {
            JSONObject root = new JSONObject(body == null ? "" : body);
            JSONObject error = root.optJSONObject("error");
            if (error != null) {
                JSONArray details = error.optJSONArray("details");
                if (details != null) {
                    for (int i = 0; i < details.length(); i++) {
                        JSONObject detail = details.optJSONObject(i);
                        String reason = detail == null
                                ? "" : sanitizeServiceStatus(detail.optString("reason", ""));
                        if (!reason.isEmpty()) return reason;
                    }
                }
                String status = sanitizeServiceStatus(error.optString("status", ""));
                if (!status.isEmpty()) return status;
            }
        } catch (Exception ignored) { }
        return statusCode > 0 ? "HTTP_" + statusCode : "UNKNOWN";
    }

    private static final class OptionalRequestException extends Exception {
        final int statusCode;
        final String endpointName;
        final String reason;

        OptionalRequestException(int statusCode, String endpointName, String reason) {
            super("Optional Google data request failed");
            this.statusCode = statusCode;
            this.endpointName = endpointName == null ? "optional" : endpointName;
            this.reason = reason == null || reason.trim().isEmpty() ? "UNKNOWN" : reason;
        }
    }

    private static ServiceErrorInfo parseSafeServiceError(String body) {
        if (body == null || body.trim().isEmpty()) return new ServiceErrorInfo("", "");
        try {
            JSONObject root = new JSONObject(body);
            JSONObject error = root.optJSONObject("error");
            if (error == null) return new ServiceErrorInfo("", "");
            String status = sanitizeServiceStatus(error.optString("status", ""));
            String message = sanitizeServiceMessage(error.optString("message", ""));
            return new ServiceErrorInfo(status, message);
        } catch (Exception ignored) {
            return new ServiceErrorInfo("", "");
        }
    }

    private static String sanitizeServiceStatus(String value) {
        if (value == null) return "";
        String safe = value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_-]", "");
        return safe.length() > 48 ? safe.substring(0, 48) : safe;
    }

    private static String sanitizeServiceMessage(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        String safe = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        safe = safe.replaceAll("(?i)https?://\\S+", "[redacted]");
        safe = safe.replaceAll("\\?\\S+", "[redacted]");
        safe = safe.replaceAll(
                "(?i)(api[_ -]?key|key|page[_ -]?token|token|authorization)\\s*[:=]\\s*[^ ,;]+",
                "$1=[redacted]");
        safe = safe.replaceAll(
                "(?i)(location(?:\\.latitude|\\.longitude)?|latitude|longitude|lat|lon)\\s*[:=]?\\s*[-+]?\\d{1,3}(?:\\.\\d{2,})?",
                "$1=[redacted]");
        safe = safe.replaceAll(
                "(?i)(address|formatted[_ -]?address)\\s*[:=]\\s*[^,;]+",
                "$1=[redacted]");
        safe = safe.replaceAll("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b", "[redacted]");
        safe = safe.replaceAll("[-+]?\\d{1,3}\\.\\d{4,}", "[redacted]");
        safe = safe.replaceAll("[A-Za-z0-9_\\-=]{32,}", "[redacted]");
        safe = safe.replaceAll("\\s+", " ").trim();
        if (safe.length() > 150) safe = safe.substring(0, 147).trim() + "…";
        return safe;
    }

    private static String safeWeatherServiceMessage(int statusCode) {
        if (statusCode == 401 || statusCode == 403) {
            return "Weather service authorization failed";
        }
        if (statusCode == 408) return "Weather service timed out";
        if (statusCode == 429) return "Weather service is busy. Try again shortly";
        if (statusCode >= 500) return "Weather service is temporarily unavailable";
        if (statusCode >= 400) return "Weather request was not accepted";
        return "Weather service request failed";
    }

    private static final class ServiceErrorInfo {
        final String status;
        final String message;

        ServiceErrorInfo(String status, String message) {
            this.status = status == null ? "" : status;
            this.message = message == null ? "" : message;
        }
    }

    private static final class WeatherRequestException extends Exception {
        final int statusCode;
        final boolean transientFailure;
        final String serviceStatus;
        final String serviceMessage;
        final String parseReason;

        WeatherRequestException(
                int statusCode,
                boolean transientFailure,
                String message,
                String serviceStatus,
                String serviceMessage,
                String parseReason) {
            super(message);
            this.statusCode = statusCode;
            this.transientFailure = transientFailure;
            this.serviceStatus = serviceStatus == null ? "" : serviceStatus;
            this.serviceMessage = serviceMessage == null ? "" : serviceMessage;
            this.parseReason = parseReason == null ? "" : parseReason;
        }

        boolean isParameterRejection() {
            if (statusCode != 400 && statusCode != 422) return false;
            String detail = (serviceStatus + " " + serviceMessage).toLowerCase(Locale.ROOT);
            if (detail.contains("location")
                    || detail.contains("latitude")
                    || detail.contains("longitude")
                    || detail.contains("language")
                    || detail.contains("unitssystem")
                    || detail.contains("units system")
                    || detail.contains("api key")
                    || detail.contains("credential")
                    || detail.contains("quota")) {
                return false;
            }
            return detail.trim().isEmpty()
                    || detail.contains("hour")
                    || detail.contains("page")
                    || detail.contains("invalid_argument");
        }
    }

    private static String readUtf8Bounded(InputStream stream, int limitBytes) throws IOException {
        if (stream == null || limitBytes <= 0) return "";
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int remaining = limitBytes;
            while (remaining > 0) {
                int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) break;
                out.write(buffer, 0, read);
                remaining -= read;
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String readUtf8(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void render(JSONObject current, JSONObject hourly, JSONObject daily, String responseUnit) {
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

    private void renderCurrentMode() {
        if (lastCurrentWeather == null || lastDailyWeather == null) {
            clearDynamicContent();
            return;
        }
        if (precipitationMode) {
            renderPrecipitationContent();
        } else {
            renderOverviewContent();
        }
    }

    private void renderOverviewContent() {
        clearDynamicContent();
        ZoneId zone = responseZone(lastCurrentWeather, lastHourlyWeather, lastDailyWeather);
        JSONArray days = lastDailyWeather == null
                ? null : lastDailyWeather.optJSONArray("forecastDays");
        JSONObject today = firstObject(days);
        addHero(lastCurrentWeather, today);
        addHourlyCard(lastHourlyWeather, zone);
        addMultiDayCard(days, lastHourlyWeather, zone);
        addDetailTiles(lastCurrentWeather);
        addSunCard(days, lastCurrentWeather, zone);
        addMoonCard(today, zone);
        addAttribution();
    }

    private void renderPrecipitationContent() {
        clearDynamicContent();
        forecastPreview.restore(false);

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
        content.addView(page, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addMinuteLoadingCard(LinearLayout page) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(dp(18), dp(22), dp(18), dp(22));
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.setContentDescription("Loading minute precipitation");
        body.addView(spinner, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView headline = text("Loading precipitation…", 17, true, WHITE);
        headline.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams headlineLp = new LinearLayout.LayoutParams(-1, -2);
        headlineLp.topMargin = dp(12);
        body.addView(headline, headlineLp);
        TextView detail = text(
                "Using the returned segment timing exactly as provided by Google Weather.",
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

    private void addMinuteStateCard(
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

    private void renderMinuteForecastReady(LinearLayout page, MinuteForecastState state) {
        ZoneId zone = minuteResponseZone(state.response);
        ArrayList<MinuteSegment> allSegments = minuteSegments(state.response, Instant.now(), 0);

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

        ArrayList<MinuteSegment> visible = minuteRangeHours == MINUTE_RANGE_SIX_HOURS
                ? new ArrayList<>(allSegments)
                : minuteSegments(state.response, Instant.now(), MINUTE_RANGE_TWO_HOURS);
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
        metricRow.addView(minuteMetricCell("CHANCE", selectedChance), new LinearLayout.LayoutParams(0, dp(58), 0.9f));
        metricRow.addView(minuteMetricCell("TYPE", selectedType), new LinearLayout.LayoutParams(0, dp(58), 1.35f));
        graphBody.addView(metricRow, new LinearLayout.LayoutParams(-1, dp(62)));

        MinutePrecipitationGraphView graph = new MinutePrecipitationGraphView(this, visible, zone);
        graph.setPreferredSelection(minuteSelectedTimeMillis);
        graph.setSelectionListener((segment, selectedMillis) -> {
            minuteSelectedTimeMillis = selectedMillis;
            Instant selectedInstant = Instant.ofEpochMilli(selectedMillis);
            selectedTime.setText(formatTime(selectedInstant, zone));
            selectedRate.setText(minuteRateDisplay(segment));
            selectedChance.setText(segment.probability == null ? "—" : segment.probability + "%");
            selectedType.setText(minuteTypeIntensityLabel(segment));
            String detail = minuteSelectionDetail(segment, selectedInstant, zone);
            metricRow.setContentDescription("Selected precipitation. " + detail);
        });

        LinearLayout.LayoutParams graphLp = new LinearLayout.LayoutParams(-1, dp(220));
        graphLp.topMargin = dp(3);
        graphBody.addView(graph, graphLp);
        addMinutePageCard(page, graphBody, 10);
    }

    private LinearLayout minuteMetricCell(String label, TextView value) {
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

    private void addMinuteRangeSelector(LinearLayout page) {
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

    private void setMinuteRangeHours(int hours) {
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

    private void addMinutePageCard(LinearLayout page, View child, int topMarginDp) {
        View panel = card(child, dp(24), cardColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(topMarginDp);
        page.addView(panel, lp);
    }

    private void addMinuteAttribution(LinearLayout page) {
        TextView attribution = text("Source: Includes weather data from Google", 11, false, FAINT_WHITE);
        attribution.setGravity(Gravity.CENTER);
        attribution.setPadding(dp(4), dp(24), dp(4), dp(12));
        attribution.setContentDescription("Weather data attribution: Google");
        page.addView(attribution);
    }

    private static ZoneId minuteResponseZone(JSONObject response) {
        String id = timeZoneId(response);
        if (!id.isEmpty()) {
            try {
                return ZoneId.of(id);
            } catch (Exception ignored) { }
        }
        return ZoneId.systemDefault();
    }

    private static ArrayList<MinuteSegment> minuteSegments(
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
            if (rangeEnd != null && !segment.start.isBefore(rangeEnd)) continue;
            output.add(segment);
        }
        output.sort((a, b) -> a.start.compareTo(b.start));
        return output;
    }

    private static MinuteSegment minuteSegment(JSONObject raw) {
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

    private static Double optionalJsonNumber(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) return null;
        try {
            return object.getDouble(key);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static MinuteCoverage minuteCoverage(ArrayList<MinuteSegment> segments) {
        if (segments == null || segments.isEmpty()) return null;
        Instant start = segments.get(0).start;
        Instant end = segments.get(0).end;
        for (MinuteSegment segment : segments) {
            if (segment.start.isBefore(start)) start = segment.start;
            if (segment.end.isAfter(end)) end = segment.end;
        }
        return new MinuteCoverage(start, end);
    }

    private static String minuteCoverageLabel(MinuteCoverage coverage, ZoneId zone) {
        if (coverage == null) return "No returned segment coverage";
        long minutes = Math.max(0L, Duration.between(coverage.start, coverage.end).toMinutes());
        long hours = minutes / 60L;
        long remainder = minutes % 60L;
        String span;
        if (hours > 0L && remainder > 0L) {
            span = hours + "h " + remainder + "m";
        } else if (hours > 0L) {
            span = hours + "h";
        } else {
            span = minutes + "m";
        }
        return "Returned coverage " + span + " · "
                + formatTime(coverage.start, zone) + "–" + formatTime(coverage.end, zone);
    }

    private static String minuteSelectionDetail(
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

    private static Double minuteRateMmPerHour(MinuteSegment segment) {
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

    private static String minuteRateDisplay(MinuteSegment segment) {
        Double rate = minuteRateMmPerHour(segment);
        return rate == null ? "— mm/h" : formatMinuteRate(rate) + " mm/h";
    }

    private static String formatMinuteRate(double rate) {
        double safe = Math.max(0d, rate);
        String text = String.format(Locale.US, safe < 10d ? "%.2f" : "%.1f", safe);
        while (text.contains(".") && text.endsWith("0")) text = text.substring(0, text.length() - 1);
        if (text.endsWith(".")) text = text.substring(0, text.length() - 1);
        return text;
    }

    private static String minuteTypeIntensityLabel(MinuteSegment segment) {
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

    private static String formatMinuteQuantity(double value) {
        String text = String.format(Locale.US, "%.3f", value);
        while (text.contains(".") && (text.endsWith("0") || text.endsWith("."))) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static String minuteUnitLabel(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String unit = raw.trim().toUpperCase(Locale.ROOT);
        if ("MILLIMETERS".equals(unit)) return " mm";
        if ("INCHES".equals(unit)) return " in";
        return " " + prettyEnum(raw);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;

        if (requestCode == CITY_MANAGER_REQUEST && data != null) {
            applySelectedLocationResult(data);
            return;
        }

        if (requestCode != SETTINGS_REQUEST || data == null) return;
        String action = data.getStringExtra(SettingsActivity.EXTRA_ACTION);
        boolean unitChanged = data.getBooleanExtra(SettingsActivity.EXTRA_UNIT_CHANGED, false);
        boolean displayUnitChanged = data.getBooleanExtra(
                SettingsActivity.EXTRA_DISPLAY_UNIT_CHANGED, false);
        boolean airQualityChanged = data.getBooleanExtra(
                SettingsActivity.EXTRA_AIR_QUALITY_CHANGED, false);
        boolean pollenChanged = data.getBooleanExtra(
                SettingsActivity.EXTRA_POLLEN_CHANGED, false);
        if (unitChanged || displayUnitChanged || airQualityChanged || pollenChanged) {
            suppressNextResumeWeatherLoad = true;
        }
        boolean actionReloadsBaseWeather = SettingsActivity.ACTION_API_KEY_CHANGED.equals(action)
                || SettingsActivity.ACTION_REFRESH.equals(action)
                || SettingsActivity.ACTION_DEVICE_LOCATION.equals(action)
                || SettingsActivity.ACTION_SELECTED_CITY.equals(action);
        if (airQualityChanged || pollenChanged) {
            // Clear changed optional state immediately. For actions that already reload base weather,
            // defer optional requests until the new weather generation succeeds; otherwise request
            // only the optional datasets whose switches actually changed.
            applyOptionalPreferenceChanges(
                    airQualityChanged, pollenChanged, !actionReloadsBaseWeather);
        }
        if (SettingsActivity.ACTION_API_KEY_CHANGED.equals(action)) {
            if (weatherLoadActive) {
                weatherReloadPending = true;
                weatherReloadForcePending = true;
            } else {
                loadWeather(true);
            }
        } else if (SettingsActivity.ACTION_REFRESH.equals(action)) {
            loadWeather(true);
            if (precipitationMode) ensureMinuteForecast(true);
        } else if (SettingsActivity.ACTION_PREFERENCES_CHANGED.equals(action)) {
            if (unitChanged || displayUnitChanged) rerenderLastWeather();
        } else if (SettingsActivity.ACTION_DEVICE_LOCATION.equals(action)) {
            requestDeviceLocationOrLoad(true);
        } else if (SettingsActivity.ACTION_ADVANCED_COORDINATES.equals(action)) {
            if (unitChanged || displayUnitChanged) rerenderLastWeather();
            showCoordinateDialog();
        } else if (SettingsActivity.ACTION_SELECTED_CITY.equals(action)) {
            applySelectedLocationResult(data);
        } else if (unitChanged || displayUnitChanged || airQualityChanged || pollenChanged) {
            if (unitChanged || displayUnitChanged) rerenderLastWeather();
        }
    }

    private void rerenderLastWeather() {
        if (lastCurrentWeather == null || lastDailyWeather == null) return;
        activeTemperatureUnit = temperatureUnitPreference();
        final int scrollY = mainScroll == null ? 0 : mainScroll.getScrollY();
        render(lastCurrentWeather, lastHourlyWeather, lastDailyWeather, activeTemperatureUnit);
        if (mainScroll != null) {
            mainScroll.post(() -> mainScroll.scrollTo(0, scrollY));
        }
    }

    private void applySelectedLocationResult(Intent data) {
        double lat = data.getDoubleExtra(CityManagerActivity.EXTRA_LATITUDE, Double.NaN);
        double lon = data.getDoubleExtra(CityManagerActivity.EXTRA_LONGITUDE, Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon)) return;

        invalidateMinuteForecastState();
        latitude = lat;
        longitude = lon;
        locationName = data.getStringExtra(CityManagerActivity.EXTRA_LOCATION_NAME);
        if (locationName == null || locationName.trim().isEmpty()) {
            locationName = String.format(Locale.US, "%.3f°, %.3f°", lat, lon);
        }
        selectedLocationId = data.getStringExtra(CityManagerActivity.EXTRA_LOCATION_ID);
        if (selectedLocationId == null) selectedLocationId = "";
        usingDeviceLocation = data.getBooleanExtra(CityManagerActivity.EXTRA_IS_DEVICE, false);
        locationTitle.setText(locationName);
        getPreferences(MODE_PRIVATE).edit()
                .putFloat("lat", (float) lat)
                .putFloat("lon", (float) lon)
                .putString("name", locationName)
                .apply();
        getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_EXPLICIT_NON_DEVICE_LOCATION, !usingDeviceLocation)
                .apply();
        locationSelectionGeneration++;
        loadWeather();
    }

    private void addHero(JSONObject current, JSONObject today) {
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

    private static String stringValue(JSONObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.isNull(key)) return "";
        String value = object.optString(key, "").trim();
        return "null".equalsIgnoreCase(value) ? "" : value;
    }

    private static String firstNonEmpty(String first, String second) {
        if (first != null && !first.trim().isEmpty()) return first.trim();
        return second == null ? "" : second.trim();
    }

    private void addHourlyCard(JSONObject hourly, ZoneId zone) {
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

    private static String hourlyDiagnostic(JSONObject hourly) {
        if (hourly == null) return "";
        return boundedDiagnostic(hourly.optString(HOURLY_DIAGNOSTIC, ""));
    }

    private interface DaySelectionListener {
        void onDaySelected(int dayIndex);
    }

    private void addMultiDayCard(JSONArray days, JSONObject hourly, ZoneId zone) {
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

    private TextView dailyModeButton(String label, String accessibilityLabel) {
        TextView button = text(label, 12, true, WHITE);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setMinWidth(dp(72));
        button.setMinHeight(dp(48));
        button.setContentDescription(accessibilityLabel);
        return button;
    }

    private void switchDailyMode(
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

    private void applyDailyMode(
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

    private void updateDailyModeButton(TextView button, boolean selected, String label) {
        button.setSelected(selected);
        button.setAlpha(selected ? 1f : 0.76f);
        button.setTextColor(selected ? WHITE : Color.argb(210, 255, 255, 255));
        button.setBackground(segmentedButtonBackground(selected));
        button.setContentDescription(label + (selected ? ", selected" : ", not selected"));
        if (Build.VERSION.SDK_INT >= 30) {
            button.setStateDescription(selected ? "Selected" : "Not selected");
        }
    }

    private StateListDrawable segmentedButtonBackground(boolean selected) {
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

    private GestureVerticalScrollView buildTenDayList(
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

    private final class DayDetailCoordinator {
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

        void onHourlyCoverageChanged() {
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

    private View buildDayHourlyDetail(
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

    private void addHourlyMetricLine(LinearLayout cell, String label, String value) {
        if (cell == null || value == null || "—".equals(value)) return;
        TextView metric = text(label + " " + value, 8, false, FAINT_WHITE);
        metric.setGravity(Gravity.CENTER);
        metric.setSingleLine(true);
        metric.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cell.addView(metric, new LinearLayout.LayoutParams(-1, dp(17)));
    }

    private void addDetailTiles(JSONObject current) {
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

    private OptionalDataState optionalDataStateForCurrentScope(boolean airQuality) {
        synchronized (optionalDataLock) {
            OptionalDataState state = airQuality ? airQualityState : pollenState;
            if (state == null) return null;
            String language = Locale.getDefault().toLanguageTag();
            return state.matches(weatherRequestGeneration, latitude, longitude, language) ? state : null;
        }
    }

    private View optionalDetailTile(
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

    private void showOptionalDataHelpDialog(
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

    private LinearLayout tileRow(View a, View b, View c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        addWeightedTile(row, a, 0, dp(4));
        addWeightedTile(row, b, dp(4), dp(4));
        addWeightedTile(row, c, dp(4), 0);
        return row;
    }

    private void addWeightedTile(LinearLayout row, View tile, int left, int right) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(138), 1);
        lp.leftMargin = left;
        lp.rightMargin = right;
        row.addView(tile, lp);
    }

    private View detailTile(String label, String value, String glyphName) {
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

    private void addSunCard(JSONArray days, JSONObject current, ZoneId zone) {
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

    private int findForecastDayIndex(JSONArray days, LocalDate target, ZoneId zone) {
        if (days == null || target == null) return -1;
        for (int i = 0; i < days.length(); i++) {
            if (target.equals(displayDate(days.optJSONObject(i), zone))) return i;
        }
        return -1;
    }

    private static Instant sunEvent(JSONObject day, String key) {
        if (day == null) return null;
        JSONObject sunEvents = day.optJSONObject("sunEvents");
        return sunEvents == null ? null : parseInstant(sunEvents.optString(key, null));
    }

    private static String solarDateContext(LocalDate eventDate, LocalDate currentDate, ZoneId zone) {
        if (eventDate == null || currentDate == null) return "";
        if (eventDate.equals(currentDate)) return "Today";
        if (eventDate.equals(currentDate.plusDays(1))) return "Tomorrow";
        try {
            return eventDate.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String solarStaticText(
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

    private void addMoonCard(JSONObject today, ZoneId zone) {
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

    private View moonEventRow(String label, String time) {
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

    private void addAttribution() {
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

    private boolean animationsAllowed() {
        boolean preference = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getBoolean(PREF_ANIMATIONS, true);
        if (!preference) return false;
        if (Build.VERSION.SDK_INT >= 26) return ValueAnimator.areAnimatorsEnabled();
        return true;
    }

    private void updatePreviewSubtitle() {
        if (previewSubtitle == null || locationArea == null) return;
        if (forecastPreview.isPreviewing()) {
            String text = forecastPreview.previewLabel
                    + " · " + forecastPreview.previewScene.condition
                    + " · Back to now";
            previewSubtitle.setText(text);
            previewSubtitle.setVisibility(View.VISIBLE);
            locationArea.setContentDescription(
                    locationName + ". " + text + ". Tap to restore current weather.");
        } else {
            previewSubtitle.setText("");
            previewSubtitle.setVisibility(View.GONE);
            locationArea.setContentDescription(locationName + ". Current forecast location.");
        }
    }

    private void announcePreview(String message) {
        if (message == null || message.trim().isEmpty()) return;
        View target = locationArea != null ? locationArea : skyLayout;
        if (target != null) target.announceForAccessibility(message);
    }

    private StateListDrawable previewTargetBackground(int radiusPx) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_selected},
                roundedBg(Color.argb(31, 255, 255, 255), radiusPx));
        states.addState(new int[]{android.R.attr.state_pressed},
                roundedBg(Color.argb(28, 255, 255, 255), radiusPx));
        states.addState(new int[]{android.R.attr.state_focused},
                roundedBg(Color.argb(22, 255, 255, 255), radiusPx));
        states.addState(new int[]{}, roundedBg(Color.TRANSPARENT, radiusPx));
        return states;
    }

    private void applyPreviewSelectionVisual(View view, boolean selected) {
        if (view == null) return;
        view.setSelected(selected);
        if (Build.VERSION.SDK_INT >= 30) {
            view.setStateDescription(selected ? "Previewing" : null);
        }
    }

    private void makeChildrenUnimportant(ViewGroup parent) {
        if (parent == null) return;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (child instanceof ViewGroup) makeChildrenUnimportant((ViewGroup) child);
        }
    }

    private SceneSpec sceneForForecastDay(JSONObject day, int index) {
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

    private String dayPreviewKey(int index) {
        return "day:" + index;
    }

    private String hourPreviewKey(JSONObject hour) {
        String identity = hourlyIdentity(hour);
        return identity.isEmpty() ? "hour:" + System.identityHashCode(hour) : "hour:" + identity;
    }

    private String hourPreviewLabel(JSONObject hour, ZoneId zone) {
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

    private void configureHourPreviewCell(
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

    private void applyScenePalette(String scene) {
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

    private void applyGlassPalette(GlassDrawable drawable) {
        if (drawable == null) return;
        if (drawable.isTile()) {
            drawable.setColors(glassTileTop, glassTileBottom, glassEdge);
        } else {
            drawable.setColors(glassCardTop, glassCardBottom, glassEdge);
        }
    }

    private void showError(Exception e) {
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
        retry.setOnClickListener(v -> loadWeather(true));
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(-2, dp(44));
        retryLp.topMargin = dp(14);
        box.addView(retry, retryLp);

        content.addView(card(box, dp(24), cardColor));
    }

    private void clearDynamicContent() {
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

    private View card(View child, int radiusPx, int color) {
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setBackground(newGlassDrawable(radiusPx, false));
        holder.addView(child, new LinearLayout.LayoutParams(-1, -2));
        return holder;
    }

    private GlassDrawable newGlassDrawable(int radiusPx, boolean tile) {
        GlassDrawable drawable = new GlassDrawable(
                radiusPx,
                Math.max(1f, getResources().getDisplayMetrics().density),
                tile);
        applyGlassPalette(drawable);
        glassDrawables.add(drawable);
        return drawable;
    }

    private LinearLayout.LayoutParams defaultCardParams() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private GradientDrawable roundedBg(int color, int radiusPx) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(radiusPx);
        return bg;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(WHITE);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setPadding(dp(15), 0, dp(15), 0);
        b.setBackground(roundedBg(Color.argb(36, 255, 255, 255), dp(22)));
        return b;
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String temperatureUnitPreference() {
        String value = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getString(PREF_TEMPERATURE_UNIT, TEMP_CELSIUS);
        return normalizeTemperatureUnit(value);
    }

    private static String normalizeTemperatureUnit(String value) {
        return TEMP_FAHRENHEIT.equalsIgnoreCase(value)
                ? TEMP_FAHRENHEIT
                : TEMP_CELSIUS;
    }

    private boolean isFahrenheitUnit() {
        return TEMP_FAHRENHEIT.equals(activeTemperatureUnit);
    }

    private String temperatureUnitSymbol() {
        return isFahrenheitUnit() ? "°F" : "°C";
    }

    private String temperatureUnitWord() {
        return isFahrenheitUnit() ? "Fahrenheit" : "Celsius";
    }

    private String windUnitPreference() {
        android.content.SharedPreferences prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        if (!prefs.contains(PREF_WIND_UNIT)) return isFahrenheitUnit() ? WIND_MPH : WIND_KMH;
        return normalizeWindUnit(prefs.getString(PREF_WIND_UNIT, WIND_KMH));
    }

    private String pressureUnitPreference() {
        String value = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getString(PREF_PRESSURE_UNIT, PRESSURE_HPA);
        return normalizePressureUnit(value);
    }

    private String visibilityUnitPreference() {
        android.content.SharedPreferences prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        if (!prefs.contains(PREF_VISIBILITY_UNIT)) return isFahrenheitUnit() ? VISIBILITY_MI : VISIBILITY_KM;
        return normalizeVisibilityUnit(prefs.getString(PREF_VISIBILITY_UNIT, VISIBILITY_KM));
    }

    private static String normalizeWindUnit(String value) {
        String raw = value == null ? "" : value.trim();
        if (WIND_MPH.equalsIgnoreCase(raw) || "mi/h".equalsIgnoreCase(raw)) return WIND_MPH;
        if (WIND_MS.equalsIgnoreCase(raw) || "mps".equalsIgnoreCase(raw)) return WIND_MS;
        if (WIND_KNOTS.equalsIgnoreCase(raw) || "kt".equalsIgnoreCase(raw)
                || "kts".equalsIgnoreCase(raw)) return WIND_KNOTS;
        return WIND_KMH;
    }

    private static String normalizePressureUnit(String value) {
        String raw = value == null ? "" : value.trim();
        if (PRESSURE_INHG.equalsIgnoreCase(raw) || "in hg".equalsIgnoreCase(raw)) {
            return PRESSURE_INHG;
        }
        if (PRESSURE_MMHG.equalsIgnoreCase(raw) || "mm hg".equalsIgnoreCase(raw)) {
            return PRESSURE_MMHG;
        }
        return PRESSURE_HPA;
    }

    private static String normalizeVisibilityUnit(String value) {
        String raw = value == null ? "" : value.trim();
        if (VISIBILITY_MI.equalsIgnoreCase(raw) || "mile".equalsIgnoreCase(raw)
                || "miles".equalsIgnoreCase(raw)) return VISIBILITY_MI;
        return VISIBILITY_KM;
    }

    private String formatWindSpeed(JSONObject speed) {
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

    private Double speedKilometersPerHour(JSONObject speed) {
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

    private String formatPressure(JSONObject pressure) {
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

    private static Double pressureHpa(JSONObject pressure) {
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

    private String formatVisibility(JSONObject visibility) {
        Double km = visibilityKilometers(visibility);
        if (km == null) return "—";
        String unit = visibilityUnitPreference();
        double value = VISIBILITY_MI.equals(unit) ? km / 1.609344d : km;
        return trimNumber(value) + " " + unit;
    }

    private Double visibilityKilometers(JSONObject visibility) {
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

    private static String safeUnitString(JSONObject object) {
        if (object == null) return "";
        String value = object.optString("unit", "");
        if (value == null) return "";
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static int settingsAccent(String scene) {
        if ("night".equals(scene)) return Color.rgb(154, 202, 255);
        if ("thunder".equals(scene)) return Color.rgb(176, 213, 255);
        if ("rain".equals(scene) || "fog".equals(scene)) return Color.rgb(168, 218, 244);
        if ("snow".equals(scene)) return Color.rgb(220, 242, 255);
        return Color.rgb(151, 211, 255);
    }

    private static String settingsSceneKey(JSONObject current, boolean daytime) {
        String key = conditionKey(description(current));
        if ("thunder".equals(key) || "rain".equals(key)
                || "fog".equals(key) || "snow".equals(key)) {
            return key;
        }
        return daytime ? "day" : "night";
    }

    private void persistSettingsSceneSnapshot() {
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit()
                .putString(PREF_SETTINGS_SCENE, displayedScene)
                .putBoolean(PREF_SETTINGS_DAYTIME, displayedDaytime)
                .putInt(PREF_SETTINGS_CARD_TOP, glassCardTop)
                .putInt(PREF_SETTINGS_CARD_BOTTOM, glassCardBottom)
                .putInt(PREF_SETTINGS_TILE_TOP, glassTileTop)
                .putInt(PREF_SETTINGS_TILE_BOTTOM, glassTileBottom)
                .putInt(PREF_SETTINGS_ACCENT, settingsAccent(displayedScene))
                .apply();
    }

    private Integer degreesOrNull(JSONObject value) {
        if (value == null) return null;
        Double celsius = numberValue(value, "degrees");
        if (celsius == null) celsius = numberValue(value, "value");
        if (celsius == null) return null;
        double displayed = isFahrenheitUnit() ? (celsius * 9d / 5d) + 32d : celsius;
        return (int) Math.round(displayed);
    }

    private static Double numberValue(JSONObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.isNull(key)) return null;
        try {
            double value = object.optDouble(key, Double.NaN);
            return Double.isNaN(value) || Double.isInfinite(value) ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int probability(JSONObject weather) {
        if (weather == null) return -1;
        JSONObject precipitation = weather.optJSONObject("precipitation");
        if (precipitation == null) return -1;
        JSONObject probability = precipitation.optJSONObject("probability");
        return probability == null ? -1 : safeInt(probability, "percent", -1);
    }

    private static int dayProbability(JSONObject day) {
        if (day == null) return -1;
        int daytime = probability(day.optJSONObject("daytimeForecast"));
        int nighttime = probability(day.optJSONObject("nighttimeForecast"));
        if (daytime < 0) return nighttime;
        if (nighttime < 0) return daytime;
        return Math.max(daytime, nighttime);
    }

    private static LocalDate hourLocalDate(JSONObject hour, ZoneId zone) {
        if (hour == null) return null;
        JSONObject display = firstJSONObject(
                hour, "displayDateTime", "display_date_time", "displayTime", "display_time");
        LocalDate displayDate = localDateFields(display);
        if (displayDate != null) return displayDate;
        try {
            JSONObject interval = firstJSONObject(hour, "interval", "timeInterval", "time_interval");
            String start = interval == null ? "" : firstNonEmpty(
                    interval.optString("startTime", ""), interval.optString("start_time", ""));
            if (start.isEmpty() && interval != null) start = interval.optString("start", "").trim();
            if (start.isEmpty()) return null;
            return Instant.parse(start).atZone(zone).toLocalDate();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String description(JSONObject weather) {
        if (weather == null) return "Unknown";
        JSONObject condition = weather.optJSONObject("weatherCondition");
        if (condition == null) return "Unknown";
        JSONObject description = condition.optJSONObject("description");
        String text = description == null ? "" : description.optString("text", "").trim();
        if (!text.isEmpty()) return text;
        String type = condition.optString("type", "Unknown").trim();
        return type.isEmpty() ? "Unknown" : prettyEnum(type);
    }

    private static String conditionKey(String condition) {
        String c = condition == null ? "" : condition.toLowerCase(Locale.ROOT);
        if (c.contains("thunder") || c.contains("storm")) return "thunder";
        if (c.contains("snow") || c.contains("flurr") || c.contains("sleet") || c.contains("ice")) return "snow";
        if (c.contains("rain") || c.contains("drizzle") || c.contains("shower")) return "rain";
        if (c.contains("fog") || c.contains("mist") || c.contains("haze") || c.contains("smoke")) return "fog";
        if (c.contains("cloud") || c.contains("overcast")) {
            return (c.contains("part") || c.contains("mostly") || c.contains("scattered")) ? "partly" : "cloud";
        }
        if (c.contains("clear") || c.contains("sun")) return "clear";
        return "partly";
    }

    private static String hourLabel(JSONObject hour, ZoneId zone) {
        if (hour == null) return "—";
        JSONObject displayDateTime = firstJSONObject(
                hour, "displayDateTime", "display_date_time", "displayTime", "display_time");
        if (displayDateTime != null) {
            int hours = safeInt(displayDateTime, "hours", safeInt(displayDateTime, "hour", -1));
            int minutes = safeInt(displayDateTime, "minutes", safeInt(displayDateTime, "minute", 0));
            if (hours >= 0 && hours <= 23 && minutes >= 0 && minutes <= 59) {
                return String.format(Locale.getDefault(), "%02d:%02d", hours, minutes);
            }
        }
        try {
            JSONObject interval = firstJSONObject(hour, "interval", "timeInterval", "time_interval");
            String start = interval == null ? "" : firstNonEmpty(
                    interval.optString("startTime", ""), interval.optString("start_time", ""));
            if (start.isEmpty() && interval != null) start = interval.optString("start", "").trim();
            if (start.isEmpty()) return "—";
            return DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
                    .withZone(zone).format(Instant.parse(start));
        } catch (Exception ignored) {
            return "—";
        }
    }

    private static String dayLabel(JSONObject day, ZoneId zone) {
        LocalDate date = displayDate(day, zone);
        if (date == null) return "—";
        return date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()));
    }

    private static String dayDateLabel(JSONObject day, ZoneId zone) {
        LocalDate date = displayDate(day, zone);
        if (date == null) return "";
        return date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()));
    }

    private static LocalDate displayDate(JSONObject day, ZoneId zone) {
        if (day == null) return null;
        LocalDate displayDate = localDateFields(day.optJSONObject("displayDate"));
        if (displayDate != null) return displayDate;
        try {
            JSONObject interval = day.optJSONObject("interval");
            if (interval != null) {
                String start = interval.optString("startTime", "");
                if (!start.isEmpty()) return Instant.parse(start).atZone(zone).toLocalDate();
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static LocalDate localDateFields(JSONObject value) {
        if (value == null) return null;
        int year = safeInt(value, "year", -1);
        int month = safeInt(value, "month", -1);
        int day = safeInt(value, "day", -1);
        if (year < 1 || month < 1 || day < 1) return null;
        try {
            return LocalDate.of(year, month, day);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static ZoneId responseZone(JSONObject current, JSONObject hourly, JSONObject daily) {
        String id = timeZoneId(current);
        if (id.isEmpty()) id = timeZoneId(hourly);
        if (id.isEmpty()) id = timeZoneId(daily);
        if (!id.isEmpty()) {
            try {
                return ZoneId.of(id);
            } catch (Exception ignored) { }
        }
        return ZoneId.systemDefault();
    }

    private static String timeZoneId(JSONObject object) {
        if (object == null) return "";
        JSONObject timeZone = object.optJSONObject("timeZone");
        return timeZone == null ? "" : timeZone.optString("id", "");
    }

    private static int safeInt(JSONObject object, String key, int fallback) {
        if (object == null || !object.has(key) || object.isNull(key)) return fallback;
        try {
            return object.optInt(key, fallback);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean safeBoolean(JSONObject object, String key, boolean fallback) {
        if (object == null || !object.has(key) || object.isNull(key)) return fallback;
        try {
            return object.optBoolean(key, fallback);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static JSONObject firstObject(JSONArray array) {
        if (array == null || array.length() == 0) return null;
        return array.optJSONObject(0);
    }

    private void persistWidgetSnapshot(JSONObject current, JSONObject today, JSONObject hourly, ZoneId zone) {
        Integer temperature = degreesOrNull(current == null ? null : current.optJSONObject("temperature"));
        if (temperature == null) return;

        String condition = description(current);
        boolean daytime = safeBoolean(current, "isDaytime", true);
        Instant observation = parseInstant(current == null ? null : current.optString("currentTime", null));
        if (observation == null) observation = Instant.now();
        String updated = "Updated " + formatTime(observation, zone);
        String advice = widgetAdvice(condition, updated);
        Integer high = degreesOrNull(today == null ? null : today.optJSONObject("maxTemperature"));
        Integer low = degreesOrNull(today == null ? null : today.optJSONObject("minTemperature"));

        JSONArray compactHours = new JSONArray();
        JSONArray hours = hourly == null ? null : hourly.optJSONArray("forecastHours");
        if (hours != null) {
            for (int i = 0; i < Math.min(8, hours.length()); i++) {
                JSONObject hour = hours.optJSONObject(i);
                if (hour == null) continue;
                Integer hourTemperature = degreesOrNull(hour.optJSONObject("temperature"));
                try {
                    JSONObject compact = new JSONObject();
                    compact.put("time", hourLabel(hour, zone));
                    if (hourTemperature != null) compact.put("temperature", hourTemperature);
                    compact.put("condition", description(hour));
                    compact.put("daytime", safeBoolean(hour, "isDaytime", true));
                    int precipitation = probability(hour);
                    if (precipitation >= 0) compact.put("precipitation", precipitation);
                    JSONObject compactWind = hour.optJSONObject("wind");
                    String wind = formatWindSpeed(
                            compactWind == null ? null : compactWind.optJSONObject("speed"));
                    String pressure = formatPressure(hour.optJSONObject("airPressure"));
                    String visibility = formatVisibility(hour.optJSONObject("visibility"));
                    if (!"—".equals(wind)) compact.put("wind", wind);
                    if (!"—".equals(pressure)) compact.put("pressure", pressure);
                    if (!"—".equals(visibility)) compact.put("visibility", visibility);
                    compactHours.put(compact);
                } catch (Exception ignored) { }
            }
        }

        android.content.SharedPreferences.Editor editor = getSharedPreferences(
                WeatherWidgetProvider.PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(WeatherWidgetProvider.KEY_HAS_SNAPSHOT, true)
                .putString(WeatherWidgetProvider.KEY_CITY, locationName == null ? "Zwerk Weather" : locationName)
                .putInt(WeatherWidgetProvider.KEY_TEMPERATURE, temperature)
                .putString(WeatherWidgetProvider.KEY_UNIT, temperatureUnitSymbol())
                .putString(WeatherWidgetProvider.KEY_CONDITION, condition)
                .putBoolean(WeatherWidgetProvider.KEY_DAYTIME, daytime)
                .putString(WeatherWidgetProvider.KEY_ADVICE, advice)
                .putString(WeatherWidgetProvider.KEY_HOURLY_JSON, compactHours.toString())
                .putLong(WeatherWidgetProvider.KEY_UPDATED_EPOCH_MS, System.currentTimeMillis());
        if (high == null) editor.remove(WeatherWidgetProvider.KEY_DAILY_HIGH);
        else editor.putInt(WeatherWidgetProvider.KEY_DAILY_HIGH, high);
        if (low == null) editor.remove(WeatherWidgetProvider.KEY_DAILY_LOW);
        else editor.putInt(WeatherWidgetProvider.KEY_DAILY_LOW, low);
        editor.apply();
        WeatherWidgetProvider.requestRefresh(this);
    }

    private static String widgetAdvice(String condition, String updated) {
        String key = conditionKey(condition);
        if ("thunder".equals(key)) return "Storm conditions • " + updated;
        if ("rain".equals(key)) return "Rain possible • " + updated;
        if ("snow".equals(key)) return "Snow possible • " + updated;
        if ("fog".equals(key)) return "Reduced visibility • " + updated;
        return updated;
    }

    private String dataAgeLabel(JSONObject current) {
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

    private static Instant parseInstant(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Instant firstInstant(JSONArray array) {
        if (array == null || array.length() == 0) return null;
        return parseInstant(array.optString(0, null));
    }

    private static String formatTime(Instant instant, ZoneId zone) {
        if (instant == null) return "—";
        try {
            return DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).withZone(zone).format(instant);
        } catch (Exception ignored) {
            return "—";
        }
    }

    private static String prettyPhase(String phase) {
        if (phase == null || phase.trim().isEmpty()) return "Moon";
        return prettyEnum(phase);
    }

    private static String prettyEnum(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String[] parts = raw.toLowerCase(Locale.ROOT).split("_");
        StringBuilder b = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return b.toString();
    }

    private static String trimNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.05) return String.valueOf(Math.round(value));
        return String.format(Locale.getDefault(), "%.1f", value);
    }

    private static String uvHint(int uv) {
        if (uv < 0) return "";
        if (uv <= 2) return "  Very weak";
        if (uv <= 5) return "  Moderate";
        if (uv <= 7) return "  High";
        if (uv <= 10) return "  Very high";
        return "  Extreme";
    }

    private static String shortCardinal(String cardinal, Double degrees) {
        if (cardinal != null && !cardinal.isEmpty()) {
            String[] pieces = cardinal.split("_");
            StringBuilder out = new StringBuilder();
            for (String piece : pieces) {
                switch (piece) {
                    case "NORTH": out.append('N'); break;
                    case "SOUTH": out.append('S'); break;
                    case "EAST": out.append('E'); break;
                    case "WEST": out.append('W'); break;
                    case "NORTHEAST": out.append("NE"); break;
                    case "NORTHWEST": out.append("NW"); break;
                    case "SOUTHEAST": out.append("SE"); break;
                    case "SOUTHWEST": out.append("SW"); break;
                    default:
                        if (!piece.isEmpty()) out.append(piece.charAt(0));
                }
            }
            if (out.length() > 0) return out.toString().toUpperCase(Locale.ROOT);
        }
        if (degrees == null) return "";
        String[] directions = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int index = (int) Math.round((((degrees % 360) + 360) % 360) / 45.0) % 8;
        return directions[index];
    }

    private static void appendPart(StringBuilder b, String part) {
        if (part == null || part.isEmpty()) return;
        if (b.length() > 0) b.append(" · ");
        b.append(part);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean enabled = animationsAllowed();
        if (skyLayout != null) {
            skyLayout.setAnimationRunning(enabled);
        }
        for (SunTrackView track : sunTrackViews) {
            if (track != null) track.setAnimationRunning(enabled);
        }

        if (!hasResumedOnce) {
            hasResumedOnce = true;
        } else if (suppressNextResumeWeatherLoad) {
            suppressNextResumeWeatherLoad = false;
        } else if (lastCurrentWeather != null
                && hasConfiguredApiKey()
                && apiKeySetupDialog == null
                && !weatherLoadActive) {
            loadWeather();
        }
    }

    @Override
    protected void onPause() {
        forecastPreview.restore(false);
        if (skyLayout != null) skyLayout.setAnimationRunning(false);
        for (SunTrackView track : sunTrackViews) {
            if (track != null) track.setAnimationRunning(false);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        weatherRequestGeneration++;
        hourlyPageState = null;
        synchronized (minuteForecastLock) {
            minuteForecastState = null;
        }
        synchronized (optionalDataLock) {
            airQualityRequestSerial++;
            pollenRequestSerial++;
            airQualityState = null;
            pollenState = null;
        }
        synchronized (hourlyCoverageLock) {
            hourlyCoverageLoadActive = false;
            pendingHourlyCoverageTarget = null;
            hourlyCoverageLoadingTarget = null;
            hourlyCoverageCoordinators.clear();
        }
        forecastPreview.dispose();
        if (skyLayout != null) skyLayout.release();
        if (headerGlass != null) headerGlass.release();
        glassDrawables.clear();
        sunTrackViews.clear();
        releaseTrackMarkerBitmaps();
        optionalExecutor.shutdownNow();
        executor.shutdownNow();
        super.onDestroy();
    }

    private interface MinuteSelectionListener {
        void onSelected(MinuteSegment segment, long selectedTimeMillis);
    }

    private final class MinutePrecipitationGraphView extends View {
        private final ArrayList<MinuteSegment> segments = new ArrayList<>();
        private final ZoneId zone;
        private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint areaPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint areaStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint markerGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path areaPath = new Path();
        private final Path topPath = new Path();
        private MinuteSelectionListener selectionListener;
        private int selectedIndex;
        private long selectedTimeMillis = Long.MIN_VALUE;
        private float plotLeft;
        private float plotRight;
        private float plotTop;
        private float plotBottom;

        MinutePrecipitationGraphView(Context context, List<MinuteSegment> source, ZoneId zone) {
            super(context);
            if (source != null) segments.addAll(source);
            this.zone = zone == null ? ZoneId.systemDefault() : zone;
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);
            setMinimumHeight(dp(190));
            setMinimumWidth(dp(48));

            bandPaint.setStyle(Paint.Style.FILL);
            gridPaint.setStyle(Paint.Style.STROKE);
            gridPaint.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density * 0.7f));
            areaPaint.setStyle(Paint.Style.FILL);
            areaStrokePaint.setStyle(Paint.Style.STROKE);
            areaStrokePaint.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density * 1.25f));
            areaStrokePaint.setStrokeJoin(Paint.Join.ROUND);
            areaStrokePaint.setStrokeCap(Paint.Cap.ROUND);
            markerPaint.setStyle(Paint.Style.STROKE);
            markerGlowPaint.setStyle(Paint.Style.STROKE);

            textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            textPaint.setTextSize(10f * getResources().getDisplayMetrics().scaledDensity);
            selectedTimeMillis = nearestSelectableTime(Instant.now().toEpochMilli());
            selectedIndex = segmentIndexForTime(selectedTimeMillis);
            updateAccessibility(false);
        }

        void setSelectionListener(MinuteSelectionListener listener) {
            selectionListener = listener;
            dispatchSelection(false);
        }

        void setPreferredSelection(long preferredMillis) {
            if (segments.isEmpty()) return;
            long requested = preferredMillis == Long.MIN_VALUE
                    ? Instant.now().toEpochMilli() : preferredMillis;
            selectedTimeMillis = nearestSelectableTime(requested);
            selectedIndex = segmentIndexForTime(selectedTimeMillis);
            dispatchSelection(false);
            invalidate();
        }

        private int segmentIndexForTime(long epochMillis) {
            if (segments.isEmpty()) return -1;
            for (int i = 0; i < segments.size(); i++) {
                MinuteSegment segment = segments.get(i);
                long start = segment.start.toEpochMilli();
                long end = segment.end.toEpochMilli();
                if (epochMillis >= start && epochMillis < end) return i;
            }
            return -1;
        }

        private long nearestSelectableTime(long epochMillis) {
            if (segments.isEmpty()) return Long.MIN_VALUE;
            long first = segments.get(0).start.toEpochMilli();
            long lastExclusive = segments.get(segments.size() - 1).end.toEpochMilli();
            long last = Math.max(first, lastExclusive - 1L);
            long clamped = Math.max(first, Math.min(last, epochMillis));
            long roundedSteps = Math.round((clamped - first) / (double) MINUTE_SELECTION_STEP_MILLIS);
            long rounded = first + roundedSteps * MINUTE_SELECTION_STEP_MILLIS;
            if (rounded > last) {
                rounded = first + Math.max(0L,
                        (last - first) / MINUTE_SELECTION_STEP_MILLIS) * MINUTE_SELECTION_STEP_MILLIS;
            }
            if (segmentIndexForTime(rounded) >= 0) return rounded;

            long best = first;
            long bestDistance = Long.MAX_VALUE;
            for (long candidate = first; candidate <= last; candidate += MINUTE_SELECTION_STEP_MILLIS) {
                if (segmentIndexForTime(candidate) >= 0) {
                    long distance = Math.abs(candidate - clamped);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
                if (candidate > Long.MAX_VALUE - MINUTE_SELECTION_STEP_MILLIS) break;
            }
            return best;
        }

        private long steppedSelection(int delta) {
            if (segments.isEmpty() || selectedTimeMillis == Long.MIN_VALUE || delta == 0) {
                return selectedTimeMillis;
            }
            long first = segments.get(0).start.toEpochMilli();
            long last = segments.get(segments.size() - 1).end.toEpochMilli() - 1L;
            long direction = delta < 0 ? -1L : 1L;
            long candidate = selectedTimeMillis
                    + direction * MINUTE_SELECTION_STEP_MILLIS;
            while (candidate >= first && candidate <= last) {
                if (segmentIndexForTime(candidate) >= 0) return candidate;
                candidate += direction * MINUTE_SELECTION_STEP_MILLIS;
            }
            return selectedTimeMillis;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (segments.isEmpty()) return;

            float density = getResources().getDisplayMetrics().density;
            plotLeft = dp(8);
            plotRight = Math.max(plotLeft + dp(100), getWidth() - dp(70));
            plotTop = dp(29);
            plotBottom = Math.max(plotTop + dp(90), getHeight() - dp(10));

            drawIntensityBands(canvas);
            drawTimeTicks(canvas);

            long minTime = segments.get(0).start.toEpochMilli();
            long maxTime = segments.get(segments.size() - 1).end.toEpochMilli();
            if (maxTime <= minTime) maxTime = minTime + 1L;

            areaPath.reset();
            topPath.reset();
            boolean runOpen = false;
            long previousEnd = Long.MIN_VALUE;
            float previousEndX = 0f;

            for (MinuteSegment segment : segments) {
                Double rate = minuteRateMmPerHour(segment);
                if (rate == null) {
                    if (runOpen) {
                        areaPath.lineTo(previousEndX, plotBottom);
                        areaPath.close();
                        runOpen = false;
                    }
                    previousEnd = Long.MIN_VALUE;
                    continue;
                }

                long startMillis = segment.start.toEpochMilli();
                long endMillis = segment.end.toEpochMilli();
                float x1 = xForTime(startMillis, minTime, maxTime);
                float x2 = xForTime(endMillis, minTime, maxTime);
                if (x2 - x1 < density * 0.8f) x2 = x1 + density * 0.8f;
                float y = yForRate(rate);

                boolean contiguous = runOpen
                        && previousEnd != Long.MIN_VALUE
                        && Math.abs(startMillis - previousEnd) <= 1000L;
                if (!contiguous) {
                    if (runOpen) {
                        areaPath.lineTo(previousEndX, plotBottom);
                        areaPath.close();
                    }
                    areaPath.moveTo(x1, plotBottom);
                    areaPath.lineTo(x1, y);
                    topPath.moveTo(x1, y);
                    runOpen = true;
                } else {
                    // A vertical/linear boundary transition connects only two real segment values.
                    areaPath.lineTo(x1, y);
                    topPath.lineTo(x1, y);
                }
                areaPath.lineTo(x2, y);
                topPath.lineTo(x2, y);
                previousEnd = endMillis;
                previousEndX = x2;
            }
            if (runOpen) {
                areaPath.lineTo(previousEndX, plotBottom);
                areaPath.close();
            }

            areaPaint.setShader(new LinearGradient(
                    0,
                    plotTop,
                    0,
                    plotBottom,
                    new int[]{
                            Color.argb(210, 125, 224, 255),
                            Color.argb(168, 55, 160, 246),
                            Color.argb(92, 31, 102, 211)},
                    new float[]{0f, 0.52f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawPath(areaPath, areaPaint);
            areaPaint.setShader(null);

            areaStrokePaint.setColor(Color.argb(225, 170, 235, 255));
            canvas.drawPath(topPath, areaStrokePaint);

            long nowMillis = Instant.now().toEpochMilli();
            if (nowMillis >= minTime && nowMillis <= maxTime) {
                float nowX = xForTime(nowMillis, minTime, maxTime);
                markerGlowPaint.setStrokeWidth(Math.max(density * 2.2f, dp(2)));
                markerGlowPaint.setColor(Color.argb(78, 255, 95, 50));
                canvas.drawLine(nowX, plotTop, nowX, plotBottom, markerGlowPaint);
                markerPaint.setStrokeWidth(Math.max(density, dp(1)));
                markerPaint.setColor(Color.argb(238, 255, 184, 42));
                canvas.drawLine(nowX, plotTop, nowX, plotBottom, markerPaint);

                String nowLabel = "Now";
                textPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                textPaint.setTextAlign(Paint.Align.CENTER);
                textPaint.setColor(Color.rgb(255, 205, 72));
                float labelHalfWidth = textPaint.measureText(nowLabel) * 0.5f + dp(4);
                float labelX = Math.max(plotLeft + labelHalfWidth,
                        Math.min(plotRight - labelHalfWidth, nowX));
                bandPaint.setColor(Color.argb(180, 34, 42, 55));
                canvas.drawRoundRect(
                        labelX - labelHalfWidth,
                        plotTop + dp(3),
                        labelX + labelHalfWidth,
                        plotTop + dp(18),
                        dp(7),
                        dp(7),
                        bandPaint);
                canvas.drawText(nowLabel, labelX, plotTop + dp(14), textPaint);
                textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            }

            if (selectedIndex >= 0 && selectedIndex < segments.size()) {
                MinuteSegment selected = segments.get(selectedIndex);
                float selectedX = xForTime(selectedTimeMillis, minTime, maxTime);
                markerGlowPaint.setStrokeWidth(dp(4));
                markerGlowPaint.setColor(Color.argb(86, 117, 228, 255));
                canvas.drawLine(selectedX, plotTop - dp(2), selectedX, plotBottom + dp(2), markerGlowPaint);
                markerPaint.setStrokeWidth(Math.max(density, dp(1.4f)));
                markerPaint.setColor(Color.argb(248, 246, 253, 255));
                canvas.drawLine(selectedX, plotTop - dp(2), selectedX, plotBottom + dp(2), markerPaint);

                Double selectedRate = minuteRateMmPerHour(selected);
                if (selectedRate != null) {
                    float cy = yForRate(selectedRate);
                    markerPaint.setStyle(Paint.Style.FILL);
                    markerPaint.setColor(Color.argb(250, 236, 251, 255));
                    canvas.drawCircle(selectedX, cy, dp(2.5f), markerPaint);
                    markerPaint.setStyle(Paint.Style.STROKE);
                }
            }
        }

        private void drawIntensityBands(Canvas canvas) {
            float lightTop = yForRate(PRECIP_LIGHT_MAX_MM_H);
            float moderateTop = yForRate(PRECIP_MODERATE_MAX_MM_H);

            bandPaint.setColor(Color.argb(12, 142, 226, 255));
            canvas.drawRect(plotLeft, lightTop, plotRight, plotBottom, bandPaint);
            bandPaint.setColor(Color.argb(17, 104, 198, 255));
            canvas.drawRect(plotLeft, moderateTop, plotRight, lightTop, bandPaint);
            bandPaint.setColor(Color.argb(22, 74, 152, 235));
            canvas.drawRect(plotLeft, plotTop, plotRight, moderateTop, bandPaint);

            gridPaint.setColor(Color.argb(62, 245, 250, 255));
            canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, gridPaint);
            canvas.drawLine(plotLeft, lightTop, plotRight, lightTop, gridPaint);
            canvas.drawLine(plotLeft, moderateTop, plotRight, moderateTop, gridPaint);

            textPaint.setColor(Color.argb(188, 255, 255, 255));
            textPaint.setTextAlign(Paint.Align.LEFT);
            float labelX = plotRight + dp(7);
            canvas.drawText("Light", labelX, (lightTop + plotBottom) * 0.5f + dp(3), textPaint);
            canvas.drawText("Moderate", labelX, (moderateTop + lightTop) * 0.5f + dp(3), textPaint);
            canvas.drawText("Heavy", labelX, (plotTop + moderateTop) * 0.5f + dp(3), textPaint);
        }

        private void drawTimeTicks(Canvas canvas) {
            long minTime = segments.get(0).start.toEpochMilli();
            long maxTime = segments.get(segments.size() - 1).end.toEpochMilli();
            if (maxTime <= minTime) maxTime = minTime + 1L;

            textPaint.setColor(Color.argb(190, 255, 255, 255));
            for (int i = 0; i < 4; i++) {
                float fraction = i / 3f;
                long time = minTime + Math.round((maxTime - minTime) * fraction);
                float x = plotLeft + (plotRight - plotLeft) * fraction;
                if (i == 0) textPaint.setTextAlign(Paint.Align.LEFT);
                else if (i == 3) textPaint.setTextAlign(Paint.Align.RIGHT);
                else textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(formatTime(Instant.ofEpochMilli(time), zone), x, dp(13), textPaint);

                gridPaint.setColor(Color.argb(50, 255, 255, 255));
                canvas.drawLine(x, plotTop - dp(4), x, plotTop, gridPaint);
            }
        }

        private float yForRate(double rateMmPerHour) {
            double capped = Math.max(0d, Math.min(PRECIP_VISUAL_CAP_MM_H, rateMmPerHour));
            double fraction = PRECIP_VISUAL_CAP_MM_H <= 0d
                    ? 0d : capped / PRECIP_VISUAL_CAP_MM_H;
            return plotBottom - (float) fraction * (plotBottom - plotTop);
        }

        private float xForTime(long value, long minTime, long maxTime) {
            if (maxTime <= minTime) return plotLeft;
            double fraction = (value - minTime) / (double) (maxTime - minTime);
            fraction = Math.max(0d, Math.min(1d, fraction));
            return plotLeft + (float) fraction * (plotRight - plotLeft);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (segments.isEmpty()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    requestFocus();
                    selectForX(event.getX(), false);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    selectForX(event.getX(), false);
                    return true;
                case MotionEvent.ACTION_UP:
                    selectForX(event.getX(), false);
                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private void selectForX(float x, boolean announce) {
            if (segments.isEmpty() || plotRight <= plotLeft) return;
            long minTime = segments.get(0).start.toEpochMilli();
            long maxTime = segments.get(segments.size() - 1).end.toEpochMilli();
            float fraction = Math.max(0f, Math.min(1f, (x - plotLeft) / (plotRight - plotLeft)));
            long target = minTime + Math.round((maxTime - minTime) * fraction);
            long nextTime = nearestSelectableTime(target);
            int nextIndex = segmentIndexForTime(nextTime);
            if (nextTime != selectedTimeMillis || nextIndex != selectedIndex) {
                selectedTimeMillis = nextTime;
                selectedIndex = nextIndex;
                dispatchSelection(announce);
                invalidate();
            }
        }

        private boolean moveSelection(int delta, boolean announce) {
            if (segments.isEmpty()) return false;
            long nextTime = steppedSelection(delta);
            if (nextTime == selectedTimeMillis) return false;
            selectedTimeMillis = nextTime;
            selectedIndex = segmentIndexForTime(selectedTimeMillis);
            dispatchSelection(announce);
            invalidate();
            return true;
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                moveSelection(-1, true);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                moveSelection(1, true);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_SPACE) {
                return performClick();
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            if (!segments.isEmpty()) dispatchSelection(true);
            return true;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.SeekBar");
            boolean canMoveBackward = steppedSelection(-1) != selectedTimeMillis;
            boolean canMoveForward = steppedSelection(1) != selectedTimeMillis;
            info.setScrollable(canMoveBackward || canMoveForward);
            if (canMoveBackward) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            }
            if (canMoveForward) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            }
        }

        @Override
        public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                return moveSelection(-1, true);
            }
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                return moveSelection(1, true);
            }
            return super.performAccessibilityAction(action, arguments);
        }

        private void dispatchSelection(boolean announce) {
            if (segments.isEmpty() || selectedIndex < 0) return;
            MinuteSegment segment = segments.get(Math.max(0, Math.min(selectedIndex, segments.size() - 1)));
            if (selectionListener != null) selectionListener.onSelected(segment, selectedTimeMillis);
            updateAccessibility(announce);
        }

        private void updateAccessibility(boolean announce) {
            if (segments.isEmpty()) {
                setContentDescription("Minute precipitation graph, no returned segments");
                return;
            }
            MinuteSegment segment = segments.get(Math.max(0, Math.min(selectedIndex, segments.size() - 1)));
            Instant selectedInstant = Instant.ofEpochMilli(selectedTimeMillis);
            String detail = minuteSelectionDetail(segment, selectedInstant, zone);
            String description = "Minute precipitation graph. " + detail
                    + ". Swipe or use left and right to move in two-minute steps.";
            setContentDescription(description);
            if (Build.VERSION.SDK_INT >= 30) {
                setStateDescription("Selected " + formatTime(selectedInstant, zone));
            }
            if (announce && isShown()) announceForAccessibility(detail);
        }
    }

    private static final class GlassDrawable extends Drawable {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float radius;
        private final float strokeWidth;
        private final boolean tile;
        private int topColor;
        private int bottomColor;
        private int edgeColor;
        private int alpha = 255;

        GlassDrawable(float radius, float strokeWidth, boolean tile) {
            this.radius = radius;
            this.strokeWidth = strokeWidth;
            this.tile = tile;
            fillPaint.setStyle(Paint.Style.FILL);
            edgePaint.setStyle(Paint.Style.STROKE);
            edgePaint.setStrokeWidth(strokeWidth);
        }

        boolean isTile() {
            return tile;
        }

        void setColors(int topColor, int bottomColor, int edgeColor) {
            if (this.topColor == topColor
                    && this.bottomColor == bottomColor
                    && this.edgeColor == edgeColor) {
                return;
            }
            this.topColor = topColor;
            this.bottomColor = bottomColor;
            this.edgeColor = edgeColor;
            rebuildShader();
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            float inset = strokeWidth * 0.5f;
            rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset);
            rebuildShader();
        }

        private void rebuildShader() {
            Rect bounds = getBounds();
            if (bounds.width() <= 0 || bounds.height() <= 0) return;
            fillPaint.setShader(new LinearGradient(
                    bounds.left,
                    bounds.top,
                    bounds.left,
                    bounds.bottom,
                    new int[]{multiplyAlpha(topColor, alpha), multiplyAlpha(bottomColor, alpha)},
                    null,
                    Shader.TileMode.CLAMP));
            edgePaint.setShader(null);
            edgePaint.setColor(multiplyAlpha(edgeColor, alpha));
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawRoundRect(rect, radius, radius, fillPaint);
            if (Color.alpha(edgePaint.getColor()) > 0) {
                canvas.drawRoundRect(rect, radius, radius, edgePaint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            int next = Math.max(0, Math.min(255, alpha));
            if (this.alpha != next) {
                this.alpha = next;
                rebuildShader();
                invalidateSelf();
            }
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            fillPaint.setColorFilter(colorFilter);
            edgePaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        private static int multiplyAlpha(int color, int drawableAlpha) {
            int base = Color.alpha(color);
            int out = (base * drawableAlpha + 127) / 255;
            return Color.argb(out, Color.red(color), Color.green(color), Color.blue(color));
        }
    }

    private final class HeaderGlassView extends View {
        private final View source;
        private final View.OnLayoutChangeListener sourceLayoutListener;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint blurMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private Shader topHighlightShader;
        private Shader sideSheenShader;
        private Shader depthShader;
        private Shader blurMaskShader;
        private float scrollDepth;
        private int fromTint = Color.rgb(20, 85, 164);
        private int toTint = fromTint;
        private long tintStarted;
        private boolean tintAnimating;
        private boolean blurPermanentlyDisabled;
        private boolean oemBackdropBlurActive;
        private boolean blurDirty = true;
        private Object blurNode;
        private Object blurEffect;
        private Object blurCoverEffect;

        HeaderGlassView(Context context, View source) {
            super(context);
            this.source = source;
            this.sourceLayoutListener = (v, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> requestBlurRefresh();
            if (source != null) source.addOnLayoutChangeListener(sourceLayoutListener);
            blurPermanentlyDisabled = Build.VERSION.SDK_INT < 31;
            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setScene(SceneSpec scene, boolean animate) {
            int target = headerTint(scene == null ? "day" : scene.headerScene());
            int visual = currentTint(SystemClock.uptimeMillis());
            if (visual == target && !tintAnimating) return;
            fromTint = visual;
            toTint = target;
            tintStarted = SystemClock.uptimeMillis();
            tintAnimating = animate && animationsAllowed();
            if (!tintAnimating) fromTint = toTint;
            blurCoverEffect = null;
            invalidate();
        }

        void setScrollDepth(float depth) {
            float next = Math.max(0f, Math.min(1f, depth));
            if (Math.abs(next - scrollDepth) < 0.004f) return;
            scrollDepth = next;
            invalidate();
        }

        void requestBlurRefresh() {
            blurDirty = true;
            invalidate();
        }

        void release() {
            if (source != null) source.removeOnLayoutChangeListener(sourceLayoutListener);
            if (oemBackdropBlurActive) Api31OplusHeaderBlur.clear(this);
            oemBackdropBlurActive = false;
            blurPermanentlyDisabled = true;
            blurNode = null;
            blurEffect = null;
            blurCoverEffect = null;
            topHighlightShader = null;
            sideSheenShader = null;
            depthShader = null;
            blurMaskShader = null;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (Build.VERSION.SDK_INT >= 31 && !oemBackdropBlurActive) {
                oemBackdropBlurActive = Api31OplusHeaderBlur.apply(this);
                if (oemBackdropBlurActive) Log.d(LOG_TAG, "header blur=oem-gradient");
            }
        }

        private int headerTint(String scene) {
            if ("night".equals(scene)) return Color.rgb(8, 28, 66);
            if ("rain".equals(scene)) return Color.rgb(31, 72, 101);
            if ("snow".equals(scene)) return Color.rgb(86, 126, 162);
            if ("fog".equals(scene)) return Color.rgb(91, 121, 140);
            if ("thunder".equals(scene)) return Color.rgb(28, 39, 78);
            return Color.rgb(18, 94, 183);
        }

        private int currentTint(long now) {
            if (!tintAnimating) return toTint;
            if (!animationsAllowed()) {
                tintAnimating = false;
                fromTint = toTint;
                return toTint;
            }
            float linear = Math.max(0f, Math.min(1f,
                    (now - tintStarted) / (float) SCENE_TRANSITION_MILLIS));
            float t = linear * linear * (3f - 2f * linear);
            if (linear >= 1f) {
                tintAnimating = false;
                fromTint = toTint;
                return toTint;
            }
            return blendColor(fromTint, toTint, t);
        }

        private int blendColor(int a, int b, float t) {
            float clamped = Math.max(0f, Math.min(1f, t));
            return Color.rgb(
                    Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * clamped),
                    Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * clamped),
                    Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * clamped));
        }

        private int mixWith(int color, int other, float amount) {
            return blendColor(color, other, amount);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            blurDirty = true;
            if (w <= 0 || h <= 0) {
                topHighlightShader = null;
                sideSheenShader = null;
                depthShader = null;
                blurMaskShader = null;
                return;
            }
            topHighlightShader = new LinearGradient(
                    0, 0, w, 0,
                    new int[]{
                            Color.argb(40, 255, 255, 255),
                            Color.argb(13, 255, 255, 255),
                            Color.argb(30, 255, 255, 255),
                            Color.argb(5, 255, 255, 255)},
                    new float[]{0f, 0.35f, 0.73f, 1f},
                    Shader.TileMode.CLAMP);
            sideSheenShader = new RadialGradient(
                    0, h * 0.28f, Math.max(dp(44), w * 0.54f),
                    new int[]{Color.argb(30, 255, 255, 255), Color.TRANSPARENT},
                    new float[]{0f, 1f},
                    Shader.TileMode.CLAMP);
            depthShader = new LinearGradient(
                    0, 0, 0, h,
                    new int[]{Color.TRANSPARENT, Color.argb(7, 0, 0, 0), Color.argb(30, 0, 0, 0)},
                    new float[]{0f, 0.58f, 1f},
                    Shader.TileMode.CLAMP);
            blurMaskShader = new LinearGradient(
                    0, 0, 0, h,
                    new int[]{Color.WHITE, Color.argb(246, 255, 255, 255),
                            Color.argb(178, 255, 255, 255), Color.TRANSPARENT},
                    new float[]{0f, 0.46f, 0.82f, 1f},
                    Shader.TileMode.CLAMP);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int tint = currentTint(SystemClock.uptimeMillis());
            if (!oemBackdropBlurActive
                    && !blurPermanentlyDisabled
                    && Build.VERSION.SDK_INT >= 31) {
                if (!canvas.isHardwareAccelerated()) {
                    blurPermanentlyDisabled = true;
                    blurNode = null;
                    blurEffect = null;
                } else if (source != null && getWidth() > 0 && getHeight() > 0) {
                    try {
                        Api31HeaderBlur.draw(
                                this, canvas, source, blurDirty, scrollDepth);
                        blurDirty = false;
                    } catch (Throwable ignored) {
                        blurPermanentlyDisabled = true;
                        blurNode = null;
                        blurEffect = null;
                    }
                }
            }

            int tintAlpha = 14;
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(
                    Math.min(248, tintAlpha),
                    Color.red(tint), Color.green(tint), Color.blue(tint)));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

            int frost = mixWith(tint, Color.WHITE, 0.54f);
            int frostAlpha = 5;
            paint.setColor(Color.argb(
                    Math.min(72, frostAlpha),
                    Color.red(frost), Color.green(frost), Color.blue(frost)));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

            if (topHighlightShader != null) {
                paint.setShader(topHighlightShader);
                canvas.drawRect(0, 0, getWidth(), Math.max(dp(18), getHeight() * 0.40f), paint);
            }
            if (sideSheenShader != null) {
                paint.setShader(sideSheenShader);
                canvas.drawRect(0, 0, getWidth() * 0.62f, getHeight(), paint);
            }
            if (depthShader != null) {
                paint.setShader(depthShader);
                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            }
            paint.setShader(null);

            float density = getResources().getDisplayMetrics().density;
            float bottom = getHeight() - Math.max(1f, density * 0.5f);
            edgePaint.setStyle(Paint.Style.STROKE);
            edgePaint.setStrokeWidth(Math.max(1f, density * 0.55f));
            edgePaint.setColor(Color.argb(12, 248, 252, 255));
            canvas.drawLine(0, bottom, getWidth(), bottom, edgePaint);

            if (tintAnimating) postInvalidateOnAnimation();
        }
    }

    private static final class Api31OplusHeaderBlur {
        private static java.lang.reflect.Method setBackgroundEffect;

        private Api31OplusHeaderBlur() { }

        static boolean apply(View target) {
            if (Build.VERSION.SDK_INT < 31 || target == null) return false;
            try {
                Class<?> effectFactory = Class.forName("com.oplus.graphics.OplusRenderEffect");
                java.lang.reflect.Method create = effectFactory.getMethod(
                        "createGradientBlurEffect",
                        float.class, float.class, boolean.class, float.class,
                        int.class, int.class, int.class);
                Object effect = create.invoke(null, 60f, 0f, true, 3f, 1, 0, 0);
                if (effect == null) return false;

                Class<?> backgroundRenderer = Class.forName(
                        "com.oplus.view.OplusViewBackgroundRenderEffect");
                for (java.lang.reflect.Method method : backgroundRenderer.getMethods()) {
                    if ("setBackgroundRenderEffect".equals(method.getName())
                            && method.getParameterTypes().length == 2) {
                        setBackgroundEffect = method;
                        method.invoke(null, effect, target);
                        return true;
                    }
                }
                Log.d(LOG_TAG, "header blur=fallback (method unavailable)");
            } catch (Throwable ignored) {
                Log.d(LOG_TAG, "header blur=fallback ("
                        + ignored.getClass().getSimpleName() + ")");
                setBackgroundEffect = null;
            }
            return false;
        }

        static void clear(View target) {
            if (target == null || setBackgroundEffect == null) return;
            try {
                setBackgroundEffect.invoke(null, null, target);
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class Api31HeaderBlur {
        private Api31HeaderBlur() { }

        static boolean draw(
                HeaderGlassView owner,
                Canvas canvas,
                View source,
                boolean refresh,
                float scrollDepth) {
            if (Build.VERSION.SDK_INT < 31) return false;
            float strength = Math.max(0f, Math.min(1f, scrollDepth));
            strength = strength * strength * (3f - 2f * strength);
            if (strength <= 0.002f || owner.blurMaskShader == null) return false;
            android.graphics.RenderNode node = owner.blurNode instanceof android.graphics.RenderNode
                    ? (android.graphics.RenderNode) owner.blurNode
                    : null;
            if (node == null) {
                node = new android.graphics.RenderNode("ZwerkWeatherHeaderStrip");
                owner.blurNode = node;
                refresh = true;
            }

            android.graphics.RenderEffect effect =
                    owner.blurEffect instanceof android.graphics.RenderEffect
                            ? (android.graphics.RenderEffect) owner.blurEffect
                            : null;
            if (effect == null) {
                float radius = 60f;
                android.graphics.RenderEffect blur = android.graphics.RenderEffect.createBlurEffect(
                        radius, radius, Shader.TileMode.CLAMP);
                android.graphics.ColorMatrix matrix = new android.graphics.ColorMatrix();
                matrix.setSaturation(1.14f);
                float[] values = matrix.getArray();
                values[4] += 6f;
                values[9] += 6f;
                values[14] += 6f;
                android.graphics.ColorMatrixColorFilter filter =
                        new android.graphics.ColorMatrixColorFilter(matrix);
                effect = android.graphics.RenderEffect.createColorFilterEffect(filter, blur);
                owner.blurEffect = effect;
            }

            android.graphics.RenderEffect coverEffect =
                    owner.blurCoverEffect instanceof android.graphics.RenderEffect
                            ? (android.graphics.RenderEffect) owner.blurCoverEffect
                            : null;
            if (coverEffect == null) {
                int cover = owner.currentTint(SystemClock.uptimeMillis());
                android.graphics.ColorMatrix coverMatrix = new android.graphics.ColorMatrix(
                        new float[]{
                                0f, 0f, 0f, 0f, Color.red(cover),
                                0f, 0f, 0f, 0f, Color.green(cover),
                                0f, 0f, 0f, 0f, Color.blue(cover),
                                0f, 0f, 0f, 1f, 0f});
                coverEffect = android.graphics.RenderEffect.createColorFilterEffect(
                        new android.graphics.ColorMatrixColorFilter(coverMatrix));
                owner.blurCoverEffect = coverEffect;
            }

            int width = owner.getWidth();
            int height = owner.getHeight();
            if (width <= 0 || height <= 0) return false;
            if (refresh || node.getWidth() != width || node.getHeight() != height) {
                node.setPosition(0, 0, width, height);
                android.graphics.RecordingCanvas recording = node.beginRecording(width, height);
                int save = recording.save();
                recording.clipRect(0, 0, width, height);
                source.draw(recording);
                recording.restoreToCount(save);
                node.endRecording();
            }
            int layer = canvas.saveLayer(0, 0, width, height, null);
            node.setRenderEffect(coverEffect);
            canvas.drawRenderNode(node);
            node.setRenderEffect(effect);
            canvas.drawRenderNode(node);

            float density = owner.getResources().getDisplayMetrics().density;
            float shift = Math.max(0.65f, density * 0.85f);
            int save = canvas.save();
            canvas.clipRect(0, height * 0.12f, width, height * 0.28f);
            canvas.translate(shift, 0f);
            canvas.drawRenderNode(node);
            canvas.restoreToCount(save);

            save = canvas.save();
            canvas.clipRect(0, height * 0.63f, width, height * 0.78f);
            canvas.translate(-shift * 0.70f, 0f);
            canvas.drawRenderNode(node);
            canvas.restoreToCount(save);

            owner.blurMaskPaint.setShader(owner.blurMaskShader);
            owner.blurMaskPaint.setAlpha(Math.round(255f * strength));
            owner.blurMaskPaint.setBlendMode(android.graphics.BlendMode.DST_IN);
            canvas.drawRect(0, 0, width, height, owner.blurMaskPaint);
            owner.blurMaskPaint.setBlendMode(null);
            owner.blurMaskPaint.setShader(null);
            owner.blurMaskPaint.setAlpha(255);
            canvas.restoreToCount(layer);
            return true;
        }
    }

    private static final class HeaderScrimDrawable extends Drawable {
        private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint depthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float scrollDepth;
        private int red = 8;
        private int green = 55;
        private int blue = 120;
        private int alpha = 255;

        HeaderScrimDrawable() {
            basePaint.setStyle(Paint.Style.FILL);
            depthPaint.setStyle(Paint.Style.FILL);
        }

        void setScene(String scene) {
            int nextRed;
            int nextGreen;
            int nextBlue;
            if ("night".equals(scene)) {
                nextRed = 3;
                nextGreen = 13;
                nextBlue = 31;
            } else if ("rain".equals(scene)) {
                nextRed = 26;
                nextGreen = 42;
                nextBlue = 55;
            } else if ("snow".equals(scene)) {
                nextRed = 58;
                nextGreen = 84;
                nextBlue = 108;
            } else {
                nextRed = 8;
                nextGreen = 55;
                nextBlue = 120;
            }
            if (red == nextRed && green == nextGreen && blue == nextBlue) return;
            red = nextRed;
            green = nextGreen;
            blue = nextBlue;
            rebuildShaders();
            invalidateSelf();
        }

        void setScrollDepth(float depth) {
            float next = Math.max(0f, Math.min(1f, depth));
            if (Math.abs(next - scrollDepth) < 0.004f) return;
            scrollDepth = next;
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            rebuildShaders();
        }

        private void rebuildShaders() {
            Rect bounds = getBounds();
            if (bounds.width() <= 0 || bounds.height() <= 0) return;
            float top = bounds.top;
            float bottom = bounds.bottom;
            basePaint.setShader(new LinearGradient(
                    0,
                    top,
                    0,
                    bottom,
                    new int[]{
                            Color.argb(52, red, green, blue),
                            Color.argb(22, red, green, blue),
                            Color.argb(0, red, green, blue)
                    },
                    new float[]{0f, 0.62f, 1f},
                    Shader.TileMode.CLAMP));
            depthPaint.setShader(new LinearGradient(
                    0,
                    top,
                    0,
                    bottom,
                    new int[]{
                            Color.argb(128, red, green, blue),
                            Color.argb(82, red, green, blue),
                            Color.argb(0, red, green, blue)
                    },
                    new float[]{0f, 0.62f, 1f},
                    Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            basePaint.setAlpha(alpha);
            depthPaint.setAlpha(Math.round(alpha * scrollDepth));
            canvas.drawRect(bounds, basePaint);
            if (scrollDepth > 0f) {
                canvas.drawRect(bounds, depthPaint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            int next = Math.max(0, Math.min(255, alpha));
            if (this.alpha != next) {
                this.alpha = next;
                invalidateSelf();
            }
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            basePaint.setColorFilter(colorFilter);
            depthPaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }



    private final class StatusGlyphView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        StatusGlyphView(Context context) {
            super(context);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            p.setColor(Color.argb(196, 255, 255, 255));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.35f));

            RectF cloud = new RectF(w * 0.14f, h * 0.38f, w * 0.86f, h * 0.76f);
            canvas.drawArc(cloud, 5f, 170f, false, p);
            canvas.drawArc(new RectF(w * 0.30f, h * 0.18f, w * 0.66f, h * 0.58f), 182f, 176f, false, p);
            canvas.drawLine(w * 0.14f, h * 0.60f, w * 0.14f, h * 0.66f, p);
            canvas.drawLine(w * 0.86f, h * 0.59f, w * 0.86f, h * 0.66f, p);
            canvas.drawLine(w * 0.18f, h * 0.74f, w * 0.80f, h * 0.74f, p);

            p.setStrokeWidth(dp(1.5f));
            path.reset();
            path.moveTo(w * 0.38f, h * 0.56f);
            path.lineTo(w * 0.47f, h * 0.65f);
            path.lineTo(w * 0.64f, h * 0.48f);
            canvas.drawPath(path, p);
        }
    }

    private final class SkyLayout extends FrameLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Rect sourceRect = new Rect();
        private final RectF destinationRect = new RectF();
        private final Path fogPath = new Path();
        private final Path stormPath = new Path();
        private final Path lightningPath = new Path();
        private final Bitmap daySky = BitmapFactory.decodeResource(getResources(), R.drawable.weather_sky_day);
        private final Bitmap nightSky = BitmapFactory.decodeResource(getResources(), R.drawable.weather_sky_night);
        private final Bitmap rainSky = BitmapFactory.decodeResource(getResources(), R.drawable.weather_sky_rain);
        private final Bitmap snowSky = BitmapFactory.decodeResource(getResources(), R.drawable.weather_sky_snow);
        private final float[] particleX = new float[92];
        private final float[] particleY = new float[92];
        private final float[] particleSpeed = new float[92];
        private final float[] particleSize = new float[92];
        private final Shader[] fogBankShaders = new Shader[4];

        private SceneSpec currentSpec = new SceneSpec("day", "none", true, "Clear");
        private SceneSpec outgoingSpec;
        private Bitmap outgoingSnapshot;
        private long transitionStarted;
        private long thunderStarted;
        private boolean animationRunning = true;
        private long pausedAt = -1L;
        private long accumulatedPause;
        private Shader fallbackShader;
        private Shader washShader;
        private Shader stormDepthShader;
        private Shader fogWashShader;
        private Shader nightWeatherOverlay;

        SkyLayout(Context context) {
            super(context);
            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            setChildrenDrawingOrderEnabled(false);
            for (int i = 0; i < particleX.length; i++) {
                particleX[i] = ((i * 37) % 97) / 97f;
                particleY[i] = ((i * 53) % 101) / 101f;
                particleSpeed[i] = 0.24f + (((i * 29) % 41) / 80f);
                particleSize[i] = 0.65f + (((i * 17) % 23) / 18f);
            }
        }

        String setScene(SceneSpec target) {
            if (target == null) return currentSpec.paletteScene();
            if (target.equals(currentSpec) && outgoingSpec == null && outgoingSnapshot == null) {
                invalidate();
                return target.paletteScene();
            }
            long now = visualNow();
            boolean animate = animationRunning && animationsAllowed();
            if (!animate) {
                clearOutgoingSnapshot();
                outgoingSpec = null;
                currentSpec = target;
                transitionStarted = 0L;
                if ("thunder".equals(target.effect)) thunderStarted = now;
                invalidate();
                return target.paletteScene();
            }

            if (transitionActive(now)) {
                Bitmap snapshot = captureCurrentVisual(now);
                clearOutgoingSnapshot();
                outgoingSnapshot = snapshot;
                outgoingSpec = null;
            } else {
                clearOutgoingSnapshot();
                outgoingSpec = currentSpec;
            }
            boolean enteringThunder = !"thunder".equals(currentSpec.effect)
                    && "thunder".equals(target.effect);
            currentSpec = target;
            transitionStarted = now;
            if (enteringThunder) thunderStarted = now;
            postInvalidateOnAnimation();
            return target.paletteScene();
        }

        void setAnimationRunning(boolean running) {
            boolean allowed = running && animationsAllowed();
            if (animationRunning == allowed) {
                if (allowed) postInvalidateOnAnimation();
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (allowed) {
                if (pausedAt >= 0L) {
                    accumulatedPause += Math.max(0L, now - pausedAt);
                    pausedAt = -1L;
                }
                animationRunning = true;
                postInvalidateOnAnimation();
            } else {
                animationRunning = false;
                pausedAt = now;
                clearOutgoingSnapshot();
                outgoingSpec = null;
                transitionStarted = 0L;
                invalidate();
            }
        }

        private long visualNow() {
            long now = SystemClock.uptimeMillis();
            if (!animationRunning && pausedAt >= 0L) now = pausedAt;
            return now - accumulatedPause;
        }

        private boolean transitionActive(long now) {
            return (outgoingSpec != null || outgoingSnapshot != null)
                    && transitionStarted > 0L
                    && now - transitionStarted < SCENE_TRANSITION_MILLIS;
        }

        private float transitionProgress(long now) {
            if (outgoingSpec == null && outgoingSnapshot == null) return 1f;
            float linear = Math.max(0f, Math.min(1f,
                    (now - transitionStarted) / (float) SCENE_TRANSITION_MILLIS));
            // Fast-Out-Slow-In compatible smoothstep without introducing an Animator lifecycle.
            return linear * linear * (3f - 2f * linear);
        }

        private Bitmap captureCurrentVisual(long now) {
            if (getWidth() <= 0 || getHeight() <= 0) return null;
            try {
                Bitmap bitmap = Bitmap.createBitmap(
                        getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawComposite(canvas, now, false);
                return bitmap;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private void clearOutgoingSnapshot() {
            if (outgoingSnapshot != null) {
                try { outgoingSnapshot.recycle(); } catch (Exception ignored) { }
                outgoingSnapshot = null;
            }
        }

        void release() {
            animationRunning = false;
            outgoingSpec = null;
            transitionStarted = 0L;
            clearOutgoingSnapshot();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w <= 0 || h <= 0) return;
            fallbackShader = new LinearGradient(
                    0, 0, 0, h,
                    new int[]{Color.rgb(12, 89, 205), Color.rgb(80, 151, 235), Color.rgb(218, 231, 248)},
                    new float[]{0f, 0.52f, 1f},
                    Shader.TileMode.CLAMP);
            washShader = new LinearGradient(
                    0, 0, 0, h,
                    new int[]{Color.argb(58, 0, 35, 104), Color.argb(12, 18, 70, 150), Color.argb(28, 12, 60, 130)},
                    new float[]{0f, 0.56f, 1f},
                    Shader.TileMode.CLAMP);
            stormDepthShader = new LinearGradient(
                    0, 0, 0, h,
                    new int[]{Color.argb(80, 4, 10, 24), Color.argb(44, 8, 20, 42), Color.argb(68, 3, 9, 22)},
                    new float[]{0f, 0.52f, 1f},
                    Shader.TileMode.CLAMP);
            fogWashShader = new LinearGradient(
                    0, 0, 0, h,
                    new int[]{Color.argb(9, 232, 241, 248), Color.argb(24, 232, 241, 248), Color.argb(14, 224, 236, 246)},
                    new float[]{0f, 0.57f, 1f},
                    Shader.TileMode.CLAMP);
            nightWeatherOverlay = new LinearGradient(
                    0, 0, 0, h,
                    new int[]{Color.argb(105, 3, 13, 38), Color.argb(72, 6, 25, 58), Color.argb(96, 2, 12, 32)},
                    new float[]{0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP);
            buildFogShaders(h);
        }

        private void buildFogShaders(float h) {
            for (int i = 0; i < fogBankShaders.length; i++) {
                float topFraction;
                float heightFraction;
                int alpha;
                switch (i) {
                    case 0: topFraction = 0.08f; heightFraction = 0.22f; alpha = 36; break;
                    case 1: topFraction = 0.28f; heightFraction = 0.25f; alpha = 48; break;
                    case 2: topFraction = 0.50f; heightFraction = 0.20f; alpha = 34; break;
                    default: topFraction = 0.68f; heightFraction = 0.24f; alpha = 42; break;
                }
                float top = h * topFraction;
                float bottom = top + h * heightFraction;
                fogBankShaders[i] = new LinearGradient(
                        0, top, 0, bottom,
                        new int[]{
                                Color.argb(0, 242, 248, 252),
                                Color.argb(alpha, 242, 248, 252),
                                Color.argb(Math.max(1, alpha - 5), 232, 241, 248),
                                Color.argb(0, 232, 241, 248)},
                        new float[]{0f, 0.30f, 0.68f, 1f},
                        Shader.TileMode.CLAMP);
            }
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            // Sky animation is background-only. Child controls own all pointer handling.
            return false;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (getWidth() <= 0 || getHeight() <= 0) return;
            if (animationRunning && !animationsAllowed()) {
                setAnimationRunning(false);
            }
            long now = visualNow();
            drawComposite(canvas, now, true);
            if (animationRunning && (transitionActive(now) || !"none".equals(currentSpec.effect))) {
                postInvalidateOnAnimation();
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            // All scene/effect rendering happens in onDraw(), before FrameLayout dispatches any
            // weather cards, text, toolbar, or controls. Never add effect drawing below this call.
            super.dispatchDraw(canvas);
        }

        private void drawComposite(Canvas canvas, long now, boolean cleanupFinished) {
            float w = getWidth();
            float h = getHeight();
            float progress = transitionProgress(now);
            boolean transitioning = outgoingSpec != null || outgoingSnapshot != null;

            if (outgoingSnapshot != null) {
                paint.setShader(null);
                paint.setAlpha(255);
                destinationRect.set(0, 0, w, h);
                canvas.drawBitmap(outgoingSnapshot, null, destinationRect, paint);
            } else if (outgoingSpec != null) {
                // Existing thunder flashes are part of the outgoing visual and therefore fade
                // with that layer. Only flashes belonging to an incoming thunder target wait
                // until the transition is mostly complete.
                drawSceneLayer(canvas, outgoingSpec, 255, now,
                        "thunder".equals(outgoingSpec.effect));
            }

            if (!transitioning) {
                drawSceneLayer(canvas, currentSpec, 255, now, true);
            } else {
                drawSceneLayer(canvas, currentSpec, Math.round(progress * 255f), now, progress >= 0.88f);
                if (cleanupFinished && progress >= 1f) {
                    outgoingSpec = null;
                    clearOutgoingSnapshot();
                    transitionStarted = 0L;
                }
            }
        }

        private void drawSceneLayer(
                Canvas canvas, SceneSpec spec, int layerAlpha, long now, boolean allowLightning) {
            if (spec == null || layerAlpha <= 0) return;
            int save = canvas.saveLayerAlpha(
                    0, 0, getWidth(), getHeight(),
                    Math.max(0, Math.min(255, layerAlpha)));
            Bitmap base = bitmapFor(spec.base);
            if (base != null) drawCover(canvas, base, getWidth(), getHeight(), 255);
            else {
                paint.setShader(fallbackShader);
                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setShader(washShader);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(null);

            if (!spec.daytime
                    && ("rain".equals(spec.effect)
                    || "snow".equals(spec.effect)
                    || "thunder".equals(spec.effect))) {
                paint.setShader(nightWeatherOverlay);
                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
                paint.setShader(null);
            }

            if ("thunder".equals(spec.effect)) {
                drawThunderAtmosphere(canvas, getWidth(), getHeight(), now);
                drawRain(canvas, getWidth(), getHeight(), now);
                if (animationRunning && allowLightning) {
                    drawLightningBolt(canvas, getWidth(), getHeight(), now);
                    drawLightningFlash(canvas, getWidth(), getHeight(), now);
                }
            } else if ("rain".equals(spec.effect)) {
                drawRain(canvas, getWidth(), getHeight(), now);
            } else if ("snow".equals(spec.effect)) {
                drawSnow(canvas, getWidth(), getHeight(), now);
            } else if ("fog".equals(spec.effect)) {
                drawFog(canvas, getWidth(), getHeight(), now);
            }
            canvas.restoreToCount(save);
        }

        private Bitmap bitmapFor(String base) {
            if ("night".equals(base)) return nightSky;
            if ("rain".equals(base)) return rainSky;
            if ("snow".equals(base)) return snowSky;
            return daySky;
        }

        private void drawCover(Canvas canvas, Bitmap bitmap, float w, float h, int alpha) {
            if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return;
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
            paint.setAlpha(alpha);
            canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint);
            paint.setAlpha(255);
        }

        private void drawRain(Canvas canvas, float w, float h, long now) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.15f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(Color.argb(90, 206, 229, 255));
            float seconds = now / 1000f;
            for (int i = 0; i < particleX.length; i++) {
                float x = particleX[i] * w;
                float y = ((particleY[i] + seconds * particleSpeed[i]) % 1.12f) * h - h * 0.08f;
                float length = dp(10f + particleSize[i] * 8f);
                canvas.drawLine(x, y, x - length * 0.28f, y + length, paint);
            }
        }

        private void drawSnow(Canvas canvas, float w, float h, long now) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(178, 255, 255, 255));
            float seconds = now / 1000f;
            for (int i = 0; i < particleX.length; i++) {
                float base = particleX[i] * w;
                float x = base + (float) Math.sin(seconds * 0.7f + i) * dp(9f);
                float y = ((particleY[i] + seconds * particleSpeed[i] * 0.17f) % 1.08f) * h - h * 0.04f;
                canvas.drawCircle(x, y, dp(particleSize[i] * 1.35f), paint);
            }
        }

        private void drawThunderAtmosphere(Canvas canvas, float w, float h, long now) {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(stormDepthShader);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            float seconds = now / 1000f;
            for (int i = 0; i < 3; i++) {
                float drift = (float) Math.sin(seconds * (0.10f + i * 0.018f) + i * 1.9f)
                        * w * (0.055f + i * 0.012f);
                float y = h * (0.13f + i * 0.24f)
                        + (float) Math.sin(seconds * (0.07f + i * 0.011f) + i)
                        * h * 0.018f;
                float bandHeight = h * (0.14f + i * 0.018f);
                float left = -w * 0.38f + drift;
                float right = w * 1.38f + drift;

                stormPath.reset();
                stormPath.moveTo(left, y + bandHeight * 0.30f);
                stormPath.cubicTo(left + w * 0.38f, y - bandHeight * 0.10f,
                        left + w * 0.72f, y + bandHeight * 0.10f,
                        left + w, y + bandHeight * 0.20f);
                stormPath.cubicTo(left + w * 1.24f, y + bandHeight * 0.30f,
                        left + w * 1.50f, y - bandHeight * 0.05f,
                        right, y + bandHeight * 0.24f);
                stormPath.lineTo(right, y + bandHeight * 0.86f);
                stormPath.cubicTo(left + w * 1.50f, y + bandHeight * 1.05f,
                        left + w * 1.18f, y + bandHeight * 0.74f,
                        left + w, y + bandHeight * 0.80f);
                stormPath.cubicTo(left + w * 0.66f, y + bandHeight * 0.96f,
                        left + w * 0.34f, y + bandHeight * 0.70f,
                        left, y + bandHeight * 0.84f);
                stormPath.close();
                paint.setColor(Color.argb(20 + i * 7, 4, 12 + i * 4, 28 + i * 7));
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
                    case 0: topFraction = 0.08f; heightFraction = 0.22f; speed = 0.055f; break;
                    case 1: topFraction = 0.28f; heightFraction = 0.25f; speed = -0.038f; break;
                    case 2: topFraction = 0.50f; heightFraction = 0.20f; speed = 0.031f; break;
                    default: topFraction = 0.68f; heightFraction = 0.24f; speed = -0.024f; break;
                }
                float top = h * topFraction;
                float bankHeight = h * heightFraction;
                float drift = (float) Math.sin(seconds * speed + i * 1.7f) * w * 0.13f;
                float left = -w * 0.48f + drift;
                float right = w * 1.48f + drift;

                fogPath.reset();
                fogPath.moveTo(left, top + bankHeight * 0.34f);
                fogPath.cubicTo(left + w * 0.36f, top - bankHeight * 0.05f,
                        left + w * 0.68f, top + bankHeight * 0.05f,
                        left + w, top + bankHeight * 0.22f);
                fogPath.cubicTo(left + w * 1.24f, top + bankHeight * 0.34f,
                        left + w * 1.58f, top - bankHeight * 0.02f,
                        right, top + bankHeight * 0.26f);
                fogPath.lineTo(right, top + bankHeight * 0.82f);
                fogPath.cubicTo(left + w * 1.56f, top + bankHeight * 1.00f,
                        left + w * 1.28f, top + bankHeight * 0.72f,
                        left + w, top + bankHeight * 0.80f);
                fogPath.cubicTo(left + w * 0.70f, top + bankHeight * 0.96f,
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
            long phase = elapsed % 6400L;
            if (phase < 78L) return 1f - phase / 96f;
            if (phase >= 112L && phase < 184L) {
                return 0.52f * (1f - (phase - 112L) / 72f);
            }
            return 0f;
        }

        private void buildLightningPath(float w, float h, long now) {
            long eventIndex = Math.max(0L, now - thunderStarted) / 6400L;
            float anchor = 0.40f + ((eventIndex * 37L) % 19L) / 100f;
            float x0 = w * anchor;
            lightningPath.reset();
            lightningPath.moveTo(x0, h * 0.08f);
            lightningPath.lineTo(x0 - w * 0.035f, h * 0.24f);
            lightningPath.lineTo(x0 + w * 0.012f, h * 0.36f);
            lightningPath.lineTo(x0 - w * 0.050f, h * 0.52f);
            lightningPath.lineTo(x0 - w * 0.018f, h * 0.68f);
            lightningPath.lineTo(x0 - w * 0.072f, h * 0.84f);
            lightningPath.moveTo(x0 + w * 0.002f, h * 0.35f);
            lightningPath.lineTo(x0 + w * 0.105f, h * 0.43f);
            lightningPath.lineTo(x0 + w * 0.145f, h * 0.54f);
        }

        private void drawLightningBolt(Canvas canvas, float w, float h, long now) {
            float strength = lightningStrength(now);
            if (strength <= 0f) return;
            buildLightningPath(w, h, now);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(dp(7f));
            paint.setColor(Color.argb(Math.round(58 * strength), 188, 220, 255));
            canvas.drawPath(lightningPath, paint);
            paint.setStrokeWidth(dp(1.65f));
            paint.setColor(Color.argb(Math.round(220 * strength), 236, 247, 255));
            canvas.drawPath(lightningPath, paint);
        }

        private void drawLightningFlash(Canvas canvas, float w, float h, long now) {
            float strength = lightningStrength(now);
            if (strength <= 0f) return;
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(Math.round(92 * strength), 225, 238, 255));
            canvas.drawRect(0, 0, w, h, paint);
        }
    }

    private final class HeaderGlyphButton extends View {
        static final int MENU_PLUS = 1;
        static final int SETTINGS = 2;
        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        HeaderGlyphButton(Context context, int kind) {
            super(context);
            this.kind = kind;
            setClickable(true);
            setFocusable(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            if (isPressed()) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.argb(28, 255, 255, 255));
                canvas.drawCircle(cx, cy, Math.min(w, h) * 0.42f, p);
            }
            p.setColor(WHITE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(2));

            if (kind == MENU_PLUS) {
                float left = w * 0.22f;
                float right = w * 0.66f;
                canvas.drawLine(left, h * 0.30f, right, h * 0.30f, p);
                canvas.drawLine(left, h * 0.48f, w * 0.57f, h * 0.48f, p);
                canvas.drawLine(left, h * 0.66f, w * 0.49f, h * 0.66f, p);
                canvas.drawLine(w * 0.68f, h * 0.55f, w * 0.68f, h * 0.80f, p);
                canvas.drawLine(w * 0.56f, h * 0.675f, w * 0.80f, h * 0.675f, p);
            } else {
                float r = Math.min(w, h) * 0.28f;
                canvas.drawCircle(cx, cy, r, p);
                canvas.drawCircle(cx, cy, r * 0.38f, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.toRadians(i * 45);
                    float x1 = cx + (float) Math.cos(a) * r * 1.06f;
                    float y1 = cy + (float) Math.sin(a) * r * 1.06f;
                    float x2 = cx + (float) Math.cos(a) * r * 1.34f;
                    float y2 = cy + (float) Math.sin(a) * r * 1.34f;
                    canvas.drawLine(x1, y1, x2, y2, p);
                }
            }
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }
    }

    private final class WeatherGlyphView extends View {
        private final String condition;
        private final boolean daytime;

        WeatherGlyphView(Context context, String condition, boolean daytime) {
            super(context);
            this.condition = condition;
            this.daytime = daytime;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            drawWeatherGlyph(canvas, condition, daytime, getWidth() / 2f, getHeight() / 2f,
                    Math.min(getWidth(), getHeight()) * 0.82f);
        }
    }

    private void drawWeatherGlyph(Canvas canvas, String condition, boolean daytime, float cx, float cy, float size) {
        String key = conditionKey(condition);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);

        if ("clear".equals(key)) {
            if (daytime) {
                drawSun(canvas, p, cx, cy, size * 0.28f);
            } else {
                drawMoonCrescent(canvas, p, cx, cy, size * 0.34f);
            }
            return;
        }

        if ("partly".equals(key)) {
            if (daytime) {
                drawSun(canvas, p, cx + size * 0.18f, cy - size * 0.16f, size * 0.20f);
            } else {
                drawMoonCrescent(canvas, p, cx + size * 0.20f, cy - size * 0.17f, size * 0.22f);
            }
            drawCloud(canvas, p, cx - size * 0.06f, cy + size * 0.08f, size * 0.62f);
            return;
        }

        if ("fog".equals(key)) {
            p.setStyle(Paint.Style.STROKE);
            p.setColor(WHITE);
            p.setStrokeWidth(Math.max(2f, size * 0.07f));
            for (int i = -1; i <= 1; i++) {
                float y = cy + i * size * 0.17f;
                canvas.drawLine(cx - size * 0.38f, y, cx + size * 0.38f, y, p);
            }
            return;
        }

        drawCloud(canvas, p, cx, cy - size * 0.04f, size * 0.68f);

        if ("rain".equals(key)) {
            p.setColor(ACCENT_BLUE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, size * 0.065f));
            for (int i = -1; i <= 1; i++) {
                float x = cx + i * size * 0.20f;
                canvas.drawLine(x, cy + size * 0.28f, x - size * 0.05f, cy + size * 0.43f, p);
            }
        } else if ("snow".equals(key)) {
            p.setColor(WHITE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.6f, size * 0.04f));
            for (int i = -1; i <= 1; i++) {
                float x = cx + i * size * 0.21f;
                float y = cy + size * 0.37f;
                canvas.drawLine(x - size * 0.06f, y, x + size * 0.06f, y, p);
                canvas.drawLine(x, y - size * 0.06f, x, y + size * 0.06f, p);
                canvas.drawLine(x - size * 0.045f, y - size * 0.045f, x + size * 0.045f, y + size * 0.045f, p);
                canvas.drawLine(x + size * 0.045f, y - size * 0.045f, x - size * 0.045f, y + size * 0.045f, p);
            }
        } else if ("thunder".equals(key)) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(ACCENT_YELLOW);
            Path bolt = new Path();
            bolt.moveTo(cx + size * 0.05f, cy + size * 0.20f);
            bolt.lineTo(cx - size * 0.10f, cy + size * 0.42f);
            bolt.lineTo(cx + size * 0.01f, cy + size * 0.40f);
            bolt.lineTo(cx - size * 0.04f, cy + size * 0.58f);
            bolt.lineTo(cx + size * 0.18f, cy + size * 0.31f);
            bolt.lineTo(cx + size * 0.06f, cy + size * 0.33f);
            bolt.close();
            canvas.drawPath(bolt, p);
        }
    }

    private static void drawSun(Canvas canvas, Paint p, float cx, float cy, float r) {
        p.setShader(null);
        p.setColor(ACCENT_YELLOW);
        p.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, r * 0.18f));
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45);
            float x1 = cx + (float) Math.cos(a) * r * 1.35f;
            float y1 = cy + (float) Math.sin(a) * r * 1.35f;
            float x2 = cx + (float) Math.cos(a) * r * 1.80f;
            float y2 = cy + (float) Math.sin(a) * r * 1.80f;
            canvas.drawLine(x1, y1, x2, y2, p);
        }
    }

    private static void drawMoonCrescent(Canvas canvas, Paint p, float cx, float cy, float r) {
        p.setShader(null);
        p.setColor(WHITE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2.5f, r * 0.22f));
        p.setStrokeCap(Paint.Cap.ROUND);
        RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(oval, 65, 230, false, p);
    }

    private static void drawCloud(Canvas canvas, Paint p, float cx, float cy, float size) {
        p.setShader(null);
        p.setColor(WHITE);
        p.setStyle(Paint.Style.FILL);
        float h = size * 0.34f;
        canvas.drawRoundRect(new RectF(cx - size * 0.46f, cy - h * 0.05f, cx + size * 0.46f, cy + h * 0.62f), h, h, p);
        canvas.drawCircle(cx - size * 0.20f, cy - h * 0.06f, h * 0.68f, p);
        canvas.drawCircle(cx + size * 0.07f, cy - h * 0.28f, h * 0.88f, p);
        canvas.drawCircle(cx + size * 0.28f, cy - h * 0.03f, h * 0.58f, p);
    }

    private final class DetailGlyphView extends View {
        private final String kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        DetailGlyphView(Context context, String kind) {
            super(context);
            this.kind = kind;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float s = Math.min(w, h);
            p.setShader(null);
            p.setColor(WHITE);
            p.setStrokeWidth(Math.max(2f, s * 0.065f));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStyle(Paint.Style.STROKE);

            switch (kind) {
                case "uv":
                    canvas.drawCircle(cx, cy, s * 0.18f, p);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.toRadians(i * 45);
                        canvas.drawLine(
                                cx + (float) Math.cos(a) * s * 0.30f,
                                cy + (float) Math.sin(a) * s * 0.30f,
                                cx + (float) Math.cos(a) * s * 0.43f,
                                cy + (float) Math.sin(a) * s * 0.43f, p);
                    }
                    break;
                case "temperature":
                    canvas.drawRoundRect(new RectF(cx - s * 0.10f, cy - s * 0.36f, cx + s * 0.10f, cy + s * 0.18f), s * 0.10f, s * 0.10f, p);
                    canvas.drawCircle(cx, cy + s * 0.27f, s * 0.18f, p);
                    canvas.drawLine(cx, cy - s * 0.22f, cx, cy + s * 0.22f, p);
                    break;
                case "humidity": {
                    Path drop = new Path();
                    drop.moveTo(cx, cy - s * 0.40f);
                    drop.cubicTo(cx - s * 0.26f, cy - s * 0.08f, cx - s * 0.30f, cy + s * 0.14f, cx, cy + s * 0.38f);
                    drop.cubicTo(cx + s * 0.30f, cy + s * 0.14f, cx + s * 0.26f, cy - s * 0.08f, cx, cy - s * 0.40f);
                    canvas.drawPath(drop, p);
                    break;
                }
                case "wind":
                    canvas.drawLine(cx - s * 0.40f, cy - s * 0.18f, cx + s * 0.17f, cy - s * 0.18f, p);
                    canvas.drawArc(new RectF(cx + s * 0.02f, cy - s * 0.33f, cx + s * 0.34f, cy - s * 0.02f), -90, 180, false, p);
                    canvas.drawLine(cx - s * 0.40f, cy + s * 0.06f, cx + s * 0.28f, cy + s * 0.06f, p);
                    canvas.drawLine(cx - s * 0.24f, cy + s * 0.28f, cx + s * 0.09f, cy + s * 0.28f, p);
                    break;
                case "pressure":
                    canvas.drawLine(cx, cy - s * 0.40f, cx, cy + s * 0.40f, p);
                    canvas.drawLine(cx, cy - s * 0.40f, cx - s * 0.10f, cy - s * 0.27f, p);
                    canvas.drawLine(cx, cy - s * 0.40f, cx + s * 0.10f, cy - s * 0.27f, p);
                    canvas.drawLine(cx - s * 0.38f, cy + s * 0.10f, cx - s * 0.12f, cy + s * 0.10f, p);
                    canvas.drawLine(cx + s * 0.12f, cy + s * 0.10f, cx + s * 0.38f, cy + s * 0.10f, p);
                    break;
                case "visibility":
                    canvas.drawOval(new RectF(cx - s * 0.40f, cy - s * 0.24f, cx + s * 0.40f, cy + s * 0.24f), p);
                    canvas.drawCircle(cx, cy, s * 0.11f, p);
                    break;
                case "air":
                    canvas.drawArc(new RectF(cx - s * 0.38f, cy - s * 0.17f, cx + s * 0.18f, cy + s * 0.20f), 180, 180, false, p);
                    canvas.drawArc(new RectF(cx - s * 0.05f, cy - s * 0.28f, cx + s * 0.38f, cy + s * 0.06f), 180, 180, false, p);
                    canvas.drawLine(cx - s * 0.35f, cy + s * 0.28f, cx + s * 0.30f, cy + s * 0.28f, p);
                    break;
                case "pollen": {
                    Path leaf = new Path();
                    leaf.moveTo(cx - s * 0.30f, cy + s * 0.25f);
                    leaf.cubicTo(cx - s * 0.16f, cy - s * 0.34f, cx + s * 0.34f, cy - s * 0.34f, cx + s * 0.28f, cy + s * 0.18f);
                    leaf.cubicTo(cx + s * 0.02f, cy + s * 0.38f, cx - s * 0.18f, cy + s * 0.34f, cx - s * 0.30f, cy + s * 0.25f);
                    canvas.drawPath(leaf, p);
                    canvas.drawLine(cx - s * 0.20f, cy + s * 0.22f, cx + s * 0.18f, cy - s * 0.16f, p);
                    canvas.drawCircle(cx + s * 0.31f, cy + s * 0.28f, s * 0.055f, p);
                    break;
                }
                default:
                    canvas.drawCircle(cx, cy, s * 0.28f, p);
            }
        }
    }

    private final class RefreshScrollView extends ScrollView {
        private static final int LOCK_NONE = 0;
        private static final int LOCK_VERTICAL = 1;
        private static final int LOCK_HORIZONTAL = 2;

        private final int touchSlop;
        private int activePointerId = MotionEvent.INVALID_POINTER_ID;
        private float downX;
        private float downY;
        private int lock = LOCK_NONE;
        private boolean eligible;
        private float pullDistance;

        RefreshScrollView(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            boolean triggerRefresh = false;

            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    activePointerId = event.getPointerId(0);
                    downX = event.getX(0);
                    downY = event.getY(0);
                    lock = LOCK_NONE;
                    pullDistance = 0f;
                    eligible = getScrollY() == 0 && !weatherLoadActive;
                    if (eligible && refreshIndicator != null) refreshIndicator.beginPull();
                    break;
                }
                case MotionEvent.ACTION_POINTER_DOWN: {
                    int index = event.getActionIndex();
                    activePointerId = event.getPointerId(index);
                    downX = event.getX(index);
                    downY = event.getY(index);
                    pullDistance = 0f;
                    lock = LOCK_NONE;
                    break;
                }
                case MotionEvent.ACTION_POINTER_UP:
                    rebaseAfterPointerUp(event);
                    break;
                case MotionEvent.ACTION_MOVE: {
                    if (!eligible || weatherLoadActive || getScrollY() != 0) {
                        cancelPull();
                        break;
                    }
                    int index = event.findPointerIndex(activePointerId);
                    if (index < 0) {
                        cancelPull();
                        break;
                    }
                    float dx = event.getX(index) - downX;
                    float dy = event.getY(index) - downY;
                    if (lock == LOCK_NONE && Math.max(Math.abs(dx), Math.abs(dy)) > touchSlop) {
                        if (Math.abs(dx) > Math.abs(dy) * 0.92f) {
                            lock = LOCK_HORIZONTAL;
                            cancelPull();
                        } else if (dy > 0f) {
                            lock = LOCK_VERTICAL;
                        } else {
                            cancelPull();
                        }
                    }
                    if (lock == LOCK_VERTICAL && dy > 0f) {
                        pullDistance = Math.min(dp(132), dy * 0.46f);
                        if (refreshIndicator != null) {
                            refreshIndicator.setPull(
                                    pullDistance / Math.max(1f, dp(74)),
                                    pullDistance >= dp(74));
                        }
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                    triggerRefresh = eligible
                            && lock == LOCK_VERTICAL
                            && pullDistance >= dp(74)
                            && getScrollY() == 0
                            && !weatherLoadActive;
                    activePointerId = MotionEvent.INVALID_POINTER_ID;
                    eligible = false;
                    lock = LOCK_NONE;
                    pullDistance = 0f;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    cancelPull();
                    activePointerId = MotionEvent.INVALID_POINTER_ID;
                    break;
            }

            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP) {
                if (triggerRefresh) {
                    performPullRefresh();
                } else if (refreshIndicator != null) {
                    refreshIndicator.settle();
                }
            } else if (action == MotionEvent.ACTION_CANCEL && refreshIndicator != null) {
                refreshIndicator.settle();
            }
            return handled;
        }

        private void rebaseAfterPointerUp(MotionEvent event) {
            if (event.getPointerId(event.getActionIndex()) != activePointerId) return;
            int replacement = event.getActionIndex() == 0 ? 1 : 0;
            if (replacement >= event.getPointerCount()) {
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                cancelPull();
                return;
            }
            activePointerId = event.getPointerId(replacement);
            downX = event.getX(replacement);
            downY = event.getY(replacement);
            pullDistance = 0f;
            lock = LOCK_NONE;
        }

        private void cancelPull() {
            eligible = false;
            pullDistance = 0f;
            lock = LOCK_NONE;
            if (refreshIndicator != null && !refreshIndicator.isRefreshing()) {
                refreshIndicator.settle();
            }
        }
    }

    private final class RefreshIndicatorView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path arrow = new Path();
        private float pullFraction;
        private boolean releaseReady;
        private boolean refreshing;
        private int topColor = Color.argb(150, 72, 132, 205);
        private int bottomColor = Color.argb(128, 72, 118, 174);
        private int accentColor = ACCENT_BLUE;
        private long refreshStarted;

        RefreshIndicatorView(Context context) {
            super(context);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setTextAlign(Paint.Align.LEFT);
            setContentDescription("Pull to refresh");
        }

        boolean isRefreshing() {
            return refreshing;
        }

        void setPalette(int top, int bottom, int accent) {
            topColor = withMinimumAlpha(top, 152);
            bottomColor = withMinimumAlpha(bottom, 126);
            accentColor = accent;
            invalidate();
        }

        void beginPull() {
            if (refreshing) return;
            animate().cancel();
            setVisibility(View.VISIBLE);
            setAlpha(0f);
            pullFraction = 0f;
            releaseReady = false;
            setContentDescription("Pull to refresh");
        }

        void setPull(float fraction, boolean ready) {
            if (refreshing) return;
            animate().cancel();
            setVisibility(View.VISIBLE);
            pullFraction = Math.max(0f, Math.min(1.28f, fraction));
            setAlpha(Math.min(1f, 0.22f + pullFraction * 0.78f));
            setTranslationY(dp(10) * (1f - Math.min(1f, pullFraction)));
            if (releaseReady != ready) {
                releaseReady = ready;
                setContentDescription(ready ? "Release to refresh" : "Pull to refresh");
                if (ready && isShown()) announceForAccessibility("Release to refresh");
            }
            invalidate();
        }

        void setRefreshing(boolean value) {
            refreshing = value;
            if (value) {
                releaseReady = false;
                pullFraction = 1f;
                refreshStarted = SystemClock.uptimeMillis();
                animate().cancel();
                setVisibility(View.VISIBLE);
                setAlpha(1f);
                setTranslationY(0f);
                setContentDescription("Refreshing weather");
                invalidate();
                postInvalidateOnAnimation();
            } else {
                settle();
            }
        }

        void finish() {
            refreshing = false;
            releaseReady = false;
            pullFraction = 0f;
            setContentDescription("Pull to refresh");
            settle();
        }

        void settle() {
            if (refreshing || getVisibility() != View.VISIBLE) return;
            animate().cancel();
            animate()
                    .alpha(0f)
                    .translationY(-dp(8))
                    .setDuration(170L)
                    .withEndAction(() -> {
                        if (!refreshing) {
                            setVisibility(View.INVISIBLE);
                            setTranslationY(0f);
                        }
                    })
                    .start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            rect.set(dp(1), dp(1), w - dp(1), h - dp(1));
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(
                    0, 0, 0, h,
                    topColor, bottomColor,
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, h * 0.48f, h * 0.48f, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.argb(48, 255, 255, 255));
            canvas.drawRoundRect(rect, h * 0.48f, h * 0.48f, paint);

            float cx = dp(23);
            float cy = h / 2f;
            paint.setStrokeWidth(dp(1.7f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(accentColor);
            paint.setStyle(Paint.Style.STROKE);

            if (refreshing) {
                float start = ((SystemClock.uptimeMillis() - refreshStarted) % 900L) / 900f * 360f - 90f;
                canvas.drawArc(new RectF(cx - dp(7), cy - dp(7), cx + dp(7), cy + dp(7)),
                        start, 250f, false, paint);
                postInvalidateOnAnimation();
            } else {
                float rotation = Math.min(1f, pullFraction) * 180f;
                int save = canvas.save();
                canvas.rotate(rotation, cx, cy);
                arrow.reset();
                arrow.moveTo(cx, cy - dp(7));
                arrow.lineTo(cx, cy + dp(5));
                arrow.moveTo(cx, cy + dp(5));
                arrow.lineTo(cx - dp(4), cy + dp(1));
                arrow.moveTo(cx, cy + dp(5));
                arrow.lineTo(cx + dp(4), cy + dp(1));
                canvas.drawPath(arrow, paint);
                canvas.restoreToCount(save);
            }

            String label = refreshing
                    ? "Refreshing…"
                    : releaseReady ? "Release to refresh" : "Pull to refresh";
            textPaint.setColor(WHITE);
            textPaint.setTextSize(dp(11.5f));
            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            float baseline = cy - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(label, dp(38), baseline, textPaint);
        }

        private int withMinimumAlpha(int color, int minimum) {
            return Color.argb(
                    Math.max(minimum, Color.alpha(color)),
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color));
        }
    }

    private final class GestureHorizontalScrollView extends HorizontalScrollView {
        private static final int AXIS_UNDECIDED = 0;
        private static final int AXIS_HORIZONTAL = 1;
        private static final int AXIS_VERTICAL = 2;

        private final int touchSlop;
        private float downX;
        private float downY;
        private int activePointerId = MotionEvent.INVALID_POINTER_ID;
        private int lockedAxis = AXIS_UNDECIDED;

        GestureHorizontalScrollView(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            // Android 12+ renders EdgeEffect as stretch. API 36 therefore supplies the
            // restrained edge elasticity and fling absorption without a custom glow.
            setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    rebaseGesture(event, event.getActionIndex());
                    setParentInterceptDisallowed(true);
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    setParentInterceptDisallowed(false);
                    rebaseGesture(event, event.getActionIndex());
                    setParentInterceptDisallowed(true);
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    setParentInterceptDisallowed(false);
                    rebaseAfterPointerUp(event);
                    setParentInterceptDisallowed(activePointerId != MotionEvent.INVALID_POINTER_ID);
                    break;
                case MotionEvent.ACTION_MOVE:
                    int pointerIndex = event.findPointerIndex(activePointerId);
                    if (pointerIndex < 0 && event.getPointerCount() > 0) {
                        rebaseGesture(event, 0);
                        setParentInterceptDisallowed(true);
                        break;
                    }
                    if (pointerIndex >= 0) {
                        float dx = Math.abs(event.getX(pointerIndex) - downX);
                        float dy = Math.abs(event.getY(pointerIndex) - downY);
                        if (lockedAxis == AXIS_UNDECIDED && (dx > touchSlop || dy > touchSlop)) {
                            lockedAxis = dx > dy ? AXIS_HORIZONTAL : AXIS_VERTICAL;
                        }
                        if (lockedAxis == AXIS_HORIZONTAL) {
                            // Keep ownership even at either horizontal edge. Releasing here is
                            // what makes a drag feel stuck and can lose the reverse-direction move.
                            setParentInterceptDisallowed(true);
                        } else if (lockedAxis == AXIS_VERTICAL) {
                            // The outer ScrollView may take over on the next move without a jump.
                            setParentInterceptDisallowed(false);
                        }
                    }
                    break;
                default:
                    break;
            }

            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                resetGestureState();
            }
            return handled;
        }

        private void rebaseGesture(MotionEvent event, int pointerIndex) {
            if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) {
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                lockedAxis = AXIS_UNDECIDED;
                return;
            }
            activePointerId = event.getPointerId(pointerIndex);
            downX = event.getX(pointerIndex);
            downY = event.getY(pointerIndex);
            lockedAxis = AXIS_UNDECIDED;
        }

        private void rebaseAfterPointerUp(MotionEvent event) {
            int liftedIndex = event.getActionIndex();
            int replacement = -1;
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (i != liftedIndex) {
                    replacement = i;
                    break;
                }
            }
            if (replacement >= 0) {
                rebaseGesture(event, replacement);
            } else {
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                lockedAxis = AXIS_UNDECIDED;
            }
        }

        private void resetGestureState() {
            activePointerId = MotionEvent.INVALID_POINTER_ID;
            lockedAxis = AXIS_UNDECIDED;
            setParentInterceptDisallowed(false);
            invalidate();
        }

        private void setParentInterceptDisallowed(boolean disallow) {
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(disallow);
            }
        }
    }

    private final class GestureVerticalScrollView extends ScrollView {
        private final int touchSlop;
        private float downX;
        private float downY;
        private int activePointerId = MotionEvent.INVALID_POINTER_ID;

        GestureVerticalScrollView(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    rebaseGesture(event, event.getActionIndex());
                    requestParentForCurrentRange();
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    setParentInterceptDisallowed(false);
                    rebaseGesture(event, event.getActionIndex());
                    requestParentForCurrentRange();
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    setParentInterceptDisallowed(false);
                    rebaseAfterPointerUp(event);
                    requestParentForCurrentRange();
                    break;
                case MotionEvent.ACTION_MOVE:
                    int pointerIndex = event.findPointerIndex(activePointerId);
                    if (pointerIndex < 0 && event.getPointerCount() > 0) {
                        rebaseGesture(event, 0);
                        requestParentForCurrentRange();
                        break;
                    }
                    if (pointerIndex >= 0) {
                        float dx = Math.abs(event.getX(pointerIndex) - downX);
                        float signedDy = event.getY(pointerIndex) - downY;
                        float dy = Math.abs(signedDy);
                        if (dy > touchSlop && dy > dx) {
                            int direction = signedDy < 0 ? 1 : -1;
                            // While the inner list can consume motion it keeps the gesture. At an
                            // edge, ownership returns to the page; a fling that reaches the edge is
                            // still absorbed by the platform stretch EdgeEffect before settling.
                            setParentInterceptDisallowed(canScrollVertically(direction));
                        } else if (dx > touchSlop) {
                            setParentInterceptDisallowed(false);
                        }
                    }
                    break;
                default:
                    break;
            }

            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                resetGestureState();
            }
            return handled;
        }

        private void rebaseGesture(MotionEvent event, int pointerIndex) {
            if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) {
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                return;
            }
            activePointerId = event.getPointerId(pointerIndex);
            downX = event.getX(pointerIndex);
            downY = event.getY(pointerIndex);
        }

        private void rebaseAfterPointerUp(MotionEvent event) {
            int liftedIndex = event.getActionIndex();
            int replacement = -1;
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (i != liftedIndex) {
                    replacement = i;
                    break;
                }
            }
            if (replacement >= 0) {
                rebaseGesture(event, replacement);
            } else {
                activePointerId = MotionEvent.INVALID_POINTER_ID;
            }
        }

        private void requestParentForCurrentRange() {
            setParentInterceptDisallowed(
                    activePointerId != MotionEvent.INVALID_POINTER_ID
                            && (canScrollVertically(1) || canScrollVertically(-1)));
        }

        private void resetGestureState() {
            activePointerId = MotionEvent.INVALID_POINTER_ID;
            setParentInterceptDisallowed(false);
            invalidate();
        }

        private void setParentInterceptDisallowed(boolean disallow) {
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(disallow);
            }
        }
    }

    private final class ForecastChartView extends View {
        private final JSONArray days;
        private final ZoneId zone;
        private final int desiredWidth;
        private final int desiredHeight;
        private final DaySelectionListener daySelectionListener;
        private final int touchSlop;
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float downX;
        private float downY;
        private boolean moved;
        private int cursorDay;
        private int previewDay = -1;

        ForecastChartView(
                Context context,
                JSONArray days,
                ZoneId zone,
                int desiredWidth,
                int desiredHeight,
                DaySelectionListener daySelectionListener) {
            super(context);
            this.days = days;
            this.zone = zone;
            this.desiredWidth = Math.max(dp(280), desiredWidth);
            this.desiredHeight = Math.max(dp(290), desiredHeight);
            this.daySelectionListener = daySelectionListener;
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setMinimumWidth(this.desiredWidth);
            setMinimumHeight(this.desiredHeight);
            setClickable(true);
            setFocusable(true);
            setContentDescription(chartAccessibilityDescription(days, zone));
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // Keep the v4/API-36 fix: HorizontalScrollView may offer an UNSPECIFIED axis.
            int measuredWidth = measuredDimension(desiredWidth, widthMeasureSpec);
            int measuredHeight = measuredDimension(desiredHeight, heightMeasureSpec);
            setMeasuredDimension(measuredWidth, measuredHeight);
        }

        private int measuredDimension(int desired, int spec) {
            int mode = MeasureSpec.getMode(spec);
            int size = MeasureSpec.getSize(spec);
            if (mode == MeasureSpec.EXACTLY) return size;
            if (mode == MeasureSpec.AT_MOST) return Math.min(desired, size);
            return desired;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX() - downX) > touchSlop
                            || Math.abs(event.getY() - downY) > touchSlop) {
                        moved = true;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved && days != null && days.length() > 0) {
                        int n = Math.min(10, days.length());
                        float cell = getWidth() / (float) Math.max(1, n);
                        int index = Math.max(0, Math.min(n - 1, (int) (event.getX() / cell)));
                        cursorDay = index;
                        performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    moved = true;
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            int count = days == null ? 0 : Math.min(10, days.length());
            if (count > 0 && daySelectionListener != null) {
                cursorDay = Math.max(0, Math.min(count - 1, cursorDay));
                daySelectionListener.onDaySelected(cursorDay);
            }
            return true;
        }

        void syncPreviewFromController() {
            int selected = -1;
            String key = forecastPreview.selectionKey();
            if (key != null && key.startsWith("day:")) {
                try { selected = Integer.parseInt(key.substring(4)); }
                catch (Exception ignored) { selected = -1; }
            }
            previewDay = selected;
            if (Build.VERSION.SDK_INT >= 30) {
                setStateDescription(previewDay >= 0 ? "Previewing" : null);
            }
            invalidate();
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            int count = days == null ? 0 : Math.min(10, days.length());
            if (count <= 0) return super.onKeyDown(keyCode, event);
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                cursorDay = Math.max(0, cursorDay - 1);
                announceCursorDay();
                invalidate();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                cursorDay = Math.min(count - 1, cursorDay + 1);
                announceCursorDay();
                invalidate();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_SPACE) {
                return performClick();
            }
            return super.onKeyDown(keyCode, event);
        }

        private void announceCursorDay() {
            JSONObject day = days == null ? null : days.optJSONObject(cursorDay);
            String label = cursorDay == 0 ? "Today" : dayLabel(day, zone);
            String condition = sceneForForecastDay(day, cursorDay).condition;
            announceForAccessibility(label + ", " + condition
                    + ". Press select to preview and show hourly details; select again to return to now.");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (days == null || days.length() == 0) {
                textPaint.setColor(SOFT_WHITE);
                textPaint.setTextSize(dp(14));
                textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("Daily forecast unavailable", getWidth() / 2f, getHeight() / 2f, textPaint);
                return;
            }

            int n = Math.min(10, days.length());
            float width = getWidth();
            float height = getHeight();
            float cell = width / Math.max(1, n);

            if (previewDay >= 0 && previewDay < n) {
                dotPaint.setStyle(Paint.Style.FILL);
                dotPaint.setColor(Color.argb(25, 255, 255, 255));
                float left = previewDay * cell + dp(3);
                float right = (previewDay + 1) * cell - dp(3);
                canvas.drawRoundRect(new RectF(left, dp(3), right, height - dp(3)),
                        dp(16), dp(16), dotPaint);
            }
            if (hasFocus() && cursorDay >= 0 && cursorDay < n) {
                dotPaint.setStyle(Paint.Style.FILL);
                dotPaint.setColor(Color.argb(14, 255, 255, 255));
                float left = cursorDay * cell + dp(6);
                float right = (cursorDay + 1) * cell - dp(6);
                canvas.drawRoundRect(new RectF(left, dp(6), right, height - dp(6)),
                        dp(14), dp(14), dotPaint);
            }

            Integer[] highs = new Integer[n];
            Integer[] lows = new Integer[n];
            int[] pops = new int[n];
            int highMin = Integer.MAX_VALUE;
            int highMax = Integer.MIN_VALUE;
            int lowMin = Integer.MAX_VALUE;
            int lowMax = Integer.MIN_VALUE;

            for (int i = 0; i < n; i++) {
                JSONObject day = days.optJSONObject(i);
                highs[i] = degreesOrNull(day == null ? null : day.optJSONObject("maxTemperature"));
                lows[i] = degreesOrNull(day == null ? null : day.optJSONObject("minTemperature"));
                pops[i] = dayProbability(day);
                if (highs[i] != null) {
                    highMin = Math.min(highMin, highs[i]);
                    highMax = Math.max(highMax, highs[i]);
                }
                if (lows[i] != null) {
                    lowMin = Math.min(lowMin, lows[i]);
                    lowMax = Math.max(lowMax, lows[i]);
                }
            }
            if (highMin == Integer.MAX_VALUE) { highMin = 0; highMax = 1; }
            if (lowMin == Integer.MAX_VALUE) { lowMin = 0; lowMax = 1; }
            if (highMin == highMax) highMax = highMin + 1;
            if (lowMin == lowMax) lowMax = lowMin + 1;

            float dayY = dp(22);
            float dateY = dp(42);
            float iconY = dp(82);
            float highTop = dp(126);
            float highBottom = dp(174);
            float lowTop = dp(205);
            float lowBottom = dp(246);
            float rainTop = Math.max(lowBottom + dp(32), height - dp(48));
            float rainBase = height - dp(7);

            float[] highX = new float[n];
            float[] highY = new float[n];
            float[] lowX = new float[n];
            float[] lowY = new float[n];

            for (int i = 0; i < n; i++) {
                JSONObject day = days.optJSONObject(i);
                float x = cell * (i + 0.5f);

                textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                textPaint.setTextAlign(Paint.Align.CENTER);
                textPaint.setColor(WHITE);
                textPaint.setTextSize(dp(13));
                canvas.drawText(i == 0 ? "Today" : dayLabel(day, zone), x, dayY, textPaint);

                textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                textPaint.setColor(SOFT_WHITE);
                textPaint.setTextSize(dp(10));
                canvas.drawText(dayDateLabel(day, zone), x, dateY, textPaint);

                JSONObject daytime = day == null ? null : day.optJSONObject("daytimeForecast");
                drawWeatherGlyph(canvas, description(daytime), true, x, iconY, dp(34));

                highX[i] = lowX[i] = x;
                highY[i] = mapTemp(highs[i], highMin, highMax, highTop, highBottom);
                lowY[i] = mapTemp(lows[i], lowMin, lowMax, lowTop, lowBottom);
            }

            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(dp(2.1f));
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setStrokeJoin(Paint.Join.ROUND);
            linePaint.setColor(Color.argb(205, 242, 246, 255));
            drawSmoothLine(canvas, highX, highY, highs, linePaint);
            linePaint.setColor(Color.argb(155, 207, 224, 248));
            drawSmoothLine(canvas, lowX, lowY, lows, linePaint);

            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(dp(16));
            textPaint.setColor(WHITE);
            dotPaint.setStyle(Paint.Style.FILL);
            dotPaint.setColor(WHITE);

            for (int i = 0; i < n; i++) {
                if (highs[i] != null) {
                    canvas.drawCircle(highX[i], highY[i], dp(3.2f), dotPaint);
                    canvas.drawText(highs[i] + "°", highX[i], highY[i] - dp(10), textPaint);
                }
                if (lows[i] != null) {
                    canvas.drawCircle(lowX[i], lowY[i], dp(3.2f), dotPaint);
                    canvas.drawText(lows[i] + "°", lowX[i], lowY[i] + dp(22), textPaint);
                }
            }

            // Compact precipitation band: actual daily probability values only, no synthesized data.
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setTextSize(dp(10));
            textPaint.setColor(ACCENT_BLUE);
            dotPaint.setColor(Color.argb(165, 176, 226, 255));
            float maxBarHeight = Math.max(dp(10), rainBase - rainTop - dp(12));
            float barWidth = Math.min(dp(22), cell * 0.30f);
            for (int i = 0; i < n; i++) {
                float x = cell * (i + 0.5f);
                int p = pops[i];
                String label = p < 0 ? "—" : p + "%";
                canvas.drawText(label, x, rainTop + dp(9), textPaint);
                if (p >= 0) {
                    float h = p == 0 ? dp(2) : Math.max(dp(3), maxBarHeight * (p / 100f));
                    RectF bar = new RectF(x - barWidth / 2f, rainBase - h, x + barWidth / 2f, rainBase);
                    canvas.drawRoundRect(bar, dp(4), dp(4), dotPaint);
                }
            }
        }

        private float mapTemp(Integer value, int min, int max, float top, float bottom) {
            if (value == null) return (top + bottom) / 2f;
            float normalized = (value - min) / (float) (max - min);
            return bottom - normalized * (bottom - top);
        }

        private void drawSmoothLine(Canvas canvas, float[] xs, float[] ys, Integer[] values, Paint paint) {
            Path path = new Path();
            boolean drawing = false;
            int previous = -1;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null) {
                    if (drawing) canvas.drawPath(path, paint);
                    path.reset();
                    drawing = false;
                    previous = -1;
                    continue;
                }
                if (!drawing) {
                    path.moveTo(xs[i], ys[i]);
                    drawing = true;
                } else {
                    float mid = (xs[previous] + xs[i]) / 2f;
                    path.cubicTo(mid, ys[previous], mid, ys[i], xs[i], ys[i]);
                }
                previous = i;
            }
            if (drawing) canvas.drawPath(path, paint);
        }

        private String chartAccessibilityDescription(JSONArray values, ZoneId zone) {
            if (values == null || values.length() == 0) return "Daily forecast unavailable";
            StringBuilder out = new StringBuilder("10-day high, low, and precipitation forecast. ");
            int count = Math.min(10, values.length());
            for (int i = 0; i < count; i++) {
                JSONObject day = values.optJSONObject(i);
                Integer high = degreesOrNull(day == null ? null : day.optJSONObject("maxTemperature"));
                Integer low = degreesOrNull(day == null ? null : day.optJSONObject("minTemperature"));
                int p = dayProbability(day);
                if (i > 0) out.append("; ");
                out.append(i == 0 ? "Today" : dayLabel(day, zone));
                String condition = sceneForForecastDay(day, i).condition;
                if (!"Unknown".equals(condition)) out.append(", ").append(condition);
                if (high != null) {
                    out.append(", high ").append(high)
                            .append(" degrees ").append(temperatureUnitWord());
                }
                if (low != null) {
                    out.append(", low ").append(low)
                            .append(" degrees ").append(temperatureUnitWord());
                }
                if (p >= 0) out.append(", precipitation ").append(p).append(" percent");
            }
            out.append(". Use left and right to move the day cursor, then press select for hourly details and preview.");
            return out.toString();
        }
    }

    private Bitmap trackMarkerBitmap(boolean moon) {
        Bitmap cached = moon ? moonTrackMarkerBitmap : sunTrackMarkerBitmap;
        if (cached != null && !cached.isRecycled()) return cached;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeResource(
                getResources(),
                moon ? R.drawable.moon_track_marker : R.drawable.sun_track_marker,
                options);
        if (moon) moonTrackMarkerBitmap = decoded;
        else sunTrackMarkerBitmap = decoded;
        return decoded;
    }

    private void releaseTrackMarkerBitmaps() {
        if (sunTrackMarkerBitmap != null && !sunTrackMarkerBitmap.isRecycled()) {
            sunTrackMarkerBitmap.recycle();
        }
        if (moonTrackMarkerBitmap != null && !moonTrackMarkerBitmap.isRecycled()) {
            moonTrackMarkerBitmap.recycle();
        }
        sunTrackMarkerBitmap = null;
        moonTrackMarkerBitmap = null;
    }

    private final class SunTrackView extends View {
        private final Instant intervalStart;
        private final Instant intervalEnd;
        private final boolean moon;
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint markerPaint = new Paint(
                Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final RectF markerBounds = new RectF();
        private final Rect markerSource = new Rect();
        private final Bitmap markerBitmap;
        private Instant baseCurrent;
        private long baseUptime;
        private Instant frozenCurrent;
        private boolean animationRunning;

        SunTrackView(
                Context context,
                Instant intervalStart,
                Instant intervalEnd,
                Instant current,
                boolean moon,
                boolean animationRunning) {
            super(context);
            this.intervalStart = intervalStart;
            this.intervalEnd = intervalEnd;
            this.moon = moon;
            this.baseCurrent = current == null ? Instant.now() : current;
            this.frozenCurrent = this.baseCurrent;
            this.baseUptime = SystemClock.uptimeMillis();
            this.animationRunning = animationRunning;
            this.markerBitmap = trackMarkerBitmap(moon);
            if (moon) {
                markerSource.set(35, 29, 209, 238);
            } else {
                markerSource.set(49, 43, 225, 214);
            }
            markerPaint.setFilterBitmap(true);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setAnimationRunning(boolean running) {
            if (animationRunning == running) {
                if (running) postInvalidateOnAnimation();
                return;
            }
            if (!running) {
                frozenCurrent = displayCurrent();
                animationRunning = false;
                invalidate();
                return;
            }
            animationRunning = true;
            baseCurrent = Instant.now();
            frozenCurrent = baseCurrent;
            baseUptime = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }

        private Instant displayCurrent() {
            if (!animationRunning) return frozenCurrent;
            long elapsed = Math.max(0L, SystemClock.uptimeMillis() - baseUptime);
            try {
                return baseCurrent.plusMillis(elapsed);
            } catch (Exception ignored) {
                return baseCurrent;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (animationRunning && !animationsAllowed()) {
                frozenCurrent = displayCurrent();
                animationRunning = false;
            }

            float markerMaxSize = dp(22);
            float sourceAspect = markerSource.height() <= 0
                    ? 1f : markerSource.width() / (float) markerSource.height();
            float markerWidth = sourceAspect >= 1f ? markerMaxSize : markerMaxSize * sourceAspect;
            float markerHeight = sourceAspect >= 1f ? markerMaxSize / sourceAspect : markerMaxSize;
            float endpointInset = Math.max(dp(18), markerWidth * 0.5f + dp(3));
            float left = endpointInset;
            float right = Math.max(left, getWidth() - endpointInset);
            float y = getHeight() * 0.59f;

            trackPaint.setShader(null);
            trackPaint.setStyle(Paint.Style.STROKE);
            trackPaint.setStrokeCap(Paint.Cap.ROUND);
            trackPaint.setStrokeWidth(dp(3.5f));
            trackPaint.setColor(Color.argb(72, 255, 255, 255));
            canvas.drawLine(left, y, right, y, trackPaint);

            float fraction = 0.5f;
            Instant now = displayCurrent();
            if (intervalStart != null && intervalEnd != null && intervalEnd.isAfter(intervalStart)) {
                double total = Duration.between(intervalStart, intervalEnd).toMillis();
                double passed = Duration.between(intervalStart, now).toMillis();
                fraction = (float) Math.max(0d, Math.min(1d, passed / total));
            }

            float x = left + (right - left) * fraction;
            trackPaint.setColor(moon
                    ? Color.argb(176, 205, 226, 255)
                    : Color.argb(210, 255, 220, 116));
            trackPaint.setStrokeWidth(dp(4.5f));
            canvas.drawLine(left, y, x, y, trackPaint);

            Bitmap marker = markerBitmap;
            if (marker != null && !marker.isRecycled()) {
                markerBounds.set(
                        x - markerWidth * 0.5f,
                        y - markerHeight * 0.5f,
                        x + markerWidth * 0.5f,
                        y + markerHeight * 0.5f);
                markerPaint.setAlpha(255);
                canvas.drawBitmap(marker, markerSource, markerBounds, markerPaint);
            }

            if (animationRunning) postInvalidateDelayed(50L);
        }
    }

    private final class MoonPhaseView extends View {
        private final String phase;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint imagePaint = new Paint(
                Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Path discPath = new Path();
        private final Path phasePath = new Path();
        private final Path phaseCutoutPath = new Path();
        private final Rect crescentSource = new Rect(35, 29, 209, 238);
        private final RectF crescentBounds = new RectF();
        private final Bitmap crescentBitmap;

        MoonPhaseView(Context context, String phase) {
            super(context);
            this.phase = phase == null ? "" : phase;
            this.crescentBitmap = trackMarkerBitmap(true);
            imagePaint.setFilterBitmap(true);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float s = Math.min(getWidth(), getHeight());
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = s * 0.34f;
            String lower = phase.toLowerCase(Locale.ROOT);
            boolean waxing = lower.contains("waxing");
            boolean waning = lower.contains("waning");
            boolean newMoon = lower.contains("new");
            boolean fullMoon = lower.contains("full") || (!lower.contains("crescent")
                    && !lower.contains("quarter") && !lower.contains("gibbous") && !newMoon);

            if (lower.contains("crescent")
                    && crescentBitmap != null
                    && !crescentBitmap.isRecycled()) {
                float markerHeight = s * 0.72f;
                float markerWidth = markerHeight
                        * crescentSource.width() / (float) crescentSource.height();
                crescentBounds.set(
                        cx - markerWidth * 0.5f,
                        cy - markerHeight * 0.5f,
                        cx + markerWidth * 0.5f,
                        cy + markerHeight * 0.5f);
                int save = canvas.save();
                if (waxing) canvas.scale(-1f, 1f, cx, cy);
                canvas.drawBitmap(crescentBitmap, crescentSource, crescentBounds, imagePaint);
                canvas.restoreToCount(save);
                return;
            }

            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(
                    cx,
                    cy,
                    r * 1.40f,
                    new int[]{
                            Color.argb(42, 198, 224, 255),
                            Color.argb(16, 178, 210, 248),
                            Color.TRANSPARENT
                    },
                    new float[]{0f, 0.62f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r * 1.40f, p);

            p.setShader(null);
            p.setColor(Color.argb(190, 51, 82, 128));
            canvas.drawCircle(cx, cy, r, p);

            discPath.reset();
            discPath.addCircle(cx, cy, r, Path.Direction.CW);
            if (!newMoon) {
                p.setShader(new LinearGradient(
                        cx - r,
                        cy - r,
                        cx + r,
                        cy + r,
                        new int[]{
                                Color.rgb(247, 250, 255),
                                Color.rgb(220, 233, 250),
                                Color.rgb(184, 207, 236)
                        },
                        null,
                        Shader.TileMode.CLAMP));
                p.setStyle(Paint.Style.FILL);

                if (fullMoon) {
                    canvas.drawCircle(cx, cy, r, p);
                } else if (lower.contains("crescent")) {
                    phasePath.reset();
                    phasePath.addCircle(cx, cy, r, Path.Direction.CW);
                    float cutDirection = waning ? 1f : -1f;
                    phaseCutoutPath.reset();
                    phaseCutoutPath.addCircle(
                            cx + cutDirection * r * 0.52f,
                            cy,
                            r * 0.94f,
                            Path.Direction.CW);
                    phasePath.op(phaseCutoutPath, Path.Op.DIFFERENCE);
                    canvas.drawPath(phasePath, p);
                } else if (lower.contains("quarter")) {
                    int save = canvas.save();
                    canvas.clipPath(discPath);
                    boolean lightRight = !waning;
                    if (lightRight) {
                        canvas.drawRect(cx, cy - r, cx + r, cy + r, p);
                    } else {
                        canvas.drawRect(cx - r, cy - r, cx, cy + r, p);
                    }
                    canvas.restoreToCount(save);
                } else if (lower.contains("gibbous")) {
                    canvas.drawCircle(cx, cy, r, p);
                    p.setShader(null);
                    p.setColor(Color.argb(205, 50, 81, 126));
                    phasePath.reset();
                    phasePath.addCircle(cx, cy, r, Path.Direction.CW);
                    float shadowDirection = waxing ? -1f : 1f;
                    phaseCutoutPath.reset();
                    phaseCutoutPath.addCircle(
                            cx + shadowDirection * r * 0.58f,
                            cy,
                            r * 0.92f,
                            Path.Direction.CW);
                    phasePath.op(phaseCutoutPath, Path.Op.DIFFERENCE);
                    canvas.drawPath(phasePath, p);
                }
            }

            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(Color.argb(95, 226, 239, 255));
            p.setStrokeWidth(dp(1f));
            canvas.drawCircle(cx, cy, r, p);

            if (!newMoon) {
                int save = canvas.save();
                canvas.clipPath(discPath);
                p.setStyle(Paint.Style.STROKE);
                p.setColor(Color.argb(55, 78, 112, 160));
                p.setStrokeWidth(dp(1f));
                canvas.drawCircle(cx - r * 0.25f, cy - r * 0.14f, r * 0.17f, p);
                canvas.drawCircle(cx + r * 0.18f, cy + r * 0.20f, r * 0.12f, p);
                canvas.drawCircle(cx + r * 0.20f, cy - r * 0.28f, r * 0.085f, p);
                canvas.restoreToCount(save);
            }
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
