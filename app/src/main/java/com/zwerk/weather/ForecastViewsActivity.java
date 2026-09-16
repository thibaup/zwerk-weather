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


abstract class ForecastViewsActivity extends WeatherInteractionViewsActivity {
    final ArrayList<SunTrackView> sunTrackViews = new ArrayList<>();
    Bitmap sunTrackMarkerBitmap;
    Bitmap moonTrackMarkerBitmap;
    final ForecastPreviewController forecastPreview = new ForecastPreviewController();

    static final class PreviewBinding {
        final WeakReference<View> view;
        final String key;

        PreviewBinding(View view, String key) {
            this.view = new WeakReference<>(view);
            this.key = key == null ? "" : key;
        }
    }

    final class ForecastPreviewController {
        private SceneSpec currentScene = new SceneSpec("day", "none", true, "Clear");
        private SceneSpec previewScene;
        private String selectionKey = "";
        private String previewLabel = "";
        private final ArrayList<PreviewBinding> bindings = new ArrayList<>();
        private final ArrayList<WeakReference<ForecastChartView>> charts = new ArrayList<>();

        void setCurrentScene(SceneSpec scene) {
            currentScene = scene == null ? new SceneSpec("day", "none", true, "Clear") : scene;
            previewScene = null;
            selectionKey = "";
            previewLabel = "";
            bindings.clear();
            charts.clear();
            updatePreviewSubtitle();
        }

        SceneSpec currentScene() {
            return currentScene;
        }

        boolean isPreviewing() {
            return previewScene != null && !selectionKey.isEmpty();
        }

        String selectionKey() {
            return selectionKey;
        }

        boolean select(String key, String label, SceneSpec scene) {
            if (key == null || key.isEmpty() || scene == null) return false;
            if (key.equals(selectionKey)) {
                restore(true);
                return false;
            }
            selectionKey = key;
            previewLabel = label == null ? "" : label;
            previewScene = scene;
            if (skyLayout != null) skyLayout.setScene(scene);
            if (headerGlass != null) headerGlass.setScene(scene, animationsAllowed());
            updatePreviewSubtitle();
            refreshSelectionVisuals();
            announcePreview(previewLabel + ", " + scene.condition + ". Previewing forecast. Tap again or the location to return to now.");
            return true;
        }

        void restore(boolean announce) {
            boolean hadPreview = isPreviewing();
            previewScene = null;
            selectionKey = "";
            previewLabel = "";
            if (skyLayout != null) skyLayout.setScene(currentScene);
            if (headerGlass != null) headerGlass.setScene(currentScene, animationsAllowed());
            updatePreviewSubtitle();
            refreshSelectionVisuals();
            if (announce && hadPreview) announcePreview("Back to current weather");
        }

        void registerTarget(View view, String key) {
            if (view == null || key == null) return;
            bindings.add(new PreviewBinding(view, key));
            applyPreviewSelectionVisual(view, key.equals(selectionKey));
        }

        void registerChart(ForecastChartView chart) {
            if (chart == null) return;
            charts.add(new WeakReference<>(chart));
            chart.syncPreviewFromController();
        }

        void dispose() {
            previewScene = null;
            selectionKey = "";
            previewLabel = "";
            bindings.clear();
            charts.clear();
        }

        private void refreshSelectionVisuals() {
            for (int i = bindings.size() - 1; i >= 0; i--) {
                PreviewBinding binding = bindings.get(i);
                View view = binding.view.get();
                if (view == null) {
                    bindings.remove(i);
                    continue;
                }
                applyPreviewSelectionVisual(view, binding.key.equals(selectionKey));
            }
            for (int i = charts.size() - 1; i >= 0; i--) {
                ForecastChartView chart = charts.get(i).get();
                if (chart == null) {
                    charts.remove(i);
                    continue;
                }
                chart.syncPreviewFromController();
            }
        }
    }

    void updatePreviewSubtitle() {
        if (previewSubtitle == null || locationArea == null) return;
        if (forecastPreview.isPreviewing()) {
            String text = forecastPreview.previewLabel
                    + " · " + forecastPreview.previewScene.condition
                    + " · Back to now";
            previewSubtitle.setText(text);
            previewSubtitle.setVisibility(View.VISIBLE);
            locationArea.setContentDescription(
                    locationName + ". " + text + ". Tap to restore current weather.");
        } else {
            previewSubtitle.setText("");
            previewSubtitle.setVisibility(View.GONE);
            locationArea.setContentDescription(locationName + ". Current forecast location.");
        }
    }

    void announcePreview(String message) {
        if (message == null || message.trim().isEmpty()) return;
        View target = locationArea != null ? locationArea : skyLayout;
        if (target != null) target.announceForAccessibility(message);
    }

    StateListDrawable previewTargetBackground(int radiusPx) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_selected},
                roundedBg(Color.argb(31, 255, 255, 255), radiusPx));
        states.addState(new int[]{android.R.attr.state_pressed},
                roundedBg(Color.argb(28, 255, 255, 255), radiusPx));
        states.addState(new int[]{android.R.attr.state_focused},
                roundedBg(Color.argb(22, 255, 255, 255), radiusPx));
        states.addState(new int[]{}, roundedBg(Color.TRANSPARENT, radiusPx));
        return states;
    }

    void applyPreviewSelectionVisual(View view, boolean selected) {
        if (view == null) return;
        view.setSelected(selected);
        if (Build.VERSION.SDK_INT >= 30) {
            view.setStateDescription(selected ? "Previewing" : null);
        }
    }


    SceneSpec sceneForForecastDay(JSONObject day, int index) {
        if (day == null) return forecastPreview.currentScene();
        boolean currentDaytime = safeBoolean(lastCurrentWeather, "isDaytime", true);
        JSONObject daytime = day.optJSONObject("daytimeForecast");
        JSONObject nighttime = day.optJSONObject("nighttimeForecast");
        JSONObject selected;
        boolean fallbackDaytime;
        if (index == 0) {
            if (currentDaytime) {
                selected = daytime != null ? daytime : nighttime;
                fallbackDaytime = daytime != null;
            } else {
                selected = nighttime != null ? nighttime : daytime;
                fallbackDaytime = nighttime == null;
            }
        } else {
            selected = daytime != null ? daytime : nighttime;
            fallbackDaytime = daytime != null;
        }
        return selected == null
                ? forecastPreview.currentScene()
                : SceneSpec.fromWeather(selected, fallbackDaytime);
    }



    final class ForecastChartView extends View {
        private final JSONArray days;
        private final ZoneId zone;
        private final int desiredWidth;
        private final int desiredHeight;
        private final DaySelectionListener daySelectionListener;
        private final int touchSlop;
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float downX;
        private float downY;
        private boolean moved;
        private int cursorDay;
        private int previewDay = -1;

        ForecastChartView(
                Context context,
                JSONArray days,
                ZoneId zone,
                int desiredWidth,
                int desiredHeight,
                DaySelectionListener daySelectionListener) {
            super(context);
            this.days = days;
            this.zone = zone;
            this.desiredWidth = Math.max(dp(280), desiredWidth);
            this.desiredHeight = Math.max(dp(290), desiredHeight);
            this.daySelectionListener = daySelectionListener;
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setMinimumWidth(this.desiredWidth);
            setMinimumHeight(this.desiredHeight);
            setClickable(true);
            setFocusable(true);
            setContentDescription(chartAccessibilityDescription(days, zone));
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // Keep the v4/API-36 fix: HorizontalScrollView may offer an UNSPECIFIED axis.
            int measuredWidth = measuredDimension(desiredWidth, widthMeasureSpec);
            int measuredHeight = measuredDimension(desiredHeight, heightMeasureSpec);
            setMeasuredDimension(measuredWidth, measuredHeight);
        }

        private int measuredDimension(int desired, int spec) {
            int mode = MeasureSpec.getMode(spec);
            int size = MeasureSpec.getSize(spec);
            if (mode == MeasureSpec.EXACTLY) return size;
            if (mode == MeasureSpec.AT_MOST) return Math.min(desired, size);
            return desired;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX() - downX) > touchSlop
                            || Math.abs(event.getY() - downY) > touchSlop) {
                        moved = true;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved && days != null && days.length() > 0) {
                        int n = Math.min(10, days.length());
                        float cell = getWidth() / (float) Math.max(1, n);
                        int index = Math.max(0, Math.min(n - 1, (int) (event.getX() / cell)));
                        cursorDay = index;
                        performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    moved = true;
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            int count = days == null ? 0 : Math.min(10, days.length());
            if (count > 0 && daySelectionListener != null) {
                cursorDay = Math.max(0, Math.min(count - 1, cursorDay));
                daySelectionListener.onDaySelected(cursorDay);
            }
            return true;
        }

        void syncPreviewFromController() {
            int selected = -1;
            String key = forecastPreview.selectionKey();
            if (key != null && key.startsWith("day:")) {
                try { selected = Integer.parseInt(key.substring(4)); }
                catch (Exception ignored) { selected = -1; }
            }
            previewDay = selected;
            if (Build.VERSION.SDK_INT >= 30) {
                setStateDescription(previewDay >= 0 ? "Previewing" : null);
            }
            invalidate();
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            int count = days == null ? 0 : Math.min(10, days.length());
            if (count <= 0) return super.onKeyDown(keyCode, event);
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                cursorDay = Math.max(0, cursorDay - 1);
                announceCursorDay();
                invalidate();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                cursorDay = Math.min(count - 1, cursorDay + 1);
                announceCursorDay();
                invalidate();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_SPACE) {
                return performClick();
            }
            return super.onKeyDown(keyCode, event);
        }

        private void announceCursorDay() {
            JSONObject day = days == null ? null : days.optJSONObject(cursorDay);
            String label = cursorDay == 0 ? "Today" : dayLabel(day, zone);
            String condition = sceneForForecastDay(day, cursorDay).condition;
            announceForAccessibility(label + ", " + condition
                    + ". Press select to preview and show hourly details; select again to return to now.");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (days == null || days.length() == 0) {
                textPaint.setColor(SOFT_WHITE);
                textPaint.setTextSize(dp(14));
                textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("Daily forecast unavailable", getWidth() / 2f, getHeight() / 2f, textPaint);
                return;
            }

            int n = Math.min(10, days.length());
            float width = getWidth();
            float height = getHeight();
            float cell = width / Math.max(1, n);

            if (previewDay >= 0 && previewDay < n) {
                dotPaint.setStyle(Paint.Style.FILL);
                dotPaint.setColor(Color.argb(25, 255, 255, 255));
                float left = previewDay * cell + dp(3);
                float right = (previewDay + 1) * cell - dp(3);
                canvas.drawRoundRect(new RectF(left, dp(3), right, height - dp(3)),
                        dp(16), dp(16), dotPaint);
            }
            if (hasFocus() && cursorDay >= 0 && cursorDay < n) {
                dotPaint.setStyle(Paint.Style.FILL);
                dotPaint.setColor(Color.argb(14, 255, 255, 255));
                float left = cursorDay * cell + dp(6);
                float right = (cursorDay + 1) * cell - dp(6);
                canvas.drawRoundRect(new RectF(left, dp(6), right, height - dp(6)),
                        dp(14), dp(14), dotPaint);
            }

            Integer[] highs = new Integer[n];
            Integer[] lows = new Integer[n];
            int[] pops = new int[n];
            int highMin = Integer.MAX_VALUE;
            int highMax = Integer.MIN_VALUE;
            int lowMin = Integer.MAX_VALUE;
            int lowMax = Integer.MIN_VALUE;

            for (int i = 0; i < n; i++) {
                JSONObject day = days.optJSONObject(i);
                highs[i] = degreesOrNull(day == null ? null : day.optJSONObject("maxTemperature"));
                lows[i] = degreesOrNull(day == null ? null : day.optJSONObject("minTemperature"));
                pops[i] = dayProbability(day);
                if (highs[i] != null) {
                    highMin = Math.min(highMin, highs[i]);
                    highMax = Math.max(highMax, highs[i]);
                }
                if (lows[i] != null) {
                    lowMin = Math.min(lowMin, lows[i]);
                    lowMax = Math.max(lowMax, lows[i]);
                }
            }
            if (highMin == Integer.MAX_VALUE) { highMin = 0; highMax = 1; }
            if (lowMin == Integer.MAX_VALUE) { lowMin = 0; lowMax = 1; }
            if (highMin == highMax) highMax = highMin + 1;
            if (lowMin == lowMax) lowMax = lowMin + 1;

            float dayY = dp(22);
            float dateY = dp(42);
            float iconY = dp(82);
            float highTop = dp(126);
            float highBottom = dp(174);
            float lowTop = dp(205);
            float lowBottom = dp(246);
            float rainTop = Math.max(lowBottom + dp(32), height - dp(48));
            float rainBase = height - dp(7);

            float[] highX = new float[n];
            float[] highY = new float[n];
            float[] lowX = new float[n];
            float[] lowY = new float[n];

            for (int i = 0; i < n; i++) {
                JSONObject day = days.optJSONObject(i);
                float x = cell * (i + 0.5f);

                textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                textPaint.setTextAlign(Paint.Align.CENTER);
                textPaint.setColor(WHITE);
                textPaint.setTextSize(dp(13));
                canvas.drawText(i == 0 ? "Today" : dayLabel(day, zone), x, dayY, textPaint);

                textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                textPaint.setColor(SOFT_WHITE);
                textPaint.setTextSize(dp(10));
                canvas.drawText(dayDateLabel(day, zone), x, dateY, textPaint);

                JSONObject daytime = day == null ? null : day.optJSONObject("daytimeForecast");
                drawWeatherGlyph(canvas, description(daytime), true, x, iconY, dp(34));

                highX[i] = lowX[i] = x;
                highY[i] = mapTemp(highs[i], highMin, highMax, highTop, highBottom);
                lowY[i] = mapTemp(lows[i], lowMin, lowMax, lowTop, lowBottom);
            }

            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(dp(2.1f));
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setStrokeJoin(Paint.Join.ROUND);
            linePaint.setColor(Color.argb(205, 242, 246, 255));
            drawSmoothLine(canvas, highX, highY, highs, linePaint);
            linePaint.setColor(Color.argb(155, 207, 224, 248));
            drawSmoothLine(canvas, lowX, lowY, lows, linePaint);

            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(dp(16));
            textPaint.setColor(WHITE);
            dotPaint.setStyle(Paint.Style.FILL);
            dotPaint.setColor(WHITE);

            for (int i = 0; i < n; i++) {
                if (highs[i] != null) {
                    canvas.drawCircle(highX[i], highY[i], dp(3.2f), dotPaint);
                    canvas.drawText(highs[i] + "°", highX[i], highY[i] - dp(10), textPaint);
                }
                if (lows[i] != null) {
                    canvas.drawCircle(lowX[i], lowY[i], dp(3.2f), dotPaint);
                    canvas.drawText(lows[i] + "°", lowX[i], lowY[i] + dp(22), textPaint);
                }
            }

            // Compact precipitation band: actual daily probability values only, no synthesized data.
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setTextSize(dp(10));
            textPaint.setColor(ACCENT_BLUE);
            dotPaint.setColor(Color.argb(165, 176, 226, 255));
            float maxBarHeight = Math.max(dp(10), rainBase - rainTop - dp(12));
            float barWidth = Math.min(dp(22), cell * 0.30f);
            for (int i = 0; i < n; i++) {
                float x = cell * (i + 0.5f);
                int p = pops[i];
                String label = p < 0 ? "—" : p + "%";
                canvas.drawText(label, x, rainTop + dp(9), textPaint);
                if (p >= 0) {
                    float h = p == 0 ? dp(2) : Math.max(dp(3), maxBarHeight * (p / 100f));
                    RectF bar = new RectF(x - barWidth / 2f, rainBase - h, x + barWidth / 2f, rainBase);
                    canvas.drawRoundRect(bar, dp(4), dp(4), dotPaint);
                }
            }
        }

        private float mapTemp(Integer value, int min, int max, float top, float bottom) {
            if (value == null) return (top + bottom) / 2f;
            float normalized = (value - min) / (float) (max - min);
            return bottom - normalized * (bottom - top);
        }

        private void drawSmoothLine(Canvas canvas, float[] xs, float[] ys, Integer[] values, Paint paint) {
            Path path = new Path();
            boolean drawing = false;
            int previous = -1;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null) {
                    if (drawing) canvas.drawPath(path, paint);
                    path.reset();
                    drawing = false;
                    previous = -1;
                    continue;
                }
                if (!drawing) {
                    path.moveTo(xs[i], ys[i]);
                    drawing = true;
                } else {
                    float mid = (xs[previous] + xs[i]) / 2f;
                    path.cubicTo(mid, ys[previous], mid, ys[i], xs[i], ys[i]);
                }
                previous = i;
            }
            if (drawing) canvas.drawPath(path, paint);
        }

        private String chartAccessibilityDescription(JSONArray values, ZoneId zone) {
            if (values == null || values.length() == 0) return "Daily forecast unavailable";
            StringBuilder out = new StringBuilder("10-day high, low, and precipitation forecast. ");
            int count = Math.min(10, values.length());
            for (int i = 0; i < count; i++) {
                JSONObject day = values.optJSONObject(i);
                Integer high = degreesOrNull(day == null ? null : day.optJSONObject("maxTemperature"));
                Integer low = degreesOrNull(day == null ? null : day.optJSONObject("minTemperature"));
                int p = dayProbability(day);
                if (i > 0) out.append("; ");
                out.append(i == 0 ? "Today" : dayLabel(day, zone));
                String condition = sceneForForecastDay(day, i).condition;
                if (!"Unknown".equals(condition)) out.append(", ").append(condition);
                if (high != null) {
                    out.append(", high ").append(high)
                            .append(" degrees ").append(temperatureUnitWord());
                }
                if (low != null) {
                    out.append(", low ").append(low)
                            .append(" degrees ").append(temperatureUnitWord());
                }
                if (p >= 0) out.append(", precipitation ").append(p).append(" percent");
            }
            out.append(". Use left and right to move the day cursor, then press select for hourly details and preview.");
            return out.toString();
        }
    }

    Bitmap trackMarkerBitmap(boolean moon) {
        Bitmap cached = moon ? moonTrackMarkerBitmap : sunTrackMarkerBitmap;
        if (cached != null && !cached.isRecycled()) return cached;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeResource(
                getResources(),
                moon ? R.drawable.moon_track_marker : R.drawable.sun_track_marker,
                options);
        if (moon) moonTrackMarkerBitmap = decoded;
        else sunTrackMarkerBitmap = decoded;
        return decoded;
    }

    void releaseTrackMarkerBitmaps() {
        if (sunTrackMarkerBitmap != null && !sunTrackMarkerBitmap.isRecycled()) {
            sunTrackMarkerBitmap.recycle();
        }
        if (moonTrackMarkerBitmap != null && !moonTrackMarkerBitmap.isRecycled()) {
            moonTrackMarkerBitmap.recycle();
        }
        sunTrackMarkerBitmap = null;
        moonTrackMarkerBitmap = null;
    }

    final class SunTrackView extends View {
        private final Instant intervalStart;
        private final Instant intervalEnd;
        private final boolean moon;
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint markerPaint = new Paint(
                Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final RectF markerBounds = new RectF();
        private final Rect markerSource = new Rect();
        private final Bitmap markerBitmap;
        private Instant baseCurrent;
        private long baseUptime;
        private Instant frozenCurrent;
        private boolean animationRunning;

        SunTrackView(
                Context context,
                Instant intervalStart,
                Instant intervalEnd,
                Instant current,
                boolean moon,
                boolean animationRunning) {
            super(context);
            this.intervalStart = intervalStart;
            this.intervalEnd = intervalEnd;
            this.moon = moon;
            this.baseCurrent = current == null ? Instant.now() : current;
            this.frozenCurrent = this.baseCurrent;
            this.baseUptime = SystemClock.uptimeMillis();
            this.animationRunning = animationRunning;
            this.markerBitmap = trackMarkerBitmap(moon);
            if (moon) {
                markerSource.set(35, 29, 209, 238);
            } else {
                markerSource.set(49, 43, 225, 214);
            }
            markerPaint.setFilterBitmap(true);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setAnimationRunning(boolean running) {
            if (animationRunning == running) {
                if (running) postInvalidateOnAnimation();
                return;
            }
            if (!running) {
                frozenCurrent = displayCurrent();
                animationRunning = false;
                invalidate();
                return;
            }
            animationRunning = true;
            baseCurrent = Instant.now();
            frozenCurrent = baseCurrent;
            baseUptime = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }

        private Instant displayCurrent() {
            if (!animationRunning) return frozenCurrent;
            long elapsed = Math.max(0L, SystemClock.uptimeMillis() - baseUptime);
            try {
                return baseCurrent.plusMillis(elapsed);
            } catch (Exception ignored) {
                return baseCurrent;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (animationRunning && !animationsAllowed()) {
                frozenCurrent = displayCurrent();
                animationRunning = false;
            }

            float markerMaxSize = dp(22);
            float sourceAspect = markerSource.height() <= 0
                    ? 1f : markerSource.width() / (float) markerSource.height();
            float markerWidth = sourceAspect >= 1f ? markerMaxSize : markerMaxSize * sourceAspect;
            float markerHeight = sourceAspect >= 1f ? markerMaxSize / sourceAspect : markerMaxSize;
            float endpointInset = Math.max(dp(18), markerWidth * 0.5f + dp(3));
            float left = endpointInset;
            float right = Math.max(left, getWidth() - endpointInset);
            float y = getHeight() * 0.59f;

            trackPaint.setShader(null);
            trackPaint.setStyle(Paint.Style.STROKE);
            trackPaint.setStrokeCap(Paint.Cap.ROUND);
            trackPaint.setStrokeWidth(dp(3.5f));
            trackPaint.setColor(Color.argb(72, 255, 255, 255));
            canvas.drawLine(left, y, right, y, trackPaint);

            float fraction = 0.5f;
            Instant now = displayCurrent();
            if (intervalStart != null && intervalEnd != null && intervalEnd.isAfter(intervalStart)) {
                double total = Duration.between(intervalStart, intervalEnd).toMillis();
                double passed = Duration.between(intervalStart, now).toMillis();
                fraction = (float) Math.max(0d, Math.min(1d, passed / total));
            }

            float x = left + (right - left) * fraction;
            trackPaint.setColor(moon
                    ? Color.argb(176, 205, 226, 255)
                    : Color.argb(210, 255, 220, 116));
            trackPaint.setStrokeWidth(dp(4.5f));
            canvas.drawLine(left, y, x, y, trackPaint);

            Bitmap marker = markerBitmap;
            if (marker != null && !marker.isRecycled()) {
                markerBounds.set(
                        x - markerWidth * 0.5f,
                        y - markerHeight * 0.5f,
                        x + markerWidth * 0.5f,
                        y + markerHeight * 0.5f);
                markerPaint.setAlpha(255);
                canvas.drawBitmap(marker, markerSource, markerBounds, markerPaint);
            }

            if (animationRunning) postInvalidateDelayed(50L);
        }
    }



    int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    final class MoonPhaseView extends View {
        private final String phase;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint imagePaint = new Paint(
                Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Path discPath = new Path();
        private final Path phasePath = new Path();
        private final Path phaseCutoutPath = new Path();
        private final Rect crescentSource = new Rect(35, 29, 209, 238);
        private final RectF crescentBounds = new RectF();
        private final Bitmap crescentBitmap;

        MoonPhaseView(Context context, String phase) {
            super(context);
            this.phase = phase == null ? "" : phase;
            this.crescentBitmap = trackMarkerBitmap(true);
            imagePaint.setFilterBitmap(true);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float s = Math.min(getWidth(), getHeight());
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = s * 0.34f;
            String lower = phase.toLowerCase(Locale.ROOT);
            boolean waxing = lower.contains("waxing");
            boolean waning = lower.contains("waning");
            boolean newMoon = lower.contains("new");
            boolean fullMoon = lower.contains("full") || (!lower.contains("crescent")
                    && !lower.contains("quarter") && !lower.contains("gibbous") && !newMoon);

            if (lower.contains("crescent")
                    && crescentBitmap != null
                    && !crescentBitmap.isRecycled()) {
                float markerHeight = s * 0.72f;
                float markerWidth = markerHeight
                        * crescentSource.width() / (float) crescentSource.height();
                crescentBounds.set(
                        cx - markerWidth * 0.5f,
                        cy - markerHeight * 0.5f,
                        cx + markerWidth * 0.5f,
                        cy + markerHeight * 0.5f);
                int save = canvas.save();
                if (waxing) canvas.scale(-1f, 1f, cx, cy);
                canvas.drawBitmap(crescentBitmap, crescentSource, crescentBounds, imagePaint);
                canvas.restoreToCount(save);
                return;
            }

            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(
                    cx,
                    cy,
                    r * 1.40f,
                    new int[]{
                            Color.argb(42, 198, 224, 255),
                            Color.argb(16, 178, 210, 248),
                            Color.TRANSPARENT
                    },
                    new float[]{0f, 0.62f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r * 1.40f, p);

            p.setShader(null);
            p.setColor(Color.argb(190, 51, 82, 128));
            canvas.drawCircle(cx, cy, r, p);

            discPath.reset();
            discPath.addCircle(cx, cy, r, Path.Direction.CW);
            if (!newMoon) {
                p.setShader(new LinearGradient(
                        cx - r,
                        cy - r,
                        cx + r,
                        cy + r,
                        new int[]{
                                Color.rgb(247, 250, 255),
                                Color.rgb(220, 233, 250),
                                Color.rgb(184, 207, 236)
                        },
                        null,
                        Shader.TileMode.CLAMP));
                p.setStyle(Paint.Style.FILL);

                if (fullMoon) {
                    canvas.drawCircle(cx, cy, r, p);
                } else if (lower.contains("crescent")) {
                    phasePath.reset();
                    phasePath.addCircle(cx, cy, r, Path.Direction.CW);
                    float cutDirection = waning ? 1f : -1f;
                    phaseCutoutPath.reset();
                    phaseCutoutPath.addCircle(
                            cx + cutDirection * r * 0.52f,
                            cy,
                            r * 0.94f,
                            Path.Direction.CW);
                    phasePath.op(phaseCutoutPath, Path.Op.DIFFERENCE);
                    canvas.drawPath(phasePath, p);
                } else if (lower.contains("quarter")) {
                    int save = canvas.save();
                    canvas.clipPath(discPath);
                    boolean lightRight = !waning;
                    if (lightRight) {
                        canvas.drawRect(cx, cy - r, cx + r, cy + r, p);
                    } else {
                        canvas.drawRect(cx - r, cy - r, cx, cy + r, p);
                    }
                    canvas.restoreToCount(save);
                } else if (lower.contains("gibbous")) {
                    canvas.drawCircle(cx, cy, r, p);
                    p.setShader(null);
                    p.setColor(Color.argb(205, 50, 81, 126));
                    phasePath.reset();
                    phasePath.addCircle(cx, cy, r, Path.Direction.CW);
                    float shadowDirection = waxing ? -1f : 1f;
                    phaseCutoutPath.reset();
                    phaseCutoutPath.addCircle(
                            cx + shadowDirection * r * 0.58f,
                            cy,
                            r * 0.92f,
                            Path.Direction.CW);
                    phasePath.op(phaseCutoutPath, Path.Op.DIFFERENCE);
                    canvas.drawPath(phasePath, p);
                }
            }

            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(Color.argb(95, 226, 239, 255));
            p.setStrokeWidth(dp(1f));
            canvas.drawCircle(cx, cy, r, p);

            if (!newMoon) {
                int save = canvas.save();
                canvas.clipPath(discPath);
                p.setStyle(Paint.Style.STROKE);
                p.setColor(Color.argb(55, 78, 112, 160));
                p.setStrokeWidth(dp(1f));
                canvas.drawCircle(cx - r * 0.25f, cy - r * 0.14f, r * 0.17f, p);
                canvas.drawCircle(cx + r * 0.18f, cy + r * 0.20f, r * 0.12f, p);
                canvas.drawCircle(cx + r * 0.20f, cy - r * 0.28f, r * 0.085f, p);
                canvas.restoreToCount(save);
            }
        }
    }

}
