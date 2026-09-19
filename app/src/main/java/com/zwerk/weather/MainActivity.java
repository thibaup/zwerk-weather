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


public class MainActivity extends WeatherSettingsFlowActivity implements DeviceLocationRefreshCoordinator.Callback {
    private ForecastSwipeLayout forecastSwipeLayout;
    private FrameLayout modeSwitchHolder;
    private View modeSwitchThumb;
    private ValueAnimator modeSwitchAnimator;
    private float modeSwitchProgress;
    private float locationGestureStartX;
    private float locationGestureStartY;
    private boolean locationGestureActive;

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        RainAlertManager.reconcile(this);
        weatherPreferences = new WeatherPreferences(this);
        forecastDiskCache = new ForecastDiskCache(this);
        configureWindow();
        locationRefreshCoordinator = new DeviceLocationRefreshCoordinator(this, LOCATION_REQUEST, this);

        latitude = getPreferences(MODE_PRIVATE).getFloat("lat", (float) latitude);
        longitude = getPreferences(MODE_PRIVATE).getFloat("lon", (float) longitude);
        locationName = getPreferences(MODE_PRIVATE).getString("name", locationName);
        CityManagerActivity.migrateLegacyMainActivityPreferences(this);
        CityManagerActivity.LocationSnapshot selected = CityManagerActivity.getSelectedLocation(this);
        if (selected != null) {
            selectedLocationId = selected.id;
            latitude = selected.lat;
            longitude = selected.lon;
            locationName = selected.name;
            usingDeviceLocation = selected.isDevice;
            getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_EXPLICIT_NON_DEVICE_LOCATION, !selected.isDevice)
                    .apply();
        }
        activeTemperatureUnit = temperatureUnitPreference();
        cleanupForecastCaches();
        cleanupMinuteForecastCaches();
        cleanupOptionalCaches();

        buildShell();
        content.postDelayed(() -> UpdateChecker.checkForUpdates(this, false), 1800L);
        if (!hasConfiguredApiKey()) {
            status.setText("Google API key required");
            progress.setVisibility(View.GONE);
            showApiKeySetupDialog();
            return;
        }
        continueStartupAfterApiKey();
    }

    void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }

        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                        0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            window.setFlags(
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    void buildShell() {
        skyLayout = new SkyLayout(this);
        SkyLayout root = skyLayout;

        mainScroll = new RefreshScrollView(this);
        RefreshScrollView scroll = mainScroll;
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        scroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            float depth = Math.min(1f, scrollY / (float) dp(72));
            if (headerGlass != null) {
                headerGlass.setScrollDepth(depth);
                headerGlass.requestBlurRefresh();
            }
        });

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(76), dp(18), dp(44));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        forecastSwipeLayout = new ForecastSwipeLayout(this);
        forecastSwipeLayout.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        root.addView(forecastSwipeLayout, new FrameLayout.LayoutParams(-1, -1));

        headerGlass = new HeaderGlassView(this, scroll);
        headerGlass.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        headerGlass.setClickable(false);
        headerGlass.setFocusable(false);
        FrameLayout.LayoutParams glassLp = new FrameLayout.LayoutParams(-1, dp(68), Gravity.TOP);
        root.addView(headerGlass, glassLp);

        refreshIndicator = new RefreshIndicatorView(this);
        refreshIndicator.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams refreshLp = new FrameLayout.LayoutParams(
                Math.min(dp(196), getResources().getDisplayMetrics().widthPixels - dp(48)),
                dp(42),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        refreshLp.topMargin = dp(72);
        root.addView(refreshIndicator, refreshLp);

        toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(18), dp(2), dp(18), 0);
        toolbar.setBackgroundColor(Color.TRANSPARENT);

        locationArea = new LinearLayout(this);
        locationArea.setOrientation(LinearLayout.VERTICAL);
        locationArea.setGravity(Gravity.CENTER_VERTICAL);
        locationArea.setClickable(true);
        locationArea.setFocusable(true);
        locationArea.setOnClickListener(v -> {
            if (forecastPreview.isPreviewing()) forecastPreview.restore(true);
        });
        locationArea.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    locationGestureStartX = event.getX();
                    locationGestureStartY = event.getY();
                    locationGestureActive = true;
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!locationGestureActive) return true;
                    locationGestureActive = false;
                    float dx = event.getX() - locationGestureStartX;
                    float dy = event.getY() - locationGestureStartY;
                    int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
                    if (Math.abs(dx) >= Math.max(dp(56), slop * 2)
                            && Math.abs(dx) > Math.abs(dy) * 1.35f) {
                        cycleSavedLocation(dx < 0 ? 1 : -1);
                    } else {
                        view.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    locationGestureActive = false;
                    return true;
                default:
                    return locationGestureActive;
            }
        });

        locationTitle = text(locationName, 20, false, WHITE);
        locationTitle.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        locationTitle.setGravity(Gravity.CENTER_VERTICAL);
        locationTitle.setSingleLine(true);
        locationTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        locationArea.addView(locationTitle, new LinearLayout.LayoutParams(-1, dp(29)));

        previewSubtitle = text("", 10, true, Color.argb(220, 255, 255, 255));
        previewSubtitle.setSingleLine(true);
        previewSubtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        previewSubtitle.setGravity(Gravity.CENTER_VERTICAL);
        previewSubtitle.setVisibility(View.GONE);
        locationArea.addView(previewSubtitle, new LinearLayout.LayoutParams(-1, dp(17)));
        makeChildrenUnimportant(locationArea);
        toolbar.addView(locationArea, new LinearLayout.LayoutParams(0, dp(52), 1));

        HeaderGlyphButton places = new HeaderGlyphButton(this, HeaderGlyphButton.MENU_PLUS);
        places.setContentDescription("Choose forecast location");
        places.setOnClickListener(v -> openCityManager());
        toolbar.addView(places, new LinearLayout.LayoutParams(dp(44), dp(44)));

        HeaderGlyphButton settings = new HeaderGlyphButton(this, HeaderGlyphButton.SETTINGS);
        settings.setContentDescription("Weather options");
        settings.setOnClickListener(v -> openSettings());
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        settingsLp.leftMargin = dp(2);
        toolbar.addView(settings, settingsLp);

        FrameLayout.LayoutParams toolbarLp = new FrameLayout.LayoutParams(-1, dp(68), Gravity.TOP);
        root.addView(toolbar, toolbarLp);
        setContentView(root);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            int pinnedHeaderBottom = top + dp(68);
            content.setPadding(dp(18), pinnedHeaderBottom + dp(8), dp(18), bottom + dp(38));
            toolbar.setPadding(dp(18), top, dp(18), 0);
            FrameLayout.LayoutParams toolbarParams = (FrameLayout.LayoutParams) toolbar.getLayoutParams();
            toolbarParams.height = pinnedHeaderBottom;
            toolbar.setLayoutParams(toolbarParams);
            if (headerGlass != null) {
                FrameLayout.LayoutParams glassParams = (FrameLayout.LayoutParams) headerGlass.getLayoutParams();
                glassParams.height = pinnedHeaderBottom;
                headerGlass.setLayoutParams(glassParams);
                headerGlass.requestBlurRefresh();
            }
            if (refreshIndicator != null) {
                FrameLayout.LayoutParams refreshParams =
                        (FrameLayout.LayoutParams) refreshIndicator.getLayoutParams();
                refreshParams.topMargin = pinnedHeaderBottom + dp(6);
                refreshIndicator.setLayoutParams(refreshParams);
            }
            return insets;
        });

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(2), 0, dp(7));
        StatusGlyphView statusGlyph = new StatusGlyphView(this);
        statusGlyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        statusRow.addView(statusGlyph, new LinearLayout.LayoutParams(dp(24), dp(24)));
        status = text("Preparing Zwerk Weather…", 13, false, SOFT_WHITE);
        status.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(0, dp(28), 1f);
        statusLp.leftMargin = dp(6);
        statusRow.addView(status, statusLp);
        content.addView(statusRow);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setAlpha(0.58f);
        content.addView(progress, new LinearLayout.LayoutParams(-1, dp(1)));

        addForecastModeSwitch();
        forecastPageHost = new ForecastPageHost(this);
        forecastPageHost.setClipChildren(true);
        forecastPageHost.setClipToPadding(true);

        overviewPageContent = forecastPage();
        precipitationPageContent = forecastPage();
        activePageContent = overviewPageContent;
        forecastPageHost.addView(overviewPageContent,
                new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        forecastPageHost.addView(precipitationPageContent,
                new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        precipitationPageContent.setVisibility(View.INVISIBLE);
        content.addView(forecastPageHost, new LinearLayout.LayoutParams(-1, -2));
        dynamicStartIndex = content.indexOfChild(forecastPageHost);
        updatePreviewSubtitle();
    }

    /** Selects the next or previous saved city when the location header is swiped. */
    private void cycleSavedLocation(int direction) {
        List<CityManagerActivity.LocationSnapshot> saved =
                CityManagerActivity.getStoredLocations(this);
        if (saved.size() < 2) return;

        int currentIndex = -1;
        for (int i = 0; i < saved.size(); i++) {
            if (saved.get(i).id.equals(selectedLocationId)) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex < 0) {
            CityManagerActivity.LocationSnapshot selected =
                    CityManagerActivity.getSelectedLocation(this);
            if (selected != null) {
                for (int i = 0; i < saved.size(); i++) {
                    if (saved.get(i).id.equals(selected.id)) {
                        currentIndex = i;
                        break;
                    }
                }
            }
        }
        if (currentIndex < 0) currentIndex = 0;
        int nextIndex = (currentIndex + direction) % saved.size();
        if (nextIndex < 0) nextIndex += saved.size();
        CityManagerActivity.LocationSnapshot next = saved.get(nextIndex);
        if (next.id.equals(selectedLocationId)) return;

        if (locationRefreshCoordinator != null) locationRefreshCoordinator.onSelectionChanged();
        // A header swipe is an explicit user selection for both saved cities and the saved
        // device entry. This also clears manual-location protection when returning to device.
        applyLocationSelection(next.lat, next.lon, next.name, next.isDevice, true);
        animateLocationHeader(direction);
        refreshWeather();
    }

    private void animateLocationHeader(int direction) {
        if (locationArea == null || !animationsAllowed()) return;
        locationArea.animate().cancel();
        locationArea.setAlpha(0.65f);
        locationArea.setTranslationX(direction > 0 ? -dp(14) : dp(14));
        locationArea.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(180L)
                .start();
    }

    private LinearLayout forecastPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        return page;
    }

    void cancelForecastSwipe() {
        if (forecastSwipeLayout != null) forecastSwipeLayout.cancelAndSnap();
    }

    /** Measures both prepared pages but gives the scroll view only the selected page's height. */
    private final class ForecastPageHost extends FrameLayout {
        private boolean transitioning;

        ForecastPageHost(Context context) {
            super(context);
        }

        void setTransitioning(boolean value) {
            if (transitioning == value) return;
            transitioning = value;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = View.MeasureSpec.getSize(widthMeasureSpec);
            int childWidth = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
            int childHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            overviewPageContent.measure(childWidth, childHeight);
            precipitationPageContent.measure(childWidth, childHeight);
            int activeHeight = (precipitationMode
                    ? precipitationPageContent : overviewPageContent).getMeasuredHeight();
            int height = activeHeight;
            if (transitioning) {
                View incoming = precipitationMode
                        ? overviewPageContent : precipitationPageContent;
                height = Math.max(height, incoming.getMeasuredHeight()
                        + Math.max(0, Math.round(incoming.getTranslationY())));
            }
            setMeasuredDimension(resolveSize(width, widthMeasureSpec),
                    resolveSize(height, heightMeasureSpec));
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int width = right - left;
            overviewPageContent.layout(0, 0, width, overviewPageContent.getMeasuredHeight());
            precipitationPageContent.layout(0, 0, width,
                    precipitationPageContent.getMeasuredHeight());
        }
    }

    /** Owns page gestures while leaving the shared status and mode controls stationary. */
    private final class ForecastSwipeLayout extends FrameLayout {
        private final int touchSlop;
        private float startX;
        private float startY;
        private boolean candidate;
        private boolean swiping;
        private boolean settling;
        private int animationGeneration;

        void cancelAndSnap() {
            animationGeneration++;
            candidate = false;
            swiping = false;
            settling = false;
            if (overviewPageContent != null) overviewPageContent.animate().cancel();
            if (precipitationPageContent != null) precipitationPageContent.animate().cancel();
            if (forecastPageHost instanceof ForecastPageHost) {
                ((ForecastPageHost) forecastPageHost).setTransitioning(false);
            }
            if (modeSwitchAnimator != null) modeSwitchAnimator.cancel();
            resetPagePositions();
        }

        ForecastSwipeLayout(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (settling) return true;
                    startX = event.getX();
                    startY = event.getY();
                    swiping = false;
                    candidate = !hitsHorizontalControl(this, startX, startY);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!candidate) break;
                    float dx = event.getX() - startX;
                    float dy = event.getY() - startY;
                    if (Math.max(Math.abs(dx), Math.abs(dy)) <= touchSlop) break;
                    // Lock the gesture once its direction is clear; a vertical scroll
                    // must never turn into a page change later in the same gesture.
                    candidate = false;
                    swiping = Math.abs(dx) > Math.abs(dy) * 1.5f
                            && (precipitationMode ? dx > 0 : dx < 0);
                    return swiping;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    candidate = false;
                    break;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (settling) return true;
            if (!swiping) return super.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    float dragX = directionalTranslation(event.getX() - startX);
                    applyPageDrag(dragX);
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_CANCEL:
                    swiping = false;
                    settleBack();
                    break;
                case MotionEvent.ACTION_UP:
                    swiping = false;
                    float dx = event.getX() - startX;
                    float dy = event.getY() - startY;
                    if (Math.abs(dx) >= Math.max(dp(64), touchSlop * 2)
                            && Math.abs(dx) > Math.abs(dy) * 1.5f
                            && (precipitationMode ? dx > 0 : dx < 0)) {
                        animateModeChange(dx < 0);
                    } else {
                        settleBack();
                    }
                    break;
            }
            return true;
        }

        void animateModeChange(boolean precipitation) {
            if (settling || precipitationMode == precipitation
                    || overviewPageContent == null || precipitationPageContent == null) return;
            settling = true;
            final int generation = ++animationGeneration;
            candidate = false;
            swiping = false;
            float width = Math.max(1, getWidth());
            View outgoing = precipitationMode ? precipitationPageContent : overviewPageContent;
            View incoming = precipitationMode ? overviewPageContent : precipitationPageContent;
            if (incoming.getVisibility() != View.VISIBLE) preparePageDrag();
            float outgoingTarget = precipitation ? -width : width;
            float incomingStart = precipitation ? width : -width;
            if (Math.abs(incoming.getTranslationX()) < 1f) incoming.setTranslationX(incomingStart);
            float remaining = Math.abs(outgoingTarget - outgoing.getTranslationX()) / width;
            long duration = animationsAllowed()
                    ? Math.max(110L, Math.round(240L * Math.min(1f, remaining))) : 0L;
            animateModeSwitchProgress(precipitation ? 1f : 0f, duration);
            outgoing.animate().cancel();
            incoming.animate().cancel();
            outgoing.animate()
                    .translationX(outgoingTarget)
                    .alpha(0.88f)
                    .setDuration(duration)
                    .withEndAction(() -> {
                        if (generation == animationGeneration) finishModeChange(precipitation);
                    })
                    .start();
            incoming.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(duration)
                    .start();
        }

        private float directionalTranslation(float dx) {
            return precipitationMode ? Math.max(0f, dx) : Math.min(0f, dx);
        }

        private void settleBack() {
            if (overviewPageContent == null || precipitationPageContent == null) return;
            settling = true;
            final int generation = ++animationGeneration;
            long duration = animationsAllowed() ? 170L : 0L;
            animateModeSwitchProgress(precipitationMode ? 1f : 0f, duration);
            float width = Math.max(1, getWidth());
            View current = precipitationMode ? precipitationPageContent : overviewPageContent;
            View adjacent = precipitationMode ? overviewPageContent : precipitationPageContent;
            float adjacentTarget = precipitationMode ? -width : width;
            current.animate().cancel();
            adjacent.animate().cancel();
            current.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(duration)
                    .withEndAction(() -> {
                        if (generation != animationGeneration) return;
                        adjacent.setTranslationX(adjacentTarget);
                        adjacent.setAlpha(1f);
                        adjacent.setTranslationY(0f);
                        adjacent.setVisibility(View.INVISIBLE);
                        ((ForecastPageHost) forecastPageHost).setTransitioning(false);
                        settling = false;
                    })
                    .start();
            adjacent.animate().translationX(adjacentTarget).alpha(0.88f)
                    .setDuration(duration).start();
        }

        private void preparePageDrag() {
            float width = Math.max(1, getWidth());
            View current = precipitationMode ? precipitationPageContent : overviewPageContent;
            View adjacent = precipitationMode ? overviewPageContent : precipitationPageContent;
            current.setVisibility(View.VISIBLE);
            adjacent.setVisibility(View.VISIBLE);
            adjacent.setTranslationY(mainScroll == null ? 0f : mainScroll.getScrollY());
            ((ForecastPageHost) forecastPageHost).setTransitioning(true);
            current.setTranslationX(0f);
            current.setAlpha(1f);
            adjacent.setTranslationX(precipitationMode ? -width : width);
            adjacent.setAlpha(0.88f);
        }

        private void applyPageDrag(float dx) {
            if ((precipitationMode ? overviewPageContent : precipitationPageContent)
                    .getVisibility() != View.VISIBLE) preparePageDrag();
            float width = Math.max(1, getWidth());
            float progress = Math.min(1f, Math.abs(dx) / width);
            View current = precipitationMode ? precipitationPageContent : overviewPageContent;
            View adjacent = precipitationMode ? overviewPageContent : precipitationPageContent;
            current.setTranslationX(dx);
            current.setAlpha(1f - (0.12f * progress));
            adjacent.setTranslationX(dx + (precipitationMode ? -width : width));
            adjacent.setAlpha(0.88f + (0.12f * progress));
            setModeSwitchProgress(precipitationMode ? 1f - progress : progress);
        }

        private void finishModeChange(boolean precipitation) {
            switchForecastMode(precipitation);
            View active = precipitation ? precipitationPageContent : overviewPageContent;
            View inactive = precipitation ? overviewPageContent : precipitationPageContent;
            active.setTranslationX(0f);
            active.setTranslationY(0f);
            active.setAlpha(1f);
            active.setVisibility(View.VISIBLE);
            inactive.setTranslationX(0f);
            inactive.setTranslationY(0f);
            inactive.setAlpha(1f);
            inactive.setVisibility(View.INVISIBLE);
            ((ForecastPageHost) forecastPageHost).setTransitioning(false);
            setModeSwitchProgress(precipitation ? 1f : 0f);
            settling = false;
        }

        void resetPagePositions() {
            if (overviewPageContent == null || precipitationPageContent == null || settling) return;
            View active = precipitationMode ? precipitationPageContent : overviewPageContent;
            View inactive = precipitationMode ? overviewPageContent : precipitationPageContent;
            active.setTranslationX(0f);
            active.setTranslationY(0f);
            active.setAlpha(1f);
            active.setVisibility(View.VISIBLE);
            inactive.setTranslationX(0f);
            inactive.setTranslationY(0f);
            inactive.setAlpha(1f);
            inactive.setVisibility(View.INVISIBLE);
            ((ForecastPageHost) forecastPageHost).setTransitioning(false);
            setModeSwitchProgress(precipitationMode ? 1f : 0f);
        }

        private boolean hitsHorizontalControl(View view, float x, float y) {
            if (view instanceof HorizontalScrollView
                    || view instanceof MinutePrecipitationGraphView
                    || view instanceof ForecastChartView) return true;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    View child = group.getChildAt(i);
                    if (child.getVisibility() != View.VISIBLE) continue;
                    float childX = x + group.getScrollX() - child.getX();
                    float childY = y + group.getScrollY() - child.getY();
                    if (childX >= 0 && childX < child.getWidth()
                            && childY >= 0 && childY < child.getHeight()
                            && hitsHorizontalControl(child, childX, childY)) return true;
                }
            }
            return false;
        }
    }

    void addForecastModeSwitch() {
        modeSwitchHolder = new FrameLayout(this);
        modeSwitchGlass = new GlassDrawable(
                dp(23),
                Math.max(1f, getResources().getDisplayMetrics().density),
                true);
        applyGlassPalette(modeSwitchGlass);
        glassDrawables.add(modeSwitchGlass);
        modeSwitchHolder.setBackground(modeSwitchGlass);

        modeSwitchThumb = new View(this);
        GradientDrawable thumbBackground = roundedBg(Color.argb(92, 255, 255, 255), dp(20));
        thumbBackground.setStroke(dp(1), Color.argb(42, 255, 255, 255));
        modeSwitchThumb.setBackground(thumbBackground);
        modeSwitchThumb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(1, dp(48));
        thumbLp.leftMargin = dp(5);
        thumbLp.topMargin = dp(4);
        modeSwitchHolder.addView(modeSwitchThumb, thumbLp);

        LinearLayout labels = new LinearLayout(this);
        labels.setGravity(Gravity.CENTER);
        labels.setPadding(dp(5), dp(4), dp(5), dp(4));

        overviewModeButton = dailyModeButton("Overview", "Overview forecast view");
        precipitationModeButton = dailyModeButton("Precipitation", "Minute precipitation view");
        overviewModeButton.setTextSize(13);
        precipitationModeButton.setTextSize(13);
        overviewModeButton.setBackgroundColor(Color.TRANSPARENT);
        precipitationModeButton.setBackgroundColor(Color.TRANSPARENT);
        overviewModeButton.setOnClickListener(v -> forecastSwipeLayout.animateModeChange(false));
        precipitationModeButton.setOnClickListener(v -> forecastSwipeLayout.animateModeChange(true));
        labels.addView(overviewModeButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams precipLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        precipLp.leftMargin = dp(4);
        labels.addView(precipitationModeButton, precipLp);
        modeSwitchHolder.addView(labels, new FrameLayout.LayoutParams(-1, -1));
        modeSwitchHolder.addOnLayoutChangeListener((v, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> setModeSwitchProgress(modeSwitchProgress));

        LinearLayout.LayoutParams holderLp = new LinearLayout.LayoutParams(-1, dp(56));
        holderLp.topMargin = dp(11);
        holderLp.bottomMargin = dp(8);
        content.addView(modeSwitchHolder, holderLp);
        updateForecastModeButtons();
    }

    void updateForecastModeButtons() {
        if (overviewModeButton == null || precipitationModeButton == null) return;
        overviewModeButton.setSelected(!precipitationMode);
        precipitationModeButton.setSelected(precipitationMode);
        overviewModeButton.setContentDescription("Overview forecast view"
                + (precipitationMode ? ", not selected" : ", selected"));
        precipitationModeButton.setContentDescription("Minute precipitation view"
                + (precipitationMode ? ", selected" : ", not selected"));
        if (Build.VERSION.SDK_INT >= 30) {
            overviewModeButton.setStateDescription(precipitationMode ? "Not selected" : "Selected");
            precipitationModeButton.setStateDescription(precipitationMode ? "Selected" : "Not selected");
        }
        setModeSwitchProgress(precipitationMode ? 1f : 0f);
    }

    void setModeSwitchProgress(float value) {
        modeSwitchProgress = Math.max(0f, Math.min(1f, value));
        if (modeSwitchHolder == null || modeSwitchThumb == null) return;
        int innerWidth = modeSwitchHolder.getWidth() - dp(10) - dp(4);
        if (innerWidth <= 0) return;
        int thumbWidth = innerWidth / 2;
        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) modeSwitchThumb.getLayoutParams();
        if (params.width != thumbWidth) {
            params.width = thumbWidth;
            modeSwitchThumb.setLayoutParams(params);
        }
        modeSwitchThumb.setTranslationX((thumbWidth + dp(4)) * modeSwitchProgress);
        overviewModeButton.setAlpha(1f - 0.25f * modeSwitchProgress);
        precipitationModeButton.setAlpha(0.75f + 0.25f * modeSwitchProgress);
    }

    void animateModeSwitchProgress(float target, long duration) {
        if (modeSwitchAnimator != null) modeSwitchAnimator.cancel();
        if (duration <= 0L) {
            setModeSwitchProgress(target);
            return;
        }
        modeSwitchAnimator = ValueAnimator.ofFloat(modeSwitchProgress, target);
        modeSwitchAnimator.setDuration(duration);
        modeSwitchAnimator.addUpdateListener(animator ->
                setModeSwitchProgress((Float) animator.getAnimatedValue()));
        modeSwitchAnimator.start();
    }

    void switchForecastMode(boolean precipitation) {
        if (precipitationMode == precipitation) return;
        int currentScroll = mainScroll == null ? 0 : mainScroll.getScrollY();
        if (precipitation) {
            overviewScrollY = currentScroll;
        } else {
            precipitationScrollY = currentScroll;
        }
        forecastPreview.restore(false);
        precipitationMode = precipitation;
        activePageContent = precipitation ? precipitationPageContent : overviewPageContent;
        updateForecastModeButtons();
        int targetScroll = precipitation ? precipitationScrollY : overviewScrollY;
        if (mainScroll != null) {
            mainScroll.scrollTo(0, targetScroll);
            mainScroll.post(() -> {
                View child = mainScroll.getChildCount() == 0 ? null : mainScroll.getChildAt(0);
                int viewportHeight = Math.max(0,
                        mainScroll.getHeight() - mainScroll.getPaddingTop()
                                - mainScroll.getPaddingBottom());
                int maxScroll = child == null
                        ? 0 : Math.max(0, child.getHeight() - viewportHeight);
                mainScroll.scrollTo(0, Math.min(Math.max(0, targetScroll), maxScroll));
                if (precipitationMode && precipitation) ensureMinuteForecast(false);
            });
        } else if (precipitation) {
            ensureMinuteForecast(false);
        }
        if (!precipitation) forecastPreview.restore(false);
    }

    @Override
    void finishWeatherLoadSuccess(
            JSONObject current,
            JSONObject hourly,
            JSONObject daily,
            HourlyPageState pageState,
            int generation) {
        super.finishWeatherLoadSuccess(current, hourly, daily, pageState, generation);
        if (generation != weatherRequestGeneration || weatherLoadActive) return;
        if (RainAlertManager.isEnabled(this)) ensureMinuteForecast(false);
    }

    void continueStartupAfterApiKey() {
        progress.setVisibility(View.VISIBLE);
        boolean forceNetwork = forceNextWeatherLoad;
        forceNextWeatherLoad = false;
        locationRefreshCoordinator.refresh(forceNetwork, true);
    }

    void showApiKeySetupDialog() {
        if (apiKeySetupDialog != null && apiKeySetupDialog.isShowing()) return;

        final Dialog dialog = new Dialog(this);
        apiKeySetupDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setClipToPadding(false);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(20), dp(20), dp(18));
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(Color.argb(246, 12, 23, 39));
        panelBackground.setCornerRadius(dp(24));
        panelBackground.setStroke(dp(1), Color.argb(54, 255, 255, 255));
        panel.setBackground(panelBackground);
        scroller.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Set up Zwerk Weather", 23, false, WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(title);

        TextView explanation = text(
                "Zwerk Weather uses Google Weather. Your key is saved only in this app's private storage and is never shown after saving.",
                13,
                false,
                SOFT_WHITE);
        explanation.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams explanationLp = new LinearLayout.LayoutParams(-1, -2);
        explanationLp.topMargin = dp(5);
        panel.addView(explanation, explanationLp);

        addApiKeySetupGuide(panel);

        TextView label = text("Google API key", 13, true, WHITE);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-1, -2);
        labelLp.topMargin = dp(18);
        panel.addView(label, labelLp);

        EditText field = apiKeyEditText();
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(-1, dp(52));
        fieldLp.topMargin = dp(7);
        panel.addView(field, fieldLp);

        TextView error = text("", 12, false, Color.rgb(255, 191, 191));
        error.setVisibility(View.GONE);
        LinearLayout.LayoutParams errorLp = new LinearLayout.LayoutParams(-1, -2);
        errorLp.topMargin = dp(8);
        panel.addView(error, errorLp);

        Button save = coordinateDialogButton("Save & continue", true);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(-1, dp(48));
        saveLp.topMargin = dp(16);
        panel.addView(save, saveLp);

        View.OnClickListener saveAction = v -> {
            String candidate = validatedApiKeyInput(field);
            if (candidate.isEmpty()) {
                showApiKeySaveFailure(error);
                return;
            }
            try {
                writeApiKeyAtomically(candidate);
                field.getText().clear();
                error.setVisibility(View.GONE);
                status.setText("Preparing Zwerk Weather…");
                progress.setVisibility(View.VISIBLE);
                forceNextWeatherLoad = true;
                dialog.dismiss();
                continueStartupAfterApiKey();
            } catch (Exception ignored) {
                showApiKeySaveFailure(error);
            }
        };
        save.setOnClickListener(saveAction);
        field.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                save.performClick();
                return true;
            }
            return false;
        });

        dialog.setOnDismissListener(ignored -> {
            if (apiKeySetupDialog == dialog) apiKeySetupDialog = null;
        });
        dialog.setContentView(scroller);
        dialog.show();

        Window dialogWindow = dialog.getWindow();
        if (dialogWindow != null) {
            dialogWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = dialogWindow.getAttributes();
            attributes.dimAmount = 0.78f;
            dialogWindow.setAttributes(attributes);
            dialogWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            dialogWindow.setGravity(Gravity.CENTER);
            dialogWindow.getDecorView().setPadding(0, 0, 0, 0);

            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int dialogWidth = Math.min(screenWidth - dp(32), dp(420));
            dialogWindow.setLayout(
                    Math.max(1, dialogWidth),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    void addApiKeySetupGuide(LinearLayout panel) {
        TextView heading = text("Get a key in 3 steps", 14, true, WHITE);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(-1, -2);
        headingLp.topMargin = dp(16);
        panel.addView(heading, headingLp);

        TextView steps = text(
                "1. Create or select a Google Cloud project and enable billing.\n"
                        + "2. Enable Weather API. Enable Air Quality API and Pollen API too if you want those optional tiles.\n"
                        + "3. Create a key. Add an Android app restriction with the package and SHA-1 below. Under API restrictions, allow every API you enabled, then paste the key below.",
                12,
                false,
                SOFT_WHITE);
        steps.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams stepsLp = new LinearLayout.LayoutParams(-1, -2);
        stepsLp.topMargin = dp(5);
        panel.addView(steps, stepsLp);

        TextView identity = text(androidRestrictionIdentity(), 11, false, FAINT_WHITE);
        identity.setTextIsSelectable(true);
        identity.setContentDescription("Android API key restriction identity. "
                + androidRestrictionIdentity().replace("\n", ". "));
        LinearLayout.LayoutParams identityLp = new LinearLayout.LayoutParams(-1, -2);
        identityLp.topMargin = dp(8);
        panel.addView(identity, identityLp);

        LinearLayout links = new LinearLayout(this);
        links.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams linksLp = new LinearLayout.LayoutParams(-1, dp(44));
        linksLp.topMargin = dp(10);
        panel.addView(links, linksLp);

        Button guide = coordinateDialogButton("Setup guide ↗", false);
        guide.setOnClickListener(v -> openExternalUrl(API_KEY_GUIDE_URL));
        links.addView(guide, new LinearLayout.LayoutParams(0, dp(44), 1f));

        Button console = coordinateDialogButton("Cloud Console ↗", false);
        console.setOnClickListener(v -> openExternalUrl(GOOGLE_CLOUD_CREDENTIALS_URL));
        LinearLayout.LayoutParams consoleLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        consoleLp.leftMargin = dp(8);
        links.addView(console, consoleLp);
    }

    void openExternalUrl(String address) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address)));
        } catch (Exception ignored) {
            Toast.makeText(this, "No browser is available to open this link.", Toast.LENGTH_SHORT).show();
        }
    }

    String androidRestrictionIdentity() {
        String fingerprint = signingCertificateSha1();
        return "Android restriction\nPackage: " + getPackageName()
                + (fingerprint.isEmpty() ? "" : "\nSHA-1: " + fingerprint);
    }

    String signingCertificateSha1() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(
                    getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null) return "";
            android.content.pm.Signature[] signatures = info.signingInfo.getApkContentsSigners();
            if (signatures == null || signatures.length == 0) return "";
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(signatures[0].toByteArray());
            StringBuilder value = new StringBuilder();
            for (byte part : digest) {
                if (value.length() > 0) value.append(':');
                value.append(String.format(Locale.US, "%02X", part & 0xff));
            }
            return value.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    EditText apiKeyEditText() {
        EditText field = new EditText(this);
        field.setHint("API key");
        field.setTextColor(WHITE);
        field.setHintTextColor(Color.argb(110, 255, 255, 255));
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setTransformationMethod(PasswordTransformationMethod.getInstance());
        int imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        if (Build.VERSION.SDK_INT >= 26) {
            imeOptions |= android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        }
        field.setImeOptions(imeOptions);
        field.setBackground(coordinateFieldBackground());
        field.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 26) {
            field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            field.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO);
        }
        return field;
    }

    static String validatedApiKeyInput(EditText field) {
        if (field == null || field.getText() == null) return "";
        String value = field.getText().toString().trim();
        if (value.length() < 8 || value.length() > 512) return "";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) return "";
        }
        return value;
    }

    void showApiKeySaveFailure(TextView error) {
        error.setText("Could not save the key. Check the value and try again.");
        error.setVisibility(View.VISIBLE);
    }

    boolean hasConfiguredApiKey() {
        try {
            return !readApiKey().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    void writeApiKeyAtomically(String value) throws Exception {
        File target = new File(getFilesDir(), API_KEY_FILE);
        File temp = new File(getFilesDir(), API_KEY_TEMP_FILE);
        if (temp.exists() && !temp.delete()) {
            throw new IOException("Could not prepare private credential file");
        }
        try {
            try (FileOutputStream out = openFileOutput(API_KEY_TEMP_FILE, MODE_PRIVATE)) {
                out.write(value.getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
            Os.rename(temp.getAbsolutePath(), target.getAbsolutePath());
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    void selectDeviceLocationAndRefresh() {
        locationRefreshCoordinator.selectDeviceLocation(false);
    }

    boolean shouldProtectSelectedLocation() {
        if (getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getBoolean(PREF_EXPLICIT_NON_DEVICE_LOCATION, false)) {
            return true;
        }
        CityManagerActivity.LocationSnapshot selected = CityManagerActivity.getSelectedLocation(this);
        return selected != null && !selected.isDevice;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (locationRefreshCoordinator != null) {
            locationRefreshCoordinator.onPermissionResult(requestCode, results);
        }
    }

    @Override
    public boolean isManualLocationProtected() {
        return shouldProtectSelectedLocation();
    }

    @Override
    public int locationSelectionGeneration() {
        return locationSelectionGeneration;
    }

    @Override
    public void onLocationCheckStarted() {
        if (status != null) status.setText("Finding your location…");
    }

    @Override
    public void onDeviceLocationResolved(
            Location location,
            boolean explicitDeviceSelection,
            boolean forceNetwork,
            int selectionGeneration) {
        if (location == null) {
            startWeatherAfterLocationCheck(forceNetwork);
            return;
        }
        if (!explicitDeviceSelection
                && (shouldProtectSelectedLocation()
                        || selectionGeneration != locationSelectionGeneration)) {
            startWeatherAfterLocationCheck(forceNetwork);
            return;
        }

        float[] distance = new float[1];
        Location.distanceBetween(
                latitude,
                longitude,
                location.getLatitude(),
                location.getLongitude(),
                distance);
        boolean locationChangedEnough = !usingDeviceLocation
                || distance[0] >= DeviceLocationRefreshCoordinator.LOCATION_CHANGE_THRESHOLD_METERS;
        if (!explicitDeviceSelection && !locationChangedEnough) {
            startWeatherAfterLocationCheck(forceNetwork);
            return;
        }

        applyLocationSelection(
                location.getLatitude(),
                location.getLongitude(),
                null,
                true,
                explicitDeviceSelection);
        startWeatherAfterLocationCheck(forceNetwork);
        resolvePlaceName(location.getLatitude(), location.getLongitude());
    }

    @Override
    public void onLocationCheckUnavailable(boolean forceNetwork) {
        startWeatherAfterLocationCheck(forceNetwork);
    }

    void startWeatherAfterLocationCheck(boolean forceNetwork) {
        startWeatherLoad(forceNetwork);
        if (minuteReloadAfterLocationCheckPending) {
            minuteReloadAfterLocationCheckPending = false;
            if (precipitationMode) ensureMinuteForecast(true);
        }
    }

    void openCityManager() {
        startActivityForResult(new Intent(this, CityManagerActivity.class), CITY_MANAGER_REQUEST);
    }

    void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_SCENE, displayedScene)
                .putExtra(SettingsActivity.EXTRA_DAYTIME, displayedDaytime)
                .putExtra(SettingsActivity.EXTRA_CARD_TOP, glassCardTop)
                .putExtra(SettingsActivity.EXTRA_CARD_BOTTOM, glassCardBottom)
                .putExtra(SettingsActivity.EXTRA_TILE_TOP, glassTileTop)
                .putExtra(SettingsActivity.EXTRA_TILE_BOTTOM, glassTileBottom)
                .putExtra(SettingsActivity.EXTRA_ACCENT, settingsAccent(displayedScene));
        startActivityForResult(intent, SETTINGS_REQUEST);
    }

    void showCoordinateDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setClipToPadding(false);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(20), dp(20), dp(18));
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(Color.argb(244, 14, 23, 34));
        panelBackground.setCornerRadius(dp(24));
        panelBackground.setStroke(dp(1), Color.argb(42, 255, 255, 255));
        panel.setBackground(panelBackground);
        scroller.addView(panel, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Forecast location", 23, false, WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setContentDescription("Forecast location");
        panel.addView(title);

        TextView explanation = text(
                "Enter a precise forecast point. Signed decimal coordinates are supported.",
                13,
                false,
                SOFT_WHITE);
        explanation.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams explanationLp = new LinearLayout.LayoutParams(-1, -2);
        explanationLp.topMargin = dp(5);
        panel.addView(explanation, explanationLp);

        TextView latLabel = text("Latitude", 13, true, WHITE);
        LinearLayout.LayoutParams firstLabelLp = new LinearLayout.LayoutParams(-1, -2);
        firstLabelLp.topMargin = dp(18);
        panel.addView(latLabel, firstLabelLp);

        TextView latExample = text("Example 50.8503  •  Range −90 to 90", 11, false, FAINT_WHITE);
        LinearLayout.LayoutParams helperLp = new LinearLayout.LayoutParams(-1, -2);
        helperLp.topMargin = dp(2);
        panel.addView(latExample, helperLp);

        EditText lat = coordinateEditText(
                String.format(Locale.US, "%.4f", latitude),
                "50.8503",
                "Latitude. Range minus 90 to 90 degrees.");
        lat.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(-1, dp(52));
        fieldLp.topMargin = dp(7);
        panel.addView(lat, fieldLp);

        TextView lonLabel = text("Longitude", 13, true, WHITE);
        LinearLayout.LayoutParams secondLabelLp = new LinearLayout.LayoutParams(-1, -2);
        secondLabelLp.topMargin = dp(14);
        panel.addView(lonLabel, secondLabelLp);

        TextView lonExample = text("Example 4.3517  •  Range −180 to 180", 11, false, FAINT_WHITE);
        LinearLayout.LayoutParams lonHelperLp = new LinearLayout.LayoutParams(-1, -2);
        lonHelperLp.topMargin = dp(2);
        panel.addView(lonExample, lonHelperLp);

        EditText lon = coordinateEditText(
                String.format(Locale.US, "%.4f", longitude),
                "4.3517",
                "Longitude. Range minus 180 to 180 degrees.");
        lon.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        LinearLayout.LayoutParams lonFieldLp = new LinearLayout.LayoutParams(-1, dp(52));
        lonFieldLp.topMargin = dp(7);
        panel.addView(lon, lonFieldLp);

        Button useLocation = coordinateDialogButton("Use my location", false);
        useLocation.setContentDescription("Use my location for the forecast");
        LinearLayout.LayoutParams locationLp = new LinearLayout.LayoutParams(-1, dp(48));
        locationLp.topMargin = dp(18);
        panel.addView(useLocation, locationLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, dp(48));
        actionsLp.topMargin = dp(10);
        panel.addView(actions, actionsLp);

        Button cancel = coordinateDialogButton("Cancel", false);
        cancel.setContentDescription("Cancel coordinate entry");
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        actions.addView(cancel, cancelLp);

        Button load = coordinateDialogButton("Load", true);
        load.setContentDescription("Load forecast for these coordinates");
        LinearLayout.LayoutParams loadLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        loadLp.leftMargin = dp(10);
        actions.addView(load, loadLp);

        cancel.setOnClickListener(v -> dialog.dismiss());
        useLocation.setOnClickListener(v -> {
            dialog.dismiss();
            selectDeviceLocationAndRefresh();
        });
        load.setOnClickListener(v -> {
            try {
                double newLat = Double.parseDouble(lat.getText().toString().trim());
                double newLon = Double.parseDouble(lon.getText().toString().trim());
                if (newLat < -90 || newLat > 90 || newLon < -180 || newLon > 180) {
                    throw new NumberFormatException();
                }
                dialog.dismiss();
                acceptLocation(newLat, newLon, null, false);
            } catch (NumberFormatException e) {
                dialog.dismiss();
                Toast.makeText(this, "Invalid coordinates", Toast.LENGTH_SHORT).show();
            }
        });
        lon.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                load.performClick();
                return true;
            }
            return false;
        });

        dialog.setContentView(scroller);
        dialog.show();

        Window dialogWindow = dialog.getWindow();
        if (dialogWindow != null) {
            dialogWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = dialogWindow.getAttributes();
            attributes.dimAmount = 0.72f;
            dialogWindow.setAttributes(attributes);
            dialogWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            dialogWindow.setGravity(Gravity.CENTER);
            dialogWindow.getDecorView().setPadding(0, 0, 0, 0);

            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int dialogWidth = Math.min(screenWidth - dp(32), dp(420));
            dialogWindow.setLayout(
                    Math.max(1, dialogWidth),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    EditText coordinateEditText(String value, String hint, String accessibilityLabel) {
        EditText field = new EditText(this);
        field.setText(value);
        field.setHint(hint);
        field.setTextColor(WHITE);
        field.setHintTextColor(Color.argb(110, 255, 255, 255));
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setSelectAllOnFocus(true);
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        field.setBackground(coordinateFieldBackground());
        field.setContentDescription(accessibilityLabel + " Current value " + value + ".");
        return field;
    }

    StateListDrawable coordinateFieldBackground() {
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Color.argb(44, 255, 255, 255));
        focused.setCornerRadius(dp(14));
        focused.setStroke(dp(1), Color.argb(220, 151, 211, 255));

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.argb(25, 255, 255, 255));
        normal.setCornerRadius(dp(14));
        normal.setStroke(dp(1), Color.argb(50, 255, 255, 255));

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, normal);
        return states;
    }

    Button coordinateDialogButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(WHITE);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setMinHeight(dp(48));

        int normalColor = primary
                ? Color.rgb(45, 112, 214)
                : Color.argb(34, 255, 255, 255);
        int pressedColor = primary
                ? Color.rgb(58, 132, 232)
                : Color.argb(58, 255, 255, 255);

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(normalColor);
        normal.setCornerRadius(dp(14));

        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(pressedColor);
        pressed.setCornerRadius(dp(14));

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{android.R.attr.state_focused}, pressed);
        states.addState(new int[]{}, normal);
        button.setBackground(states);
        return button;
    }

    void acceptLocation(double lat, double lon, String explicitName, boolean isDevice) {
        if (locationRefreshCoordinator != null) locationRefreshCoordinator.onSelectionChanged();
        applyLocationSelection(lat, lon, explicitName, isDevice, !isDevice);
        refreshWeather();
        resolvePlaceName(lat, lon);
    }

    void applyLocationSelection(
            double lat,
            double lon,
            String explicitName,
            boolean isDevice,
            boolean explicitSelection) {
        if (explicitSelection) {
            locationSelectionGeneration++;
        }
        invalidateMinuteForecastState();
        latitude = lat;
        longitude = lon;
        usingDeviceLocation = isDevice;
        locationName = explicitName != null
                ? explicitName
                : String.format(Locale.US, "%.3f°, %.3f°", lat, lon);
        locationTitle.setText(locationName);
        getPreferences(MODE_PRIVATE).edit()
                .putFloat("lat", (float) lat)
                .putFloat("lon", (float) lon)
                .putString("name", locationName)
                .apply();

        android.content.SharedPreferences.Editor selectionEditor =
                getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit();
        if (!isDevice) {
            selectionEditor.putBoolean(PREF_EXPLICIT_NON_DEVICE_LOCATION, true);
        } else if (explicitSelection) {
            selectionEditor.putBoolean(PREF_EXPLICIT_NON_DEVICE_LOCATION, false);
        }
        selectionEditor.apply();

        selectedLocationId = isDevice
                ? CityManagerActivity.upsertDeviceLocation(
                        this, locationName, lat, lon, "", "", true)
                : CityManagerActivity.upsertLocation(
                        this, null, locationName, lat, lon, false, "", "", true);
    }

    void resolvePlaceName(double lat, double lon) {
        final int expectedSelectionGeneration = locationSelectionGeneration;
        final String expectedLocationId = selectedLocationId == null ? "" : selectedLocationId;
        final boolean expectedDeviceLocation = usingDeviceLocation;
        executor.execute(() -> {
            try {
                List<Address> results = new Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1);
                if (results == null || results.isEmpty()) return;
                Address a = results.get(0);
                String name = a.getLocality();
                if (name == null || name.isEmpty()) name = a.getSubAdminArea();
                if (name == null || name.isEmpty()) name = a.getAdminArea();
                if (name == null || name.isEmpty()) return;

                final String resolved = name;
                runOnUiThread(() -> {
                    if (isFinishing()
                            || (Build.VERSION.SDK_INT >= 17 && isDestroyed())
                            || expectedSelectionGeneration != locationSelectionGeneration
                            || Math.abs(latitude - lat) > WEATHER_CACHE_COORDINATE_TOLERANCE
                            || Math.abs(longitude - lon) > WEATHER_CACHE_COORDINATE_TOLERANCE
                            || !(selectedLocationId == null ? "" : selectedLocationId)
                                    .equals(expectedLocationId)
                            || usingDeviceLocation != expectedDeviceLocation) {
                        return;
                    }
                    locationName = resolved;
                    getPreferences(MODE_PRIVATE).edit().putString("name", resolved).apply();
                    selectedLocationId = CityManagerActivity.updateSelectedLocationSnapshot(
                            this, resolved, lat, lon, expectedDeviceLocation, "", "");
                    android.content.SharedPreferences widgetPrefs = getSharedPreferences(
                            WeatherWidgetProvider.PREFS_NAME, MODE_PRIVATE);
                    if (widgetPrefs.getBoolean(WeatherWidgetProvider.KEY_HAS_SNAPSHOT, false)) {
                        widgetPrefs.edit()
                                .putString(WeatherWidgetProvider.KEY_CITY, resolved)
                                .apply();
                        WeatherWidgetProvider.requestRefresh(this);
                    }
                    locationTitle.setText(resolved);
                    updatePreviewSubtitle();
                });
            } catch (Exception ignored) {
                // The coordinate label remains usable if reverse geocoding is unavailable.
            }
        });
    }

    void refreshWeather() {
        boolean forceNetwork = forceNextWeatherLoad;
        forceNextWeatherLoad = false;
        refreshWeather(forceNetwork);
    }

    void refreshWeather(boolean forceNetwork) {
        if (locationRefreshCoordinator == null) {
            startWeatherLoad(forceNetwork);
            return;
        }
        locationRefreshCoordinator.refresh(forceNetwork, false);
    }

    void refreshWeather(boolean forceNetwork, boolean refreshMinuteForecast) {
        minuteReloadAfterLocationCheckPending |= refreshMinuteForecast;
        refreshWeather(forceNetwork);
    }


    protected void onResume() {
        super.onResume();
        boolean enabled = animationsAllowed();
        if (skyLayout != null) {
            skyLayout.setAnimationRunning(enabled);
        }
        for (SunTrackView track : sunTrackViews) {
            if (track != null) track.setAnimationRunning(enabled);
        }

        if (!hasResumedOnce) {
            hasResumedOnce = true;
        } else if (suppressNextResumeWeatherLoad) {
            suppressNextResumeWeatherLoad = false;
        } else if (lastCurrentWeather != null
                && hasConfiguredApiKey()
                && apiKeySetupDialog == null
                && !weatherLoadActive) {
            refreshWeather();
        }
    }

    @Override
    protected void onPause() {
        forecastPreview.restore(false);
        if (skyLayout != null) skyLayout.setAnimationRunning(false);
        for (SunTrackView track : sunTrackViews) {
            if (track != null) track.setAnimationRunning(false);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (locationRefreshCoordinator != null) locationRefreshCoordinator.destroy();
        weatherRequestGeneration++;
        hourlyPageState = null;
        synchronized (minuteForecastLock) {
            minuteForecastState = null;
        }
        synchronized (optionalDataLock) {
            airQualityRequestSerial++;
            pollenRequestSerial++;
            airQualityState = null;
            pollenState = null;
        }
        synchronized (hourlyCoverageLock) {
            hourlyCoverageLoadActive = false;
            pendingHourlyCoverageTarget = null;
            hourlyCoverageLoadingTarget = null;
            hourlyCoverageCoordinators.clear();
        }
        forecastPreview.dispose();
        if (skyLayout != null) skyLayout.release();
        if (headerGlass != null) headerGlass.release();
        glassDrawables.clear();
        sunTrackViews.clear();
        releaseTrackMarkerBitmaps();
        cancelMinuteExpiration();
        optionalExecutor.shutdownNow();
        cacheExecutor.shutdownNow();
        executor.shutdownNow();
        super.onDestroy();
    }


}
