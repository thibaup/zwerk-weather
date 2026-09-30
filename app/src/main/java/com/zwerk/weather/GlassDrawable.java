package com.zwerk.weather;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

final class GlassDrawable extends Drawable {
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float radius;
    private final float strokeWidth;
    private final boolean tile;
    private int topColor;
    private int bottomColor;
    private int edgeColor;
    private int alpha = 255;
    private int fillOpacityBoost;
    private int transparency = WeatherPreferences.DEFAULT_TILE_TRANSPARENCY;

    GlassDrawable(float radius, float strokeWidth, boolean tile) {
        this.radius = radius;
        this.strokeWidth = strokeWidth;
        this.tile = tile;
        fillPaint.setStyle(Paint.Style.FILL);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(strokeWidth);
    }

    boolean isTile() {
        return tile;
    }

    void setTransparency(int value) {
        int next = Math.max(0, Math.min(100, value));
        if (transparency == next) return;
        transparency = next;
        rebuildShader();
        invalidateSelf();
    }

    void setFillOpacityBoost(int amount) {
        int next = Math.max(0, Math.min(255, amount));
        if (fillOpacityBoost == next) return;
        fillOpacityBoost = next;
        rebuildShader();
        invalidateSelf();
    }

    void setColors(int topColor, int bottomColor, int edgeColor) {
        if (this.topColor == topColor
                && this.bottomColor == bottomColor
                && this.edgeColor == edgeColor) {
            return;
        }
        this.topColor = topColor;
        this.bottomColor = bottomColor;
        this.edgeColor = edgeColor;
        rebuildShader();
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        float inset = strokeWidth * 0.5f;
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset);
        rebuildShader();
    }

    private void rebuildShader() {
        Rect bounds = getBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        fillPaint.setShader(new LinearGradient(
                bounds.left,
                bounds.top,
                bounds.left,
                bounds.bottom,
                new int[]{surfaceColor(topColor), surfaceColor(bottomColor)},
                null,
                Shader.TileMode.CLAMP));
        edgePaint.setShader(null);
        int edgeAlpha = Math.round(alpha * Math.min(1f,
                (100 - transparency) / (float) (100 - WeatherPreferences.DEFAULT_TILE_TRANSPARENCY)));
        edgePaint.setColor(multiplyAlpha(edgeColor, edgeAlpha, 0));
    }

    private int surfaceColor(int color) {
        int boosted = multiplyAlpha(color, 255, fillOpacityBoost);
        return multiplyAlpha(WeatherPreferences.surfaceColor(boosted, transparency), alpha, 0);
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawRoundRect(rect, radius, radius, fillPaint);
        if (Color.alpha(edgePaint.getColor()) > 0) {
            canvas.drawRoundRect(rect, radius, radius, edgePaint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        int next = Math.max(0, Math.min(255, alpha));
        if (this.alpha != next) {
            this.alpha = next;
            rebuildShader();
            invalidateSelf();
        }
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fillPaint.setColorFilter(colorFilter);
        edgePaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private static int multiplyAlpha(int color, int drawableAlpha, int opacityBoost) {
        int base = Math.min(255, Color.alpha(color) + opacityBoost);
        int out = (base * drawableAlpha + 127) / 255;
        return Color.argb(out, Color.red(color), Color.green(color), Color.blue(color));
    }
}
