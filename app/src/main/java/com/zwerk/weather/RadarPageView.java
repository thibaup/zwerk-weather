package com.zwerk.weather;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** A complete, on-demand radar page with map, timeline and source credit. */
final class RadarPageView extends LinearLayout {
    private static final DateTimeFormatter FRAME_TIME =
            DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault());
    private static final long TIMELINE_WATCHDOG_MS = 18_000L;
    private static final long VIEWPORT_COALESCE_MS = 140L;
    private static final long VIEWPORT_RETRY_MS = 1_500L;
    private static final int MAX_VIEWPORT_RETRIES = 3;
    private final RadarDataClient data;
    private final LinearLayout panel;
    private final FrameLayout mapHolder;
    private final RadarMapView map;
    private final TextView summary;
    private final TextView frameTime;
    private final TextView mapMessage;
    private final TextView radarCredit;
    private final TextView hint;
    private final TextView rainViewerOption;
    private final TextView googleOption;
    private final View legendBar;
    private final LinearLayout legendLabels;
    private final RadarPlaybackButton playButton;
    private final LinearLayout playbackRow;
    private final Button fullscreenButton;
    private final SeekBar timelineBar;
    private final ProgressBar loading;
    private final RadarRefreshButton refreshButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private RadarDataClient.Timeline timeline;
    private int frameIndex = -1;
    private boolean active;
    private String source = RadarProviderConfig.RAINVIEWER;
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
    private Dialog fullscreenDialog;
    private boolean fullscreen;
    private int mapHolderPanelIndex = -1;
    private int playbackRowPanelIndex = -1;
    private int frameTimePanelIndex = -1;
    private ViewGroup.LayoutParams mapHolderPanelLayoutParams;
    private ViewGroup.LayoutParams playbackRowPanelLayoutParams;
    private ViewGroup.LayoutParams frameTimePanelLayoutParams;
    private final Runnable applyScrub = () -> showFrame(pendingScrubIndex, true);
    private final Runnable playbackStep = new Runnable() {
        @Override public void run() {
            if (disposed || !active || !playing || timeline == null || timeline.frames.isEmpty()) return;
            int nextIndex = (frameIndex + 1) % timeline.frames.size();
            showFrame(nextIndex, false);
            preloadPlaybackWindow();
            handler.postDelayed(this, 650L);
        }
    };

    RadarPageView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(0, dp(8), 0, dp(16));
        data = new RadarDataClient(context);

        panel = new LinearLayout(context);
        panel.setOrientation(VERTICAL);
        panel.setPadding(dp(14), dp(16), dp(14), dp(16));
        panel.setBackground(panelBackground(
                Color.argb(150, 32, 82, 142), Color.argb(126, 18, 55, 107)));
        addView(panel, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(VERTICAL);
        heading.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView title = label("Rain radar", 23, true, Color.WHITE);
        titles.addView(title);
        summary = label("", 13, false,
                Color.argb(220, 231, 242, 255));
        summary.setPadding(0, dp(3), 0, 0);
        summary.setVisibility(GONE);
        titles.addView(summary);
        refreshButton = new RadarRefreshButton(context);
        refreshButton.setContentDescription(UiTranslations.text(getContext(), "Refresh radar frames"));
        refreshButton.setOnClickListener(v -> loadTimeline(true));
        heading.addView(refreshButton, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout sourceSelector = new LinearLayout(context);
        sourceSelector.setPadding(dp(4), dp(4), dp(4), dp(4));
        sourceSelector.setBackground(panelBackground(
                Color.argb(105, 10, 43, 78), Color.argb(105, 9, 35, 67)));
        LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(-1, dp(62));
        sourceParams.topMargin = dp(13);
        panel.addView(sourceSelector, sourceParams);
        rainViewerOption = sourceOption("RainViewer\nRecommended",
                RadarProviderConfig.RAINVIEWER);
        sourceSelector.addView(rainViewerOption, new LinearLayout.LayoutParams(0, -1, 1f));
        googleOption = sourceOption("Google\nCurrent map", RadarProviderConfig.GOOGLE);
        LinearLayout.LayoutParams googleParams = new LinearLayout.LayoutParams(0, -1, 1f);
        googleParams.leftMargin = dp(4);
        sourceSelector.addView(googleOption, googleParams);
        mapHolder = new FrameLayout(context);
        LinearLayout.LayoutParams mapParams = new LinearLayout.LayoutParams(-1, dp(410));
        mapParams.topMargin = dp(16);
        panel.addView(mapHolder, mapParams);
        GradientDrawable mapBorder = panelBackground(
                Color.rgb(13, 44, 78), Color.rgb(7, 28, 56));
        mapBorder.setCornerRadius(dp(18));
        mapHolder.setBackground(mapBorder);
        mapHolder.setClipToOutline(true);

        map = new RadarMapView(context, data, this::onViewportChanged);
        mapHolder.addView(map, new FrameLayout.LayoutParams(-1, -1));
        map.setContentDescription(UiTranslations.text(getContext(),
                "Interactive precipitation radar map. Drag to pan."));

        LinearLayout mapControls = new LinearLayout(context);
        mapControls.setOrientation(VERTICAL);
        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(
                dp(44), -2, Gravity.TOP | Gravity.END);
        controlsParams.setMargins(0, dp(12), dp(12), 0);
        mapHolder.addView(mapControls, controlsParams);
        Button zoomIn = control("+", "Zoom in on radar");
        zoomIn.setOnClickListener(v -> map.zoomBy(1));
        mapControls.addView(zoomIn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        Button zoomOut = control("−", "Zoom out on radar");
        zoomOut.setOnClickListener(v -> map.zoomBy(-1));
        LinearLayout.LayoutParams zoomOutParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        zoomOutParams.topMargin = dp(6);
        mapControls.addView(zoomOut, zoomOutParams);
        Button recenter = control("⌖", "Center radar on selected location");
        recenter.setOnClickListener(v -> map.recenter());
        LinearLayout.LayoutParams recenterParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        recenterParams.topMargin = dp(6);
        mapControls.addView(recenter, recenterParams);
        fullscreenButton = control("⛶", "Enter fullscreen radar");
        fullscreenButton.setOnClickListener(v -> toggleFullscreen());
        LinearLayout.LayoutParams fullscreenParams =
                new LinearLayout.LayoutParams(dp(44), dp(44));
        fullscreenParams.topMargin = dp(6);
        mapControls.addView(fullscreenButton, fullscreenParams);

        loading = new ProgressBar(context);
        loading.setVisibility(GONE);
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(
                dp(42), dp(42), Gravity.CENTER);
        mapHolder.addView(loading, loadingParams);

        mapMessage = label("", 14, false, Color.WHITE);
        mapMessage.setGravity(Gravity.CENTER);
        mapMessage.setPadding(dp(16), dp(10), dp(16), dp(10));
        mapMessage.setBackground(panelBackground(
                Color.argb(210, 24, 57, 103), Color.argb(210, 12, 40, 79)));
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
        mapHolder.addView(mapMessage, messageParams);

        LinearLayout credits = new LinearLayout(context);
        credits.setGravity(Gravity.CENTER_VERTICAL);
        credits.setPadding(dp(9), dp(4), dp(9), dp(4));
        credits.setBackgroundColor(Color.argb(204, 8, 27, 51));
        FrameLayout.LayoutParams creditsParams = new FrameLayout.LayoutParams(
                -1, dp(30), Gravity.BOTTOM);
        mapHolder.addView(credits, creditsParams);
        TextView osmCredit = label("© OpenStreetMap contributors", 10, false,
                Color.argb(238, 243, 248, 255));
        osmCredit.setOnClickListener(v -> openUrl("https://www.openstreetmap.org/copyright"));
        osmCredit.setContentDescription(UiTranslations.text(getContext(),
                "Map by OpenStreetMap contributors. Open copyright page."));
        credits.addView(osmCredit, new LinearLayout.LayoutParams(0, -2, 1f));
        radarCredit = label("Radar by RainViewer ↗", 10, false,
                Color.argb(255, 168, 219, 255));
        radarCredit.setGravity(Gravity.END);
        radarCredit.setOnClickListener(v -> openUrl("https://www.rainviewer.com/"));
        credits.addView(radarCredit, new LinearLayout.LayoutParams(0, -2, 1f));

        playbackRow = new LinearLayout(context);
        playbackRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams playbackParams = new LinearLayout.LayoutParams(-1, dp(54));
        playbackParams.topMargin = dp(15);
        panel.addView(playbackRow, playbackParams);
        playButton = new RadarPlaybackButton(context);
        playButton.setOnClickListener(v -> togglePlayback());
        playbackRow.addView(playButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        timelineBar = new SeekBar(context);
        timelineBar.setMax(0);
        timelineBar.setEnabled(false);
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
        latest.setTextSize(12f);
        latest.setOnClickListener(v -> {
            stopPlayback();
            if (timeline != null) showFrame(timeline.frames.size() - 1, true);
        });
        playbackRow.addView(latest, new LinearLayout.LayoutParams(dp(54), dp(42)));

        frameTime = label("Radar timeline", 14, true, Color.WHITE);
        frameTime.setPadding(dp(3), 0, 0, 0);
        panel.addView(frameTime);
        hint = label("", 12, false, Color.argb(218, 221, 238, 252));
        hint.setVisibility(GONE);
        panel.addView(hint);
        legendBar = new View(context);
        GradientDrawable legendBackground = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(125, 210, 246), Color.rgb(47, 119, 223),
                        Color.rgb(23, 68, 161), Color.rgb(92, 43, 152)});
        legendBackground.setCornerRadius(dp(5));
        legendBar.setBackground(legendBackground);
        LinearLayout.LayoutParams legendParams = new LinearLayout.LayoutParams(-1, dp(7));
        legendParams.topMargin = dp(14);
        legendParams.leftMargin = dp(3);
        legendParams.rightMargin = dp(3);
        panel.addView(legendBar, legendParams);
        legendLabels = new LinearLayout(context);
        legendLabels.setPadding(dp(3), dp(3), dp(3), 0);
        TextView light = label("Light precipitation", 11, false,
                Color.argb(220, 234, 246, 255));
        TextView heavy = label("Heavy precipitation", 11, false,
                Color.argb(220, 234, 246, 255));
        heavy.setGravity(Gravity.END);
        legendLabels.addView(light, new LinearLayout.LayoutParams(0, -2, 1f));
        legendLabels.addView(heavy, new LinearLayout.LayoutParams(0, -2, 1f));
        panel.addView(legendLabels);
        updateSourceSelector();
    }

    void setStatusChangedListener(Runnable listener) {
        statusChanged = listener == null ? () -> { } : listener;
    }

    String statusText() {
        if (timelineLoading) {
            return RadarProviderConfig.GOOGLE.equals(source)
                    ? "Loading precipitation map…" : "Loading radar data…";
        }
        if (timeline == null) {
            return timelineError.isEmpty() ? "Radar data not loaded" : "Radar data unavailable";
        }
        String prefix = RadarProviderConfig.GOOGLE.equals(source)
                ? "Google precipitation map" : "Radar data";
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
        summary.setText(UiTranslations.text(getContext(), value));
        summary.setVisibility(value.isEmpty() ? GONE : VISIBLE);
    }

    void setLocation(double latitude, double longitude, String name) {
        if (disposed) return;
        boolean changed = map.locationChanged(latitude, longitude);
        data.setLocation(latitude, longitude);
        if (!changed) return;

        map.setLocation(latitude, longitude);
        timelineError = "";
        mapMessage.setVisibility(GONE);

        if (RadarProviderConfig.GOOGLE.equals(source)) {
            stopPlayback();
            timeline = null;
            frameIndex = -1;
            map.setFrame(null, null);
            notifyStatusChanged();
            if (active) loadTimeline(false);
            return;
        }

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
        data.setActive(value);
        if (value) {
            configureSource();
            loadTimeline(false);
        } else {
            timelineLoadGeneration++;
            viewportLoadGeneration++;
            timelineLoading = false;
            cancelTimelineWatchdog();
            cancelViewportWork();
            exitFullscreen();
            stopPlayback();
            refreshButton.setLoading(false);
            loading.setVisibility(GONE);
            notifyStatusChanged();
        }
    }

    void reloadSource() {
        if (!disposed && configureSource() && active) loadTimeline(false);
    }

    private boolean configureSource() {
        if (disposed) return false;
        updateSourceSelector();
        String next = RadarProviderConfig.source(getContext());
        if (source.equals(next)) return false;
        timelineLoadGeneration++;
        viewportLoadGeneration++;
        cancelTimelineWatchdog();
        cancelViewportWork();
        stopPlayback();
        source = next;
        timeline = null;
        timelineError = "";
        frameIndex = -1;
        map.setFrame(null, null);
        data.setSource(next);
        boolean google = RadarProviderConfig.GOOGLE.equals(next);
        updateSourceSelector();
        playbackRow.setVisibility(google ? GONE : VISIBLE);
        legendBar.setVisibility(google ? GONE : VISIBLE);
        legendLabels.setVisibility(google ? GONE : VISIBLE);
        radarCredit.setText(UiTranslations.text(getContext(),
                google ? "Weather map by Google ↗" : "Radar by RainViewer ↗"));
        radarCredit.setOnClickListener(v -> openUrl(google
                ? "https://developers.google.com/maps/documentation/weather/weather-map"
                : "https://www.rainviewer.com/"));
        hint.setText("");
        hint.setVisibility(GONE);
        frameTime.setText(UiTranslations.text(getContext(),
                google ? "Current precipitation" : "Radar timeline"));
        notifyStatusChanged();
        return true;
    }

    private TextView sourceOption(String title, String nextSource) {
        String[] lines = title.split("\n", 2);
        String localized = UiTranslations.text(getContext(), lines[0]) + "\n"
                + UiTranslations.text(getContext(), lines[1]);
        TextView option = label(localized, 13, true, Color.WHITE);
        option.setGravity(Gravity.CENTER);
        option.setClickable(true);
        option.setFocusable(true);
        option.setContentDescription(localized.replace('\n', ' '));
        option.setOnClickListener(v -> {
            if (nextSource.equals(source)) return;
            RadarProviderConfig.setSource(getContext(), nextSource);
            configureSource();
            loadTimeline(false);
        });
        return option;
    }

    private void updateSourceSelector() {
        googleOption.setVisibility(OpenMeteoConfig.isOpenMeteo(getContext()) ? GONE : VISIBLE);
        boolean google = RadarProviderConfig.GOOGLE.equals(source);
        styleSourceOption(rainViewerOption, !google);
        styleSourceOption(googleOption, google);
    }

    private void styleSourceOption(TextView option, boolean selected) {
        GradientDrawable background = panelBackground(
                selected ? Color.argb(215, 78, 145, 208) : Color.TRANSPARENT,
                selected ? Color.argb(205, 40, 105, 175) : Color.TRANSPARENT);
        background.setCornerRadius(dp(17));
        background.setStroke(dp(1), selected
                ? Color.argb(105, 255, 255, 255) : Color.TRANSPARENT);
        option.setBackground(background);
        option.setTextColor(selected ? Color.WHITE : Color.argb(210, 220, 235, 249));
        option.setSelected(selected);
    }

    void setPalette(int top, int bottom) {
        View panel = getChildCount() > 0 ? getChildAt(0) : null;
        if (panel != null) panel.setBackground(panelBackground(top, bottom));
    }

    void refresh() {
        if (disposed) return;
        configureSource();
        retryRadarLoad(true);
    }

    void dispose() {
        if (disposed) return;
        disposed = true;
        active = false;
        timelineLoadGeneration++;
        viewportLoadGeneration++;
        data.setActive(false);
        cancelTimelineWatchdog();
        cancelViewportWork();
        exitFullscreen();
        stopPlayback();
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
        setSummary(RadarProviderConfig.GOOGLE.equals(source)
                ? "Loading current precipitation map…" : "Checking the latest radar frames…");
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
            if (RadarProviderConfig.GOOGLE.equals(source)) {
                final int expectedGeneration = loadGeneration;
                handler.postDelayed(() -> {
                    if (disposed || !active
                            || expectedGeneration != timelineLoadGeneration
                            || timeline != result || map.hasRadarContent()) {
                        return;
                    }
                    String tileError = data.lastRadarError();
                    if (!tileError.isEmpty()) {
                        showMapRetryMessage(tileError);
                    }
                }, 5_500L);
            }
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
            // Invalidate the callback from the timed-out page request. The data client has
            // shorter HTTP timeouts, so this is a final UI recovery guard, not cancellation.
            timelineLoadGeneration++;
            timelineLoading = false;
            pendingPlay = false;
            refreshButton.setLoading(false);
            loading.setVisibility(GONE);
            playButton.setMode(RadarPlaybackButton.PLAY);
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
        String time = FRAME_TIME.format(Instant.ofEpochSecond(frame.timeSeconds)
                .atZone(ZoneId.systemDefault()));
        frameTime.setText(UiTranslations.text(getContext(), RadarProviderConfig.GOOGLE.equals(source)
                ? "Current" : frameIndex == timeline.frames.size() - 1 ? "Latest" : "Past")
                + " · " + time);
        if (userSelected) mapMessage.setVisibility(GONE);
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
        preparationTickScheduled = false;
        preparationStartedAt = System.currentTimeMillis();
        int generation = ++preparationGeneration;
        playButton.setMode(RadarPlaybackButton.LOADING);
        setSummary("Preparing radar animation…");
        checkPreparation(generation);
    }

    private void checkPreparation(int generation) {
        if (generation != preparationGeneration || !preparing || !active
                || disposed || timeline == null) return;
        if (map.getWidth() == 0 || map.getHeight() == 0) {
            if (System.currentTimeMillis() - preparationStartedAt > 5_000L) {
                preparing = false;
                playButton.setMode(RadarPlaybackButton.PLAY);
                setSummary("Radar map is not ready · tap play to retry");
                return;
            }
            setSummary("Waiting for radar map layout…");
            schedulePreparationCheck(generation);
            return;
        }
        // A full timeline can exceed the bitmap cache. Loading every frame at once can
        // evict the first frame before the last one finishes, leaving playback at 12/13.
        // Prepare only the visible frame and the next two, then stream the rest.
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
        if (ready < 2 && System.currentTimeMillis() - preparationStartedAt < 10_000L) {
            setSummary(String.format(Locale.getDefault(),
                    UiTranslations.text(getContext(), "Preparing animation · %d/%d frames"),
                    ready, window));
            schedulePreparationCheck(generation);
            return;
        }
        preparing = false;
        if (ready < 2) {
            playButton.setMode(RadarPlaybackButton.PLAY);
            setSummary("Animation could not load · tap play to retry");
            return;
        }
        setSummary("");
        playing = true;
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
            RadarDataClient.Frame frame = timeline.frames.get((current + offset) % count);
            map.ensureFrame(frame, map::invalidate);
            if (offset == 0 && map.frameReady(frame)) map.setFallbackFrame(frame);
        }
    }

    private void continuePlaybackAfterViewportChange() {
        if (!active || disposed || timeline == null || timeline.frames.size() < 2) return;
        map.setManagedRadarLoading(true);
        if (preparing) {
            preparationTickScheduled = false;
            preparationStartedAt = System.currentTimeMillis();
            int generation = ++preparationGeneration;
            checkPreparation(generation);
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
        boolean interruptedPreparation = preparing || pendingPlay;
        playing = false;
        preparing = false;
        pendingPlay = false;
        preparationTickScheduled = false;
        preparationGeneration++;
        map.setManagedRadarLoading(false);
        handler.removeCallbacks(playbackStep);
        handler.removeCallbacks(applyScrub);
        if (playButton != null) playButton.setMode(RadarPlaybackButton.PLAY);
        if (interruptedPreparation && timeline != null
                && RadarProviderConfig.RAINVIEWER.equals(source)) {
            setSummary("");
        }
    }

    private void onViewportChanged() {
        if (disposed) return;
        data.invalidateViewportRequests();
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
        map.invalidate();
        if (timeline == null || timeline.frames.isEmpty() || frameIndex < 0) return;

        RadarDataClient.Frame current = timeline.frames.get(
                Math.max(0, Math.min(frameIndex, timeline.frames.size() - 1)));
        if (preparing || playing) continuePlaybackAfterViewportChange();
        else map.ensureFrame(current, map::invalidate);

        if (map.baseTilesReady() && map.frameReady(current)) {
            mapMessage.setVisibility(GONE);
            return;
        }

        if (attempt >= MAX_VIEWPORT_RETRIES) {
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
            data.retryFailedTiles();
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

    private void toggleFullscreen() {
        if (fullscreen) exitFullscreen();
        else enterFullscreen();
    }

    private void enterFullscreen() {
        if (fullscreen || mapHolder.getParent() != panel
                || playbackRow.getParent() != panel || frameTime.getParent() != panel) {
            return;
        }

        mapHolderPanelIndex = panel.indexOfChild(mapHolder);
        playbackRowPanelIndex = panel.indexOfChild(playbackRow);
        frameTimePanelIndex = panel.indexOfChild(frameTime);
        mapHolderPanelLayoutParams = mapHolder.getLayoutParams();
        playbackRowPanelLayoutParams = playbackRow.getLayoutParams();
        frameTimePanelLayoutParams = frameTime.getLayoutParams();

        panel.removeView(frameTime);
        panel.removeView(playbackRow);
        panel.removeView(mapHolder);

        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(VERTICAL);
        root.setPadding(0, 0, 0, dp(8));
        root.setBackgroundColor(Color.rgb(7, 28, 56));

        mapHolder.setClipToOutline(false);
        root.addView(mapHolder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout footer = new LinearLayout(getContext());
        footer.setOrientation(VERTICAL);
        footer.setPadding(dp(12), dp(8), dp(12), 0);
        footer.setBackgroundColor(Color.argb(245, 8, 27, 51));
        root.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        frameTime.setPadding(dp(3), 0, 0, dp(3));
        footer.addView(frameTime, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
        footer.addView(playbackRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        final Dialog dialog = new Dialog(getContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(ignored -> restoreFullscreenViews());

        fullscreen = true;
        fullscreenDialog = dialog;
        fullscreenButton.setText("×");
        fullscreenButton.setContentDescription(UiTranslations.text(getContext(),
                "Exit fullscreen radar"));

        try {
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.rgb(7, 28, 56)));
                window.setLayout(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT);
                window.setStatusBarColor(Color.rgb(7, 28, 56));
                window.setNavigationBarColor(Color.rgb(7, 28, 56));
            }
            map.post(this::onViewportChanged);
        } catch (RuntimeException error) {
            restoreFullscreenViews();
        }
    }

    private void exitFullscreen() {
        Dialog dialog = fullscreenDialog;
        if (!fullscreen) return;
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        } else {
            restoreFullscreenViews();
        }
    }

    private void restoreFullscreenViews() {
        if (!fullscreen) return;
        fullscreen = false;

        detachFromParent(frameTime);
        detachFromParent(playbackRow);
        detachFromParent(mapHolder);

        mapHolder.setClipToOutline(true);
        int mapIndex = Math.max(0, Math.min(mapHolderPanelIndex, panel.getChildCount()));
        panel.addView(mapHolder, mapIndex, mapHolderPanelLayoutParams);
        int playbackIndex = Math.max(0, Math.min(playbackRowPanelIndex, panel.getChildCount()));
        panel.addView(playbackRow, playbackIndex, playbackRowPanelLayoutParams);
        int timeIndex = Math.max(0, Math.min(frameTimePanelIndex, panel.getChildCount()));
        panel.addView(frameTime, timeIndex, frameTimePanelLayoutParams);
        frameTime.setPadding(dp(3), 0, 0, 0);

        fullscreenButton.setText("⛶");
        fullscreenButton.setContentDescription(UiTranslations.text(getContext(),
                "Enter fullscreen radar"));
        fullscreenDialog = null;
        mapHolderPanelLayoutParams = null;
        playbackRowPanelLayoutParams = null;
        frameTimePanelLayoutParams = null;
        if (active) map.post(this::onViewportChanged);
    }

    private void detachFromParent(View view) {
        if (view == null) return;
        android.view.ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(view);
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
        button.setBackground(panelBackground(
                Color.argb(190, 68, 130, 190), Color.argb(190, 36, 91, 158)));
        button.setContentDescription(UiTranslations.text(getContext(), description));
        return button;
    }

    private GradientDrawable panelBackground(int top, int bottom) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        drawable.setCornerRadius(dp(22));
        drawable.setStroke(dp(1), Color.argb(75, 255, 255, 255));
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

/** Drawn refresh control keeps the circular arrow optically centered in a 48dp target. */
final class RadarRefreshButton extends View {
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private ObjectAnimator spinner;

    RadarRefreshButton(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(190, 68, 130, 190), Color.argb(190, 36, 91, 158)});
        background.setCornerRadius(dp(22));
        background.setStroke(dp(1), Color.argb(75, 255, 255, 255));
        setBackground(background);
    }

    void setLoading(boolean loading) {
        if (loading) {
            if (spinner != null && spinner.isRunning()) return;
            spinner = ObjectAnimator.ofFloat(this, View.ROTATION, 0f, 360f);
            spinner.setDuration(850L);
            spinner.setInterpolator(new LinearInterpolator());
            spinner.setRepeatCount(ValueAnimator.INFINITE);
            spinner.setRepeatMode(ValueAnimator.RESTART);
            spinner.start();
        } else {
            if (spinner != null) {
                spinner.cancel();
                spinner = null;
            }
            setRotation(0f);
        }
    }

    @Override protected void onDetachedFromWindow() {
        setLoading(false);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = dp(9f);
        icon.setColor(Color.WHITE);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeWidth(dp(2.6f));
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeJoin(Paint.Join.ROUND);
        RectF arc = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(arc, -55f, 285f, false, icon);

        double end = Math.toRadians(230d);
        float tipX = cx + (float) Math.cos(end) * radius;
        float tipY = cy + (float) Math.sin(end) * radius;
        float tangentX = (float) -Math.sin(end);
        float tangentY = (float) Math.cos(end);
        float normalX = (float) Math.cos(end);
        float normalY = (float) Math.sin(end);
        float backX = tipX - tangentX * dp(6f);
        float backY = tipY - tangentY * dp(6f);
        arrow.reset();
        arrow.moveTo(tipX, tipY);
        arrow.lineTo(backX + normalX * dp(3f), backY + normalY * dp(3f));
        arrow.moveTo(tipX, tipY);
        arrow.lineTo(backX - normalX * dp(3f), backY - normalY * dp(3f));
        canvas.drawPath(arrow, icon);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

/** Drawn controls keep the play, pause and loading symbols optically centered. */
final class RadarPlaybackButton extends View {
    static final int PLAY = 0;
    static final int PAUSE = 1;
    static final int LOADING = 2;
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path triangle = new Path();
    private int mode = PLAY;

    RadarPlaybackButton(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(190, 68, 130, 190), Color.argb(190, 36, 91, 158)});
        background.setCornerRadius(dp(22));
        background.setStroke(dp(1), Color.argb(75, 255, 255, 255));
        setBackground(background);
        setMode(PLAY);
    }

    void setMode(int next) {
        mode = next;
        setContentDescription(UiTranslations.text(getContext(), next == PAUSE
                ? "Pause radar animation"
                : next == LOADING ? "Radar animation loading. Tap to cancel playback."
                : "Play radar animation"));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        icon.setColor(Color.WHITE);
        icon.setStyle(Paint.Style.FILL);
        if (mode == PAUSE) {
            canvas.drawRoundRect(cx - dp(7), cy - dp(9), cx - dp(2), cy + dp(9),
                    dp(1.5f), dp(1.5f), icon);
            canvas.drawRoundRect(cx + dp(2), cy - dp(9), cx + dp(7), cy + dp(9),
                    dp(1.5f), dp(1.5f), icon);
        } else if (mode == LOADING) {
            icon.setStyle(Paint.Style.STROKE);
            icon.setStrokeWidth(dp(3));
            icon.setStrokeCap(Paint.Cap.ROUND);
            float turn = (SystemClock.uptimeMillis() % 1000L) * 0.36f;
            canvas.drawArc(new RectF(cx - dp(9), cy - dp(9), cx + dp(9), cy + dp(9)),
                    turn, 250f, false, icon);
            postInvalidateDelayed(32L);
        } else {
            triangle.reset();
            triangle.moveTo(cx - dp(5), cy - dp(9));
            triangle.lineTo(cx + dp(8), cy);
            triangle.lineTo(cx - dp(5), cy + dp(9));
            triangle.close();
            canvas.drawPath(triangle, icon);
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

/** Pan/zoom Web-Mercator map with viewport-aligned radar tiles. */
final class RadarMapView extends View {
    private static final int TILE_PX = 512;
    private final RadarDataClient data;
    private final Runnable viewportChanged;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholderPaint = new Paint();
    private final ScaleGestureDetector scaleDetector;
    private RadarDataClient.Timeline timeline;
    private RadarDataClient.Frame frame;
    private RadarDataClient.Frame fallbackFrame;
    private double locationLat = 50.8503;
    private double locationLon = 4.3517;
    private double centerLat = locationLat;
    private double centerLon = locationLon;
    private float displayZoom = 6f;
    private ValueAnimator zoomAnimator;
    private float lastX;
    private float lastY;
    private boolean dragging;
    private boolean gestureViewportChanged;
    private boolean managedRadarLoading;

    RadarMapView(Context context, RadarDataClient data, Runnable viewportChanged) {
        super(context);
        this.data = data;
        this.viewportChanged = viewportChanged;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                        if (zoomAnimator != null) zoomAnimator.cancel();
                        return true;
                    }

                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        float delta = (float) (Math.log(detector.getScaleFactor())
                                / Math.log(2d));
                        float before = displayZoom;
                        changeZoom(displayZoom + delta,
                                detector.getFocusX(), detector.getFocusY());
                        if (Math.abs(displayZoom - before) > 0.0001f) {
                            gestureViewportChanged = true;
                        }
                        return true;
                    }

                    @Override public void onScaleEnd(ScaleGestureDetector detector) {
                        // ACTION_UP/CANCEL publishes the final viewport once for tile preparation.
                    }
                });
    }

    boolean locationChanged(double lat, double lon) {
        return Math.abs(locationLat - clampLat(lat)) > 0.00001d
                || Math.abs(locationLon - wrapLon(lon)) > 0.00001d;
    }

    void setLocation(double lat, double lon) {
        if (zoomAnimator != null) zoomAnimator.cancel();
        locationLat = clampLat(lat);
        locationLon = wrapLon(lon);
        centerLat = locationLat;
        centerLon = locationLon;
        invalidate();
    }

    void setFrame(RadarDataClient.Timeline timeline, RadarDataClient.Frame frame) {
        if (this.timeline != timeline) fallbackFrame = null;
        this.timeline = timeline;
        this.frame = frame;
        if (frame == null) fallbackFrame = null;
        invalidate();
    }

    void setManagedRadarLoading(boolean managed) {
        if (managedRadarLoading == managed) return;
        managedRadarLoading = managed;
        invalidate();
    }

    void setFallbackFrame(RadarDataClient.Frame available) {
        if (available != null && timeline != null) fallbackFrame = available;
    }

    void zoomBy(int delta) {
        float next = Math.max(3f, Math.min(7f, displayZoom + delta));
        if (Math.abs(next - displayZoom) < 0.01f) return;
        if (zoomAnimator != null) zoomAnimator.cancel();
        ValueAnimator animation = ValueAnimator.ofFloat(displayZoom, next);
        zoomAnimator = animation;
        animation.setDuration(270L);
        animation.setInterpolator(new DecelerateInterpolator());
        animation.addUpdateListener(value -> changeZoom((float) value.getAnimatedValue(),
                getWidth() / 2f, getHeight() / 2f));
        animation.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override public void onAnimationCancel(Animator ended) {
                cancelled = true;
                if (zoomAnimator == ended) zoomAnimator = null;
            }

            @Override public void onAnimationEnd(Animator ended) {
                if (zoomAnimator == ended) zoomAnimator = null;
                if (!cancelled) viewportChanged.run();
                invalidate();
            }
        });
        animation.start();
    }

    private void changeZoom(float next, float focusX, float focusY) {
        next = Math.max(3f, Math.min(7f, next));
        if (Math.abs(next - displayZoom) < 0.001f) return;
        int oldZoom = tileZoom();
        double oldScale = mapScale();
        double focalLon = toLon((toWorldX(centerLon, oldZoom) * oldScale
                + focusX - getWidth() / 2d) / oldScale, oldZoom);
        double focalLat = toLat((toWorldY(centerLat, oldZoom) * oldScale
                + focusY - getHeight() / 2d) / oldScale, oldZoom);
        displayZoom = next;
        int nextZoom = tileZoom();
        double nextScale = mapScale();
        double world = TILE_PX * (1 << nextZoom) * nextScale;
        double nextCenterX = toWorldX(focalLon, nextZoom) * nextScale
                - focusX + getWidth() / 2d;
        double nextCenterY = toWorldY(focalLat, nextZoom) * nextScale
                - focusY + getHeight() / 2d;
        centerLon = toLon(nextCenterX / nextScale, nextZoom);
        centerLat = toLat(Math.max(0d, Math.min(world, nextCenterY))
                / nextScale, nextZoom);
        invalidate();
    }

    private int tileZoom() {
        return Math.max(3, Math.min(7, Math.round(displayZoom)));
    }

    private double mapScale() {
        return Math.pow(2d, displayZoom - tileZoom());
    }

    void recenter() {
        centerLat = locationLat;
        centerLon = locationLon;
        invalidate();
        viewportChanged.run();
    }

    boolean baseTilesReady() {
        if (getWidth() == 0 || getHeight() == 0) return false;
        int zoom = tileZoom();
        double scale = mapScale();
        double tilePx = TILE_PX * scale;
        double cx = toWorldX(centerLon, zoom) * scale;
        double cy = toWorldY(centerLat, zoom) * scale;
        int count = 1 << zoom;
        int firstX = (int) Math.floor((cx - getWidth() / 2d) / tilePx);
        int lastX = (int) Math.floor((cx + getWidth() / 2d) / tilePx);
        int firstY = Math.max(0,
                (int) Math.floor((cy - getHeight() / 2d) / tilePx));
        int lastY = Math.min(count - 1,
                (int) Math.floor((cy + getHeight() / 2d) / tilePx));
        for (int y = firstY; y <= lastY; y++) {
            for (int x = firstX; x <= lastX; x++) {
                if (data.baseTile(zoom, x, y, null, false) == null) return false;
            }
        }
        return true;
    }

    boolean frameReady(RadarDataClient.Frame requested) {
        if (timeline == null || requested == null || getWidth() == 0 || getHeight() == 0) {
            return false;
        }
        int radarZoom = radarZoom();
        int[] bounds = radarBounds(radarZoom);
        for (int y = bounds[2]; y <= bounds[3]; y++) {
            for (int x = bounds[0]; x <= bounds[1]; x++) {
                if (data.radarTile(timeline, requested, radarZoom, x, y,
                        null, false) == null) return false;
            }
        }
        return true;
    }

    boolean hasRadarContent() {
        if (timeline == null || frame == null) return false;
        int radarZoom = radarZoom();
        int[] bounds = radarBounds(radarZoom);
        for (int y = bounds[2]; y <= bounds[3]; y++) {
            for (int x = bounds[0]; x <= bounds[1]; x++) {
                if (data.radarTile(timeline, frame, radarZoom, x, y,
                        null, false) != null) return true;
            }
        }
        return false;
    }

    void ensureFrame(RadarDataClient.Frame requested, Runnable onReady) {
        if (timeline == null || requested == null || getWidth() == 0 || getHeight() == 0) return;
        int radarZoom = radarZoom();
        int[] bounds = radarBounds(radarZoom);
        for (int y = bounds[2]; y <= bounds[3]; y++) {
            for (int x = bounds[0]; x <= bounds[1]; x++) {
                data.radarTile(timeline, requested, radarZoom, x, y, onReady, true);
            }
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        canvas.drawColor(Color.rgb(14, 43, 74));
        int tileZoom = tileZoom();
        double scale = mapScale();
        double tilePx = TILE_PX * scale;
        double cx = toWorldX(centerLon, tileZoom) * scale;
        double cy = toWorldY(centerLat, tileZoom) * scale;
        int firstX = (int) Math.floor((cx - width / 2d) / tilePx);
        int lastX = (int) Math.floor((cx + width / 2d) / tilePx);
        int firstY = (int) Math.floor((cy - height / 2d) / tilePx);
        int lastY = (int) Math.floor((cy + height / 2d) / tilePx);
        for (int y = firstY; y <= lastY; y++) {
            for (int x = firstX; x <= lastX; x++) {
                float left = (float) (x * tilePx - cx + width / 2d);
                float top = (float) (y * tilePx - cy + height / 2d);
                RectF target = new RectF(left, top,
                        (float) (left + tilePx), (float) (top + tilePx));
                Bitmap tile = data.baseTile(tileZoom, x, y,
                        this::invalidate, !dragging);
                if (tile != null) canvas.drawBitmap(tile, null, target, bitmapPaint);
                else {
                    placeholderPaint.setColor(((x + y) & 1) == 0
                            ? Color.rgb(17, 50, 81) : Color.rgb(20, 56, 88));
                    canvas.drawRect(target, placeholderPaint);
                }
            }
        }
        if (timeline != null && frame != null) {
            int radarZoom = radarZoom();
            double radarTilePx = TILE_PX * (1 << (tileZoom - radarZoom)) * scale;
            int[] bounds = radarBounds(radarZoom);
            int totalTiles = Math.max(0, bounds[1] - bounds[0] + 1)
                    * Math.max(0, bounds[3] - bounds[2] + 1);
            int currentTiles = 0;
            bitmapPaint.setAlpha(225);
            for (int y = bounds[2]; y <= bounds[3]; y++) {
                for (int x = bounds[0]; x <= bounds[1]; x++) {
                    Bitmap radar = data.radarTile(timeline, frame, radarZoom, x, y,
                            this::invalidate, !dragging && !managedRadarLoading);
                    if (radar != null) {
                        currentTiles++;
                    } else if (fallbackFrame != null && fallbackFrame != frame) {
                        radar = data.radarTile(timeline, fallbackFrame, radarZoom, x, y,
                                null, false);
                    }
                    if (radar == null) continue;
                    float left = (float) (x * (double) radarTilePx - cx + width / 2d);
                    float top = (float) (y * (double) radarTilePx - cy + height / 2d);
                    canvas.drawBitmap(radar, null,
                            new RectF(left, top,
                                    (float) (left + radarTilePx),
                                    (float) (top + radarTilePx)),
                            bitmapPaint);
                }
            }
            if (totalTiles > 0 && currentTiles == totalTiles) fallbackFrame = frame;
            bitmapPaint.setAlpha(255);
        }
        float markerX = (float) (toWorldX(locationLon, tileZoom) * scale
                - cx + width / 2d);
        float markerY = (float) (toWorldY(locationLat, tileZoom) * scale
                - cy + height / 2d);
        if (markerX >= 0 && markerX <= width && markerY >= 0 && markerY <= height) {
            markerPaint.setColor(Color.argb(190, 12, 38, 71));
            canvas.drawCircle(markerX, markerY, dp(12), markerPaint);
            markerPaint.setColor(Color.WHITE);
            canvas.drawCircle(markerX, markerY, dp(5), markerPaint);
            markerPaint.setColor(Color.rgb(105, 200, 255));
            canvas.drawCircle(markerX, markerY, dp(3), markerPaint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                gestureViewportChanged = false;
                lastX = event.getX();
                lastY = event.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    int tileZoom = tileZoom();
                    double scale = mapScale();
                    double world = TILE_PX * (1 << tileZoom) * scale;
                    double nextX = toWorldX(centerLon, tileZoom) * scale - dx;
                    double nextY = Math.max(0, Math.min(world,
                            toWorldY(centerLat, tileZoom) * scale - dy));
                    centerLon = toLon(nextX / scale, tileZoom);
                    centerLat = toLat(nextY / scale, tileZoom);
                    if (Math.abs(dx) > 0.01f || Math.abs(dy) > 0.01f) {
                        gestureViewportChanged = true;
                    }
                    invalidate();
                }
                lastX = event.getX();
                lastY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                if (gestureViewportChanged) viewportChanged.run();
                gestureViewportChanged = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                return true;
            default:
                return true;
        }
    }

    private int radarZoom() {
        return Math.max(2, tileZoom() - 1);
    }

    private int[] radarBounds(int radarZoom) {
        int tileZoom = tileZoom();
        double scale = mapScale();
        double radarTilePx = TILE_PX * (1 << (tileZoom - radarZoom)) * scale;
        int maxY = (1 << radarZoom) - 1;
        double cx = toWorldX(centerLon, tileZoom) * scale;
        double cy = toWorldY(centerLat, tileZoom) * scale;
        return new int[]{
                (int) Math.floor((cx - getWidth() / 2d) / radarTilePx),
                (int) Math.floor((cx + getWidth() / 2d) / radarTilePx),
                Math.max(0, (int) Math.floor((cy - getHeight() / 2d) / radarTilePx)),
                Math.min(maxY, (int) Math.floor((cy + getHeight() / 2d) / radarTilePx))
        };
    }

    private static double toWorldX(double lon, int zoom) {
        return (wrapLon(lon) + 180d) / 360d * TILE_PX * (1 << zoom);
    }

    private static double toWorldY(double lat, int zoom) {
        double sin = Math.sin(Math.toRadians(clampLat(lat)));
        return (0.5d - Math.log((1d + sin) / (1d - sin)) / (4d * Math.PI))
                * TILE_PX * (1 << zoom);
    }

    private static double toLon(double worldX, int zoom) {
        double world = TILE_PX * (1 << zoom);
        return wrapLon(worldX / world * 360d - 180d);
    }

    private static double toLat(double worldY, int zoom) {
        double world = TILE_PX * (1 << zoom);
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1d - 2d * worldY / world))));
    }

    private static double clampLat(double value) {
        return Math.max(-85.05112878d, Math.min(85.05112878d, value));
    }

    private static double wrapLon(double value) {
        double wrapped = (value + 180d) % 360d;
        if (wrapped < 0d) wrapped += 360d;
        return wrapped - 180d;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
