package com.zwerk.weather;

import android.content.Intent;


import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;


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
        boolean severeAlertsChanged = data.getBooleanExtra(
                SettingsActivity.EXTRA_SEVERE_ALERTS_CHANGED, false);
        boolean weatherDetailsChanged = data.getBooleanExtra(
                SettingsActivity.EXTRA_WEATHER_DETAILS_CHANGED, false);
        boolean forecastPagesChanged = data.getBooleanExtra(
                SettingsActivity.EXTRA_FORECAST_PAGES_CHANGED, false);
        boolean providerChanged = data.getBooleanExtra(
                SettingsActivity.EXTRA_PROVIDER_CHANGED, false);
        boolean precipitationProviderChanged = data.getBooleanExtra(
                SettingsActivity.EXTRA_PRECIPITATION_PROVIDER_CHANGED, false);
        if (unitChanged || displayUnitChanged || airQualityChanged || pollenChanged
                || severeAlertsChanged || weatherDetailsChanged || forecastPagesChanged
                || providerChanged || precipitationProviderChanged) {
            suppressNextResumeWeatherLoad = true;
        }
        boolean actionReloadsBaseWeather = SettingsActivity.ACTION_API_KEY_CHANGED.equals(action)
                || providerChanged
                || SettingsActivity.ACTION_REFRESH.equals(action)
                || SettingsActivity.ACTION_DEVICE_LOCATION.equals(action)
                || SettingsActivity.ACTION_SELECTED_CITY.equals(action);
        if (airQualityChanged || pollenChanged || severeAlertsChanged) {
            // Clear changed optional state immediately. For actions that already reload base weather,
            // defer optional requests until the new weather generation succeeds; otherwise request
            // only the optional datasets whose switches actually changed.
            applyOptionalPreferenceChanges(
                    airQualityChanged, pollenChanged, severeAlertsChanged,
                    !actionReloadsBaseWeather);
        }
        if (forecastPagesChanged && this instanceof MainActivity) {
            ((MainActivity) this).applyForecastPagePreferences();
        }
        if (providerChanged) {
            invalidateMinuteForecastState();
            hourlyPageState = null;
            if (OpenMeteoConfig.GOOGLE.equals(OpenMeteoConfig.provider(this))
                    && this instanceof MainActivity
                    && !((MainActivity) this).hasConfiguredApiKey()) {
                ((MainActivity) this).showApiKeySetupDialog();
            } else {
                refreshWeather(true);
            }
        } else if (SettingsActivity.ACTION_API_KEY_CHANGED.equals(action)) {
            refreshWeather(true);
        } else if (SettingsActivity.ACTION_REFRESH.equals(action)) {
            refreshWeather(true, precipitationMode);
        } else if (SettingsActivity.ACTION_PREFERENCES_CHANGED.equals(action)) {
            if (unitChanged || displayUnitChanged || weatherDetailsChanged) rerenderLastWeather();
            if (precipitationProviderChanged) {
                invalidateMinuteForecastState();
                if (OpenMeteoConfig.GOOGLE.equals(
                        OpenMeteoConfig.precipitationProvider(this))
                        && this instanceof MainActivity
                        && !((MainActivity) this).hasConfiguredApiKey()) {
                    ((MainActivity) this).showApiKeySetupDialog();
                } else if (precipitationMode) {
                    ensureMinuteForecast(true);
                }
            }
        } else if (SettingsActivity.ACTION_DEVICE_LOCATION.equals(action)) {
            selectDeviceLocationAndRefresh();
        } else if (SettingsActivity.ACTION_ADVANCED_COORDINATES.equals(action)) {
            if (unitChanged || displayUnitChanged || weatherDetailsChanged) rerenderLastWeather();
            showCoordinateDialog();
        } else if (SettingsActivity.ACTION_SELECTED_CITY.equals(action)) {
            applySelectedLocationResult(data);
        } else if (unitChanged || displayUnitChanged || airQualityChanged || pollenChanged
                || severeAlertsChanged || weatherDetailsChanged) {
            if (unitChanged || displayUnitChanged || weatherDetailsChanged) rerenderLastWeather();
        } else if (precipitationProviderChanged) {
            invalidateMinuteForecastState();
            if (precipitationMode) ensureMinuteForecast(true);
        }
    }

    void rerenderLastWeather() {
        if (lastCurrentWeather == null || lastDailyWeather == null) return;
        OpenMeteoForecastClient.pruneElapsedHourly(lastHourlyWeather, Instant.now());
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
        locationTitle.setText(lastCurrentWeather == null ? locationName : forecastLocationName());
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
        onForecastLocationChanged(lat, lon, locationName);
        refreshWeather();
    }

    /** Lets forecast-page hosts update map-backed views when a saved city is selected. */
    void onForecastLocationChanged(double lat, double lon, String name) { }


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
        widgetSnapshotPublisher.publish(current, today, hourly, lastDailyWeather, zone,
                forecastLocationName(), latitude, longitude);
    }

}
