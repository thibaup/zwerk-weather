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


abstract class WeatherActivityFoundation extends Activity {
    abstract void addHero(JSONObject current, JSONObject today);
    abstract void addHourlyCard(JSONObject hourly, ZoneId zone);
    abstract void addMultiDayCard(JSONArray days, JSONObject hourly, ZoneId zone);
    abstract void addDetailTiles(JSONObject current);
    abstract void addSunCard(JSONArray days, JSONObject current, ZoneId zone);
    abstract void addMoonCard(JSONObject today, ZoneId zone);
    abstract void updateDailyModeButton(TextView button, boolean selected, String label);
    abstract View card(View child, int radiusPx, int color);
    abstract GradientDrawable roundedBg(int color, int radiusPx);
    abstract void persistWidgetSnapshot(
            JSONObject current,
            JSONObject today,
            JSONObject hourly,
            ZoneId zone);

    static final int LOCATION_REQUEST = 40;
    static final int CITY_MANAGER_REQUEST = 41;
    static final int SETTINGS_REQUEST = 42;
    static final String UI_PREFS = "WEATHER_UI";
    static final String PREF_DAILY_MODE = "daily_mode";
    static final String PREF_ANIMATIONS = "weather_animations";
    static final String PREF_TEMPERATURE_UNIT = "temperature_unit";
    static final String PREF_WIND_UNIT = "wind_unit";
    static final String PREF_PRESSURE_UNIT = "pressure_unit";
    static final String PREF_VISIBILITY_UNIT = "visibility_unit";
    static final String PREF_AIR_QUALITY = "google_air_quality_enabled";
    static final String PREF_POLLEN = "google_pollen_enabled";
    static final String PREF_EXPLICIT_NON_DEVICE_LOCATION = "explicit_non_device_location";
    static final String PREF_SETTINGS_SCENE = "settings_scene";
    static final String PREF_SETTINGS_DAYTIME = "settings_daytime";
    static final String PREF_SETTINGS_CARD_TOP = "settings_card_top";
    static final String PREF_SETTINGS_CARD_BOTTOM = "settings_card_bottom";
    static final String PREF_SETTINGS_TILE_TOP = "settings_tile_top";
    static final String PREF_SETTINGS_TILE_BOTTOM = "settings_tile_bottom";
    static final String PREF_SETTINGS_ACCENT = "settings_accent";
    static final String TEMP_CELSIUS = "C";
    static final String TEMP_FAHRENHEIT = "F";
    static final String WIND_KMH = "km/h";
    static final String WIND_MPH = "mph";
    static final String WIND_MS = "m/s";
    static final String WIND_KNOTS = "knots";
    static final String PRESSURE_HPA = "hPa";
    static final String PRESSURE_INHG = "inHg";
    static final String PRESSURE_MMHG = "mmHg";
    static final String VISIBILITY_KM = "km";
    static final String VISIBILITY_MI = "mi";

    static final String API_ROOT = "https://weather.googleapis.com/v1/";
    static final String AIR_QUALITY_ENDPOINT = "https://airquality.googleapis.com/v1/currentConditions:lookup";
    static final String POLLEN_ENDPOINT = "https://pollen.googleapis.com/v1/forecast:lookup";
    static final String API_KEY_GUIDE_URL =
            "https://developers.google.com/maps/documentation/weather/get-api-key";
    static final String GOOGLE_CLOUD_CREDENTIALS_URL =
            "https://console.cloud.google.com/apis/credentials";
    static final String AIR_QUALITY_GUIDE_URL =
            "https://developers.google.com/maps/documentation/air-quality/get-api-key";
    static final String POLLEN_GUIDE_URL =
            "https://developers.google.com/maps/documentation/pollen/get-api-key";
    static final String API_KEY_FILE = "weather_api_key";
    static final String API_KEY_TEMP_FILE = "weather_api_key.tmp";
    static final String WEATHER_CACHE_V1_PREFIX = "weather_forecast_cache_v1_";
    static final String WEATHER_CACHE_FILE_PREFIX = "weather_forecast_cache_v2_";
    static final String WEATHER_CACHE_FILE_SUFFIX = ".json";
    static final long WEATHER_CACHE_MAX_AGE_MILLIS = 60L * 60L * 1000L;
    static final String OPTIONAL_CACHE_FILE_SUFFIX = ".json";
    static final String AIR_QUALITY_CACHE_ROOT_PREFIX = "google_air_quality_cache_";
    static final String POLLEN_CACHE_ROOT_PREFIX = "google_pollen_cache_";
    static final String AIR_QUALITY_CACHE_FILE_PREFIX = "google_air_quality_cache_v1_";
    static final String POLLEN_CACHE_FILE_PREFIX = "google_pollen_cache_v1_";
    static final long AIR_QUALITY_CACHE_MAX_AGE_MILLIS = 60L * 60L * 1000L;
    static final long POLLEN_CACHE_MAX_AGE_MILLIS = 6L * 60L * 60L * 1000L;
    static final String MINUTE_CACHE_FILE_PREFIX = "weather_minute_cache_v1_";
    static final String MINUTE_CACHE_LEGACY_PREFIX = "weather_minute_cache_";
    static final String MINUTE_CACHE_FILE_SUFFIX = ".json";
    static final long MINUTE_CACHE_MAX_AGE_MILLIS = 60L * 60L * 1000L;
    static final int MINUTE_PAGE_SIZE = 180;
    static final int MINUTE_RANGE_TWO_HOURS = 2;
    static final int MINUTE_RANGE_SIX_HOURS = 6;
    static final long MINUTE_SELECTION_STEP_MILLIS = 2L * 60L * 1000L;
    static final double PRECIP_LIGHT_MAX_MM_H = 2.5d;
    static final double PRECIP_MODERATE_MAX_MM_H = 7.5d;
    static final double PRECIP_VISUAL_CAP_MM_H = 15d;
    static final double WEATHER_CACHE_COORDINATE_TOLERANCE = 0.00001d;
    static final int HOURLY_FORECAST_HOURS = 240;
    static final int HOURLY_PAGE_SIZE = 24;
    static final int TOP_HOURLY_STRIP_HOURS = 24;
    static final long SCENE_TRANSITION_MILLIS = 550L;
    static final String LOG_TAG = "ZwerkWeather";
    static final int ERROR_BODY_LIMIT_BYTES = 32768;
    static final String HOURLY_DIAGNOSTIC = "_weatherNextHourlyDiagnostic";
    static final String HOURLY_PARTIAL = "_weatherNextHourlyPartial";
    static final String HOURLY_LOAD_ERROR = "_weatherNextHourlyLoadError";
    static final String HOURLY_TOP_KEYS = "_weatherNextHourlyTopKeys";
    static final String HOURLY_FIRST_PAGE_COUNT = "_weatherNextHourlyFirstPageCount";
    static final String HOURLY_NEXT_TOKEN_PRESENT = "_weatherNextHourlyNextTokenPresent";

    static final int WHITE = Color.WHITE;
    static final int SOFT_WHITE = Color.argb(205, 255, 255, 255);
    static final int FAINT_WHITE = Color.argb(145, 255, 255, 255);
    static final int ACCENT_YELLOW = Color.rgb(255, 194, 24);
    static final int ACCENT_BLUE = Color.rgb(176, 226, 255);

    final ExecutorService executor = Executors.newSingleThreadExecutor();
    WeatherPreferences weatherPreferences;
    DeviceLocationRefreshCoordinator locationRefreshCoordinator;
    final ExecutorService optionalExecutor = Executors.newFixedThreadPool(2);

    LinearLayout content;
    ProgressBar progress;
    TextView status;
    TextView locationTitle;
    RefreshIndicatorView refreshIndicator;
    LinearLayout toolbar;
    LinearLayout locationArea;
    TextView previewSubtitle;
    HeaderScrimDrawable headerScrim;
    final ArrayList<GlassDrawable> glassDrawables = new ArrayList<>();
    int cardColor = Color.argb(92, 31, 102, 211);
    int tileColor = Color.argb(72, 31, 102, 211);
    int glassCardTop = Color.argb(94, 48, 116, 207);
    int glassCardBottom = Color.argb(56, 70, 126, 188);
    int glassTileTop = Color.argb(84, 50, 117, 202);
    int glassTileBottom = Color.argb(48, 66, 119, 178);
    int glassEdge = Color.TRANSPARENT;
    int dynamicStartIndex;
    TextView overviewModeButton;
    TextView precipitationModeButton;
    GlassDrawable modeSwitchGlass;
    boolean precipitationMode;
    int overviewScrollY;
    int precipitationScrollY;
    int minuteRangeHours = MINUTE_RANGE_TWO_HOURS;
    long minuteSelectedTimeMillis = Long.MIN_VALUE;
    View precipitationBody;
    final Object minuteForecastLock = new Object();
    MinuteForecastState minuteForecastState;
    boolean minuteReloadAfterLocationCheckPending;
    boolean weatherLoadActive;
    double activeLoadLatitude = Double.NaN;
    double activeLoadLongitude = Double.NaN;
    String activeLoadLanguage = "";
    String activeLoadLocationId = "";
    int weatherRequestGeneration;
    final Object hourlyCoverageLock = new Object();
    boolean hourlyCoverageLoadActive;
    LocalDate pendingHourlyCoverageTarget;
    LocalDate hourlyCoverageLoadingTarget;
    boolean weatherReloadPending;
    boolean weatherReloadForcePending;
    boolean forceNextWeatherLoad;
    boolean hasResumedOnce;
    boolean suppressNextResumeWeatherLoad;
    int locationSelectionGeneration;
    String activeTemperatureUnit = TEMP_CELSIUS;
    String displayedScene = "day";
    boolean displayedDaytime = true;
    JSONObject lastCurrentWeather;
    JSONObject lastHourlyWeather;
    JSONObject lastDailyWeather;
    final Object optionalDataLock = new Object();
    OptionalDataState airQualityState;
    OptionalDataState pollenState;
    long airQualityRequestSerial;
    long pollenRequestSerial;
    Dialog apiKeySetupDialog;

    double latitude = 50.8503;
    double longitude = 4.3517;
    String locationName = "Brussels";
    String selectedLocationId = "";
    boolean usingDeviceLocation;

    interface DaySelectionListener {
        void onDaySelected(int dayIndex);
    }

    interface MinuteSelectionListener {
        void onSelected(MinuteSegment segment, long selectedTimeMillis);
    }

    interface HourlyCoverageCoordinator {
        void onHourlyCoverageChanged();
    }

    final ArrayList<WeakReference<HourlyCoverageCoordinator>> hourlyCoverageCoordinators = new ArrayList<>();

    abstract void performPullRefresh();
    abstract void render(JSONObject current, JSONObject hourly, JSONObject daily, String responseUnit);
    abstract void showError(Exception error);
    abstract void requestEnabledOptionalDataForCurrentScope();
    abstract void rebindFreshMinuteStateToGeneration(int generation, double lat, double lon, String language);
    abstract void refreshWeather();
    abstract void refreshWeather(boolean forceNetwork);
    abstract void refreshWeather(boolean forceNetwork, boolean refreshMinuteForecast);
    abstract void rerenderLastWeather();
    abstract void renderOverviewContent();
    abstract void renderPrecipitationContent();
    abstract void clearDynamicContent();
    abstract void addAttribution();
    abstract TextView text(String value, int sp, boolean bold, int color);
    abstract Button button(String label);
    abstract GlassDrawable newGlassDrawable(int radiusPx, boolean tile);
    abstract TextView dailyModeButton(String label, String accessibilityLabel);
    abstract void persistSettingsSceneSnapshot();
    abstract String signingCertificateSha1();
    abstract String androidRestrictionIdentity();
    abstract void openExternalUrl(String address);
    abstract Button coordinateDialogButton(String label, boolean primary);
    abstract void selectDeviceLocationAndRefresh();
    abstract void showCoordinateDialog();

    boolean animationsAllowed() {
        if (!weatherPreferences.animationsAllowed()) return false;
        if (Build.VERSION.SDK_INT >= 26) return ValueAnimator.areAnimatorsEnabled();
        return true;
    }


    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    String temperatureUnitPreference() {
        return weatherPreferences.temperatureUnit();
    }

    static String normalizeTemperatureUnit(String value) {
        return WeatherPreferences.normalizeTemperatureUnit(value);
    }

    boolean isFahrenheitUnit() {
        return TEMP_FAHRENHEIT.equals(activeTemperatureUnit);
    }

    String temperatureUnitSymbol() {
        return isFahrenheitUnit() ? "°F" : "°C";
    }

    String temperatureUnitWord() {
        return isFahrenheitUnit() ? "Fahrenheit" : "Celsius";
    }


    Integer degreesOrNull(JSONObject value) {
        if (value == null) return null;
        Double celsius = numberValue(value, "degrees");
        if (celsius == null) celsius = numberValue(value, "value");
        if (celsius == null) return null;
        double displayed = isFahrenheitUnit() ? (celsius * 9d / 5d) + 32d : celsius;
        return (int) Math.round(displayed);
    }

    static Double numberValue(JSONObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.isNull(key)) return null;
        try {
            double value = object.optDouble(key, Double.NaN);
            return Double.isNaN(value) || Double.isInfinite(value) ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    static int probability(JSONObject weather) {
        if (weather == null) return -1;
        JSONObject precipitation = weather.optJSONObject("precipitation");
        if (precipitation == null) return -1;
        JSONObject probability = precipitation.optJSONObject("probability");
        return probability == null ? -1 : safeInt(probability, "percent", -1);
    }

    static int dayProbability(JSONObject day) {
        if (day == null) return -1;
        int daytime = probability(day.optJSONObject("daytimeForecast"));
        int nighttime = probability(day.optJSONObject("nighttimeForecast"));
        if (daytime < 0) return nighttime;
        if (nighttime < 0) return daytime;
        return Math.max(daytime, nighttime);
    }

    static LocalDate hourLocalDate(JSONObject hour, ZoneId zone) {
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

    static String description(JSONObject weather) {
        if (weather == null) return "Unknown";
        JSONObject condition = weather.optJSONObject("weatherCondition");
        if (condition == null) return "Unknown";
        JSONObject description = condition.optJSONObject("description");
        String text = description == null ? "" : description.optString("text", "").trim();
        if (!text.isEmpty()) return text;
        String type = condition.optString("type", "Unknown").trim();
        return type.isEmpty() ? "Unknown" : prettyEnum(type);
    }

    static String conditionKey(String condition) {
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

    static String hourLabel(JSONObject hour, ZoneId zone) {
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

    static String dayLabel(JSONObject day, ZoneId zone) {
        LocalDate date = displayDate(day, zone);
        if (date == null) return "—";
        return date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()));
    }

    static String dayDateLabel(JSONObject day, ZoneId zone) {
        LocalDate date = displayDate(day, zone);
        if (date == null) return "";
        return date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()));
    }

    static LocalDate displayDate(JSONObject day, ZoneId zone) {
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

    static LocalDate localDateFields(JSONObject value) {
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

    static ZoneId responseZone(JSONObject current, JSONObject hourly, JSONObject daily) {
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

    static String timeZoneId(JSONObject object) {
        if (object == null) return "";
        JSONObject timeZone = object.optJSONObject("timeZone");
        return timeZone == null ? "" : timeZone.optString("id", "");
    }

    static int safeInt(JSONObject object, String key, int fallback) {
        if (object == null || !object.has(key) || object.isNull(key)) return fallback;
        try {
            return object.optInt(key, fallback);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    static boolean safeBoolean(JSONObject object, String key, boolean fallback) {
        if (object == null || !object.has(key) || object.isNull(key)) return fallback;
        try {
            return object.optBoolean(key, fallback);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    static JSONObject firstObject(JSONArray array) {
        if (array == null || array.length() == 0) return null;
        return array.optJSONObject(0);
    }


    static Instant parseInstant(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    static Instant firstInstant(JSONArray array) {
        if (array == null || array.length() == 0) return null;
        return parseInstant(array.optString(0, null));
    }

    static String formatTime(Instant instant, ZoneId zone) {
        if (instant == null) return "—";
        try {
            return DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).withZone(zone).format(instant);
        } catch (Exception ignored) {
            return "—";
        }
    }

    static String prettyPhase(String phase) {
        if (phase == null || phase.trim().isEmpty()) return "Moon";
        return prettyEnum(phase);
    }

    static String prettyEnum(String raw) {
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

    static String trimNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.05) return String.valueOf(Math.round(value));
        return String.format(Locale.getDefault(), "%.1f", value);
    }

    static String uvHint(int uv) {
        if (uv < 0) return "";
        if (uv <= 2) return "  Very weak";
        if (uv <= 5) return "  Moderate";
        if (uv <= 7) return "  High";
        if (uv <= 10) return "  Very high";
        return "  Extreme";
    }

    static String shortCardinal(String cardinal, Double degrees) {
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

    static void appendPart(StringBuilder b, String part) {
        if (part == null || part.isEmpty()) return;
        if (b.length() > 0) b.append(" · ");
        b.append(part);
    }

    void drawWeatherGlyph(Canvas canvas, String condition, boolean daytime, float cx, float cy, float size) {
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

    static void drawSun(Canvas canvas, Paint p, float cx, float cy, float r) {
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

    static void drawMoonCrescent(Canvas canvas, Paint p, float cx, float cy, float r) {
        Path moon = new Path();
        moon.addCircle(cx, cy, r, Path.Direction.CW);
        Path shadow = new Path();
        shadow.addCircle(cx + r * 0.42f, cy - r * 0.16f, r * 0.90f, Path.Direction.CW);
        moon.op(shadow, Path.Op.DIFFERENCE);

        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(
                cx - r,
                cy - r,
                cx + r,
                cy + r,
                Color.rgb(255, 252, 225),
                Color.rgb(181, 218, 255),
                Shader.TileMode.CLAMP));
        canvas.drawPath(moon, p);

        int save = canvas.save();
        canvas.clipPath(moon);
        p.setShader(null);
        p.setColor(Color.argb(42, 88, 126, 170));
        canvas.drawCircle(cx - r * 0.28f, cy - r * 0.22f, r * 0.13f, p);
        canvas.drawCircle(cx - r * 0.17f, cy + r * 0.30f, r * 0.09f, p);
        canvas.restoreToCount(save);

        p.setShader(null);
        p.setColor(Color.argb(210, 232, 245, 255));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.4f, r * 0.07f));
        canvas.drawPath(moon, p);
    }

    static void drawCloud(Canvas canvas, Paint p, float cx, float cy, float size) {
        p.setShader(null);
        p.setColor(WHITE);
        p.setStyle(Paint.Style.FILL);
        float h = size * 0.34f;
        canvas.drawRoundRect(new RectF(cx - size * 0.46f, cy - h * 0.05f, cx + size * 0.46f, cy + h * 0.62f), h, h, p);
        canvas.drawCircle(cx - size * 0.20f, cy - h * 0.06f, h * 0.68f, p);
        canvas.drawCircle(cx + size * 0.07f, cy - h * 0.28f, h * 0.88f, p);
        canvas.drawCircle(cx + size * 0.28f, cy - h * 0.03f, h * 0.58f, p);
    }



    int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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

    static JSONObject firstJSONObject(JSONObject object, String... keys) {
        if (object == null || keys == null) return null;
        for (String key : keys) {
            JSONObject value = object.optJSONObject(key);
            if (value != null) return value;
        }
        return null;
    }

    static String firstNonEmpty(String first, String second) {
        if (first != null && !first.trim().isEmpty()) return first.trim();
        return second == null ? "" : second.trim();
    }

    static String stringValue(JSONObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.isNull(key)) return "";
        String value = object.optString(key, "").trim();
        return "null".equalsIgnoreCase(value) ? "" : value;
    }



}
