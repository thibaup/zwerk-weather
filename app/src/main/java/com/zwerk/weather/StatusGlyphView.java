package com.zwerk.weather;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

final class StatusGlyphView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    StatusGlyphView(Context context) {
        super(context);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        p.setColor(Color.argb(196, 255, 255, 255));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1.35f));

        RectF cloud = new RectF(w * 0.14f, h * 0.38f, w * 0.86f, h * 0.76f);
        canvas.drawArc(cloud, 5f, 170f, false, p);
        canvas.drawArc(new RectF(w * 0.30f, h * 0.18f, w * 0.66f, h * 0.58f), 182f, 176f, false, p);
        canvas.drawLine(w * 0.14f, h * 0.60f, w * 0.14f, h * 0.66f, p);
        canvas.drawLine(w * 0.86f, h * 0.59f, w * 0.86f, h * 0.66f, p);
        canvas.drawLine(w * 0.18f, h * 0.74f, w * 0.80f, h * 0.74f, p);

        p.setStrokeWidth(dp(1.5f));
        path.reset();
        path.moveTo(w * 0.38f, h * 0.56f);
        path.lineTo(w * 0.47f, h * 0.65f);
        path.lineTo(w * 0.64f, h * 0.48f);
        canvas.drawPath(path, p);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
