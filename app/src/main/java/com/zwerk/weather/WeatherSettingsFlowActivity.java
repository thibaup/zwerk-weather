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


abstract class WeatherSettingsFlowActivity extends WeatherOverviewRenderingActivity {
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
            refreshWeather(true);
        } else if (SettingsActivity.ACTION_REFRESH.equals(action)) {
            refreshWeather(true, precipitationMode);
        } else if (SettingsActivity.ACTION_PREFERENCES_CHANGED.equals(action)) {
            if (unitChanged || displayUnitChanged) rerenderLastWeather();
        } else if (SettingsActivity.ACTION_DEVICE_LOCATION.equals(action)) {
            selectDeviceLocationAndRefresh();
        } else if (SettingsActivity.ACTION_ADVANCED_COORDINATES.equals(action)) {
            if (unitChanged || displayUnitChanged) rerenderLastWeather();
            showCoordinateDialog();
        } else if (SettingsActivity.ACTION_SELECTED_CITY.equals(action)) {
            applySelectedLocationResult(data);
        } else if (unitChanged || displayUnitChanged || airQualityChanged || pollenChanged) {
            if (unitChanged || displayUnitChanged) rerenderLastWeather();
        }
    }

    void rerenderLastWeather() {
        if (lastCurrentWeather == null || lastDailyWeather == null) return;
        activeTemperatureUnit = temperatureUnitPreference();
        final int scrollY = mainScroll == null ? 0 : mainScroll.getScrollY();
        render(lastCurrentWeather, lastHourlyWeather, lastDailyWeather, activeTemperatureUnit);
        if (mainScroll != null) {
            mainScroll.post(() -> mainScroll.scrollTo(0, scrollY));
        }
    }

    void applySelectedLocationResult(Intent data) {
        double lat = data.getDoubleExtra(CityManagerActivity.EXTRA_LATITUDE, Double.NaN);
        double lon = data.getDoubleExtra(CityManagerActivity.EXTRA_LONGITUDE, Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon)) return;

        if (locationRefreshCoordinator != null) locationRefreshCoordinator.onSelectionChanged();
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
        refreshWeather();
    }


    void persistSettingsSceneSnapshot() {
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







    private WeatherWidgetSnapshotPublisher widgetSnapshotPublisher;

    void persistWidgetSnapshot(JSONObject current, JSONObject today, JSONObject hourly, ZoneId zone) {
        if (widgetSnapshotPublisher == null) {
            widgetSnapshotPublisher = new WeatherWidgetSnapshotPublisher(this, new WeatherWidgetSnapshotPublisher.Formatter() {
                public Integer degreesOrNull(JSONObject value) { return WeatherSettingsFlowActivity.this.degreesOrNull(value); }
                public String description(JSONObject weather) { return WeatherSettingsFlowActivity.description(weather); }
                public boolean safeBoolean(JSONObject object, String key, boolean fallback) { return WeatherSettingsFlowActivity.safeBoolean(object, key, fallback); }
                public Instant parseInstant(String value) { return WeatherSettingsFlowActivity.parseInstant(value); }
                public String formatTime(Instant instant, ZoneId zone) { return WeatherSettingsFlowActivity.formatTime(instant, zone); }
                public String hourLabel(JSONObject hour, ZoneId zone) { return WeatherSettingsFlowActivity.hourLabel(hour, zone); }
                public int probability(JSONObject weather) { return WeatherSettingsFlowActivity.probability(weather); }
                public String formatWindSpeed(JSONObject speed) { return WeatherSettingsFlowActivity.this.formatWindSpeed(speed); }
                public String formatPressure(JSONObject pressure) { return WeatherSettingsFlowActivity.this.formatPressure(pressure); }
                public String formatVisibility(JSONObject visibility) { return WeatherSettingsFlowActivity.this.formatVisibility(visibility); }
                public String temperatureUnitSymbol() { return WeatherSettingsFlowActivity.this.temperatureUnitSymbol(); }
                public String conditionKey(String condition) { return WeatherSettingsFlowActivity.conditionKey(condition); }
            });
        }
        widgetSnapshotPublisher.publish(current, today, hourly, zone, locationName);
    }

}
