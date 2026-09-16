package com.zwerk.weather;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

final class DetailGlyphView extends View {
    private final String kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    DetailGlyphView(Context context, String kind) {
        super(context);
        this.kind = kind;
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float s = Math.min(w, h);
        p.setShader(null);
        p.setColor(WHITE);
        p.setStrokeWidth(Math.max(2f, s * 0.065f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStyle(Paint.Style.STROKE);

        switch (kind) {
            case "uv":
                canvas.drawCircle(cx, cy, s * 0.18f, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.toRadians(i * 45);
                    canvas.drawLine(
                            cx + (float) Math.cos(a) * s * 0.30f,
                            cy + (float) Math.sin(a) * s * 0.30f,
                            cx + (float) Math.cos(a) * s * 0.43f,
                            cy + (float) Math.sin(a) * s * 0.43f, p);
                }
                break;
            case "temperature":
                canvas.drawRoundRect(new RectF(cx - s * 0.10f, cy - s * 0.36f, cx + s * 0.10f, cy + s * 0.18f), s * 0.10f, s * 0.10f, p);
                canvas.drawCircle(cx, cy + s * 0.27f, s * 0.18f, p);
                canvas.drawLine(cx, cy - s * 0.22f, cx, cy + s * 0.22f, p);
                break;
            case "humidity": {
                Path drop = new Path();
                drop.moveTo(cx, cy - s * 0.40f);
                drop.cubicTo(cx - s * 0.26f, cy - s * 0.08f, cx - s * 0.30f, cy + s * 0.14f, cx, cy + s * 0.38f);
                drop.cubicTo(cx + s * 0.30f, cy + s * 0.14f, cx + s * 0.26f, cy - s * 0.08f, cx, cy - s * 0.40f);
                canvas.drawPath(drop, p);
                break;
            }
            case "wind":
                canvas.drawLine(cx - s * 0.40f, cy - s * 0.18f, cx + s * 0.17f, cy - s * 0.18f, p);
                canvas.drawArc(new RectF(cx + s * 0.02f, cy - s * 0.33f, cx + s * 0.34f, cy - s * 0.02f), -90, 180, false, p);
                canvas.drawLine(cx - s * 0.40f, cy + s * 0.06f, cx + s * 0.28f, cy + s * 0.06f, p);
                canvas.drawLine(cx - s * 0.24f, cy + s * 0.28f, cx + s * 0.09f, cy + s * 0.28f, p);
                break;
            case "pressure":
                canvas.drawLine(cx, cy - s * 0.40f, cx, cy + s * 0.40f, p);
                canvas.drawLine(cx, cy - s * 0.40f, cx - s * 0.10f, cy - s * 0.27f, p);
                canvas.drawLine(cx, cy - s * 0.40f, cx + s * 0.10f, cy - s * 0.27f, p);
                canvas.drawLine(cx - s * 0.38f, cy + s * 0.10f, cx - s * 0.12f, cy + s * 0.10f, p);
                canvas.drawLine(cx + s * 0.12f, cy + s * 0.10f, cx + s * 0.38f, cy + s * 0.10f, p);
                break;
            case "visibility":
                canvas.drawOval(new RectF(cx - s * 0.40f, cy - s * 0.24f, cx + s * 0.40f, cy + s * 0.24f), p);
                canvas.drawCircle(cx, cy, s * 0.11f, p);
                break;
            case "air":
                canvas.drawArc(new RectF(cx - s * 0.38f, cy - s * 0.17f, cx + s * 0.18f, cy + s * 0.20f), 180, 180, false, p);
                canvas.drawArc(new RectF(cx - s * 0.05f, cy - s * 0.28f, cx + s * 0.38f, cy + s * 0.06f), 180, 180, false, p);
                canvas.drawLine(cx - s * 0.35f, cy + s * 0.28f, cx + s * 0.30f, cy + s * 0.28f, p);
                break;
            case "pollen": {
                Path leaf = new Path();
                leaf.moveTo(cx - s * 0.30f, cy + s * 0.25f);
                leaf.cubicTo(cx - s * 0.16f, cy - s * 0.34f, cx + s * 0.34f, cy - s * 0.34f, cx + s * 0.28f, cy + s * 0.18f);
                leaf.cubicTo(cx + s * 0.02f, cy + s * 0.38f, cx - s * 0.18f, cy + s * 0.34f, cx - s * 0.30f, cy + s * 0.25f);
                canvas.drawPath(leaf, p);
                canvas.drawLine(cx - s * 0.20f, cy + s * 0.22f, cx + s * 0.18f, cy - s * 0.16f, p);
                canvas.drawCircle(cx + s * 0.31f, cy + s * 0.28f, s * 0.055f, p);
                break;
            }
            default:
                canvas.drawCircle(cx, cy, s * 0.28f, p);
        }
    }

    private static final int WHITE = Color.WHITE;
}
