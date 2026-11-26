package com.zwerk.weather;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;
import java.util.HashMap;

// Map data and the viewport callback are required when this programmatic view is created.
@android.annotation.SuppressLint("ViewConstructor")
final class RadarMapView extends View {
    private static final int TILE_PX = 512;
    private static final int INVALID_POINTER_ID = -1;
    private static final long FRAME_CROSSFADE_MS = 160L;
    private static final long ZOOM_LOAD_DELAY_MS = 350L;
    private static final double EARTH_EQUATORIAL_CIRCUMFERENCE_M = 40_075_016.68557849d;
    private static final double METERS_PER_MILE = 1609.344d;
    private static final double METERS_PER_FOOT = 0.3048d;
    private final RadarDataClient data;
    private final Runnable viewportChanged;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint basePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private boolean darkMap;
    private int radarOpacityPercent = 88;
    private int radarAlpha = Math.round(255f * 88 / 100f);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholderPaint = new Paint();
    private final Paint scaleBarOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scaleBarFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scaleTextOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scaleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path scaleBarPath = new Path();
    private final RectF drawRect = new RectF();
    private final int[] radarBounds = new int[4];
    private final Runnable tileReady = this::onTileReady;
    private final ScaleGestureDetector scaleDetector;
    private final OverScroller scroller;
    private final int touchSlop;
    private final int doubleTapSlopSq;
    private final int doubleTapTimeout;
    private final int tapTimeout;
    private final int minimumFlingVelocity;
    private final int maximumFlingVelocity;
    private RadarDataClient.Timeline timeline;
    private RadarDataClient.Frame frame;
    private RadarDataClient.Frame displayedFrame;
    private RadarDataClient.Frame transitionFromFrame;
    private long transitionStartedAt;
    private double locationLat = 50.8503;
    private double locationLon = 4.3517;
    private double centerLat = locationLat;
    private double centerLon = locationLon;
    private float displayZoom = 6f;
    private String tileDemandSignature = "";
    private long radarZoomSettlesAt;
    private final Runnable resumeRadarAfterZoom = this::resumeRadarAfterZoom;
    private ValueAnimator zoomAnimator;
    private VelocityTracker velocityTracker;
    private int activePointerId = INVALID_POINTER_ID;
    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private long downTime;
    private long lastTapUpTime;
    private float lastTapX;
    private float lastTapY;
    private boolean doubleTapCandidate;
    private boolean doubleTapDragZooming;
    private float doubleTapFocusX;
    private float doubleTapFocusY;
    private boolean inheritedChangeOnDown;
    private boolean dragging;
    private boolean movedBeyondSlop;
    private boolean scaledDuringGesture;
    private boolean gestureViewportChanged;
    private boolean managedRadarLoading;
    private boolean flingRunning;
    private Runnable frameDisplayListener;
    private int flingLastX;
    private int flingLastY;
    private int scaleBarLeftInset;
    private int scaleBarTopInset;
    private int scaleBarRightInset;
    private int scaleBarBottomInset;
    private boolean imperialScale;
    private double cachedScaleZoom = Double.NaN;
    private double cachedScaleLat = Double.NaN;
    private float cachedScaleMaxBarPx = -1f;
    private boolean cachedScaleImperial;
    private float cachedScaleBarPx;
    private String cachedScaleLabel = "";
    private final RetainedLayer retainedBase = new RetainedLayer();
    private final RetainedLayer retainedRadar = new RetainedLayer();
    private long lastMotionLoadAt;
    private int lastMotionTileX = Integer.MIN_VALUE;
    private int lastMotionTileY = Integer.MIN_VALUE;
    private int lastMotionZoom = -1;
    private long tileRevision;
    private Object backdropNode;
    private boolean backdropCaptureEnabled;
    private boolean backdropCaptureDisabled;
    private Runnable backdropDrawListener;

    private static final class RetainedLayer {
        Object node;
        Bitmap bitmap;
        int gutter;
        long tileRevision;
        RadarMapTransform camera;
        int width;
        int height;
        RadarDataClient.Frame frame;
        RadarDataClient.Timeline timeline;
        double latitude;
        double longitude;
        float zoom;
    }

    RadarMapView(Context context, RadarDataClient data, Runnable viewportChanged) {
        super(context);
        this.data = data;
        this.viewportChanged = viewportChanged;
        scroller = new OverScroller(context);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        int doubleTapSlop = configuration.getScaledDoubleTapSlop();
        doubleTapSlopSq = doubleTapSlop * doubleTapSlop;
        doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout();
        tapTimeout = ViewConfiguration.getTapTimeout();
        minimumFlingVelocity = configuration.getScaledMinimumFlingVelocity();
        maximumFlingVelocity = configuration.getScaledMaximumFlingVelocity();

        scaleBarOutlinePaint.setStyle(Paint.Style.STROKE);
        scaleBarOutlinePaint.setColor(Color.argb(204, 10, 16, 26));
        scaleBarOutlinePaint.setStrokeWidth(dp(4.2f));
        scaleBarOutlinePaint.setStrokeCap(Paint.Cap.ROUND);
        scaleBarOutlinePaint.setStrokeJoin(Paint.Join.ROUND);

        scaleBarFillPaint.setStyle(Paint.Style.STROKE);
        scaleBarFillPaint.setColor(Color.WHITE);
        scaleBarFillPaint.setStrokeWidth(dp(2f));
        scaleBarFillPaint.setStrokeCap(Paint.Cap.ROUND);
        scaleBarFillPaint.setStrokeJoin(Paint.Join.ROUND);

        float scaleTextPx = sp(11f);
        Typeface scaleTypeface = Typeface.create("sans-serif", Typeface.BOLD);
        scaleTextOutlinePaint.setStyle(Paint.Style.STROKE);
        scaleTextOutlinePaint.setColor(Color.argb(214, 10, 16, 26));
        scaleTextOutlinePaint.setStrokeWidth(dp(3f));
        scaleTextOutlinePaint.setStrokeJoin(Paint.Join.ROUND);
        scaleTextOutlinePaint.setTextSize(scaleTextPx);
        scaleTextOutlinePaint.setTypeface(scaleTypeface);

        scaleTextPaint.setStyle(Paint.Style.FILL);
        scaleTextPaint.setColor(Color.WHITE);
        scaleTextPaint.setTextSize(scaleTextPx);
        scaleTextPaint.setTypeface(scaleTypeface);

        WeatherPreferences prefs = new WeatherPreferences(context);
        radarOpacityPercent = Math.max(20, Math.min(100, prefs.radarOpacityPercent()));
        radarAlpha = Math.round(255f * radarOpacityPercent / 100f);
        refreshScaleUnit();
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                        boolean inheritedChange = cancelFling(false) | cancelZoomAnimation();
                        if (inheritedChange) gestureViewportChanged = true;
                        transitionFromFrame = null;
                        scaledDuringGesture = true;
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
                        // The final viewport is committed once the gesture itself settles.
                    }
                });
    }

    boolean locationChanged(double lat, double lon) {
        return Math.abs(locationLat - clampLat(lat)) > 0.00001d
                || Math.abs(locationLon - wrapLon(lon)) > 0.00001d;
    }

    void setLocation(double lat, double lon) {
        cancelFling(false);
        cancelZoomAnimation();
        cancelFrameTransition();
        locationLat = clampLat(lat);
        locationLon = wrapLon(lon);
        centerLat = locationLat;
        centerLon = locationLon;
        invalidate();
    }

    void setFrame(RadarDataClient.Timeline timeline, RadarDataClient.Frame frame) {
        if (this.timeline != timeline) {
            displayedFrame = null;
            transitionFromFrame = null;
        }
        this.timeline = timeline;
        this.frame = frame;
        if (frame == null) {
            displayedFrame = null;
            transitionFromFrame = null;
        }
        updateTileDemand();
        invalidate();
    }

    void setManagedRadarLoading(boolean managed) {
        if (!managed) cancelFrameTransition();
        if (managedRadarLoading == managed) return;
        managedRadarLoading = managed;
        updateTileDemand();
        invalidate();
    }

    void setFallbackFrame(RadarDataClient.Frame available) {
        if (available == null || timeline == null || displayedFrame != null) return;
        if (frameReady(available)) {
            displayedFrame = available;
            if (frameDisplayListener != null) post(frameDisplayListener);
            invalidate();
        }
    }

    private void cancelFrameTransition() {
        transitionFromFrame = null;
        transitionStartedAt = 0L;
        invalidate();
    }

    void setFrameDisplayListener(Runnable listener) { frameDisplayListener = listener; }

    void setBackdropDrawListener(Runnable listener) { backdropDrawListener = listener; }

    void setBackdropCaptureEnabled(boolean enabled) {
        if (backdropCaptureEnabled == enabled) return;
        backdropCaptureEnabled = enabled;
        if (!enabled) releaseBackdrop();
        invalidate();
    }

    void setRadarOpacity(int percent) {
        int clamped = Math.max(20, Math.min(100, percent));
        int alpha = Math.round(255f * clamped / 100f);
        if (radarOpacityPercent == clamped && radarAlpha == alpha) return;
        radarOpacityPercent = clamped;
        radarAlpha = alpha;
        releaseLayer(retainedRadar);
        invalidate();
    }

    void setScaleBarInsets(int left, int top, int right, int bottom) {
        if (scaleBarLeftInset == left && scaleBarTopInset == top
                && scaleBarRightInset == right && scaleBarBottomInset == bottom) return;
        scaleBarLeftInset = left;
        scaleBarTopInset = top;
        scaleBarRightInset = right;
        scaleBarBottomInset = bottom;
        invalidate();
    }

    void refreshScaleUnit() {
        WeatherPreferences prefs = new WeatherPreferences(getContext());
        boolean fahrenheit = WeatherPreferences.TEMP_FAHRENHEIT.equals(prefs.temperatureUnit());
        boolean nextImperial = WeatherPreferences.VISIBILITY_MI.equals(prefs.visibilityUnit(fahrenheit));
        if (imperialScale != nextImperial) {
            imperialScale = nextImperial;
            cachedScaleZoom = Double.NaN;
            invalidate();
        }
    }

    void trimRetainedLayers() {
        clearRetainedLayers();
        releaseBackdrop();
    }

    Object publishedBackdrop() { return backdropNode; }

    private void releaseBackdrop() {
        if (Build.VERSION.SDK_INT >= 29 && backdropNode != null) Api29RetainedMap.release(backdropNode);
        backdropNode = null;
    }

    boolean isFrameDisplayed(RadarDataClient.Frame requested) { return displayedFrame == requested; }

    boolean isViewportInMotion() {
        return dragging || flingRunning || !scroller.isFinished()
                || scaleDetector.isInProgress() || zoomAnimator != null;
    }

    void zoomBy(int delta) {
        zoomBy((float) delta, getWidth() / 2f, getHeight() / 2f);
    }

    private void zoomBy(float delta, float focusX, float focusY) {
        float next = Math.max(3f, Math.min(11f, displayZoom + delta));
        if (Math.abs(next - displayZoom) < 0.01f) return;
        cancelFling(false);
        cancelZoomAnimation();
        cancelFrameTransition();
        final float fx = focusX;
        final float fy = focusY;
        ValueAnimator animation = ValueAnimator.ofFloat(displayZoom, next);
        zoomAnimator = animation;
        animation.setDuration(270L);
        animation.setInterpolator(new DecelerateInterpolator());
        animation.addUpdateListener(value -> changeZoom((float) value.getAnimatedValue(), fx, fy));
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

    private boolean cancelZoomAnimation() {
        ValueAnimator animation = zoomAnimator;
        if (animation == null) return false;
        boolean changed = animation.isStarted();
        animation.cancel();
        if (zoomAnimator == animation) zoomAnimator = null;
        return changed;
    }

    private void changeZoom(float next, float focusX, float focusY) {
        next = Math.max(3f, Math.min(11f, next));
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
        radarZoomSettlesAt = SystemClock.uptimeMillis() + ZOOM_LOAD_DELAY_MS;
        removeCallbacks(resumeRadarAfterZoom);
        postDelayed(resumeRadarAfterZoom, ZOOM_LOAD_DELAY_MS);
        invalidate();
    }

    private boolean isZoomingOrSettling() {
        return scaleDetector.isInProgress() || doubleTapDragZooming || zoomAnimator != null
                || SystemClock.uptimeMillis() < radarZoomSettlesAt;
    }

    private void resumeRadarAfterZoom() {
        updateTileDemand();
        invalidate();
        viewportChanged.run();
    }

    private int tileZoom() {
        return Math.max(3, Math.min(11, Math.round(displayZoom)));
    }

    private double mapScale() {
        return Math.pow(2d, displayZoom - tileZoom());
    }

    void recenter() {
        cancelFling(false);
        cancelZoomAnimation();
        cancelFrameTransition();
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
        fillRadarBounds(radarZoom);
        for (int y = radarBounds[2]; y <= radarBounds[3]; y++) {
            for (int x = radarBounds[0]; x <= radarBounds[1]; x++) {
                if (data.radarTile(timeline, requested, radarZoom, x, y,
                        null, false) == null) return false;
            }
        }
        return true;
    }

    void ensureFrame(RadarDataClient.Frame requested, Runnable onReady) {
        if (timeline == null || requested == null || getWidth() == 0 || getHeight() == 0
                || isViewportInMotion() || isZoomingOrSettling()) return;
        updateTileDemand();
        int radarZoom = radarZoom();
        Runnable callback = onReady == null ? tileReady : onReady;
        fillRadarBounds(radarZoom);
        for (int y = radarBounds[2]; y <= radarBounds[3]; y++) {
            for (int x = radarBounds[0]; x <= radarBounds[1]; x++) {
                data.radarTile(timeline, requested, radarZoom, x, y, callback, true);
            }
        }
    }

    private void requestFrame(RadarDataClient.Frame requested) {
        if (timeline == null || requested == null || getWidth() == 0 || getHeight() == 0
                || isZoomingOrSettling()) return;
        int radarZoom = radarZoom();
        fillRadarBounds(radarZoom);
        for (int y = radarBounds[2]; y <= radarBounds[3]; y++) {
            for (int x = radarBounds[0]; x <= radarBounds[1]; x++) {
                data.radarTile(timeline, requested, radarZoom, x, y, tileReady, true);
            }
        }
    }

    private void onTileReady() {
        tileRevision++;
        postInvalidateOnAnimation();
    }

    private void promoteFrameIfReady(boolean targetReady) {
        if (timeline == null || frame == null || displayedFrame == frame || !targetReady) return;
        RadarDataClient.Frame previous = displayedFrame;
        displayedFrame = frame;
        if (frameDisplayListener != null) post(frameDisplayListener);
        if (previous != null && previous != frame && frameReady(previous)) {
            transitionFromFrame = previous;
            transitionStartedAt = SystemClock.uptimeMillis();
        } else {
            transitionFromFrame = null;
            transitionStartedAt = 0L;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        boolean published = false;
        if (backdropCaptureEnabled && !backdropCaptureDisabled && Build.VERSION.SDK_INT >= 29
                && canvas.isHardwareAccelerated() && getWidth() > 0 && getHeight() > 0) {
            try {
                backdropNode = Api29RetainedMap.record(backdropNode, getWidth(), getHeight(),
                        0, this::drawMapContent);
                Api29RetainedMap.draw(canvas, backdropNode);
                published = true;
            } catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) {
                backdropCaptureDisabled = true;
                releaseBackdrop();
            }
        }
        if (!published) drawMapContent(canvas);
        if (backdropDrawListener != null) backdropDrawListener.run();
        drawScaleBar(canvas);
    }

    private void drawScaleBar(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        float left = Math.max(dp(16), scaleBarLeftInset);
        float bottom = height - Math.max(dp(16), scaleBarBottomInset);
        float availableWidth = width - left - Math.max(dp(16), scaleBarRightInset);
        if (availableWidth < dp(56) || bottom - dp(24) < scaleBarTopInset) return;

        float maxBarPx = Math.min(dp(112), Math.max(dp(48), availableWidth * 0.34f));
        if (Double.isNaN(cachedScaleZoom)
                || Math.abs(cachedScaleZoom - displayZoom) > 0.002d
                || Math.abs(cachedScaleLat - centerLat) > 0.05d
                || Math.abs(cachedScaleMaxBarPx - maxBarPx) > 1f
                || cachedScaleImperial != imperialScale) {
            updateScaleBarCache(maxBarPx);
        }
        if (cachedScaleBarPx < dp(20) || cachedScaleLabel.isEmpty()) return;

        float barLeft = left;
        float barRight = barLeft + cachedScaleBarPx;
        float tickTop = bottom - dp(5f);
        scaleBarPath.reset();
        scaleBarPath.moveTo(barLeft, tickTop);
        scaleBarPath.lineTo(barLeft, bottom);
        scaleBarPath.lineTo(barRight, bottom);
        scaleBarPath.lineTo(barRight, tickTop);
        canvas.drawPath(scaleBarPath, scaleBarOutlinePaint);
        canvas.drawPath(scaleBarPath, scaleBarFillPaint);

        float textY = bottom - dp(7f);
        canvas.drawText(cachedScaleLabel, barLeft + dp(2f), textY, scaleTextOutlinePaint);
        canvas.drawText(cachedScaleLabel, barLeft + dp(2f), textY, scaleTextPaint);
    }

    private void updateScaleBarCache(float maxBarPx) {
        cachedScaleZoom = displayZoom;
        cachedScaleLat = centerLat;
        cachedScaleMaxBarPx = maxBarPx;
        cachedScaleImperial = imperialScale;
        cachedScaleBarPx = 0f;
        cachedScaleLabel = "";

        double worldPx = TILE_PX * Math.pow(2d, displayZoom);
        double metersPerPx = (EARTH_EQUATORIAL_CIRCUMFERENCE_M * Math.cos(Math.toRadians(centerLat)))
                / worldPx;
        if (!Double.isFinite(metersPerPx) || metersPerPx <= 0d) return;
        double maxMeters = metersPerPx * maxBarPx;
        if (!Double.isFinite(maxMeters) || maxMeters <= 0d) return;

        if (imperialScale) {
            double maxMiles = maxMeters / METERS_PER_MILE;
            if (maxMiles >= 1d) {
                double niceMiles = niceScaleValue(maxMiles);
                cachedScaleBarPx = (float) (niceMiles * METERS_PER_MILE / metersPerPx);
                cachedScaleLabel = Math.round(niceMiles) + " mi";
            } else {
                double maxFeet = maxMeters / METERS_PER_FOOT;
                double niceFeet = niceScaleValue(maxFeet);
                cachedScaleBarPx = (float) (niceFeet * METERS_PER_FOOT / metersPerPx);
                cachedScaleLabel = Math.round(niceFeet) + " ft";
            }
        } else {
            double niceMeters = niceScaleValue(maxMeters);
            cachedScaleBarPx = (float) (niceMeters / metersPerPx);
            cachedScaleLabel = niceMeters >= 1000d
                    ? Math.round(niceMeters / 1000d) + " km"
                    : Math.round(niceMeters) + " m";
        }
    }

    private static double niceScaleValue(double maxVal) {
        if (maxVal <= 1d) return 1d;
        double pow10 = Math.pow(10d, Math.floor(Math.log10(maxVal)));
        double normalized = maxVal / pow10;
        double step = normalized >= 5d ? 5d : normalized >= 2d ? 2d : 1d;
        return step * pow10;
    }

    void setDarkMap(boolean dark) {
        if (darkMap == dark) return;
        darkMap = dark;
        // Invert luminance into a near-black palette. Only OSM is filtered;
        // precipitation, markers and attribution retain their original colours.
        basePaint.setColorFilter(dark ? new ColorMatrixColorFilter(new float[]{
                -0.153f, -0.515f, -0.052f, 0, 190,
                -0.153f, -0.515f, -0.052f, 0, 194,
                -0.153f, -0.515f, -0.052f, 0, 200,
                0, 0, 0, 1, 0}) : null);
        releaseLayer(retainedBase);
        invalidate();
    }

    private int mapBackground() { return darkMap ? Color.rgb(7, 11, 17) : Color.rgb(14, 43, 74); }

    private void drawMapContent(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        updateTileDemand();
        canvas.drawColor(mapBackground());
        int tileZoom = tileZoom();
        double scale = mapScale();
        double cx = toWorldX(centerLon, tileZoom) * scale;
        double cy = toWorldY(centerLat, tileZoom) * scale;
        boolean moving = isViewportInMotion();
        boolean hardwareLayers = Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated();
        if (moving) requestMovingViewport();
        boolean baseReady = !moving && baseTilesReady();
        boolean retainedBaseDrawn = (moving || !baseReady)
                && drawRetained(canvas, retainedBase);
        // Keep the old map underneath and fill available tiles progressively.
        // Loads during gestures are coalesced by tile window, not by frame.
        drawBaseTiles(canvas, width, height, !moving, !retainedBaseDrawn,
                !moving ? retentionGutter() : 0);
        if (!moving && baseReady && (hardwareLayers || Build.VERSION.SDK_INT < 29)) {
            captureLayer(retainedBase, false, hardwareLayers);
        }

        if (timeline != null && frame != null) {
            boolean targetReady = !moving && frameReady(frame);
            if (!moving && !managedRadarLoading && !targetReady) requestFrame(frame);
            if (!moving) promoteFrameIfReady(targetReady);
            boolean displayedReady = !moving && displayedFrame != null
                    && (displayedFrame == frame ? targetReady : frameReady(displayedFrame));
            boolean needsFallback = moving || !displayedReady;
            boolean sameFrame = displayedFrame == frame && transitionFromFrame == null
                    && retainedRadar.frame == frame && retainedRadar.timeline == timeline;
            boolean retainedRadarDrawn = needsFallback && (sameFrame
                    ? drawRadarFallback(canvas) : drawRetained(canvas, retainedRadar));
            if (sameFrame || !retainedRadarDrawn) {
                drawDisplayedRadar(canvas, width, height, tileZoom, scale, cx, cy);
            }
            if (!moving && transitionFromFrame == null && displayedReady
                    && (hardwareLayers || Build.VERSION.SDK_INT < 29)) {
                captureLayer(retainedRadar, true, hardwareLayers);
            }
        }

        drawMarker(canvas, width, height, tileZoom, scale, cx, cy);
    }

    /** Side-effect-free fallback capture: cached tiles only, with no view or network redraw. */
    void drawSoftwareBackdrop(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        canvas.drawColor(mapBackground());
        int zoom = tileZoom();
        double scale = mapScale();
        double cx = toWorldX(centerLon, zoom) * scale;
        double cy = toWorldY(centerLat, zoom) * scale;
        boolean baseRetained = drawRetained(canvas, retainedBase);
        drawBaseTiles(canvas, width, height, false, !baseRetained, 0);
        if (timeline != null && frame != null) {
            boolean needsFallback = isViewportInMotion() || !frameReady(displayedFrame);
            boolean sameFrame = displayedFrame == frame && transitionFromFrame == null
                    && retainedRadar.frame == frame && retainedRadar.timeline == timeline;
            boolean radarRetained = needsFallback && (sameFrame
                    ? drawRadarFallback(canvas) : drawRetained(canvas, retainedRadar));
            if (sameFrame || !radarRetained) {
                drawDisplayedRadar(canvas, width, height, zoom, scale, cx, cy, false);
            }
        }
        drawMarker(canvas, width, height, zoom, scale, cx, cy);
    }

    private void drawMarker(Canvas canvas, int width, int height, int tileZoom,
            double scale, double cx, double cy) {
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

    private int retentionGutter() {
        return Math.min(dp(96), (int) Math.ceil(TILE_PX * mapScale()));
    }

    private void requestMovingViewport() {
        long now = SystemClock.uptimeMillis();
        if (now - lastMotionLoadAt < 160L) return;
        int zoom = tileZoom();
        int tileX = (int) Math.floor(toWorldX(centerLon, zoom) / TILE_PX);
        int tileY = (int) Math.floor(toWorldY(centerLat, zoom) / TILE_PX);
        if (tileX == lastMotionTileX && tileY == lastMotionTileY && zoom == lastMotionZoom) return;
        lastMotionLoadAt = now;
        lastMotionTileX = tileX;
        lastMotionTileY = tileY;
        lastMotionZoom = zoom;
        requestBaseViewport();
        requestFrame(displayedFrame == null ? frame : displayedFrame);
    }

    private void requestBaseViewport() {
        int zoom = tileZoom();
        double scale = mapScale();
        double tilePixels = TILE_PX * scale;
        double cx = toWorldX(centerLon, zoom) * scale;
        double cy = toWorldY(centerLat, zoom) * scale;
        int firstX = (int) Math.floor((cx - getWidth() / 2d) / tilePixels);
        int lastX = (int) Math.floor((cx + getWidth() / 2d) / tilePixels);
        int firstY = (int) Math.floor((cy - getHeight() / 2d) / tilePixels);
        int lastY = (int) Math.floor((cy + getHeight() / 2d) / tilePixels);
        for (int y = firstY; y <= lastY; y++) {
            for (int x = firstX; x <= lastX; x++) data.baseTile(zoom, x, y, tileReady, true);
        }
    }

    /** Reprioritize only when the tile window changes, including intermediate pan/zoom views. */
    private void updateTileDemand() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        int zoom = tileZoom();
        double scale = mapScale();
        double tilePx = TILE_PX * scale;
        double cx = toWorldX(centerLon, zoom) * scale;
        double cy = toWorldY(centerLat, zoom) * scale;
        int count = 1 << zoom;
        int left = (int) Math.floor((cx - getWidth() / 2d) / tilePx);
        int right = (int) Math.floor((cx + getWidth() / 2d) / tilePx);
        int top = Math.max(0, (int) Math.floor((cy - getHeight() / 2d) / tilePx));
        int bottom = Math.min(count - 1, (int) Math.floor((cy + getHeight() / 2d) / tilePx));
        int radarZoom = radarZoom();
        fillRadarBounds(radarZoom);
        boolean radarLoads = !isZoomingOrSettling();
        boolean lookAhead = managedRadarLoading && !isViewportInMotion() && radarLoads;
        int start = timeline == null || frame == null ? -1 : timeline.frames.indexOf(frame);
        String signature = data.viewportGeneration() + ":" + zoom + ":" + left + ":" + right + ":" + top + ":" + bottom
                + ":" + radarZoom + ":" + radarBounds[0] + ":" + radarBounds[1] + ":" + radarBounds[2] + ":" + radarBounds[3]
                + ":" + lookAhead + ":" + radarLoads + ":" + (timeline == null ? "" : timeline.host + timeline.fetchedAtMillis)
                + ":" + start + ":" + (frame == null ? "" : frame.path)
                + ":" + (displayedFrame == null ? "" : displayedFrame.path);
        if (signature.equals(tileDemandSignature)) return;
        tileDemandSignature = signature;
        HashMap<String, Integer> base = new HashMap<>();
        HashMap<String, Integer> radar = new HashMap<>();
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) base.put(RadarDataClient.baseTileUrl(zoom, x, y), 0);
        }
        if (radarLoads && timeline != null && frame != null) {
            addRadarDemand(radar, frame, 0, radarZoom);
            if (displayedFrame != null) addRadarDemand(radar, displayedFrame, 0, radarZoom);
            if (lookAhead && start >= 0) {
                for (int offset = 1; offset < Math.min(3, timeline.frames.size()); offset++)
                    addRadarDemand(radar, timeline.frames.get((start + offset) % timeline.frames.size()), offset, radarZoom);
            }
        }
        data.setTileDemand(base, radar);
    }

    private void addRadarDemand(HashMap<String, Integer> demand, RadarDataClient.Frame item, int priority, int zoom) {
        for (int y = radarBounds[2]; y <= radarBounds[3]; y++) {
            for (int x = radarBounds[0]; x <= radarBounds[1]; x++) {
                String url = RadarDataClient.radarTileUrl(timeline, item, zoom, x, y);
                if (url != null) demand.merge(url, priority, Math::min);
            }
        }
    }

    private void drawBaseTiles(Canvas canvas, int width, int height, boolean allowLoad,
            boolean placeholders, int gutter) {
        int zoom = tileZoom();
        double scale = mapScale();
        double tilePx = TILE_PX * scale;
        double cx = toWorldX(centerLon, zoom) * scale;
        double cy = toWorldY(centerLat, zoom) * scale;
        int firstX = (int) Math.floor((cx - width / 2d - gutter) / tilePx);
        int lastX = (int) Math.floor((cx + width / 2d + gutter) / tilePx);
        int firstY = (int) Math.floor((cy - height / 2d - gutter) / tilePx);
        int lastY = (int) Math.floor((cy + height / 2d + gutter) / tilePx);
        for (int y = firstY; y <= lastY; y++) {
            for (int x = firstX; x <= lastX; x++) {
                float left = (float) (x * tilePx - cx + width / 2d);
                float top = (float) (y * tilePx - cy + height / 2d);
                drawRect.set(left, top, (float) (left + tilePx), (float) (top + tilePx));
                Bitmap tile = data.baseTile(zoom, x, y, tileReady, allowLoad);
                if (tile != null) canvas.drawBitmap(tile, null, drawRect, basePaint);
                else if (placeholders) {
                    placeholderPaint.setColor(darkMap ? (((x + y) & 1) == 0
                            ? Color.rgb(10, 14, 20) : Color.rgb(14, 18, 24)) : ((x + y) & 1) == 0
                            ? Color.rgb(17, 50, 81) : Color.rgb(20, 56, 88));
                    canvas.drawRect(drawRect, placeholderPaint);
                }
            }
        }
    }

    private boolean drawRetained(Canvas canvas, RetainedLayer layer) {
        if (layer.camera == null || layer.node == null && layer.bitmap == null) return false;
        RadarMapTransform next = new RadarMapTransform(centerLat, centerLon, displayZoom, TILE_PX);
        int save = canvas.save();
        canvas.translate((float) next.translationX(layer.camera, layer.width) + (getWidth() - layer.width) / 2f,
                (float) next.translationY(layer.camera, layer.height) + (getHeight() - layer.height) / 2f);
        float scale = (float) next.scaleFrom(layer.camera);
        canvas.scale(scale, scale);
        if (Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated() && layer.node != null) {
            Api29RetainedMap.draw(canvas, layer.node);
        } else if (layer.bitmap != null) {
            drawRect.set(-layer.gutter, -layer.gutter,
                    layer.width + layer.gutter, layer.height + layer.gutter);
            canvas.drawBitmap(layer.bitmap, null, drawRect, bitmapPaint);
        } else { canvas.restoreToCount(save); return false; }
        canvas.restoreToCount(save);
        return true;
    }

    private boolean drawRadarFallback(Canvas canvas) {
        int save = canvas.save();
        int tileZoom = tileZoom();
        int radarZoom = radarZoom();
        double scale = mapScale();
        double tilePx = TILE_PX * (1 << (tileZoom - radarZoom)) * scale;
        double cx = toWorldX(centerLon, tileZoom) * scale;
        double cy = toWorldY(centerLat, tileZoom) * scale;
        fillRadarBounds(radarZoom);
        for (int y = radarBounds[2]; y <= radarBounds[3]; y++) {
            for (int x = radarBounds[0]; x <= radarBounds[1]; x++) {
                if (data.radarTile(timeline, frame, radarZoom, x, y, null, false) == null) continue;
                float left = (float) (x * tilePx - cx + getWidth() / 2d);
                float top = (float) (y * tilePx - cy + getHeight() / 2d);
                // Draw the retained radar only in missing tiles; overlapping two
                // translucent rain tiles would darken the same precipitation.
                canvas.clipOutRect(left, top, (float) (left + tilePx), (float) (top + tilePx));
            }
        }
        boolean drawn = drawRetained(canvas, retainedRadar);
        canvas.restoreToCount(save);
        return drawn;
    }

    private void captureLayer(RetainedLayer layer, boolean radar, boolean hardware) {
        int width = getWidth();
        int height = getHeight();
        if ((layer.node != null || layer.bitmap != null) && layer.width == width && layer.height == height
                && layer.latitude == centerLat && layer.longitude == centerLon && layer.zoom == displayZoom
                && layer.tileRevision == tileRevision
                && (!radar || layer.frame == displayedFrame && layer.timeline == timeline)) return;
        int gutter = retentionGutter();
        java.util.function.Consumer<Canvas> draw = recording -> {
            if (!radar) drawBaseTiles(recording, width, height, false, false, gutter);
            else {
                int zoom = tileZoom();
                double scale = mapScale();
                drawRadarFrame(recording, displayedFrame, radarAlpha, width, height, zoom, scale,
                        toWorldX(centerLon, zoom) * scale, toWorldY(centerLat, zoom) * scale, gutter);
            }
        };
        if (Build.VERSION.SDK_INT >= 29 && hardware) {
            layer.node = Api29RetainedMap.record(layer.node, width, height, gutter, draw);
        } else {
            float resolution = Math.min(1f, 1024f / Math.max(width + 2 * gutter, height + 2 * gutter));
            int bufferWidth = Math.max(1, Math.round((width + 2 * gutter) * resolution));
            int bufferHeight = Math.max(1, Math.round((height + 2 * gutter) * resolution));
            try {
                if (layer.bitmap == null || layer.bitmap.getWidth() != bufferWidth
                        || layer.bitmap.getHeight() != bufferHeight) {
                    layer.bitmap = Bitmap.createBitmap(bufferWidth, bufferHeight, Bitmap.Config.ARGB_8888);
                }
                layer.bitmap.eraseColor(Color.TRANSPARENT);
                Canvas recording = new Canvas(layer.bitmap);
                recording.scale(resolution, resolution);
                recording.translate(gutter, gutter);
                draw.accept(recording);
            } catch (OutOfMemoryError unavailable) { return; }
        }
        layer.gutter = gutter;
        layer.tileRevision = tileRevision;
        layer.camera = new RadarMapTransform(centerLat, centerLon, displayZoom, TILE_PX);
        layer.latitude = centerLat;
        layer.longitude = centerLon;
        layer.zoom = displayZoom;
        layer.width = width;
        layer.height = height;
        layer.frame = radar ? displayedFrame : null;
        layer.timeline = radar ? timeline : null;
    }

    private void releaseLayer(RetainedLayer layer) {
        if (Build.VERSION.SDK_INT >= 29 && layer.node != null) Api29RetainedMap.release(layer.node);
        layer.node = null;
        layer.bitmap = null;
        layer.camera = null;
        layer.frame = null;
        layer.timeline = null;
    }

    private void clearRetainedLayers() { releaseLayer(retainedBase); releaseLayer(retainedRadar); }

    @androidx.annotation.RequiresApi(29)
    private static final class Api29RetainedMap {
        static Object record(Object previous, int width, int height, int gutter,
                java.util.function.Consumer<Canvas> draw) {
            android.graphics.RenderNode node = previous instanceof android.graphics.RenderNode
                    ? (android.graphics.RenderNode) previous : new android.graphics.RenderNode("ZwerkRadarTiles");
            node.setPosition(-gutter, -gutter, width + gutter, height + gutter);
            android.graphics.RecordingCanvas recording = node.beginRecording(width + 2 * gutter, height + 2 * gutter);
            try {
                recording.translate(gutter, gutter);
                draw.accept(recording);
            } finally {
                node.endRecording();
            }
            return node;
        }
        static void draw(Canvas canvas, Object node) { canvas.drawRenderNode((android.graphics.RenderNode) node); }
        static void release(Object node) { ((android.graphics.RenderNode) node).discardDisplayList(); }
    }

    private void drawDisplayedRadar(Canvas canvas, int width, int height, int tileZoom,
            double scale, double cx, double cy) {
        drawDisplayedRadar(canvas, width, height, tileZoom, scale, cx, cy, true);
    }

    private void drawDisplayedRadar(Canvas canvas, int width, int height, int tileZoom,
            double scale, double cx, double cy, boolean advance) {
        if (displayedFrame == null) return;
        RadarDataClient.Frame from = transitionFromFrame;
        if (from == null) {
            drawRadarFrame(canvas, displayedFrame, radarAlpha, width, height, tileZoom, scale, cx, cy);
            return;
        }
        // Readiness was checked at commit time. Camera movement cancels the
        // transition; rescanning every tile on each animation frame adds UI work.
        long elapsed = SystemClock.uptimeMillis() - transitionStartedAt;
        if (elapsed >= FRAME_CROSSFADE_MS) {
            if (advance) {
                transitionFromFrame = null;
                transitionStartedAt = 0L;
            }
            drawRadarFrame(canvas, displayedFrame, radarAlpha, width, height, tileZoom, scale, cx, cy);
            return;
        }
        float progress = Math.max(0f, Math.min(1f, elapsed / (float) FRAME_CROSSFADE_MS));
        drawRadarFrame(canvas, from, Math.round(radarAlpha * (1f - progress)),
                width, height, tileZoom, scale, cx, cy);
        drawRadarFrame(canvas, displayedFrame, Math.round(radarAlpha * progress),
                width, height, tileZoom, scale, cx, cy);
        if (advance) postInvalidateOnAnimation();
    }

    private void drawRadarFrame(Canvas canvas, RadarDataClient.Frame requested, int alpha,
            int width, int height, int tileZoom, double scale, double cx, double cy) {
        drawRadarFrame(canvas, requested, alpha, width, height, tileZoom, scale, cx, cy, 0);
    }

    private void drawRadarFrame(Canvas canvas, RadarDataClient.Frame requested, int alpha,
            int width, int height, int tileZoom, double scale, double cx, double cy, int gutter) {
        if (requested == null || alpha <= 0) return;
        int radarZoom = radarZoom();
        double radarTilePx = TILE_PX * (1 << (tileZoom - radarZoom)) * scale;
        fillRadarBounds(radarZoom, gutter);
        bitmapPaint.setAlpha(alpha);
        for (int y = radarBounds[2]; y <= radarBounds[3]; y++) {
            for (int x = radarBounds[0]; x <= radarBounds[1]; x++) {
                Bitmap radar = data.radarTile(timeline, requested, radarZoom, x, y,
                        null, false);
                if (radar == null) continue;
                float left = (float) (x * radarTilePx - cx + width / 2d);
                float top = (float) (y * radarTilePx - cy + height / 2d);
                drawRect.set(left, top,
                        (float) (left + radarTilePx), (float) (top + radarTilePx));
                canvas.drawBitmap(radar, null, drawRect, bitmapPaint);
            }
        }
        bitmapPaint.setAlpha(255);
    }

    // The map handles pan/pinch/fling only. RadarPageView provides accessible zoom and
    // recenter buttons, so there is no tap action to implement via performClick().
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            boolean inheritedChange = cancelFling(false) | cancelZoomAnimation();
            cancelFrameTransition();
            obtainVelocityTracker();
            velocityTracker.addMovement(event);
            activePointerId = event.getPointerId(0);
            downX = lastX = event.getX(0);
            downY = lastY = event.getY(0);
            downTime = event.getEventTime();
            inheritedChangeOnDown = inheritedChange;
            long dt = downTime - lastTapUpTime;
            float tapDx = downX - lastTapX;
            float tapDy = downY - lastTapY;
            doubleTapCandidate = lastTapUpTime > 0L && dt >= 0L && dt <= doubleTapTimeout
                    && tapDx * tapDx + tapDy * tapDy <= doubleTapSlopSq;
            doubleTapDragZooming = false;
            if (doubleTapCandidate) {
                doubleTapFocusX = downX;
                doubleTapFocusY = downY;
            }
            dragging = true;
            movedBeyondSlop = false;
            scaledDuringGesture = false;
            gestureViewportChanged = inheritedChange;
            requestParentIntercept(false);
            scaleDetector.onTouchEvent(event);
            return true;
        }

        if (velocityTracker != null) velocityTracker.addMovement(event);
        scaleDetector.onTouchEvent(event);

        switch (action) {
            case MotionEvent.ACTION_MOVE: {
                int index = event.findPointerIndex(activePointerId);
                if (index < 0) {
                    index = 0;
                    activePointerId = event.getPointerId(index);
                    lastX = event.getX(index);
                    lastY = event.getY(index);
                }
                float x = event.getX(index);
                float y = event.getY(index);
                if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
                    float totalDx = x - downX;
                    float totalDy = y - downY;
                    boolean exceededSlop = movedBeyondSlop
                            || totalDx * totalDx + totalDy * totalDy
                            >= (float) touchSlop * touchSlop;
                    if (doubleTapCandidate) {
                        if (!doubleTapDragZooming && exceededSlop) {
                            movedBeyondSlop = true;
                            doubleTapDragZooming = true;
                        } else if (doubleTapDragZooming) {
                            float dy = y - lastY;
                            if (dy != 0f) {
                                float deltaZoom = dy / Math.max(1f, dp(140f));
                                float before = displayZoom;
                                changeZoom(displayZoom + deltaZoom, doubleTapFocusX, doubleTapFocusY);
                                if (Math.abs(displayZoom - before) > 0.0001f) {
                                    gestureViewportChanged = true;
                                }
                            }
                        }
                    } else {
                        float dx = x - lastX;
                        float dy = y - lastY;
                        if (dx != 0f || dy != 0f) {
                            panByScreenDelta(dx, dy);
                            gestureViewportChanged = true;
                        }
                        if (exceededSlop) movedBeyondSlop = true;
                    }
                }
                lastX = x;
                lastY = y;
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                doubleTapCandidate = false;
                doubleTapDragZooming = false;
                lastTapUpTime = 0L;
                scaledDuringGesture = true;
                movedBeyondSlop = true;
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                handlePointerUp(event);
                scaledDuringGesture = true;
                return true;
            case MotionEvent.ACTION_UP: {
                dragging = false;
                boolean wasDoubleTapDrag = doubleTapDragZooming;
                boolean wasDoubleTap = doubleTapCandidate && !doubleTapDragZooming
                        && !scaledDuringGesture && !movedBeyondSlop;
                doubleTapCandidate = false;
                doubleTapDragZooming = false;
                boolean flung = false;
                if (wasDoubleTap) {
                    lastTapUpTime = 0L;
                    gestureViewportChanged = false;
                    recycleVelocityTracker();
                    activePointerId = INVALID_POINTER_ID;
                    requestParentIntercept(true);
                    zoomBy(1f, doubleTapFocusX, doubleTapFocusY);
                    invalidate();
                    return true;
                }
                if (wasDoubleTapDrag) {
                    lastTapUpTime = 0L;
                } else if (!scaledDuringGesture && !movedBeyondSlop && !inheritedChangeOnDown
                        && event.getEventTime() - downTime <= tapTimeout) {
                    lastTapUpTime = event.getEventTime();
                    lastTapX = event.getX();
                    lastTapY = event.getY();
                } else {
                    lastTapUpTime = 0L;
                    if (!scaledDuringGesture && movedBeyondSlop && velocityTracker != null
                            && activePointerId != INVALID_POINTER_ID) {
                        velocityTracker.computeCurrentVelocity(1000, maximumFlingVelocity);
                        float velocityX = velocityTracker.getXVelocity(activePointerId);
                        float velocityY = velocityTracker.getYVelocity(activePointerId);
                        flung = startFling(velocityX, velocityY);
                    }
                }
                recycleVelocityTracker();
                activePointerId = INVALID_POINTER_ID;
                requestParentIntercept(true);
                if (!flung) commitGestureViewport();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                doubleTapCandidate = false;
                doubleTapDragZooming = false;
                lastTapUpTime = 0L;
                recycleVelocityTracker();
                activePointerId = INVALID_POINTER_ID;
                requestParentIntercept(true);
                commitGestureViewport();
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void obtainVelocityTracker() {
        recycleVelocityTracker();
        velocityTracker = VelocityTracker.obtain();
    }

    private void recycleVelocityTracker() {
        if (velocityTracker == null) return;
        velocityTracker.recycle();
        velocityTracker = null;
    }

    private void handlePointerUp(MotionEvent event) {
        int actionIndex = event.getActionIndex();
        int pointerId = event.getPointerId(actionIndex);
        if (pointerId != activePointerId) return;
        int replacementIndex = actionIndex == 0 ? 1 : 0;
        if (replacementIndex >= event.getPointerCount()) {
            activePointerId = INVALID_POINTER_ID;
            return;
        }
        activePointerId = event.getPointerId(replacementIndex);
        lastX = event.getX(replacementIndex);
        lastY = event.getY(replacementIndex);
        downX = lastX;
        downY = lastY;
        if (velocityTracker != null) velocityTracker.clear();
    }

    private void requestParentIntercept(boolean allowIntercept) {
        android.view.ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(!allowIntercept);
    }

    private void panByScreenDelta(float dx, float dy) {
        int zoom = tileZoom();
        double scale = mapScale();
        double world = TILE_PX * (1 << zoom) * scale;
        double nextX = toWorldX(centerLon, zoom) * scale - dx;
        double nextY = Math.max(0d, Math.min(world,
                toWorldY(centerLat, zoom) * scale - dy));
        centerLon = toLon(nextX / scale, zoom);
        centerLat = toLat(nextY / scale, zoom);
        invalidate();
    }

    private boolean startFling(float velocityX, float velocityY) {
        if (Math.abs(velocityX) < minimumFlingVelocity
                && Math.abs(velocityY) < minimumFlingVelocity) {
            return false;
        }
        int bound = Math.max(dp(360), Math.min(dp(1800),
                Math.max(getWidth(), getHeight()) * 3));
        int vx = Math.round(Math.max(-maximumFlingVelocity,
                Math.min(maximumFlingVelocity, velocityX)));
        int vy = Math.round(Math.max(-maximumFlingVelocity,
                Math.min(maximumFlingVelocity, velocityY)));
        scroller.fling(0, 0, vx, vy, -bound, bound, -bound, bound);
        flingLastX = 0;
        flingLastY = 0;
        flingRunning = !scroller.isFinished();
        if (flingRunning) postInvalidateOnAnimation();
        return flingRunning;
    }

    private boolean cancelFling(boolean publishViewport) {
        if (!flingRunning && scroller.isFinished()) return false;
        scroller.abortAnimation();
        flingRunning = false;
        if (publishViewport && gestureViewportChanged) commitGestureViewport();
        invalidate();
        return true;
    }

    @Override public void computeScroll() {
        super.computeScroll();
        if (!flingRunning) return;
        if (!scroller.computeScrollOffset()) {
            finishFling();
            return;
        }
        int currentX = scroller.getCurrX();
        int currentY = scroller.getCurrY();
        int dx = currentX - flingLastX;
        int dy = currentY - flingLastY;
        flingLastX = currentX;
        flingLastY = currentY;
        if (dx != 0 || dy != 0) {
            panByScreenDelta(dx, dy);
            gestureViewportChanged = true;
        }
        if (scroller.isFinished()) finishFling();
        else postInvalidateOnAnimation();
    }

    private void finishFling() {
        if (!flingRunning) return;
        flingRunning = false;
        commitGestureViewport();
        invalidate();
    }

    private void commitGestureViewport() {
        if (gestureViewportChanged) viewportChanged.run();
        gestureViewportChanged = false;
    }

    void stopMotion() {
        removeCallbacks(resumeRadarAfterZoom);
        radarZoomSettlesAt = 0L;
        cancelFling(false);
        cancelZoomAnimation();
        cancelFrameTransition();
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
        try { scaleDetector.onTouchEvent(cancel); } finally { cancel.recycle(); }
        dragging = false;
        doubleTapCandidate = false;
        doubleTapDragZooming = false;
        lastTapUpTime = 0L;
        gestureViewportChanged = false;
        activePointerId = INVALID_POINTER_ID;
        recycleVelocityTracker();
        requestParentIntercept(true);
        if (frameDisplayListener != null) removeCallbacks(frameDisplayListener);
    }

    @Override protected void onDetachedFromWindow() {
        releaseBackdrop();
        clearRetainedLayers();
        stopMotion();
        super.onDetachedFromWindow();
    }

    private int radarZoom() {
        // Native zoom 6 with 512px tiles has the same detail as zoom 7 with 256px tiles.
        return Math.max(1, Math.min(6, tileZoom() - 2));
    }

    private void fillRadarBounds(int radarZoom) {
        fillRadarBounds(radarZoom, 0);
    }

    private void fillRadarBounds(int radarZoom, int gutter) {
        int tileZoom = tileZoom();
        double scale = mapScale();
        double radarTilePx = TILE_PX * (1 << (tileZoom - radarZoom)) * scale;
        int maxY = (1 << radarZoom) - 1;
        double cx = toWorldX(centerLon, tileZoom) * scale;
        double cy = toWorldY(centerLat, tileZoom) * scale;
        radarBounds[0] = (int) Math.floor((cx - getWidth() / 2d - gutter) / radarTilePx);
        radarBounds[1] = (int) Math.ceil((cx + getWidth() / 2d + gutter) / radarTilePx) - 1;
        radarBounds[2] = Math.max(0,
                (int) Math.floor((cy - getHeight() / 2d - gutter) / radarTilePx));
        radarBounds[3] = Math.min(maxY,
                (int) Math.ceil((cy + getHeight() / 2d + gutter) / radarTilePx) - 1);
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

    private float sp(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
    }
}
