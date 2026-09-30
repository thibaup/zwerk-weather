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
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.function.IntConsumer;

@android.annotation.SuppressLint("ViewConstructor")
final class HourlyDayChartView extends ViewGroup {
    private final double[] temperatures;
    private final double[] precipitation;
    private final String[] times;
    private final String[] temperatureLabels;
    private final String[] rainLabels;
    private final String[] keys;
    private final IntConsumer selectionChanged;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curve = new Path();
    private final float density;
    private final float textScale;
    private final boolean hasRain;
    private final double minimum;
    private final double maximum;
    private final double maximumRain;
    private int selectedHour = -1;

    HourlyDayChartView(Context context, double[] temperatures, double[] precipitation,
            String[] times, String[] temperatureLabels, String[] rainLabels, String[] descriptions,
            String[] keys, IntConsumer hourClicked, IntConsumer selectionChanged) {
        super(context);
        this.temperatures = temperatures;
        this.precipitation = precipitation;
        this.times = times;
        this.temperatureLabels = temperatureLabels;
        this.rainLabels = rainLabels;
        this.keys = keys;
        this.selectionChanged = selectionChanged;
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

    void syncPreview(String key) {
        int next = -1;
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
        int desiredHeight = Math.round(dp(hasRain ? 194 : 156) + dp(30) * (fontScale - 1));
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
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
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

    private void drawLabel(Canvas canvas, String text, float x, float y) {
        float half = paint.measureText(text) / 2f;
        canvas.drawText(text, Math.max(half + dp(2), Math.min(getWidth() - half - dp(2), x)), y, paint);
    }

    private float temperatureY(double temperature, float top, float bottom) {
        return bottom - (float) ((temperature - minimum) / (maximum - minimum)) * (bottom - top);
    }

    private float dp(float value) { return value * density; }
}
