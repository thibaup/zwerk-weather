package com.zwerk.weather;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Build;
import android.widget.ScrollView;

/** Publishes child content for glass while ScrollView owns its native edge effects. */
class GlassSourceScrollView extends ScrollView {
    private Object contentNode;
    private boolean contentCacheDisabled;
    private Runnable contentDrawListener;

    GlassSourceScrollView(Context context) {
        super(context);
    }

    @Override public void draw(Canvas canvas) {
        // EdgeEffect applies stretch to the RenderNode backing this canvas. Keep
        // ScrollView.draw on the framework canvas, whose stretch is reset by View.
        super.draw(canvas);
        if (canvas.isHardwareAccelerated() && contentDrawListener != null) {
            contentDrawListener.run();
        }
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        if (Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated()
                && !contentCacheDisabled && getWidth() > 0 && getHeight() > 0) {
            try {
                Api29.drawContent(this, canvas);
                return;
            } catch (Throwable ignored) {
                contentCacheDisabled = true;
                Api29.discardContent(this);
                contentNode = null;
            }
        }
        super.dispatchDraw(canvas);
    }

    boolean drawGlassContent(Canvas canvas) {
        if (Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated()) {
            return !contentCacheDisabled && Api29.drawPublishedContent(this, canvas);
        }
        // Older platforms use glow, so their portable capture remains supported.
        // On Android 12+, redrawing descendants into a bitmap can end the native
        // stretch of a nested scroller. Omit foreground glass if no safe node exists.
        if (Build.VERSION.SDK_INT < 31) {
            drawContent(canvas);
            return true;
        }
        return false;
    }

    void setContentDrawListener(Runnable listener) {
        contentDrawListener = listener;
    }

    private void drawContent(Canvas canvas) {
        // Exclude ScrollView.draw, and therefore its stateful EdgeEffect.draw.
        super.dispatchDraw(canvas);
    }

    @Override protected void onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 29) Api29.discardContent(this);
        contentNode = null;
        super.onDetachedFromWindow();
    }

    @android.annotation.TargetApi(29)
    private static final class Api29 {
        static void drawContent(GlassSourceScrollView owner, Canvas canvas) {
            android.graphics.RenderNode node = (android.graphics.RenderNode) owner.contentNode;
            if (node == null) {
                node = new android.graphics.RenderNode("ZwerkWeatherUnblurredContent");
                // The normal caller applies the viewport clip and scroll offset.
                // Keep the source in content coordinates for every consumer.
                node.setClipToBounds(false);
                owner.contentNode = node;
            }
            int width = owner.getWidth();
            int height = owner.getHeight();
            node.setPosition(0, 0, width, height);
            android.graphics.RecordingCanvas recording = node.beginRecording(width, height);
            try {
                owner.drawContent(recording);
            } finally {
                node.endRecording();
            }
            canvas.drawRenderNode(node);
        }

        static boolean drawPublishedContent(GlassSourceScrollView owner, Canvas canvas) {
            android.graphics.RenderNode node = (android.graphics.RenderNode) owner.contentNode;
            if (node == null || !node.hasDisplayList()
                    || node.getWidth() != owner.getWidth() || node.getHeight() != owner.getHeight()) {
                return false;
            }
            canvas.drawRenderNode(node);
            return true;
        }

        static void discardContent(GlassSourceScrollView owner) {
            if (owner.contentNode instanceof android.graphics.RenderNode) {
                ((android.graphics.RenderNode) owner.contentNode).discardDisplayList();
            }
        }
    }
}
