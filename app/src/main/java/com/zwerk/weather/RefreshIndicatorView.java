package com.zwerk.weather;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

final class RefreshIndicatorView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path arrow = new Path();
    private float pullFraction;
    private boolean releaseReady;
    private boolean refreshing;
    private int topColor = Color.argb(150, 72, 132, 205);
    private int bottomColor = Color.argb(128, 72, 118, 174);
    private int accentColor = ACCENT_BLUE;
    private long refreshStarted;

    RefreshIndicatorView(Context context) {
        super(context);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textPaint.setTextAlign(Paint.Align.LEFT);
        setContentDescription("Pull to refresh");
    }

    boolean isRefreshing() {
        return refreshing;
    }

    void setPalette(int top, int bottom, int accent) {
        topColor = withMinimumAlpha(top, 152);
        bottomColor = withMinimumAlpha(bottom, 126);
        accentColor = accent;
        invalidate();
    }

    void beginPull() {
        if (refreshing) return;
        animate().cancel();
        setVisibility(View.VISIBLE);
        setAlpha(0f);
        pullFraction = 0f;
        releaseReady = false;
        setContentDescription("Pull to refresh");
    }

    void setPull(float fraction, boolean ready) {
        if (refreshing) return;
        animate().cancel();
        setVisibility(View.VISIBLE);
        pullFraction = Math.max(0f, Math.min(1.28f, fraction));
        setAlpha(Math.min(1f, 0.22f + pullFraction * 0.78f));
        setTranslationY(dp(10) * (1f - Math.min(1f, pullFraction)));
        if (releaseReady != ready) {
            releaseReady = ready;
            setContentDescription(ready ? "Release to refresh" : "Pull to refresh");
            if (ready && isShown()) announceForAccessibility("Release to refresh");
        }
        invalidate();
    }

    void setRefreshing(boolean value) {
        refreshing = value;
        if (value) {
            releaseReady = false;
            pullFraction = 1f;
            refreshStarted = SystemClock.uptimeMillis();
            animate().cancel();
            setVisibility(View.VISIBLE);
            setAlpha(1f);
            setTranslationY(0f);
            setContentDescription("Refreshing weather");
            invalidate();
            postInvalidateOnAnimation();
        } else {
            settle();
        }
    }

    void finish() {
        refreshing = false;
        releaseReady = false;
        pullFraction = 0f;
        setContentDescription("Pull to refresh");
        settle();
    }

    void settle() {
        if (refreshing || getVisibility() != View.VISIBLE) return;
        animate().cancel();
        animate()
                .alpha(0f)
                .translationY(-dp(8))
                .setDuration(170L)
                .withEndAction(() -> {
                    if (!refreshing) {
                        setVisibility(View.INVISIBLE);
                        setTranslationY(0f);
                    }
                })
                .start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        rect.set(dp(1), dp(1), w - dp(1), h - dp(1));
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(
                0, 0, 0, h,
                topColor, bottomColor,
                Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, h * 0.48f, h * 0.48f, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.argb(48, 255, 255, 255));
        canvas.drawRoundRect(rect, h * 0.48f, h * 0.48f, paint);

        float cx = dp(23);
        float cy = h / 2f;
        paint.setStrokeWidth(dp(1.7f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(accentColor);
        paint.setStyle(Paint.Style.STROKE);

        if (refreshing) {
            float start = ((SystemClock.uptimeMillis() - refreshStarted) % 900L) / 900f * 360f - 90f;
            canvas.drawArc(new RectF(cx - dp(7), cy - dp(7), cx + dp(7), cy + dp(7)),
                    start, 250f, false, paint);
            postInvalidateOnAnimation();
        } else {
            float rotation = Math.min(1f, pullFraction) * 180f;
            int save = canvas.save();
            canvas.rotate(rotation, cx, cy);
            arrow.reset();
            arrow.moveTo(cx, cy - dp(7));
            arrow.lineTo(cx, cy + dp(5));
            arrow.moveTo(cx, cy + dp(5));
            arrow.lineTo(cx - dp(4), cy + dp(1));
            arrow.moveTo(cx, cy + dp(5));
            arrow.lineTo(cx + dp(4), cy + dp(1));
            canvas.drawPath(arrow, paint);
            canvas.restoreToCount(save);
        }

        String label = refreshing
                ? "Refreshing…"
                : releaseReady ? "Release to refresh" : "Pull to refresh";
        textPaint.setColor(WHITE);
        textPaint.setTextSize(dp(11.5f));
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float baseline = cy - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(label, dp(38), baseline, textPaint);
    }

    private int withMinimumAlpha(int color, int minimum) {
        return Color.argb(
                Math.max(minimum, Color.alpha(color)),
                Color.red(color),
                Color.green(color),
                Color.blue(color));
    }

    private static final int WHITE = Color.WHITE;
    private static final int ACCENT_BLUE = Color.rgb(176, 226, 255);

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
