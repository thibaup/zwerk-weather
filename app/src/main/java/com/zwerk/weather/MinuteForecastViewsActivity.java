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


abstract class MinuteForecastViewsActivity extends ForecastViewsActivity {
    final class MinutePrecipitationGraphView extends View {
        private final ArrayList<MinuteSegment> segments = new ArrayList<>();
        private final ZoneId zone;
        private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint areaPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint areaStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint markerGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path areaPath = new Path();
        private final Path topPath = new Path();
        private MinuteSelectionListener selectionListener;
        private int selectedIndex;
        private long selectedTimeMillis = Long.MIN_VALUE;
        private float plotLeft;
        private float plotRight;
        private float plotTop;
        private float plotBottom;

        MinutePrecipitationGraphView(Context context, List<MinuteSegment> source, ZoneId zone) {
            super(context);
            if (source != null) segments.addAll(source);
            this.zone = zone == null ? ZoneId.systemDefault() : zone;
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);
            setMinimumHeight(dp(190));
            setMinimumWidth(dp(48));

            bandPaint.setStyle(Paint.Style.FILL);
            gridPaint.setStyle(Paint.Style.STROKE);
            gridPaint.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density * 0.7f));
            areaPaint.setStyle(Paint.Style.FILL);
            areaStrokePaint.setStyle(Paint.Style.STROKE);
            areaStrokePaint.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density * 1.25f));
            areaStrokePaint.setStrokeJoin(Paint.Join.ROUND);
            areaStrokePaint.setStrokeCap(Paint.Cap.ROUND);
            markerPaint.setStyle(Paint.Style.STROKE);
            markerGlowPaint.setStyle(Paint.Style.STROKE);

            textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            textPaint.setTextSize(10f * getResources().getDisplayMetrics().scaledDensity);
            selectedTimeMillis = nearestSelectableTime(Instant.now().toEpochMilli());
            selectedIndex = segmentIndexForTime(selectedTimeMillis);
            updateAccessibility(false);
        }

        void setSelectionListener(MinuteSelectionListener listener) {
            selectionListener = listener;
            dispatchSelection(false);
        }

        void setPreferredSelection(long preferredMillis) {
            if (segments.isEmpty()) return;
            long requested = preferredMillis == Long.MIN_VALUE
                    ? Instant.now().toEpochMilli() : preferredMillis;
            selectedTimeMillis = nearestSelectableTime(requested);
            selectedIndex = segmentIndexForTime(selectedTimeMillis);
            dispatchSelection(false);
            invalidate();
        }

        private int segmentIndexForTime(long epochMillis) {
            if (segments.isEmpty()) return -1;
            for (int i = 0; i < segments.size(); i++) {
                MinuteSegment segment = segments.get(i);
                long start = segment.start.toEpochMilli();
                long end = segment.end.toEpochMilli();
                if (epochMillis >= start && epochMillis < end) return i;
            }
            return -1;
        }

        private long nearestSelectableTime(long epochMillis) {
            if (segments.isEmpty()) return Long.MIN_VALUE;
            long first = segments.get(0).start.toEpochMilli();
            long lastExclusive = segments.get(segments.size() - 1).end.toEpochMilli();
            long last = Math.max(first, lastExclusive - 1L);
            long clamped = Math.max(first, Math.min(last, epochMillis));
            long roundedSteps = Math.round((clamped - first) / (double) MINUTE_SELECTION_STEP_MILLIS);
            long rounded = first + roundedSteps * MINUTE_SELECTION_STEP_MILLIS;
            if (rounded > last) {
                rounded = first + Math.max(0L,
                        (last - first) / MINUTE_SELECTION_STEP_MILLIS) * MINUTE_SELECTION_STEP_MILLIS;
            }
            if (segmentIndexForTime(rounded) >= 0) return rounded;

            long best = first;
            long bestDistance = Long.MAX_VALUE;
            for (long candidate = first; candidate <= last; candidate += MINUTE_SELECTION_STEP_MILLIS) {
                if (segmentIndexForTime(candidate) >= 0) {
                    long distance = Math.abs(candidate - clamped);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
                if (candidate > Long.MAX_VALUE - MINUTE_SELECTION_STEP_MILLIS) break;
            }
            return best;
        }

        private long steppedSelection(int delta) {
            if (segments.isEmpty() || selectedTimeMillis == Long.MIN_VALUE || delta == 0) {
                return selectedTimeMillis;
            }
            long first = segments.get(0).start.toEpochMilli();
            long last = segments.get(segments.size() - 1).end.toEpochMilli() - 1L;
            long direction = delta < 0 ? -1L : 1L;
            long candidate = selectedTimeMillis
                    + direction * MINUTE_SELECTION_STEP_MILLIS;
            while (candidate >= first && candidate <= last) {
                if (segmentIndexForTime(candidate) >= 0) return candidate;
                candidate += direction * MINUTE_SELECTION_STEP_MILLIS;
            }
            return selectedTimeMillis;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (segments.isEmpty()) return;

            float density = getResources().getDisplayMetrics().density;
            plotLeft = dp(8);
            plotRight = Math.max(plotLeft + dp(100), getWidth() - dp(70));
            plotTop = dp(29);
            plotBottom = Math.max(plotTop + dp(90), getHeight() - dp(10));

            drawIntensityBands(canvas);
            drawTimeTicks(canvas);

            long minTime = segments.get(0).start.toEpochMilli();
            long maxTime = segments.get(segments.size() - 1).end.toEpochMilli();
            if (maxTime <= minTime) maxTime = minTime + 1L;

            areaPath.reset();
            topPath.reset();
            boolean runOpen = false;
            long previousEnd = Long.MIN_VALUE;
            float previousEndX = 0f;

            for (MinuteSegment segment : segments) {
                Double rate = minuteRateMmPerHour(segment);
                if (rate == null) {
                    if (runOpen) {
                        areaPath.lineTo(previousEndX, plotBottom);
                        areaPath.close();
                        runOpen = false;
                    }
                    previousEnd = Long.MIN_VALUE;
                    continue;
                }

                long startMillis = segment.start.toEpochMilli();
                long endMillis = segment.end.toEpochMilli();
                float x1 = xForTime(startMillis, minTime, maxTime);
                float x2 = xForTime(endMillis, minTime, maxTime);
                if (x2 - x1 < density * 0.8f) x2 = x1 + density * 0.8f;
                float y = yForRate(rate);

                boolean contiguous = runOpen
                        && previousEnd != Long.MIN_VALUE
                        && Math.abs(startMillis - previousEnd) <= 1000L;
                if (!contiguous) {
                    if (runOpen) {
                        areaPath.lineTo(previousEndX, plotBottom);
                        areaPath.close();
                    }
                    areaPath.moveTo(x1, plotBottom);
                    areaPath.lineTo(x1, y);
                    topPath.moveTo(x1, y);
                    runOpen = true;
                } else {
                    // A vertical/linear boundary transition connects only two real segment values.
                    areaPath.lineTo(x1, y);
                    topPath.lineTo(x1, y);
                }
                areaPath.lineTo(x2, y);
                topPath.lineTo(x2, y);
                previousEnd = endMillis;
                previousEndX = x2;
            }
            if (runOpen) {
                areaPath.lineTo(previousEndX, plotBottom);
                areaPath.close();
            }

            areaPaint.setShader(new LinearGradient(
                    0,
                    plotTop,
                    0,
                    plotBottom,
                    new int[]{
                            Color.argb(210, 125, 224, 255),
                            Color.argb(168, 55, 160, 246),
                            Color.argb(92, 31, 102, 211)},
                    new float[]{0f, 0.52f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawPath(areaPath, areaPaint);
            areaPaint.setShader(null);

            areaStrokePaint.setColor(Color.argb(225, 170, 235, 255));
            canvas.drawPath(topPath, areaStrokePaint);

            long nowMillis = Instant.now().toEpochMilli();
            if (nowMillis >= minTime && nowMillis <= maxTime) {
                float nowX = xForTime(nowMillis, minTime, maxTime);
                markerGlowPaint.setStrokeWidth(Math.max(density * 2.2f, dp(2)));
                markerGlowPaint.setColor(Color.argb(78, 255, 95, 50));
                canvas.drawLine(nowX, plotTop, nowX, plotBottom, markerGlowPaint);
                markerPaint.setStrokeWidth(Math.max(density, dp(1)));
                markerPaint.setColor(Color.argb(238, 255, 184, 42));
                canvas.drawLine(nowX, plotTop, nowX, plotBottom, markerPaint);

                String nowLabel = "Now";
                textPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                textPaint.setTextAlign(Paint.Align.CENTER);
                textPaint.setColor(Color.rgb(255, 205, 72));
                float labelHalfWidth = textPaint.measureText(nowLabel) * 0.5f + dp(4);
                float labelX = Math.max(plotLeft + labelHalfWidth,
                        Math.min(plotRight - labelHalfWidth, nowX));
                bandPaint.setColor(Color.argb(180, 34, 42, 55));
                canvas.drawRoundRect(
                        labelX - labelHalfWidth,
                        plotTop + dp(3),
                        labelX + labelHalfWidth,
                        plotTop + dp(18),
                        dp(7),
                        dp(7),
                        bandPaint);
                canvas.drawText(nowLabel, labelX, plotTop + dp(14), textPaint);
                textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            }

            if (selectedIndex >= 0 && selectedIndex < segments.size()) {
                MinuteSegment selected = segments.get(selectedIndex);
                float selectedX = xForTime(selectedTimeMillis, minTime, maxTime);
                markerGlowPaint.setStrokeWidth(dp(4));
                markerGlowPaint.setColor(Color.argb(86, 117, 228, 255));
                canvas.drawLine(selectedX, plotTop - dp(2), selectedX, plotBottom + dp(2), markerGlowPaint);
                markerPaint.setStrokeWidth(Math.max(density, dp(1.4f)));
                markerPaint.setColor(Color.argb(248, 246, 253, 255));
                canvas.drawLine(selectedX, plotTop - dp(2), selectedX, plotBottom + dp(2), markerPaint);

                Double selectedRate = minuteRateMmPerHour(selected);
                if (selectedRate != null) {
                    float cy = yForRate(selectedRate);
                    markerPaint.setStyle(Paint.Style.FILL);
                    markerPaint.setColor(Color.argb(250, 236, 251, 255));
                    canvas.drawCircle(selectedX, cy, dp(2.5f), markerPaint);
                    markerPaint.setStyle(Paint.Style.STROKE);
                }
            }
        }

        private void drawIntensityBands(Canvas canvas) {
            float lightTop = yForRate(PRECIP_LIGHT_MAX_MM_H);
            float moderateTop = yForRate(PRECIP_MODERATE_MAX_MM_H);

            bandPaint.setColor(Color.argb(12, 142, 226, 255));
            canvas.drawRect(plotLeft, lightTop, plotRight, plotBottom, bandPaint);
            bandPaint.setColor(Color.argb(17, 104, 198, 255));
            canvas.drawRect(plotLeft, moderateTop, plotRight, lightTop, bandPaint);
            bandPaint.setColor(Color.argb(22, 74, 152, 235));
            canvas.drawRect(plotLeft, plotTop, plotRight, moderateTop, bandPaint);

            gridPaint.setColor(Color.argb(62, 245, 250, 255));
            canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, gridPaint);
            canvas.drawLine(plotLeft, lightTop, plotRight, lightTop, gridPaint);
            canvas.drawLine(plotLeft, moderateTop, plotRight, moderateTop, gridPaint);

            textPaint.setColor(Color.argb(188, 255, 255, 255));
            textPaint.setTextAlign(Paint.Align.LEFT);
            float labelX = plotRight + dp(7);
            canvas.drawText("Light", labelX, (lightTop + plotBottom) * 0.5f + dp(3), textPaint);
            canvas.drawText("Moderate", labelX, (moderateTop + lightTop) * 0.5f + dp(3), textPaint);
            canvas.drawText("Heavy", labelX, (plotTop + moderateTop) * 0.5f + dp(3), textPaint);
        }

        private void drawTimeTicks(Canvas canvas) {
            long minTime = segments.get(0).start.toEpochMilli();
            long maxTime = segments.get(segments.size() - 1).end.toEpochMilli();
            if (maxTime <= minTime) maxTime = minTime + 1L;

            textPaint.setColor(Color.argb(190, 255, 255, 255));
            for (int i = 0; i < 4; i++) {
                float fraction = i / 3f;
                long time = minTime + Math.round((maxTime - minTime) * fraction);
                float x = plotLeft + (plotRight - plotLeft) * fraction;
                if (i == 0) textPaint.setTextAlign(Paint.Align.LEFT);
                else if (i == 3) textPaint.setTextAlign(Paint.Align.RIGHT);
                else textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(formatTime(Instant.ofEpochMilli(time), zone), x, dp(13), textPaint);

                gridPaint.setColor(Color.argb(50, 255, 255, 255));
                canvas.drawLine(x, plotTop - dp(4), x, plotTop, gridPaint);
            }
        }

        private float yForRate(double rateMmPerHour) {
            double capped = Math.max(0d, Math.min(PRECIP_VISUAL_CAP_MM_H, rateMmPerHour));
            double fraction = PRECIP_VISUAL_CAP_MM_H <= 0d
                    ? 0d : capped / PRECIP_VISUAL_CAP_MM_H;
            return plotBottom - (float) fraction * (plotBottom - plotTop);
        }

        private float xForTime(long value, long minTime, long maxTime) {
            if (maxTime <= minTime) return plotLeft;
            double fraction = (value - minTime) / (double) (maxTime - minTime);
            fraction = Math.max(0d, Math.min(1d, fraction));
            return plotLeft + (float) fraction * (plotRight - plotLeft);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (segments.isEmpty()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    requestFocus();
                    selectForX(event.getX(), false);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    selectForX(event.getX(), false);
                    return true;
                case MotionEvent.ACTION_UP:
                    selectForX(event.getX(), false);
                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private void selectForX(float x, boolean announce) {
            if (segments.isEmpty() || plotRight <= plotLeft) return;
            long minTime = segments.get(0).start.toEpochMilli();
            long maxTime = segments.get(segments.size() - 1).end.toEpochMilli();
            float fraction = Math.max(0f, Math.min(1f, (x - plotLeft) / (plotRight - plotLeft)));
            long target = minTime + Math.round((maxTime - minTime) * fraction);
            long nextTime = nearestSelectableTime(target);
            int nextIndex = segmentIndexForTime(nextTime);
            if (nextTime != selectedTimeMillis || nextIndex != selectedIndex) {
                selectedTimeMillis = nextTime;
                selectedIndex = nextIndex;
                dispatchSelection(announce);
                invalidate();
            }
        }

        private boolean moveSelection(int delta, boolean announce) {
            if (segments.isEmpty()) return false;
            long nextTime = steppedSelection(delta);
            if (nextTime == selectedTimeMillis) return false;
            selectedTimeMillis = nextTime;
            selectedIndex = segmentIndexForTime(selectedTimeMillis);
            dispatchSelection(announce);
            invalidate();
            return true;
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                moveSelection(-1, true);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                moveSelection(1, true);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_SPACE) {
                return performClick();
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            if (!segments.isEmpty()) dispatchSelection(true);
            return true;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.SeekBar");
            boolean canMoveBackward = steppedSelection(-1) != selectedTimeMillis;
            boolean canMoveForward = steppedSelection(1) != selectedTimeMillis;
            info.setScrollable(canMoveBackward || canMoveForward);
            if (canMoveBackward) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            }
            if (canMoveForward) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            }
        }

        @Override
        public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                return moveSelection(-1, true);
            }
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                return moveSelection(1, true);
            }
            return super.performAccessibilityAction(action, arguments);
        }

        private void dispatchSelection(boolean announce) {
            if (segments.isEmpty() || selectedIndex < 0) return;
            MinuteSegment segment = segments.get(Math.max(0, Math.min(selectedIndex, segments.size() - 1)));
            if (selectionListener != null) selectionListener.onSelected(segment, selectedTimeMillis);
            updateAccessibility(announce);
        }

        private void updateAccessibility(boolean announce) {
            if (segments.isEmpty()) {
                setContentDescription("Minute precipitation graph, no returned segments");
                return;
            }
            MinuteSegment segment = segments.get(Math.max(0, Math.min(selectedIndex, segments.size() - 1)));
            Instant selectedInstant = Instant.ofEpochMilli(selectedTimeMillis);
            String detail = minuteSelectionDetail(segment, selectedInstant, zone);
            String description = "Minute precipitation graph. " + detail
                    + ". Swipe or use left and right to move in two-minute steps.";
            setContentDescription(description);
            if (Build.VERSION.SDK_INT >= 30) {
                setStateDescription("Selected " + formatTime(selectedInstant, zone));
            }
            if (announce && isShown()) announceForAccessibility(detail);
        }
    }

}
