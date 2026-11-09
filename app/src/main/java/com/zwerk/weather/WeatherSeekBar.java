package com.zwerk.weather;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.SeekBar;

/** Lets vertical scrolling start on a slider without changing its value. */
final class WeatherSeekBar extends SeekBar {
    private final int touchSlop;
    private final boolean deliberateDrag;
    private final boolean tapToSeek;
    private float downX;
    private float downY;
    private boolean dragging;
    private boolean verticalGesture;

    WeatherSeekBar(Context context) {
        this(context, true);
    }

    WeatherSeekBar(Context context, boolean deliberateDrag) {
        this(context, deliberateDrag, false);
    }

    WeatherSeekBar(Context context, boolean deliberateDrag, boolean tapToSeek) {
        super(context);
        this.deliberateDrag = deliberateDrag;
        this.tapToSeek = tapToSeek;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        ViewParent parent = getParent();
        int action = event.getActionMasked();
        if (!deliberateDrag) {
            if (action == MotionEvent.ACTION_DOWN && isEnabled() && parent != null) {
                parent.requestDisallowInterceptTouchEvent(true);
            }
            boolean handled = super.onTouchEvent(event);
            if (parent != null && (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL || !handled)) {
                parent.requestDisallowInterceptTouchEvent(false);
            }
            return handled;
        }
        if (!isEnabled()) return super.onTouchEvent(event);
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            dragging = false;
            verticalGesture = false;
            // Do not send DOWN to SeekBar: it can jump to the touched position.
            // The ScrollView remains free to intercept a vertical gesture.
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && !dragging && !verticalGesture) {
            float dx = Math.abs(event.getX() - downX);
            float dy = Math.abs(event.getY() - downY);
            if (Math.max(dx, dy) <= touchSlop) return true;
            if (dx <= dy * 1.5f) {
                verticalGesture = true;
                return true;
            }
            dragging = true;
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            beginSeek(event);
        }
        boolean tap = action == MotionEvent.ACTION_UP && !dragging && !verticalGesture;
        if (tap && tapToSeek) beginSeek(event);
        boolean handled = dragging || (tap && tapToSeek) ? super.onTouchEvent(event) : true;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (tap) performClick();
            dragging = false;
            verticalGesture = false;
            if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        }
        return handled;
    }

    private void beginSeek(MotionEvent event) {
        MotionEvent down = MotionEvent.obtain(event);
        down.setAction(MotionEvent.ACTION_DOWN);
        down.setLocation(downX, downY);
        super.onTouchEvent(down);
        down.recycle();
    }

    @Override public boolean performClick() { return super.performClick(); }
}
