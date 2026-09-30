package com.zwerk.weather;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class NavigationIconView extends View {
    static final int OVERVIEW = 0, FORECAST = 1, RAIN = 2, RADAR = 3, SETTINGS = 4;
    private final int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    NavigationIconView(Context context, int kind) {
        super(context);
        this.kind = kind;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override protected void onDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.scale(getWidth() / 24f, getHeight() / 24f);
        paint.setStrokeWidth(1.6f);
        paint.setColor(isSelected() ? Color.WHITE : Color.argb(185, 255, 255, 255));
        if (kind == OVERVIEW) {
            canvas.drawCircle(12, 12, 4, paint);
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4;
                canvas.drawLine(12 + (float) Math.cos(angle) * 7, 12 + (float) Math.sin(angle) * 7,
                        12 + (float) Math.cos(angle) * 9, 12 + (float) Math.sin(angle) * 9, paint);
            }
        } else if (kind == FORECAST) {
            canvas.drawRoundRect(3, 5, 21, 21, 4, 4, paint);
            canvas.drawLine(3, 10, 21, 10, paint);
            canvas.drawLine(8, 3, 8, 7, paint);
            canvas.drawLine(16, 3, 16, 7, paint);
            canvas.drawLine(7, 14, 10, 14, paint);
            canvas.drawLine(14, 14, 17, 14, paint);
            canvas.drawLine(7, 17, 10, 17, paint);
        } else if (kind == RAIN) {
            path.reset();
            path.moveTo(12, 3);
            path.cubicTo(10, 6, 6, 10, 6, 14);
            path.cubicTo(6, 22, 18, 22, 18, 14);
            path.cubicTo(18, 10, 14, 6, 12, 3);
            path.close();
            canvas.drawPath(path, paint);
            canvas.drawArc(9, 12, 15, 18, 80, 70, false, paint);
        } else if (kind == RADAR) {
            canvas.drawCircle(12, 12, 9, paint);
            canvas.drawCircle(12, 12, 5, paint);
            canvas.drawLine(12, 12, 18.4f, 5.6f, paint);
            canvas.drawCircle(12, 12, 0.8f, paint);
        } else {
            canvas.drawCircle(12, 12, 6, paint);
            canvas.drawCircle(12, 12, 2.3f, paint);
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4;
                canvas.drawLine(12 + (float) Math.cos(angle) * 6.4f, 12 + (float) Math.sin(angle) * 6.4f,
                        12 + (float) Math.cos(angle) * 8.5f, 12 + (float) Math.sin(angle) * 8.5f, paint);
            }
        }
        canvas.restoreToCount(save);
    }

    @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }
}
