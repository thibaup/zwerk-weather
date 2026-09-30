package com.zwerk.weather;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import android.system.Os;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

public class MainActivity extends WeatherSettingsFlowActivity implements DeviceLocationRefreshCoordinator.Callback {
    static final String ACTION_WIDGET_REFRESH = "com.zwerk.weather.action.WIDGET_REFRESH_FORECAST";
    static final String ACTION_WIDGET_PRECIPITATION =
            "com.zwerk.weather.action.WIDGET_OPEN_PRECIPITATION";
    private static final int PAGE_OVERVIEW = 0;
    private static final int PAGE_DAILY = 1;
    private static final int PAGE_PRECIPITATION = 2;
    private static final int PAGE_RADAR = 3;
    private static final int PAGE_SETTINGS = 4;
    private static final String PREF_SUPPORT_FIRST_OPEN = "support_first_open_at";
    private static final String PREF_SUPPORT_LAUNCH_COUNT = "support_launch_count";
    private static final long SUPPORT_PROMPT_DELAY_MILLIS = 3L * 24L * 60L * 60L * 1000L;
    private final Handler supportPromptHandler = new Handler(Looper.getMainLooper());
    private final Runnable supportPromptTask = this::showSupportPromptIfEligible;
    private Dialog supportPromptDialog;
    private ForecastSwipeLayout forecastSwipeLayout;
    private LinearLayout radarPageContent;
    private RadarPageView radarPageView;
    private TextView radarModeButton;
    private TextView dailyModeTab;
    private TextView settingsModeTab;
    private LinearLayout settingsPageContent;
    private final LinearLayout[] weatherPageWrappers = new LinearLayout[4];
    private final TextView[] weatherPageStatus = new TextView[PAGE_PRECIPITATION + 1];
    private final ProgressBar[] weatherPageProgress = new ProgressBar[PAGE_PRECIPITATION + 1];
    private SettingsScreen settingsScreen;
    private Bundle restoredSettingsState;
    private Intent pendingSettingsAction;
    private boolean precipitationPageEnabled;
    private boolean radarPageEnabled;
    private int selectedForecastPage = PAGE_OVERVIEW;
    private int transitionTargetPage = PAGE_OVERVIEW;
    private final android.graphics.Rect systemBarInsets = new android.graphics.Rect();
    private boolean radarRefreshIndicatorActive;
    private boolean precipitationRefreshIndicatorActive;
    private FrameLayout modeSwitchHolder;
    private LinearLayout modeSwitchLabels;
    private View modeSwitchThumb;
    private ValueAnimator modeSwitchAnimator;
    private float modeSwitchProgress;
    private float locationGestureStartX;
    private float locationGestureStartY;
    private boolean locationGestureActive;
    private static final long STATUS_AGE_REFRESH_INTERVAL_MILLIS = 15_000L;
    private final Handler statusAgeHandler = new Handler(Looper.getMainLooper());
    private boolean statusAgeRefreshRunning;
    private final Runnable statusAgeRefresh = new Runnable() {
        @Override public void run() {
            if (!statusAgeRefreshRunning) return;
            if (OpenMeteoForecastClient.pruneElapsedHourly(lastHourlyWeather, Instant.now())) {
                rerenderLastWeather();
            }
            if (radarPageView != null && selectedForecastPage == PAGE_RADAR) {
                radarPageView.updateMapTheme();
            }
            if (statusShowsDataAge()) notifyActiveForecastStatusChanged();
            statusAgeHandler.postDelayed(this, STATUS_AGE_REFRESH_INTERVAL_MILLIS);
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocaleManager.wrap(newBase));
    }

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        recordSupportPromptLaunch();
        RainAlertManager.reconcile(this);
        weatherPreferences = new WeatherPreferences(this);
        forecastDiskCache = new ForecastDiskCache(this);
        boolean widgetRefreshRequested = consumeWidgetRefreshIntent(getIntent());
        boolean widgetPrecipitationRequested = consumeWidgetPrecipitationIntent(getIntent());
        forceNextWeatherLoad |= widgetRefreshRequested;
        precipitationPageEnabled = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.PREF_PRECIPITATION_PAGE, true);
        radarPageEnabled = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.PREF_RADAR_PAGE, true);
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

        restoredSettingsState = state == null ? null : state.getBundle("settings_screen");
        buildShell();
        if (state != null && !widgetRefreshRequested) {
            int restoredPage = state.getInt("selected_page", PAGE_OVERVIEW);
            selectedForecastPage = forecastPageEnabled(restoredPage) ? restoredPage : PAGE_OVERVIEW;
            activePageContent = activeForecastPageContent();
            forecastSwipeLayout.resetPagePositions();
            updateForecastModeButtons();
        }
        if (Build.VERSION.SDK_INT >= 33) MainBackNavigation.register(this, this::handleMainBack);
        content.postDelayed(() -> UpdateChecker.checkForUpdates(this, false), 1800L);
        if (!OpenMeteoConfig.isOpenMeteo(this) && !hasConfiguredApiKey()) {
            status.setText(UiTranslations.text(this, "Google API key required"));
            progress.setVisibility(View.GONE);
            showApiKeySetupDialog();
            return;
        }
        continueStartupAfterApiKey();
        if (widgetPrecipitationRequested && precipitationPageEnabled) {
            switchForecastMode(PAGE_PRECIPITATION);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (consumeWidgetPrecipitationIntent(intent)) {
            if (precipitationPageEnabled) switchForecastMode(PAGE_PRECIPITATION);
            return;
        }
        if (!consumeWidgetRefreshIntent(intent)) return;
        suppressNextResumeWeatherLoad = true;
        cancelForecastSwipe();
        switchForecastMode(PAGE_OVERVIEW);
        forecastSwipeLayout.resetPagePositions();
        if (!OpenMeteoConfig.isOpenMeteo(this) && !hasConfiguredApiKey()) {
            forceNextWeatherLoad = true;
            showApiKeySetupDialog();
        } else {
            performPullRefresh();
        }
    }

    private static boolean consumeWidgetRefreshIntent(Intent intent) {
        if (intent == null || !ACTION_WIDGET_REFRESH.equals(intent.getAction())) return false;
        // A recreated activity must not replay a request the user already made.
        intent.setAction(Intent.ACTION_MAIN);
        return true;
    }

    private boolean consumeWidgetPrecipitationIntent(Intent intent) {
        if (intent == null || !ACTION_WIDGET_PRECIPITATION.equals(intent.getAction())) return false;
        minuteRangeHours = MINUTE_RANGE_SIX_HOURS;
        intent.setAction(Intent.ACTION_MAIN);
        return true;
    }

    void configureWindow() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.setFlags(
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        WeatherSystemBars.applyAppearance(window);
    }

    void buildShell() {
        skyLayout = new SkyLayout(this);
        SkyLayout root = skyLayout;

        mainScroll = new RefreshScrollView(this);
        RefreshScrollView scroll = mainScroll;
        scroll.setContentDrawListener(this::refreshForecastGlassCaptures);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        scroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (headerGlass != null) headerGlass.requestBlurRefresh();
            if (bottomGlass != null) bottomGlass.requestBlurRefresh();
            updateOverviewBackgroundBlur(scrollY);
        });
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot && forecastPageHost != null) forecastPageHost.requestLayout();
        });

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
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
            if (forecastPreview.isPreviewing()) {
                forecastPreview.restore(true);
                if (expandedDayIndex >= 0) {
                    expandedDayIndex = -1;
                    rerenderDailyPage();
                }
            } else {
                openCityManager();
            }
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
        places.setContentDescription(UiTranslations.text(this, "Choose forecast location"));
        places.setOnClickListener(v -> openCityManager());
        toolbar.addView(places, new LinearLayout.LayoutParams(dp(44), dp(44)));

        FrameLayout.LayoutParams toolbarLp = new FrameLayout.LayoutParams(-1, dp(68), Gravity.TOP);
        root.addView(toolbar, toolbarLp);
        setContentView(root);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Rect bars = WeatherSystemBars.safeInsets(insets);
            int top = bars.top;
            int bottom = bars.bottom;
            int pinnedHeaderBottom = top + dp(68);
            applyPageInsets(bars);
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
            if (modeSwitchHolder != null) {
                FrameLayout.LayoutParams dock = (FrameLayout.LayoutParams) modeSwitchHolder.getLayoutParams();
                dock.bottomMargin = bottom + dp(10);
                modeSwitchHolder.setLayoutParams(dock);
                if (bottomGlass != null) bottomGlass.requestBlurRefresh();
            }
            if (refreshIndicator != null) {
                FrameLayout.LayoutParams refreshParams =
                        (FrameLayout.LayoutParams) refreshIndicator.getLayoutParams();
                refreshParams.topMargin = pinnedHeaderBottom + dp(6);
                refreshIndicator.setLayoutParams(refreshParams);
            }
            return insets;
        });

        // Keep the shared loading state, but render it inside each weather page.
        // Settings can then slide in at its final height without hiding a sibling row.
        status = text("Preparing Zwerk Weather…", 13, false, SOFT_WHITE);
        status.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                for (int page = PAGE_OVERVIEW; page <= PAGE_DAILY; page++) {
                    if (weatherPageStatus[page] != null) weatherPageStatus[page].setText(s);
                }
            }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        progress = new WeatherProgressIndicator(this);
        progress.setIndeterminate(true);

        addForecastModeSwitch();
        forecastPageHost = new ForecastPageHost(this);
        forecastPageHost.setClipChildren(true);
        forecastPageHost.setClipToPadding(true);

        overviewPageContent = forecastPage();
        dailyPageContent = forecastPage();
        precipitationPageContent = forecastPage();
        radarPageContent = forecastPage();
        settingsPageContent = forecastPage();
        settingsScreen = new SettingsScreen(this, settingsPageContent, result -> {
            pendingSettingsAction = result;
            forecastSwipeLayout.animateModeChange(PAGE_OVERVIEW);
        });
        settingsScreen.initialize(restoredSettingsState);
        radarPageView = new RadarPageView(this);
        radarPageView.setStatusChangedListener(this::notifyActiveForecastStatusChanged);
        radarPageView.setLocation(latitude, longitude);
        radarPageContent.addView(radarPageView,
                new LinearLayout.LayoutParams(-1, -1));
        activePageContent = overviewPageContent;
        createWeatherPage(PAGE_OVERVIEW, overviewPageContent);
        createWeatherPage(PAGE_DAILY, dailyPageContent);
        createWeatherPage(PAGE_PRECIPITATION, precipitationPageContent);
        weatherPageWrappers[PAGE_RADAR] = radarPageContent;
        radarPageContent.setVisibility(View.INVISIBLE);
        forecastPageHost.addView(radarPageContent, new FrameLayout.LayoutParams(-1, -1));
        forecastPageHost.addView(settingsPageContent,
                new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        settingsPageContent.setVisibility(View.INVISIBLE);
        applyPageInsets(systemBarInsets);
        notifyActiveForecastStatusChanged();
        content.addView(forecastPageHost, new LinearLayout.LayoutParams(-1, -2));
        updatePreviewSubtitle();
    }

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

    /** Preserve weather spacing while keeping radar controls clear of the header and dock. */
    private void applyPageInsets(android.graphics.Rect bars) {
        systemBarInsets.set(bars);
        int headerBottom = bars.top + dp(68);
        int top = headerBottom + dp(8);
        int bottom = bars.bottom + dp(110);
        for (int page = PAGE_OVERVIEW; page <= PAGE_PRECIPITATION; page++) {
            LinearLayout wrapper = weatherPageWrappers[page];
            if (wrapper != null) wrapper.setPadding(dp(18), top, dp(18), bottom);
        }
        if (settingsPageContent != null) {
            settingsPageContent.setPadding(dp(18), top, dp(18), bottom);
        }
        if (radarPageView != null) radarPageView.setViewportInsets(
                bars.left, headerBottom, bars.right, bars.bottom + dp(72) + dp(10));
    }

    private void createWeatherPage(int page, LinearLayout pageContent) {
        LinearLayout wrapper = forecastPage();
        weatherPageWrappers[page] = wrapper;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(7));
        StatusGlyphView glyph = new StatusGlyphView(this);
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(glyph, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView caption = text(status.getText().toString(), 13, false, SOFT_WHITE);
        caption.setGravity(Gravity.CENTER_VERTICAL);
        caption.setOnClickListener(v -> performPullRefresh());
        caption.setTooltipText(UiTranslations.text(this, "Refresh forecast"));
        LinearLayout.LayoutParams captionLp = new LinearLayout.LayoutParams(0, dp(28), 1f);
        captionLp.leftMargin = dp(6);
        row.addView(caption, captionLp);
        weatherPageStatus[page] = caption;
        wrapper.addView(row);
        ProgressBar indicator = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        indicator.setIndeterminate(true);
        indicator.setAlpha(0.58f);
        indicator.setVisibility(progress.getVisibility());
        weatherPageProgress[page] = indicator;
        wrapper.addView(indicator, new LinearLayout.LayoutParams(-1, dp(1)));
        wrapper.addView(pageContent, new LinearLayout.LayoutParams(-1, -2));
        wrapper.setVisibility(page == selectedForecastPage ? View.VISIBLE : View.INVISIBLE);
        forecastPageHost.addView(wrapper, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
    }

    private final class WeatherProgressIndicator extends ProgressBar {
        WeatherProgressIndicator(Context context) {
            super(context, null, android.R.attr.progressBarStyleHorizontal);
        }

        @Override public void setVisibility(int visibility) {
            super.setVisibility(visibility);
            if (weatherPageProgress == null) return;
            for (ProgressBar indicator : weatherPageProgress) {
                if (indicator != null) indicator.setVisibility(visibility);
            }
        }
    }

    void cancelForecastSwipe() {
        if (forecastSwipeLayout != null) forecastSwipeLayout.cancelAndSnap();
    }

    private View forecastPage(int page) {
        if (page == PAGE_SETTINGS) return settingsPageContent;
        return weatherPageWrappers[page];
    }

    private boolean forecastPageEnabled(int page) {
        return page == PAGE_OVERVIEW || page == PAGE_DAILY || page == PAGE_SETTINGS
                || page == PAGE_PRECIPITATION && precipitationPageEnabled
                || page == PAGE_RADAR && radarPageEnabled;
    }

    private int adjacentForecastPage(int direction) {
        for (int page = selectedForecastPage + direction;
                page >= PAGE_OVERVIEW && page <= PAGE_SETTINGS; page += direction) {
            if (forecastPageEnabled(page)) return page;
        }
        return -1;
    }

    private int forecastPagePosition(int page) {
        int index = 0;
        for (int candidate = PAGE_OVERVIEW; candidate < page; candidate++) {
            if (forecastPageEnabled(candidate)) index++;
        }
        return index;
    }

    private int enabledForecastPageCount() {
        return 3 + (precipitationPageEnabled ? 1 : 0) + (radarPageEnabled ? 1 : 0);
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
            int viewportHeight = mainScroll.getMeasuredHeight();
            if (viewportHeight == 0) viewportHeight = getResources().getDisplayMetrics().heightPixels;
            for (int page = PAGE_OVERVIEW; page <= PAGE_SETTINGS; page++) {
                forecastPage(page).measure(childWidth, page == PAGE_RADAR
                        ? View.MeasureSpec.makeMeasureSpec(viewportHeight, View.MeasureSpec.EXACTLY)
                        : childHeight);
            }
            int activeHeight = forecastPage(selectedForecastPage).getMeasuredHeight();
            int height = activeHeight;
            if (transitioning) {
                View incoming = forecastPage(transitionTargetPage);
                height = Math.max(height, incoming.getMeasuredHeight()
                        + Math.max(0, Math.round(incoming.getTranslationY())));
            }
            setMeasuredDimension(resolveSize(width, widthMeasureSpec),
                    resolveSize(height, heightMeasureSpec));
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int width = right - left;
            for (int page = PAGE_OVERVIEW; page <= PAGE_SETTINGS; page++) {
                View child = forecastPage(page);
                child.layout(0, 0, width, child.getMeasuredHeight());
            }
        }
    }

    /** Slides each complete page while keeping the location header and dock stationary. */
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
            cancelPageAnimations();
            if (forecastPageHost instanceof ForecastPageHost) {
                ((ForecastPageHost) forecastPageHost).setTransitioning(false);
            }
            resetPagePositions();
        }

        private void cancelPageAnimations() {
            for (int page = PAGE_OVERVIEW; page <= PAGE_SETTINGS; page++) {
                View child = forecastPage(page);
                if (child != null) child.animate().withEndAction(null).cancel();
            }
            if (modeSwitchAnimator != null) {
                modeSwitchAnimator.cancel();
                modeSwitchAnimator = null;
            }
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
                            && adjacentForecastPage(dx < 0f ? 1 : -1) >= 0;
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
                            && adjacentForecastPage(dx < 0f ? 1 : -1) >= 0) {
                        animateModeChange(adjacentForecastPage(dx < 0f ? 1 : -1));
                    } else {
                        settleBack();
                    }
                    break;
            }
            return true;
        }

        void animateModeChange(int targetPage) {
            if (settling || selectedForecastPage == targetPage
                    || !forecastPageEnabled(targetPage)
                    || overviewPageContent == null || precipitationPageContent == null
                    || radarPageContent == null) return;
            settling = true;
            final int generation = ++animationGeneration;
            candidate = false;
            swiping = false;
            float width = Math.max(1, getWidth());
            View outgoing = forecastPage(selectedForecastPage);
            View incoming = forecastPage(targetPage);
            if (incoming.getVisibility() != View.VISIBLE
                    || transitionTargetPage != targetPage) preparePageDrag(targetPage);
            float outgoingTarget = targetPage > selectedForecastPage ? -width : width;
            float incomingStart = -outgoingTarget;
            if (Math.abs(incoming.getTranslationX()) < 1f) incoming.setTranslationX(incomingStart);
            float remaining = Math.abs(outgoingTarget - outgoing.getTranslationX()) / width;
            long duration = animationsAllowed()
                    ? Math.max(110L, Math.round(240L * Math.min(1f, remaining))) : 0L;
            animateModeSwitchProgress(forecastPagePosition(targetPage), duration);
            outgoing.animate().cancel();
            incoming.animate().cancel();
            outgoing.animate()
                    .translationX(outgoingTarget)
                    .alpha(0.88f)
                    .setDuration(duration)
                    .withEndAction(() -> {
                        if (generation == animationGeneration) finishModeChange(targetPage);
                    })
                    .start();
            incoming.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(duration)
                    .start();
        }

        private float directionalTranslation(float dx) {
            if (dx < 0f) return adjacentForecastPage(1) >= 0 ? dx : 0f;
            return adjacentForecastPage(-1) >= 0 ? dx : 0f;
        }

        private void settleBack() {
            if (overviewPageContent == null || transitionTargetPage == selectedForecastPage) {
                resetPagePositions();
                return;
            }
            settling = true;
            final int generation = ++animationGeneration;
            long duration = animationsAllowed() ? 170L : 0L;
            animateModeSwitchProgress(forecastPagePosition(selectedForecastPage), duration);
            float width = Math.max(1, getWidth());
            View current = forecastPage(selectedForecastPage);
            View adjacent = forecastPage(transitionTargetPage);
            float adjacentTarget = transitionTargetPage > selectedForecastPage ? width : -width;
            current.animate().cancel();
            adjacent.animate().cancel();
            current.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(duration)
                    .withEndAction(() -> {
                        if (generation != animationGeneration) return;
                        animationGeneration++;
                        cancelPageAnimations();
                        adjacent.setTranslationX(adjacentTarget);
                        adjacent.setAlpha(1f);
                        adjacent.setTranslationY(0f);
                        adjacent.setVisibility(View.INVISIBLE);
                        ((ForecastPageHost) forecastPageHost).setTransitioning(false);
                        transitionTargetPage = selectedForecastPage;
                        settling = false;
                        setModeSwitchProgress(forecastPagePosition(selectedForecastPage));
                        resetForecastGlassCaptures();
                    })
                    .start();
            adjacent.animate().translationX(adjacentTarget).alpha(0.88f)
                    .setDuration(duration).start();
        }

        private void preparePageDrag(int targetPage) {
            float width = Math.max(1, getWidth());
            if (transitionTargetPage != selectedForecastPage
                    && transitionTargetPage != targetPage) {
                View oldAdjacent = forecastPage(transitionTargetPage);
                oldAdjacent.setVisibility(View.INVISIBLE);
                oldAdjacent.setTranslationX(0f);
                oldAdjacent.setTranslationY(0f);
            }
            transitionTargetPage = targetPage;
            View current = forecastPage(selectedForecastPage);
            View adjacent = forecastPage(targetPage);
            current.setVisibility(View.VISIBLE);
            adjacent.setVisibility(View.VISIBLE);
            adjacent.setTranslationY(mainScroll == null ? 0f : mainScroll.getScrollY());
            ((ForecastPageHost) forecastPageHost).setTransitioning(true);
            current.setTranslationX(0f);
            current.setAlpha(1f);
            adjacent.setTranslationX(targetPage > selectedForecastPage ? width : -width);
            adjacent.setAlpha(0.88f);
            resetForecastGlassCaptures();
        }

        private void applyPageDrag(float dx) {
            int targetPage = adjacentForecastPage(dx < 0f ? 1 : -1);
            if (targetPage < 0 || dx == 0f) return;
            if (transitionTargetPage != targetPage
                    || forecastPage(targetPage).getVisibility() != View.VISIBLE) {
                preparePageDrag(targetPage);
            }
            float width = Math.max(1, getWidth());
            float progress = Math.min(1f, Math.abs(dx) / width);
            View current = forecastPage(selectedForecastPage);
            View adjacent = forecastPage(targetPage);
            current.setTranslationX(dx);
            current.setAlpha(1f - (0.12f * progress));
            adjacent.setTranslationX(dx + (targetPage > selectedForecastPage ? width : -width));
            adjacent.setAlpha(0.88f + (0.12f * progress));
            setModeSwitchProgress(forecastPagePosition(selectedForecastPage)
                    + (targetPage > selectedForecastPage ? progress : -progress));
        }

        private void finishModeChange(int targetPage) {
            animationGeneration++;
            cancelPageAnimations();
            int previousPage = selectedForecastPage;
            switchForecastMode(targetPage);
            View active = forecastPage(targetPage);
            active.setTranslationX(0f);
            active.setTranslationY(0f);
            active.setAlpha(1f);
            active.setVisibility(View.VISIBLE);
            for (int page = PAGE_OVERVIEW; page <= PAGE_SETTINGS; page++) {
                if (page == targetPage) continue;
                View inactive = forecastPage(page);
                inactive.setTranslationX(0f);
                inactive.setTranslationY(0f);
                inactive.setAlpha(1f);
                inactive.setVisibility(View.INVISIBLE);
            }
            ((ForecastPageHost) forecastPageHost).setTransitioning(false);
            transitionTargetPage = targetPage;
            setModeSwitchProgress(forecastPagePosition(targetPage));
            settling = false;
            resetForecastGlassCaptures();
            onForecastPageSettled(previousPage, targetPage);
        }

        void resetPagePositions() {
            if (overviewPageContent == null || precipitationPageContent == null
                    || radarPageContent == null || settling) return;
            for (int page = PAGE_OVERVIEW; page <= PAGE_SETTINGS; page++) {
                View candidate = forecastPage(page);
                candidate.setTranslationX(0f);
                candidate.setTranslationY(0f);
                candidate.setAlpha(1f);
                candidate.setVisibility(page == selectedForecastPage
                        ? View.VISIBLE : View.INVISIBLE);
            }
            ((ForecastPageHost) forecastPageHost).setTransitioning(false);
            transitionTargetPage = selectedForecastPage;
            setModeSwitchProgress(forecastPagePosition(selectedForecastPage));
            resetForecastGlassCaptures();
        }

        private boolean hitsHorizontalControl(View view, float x, float y) {
            if (view instanceof HorizontalScrollView
                    || view instanceof SeekBar
                    || view instanceof MinutePrecipitationGraphView
                    || view instanceof ForecastChartView
                    || view instanceof HourlyDayChartView
                    || view instanceof RadarMapView) return true;
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
        GradientDrawable outline = roundedBg(Color.TRANSPARENT, dp(28));
        modeSwitchHolder.setBackground(outline);
        modeSwitchHolder.setClipToOutline(true);
        modeSwitchHolder.setElevation(dp(5));
        bottomGlass = new HeaderGlassView(this, mainScroll, true);
        bottomGlass.setScene(forecastPreview.currentScene(), false);
        modeSwitchHolder.addView(bottomGlass, new FrameLayout.LayoutParams(-1, -1));

        modeSwitchThumb = new View(this);
        GradientDrawable selected = roundedBg(Color.argb(48, 255, 255, 255), dp(23));
        selected.setStroke(Math.max(1, dp(0.5f)), Color.argb(34, 255, 255, 255));
        modeSwitchThumb.setBackground(selected);
        modeSwitchThumb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(1, dp(62));
        thumbLp.leftMargin = dp(5);
        thumbLp.topMargin = dp(5);
        modeSwitchHolder.addView(modeSwitchThumb, thumbLp);

        modeSwitchLabels = new LinearLayout(this);
        modeSwitchLabels.setGravity(Gravity.CENTER);
        modeSwitchLabels.setPadding(dp(5), dp(5), dp(5), dp(5));
        modeSwitchLabels.setBaselineAligned(false);
        overviewModeButton = addNavigationItem(UiTranslations.text(this, "Overview"),
                "Overview forecast view", NavigationIconView.OVERVIEW,
                () -> forecastSwipeLayout.animateModeChange(PAGE_OVERVIEW));
        dailyModeTab = addNavigationItem(UiTranslations.text(this, "Forecast"),
                "Multi-day forecast", NavigationIconView.FORECAST,
                () -> forecastSwipeLayout.animateModeChange(PAGE_DAILY));
        precipitationModeButton = null;
        if (precipitationPageEnabled) precipitationModeButton = addNavigationItem(
                UiTranslations.text(this, "Rain"), "Minute precipitation view", NavigationIconView.RAIN,
                () -> forecastSwipeLayout.animateModeChange(PAGE_PRECIPITATION));
        radarModeButton = null;
        if (radarPageEnabled) radarModeButton = addNavigationItem(
                UiTranslations.text(this, "Radar"), "Animated precipitation radar view", NavigationIconView.RADAR,
                () -> forecastSwipeLayout.animateModeChange(PAGE_RADAR));
        settingsModeTab = addNavigationItem(getString(R.string.settings_title), "Weather options",
                NavigationIconView.SETTINGS, this::openSettings);
        modeSwitchHolder.addView(modeSwitchLabels, new FrameLayout.LayoutParams(-1, -1));
        modeSwitchHolder.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            setModeSwitchProgress(modeSwitchProgress);
            bottomGlass.requestBlurRefresh();
        });
        int width = Math.min(dp(600), getResources().getDisplayMetrics().widthPixels - dp(32));
        FrameLayout.LayoutParams dock = new FrameLayout.LayoutParams(width, dp(72),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dock.bottomMargin = dp(10);
        skyLayout.addView(modeSwitchHolder, dock);
        skyLayout.requestApplyInsets();
        updateForecastModeButtons();
    }

    private TextView addNavigationItem(String title, String description, int icon, Runnable action) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setFocusable(true);
        item.setContentDescription(UiTranslations.text(this, description));
        item.setOnClickListener(v -> action.run());
        NavigationIconView glyph = new NavigationIconView(this, icon);
        item.addView(glyph, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView label = text(title, 11, true, WHITE);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setAutoSizeTextTypeUniformWithConfiguration(9, 11, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(-1, dp(20));
        textLp.topMargin = dp(2);
        item.addView(label, textLp);
        makeChildrenUnimportant(item);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1f);
        modeSwitchLabels.addView(item, params);
        return label;
    }

    private void selectNavigationItem(TextView label, boolean selected, String description) {
        if (label == null) return;
        LinearLayout item = (LinearLayout) label.getParent();
        item.setSelected(selected);
        item.getChildAt(0).setSelected(selected);
        item.setContentDescription(UiTranslations.text(this, description) + ", "
                + UiTranslations.text(this, selected ? "selected" : "not selected"));
        if (Build.VERSION.SDK_INT >= 30) item.setStateDescription(
                UiTranslations.text(this, selected ? "Selected" : "Not selected"));
    }

    void updateForecastModeButtons() {
        if (headerGlass != null) headerGlass.setVisibility(
                selectedForecastPage == PAGE_RADAR ? View.GONE : View.VISIBLE);
        if (mainScroll != null) mainScroll.setOverScrollMode(selectedForecastPage == PAGE_RADAR
                ? View.OVER_SCROLL_NEVER : View.OVER_SCROLL_ALWAYS);
        selectNavigationItem(overviewModeButton, selectedForecastPage == PAGE_OVERVIEW, "Overview forecast view");
        selectNavigationItem(dailyModeTab, selectedForecastPage == PAGE_DAILY, "Multi-day forecast");
        selectNavigationItem(precipitationModeButton, selectedForecastPage == PAGE_PRECIPITATION, "Minute precipitation view");
        selectNavigationItem(radarModeButton, selectedForecastPage == PAGE_RADAR, "Animated precipitation radar view");
        selectNavigationItem(settingsModeTab, selectedForecastPage == PAGE_SETTINGS, "Weather options");
        setModeSwitchProgress(forecastPagePosition(selectedForecastPage));
    }

    private void resetForecastGlassCaptures() {
        if (mainScroll != null) mainScroll.invalidate();
        if (headerGlass != null) headerGlass.resetBackdropCapture();
        if (bottomGlass != null) bottomGlass.resetBackdropCapture();
    }

    private void refreshForecastGlassCaptures() {
        if (headerGlass != null) headerGlass.requestBlurRefresh();
        if (bottomGlass != null) bottomGlass.requestBlurRefresh();
    }

    private void updateOverviewBackgroundBlur(int scrollY) {
        if (skyLayout == null) return;
        float depth = 0f;
        if (selectedForecastPage == PAGE_OVERVIEW
                || transitionTargetPage == PAGE_OVERVIEW) {
            float scrollDepth = Math.min(1f, Math.max(0, scrollY) / (float) dp(560));
            float overviewPresence = Math.max(0f,
                    1f - Math.abs(modeSwitchProgress - forecastPagePosition(PAGE_OVERVIEW)));
            depth = 0.72f * scrollDepth * overviewPresence;
        }
        skyLayout.setBackgroundBlurDepth(depth);
    }

    void setModeSwitchProgress(float value) {
        modeSwitchProgress = Math.max(0f,
                Math.min(enabledForecastPageCount() - 1f, value));
        updateOverviewBackgroundBlur(mainScroll == null ? 0 : mainScroll.getScrollY());
        // Both strips follow page properties throughout a swipe or animation,
        // including frames that do not change the ScrollView's layout.
        refreshForecastGlassCaptures();
        if (modeSwitchHolder == null || modeSwitchThumb == null) return;
        int gap = 0;
        int count = enabledForecastPageCount();
        int innerWidth = modeSwitchHolder.getWidth() - dp(10);
        if (innerWidth <= 0) return;
        int thumbWidth = (innerWidth - gap * (count - 1)) / count;
        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) modeSwitchThumb.getLayoutParams();
        if (params.width != thumbWidth) {
            params.width = thumbWidth;
            modeSwitchThumb.setLayoutParams(params);
        }
        modeSwitchThumb.setTranslationX((thumbWidth + gap) * modeSwitchProgress);
        overviewModeButton.setAlpha(1f - 0.25f * Math.min(1f,
                Math.abs(modeSwitchProgress - forecastPagePosition(PAGE_OVERVIEW))));
        if (dailyModeTab != null) dailyModeTab.setAlpha(1f - 0.25f * Math.min(1f,
                Math.abs(modeSwitchProgress - forecastPagePosition(PAGE_DAILY))));
        if (settingsModeTab != null) settingsModeTab.setAlpha(1f - 0.25f * Math.min(1f,
                Math.abs(modeSwitchProgress - forecastPagePosition(PAGE_SETTINGS))));
        if (precipitationModeButton != null) precipitationModeButton.setAlpha(
                1f - 0.25f * Math.min(1f,
                        Math.abs(modeSwitchProgress - forecastPagePosition(PAGE_PRECIPITATION))));
        if (radarModeButton != null) radarModeButton.setAlpha(
                1f - 0.25f * Math.min(1f,
                        Math.abs(modeSwitchProgress - forecastPagePosition(PAGE_RADAR))));
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

    LinearLayout activeForecastPageContent() {
        if (selectedForecastPage == PAGE_SETTINGS) return settingsPageContent;
        if (selectedForecastPage == PAGE_DAILY) return dailyPageContent;
        if (selectedForecastPage == PAGE_PRECIPITATION) return precipitationPageContent;
        if (selectedForecastPage == PAGE_RADAR) return radarPageContent;
        return overviewPageContent;
    }

    void switchForecastMode(int targetPage) {
        if (selectedForecastPage == targetPage) return;
        if (targetPage != PAGE_RADAR && radarRefreshIndicatorActive) {
            radarRefreshIndicatorActive = false;
            finishRefreshIndicator();
        }
        if (targetPage != PAGE_PRECIPITATION && precipitationRefreshIndicatorActive) {
            precipitationRefreshIndicatorActive = false;
            finishRefreshIndicator();
        }
        forecastPreview.restore(false);
        selectedForecastPage = targetPage;
        precipitationMode = targetPage == PAGE_PRECIPITATION;
        activePageContent = activeForecastPageContent();
        moveForecastErrorToPage();
        if (radarPageView != null) radarPageView.setActive(targetPage == PAGE_RADAR);
        if (targetPage == PAGE_SETTINGS) settingsScreen.onShown();
        notifyActiveForecastStatusChanged();
        updateForecastModeButtons();
        if (mainScroll != null) {
            mainScroll.scrollTo(0, 0);
            mainScroll.post(() -> {
                mainScroll.scrollTo(0, 0);
                if (selectedForecastPage == PAGE_PRECIPITATION) ensureMinuteForecast(false);
            });
        } else if (targetPage == PAGE_PRECIPITATION) {
            ensureMinuteForecast(false);
        }
    }

    private void moveForecastErrorToPage() {
        if (globalErrorView == null || selectedForecastPage > PAGE_PRECIPITATION) return;
        LinearLayout page = activeForecastPageContent();
        if (globalErrorView.getParent() == page) return;
        ViewGroup parent = (ViewGroup) globalErrorView.getParent();
        if (parent != null) parent.removeView(globalErrorView);
        page.addView(globalErrorView);
    }

    void applyForecastPagePreferences() {
        precipitationPageEnabled = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.PREF_PRECIPITATION_PAGE, true);
        radarPageEnabled = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getBoolean(SettingsActivity.PREF_RADAR_PAGE, true);
        cancelForecastSwipe();
        if (!forecastPageEnabled(selectedForecastPage)) {
            switchForecastMode(PAGE_OVERVIEW);
            forecastSwipeLayout.resetPagePositions();
        }
        if (modeSwitchHolder != null) {
            skyLayout.removeView(modeSwitchHolder);
            if (bottomGlass != null) bottomGlass.release();
            addForecastModeSwitch();
        }
        if (forecastPageHost != null) forecastPageHost.requestLayout();
    }

    @Override
    void performPullRefresh() {
        if (selectedForecastPage == PAGE_SETTINGS) { finishRefreshIndicator(); return; }
        if (selectedForecastPage == PAGE_RADAR && radarPageView != null) {
            radarPageView.refresh();
            radarRefreshIndicatorActive = true;
            notifyActiveForecastStatusChanged();
            return;
        }
        if (selectedForecastPage == PAGE_PRECIPITATION) {
            forecastPreview.restore(false);
            if (refreshIndicator != null) refreshIndicator.setRefreshing(true);
            precipitationRefreshIndicatorActive = true;
            ensureMinuteForecast(true);
            notifyActiveForecastStatusChanged();
            return;
        }
        super.performPullRefresh();
    }

    @Override
    void notifyActiveForecastStatusChanged() {
        if (status == null) return;
        if (weatherPageStatus[PAGE_PRECIPITATION] != null) {
            weatherPageStatus[PAGE_PRECIPITATION].setText(UiTranslations.text(this, minuteForecastStatusLabel()));
        }
        if (selectedForecastPage == PAGE_RADAR) {
            if (radarRefreshIndicatorActive
                    && (radarPageView == null || !radarPageView.isLoading())) {
                radarRefreshIndicatorActive = false;
                finishRefreshIndicator();
            }
            return;
        }
        if (selectedForecastPage == PAGE_PRECIPITATION) {
            if (precipitationRefreshIndicatorActive) {
                MinuteForecastState state = minuteForecastStateForCurrentScope();
                if (state == null || !state.loading) {
                    precipitationRefreshIndicatorActive = false;
                    finishRefreshIndicator();
                }
            }
            return;
        }
        if (locationRefreshCoordinator != null && locationRefreshCoordinator.isCheckingLocation()) {
            status.setText(UiTranslations.text(this, "Finding your location…"));
        } else if (lastCurrentWeather != null && weatherLoadActive) {
            status.setText(UiTranslations.text(this, "Updating forecast…"));
        } else if (lastCurrentWeather != null && forecastRefreshFailed) {
            status.setText(UiTranslations.text(this, "Could not update forecast") + " · "
                    + dataAgeLabel(lastCurrentWeather));
        } else if (lastCurrentWeather != null) {
            status.setText(dataAgeLabel(lastCurrentWeather));
        } else if (weatherLoadActive) {
            status.setText(UiTranslations.text(this, OpenMeteoConfig.isOpenMeteo(this)
                    ? "Loading Open-Meteo forecast…" : "Loading Google Weather data…"));
        } else {
            status.setText(UiTranslations.text(this, "Weather data not loaded"));
        }
    }

    @Override
    void finishWeatherLoadSuccess(
            JSONObject current,
            JSONObject hourly,
            JSONObject daily,
            HourlyPageState pageState,
            int generation,
            long fetchedAtMillis) {
        super.finishWeatherLoadSuccess(
                current, hourly, daily, pageState, generation, fetchedAtMillis);
        notifyActiveForecastStatusChanged();
        if (generation != weatherRequestGeneration || weatherLoadActive) return;
        if (!current.optBoolean(SavedForecast.FALLBACK, false)
                && RainAlertManager.isEnabled(this)) ensureMinuteForecast(false);
    }

    @Override
    void finishWeatherLoadFailure(Exception error, int generation) {
        super.finishWeatherLoadFailure(error, generation);
        moveForecastErrorToPage();
        if (selectedForecastPage != PAGE_OVERVIEW) {
            notifyActiveForecastStatusChanged();
        }
    }

    private void startStatusAgeRefresh() {
        statusAgeRefreshRunning = true;
        statusAgeHandler.removeCallbacks(statusAgeRefresh);
        if (statusShowsDataAge()) notifyActiveForecastStatusChanged();
        statusAgeHandler.postDelayed(statusAgeRefresh, STATUS_AGE_REFRESH_INTERVAL_MILLIS);
    }

    private boolean statusShowsDataAge() {
        return (selectedForecastPage == PAGE_OVERVIEW || selectedForecastPage == PAGE_DAILY)
                && lastCurrentWeather != null
                && (!lastCurrentWeather.has("_openMeteo")
                        || lastCurrentWeather.optBoolean(SavedForecast.FALLBACK, false))
                && parseInstant(lastCurrentWeather.optString("currentTime", null)) != null;
    }

    private void stopStatusAgeRefresh() {
        statusAgeRefreshRunning = false;
        statusAgeHandler.removeCallbacks(statusAgeRefresh);
    }

    void continueStartupAfterApiKey() {
        progress.setVisibility(View.VISIBLE);
        restoreSavedForecastBeforeLocationCheck();
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

        Button useOpenMeteo = coordinateDialogButton("Use Open-Meteo without a key", false);
        LinearLayout.LayoutParams openMeteoLp = new LinearLayout.LayoutParams(-1, dp(48));
        openMeteoLp.topMargin = dp(8);
        panel.addView(useOpenMeteo, openMeteoLp);
        useOpenMeteo.setOnClickListener(v -> {
            OpenMeteoConfig.setProvider(this, OpenMeteoConfig.OPEN_METEO);
            field.getText().clear();
            dialog.dismiss();
            continueStartupAfterApiKey();
        });

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
                status.setText(UiTranslations.text(this, "Preparing Zwerk Weather…"));
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
                UiTranslations.text(this,
                        "1. Create or select a Google Cloud project and enable billing.")
                        + "\n" + UiTranslations.text(this,
                                "2. Enable Weather API. Enable Air Quality API and Pollen API too if you want those optional tiles.")
                        + "\n" + UiTranslations.text(this,
                                "3. Create a key. Add an Android app restriction with the package and SHA-1 below. Under API restrictions, allow every API you enabled, then paste the key below."),
                12,
                false,
                SOFT_WHITE);
        steps.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams stepsLp = new LinearLayout.LayoutParams(-1, -2);
        stepsLp.topMargin = dp(5);
        panel.addView(steps, stepsLp);

        TextView identity = text(androidRestrictionIdentity(), 11, false, FAINT_WHITE);
        identity.setTextIsSelectable(true);
        identity.setContentDescription(UiTranslations.text(this,
                "Android API key restriction identity.") + " "
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
            Toast.makeText(this, UiTranslations.text(this,
                    "No browser is available to open this link."), Toast.LENGTH_SHORT).show();
        }
    }

    String androidRestrictionIdentity() {
        String fingerprint = signingCertificateSha1();
        return UiTranslations.text(this, "Android restriction") + "\n"
                + UiTranslations.text(this, "Package") + ": " + getPackageName()
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
        field.setHint(UiTranslations.text(this, "API key"));
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
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        field.setImeOptions(imeOptions);
        field.setBackground(coordinateFieldBackground());
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
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
        error.setText(UiTranslations.text(this,
                "Could not save the key. Check the value and try again."));
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
        if (settingsScreen != null) settingsScreen.onRequestPermissionsResult(requestCode, permissions, results);
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
        if (status != null) status.setText(UiTranslations.text(this,
                "Finding your location…"));
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
        forecastSwipeLayout.animateModeChange(PAGE_SETTINGS);
    }

    boolean allowsPullRefresh() { return selectedForecastPage <= PAGE_PRECIPITATION; }

    private void onForecastPageSettled(int previous, int target) {
        if (previous != PAGE_SETTINGS || target == PAGE_SETTINGS || settingsScreen == null) return;
        settingsScreen.onPause();
        Intent result = pendingSettingsAction;
        pendingSettingsAction = null;
        if (result == null) result = settingsScreen.takeChanges();
        else settingsScreen.acceptChanges();
        onActivityResult(SETTINGS_REQUEST, RESULT_OK, result);
        // Inline preferences do not cause an Activity resume; consume that suppression here.
        suppressNextResumeWeatherLoad = false;
        boolean enabled = animationsAllowed();
        if (skyLayout != null) skyLayout.setAnimationRunning(enabled);
        for (SunTrackView track : sunTrackViews) if (track != null) track.setAnimationRunning(enabled);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        if (settingsScreen != null) settingsScreen.onActivityResult(request, result, data);
        super.onActivityResult(request, result, data);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("selected_page", selectedForecastPage);
        if (settingsScreen != null) {
            Bundle settingsState = new Bundle();
            settingsScreen.onSaveInstanceState(settingsState);
            state.putBundle("settings_screen", settingsState);
        }
        super.onSaveInstanceState(state);
    }

    // API 33+ uses MainBackNavigation; this override handles API 28-32.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { handleMainBack(); }

    private void handleMainBack() {
        if (selectedForecastPage != PAGE_OVERVIEW) forecastSwipeLayout.animateModeChange(PAGE_OVERVIEW);
        else finish();
    }

    @android.annotation.TargetApi(33)
    private static final class MainBackNavigation {
        static void register(Activity activity, Runnable action) {
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, action::run);
        }
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
        title.setContentDescription(UiTranslations.text(this, "Forecast location"));
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
        useLocation.setContentDescription(UiTranslations.text(this,
                "Use my location for the forecast"));
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
        cancel.setContentDescription(UiTranslations.text(this, "Cancel coordinate entry"));
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        actions.addView(cancel, cancelLp);

        Button load = coordinateDialogButton("Load", true);
        load.setContentDescription(UiTranslations.text(this,
                "Load forecast for these coordinates"));
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
                Toast.makeText(this, UiTranslations.text(this, "Invalid coordinates"),
                        Toast.LENGTH_SHORT).show();
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
        button.setText(UiTranslations.text(this, label));
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
        locationTitle.setText(lastCurrentWeather == null ? locationName : forecastLocationName());
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
        if (radarPageView != null) radarPageView.setLocation(lat, lon);
    }

    @Override
    void onForecastLocationChanged(double lat, double lon, String name) {
        if (radarPageView != null) radarPageView.setLocation(lat, lon);
    }

    @Override
    void applyScenePalette(String scene) {
        super.applyScenePalette(scene);
        if (radarPageView != null) radarPageView.applyTileTransparency();
    }

    @Override
    void applyTileTransparency() {
        super.applyTileTransparency();
        if (radarPageView != null) radarPageView.applyTileTransparency();
    }

    void resolvePlaceName(double lat, double lon) {
        if (!usingDeviceLocation) return;
        final int expectedSelectionGeneration = locationSelectionGeneration;
        final String expectedLocationId = selectedLocationId == null ? "" : selectedLocationId;
        final boolean expectedDeviceLocation = usingDeviceLocation;
        // Reverse geocoding can block independently of the Weather API. Keep it off the
        // serialized forecast executor so a slow platform Geocoder cannot hold weather loading.
        optionalExecutor.execute(() -> {
            try {
                List<Address> results = new Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1);
                if (results == null || results.isEmpty()) return;
                Address a = results.get(0);
                String name = LocationNaming.cityName(a, "");
                if (name.isEmpty()) return;

                final String resolved = name;
                runOnUiThread(() -> {
                    if (isFinishing()
                            || isDestroyed()
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
                    if (displayedForecastMatchesSelection()
                            && widgetPrefs.getBoolean(WeatherWidgetProvider.KEY_HAS_SNAPSHOT, false)) {
                        widgetPrefs.edit()
                                .putString(WeatherWidgetProvider.KEY_CITY, resolved)
                                .apply();
                        WeatherWidgetProvider.requestRefresh(this);
                    }
                    if (displayedForecastMatchesSelection()) displayedForecastLocationName = resolved;
                    locationTitle.setText(lastCurrentWeather == null ? resolved : forecastLocationName());
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

    @Override
    void render(JSONObject current, JSONObject hourly, JSONObject daily, String responseUnit) {
        super.render(current, hourly, daily, responseUnit);
        scheduleSupportPrompt();
    }

    private void recordSupportPromptLaunch() {
        android.content.SharedPreferences prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(SettingsActivity.PREF_SUPPORT_PROMPT_HANDLED, false)) return;
        long firstOpen = prefs.getLong(PREF_SUPPORT_FIRST_OPEN, 0L);
        android.content.SharedPreferences.Editor editor = prefs.edit();
        if (firstOpen <= 0L) editor.putLong(PREF_SUPPORT_FIRST_OPEN, System.currentTimeMillis());
        editor.putInt(PREF_SUPPORT_LAUNCH_COUNT,
                Math.min(3, prefs.getInt(PREF_SUPPORT_LAUNCH_COUNT, 0) + 1)).apply();
    }

    private void scheduleSupportPrompt() {
        supportPromptHandler.removeCallbacks(supportPromptTask);
        android.content.SharedPreferences prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        long firstOpen = prefs.getLong(PREF_SUPPORT_FIRST_OPEN, 0L);
        if (prefs.getBoolean(SettingsActivity.PREF_SUPPORT_PROMPT_HANDLED, false)
                || firstOpen <= 0L
                || System.currentTimeMillis() - firstOpen < SUPPORT_PROMPT_DELAY_MILLIS
                || prefs.getInt(PREF_SUPPORT_LAUNCH_COUNT, 0) < 3) return;
        supportPromptHandler.postDelayed(supportPromptTask, 3500L);
    }

    private void showSupportPromptIfEligible() {
        if (isFinishing() || isDestroyed() || !hasWindowFocus()
                || lastCurrentWeather == null || lastDailyWeather == null
                || apiKeySetupDialog != null
                || (supportPromptDialog != null && supportPromptDialog.isShowing())) return;
        android.content.SharedPreferences prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(SettingsActivity.PREF_SUPPORT_PROMPT_HANDLED, false)) return;

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(22), dp(22), dp(18));
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(22, 48, 77), Color.rgb(11, 25, 43)});
        background.setCornerRadius(dp(24));
        background.setStroke(dp(1), Color.argb(65, 255, 255, 255));
        panel.setBackground(background);

        TextView star = text("★", 25, true, ACCENT_YELLOW);
        star.setGravity(Gravity.CENTER);
        star.setBackground(roundedBg(Color.argb(32, 255, 194, 24), dp(14)));
        star.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        panel.addView(star, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = text("Enjoying Zwerk Weather?", 22, true, WHITE);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(15);
        panel.addView(title, titleLp);

        TextView description = text(
                "Zwerk Weather is ad-free and open source. A GitHub star helps more people discover the project.",
                14, false, SOFT_WHITE);
        description.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams descriptionLp = new LinearLayout.LayoutParams(-1, -2);
        descriptionLp.topMargin = dp(8);
        panel.addView(description, descriptionLp);

        Button starButton = coordinateDialogButton("Star on GitHub ↗", true);
        LinearLayout.LayoutParams starLp = new LinearLayout.LayoutParams(-1, dp(48));
        starLp.topMargin = dp(22);
        panel.addView(starButton, starLp);
        starButton.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(SettingsActivity.PROJECT_GITHUB_URL)));
            } catch (Exception ignored) {
                Toast.makeText(this, UiTranslations.text(this,
                        "No browser is available to open this link."), Toast.LENGTH_SHORT).show();
            }
        });

        Button close = coordinateDialogButton("Close", false);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(-1, dp(48));
        closeLp.topMargin = dp(8);
        panel.addView(close, closeLp);
        close.setOnClickListener(v -> dialog.dismiss());

        dialog.setOnDismissListener(ignored -> {
            if (supportPromptDialog == dialog) supportPromptDialog = null;
        });
        dialog.setContentView(panel);
        dialog.show();
        supportPromptDialog = dialog;
        prefs.edit().putBoolean(SettingsActivity.PREF_SUPPORT_PROMPT_HANDLED, true).apply();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.62f;
            window.setAttributes(attributes);
            window.setGravity(Gravity.CENTER);
            window.getDecorView().setPadding(0, 0, 0, 0);
            int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(420));
            window.setLayout(Math.max(1, width), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    protected void onResume() {
        super.onResume();
        if (settingsScreen != null && selectedForecastPage == PAGE_SETTINGS) settingsScreen.onResume();
        if (AppLocaleManager.isContextStale(this)) {
            recreate();
            return;
        }
        if (radarPageView != null && selectedForecastPage == PAGE_RADAR) {
            radarPageView.setActive(true);
        }
        startStatusAgeRefresh();
        boolean enabled = animationsAllowed();
        if (skyLayout != null) {
            skyLayout.setAnimationRunning(enabled);
        }
        for (SunTrackView track : sunTrackViews) {
            if (track != null) track.setAnimationRunning(enabled);
        }
        scheduleSupportPrompt();

        if (!hasResumedOnce) {
            hasResumedOnce = true;
        } else if (suppressNextResumeWeatherLoad) {
            suppressNextResumeWeatherLoad = false;
        } else if (lastCurrentWeather != null
                && (OpenMeteoConfig.isOpenMeteo(this) || hasConfiguredApiKey())
                && apiKeySetupDialog == null
                && !weatherLoadActive) {
            refreshWeather();
        }
    }

    @Override
    protected void onPause() {
        if (settingsScreen != null) settingsScreen.onPause();
        supportPromptHandler.removeCallbacks(supportPromptTask);
        stopStatusAgeRefresh();
        forecastPreview.restore(false);
        if (radarPageView != null) radarPageView.setActive(false);
        if (skyLayout != null) skyLayout.setAnimationRunning(false);
        for (SunTrackView track : sunTrackViews) {
            if (track != null) track.setAnimationRunning(false);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        supportPromptHandler.removeCallbacks(supportPromptTask);
        if (supportPromptDialog != null) supportPromptDialog.dismiss();
        stopStatusAgeRefresh();
        if (radarPageView != null) radarPageView.dispose();
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
        hourlyCoverageQueue.reset();
        hourlyCoverageCoordinators.clear();
        forecastPreview.dispose();
        if (skyLayout != null) skyLayout.release();
        if (headerGlass != null) headerGlass.release();
        if (bottomGlass != null) bottomGlass.release();
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
