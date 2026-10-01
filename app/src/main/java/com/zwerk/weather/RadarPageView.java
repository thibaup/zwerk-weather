package com.zwerk.weather;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

final class RadarPageView extends FrameLayout {

    private static final long TIMELINE_WATCHDOG_MS = 18_000L;
    private static final long VIEWPORT_COALESCE_MS = 140L;
    private static final long VIEWPORT_RETRY_MS = 1_500L;
    private static final int MAX_VIEWPORT_RETRIES = 3;
    private static final long PLAYBACK_FRAME_DELAY_MS = 650L;
    private static final long PLAYBACK_POLL_MS = 90L;
    private final RadarDataClient data;
    private final FrameLayout mapSurface;
    private final FrameLayout overlays;
    private final LinearLayout mapControls;
    private final LinearLayout playbackPanel;
    private final LinearLayout legend;
    private final java.util.ArrayList<View> surfaceControls = new java.util.ArrayList<>();
    private final RadarMapView map;
    private final RadarBackdrop backdrop;
    private final TextView summary;
    private final TextView frameTime;
    private final TextView mapMessage;
    private final RadarPlaybackButton playButton;
    private final SeekBar timelineBar;
    private final ProgressBar loading;
    private final RadarRefreshButton refreshButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private RadarDataClient.Timeline timeline;
    private int frameIndex = -1;
    private boolean active;
    private boolean playing;
    private boolean preparing;
    private boolean pendingPlay;
    private boolean preparationTickScheduled;
    private boolean timelineLoading;
    private String timelineError = "";
    private Runnable statusChanged = () -> { };
    private boolean disposed;
    private int timelineLoadGeneration;
    private int viewportLoadGeneration;
    private Runnable timelineWatchdogTask;
    private Runnable viewportLoadTask;
    private Runnable viewportRetryTask;
    private int preparationGeneration;
    private long preparationStartedAt;
    private int pendingScrubIndex;
    private final RadarPlaybackClock playbackClock = new RadarPlaybackClock();
    private final Runnable applyScrub = () -> showFrame(pendingScrubIndex, true);
    private final Runnable playbackStep = new Runnable() {
        @Override public void run() {
            if (disposed || !active || !playing || timeline == null || timeline.frames.isEmpty()) {
                return;
            }
            int count = timeline.frames.size();
            int currentIndex = Math.max(0, Math.min(frameIndex, count - 1));
            RadarDataClient.Frame current = timeline.frames.get(currentIndex);
            int nextIndex = (currentIndex + playbackClock.candidateOffset()) % count;
            RadarDataClient.Frame next = timeline.frames.get(nextIndex);
            map.ensureFrame(current, null);
            map.ensureFrame(next, null);
            int action = playbackClock.step(SystemClock.uptimeMillis(), map.isViewportInMotion(),
                    map.frameReady(current), map.frameReady(next), count, data.radarCooldownMillis());
            String wait = action == RadarPlaybackClock.WAIT ? data.radarWaitMessage() : "";
            setSummary(wait);
            if (action == RadarPlaybackClock.ADVANCE) {
                showFrame(nextIndex, false);
                preloadPlaybackWindow();
                handler.postDelayed(this, PLAYBACK_FRAME_DELAY_MS);
                return;
            }
            if (action == RadarPlaybackClock.STOP) {
                stopPlayback();
                setSummary("Animation could not load · tap play to retry");
                return;
            }
            handler.postDelayed(this, action == RadarPlaybackClock.SKIP ? 0L
                    : wait.isEmpty() ? PLAYBACK_POLL_MS : 500L);
        }
    };

    RadarPageView(Context context) {
        super(context);
        data = new RadarDataClient(context);

        mapSurface = new FrameLayout(context);
        addView(mapSurface, new FrameLayout.LayoutParams(-1, -1));
        map = new RadarMapView(context, data, this::onViewportChanged);
        backdrop = new RadarBackdrop(map);
        map.setFrameDisplayListener(this::updateFrameTime);
        map.setContentDescription(UiTranslations.text(getContext(),
                "Interactive precipitation radar map. Drag to pan."));
        mapSurface.addView(map, new FrameLayout.LayoutParams(-1, -1));
        overlays = new FrameLayout(context);
        mapSurface.addView(overlays, new FrameLayout.LayoutParams(-1, -1));

        summary = label("", 13, false,
                Color.argb(220, 231, 242, 255));
        summary.setPadding(dp(3), 0, dp(3), dp(3));
        summary.setMaxLines(2);
        summary.setVisibility(GONE);
        refreshButton = new RadarRefreshButton(context);
        refreshButton.setContentDescription(UiTranslations.text(getContext(), "Refresh radar frames"));
        refreshButton.setOnClickListener(v -> refresh());
        surfaceControls.add(refreshButton);
        overlays.addView(refreshButton, new FrameLayout.LayoutParams(
                dp(48), dp(48), Gravity.TOP | Gravity.END));

        mapControls = new LinearLayout(context);
        mapControls.setOrientation(LinearLayout.VERTICAL);
        overlays.addView(mapControls, new FrameLayout.LayoutParams(
                -2, -2, Gravity.TOP | Gravity.END));
        Button zoomIn = control("+", "Zoom in on radar");
        zoomIn.setOnClickListener(v -> map.zoomBy(1));
        mapControls.addView(zoomIn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        Button zoomOut = control("−", "Zoom out on radar");
        zoomOut.setOnClickListener(v -> map.zoomBy(-1));
        mapControls.addView(zoomOut, new LinearLayout.LayoutParams(dp(44), dp(44)));
        Button recenter = control("⌖", "Center radar on selected location");
        recenter.setOnClickListener(v -> map.recenter());
        mapControls.addView(recenter, new LinearLayout.LayoutParams(dp(44), dp(44)));

        loading = new ProgressBar(context);
        loading.setVisibility(GONE);
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(
                dp(42), dp(42), Gravity.CENTER);
        overlays.addView(loading, loadingParams);

        mapMessage = label("", 14, false, Color.WHITE);
        mapMessage.setGravity(Gravity.CENTER);
        mapMessage.setPadding(dp(16), dp(10), dp(16), dp(10));
        surfaceControls.add(mapMessage);
        mapMessage.setVisibility(GONE);
        mapMessage.setClickable(true);
        mapMessage.setFocusable(true);
        mapMessage.setContentDescription(UiTranslations.text(getContext(),
                "Radar status. Tap to retry loading the map."));
        mapMessage.setOnClickListener(v -> retryRadarLoad(false));
        FrameLayout.LayoutParams messageParams = new FrameLayout.LayoutParams(
                -2, -2, Gravity.CENTER);
        messageParams.leftMargin = dp(16);
        messageParams.rightMargin = dp(16);
        overlays.addView(mapMessage, messageParams);

        playbackPanel = new LinearLayout(context);
        playbackPanel.setOrientation(LinearLayout.VERTICAL);
        playbackPanel.setPadding(dp(12), dp(10), dp(12), dp(4));
        surfaceControls.add(playbackPanel);
        overlays.addView(playbackPanel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        playbackPanel.addView(summary);
        frameTime = label("Radar timeline", 14, true, Color.WHITE);
        frameTime.setPadding(dp(3), 0, 0, 0);
        playbackPanel.addView(frameTime);
        LinearLayout playbackRow = new LinearLayout(context);
        playbackRow.setGravity(Gravity.CENTER_VERTICAL);
        playbackPanel.addView(playbackRow, new LinearLayout.LayoutParams(-1, dp(54)));
        playButton = new RadarPlaybackButton(context);
        playButton.setOnClickListener(v -> togglePlayback());
        playButton.setBackground(null);
        playbackRow.addView(playButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        timelineBar = new WeatherSeekBar(context, false);
        timelineBar.setMax(0);
        timelineBar.setEnabled(false);
        timelineBar.setProgressTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(125, 210, 246)));
        timelineBar.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        timelineBar.setContentDescription(UiTranslations.text(getContext(), "Radar time"));
        timelineBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser) return;
                stopPlayback();
                pendingScrubIndex = value;
                handler.removeCallbacks(applyScrub);
                handler.postDelayed(applyScrub, 120L);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { stopPlayback(); }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                handler.removeCallbacks(applyScrub);
                showFrame(bar.getProgress(), true);
            }
        });
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        seekParams.leftMargin = dp(8);
        playbackRow.addView(timelineBar, seekParams);
        Button latest = control("Now", "Show latest radar frame");
        surfaceControls.remove(latest);
        latest.setBackground(null);
        latest.setTextSize(12f);
        latest.setOnClickListener(v -> {
            stopPlayback();
            if (timeline != null) showFrame(timeline.frames.size() - 1, true);
        });
        playbackRow.addView(latest, new LinearLayout.LayoutParams(dp(54), dp(42)));

        legend = new LinearLayout(context);
        legend.setOrientation(LinearLayout.VERTICAL);
        playbackPanel.addView(legend);
        View legendBar = new View(context);
        GradientDrawable legendBackground = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(125, 210, 246), Color.rgb(47, 119, 223),
                        Color.rgb(23, 68, 161), Color.rgb(92, 43, 152)});
        legendBackground.setCornerRadius(dp(5));
        legendBar.setBackground(legendBackground);
        LinearLayout.LayoutParams legendParams = new LinearLayout.LayoutParams(-1, dp(7));
        legendParams.topMargin = dp(4);
        legendParams.leftMargin = dp(3);
        legendParams.rightMargin = dp(3);
        legend.addView(legendBar, legendParams);
        LinearLayout legendLabels = new LinearLayout(context);
        legendLabels.setPadding(dp(3), dp(3), dp(3), 0);
        TextView light = label("Light precipitation", 11, false,
                Color.argb(220, 234, 246, 255));
        TextView heavy = label("Heavy precipitation", 11, false,
                Color.argb(220, 234, 246, 255));
        heavy.setGravity(Gravity.END);
        legendLabels.addView(light, new LinearLayout.LayoutParams(0, -2, 1f));
        legendLabels.addView(heavy, new LinearLayout.LayoutParams(0, -2, 1f));
        legend.addView(legendLabels);

        LinearLayout credits = new LinearLayout(context);
        credits.setGravity(Gravity.CENTER_VERTICAL);
        credits.setMinimumHeight(dp(30));
        credits.setPadding(dp(3), dp(4), dp(3), 0);
        TextView osmCredit = label("© OpenStreetMap contributors", 10, false,
                Color.argb(238, 243, 248, 255));
        osmCredit.setOnClickListener(v -> openUrl("https://www.openstreetmap.org/copyright"));
        osmCredit.setContentDescription(UiTranslations.text(getContext(),
                "Map by OpenStreetMap contributors. Open copyright page."));
        credits.addView(osmCredit, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView radarCredit = label("Radar by RainViewer ↗", 10, false,
                Color.argb(255, 168, 219, 255));
        radarCredit.setGravity(Gravity.END);
        radarCredit.setOnClickListener(v -> openUrl("https://www.rainviewer.com/"));
        credits.addView(radarCredit, new LinearLayout.LayoutParams(0, -2, 1f));
        playbackPanel.addView(credits, new LinearLayout.LayoutParams(-1, -2));
        mapSurface.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                layoutOverlays();
                if (active) map.post(this::onViewportChanged);
            }
        });
        applyTileTransparency();
        layoutOverlays();
    }

    void setStatusChangedListener(Runnable listener) {
        statusChanged = listener == null ? () -> { } : listener;
    }

    private String statusText() {
        if (timelineLoading) {
            return "Loading radar data…";
        }
        if (timeline == null) {
            return timelineError.isEmpty() ? "Radar data not loaded" : "Radar data unavailable";
        }
        String prefix = "Radar data";
        return WeatherActivityFoundation.fetchedDataAgeLabel(prefix, timeline.fetchedAtMillis);
    }

    boolean isLoading() {
        return timelineLoading;
    }

    private void notifyStatusChanged() {
        statusChanged.run();
    }

    private void setSummary(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) value = statusText();
        String translated = UiTranslations.text(getContext(), value);
        if (!translated.contentEquals(summary.getText())) summary.setText(translated);
        summary.setVisibility(VISIBLE);
    }

    void setLocation(double latitude, double longitude) {
        if (disposed) return;
        boolean changed = map.locationChanged(latitude, longitude);
        data.setLocation(latitude, longitude);
        if (!changed) return;

        map.setLocation(latitude, longitude);
        updateMapTheme();
        timelineError = "";
        mapMessage.setVisibility(GONE);

        // RainViewer metadata is global, so keep the selected frame/timeline and only
        // replace the viewport tiles. This makes saved/device location changes immediate.
        notifyStatusChanged();
        if (active) {
            if (timeline == null || timeline.frames.isEmpty()) loadTimeline(false);
            else scheduleViewportLoad(0L);
        }
    }

    void setActive(boolean value) {
        if (disposed || active == value) return;
        active = value;
        backdrop.setActive(value);
        data.setActive(value);
        if (value) {
            applyTileTransparency();
            loadTimeline(false);
        } else {
            timelineLoadGeneration++;
            viewportLoadGeneration++;
            timelineLoading = false;
            cancelTimelineWatchdog();
            cancelViewportWork();
            stopPlayback();
            map.stopMotion();
            refreshButton.setLoading(false);
            loading.setVisibility(GONE);
            notifyStatusChanged();
        }
    }

    void applyTileTransparency() {
        updateMapTheme();
        backdrop.updateAppearance(surfaceControls);
    }

    void updateMapTheme() {
        WeatherPreferences prefs = new WeatherPreferences(getContext());
        String theme = prefs.radarMapTheme();
        map.setDarkMap(WeatherPreferences.MAP_DARK.equals(theme)
                || WeatherPreferences.MAP_AUTOMATIC.equals(theme) && !isDaytimeAtLocation());
    }

    private boolean isDaytimeAtLocation() {
        if (!(getContext() instanceof WeatherActivityFoundation)) return true;
        WeatherActivityFoundation weather = (WeatherActivityFoundation) getContext();
        if (!weather.displayedForecastMatchesSelection()) return true;

        Instant now = Instant.now();
        ZoneId zone = WeatherActivityFoundation.responseZone(
                weather.lastCurrentWeather, weather.lastHourlyWeather, weather.lastDailyWeather);
        LocalDate today = now.atZone(zone).toLocalDate();
        JSONArray days = weather.lastDailyWeather == null ? null
                : weather.lastDailyWeather.optJSONArray("forecastDays");
        for (int i = 0; days != null && i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            if (!today.equals(WeatherActivityFoundation.displayDate(day, zone))) continue;
            JSONObject sun = day.optJSONObject("sunEvents");
            if (sun == null) continue;
            Instant sunrise = WeatherActivityFoundation.parseInstant(sun.optString("sunriseTime", null));
            Instant sunset = WeatherActivityFoundation.parseInstant(sun.optString("sunsetTime", null));
            if (sunrise != null && sunset != null && sunset.isAfter(sunrise)) {
                return !now.isBefore(sunrise) && now.isBefore(sunset);
            }
        }

        JSONArray hours = weather.lastHourlyWeather == null ? null
                : weather.lastHourlyWeather.optJSONArray("forecastHours");
        for (int i = 0; hours != null && i < hours.length(); i++) {
            JSONObject hour = hours.optJSONObject(i);
            if (ForecastClock.contains(SavedForecast.epoch(hour, "startTime"),
                    SavedForecast.epoch(hour, "endTime"), now.toEpochMilli())
                    && hour.has("isDaytime")) {
                return WeatherActivityFoundation.safeBoolean(hour, "isDaytime", weather.displayedDaytime);
            }
        }
        return weather.displayedDaytime;
    }

    void setViewportInsets(int left, int top, int right, int bottom) {
        overlays.setPadding(left, top, right, bottom);
        layoutOverlays();
    }

    private void layoutOverlays() {
        int height = mapSurface.getHeight();
        if (height == 0) height = getResources().getDisplayMetrics().heightPixels;
        boolean compact = height - overlays.getPaddingTop() - overlays.getPaddingBottom() < dp(460);
        legend.setVisibility(compact ? GONE : VISIBLE);
        summary.setMaxLines(compact ? 1 : 2);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        playbackPanel.setPadding(dp(12), dp(compact ? 6 : 10), dp(12), dp(compact ? 2 : 4));
        setOverlayMargins(refreshButton, dp(16), dp(12), dp(16), 0);
        setOverlayMargins(playbackPanel, dp(16), 0, dp(16), dp(12));
        mapControls.setOrientation(compact ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        setOverlayMargins(mapControls, dp(16),
                dp(compact ? 12 : 76),
                dp(compact ? 76 : 16), 0);
        for (int i = 0; i < mapControls.getChildCount(); i++) {
            View control = mapControls.getChildAt(i);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) control.getLayoutParams();
            int left = compact && i > 0 ? dp(6) : 0;
            int top = !compact && i > 0 ? dp(6) : 0;
            if (params.leftMargin == left && params.topMargin == top) continue;
            params.leftMargin = left;
            params.topMargin = top;
            control.setLayoutParams(params);
        }
    }

    private void setOverlayMargins(View view, int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (params.leftMargin == left && params.topMargin == top
                && params.rightMargin == right && params.bottomMargin == bottom) return;
        params.setMargins(left, top, right, bottom);
        view.setLayoutParams(params);
    }

    void refresh() {
        if (disposed) return;
        retryRadarLoad(true);
    }

    void dispose() {
        if (disposed) return;
        disposed = true;
        active = false;
        backdrop.release();
        timelineLoadGeneration++;
        viewportLoadGeneration++;
        data.setActive(false);
        cancelTimelineWatchdog();
        cancelViewportWork();
        stopPlayback();
        map.stopMotion();
        timelineLoading = false;
        refreshButton.setLoading(false);
        loading.setVisibility(GONE);
        handler.removeCallbacksAndMessages(null);
        data.dispose();
    }

    private void loadTimeline(boolean force) {
        loadTimeline(force, false);
    }

    private void loadTimeline(boolean force, boolean startAfterLoad) {
        if (!active || disposed) return;
        stopPlayback();
        final int loadGeneration = ++timelineLoadGeneration;
        timelineLoading = true;
        timelineError = "";
        pendingPlay = startAfterLoad;
        refreshButton.setLoading(true);
        playButton.setMode(RadarPlaybackButton.LOADING);
        loading.setVisibility(VISIBLE);
        mapMessage.setVisibility(GONE);
        setSummary("Checking the latest radar frames…");
        notifyStatusChanged();
        scheduleTimelineWatchdog(loadGeneration);
        data.loadTimeline(force, (result, error) -> {
            if (disposed || !active || loadGeneration != timelineLoadGeneration) return;
            cancelTimelineWatchdog();
            timelineLoading = false;
            timelineError = error == null ? "" : error;
            refreshButton.setLoading(false);
            loading.setVisibility(GONE);
            if (result == null || result.frames.isEmpty()) {
                pendingPlay = false;
                playButton.setMode(RadarPlaybackButton.PLAY);
                showMapRetryMessage(error == null
                        ? "Radar is unavailable here right now."
                        : error);
                setSummary("Radar unavailable · tap ↻ to retry");
                timelineBar.setEnabled(false);
                notifyStatusChanged();
                return;
            }
            timeline = result;
            timelineBar.setMax(result.frames.size() - 1);
            timelineBar.setEnabled(result.frames.size() > 1);
            setSummary(error == null ? ""
                    : "Showing saved radar frames · tap ↻ to retry");
            showFrame(result.frames.size() - 1, false);
            if (pendingPlay && result.frames.size() > 1) {
                pendingPlay = false;
                beginPlaybackPreparation();
            } else {
                pendingPlay = false;
                playButton.setMode(RadarPlaybackButton.PLAY);
            }
            notifyStatusChanged();
            scheduleViewportLoad(0L);
        });
    }

    private void scheduleTimelineWatchdog(int loadGeneration) {
        cancelTimelineWatchdog();
        timelineWatchdogTask = () -> {
            timelineWatchdogTask = null;
            if (disposed || !active || !timelineLoading
                    || loadGeneration != timelineLoadGeneration) {
                return;
            }
            // Reject late callbacks after the UI watchdog times out.
            timelineLoadGeneration++;
            timelineLoading = false;
            pendingPlay = false;
            refreshButton.setLoading(false);
            loading.setVisibility(GONE);
            playButton.setMode(RadarPlaybackButton.PLAY);
            DiagnosticLog.event(DiagnosticLog.Area.RADAR, DiagnosticLog.Event.LOADING_TIMEOUT);
            timelineError = "Radar loading timed out · tap ↻ to retry";
            if (timeline != null && !timeline.frames.isEmpty()) {
                setSummary("Radar refresh timed out · showing current frame · tap ↻ to retry");
                scheduleViewportLoad(0L);
            } else {
                timelineBar.setEnabled(false);
                setSummary("Radar loading timed out · tap ↻ to retry");
            }
            showMapRetryMessage(timelineError);
            notifyStatusChanged();
        };
        handler.postDelayed(timelineWatchdogTask, TIMELINE_WATCHDOG_MS);
    }

    private void cancelTimelineWatchdog() {
        if (timelineWatchdogTask == null) return;
        handler.removeCallbacks(timelineWatchdogTask);
        timelineWatchdogTask = null;
    }

    private void showMapRetryMessage(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) value = "Radar loading failed.";
        boolean hasRetry = value.toLowerCase(Locale.ROOT).contains("retry");
        value = UiTranslations.text(getContext(), value);
        if (!hasRetry) {
            value += " " + UiTranslations.text(getContext(), "Tap to retry.");
        }
        mapMessage.setText(value);
        mapMessage.setVisibility(VISIBLE);
    }

    private void retryRadarLoad(boolean refreshTimeline) {
        if (disposed || !active) return;
        data.retryFailedTiles();
        mapMessage.setVisibility(GONE);
        if (refreshTimeline || timeline == null || timeline.frames.isEmpty()) {
            loadTimeline(true);
        } else {
            timelineError = "";
            setSummary("");
            scheduleViewportLoad(0L);
            notifyStatusChanged();
        }
    }

    private void showFrame(int index, boolean userSelected) {
        if (timeline == null || timeline.frames.isEmpty()) return;
        frameIndex = Math.max(0, Math.min(index, timeline.frames.size() - 1));
        RadarDataClient.Frame frame = timeline.frames.get(frameIndex);
        map.setFrame(timeline, frame);
        timelineBar.setProgress(frameIndex);
        updateFrameTime();
        if (userSelected) mapMessage.setVisibility(GONE);
    }

    void refreshTimeFormat() { updateFrameTime(); }

    private void updateFrameTime() {
        if (disposed || timeline == null || timeline.frames.isEmpty() || frameIndex < 0) return;
        RadarDataClient.Frame frame = timeline.frames.get(frameIndex);
        String time = WeatherTimeFormat.dated(getContext(),
                Instant.ofEpochSecond(frame.timeSeconds), ZoneId.systemDefault(), "EEE");
        frameTime.setText(UiTranslations.text(getContext(), !map.isFrameDisplayed(frame)
                && !map.frameReady(frame) ? "Loading…" : frameIndex == timeline.frames.size() - 1 ? "Latest" : "Past")
                + " · " + time);
    }

    private void togglePlayback() {
        if (playing || preparing || pendingPlay) {
            stopPlayback();
            return;
        }
        if (loading.getVisibility() == VISIBLE) {
            pendingPlay = true;
            playButton.setMode(RadarPlaybackButton.LOADING);
            setSummary("Loading radar frames · playback will start when ready");
            return;
        }
        if (timeline == null || timeline.frames.size() < 2) {
            loadTimeline(false, true);
            return;
        }
        beginPlaybackPreparation();
    }

    private void beginPlaybackPreparation() {
        if (disposed || !active || timeline == null || timeline.frames.size() < 2) return;
        data.retryFailedRadarTiles();
        preparing = true;
        map.setManagedRadarLoading(true);
        preparationTickScheduled = false;
        preparationStartedAt = SystemClock.uptimeMillis();
        int generation = ++preparationGeneration;
        playButton.setMode(RadarPlaybackButton.LOADING);
        setSummary("Preparing radar animation…");
        checkPreparation(generation);
    }

    private void checkPreparation(int generation) {
        if (generation != preparationGeneration || !preparing || !active
                || disposed || timeline == null) return;
        if (map.isViewportInMotion()) {
            preparationStartedAt = SystemClock.uptimeMillis();
            schedulePreparationCheck(generation);
            return;
        }
        if (map.getWidth() == 0 || map.getHeight() == 0) {
            if (SystemClock.uptimeMillis() - preparationStartedAt > 5_000L) {
                preparing = false;
                map.setManagedRadarLoading(false);
                playButton.setMode(RadarPlaybackButton.PLAY);
                setSummary("Radar map is not ready · tap play to retry");
                return;
            }
            setSummary("Waiting for radar map layout…");
            schedulePreparationCheck(generation);
            return;
        }
        // Stream three frames at a time so preloading cannot evict the first playback frame.
        int count = timeline.frames.size();
        int start = Math.max(0, Math.min(frameIndex, count - 1));
        int ready = 0;
        int firstReady = -1;
        int window = Math.min(3, count);
        for (int offset = 0; offset < window; offset++) {
            int index = (start + offset) % count;
            RadarDataClient.Frame frame = timeline.frames.get(index);
            map.ensureFrame(frame, () -> checkPreparation(generation));
            if (map.frameReady(frame)) {
                ready++;
                if (firstReady < 0) firstReady = index;
            }
        }
        if (ready < 2 && data.radarCooldownMillis() > 0L) {
            preparationStartedAt = SystemClock.uptimeMillis();
            setSummary(data.radarWaitMessage());
            schedulePreparationCheck(generation);
            return;
        }
        if (ready < 2 && SystemClock.uptimeMillis() - preparationStartedAt < 30_000L) {
            setSummary(String.format(Locale.getDefault(),
                    UiTranslations.text(getContext(), "Preparing animation · %d/%d frames"),
                    ready, window));
            schedulePreparationCheck(generation);
            return;
        }
        preparing = false;
        if (ready < 2) {
            map.setManagedRadarLoading(false);
            playButton.setMode(RadarPlaybackButton.PLAY);
            setSummary("Animation could not load · tap play to retry");
            return;
        }
        setSummary("");
        playing = true;
        DiagnosticLog.event(DiagnosticLog.Area.RADAR, DiagnosticLog.Event.PLAYBACK_STARTED);
        playbackClock.reset();
        if (firstReady >= 0 && firstReady != frameIndex) showFrame(firstReady, false);
        map.setFallbackFrame(timeline.frames.get(frameIndex));
        map.setManagedRadarLoading(true);
        playButton.setMode(RadarPlaybackButton.PAUSE);
        preloadPlaybackWindow();
        handler.post(playbackStep);
    }

    private void preloadPlaybackWindow() {
        if (disposed || !active || timeline == null || timeline.frames.isEmpty()) return;
        int count = timeline.frames.size();
        int current = Math.max(0, Math.min(frameIndex, count - 1));
        for (int offset = 0; offset < Math.min(3, count); offset++) {
            RadarDataClient.Frame item = timeline.frames.get((current + offset) % count);
            map.ensureFrame(item, null);
            if (offset == 0 && map.frameReady(item)) map.setFallbackFrame(item);
        }
    }

    private void continuePlaybackAfterViewportChange() {
        if (!active || disposed || timeline == null || timeline.frames.size() < 2) return;
        map.setManagedRadarLoading(true);
        if (preparing) {
            preparationStartedAt = SystemClock.uptimeMillis();
            checkPreparation(preparationGeneration);
            return;
        }
        if (playing) preloadPlaybackWindow();
    }

    private void schedulePreparationCheck(int generation) {
        if (preparationTickScheduled) return;
        preparationTickScheduled = true;
        handler.postDelayed(() -> {
            preparationTickScheduled = false;
            checkPreparation(generation);
        }, 500L);
    }

    private void stopPlayback() {
        if (playing || preparing || pendingPlay)
            DiagnosticLog.event(DiagnosticLog.Area.RADAR, DiagnosticLog.Event.PLAYBACK_STOPPED);
        boolean interruptedPreparation = preparing || pendingPlay;
        playing = false;
        preparing = false;
        pendingPlay = false;
        preparationTickScheduled = false;
        playbackClock.reset();
        preparationGeneration++;
        map.setManagedRadarLoading(false);
        handler.removeCallbacks(playbackStep);
        handler.removeCallbacks(applyScrub);
        if (playButton != null) playButton.setMode(RadarPlaybackButton.PLAY);
        if (interruptedPreparation && timeline != null) {
            setSummary("");
        }
    }

    private void onViewportChanged() {
        if (disposed) return;
        // Coordinate-keyed tiles remain valid across pans; do not cancel useful
        // in-flight downloads every time the camera stops.
        map.invalidate();
        if (!active) return;
        scheduleViewportLoad(VIEWPORT_COALESCE_MS);
    }

    private void scheduleViewportLoad(long delayMillis) {
        if (disposed || !active) return;
        final int generation = ++viewportLoadGeneration;
        cancelViewportWork();
        viewportLoadTask = () -> {
            viewportLoadTask = null;
            runViewportLoad(generation, 0);
        };
        handler.postDelayed(viewportLoadTask, Math.max(0L, delayMillis));
    }

    private void runViewportLoad(int generation, int attempt) {
        if (disposed || !active || generation != viewportLoadGeneration) return;
        if (map.isViewportInMotion()) {
            viewportRetryTask = () -> {
                viewportRetryTask = null;
                runViewportLoad(generation, attempt);
            };
            handler.postDelayed(viewportRetryTask, VIEWPORT_COALESCE_MS);
            return;
        }
        map.invalidate();
        if (timeline == null || timeline.frames.isEmpty() || frameIndex < 0) return;

        RadarDataClient.Frame current = timeline.frames.get(
                Math.max(0, Math.min(frameIndex, timeline.frames.size() - 1)));
        if (preparing || playing) {
            if (attempt == 0) continuePlaybackAfterViewportChange();
            else preloadPlaybackWindow();
        }
        else map.ensureFrame(current, map::invalidate);

        if (map.baseTilesReady() && map.frameReady(current)) {
            mapMessage.setVisibility(GONE);
            return;
        }

        if (attempt >= MAX_VIEWPORT_RETRIES && !playing && !preparing && data.radarCooldownMillis() == 0L) {
            String tileError = data.lastRadarError();
            showMapRetryMessage(tileError.isEmpty()
                    ? "Some map tiles did not load."
                    : tileError);
            viewportRetryTask = () -> {
                viewportRetryTask = null;
                if (disposed || !active || generation != viewportLoadGeneration) return;
                if (map.baseTilesReady() && map.frameReady(current)) {
                    mapMessage.setVisibility(GONE);
                }
            };
            handler.postDelayed(viewportRetryTask, TIMELINE_WATCHDOG_MS);
            return;
        }

        viewportRetryTask = () -> {
            viewportRetryTask = null;
            if (disposed || !active || generation != viewportLoadGeneration) return;
            runViewportLoad(generation, attempt + 1);
        };
        handler.postDelayed(viewportRetryTask, VIEWPORT_RETRY_MS);
    }

    private void cancelViewportWork() {
        if (viewportLoadTask != null) {
            handler.removeCallbacks(viewportLoadTask);
            viewportLoadTask = null;
        }
        if (viewportRetryTask != null) {
            handler.removeCallbacks(viewportRetryTask);
            viewportRetryTask = null;
        }
    }

    private void openUrl(String url) {
        try { getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception ignored) { }
    }

    private TextView label(String value, float size, boolean bold, int color) {
        TextView view = new TextView(getContext());
        view.setText(UiTranslations.text(getContext(), value));
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }

    private Button control(String value, String description) {
        Button button = new Button(getContext());
        button.setText(UiTranslations.text(getContext(), value));
        button.setAllCaps(false);
        button.setTextSize(20f);
        button.setTextColor(Color.WHITE);
        button.setPadding(0, 0, 0, 0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        surfaceControls.add(button);
        button.setContentDescription(UiTranslations.text(getContext(), description));
        return button;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
