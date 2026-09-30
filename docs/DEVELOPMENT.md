# Configuration and development

See the [main README](../README.md) for features and screenshots. These notes cover provider setup, offline behavior, radar, widgets, and building the app.

## Requirements and provider setup

- Android 9 or newer (API 28+).
- Open-Meteo needs no API key for noncommercial use. Paid plans can use an optional customer key.
- Google Weather requires a Google Cloud project with billing enabled and the [Weather API](https://developers.google.com/maps/documentation/weather/get-api-key) enabled. Enable the [Air Quality API](https://developers.google.com/maps/documentation/air-quality/get-api-key) and [Pollen API](https://developers.google.com/maps/documentation/pollen/get-api-key) only if you want those optional tiles.

For Google Weather, create an API key in **APIs & Services → Credentials** and restrict it to the enabled APIs. If you add an Android application restriction, use package `com.zwerk.weather` and the signing-certificate SHA-1 shown in the app's setup/help screen. Paste the key into Zwerk Weather during setup.

If a tile reports `API_KEY_SERVICE_BLOCKED`, confirm that the required API is enabled in the same project, included in the key's API restrictions, billing is active, and any Android restriction uses the exact package name and displayed signing SHA-1. Save changes, allow a few minutes for propagation, then refresh.

## Forecasts, widgets, and radar

Fresh forecasts are reused for one hour. Open-Meteo saved forecasts can remain available offline for up to 48 hours. Google current conditions and hourly forecasts expire after one hour under [Google Weather API caching terms](https://cloud.google.com/maps-platform/terms/maps-service-terms).

Widgets refresh in the background, but Android may delay scheduled work. The **Zwerk Precipitation** widget shows precipitation amounts for the next six hours in millimetres.

With Open-Meteo **Best Match**, short-term precipitation uses Météo-France Seamless inside its model area and automatic selection elsewhere. Explicit model choices are respected. Amounts and precipitation chances come from different forecast fields. The chance field is hidden when no probabilities are returned. See the [Météo-France model documentation](https://open-meteo.com/en/docs/meteofrance-api).

Radar uses RainViewer and needs no API key. Radar tiles are cached, and loading resumes automatically after rate-limit cooldowns. Automatic map appearance follows day and night at the selected location.

## Build, checks, and install

Use JDK 17 and Android SDK 36. From the project root:

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Optional checks:

```powershell
.\gradlew.bat :app:lintDebug --no-daemon
.\tests\run_forecast_tests.ps1
```

To install the existing release APK with ADB:

```powershell
adb install -r .\Zwerk-Weather-1.5.0.apk
```

The app checks GitHub Releases automatically. You can also use **Check for updates** in Settings.
