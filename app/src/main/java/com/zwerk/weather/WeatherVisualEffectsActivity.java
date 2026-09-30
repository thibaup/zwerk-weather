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
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

abstract class WeatherVisualEffectsActivity extends WeatherActivityFoundation {
    SkyLayout skyLayout;
    HeaderGlassView headerGlass;
    HeaderGlassView bottomGlass;

    final class HeaderGlassView extends View {
        private final View source;
        private final boolean floating;
        private final int[] glassLocation = new int[2];
        private final int[] sourceLocation = new int[2];
        private final RectF glassBounds = new RectF();
        private final Path glassClip = new Path();
        private final View.OnLayoutChangeListener sourceLayoutListener;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint blurMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint portableBlurPaint = new Paint(
                Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF portableBlurDestination = new RectF();
        private final android.graphics.PorterDuffXfermode portableBlurMaskMode =
                new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN);
        private Shader blurMaskShader;
        private Shader pinnedTintShader;
        private int pinnedTintColour;
        private int fromTint = Color.rgb(20, 85, 164);
        private int toTint = fromTint;
        private long tintStarted;
        private boolean tintAnimating;
        private boolean blurPermanentlyDisabled;
        private boolean oemBackdropBlurActive;
        private boolean blurDirty = true;
        private Object blurNode;
        private Object blurEffect;
        private Bitmap portableBlurBitmap;
        private Canvas portableBlurCanvas;
        private final SoftwareBlur portableBlur = new SoftwareBlur();
        private boolean genericBlurLogged;

        HeaderGlassView(Context context, View source) {
            this(context, source, false);
        }

        HeaderGlassView(Context context, View source, boolean floating) {
            super(context);
            this.source = source;
            this.floating = floating;
            this.sourceLayoutListener = (v, left, top, right, bottom,
                    oldLeft, oldTop, oldRight, oldBottom) -> requestBlurRefresh();
            if (source != null) source.addOnLayoutChangeListener(sourceLayoutListener);
            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setScene(SceneSpec scene, boolean animate) {
            int target = headerTint(scene == null ? "day" : scene.headerScene());
            if (target == toTint) return;
            int visual = currentTint(SystemClock.uptimeMillis());
            if (visual == target && !tintAnimating) return;
            fromTint = visual;
            toTint = target;
            tintStarted = SystemClock.uptimeMillis();
            tintAnimating = animate && animationsAllowed();
            if (!tintAnimating) fromTint = toTint;
            requestBlurRefresh();
        }

        void requestBlurRefresh() {
            blurDirty = true;
            invalidate();
        }

        void resetBackdropCapture() {
            // Invalidate derived captures when the visible page changes.
            discardRenderNode(blurNode);
            blurNode = null;
            blurEffect = null;
            requestBlurRefresh();
        }

        void requestAnimatedBackdropRefresh() {
            if (oemBackdropBlurActive || getVisibility() != VISIBLE) return;
            blurDirty = true;
            postInvalidateOnAnimation();
        }

        void release() {
            if (source != null) source.removeOnLayoutChangeListener(sourceLayoutListener);
            if (oemBackdropBlurActive) Api31OplusHeaderBlur.clear(this);
            oemBackdropBlurActive = false;
            blurPermanentlyDisabled = true;
            discardRenderNode(blurNode);
            blurNode = null;
            blurEffect = null;
            releasePortableBlurBuffer();
            blurMaskShader = null;
            pinnedTintShader = null;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            // Use portable blur for the dock; Oplus cannot reliably clip its rounded outline.
            if (!floating && !BuildConfig.FORCE_GENERIC_HEADER_BLUR
                    && Build.VERSION.SDK_INT >= 31 && !oemBackdropBlurActive) {
                oemBackdropBlurActive = Api31OplusHeaderBlur.apply(this);
                if (oemBackdropBlurActive) Log.d(LOG_TAG, "header blur=oem-gradient");
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            if (oemBackdropBlurActive) Api31OplusHeaderBlur.clear(this);
            oemBackdropBlurActive = false;
            super.onDetachedFromWindow();
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

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            blurDirty = true;
            pinnedTintShader = null;
            glassClip.reset();
            if (floating) glassClip.addRoundRect(0, 0, w, h, dp(28), dp(28), Path.Direction.CW);
            if (w != oldw || h != oldh) releasePortableBlurBuffer();
            if (w <= 0 || h <= 0) {
                blurMaskShader = null;
                return;
            }
            blurMaskShader = new LinearGradient(
                    0, 0, 0, h,
                    new int[]{Color.WHITE, Color.WHITE,
                            Color.argb(234, 255, 255, 255),
                            Color.argb(104, 255, 255, 255), Color.TRANSPARENT},
                    new float[]{0f, 0.60f, 0.76f, 0.90f, 1f},
                    Shader.TileMode.CLAMP);
            if (floating) blurMaskShader = new LinearGradient(0, 0, 0, h,
                    Color.WHITE, Color.WHITE, Shader.TileMode.CLAMP);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // Extra tint layers hide ColorOS's native progressive blur.
            if (oemBackdropBlurActive) return;
            // Layer bounds do not clip; constrain the padded dock blur explicitly.
            int clipSave = canvas.save();
            if (floating) canvas.clipPath(glassClip);
            else {
                canvas.clipRect(0, 0, getWidth(), getHeight());
            }
            int tint = currentTint(SystemClock.uptimeMillis());
            boolean blurDrawn = false;
            if (!oemBackdropBlurActive && !blurPermanentlyDisabled && Build.VERSION.SDK_INT >= 31
                    && canvas.isHardwareAccelerated()
                    && source != null && getWidth() > 0 && getHeight() > 0) {
                try {
                    blurDrawn = Api31HeaderBlur.draw(this, canvas, blurDirty);
                    blurDirty = false;
                } catch (Throwable ignored) {
                    blurPermanentlyDisabled = true;
                    blurNode = null;
                    blurEffect = null;
                }
            }
            if (!oemBackdropBlurActive && !blurDrawn && (floating || source != null)) {
                blurDrawn = drawPortableHeaderBlur(canvas);
            }
            if (!oemBackdropBlurActive && blurDrawn && !genericBlurLogged) {
                genericBlurLogged = true;
                Log.d(LOG_TAG, Build.VERSION.SDK_INT >= 31 && !blurPermanentlyDisabled
                        ? "header blur=platform-gradient"
                        : "header blur=portable-gradient");
            }

            if (!floating) {
                if (pinnedTintShader == null || pinnedTintColour != tint) {
                    pinnedTintColour = tint;
                    pinnedTintShader = new LinearGradient(0, 0, 0, getHeight(),
                            new int[]{Color.argb(18, Color.red(tint), Color.green(tint), Color.blue(tint)),
                                    Color.argb(7, Color.red(tint), Color.green(tint), Color.blue(tint)),
                                    Color.TRANSPARENT, Color.TRANSPARENT},
                            new float[]{0f, 0.58f, 0.96f, 1f}, Shader.TileMode.CLAMP);
                }
                paint.setShader(pinnedTintShader);
                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
                paint.setShader(null);
                canvas.restoreToCount(clipSave);
                if (tintAnimating) postInvalidateOnAnimation();
                return;
            }

            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(
                    76,
                    Color.red(tint), Color.green(tint), Color.blue(tint)));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

            paint.setColor(Color.argb(16, 255, 255, 255));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

            float density = getResources().getDisplayMetrics().density;
            edgePaint.setStyle(Paint.Style.STROKE);
            edgePaint.setStrokeWidth(Math.max(1f, density * 0.55f));
            float inset = Math.max(1f, density * 0.5f);
            glassBounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
            edgePaint.setColor(Color.argb(64, 248, 252, 255));
            canvas.drawRoundRect(glassBounds, dp(28), dp(28), edgePaint);

            canvas.restoreToCount(clipSave);
            if (tintAnimating) postInvalidateOnAnimation();
        }

        private void drawBackdropSource(Canvas target) {
            // Capture in this strip's coordinates to sample the content behind the dock.
            getLocationInWindow(glassLocation);
            // Include the sky so the header fade joins the background smoothly.
            drawSkyBackdrop(target, true);
            if (source != null) {
                source.getLocationInWindow(sourceLocation);
                int saved = target.save();
                target.translate(sourceLocation[0] - glassLocation[0], sourceLocation[1] - glassLocation[1]);
                // Use published raw content; redrawing live views can consume their invalidation during transitions.
                target.translate(-source.getScrollX(), -source.getScrollY());
                if (source instanceof GlassSourceScrollView) {
                    // Use sky/tint for unpublished content; redrawing the live scroller breaks native stretch.
                    ((GlassSourceScrollView) source).drawGlassContent(target);
                } else {
                    source.draw(target);
                }
                target.restoreToCount(saved);
            }
        }

        private void drawSkyBackdrop(Canvas target, boolean unblurred) {
            if (skyLayout != null) {
                getLocationInWindow(glassLocation);
                skyLayout.getLocationInWindow(sourceLocation);
                int saved = target.save();
                target.translate(sourceLocation[0] - glassLocation[0], sourceLocation[1] - glassLocation[1]);
                skyLayout.drawBackdropForHeader(target, unblurred);
                target.restoreToCount(saved);
            }
        }

        private boolean drawPortableHeaderBlur(Canvas canvas) {
            if (blurMaskShader == null || getWidth() <= 0 || getHeight() <= 0) return false;
            if (!ensurePortableBlurBuffer()) return false;

            try {
                if (blurDirty) {
                    portableBlurBitmap.eraseColor(Color.TRANSPARENT);
                    int offscreenSave = portableBlurCanvas.save();
                    try {
                        portableBlurCanvas.scale(
                                portableBlurBitmap.getWidth() / portableBlurDestination.width(),
                                portableBlurBitmap.getHeight() / portableBlurDestination.height());
                        portableBlurCanvas.translate(-portableBlurDestination.left, -portableBlurDestination.top);
                        drawBackdropSource(portableBlurCanvas);
                    } finally {
                        portableBlurCanvas.restoreToCount(offscreenSave);
                    }
                    float radius = backdropBlurRadius();
                    portableBlur.apply(portableBlurBitmap,
                            radius * portableBlurBitmap.getWidth() / portableBlurDestination.width(),
                            radius * portableBlurBitmap.getHeight() / portableBlurDestination.height());
                    blurDirty = false;
                }
            } catch (RuntimeException | OutOfMemoryError unavailable) {
                releasePortableBlurBuffer();
                return false;
            }

            int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
            portableBlurPaint.setAlpha(floating ? 244 : 255);
            canvas.drawBitmap(
                    portableBlurBitmap, null, portableBlurDestination, portableBlurPaint);

            if (floating) {
                portableBlurPaint.setAlpha(255);
                canvas.restoreToCount(layer);
                return true;
            }

            float strength = backdropBlurStrength();
            blurMaskPaint.setShader(blurMaskShader);
            blurMaskPaint.setAlpha(Math.round(255f * strength));
            blurMaskPaint.setXfermode(portableBlurMaskMode);
            canvas.drawRect(0, 0, getWidth(), getHeight(), blurMaskPaint);
            blurMaskPaint.setXfermode(null);
            blurMaskPaint.setShader(null);
            blurMaskPaint.setAlpha(255);
            canvas.restoreToCount(layer);
            return true;
        }

        private boolean ensurePortableBlurBuffer() {
            float radius = backdropBlurRadius();
            int gutter = (int) Math.ceil(radius * 3f);
            if (floating) portableBlurDestination.set(-gutter, -gutter,
                    getWidth() + gutter, getHeight() + gutter);
            else portableBlurDestination.set(0, 0, getWidth(), getHeight() + gutter);
            int width = Math.max(1, (int) Math.ceil(portableBlurDestination.width() / 10f));
            int height = Math.max(1, (int) Math.ceil(portableBlurDestination.height() / 10f));
            if (portableBlurBitmap != null
                    && !portableBlurBitmap.isRecycled()
                    && portableBlurBitmap.getWidth() == width
                    && portableBlurBitmap.getHeight() == height
                    && portableBlurCanvas != null) {
                return true;
            }
            releasePortableBlurBuffer();
            try {
                portableBlurBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                portableBlurCanvas = new Canvas(portableBlurBitmap);
                blurDirty = true;
                return true;
            } catch (Throwable ignored) {
                releasePortableBlurBuffer();
                return false;
            }
        }

        private void releasePortableBlurBuffer() {
            portableBlur.release();
            portableBlurCanvas = null;
            // A recorded hardware canvas may still reference the previous bitmap.
            portableBlurBitmap = null;
            blurDirty = true;
        }

        private float backdropBlurRadius() {
            return Math.min(80f, getResources().getDisplayMetrics().density * (floating ? 20f : 18f));
        }

        private float backdropBlurStrength() {
            // Keep coverage steady as content enters the strip when scrolling up.
            return floating ? 1f : 0.94f;
        }
    }

    @android.annotation.TargetApi(31)
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
                    Class<?>[] parameters = method.getParameterTypes();
                    if ("setBackgroundRenderEffect".equals(method.getName())
                            && java.lang.reflect.Modifier.isStatic(method.getModifiers())
                            && parameters.length == 2 && parameters[0].isInstance(effect)
                            && parameters[1].isInstance(target)) {
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

    @android.annotation.TargetApi(31)
    static final class Api31HeaderBlur {
        private Api31HeaderBlur() { }

        static boolean draw(
                HeaderGlassView owner, Canvas canvas, boolean refresh) {
            if (Build.VERSION.SDK_INT < 31) return false;
            if (owner.floating) return drawFloating(owner, canvas, refresh);
            float strength = owner.backdropBlurStrength();
            if (owner.blurMaskShader == null) return false;
            int width = owner.getWidth();
            int height = owner.getHeight();
            if (width <= 0 || height <= 0) return false;
            // Blur both axes so content under the title washes out instead of
            // retaining vertical letter shapes through a horizontal smear.
            float radius = owner.backdropBlurRadius();
            int gutter = Math.max(2, (int) Math.ceil(radius * 2f));
            android.graphics.RenderNode node = (android.graphics.RenderNode) owner.blurNode;
            if (node == null) {
                node = new android.graphics.RenderNode("ZwerkWeatherHeaderStrip");
                owner.blurNode = node;
                node.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        radius, radius, Shader.TileMode.CLAMP));
                refresh = true;
            }
            // The strip touches the screen's top/side edges. Padding there captures
            // transparent sky and offscreen text; CLAMP must extend visible pixels.
            // Keep bottom padding so the lower fade can sample the page below it.
            int captureWidth = width;
            int captureHeight = height + gutter;
            if (refresh || node.getWidth() != captureWidth || node.getHeight() != captureHeight) {
                node.setPosition(0, 0, width, height + gutter);
                android.graphics.RecordingCanvas recording = node.beginRecording(captureWidth, captureHeight);
                try {
                    owner.drawBackdropSource(recording);
                } finally {
                    node.endRecording();
                }
            }
            int layer = canvas.saveLayer(0, 0, width, height, null);
            canvas.drawRenderNode(node);
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

        private static boolean drawFloating(HeaderGlassView owner, Canvas canvas, boolean refresh) {
            int width = owner.getWidth();
            int height = owner.getHeight();
            if (width <= 0 || height <= 0) return false;
            float radius = owner.backdropBlurRadius();
            int gutter = Math.round(radius * 2f);
            android.graphics.RenderNode node = owner.blurNode instanceof android.graphics.RenderNode
                    ? (android.graphics.RenderNode) owner.blurNode : null;
            if (node == null) {
                node = new android.graphics.RenderNode("ZwerkWeatherNavigationGlass");
                owner.blurNode = node;
                node.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        radius, radius, Shader.TileMode.CLAMP));
                refresh = true;
            }
            int captureWidth = width + gutter * 2;
            int captureHeight = height + gutter * 2;
            if (refresh || node.getWidth() != captureWidth || node.getHeight() != captureHeight) {
                node.setPosition(-gutter, -gutter, width + gutter, height + gutter);
                android.graphics.RecordingCanvas recording = node.beginRecording(captureWidth, captureHeight);
                try {
                    recording.translate(gutter, gutter);
                    owner.drawBackdropSource(recording);
                } finally {
                    node.endRecording();
                }
            }
            // Use one isotropic blur pass to avoid ghosting text.
            int layer = canvas.saveLayerAlpha(0, 0, width, height, 244);
            canvas.drawRenderNode(node);
            canvas.restoreToCount(layer);
            return true;
        }
    }

    private static void discardRenderNode(Object node) {
        if (Build.VERSION.SDK_INT >= 29 && node != null) Api29RenderNodes.discard(node);
    }

    @android.annotation.TargetApi(29)
    private static final class Api29RenderNodes {
        static void discard(Object node) {
            if (node instanceof android.graphics.RenderNode) {
                ((android.graphics.RenderNode) node).discardDisplayList();
            }
        }
    }

    @android.annotation.TargetApi(29)
    static final class Api29SkyComposite {
        private Api29SkyComposite() { }

        static void record(SkyLayout owner, long now) {
            android.graphics.RenderNode node = (android.graphics.RenderNode) owner.sceneNode;
            if (node == null) {
                node = new android.graphics.RenderNode("ZwerkWeatherSky");
                owner.sceneNode = node;
            }
            int width = owner.getWidth();
            int height = owner.getHeight();
            node.setPosition(0, 0, width, height);
            android.graphics.RecordingCanvas recording = node.beginRecording(width, height);
            try {
                // Record the sky separately to keep header and dock captures free of view-tree cycles.
                owner.drawComposite(recording, now, true);
            } finally {
                node.endRecording();
            }
        }

        static boolean drawBackdrop(SkyLayout owner, Canvas canvas) {
            android.graphics.RenderNode node = (android.graphics.RenderNode) owner.sceneNode;
            if (node == null || !node.hasDisplayList()
                    || node.getWidth() != owner.getWidth() || node.getHeight() != owner.getHeight()) {
                return false;
            }
            canvas.drawRenderNode(node);
            return true;
        }
    }

    @android.annotation.TargetApi(36)
    static final class Api36SkyFrameRate {
        private Api36SkyFrameRate() { }

        static void setAnimating(View view, boolean animating) {
            view.setRequestedFrameRate(animating ? View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
                    : View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE);
        }
    }

    final class SkyLayout extends FrameLayout {
        private final Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final WeatherAtmosphereRenderer atmosphere = new WeatherAtmosphereRenderer(
                getResources().getDisplayMetrics().density);
        private final Paint portableBlurPaint = new Paint(
                Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Rect sourceRect = new Rect();
        private final RectF destinationRect = new RectF();
        private final Path fogPath = new Path();
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
        private Object sceneNode;
        private boolean platformSceneCacheDisabled;
        private long transitionStarted;
        private long thunderStarted;
        private boolean animationRunning = true;
        private long pausedAt = -1L;
        private long accumulatedPause;
        private Shader fallbackShader;
        private Shader washShader;
        private Shader fogWashShader;
        private Shader nightWeatherOverlay;
        private float backgroundBlurDepth;
        private Bitmap portableBlurBitmap;
        private Canvas portableBlurCanvas;
        private Object backgroundBlurNode;
        private Object backgroundBlurEffect;
        private float backgroundBlurEffectRadius = -1f;
        private boolean platformBackgroundBlurDisabled;

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

        void setBackgroundBlurDepth(float depth) {
            float next = Math.max(0f, Math.min(1f, depth));
            if (Math.abs(next - backgroundBlurDepth) < 0.004f) return;
            backgroundBlurDepth = next;
            invalidate();
        }

        String setScene(SceneSpec target) {
            if (target == null) return currentSpec.paletteScene();
            if (target.hasSameVisuals(currentSpec)) {
                // Keep the particle clock and fade when only hour labels change.
                currentSpec = target;
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
                outgoingSpec = snapshot == null ? currentSpec : null;
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
            if (Build.VERSION.SDK_INT >= 36) Api36SkyFrameRate.setAnimating(this, allowed);
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
            return linear * linear * (3f - 2f * linear);
        }

        private Bitmap captureCurrentVisual(long now) {
            if (getWidth() <= 0 || getHeight() <= 0) return null;
            try {
                // Snapshot only interrupted fades and cap the bitmap size.
                float scale = Math.min(1f, 512f / Math.max(getWidth(), getHeight()));
                int width = Math.max(1, Math.round(getWidth() * scale));
                int height = Math.max(1, Math.round(getHeight() * scale));
                Bitmap bitmap = Bitmap.createBitmap(
                        width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                canvas.scale(width / (float) getWidth(), height / (float) getHeight());
                drawComposite(canvas, now, false);
                return bitmap;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private void clearOutgoingSnapshot() {
            // RenderNode may hold the old bitmap; let it be collected after that reference is released.
            outgoingSnapshot = null;
        }

        void release() {
            animationRunning = false;
            if (Build.VERSION.SDK_INT >= 36) Api36SkyFrameRate.setAnimating(this, false);
            discardRenderNode(sceneNode);
            sceneNode = null;
            outgoingSpec = null;
            transitionStarted = 0L;
            clearOutgoingSnapshot();
            releasePortableBackgroundBlurBuffer();
            discardRenderNode(backgroundBlurNode);
            backgroundBlurNode = null;
            backgroundBlurEffect = null;
            backgroundBlurEffectRadius = -1f;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w != oldw || h != oldh) {
                sceneNode = null;
                releasePortableBackgroundBlurBuffer();
                backgroundBlurNode = null;
                backgroundBlurEffect = null;
                backgroundBlurEffectRadius = -1f;
            }
            if (w <= 0 || h <= 0) return;
            atmosphere.onSizeChanged(w, h);
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
            boolean cached = false;
            if (Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated()
                    && !platformSceneCacheDisabled) {
                try {
                    Api29SkyComposite.record(this, now);
                    cached = true;
                } catch (Throwable ignored) {
                    platformSceneCacheDisabled = true;
                    sceneNode = null;
                }
            }
            boolean backgroundDrawn = false;
            if (backgroundBlurDepth > 0.002f
                    && Build.VERSION.SDK_INT >= 31
                    && !platformBackgroundBlurDisabled
                    && canvas.isHardwareAccelerated()) {
                try {
                    backgroundDrawn = Api31BackgroundBlur.draw(this, canvas, now);
                } catch (Throwable ignored) {
                    platformBackgroundBlurDisabled = true;
                    backgroundBlurNode = null;
                    backgroundBlurEffect = null;
                    backgroundBlurEffectRadius = -1f;
                }
            }
            if (!backgroundDrawn) {
                if (backgroundBlurDepth > 0.002f) {
                    drawPortableBlurredComposite(canvas, now);
                } else {
                    if (cached) Api29SkyComposite.drawBackdrop(this, canvas);
                    else drawComposite(canvas, now, true);
                }
            }
            if (animationRunning && isAttachedToWindow()) {
                if (headerGlass != null) headerGlass.requestAnimatedBackdropRefresh();
                if (bottomGlass != null) bottomGlass.requestAnimatedBackdropRefresh();
                postInvalidateOnAnimation();
            }
        }

        void drawBackdropForHeader(Canvas canvas, boolean unblurred) {
            if (canvas == null || getWidth() <= 0 || getHeight() <= 0) return;
            // Capture raw leaves so the dock never feeds its blurred output back into itself.
            if (!unblurred && backgroundBlurDepth > 0.002f) {
                if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated()
                        && !platformBackgroundBlurDisabled
                        && Api31BackgroundBlur.drawPublished(this, canvas)) return;
                drawPortableBlurredComposite(canvas, visualNow());
                return;
            }
            if (Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated()
                    && !platformSceneCacheDisabled && Api29SkyComposite.drawBackdrop(this, canvas)) {
                return;
            }
            drawComposite(canvas, visualNow(), false);
        }

        private void drawPortableBlurredComposite(Canvas canvas, long now) {
            float strength = easedBackgroundBlurDepth();
            if (!ensurePortableBackgroundBlurBuffer()) {
                drawComposite(canvas, now, true);
                return;
            }

            portableBlurBitmap.eraseColor(Color.TRANSPARENT);
            int offscreenSave = portableBlurCanvas.save();
            portableBlurCanvas.scale(
                    portableBlurBitmap.getWidth() / (float) getWidth(),
                    portableBlurBitmap.getHeight() / (float) getHeight());
            boolean drawSharpLayer = strength < 0.995f;
            drawComposite(portableBlurCanvas, now, !drawSharpLayer);
            portableBlurCanvas.restoreToCount(offscreenSave);

            if (drawSharpLayer) drawComposite(canvas, now, true);
            destinationRect.set(0, 0, getWidth(), getHeight());
            portableBlurPaint.setAlpha(Math.round(255f * strength));
            canvas.drawBitmap(portableBlurBitmap, null, destinationRect, portableBlurPaint);
            portableBlurPaint.setAlpha(255);
        }

        private float easedBackgroundBlurDepth() {
            float t = Math.max(0f, Math.min(1f, backgroundBlurDepth));
            return t * t * (3f - 2f * t);
        }

        private boolean ensurePortableBackgroundBlurBuffer() {
            int width = Math.max(1, (getWidth() + 7) / 8);
            int height = Math.max(1, (getHeight() + 7) / 8);
            if (portableBlurBitmap != null
                    && !portableBlurBitmap.isRecycled()
                    && portableBlurBitmap.getWidth() == width
                    && portableBlurBitmap.getHeight() == height
                    && portableBlurCanvas != null) {
                return true;
            }
            releasePortableBackgroundBlurBuffer();
            try {
                portableBlurBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                portableBlurCanvas = new Canvas(portableBlurBitmap);
                return true;
            } catch (Throwable ignored) {
                releasePortableBackgroundBlurBuffer();
                return false;
            }
        }

        private void releasePortableBackgroundBlurBuffer() {
            portableBlurCanvas = null;
            if (portableBlurBitmap != null) {
                try { portableBlurBitmap.recycle(); } catch (Exception ignored) { }
                portableBlurBitmap = null;
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            // Keep effects in onDraw so child controls stay above them.
            super.dispatchDraw(canvas);
        }

        private void drawComposite(Canvas canvas, long now, boolean cleanupFinished) {
            float w = getWidth();
            float h = getHeight();
            float progress = transitionProgress(now);
            if (cleanupFinished && progress >= 1f) finishCompositeTransition();
            boolean transitioning = outgoingSpec != null || outgoingSnapshot != null;

            if (outgoingSnapshot != null) {
                paint.setShader(null);
                paint.setAlpha(255);
                destinationRect.set(0, 0, w, h);
                canvas.drawBitmap(outgoingSnapshot, null, destinationRect, paint);
            } else if (outgoingSpec != null) {
                // Outgoing lightning fades with its scene; incoming flashes wait until the fade is nearly complete.
                drawSceneLayer(canvas, outgoingSpec, 255, now,
                        "thunder".equals(outgoingSpec.effect));
            }

            if (!transitioning) {
                drawSceneLayer(canvas, currentSpec, 255, now, true);
            } else {
                drawSceneLayer(canvas, currentSpec, Math.round(progress * 255f), now, progress >= 0.88f);
            }
        }

        private void finishCompositeTransition() {
            outgoingSpec = null;
            clearOutgoingSnapshot();
            transitionStarted = 0L;
        }

        private void drawSceneLayer(
                Canvas canvas, SceneSpec spec, int layerAlpha, long now, boolean allowLightning) {
            if (spec == null || layerAlpha <= 0) return;
            int save = layerAlpha >= 255 ? canvas.save() : canvas.saveLayerAlpha(
                0, 0, getWidth(), getHeight(),
                    Math.max(0, Math.min(255, layerAlpha)));
            Bitmap base = bitmapFor(spec.base);
            if (base != null) drawCover(canvas, base, getWidth(), getHeight(), 255, now);
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

            atmosphere.drawAmbient(canvas, getWidth(), getHeight(), now, spec);

            if ("thunder".equals(spec.effect)) {
                atmosphere.drawRain(canvas, getWidth(), getHeight(), now, spec);
                if (animationRunning && allowLightning) {
                    atmosphere.drawLightning(canvas, getWidth(), getHeight(), now,
                            thunderStarted);
                }
            } else if ("rain".equals(spec.effect)) {
                atmosphere.drawRain(canvas, getWidth(), getHeight(), now, spec);
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

        private void drawCover(Canvas canvas, Bitmap bitmap, float w, float h, int alpha,
                long now) {
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
            // Overscan the moving sky so its edges stay offscreen.
            float marginX = w * 0.018f;
            float marginY = h * 0.018f;
            float driftX = (float) Math.sin(now * 0.000045) * marginX * 0.70f;
            float driftY = (float) Math.sin(now * 0.000032 + 1.2) * marginY * 0.70f;
            destinationRect.set(-marginX + driftX, -marginY + driftY,
                    w + marginX + driftX, h + marginY + driftY);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(alpha);
            canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint);
            paint.setAlpha(255);
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

    }

    @android.annotation.TargetApi(31)
    static final class Api31BackgroundBlur {
        private Api31BackgroundBlur() { }

        static boolean drawPublished(SkyLayout owner, Canvas canvas) {
            android.graphics.RenderNode node = (android.graphics.RenderNode) owner.backgroundBlurNode;
            if (node == null || !node.hasDisplayList()
                    || node.getWidth() != owner.getWidth() || node.getHeight() != owner.getHeight()) {
                return false;
            }
            canvas.drawRenderNode(node);
            return true;
        }

        static boolean draw(SkyLayout owner, Canvas canvas, long now) {
            if (Build.VERSION.SDK_INT < 31 || owner == null || !canvas.isHardwareAccelerated()) {
                return false;
            }
            int width = owner.getWidth();
            int height = owner.getHeight();
            if (width <= 0 || height <= 0) return false;

            android.graphics.RenderNode node =
                    owner.backgroundBlurNode instanceof android.graphics.RenderNode
                            ? (android.graphics.RenderNode) owner.backgroundBlurNode
                            : null;
            if (node == null) {
                node = new android.graphics.RenderNode("ZwerkWeatherBackground");
                owner.backgroundBlurNode = node;
            }
            node.setPosition(0, 0, width, height);
            android.graphics.RecordingCanvas recording = node.beginRecording(width, height);
            // Keep outgoing bitmaps alive until drawRenderNode submits the deferred recording.
            try {
                if (!Api29SkyComposite.drawBackdrop(owner, recording)) {
                    owner.drawComposite(recording, now, false);
                }
            } finally {
                node.endRecording();
            }

            float density = owner.getResources().getDisplayMetrics().density;
            float radius = Math.max(0.1f,
                    Math.round(owner.easedBackgroundBlurDepth() * density * 24f * 2f) / 2f);
            android.graphics.RenderEffect effect =
                    owner.backgroundBlurEffect instanceof android.graphics.RenderEffect
                            ? (android.graphics.RenderEffect) owner.backgroundBlurEffect
                            : null;
            if (effect == null || Math.abs(radius - owner.backgroundBlurEffectRadius) >= 0.5f) {
                effect = android.graphics.RenderEffect.createBlurEffect(
                        radius, radius, Shader.TileMode.CLAMP);
                owner.backgroundBlurEffect = effect;
                owner.backgroundBlurEffectRadius = radius;
            }
            node.setRenderEffect(effect);
            int save = canvas.save();
            canvas.clipRect(0, 0, width, height);
            canvas.drawRenderNode(node);
            canvas.restoreToCount(save);
            if (owner.transitionProgress(now) >= 1f
                    && (owner.outgoingSpec != null || owner.outgoingSnapshot != null)) {
                owner.finishCompositeTransition();
            }
            return true;
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
