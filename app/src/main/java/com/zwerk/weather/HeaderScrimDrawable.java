package com.zwerk.weather;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

final class HeaderScrimDrawable extends Drawable {
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint depthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float scrollDepth;
    private int red = 8;
    private int green = 55;
    private int blue = 120;
    private int alpha = 255;

    HeaderScrimDrawable() {
        basePaint.setStyle(Paint.Style.FILL);
        depthPaint.setStyle(Paint.Style.FILL);
    }

    void setScene(String scene) {
        int nextRed;
        int nextGreen;
        int nextBlue;
        if ("night".equals(scene)) {
            nextRed = 3;
            nextGreen = 13;
            nextBlue = 31;
        } else if ("rain".equals(scene)) {
            nextRed = 26;
            nextGreen = 42;
            nextBlue = 55;
        } else if ("snow".equals(scene)) {
            nextRed = 58;
            nextGreen = 84;
            nextBlue = 108;
        } else {
            nextRed = 8;
            nextGreen = 55;
            nextBlue = 120;
        }
        if (red == nextRed && green == nextGreen && blue == nextBlue) return;
        red = nextRed;
        green = nextGreen;
        blue = nextBlue;
        rebuildShaders();
        invalidateSelf();
    }

    void setScrollDepth(float depth) {
        float next = Math.max(0f, Math.min(1f, depth));
        if (Math.abs(next - scrollDepth) < 0.004f) return;
        scrollDepth = next;
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        rebuildShaders();
    }

    private void rebuildShaders() {
        Rect bounds = getBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        float top = bounds.top;
        float bottom = bounds.bottom;
        basePaint.setShader(new LinearGradient(
                0,
                top,
                0,
                bottom,
                new int[]{
                        Color.argb(52, red, green, blue),
                        Color.argb(22, red, green, blue),
                        Color.argb(0, red, green, blue)
                },
                new float[]{0f, 0.62f, 1f},
                Shader.TileMode.CLAMP));
        depthPaint.setShader(new LinearGradient(
                0,
                top,
                0,
                bottom,
                new int[]{
                        Color.argb(128, red, green, blue),
                        Color.argb(82, red, green, blue),
                        Color.argb(0, red, green, blue)
                },
                new float[]{0f, 0.62f, 1f},
                Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        basePaint.setAlpha(alpha);
        depthPaint.setAlpha(Math.round(alpha * scrollDepth));
        canvas.drawRect(bounds, basePaint);
        if (scrollDepth > 0f) {
            canvas.drawRect(bounds, depthPaint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        int next = Math.max(0, Math.min(255, alpha));
        if (this.alpha != next) {
            this.alpha = next;
            invalidateSelf();
        }
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        basePaint.setColorFilter(colorFilter);
        depthPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
