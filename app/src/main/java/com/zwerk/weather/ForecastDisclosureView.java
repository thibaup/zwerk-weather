package com.zwerk.weather;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

final class ForecastDisclosureView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final boolean compact;
    private boolean expanded;

    ForecastDisclosureView(Context context) {
        this(context, false);
    }

    ForecastDisclosureView(Context context, boolean compact) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        this.compact = compact;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setExpanded(boolean value) {
        if (expanded == value) return;
        expanded = value;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float x = getWidth() / 2f;
        float y = getHeight() / 2f;
        if (!compact) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(expanded ? 34 : 20, 255, 255, 255));
            canvas.drawCircle(x, y, 12 * density, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(.7f * density);
            paint.setColor(Color.argb(expanded ? 90 : 48, 255, 255, 255));
            canvas.drawCircle(x, y, 12 * density, paint);
        }
        drawChevron(canvas, x, y, expanded, density, paint);
    }

    static void drawChevron(Canvas canvas, float x, float y, boolean expanded,
            float density, Paint paint) {
        float direction = expanded ? -1f : 1f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(Color.argb(230, 255, 255, 255));
        canvas.drawLine(x - 4 * density, y - direction * 2 * density,
                x, y + direction * 2 * density, paint);
        canvas.drawLine(x, y + direction * 2 * density,
                x + 4 * density, y - direction * 2 * density, paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
