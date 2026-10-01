$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$sourceDir = Join-Path $projectDir 'app/src/main/java/com/zwerk/weather'
$testDir = Join-Path ([System.IO.Path]::GetTempPath()) ('zwerk-forecast-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testDir | Out-Null
$jsonJar = Join-Path $testDir 'json.jar'
$jsonUrl = 'https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar'
Invoke-WebRequest -UseBasicParsing -Uri $jsonUrl -OutFile $jsonJar
$expected = (Invoke-WebRequest -UseBasicParsing -Uri ($jsonUrl + '.sha1')).Content.Trim()
if ((Get-FileHash -LiteralPath $jsonJar -Algorithm SHA1).Hash.ToLowerInvariant() -ne $expected) {
    throw 'JSON test dependency checksum mismatch.'
}

# Minimal Android I/O adapters, not mocks of the production cache/forecast logic.
$stubs = @{
    'Address.java' = 'package android.location; public class Address { private String locality, feature, admin, sub, country, code; private double lat,lon; public Address(java.util.Locale locale) {} public String getLocality(){return locality;} public void setLocality(String v){locality=v;} public String getFeatureName(){return feature;} public void setFeatureName(String v){feature=v;} public String getAdminArea(){return admin;} public void setAdminArea(String v){admin=v;} public String getSubAdminArea(){return sub;} public void setSubAdminArea(String v){sub=v;} public String getCountryName(){return country;} public void setCountryName(String v){country=v;} public String getCountryCode(){return code;} public void setCountryCode(String v){code=v;} public double getLatitude(){return lat;} public void setLatitude(double v){lat=v;} public double getLongitude(){return lon;} public void setLongitude(double v){lon=v;} }'
    'Context.java' = 'package android.content; public class Context { public static final int MODE_PRIVATE=0; private final java.io.File dir; public Context(java.io.File dir) { this.dir=dir; } public java.io.File getFilesDir() { return dir; } public String getString(int id) { return ""; } public SharedPreferences getSharedPreferences(String name,int mode) { return new SharedPreferences() { public String getString(String key,String fallback) { return fallback; } public long getLong(String key,long fallback) { return fallback; } public boolean getBoolean(String key,boolean fallback) { return fallback; } }; } }'
    'SharedPreferences.java' = 'package android.content; public interface SharedPreferences { String getString(String key,String fallback); long getLong(String key,long fallback); boolean getBoolean(String key,boolean fallback); }'
    'Os.java' = 'package android.system; public class Os { public static void rename(String a,String b) throws Exception { java.nio.file.Files.move(java.nio.file.Path.of(a),java.nio.file.Path.of(b),java.nio.file.StandardCopyOption.REPLACE_EXISTING); } }'
    'OpenMeteoConfig.java' = 'package com.zwerk.weather; final class OpenMeteoConfig { static final String OPEN_METEO="open-meteo"; }'
    'WeatherWidgetProvider.java' = 'package com.zwerk.weather; final class WeatherWidgetProvider { static final String KEY_HOURLY_JSON="hourly_json", KEY_DAILY_JSON="daily_json", KEY_ZONE="zone", KEY_UPDATED_EPOCH_MS="updated_epoch_ms", KEY_OPEN_METEO="open_meteo"; }'
    'DateFormat.java' = 'package android.text.format; public final class DateFormat { public static boolean is24HourFormat(android.content.Context context) { return true; } }'
    'WeatherPreferences.java' = 'package com.zwerk.weather; final class WeatherPreferences { static final String PREFS_NAME="weather_ui_settings"; }'
    'R.java' = 'package com.zwerk.weather; final class R { static final class string { static final int settings_hour_format_12=1, settings_hour_format_24=2, language_system_default=3; } }'
    'DiagnosticLog.java' = 'package com.zwerk.weather; final class DiagnosticLog { enum Area { WEATHER } enum Event { CACHE_FALLBACK } static void event(Area area,Event event) {} }'
}
foreach ($entry in $stubs.GetEnumerator()) {
    [System.IO.File]::WriteAllText((Join-Path $testDir $entry.Key), $entry.Value)
}
$sources = @('ForecastClock.java','ForecastDiskCache.java','HourlyCoverageQueue.java','SavedForecast.java','WeatherWidgetTimeline.java','WeatherTimeFormat.java','PrecipitationWidgetData.java','RadarPlaybackClock.java','PrecipitationWindow.java','PrecipitationModelPolicy.java','CitySearchClient.java','LocationNaming.java','RadarMapTransform.java','WeatherModels.java','BlurKernel.java') |
    ForEach-Object { Join-Path $sourceDir $_ }
$sources += Get-ChildItem -LiteralPath $testDir -Filter '*.java' | Select-Object -ExpandProperty FullName
$sources += Join-Path $PSScriptRoot 'ForecastOfflineWidgetTest.java'
$sources += Join-Path $sourceDir 'WidgetRefreshPolicy.java'
$sources += Join-Path $PSScriptRoot 'PrecipitationWidgetDataTest.java'
$sources += Join-Path $PSScriptRoot 'WidgetRefreshPolicyTest.java'
$sources += Join-Path $PSScriptRoot 'PrecipitationForecastTest.java'
$sources += Join-Path $PSScriptRoot 'CityRadarTest.java'
$sources += Join-Path $PSScriptRoot 'SceneAnimationTest.java'
$sources += Join-Path $PSScriptRoot 'ForecastCachingTest.java'
$sources += Join-Path $PSScriptRoot 'BlurKernelTest.java'
& javac --release 17 -encoding UTF-8 -cp $jsonJar -d $testDir $sources
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
& java -cp ($testDir + [System.IO.Path]::PathSeparator + $jsonJar) com.zwerk.weather.ForecastOfflineWidgetTest
if ($LASTEXITCODE -ne 0) { throw 'Forecast tests failed.' }
& java -cp ($testDir + [System.IO.Path]::PathSeparator + $jsonJar) com.zwerk.weather.PrecipitationWidgetDataTest
if ($LASTEXITCODE -ne 0) { throw 'Six-hour widget tests failed.' }
& java -cp ($testDir + [System.IO.Path]::PathSeparator + $jsonJar) com.zwerk.weather.WidgetRefreshPolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Widget refresh policy tests failed.' }
& java -cp ($testDir + [System.IO.Path]::PathSeparator + $jsonJar) com.zwerk.weather.PrecipitationForecastTest
if ($LASTEXITCODE -ne 0) { throw 'Precipitation tests failed.' }

& java -cp ($testDir + [System.IO.Path]::PathSeparator + $jsonJar) com.zwerk.weather.CityRadarTest
if ($LASTEXITCODE -ne 0) { throw 'City/radar tests failed.' }
& java -cp ($testDir + [System.IO.Path]::PathSeparator + $jsonJar) com.zwerk.weather.SceneAnimationTest
if ($LASTEXITCODE -ne 0) { throw 'Scene animation tests failed.' }
& java -cp ($testDir + [System.IO.Path]::PathSeparator + $jsonJar) com.zwerk.weather.ForecastCachingTest
if ($LASTEXITCODE -ne 0) { throw 'Expanded forecast cache tests failed.' }
& java -cp ($testDir + [System.IO.Path]::PathSeparator + $jsonJar) com.zwerk.weather.BlurKernelTest
if ($LASTEXITCODE -ne 0) { throw 'Backdrop blur tests failed.' }
