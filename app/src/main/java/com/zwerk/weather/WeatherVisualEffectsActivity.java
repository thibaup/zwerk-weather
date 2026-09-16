package com.zwerk.weather;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.system.Os;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.Executors;


abstract class WeatherVisualEffectsActivity extends WeatherActivityFoundation {
    SkyLayout skyLayout;
    HeaderGlassView headerGlass;

    final class HeaderGlassView extends View {
        private final View source;
        private final View.OnLayoutChangeListener sourceLayoutListener;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint blurMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private Shader topHighlightShader;
        private Shader sideSheenShader;
        private Shader depthShader;
        private Shader blurMaskShader;
        private float scrollDepth;
        private int fromTint = Color.rgb(20, 85, 164);
        private int toTint = fromTint;
        private long tintStarted;
        private boolean tintAnimating;
        private boolean blurPermanentlyDisabled;
        private boolean oemBackdropBlurActive;
        private boolean blurDirty = true;
        private Object blurNode;
        private Object blurEffect;
        private Object blurCoverEffect;

        HeaderGlassView(Context context, View source) {
            super(context);
            this.source = source;
            this.sourceLayoutListener = (v, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> requestBlurRefresh();
            if (source != null) source.addOnLayoutChangeListener(sourceLayoutListener);
            blurPermanentlyDisabled = Build.VERSION.SDK_INT < 31;
            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setScene(SceneSpec scene, boolean animate) {
            int target = headerTint(scene == null ? "day" : scene.headerScene());
            int visual = currentTint(SystemClock.uptimeMillis());
            if (visual == target && !tintAnimating) return;
            fromTint = visual;
            toTint = target;
            tintStarted = SystemClock.uptimeMillis();
            tintAnimating = animate && animationsAllowed();
            if (!tintAnimating) fromTint = toTint;
            blurCoverEffect = null;
            invalidate();
        }

        void setScrollDepth(float depth) {
            float next = Math.max(0f, Math.min(1f, depth));
            if (Math.abs(next - scrollDepth) < 0.004f) return;
            scrollDepth = next;
            invalidate();
        }

        void requestBlurRefresh() {
            blurDirty = true;
            invalidate();
        }

        void release() {
            if (source != null) source.removeOnLayoutChangeListener(sourceLayoutListener);
            if (oemBackdropBlurActive) Api31OplusHeaderBlur.clear(this);
            oemBackdropBlurActive = false;
            blurPermanentlyDisabled = true;
            blurNode = null;
            blurEffect = null;
            blurCoverEffect = null;
            topHighlightShader = null;
            sideSheenShader = null;
            depthShader = null;
            blurMaskShader = null;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (Build.VERSION.SDK_INT >= 31 && !oemBackdropBlurActive) {
                oemBackdropBlurActive = Api31OplusHeaderBlur.apply(this);
                if (oemBackdropBlurActive) Log.d(LOG_TAG, "header blur=oem-gradient");
            }
        }

        private int headerTint(String scene) {
            if ("night".equals(scene)) return Color.rgb(8, 28, 66);
            if ("rain".equals(scene)) return Color.rgb(31, 72, 101);
            if ("snow".equals(scene)) return Color.rgb(86, 126, 162);
            if ("fog".equals(scene)) return Color.rgb(91, 121, 140);
            if ("thunder".equals(scene)) return Color.rgb(28, 39, 78);
            return Color.rgb(18, 94, 183);
        }

        private int currentTint(long now) {
            if (!tintAnimating) return toTint;
            if (!animationsAllowed()) {
                tintAnimating = false;
                fromTint = toTint;
                return toTint;
            }
            float linear = Math.max(0f, Math.min(1f,
                    (now - tintStarted) / (float) SCENE_TRANSITION_MILLIS));
            float t = linear * linear * (3f - 2f * linear);
            if (linear >= 1f) {
                tintAnimating = false;
                fromTint = toTint;
                return toTint;
            }
            return blendColor(fromTint, toTint, t);
        }

        private int blendColor(int a, int b, float t) {
            float clamped = Math.max(0f, Math.min(1f, t));
            return Color.rgb(
                    Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * clamped),
                    Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * clamped),
                    Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * clamped));
        }

        private int mixWith(int color, int other, float amount) {
            return blendColor(color, other, amount);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            blurDirty = true;
            if (w <= 0 || h <= 0) {
                topHighlightShader = null;
                sideSheenShader = null;
                depthShader = null;
                blurMaskShader = null;
                return;
            }
            topHighlightShader = new LinearGradient(
                    0, 0, w, 0,
                    new int[]{
                            Color.argb(40, 255, 255, 255),
                            Color.argb(13, 255, 255, 255),
                            Color.argb(30, 255, 255, 255),
                            Color.argb(5, 255, 255, 255)},
                    new float[]{0f, 0.35f, 0.73f, 1f},
                    Shader.TileMode.CLAMP);
            sideSheenShader = new RadialGradient(
                    0, h * 0.28f, Math.max(dp(44), w * 0.54f),
                    new int[]{Color.argb(30, 255, 255, 255), Color.TRANSPARENT},
                    new float[]{0f, 1f},
                    Shader.TileMode.CLAMP);
            depthShader = new LinearGradient(
                    0, 0, 0, h,
                    new int[]{Color.TRANSPARENT, Color.argb(7, 0, 0, 0), Color.argb(30, 0, 0, 0)},
                    new float[]{0f, 0.58f, 1f},
                    Shader.TileMode.CLAMP);
            blurMaskShader = new LinearGradient(
                    0, 0, 0, h,
                    new int[]{Color.WHITE, Color.argb(246, 255, 255, 255),
                            Color.argb(178, 255, 255, 255), Color.TRANSPARENT},
                    new float[]{0f, 0.46f, 0.82f, 1f},
                    Shader.TileMode.CLAMP);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int tint = currentTint(SystemClock.uptimeMillis());
            if (!oemBackdropBlurActive
                    && !blurPermanentlyDisabled
                    && Build.VERSION.SDK_INT >= 31) {
                if (!canvas.isHardwareAccelerated()) {
                    blurPermanentlyDisabled = true;
                    blurNode = null;
                    blurEffect = null;
                } else if (source != null && getWidth() > 0 && getHeight() > 0) {
                    try {
                        Api31HeaderBlur.draw(
                                this, canvas, source, blurDirty, scrollDepth);
                        blurDirty = false;
                    } catch (Throwable ignored) {
                        blurPermanentlyDisabled = true;
                        blurNode = null;
                        blurEffect = null;
                    }
                }
            }

            int tintAlpha = 14;
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(
                    Math.min(248, tintAlpha),
                    Color.red(tint), Color.green(tint), Color.blue(tint)));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

            int frost = mixWith(tint, Color.WHITE, 0.54f);
            int frostAlpha = 5;
            paint.setColor(Color.argb(
                    Math.min(72, frostAlpha),
                    Color.red(frost), Color.green(frost), Color.blue(frost)));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

            if (topHighlightShader != null) {
                paint.setShader(topHighlightShader);
                canvas.drawRect(0, 0, getWidth(), Math.max(dp(18), getHeight() * 0.40f), paint);
            }
            if (sideSheenShader != null) {
                paint.setShader(sideSheenShader);
                canvas.drawRect(0, 0, getWidth() * 0.62f, getHeight(), paint);
            }
            if (depthShader != null) {
                paint.setShader(depthShader);
                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            }
            paint.setShader(null);

            float density = getResources().getDisplayMetrics().density;
            float bottom = getHeight() - Math.max(1f, density * 0.5f);
            edgePaint.setStyle(Paint.Style.STROKE);
            edgePaint.setStrokeWidth(Math.max(1f, density * 0.55f));
            edgePaint.setColor(Color.argb(12, 248, 252, 255));
            canvas.drawLine(0, bottom, getWidth(), bottom, edgePaint);

            if (tintAnimating) postInvalidateOnAnimation();
        }
    }

    static final class Api31OplusHeaderBlur {
        private static java.lang.reflect.Method setBackgroundEffect;

        private Api31OplusHeaderBlur() { }

        static boolean apply(View target) {
            if (Build.VERSION.SDK_INT < 31 || target == null) return false;
            try {
                Class<?> effectFactory = Class.forName("com.oplus.graphics.OplusRenderEffect");
                java.lang.reflect.Method create = effectFactory.getMethod(
                        "createGradientBlurEffect",
                        float.class, float.class, boolean.class, float.class,
                        int.class, int.class, int.class);
                Object effect = create.invoke(null, 60f, 0f, true, 3f, 1, 0, 0);
                if (effect == null) return false;

                Class<?> backgroundRenderer = Class.forName(
                        "com.oplus.view.OplusViewBackgroundRenderEffect");
                for (java.lang.reflect.Method method : backgroundRenderer.getMethods()) {
                    if ("setBackgroundRenderEffect".equals(method.getName())
                            && method.getParameterTypes().length == 2) {
                        setBackgroundEffect = method;
                        method.invoke(null, effect, target);
                        return true;
                    }
                }
                Log.d(LOG_TAG, "header blur=fallback (method unavailable)");
            } catch (Throwable ignored) {
                Log.d(LOG_TAG, "header blur=fallback ("
                        + ignored.getClass().getSimpleName() + ")");
                setBackgroundEffect = null;
            }
            return false;
        }

        static void clear(View target) {
            if (target == null || setBackgroundEffect == null) return;
            try {
                setBackgroundEffect.invoke(null, null, target);
            } catch (Throwable ignored) {
            }
        }
    }

    static final class Api31HeaderBlur {
        private Api31HeaderBlur() { }

        static boolean draw(
                HeaderGlassView owner,
                Canvas canvas,
                View source,
                boolean refresh,
                float scrollDepth) {
            if (Build.VERSION.SDK_INT < 31) return false;
            float strength = Math.max(0f, Math.min(1f, scrollDepth));
            strength = strength * strength * (3f - 2f * strength);
            if (strength <= 0.002f || owner.blurMaskShader == null) return false;
            android.graphics.RenderNode node = owner.blurNode instanceof android.graphics.RenderNode
                    ? (android.graphics.RenderNode) owner.blurNode
                    : null;
            if (node == null) {
                node = new android.graphics.RenderNode("ZwerkWeatherHeaderStrip");
                owner.blurNode = node;
                refresh = true;
            }

            android.graphics.RenderEffect effect =
                    owner.blurEffect instanceof android.graphics.RenderEffect
                            ? (android.graphics.RenderEffect) owner.blurEffect
                            : null;
            if (effect == null) {
                float radius = 60f;
                android.graphics.RenderEffect blur = android.graphics.RenderEffect.createBlurEffect(
                        radius, radius, Shader.TileMode.CLAMP);
                android.graphics.ColorMatrix matrix = new android.graphics.ColorMatrix();
                matrix.setSaturation(1.14f);
                float[] values = matrix.getArray();
                values[4] += 6f;
                values[9] += 6f;
                values[14] += 6f;
                android.graphics.ColorMatrixColorFilter filter =
                        new android.graphics.ColorMatrixColorFilter(matrix);
                effect = android.graphics.RenderEffect.createColorFilterEffect(filter, blur);
                owner.blurEffect = effect;
            }

            android.graphics.RenderEffect coverEffect =
                    owner.blurCoverEffect instanceof android.graphics.RenderEffect
                            ? (android.graphics.RenderEffect) owner.blurCoverEffect
                            : null;
            if (coverEffect == null) {
                int cover = owner.currentTint(SystemClock.uptimeMillis());
                android.graphics.ColorMatrix coverMatrix = new android.graphics.ColorMatrix(
                        new float[]{
                                0f, 0f, 0f, 0f, Color.red(cover),
                                0f, 0f, 0f, 0f, Color.green(cover),
                                0f, 0f, 0f, 0f, Color.blue(cover),
                                0f, 0f, 0f, 1f, 0f});
                coverEffect = android.graphics.RenderEffect.createColorFilterEffect(
                        new android.graphics.ColorMatrixColorFilter(coverMatrix));
                owner.blurCoverEffect = coverEffect;
            }

            int width = owner.getWidth();
            int height = owner.getHeight();
            if (width <= 0 || height <= 0) return false;
            if (refresh || node.getWidth() != width || node.getHeight() != height) {
                node.setPosition(0, 0, width, height);
                android.graphics.RecordingCanvas recording = node.beginRecording(width, height);
                int save = recording.save();
                recording.clipRect(0, 0, width, height);
                source.draw(recording);
                recording.restoreToCount(save);
                node.endRecording();
            }
            int layer = canvas.saveLayer(0, 0, width, height, null);
            node.setRenderEffect(coverEffect);
            canvas.drawRenderNode(node);
            node.setRenderEffect(effect);
            canvas.drawRenderNode(node);

            float density = owner.getResources().getDisplayMetrics().density;
            float shift = Math.max(0.65f, density * 0.85f);
            int save = canvas.save();
            canvas.clipRect(0, height * 0.12f, width, height * 0.28f);
            canvas.translate(shift, 0f);
            canvas.drawRenderNode(node);
            canvas.restoreToCount(save);

            save = canvas.save();
            canvas.clipRect(0, height * 0.63f, width, height * 0.78f);
            canvas.translate(-shift * 0.70f, 0f);
            canvas.drawRenderNode(node);
            canvas.restoreToCount(save);

            owner.blurMaskPaint.setShader(owner.blurMaskShader);
            owner.blurMaskPaint.setAlpha(Math.round(255f * strength));
            owner.blurMaskPaint.setBlendMode(android.graphics.BlendMode.DST_IN);
            canvas.drawRect(0, 0, width, height, owner.blurMaskPaint);
            owner.blurMaskPaint.setBlendMode(null);
            owner.blurMaskPaint.setShader(null);
            owner.blurMaskPaint.setAlpha(255);
            canvas.restoreToCount(layer);
            return true;
        }
    }







    final class SkyLayout extends FrameLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Rect sourceRect = new Rect();
        private final RectF destinationRect = new RectF();
        private final Path fogPath = new Path();
        private final Path stormPath = new Path();
        private final Path lightningPath = new Path();
        private final Bitmap daySky = BitmapFactory.decodeResource(getResources(), R.drawable.weather_sky_day);
        private final Bitmap nightSky = BitmapFactory.decodeResource(getResources(), R.drawable.weather_sky_night);
        private final Bitmap rainSky = BitmapFactory.decodeResource(getResources(), R.drawable.weather_sky_rain);
        private final Bitmap snowSky = BitmapFactory.decodeResource(getResources(), R.drawable.weather_sky_snow);
        private final float[] particleX = new float[92];
        private final float[] particleY = new float[92];
        private final float[] particleSpeed = new float[92];
        private final float[] particleSize = new float[92];
        private final Shader[] fogBankShaders = new Shader[4];

        private SceneSpec currentSpec = new SceneSpec("day", "none", true, "Clear");
        private SceneSpec outgoingSpec;
        private Bitmap outgoingSnapshot;
        private long transitionStarted;
        private long thunderStarted;
        private boolean animationRunning = true;
        private long pausedAt = -1L;
        private long accumulatedPause;
        private Shader fallbackShader;
        private Shader washShader;
        private Shader stormDepthShader;
        private Shader fogWashShader;
        private Shader nightWeatherOverlay;

        SkyLayout(Context context) {
            super(context);
            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            setChildrenDrawingOrderEnabled(false);
            for (int i = 0; i < particleX.length; i++) {
                particleX[i] = ((i * 37) % 97) / 97f;
                particleY[i] = ((i * 53) % 101) / 101f;
                particleSpeed[i] = 0.24f + (((i * 29) % 41) / 80f);
                particleSize[i] = 0.65f + (((i * 17) % 23) / 18f);
            }
        }

        String setScene(SceneSpec target) {
            if (target == null) return currentSpec.paletteScene();
            if (target.equals(currentSpec) && outgoingSpec == null && outgoingSnapshot == null) {
                invalidate();
                return target.paletteScene();
            }
            long now = visualNow();
            boolean animate = animationRunning && animationsAllowed();
            if (!animate) {
                clearOutgoingSnapshot();
                outgoingSpec = null;
                currentSpec = target;
                transitionStarted = 0L;
                if ("thunder".equals(target.effect)) thunderStarted = now;
                invalidate();
                return target.paletteScene();
            }

            if (transitionActive(now)) {
                Bitmap snapshot = captureCurrentVisual(now);
                clearOutgoingSnapshot();
                outgoingSnapshot = snapshot;
                outgoingSpec = null;
            } else {
                clearOutgoingSnapshot();
                outgoingSpec = currentSpec;
            }
            boolean enteringThunder = !"thunder".equals(currentSpec.effect)
                    && "thunder".equals(target.effect);
            currentSpec = target;
            transitionStarted = now;
            if (enteringThunder) thunderStarted = now;
            postInvalidateOnAnimation();
            return target.paletteScene();
        }

        void setAnimationRunning(boolean running) {
            boolean allowed = running && animationsAllowed();
            if (animationRunning == allowed) {
                if (allowed) postInvalidateOnAnimation();
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (allowed) {
                if (pausedAt >= 0L) {
                    accumulatedPause += Math.max(0L, now - pausedAt);
                    pausedAt = -1L;
                }
                animationRunning = true;
                postInvalidateOnAnimation();
            } else {
                animationRunning = false;
                pausedAt = now;
                clearOutgoingSnapshot();
                outgoingSpec = null;
                transitionStarted = 0L;
                invalidate();
            }
        }

        private long visualNow() {
            long now = SystemClock.uptimeMillis();
            if (!animationRunning && pausedAt >= 0L) now = pausedAt;
            return now - accumulatedPause;
        }

        private boolean transitionActive(long now) {
            return (outgoingSpec != null || outgoingSnapshot != null)
                    && transitionStarted > 0L
                    && now - transitionStarted < SCENE_TRANSITION_MILLIS;
        }

        private float transitionProgress(long now) {
            if (outgoingSpec == null && outgoingSnapshot == null) return 1f;
            float linear = Math.max(0f, Math.min(1f,
                    (now - transitionStarted) / (float) SCENE_TRANSITION_MILLIS));
            // Fast-Out-Slow-In compatible smoothstep without introducing an Animator lifecycle.
            return linear * linear * (3f - 2f * linear);
        }

        private Bitmap captureCurrentVisual(long now) {
            if (getWidth() <= 0 || getHeight() <= 0) return null;
            try {
                Bitmap bitmap = Bitmap.createBitmap(
                        getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawComposite(canvas, now, false);
                return bitmap;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private void clearOutgoingSnapshot() {
            if (outgoingSnapshot != null) {
                try { outgoingSnapshot.recycle(); } catch (Exception ignored) { }
                outgoingSnapshot = null;
            }
        }

        void release() {
            animationRunning = false;
            outgoingSpec = null;
            transitionStarted = 0L;
            clearOutgoingSnapshot();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w <= 0 || h <= 0) return;
            fallbackShader = new LinearGradient(
                    0, 0, 0, h,
                    new int[]{Color.rgb(12, 89, 205), Color.rgb(80, 151, 235), Color.rgb(218, 231, 248)},
                    new float[]{0f, 0.52f, 1f},
                    Shader.TileMode.CLAMP);
            washShader = new LinearGradient(
                    0, 0, 0, h,
                    new int[]{Color.argb(58, 0, 35, 104), Color.argb(12, 18, 70, 150), Color.argb(28, 12, 60, 130)},
                    new float[]{0f, 0.56f, 1f},
                    Shader.TileMode.CLAMP);
            stormDepthShader = new LinearGradient(
                    0, 0, 0, h,
                    new int[]{Color.argb(80, 4, 10, 24), Color.argb(44, 8, 20, 42), Color.argb(68, 3, 9, 22)},
                    new float[]{0f, 0.52f, 1f},
                    Shader.TileMode.CLAMP);
            fogWashShader = new LinearGradient(
                    0, 0, 0, h,
                    new int[]{Color.argb(9, 232, 241, 248), Color.argb(24, 232, 241, 248), Color.argb(14, 224, 236, 246)},
                    new float[]{0f, 0.57f, 1f},
                    Shader.TileMode.CLAMP);
            nightWeatherOverlay = new LinearGradient(
                    0, 0, 0, h,
                    new int[]{Color.argb(105, 3, 13, 38), Color.argb(72, 6, 25, 58), Color.argb(96, 2, 12, 32)},
                    new float[]{0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP);
            buildFogShaders(h);
        }

        private void buildFogShaders(float h) {
            for (int i = 0; i < fogBankShaders.length; i++) {
                float topFraction;
                float heightFraction;
                int alpha;
                switch (i) {
                    case 0: topFraction = 0.08f; heightFraction = 0.22f; alpha = 36; break;
                    case 1: topFraction = 0.28f; heightFraction = 0.25f; alpha = 48; break;
                    case 2: topFraction = 0.50f; heightFraction = 0.20f; alpha = 34; break;
                    default: topFraction = 0.68f; heightFraction = 0.24f; alpha = 42; break;
                }
                float top = h * topFraction;
                float bottom = top + h * heightFraction;
                fogBankShaders[i] = new LinearGradient(
                        0, top, 0, bottom,
                        new int[]{
                                Color.argb(0, 242, 248, 252),
                                Color.argb(alpha, 242, 248, 252),
                                Color.argb(Math.max(1, alpha - 5), 232, 241, 248),
                                Color.argb(0, 232, 241, 248)},
                        new float[]{0f, 0.30f, 0.68f, 1f},
                        Shader.TileMode.CLAMP);
            }
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            // Sky animation is background-only. Child controls own all pointer handling.
            return false;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (getWidth() <= 0 || getHeight() <= 0) return;
            if (animationRunning && !animationsAllowed()) {
                setAnimationRunning(false);
            }
            long now = visualNow();
            drawComposite(canvas, now, true);
            if (animationRunning && (transitionActive(now) || !"none".equals(currentSpec.effect))) {
                postInvalidateOnAnimation();
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            // All scene/effect rendering happens in onDraw(), before FrameLayout dispatches any
            // weather cards, text, toolbar, or controls. Never add effect drawing below this call.
            super.dispatchDraw(canvas);
        }

        private void drawComposite(Canvas canvas, long now, boolean cleanupFinished) {
            float w = getWidth();
            float h = getHeight();
            float progress = transitionProgress(now);
            boolean transitioning = outgoingSpec != null || outgoingSnapshot != null;

            if (outgoingSnapshot != null) {
                paint.setShader(null);
                paint.setAlpha(255);
                destinationRect.set(0, 0, w, h);
                canvas.drawBitmap(outgoingSnapshot, null, destinationRect, paint);
            } else if (outgoingSpec != null) {
                // Existing thunder flashes are part of the outgoing visual and therefore fade
                // with that layer. Only flashes belonging to an incoming thunder target wait
                // until the transition is mostly complete.
                drawSceneLayer(canvas, outgoingSpec, 255, now,
                        "thunder".equals(outgoingSpec.effect));
            }

            if (!transitioning) {
                drawSceneLayer(canvas, currentSpec, 255, now, true);
            } else {
                drawSceneLayer(canvas, currentSpec, Math.round(progress * 255f), now, progress >= 0.88f);
                if (cleanupFinished && progress >= 1f) {
                    outgoingSpec = null;
                    clearOutgoingSnapshot();
                    transitionStarted = 0L;
                }
            }
        }

        private void drawSceneLayer(
                Canvas canvas, SceneSpec spec, int layerAlpha, long now, boolean allowLightning) {
            if (spec == null || layerAlpha <= 0) return;
            int save = canvas.saveLayerAlpha(
                    0, 0, getWidth(), getHeight(),
                    Math.max(0, Math.min(255, layerAlpha)));
            Bitmap base = bitmapFor(spec.base);
            if (base != null) drawCover(canvas, base, getWidth(), getHeight(), 255);
            else {
                paint.setShader(fallbackShader);
                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setShader(washShader);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(null);

            if (!spec.daytime
                    && ("rain".equals(spec.effect)
                    || "snow".equals(spec.effect)
                    || "thunder".equals(spec.effect))) {
                paint.setShader(nightWeatherOverlay);
                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
                paint.setShader(null);
            }

            if ("thunder".equals(spec.effect)) {
                drawThunderAtmosphere(canvas, getWidth(), getHeight(), now);
                drawRain(canvas, getWidth(), getHeight(), now);
                if (animationRunning && allowLightning) {
                    drawLightningBolt(canvas, getWidth(), getHeight(), now);
                    drawLightningFlash(canvas, getWidth(), getHeight(), now);
                }
            } else if ("rain".equals(spec.effect)) {
                drawRain(canvas, getWidth(), getHeight(), now);
            } else if ("snow".equals(spec.effect)) {
                drawSnow(canvas, getWidth(), getHeight(), now);
            } else if ("fog".equals(spec.effect)) {
                drawFog(canvas, getWidth(), getHeight(), now);
            }
            canvas.restoreToCount(save);
        }

        private Bitmap bitmapFor(String base) {
            if ("night".equals(base)) return nightSky;
            if ("rain".equals(base)) return rainSky;
            if ("snow".equals(base)) return snowSky;
            return daySky;
        }

        private void drawCover(Canvas canvas, Bitmap bitmap, float w, float h, int alpha) {
            if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return;
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
            destinationRect.set(0, 0, w, h);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(alpha);
            canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint);
            paint.setAlpha(255);
        }

        private void drawRain(Canvas canvas, float w, float h, long now) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.15f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(Color.argb(90, 206, 229, 255));
            float seconds = now / 1000f;
            for (int i = 0; i < particleX.length; i++) {
                float x = particleX[i] * w;
                float y = ((particleY[i] + seconds * particleSpeed[i]) % 1.12f) * h - h * 0.08f;
                float length = dp(10f + particleSize[i] * 8f);
                canvas.drawLine(x, y, x - length * 0.28f, y + length, paint);
            }
        }

        private void drawSnow(Canvas canvas, float w, float h, long now) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(178, 255, 255, 255));
            float seconds = now / 1000f;
            for (int i = 0; i < particleX.length; i++) {
                float base = particleX[i] * w;
                float x = base + (float) Math.sin(seconds * 0.7f + i) * dp(9f);
                float y = ((particleY[i] + seconds * particleSpeed[i] * 0.17f) % 1.08f) * h - h * 0.04f;
                canvas.drawCircle(x, y, dp(particleSize[i] * 1.35f), paint);
            }
        }

        private void drawThunderAtmosphere(Canvas canvas, float w, float h, long now) {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(stormDepthShader);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            float seconds = now / 1000f;
            for (int i = 0; i < 3; i++) {
                float drift = (float) Math.sin(seconds * (0.10f + i * 0.018f) + i * 1.9f)
                        * w * (0.055f + i * 0.012f);
                float y = h * (0.13f + i * 0.24f)
                        + (float) Math.sin(seconds * (0.07f + i * 0.011f) + i)
                        * h * 0.018f;
                float bandHeight = h * (0.14f + i * 0.018f);
                float left = -w * 0.38f + drift;
                float right = w * 1.38f + drift;

                stormPath.reset();
                stormPath.moveTo(left, y + bandHeight * 0.30f);
                stormPath.cubicTo(left + w * 0.38f, y - bandHeight * 0.10f,
                        left + w * 0.72f, y + bandHeight * 0.10f,
                        left + w, y + bandHeight * 0.20f);
                stormPath.cubicTo(left + w * 1.24f, y + bandHeight * 0.30f,
                        left + w * 1.50f, y - bandHeight * 0.05f,
                        right, y + bandHeight * 0.24f);
                stormPath.lineTo(right, y + bandHeight * 0.86f);
                stormPath.cubicTo(left + w * 1.50f, y + bandHeight * 1.05f,
                        left + w * 1.18f, y + bandHeight * 0.74f,
                        left + w, y + bandHeight * 0.80f);
                stormPath.cubicTo(left + w * 0.66f, y + bandHeight * 0.96f,
                        left + w * 0.34f, y + bandHeight * 0.70f,
                        left, y + bandHeight * 0.84f);
                stormPath.close();
                paint.setColor(Color.argb(20 + i * 7, 4, 12 + i * 4, 28 + i * 7));
                canvas.drawPath(stormPath, paint);
            }
        }

        private void drawFog(Canvas canvas, float w, float h, long now) {
            float seconds = now / 1000f;
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < fogBankShaders.length; i++) {
                float topFraction;
                float heightFraction;
                float speed;
                switch (i) {
                    case 0: topFraction = 0.08f; heightFraction = 0.22f; speed = 0.055f; break;
                    case 1: topFraction = 0.28f; heightFraction = 0.25f; speed = -0.038f; break;
                    case 2: topFraction = 0.50f; heightFraction = 0.20f; speed = 0.031f; break;
                    default: topFraction = 0.68f; heightFraction = 0.24f; speed = -0.024f; break;
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
            paint.setShader(fogWashShader);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            paint.setAlpha(255);
        }

        private float lightningStrength(long now) {
            long elapsed = Math.max(0L, now - thunderStarted);
            long phase = elapsed % 6400L;
            if (phase < 78L) return 1f - phase / 96f;
            if (phase >= 112L && phase < 184L) {
                return 0.52f * (1f - (phase - 112L) / 72f);
            }
            return 0f;
        }

        private void buildLightningPath(float w, float h, long now) {
            long eventIndex = Math.max(0L, now - thunderStarted) / 6400L;
            float anchor = 0.40f + ((eventIndex * 37L) % 19L) / 100f;
            float x0 = w * anchor;
            lightningPath.reset();
            lightningPath.moveTo(x0, h * 0.08f);
            lightningPath.lineTo(x0 - w * 0.035f, h * 0.24f);
            lightningPath.lineTo(x0 + w * 0.012f, h * 0.36f);
            lightningPath.lineTo(x0 - w * 0.050f, h * 0.52f);
            lightningPath.lineTo(x0 - w * 0.018f, h * 0.68f);
            lightningPath.lineTo(x0 - w * 0.072f, h * 0.84f);
            lightningPath.moveTo(x0 + w * 0.002f, h * 0.35f);
            lightningPath.lineTo(x0 + w * 0.105f, h * 0.43f);
            lightningPath.lineTo(x0 + w * 0.145f, h * 0.54f);
        }

        private void drawLightningBolt(Canvas canvas, float w, float h, long now) {
            float strength = lightningStrength(now);
            if (strength <= 0f) return;
            buildLightningPath(w, h, now);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(dp(7f));
            paint.setColor(Color.argb(Math.round(58 * strength), 188, 220, 255));
            canvas.drawPath(lightningPath, paint);
            paint.setStrokeWidth(dp(1.65f));
            paint.setColor(Color.argb(Math.round(220 * strength), 236, 247, 255));
            canvas.drawPath(lightningPath, paint);
        }

        private void drawLightningFlash(Canvas canvas, float w, float h, long now) {
            float strength = lightningStrength(now);
            if (strength <= 0f) return;
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(Math.round(92 * strength), 225, 238, 255));
            canvas.drawRect(0, 0, w, h, paint);
        }
    }



    final class WeatherGlyphView extends View {
        private final String condition;
        private final boolean daytime;

        WeatherGlyphView(Context context, String condition, boolean daytime) {
            super(context);
            this.condition = condition;
            this.daytime = daytime;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            drawWeatherGlyph(canvas, condition, daytime, getWidth() / 2f, getHeight() / 2f,
                    Math.min(getWidth(), getHeight()) * 0.82f);
        }
    }

    void drawWeatherGlyph(Canvas canvas, String condition, boolean daytime, float cx, float cy, float size) {
        String key = conditionKey(condition);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
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
            p.setStrokeWidth(Math.max(2f, size * 0.07f));
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
            p.setStrokeWidth(Math.max(2f, size * 0.065f));
            for (int i = -1; i <= 1; i++) {
                float x = cx + i * size * 0.20f;
                canvas.drawLine(x, cy + size * 0.28f, x - size * 0.05f, cy + size * 0.43f, p);
            }
        } else if ("snow".equals(key)) {
            p.setColor(WHITE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.6f, size * 0.04f));
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
            Path bolt = new Path();
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
        p.setStrokeWidth(Math.max(2f, r * 0.18f));
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
        Path moon = new Path();
        moon.addCircle(cx, cy, r, Path.Direction.CW);
        Path shadow = new Path();
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
        p.setStrokeWidth(Math.max(1.4f, r * 0.07f));
        canvas.drawPath(moon, p);
    }

    static void drawCloud(Canvas canvas, Paint p, float cx, float cy, float size) {
        p.setShader(null);
        p.setColor(WHITE);
        p.setStyle(Paint.Style.FILL);
        float h = size * 0.34f;
        canvas.drawRoundRect(new RectF(cx - size * 0.46f, cy - h * 0.05f, cx + size * 0.46f, cy + h * 0.62f), h, h, p);
        canvas.drawCircle(cx - size * 0.20f, cy - h * 0.06f, h * 0.68f, p);
        canvas.drawCircle(cx + size * 0.07f, cy - h * 0.28f, h * 0.88f, p);
        canvas.drawCircle(cx + size * 0.28f, cy - h * 0.03f, h * 0.58f, p);
    }



}
