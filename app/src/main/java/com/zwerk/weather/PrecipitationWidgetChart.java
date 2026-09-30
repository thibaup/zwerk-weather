package com.zwerk.weather;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

final class PrecipitationWidgetChart {
    private PrecipitationWidgetChart() { }

    static Bitmap render(PrecipitationWidgetData data, int widthDp, int heightDp,
            String[] timeLabels, String emptyLabel) {
        // Two pixels per dp keep bars and lettering sharp without large RemoteViews parcels.
        int width = Math.max(160, Math.min(600, widthDp));
        int height = Math.max(32, Math.min(280, heightDp));
        Bitmap bitmap = Bitmap.createBitmap(width * 2, height * 2, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(2f, 2f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        paint.setTextSize(10f);
        float left = 43f;
        float right = width - 4f;
        float top = 6f;
        float bottom = height - 17f;
        float plotHeight = bottom - top;
        paint.setStrokeWidth(0.7f);
        double maximum = PrecipitationWidgetData.chartMaximum(data.peakMm);
        for (int tick : new int[]{2, 1, 0}) {
            float y = bottom - plotHeight * tick / 2f;
            paint.setColor(Color.argb(tick == 0 ? 140 : 65, 235, 249, 255));
            canvas.drawLine(left, y, right, y, paint);
            if (tick != 1 || plotHeight >= 36f) {
                paint.setColor(Color.argb(225, 235, 249, 255));
                paint.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(PrecipitationWidgetData.amountLabel(maximum * tick / 2d)
                        + (tick == 2 ? " mm" : ""), left - 4f, y + 3f, paint);
            }
        }
        for (int tick = 0; tick <= 6; tick++) {
            float x = left + (right - left) * tick / 6f;
            paint.setColor(Color.argb(38, 235, 249, 255));
            canvas.drawLine(x, top, x, bottom, paint);
        }

        float cell = (right - left) / PrecipitationWidgetData.BIN_COUNT;
        float gap = Math.max(0.8f, Math.min(2.5f, cell * 0.24f));
        RectF bar = new RectF();
        Shader fill = new LinearGradient(0f, top, 0f, bottom,
                Color.rgb(210, 250, 255), Color.rgb(77, 183, 244), Shader.TileMode.CLAMP);
        for (int i = 0; i < data.amountsMm.length; i++) {
            float x = left + i * cell + gap / 2f;
            float end = left + (i + 1) * cell - gap / 2f;
            double amount = data.amountsMm[i];
            if (Double.isFinite(amount) && amount > 0d) {
                paint.setShader(fill);
                paint.setAlpha(255);
                paint.setStyle(Paint.Style.FILL);
                float barTop = bottom - plotHeight * (float) (amount / maximum);
                bar.set(x, barTop, end, bottom);
                canvas.drawRoundRect(bar, 1.2f, 1.2f, paint);
            }
        }
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(235, 235, 249, 255));
        paint.setTextSize(11f);
        for (int tick = 0; tick < 3; tick++) {
            float x = left + (right - left) * tick / 2f;
            paint.setTextAlign(tick == 0 ? Paint.Align.LEFT
                    : tick == 2 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            canvas.drawText(timeLabels[tick], x, height - 2f, paint);
        }
        if (data.knownBins == 0) {
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(11f);
            canvas.drawText(emptyLabel, (left + right) / 2f,
                    top + plotHeight / 2f + 3f, paint);
        }
        return bitmap;
    }
}
