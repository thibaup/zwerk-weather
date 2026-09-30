package com.zwerk.weather;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/** Procedural, time-based atmosphere shared by the forecast and settings previews. */
final class WeatherAtmosphereRenderer {
    private static final int CLOUD_WIDTH = 384;
    private static final int CLOUD_HEIGHT = 144;
    private static final long[] LIGHTNING_INTERVALS = {6700L, 9100L, 4800L, 10700L, 7300L};
    private static final long LIGHTNING_CYCLE = 38600L;
    private static Bitmap farClouds;
    private static Bitmap nearClouds;

    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint cloudPaint = new Paint(
            Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path[] rainPaths = {new Path(), new Path(), new Path()};
    private final Path boltPath = new Path();
    private final Path branchPath = new Path();
    private final RectF cloudDestination = new RectF();
    private final LightingColorFilter daylightClouds = new LightingColorFilter(
            Color.rgb(255, 251, 245), 0);
    private final LightingColorFilter daylightCloudShadows = new LightingColorFilter(
            Color.rgb(105, 133, 169), 0);
    private final LightingColorFilter moonlitClouds = new LightingColorFilter(
            Color.rgb(150, 179, 221), 0);
    private final LightingColorFilter nightCloudShadows = new LightingColorFilter(
            Color.rgb(39, 63, 105), 0);
    private final LightingColorFilter rainClouds = new LightingColorFilter(
            Color.rgb(166, 189, 207), 0);
    private final LightingColorFilter stormClouds = new LightingColorFilter(
            Color.rgb(103, 128, 163), 0);
    private final LightingColorFilter snowClouds = new LightingColorFilter(
            Color.rgb(205, 226, 240), 0);

    private int width;
    private int height;
    private Shader dayGlow;
    private Shader moonGlow;
    private Shader stormVeil;
    private Shader rainHaze;
    private Shader lightningGlow;
    private long cachedLightningEvent = Long.MIN_VALUE;
    private float lightningX;
    private float lightningY;

    WeatherAtmosphereRenderer(float density) {
        this.density = Math.max(1f, density);
        ensureCloudTextures();
    }

    void onSizeChanged(int w, int h) {
        if (w <= 0 || h <= 0 || (width == w && height == h)) return;
        width = w;
        height = h;
        dayGlow = new RadialGradient(w * 0.91f, h * 0.11f, h * 0.43f,
                new int[]{Color.argb(80, 255, 241, 202), Color.argb(30, 255, 236, 196),
                        Color.TRANSPARENT},
                new float[]{0f, 0.36f, 1f}, Shader.TileMode.CLAMP);
        moonGlow = new RadialGradient(w * 0.92f, h * 0.12f, h * 0.33f,
                new int[]{Color.argb(52, 202, 221, 255), Color.argb(20, 160, 195, 245),
                        Color.TRANSPARENT},
                new float[]{0f, 0.40f, 1f}, Shader.TileMode.CLAMP);
        stormVeil = new LinearGradient(0, 0, 0, h,
                new int[]{Color.argb(72, 5, 11, 28), Color.argb(29, 10, 25, 52),
                        Color.argb(78, 2, 8, 25)},
                new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP);
        rainHaze = new LinearGradient(0, h * 0.18f, 0, h,
                new int[]{Color.TRANSPARENT, Color.argb(12, 202, 225, 245),
                        Color.argb(31, 165, 194, 222)},
                new float[]{0f, 0.58f, 1f}, Shader.TileMode.CLAMP);
        cachedLightningEvent = Long.MIN_VALUE;
    }

    void drawAmbient(Canvas canvas, float w, float h, long now, SceneSpec spec) {
        if (spec == null || w <= 0f || h <= 0f) return;
        onSizeChanged(Math.round(w), Math.round(h));
        String effect = spec.effect;
        boolean storm = "thunder".equals(effect);
        boolean rain = "rain".equals(effect);
        boolean snow = "snow".equals(effect);
        boolean fog = "fog".equals(effect);
        boolean cloud = "cloud".equals(effect);
        boolean partly = "partly".equals(effect);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        if (storm) {
            paint.setShader(stormVeil);
            canvas.drawRect(0, 0, w, h, paint);
        } else if (!rain && !fog && !snow) {
            paint.setShader(spec.daytime ? dayGlow : moonGlow);
            float pulse = 0.94f + 0.06f * (float) Math.sin(now * 0.00035);
            paint.setAlpha(Math.round(255f * pulse));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setAlpha(255);
        }
        paint.setShader(null);

        if (!spec.daytime && !rain && !storm && !fog && !snow) {
            drawStars(canvas, w, h, now, cloud ? 0.32f : 1f);
        }

        LightingColorFilter filter = storm ? stormClouds
                : rain || fog ? rainClouds
                : snow ? snowClouds
                : spec.daytime ? daylightClouds : moonlitClouds;
        LightingColorFilter shadowFilter = spec.daytime
                ? daylightCloudShadows : nightCloudShadows;
        int farAlpha = storm ? 174 : rain ? 145 : snow ? 135 : fog ? 73
                : cloud ? 138 : partly ? 105 : 37;
        int nearAlpha = storm ? 162 : rain ? 132 : snow ? 118 : fog ? 58
                : cloud ? 123 : partly ? 84 : 25;
        drawCloudBand(canvas, farClouds, filter, shadowFilter, farAlpha, w, h,
                -0.06f, 0.32f, 1.55f, 0.0f, 21.0f, now);
        drawCloudBand(canvas, nearClouds, filter, shadowFilter, nearAlpha, w, h,
                0.27f, 0.31f, 1.70f, 0.38f, -34.0f, now);
        if (storm || rain || cloud || snow || fog) {
            drawCloudBand(canvas, farClouds, filter, shadowFilter,
                    Math.round(nearAlpha * 0.76f), w, h,
                    0.68f, 0.30f, 1.42f, 0.72f, 17.0f, now);
        }
    }

    void drawRain(Canvas canvas, float w, float h, long now, SceneSpec spec) {
        if (w <= 0f || h <= 0f) return;
        onSizeChanged(Math.round(w), Math.round(h));
        boolean storm = spec != null && "thunder".equals(spec.effect);
        float intensity = spec == null ? 1f : spec.rainIntensity;

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(rainHaze);
        paint.setAlpha(storm ? 230 : 170);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        paint.setAlpha(255);

        final int[] counts = {56, 74, 46};
        final float[] widths = {0.55f, 0.90f, 1.45f};
        final int[] alphas = {46, 86, 120};
        double seconds = now / 1000.0;
        for (int layer = 0; layer < rainPaths.length; layer++) {
            Path path = rainPaths[layer];
            path.reset();
            int count = Math.round(counts[layer] * intensity);
            float margin = h * 0.10f;
            float travel = h + margin * 2f;
            for (int i = 0; i < count; i++) {
                float seedX = random01(i, layer, 17);
                float seedY = random01(i, layer, 43);
                float speed = h * (0.66f + layer * 0.32f
                        + random01(i, layer, 71) * 0.24f);
                float y = (float) positiveModulo(seedY * travel + seconds * speed, travel)
                        - margin;
                float x = (float) positiveModulo(seedX * (w + margin)
                        + seconds * (w * (0.020f + layer * 0.009f))
                        - y * (0.085f + layer * 0.028f), w + margin) - margin * 0.5f;
                float length = density * (7f + layer * 7f
                        + random01(i, layer, 99) * (5f + layer * 3f));
                path.moveTo(x, y);
                path.lineTo(x - length * (0.23f + layer * 0.035f), y + length);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(density * widths[layer]);
            paint.setColor(Color.argb(Math.min(255, Math.round(alphas[layer] * intensity)),
                    214, 235, 255));
            canvas.drawPath(path, paint);
        }
        paint.setStrokeWidth(density * 0.65f);
        paint.setColor(Color.argb(storm ? 125 : 98, 236, 246, 255));
        canvas.drawPath(rainPaths[2], paint);
        paint.setStyle(Paint.Style.FILL);
    }

    void drawLightning(Canvas canvas, float w, float h, long now, long thunderStarted) {
        if (w <= 0f || h <= 0f) return;
        onSizeChanged(Math.round(w), Math.round(h));
        long elapsed = Math.max(0L, now - thunderStarted);
        long cycle = elapsed / LIGHTNING_CYCLE;
        long phase = elapsed % LIGHTNING_CYCLE;
        long event = cycle * LIGHTNING_INTERVALS.length;
        for (long interval : LIGHTNING_INTERVALS) {
            if (phase < interval) break;
            phase -= interval;
            event++;
        }
        float strength = lightningStrength(phase);
        if (strength <= 0f) return;
        if (event != cachedLightningEvent) buildLightning(event, w, h);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(lightningGlow);
        paint.setAlpha(Math.round(255f * strength));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        paint.setAlpha(255);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(density * 11f);
        paint.setColor(Color.argb(Math.round(52f * strength), 169, 211, 255));
        canvas.drawPath(boltPath, paint);
        paint.setStrokeWidth(density * 5f);
        paint.setColor(Color.argb(Math.round(113f * strength), 199, 226, 255));
        canvas.drawPath(boltPath, paint);
        paint.setStrokeWidth(density * 1.65f);
        paint.setColor(Color.argb(Math.round(247f * strength), 247, 250, 255));
        canvas.drawPath(boltPath, paint);
        paint.setStrokeWidth(density * 1.0f);
        paint.setColor(Color.argb(Math.round(173f * strength), 223, 240, 255));
        canvas.drawPath(branchPath, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(Math.round(51f * strength), 232, 241, 255));
        canvas.drawRect(0, 0, w, h, paint);
    }

    private void drawCloudBand(Canvas canvas, Bitmap texture, LightingColorFilter filter,
            LightingColorFilter shadowFilter, int alpha, float w, float h,
            float topFraction, float heightFraction,
            float widthScale, float phase, float speedDp, long now) {
        if (texture == null || alpha <= 0) return;
        float tileWidth = w * widthScale;
        float bandHeight = h * heightFraction;
        float top = h * topFraction
                + (float) Math.sin(now * 0.00024 + phase * 5.0f) * density * 4f;
        float offset = (float) positiveModulo(
                phase * tileWidth + now / 1000.0 * speedDp * density, tileWidth);
        float shadowOffset = Math.max(density * 3f, bandHeight * 0.042f);
        for (float left = offset - tileWidth; left < w; left += tileWidth) {
            cloudPaint.setAlpha(Math.round(alpha * 0.30f));
            cloudPaint.setColorFilter(shadowFilter);
            cloudDestination.set(left, top + shadowOffset,
                    left + tileWidth, top + bandHeight + shadowOffset);
            canvas.drawBitmap(texture, null, cloudDestination, cloudPaint);
            cloudPaint.setAlpha(alpha);
            cloudPaint.setColorFilter(filter);
            cloudDestination.set(left, top, left + tileWidth, top + bandHeight);
            canvas.drawBitmap(texture, null, cloudDestination, cloudPaint);
        }
        cloudPaint.setColorFilter(null);
        cloudPaint.setAlpha(255);
    }

    private void drawStars(Canvas canvas, float w, float h, long now, float dimming) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 31; i++) {
            float x = random01(i, 4, 101) * w;
            float y = random01(i, 7, 127) * h * 0.78f;
            float pulse = 0.54f + 0.46f * (float) Math.sin(
                    now * (0.0006f + random01(i, 1, 149) * 0.0007f) + i * 2.39f);
            int alpha = Math.round((21f + pulse * 69f) * dimming);
            paint.setColor(Color.argb(alpha, 225, 237, 255));
            canvas.drawCircle(x, y, density * (0.38f + random01(i, 2, 173) * 0.75f), paint);
        }
    }

    private void buildLightning(long event, float w, float h) {
        cachedLightningEvent = event;
        boltPath.reset();
        branchPath.reset();
        lightningX = w * (0.23f + random01((int) event, 3, 191) * 0.54f);
        lightningY = h * (0.08f + random01((int) event, 5, 193) * 0.13f);
        float x = lightningX;
        float y = lightningY;
        float end = h * (0.57f + random01((int) event, 9, 197) * 0.28f);
        boltPath.moveTo(x, y);
        for (int i = 0; i < 9; i++) {
            float nextY = lightningY + (end - lightningY) * (i + 1) / 9f;
            float nextX = x + w * (random01((int) event, i, 211) - 0.5f) * 0.17f;
            nextX = Math.max(w * 0.08f, Math.min(w * 0.92f, nextX));
            if (i == 3 || i == 6) {
                float side = random01((int) event, i, 223) < 0.5f ? -1f : 1f;
                branchPath.moveTo(x, y);
                branchPath.lineTo(x + side * w * 0.08f, y + h * 0.045f);
                branchPath.lineTo(x + side * w * 0.15f, y + h * 0.10f);
            }
            boltPath.lineTo(nextX, nextY);
            x = nextX;
            y = nextY;
        }
        lightningGlow = new RadialGradient(lightningX, lightningY + h * 0.17f,
                Math.max(w * 0.75f, h * 0.43f),
                new int[]{Color.argb(119, 197, 221, 255),
                        Color.argb(43, 146, 180, 235), Color.TRANSPARENT},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP);
    }

    private static float lightningStrength(long phase) {
        if (phase < 82L) return 1f - phase / 96f;
        if (phase >= 133L && phase < 222L) {
            return 0.74f * (1f - (phase - 133L) / 89f);
        }
        if (phase >= 270L && phase < 328L) {
            return 0.29f * (1f - (phase - 270L) / 58f);
        }
        return 0f;
    }

    private static synchronized void ensureCloudTextures() {
        if (farClouds != null && nearClouds != null) return;
        farClouds = buildCloudTexture(11, 0.59f);
        nearClouds = buildCloudTexture(29, 0.56f);
    }

    private static Bitmap buildCloudTexture(int seed, float threshold) {
        int[] pixels = new int[CLOUD_WIDTH * CLOUD_HEIGHT];
        for (int y = 0; y < CLOUD_HEIGHT; y++) {
            float v = y / (float) (CLOUD_HEIGHT - 1);
            float envelope = (float) Math.pow(Math.max(0f,
                    Math.sin(Math.PI * v)), 0.92);
            for (int x = 0; x < CLOUD_WIDTH; x++) {
                float u = x / (float) CLOUD_WIDTH;
                float broad = valueNoise(u * 4f, v * 2.2f, 4, seed);
                float medium = valueNoise(u * 9f, v * 5.1f, 9, seed + 17);
                float detail = valueNoise(u * 19f, v * 10.5f, 19, seed + 37);
                float shape = broad * 0.54f + medium * 0.33f + detail * 0.13f;
                float coverage = smoothstep(threshold - 0.055f,
                        threshold + 0.065f, shape) * envelope;
                int alpha = clamp255(Math.round(coverage * 242f));
                int shade = clamp255(Math.round(151f + shape * 94f + (1f - v) * 16f));
                pixels[y * CLOUD_WIDTH + x] = Color.argb(alpha, shade, shade, shade);
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(CLOUD_WIDTH, CLOUD_HEIGHT, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, CLOUD_WIDTH, 0, 0, CLOUD_WIDTH, CLOUD_HEIGHT);
        return bitmap;
    }

    private static float valueNoise(float x, float y, int periodX, int seed) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        float fx = smoothstep(0f, 1f, x - x0);
        float fy = smoothstep(0f, 1f, y - y0);
        int xa = ((x0 % periodX) + periodX) % periodX;
        int xb = (xa + 1) % periodX;
        float top = mix(random01(xa, y0, seed), random01(xb, y0, seed), fx);
        float bottom = mix(random01(xa, y0 + 1, seed), random01(xb, y0 + 1, seed), fx);
        return mix(top, bottom, fy);
    }

    private static float random01(int x, int y, int seed) {
        int value = x * 0x1f123bb5 ^ y * 0x5f356495 ^ seed * 0x6c8e9cf5;
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        value ^= value >>> 16;
        return (value & 0x00ffffff) / 16777216f;
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothstep(float low, float high, float x) {
        float t = Math.max(0f, Math.min(1f, (x - low) / (high - low)));
        return t * t * (3f - 2f * t);
    }

    private static double positiveModulo(double value, double modulus) {
        double result = value % modulus;
        return result < 0.0 ? result + modulus : result;
    }

    private static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
