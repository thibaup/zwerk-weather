package com.zwerk.weather;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.HashMap;

/**
 * Thread-safe process cache for sky backdrops (RGB_565, optional sampleSize) and shared
 * cover/snow/fog drawing helpers used by SkyLayout, SettingsBackdropView, and ScenePreviewView.
 */
final class SkyAssetCache {
    private static final Object LOCK = new Object();
    private static final HashMap<String, Bitmap> CACHE = new HashMap<>();

    private SkyAssetCache() { }

    static Bitmap get(Context context, String base) {
        return get(context, base, 1);
    }

    static Bitmap get(Context context, String base, int sampleSize) {
        if (context == null) return null;
        String keyBase = normalizeBase(base);
        int safeSample = Math.max(1, sampleSize);
        String cacheKey = keyBase + "@" + safeSample;
        synchronized (LOCK) {
            Bitmap cached = CACHE.get(cacheKey);
            if (cached != null && !cached.isRecycled()) return cached;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inSampleSize = safeSample;
        Bitmap decoded = null;
        try {
            decoded = BitmapFactory.decodeResource(
                    context.getApplicationContext().getResources(),
                    drawableForBase(keyBase),
                    options);
        } catch (OutOfMemoryError ignored) {
            trimMemory();
            try {
                options.inSampleSize = Math.max(2, safeSample * 2);
                decoded = BitmapFactory.decodeResource(
                        context.getApplicationContext().getResources(),
                        drawableForBase(keyBase),
                        options);
            } catch (OutOfMemoryError ignoredAgain) {
                return null;
            }
        }
        if (decoded == null) return null;
        synchronized (LOCK) {
            Bitmap existing = CACHE.get(cacheKey);
            if (existing != null && !existing.isRecycled()) return existing;
            CACHE.put(cacheKey, decoded);
            return decoded;
        }
    }

    /** Clears cached references so GC reclaims unused backdrops without recycling in-flight frames. */
    static void trimMemory() {
        synchronized (LOCK) {
            CACHE.clear();
        }
    }

    static String normalizeBase(String base) {
        if ("night".equals(base)) return "night";
        if ("rain".equals(base)) return "rain";
        if ("snow".equals(base)) return "snow";
        return "day";
    }

    private static int drawableForBase(String base) {
        if ("night".equals(base)) return R.drawable.weather_sky_night;
        if ("rain".equals(base)) return R.drawable.weather_sky_rain;
        if ("snow".equals(base)) return R.drawable.weather_sky_snow;
        return R.drawable.weather_sky_day;
    }

    static void initParticles(float[] particleX, float[] particleY,
            float[] particleSpeed, float[] particleSize) {
        int count = particleX == null ? 0 : particleX.length;
        for (int i = 0; i < count; i++) {
            particleX[i] = ((i * 37) % 97) / 97f;
            if (particleY != null && i < particleY.length) {
                particleY[i] = ((i * 53) % 101) / 101f;
            }
            if (particleSpeed != null && i < particleSpeed.length) {
                particleSpeed[i] = 0.24f + (((i * 29) % 41) / 80f);
            }
            if (particleSize != null && i < particleSize.length) {
                particleSize[i] = 0.65f + (((i * 17) % 23) / 18f);
            }
        }
    }

    static void drawCover(Canvas canvas, Bitmap bitmap, float w, float h, int alpha,
            long now, boolean drift, Rect sourceRect, RectF destinationRect, Paint paint) {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() <= 0
                || bitmap.getHeight() <= 0 || w <= 0f || h <= 0f) {
            return;
        }
        float destinationRatio = w / h;
        float sourceRatio = bitmap.getWidth() / (float) bitmap.getHeight();
        if (sourceRatio > destinationRatio) {
            int sourceWidth = Math.round(bitmap.getHeight() * destinationRatio);
            int left = (bitmap.getWidth() - sourceWidth) / 2;
            sourceRect.set(left, 0, left + sourceWidth, bitmap.getHeight());
        } else {
            int sourceHeight = Math.round(bitmap.getWidth() / destinationRatio);
            int top = (bitmap.getHeight() - sourceHeight) / 2;
            sourceRect.set(0, top, bitmap.getWidth(), top + sourceHeight);
        }
        if (drift) {
            // Overscan the moving sky so its edges stay offscreen.
            float marginX = w * 0.018f;
            float marginY = h * 0.018f;
            float driftX = (float) Math.sin(now * 0.000045) * marginX * 0.70f;
            float driftY = (float) Math.sin(now * 0.000032 + 1.2) * marginY * 0.70f;
            destinationRect.set(-marginX + driftX, -marginY + driftY,
                    w + marginX + driftX, h + marginY + driftY);
        } else {
            destinationRect.set(0f, 0f, w, h);
        }
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(Math.max(0, Math.min(255, alpha)));
        canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint);
        paint.setAlpha(255);
    }

    static Shader[] buildFogBankShaders(float h) {
        Shader[] shaders = new Shader[4];
        if (h <= 0f) return shaders;
        for (int i = 0; i < shaders.length; i++) {
            float topFraction;
            float heightFraction;
            int alpha;
            switch (i) {
                case 0:
                    topFraction = 0.08f;
                    heightFraction = 0.22f;
                    alpha = 68;
                    break;
                case 1:
                    topFraction = 0.28f;
                    heightFraction = 0.25f;
                    alpha = 86;
                    break;
                case 2:
                    topFraction = 0.50f;
                    heightFraction = 0.20f;
                    alpha = 74;
                    break;
                default:
                    topFraction = 0.68f;
                    heightFraction = 0.24f;
                    alpha = 80;
                    break;
            }
            float top = h * topFraction;
            float bankHeight = h * heightFraction;
            shaders[i] = new LinearGradient(
                    0f, top, 0f, top + bankHeight,
                    new int[]{
                            Color.argb(0, 232, 241, 248),
                            Color.argb(alpha, 235, 244, 250),
                            Color.argb(Math.round(alpha * 0.86f), 226, 238, 248),
                            Color.argb(0, 224, 236, 246)
                    },
                    new float[]{0f, 0.34f, 0.70f, 1f},
                    Shader.TileMode.CLAMP);
        }
        return shaders;
    }

    static Shader buildFogWashShader(float h) {
        if (h <= 0f) return null;
        return new LinearGradient(
                0f, 0f, 0f, h,
                new int[]{
                        Color.argb(9, 232, 241, 248),
                        Color.argb(24, 232, 241, 248),
                        Color.argb(14, 224, 236, 246)
                },
                new float[]{0f, 0.57f, 1f},
                Shader.TileMode.CLAMP);
    }

    static void drawSnow(Canvas canvas, float w, float h, long now, float density,
            float[] particleX, float[] particleY, float[] particleSpeed, float[] particleSize,
            Paint paint) {
        if (w <= 0f || h <= 0f || particleX == null || particleY == null || particleSpeed == null) {
            return;
        }
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(178, 255, 255, 255));
        float seconds = now / 1000f;
        int count = Math.min(particleX.length, Math.min(particleY.length, particleSpeed.length));
        float swayAmp = 9f * density;
        for (int i = 0; i < count; i++) {
            float base = particleX[i] * w;
            float x = base + (float) Math.sin(seconds * 0.7f + i) * swayAmp;
            float y = ((particleY[i] + seconds * particleSpeed[i] * 0.17f) % 1.08f) * h - h * 0.04f;
            float sizeFactor = particleSize != null && i < particleSize.length ? particleSize[i] : 1f;
            canvas.drawCircle(x, y, sizeFactor * 1.35f * density, paint);
        }
    }

    static void drawFog(Canvas canvas, float w, float h, long now,
            Shader[] fogBankShaders, Shader fogWashShader, Path fogPath, Paint paint) {
        if (w <= 0f || h <= 0f || fogBankShaders == null || fogPath == null) return;
        float seconds = now / 1000f;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < fogBankShaders.length; i++) {
            if (fogBankShaders[i] == null) continue;
            float topFraction;
            float heightFraction;
            float speed;
            switch (i) {
                case 0:
                    topFraction = 0.08f;
                    heightFraction = 0.22f;
                    speed = 0.055f;
                    break;
                case 1:
                    topFraction = 0.28f;
                    heightFraction = 0.25f;
                    speed = -0.038f;
                    break;
                case 2:
                    topFraction = 0.50f;
                    heightFraction = 0.20f;
                    speed = 0.031f;
                    break;
                default:
                    topFraction = 0.68f;
                    heightFraction = 0.24f;
                    speed = -0.024f;
                    break;
            }
            float top = h * topFraction;
            float bankHeight = h * heightFraction;
            float drift = (float) Math.sin(seconds * speed + i * 1.7f) * w * 0.13f;
            float left = -w * 0.48f + drift;
            float right = w * 1.48f + drift;

            fogPath.reset();
            fogPath.moveTo(left, top + bankHeight * 0.34f);
            fogPath.cubicTo(left + w * 0.36f, top - bankHeight * 0.05f,
                    left + w * 0.68f, top + bankHeight * 0.05f,
                    left + w, top + bankHeight * 0.22f);
            fogPath.cubicTo(left + w * 1.24f, top + bankHeight * 0.34f,
                    left + w * 1.58f, top - bankHeight * 0.02f,
                    right, top + bankHeight * 0.26f);
            fogPath.lineTo(right, top + bankHeight * 0.82f);
            fogPath.cubicTo(left + w * 1.56f, top + bankHeight * 1.00f,
                    left + w * 1.28f, top + bankHeight * 0.72f,
                    left + w, top + bankHeight * 0.80f);
            fogPath.cubicTo(left + w * 0.70f, top + bankHeight * 0.96f,
                    left + w * 0.34f, top + bankHeight * 0.73f,
                    left, top + bankHeight * 0.84f);
            fogPath.close();
            paint.setShader(fogBankShaders[i]);
            canvas.drawPath(fogPath, paint);
        }
        if (fogWashShader != null) {
            paint.setShader(fogWashShader);
            canvas.drawRect(0f, 0f, w, h, paint);
        }
        paint.setShader(null);
        paint.setAlpha(255);
    }

    static void drawSceneAtmosphere(Canvas canvas, int width, int height, String scene,
            boolean daytime, float phase, float density, Paint paint, Path fogPath,
            Shader[] fogBankShaders, Shader fogWashShader,
            float[] particleX, float[] particleY, float[] particleSpeed, float[] particleSize) {
        if (width <= 0 || height <= 0 || scene == null) return;
        long now = Math.round(phase * 1000.0);
        if ("snow".equals(scene)) {
            drawSnow(canvas, width, height, now, density,
                    particleX, particleY, particleSpeed, particleSize, paint);
        } else if ("fog".equals(scene)) {
            drawFog(canvas, width, height, now, fogBankShaders, fogWashShader, fogPath, paint);
        }
    }
}
