package com.zwerk.weather;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Clean-room city manager for WeatherNext.
 *
 * This activity uses only Android platform APIs and programmatic drawing. It has no dependency on
 * vendor weather packages, AndroidX, Material Components, or third-party libraries.
 */
public class CityManagerActivity extends Activity {
    public static final String PREFS_NAME = "WEATHER_LOCATIONS";
    public static final String KEY_LOCATIONS_JSON = "locations";
    public static final String KEY_SELECTED_ID = "selected_id";

    public static final String EXTRA_LOCATION_ID = "com.zwerk.weather.extra.LOCATION_ID";
    public static final String EXTRA_LOCATION_NAME = "com.zwerk.weather.extra.LOCATION_NAME";
    public static final String EXTRA_LATITUDE = "com.zwerk.weather.extra.LATITUDE";
    public static final String EXTRA_LONGITUDE = "com.zwerk.weather.extra.LONGITUDE";
    public static final String EXTRA_IS_DEVICE = "com.zwerk.weather.extra.IS_DEVICE";

    public static final String DEVICE_LOCATION_ID = "device";

    private static final String KEY_LEGACY_MIGRATED = "legacy_main_migrated_v1";
    private static final String LEGACY_MAIN_PREFS = "MainActivity";
    private static final String LEGACY_LAT = "lat";
    private static final String LEGACY_LON = "lon";
    private static final String LEGACY_NAME = "name";

    private static final int MAX_GEOCODER_RESULTS = 8;
    private static final double COORD_EPSILON = 0.0015;
    private static final double NAME_COORD_EPSILON = 0.02;
    private static final Object STORE_LOCK = new Object();

    private static final int COLOR_BACKGROUND = Color.BLACK;
    private static final int COLOR_PRIMARY_TEXT = Color.rgb(244, 246, 250);
    private static final int COLOR_SECONDARY_TEXT = Color.rgb(218, 226, 237);
    private static final int COLOR_SEARCH = Color.rgb(20, 25, 33);
    private static final int COLOR_SEARCH_HINT = Color.rgb(116, 116, 116);
    private static final int COLOR_DELETE = Color.rgb(255, 111, 105);

    private final ExecutorService geocoderExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CityManager-Geocoder");
        t.setDaemon(true);
        return t;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private LinearLayout page;
    private LinearLayout cityList;
    private ScrollView listScroll;
    private EditText searchInput;
    private ProgressBar searchProgress;
    private TextView searchStatus;
    private TextView clearSearch;
    private TextView coordinateShortcut;
    private GlyphButton editButton;
    private FloatingAddButton addButton;

    private boolean editMode;
    private volatile boolean destroyed;
    private int searchGeneration;
    private int lastIssuedGeneration = -1;
    private String lastIssuedQuery = "";
    private Runnable pendingSearch;
    private boolean searchMode;
    private AlertDialog activeDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        migrateLegacyMainActivityPreferences(this);
        buildUi();
        renderLocations();
        if (Build.VERSION.SDK_INT >= 33) {
            Api33BackNavigation.register(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cityList != null) {
            renderLocations();
        }
    }

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        if (searchMode || (searchInput != null && searchInput.length() > 0)) {
            clearSearchAndShowSaved(true);
            return;
        }
        if (editMode) {
            setEditMode(false);
            return;
        }
        finishAfterTransition();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        searchGeneration++;
        geocoderExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        if (activeDialog != null) {
            activeDialog.dismiss();
            activeDialog = null;
        }
        super.onDestroy();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 28) {
            window.setNavigationBarDividerColor(Color.TRANSPARENT);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }

        View decor = window.getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            // Some platform builds are safer when the controller is obtained from the decor view
            // after decor creation rather than directly from Window.
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                        0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BACKGROUND);
        root.setFitsSystemWindows(false);

        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        buildHeader();
        buildSearch();
        buildList();
        buildFloatingButton();

        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                top = bars.top;
                bottom = Math.max(bars.bottom, ime.bottom);
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            page.setPadding(0, top, 0, 0);
            cityList.setPadding(0, dp(2), 0, bottom + dp(108));

            FrameLayout.LayoutParams fabLp = (FrameLayout.LayoutParams) addButton.getLayoutParams();
            fabLp.bottomMargin = bottom + dp(24);
            addButton.setLayoutParams(fabLp);
            return insets;
        });
        root.requestApplyInsets();
    }

    private void buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(1), dp(9), 0);
        page.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        GlyphButton back = new GlyphButton(this, GlyphButton.BACK);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> handleBack());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = makeText("Manage cities", 24f, COLOR_PRIMARY_TEXT, false);
        title.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        titleLp.leftMargin = dp(4);
        header.addView(title, titleLp);

        editButton = new GlyphButton(this, GlyphButton.EDIT);
        editButton.setContentDescription("Edit cities");
        editButton.setOnClickListener(v -> setEditMode(!editMode));
        header.addView(editButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
    }

    private void buildSearch() {
        FrameLayout searchBox = new FrameLayout(this);
        GradientDrawable searchBackground = new GradientDrawable();
        searchBackground.setColor(COLOR_SEARCH);
        searchBackground.setCornerRadius(dp(23));
        searchBox.setBackground(searchBackground);
        searchBox.setContentDescription("City search");

        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        searchLp.leftMargin = dp(18);
        searchLp.rightMargin = dp(18);
        searchLp.topMargin = dp(3);
        searchLp.bottomMargin = dp(6);
        page.addView(searchBox, searchLp);

        GlyphButton searchIcon = new GlyphButton(this, GlyphButton.SEARCH);
        searchIcon.setContentDescription("Focus city search");
        searchIcon.setOnClickListener(v -> focusSearch());
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(
                dp(40), dp(40), Gravity.START | Gravity.CENTER_VERTICAL);
        iconLp.leftMargin = dp(8);
        searchBox.addView(searchIcon, iconLp);

        searchInput = new EditText(this);
        searchInput.setBackground(null);
        searchInput.setTextColor(COLOR_PRIMARY_TEXT);
        searchInput.setHintTextColor(COLOR_SEARCH_HINT);
        searchInput.setHint("Search cities");
        searchInput.setTextSize(18f);
        searchInput.setSingleLine(true);
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        searchInput.setPadding(0, 0, 0, 0);
        searchInput.setContentDescription("Search cities");
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null
                    && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                submitCitySearch();
                return true;
            }
            return false;
        });
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable editable) {
                onSearchTextChanged(cleanString(editable == null ? "" : editable.toString()));
            }
        });
        FrameLayout.LayoutParams inputLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        inputLp.leftMargin = dp(52);
        inputLp.rightMargin = dp(92);
        searchBox.addView(searchInput, inputLp);

        clearSearch = makeText("×", 28f, COLOR_SECONDARY_TEXT, false);
        clearSearch.setGravity(Gravity.CENTER);
        clearSearch.setClickable(true);
        clearSearch.setFocusable(true);
        clearSearch.setVisibility(View.GONE);
        clearSearch.setContentDescription("Clear search and show saved cities");
        clearSearch.setOnClickListener(v -> clearSearchAndShowSaved(false));
        FrameLayout.LayoutParams clearLp = new FrameLayout.LayoutParams(
                dp(42), dp(42), Gravity.END | Gravity.CENTER_VERTICAL);
        clearLp.rightMargin = dp(4);
        searchBox.addView(clearSearch, clearLp);

        searchProgress = new ProgressBar(this);
        searchProgress.setIndeterminate(true);
        searchProgress.setVisibility(View.GONE);
        searchProgress.setIndeterminateTintList(ColorStateList.valueOf(COLOR_SECONDARY_TEXT));
        searchProgress.setContentDescription("Searching");
        FrameLayout.LayoutParams progressLp = new FrameLayout.LayoutParams(
                dp(24), dp(24), Gravity.END | Gravity.CENTER_VERTICAL);
        progressLp.rightMargin = dp(50);
        searchBox.addView(searchProgress, progressLp);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.leftMargin = dp(24);
        metaLp.rightMargin = dp(20);
        metaLp.bottomMargin = dp(10);
        page.addView(meta, metaLp);

        searchStatus = makeText("", 13f, Color.rgb(175, 183, 194), false);
        searchStatus.setVisibility(View.GONE);
        searchStatus.setSingleLine(false);
        meta.addView(searchStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        coordinateShortcut = makeText("Coordinates", 13f, Color.rgb(158, 191, 236), true);
        coordinateShortcut.setGravity(Gravity.CENTER);
        coordinateShortcut.setPadding(dp(10), dp(7), dp(10), dp(7));
        coordinateShortcut.setClickable(true);
        coordinateShortcut.setFocusable(true);
        coordinateShortcut.setContentDescription("Advanced coordinate entry");
        coordinateShortcut.setOnClickListener(v -> showCoordinateEntryDialog());
        meta.addView(coordinateShortcut, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
    }

    private void buildList() {
        listScroll = new ScrollView(this);
        listScroll.setFillViewport(true);
        listScroll.setClipToPadding(false);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        page.addView(listScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        cityList = new LinearLayout(this);
        cityList.setOrientation(LinearLayout.VERTICAL);
        cityList.setClipToPadding(false);
        listScroll.addView(cityList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void buildFloatingButton() {
        addButton = new FloatingAddButton(this);
        addButton.setContentDescription("Add city. Long press for advanced coordinate entry.");
        addButton.setOnClickListener(v -> focusSearch());
        addButton.setOnLongClickListener(v -> {
            showCoordinateEntryDialog();
            return true;
        });
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(
                dp(60), dp(60), Gravity.END | Gravity.BOTTOM);
        fabLp.rightMargin = dp(20);
        fabLp.bottomMargin = dp(24);
        root.addView(addButton, fabLp);
    }

    private void setEditMode(boolean enabled) {
        editMode = enabled;
        editButton.setGlyph(enabled ? GlyphButton.DONE : GlyphButton.EDIT);
        editButton.setContentDescription(enabled ? "Done editing cities" : "Edit cities");
        renderLocations();
    }

    private void renderLocations() {
        if (cityList == null) return;
        if (searchMode && searchInput != null && searchInput.length() > 0) return;

        cityList.removeAllViews();
        editButton.setVisibility(View.VISIBLE);
        addButton.setVisibility(View.VISIBLE);
        StoreState state = readStore(this);
        if (state.locations.isEmpty()) {
            TextView empty = makeText("Search for a city to add it.", 16f, Color.rgb(145, 145, 145), false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(30), dp(52), dp(30), dp(30));
            cityList.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }

        for (LocationSnapshot location : state.locations) {
            boolean selected = location.id.equals(state.selectedId);
            CityCardView card = new CityCardView(this, location, selected);
            card.setEditMode(editMode);
            card.setOnClickListener(v -> {
                if (!editMode) selectAndReturn(location);
            });
            card.setDeleteAction(v -> confirmDelete(location));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(104));
            lp.leftMargin = dp(18);
            lp.rightMargin = dp(18);
            lp.bottomMargin = dp(16);
            cityList.addView(card, lp);
        }
    }

    private void focusSearch() {
        if (editMode) {
            setEditMode(false);
        }
        searchInput.requestFocus();
        searchInput.setSelection(searchInput.length());
        searchInput.post(() -> {
            if (destroyed) {
                return;
            }
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && searchInput != null) {
            imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        }
    }

    private void submitCitySearch() {
        String query = cleanString(searchInput.getText().toString());
        if (query.length() < 2) {
            setSearchState(query.isEmpty() ? "" : "Type at least 2 letters.", false);
            return;
        }
        if (pendingSearch != null) {
            mainHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
        beginCitySearch(query, searchGeneration);
    }

    private void onSearchTextChanged(String query) {
        searchGeneration++;
        if (pendingSearch != null) {
            mainHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
        searchProgress.setVisibility(View.GONE);
        clearSearch.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);

        if (query.isEmpty()) {
            searchMode = false;
            lastIssuedGeneration = -1;
            lastIssuedQuery = "";
            setSearchState("", false);
            editButton.setVisibility(View.VISIBLE);
            addButton.setVisibility(View.VISIBLE);
            renderLocations();
            return;
        }

        searchMode = true;
        if (editMode) setEditMode(false);
        editButton.setVisibility(View.INVISIBLE);
        addButton.setVisibility(View.GONE);
        cityList.removeAllViews();
        if (query.length() < 2) {
            setSearchState("Type at least 2 letters.", false);
            renderSearchPlaceholder("Keep typing to search cities.");
            return;
        }

        setSearchState("", false);
        final int generation = searchGeneration;
        final String scheduledQuery = query;
        pendingSearch = () -> {
            pendingSearch = null;
            beginCitySearch(scheduledQuery, generation);
        };
        mainHandler.postDelayed(pendingSearch, 350L);
    }

    private void beginCitySearch(String query, int generation) {
        if (!isSearchCurrent(generation)) return;
        String current = cleanString(searchInput.getText().toString());
        if (!query.equals(current) || query.length() < 2) return;
        if (lastIssuedGeneration == generation && query.equals(lastIssuedQuery)) return;
        lastIssuedGeneration = generation;
        lastIssuedQuery = query;

        if (!Geocoder.isPresent()) {
            setSearchState("City search isn't available on this device right now.", false);
            renderSearchPlaceholder("You can still add a place with Coordinates.");
            return;
        }

        setSearchState("Searching…", true);
        Geocoder geocoder = new Geocoder(getApplicationContext(), Locale.getDefault());
        if (Build.VERSION.SDK_INT >= 33) {
            WeakReference<CityManagerActivity> activityRef = new WeakReference<>(this);
            try {
                Api33Geocoder.search(geocoder, query, MAX_GEOCODER_RESULTS, new Api33Geocoder.ResultCallback() {
                    @Override
                    public void onSuccess(List<Address> addresses) {
                        CityManagerActivity activity = activityRef.get();
                        if (activity != null && !activity.destroyed) {
                            activity.mainHandler.post(() -> activity.handleSearchResults(generation, query, addresses));
                        }
                    }

                    @Override
                    public void onFailure(String message) {
                        CityManagerActivity activity = activityRef.get();
                        if (activity != null && !activity.destroyed) {
                            activity.mainHandler.post(() -> activity.handleSearchError(generation, message));
                        }
                    }
                });
            } catch (RuntimeException e) {
                handleSearchError(generation, cleanString(e.getMessage()));
            }
        } else {
            geocoderExecutor.execute(() -> {
                try {
                    @SuppressWarnings("deprecation")
                    List<Address> results = geocoder.getFromLocationName(query, MAX_GEOCODER_RESULTS);
                    if (!Thread.currentThread().isInterrupted()) {
                        mainHandler.post(() -> handleSearchResults(generation, query, results));
                    }
                } catch (IOException | IllegalArgumentException e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        mainHandler.post(() -> handleSearchError(generation, cleanString(e.getMessage())));
                    }
                }
            });
        }
    }

    private void clearSearchAndShowSaved(boolean hideIme) {
        searchGeneration++;
        if (pendingSearch != null) {
            mainHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
        lastIssuedGeneration = -1;
        lastIssuedQuery = "";
        searchMode = false;
        if (searchInput != null && searchInput.length() > 0) searchInput.setText("");
        setSearchState("", false);
        clearSearch.setVisibility(View.GONE);
        editButton.setVisibility(View.VISIBLE);
        addButton.setVisibility(View.VISIBLE);
        renderLocations();
        if (hideIme) hideKeyboard();
    }

    private void renderSearchPlaceholder(String message) {
        cityList.removeAllViews();
        TextView text = makeText(message, 15f, Color.rgb(154, 162, 174), false);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(28), dp(42), dp(28), dp(24));
        cityList.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void handleSearchResults(int generation, String query, List<Address> addresses) {
        if (!isSearchCurrent(generation)) return;
        setSearchState("", false);
        List<Address> candidates = sanitizeCandidates(addresses);
        if (candidates.isEmpty()) {
            setSearchState("No results for “" + query + "”.", false);
            renderSearchPlaceholder("Try a nearby city name or use Coordinates.");
            return;
        }
        renderSearchResults(query, candidates);
    }

    private void handleSearchError(int generation, String message) {
        if (!isSearchCurrent(generation)) return;
        setSearchState("Couldn't search right now. Try again.", false);
        renderSearchPlaceholder("Check your connection or use Coordinates.");
    }

    private boolean isSearchCurrent(int generation) {
        return !destroyed && generation == searchGeneration && !isFinishing();
    }

    private void setSearchState(String message, boolean loading) {
        searchProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (message == null || message.isEmpty()) {
            searchStatus.setText("");
            searchStatus.setVisibility(View.GONE);
        } else {
            searchStatus.setText(message);
            searchStatus.setVisibility(View.VISIBLE);
        }
    }

    private void showSearchFailure(String title, String message, boolean offerCoordinates) {
        setSearchState(title + ".", false);
        renderSearchPlaceholder(message);
    }

    private List<Address> sanitizeCandidates(List<Address> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<Address> result = new ArrayList<>();
        for (Address address : addresses) {
            if (address == null || !address.hasLatitude() || !address.hasLongitude()) {
                continue;
            }
            double lat = address.getLatitude();
            double lon = address.getLongitude();
            if (!validCoordinates(lat, lon)) {
                continue;
            }
            boolean duplicate = false;
            for (Address prior : result) {
                String a = normalizeName(bestCityName(address, ""));
                String b = normalizeName(bestCityName(prior, ""));
                if ((Math.abs(lat - prior.getLatitude()) <= COORD_EPSILON
                        && Math.abs(lon - prior.getLongitude()) <= COORD_EPSILON)
                        || (!a.isEmpty() && a.equals(b)
                        && Math.abs(lat - prior.getLatitude()) <= NAME_COORD_EPSILON
                        && Math.abs(lon - prior.getLongitude()) <= NAME_COORD_EPSILON)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                result.add(address);
            }
            if (result.size() >= MAX_GEOCODER_RESULTS) {
                break;
            }
        }
        return result;
    }

    private void renderSearchResults(String query, List<Address> candidates) {
        cityList.removeAllViews();
        TextView heading = makeText("Search results", 13f, Color.rgb(145, 154, 168), true);
        heading.setPadding(dp(22), dp(6), dp(22), dp(10));
        cityList.addView(heading);

        for (Address address : candidates) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(18), dp(13), dp(18), dp(13));
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackground(pressableRoundedBackground(
                    Color.argb(176, 24, 35, 50),
                    Color.argb(218, 34, 52, 72),
                    dp(18)));

            String primary = bestCityName(address, query);
            TextView name = makeText(primary, 20f, COLOR_PRIMARY_TEXT, false);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name);

            String secondary = candidateSecondary(address, primary);
            if (!secondary.isEmpty()) {
                TextView detail = makeText(secondary, 14f, Color.rgb(174, 184, 198), false);
                detail.setPadding(0, dp(4), 0, 0);
                detail.setMaxLines(2);
                detail.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(detail);
            }
            row.setContentDescription(primary + (secondary.isEmpty() ? "" : ", " + secondary));
            row.setOnClickListener(v -> chooseCandidate(query, address));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(18);
            lp.rightMargin = dp(18);
            lp.bottomMargin = dp(10);
            cityList.addView(row, lp);
        }
    }

    private static String candidateSecondary(Address address, String primary) {
        ArrayList<String> parts = new ArrayList<>();
        addDistinctPart(parts, primary, cleanString(address == null ? null : address.getSubAdminArea()));
        addDistinctPart(parts, primary, cleanString(address == null ? null : address.getAdminArea()));
        addDistinctPart(parts, primary, cleanString(address == null ? null : address.getCountryName()));
        return TextUtils.join(" · ", parts);
    }

    private void chooseCandidate(String query, Address address) {
        if (address == null || !address.hasLatitude() || !address.hasLongitude()) {
            showSearchFailure("Invalid result", "The selected result did not include usable coordinates.", true);
            return;
        }
        double lat = address.getLatitude();
        double lon = address.getLongitude();
        if (!validCoordinates(lat, lon)) {
            showSearchFailure("Invalid result", "The selected result had coordinates outside the valid latitude/longitude range.", true);
            return;
        }
        String name = bestCityName(address, query);
        String id = upsertLocation(this, null, name, lat, lon, false, "", "", true);
        LocationSnapshot selected = getLocationById(this, id);
        if (selected == null) {
            selected = new LocationSnapshot(id, name, lat, lon, false, "", "");
        }
        selectAndReturn(selected);
    }

    private void showCoordinateEntryDialog() {
        if (destroyed || isFinishing()) {
            return;
        }
        hideKeyboard();

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(4), dp(22), 0);

        EditText name = new EditText(this);
        name.setHint("Name (optional)");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        name.setContentDescription("Optional location name");

        EditText latitude = new EditText(this);
        latitude.setHint("Latitude, e.g. 50.8503");
        latitude.setSingleLine(true);
        latitude.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        latitude.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        latitude.setContentDescription("Latitude");

        EditText longitude = new EditText(this);
        longitude.setHint("Longitude, e.g. 4.3517");
        longitude.setSingleLine(true);
        longitude.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        longitude.setImeOptions(EditorInfo.IME_ACTION_DONE);
        longitude.setContentDescription("Longitude");

        form.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        form.addView(latitude, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        form.addView(longitude, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Advanced coordinate entry")
                .setMessage("City-name search is recommended. Use coordinates only when needed.")
                .setView(form)
                .setPositiveButton("Add", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                Double lat = parseFiniteDouble(latitude.getText().toString());
                Double lon = parseFiniteDouble(longitude.getText().toString());
                if (lat == null || lon == null || !validCoordinates(lat, lon)) {
                    Toast.makeText(this, "Enter a latitude from -90 to 90 and longitude from -180 to 180.", Toast.LENGTH_LONG).show();
                    return;
                }
                String explicitName = cleanString(name.getText().toString());
                if (explicitName.isEmpty()) {
                    explicitName = coordinateLabel(lat, lon);
                }
                String id = upsertLocation(this, null, explicitName, lat, lon, false, "", "", true);
                LocationSnapshot selected = getLocationById(this, id);
                if (selected == null) {
                    selected = new LocationSnapshot(id, explicitName, lat, lon, false, "", "");
                }
                dialog.dismiss();
                selectAndReturn(selected);
            });
            longitude.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                    return true;
                }
                return false;
            });
        });
        showManagedDialog(dialog);
    }

    private void confirmDelete(LocationSnapshot location) {
        if (location == null) {
            return;
        }
        if (location.isDevice) {
            Toast.makeText(this, "The device location cannot be deleted.", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Delete city?")
                .setMessage("Remove “" + location.name + "” from saved cities?")
                .setPositiveButton("Delete", (d, which) -> {
                    deleteLocation(this, location.id);
                    renderLocations();
                })
                .setNegativeButton("Cancel", null)
                .create();
        showManagedDialog(dialog);
    }

    private void showManagedDialog(AlertDialog dialog) {
        if (destroyed || isFinishing()) {
            return;
        }
        if (activeDialog != null && activeDialog.isShowing()) {
            activeDialog.dismiss();
        }
        activeDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (activeDialog == dialog) {
                activeDialog = null;
            }
        });
        dialog.show();
    }

    private void selectAndReturn(LocationSnapshot location) {
        if (location == null || !validCoordinates(location.lat, location.lon)) {
            return;
        }
        selectLocation(this, location.id);
        Intent data = new Intent()
                .putExtra(EXTRA_LOCATION_ID, location.id)
                .putExtra(EXTRA_LOCATION_NAME, location.name)
                .putExtra(EXTRA_LATITUDE, location.lat)
                .putExtra(EXTRA_LONGITUDE, location.lon)
                .putExtra(EXTRA_IS_DEVICE, location.isDevice);
        setResult(RESULT_OK, data);
        finish();
    }

    private StateListDrawable pressableRoundedBackground(int normal, int pressed, float radius) {
        StateListDrawable states = new StateListDrawable();
        GradientDrawable pressedBg = new GradientDrawable();
        pressedBg.setColor(pressed);
        pressedBg.setCornerRadius(radius);
        GradientDrawable normalBg = new GradientDrawable();
        normalBg.setColor(normal);
        normalBg.setCornerRadius(radius);
        states.addState(new int[]{android.R.attr.state_pressed}, pressedBg);
        states.addState(new int[]{android.R.attr.state_focused}, pressedBg);
        states.addState(new int[]{}, normalBg);
        return states;
    }

    private TextView makeText(String value, float sizeSp, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        text.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        text.setIncludeFontPadding(false);
        return text;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static void addDistinctPart(List<String> parts, String city, String candidate) {
        String clean = cleanString(candidate);
        if (clean.isEmpty() || normalizeName(clean).equals(normalizeName(city))) {
            return;
        }
        for (String prior : parts) {
            if (normalizeName(prior).equals(normalizeName(clean))) {
                return;
            }
        }
        parts.add(clean);
    }

    private static String bestCityName(Address address, String fallback) {
        if (address != null) {
            String[] candidates = new String[]{
                    address.getLocality(),
                    address.getSubAdminArea(),
                    address.getAdminArea(),
                    address.getFeatureName()
            };
            for (String candidate : candidates) {
                String clean = cleanString(candidate);
                if (!clean.isEmpty() && !looksLikeCoordinate(clean)) {
                    return clean;
                }
            }
        }
        String cleanFallback = cleanString(fallback);
        return cleanFallback.isEmpty() ? "Saved location" : cleanFallback;
    }

    private static boolean looksLikeCoordinate(String value) {
        return value.matches("[-+]?\\d+(?:\\.\\d+)?\\s*[,;]\\s*[-+]?\\d+(?:\\.\\d+)?");
    }

    private static Double parseFiniteDouble(String value) {
        try {
            double parsed = Double.parseDouble(cleanString(value).replace(',', '.'));
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean validCoordinates(double lat, double lon) {
        return Double.isFinite(lat)
                && Double.isFinite(lon)
                && lat >= -90.0 && lat <= 90.0
                && lon >= -180.0 && lon <= 180.0;
    }

    private static String coordinateLabel(double lat, double lon) {
        return String.format(Locale.US, "%.4f°, %.4f°", lat, lon);
    }

    private static String displayTemperature(String cachedTemp) {
        String temp = cleanString(cachedTemp);
        if (temp.isEmpty()) {
            return "—";
        }
        if (temp.indexOf('°') >= 0) {
            return temp;
        }
        if (temp.matches("[-+]?\\d+(?:\\.\\d+)?")) {
            return temp + "°";
        }
        return temp;
    }

    private static String cleanString(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "";
        }
        String text = String.valueOf(value).trim();
        return "null".equalsIgnoreCase(text) ? "" : text;
    }

    private static String normalizeName(String value) {
        String clean = cleanString(value).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(clean.length());
        boolean previousSpace = false;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
                previousSpace = false;
            } else if (!previousSpace && out.length() > 0) {
                out.append(' ');
                previousSpace = true;
            }
        }
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == ' ') {
            end--;
        }
        return out.substring(0, end);
    }

    /** Immutable snapshot shared with MainActivity through helper methods. */
    public static final class LocationSnapshot {
        public final String id;
        public final String name;
        public final double lat;
        public final double lon;
        public final boolean isDevice;
        public final String cachedTemp;
        public final String cachedCondition;

        public LocationSnapshot(
                String id,
                String name,
                double lat,
                double lon,
                boolean isDevice,
                String cachedTemp,
                String cachedCondition) {
            if (!validCoordinates(lat, lon)) {
                throw new IllegalArgumentException("Invalid latitude/longitude");
            }
            String cleanId = cleanString(id);
            this.id = cleanId.isEmpty() ? generatedId(name, lat, lon, isDevice) : cleanId;
            String cleanName = cleanString(name);
            this.name = cleanName.isEmpty() ? coordinateLabel(lat, lon) : cleanName;
            this.lat = lat;
            this.lon = lon;
            this.isDevice = isDevice;
            this.cachedTemp = cleanString(cachedTemp);
            this.cachedCondition = cleanString(cachedCondition);
        }

        private JSONObject toJson() throws JSONException {
            JSONObject object = new JSONObject();
            object.put("id", id);
            object.put("name", name);
            object.put("lat", lat);
            object.put("lon", lon);
            object.put("isDevice", isDevice);
            object.put("cachedTemp", cachedTemp);
            object.put("cachedCondition", cachedCondition);
            return object;
        }
    }

    private static final class StoreState {
        final ArrayList<LocationSnapshot> locations;
        String selectedId;

        StoreState(ArrayList<LocationSnapshot> locations, String selectedId) {
            this.locations = locations;
            this.selectedId = cleanString(selectedId);
        }
    }

    /** Returns a defensive, read-only copy of all valid stored locations. */
    public static List<LocationSnapshot> getStoredLocations(Context context) {
        StoreState state = readStore(context);
        return Collections.unmodifiableList(new ArrayList<>(state.locations));
    }

    /** Returns the selected location, or null when no valid location is stored. */
    public static LocationSnapshot getSelectedLocation(Context context) {
        StoreState state = readStore(context);
        return findById(state.locations, state.selectedId);
    }

    /** Returns a location by id, or null when it is absent. */
    public static LocationSnapshot getLocationById(Context context, String id) {
        StoreState state = readStore(context);
        return findById(state.locations, id);
    }

    /**
     * Inserts or updates a location. Equivalent ids/coordinates are deduplicated. When select is
     * true, the surviving location becomes the selected id. The surviving id is returned.
     */
    public static String upsertLocation(
            Context context,
            String id,
            String name,
            double lat,
            double lon,
            boolean isDevice,
            String cachedTemp,
            String cachedCondition,
            boolean select) {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        }
        if (!validCoordinates(lat, lon)) {
            throw new IllegalArgumentException("Invalid latitude/longitude");
        }
        synchronized (STORE_LOCK) {
            StoreState state = readStoreLocked(context.getApplicationContext());
            LocationSnapshot incoming = new LocationSnapshot(
                    isDevice ? DEVICE_LOCATION_ID : id,
                    name,
                    lat,
                    lon,
                    isDevice,
                    cachedTemp,
                    cachedCondition);
            String survivingId = upsertLocked(state, incoming);
            if (select || state.selectedId.isEmpty()) {
                state.selectedId = survivingId;
            }
            normalizeSelectedId(state);
            writeStoreLocked(context.getApplicationContext(), state);
            return survivingId;
        }
    }

    public static String upsertDeviceLocation(
            Context context,
            String name,
            double lat,
            double lon,
            String cachedTemp,
            String cachedCondition,
            boolean select) {
        return upsertLocation(
                context,
                DEVICE_LOCATION_ID,
                name,
                lat,
                lon,
                true,
                cachedTemp,
                cachedCondition,
                select);
    }

    /** Selects an existing location id. Returns false if the id is unknown. */
    public static boolean selectLocation(Context context, String id) {
        if (context == null) {
            return false;
        }
        synchronized (STORE_LOCK) {
            StoreState state = readStoreLocked(context.getApplicationContext());
            LocationSnapshot location = findById(state.locations, id);
            if (location == null) {
                return false;
            }
            state.selectedId = location.id;
            writeStoreLocked(context.getApplicationContext(), state);
            return true;
        }
    }

    /**
     * Updates only cached weather presentation for the currently selected location. No network
     * request is made. Returns false if no location is selected.
     */
    public static boolean updateSelectedLocationSnapshot(
            Context context,
            String cachedTemp,
            String cachedCondition) {
        if (context == null) {
            return false;
        }
        synchronized (STORE_LOCK) {
            StoreState state = readStoreLocked(context.getApplicationContext());
            int index = indexById(state.locations, state.selectedId);
            if (index < 0) {
                return false;
            }
            LocationSnapshot old = state.locations.get(index);
            state.locations.set(index, new LocationSnapshot(
                    old.id,
                    old.name,
                    old.lat,
                    old.lon,
                    old.isDevice,
                    cachedTemp,
                    cachedCondition));
            writeStoreLocked(context.getApplicationContext(), state);
            return true;
        }
    }

    /**
     * Upserts the selected location and its cached weather snapshot in one call. MainActivity can
     * call this after it resolves/loads its active location. The selected/surviving id is returned.
     */
    public static String updateSelectedLocationSnapshot(
            Context context,
            String name,
            double lat,
            double lon,
            boolean isDevice,
            String cachedTemp,
            String cachedCondition) {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        }
        if (!validCoordinates(lat, lon)) {
            throw new IllegalArgumentException("Invalid latitude/longitude");
        }
        synchronized (STORE_LOCK) {
            StoreState state = readStoreLocked(context.getApplicationContext());
            String currentId = cleanString(state.selectedId);
            String requestedId = isDevice ? DEVICE_LOCATION_ID : currentId;
            LocationSnapshot incoming = new LocationSnapshot(
                    requestedId,
                    name,
                    lat,
                    lon,
                    isDevice,
                    cachedTemp,
                    cachedCondition);
            String id = upsertLocked(state, incoming);
            state.selectedId = id;
            normalizeSelectedId(state);
            writeStoreLocked(context.getApplicationContext(), state);
            return id;
        }
    }

    /**
     * Migrates MainActivity's historical private lat/lon/name preference once data exists. The
     * no-argument variant conservatively treats the old entry as a normal saved city because the
     * legacy preference did not record whether it came from the device location provider.
     */
    public static boolean migrateLegacyMainActivityPreferences(Context context) {
        return migrateLegacyMainActivityPreferences(context, false);
    }

    /** Same migration helper with an explicit device-location assumption for callers that know it. */
    public static boolean migrateLegacyMainActivityPreferences(Context context, boolean assumeDeviceLocation) {
        if (context == null) {
            return false;
        }
        Context app = context.getApplicationContext();
        synchronized (STORE_LOCK) {
            SharedPreferences target = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (coerceBoolean(target.getAll().get(KEY_LEGACY_MIGRATED))) {
                return false;
            }

            SharedPreferences legacy = app.getSharedPreferences(LEGACY_MAIN_PREFS, Context.MODE_PRIVATE);
            Map<String, ?> all = legacy.getAll();
            if (!all.containsKey(LEGACY_LAT) || !all.containsKey(LEGACY_LON)) {
                return false;
            }
            Double lat = coerceDouble(all.get(LEGACY_LAT));
            Double lon = coerceDouble(all.get(LEGACY_LON));
            if (lat == null || lon == null || !validCoordinates(lat, lon)) {
                return false;
            }
            String name = cleanString(all.get(LEGACY_NAME));
            if (name.isEmpty()) {
                name = coordinateLabel(lat, lon);
            }

            StoreState state = readStoreLocked(app);
            LocationSnapshot incoming = new LocationSnapshot(
                    assumeDeviceLocation ? DEVICE_LOCATION_ID : null,
                    name,
                    lat,
                    lon,
                    assumeDeviceLocation,
                    "",
                    "");
            String id = upsertLocked(state, incoming);
            if (state.selectedId.isEmpty()) {
                state.selectedId = id;
            }
            normalizeSelectedId(state);
            writeStoreLocked(app, state);
            target.edit().putBoolean(KEY_LEGACY_MIGRATED, true).commit();
            return true;
        }
    }

    private static void deleteLocation(Context context, String id) {
        synchronized (STORE_LOCK) {
            StoreState state = readStoreLocked(context.getApplicationContext());
            int index = indexById(state.locations, id);
            if (index < 0) {
                return;
            }
            LocationSnapshot target = state.locations.get(index);
            if (target.isDevice) {
                return;
            }
            boolean wasSelected = target.id.equals(state.selectedId);
            state.locations.remove(index);
            if (wasSelected) {
                state.selectedId = preferredFallbackId(state.locations);
            }
            normalizeSelectedId(state);
            writeStoreLocked(context.getApplicationContext(), state);
        }
    }

    private static StoreState readStore(Context context) {
        if (context == null) {
            return new StoreState(new ArrayList<>(), "");
        }
        synchronized (STORE_LOCK) {
            return readStoreLocked(context.getApplicationContext());
        }
    }

    private static StoreState readStoreLocked(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Map<String, ?> stored = prefs.getAll();
        String raw = cleanString(stored.get(KEY_LOCATIONS_JSON));
        if (raw.isEmpty()) {
            raw = "[]";
        }
        String selected = cleanString(stored.get(KEY_SELECTED_ID));
        ArrayList<LocationSnapshot> locations = new ArrayList<>();
        HashMap<String, String> idAliases = new HashMap<>();
        boolean dirty = false;

        JSONArray array = null;
        if (!raw.isEmpty()) {
            try {
                if (raw.startsWith("{")) {
                    JSONObject wrapper = new JSONObject(raw);
                    array = wrapper.optJSONArray("locations");
                    if (selected.isEmpty()) {
                        selected = cleanString(wrapper.opt("selectedId"));
                    }
                    if (selected.isEmpty()) {
                        selected = cleanString(wrapper.opt("selected_id"));
                    }
                    dirty = true;
                } else {
                    array = new JSONArray(raw);
                }
            } catch (JSONException e) {
                dirty = true;
            }
        }
        if (array == null) {
            array = new JSONArray();
        }

        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) {
                dirty = true;
                continue;
            }
            LocationSnapshot parsed = parseLocation(object);
            if (parsed == null) {
                dirty = true;
                continue;
            }
            int duplicate = findEquivalentIndex(locations, parsed);
            if (duplicate >= 0) {
                LocationSnapshot merged = mergeLocations(locations.get(duplicate), parsed);
                String oldId = parsed.id;
                locations.set(duplicate, merged);
                idAliases.put(oldId, merged.id);
                dirty = true;
            } else {
                locations.add(parsed);
            }
        }

        if (!selected.isEmpty() && idAliases.containsKey(selected)) {
            selected = idAliases.get(selected);
            dirty = true;
        }
        StoreState state = new StoreState(locations, selected);
        String before = state.selectedId;
        normalizeSelectedId(state);
        if (!before.equals(state.selectedId)) {
            dirty = true;
        }
        if (dirty) {
            writeStoreLocked(context, state);
        }
        return state;
    }

    private static LocationSnapshot parseLocation(JSONObject object) {
        String id = cleanString(object.opt("id"));
        String name = cleanString(object.opt("name"));
        Double lat = coerceDouble(object.opt("lat"));
        Double lon = coerceDouble(object.opt("lon"));
        if (lat == null || lon == null || !validCoordinates(lat, lon)) {
            return null;
        }
        boolean isDevice = coerceBoolean(object.opt("isDevice"));
        if (isDevice) {
            id = DEVICE_LOCATION_ID;
        }
        if (id.isEmpty()) {
            id = generatedId(name, lat, lon, isDevice);
        }
        String cachedTemp = cleanString(object.opt("cachedTemp"));
        String cachedCondition = cleanString(object.opt("cachedCondition"));
        return new LocationSnapshot(id, name, lat, lon, isDevice, cachedTemp, cachedCondition);
    }

    private static void writeStoreLocked(Context context, StoreState state) {
        JSONArray array = new JSONArray();
        for (LocationSnapshot location : state.locations) {
            try {
                array.put(location.toJson());
            } catch (JSONException ignored) {
                // Coordinates are validated before storage, so serialization failure is unexpected.
            }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LOCATIONS_JSON, array.toString())
                .putString(KEY_SELECTED_ID, cleanString(state.selectedId))
                .commit();
    }

    private static String upsertLocked(StoreState state, LocationSnapshot incoming) {
        int index = findEquivalentIndex(state.locations, incoming);
        if (index >= 0) {
            LocationSnapshot existing = state.locations.get(index);
            LocationSnapshot merged = mergeLocations(existing, incoming);
            state.locations.set(index, merged);
            if (state.selectedId.equals(existing.id) && !merged.id.equals(existing.id)) {
                state.selectedId = merged.id;
            }
            return merged.id;
        }
        state.locations.add(incoming);
        return incoming.id;
    }

    private static LocationSnapshot mergeLocations(LocationSnapshot existing, LocationSnapshot incoming) {
        boolean device = existing.isDevice || incoming.isDevice;
        String id = device ? DEVICE_LOCATION_ID : existing.id;
        String name = !cleanString(incoming.name).isEmpty() ? incoming.name : existing.name;
        String temp = !cleanString(incoming.cachedTemp).isEmpty() ? incoming.cachedTemp : existing.cachedTemp;
        String condition = !cleanString(incoming.cachedCondition).isEmpty()
                ? incoming.cachedCondition : existing.cachedCondition;

        double lat = existing.lat;
        double lon = existing.lon;
        if (incoming.isDevice || !existing.isDevice) {
            lat = incoming.lat;
            lon = incoming.lon;
        }
        return new LocationSnapshot(id, name, lat, lon, device, temp, condition);
    }

    private static int findEquivalentIndex(List<LocationSnapshot> locations, LocationSnapshot incoming) {
        for (int i = 0; i < locations.size(); i++) {
            LocationSnapshot existing = locations.get(i);
            if (existing.id.equals(incoming.id)) {
                return i;
            }
            if (existing.isDevice && incoming.isDevice) {
                return i;
            }
            double latDiff = Math.abs(existing.lat - incoming.lat);
            double lonDiff = Math.abs(existing.lon - incoming.lon);
            if (latDiff <= COORD_EPSILON && lonDiff <= COORD_EPSILON) {
                return i;
            }
            String a = normalizeName(existing.name);
            String b = normalizeName(incoming.name);
            if (!a.isEmpty() && a.equals(b)
                    && latDiff <= NAME_COORD_EPSILON
                    && lonDiff <= NAME_COORD_EPSILON) {
                return i;
            }
        }
        return -1;
    }

    private static void normalizeSelectedId(StoreState state) {
        if (state.locations.isEmpty()) {
            state.selectedId = "";
            return;
        }
        if (findById(state.locations, state.selectedId) == null) {
            state.selectedId = preferredFallbackId(state.locations);
        }
    }

    private static String preferredFallbackId(List<LocationSnapshot> locations) {
        for (LocationSnapshot location : locations) {
            if (location.isDevice) {
                return location.id;
            }
        }
        return locations.isEmpty() ? "" : locations.get(0).id;
    }

    private static int indexById(List<LocationSnapshot> locations, String id) {
        String cleanId = cleanString(id);
        for (int i = 0; i < locations.size(); i++) {
            if (locations.get(i).id.equals(cleanId)) {
                return i;
            }
        }
        return -1;
    }

    private static LocationSnapshot findById(List<LocationSnapshot> locations, String id) {
        int index = indexById(locations, id);
        return index < 0 ? null : locations.get(index);
    }

    private static String generatedId(String name, double lat, double lon, boolean device) {
        if (device) {
            return DEVICE_LOCATION_ID;
        }
        String seed = normalizeName(name)
                + "|" + String.format(Locale.US, "%.5f", lat)
                + "|" + String.format(Locale.US, "%.5f", lon);
        UUID stable = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
        return "city-" + stable;
    }

    private static Double coerceDouble(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof Number) {
            double result = ((Number) value).doubleValue();
            return Double.isFinite(result) ? result : null;
        }
        try {
            double result = Double.parseDouble(cleanString(value));
            return Double.isFinite(result) ? result : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean coerceBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String text = cleanString(value);
        return "1".equals(text) || "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text);
    }

    private static final class CityCardBackground extends Drawable {
        private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hazePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float radius;
        private final float strokeWidth;
        private final boolean selected;
        private int alpha = 255;

        CityCardBackground(float radius, float density, boolean selected) {
            this.radius = radius;
            this.selected = selected;
            this.strokeWidth = Math.max(1f, density * 0.85f);
            basePaint.setStyle(Paint.Style.FILL);
            glowPaint.setStyle(Paint.Style.FILL);
            hazePaint.setStyle(Paint.Style.FILL);
            edgePaint.setStyle(Paint.Style.STROKE);
            edgePaint.setStrokeWidth(strokeWidth);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            float inset = strokeWidth * 0.5f;
            rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset);
            rebuildPaints(bounds);
        }

        private void rebuildPaints(Rect bounds) {
            if (bounds.width() <= 0 || bounds.height() <= 0) return;
            int top = selected ? Color.argb(178, 31, 119, 224) : Color.argb(154, 27, 102, 190);
            int middle = selected ? Color.argb(145, 70, 145, 207) : Color.argb(126, 60, 126, 181);
            int bottom = selected ? Color.argb(100, 143, 157, 150) : Color.argb(90, 124, 139, 136);
            basePaint.setShader(new LinearGradient(
                    bounds.left, bounds.top, bounds.left, bounds.bottom,
                    new int[]{multiplyAlpha(top, alpha), multiplyAlpha(middle, alpha), multiplyAlpha(bottom, alpha)},
                    new float[]{0f, 0.58f, 1f}, Shader.TileMode.CLAMP));

            float glowRadius = Math.max(bounds.width(), bounds.height()) * 0.78f;
            glowPaint.setShader(new RadialGradient(
                    bounds.left + bounds.width() * 0.78f,
                    bounds.top + bounds.height() * 0.08f,
                    glowRadius,
                    new int[]{
                            multiplyAlpha(Color.argb(24, 190, 224, 255), alpha),
                            multiplyAlpha(Color.argb(8, 190, 224, 255), alpha),
                            Color.TRANSPARENT
                    },
                    new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP));

            hazePaint.setShader(new LinearGradient(
                    bounds.left, bounds.top + bounds.height() * 0.45f, bounds.left, bounds.bottom,
                    new int[]{
                            Color.TRANSPARENT,
                            multiplyAlpha(Color.argb(10, 235, 238, 225), alpha),
                            multiplyAlpha(Color.argb(16, 225, 226, 207), alpha)
                    },
                    new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));

            int edge = selected ? Color.argb(86, 82, 156, 255) : Color.TRANSPARENT;
            edgePaint.setShader(null);
            edgePaint.setColor(multiplyAlpha(edge, alpha));
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawRoundRect(rect, radius, radius, basePaint);
            canvas.drawRoundRect(rect, radius, radius, glowPaint);
            canvas.drawRoundRect(rect, radius, radius, hazePaint);
            if (Color.alpha(edgePaint.getColor()) > 0) {
                canvas.drawRoundRect(rect, radius, radius, edgePaint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            int next = Math.max(0, Math.min(255, alpha));
            if (this.alpha != next) {
                this.alpha = next;
                rebuildPaints(getBounds());
                invalidateSelf();
            }
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            basePaint.setColorFilter(colorFilter);
            glowPaint.setColorFilter(colorFilter);
            hazePaint.setColorFilter(colorFilter);
            edgePaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }

        private static int multiplyAlpha(int color, int drawableAlpha) {
            int base = Color.alpha(color);
            int out = (base * drawableAlpha + 127) / 255;
            return Color.argb(out, Color.red(color), Color.green(color), Color.blue(color));
        }
    }

    private final class CityCardView extends FrameLayout {
        private final LocationSnapshot location;
        private final LinearLayout contentColumn;
        private final GlyphButton deleteButton;

        CityCardView(Context context, LocationSnapshot location, boolean selected) {
            super(context);
            this.location = location;
            setClickable(true);
            setFocusable(true);
            setMinimumHeight(dp(104));
            setContentDescription((selected ? "Selected. " : "") + cardDescription(location));
            setBackground(new CityCardBackground(
                    dp(18),
                    Math.max(1f, getResources().getDisplayMetrics().density),
                    selected));

            contentColumn = new LinearLayout(context);
            contentColumn.setOrientation(LinearLayout.VERTICAL);
            addView(contentColumn, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout top = new LinearLayout(context);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.TOP | Gravity.CENTER_VERTICAL);
            top.setPadding(dp(16), dp(10), dp(16), 0);
            contentColumn.addView(top, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            LinearLayout nameRow = new LinearLayout(context);
            nameRow.setOrientation(LinearLayout.HORIZONTAL);
            nameRow.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(nameRow, new LinearLayout.LayoutParams(0, dp(46), 1f));

            TextView name = makeText(location.name, 23f, COLOR_PRIMARY_TEXT, false);
            name.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            nameRow.addView(name, new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f));

            if (location.isDevice) {
                LocationPinView pin = new LocationPinView(context);
                pin.setContentDescription("Device location");
                LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(dp(20), dp(20));
                pinLp.leftMargin = dp(5);
                nameRow.addView(pin, pinLp);
            }

            TextView temp = makeText(displayTemperature(location.cachedTemp), 29f, COLOR_PRIMARY_TEXT, false);
            temp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            temp.setGravity(Gravity.END | Gravity.TOP);
            temp.setSingleLine(true);
            top.addView(temp, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)));

            LinearLayout bottom = new LinearLayout(context);
            bottom.setOrientation(LinearLayout.HORIZONTAL);
            bottom.setGravity(Gravity.BOTTOM | Gravity.CENTER_VERTICAL);
            bottom.setPadding(dp(16), 0, dp(16), dp(13));
            contentColumn.addView(bottom, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(39)));

            TextView cached = makeText("Cached", 13f, COLOR_SECONDARY_TEXT, false);
            cached.setSingleLine(true);
            bottom.addView(cached, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView condition = makeText(
                    location.cachedCondition.isEmpty() ? "No cached weather" : location.cachedCondition,
                    13f,
                    COLOR_SECONDARY_TEXT,
                    false);
            condition.setGravity(Gravity.END);
            condition.setSingleLine(true);
            condition.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams conditionLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            conditionLp.leftMargin = dp(10);
            bottom.addView(condition, conditionLp);

            deleteButton = new GlyphButton(context, location.isDevice ? GlyphButton.LOCK : GlyphButton.TRASH);
            deleteButton.setVisibility(View.GONE);
            deleteButton.setContentDescription(location.isDevice
                    ? "Device location cannot be deleted"
                    : "Delete " + location.name);
            deleteButton.setEnabled(!location.isDevice);
            if (!location.isDevice) {
                deleteButton.setOnClickListener(v -> {
                    if (isEnabled()) {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    }
                });
            }
            FrameLayout.LayoutParams deleteLp = new FrameLayout.LayoutParams(
                    dp(48), dp(48), Gravity.END | Gravity.CENTER_VERTICAL);
            deleteLp.rightMargin = dp(7);
            addView(deleteButton, deleteLp);
        }

        void setDeleteAction(OnClickListener listener) {
            if (!location.isDevice) {
                deleteButton.setOnClickListener(listener);
            }
        }

        void setEditMode(boolean enabled) {
            deleteButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
            contentColumn.setPadding(0, 0, enabled ? dp(52) : 0, 0);
            setClickable(!enabled);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            boolean pressed = isPressed();
            setAlpha(pressed ? 0.93f : 1f);
            setScaleX(pressed ? 0.995f : 1f);
            setScaleY(pressed ? 0.995f : 1f);
        }
    }

    private static String cardDescription(LocationSnapshot location) {
        StringBuilder text = new StringBuilder();
        text.append(location.name);
        if (location.isDevice) {
            text.append(", device location");
        }
        if (!location.cachedTemp.isEmpty()) {
            text.append(", ").append(displayTemperature(location.cachedTemp));
        }
        if (!location.cachedCondition.isEmpty()) {
            text.append(", ").append(location.cachedCondition);
        }
        text.append(". Double tap to select.");
        return text.toString();
    }

    private final class LocationPinView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        LocationPinView(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            paint.setColor(COLOR_PRIMARY_TEXT);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.2f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() * 0.43f;
            float r = Math.min(getWidth(), getHeight()) * 0.25f;
            path.reset();
            path.moveTo(cx, getHeight() * 0.88f);
            path.cubicTo(
                    cx - getWidth() * 0.26f, getHeight() * 0.60f,
                    cx - getWidth() * 0.29f, getHeight() * 0.20f,
                    cx, getHeight() * 0.14f);
            path.cubicTo(
                    cx + getWidth() * 0.29f, getHeight() * 0.20f,
                    cx + getWidth() * 0.26f, getHeight() * 0.60f,
                    cx, getHeight() * 0.88f);
            canvas.drawPath(path, paint);
            canvas.drawCircle(cx, cy, r * 0.42f, paint);
        }
    }

    private final class GlyphButton extends View {
        static final int BACK = 1;
        static final int EDIT = 2;
        static final int DONE = 3;
        static final int SEARCH = 4;
        static final int TRASH = 5;
        static final int LOCK = 6;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private int glyph;

        GlyphButton(Context context, int glyph) {
            super(context);
            this.glyph = glyph;
            setClickable(true);
            setFocusable(true);
            setMinimumWidth(dp(44));
            setMinimumHeight(dp(44));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setGlyph(int glyph) {
            this.glyph = glyph;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (isPressed()) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(30, 255, 255, 255));
                canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) * 0.42f, paint);
            }

            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(glyph == SEARCH ? 2.4f : 2.0f));
            paint.setColor(glyph == TRASH ? COLOR_DELETE : Color.rgb(221, 225, 230));
            path.reset();

            switch (glyph) {
                case BACK:
                    path.moveTo(w * 0.67f, h * 0.25f);
                    path.lineTo(w * 0.37f, h * 0.50f);
                    path.lineTo(w * 0.67f, h * 0.75f);
                    canvas.drawPath(path, paint);
                    canvas.drawLine(w * 0.39f, h * 0.50f, w * 0.82f, h * 0.50f, paint);
                    break;
                case EDIT:
                    for (int i = 0; i < 3; i++) {
                        float y = h * (0.31f + i * 0.19f);
                        path.reset();
                        path.moveTo(w * 0.22f, y);
                        path.lineTo(w * 0.27f, y + h * 0.05f);
                        path.lineTo(w * 0.35f, y - h * 0.05f);
                        canvas.drawPath(path, paint);
                        canvas.drawLine(w * 0.45f, y, w * 0.80f, y, paint);
                    }
                    break;
                case DONE:
                    path.moveTo(w * 0.24f, h * 0.52f);
                    path.lineTo(w * 0.43f, h * 0.69f);
                    path.lineTo(w * 0.78f, h * 0.31f);
                    canvas.drawPath(path, paint);
                    break;
                case SEARCH:
                    canvas.drawCircle(w * 0.44f, h * 0.43f, Math.min(w, h) * 0.19f, paint);
                    canvas.drawLine(w * 0.57f, h * 0.57f, w * 0.72f, h * 0.72f, paint);
                    break;
                case TRASH:
                    canvas.drawLine(w * 0.31f, h * 0.34f, w * 0.69f, h * 0.34f, paint);
                    canvas.drawLine(w * 0.42f, h * 0.27f, w * 0.58f, h * 0.27f, paint);
                    RectF bin = new RectF(w * 0.35f, h * 0.39f, w * 0.65f, h * 0.73f);
                    canvas.drawRoundRect(bin, dp(2), dp(2), paint);
                    canvas.drawLine(w * 0.45f, h * 0.46f, w * 0.45f, h * 0.66f, paint);
                    canvas.drawLine(w * 0.55f, h * 0.46f, w * 0.55f, h * 0.66f, paint);
                    break;
                case LOCK:
                    RectF body = new RectF(w * 0.34f, h * 0.47f, w * 0.66f, h * 0.72f);
                    canvas.drawRoundRect(body, dp(2), dp(2), paint);
                    RectF shackle = new RectF(w * 0.39f, h * 0.28f, w * 0.61f, h * 0.56f);
                    canvas.drawArc(shackle, 180f, 180f, false, paint);
                    break;
                default:
                    break;
            }
        }
    }

    private final class FloatingAddButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        FloatingAddButton(Context context) {
            super(context);
            setClickable(true);
            setFocusable(true);
            setMinimumWidth(dp(44));
            setMinimumHeight(dp(44));
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(Color.rgb(43, 128, 244));
            setBackground(circle);
            setElevation(dp(7));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(COLOR_PRIMARY_TEXT);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.SQUARE);
            paint.setStrokeWidth(dp(2.2f));
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float d = Math.min(getWidth(), getHeight()) * 0.24f;
            canvas.drawLine(cx - d, cy, cx + d, cy, paint);
            canvas.drawLine(cx, cy - d, cx, cy + d, paint);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            boolean pressed = isPressed();
            setAlpha(pressed ? 0.88f : 1f);
            setScaleX(pressed ? 0.96f : 1f);
            setScaleY(pressed ? 0.96f : 1f);
        }
    }

    /** API-33 Geocoder bridge isolated so older devices never resolve callback-only API classes. */
    @TargetApi(33)
    private static final class Api33Geocoder {
        interface ResultCallback {
            void onSuccess(List<Address> addresses);
            void onFailure(String message);
        }

        static void search(Geocoder geocoder, String query, int maxResults, ResultCallback callback) {
            geocoder.getFromLocationName(query, maxResults, new Geocoder.GeocodeListener() {
                @Override
                public void onGeocode(List<Address> addresses) {
                    callback.onSuccess(addresses == null ? Collections.emptyList() : addresses);
                }

                @Override
                public void onError(String errorMessage) {
                    callback.onFailure(cleanString(errorMessage));
                }
            });
        }
    }

    /** Platform predictive-back bridge, kept isolated from API 28-32 class verification. */
    @TargetApi(33)
    private static final class Api33BackNavigation {
        static void register(CityManagerActivity activity) {
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    activity::handleBack);
        }
    }
}
