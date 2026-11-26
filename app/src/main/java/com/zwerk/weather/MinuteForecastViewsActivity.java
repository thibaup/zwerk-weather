package com.zwerk.weather;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;



import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


abstract class MinuteForecastViewsActivity extends ForecastViewsActivity {
    final class MinutePrecipitationGraphView extends View {
        private final ArrayList<MinuteSegment> segments = new ArrayList<>();
        private final ZoneId zone;
        private final long windowStartMillis;
        private final long windowEndMillis;
        private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint areaPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint areaStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint markerGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tempLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tempDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tempLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path areaPath = new Path();
        private final Path topPath = new Path();
        private final Path runAreaPath = new Path();
        private final Typeface normalTypeface = Typeface.create("sans-serif", Typeface.NORMAL);
        private final Typeface boldTypeface = Typeface.create("sans-serif", Typeface.BOLD);
        private LinearGradient areaGradient;
        private boolean compactCombinedLayout;
        private boolean useInches;
        private double currentRateCapMmPerHour = PRECIP_VISUAL_CAP_MM_H;
        private MinuteSelectionListener selectionListener;
        private int selectedIndex;
        private long selectedTimeMillis = Long.MIN_VALUE;
        private float plotLeft;
        private float plotRight;
        private float plotTop;
        private float plotBottom;
        private final int touchSlop;
        private float touchDownX, touchDownY;
        private int touchAxis;

        // Reusable scratch buffers for smoothed precipitation runs.
        private float[] segStartX = new float[32];
        private float[] segEndX = new float[32];
        private float[] segY = new float[32];
        private float[] precipRunX = new float[34];
        private float[] precipRunY = new float[34];

        // Optional overlaid temperature evolution series.
        private long[] tempEpochs;
        private double[] tempValues;
        private String[] tempLabels;
        private String tempUnitSymbol = "";
        private int tempCount;

        // Reusable scratch buffers for temperature overlay rendering.
        private float[] tempVisX = new float[32];
        private float[] tempVisY = new float[32];
        private double[] tempVisVal = new double[32];
        private boolean[] tempVisHourly = new boolean[32];
        private String[] tempVisLabel = new String[32];
        private int[] tempRunStarts = new int[8];
        private int[] tempRunLengths = new int[8];
        private float[] tempSliceX = new float[32];
        private float[] tempSliceY = new float[32];
        private float[] placedLabelCenterX = new float[32];
        private float[] placedLabelLeft = new float[32];
        private float[] placedLabelTop = new float[32];
        private float[] placedLabelRight = new float[32];
        private float[] placedLabelBottom = new float[32];

        MinutePrecipitationGraphView(Context context, List<MinuteSegment> source, ZoneId zone,
                Instant windowStart, Instant windowEnd) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            useInches = new WeatherPreferences(context).isPrecipitationInches();
            if (source != null) segments.addAll(source);
            this.zone = zone == null ? ZoneId.systemDefault() : zone;
            windowStartMillis = windowStart.toEpochMilli();
            windowEndMillis = windowEnd.toEpochMilli();
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);
            setMinimumHeight(dp(190));
            setMinimumWidth(dp(48));

            float density = getResources().getDisplayMetrics().density;
            float scaledDensity = getResources().getDisplayMetrics().scaledDensity;

            bandPaint.setStyle(Paint.Style.FILL);
            gridPaint.setStyle(Paint.Style.STROKE);
            gridPaint.setStrokeWidth(Math.max(1f, density * 0.7f));
            areaPaint.setStyle(Paint.Style.FILL);
            areaStrokePaint.setStyle(Paint.Style.STROKE);
            areaStrokePaint.setStrokeWidth(Math.max(1f, density * 1.25f));
            areaStrokePaint.setStrokeJoin(Paint.Join.ROUND);
            areaStrokePaint.setStrokeCap(Paint.Cap.ROUND);
            markerPaint.setStyle(Paint.Style.STROKE);
            markerGlowPaint.setStyle(Paint.Style.STROKE);

            tempLinePaint.setStyle(Paint.Style.STROKE);
            tempLinePaint.setStrokeWidth(density * 2.2f);
            tempLinePaint.setStrokeJoin(Paint.Join.ROUND);
            tempLinePaint.setStrokeCap(Paint.Cap.ROUND);
            tempLinePaint.setColor(Color.argb(235, 255, 255, 255));

            tempDotPaint.setStyle(Paint.Style.FILL);
            tempDotPaint.setColor(Color.WHITE);

            tempLabelPaint.setTypeface(normalTypeface);
            tempLabelPaint.setTextAlign(Paint.Align.CENTER);
            tempLabelPaint.setTextSize(12f * scaledDensity);
            tempLabelPaint.setColor(Color.WHITE);

            textPaint.setTypeface(normalTypeface);
            textPaint.setTextSize(10f * scaledDensity);
            selectedTimeMillis = nearestSelectableTime(Instant.now().toEpochMilli());
            selectedIndex = segmentIndexForTime(selectedTimeMillis);
            updateAccessibility(false);
        }

        void setCompactCombinedLayout(boolean enabled) {
            if (compactCombinedLayout == enabled) return;
            compactCombinedLayout = enabled;
            if (getHeight() > 0) updateAreaGradient(getHeight());
            requestLayout();
            invalidate();
        }

        void setUseInches(boolean inches) {
            if (useInches == inches) return;
            useInches = inches;
            updateAccessibility(false);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (!compactCombinedLayout) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                return;
            }
            float density = getResources().getDisplayMetrics().density;
            float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
            float fontScale = Math.max(1f, scaledDensity / Math.max(0.1f, density));
            int desiredWidth = Math.round(336f * density);
            int desiredHeight = Math.round(202f * density + 30f * density * (fontScale - 1f));
            setMeasuredDimension(
                    resolveSize(desiredWidth, widthMeasureSpec),
                    resolveSize(desiredHeight, heightMeasureSpec));
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            updateAreaGradient(h);
        }

        private void updateAreaGradient(int h) {
            float top = compactCombinedLayout ? dp(28) : dp(30);
            float bottom = compactCombinedLayout
                    ? Math.max(top + dp(40), h - dp(38))
                    : Math.max(top + dp(100), h - dp(12));
            areaGradient = new LinearGradient(
                    0, top, 0, bottom,
                    new int[]{
                            Color.argb(210, 125, 224, 255),
                            Color.argb(168, 55, 160, 246),
                            Color.argb(92, 31, 102, 211)},
                    new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP);
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

        void setTemperatureSeries(long[] epochsMillis, double[] temperatures,
                String[] labels, String unitSymbol) {
            tempUnitSymbol = unitSymbol == null ? "" : unitSymbol;
            if (epochsMillis == null || temperatures == null) {
                clearTemperatureSeries();
                return;
            }
            int rawCount = Math.min(epochsMillis.length, temperatures.length);
            if (rawCount <= 0) {
                clearTemperatureSeries();
                return;
            }

            long[] sortedEpochs = new long[rawCount];
            double[] sortedValues = new double[rawCount];
            String[] sortedLabels = new String[rawCount];
            int count = 0;
            for (int i = 0; i < rawCount; i++) {
                long epoch = epochsMillis[i];
                double temp = temperatures[i];
                String label = labels != null && i < labels.length && labels[i] != null
                        ? labels[i].trim() : "";
                int pos = count;
                while (pos > 0 && sortedEpochs[pos - 1] > epoch) pos--;
                if (pos > 0 && sortedEpochs[pos - 1] == epoch) {
                    if (Double.isFinite(temp) || !Double.isFinite(sortedValues[pos - 1])) {
                        sortedValues[pos - 1] = temp;
                        sortedLabels[pos - 1] = label;
                    }
                    continue;
                }
                if (pos < count) {
                    System.arraycopy(sortedEpochs, pos, sortedEpochs, pos + 1, count - pos);
                    System.arraycopy(sortedValues, pos, sortedValues, pos + 1, count - pos);
                    System.arraycopy(sortedLabels, pos, sortedLabels, pos + 1, count - pos);
                }
                sortedEpochs[pos] = epoch;
                sortedValues[pos] = temp;
                sortedLabels[pos] = label;
                count++;
            }

            tempEpochs = sortedEpochs;
            tempValues = sortedValues;
            tempLabels = sortedLabels;
            tempCount = count;
            if (selectionListener != null) dispatchSelection(false);
            else updateAccessibility(false);
            invalidate();
        }

        private void clearTemperatureSeries() {
            tempEpochs = null;
            tempValues = null;
            tempLabels = null;
            tempCount = 0;
            if (selectionListener != null) dispatchSelection(false);
            else updateAccessibility(false);
            invalidate();
        }

        Double temperatureAt(long epochMillis) {
            if (tempCount <= 0 || tempEpochs == null || tempValues == null) return null;
            if (epochMillis < tempEpochs[0] || epochMillis > tempEpochs[tempCount - 1]) return null;
            if (tempCount == 1) {
                return epochMillis == tempEpochs[0] && Double.isFinite(tempValues[0])
                        ? tempValues[0] : null;
            }
            int lo = 0;
            int hi = tempCount - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                long midEpoch = tempEpochs[mid];
                if (midEpoch == epochMillis) {
                    return Double.isFinite(tempValues[mid]) ? tempValues[mid] : null;
                }
                if (midEpoch < epochMillis) lo = mid + 1;
                else hi = mid - 1;
            }
            if (hi < 0 || lo >= tempCount) return null;
            double v0 = tempValues[hi];
            double v1 = tempValues[lo];
            if (!Double.isFinite(v0) || !Double.isFinite(v1)) return null;
            long span = tempEpochs[lo] - tempEpochs[hi];
            if (span <= 0L) return v0;
            double fraction = (epochMillis - tempEpochs[hi]) / (double) span;
            double interpolated = v0 + (v1 - v0) * fraction;
            return Double.isFinite(interpolated) ? interpolated : null;
        }

        private int segmentIndexForTime(long epochMillis) {
            if (epochMillis < windowStartMillis || epochMillis >= windowEndMillis) return -1;
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
            long first = Math.max(windowStartMillis, segments.get(0).start.toEpochMilli());
            long lastExclusive = Math.min(windowEndMillis, segments.get(segments.size() - 1).end.toEpochMilli());
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
            long first = Math.max(windowStartMillis, segments.get(0).start.toEpochMilli());
            long last = Math.min(windowEndMillis, segments.get(segments.size() - 1).end.toEpochMilli()) - 1L;
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
            float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
            plotLeft = dp(8);
            if (compactCombinedLayout) {
                plotRight = Math.max(plotLeft + dp(100), getWidth() - dp(8));
                plotTop = dp(28);
                plotBottom = Math.max(plotTop + dp(40), getHeight() - dp(38));
            } else {
                plotRight = Math.max(plotLeft + dp(100), getWidth() - dp(70));
                plotTop = dp(30);
                plotBottom = Math.max(plotTop + dp(100), getHeight() - dp(12));
            }

            long minTime = windowStartMillis;
            long maxTime = windowEndMillis;
            if (maxTime <= minTime) maxTime = minTime + 1L;

            String scaleCaption = "";
            float scaleCaptionLeft = Float.POSITIVE_INFINITY;
            if (compactCombinedLayout) {
                double peakMm = peakVisibleRateMmPerHour(minTime, maxTime);
                if (useInches) {
                    double peakIn = peakMm / 25.4d;
                    double capIn = peakIn <= 0.2d ? 0.2d
                            : peakIn <= 0.4d ? 0.4d
                            : peakIn <= 0.6d ? 0.6d
                            : Math.ceil(peakIn * 5d) / 5d;
                    currentRateCapMmPerHour = capIn * 25.4d;
                    scaleCaption = formatScaleCeiling(capIn) + " "
                            + UiTranslations.text(getContext(), "in/h");
                } else {
                    double capMm = peakMm <= 5.0d ? 5.0d
                            : peakMm <= 10.0d ? 10.0d
                            : peakMm <= 15.0d ? 15.0d
                            : Math.ceil(peakMm / 5.0d) * 5.0d;
                    currentRateCapMmPerHour = capMm;
                    scaleCaption = formatScaleCeiling(capMm) + " "
                            + UiTranslations.text(getContext(), "mm/h");
                }
            } else {
                currentRateCapMmPerHour = useInches ? 0.6d * 25.4d : PRECIP_VISUAL_CAP_MM_H;
            }

            drawIntensityBands(canvas, tempCount > 0);
            drawTimeTicks(canvas, scaledDensity);

            if (compactCombinedLayout && !scaleCaption.isEmpty()) {
                textPaint.setTypeface(normalTypeface);
                textPaint.setTextSize(10f * scaledDensity);
                textPaint.setTextAlign(Paint.Align.RIGHT);
                textPaint.setColor(Color.argb(128, 176, 226, 255));
                float captionRight = plotRight - dp(2);
                scaleCaptionLeft = captionRight - textPaint.measureText(scaleCaption);
                canvas.drawText(scaleCaption, captionRight, plotTop + dp(12), textPaint);
            }

            boolean hasSelection = selectedIndex >= 0 && selectedIndex < segments.size();
            float selectedX = hasSelection ? xForTime(selectedTimeMillis, minTime, maxTime) : Float.NaN;
            float selectedRainY = drawSmoothedPrecipitation(canvas, minTime, maxTime, density,
                    hasSelection, selectedX);

            long nowMillis = Instant.now().toEpochMilli();
            boolean hasNowMarker = nowMillis >= minTime && nowMillis <= maxTime;
            float nowX = 0f;
            float nowLabelX = 0f;
            float nowBadgeLeft = 0f;
            float nowBadgeTop = 0f;
            float nowBadgeRight = 0f;
            float nowBadgeBottom = 0f;
            String nowLabel = "";
            if (hasNowMarker) {
                nowX = xForTime(nowMillis, minTime, maxTime);
                markerGlowPaint.setStrokeWidth(Math.max(density * 2.2f, dp(2)));
                markerGlowPaint.setColor(Color.argb(78, 255, 95, 50));
                canvas.drawLine(nowX, plotTop, nowX, plotBottom, markerGlowPaint);
                markerPaint.setStrokeWidth(Math.max(density, dp(1)));
                markerPaint.setColor(Color.argb(238, 255, 184, 42));
                canvas.drawLine(nowX, plotTop, nowX, plotBottom, markerPaint);

                nowLabel = UiTranslations.text(getContext(), "Now");
                textPaint.setTextSize(10f * scaledDensity);
                textPaint.setTypeface(boldTypeface);
                textPaint.setTextAlign(Paint.Align.CENTER);
                float labelHalfWidth = textPaint.measureText(nowLabel) * 0.5f + dp(4);
                nowLabelX = Math.max(plotLeft + labelHalfWidth,
                        Math.min(plotRight - labelHalfWidth, nowX));
                nowBadgeLeft = nowLabelX - labelHalfWidth;
                nowBadgeTop = plotTop + dp(3);
                nowBadgeRight = nowLabelX + labelHalfWidth;
                nowBadgeBottom = plotTop + dp(18);
                textPaint.setTypeface(normalTypeface);
            }

            float selectedTempY = drawTemperatureOverlay(canvas, minTime, maxTime, density,
                    scaledDensity, hasNowMarker, nowBadgeLeft, nowBadgeTop, nowBadgeRight,
                    nowBadgeBottom, scaleCaptionLeft, hasSelection, selectedX);

            if (hasNowMarker) {
                textPaint.setTextSize(10f * scaledDensity);
                textPaint.setTypeface(boldTypeface);
                textPaint.setTextAlign(Paint.Align.CENTER);
                textPaint.setColor(Color.rgb(255, 205, 72));
                bandPaint.setColor(Color.argb(180, 34, 42, 55));
                canvas.drawRoundRect(
                        nowBadgeLeft,
                        nowBadgeTop,
                        nowBadgeRight,
                        nowBadgeBottom,
                        dp(7),
                        dp(7),
                        bandPaint);
                canvas.drawText(nowLabel, nowLabelX, plotTop + dp(14), textPaint);
                textPaint.setTypeface(normalTypeface);
            }

            if (hasSelection) {
                MinuteSegment selected = segments.get(selectedIndex);
                markerGlowPaint.setStrokeWidth(dp(4));
                markerGlowPaint.setColor(Color.argb(86, 117, 228, 255));
                canvas.drawLine(selectedX, plotTop - dp(2), selectedX, plotBottom + dp(2), markerGlowPaint);
                markerPaint.setStrokeWidth(Math.max(density, dp(1.4f)));
                markerPaint.setColor(Color.argb(248, 246, 253, 255));
                canvas.drawLine(selectedX, plotTop - dp(2), selectedX, plotBottom + dp(2), markerPaint);

                Double selectedRate = minuteRateMmPerHour(selected);
                if (selectedRate != null) {
                    float cy = Float.isFinite(selectedRainY) ? selectedRainY : yForRate(selectedRate);
                    markerPaint.setStyle(Paint.Style.FILL);
                    markerPaint.setColor(Color.argb(250, 236, 251, 255));
                    canvas.drawCircle(selectedX, cy, Math.max(2f, density * 2.5f), markerPaint);
                    markerPaint.setStyle(Paint.Style.STROKE);
                }

                if (Float.isFinite(selectedTempY)) {
                    tempDotPaint.setColor(Color.WHITE);
                    canvas.drawCircle(selectedX, selectedTempY, density * 4.5f, tempDotPaint);
                }
            }
        }

        private float drawSmoothedPrecipitation(Canvas canvas, long minTime, long maxTime,
                float density, boolean hasSelection, float selectedX) {
            areaPath.reset();
            topPath.reset();
            float selectedRainY = Float.NaN;
            int runLen = 0;
            long previousEnd = Long.MIN_VALUE;

            for (int i = 0; i < segments.size(); i++) {
                MinuteSegment segment = segments.get(i);
                Double rate = minuteRateMmPerHour(segment);
                if (rate == null) {
                    if (runLen > 0) {
                        selectedRainY = flushPrecipitationRun(runLen, density, hasSelection,
                                selectedX, selectedRainY);
                        runLen = 0;
                    }
                    previousEnd = Long.MIN_VALUE;
                    continue;
                }

                long startMillis = segment.start.toEpochMilli();
                long endMillis = segment.end.toEpochMilli();
                if (endMillis <= minTime || startMillis >= maxTime) {
                    if (runLen > 0) {
                        selectedRainY = flushPrecipitationRun(runLen, density, hasSelection,
                                selectedX, selectedRainY);
                        runLen = 0;
                    }
                    previousEnd = Long.MIN_VALUE;
                    continue;
                }

                boolean contiguous = runLen > 0
                        && previousEnd != Long.MIN_VALUE
                        && Math.abs(startMillis - previousEnd) <= 1000L;
                if (!contiguous && runLen > 0) {
                    selectedRainY = flushPrecipitationRun(runLen, density, hasSelection,
                            selectedX, selectedRainY);
                    runLen = 0;
                }

                ensureSegmentCapacity(runLen + 1);
                float x1 = xForTime(startMillis, minTime, maxTime);
                float x2 = xForTime(endMillis, minTime, maxTime);
                if (x2 - x1 < density * 0.8f) {
                    x2 = Math.min(plotRight, x1 + density * 0.8f);
                    if (x2 - x1 < density * 0.8f) {
                        x1 = Math.max(plotLeft, x2 - density * 0.8f);
                    }
                }
                segStartX[runLen] = x1;
                segEndX[runLen] = x2;
                segY[runLen] = yForRate(rate);
                runLen++;
                previousEnd = endMillis;
            }
            if (runLen > 0) {
                selectedRainY = flushPrecipitationRun(runLen, density, hasSelection,
                        selectedX, selectedRainY);
            }

            areaPaint.setShader(areaGradient);
            canvas.drawPath(areaPath, areaPaint);
            areaPaint.setShader(null);

            areaStrokePaint.setColor(Color.argb(225, 170, 235, 255));
            canvas.drawPath(topPath, areaStrokePaint);
            return selectedRainY;
        }

        private float flushPrecipitationRun(int segCount, float density, boolean hasSelection,
                float selectedX, float currentSelectedY) {
            if (segCount <= 0) return currentSelectedY;
            ensurePrecipRunCapacity(segCount + 2);
            int ptCount = 0;

            if (segCount == 1) {
                float x1 = segStartX[0];
                float x2 = segEndX[0];
                if (x2 <= x1 + 0.01f) {
                    x2 = Math.min(plotRight, x1 + Math.max(1f, density * 0.8f));
                }
                precipRunX[0] = x1;
                precipRunY[0] = segY[0];
                precipRunX[1] = x2;
                precipRunY[1] = segY[0];
                ptCount = 2;
            } else {
                precipRunX[ptCount] = segStartX[0];
                precipRunY[ptCount] = segY[0];
                ptCount++;

                for (int i = 0; i < segCount; i++) {
                    float midX = (segStartX[i] + segEndX[i]) * 0.5f;
                    if (midX > precipRunX[ptCount - 1] + 0.01f) {
                        precipRunX[ptCount] = midX;
                        precipRunY[ptCount] = segY[i];
                        ptCount++;
                    } else if (i > 0) {
                        precipRunY[ptCount - 1] = Math.min(precipRunY[ptCount - 1], segY[i]);
                    }
                }

                float endX = segEndX[segCount - 1];
                float endY = segY[segCount - 1];
                if (endX > precipRunX[ptCount - 1] + 0.01f) {
                    precipRunX[ptCount] = endX;
                    precipRunY[ptCount] = endY;
                    ptCount++;
                } else if (ptCount == 1) {
                    precipRunX[ptCount] = Math.min(plotRight,
                            precipRunX[0] + Math.max(1f, density * 0.8f));
                    precipRunY[ptCount] = endY;
                    ptCount++;
                }
            }

            if (ptCount < 2) return currentSelectedY;

            Path runTop = CombinedForecastWidgetChart.buildMonotonePath(
                    precipRunX, precipRunY, ptCount, plotTop, plotBottom);
            topPath.addPath(runTop);

            runAreaPath.reset();
            runAreaPath.addPath(runTop);
            runAreaPath.lineTo(precipRunX[ptCount - 1], plotBottom);
            runAreaPath.lineTo(precipRunX[0], plotBottom);
            runAreaPath.close();
            areaPath.addPath(runAreaPath);

            if (hasSelection && !Float.isFinite(currentSelectedY)
                    && selectedX >= precipRunX[0] - 0.5f
                    && selectedX <= precipRunX[ptCount - 1] + 0.5f) {
                return evaluateMonotoneY(precipRunX, precipRunY, ptCount,
                        plotTop, plotBottom, selectedX);
            }
            return currentSelectedY;
        }

        private float drawTemperatureOverlay(Canvas canvas, long minTime, long maxTime, float density,
                float scaledDensity, boolean hasNowBadge, float nowLeft, float nowTop,
                float nowRight, float nowBottom, float scaleCaptionLeft,
                boolean hasSelection, float selectedX) {
            if (tempCount <= 0 || tempEpochs == null || tempValues == null) return Float.NaN;

            int visCount = 0;
            int runCount = 0;
            double minTemp = Double.POSITIVE_INFINITY;
            double maxTemp = Double.NEGATIVE_INFINITY;

            int i = 0;
            while (i < tempCount) {
                while (i < tempCount && !Double.isFinite(tempValues[i])) i++;
                if (i >= tempCount) break;
                int runStartIdx = i;
                while (i < tempCount && Double.isFinite(tempValues[i])) i++;
                int runEndIdx = i - 1;

                if (tempEpochs[runEndIdx] < minTime || tempEpochs[runStartIdx] > maxTime) continue;

                int visStart = visCount;
                boolean hasExactStart = false;
                boolean hasExactEnd = false;
                for (int k = runStartIdx; k <= runEndIdx; k++) {
                    if (tempEpochs[k] == minTime) hasExactStart = true;
                    if (tempEpochs[k] == maxTime) hasExactEnd = true;
                }

                if (tempEpochs[runStartIdx] < minTime && !hasExactStart) {
                    Double startVal = interpolateInRun(minTime, runStartIdx, runEndIdx);
                    if (startVal != null) {
                        ensureTempVisCapacity(visCount + 1);
                        tempVisX[visCount] = plotLeft;
                        tempVisVal[visCount] = startVal;
                        tempVisHourly[visCount] = false;
                        tempVisLabel[visCount] = "";
                        minTemp = Math.min(minTemp, startVal);
                        maxTemp = Math.max(maxTemp, startVal);
                        visCount++;
                    }
                }

                for (int k = runStartIdx; k <= runEndIdx; k++) {
                    long epoch = tempEpochs[k];
                    if (epoch < minTime || epoch > maxTime) continue;
                    double val = tempValues[k];
                    ensureTempVisCapacity(visCount + 1);
                    tempVisX[visCount] = xForTime(epoch, minTime, maxTime);
                    tempVisVal[visCount] = val;
                    tempVisHourly[visCount] = true;
                    tempVisLabel[visCount] = formatTempLabel(k, val);
                    minTemp = Math.min(minTemp, val);
                    maxTemp = Math.max(maxTemp, val);
                    visCount++;
                }

                if (tempEpochs[runEndIdx] > maxTime && !hasExactEnd) {
                    Double endVal = interpolateInRun(maxTime, runStartIdx, runEndIdx);
                    if (endVal != null) {
                        ensureTempVisCapacity(visCount + 1);
                        tempVisX[visCount] = plotRight;
                        tempVisVal[visCount] = endVal;
                        tempVisHourly[visCount] = false;
                        tempVisLabel[visCount] = "";
                        minTemp = Math.min(minTemp, endVal);
                        maxTemp = Math.max(maxTemp, endVal);
                        visCount++;
                    }
                }

                int visLen = visCount - visStart;
                if (visLen > 0) {
                    ensureTempRunCapacity(runCount + 1);
                    tempRunStarts[runCount] = visStart;
                    tempRunLengths[runCount] = visLen;
                    runCount++;
                }
            }

            if (visCount <= 0 || !Double.isFinite(minTemp) || !Double.isFinite(maxTemp)) {
                return Float.NaN;
            }

            double scaleMin = minTemp;
            double scaleMax = maxTemp;
            if (compactCombinedLayout) {
                double pad = Math.max(1.0d, (scaleMax - scaleMin) * 0.15d);
                scaleMin -= pad;
                scaleMax += pad;
            } else if (scaleMax - scaleMin < 1.0d) {
                double mid = (scaleMin + scaleMax) * 0.5d;
                scaleMin = mid - 1.0d;
                scaleMax = mid + 1.0d;
            }

            float tempTop = compactCombinedLayout ? plotTop : plotTop + dp(28);
            float tempBottom = compactCombinedLayout
                    ? plotBottom
                    : Math.max(tempTop + dp(16), plotBottom - dp(22));

            for (int k = 0; k < visCount; k++) {
                tempVisY[k] = yForTemperature(tempVisVal[k], scaleMin, scaleMax, tempTop, tempBottom);
            }

            float selectedTempY = Float.NaN;
            for (int r = 0; r < runCount; r++) {
                int start = tempRunStarts[r];
                int len = tempRunLengths[r];
                ensureTempSliceCapacity(len);
                int sliceLen = 0;
                for (int k = 0; k < len; k++) {
                    float px = tempVisX[start + k];
                    float py = tempVisY[start + k];
                    if (sliceLen == 0 || px > tempSliceX[sliceLen - 1] + 0.01f) {
                        tempSliceX[sliceLen] = px;
                        tempSliceY[sliceLen] = py;
                        sliceLen++;
                    } else {
                        tempSliceY[sliceLen - 1] = py;
                    }
                }
                if (sliceLen >= 2) {
                    Path tempCurve = CombinedForecastWidgetChart.buildMonotonePath(
                            tempSliceX, tempSliceY, sliceLen, tempTop, tempBottom);
                    canvas.drawPath(tempCurve, tempLinePaint);
                    if (hasSelection && !Float.isFinite(selectedTempY)
                            && selectedX >= tempSliceX[0] - 0.5f
                            && selectedX <= tempSliceX[sliceLen - 1] + 0.5f) {
                        selectedTempY = evaluateMonotoneY(tempSliceX, tempSliceY, sliceLen,
                                tempTop, tempBottom, selectedX);
                    }
                } else if (sliceLen == 1 && hasSelection && !Float.isFinite(selectedTempY)
                        && Math.abs(selectedX - tempSliceX[0]) <= Math.max(2f, density * 2f)) {
                    selectedTempY = tempSliceY[0];
                }
            }

            if (hasSelection && !Float.isFinite(selectedTempY)) {
                Double selectedTemp = temperatureAt(selectedTimeMillis);
                if (selectedTemp != null) {
                    selectedTempY = yForTemperature(selectedTemp, scaleMin, scaleMax,
                            tempTop, tempBottom);
                }
            }

            Paint.FontMetrics fm = tempLabelPaint.getFontMetrics();
            float dotRadius = density * 2.3f;
            float labelGapAbove = compactCombinedLayout ? dp(11) : dp(7);
            float labelGapBelow = dp(7);
            float minLabelTop = compactCombinedLayout ? dp(2) : plotTop + dp(2);
            float minLabelGapX = Math.max(dp(56), 56f * scaledDensity);
            float padX = dp(6);
            float padY = dp(2);
            float nowPadX = dp(4);
            float nowTopZoneBottom = plotTop + dp(46);
            int placedCount = 0;

            tempDotPaint.setColor(Color.WHITE);
            for (int k = 0; k < visCount; k++) {
                if (!tempVisHourly[k]) continue;
                float px = tempVisX[k];
                float py = tempVisY[k];
                canvas.drawCircle(px, py, dotRadius, tempDotPaint);

                String label = tempVisLabel[k];
                if (label == null || label.isEmpty()) continue;

                float halfW = tempLabelPaint.measureText(label) * 0.5f;
                float labelX = clamp(px, plotLeft + halfW + dp(2), plotRight - halfW - dp(2));
                float left = labelX - halfW;
                float right = labelX + halfW;

                if (hasNowBadge && py < nowTopZoneBottom
                        && right + nowPadX > nowLeft && left - nowPadX < nowRight) {
                    continue;
                }

                float aboveBase = py - labelGapAbove;
                float aboveTop = aboveBase + fm.ascent;
                float aboveBottom = aboveBase + fm.descent;

                float belowBase = py + labelGapBelow - fm.ascent;
                float belowTop = belowBase + fm.ascent;
                float belowBottom = belowBase + fm.descent;

                float chosenBase = Float.NaN;
                float chosenTop = 0f;
                float chosenBottom = 0f;

                if (canPlaceTempLabel(labelX, left, aboveTop, right, aboveBottom, minLabelTop,
                        hasNowBadge, nowLeft, nowTop, nowRight, nowBottom, nowPadX,
                        scaleCaptionLeft, placedCount, minLabelGapX, padX, padY)) {
                    chosenBase = aboveBase;
                    chosenTop = aboveTop;
                    chosenBottom = aboveBottom;
                } else if (canPlaceTempLabel(labelX, left, belowTop, right, belowBottom, minLabelTop,
                        hasNowBadge, nowLeft, nowTop, nowRight, nowBottom, nowPadX,
                        scaleCaptionLeft, placedCount, minLabelGapX, padX, padY)) {
                    chosenBase = belowBase;
                    chosenTop = belowTop;
                    chosenBottom = belowBottom;
                }

                if (Float.isFinite(chosenBase)) {
                    canvas.drawText(label, labelX, chosenBase, tempLabelPaint);
                    ensurePlacedLabelCapacity(placedCount + 1);
                    placedLabelCenterX[placedCount] = labelX;
                    placedLabelLeft[placedCount] = left;
                    placedLabelTop[placedCount] = chosenTop;
                    placedLabelRight[placedCount] = right;
                    placedLabelBottom[placedCount] = chosenBottom;
                    placedCount++;
                }
            }

            return selectedTempY;
        }

        private boolean canPlaceTempLabel(float centerX, float left, float top, float right, float bottom,
                float minLabelTop, boolean hasNowBadge, float nowLeft, float nowTop, float nowRight,
                float nowBottom, float nowPadX, float scaleCaptionLeft, int placedCount,
                float minLabelGapX, float padX, float padY) {
            if (top < minLabelTop || bottom > plotBottom - dp(2)) return false;
            if (hasNowBadge
                    && right + nowPadX > nowLeft && left - nowPadX < nowRight
                    && bottom + padY > nowTop && top - padY < nowBottom) {
                return false;
            }
            if (Float.isFinite(scaleCaptionLeft)
                    && right + dp(4) > scaleCaptionLeft
                    && bottom + padY > plotTop && top - padY < plotTop + dp(16)) {
                return false;
            }
            for (int i = 0; i < placedCount; i++) {
                if (Math.abs(centerX - placedLabelCenterX[i]) < minLabelGapX) {
                    return false;
                }
                if (right + padX > placedLabelLeft[i] && left - padX < placedLabelRight[i]
                        && bottom + padY > placedLabelTop[i] && top - padY < placedLabelBottom[i]) {
                    return false;
                }
            }
            return true;
        }

        private Double interpolateInRun(long targetEpoch, int runStartIdx, int runEndIdx) {
            if (targetEpoch < tempEpochs[runStartIdx] || targetEpoch > tempEpochs[runEndIdx]) {
                return null;
            }
            for (int k = runStartIdx; k < runEndIdx; k++) {
                long e0 = tempEpochs[k];
                long e1 = tempEpochs[k + 1];
                if (targetEpoch == e0) return tempValues[k];
                if (targetEpoch == e1) return tempValues[k + 1];
                if (targetEpoch > e0 && targetEpoch < e1) {
                    long span = e1 - e0;
                    if (span <= 0L) return tempValues[k];
                    double t = (targetEpoch - e0) / (double) span;
                    double v = tempValues[k] + (tempValues[k + 1] - tempValues[k]) * t;
                    return Double.isFinite(v) ? v : null;
                }
            }
            return targetEpoch == tempEpochs[runStartIdx] ? tempValues[runStartIdx] : null;
        }

        private String formatTempLabel(int index, double value) {
            if (tempLabels != null && index >= 0 && index < tempLabels.length
                    && tempLabels[index] != null && !tempLabels[index].isEmpty()) {
                return tempLabels[index];
            }
            return Math.round(value) + "°";
        }

        private float yForTemperature(double value, double minTemp, double maxTemp,
                float top, float bottom) {
            if (maxTemp <= minTemp) return (top + bottom) * 0.5f;
            double fraction = (value - minTemp) / (maxTemp - minTemp);
            fraction = Math.max(0d, Math.min(1d, fraction));
            return bottom - (float) fraction * (bottom - top);
        }

        private float evaluateMonotoneY(float[] xs, float[] ys, int count,
                float minY, float maxY, float targetX) {
            if (count <= 0) return maxY;
            if (count == 1 || targetX <= xs[0]) return ys[0];
            if (targetX >= xs[count - 1]) return ys[count - 1];
            if (count == 2) {
                if (ys[0] == ys[1]) return ys[0];
                float x0 = xs[0];
                float x1 = xs[1];
                float mid = (x0 + x1) * 0.5f;
                float lo = 0f;
                float hi = 1f;
                for (int iter = 0; iter < 14; iter++) {
                    float t = (lo + hi) * 0.5f;
                    float u = 1f - t;
                    float bx = u * u * u * x0
                            + 3f * u * u * t * mid
                            + 3f * u * t * t * mid
                            + t * t * t * x1;
                    if (bx < targetX) lo = t;
                    else hi = t;
                }
                float t = (lo + hi) * 0.5f;
                float u = 1f - t;
                float by = u * u * u * ys[0]
                        + 3f * u * u * t * ys[0]
                        + 3f * u * t * t * ys[1]
                        + t * t * t * ys[1];
                return clamp(by, minY, maxY);
            }

            int seg = 0;
            for (int i = 0; i < count - 1; i++) {
                if (targetX <= xs[i + 1] || i == count - 2) {
                    seg = i;
                    break;
                }
            }
            float dx = xs[seg + 1] - xs[seg];
            if (dx <= 0f) return ys[seg];
            float t = clamp((targetX - xs[seg]) / dx, 0f, 1f);
            float m0 = monotoneTangent(xs, ys, count, seg);
            float m1 = monotoneTangent(xs, ys, count, seg + 1);
            float cp1y = clamp(ys[seg] + m0 * dx / 3f, minY, maxY);
            float cp2y = clamp(ys[seg + 1] - m1 * dx / 3f, minY, maxY);
            float u = 1f - t;
            float by = u * u * u * ys[seg]
                    + 3f * u * u * t * cp1y
                    + 3f * u * t * t * cp2y
                    + t * t * t * ys[seg + 1];
            return clamp(by, minY, maxY);
        }

        private float monotoneSlope(float[] xs, float[] ys, int idx) {
            float dx = xs[idx + 1] - xs[idx];
            return dx > 0f ? (ys[idx + 1] - ys[idx]) / dx : 0f;
        }

        private float monotoneTangent(float[] xs, float[] ys, int count, int idx) {
            if (idx == 0) {
                float d0 = monotoneSlope(xs, ys, 0);
                return d0 == 0f ? 0f : d0;
            }
            if (idx == count - 1) {
                float dl = monotoneSlope(xs, ys, count - 2);
                return dl == 0f ? 0f : dl;
            }
            float dPrev = monotoneSlope(xs, ys, idx - 1);
            float dNext = monotoneSlope(xs, ys, idx);
            if (dPrev * dNext <= 0f) return 0f;
            float avg = (dPrev + dNext) * 0.5f;
            float limit = 3f * Math.min(Math.abs(dPrev), Math.abs(dNext));
            return Math.max(-limit, Math.min(limit, avg));
        }

        private float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }

        private void ensureSegmentCapacity(int required) {
            if (required <= segStartX.length) return;
            int next = Math.max(required, segStartX.length * 2);
            segStartX = Arrays.copyOf(segStartX, next);
            segEndX = Arrays.copyOf(segEndX, next);
            segY = Arrays.copyOf(segY, next);
        }

        private void ensurePrecipRunCapacity(int required) {
            if (required <= precipRunX.length) return;
            int next = Math.max(required, precipRunX.length * 2);
            precipRunX = Arrays.copyOf(precipRunX, next);
            precipRunY = Arrays.copyOf(precipRunY, next);
        }

        private void ensureTempVisCapacity(int required) {
            if (required <= tempVisX.length) return;
            int next = Math.max(required, tempVisX.length * 2);
            tempVisX = Arrays.copyOf(tempVisX, next);
            tempVisY = Arrays.copyOf(tempVisY, next);
            tempVisVal = Arrays.copyOf(tempVisVal, next);
            tempVisHourly = Arrays.copyOf(tempVisHourly, next);
            tempVisLabel = Arrays.copyOf(tempVisLabel, next);
        }

        private void ensureTempRunCapacity(int required) {
            if (required <= tempRunStarts.length) return;
            int next = Math.max(required, tempRunStarts.length * 2);
            tempRunStarts = Arrays.copyOf(tempRunStarts, next);
            tempRunLengths = Arrays.copyOf(tempRunLengths, next);
        }

        private void ensureTempSliceCapacity(int required) {
            if (required <= tempSliceX.length) return;
            int next = Math.max(required, tempSliceX.length * 2);
            tempSliceX = Arrays.copyOf(tempSliceX, next);
            tempSliceY = Arrays.copyOf(tempSliceY, next);
        }

        private void ensurePlacedLabelCapacity(int required) {
            if (required <= placedLabelLeft.length) return;
            int next = Math.max(required, placedLabelLeft.length * 2);
            placedLabelCenterX = Arrays.copyOf(placedLabelCenterX, next);
            placedLabelLeft = Arrays.copyOf(placedLabelLeft, next);
            placedLabelTop = Arrays.copyOf(placedLabelTop, next);
            placedLabelRight = Arrays.copyOf(placedLabelRight, next);
            placedLabelBottom = Arrays.copyOf(placedLabelBottom, next);
        }

        private void drawIntensityBands(Canvas canvas, boolean subtle) {
            if (compactCombinedLayout) {
                gridPaint.setColor(Color.argb(24, 255, 255, 255));
                canvas.drawLine(plotLeft, plotTop, plotRight, plotTop, gridPaint);
                canvas.drawLine(plotLeft, (plotTop + plotBottom) * 0.5f,
                        plotRight, (plotTop + plotBottom) * 0.5f, gridPaint);
                gridPaint.setColor(Color.argb(62, 245, 250, 255));
                canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, gridPaint);
                return;
            }

            float lightTop = yForRate(PRECIP_LIGHT_MAX_MM_H);
            float moderateTop = yForRate(PRECIP_MODERATE_MAX_MM_H);

            bandPaint.setColor(Color.argb(subtle ? 5 : 12, 142, 226, 255));
            canvas.drawRect(plotLeft, lightTop, plotRight, plotBottom, bandPaint);
            bandPaint.setColor(Color.argb(subtle ? 7 : 17, 104, 198, 255));
            canvas.drawRect(plotLeft, moderateTop, plotRight, lightTop, bandPaint);
            bandPaint.setColor(Color.argb(subtle ? 10 : 22, 74, 152, 235));
            canvas.drawRect(plotLeft, plotTop, plotRight, moderateTop, bandPaint);

            gridPaint.setColor(Color.argb(subtle ? 56 : 62, 245, 250, 255));
            canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, gridPaint);
            gridPaint.setColor(Color.argb(subtle ? 24 : 62, 245, 250, 255));
            canvas.drawLine(plotLeft, lightTop, plotRight, lightTop, gridPaint);
            canvas.drawLine(plotLeft, moderateTop, plotRight, moderateTop, gridPaint);

            textPaint.setTypeface(normalTypeface);
            textPaint.setTextSize(10f * getResources().getDisplayMetrics().scaledDensity);
            textPaint.setColor(Color.argb(subtle ? 148 : 188, 255, 255, 255));
            textPaint.setTextAlign(Paint.Align.LEFT);
            float labelX = plotRight + dp(7);
            canvas.drawText(UiTranslations.text(getContext(), "Light"), labelX,
                    (lightTop + plotBottom) * 0.5f + dp(3), textPaint);
            canvas.drawText(UiTranslations.text(getContext(), "Moderate"), labelX,
                    (moderateTop + lightTop) * 0.5f + dp(3), textPaint);
            canvas.drawText(UiTranslations.text(getContext(), "Heavy"), labelX,
                    (plotTop + moderateTop) * 0.5f + dp(3), textPaint);
        }

        private void drawTimeTicks(Canvas canvas, float scaledDensity) {
            long minTime = windowStartMillis;
            long maxTime = windowEndMillis;
            if (maxTime <= minTime) maxTime = minTime + 1L;

            textPaint.setTypeface(normalTypeface);
            if (compactCombinedLayout) {
                textPaint.setTextSize(12f * scaledDensity);
                textPaint.setColor(Color.argb(220, 255, 255, 255));
                float baselineY = getHeight() - dp(10);
                for (int i = 0; i < 4; i++) {
                    float fraction = i / 3f;
                    long time = minTime + Math.round((maxTime - minTime) * fraction);
                    float x = plotLeft + (plotRight - plotLeft) * fraction;
                    if (i == 0) textPaint.setTextAlign(Paint.Align.LEFT);
                    else if (i == 3) textPaint.setTextAlign(Paint.Align.RIGHT);
                    else textPaint.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText(formatTime(Instant.ofEpochMilli(time), zone), x, baselineY, textPaint);
                }
                return;
            }

            textPaint.setTextSize(10f * scaledDensity);
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

        private double peakVisibleRateMmPerHour(long minTime, long maxTime) {
            double peak = 0d;
            for (int i = 0; i < segments.size(); i++) {
                MinuteSegment segment = segments.get(i);
                long start = segment.start.toEpochMilli();
                long end = segment.end.toEpochMilli();
                if (end <= minTime || start >= maxTime) continue;
                Double rate = minuteRateMmPerHour(segment);
                if (rate != null && Double.isFinite(rate) && rate > peak) peak = rate;
            }
            return peak;
        }

        private static String formatScaleCeiling(double value) {
            if (Math.abs(value - Math.rint(value)) < 0.005d) {
                return Long.toString(Math.round(value));
            }
            String formatted = String.format(java.util.Locale.US, "%.2f", value);
            while (formatted.contains(".") && formatted.endsWith("0")) {
                formatted = formatted.substring(0, formatted.length() - 1);
            }
            if (formatted.endsWith(".")) formatted = formatted.substring(0, formatted.length() - 1);
            return formatted;
        }

        private float yForRate(double rateMmPerHour) {
            double cap = currentRateCapMmPerHour > 0d ? currentRateCapMmPerHour : PRECIP_VISUAL_CAP_MM_H;
            double capped = Math.max(0d, Math.min(cap, rateMmPerHour));
            double fraction = cap <= 0d ? 0d : capped / cap;
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
                    // Focusing a partially visible graph asks ScrollView to bring it
                    // into view. Defer selection so a vertical drag never jumps there.
                    touchDownX = event.getX();
                    touchDownY = event.getY();
                    touchAxis = 0;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (touchAxis == 0) {
                        float dx = Math.abs(event.getX() - touchDownX);
                        float dy = Math.abs(event.getY() - touchDownY);
                        if (Math.max(dx, dy) > touchSlop) touchAxis = dx > dy ? 1 : 2;
                    }
                    if (touchAxis == 1) {
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        selectForX(event.getX(), false);
                    } else if (touchAxis == 2 && getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (touchAxis != 2) {
                        selectForX(event.getX(), false);
                        performClick();
                    }
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private void selectForX(float x, boolean announce) {
            if (segments.isEmpty() || plotRight <= plotLeft) return;
            long minTime = windowStartMillis;
            long maxTime = windowEndMillis;
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
                setContentDescription(UiTranslations.text(getContext(),
                        "Minute precipitation graph, no returned segments"));
                return;
            }
            MinuteSegment segment = segments.get(Math.max(0, Math.min(selectedIndex, segments.size() - 1)));
            Instant selectedInstant = Instant.ofEpochMilli(selectedTimeMillis);
            String detail = localizedMinuteSelectionDetail(getContext(), segment,
                    selectedInstant, zone);
            Double selectedTemp = temperatureAt(selectedTimeMillis);
            if (selectedTemp != null) {
                String unit = tempUnitSymbol.isEmpty() ? "°" : tempUnitSymbol;
                detail = detail + ", " + Math.round(selectedTemp) + unit;
            }
            String description = UiTranslations.text(getContext(), "Minute precipitation graph. ")
                    + detail + UiTranslations.text(getContext(),
                    ". Swipe or use left and right to move in two-minute steps.");
            setContentDescription(description);
            if (Build.VERSION.SDK_INT >= 30) {
                setStateDescription(UiTranslations.text(getContext(), "Selected ")
                        + formatTime(selectedInstant, zone));
            }
            if (announce && isShown()) announceForAccessibility(detail);
        }
    }

}
