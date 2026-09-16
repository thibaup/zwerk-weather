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
                new int[]{multiplyAlpha(topColor, alpha), multiplyAlpha(bottomColor, alpha)},
                null,
                Shader.TileMode.CLAMP));
        edgePaint.setShader(null);
        edgePaint.setColor(multiplyAlpha(edgeColor, alpha));
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

    private static int multiplyAlpha(int color, int drawableAlpha) {
        int base = Color.alpha(color);
        int out = (base * drawableAlpha + 127) / 255;
        return Color.argb(out, Color.red(color), Color.green(color), Color.blue(color));
    }
}
