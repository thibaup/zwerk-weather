package com.zwerk.weather;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

final class HeaderGlyphButton extends View {
    static final int MENU_PLUS = 1;
    static final int SETTINGS = 2;
    private final int kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    HeaderGlyphButton(Context context, int kind) {
        super(context);
        this.kind = kind;
        setClickable(true);
        setFocusable(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        if (isPressed()) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(28, 255, 255, 255));
            canvas.drawCircle(cx, cy, Math.min(w, h) * 0.42f, p);
        }
        p.setColor(WHITE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(dp(2));

        if (kind == MENU_PLUS) {
            float left = w * 0.22f;
            float right = w * 0.66f;
            canvas.drawLine(left, h * 0.30f, right, h * 0.30f, p);
            canvas.drawLine(left, h * 0.48f, w * 0.57f, h * 0.48f, p);
            canvas.drawLine(left, h * 0.66f, w * 0.49f, h * 0.66f, p);
            canvas.drawLine(w * 0.68f, h * 0.55f, w * 0.68f, h * 0.80f, p);
            canvas.drawLine(w * 0.56f, h * 0.675f, w * 0.80f, h * 0.675f, p);
        } else {
            float r = Math.min(w, h) * 0.28f;
            canvas.drawCircle(cx, cy, r, p);
            canvas.drawCircle(cx, cy, r * 0.38f, p);
            for (int i = 0; i < 8; i++) {
                double a = Math.toRadians(i * 45);
                float x1 = cx + (float) Math.cos(a) * r * 1.06f;
                float y1 = cy + (float) Math.sin(a) * r * 1.06f;
                float x2 = cx + (float) Math.cos(a) * r * 1.34f;
                float y2 = cy + (float) Math.sin(a) * r * 1.34f;
                canvas.drawLine(x1, y1, x2, y2, p);
            }
        }
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    private static final int WHITE = Color.WHITE;

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
