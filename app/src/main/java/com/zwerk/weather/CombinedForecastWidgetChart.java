package com.zwerk.weather;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * Renders the condensed multi-day combined temperature + precipitation chart for widgets,
 * and provides shared monotone curve/precipitation drawing helpers for the main app view.
 */
final class CombinedForecastWidgetChart {
    private static final int SHADOW_COLOR = Color.argb(175, 6, 18, 36);
    private static final int WIDGET_HIGH_LINE_COLOR = Color.argb(245, 255, 246, 224);
    private static final int WIDGET_LOW_LINE_COLOR = Color.argb(220, 208, 226, 248);
    private static final int PRECIP_STROKE_COLOR = Color.argb(200, 152, 228, 255);
    private static final int PRECIP_LABEL_COLOR = Color.rgb(186, 234, 255);
    private static final int PRECIP_MUTED_COLOR = Color.argb(135, 194, 228, 255);

    private CombinedForecastWidgetChart() { }

    static float bitmapScale(int widthDp, int heightDp, float density) {
        float target = Math.max(2f, Math.min(3f, Float.isFinite(density) && density > 0f ? density : 2f));
        double maxPixels = 850_000d; // <= 3.4 MB ARGB_8888, safely within RemoteViews Binder limits
        double basePixels = (double) Math.max(1, widthDp) * Math.max(1, heightDp);
        if (basePixels * target * target > maxPixels) {
            target = (float) Math.sqrt(maxPixels / basePixels);
        }
        return Math.max(1f, target);
    }

    static Bitmap render(CombinedForecastData data, int widthDp, int heightDp,
            String[] dayLabels, String[] shortDayLabels, String precipitationStyle,
            boolean showIcons, String emptyLabel, float textScale) {
        return render(data, widthDp, heightDp, dayLabels, shortDayLabels, precipitationStyle,
                showIcons, emptyLabel, textScale, 2f);
    }

    static Bitmap render(CombinedForecastData data, int widthDp, int heightDp,
            String[] dayLabels, String[] shortDayLabels, String precipitationStyle,
            boolean showIcons, String emptyLabel, float textScale, float density) {
        int width = Math.max(180, Math.min(600, widthDp));
        int height = Math.max(70, Math.min(320, heightDp));
        float scaleFactor = bitmapScale(width, height, density);
        int pixelW = Math.max(1, Math.round(width * scaleFactor));
        int pixelH = Math.max(1, Math.round(height * scaleFactor));
        Bitmap bitmap = Bitmap.createBitmap(pixelW, pixelH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(pixelW / (float) width, pixelH / (float) height);

        TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        float clampedScale = Math.max(0.8f, Math.min(1.2f, textScale));

        if (data == null || data.isEmpty()) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.7f);
            paint.setColor(Color.argb(90, 225, 244, 255));
            canvas.drawLine(4f, height - 12f, width - 4f, height - 12f, paint);
            textPaint.setColor(Color.argb(225, 235, 249, 255));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(11f * clampedScale);
            textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            canvas.drawText(emptyLabel == null ? "" : emptyLabel, width / 2f, height / 2f + 3f, textPaint);
            return bitmap;
        }

        int n = data.days.size();
        float padX = 2f;
        float plotLeft = padX;
        float plotRight = width - padX;
        float cell = (plotRight - plotLeft) / n;

        float narrowFactor = n >= 7 ? 0.88f : n == 6 ? 0.94f : 1.0f;
        float heightFactor = height < 92 ? Math.max(0.84f, height / 92f) : 1.0f;
        float scale = clampedScale * narrowFactor * heightFactor;

        float daySize = Math.max(8f, Math.min(11.5f, 9.8f * scale));
        float tempSize = Math.max(8.5f, Math.min(12.5f, 10.4f * scale));
        float precipSize = Math.max(7.4f, Math.min(10.4f, 8.6f * scale));
        boolean showTwoLinePrecip = height >= 134 && data.hasPrecipitationAmount
                && data.hasPrecipitationProbability;

        // Subtle Today column anchor behind the full column height.
        for (int i = 0; i < n; i++) {
            if (data.days.get(i).today) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(22, 255, 255, 255));
                float left = plotLeft + i * cell + 1.5f;
                float right = plotLeft + (i + 1) * cell - 1.5f;
                canvas.drawRoundRect(new RectF(left, 0f, right, height), 7f, 7f, paint);
                break;
            }
        }

        float dayBaselineY = daySize + 1f;
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(daySize);
        for (int i = 0; i < n; i++) {
            CombinedForecastData.DayPoint day = data.days.get(i);
            float x = plotLeft + (i + 0.5f) * cell;
            textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            textPaint.setColor(day.today ? Color.WHITE : Color.argb(220, 240, 248, 255));
            String preferred = dayLabels != null && i < dayLabels.length ? dayLabels[i] : "";
            String fallback = shortDayLabels != null && i < shortDayLabels.length ? shortDayLabels[i] : preferred;
            String label = preferred;
            if (textPaint.measureText(label) > cell - 3f && !fallback.isEmpty()) {
                label = fallback;
            }
            label = fitText(label, textPaint, Math.max(10f, cell - 2f));
            canvas.drawText(label, x, dayBaselineY, textPaint);
        }

        float plotTop;
        if (showIcons) {
            float iconSize = Math.max(11.5f, Math.min(18.5f, Math.min(cell * 0.42f, height * 0.165f)));
            float iconCenterY = dayBaselineY + 2.5f + iconSize * 0.5f;
            for (int i = 0; i < n; i++) {
                CombinedForecastData.DayPoint day = data.days.get(i);
                if (!day.conditionType.isEmpty()) {
                    float x = plotLeft + (i + 0.5f) * cell;
                    WeatherGlyphDrawer.draw(canvas, day.conditionType, true, x, iconCenterY, iconSize);
                }
            }
            plotTop = iconCenterY + iconSize * 0.5f + 2f;
        } else {
            plotTop = dayBaselineY + 4f;
        }

        float precipFooterHeight = showTwoLinePrecip
                ? (precipSize * 2f + 4.5f)
                : (precipSize + 3.5f);
        float plotBottom = Math.max(plotTop + 26f, height - precipFooterHeight - 1.5f);
        float plotHeight = plotBottom - plotTop;

        // Subtle horizontal glass guide lines.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.6f);
        paint.setColor(Color.argb(30, 255, 255, 255));
        canvas.drawLine(plotLeft + 2f, plotTop + plotHeight * 0.5f, plotRight - 2f, plotTop + plotHeight * 0.5f, paint);
        paint.setStrokeWidth(0.75f);
        paint.setColor(Color.argb(95, 224, 243, 255));
        canvas.drawLine(plotLeft + 2f, plotBottom, plotRight - 2f, plotBottom, paint);

        float[] xs = new float[n];
        float[] precipFractions = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = plotLeft + (i + 0.5f) * cell;
            precipFractions[i] = data.precipitationFraction(data.days.get(i));
        }

        // Layer 1 (behind): blue precipitation overlapping into the temperature band.
        float precipTop = plotBottom - plotHeight * 0.65f;
        String style = CombinedForecastData.normalizePrecipitationStyle(precipitationStyle);
        if (CombinedForecastData.PRECIP_STYLE_LINE.equals(style)) {
            drawPrecipitationLine(canvas, xs, precipFractions, n, precipTop, plotBottom, 1.25f, 0f);
        } else {
            float barWidth = Math.max(5.5f, Math.min(20f, cell * 0.44f));
            drawPrecipitationColumns(canvas, xs, precipFractions, n, precipTop, plotBottom, barWidth, 3.0f);
        }

        // Layer 2 (on top): unified high/low shaded band, warm golden-cream high curve & ice-blue low curve.
        float topClearance = Math.min(plotHeight * 0.30f, tempSize + 4.5f);
        float bottomClearance = Math.min(plotHeight * 0.32f, tempSize + 5.0f);
        float tempTop = plotTop + topClearance;
        float tempBottom = Math.max(tempTop + 6f, plotBottom - bottomClearance);

        int minT = data.minTemperature;
        int maxT = data.maxTemperature;
        if (minT == maxT) maxT = minT + 1;

        Integer[] highs = new Integer[n];
        Integer[] lows = new Integer[n];
        float[] highY = new float[n];
        float[] lowY = new float[n];
        for (int i = 0; i < n; i++) {
            CombinedForecastData.DayPoint day = data.days.get(i);
            highs[i] = day.high;
            lows[i] = day.low;
            highY[i] = mapTemperature(day.high, minT, maxT, tempTop, tempBottom);
            lowY[i] = mapTemperature(day.low, minT, maxT, tempTop, tempBottom);
        }

        drawWidgetThermalBand(canvas, xs, highY, lowY, highs, lows, n, tempTop, tempBottom);
        drawTemperatureCurve(canvas, xs, highY, highs, n, WIDGET_HIGH_LINE_COLOR, 1.9f);
        drawTemperatureCurve(canvas, xs, lowY, lows, n, WIDGET_LOW_LINE_COLOR, 1.55f);

        // Dots and temperature labels with subtle dark translucent halo so cyan bars stay legible.
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.clearShadowLayer();
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(tempSize);
        textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        textPaint.setShadowLayer(2.2f, 0f, 0.8f, SHADOW_COLOR);

        for (int i = 0; i < n; i++) {
            float x = xs[i];
            float highLabelY = Float.NaN;
            float lowLabelY = Float.NaN;
            if (highs[i] != null) {
                highLabelY = Math.max(plotTop + tempSize - 0.5f, highY[i] - 4.6f);
            }
            if (lows[i] != null) {
                lowLabelY = lowY[i] + tempSize + 1.8f;
                if (!Float.isNaN(highLabelY)) {
                    lowLabelY = Math.max(lowLabelY, highLabelY + tempSize + 2.2f);
                }
                if (lowLabelY > plotBottom - 1.5f) {
                    lowLabelY = plotBottom - 1.5f;
                    if (!Float.isNaN(highLabelY)) {
                        highLabelY = Math.max(plotTop + tempSize - 0.5f,
                                Math.min(highLabelY, lowLabelY - tempSize - 2.2f));
                    }
                }
                // Guard narrow diurnal ranges (high - low <= 1°) near bottom so low text clears the dot.
                if (lowLabelY - tempSize * 0.78f < lowY[i] + 2.2f) {
                    lowLabelY = Math.min(plotBottom - 1.2f, lowY[i] + tempSize + 2.0f);
                }
            }
            if (highs[i] != null) {
                paint.setColor(Color.WHITE);
                canvas.drawCircle(x, highY[i], 2.15f, paint);
                textPaint.setColor(Color.WHITE);
                drawClampedText(canvas, highs[i] + "°", x, highLabelY, width, textPaint);
            }
            if (lows[i] != null) {
                paint.setColor(WIDGET_LOW_LINE_COLOR);
                canvas.drawCircle(x, lowY[i], 1.95f, paint);
                textPaint.setColor(Color.argb(235, 232, 242, 255));
                drawClampedText(canvas, lows[i] + "°", x, lowLabelY, width, textPaint);
            }
        }
        textPaint.clearShadowLayer();

        // Bottom precipitation readouts.
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(precipSize);
        textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        boolean includeUnit = cell >= 42f;
        float firstLineY = plotBottom + precipSize + 1.6f;
        float secondLineY = firstLineY + precipSize + 1.4f;
        for (int i = 0; i < n; i++) {
            CombinedForecastData.DayPoint day = data.days.get(i);
            float x = xs[i];
            boolean hasAmount = Double.isFinite(day.precipitationMm) && day.precipitationMm >= 0d;
            boolean wetAmount = hasAmount && day.precipitationMm > 0d;
            boolean hasProb = day.probability >= 0;

            String primary;
            String secondary = "";
            boolean emphasize;
            if (wetAmount) {
                primary = CombinedForecastData.formatCompactAmount(
                        day.precipitationMm, data.fahrenheit, includeUnit);
                secondary = hasProb ? day.probability + "%" : "";
                emphasize = true;
            } else if (hasProb && day.probability > 0) {
                primary = day.probability + "%";
                secondary = hasAmount
                        ? CombinedForecastData.formatCompactAmount(0d, data.fahrenheit, includeUnit) : "";
                emphasize = true;
            } else if (hasProb) {
                primary = "0%";
                secondary = hasAmount
                        ? CombinedForecastData.formatCompactAmount(0d, data.fahrenheit, includeUnit) : "";
                emphasize = false;
            } else if (hasAmount) {
                primary = CombinedForecastData.formatCompactAmount(0d, data.fahrenheit, includeUnit);
                emphasize = false;
            } else {
                primary = "—";
                emphasize = false;
            }

            textPaint.setColor(emphasize ? PRECIP_LABEL_COLOR : PRECIP_MUTED_COLOR);
            canvas.drawText(fitText(primary, textPaint, Math.max(10f, cell - 2f)), x, firstLineY, textPaint);

            if (showTwoLinePrecip && !secondary.isEmpty()) {
                textPaint.setColor(PRECIP_MUTED_COLOR);
                canvas.drawText(fitText(secondary, textPaint, Math.max(10f, cell - 2f)), x, secondLineY, textPaint);
            }
        }

        return bitmap;
    }

    static void drawPrecipitationColumns(Canvas canvas, float[] xs, float[] fractions, int count,
            float top, float bottom, float barWidth, float cornerRadius) {
        if (count <= 0 || bottom <= top) return;
        float height = bottom - top;
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setShader(new LinearGradient(
                0f, top, 0f, bottom,
                new int[]{
                        Color.argb(176, 134, 228, 255),
                        Color.argb(126, 58, 168, 248),
                        Color.argb(56, 28, 102, 212)
                },
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));

        Paint capPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        capPaint.setStyle(Paint.Style.STROKE);
        capPaint.setStrokeWidth(Math.max(0.8f, cornerRadius * 0.30f));
        capPaint.setStrokeCap(Paint.Cap.ROUND);
        capPaint.setColor(Color.argb(175, 192, 244, 255));

        Path barPath = new Path();
        RectF bounds = new RectF();
        float[] radii = {cornerRadius, cornerRadius, cornerRadius, cornerRadius, 0f, 0f, 0f, 0f};
        for (int i = 0; i < count; i++) {
            float frac = fractions[i];
            if (!Float.isFinite(frac) || frac <= 0f) continue;
            float clampedFrac = Math.min(1f, frac);
            float rawH = clampedFrac * height;
            float barH = clampedFrac < 0.05f ? rawH : Math.max(1.0f, rawH);
            if (barH <= 0f) continue;
            float left = xs[i] - barWidth * 0.5f;
            float right = xs[i] + barWidth * 0.5f;
            float barTop = bottom - barH;
            barPath.reset();
            bounds.set(left, barTop, right, bottom);
            barPath.addRoundRect(bounds, radii, Path.Direction.CW);
            canvas.drawPath(barPath, fillPaint);
            if (barH >= 1.2f) {
                float capInset = Math.min(cornerRadius * 0.7f, barWidth * 0.25f);
                canvas.drawLine(left + capInset, barTop + 0.4f, right - capInset, barTop + 0.4f, capPaint);
            }
        }
    }

    static void drawPrecipitationLine(Canvas canvas, float[] xs, float[] fractions, int count,
            float top, float bottom, float strokeWidth, float dotRadius) {
        if (count <= 0 || bottom <= top) return;
        float height = bottom - top;
        boolean anyPositive = false;
        for (int i = 0; i < count; i++) {
            if (Float.isFinite(fractions[i]) && fractions[i] > 0f) {
                anyPositive = true;
                break;
            }
        }
        if (!anyPositive) return;

        Paint areaPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        areaPaint.setStyle(Paint.Style.FILL);
        areaPaint.setShader(new LinearGradient(
                0f, top, 0f, bottom,
                new int[]{
                        Color.argb(118, 128, 222, 255),
                        Color.argb(66, 58, 170, 248),
                        Color.argb(16, 28, 112, 224)
                },
                new float[]{0f, 0.58f, 1f}, Shader.TileMode.CLAMP));

        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(Math.max(0.5f, strokeWidth));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(PRECIP_STROKE_COLOR);

        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(Color.argb(215, 176, 234, 255));

        int start = 0;
        while (start < count) {
            while (start < count && !Float.isFinite(fractions[start])) start++;
            if (start >= count) break;
            int end = start;
            while (end < count && Float.isFinite(fractions[end])) end++;
            int runLen = end - start;
            float[] rx = new float[runLen];
            float[] ry = new float[runLen];
            for (int k = 0; k < runLen; k++) {
                float f = Math.max(0f, Math.min(1f, fractions[start + k]));
                rx[k] = xs[start + k];
                float rawH = f * height;
                float h = f == 0f ? 0f : (f < 0.05f ? rawH : Math.max(1.0f, rawH));
                ry[k] = bottom - h;
            }
            if (runLen == 1) {
                float f0 = Math.max(0f, Math.min(1f, fractions[start]));
                if (f0 > 0f) {
                    float baseR = dotRadius > 0f ? dotRadius : Math.max(1.2f, strokeWidth);
                    float r = f0 < 0.05f ? baseR * (0.45f + 0.55f * (f0 / 0.05f)) : baseR;
                    canvas.drawCircle(rx[0], ry[0], r, dotPaint);
                }
            } else {
                Path linePath = buildMonotonePath(rx, ry, runLen, top, bottom);
                Path areaPath = new Path(linePath);
                areaPath.lineTo(rx[runLen - 1], bottom);
                areaPath.lineTo(rx[0], bottom);
                areaPath.close();
                canvas.drawPath(areaPath, areaPaint);
                canvas.drawPath(linePath, linePaint);
                if (dotRadius > 0f) {
                    for (int k = 0; k < runLen; k++) {
                        float fk = Math.max(0f, Math.min(1f, fractions[start + k]));
                        if (fk > 0f) {
                            float r = fk < 0.05f ? dotRadius * (0.45f + 0.55f * (fk / 0.05f)) : dotRadius;
                            canvas.drawCircle(rx[k], ry[k], r, dotPaint);
                        }
                    }
                }
            }
            start = end;
        }
    }

    private static void drawWidgetThermalBand(Canvas canvas, float[] xs, float[] highY,
            float[] lowY, Integer[] highs, Integer[] lows, int count, float top, float bottom) {
        Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        bandPaint.setStyle(Paint.Style.FILL);
        bandPaint.setShader(new LinearGradient(
                0f, top, 0f, bottom,
                Color.argb(24, 255, 248, 228),
                Color.argb(8, 198, 224, 255),
                Shader.TileMode.CLAMP));

        int start = 0;
        while (start < count) {
            while (start < count && (highs[start] == null || lows[start] == null)) start++;
            if (start >= count) break;
            int end = start;
            while (end < count && highs[end] != null && lows[end] != null) end++;
            int runLen = end - start;
            if (runLen >= 2) {
                float[] rx = new float[runLen];
                float[] rh = new float[runLen];
                float[] rl = new float[runLen];
                for (int k = 0; k < runLen; k++) {
                    rx[k] = xs[start + k];
                    rh[k] = highY[start + k];
                    rl[k] = lowY[start + k];
                }
                Path band = buildSmoothCurvePath(rx, rh, runLen);
                band.lineTo(rx[runLen - 1], rl[runLen - 1]);
                for (int k = runLen - 1; k > 0; k--) {
                    float mid = (rx[k] + rx[k - 1]) * 0.5f;
                    band.cubicTo(mid, rl[k], mid, rl[k - 1], rx[k - 1], rl[k - 1]);
                }
                band.close();
                canvas.drawPath(band, bandPaint);
            }
            start = end;
        }
    }

    static void drawTemperatureCurve(Canvas canvas, float[] xs, float[] ys, Integer[] values,
            int count, int color, float strokeWidth) {
        drawTemperatureCurve(canvas, xs, ys, values, count, color, strokeWidth, false);
    }

    static void drawTemperatureCurve(Canvas canvas, float[] xs, float[] ys, Integer[] values,
            int count, int color, float strokeWidth, boolean withShadow) {
        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(strokeWidth);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(color);
        if (withShadow) {
            linePaint.setShadowLayer(1.6f, 0f, 0.7f, SHADOW_COLOR);
        }

        int start = 0;
        while (start < count) {
            while (start < count && values[start] == null) start++;
            if (start >= count) break;
            int end = start;
            while (end < count && values[end] != null) end++;
            int runLen = end - start;
            if (runLen >= 2) {
                float[] rx = new float[runLen];
                float[] ry = new float[runLen];
                for (int k = 0; k < runLen; k++) {
                    rx[k] = xs[start + k];
                    ry[k] = ys[start + k];
                }
                canvas.drawPath(buildSmoothCurvePath(rx, ry, runLen), linePaint);
            }
            start = end;
        }
    }

    static Path buildSmoothCurvePath(float[] xs, float[] ys, int count) {
        Path path = new Path();
        if (count <= 0) return path;
        path.moveTo(xs[0], ys[0]);
        for (int i = 1; i < count; i++) {
            float mid = (xs[i - 1] + xs[i]) * 0.5f;
            path.cubicTo(mid, ys[i - 1], mid, ys[i], xs[i], ys[i]);
        }
        return path;
    }

    /** Fritsch-Carlson monotone cubic Bezier path so precipitation curves never undershoot below baseline. */
    static Path buildMonotonePath(float[] xs, float[] ys, int count, float minY, float maxY) {
        Path path = new Path();
        if (count <= 0) return path;
        path.moveTo(xs[0], ys[0]);
        if (count == 1) return path;
        if (count == 2) {
            float mid = (xs[0] + xs[1]) * 0.5f;
            path.cubicTo(mid, ys[0], mid, ys[1], xs[1], ys[1]);
            return path;
        }

        float[] d = new float[count - 1];
        for (int i = 0; i < count - 1; i++) {
            float dx = xs[i + 1] - xs[i];
            d[i] = dx > 0f ? (ys[i + 1] - ys[i]) / dx : 0f;
        }

        float[] m = new float[count];
        m[0] = d[0];
        m[count - 1] = d[count - 2];
        for (int i = 1; i < count - 1; i++) {
            if (d[i - 1] * d[i] <= 0f) {
                m[i] = 0f;
            } else {
                float avg = (d[i - 1] + d[i]) * 0.5f;
                float limit = 3f * Math.min(Math.abs(d[i - 1]), Math.abs(d[i]));
                m[i] = Math.max(-limit, Math.min(limit, avg));
            }
        }
        if (d[0] == 0f) m[0] = 0f;
        if (d[count - 2] == 0f) m[count - 1] = 0f;

        for (int i = 0; i < count - 1; i++) {
            float dx = xs[i + 1] - xs[i];
            float cp1x = xs[i] + dx / 3f;
            float cp1y = clamp(ys[i] + m[i] * dx / 3f, minY, maxY);
            float cp2x = xs[i + 1] - dx / 3f;
            float cp2y = clamp(ys[i + 1] - m[i + 1] * dx / 3f, minY, maxY);
            path.cubicTo(cp1x, cp1y, cp2x, cp2y, xs[i + 1], ys[i + 1]);
        }
        return path;
    }

    static float mapTemperature(Integer value, int min, int max, float top, float bottom) {
        if (value == null) return (top + bottom) * 0.5f;
        if (max <= min) return (top + bottom) * 0.5f;
        float normalized = (value - min) / (float) (max - min);
        return bottom - normalized * (bottom - top);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void drawClampedText(Canvas canvas, String text, float x, float y,
            float width, Paint paint) {
        float half = paint.measureText(text) * 0.5f;
        float clampedX = Math.max(half + 1.5f, Math.min(width - half - 1.5f, x));
        canvas.drawText(text, clampedX, y, paint);
    }

    private static String fitText(String label, TextPaint paint, float maxWidth) {
        if (label == null || label.isEmpty()) return "";
        if (paint.measureText(label) <= maxWidth) return label;
        return TextUtils.ellipsize(label, paint, maxWidth, TextUtils.TruncateAt.END).toString();
    }
}
