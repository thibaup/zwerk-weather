package com.zwerk.weather;

import android.content.Context;

/** Reuses the translated Settings resources for identical labels elsewhere in the app. */
final class UiResourceFallback {
    private UiResourceFallback() { }

    static String text(Context context, String english) {
        switch (english) {
            case "System default":
                return context.getString(R.string.language_system_default);
            case "English":
                return context.getString(R.string.language_name_en);
            case "Nederlands":
                return context.getString(R.string.language_name_nl);
            case "Français":
                return context.getString(R.string.language_name_fr);
            case "Deutsch":
                return context.getString(R.string.language_name_de);
            case "Español":
                return context.getString(R.string.language_name_es);
            case "Italiano":
                return context.getString(R.string.language_name_it);
            case "Português (Portugal)":
                return context.getString(R.string.language_name_pt_pt);
            case "Português (Brasil)":
                return context.getString(R.string.language_name_pt_br);
            case "Polski":
                return context.getString(R.string.language_name_pl);
            case "Русский":
                return context.getString(R.string.language_name_ru);
            case "Українська":
                return context.getString(R.string.language_name_uk);
            case "Türkçe":
                return context.getString(R.string.language_name_tr);
            case "Svenska":
                return context.getString(R.string.language_name_sv);
            case "Dansk":
                return context.getString(R.string.language_name_da);
            case "Norsk bokmål":
                return context.getString(R.string.language_name_nb);
            case "Suomi":
                return context.getString(R.string.language_name_fi);
            case "Čeština":
                return context.getString(R.string.language_name_cs);
            case "Slovenčina":
                return context.getString(R.string.language_name_sk);
            case "Magyar":
                return context.getString(R.string.language_name_hu);
            case "Română":
                return context.getString(R.string.language_name_ro);
            case "Ελληνικά":
                return context.getString(R.string.language_name_el);
            case "Български":
                return context.getString(R.string.language_name_bg);
            case "Hrvatski":
                return context.getString(R.string.language_name_hr);
            case "Back":
                return context.getString(R.string.settings_back);
            case "Settings":
                return context.getString(R.string.settings_title);
            case "Zwerk Weather":
                return context.getString(R.string.settings_section_app);
            case "App language":
                return context.getString(R.string.settings_app_language);
            case "Weather source":
                return context.getString(R.string.settings_weather_source);
            case "Google Weather":
                return context.getString(R.string.settings_source_google_weather);
            case "Open-Meteo":
                return context.getString(R.string.settings_source_open_meteo);
            case "Open-Meteo forecast model":
                return context.getString(R.string.settings_open_meteo_model);
            case "Open-Meteo customer key":
                return context.getString(R.string.settings_open_meteo_customer_key);
            case "Configured":
                return context.getString(R.string.settings_configured);
            case "Optional for paid plans":
                return context.getString(R.string.settings_optional_paid_plans);
            case "Google API key":
                return context.getString(R.string.settings_google_api_key);
            case "Configured · tap to replace":
                return context.getString(R.string.settings_configured_tap_replace);
            case "Not configured · tap to add":
                return context.getString(R.string.settings_not_configured_tap_add);
            case "API request limits":
                return context.getString(R.string.settings_api_request_limits);
            case "Google · Open-Meteo · radar":
                return context.getString(R.string.settings_api_request_limits_summary);
            case "Refresh forecast":
                return context.getString(R.string.settings_refresh_forecast);
            case "Check for updates":
                return context.getString(R.string.settings_check_updates);
            case "Checks GitHub Releases for a newer APK.":
                return context.getString(R.string.settings_check_updates_summary);
            case "Alerts":
                return context.getString(R.string.settings_alerts);
            case "Rain alerts":
                return context.getString(R.string.settings_rain_alerts);
            case "Severe weather alerts":
                return context.getString(R.string.settings_severe_weather_alerts);
            case "Enable alert notifications":
                return context.getString(R.string.settings_enable_alert_notifications);
            case "Optional Open-Meteo data":
                return context.getString(R.string.settings_optional_open_meteo_data);
            case "Optional Google data":
                return context.getString(R.string.settings_optional_google_data);
            case "Air Quality":
                return context.getString(R.string.settings_air_quality);
            case "Pollen":
                return context.getString(R.string.settings_pollen);
            case "Display":
                return context.getString(R.string.settings_display);
            case "Precipitation page":
                return context.getString(R.string.settings_precipitation_page);
            case "Show the minute-by-minute rain view in the forecast tabs.":
                return context.getString(R.string.settings_precipitation_page_summary);
            case "Radar page":
                return context.getString(R.string.settings_radar_page);
            case "Show the radar map in the forecast tabs.":
                return context.getString(R.string.settings_radar_page_summary);
            case "Weather details":
                return context.getString(R.string.settings_weather_details);
            case "Choose the measurements shown on the overview.":
                return context.getString(R.string.settings_weather_details_summary);
            case "Units":
                return context.getString(R.string.settings_units_title);
            case "Temperature, wind, pressure, and visibility":
                return context.getString(R.string.settings_units_summary);
            case "Weather animations":
                return context.getString(R.string.settings_weather_animations);
            case "Preview weather scenes":
                return context.getString(R.string.settings_preview_weather_scenes);
            case "Measurement units":
                return context.getString(R.string.settings_measurement_units);
            case "Temperature unit":
                return context.getString(R.string.settings_temperature_unit);
            case "Celsius":
                return context.getString(R.string.settings_celsius);
            case "°C  Celsius":
                return context.getString(R.string.settings_celsius_option);
            case "Fahrenheit":
                return context.getString(R.string.settings_fahrenheit);
            case "°F  Fahrenheit":
                return context.getString(R.string.settings_fahrenheit_option);
            case "Wind speed":
                return context.getString(R.string.settings_wind_speed);
            case "knots":
                return context.getString(R.string.settings_knots);
            case "Air pressure":
                return context.getString(R.string.settings_air_pressure);
            case "Visibility":
                return context.getString(R.string.settings_visibility);
            case "Cancel":
                return context.getString(R.string.settings_cancel);
            case "selected":
                return context.getString(R.string.settings_selected);
            case "not selected":
                return context.getString(R.string.settings_not_selected);
            case "On":
                return context.getString(R.string.settings_on);
            case "Off":
                return context.getString(R.string.settings_off);
            default:
                return english;
        }
    }
}
