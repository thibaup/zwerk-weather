package com.zwerk.weather;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/** Shared vector weather glyphs used by both in-app forecast charts and home-screen widgets. */
final class WeatherGlyphDrawer {
    private static final int WHITE = Color.WHITE;
    private static final int ACCENT_YELLOW = Color.rgb(255, 194, 24);
    private static final int ACCENT_BLUE = Color.rgb(176, 226, 255);

    private static final class Scratch {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path bolt = new Path();
        final Path moon = new Path();
        final Path shadow = new Path();
        final RectF cloudRect = new RectF();
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private WeatherGlyphDrawer() { }

    static void draw(Canvas canvas, String condition, boolean daytime, float cx, float cy, float size) {
        String key = WeatherActivityFoundation.conditionKey(condition);
        Scratch s = SCRATCH.get();
        Paint p = s.paint;
        p.reset();
        p.setFlags(Paint.ANTI_ALIAS_FLAG);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);

        if ("clear".equals(key)) {
            if (daytime) {
                drawSun(canvas, p, cx, cy, size * 0.28f);
            } else {
                drawMoonCrescent(canvas, p, cx, cy, size * 0.34f);
            }
            return;
        }

        if ("partly".equals(key)) {
            if (daytime) {
                drawSun(canvas, p, cx + size * 0.18f, cy - size * 0.16f, size * 0.20f);
            } else {
                drawMoonCrescent(canvas, p, cx + size * 0.20f, cy - size * 0.17f, size * 0.22f);
            }
            drawCloud(canvas, p, cx - size * 0.06f, cy + size * 0.08f, size * 0.62f);
            return;
        }

        if ("fog".equals(key)) {
            p.setStyle(Paint.Style.STROKE);
            p.setColor(WHITE);
            p.setStrokeWidth(Math.max(1.2f, size * 0.07f));
            for (int i = -1; i <= 1; i++) {
                float y = cy + i * size * 0.17f;
                canvas.drawLine(cx - size * 0.38f, y, cx + size * 0.38f, y, p);
            }
            return;
        }

        drawCloud(canvas, p, cx, cy - size * 0.04f, size * 0.68f);

        if ("rain".equals(key)) {
            p.setColor(ACCENT_BLUE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.2f, size * 0.065f));
            for (int i = -1; i <= 1; i++) {
                float x = cx + i * size * 0.20f;
                canvas.drawLine(x, cy + size * 0.28f, x - size * 0.05f, cy + size * 0.43f, p);
            }
        } else if ("snow".equals(key)) {
            p.setColor(WHITE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.0f, size * 0.04f));
            for (int i = -1; i <= 1; i++) {
                float x = cx + i * size * 0.21f;
                float y = cy + size * 0.37f;
                canvas.drawLine(x - size * 0.06f, y, x + size * 0.06f, y, p);
                canvas.drawLine(x, y - size * 0.06f, x, y + size * 0.06f, p);
                canvas.drawLine(x - size * 0.045f, y - size * 0.045f, x + size * 0.045f, y + size * 0.045f, p);
                canvas.drawLine(x + size * 0.045f, y - size * 0.045f, x - size * 0.045f, y + size * 0.045f, p);
            }
        } else if ("thunder".equals(key)) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(ACCENT_YELLOW);
            Path bolt = s.bolt;
            bolt.reset();
            bolt.moveTo(cx + size * 0.05f, cy + size * 0.20f);
            bolt.lineTo(cx - size * 0.10f, cy + size * 0.42f);
            bolt.lineTo(cx + size * 0.01f, cy + size * 0.40f);
            bolt.lineTo(cx - size * 0.04f, cy + size * 0.58f);
            bolt.lineTo(cx + size * 0.18f, cy + size * 0.31f);
            bolt.lineTo(cx + size * 0.06f, cy + size * 0.33f);
            bolt.close();
            canvas.drawPath(bolt, p);
        }
    }

    static void drawSun(Canvas canvas, Paint p, float cx, float cy, float r) {
        p.setShader(null);
        p.setColor(ACCENT_YELLOW);
        p.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.1f, r * 0.18f));
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45);
            float x1 = cx + (float) Math.cos(a) * r * 1.35f;
            float y1 = cy + (float) Math.sin(a) * r * 1.35f;
            float x2 = cx + (float) Math.cos(a) * r * 1.80f;
            float y2 = cy + (float) Math.sin(a) * r * 1.80f;
            canvas.drawLine(x1, y1, x2, y2, p);
        }
    }

    static void drawMoonCrescent(Canvas canvas, Paint p, float cx, float cy, float r) {
        Scratch s = SCRATCH.get();
        Path moon = s.moon;
        moon.reset();
        moon.addCircle(cx, cy, r, Path.Direction.CW);
        Path shadow = s.shadow;
        shadow.reset();
        shadow.addCircle(cx + r * 0.42f, cy - r * 0.16f, r * 0.90f, Path.Direction.CW);
        moon.op(shadow, Path.Op.DIFFERENCE);

        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(
                cx - r,
                cy - r,
                cx + r,
                cy + r,
                Color.rgb(255, 252, 225),
                Color.rgb(181, 218, 255),
                Shader.TileMode.CLAMP));
        canvas.drawPath(moon, p);

        int save = canvas.save();
        canvas.clipPath(moon);
        p.setShader(null);
        p.setColor(Color.argb(42, 88, 126, 170));
        canvas.drawCircle(cx - r * 0.28f, cy - r * 0.22f, r * 0.13f, p);
        canvas.drawCircle(cx - r * 0.17f, cy + r * 0.30f, r * 0.09f, p);
        canvas.restoreToCount(save);

        p.setShader(null);
        p.setColor(Color.argb(210, 232, 245, 255));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.0f, r * 0.07f));
        canvas.drawPath(moon, p);
    }

    static void drawCloud(Canvas canvas, Paint p, float cx, float cy, float size) {
        p.setShader(null);
        p.setColor(WHITE);
        p.setStyle(Paint.Style.FILL);
        float h = size * 0.34f;
        RectF rect = SCRATCH.get().cloudRect;
        rect.set(cx - size * 0.46f, cy - h * 0.05f, cx + size * 0.46f, cy + h * 0.62f);
        canvas.drawRoundRect(rect, h, h, p);
        canvas.drawCircle(cx - size * 0.20f, cy - h * 0.06f, h * 0.68f, p);
        canvas.drawCircle(cx + size * 0.07f, cy - h * 0.28f, h * 0.88f, p);
        canvas.drawCircle(cx + size * 0.28f, cy - h * 0.03f, h * 0.58f, p);
    }
}
