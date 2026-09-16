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


abstract class WeatherInteractionViewsActivity extends WeatherVisualEffectsActivity {
    RefreshScrollView mainScroll;

    final class RefreshScrollView extends ScrollView {
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

        RefreshScrollView(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
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
                    eligible = getScrollY() == 0 && !weatherLoadActive;
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
                    break;
                case MotionEvent.ACTION_CANCEL:
                    cancelPull();
                    activePointerId = MotionEvent.INVALID_POINTER_ID;
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
                    setParentInterceptDisallowed(true);
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    setParentInterceptDisallowed(false);
                    rebaseGesture(event, event.getActionIndex());
                    setParentInterceptDisallowed(true);
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    setParentInterceptDisallowed(false);
                    rebaseAfterPointerUp(event);
                    setParentInterceptDisallowed(activePointerId != MotionEvent.INVALID_POINTER_ID);
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
    }

    final class GestureVerticalScrollView extends ScrollView {
        private final int touchSlop;
        private float downX;
        private float downY;
        private int activePointerId = MotionEvent.INVALID_POINTER_ID;

        GestureVerticalScrollView(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    rebaseGesture(event, event.getActionIndex());
                    requestParentForCurrentRange();
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    setParentInterceptDisallowed(false);
                    rebaseGesture(event, event.getActionIndex());
                    requestParentForCurrentRange();
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    setParentInterceptDisallowed(false);
                    rebaseAfterPointerUp(event);
                    requestParentForCurrentRange();
                    break;
                case MotionEvent.ACTION_MOVE:
                    int pointerIndex = event.findPointerIndex(activePointerId);
                    if (pointerIndex < 0 && event.getPointerCount() > 0) {
                        rebaseGesture(event, 0);
                        requestParentForCurrentRange();
                        break;
                    }
                    if (pointerIndex >= 0) {
                        float dx = Math.abs(event.getX(pointerIndex) - downX);
                        float signedDy = event.getY(pointerIndex) - downY;
                        float dy = Math.abs(signedDy);
                        if (dy > touchSlop && dy > dx) {
                            int direction = signedDy < 0 ? 1 : -1;
                            // While the inner list can consume motion it keeps the gesture. At an
                            // edge, ownership returns to the page; a fling that reaches the edge is
                            // still absorbed by the platform stretch EdgeEffect before settling.
                            setParentInterceptDisallowed(canScrollVertically(direction));
                        } else if (dx > touchSlop) {
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
                return;
            }
            activePointerId = event.getPointerId(pointerIndex);
            downX = event.getX(pointerIndex);
            downY = event.getY(pointerIndex);
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
            }
        }

        private void requestParentForCurrentRange() {
            setParentInterceptDisallowed(
                    activePointerId != MotionEvent.INVALID_POINTER_ID
                            && (canScrollVertically(1) || canScrollVertically(-1)));
        }

        private void resetGestureState() {
            activePointerId = MotionEvent.INVALID_POINTER_ID;
            setParentInterceptDisallowed(false);
            invalidate();
        }

        private void setParentInterceptDisallowed(boolean disallow) {
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(disallow);
            }
        }
    }

}
