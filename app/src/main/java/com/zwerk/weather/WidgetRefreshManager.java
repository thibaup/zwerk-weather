package com.zwerk.weather;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.PersistableBundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;

/** Network-constrained widget recovery. Receivers only render and schedule this worker. */
final class WidgetRefreshManager {
    private static final int PERIODIC_JOB = 0x05A77301;
    private static final int RECOVERY_JOB = 0x05A77302;
    private static final String API_ROOT = "https://weather.googleapis.com/v1/";
    private WidgetRefreshManager() { }

    static boolean hasWidgets(Context context) {
        return WeatherWidgetProvider.widgetIds(context).length > 0
                || hasWidget(context, PrecipitationWidgetProvider.class);
    }
    private static boolean hasWidget(Context context, Class<?> provider) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, provider)).length > 0;
    }

    static void reconcile(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        if (!hasWidgets(context)) {
            scheduler.cancel(PERIODIC_JOB);
            scheduler.cancel(RECOVERY_JOB);
            return;
        }
        purgeExpired(context);
        ComponentName service = new ComponentName(context, WidgetRefreshJobService.class);
        if (scheduler.getPendingJob(PERIODIC_JOB) == null) {
            scheduler.schedule(new JobInfo.Builder(PERIODIC_JOB, service)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                    .setPeriodic(15L * 60_000L, 5L * 60_000L).build());
        }
        // Publishing sends widget redraw broadcasts while this job is still running.
        // Replacing its ID here would cancel the very refresh that just published.
        if (WidgetRefreshJobService.isRunning()) return;
        Target target = Target.resolve(context);
        if (target == null) { scheduler.cancel(RECOVERY_JOB); return; }
        SharedPreferences prefs = snapshot(context);
        long now = System.currentTimeMillis();
        long fetchedAt = prefs.getLong(WeatherWidgetProvider.KEY_UPDATED_EPOCH_MS, 0L);
        boolean precipitation = hasWidget(context, PrecipitationWidgetProvider.class);
        long minutesAt = precipitation ? prefs.getLong(PrecipitationWidgetProvider.KEY_MINUTE_FETCHED_AT_MS, 0L) : 0L;
        boolean immediate = needsBaseRefresh(context, target, now) || needsMinuteRefresh(context, now);
        long dueAt = fetchedAt + WidgetRefreshPolicy.REFRESH_MILLIS;
        if (precipitation) dueAt = Math.min(dueAt, minutesAt + WidgetRefreshPolicy.REFRESH_MILLIS);
        PersistableBundle plan = new PersistableBundle();
        plan.putString("scope", target.scope);
        plan.putString("units", target.units);
        plan.putString("language", target.language);
        plan.putString("precipitationProvider", target.precipitationProvider);
        plan.putLong("fetchedAt", fetchedAt);
        plan.putLong("minutesAt", minutesAt);
        plan.putBoolean("immediate", immediate);
        JobInfo pending = scheduler.getPendingJob(RECOVERY_JOB);
        if (pending != null && samePlan(pending.getExtras(), plan)) return;
        long delay = immediate ? 0L : Math.max(0L, dueAt - now);
        scheduler.schedule(new JobInfo.Builder(RECOVERY_JOB, service)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                // Android 15+ rejects constrained jobs with windows under 15 minutes.
                .setMinimumLatency(delay).setOverrideDeadline(delay + 15L * 60_000L)
                .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_LINEAR)
                .setExtras(plan).build());
    }

    private static boolean samePlan(PersistableBundle first, PersistableBundle second) {
        for (String key : second.keySet()) {
            if (!java.util.Objects.equals(first.get(key), second.get(key))) return false;
        }
        return true;
    }

    static boolean needsRefresh(Context context) {
        if (!hasWidgets(context)) return false;
        Target target = Target.resolve(context);
        long now = System.currentTimeMillis();
        return target != null && (needsBaseRefresh(context, target, now) || needsMinuteRefresh(context, now));
    }

    private static boolean needsMinuteRefresh(Context context, long now) {
        if (!hasWidget(context, PrecipitationWidgetProvider.class)) return false;
        SharedPreferences prefs = snapshot(context);
        return PrecipitationWidgetProvider.freshMinutes(context, prefs, now) == null
                || !ForecastClock.withinAge(prefs.getLong(PrecipitationWidgetProvider.KEY_MINUTE_FETCHED_AT_MS, 0L),
                        now, WidgetRefreshPolicy.REFRESH_MILLIS);
    }

    private static SharedPreferences snapshot(Context context) {
        return context.getSharedPreferences(WeatherWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static boolean needsBaseRefresh(Context context, Target target, long now) {
        SharedPreferences prefs = snapshot(context);
        WeatherWidgetTimeline timeline = new WeatherWidgetTimeline(prefs, now);
        boolean coverage = timeline.firstIndex >= 0
                && new WidgetForecastFormatter(context).temperatureUnitSymbol().equals(
                        prefs.getString(WeatherWidgetProvider.KEY_UNIT, ""));
        // Older snapshots contain chance only; refresh once to migrate the quantity chart.
        if (hasWidget(context, PrecipitationWidgetProvider.class))
            coverage &= new PrecipitationWidgetData(timeline.retained ? timeline.hours : null,
                    PrecipitationWidgetProvider.freshMinutes(context, prefs, now), now).knownBins > 0;
        return WidgetRefreshPolicy.needsRefresh(prefs.getBoolean(WeatherWidgetProvider.KEY_HAS_SNAPSHOT, false),
                target.scope.equals(prefs.getString(PrecipitationWidgetProvider.KEY_LOCATION_SCOPE, "")),
                coverage, prefs.getLong(WeatherWidgetProvider.KEY_UPDATED_EPOCH_MS, 0L), now);
    }

    /** Purge payloads once, under the publishing lock; never clear unrelated shared state. */
    private static void purgeExpired(Context context) {
        synchronized (WeatherWidgetSnapshotPublisher.class) {
            SharedPreferences prefs = snapshot(context);
            long now = System.currentTimeMillis();
            SharedPreferences.Editor editor = prefs.edit();
            if (prefs.getBoolean(WeatherWidgetProvider.KEY_HAS_SNAPSHOT, false)
                    && !new WeatherWidgetTimeline(prefs, now).retained) {
                editor.putBoolean(WeatherWidgetProvider.KEY_HAS_SNAPSHOT, false);
                for (String key : new String[]{WeatherWidgetProvider.KEY_TEMPERATURE, WeatherWidgetProvider.KEY_CONDITION,
                        WeatherWidgetProvider.KEY_CONDITION_TYPE, WeatherWidgetProvider.KEY_ADVICE,
                        WeatherWidgetProvider.KEY_HOURLY_JSON, WeatherWidgetProvider.KEY_DAILY_JSON,
                        WeatherWidgetProvider.KEY_DAILY_HIGH, WeatherWidgetProvider.KEY_DAILY_LOW,
                        WeatherWidgetProvider.KEY_OBSERVATION_EPOCH_MS,
                        WeatherWidgetProvider.KEY_UPDATED_EPOCH_MS}) editor.remove(key);
            }
            long minutesAt = prefs.getLong(PrecipitationWidgetProvider.KEY_MINUTE_FETCHED_AT_MS, 0L);
            if (!ForecastClock.withinAge(minutesAt, now, ForecastClock.FRESH_MILLIS))
                editor.remove(PrecipitationWidgetProvider.KEY_MINUTE_JSON)
                        .remove(PrecipitationWidgetProvider.KEY_MINUTE_FETCHED_AT_MS);
            editor.apply();
        }
    }

    /** Returns false for a recoverable failure; JobScheduler retries with backoff. */
    static boolean refresh(Context context) {
        if (!hasWidgets(context)) return true;
        Target target = Target.resolve(context);
        if (target == null) return true; // Set up a location in the app first.
        // A deadline may run a job despite its network constraint. Don't spend request
        // budget while offline; cached responses can still be republished below.
        ConnectivityManager networks = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities network = networks == null ? null : networks.getNetworkCapabilities(networks.getActiveNetwork());
        boolean online = network != null && network.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        boolean success = true;
        if (needsBaseRefresh(context, target, System.currentTimeMillis())) {
            try {
                long startedAt = System.currentTimeMillis();
                ForecastDiskCache cache = new ForecastDiskCache(context);
                ForecastDiskCache.Snapshot saved = cache.readFresh(target.lat, target.lon,
                        target.language, target.id + "|" + target.provider);
                JSONObject current, hourly, daily;
                long fetchedAt;
                if (saved != null && ForecastClock.withinAge(saved.fetchedAtMillis,
                        System.currentTimeMillis(), WidgetRefreshPolicy.REFRESH_MILLIS)
                        && new WidgetForecastFormatter(context).degreesOrNull(saved.current.optJSONObject("temperature")) != null
                        && saved.hourly.optJSONArray("forecastHours") != null
                        && saved.hourly.optJSONArray("forecastHours").length() > 0) {
                    current = saved.current; hourly = saved.hourly; daily = saved.daily;
                    fetchedAt = saved.fetchedAtMillis;
                } else {
                    if (!online) return false;
                    if (OpenMeteoConfig.isOpenMeteo(context)) {
                        OpenMeteoForecastClient.Forecast forecast = OpenMeteoForecastClient.loadForecast(
                                context, target.lat, target.lon, OpenMeteoConfig.model(context),
                                OpenMeteoConfig.readCustomerKey(context));
                        current = forecast.current; hourly = forecast.hourly; daily = forecast.daily;
                    } else {
                        String query = googleQuery(context, target);
                        current = requestGoogle(context, API_ROOT + "currentConditions:lookup" + query);
                        target.requireCurrent(context);
                        try {
                            hourly = requestGoogle(context, API_ROOT + "forecast/hours:lookup" + query + "&hours=24&pageSize=24");
                        } catch (InterruptedException stopped) { throw stopped; }
                        catch (Exception unavailable) { hourly = new JSONObject(); }
                        target.requireCurrent(context);
                        try {
                            daily = requestGoogle(context, API_ROOT + "forecast/days:lookup" + query + "&days=10&pageSize=10");
                        } catch (InterruptedException stopped) { throw stopped; }
                        catch (Exception unavailable) { daily = new JSONObject(); }
                    }
                    fetchedAt = startedAt;
                }
                target.requireCurrent(context);
                current.put(SavedForecast.FETCHED_AT, fetchedAt);
                ZoneId zone = WeatherActivityFoundation.responseZone(current, hourly, daily);
                JSONArray days = daily.optJSONArray("forecastDays");
                JSONObject today = null;
                for (int i = 0; days != null && i < days.length(); i++) {
                    JSONObject day = days.optJSONObject(i);
                    long start = SavedForecast.epoch(day, "startTime"), end = SavedForecast.epoch(day, "endTime");
                    if (start <= System.currentTimeMillis() && end > System.currentTimeMillis()) { today = day; break; }
                }
                synchronized (WeatherWidgetSnapshotPublisher.class) {
                    target.requireCurrent(context);
                    boolean published = new WeatherWidgetSnapshotPublisher(context, new WidgetForecastFormatter(context))
                            .publish(current, today, hourly, daily, zone, target.name, target.lat, target.lon);
                    if (!published) throw new IllegalStateException("Missing current temperature");
                }
                if (needsBaseRefresh(context, target, System.currentTimeMillis())) success = false;
            } catch (Exception failure) {
                DiagnosticLog.error(DiagnosticLog.Area.WIDGET, failure);
                success = false;
            }
        }
        if (hasWidget(context, PrecipitationWidgetProvider.class) && target.isCurrent(context)) {
            if (needsMinuteRefresh(context, System.currentTimeMillis())) {
                try {
                    if (!online) return false;
                    long startedAt = System.currentTimeMillis();
                    JSONObject minutes = OpenMeteoConfig.isPrecipitationOpenMeteo(context)
                            ? OpenMeteoForecastClient.loadMinute(context, target.lat, target.lon,
                                    OpenMeteoConfig.model(context), OpenMeteoConfig.readCustomerKey(context))
                            : requestGoogle(context, API_ROOT + "forecast/minutes:lookup"
                                    + googleQuery(context, target) + "&pageSize=180");
                    synchronized (WeatherWidgetSnapshotPublisher.class) {
                        target.requireCurrent(context);
                        PrecipitationWidgetProvider.publishMinute(context, minutes,
                                startedAt, target.lat, target.lon);
                    }
                } catch (Exception failure) {
                    DiagnosticLog.error(DiagnosticLog.Area.PRECIPITATION, failure);
                    // Hourly quantities remain usable when minute data is unsupported.
                }
            }
        }
        return success;
    }

    private static String googleQuery(Context context, Target target) throws Exception {
        String key = RainAlertManager.readApiKey(context);
        if (key.isEmpty()) throw new IllegalStateException("Weather key unavailable");
        return "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8.name())
                + "&location.latitude=" + target.lat + "&location.longitude=" + target.lon
                + "&unitsSystem=METRIC&languageCode=" + URLEncoder.encode(target.language, StandardCharsets.UTF_8.name());
    }

    private static JSONObject requestGoogle(Context context, String address) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (!ApiRequestBudgetManager.tryAcquire(context, ApiRequestBudgetManager.Category.WEATHER).allowed) {
            DiagnosticLog.event(DiagnosticLog.Area.WIDGET, DiagnosticLog.Event.REQUEST_LIMIT);
            throw new IllegalStateException("Request limit reached");
        }
        long diagnosticStart = android.os.SystemClock.elapsedRealtime();
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(18_000);
        connection.setRequestProperty("Accept", "application/json");
        RainAlertManager.applyAndroidApiKeyRestrictionHeaders(context, connection);
        try {
            int status = connection.getResponseCode();
            DiagnosticLog.http(DiagnosticLog.Area.WIDGET, status, android.os.SystemClock.elapsedRealtime() - diagnosticStart);
            if (status != 200) throw new IllegalStateException("Weather service unavailable");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (output.size() + count > 2 * 1024 * 1024) throw new IllegalStateException("Response too large");
                    output.write(buffer, 0, count);
                }
                return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            }
        } finally { connection.disconnect(); }
    }

    private static final class Target {
        final String id, name, provider, precipitationProvider, language, scope, units;
        final double lat, lon;
        Target(Context context, String id, String name, double lat, double lon) {
            this.id = id; this.name = name; this.lat = lat; this.lon = lon;
            provider = OpenMeteoConfig.cacheScope(context);
            precipitationProvider = OpenMeteoConfig.precipitationCacheScope(context);
            Context localized = AppLocaleManager.wrap(context);
            language = localized.getResources().getConfiguration().getLocales().get(0).toLanguageTag();
            scope = PrecipitationWidgetData.locationScope(lat, lon, provider);
            WeatherPreferences prefs = new WeatherPreferences(context);
            boolean fahrenheit = WeatherPreferences.TEMP_FAHRENHEIT.equals(prefs.temperatureUnit());
            units = prefs.temperatureUnit() + "|" + prefs.windUnit(fahrenheit)
                    + "|" + prefs.pressureUnit() + "|" + prefs.visibilityUnit(fahrenheit);
        }
        static Target resolve(Context context) {
            CityManagerActivity.LocationSnapshot selected = CityManagerActivity.getSelectedLocation(context);
            if (selected != null && valid(selected.lat, selected.lon))
                return new Target(context, selected.id, selected.name, selected.lat, selected.lon);
            SharedPreferences legacy = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
            try {
                double lat = legacy.getFloat("lat", Float.NaN), lon = legacy.getFloat("lon", Float.NaN);
                return valid(lat, lon) ? new Target(context, "", legacy.getString("name", ""), lat, lon) : null;
            } catch (ClassCastException ignored) { return null; }
        }
        boolean isCurrent(Context context) {
            if (Thread.currentThread().isInterrupted() || !hasWidgets(context)) return false;
            Target current = resolve(context);
            return current != null && id.equals(current.id) && scope.equals(current.scope)
                    && precipitationProvider.equals(current.precipitationProvider)
                    && language.equals(current.language) && units.equals(current.units);
        }
        void requireCurrent(Context context) throws InterruptedException {
            if (!isCurrent(context)) throw new InterruptedException("Widget location or settings changed");
        }
        static boolean valid(double lat, double lon) {
            return Double.isFinite(lat) && Double.isFinite(lon)
                    && lat >= -90d && lat <= 90d && lon >= -180d && lon <= 180d;
        }
    }
}
