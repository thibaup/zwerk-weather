package com.zwerk.weather;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Log;
import android.view.View;

import java.util.ArrayList;

/** One shared, unblurred map source; each control selects OEM, GPU or software backdrop blur. */
final class RadarBackdrop {
    private final RadarMapView source;
    private final ArrayList<Surface> surfaces = new ArrayList<>();
    private final int[] sourceLocation = new int[2];
    private final int[] ownerLocation = new int[2];
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF bitmapDestination = new RectF();
    private final SoftwareBlur softwareBlur = new SoftwareBlur();
    private Bitmap bitmap;
    private Canvas bitmapCanvas;
    private Object filteredNode;
    private Object platformEffect;
    private Object nativeEffect;
    private boolean active;
    private boolean dirty = true;
    private boolean gpuDisabled;
    private boolean platformLogged;
    private boolean softwareLogged;
    private boolean oemLogged;
    private float radius;

    RadarBackdrop(RadarMapView source) {
        this.source = source;
        source.setBackdropDrawListener(() -> {
            if (!active || radius <= 0f) return;
            dirty = true;
            for (Surface surface : surfaces) if (!surface.oem) surface.invalidateSelf();
        });
    }

    void setActive(boolean value) {
        active = value;
        for (Surface surface : surfaces) surface.updateNativeEffect();
        if (!value) releaseCapture();
        updateCaptureRequirement();
    }

    void updateAppearance(ArrayList<View> controls) {
        int transparency = new WeatherPreferences(source.getContext()).tileTransparency();
        float strength = Math.min(1f, (100 - transparency) / 30f);
        float nextRadius = Math.min(60f, source.getResources().getDisplayMetrics().density * 18f) * strength;
        if (radius != nextRadius) {
            radius = nextRadius;
            nativeEffect = null;
            platformEffect = null;
            releaseCapture();
        }
        for (View control : controls) {
            Surface surface;
            if (control.getBackground() instanceof Surface) surface = (Surface) control.getBackground();
            else {
                surface = new Surface(control);
                surfaces.add(surface);
                control.setBackground(surface);
            }
            surface.tint = RadarControlStyle.background(control.getContext());
            surface.tint.setBounds(surface.getBounds());
            surface.updateNativeEffect();
            surface.invalidateSelf();
        }
        updateCaptureRequirement();
    }

    private void updateCaptureRequirement() {
        boolean capture = false;
        for (Surface surface : surfaces) {
            if (active && radius > 0f && surface.owner.isAttachedToWindow() && !surface.oem) {
                capture = true;
                break;
            }
        }
        source.setBackdropCaptureEnabled(capture && !gpuDisabled && Build.VERSION.SDK_INT >= 31);
    }

    void release() {
        active = false;
        for (Surface surface : surfaces) {
            OemBackdropBlur.clear(surface.owner);
            surface.owner.removeOnAttachStateChangeListener(surface);
        }
        surfaces.clear();
        source.setBackdropDrawListener(null);
        source.setBackdropCaptureEnabled(false);
        releaseCapture();
    }

    private void releaseCapture() {
        if (Build.VERSION.SDK_INT >= 31 && filteredNode != null) Api31.discard(filteredNode);
        filteredNode = null;
        bitmapCanvas = null;
        bitmap = null;
        softwareBlur.release();
        dirty = true;
    }

    private void draw(Canvas canvas, View owner) {
        if (!active || radius <= 0f || source.getWidth() <= 0 || source.getHeight() <= 0) return;
        source.getLocationInWindow(sourceLocation);
        owner.getLocationInWindow(ownerLocation);
        int save = canvas.save();
        canvas.translate(sourceLocation[0] - ownerLocation[0], sourceLocation[1] - ownerLocation[1]);
        try {
            if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated() && !gpuDisabled) {
                try {
                    if (Api31.draw(this, canvas)) {
                        if (!platformLogged) {
                            platformLogged = true;
                            Log.d("ZwerkWeather", "radar blur=platform");
                        }
                        return;
                    }
                } catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) {
                    gpuDisabled = true;
                    releaseCapture();
                    updateCaptureRequirement();
                }
            }
            drawSoftware(canvas);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private void drawSoftware(Canvas canvas) {
        int width = Math.max(1, (source.getWidth() + 7) / 8);
        int height = Math.max(1, (source.getHeight() + 7) / 8);
        try {
            if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
                releaseCapture();
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmapCanvas = new Canvas(bitmap);
            }
            if (dirty) {
                int save = bitmapCanvas.save();
                bitmapCanvas.scale(width / (float) source.getWidth(), height / (float) source.getHeight());
                source.drawSoftwareBackdrop(bitmapCanvas);
                bitmapCanvas.restoreToCount(save);
                softwareBlur.apply(bitmap, radius * width / source.getWidth(), radius * height / source.getHeight());
                dirty = false;
            }
            bitmapDestination.set(0, 0, source.getWidth(), source.getHeight());
            canvas.drawBitmap(bitmap, null, bitmapDestination, bitmapPaint);
            if (!softwareLogged) {
                softwareLogged = true;
                Log.d("ZwerkWeather", "radar blur=software");
            }
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            releaseCapture();
        }
    }

    private final class Surface extends Drawable implements View.OnAttachStateChangeListener {
        final View owner;
        final Path clip = new Path();
        final RectF bounds = new RectF();
        GlassDrawable tint;
        boolean oem;
        boolean oemDisabled;
        Object appliedEffect;

        Surface(View owner) {
            this.owner = owner;
            tint = RadarControlStyle.background(owner.getContext());
            owner.setStateListAnimator(null);
            owner.setElevation(0f);
            owner.setTranslationZ(0f);
            owner.setClipToOutline(true);
            owner.addOnAttachStateChangeListener(this);
        }

        void updateNativeEffect() {
            boolean eligible = active && radius > 0f && Build.VERSION.SDK_INT >= 31
                    && owner.isAttachedToWindow() && owner.isHardwareAccelerated()
                    && !BuildConfig.FORCE_GENERIC_RADAR_BLUR && !oemDisabled;
            if (oem && eligible && appliedEffect == nativeEffect) return;
            if (oem) OemBackdropBlur.clear(owner);
            oem = false;
            appliedEffect = null;
            if (eligible) {
                try {
                    if (nativeEffect == null) nativeEffect = Api31.effect(radius, radius);
                    oem = OemBackdropBlur.apply(owner, nativeEffect);
                } catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) {
                    oem = false;
                }
                if (oem) appliedEffect = nativeEffect;
                else {
                    OemBackdropBlur.clear(owner);
                    oemDisabled = true;
                }
                if (oem && !oemLogged) {
                    oemLogged = true;
                    Log.d("ZwerkWeather", "radar blur=oem-background");
                }
            }
            invalidateSelf();
        }

        @Override protected void onBoundsChange(Rect rectangle) {
            bounds.set(rectangle);
            clip.reset();
            float corner = owner.getResources().getDisplayMetrics().density * 22f;
            clip.addRoundRect(bounds, corner, corner, Path.Direction.CW);
            tint.setBounds(rectangle);
            owner.invalidateOutline();
        }

        @Override public void draw(Canvas canvas) {
            int save = canvas.save();
            canvas.clipPath(clip);
            if (!oem) RadarBackdrop.this.draw(canvas, owner);
            tint.draw(canvas);
            canvas.restoreToCount(save);
        }

        @Override public void getOutline(Outline outline) {
            outline.setRoundRect(getBounds(), owner.getResources().getDisplayMetrics().density * 22f);
            outline.setAlpha(1f);
        }
        @Override public void setAlpha(int alpha) { tint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { tint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

        @Override public void onViewAttachedToWindow(View view) {
            updateNativeEffect();
            updateCaptureRequirement();
        }

        @Override public void onViewDetachedFromWindow(View view) {
            if (oem) OemBackdropBlur.clear(owner);
            oem = false;
            appliedEffect = null;
            updateCaptureRequirement();
        }
    }

    @android.annotation.TargetApi(31)
    private static final class Api31 {
        static Object effect(float x, float y) {
            return android.graphics.RenderEffect.createBlurEffect(x, y, Shader.TileMode.CLAMP);
        }

        static boolean draw(RadarBackdrop owner, Canvas canvas) {
            Object published = owner.source.publishedBackdrop();
            if (!(published instanceof android.graphics.RenderNode)) return false;
            int width = owner.source.getWidth();
            int height = owner.source.getHeight();
            android.graphics.RenderNode node = (android.graphics.RenderNode) owner.filteredNode;
            if (node == null) {
                node = new android.graphics.RenderNode("ZwerkRadarBackdropBlur");
                owner.filteredNode = node;
                if (owner.platformEffect == null) owner.platformEffect = effect(owner.radius, owner.radius);
                node.setRenderEffect((android.graphics.RenderEffect) owner.platformEffect);
                owner.dirty = true;
            }
            if (owner.dirty || node.getWidth() != width || node.getHeight() != height) {
                node.setPosition(0, 0, width, height);
                android.graphics.RecordingCanvas recording = node.beginRecording(width, height);
                try {
                    recording.drawRenderNode((android.graphics.RenderNode) published);
                } finally {
                    node.endRecording();
                }
                owner.dirty = false;
            }
            // RenderEffect already downsamples internally; another scale loses map detail.
            canvas.drawRenderNode(node);
            return true;
        }

        static void discard(Object node) { ((android.graphics.RenderNode) node).discardDisplayList(); }
    }
}
