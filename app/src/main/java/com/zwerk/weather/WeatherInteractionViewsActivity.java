package com.zwerk.weather;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;





abstract class WeatherInteractionViewsActivity extends WeatherVisualEffectsActivity {
    RefreshScrollView mainScroll;

    final class RefreshScrollView extends GlassSourceScrollView {
        private static final int LOCK_NONE = 0;
        private static final int LOCK_VERTICAL = 1;
        private static final int LOCK_HORIZONTAL = 2;

        private final int touchSlop;
        private int activePointerId = MotionEvent.INVALID_POINTER_ID;
        private float downX;
        private float downY;
        private int lock = LOCK_NONE;
        private boolean eligible;
        private float pullDistance;
        private DayListScrollView touchedDayList;

        RefreshScrollView(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        void onDayListTouchStart(DayListScrollView list) {
            touchedDayList = list;
            if (list.getScrollY() > 0) cancelPull();
        }

        void onDayListTouchEnd(DayListScrollView list) {
            if (touchedDayList == list) touchedDayList = null;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                    && touchedDayList != null) {
                int index = event.findPointerIndex(activePointerId);
                if (index >= 0) {
                    float dx = event.getX(index) - downX;
                    float dy = event.getY(index) - downY;
                    if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx)
                            && touchedDayList.canScrollVertically(dy < 0f ? 1 : -1)) {
                        return false;
                    }
                }
            }
            return super.onInterceptTouchEvent(event);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            boolean triggerRefresh = false;

            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    activePointerId = event.getPointerId(0);
                    downX = event.getX(0);
                    downY = event.getY(0);
                    lock = LOCK_NONE;
                    pullDistance = 0f;
                    eligible = getScrollY() == 0 && !weatherLoadActive
                            && (!(WeatherInteractionViewsActivity.this instanceof MainActivity)
                            || ((MainActivity) WeatherInteractionViewsActivity.this).allowsPullRefresh());
                    if (eligible && refreshIndicator != null) refreshIndicator.beginPull();
                    break;
                }
                case MotionEvent.ACTION_POINTER_DOWN: {
                    int index = event.getActionIndex();
                    activePointerId = event.getPointerId(index);
                    downX = event.getX(index);
                    downY = event.getY(index);
                    pullDistance = 0f;
                    lock = LOCK_NONE;
                    break;
                }
                case MotionEvent.ACTION_POINTER_UP:
                    rebaseAfterPointerUp(event);
                    break;
                case MotionEvent.ACTION_MOVE: {
                    if (!eligible || weatherLoadActive || getScrollY() != 0) {
                        cancelPull();
                        break;
                    }
                    int index = event.findPointerIndex(activePointerId);
                    if (index < 0) {
                        cancelPull();
                        break;
                    }
                    float dx = event.getX(index) - downX;
                    float dy = event.getY(index) - downY;
                    if (touchedDayList != null && Math.abs(dy) > touchSlop
                            && Math.abs(dy) > Math.abs(dx)
                            && touchedDayList.canScrollVertically(dy < 0f ? 1 : -1)) {
                        cancelPull();
                        break;
                    }
                    if (lock == LOCK_NONE && Math.max(Math.abs(dx), Math.abs(dy)) > touchSlop) {
                        if (Math.abs(dx) > Math.abs(dy) * 0.92f) {
                            lock = LOCK_HORIZONTAL;
                            cancelPull();
                        } else if (dy > 0f) {
                            lock = LOCK_VERTICAL;
                        } else {
                            cancelPull();
                        }
                    }
                    if (lock == LOCK_VERTICAL && dy > 0f) {
                        pullDistance = Math.min(dp(132), dy * 0.46f);
                        if (refreshIndicator != null) {
                            refreshIndicator.setPull(
                                    pullDistance / Math.max(1f, dp(74)),
                                    pullDistance >= dp(74));
                        }
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                    triggerRefresh = eligible
                            && lock == LOCK_VERTICAL
                            && pullDistance >= dp(74)
                            && getScrollY() == 0
                            && !weatherLoadActive;
                    activePointerId = MotionEvent.INVALID_POINTER_ID;
                    eligible = false;
                    lock = LOCK_NONE;
                    pullDistance = 0f;
                    touchedDayList = null;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    cancelPull();
                    activePointerId = MotionEvent.INVALID_POINTER_ID;
                    touchedDayList = null;
                    break;
            }

            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP) {
                if (triggerRefresh) {
                    performPullRefresh();
                } else if (refreshIndicator != null) {
                    refreshIndicator.settle();
                }
            } else if (action == MotionEvent.ACTION_CANCEL && refreshIndicator != null) {
                refreshIndicator.settle();
            }
            return handled;
        }

        private void rebaseAfterPointerUp(MotionEvent event) {
            if (event.getPointerId(event.getActionIndex()) != activePointerId) return;
            int replacement = event.getActionIndex() == 0 ? 1 : 0;
            if (replacement >= event.getPointerCount()) {
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                cancelPull();
                return;
            }
            activePointerId = event.getPointerId(replacement);
            downX = event.getX(replacement);
            downY = event.getY(replacement);
            pullDistance = 0f;
            lock = LOCK_NONE;
        }

        private void cancelPull() {
            eligible = false;
            pullDistance = 0f;
            lock = LOCK_NONE;
            if (refreshIndicator != null && !refreshIndicator.isRefreshing()) {
                refreshIndicator.settle();
            }
        }
    }



    final class DayListScrollView extends ScrollView {
        private static final int AXIS_UNDECIDED = 0;
        private static final int AXIS_HORIZONTAL = 1;
        private static final int AXIS_VERTICAL = 2;

        private final int touchSlop;
        private int activePointerId = MotionEvent.INVALID_POINTER_ID;
        private int lockedAxis = AXIS_UNDECIDED;
        private int verticalDirection;
        private float downX;
        private float downY;
        private float lastY;

        DayListScrollView(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    if (mainScroll != null) mainScroll.onDayListTouchStart(this);
                    rebaseGesture(event, event.getActionIndex());
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    rebaseGesture(event, event.getActionIndex());
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    rebaseAfterPointerUp(event);
                    break;
                case MotionEvent.ACTION_MOVE:
                    int index = event.findPointerIndex(activePointerId);
                    if (index < 0 && event.getPointerCount() > 0) {
                        rebaseGesture(event, 0);
                        break;
                    }
                    if (index >= 0) {
                        float dx = event.getX(index) - downX;
                        float currentY = event.getY(index);
                        float dy = currentY - downY;
                        if (lockedAxis == AXIS_UNDECIDED
                                && Math.max(Math.abs(dx), Math.abs(dy)) > touchSlop) {
                            lockedAxis = Math.abs(dx) > Math.abs(dy)
                                    ? AXIS_HORIZONTAL : AXIS_VERTICAL;
                        }
                        if (lockedAxis == AXIS_VERTICAL) {
                            float stepY = currentY - lastY;
                            if (stepY != 0f) verticalDirection = stepY < 0f ? 1 : -1;
                            allowParentIntercept(!canScrollVertically(verticalDirection));
                        } else if (lockedAxis == AXIS_HORIZONTAL) {
                            // The hourly strip claims horizontal drags; a day row can still
                            // hand a horizontal drag to the forecast page switcher.
                            allowParentIntercept(true);
                        }
                        lastY = currentY;
                    }
                    break;
                default:
                    break;
            }

            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                lockedAxis = AXIS_UNDECIDED;
                verticalDirection = 0;
                if (mainScroll != null) mainScroll.onDayListTouchEnd(this);
                allowParentIntercept(true);
            }
            return handled;
        }

        @Override
        public void requestDisallowInterceptTouchEvent(boolean disallow) {
            super.requestDisallowInterceptTouchEvent(disallow);
            if (!disallow && lockedAxis == AXIS_VERTICAL
                    && canScrollVertically(verticalDirection)) {
                // The hourly strip released a vertical drag. Let this list intercept
                // the next move while keeping the page scroller out of that gesture.
                allowParentIntercept(false);
            }
        }

        private void rebaseGesture(MotionEvent event, int index) {
            if (index < 0 || index >= event.getPointerCount()) {
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                return;
            }
            activePointerId = event.getPointerId(index);
            downX = event.getX(index);
            downY = event.getY(index);
            lastY = downY;
            lockedAxis = AXIS_UNDECIDED;
            verticalDirection = 0;
        }

        private void rebaseAfterPointerUp(MotionEvent event) {
            if (event.getPointerId(event.getActionIndex()) != activePointerId) return;
            int replacement = event.getActionIndex() == 0 ? 1 : 0;
            if (replacement < event.getPointerCount()) rebaseGesture(event, replacement);
            else activePointerId = MotionEvent.INVALID_POINTER_ID;
        }

        private void allowParentIntercept(boolean allow) {
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(!allow);
            }
        }
    }

    final class GestureHorizontalScrollView extends HorizontalScrollView {
        private static final int AXIS_UNDECIDED = 0;
        private static final int AXIS_HORIZONTAL = 1;
        private static final int AXIS_VERTICAL = 2;

        private final int touchSlop;
        private float downX;
        private float downY;
        private int activePointerId = MotionEvent.INVALID_POINTER_ID;
        private int lockedAxis = AXIS_UNDECIDED;

        GestureHorizontalScrollView(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            // Android 12+ renders EdgeEffect as stretch. API 36 therefore supplies the
            // restrained edge elasticity and fling absorption without a custom glow.
            setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    rebaseGesture(event, event.getActionIndex());
                    // The bounded day list and page scroller choose who owns a vertical
                    // drag on its first MOVE. Horizontal drags are claimed below.
                    setParentInterceptDisallowed(!insideDayList());
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    setParentInterceptDisallowed(false);
                    rebaseGesture(event, event.getActionIndex());
                    setParentInterceptDisallowed(!insideDayList());
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    setParentInterceptDisallowed(false);
                    rebaseAfterPointerUp(event);
                    setParentInterceptDisallowed(activePointerId != MotionEvent.INVALID_POINTER_ID
                            && !insideDayList());
                    break;
                case MotionEvent.ACTION_MOVE:
                    int pointerIndex = event.findPointerIndex(activePointerId);
                    if (pointerIndex < 0 && event.getPointerCount() > 0) {
                        rebaseGesture(event, 0);
                        setParentInterceptDisallowed(true);
                        break;
                    }
                    if (pointerIndex >= 0) {
                        float dx = Math.abs(event.getX(pointerIndex) - downX);
                        float dy = Math.abs(event.getY(pointerIndex) - downY);
                        if (lockedAxis == AXIS_UNDECIDED && (dx > touchSlop || dy > touchSlop)) {
                            lockedAxis = dx > dy ? AXIS_HORIZONTAL : AXIS_VERTICAL;
                        }
                        if (lockedAxis == AXIS_HORIZONTAL) {
                            // Keep ownership even at either horizontal edge. Releasing here is
                            // what makes a drag feel stuck and can lose the reverse-direction move.
                            setParentInterceptDisallowed(true);
                        } else if (lockedAxis == AXIS_VERTICAL) {
                            // The outer ScrollView may take over on the next move without a jump.
                            setParentInterceptDisallowed(false);
                        }
                    }
                    break;
                default:
                    break;
            }

            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                resetGestureState();
            }
            return handled;
        }

        private void rebaseGesture(MotionEvent event, int pointerIndex) {
            if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) {
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                lockedAxis = AXIS_UNDECIDED;
                return;
            }
            activePointerId = event.getPointerId(pointerIndex);
            downX = event.getX(pointerIndex);
            downY = event.getY(pointerIndex);
            lockedAxis = AXIS_UNDECIDED;
        }

        private void rebaseAfterPointerUp(MotionEvent event) {
            int liftedIndex = event.getActionIndex();
            int replacement = -1;
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (i != liftedIndex) {
                    replacement = i;
                    break;
                }
            }
            if (replacement >= 0) {
                rebaseGesture(event, replacement);
            } else {
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                lockedAxis = AXIS_UNDECIDED;
            }
        }

        private void resetGestureState() {
            activePointerId = MotionEvent.INVALID_POINTER_ID;
            lockedAxis = AXIS_UNDECIDED;
            setParentInterceptDisallowed(false);
            invalidate();
        }

        private void setParentInterceptDisallowed(boolean disallow) {
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(disallow);
            }
        }

        private boolean insideDayList() {
            android.view.ViewParent ancestor = getParent();
            while (ancestor != null) {
                if (ancestor instanceof DayListScrollView) return true;
                ancestor = ancestor.getParent();
            }
            return false;
        }
    }

}
