package com.zwerk.weather;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.LinearInterpolator;

final class RadarControlStyle {
    private RadarControlStyle() { }

    static GlassDrawable background(Context context) {
        int transparency = new WeatherPreferences(context).tileTransparency();
        int top = Color.argb(74, 73, 133, 198);
        int bottom = Color.argb(42, 67, 112, 167);
        int edge = Color.argb(26, 255, 255, 255);
        if (context instanceof WeatherActivityFoundation) {
            WeatherActivityFoundation activity = (WeatherActivityFoundation) context;
            top = activity.glassTileTop;
            bottom = activity.glassTileBottom;
            edge = activity.glassEdge;
        }
        float density = context.getResources().getDisplayMetrics().density;
        GlassDrawable drawable = new GlassDrawable(22 * density, Math.max(1f, density), true);
        drawable.setColors(top, bottom, edge);
        // Map labels need a little more cover than the weather scene behind ordinary tiles.
        drawable.setFillOpacityBoost(36);
        drawable.setTransparency(transparency);
        return drawable;
    }
}

abstract class RadarIconButton extends View {
    final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF iconBounds = new RectF();

    RadarIconButton(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setBackground(RadarControlStyle.background(context));
    }

    final int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

final class RadarRefreshButton extends RadarIconButton {
    private final Path arrow = new Path();
    private ValueAnimator spinner;
    private float rotation;

    RadarRefreshButton(Context context) {
        super(context);
    }

    void setLoading(boolean loading) {
        if (loading) {
            if (spinner != null && spinner.isRunning()) return;
            spinner = ValueAnimator.ofFloat(0f, 360f);
            spinner.setDuration(850L);
            spinner.setInterpolator(new LinearInterpolator());
            spinner.setRepeatCount(ValueAnimator.INFINITE);
            spinner.setRepeatMode(ValueAnimator.RESTART);
            spinner.addUpdateListener(animation -> {
                rotation = (float) animation.getAnimatedValue();
                invalidate();
            });
            spinner.start();
        } else {
            if (spinner != null) {
                spinner.cancel();
                spinner = null;
            }
            rotation = 0f;
            invalidate();
        }
    }

    @Override protected void onDetachedFromWindow() {
        setLoading(false);
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        // Keep the backdrop stationary while the refresh glyph spins.
        int save = canvas.save();
        canvas.rotate(rotation, cx, cy);
        float radius = dp(9f);
        icon.setColor(Color.WHITE);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeWidth(dp(2.6f));
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeJoin(Paint.Join.ROUND);
        iconBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(iconBounds, -55f, 285f, false, icon);

        double end = Math.toRadians(230d);
        float tipX = cx + (float) Math.cos(end) * radius;
        float tipY = cy + (float) Math.sin(end) * radius;
        float tangentX = (float) -Math.sin(end);
        float tangentY = (float) Math.cos(end);
        float normalX = (float) Math.cos(end);
        float normalY = (float) Math.sin(end);
        float backX = tipX - tangentX * dp(6f);
        float backY = tipY - tangentY * dp(6f);
        arrow.reset();
        arrow.moveTo(tipX, tipY);
        arrow.lineTo(backX + normalX * dp(3f), backY + normalY * dp(3f));
        arrow.moveTo(tipX, tipY);
        arrow.lineTo(backX - normalX * dp(3f), backY - normalY * dp(3f));
        canvas.drawPath(arrow, icon);
        canvas.restoreToCount(save);
    }

}

final class RadarPlaybackButton extends RadarIconButton {
    static final int PLAY = 0;
    static final int PAUSE = 1;
    static final int LOADING = 2;
    private final Path triangle = new Path();
    private int mode = PLAY;

    RadarPlaybackButton(Context context) {
        super(context);
        setMode(PLAY);
    }

    void setMode(int next) {
        mode = next;
        setContentDescription(UiTranslations.text(getContext(), next == PAUSE
                ? "Pause radar animation"
                : next == LOADING ? "Radar animation loading. Tap to cancel playback."
                : "Play radar animation"));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        icon.setColor(Color.WHITE);
        icon.setStyle(Paint.Style.FILL);
        if (mode == PAUSE) {
            canvas.drawRoundRect(cx - dp(7), cy - dp(9), cx - dp(2), cy + dp(9),
                    dp(1.5f), dp(1.5f), icon);
            canvas.drawRoundRect(cx + dp(2), cy - dp(9), cx + dp(7), cy + dp(9),
                    dp(1.5f), dp(1.5f), icon);
        } else if (mode == LOADING) {
            icon.setStyle(Paint.Style.STROKE);
            icon.setStrokeWidth(dp(3));
            icon.setStrokeCap(Paint.Cap.ROUND);
            float turn = (SystemClock.uptimeMillis() % 1000L) * 0.36f;
            iconBounds.set(cx - dp(9), cy - dp(9), cx + dp(9), cy + dp(9));
            canvas.drawArc(iconBounds, turn, 250f, false, icon);
            postInvalidateDelayed(32L);
        } else {
            triangle.reset();
            triangle.moveTo(cx - dp(5), cy - dp(9));
            triangle.lineTo(cx + dp(8), cy);
            triangle.lineTo(cx - dp(5), cy + dp(9));
            triangle.close();
            canvas.drawPath(triangle, icon);
        }
    }

}
