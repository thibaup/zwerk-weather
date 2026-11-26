package com.zwerk.weather;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.function.IntConsumer;

// This chart is created in code with the hourly data and selection callbacks it needs.
@android.annotation.SuppressLint("ViewConstructor")
final class HourlyDayChartView extends ViewGroup {
    private final double[] temperatures;
    private final double[] precipitation;
    private final String[] times;
    private final String[] temperatureLabels;
    private final String[] rainLabels;
    private final String[] keys;
    private final int defaultHour;
    private final IntConsumer selectionChanged;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface normalTypeface = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Path curve = new Path();
    private final float density;
    private final float textScale;
    private final boolean hasRain;
    private final double minimum;
    private final double maximum;
    private final double maximumRain;
    private final float[] pointXs;
    private final float[] pointYs;
    private final float[] precipFractions;
    private boolean combinedOverlay;
    private boolean useInches;
    private String precipitationStyle = CombinedForecastData.PRECIP_STYLE_LINE;
    private int selectedHour = -1;
    private final IntConsumer hourClicked;
    private final int touchSlop;
    private float downX;
    private float downY;
    private boolean scrubbing;
    private boolean verticalGesture;
    private boolean ignoreGesture;

    HourlyDayChartView(Context context, double[] temperatures, double[] precipitation,
            String[] times, String[] temperatureLabels, String[] rainLabels, String[] descriptions,
            String[] keys, int defaultHour, IntConsumer hourClicked, IntConsumer selectionChanged) {
        super(context);
        this.temperatures = temperatures;
        this.precipitation = precipitation;
        this.times = times;
        this.temperatureLabels = temperatureLabels;
        this.rainLabels = rainLabels;
        this.keys = keys;
        this.defaultHour = defaultHour;
        this.selectionChanged = selectionChanged;
        this.hourClicked = hourClicked;
        this.pointXs = new float[temperatures.length];
        this.pointYs = new float[temperatures.length];
        this.precipFractions = new float[temperatures.length];
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        density = getResources().getDisplayMetrics().density;
        textScale = getResources().getDisplayMetrics().scaledDensity;
        double low = Double.POSITIVE_INFINITY;
        double high = Double.NEGATIVE_INFINITY;
        double rain = 0;
        for (int i = 0; i < temperatures.length; i++) {
            if (Double.isFinite(temperatures[i])) {
                low = Math.min(low, temperatures[i]);
                high = Math.max(high, temperatures[i]);
            }
            if (Double.isFinite(precipitation[i])) rain = Math.max(rain, precipitation[i]);
            final int hour = i;
            View target = new View(context) {
                @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(info);
                    info.setClassName("android.widget.Button");
                }
            };
            target.setClickable(true);
            target.setFocusable(true);
            target.setContentDescription(descriptions[i]);
            StateListDrawable highlight = new StateListDrawable();
            highlight.addState(new int[] {android.R.attr.state_pressed},
                    new ColorDrawable(Color.argb(22, 255, 255, 255)));
            highlight.addState(new int[] {android.R.attr.state_focused},
                    new ColorDrawable(Color.argb(18, 255, 255, 255)));
            highlight.addState(new int[] {}, new ColorDrawable(Color.TRANSPARENT));
            target.setBackground(highlight);
            target.setOnClickListener(v -> hourClicked.accept(hour));
            addView(target);
        }
        if (!Double.isFinite(low)) { low = 0; high = 2; }
        double padding = Math.max(1, (high - low) * .15);
        minimum = low - padding;
        maximum = high + padding;
        maximumRain = rain;
        hasRain = rain > 0;
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    boolean hasRain() { return hasRain; }

    void setCombinedOverlay(boolean enabled, String precipitationStyle) {
        setCombinedOverlay(enabled, precipitationStyle, inferInchesFromLabels());
    }

    void setCombinedOverlay(boolean enabled, String precipitationStyle, boolean useInches) {
        String normalized = precipitationStyle == null || precipitationStyle.trim().isEmpty()
                ? CombinedForecastData.PRECIP_STYLE_LINE
                : CombinedForecastData.normalizePrecipitationStyle(precipitationStyle);
        boolean modeChanged = combinedOverlay != enabled;
        boolean styleChanged = !normalized.equals(this.precipitationStyle);
        boolean unitChanged = this.useInches != useInches;
        if (!modeChanged && !styleChanged && !unitChanged) return;
        combinedOverlay = enabled;
        this.precipitationStyle = normalized;
        this.useInches = useInches;
        if (modeChanged) requestLayout();
        invalidate();
    }

    private boolean inferInchesFromLabels() {
        if (rainLabels == null) return false;
        for (String label : rainLabels) {
            if (label != null && label.toLowerCase(java.util.Locale.ROOT).contains("in")) {
                return true;
            }
        }
        return false;
    }

    private static double hourlyRainScaleMax(double peak, boolean imperial) {
        double value = Double.isFinite(peak) ? Math.max(0d, peak) : 0d;
        if (imperial) {
            if (value <= 0.2d) return 0.2d;
            if (value <= 0.4d) return 0.4d;
            if (value <= 0.6d) return 0.6d;
            if (value <= 1.0d) return 1.0d;
            return Math.ceil(value * 2d) / 2d;
        }
        if (value <= 5.0d) return 5.0d;
        if (value <= 10.0d) return 10.0d;
        if (value <= 15.0d) return 15.0d;
        if (value <= 20.0d) return 20.0d;
        return Math.ceil(value / 5.0d) * 5.0d;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            scrubbing = false;
            verticalGesture = false;
            ignoreGesture = false;
            // Keep the first move so we can distinguish scrubbing from page scrolling.
            disallowParentIntercept(true);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            ignoreGesture = true;
        }
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            scrubbing = false;
            disallowParentIntercept(false);
        }
        return handled;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (ignoreGesture) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !verticalGesture) {
            float dx = Math.abs(event.getX() - downX);
            float dy = Math.abs(event.getY() - downY);
            if (!scrubbing && Math.max(dx, dy) > touchSlop) {
                scrubbing = dx > dy;
                verticalGesture = !scrubbing;
                if (verticalGesture) disallowParentIntercept(false);
                // This MOVE cancels the child target; Android only routes later
                // events to onTouchEvent, so update the first scrub here as well.
                if (scrubbing) scrubTo(event.getX());
            }
        }
        // Cancel a pending child click even when no further MOVE arrives before UP.
        return scrubbing || verticalGesture;
    }

    // Only drags reach the chart itself. Taps remain clicks on its accessible hour children.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (ignoreGesture || verticalGesture) return true;
        int action = event.getActionMasked();
        if (scrubbing && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)) {
            scrubTo(event.getX());
            return true;
        }
        return scrubbing || super.onTouchEvent(event);
    }

    private void scrubTo(float x) {
        int count = getChildCount();
        if (count == 0) return;
        int hour = Math.max(0, Math.min(count - 1,
                (int) (x * count / Math.max(1, getWidth()))));
        // The preview callback toggles on a second selection. A drag must keep
        // the current hour selected, including when returning to a previous hour.
        if (hour != selectedHour) hourClicked.accept(hour);
    }

    private void disallowParentIntercept(boolean disallow) {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(disallow);
    }

    void syncPreview(String key) {
        int next = defaultHour;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(key)) { next = i; break; }
        }
        if (next == selectedHour) return;
        selectedHour = next;
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).setSelected(i == next);
        invalidate();
        selectionChanged.accept(next);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        float fontScale = Math.max(1f, textScale / density);
        int desiredWidth = Math.round(dp(336));
        float baseHeightDp = combinedOverlay ? 202 : (hasRain ? 194 : 156);
        int desiredHeight = Math.round(dp(baseHeightDp) + dp(30) * (fontScale - 1));
        int width = resolveSize(desiredWidth, widthSpec);
        int height = resolveSize(desiredHeight, heightSpec);
        int columnWidth = Math.max(1, width / Math.max(1, getChildCount()));
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(columnWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        float column = getWidth() / (float) Math.max(1, getChildCount());
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).layout(Math.round(i * column), 0,
                    Math.round((i + 1) * column), getHeight());
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (temperatures.length == 0) return;
        if (combinedOverlay) {
            drawCombinedOverlay(canvas);
            return;
        }
        paint.clearShadowLayer();
        float column = getWidth() / (float) temperatures.length;
        float curveTop = dp(28);
        float curveBottom = getHeight() - dp(hasRain ? 82 : 40);
        float rainBottom = getHeight() - dp(31);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(18, 255, 255, 255));
        if (selectedHour >= 0) canvas.drawRoundRect(selectedHour * column, dp(3),
                (selectedHour + 1) * column, getHeight() - dp(3), dp(12), dp(12), paint);
        paint.setColor(Color.argb(24, 255, 255, 255));
        paint.setStrokeWidth(dp(.7f));
        for (int i = 0; i < 3; i++) {
            float y = curveTop + (curveBottom - curveTop) * i / 2f;
            canvas.drawLine(0, y, getWidth(), y, paint);
        }

        curve.reset();
        boolean connected = false;
        for (int i = 0; i < temperatures.length; i++) {
            if (!Double.isFinite(temperatures[i])) { connected = false; continue; }
            float x = (i + .5f) * column;
            float y = temperatureY(temperatures[i], curveTop, curveBottom);
            if (connected) curve.lineTo(x, y); else curve.moveTo(x, y);
            connected = true;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.2f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(Color.argb(235, 255, 255, 255));
        canvas.drawPath(curve, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(normalTypeface);
        int labelStride = Math.max(1, (int) Math.ceil(temperatures.length
                / Math.max(1f, getWidth() / (48 * textScale))));
        int peakRain = 0;
        for (int i = 1; i < precipitation.length; i++) {
            if (Double.isFinite(precipitation[i])
                    && (!Double.isFinite(precipitation[peakRain])
                    || precipitation[i] > precipitation[peakRain])) peakRain = i;
        }
        for (int i = 0; i < temperatures.length; i++) {
            float x = (i + .5f) * column;
            boolean label = i == selectedHour
                    || (i % labelStride == 0 && (i == 0 || i < temperatures.length - labelStride - 1)
                    || i == temperatures.length - 1)
                    && (selectedHour < 0 || Math.abs(i - selectedHour) * column > 44 * textScale);
            if (Double.isFinite(temperatures[i])) {
                float y = temperatureY(temperatures[i], curveTop, curveBottom);
                paint.setColor(Color.WHITE);
                canvas.drawCircle(x, y, dp(i == selectedHour ? 4.5f : 2.3f), paint);
                paint.setTextSize(12 * textScale);
                if (label) drawLabel(canvas, temperatureLabels[i], x, y - dp(11));
            }
            if (hasRain && Double.isFinite(precipitation[i]) && precipitation[i] > 0) {
                float barHeight = (float) (dp(29) * precipitation[i] / maximumRain);
                paint.setColor(Color.argb(210, 112, 203, 255));
                float halfBar = Math.min(dp(9), column * .31f);
                canvas.drawRoundRect(x - halfBar, rainBottom - Math.max(dp(2), barHeight),
                        x + halfBar, rainBottom, dp(3), dp(3), paint);
                paint.setTextSize(10 * textScale);
                if (i == selectedHour || i == peakRain
                        && (selectedHour < 0 || Math.abs(i - selectedHour) * column > 44 * textScale)) {
                    drawLabel(canvas, rainLabels[i], x, rainBottom - barHeight - dp(5));
                }
            }
            paint.setColor(Color.argb(220, 255, 255, 255));
            paint.setTextSize(12 * textScale);
            if (label) drawLabel(canvas, times[i], x, getHeight() - dp(10));
        }
    }

    private void drawCombinedOverlay(Canvas canvas) {
        int count = temperatures.length;
        float width = getWidth();
        float height = getHeight();
        float column = width / (float) count;
        float curveTop = dp(28);
        float bottomReserve = Math.max(dp(38), 24 * textScale + dp(14));
        float curveBottom = Math.max(curveTop + dp(24), height - bottomReserve);
        float plotHeight = curveBottom - curveTop;

        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(18, 255, 255, 255));
        if (selectedHour >= 0 && selectedHour < count) {
            canvas.drawRoundRect(selectedHour * column, dp(3),
                    (selectedHour + 1) * column, height - dp(3), dp(12), dp(12), paint);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.7f));
        paint.setColor(Color.argb(24, 255, 255, 255));
        canvas.drawLine(0, curveTop, width, curveTop, paint);
        canvas.drawLine(0, curveTop + plotHeight * 0.5f, width, curveTop + plotHeight * 0.5f, paint);
        canvas.drawLine(0, curveBottom, width, curveBottom, paint);

        double rainCeiling = hourlyRainScaleMax(maximumRain, useInches);
        for (int i = 0; i < count; i++) {
            pointXs[i] = (i + 0.5f) * column;
            if (hasRain && rainCeiling > 0d && i < precipitation.length
                    && Double.isFinite(precipitation[i]) && precipitation[i] >= 0d) {
                precipFractions[i] = (float) Math.max(0d, Math.min(1d, precipitation[i] / rainCeiling));
            } else {
                precipFractions[i] = Float.NaN;
            }
        }

        // Layer 1 (behind): thin blue precipitation line or columns directly in the same plot area.
        float precipTop = curveTop + plotHeight * 0.28f;
        if (hasRain && rainCeiling > 0d) {
            if (CombinedForecastData.PRECIP_STYLE_LINE.equals(precipitationStyle)) {
                CombinedForecastWidgetChart.drawPrecipitationLine(
                        canvas, pointXs, precipFractions, count, precipTop, curveBottom,
                        Math.max(1f, dp(1.25f)), 0f);
            } else {
                float barWidth = Math.max(dp(4), Math.min(dp(18), column * 0.62f));
                CombinedForecastWidgetChart.drawPrecipitationColumns(
                        canvas, pointXs, precipFractions, count, precipTop, curveBottom, barWidth, dp(3));
            }
        }

        // Layer 2 (on top): temperature evolution curve identical to original HourlyDayChartView.
        curve.reset();
        boolean connected = false;
        float prevX = 0f;
        float prevY = 0f;
        for (int i = 0; i < count; i++) {
            if (!Double.isFinite(temperatures[i])) {
                connected = false;
                pointYs[i] = Float.NaN;
                continue;
            }
            float x = pointXs[i];
            float y = temperatureY(temperatures[i], curveTop, curveBottom);
            pointYs[i] = y;
            if (connected) {
                float mid = (prevX + x) * 0.5f;
                curve.cubicTo(mid, prevY, mid, y, x, y);
            } else {
                curve.moveTo(x, y);
            }
            prevX = x;
            prevY = y;
            connected = true;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.2f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(Color.argb(235, 255, 255, 255));
        canvas.drawPath(curve, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(normalTypeface);
        int labelStride = Math.max(1, (int) Math.ceil(count
                / Math.max(1f, width / (56 * textScale))));
        int peakRain = 0;
        for (int i = 1; i < precipitation.length; i++) {
            if (Double.isFinite(precipitation[i])
                    && (!Double.isFinite(precipitation[peakRain])
                    || precipitation[i] > precipitation[peakRain])) {
                peakRain = i;
            }
        }

        float timeLabelY = height - dp(10);
        float rainLabelY = Math.min(curveBottom + 10 * textScale + dp(2),
                timeLabelY - 12 * textScale - dp(2));
        for (int i = 0; i < count; i++) {
            float x = pointXs[i];
            boolean label = i == selectedHour
                    || (i % labelStride == 0 && (i == 0 || i < count - labelStride - 1)
                    || i == count - 1)
                    && (selectedHour < 0 || Math.abs(i - selectedHour) * column > 52 * textScale);

            if (Double.isFinite(temperatures[i])) {
                float y = pointYs[i];
                paint.setColor(Color.WHITE);
                canvas.drawCircle(x, y, dp(i == selectedHour ? 4.5f : 2.3f), paint);
                if (label) {
                    paint.setTextSize(12 * textScale);
                    float tempY = Math.max(12 * textScale + dp(3), y - dp(11));
                    drawLabel(canvas, temperatureLabels[i], x, tempY);
                }
            }

            if (hasRain && i < precipitation.length && i < rainLabels.length
                    && Double.isFinite(precipitation[i]) && precipitation[i] > 0d
                    && rainLabels[i] != null && !rainLabels[i].isEmpty()
                    && (i == selectedHour || (i == peakRain
                    && (selectedHour < 0 || Math.abs(i - selectedHour) * column > 52 * textScale)))) {
                paint.setColor(Color.argb(210, 112, 203, 255));
                paint.setTextSize(10 * textScale);
                drawLabel(canvas, rainLabels[i], x, rainLabelY);
            }

            if (label) {
                paint.setColor(Color.argb(220, 255, 255, 255));
                paint.setTextSize(12 * textScale);
                drawLabel(canvas, times[i], x, timeLabelY);
            }
        }
    }

    private void drawLabel(Canvas canvas, String text, float x, float y) {
        float half = paint.measureText(text) / 2f;
        canvas.drawText(text, Math.max(half + dp(2), Math.min(getWidth() - half - dp(2), x)), y, paint);
    }

    private float temperatureY(double temperature, float top, float bottom) {
        return bottom - (float) ((temperature - minimum) / (maximum - minimum)) * (bottom - top);
    }

    private float dp(float value) { return value * density; }
}
