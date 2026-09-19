package com.zwerk.weather;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ClipData;
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
import android.view.DragEvent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
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
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.Executors;


abstract class WeatherOverviewRenderingActivity extends MinuteForecastRenderingActivity {
    static final String PREF_OVERVIEW_TILE_ORDER = "overview_information_tile_order_v1";
    static final String PREF_OVERVIEW_BLOCK_ORDER = "overview_top_level_order_v1";
    static final String OVERVIEW_TILE_STREAM_PREFIX = "tile:";
    static final int ACCESSIBILITY_MOVE_TILE_EARLIER =
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
    static final int ACCESSIBILITY_MOVE_TILE_LATER =
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
    static final String[] DEFAULT_OVERVIEW_TILE_ORDER = new String[] {
            "uv",
            "feels_like",
            "humidity",
            "wind",
            "air_pressure",
            "visibility",
            "cloud_cover",
            "wind_gust",
            "thunder_chance",
            "dew_point",
            "heat_index",
            "wind_chill",
            "temperature_change_24h",
            "precipitation_24h",
            "air_quality",
            "pollen"
    };
    static final String[] DEFAULT_OVERVIEW_BLOCK_ORDER = new String[] {
            "hero",
            "alerts",
            "hourly",
            "daily",
            "details",
            "solar",
            "moon",
            "attribution"
    };

    static final int OVERVIEW_ID_HERO = 0x6f710001;
    static final int OVERVIEW_ID_ALERTS = 0x6f710002;
    static final int OVERVIEW_ID_HOURLY = 0x6f710003;
    static final int OVERVIEW_ID_DAILY = 0x6f710004;
    static final int OVERVIEW_ID_DETAILS = 0x6f710005;
    static final int OVERVIEW_ID_SOLAR = 0x6f710006;
    static final int OVERVIEW_ID_MOON = 0x6f710007;
    static final int OVERVIEW_ID_ATTRIBUTION = 0x6f710008;

    static final class OverviewTileSpec {
        final String id;
        final View view;

        OverviewTileSpec(String id, View view) {
            this.id = id;
            this.view = view;
        }
    }

    static final class OverviewStreamSpec {
        final String id;
        final View view;
        final boolean tile;

        OverviewStreamSpec(String id, View view, boolean tile) {
            this.id = id;
            this.view = view;
            this.tile = tile;
        }
    }

    static final class OverviewDragState {
        final String id;
        final View source;
        final boolean tile;
        View placeholder;
        ValueAnimator placeholderAnimator;
        int insertionIndex;
        boolean dragActive;
        boolean dropHandled;
        float latestPointerScreenX = Float.NaN;
        float latestPointerScreenY = Float.NaN;
        ScrollView autoScrollViewport;
        Runnable autoScrollRunnable;
        boolean autoScrollFrameScheduled;

        OverviewDragState(String id, View source, boolean tile, int insertionIndex) {
            this.id = id;
            this.source = source;
            this.tile = tile;
            this.insertionIndex = insertionIndex;
        }
    }

    @Override
    void installOverviewTopLevelBoard(LinearLayout staging) {
        if (staging == null || overviewPageContent == null) return;
        removePageGlassDrawables(overviewPageContent);
        overviewPageContent.removeAllViews();

        ArrayList<OverviewStreamSpec> available = new ArrayList<>();
        while (staging.getChildCount() > 0) {
            View child = staging.getChildAt(0);
            staging.removeViewAt(0);
            Object tag = child.getTag();
            String id = tag instanceof String ? ((String) tag).trim() : "";
            if (id.isEmpty()) id = "overview_block_" + available.size();
            boolean tile = isOverviewTileStreamId(id);
            available.add(new OverviewStreamSpec(id, child, tile));
        }
        if (available.isEmpty()) return;

        ArrayList<OverviewStreamSpec> stream = orderedOverviewStream(available);
        LinearLayout board = new LinearLayout(this);
        board.setOrientation(LinearLayout.VERTICAL);
        board.setClipChildren(false);
        board.setClipToPadding(false);

        for (OverviewStreamSpec spec : stream) {
            configureOverviewStreamReordering(board, stream, spec);
        }
        rebuildOverviewStreamBoard(board, stream, null);
        board.setOnDragListener((target, event) -> handleOverviewStreamDrag(board, stream, event));
        overviewPageContent.addView(board, new LinearLayout.LayoutParams(-1, -2));
    }

    String overviewTileStreamId(String tileId) {
        return OVERVIEW_TILE_STREAM_PREFIX + (tileId == null ? "" : tileId.trim());
    }

    boolean isOverviewTileStreamId(String id) {
        return id != null && id.startsWith(OVERVIEW_TILE_STREAM_PREFIX)
                && id.length() > OVERVIEW_TILE_STREAM_PREFIX.length();
    }

    String overviewTileIdFromStreamId(String id) {
        return isOverviewTileStreamId(id)
                ? id.substring(OVERVIEW_TILE_STREAM_PREFIX.length()) : "";
    }

    ArrayList<OverviewStreamSpec> orderedOverviewStream(ArrayList<OverviewStreamSpec> available) {
        ArrayList<OverviewStreamSpec> ordered = new ArrayList<>();
        ArrayList<String> saved = overviewStreamFullOrder();
        for (String id : saved) {
            OverviewStreamSpec spec = findOverviewStreamSpec(available, id);
            if (spec != null && findOverviewStreamSpec(ordered, id) == null) ordered.add(spec);
        }
        for (OverviewStreamSpec spec : available) {
            if (findOverviewStreamSpec(ordered, spec.id) == null) ordered.add(spec);
        }
        return ordered;
    }

    ArrayList<String> overviewSavedBlockOrderRaw() {
        ArrayList<String> order = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        String saved = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getString(PREF_OVERVIEW_BLOCK_ORDER, "");
        if (saved != null && !saved.trim().isEmpty()) {
            try {
                JSONArray array = new JSONArray(saved);
                for (int i = 0; i < array.length() && order.size() < 128; i++) {
                    String id = array.optString(i, "").trim();
                    if (!id.isEmpty() && seen.add(id)) order.add(id);
                }
            } catch (Exception ignored) { }
        }
        return order;
    }

    ArrayList<String> overviewLegacyBlockFullOrder() {
        ArrayList<String> order = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (String id : overviewSavedBlockOrderRaw()) {
            if (isOverviewTileStreamId(id)) continue;
            if (seen.add(id)) order.add(id);
        }
        for (String id : DEFAULT_OVERVIEW_BLOCK_ORDER) {
            if (seen.add(id)) order.add(id);
        }
        return order;
    }

    ArrayList<String> overviewStreamFullOrder() {
        ArrayList<String> raw = overviewSavedBlockOrderRaw();
        boolean hasCombinedOrder = false;
        for (String id : raw) {
            if (isOverviewTileStreamId(id)) {
                hasCombinedOrder = true;
                break;
            }
        }

        ArrayList<String> tileOrder = overviewTileFullOrder();
        ArrayList<String> order = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        if (!hasCombinedOrder) {
            boolean insertedTiles = false;
            for (String id : overviewLegacyBlockFullOrder()) {
                if ("details".equals(id)) {
                    for (String tileId : tileOrder) {
                        String streamId = overviewTileStreamId(tileId);
                        if (seen.add(streamId)) order.add(streamId);
                    }
                    insertedTiles = true;
                } else if (seen.add(id)) {
                    order.add(id);
                }
            }
            if (!insertedTiles) {
                for (String tileId : tileOrder) {
                    String streamId = overviewTileStreamId(tileId);
                    if (seen.add(streamId)) order.add(streamId);
                }
            }
            return order;
        }

        for (String id : raw) {
            if ("details".equals(id)) continue;
            if (seen.add(id)) order.add(id);
        }

        int tileInsert = -1;
        for (int i = 0; i < order.size(); i++) {
            if (isOverviewTileStreamId(order.get(i))) tileInsert = i + 1;
        }
        if (tileInsert < 0) {
            tileInsert = order.indexOf("solar");
            if (tileInsert < 0) tileInsert = order.size();
        }
        for (String tileId : tileOrder) {
            String streamId = overviewTileStreamId(tileId);
            if (seen.add(streamId)) order.add(tileInsert++, streamId);
        }
        for (String id : DEFAULT_OVERVIEW_BLOCK_ORDER) {
            if ("details".equals(id)) continue;
            if (seen.add(id)) order.add(id);
        }
        return order;
    }

    void persistOverviewStreamOrder(ArrayList<OverviewStreamSpec> visibleStream) {
        if (visibleStream == null || visibleStream.isEmpty()) return;
        ArrayList<String> fullOrder = overviewStreamFullOrder();
        HashSet<String> visibleIds = new HashSet<>();
        for (OverviewStreamSpec spec : visibleStream) visibleIds.add(spec.id);

        int nextVisible = 0;
        for (int i = 0; i < fullOrder.size() && nextVisible < visibleStream.size(); i++) {
            if (!visibleIds.contains(fullOrder.get(i))) continue;
            fullOrder.set(i, visibleStream.get(nextVisible++).id);
        }
        while (nextVisible < visibleStream.size()) {
            String id = visibleStream.get(nextVisible++).id;
            if (!fullOrder.contains(id)) fullOrder.add(id);
        }

        JSONArray serialized = new JSONArray();
        for (String id : fullOrder) serialized.put(id);
        getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_OVERVIEW_BLOCK_ORDER, serialized.toString())
                .apply();

        ArrayList<String> visibleTileIds = new ArrayList<>();
        for (OverviewStreamSpec spec : visibleStream) {
            if (spec.tile) visibleTileIds.add(overviewTileIdFromStreamId(spec.id));
        }
        persistOverviewTileIds(visibleTileIds);
    }

    void persistOverviewTileIds(ArrayList<String> visibleTileIds) {
        if (visibleTileIds == null || visibleTileIds.isEmpty()) return;
        ArrayList<String> fullOrder = overviewTileFullOrder();
        HashSet<String> visibleIds = new HashSet<>(visibleTileIds);
        int nextVisible = 0;
        for (int i = 0; i < fullOrder.size() && nextVisible < visibleTileIds.size(); i++) {
            if (!visibleIds.contains(fullOrder.get(i))) continue;
            fullOrder.set(i, visibleTileIds.get(nextVisible++));
        }
        while (nextVisible < visibleTileIds.size()) {
            String id = visibleTileIds.get(nextVisible++);
            if (!fullOrder.contains(id)) fullOrder.add(id);
        }
        JSONArray serialized = new JSONArray();
        for (String id : fullOrder) serialized.put(id);
        getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_OVERVIEW_TILE_ORDER, serialized.toString())
                .apply();
    }

    void configureOverviewStreamReordering(
            LinearLayout board,
            ArrayList<OverviewStreamSpec> stream,
            OverviewStreamSpec spec) {
        View source = spec.view;
        if (!spec.tile) source.setId(overviewBlockViewId(spec.id));
        source.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        CharSequence existingDescription = source.getContentDescription();
        String accessibleBase = existingDescription == null
                ? "" : existingDescription.toString().trim();
        if (accessibleBase.isEmpty() && source instanceof TextView) {
            CharSequence sourceText = ((TextView) source).getText();
            accessibleBase = sourceText == null ? "" : sourceText.toString().trim();
        }
        if (accessibleBase.isEmpty()) {
            accessibleBase = spec.tile ? "Information tile" : overviewBlockLabel(spec.id);
        }
        source.setContentDescription(accessibleBase);
        source.setTooltipText(spec.tile ? "Reorder information tile" : "Reorder section");
        source.setLongClickable(true);

        View.OnLongClickListener longClick = v -> startOverviewStreamDrag(board, stream, spec);
        source.setOnLongClickListener(longClick);
        if (!spec.tile) installOverviewLongPressOnDescendants(source, longClick);

        source.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(
                    View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                int index = indexOfOverviewStream(stream, spec.id);
                info.setLongClickable(true);
                info.setTooltipText(spec.tile ? "Reorder information tile" : "Reorder section");
                if (index > 0) {
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            spec.tile ? ACCESSIBILITY_MOVE_TILE_EARLIER
                                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                            spec.tile ? "Move earlier" : "Move section earlier"));
                }
                if (index >= 0 && index < stream.size() - 1) {
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            spec.tile ? ACCESSIBILITY_MOVE_TILE_LATER
                                    : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
                            spec.tile ? "Move later" : "Move section later"));
                }
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == (spec.tile ? ACCESSIBILITY_MOVE_TILE_EARLIER
                        : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                    return moveOverviewStreamByOffset(board, stream, spec.id, -1);
                }
                if (action == (spec.tile ? ACCESSIBILITY_MOVE_TILE_LATER
                        : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    return moveOverviewStreamByOffset(board, stream, spec.id, 1);
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });
    }

    void installOverviewLongPressOnDescendants(
            View root, View.OnLongClickListener listener) {
        if (!(root instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.isClickable() || child.isLongClickable()
                    || child instanceof HorizontalScrollView || child instanceof ScrollView) {
                child.setOnLongClickListener(listener);
            }
            if (child instanceof ViewGroup) {
                installOverviewLongPressOnDescendants(child, listener);
            }
        }
    }

    boolean startOverviewStreamDrag(
            LinearLayout board,
            ArrayList<OverviewStreamSpec> stream,
            OverviewStreamSpec spec) {
        int index = indexOfOverviewStream(stream, spec.id);
        if (index < 0 || spec.view.getParent() == null) return false;
        OverviewDragState state = new OverviewDragState(spec.id, spec.view, spec.tile, index);
        ClipData data = ClipData.newPlainText(
                spec.tile ? "overview_tile_id" : "overview_block_id", spec.id);
        boolean started = spec.view.startDragAndDrop(
                data, new View.DragShadowBuilder(spec.view), state, 0);
        if (!started) {
            state.dragActive = false;
            clearOverviewAutoScroll(state);
            removeOverviewPlaceholder(state);
            rebuildOverviewStreamBoard(board, stream, null);
            restoreOverviewStreamDragVisuals(stream);
        }
        return started;
    }

    boolean handleOverviewStreamDrag(
            LinearLayout board,
            ArrayList<OverviewStreamSpec> stream,
            DragEvent event) {
        Object local = event.getLocalState();
        if (!(local instanceof OverviewDragState)) return false;
        OverviewDragState state = (OverviewDragState) local;
        OverviewStreamSpec dragged = findOverviewStreamSpec(stream, state.id);
        if (dragged == null || dragged.view != state.source) {
            state.dragActive = false;
            clearOverviewAutoScroll(state);
            removeOverviewPlaceholder(state);
            rebuildOverviewStreamBoard(board, stream, null);
            restoreOverviewStreamDragVisuals(stream);
            return false;
        }

        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                state.dragActive = true;
                state.dropHandled = false;
                state.source.animate().cancel();
                state.source.setAlpha(0.38f);
                state.source.setScaleX(0.985f);
                state.source.setScaleY(0.985f);
                if (state.placeholder == null) {
                    state.placeholder = createOverviewPlaceholder(state.source, state.tile);
                    startOverviewPlaceholderAnimation(state);
                }
                rebuildOverviewStreamBoard(board, stream, state);
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                updateOverviewDragLocation(board, stream, state, event.getX(), event.getY());
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                // EXITED at a clipped edge is not termination. The self-rescheduling frame loop
                // continues from the last screen coordinate until scrolling reaches its limit.
                return true;
            case DragEvent.ACTION_DROP:
                updateOverviewDragLocation(board, stream, state, event.getX(), event.getY());
                state.dragActive = false;
                boolean changed = moveOverviewStreamToInsertion(
                        stream, state.id, state.insertionIndex);
                state.dropHandled = true;
                if (changed) persistOverviewStreamOrder(stream);
                clearOverviewAutoScroll(state);
                removeOverviewPlaceholder(state);
                rebuildOverviewStreamBoard(board, stream, null);
                restoreOverviewStreamDragVisuals(stream);
                if (changed) {
                    state.source.announceForAccessibility(
                            state.tile ? "Information tile moved"
                                    : overviewBlockLabel(state.id) + " moved");
                }
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                state.dragActive = false;
                clearOverviewAutoScroll(state);
                removeOverviewPlaceholder(state);
                if (!state.dropHandled) rebuildOverviewStreamBoard(board, stream, null);
                restoreOverviewStreamDragVisuals(stream);
                return true;
            default:
                return true;
        }
    }

    void updateOverviewDragLocation(
            LinearLayout board,
            ArrayList<OverviewStreamSpec> stream,
            OverviewDragState state,
            float boardX,
            float boardY) {
        if (state == null || !state.dragActive || board == null) return;
        int[] boardLocation = new int[2];
        board.getLocationOnScreen(boardLocation);
        state.latestPointerScreenX = boardLocation[0] + boardX;
        state.latestPointerScreenY = boardLocation[1] + boardY;
        int insertion = overviewInsertionIndexForPosition(
                board, stream, state.id, boardX, boardY);
        if (insertion != state.insertionIndex) {
            state.insertionIndex = insertion;
            rebuildOverviewStreamBoard(board, stream, state);
        }
        updateOverviewAutoScroll(board, stream, state);
    }

    int overviewInsertionIndexForPosition(
            LinearLayout board,
            ArrayList<OverviewStreamSpec> stream,
            String draggedId,
            float boardX,
            float boardY) {
        ArrayList<OverviewStreamSpec> remaining = new ArrayList<>();
        for (OverviewStreamSpec spec : stream) {
            if (!spec.id.equals(draggedId)) remaining.add(spec);
        }
        if (remaining.isEmpty()) return 0;

        int[] boardLocation = new int[2];
        board.getLocationOnScreen(boardLocation);
        int i = 0;
        while (i < remaining.size()) {
            OverviewStreamSpec first = remaining.get(i);
            ViewParent firstParent = first.view.getParent();
            int end = i + 1;
            if (first.tile && firstParent instanceof ViewGroup && firstParent != board) {
                while (end < remaining.size()) {
                    OverviewStreamSpec next = remaining.get(end);
                    if (!next.tile || next.view.getParent() != firstParent) break;
                    end++;
                }
            }

            int groupTop = Integer.MAX_VALUE;
            int groupBottom = Integer.MIN_VALUE;
            for (int j = i; j < end; j++) {
                int[] location = new int[2];
                View view = remaining.get(j).view;
                view.getLocationOnScreen(location);
                int top = location[1] - boardLocation[1];
                groupTop = Math.min(groupTop, top);
                groupBottom = Math.max(groupBottom, top + Math.max(1, view.getHeight()));
            }
            if (boardY < groupTop) return i;
            if (boardY <= groupBottom) {
                if (first.tile) {
                    for (int j = i; j < end; j++) {
                        View view = remaining.get(j).view;
                        int[] location = new int[2];
                        view.getLocationOnScreen(location);
                        float left = location[0] - boardLocation[0];
                        float midpoint = left + view.getWidth() / 2f;
                        if (boardX < midpoint) return j;
                    }
                    return end;
                }
                View view = first.view;
                int[] location = new int[2];
                view.getLocationOnScreen(location);
                float midpoint = location[1] - boardLocation[1] + view.getHeight() / 2f;
                return boardY < midpoint ? i : end;
            }

            if (end < remaining.size()) {
                int[] nextLocation = new int[2];
                remaining.get(end).view.getLocationOnScreen(nextLocation);
                float nextTop = nextLocation[1] - boardLocation[1];
                if (boardY < nextTop) return end;
            }
            i = end;
        }
        return remaining.size();
    }

    boolean moveOverviewStreamToInsertion(
            ArrayList<OverviewStreamSpec> stream, String draggedId, int insertionIndex) {
        int from = indexOfOverviewStream(stream, draggedId);
        if (from < 0) return false;
        OverviewStreamSpec moving = stream.remove(from);
        int insertion = Math.max(0, Math.min(insertionIndex, stream.size()));
        stream.add(insertion, moving);
        return insertion != from;
    }

    boolean moveOverviewStreamByOffset(
            LinearLayout board,
            ArrayList<OverviewStreamSpec> stream,
            String id,
            int offset) {
        int from = indexOfOverviewStream(stream, id);
        int to = from + offset;
        if (from < 0 || to < 0 || to >= stream.size()) return false;
        OverviewStreamSpec moving = stream.remove(from);
        stream.add(to, moving);
        persistOverviewStreamOrder(stream);
        rebuildOverviewStreamBoard(board, stream, null);
        moving.view.announceForAccessibility(moving.tile
                ? (offset < 0 ? "Information tile moved earlier" : "Information tile moved later")
                : overviewBlockLabel(id) + (offset < 0 ? " moved earlier" : " moved later"));
        return true;
    }

    void rebuildOverviewStreamBoard(
            LinearLayout board,
            ArrayList<OverviewStreamSpec> stream,
            OverviewDragState dragState) {
        if (board == null || stream == null) return;
        for (OverviewStreamSpec spec : stream) {
            ViewParent parent = spec.view.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(spec.view);
        }
        if (dragState != null && dragState.placeholder != null) {
            ViewParent parent = dragState.placeholder.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(dragState.placeholder);
        }
        board.removeAllViews();

        ArrayList<View> views = new ArrayList<>();
        ArrayList<Boolean> tiles = new ArrayList<>();
        if (dragState == null || dragState.placeholder == null) {
            for (OverviewStreamSpec spec : stream) {
                views.add(spec.view);
                tiles.add(spec.tile);
            }
        } else {
            ArrayList<OverviewStreamSpec> remaining = new ArrayList<>();
            for (OverviewStreamSpec spec : stream) {
                if (!dragState.id.equals(spec.id)) remaining.add(spec);
            }
            int insertion = Math.max(0, Math.min(dragState.insertionIndex, remaining.size()));
            for (int i = 0; i <= remaining.size(); i++) {
                if (i == insertion) {
                    views.add(dragState.placeholder);
                    tiles.add(dragState.tile);
                }
                if (i < remaining.size()) {
                    views.add(remaining.get(i).view);
                    tiles.add(remaining.get(i).tile);
                }
            }
        }

        int columns = overviewTileColumnCount(board);
        int index = 0;
        boolean previousWasTileRow = false;
        while (index < views.size()) {
            if (!tiles.get(index)) {
                View view = views.get(index);
                ViewGroup.LayoutParams existing = view.getLayoutParams();
                LinearLayout.LayoutParams lp = existing instanceof LinearLayout.LayoutParams
                        ? new LinearLayout.LayoutParams((LinearLayout.LayoutParams) existing)
                        : new LinearLayout.LayoutParams(-1, -2);
                if (lp.width == 0) lp.width = -1;
                board.addView(view, lp);
                previousWasTileRow = false;
                index++;
                continue;
            }

            int runEnd = index;
            while (runEnd < views.size() && tiles.get(runEnd)) runEnd++;
            for (int rowStart = index; rowStart < runEnd; rowStart += columns) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setBaselineAligned(false);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                int count = Math.min(columns, runEnd - rowStart);
                for (int column = 0; column < count; column++) {
                    int left = column == 0 ? 0 : dp(4);
                    int right = column == count - 1 ? 0 : dp(4);
                    addWeightedTile(row, views.get(rowStart + column), left, right);
                }
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
                rowLp.topMargin = previousWasTileRow ? dp(8) : dp(12);
                board.addView(row, rowLp);
                previousWasTileRow = true;
            }
            index = runEnd;
        }
        board.requestLayout();
        board.invalidate();
    }

    View createOverviewPlaceholder(View source, boolean tile) {
        FrameLayout placeholder = new FrameLayout(this);
        placeholder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        placeholder.setClickable(false);
        placeholder.setFocusable(false);
        GradientDrawable ghost = new GradientDrawable();
        ghost.setColor(Color.argb(78,
                Color.red(cardColor), Color.green(cardColor), Color.blue(cardColor)));
        ghost.setCornerRadius(dp(tile ? 20 : 22));
        ghost.setStroke(dp(2), Color.argb(190,
                Color.red(ACCENT_BLUE), Color.green(ACCENT_BLUE), Color.blue(ACCENT_BLUE)));
        placeholder.setBackground(ghost);
        if (!tile) {
            int height = Math.max(dp(48), Math.max(source.getHeight(), source.getMeasuredHeight()));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, height);
            ViewGroup.LayoutParams sourceParams = source.getLayoutParams();
            if (sourceParams instanceof LinearLayout.LayoutParams) {
                lp = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) sourceParams);
                lp.width = -1;
                lp.height = height;
            }
            placeholder.setLayoutParams(lp);
        }
        return placeholder;
    }

    void startOverviewPlaceholderAnimation(OverviewDragState state) {
        if (state == null || state.placeholder == null) return;
        ValueAnimator pulse = ValueAnimator.ofFloat(0.62f, 0.92f);
        pulse.setDuration(520L);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.addUpdateListener(animation -> {
            if (state.placeholder != null) {
                state.placeholder.setAlpha((Float) animation.getAnimatedValue());
            }
        });
        state.placeholderAnimator = pulse;
        pulse.start();
    }

    void removeOverviewPlaceholder(OverviewDragState state) {
        if (state == null) return;
        if (state.placeholderAnimator != null) {
            state.placeholderAnimator.cancel();
            state.placeholderAnimator = null;
        }
        if (state.placeholder != null) {
            ViewParent parent = state.placeholder.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(state.placeholder);
            state.placeholder = null;
        }
    }

    void restoreOverviewStreamDragVisuals(ArrayList<OverviewStreamSpec> stream) {
        if (stream == null) return;
        for (OverviewStreamSpec spec : stream) {
            spec.view.animate().cancel();
            spec.view.setAlpha(1f);
            spec.view.setScaleX(1f);
            spec.view.setScaleY(1f);
        }
    }

    void updateOverviewAutoScroll(
            LinearLayout board,
            ArrayList<OverviewStreamSpec> stream,
            OverviewDragState state) {
        if (state == null || !state.dragActive || board == null || !board.isAttachedToWindow()
                || Float.isNaN(state.latestPointerScreenY)) return;
        ScrollView viewport = overviewAutoScrollViewport(board);
        if (viewport == null) return;
        int direction = overviewAutoScrollDirection(viewport, state.latestPointerScreenY);
        if (direction == 0 || !overviewCanAutoScroll(viewport, direction)) return;
        if (state.autoScrollRunnable == null) {
            state.autoScrollRunnable = () -> runOverviewAutoScrollFrame(board, stream, state);
        }
        scheduleOverviewAutoScrollFrame(state, viewport);
    }

    void scheduleOverviewAutoScrollFrame(OverviewDragState state, ScrollView viewport) {
        if (state == null || viewport == null || state.autoScrollRunnable == null
                || state.autoScrollFrameScheduled || !state.dragActive) return;
        state.autoScrollViewport = viewport;
        state.autoScrollFrameScheduled = true;
        viewport.postOnAnimation(state.autoScrollRunnable);
    }

    void runOverviewAutoScrollFrame(
            LinearLayout board,
            ArrayList<OverviewStreamSpec> stream,
            OverviewDragState state) {
        if (state == null) return;
        state.autoScrollFrameScheduled = false;
        if (!state.dragActive || state.autoScrollRunnable == null || board == null
                || !board.isAttachedToWindow() || Float.isNaN(state.latestPointerScreenY)) return;

        ScrollView viewport = overviewAutoScrollViewport(board);
        if (viewport == null || !viewport.isAttachedToWindow()) return;
        state.autoScrollViewport = viewport;
        int direction = overviewAutoScrollDirection(viewport, state.latestPointerScreenY);
        if (direction == 0 || !overviewCanAutoScroll(viewport, direction)) return;

        float depth = overviewAutoScrollDepth(viewport, state.latestPointerScreenY, direction);
        int step = Math.max(1, Math.round(dp(18) * Math.max(0.18f, depth)));
        int before = Math.max(0, viewport.getScrollY());
        int maxScrollY = overviewMaxScrollY(viewport);
        int target = Math.max(0, Math.min(maxScrollY, before + direction * step));
        if (target != before) {
            viewport.scrollTo(viewport.getScrollX(), target);
        } else if (viewport.canScrollVertically(direction)) {
            viewport.scrollBy(0, direction * step);
        }

        int after = Math.max(0, viewport.getScrollY());
        if (after != before) {
            int[] boardLocation = new int[2];
            board.getLocationOnScreen(boardLocation);
            float clippedY = overviewClippedPointerScreenY(viewport, state.latestPointerScreenY);
            if (!Float.isNaN(clippedY)) {
                float boardX = Float.isNaN(state.latestPointerScreenX)
                        ? board.getWidth() / 2f : state.latestPointerScreenX - boardLocation[0];
                float boardY = clippedY - boardLocation[1];
                int insertion = overviewInsertionIndexForPosition(
                        board, stream, state.id, boardX, boardY);
                if (insertion != state.insertionIndex) {
                    state.insertionIndex = insertion;
                    rebuildOverviewStreamBoard(board, stream, state);
                }
            }
        }

        ScrollView liveViewport = overviewAutoScrollViewport(board);
        if (state.dragActive && liveViewport != null) {
            int liveDirection = overviewAutoScrollDirection(
                    liveViewport, state.latestPointerScreenY);
            if (liveDirection != 0 && overviewCanAutoScroll(liveViewport, liveDirection)) {
                scheduleOverviewAutoScrollFrame(state, liveViewport);
            }
        }
    }

    ScrollView overviewAutoScrollViewport(LinearLayout board) {
        ViewParent parent = board == null ? null : board.getParent();
        while (parent instanceof View) {
            View candidate = (View) parent;
            if (candidate instanceof ScrollView && overviewViewportIsVisible(candidate)) {
                return (ScrollView) candidate;
            }
            parent = candidate.getParent();
        }
        if (mainScroll instanceof ScrollView && overviewViewportIsVisible(mainScroll)) {
            return (ScrollView) mainScroll;
        }
        return null;
    }

    boolean overviewViewportIsVisible(View viewport) {
        if (viewport == null || !viewport.isShown() || !viewport.isAttachedToWindow()) return false;
        Rect visible = new Rect();
        return viewport.getGlobalVisibleRect(visible) && visible.height() > 0;
    }

    boolean overviewCanAutoScroll(ScrollView viewport, int direction) {
        if (viewport == null || direction == 0) return false;
        int scrollY = Math.max(0, viewport.getScrollY());
        int maxScrollY = overviewMaxScrollY(viewport);
        boolean platformCanScroll = viewport.canScrollVertically(direction);
        if (direction < 0) return platformCanScroll || scrollY > 0;
        return platformCanScroll || scrollY < maxScrollY;
    }

    int overviewMaxScrollY(ScrollView viewport) {
        if (viewport == null || viewport.getChildCount() == 0) return 0;
        View child = viewport.getChildAt(0);
        int bottomMargin = 0;
        ViewGroup.LayoutParams params = child.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            bottomMargin = ((ViewGroup.MarginLayoutParams) params).bottomMargin;
        }
        int childBottom = Math.max(child.getBottom(),
                child.getTop() + Math.max(child.getHeight(), child.getMeasuredHeight()));
        int visibleBottom = Math.max(viewport.getPaddingTop(),
                viewport.getHeight() - viewport.getPaddingBottom());
        int laidOutRange = Math.max(0, childBottom + bottomMargin - visibleBottom);
        return Math.max(Math.max(0, viewport.getScrollY()), laidOutRange);
    }

    boolean overviewAutoScrollBoundsOnScreen(View viewport, Rect outBounds) {
        if (viewport == null || outBounds == null || !viewport.getGlobalVisibleRect(outBounds)
                || outBounds.height() <= 0) return false;
        int[] viewportLocation = new int[2];
        viewport.getLocationOnScreen(viewportLocation);
        int contentTop = viewportLocation[1] + viewport.getPaddingTop();
        int contentBottom = viewportLocation[1] + viewport.getHeight()
                - viewport.getPaddingBottom();
        outBounds.top = Math.max(outBounds.top, contentTop);
        outBounds.bottom = Math.min(outBounds.bottom, contentBottom);
        return outBounds.bottom > outBounds.top;
    }

    float overviewClippedPointerScreenY(View viewport, float pointerScreenY) {
        Rect bounds = new Rect();
        if (!overviewAutoScrollBoundsOnScreen(viewport, bounds) || Float.isNaN(pointerScreenY)) {
            return Float.NaN;
        }
        return Math.max(bounds.top, Math.min(pointerScreenY, bounds.bottom));
    }

    int overviewAutoScrollDirection(View viewport, float pointerScreenY) {
        if (Float.isNaN(pointerScreenY)) return 0;
        Rect bounds = new Rect();
        if (!overviewAutoScrollBoundsOnScreen(viewport, bounds)) return 0;
        float clippedY = Math.max(bounds.top, Math.min(pointerScreenY, bounds.bottom));
        int band = Math.min(dp(120), Math.max(1, bounds.height() / 2));
        float topDepth = Math.max(0f, Math.min(1f,
                (bounds.top + band - clippedY) / band));
        float bottomDepth = Math.max(0f, Math.min(1f,
                (clippedY - (bounds.bottom - band)) / band));
        if (topDepth <= 0f && bottomDepth <= 0f) return 0;
        return topDepth >= bottomDepth ? -1 : 1;
    }

    float overviewAutoScrollDepth(View viewport, float pointerScreenY, int direction) {
        if (Float.isNaN(pointerScreenY)) return 0f;
        Rect bounds = new Rect();
        if (!overviewAutoScrollBoundsOnScreen(viewport, bounds)) return 0f;
        float clippedY = Math.max(bounds.top, Math.min(pointerScreenY, bounds.bottom));
        int band = Math.min(dp(120), Math.max(1, bounds.height() / 2));
        float depth = direction < 0
                ? (bounds.top + band - clippedY) / band
                : (clippedY - (bounds.bottom - band)) / band;
        return Math.max(0f, Math.min(1f, depth));
    }

    void clearOverviewAutoScroll(OverviewDragState state) {
        if (state == null) return;
        if (state.autoScrollViewport != null && state.autoScrollRunnable != null) {
            state.autoScrollViewport.removeCallbacks(state.autoScrollRunnable);
        }
        state.autoScrollFrameScheduled = false;
        state.autoScrollRunnable = null;
        state.autoScrollViewport = null;
        state.latestPointerScreenX = Float.NaN;
        state.latestPointerScreenY = Float.NaN;
    }

    OverviewStreamSpec findOverviewStreamSpec(ArrayList<OverviewStreamSpec> stream, String id) {
        if (stream == null || id == null) return null;
        for (OverviewStreamSpec spec : stream) {
            if (id.equals(spec.id)) return spec;
        }
        return null;
    }

    int indexOfOverviewStream(ArrayList<OverviewStreamSpec> stream, String id) {
        if (stream == null || id == null) return -1;
        for (int i = 0; i < stream.size(); i++) {
            if (id.equals(stream.get(i).id)) return i;
        }
        return -1;
    }

    int overviewBlockViewId(String id) {
        if ("hero".equals(id)) return OVERVIEW_ID_HERO;
        if ("alerts".equals(id)) return OVERVIEW_ID_ALERTS;
        if ("hourly".equals(id)) return OVERVIEW_ID_HOURLY;
        if ("daily".equals(id)) return OVERVIEW_ID_DAILY;
        if ("details".equals(id)) return OVERVIEW_ID_DETAILS;
        if ("solar".equals(id)) return OVERVIEW_ID_SOLAR;
        if ("moon".equals(id)) return OVERVIEW_ID_MOON;
        if ("attribution".equals(id)) return OVERVIEW_ID_ATTRIBUTION;
        int hash = id == null ? 1 : id.hashCode();
        return 0x6f720000 | (hash & 0xffff);
    }

    String overviewBlockLabel(String id) {
        if ("hero".equals(id)) return "Current weather";
        if ("alerts".equals(id)) return "Severe weather alert";
        if ("hourly".equals(id)) return "Hourly forecast";
        if ("daily".equals(id)) return "Multi-day forecast";
        if ("details".equals(id)) return "Weather information";
        if ("solar".equals(id)) return "Next solar event";
        if ("moon".equals(id)) return "Moon";
        if ("attribution".equals(id)) return "Attribution";
        return "Overview section";
    }

    void render(JSONObject current, JSONObject hourly, JSONObject daily, String responseUnit) {
        activeTemperatureUnit = normalizeTemperatureUnit(responseUnit);
        lastCurrentWeather = current;
        lastHourlyWeather = hourly;
        lastDailyWeather = daily;
        progress.setVisibility(View.GONE);
        status.setText(dataAgeLabel(current));

        displayedDaytime = safeBoolean(current, "isDaytime", true);
        SceneSpec currentScene = SceneSpec.fromWeather(current, displayedDaytime);
        forecastPreview.setCurrentScene(currentScene);
        if (skyLayout != null) skyLayout.setScene(currentScene);
        if (headerGlass != null) headerGlass.setScene(currentScene, animationsAllowed());
        applyScenePalette(currentScene.paletteScene());
        displayedScene = settingsSceneKey(current, displayedDaytime);
        persistSettingsSceneSnapshot();

        ZoneId zone = responseZone(current, hourly, daily);
        JSONArray days = daily == null ? null : daily.optJSONArray("forecastDays");
        JSONObject today = firstObject(days);
        Integer snapshotTemp = degreesOrNull(current == null ? null : current.optJSONObject("temperature"));
        CityManagerActivity.updateSelectedLocationSnapshot(
                this,
                snapshotTemp == null ? "" : snapshotTemp + temperatureUnitSymbol(),
                description(current));
        persistWidgetSnapshot(current, today, hourly, zone);

        renderCurrentMode();
        if (headerGlass != null) headerGlass.requestBlurRefresh();
    }


    void addHero(JSONObject current, JSONObject today) {
        String condition = description(current);
        Integer temp = degreesOrNull(current == null ? null : current.optJSONObject("temperature"));
        Integer min = degreesOrNull(today == null ? null : today.optJSONObject("minTemperature"));
        Integer max = degreesOrNull(today == null ? null : today.optJSONObject("maxTemperature"));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(4), dp(18), dp(4), dp(62));

        TextView temperature = text(temp == null ? "—°" : temp + "°", 100, false, WHITE);
        temperature.setContentDescription(temp == null
                ? "Temperature unavailable"
                : temp + " degrees " + temperatureUnitWord());
        temperature.setGravity(Gravity.CENTER);
        temperature.setIncludeFontPadding(false);
        temperature.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        temperature.setLetterSpacing(-0.025f);
        hero.addView(temperature);

        String range = (min == null || max == null) ? "" : "  " + min + "° / " + max + "°";
        String summary = condition + range;
        TextView subtitle = text(summary, 18, false, WHITE);
        StringBuilder heroSummaryDescription = new StringBuilder(condition);
        if (min != null) {
            heroSummaryDescription.append(", low ").append(min)
                    .append(" degrees ").append(temperatureUnitWord());
        }
        if (max != null) {
            heroSummaryDescription.append(", high ").append(max)
                    .append(" degrees ").append(temperatureUnitWord());
        }
        subtitle.setContentDescription(heroSummaryDescription.toString());
        subtitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setMaxLines(3);
        subtitle.setLineSpacing(0, 1.06f);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(-1, -2);
        subtitleLp.topMargin = dp(8);
        hero.addView(subtitle, subtitleLp);

        pageContent().addView(hero);
    }

    void addWeatherAlertsCard() {
        if (!severeAlertsEnabled()) return;
        OptionalDataState state;
        synchronized (optionalDataLock) {
            state = severeAlertsState;
        }
        String language = Locale.getDefault().toLanguageTag();
        if (state == null || !state.matches(
                weatherRequestGeneration, latitude, longitude, language)
                || state.loading || !state.available || state.details == null) return;
        JSONArray alerts = state.details.optJSONArray("weatherAlerts");
        if (alerts == null || alerts.length() == 0) return;
        JSONObject alert = alerts.optJSONObject(0);
        if (alert == null) return;

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(15), dp(18), dp(15));
        TextView eyebrow = text("OFFICIAL WEATHER ALERT", 11, true, Color.rgb(255, 220, 150));
        body.addView(eyebrow);
        String titleValue = alertTitle(alert);
        if (titleValue.isEmpty()) titleValue = prettyEnum(alert.optString("eventType", "Weather alert"));
        TextView title = text(titleValue, 19, true, WHITE);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(4);
        body.addView(title, titleLp);
        String severity = prettyEnum(alert.optString("severity", ""));
        String area = alert.optString("areaName", "").trim();
        String summary = firstNonEmpty(severity, area);
        if (!severity.isEmpty() && !area.isEmpty()) summary = severity + " · " + area;
        if (alerts.length() > 1) summary += " · " + alerts.length() + " active alerts";
        TextView subtitle = text(summary, 12, false, SOFT_WHITE);
        subtitle.setMaxLines(3);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(-1, -2);
        subtitleLp.topMargin = dp(5);
        body.addView(subtitle, subtitleLp);
        JSONObject source = alert.optJSONObject("dataSource");
        String sourceName = source == null ? "" : source.optString("name", "").trim();
        String sourceUrl = source == null ? "" : source.optString("authorityUri", "").trim();
        if (!sourceName.isEmpty()) {
            TextView sourceLink = text("Source: " + sourceName + (sourceUrl.isEmpty() ? "" : " ↗"),
                    11, true, ACCENT_BLUE);
            sourceLink.setPadding(0, dp(7), 0, 0);
            if (!sourceUrl.isEmpty()) {
                sourceLink.setClickable(true);
                sourceLink.setFocusable(true);
                sourceLink.setOnClickListener(v -> openExternalUrl(sourceUrl));
                sourceLink.setContentDescription("Open alert source " + sourceName);
            }
            body.addView(sourceLink);
        }
        body.setClickable(true);
        body.setFocusable(true);
        body.setContentDescription(titleValue + ", " + summary + ". Tap for official details.");
        body.setOnClickListener(v -> showWeatherAlertsDialog(alerts));
        View alertCard = card(body, dp(24), cardColor);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        pageContent().addView(alertCard, lp);
    }

    void showWeatherAlertsDialog(JSONArray alerts) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(20), dp(20), dp(18));
        panel.setBackground(roundedBg(Color.rgb(15, 48, 81), dp(24)));
        scroller.addView(panel, new ScrollView.LayoutParams(-1, -2));
        panel.addView(text("Official weather alerts", 23, true, WHITE));
        TextView intro = text("Warnings for " + locationName + ". Follow the issuing authority's instructions.",
                12, false, SOFT_WHITE);
        intro.setPadding(0, dp(5), 0, dp(8));
        panel.addView(intro);
        for (int i = 0; i < alerts.length(); i++) {
            JSONObject alert = alerts.optJSONObject(i);
            if (alert == null) continue;
            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(dp(14), dp(13), dp(14), dp(13));
            block.setBackground(roundedBg(Color.argb(42, 255, 255, 255), dp(16)));
            String heading = alertTitle(alert);
            if (heading.isEmpty()) heading = prettyEnum(alert.optString("eventType", "Weather alert"));
            block.addView(text(heading, 17, true, WHITE));
            String meta = prettyEnum(alert.optString("severity", ""));
            String certainty = prettyEnum(alert.optString("certainty", ""));
            String urgency = prettyEnum(alert.optString("urgency", ""));
            if (!certainty.isEmpty()) meta += (meta.isEmpty() ? "" : " · ") + certainty;
            if (!urgency.isEmpty()) meta += (meta.isEmpty() ? "" : " · ") + urgency;
            String area = alert.optString("areaName", "").trim();
            if (!area.isEmpty()) meta += (meta.isEmpty() ? "" : "\n") + area;
            String expiry = formatAlertTime(alert.optString("expirationTime", ""));
            if (!expiry.isEmpty()) meta += (meta.isEmpty() ? "" : "\n") + "Until " + expiry;
            TextView metaView = text(meta, 12, false, Color.rgb(255, 220, 150));
            metaView.setPadding(0, dp(4), 0, 0);
            block.addView(metaView);
            String description = alert.optString("description", "").trim();
            if (!description.isEmpty()) {
                TextView descriptionView = text(description, 13, false, SOFT_WHITE);
                descriptionView.setPadding(0, dp(9), 0, 0);
                block.addView(descriptionView);
            }
            JSONArray instructions = alert.optJSONArray("instruction");
            if (instructions != null) {
                for (int j = 0; j < instructions.length(); j++) {
                    String instruction = instructions.optString(j, "").trim();
                    if (!instruction.isEmpty()) {
                        TextView item = text("• " + instruction, 13, false, WHITE);
                        item.setPadding(0, dp(8), 0, 0);
                        block.addView(item);
                    }
                }
            }
            JSONArray safety = alert.optJSONArray("safetyRecommendations");
            if (safety != null) {
                for (int j = 0; j < safety.length(); j++) {
                    JSONObject recommendation = safety.optJSONObject(j);
                    if (recommendation == null) continue;
                    String directive = recommendation.optString("directive", "").trim();
                    String subtext = recommendation.optString("subtext", "").trim();
                    String combined = directive + (subtext.isEmpty() ? "" : "\n" + subtext);
                    if (!combined.trim().isEmpty()) {
                        TextView item = text("• " + combined, 13, false, WHITE);
                        item.setPadding(0, dp(8), 0, 0);
                        block.addView(item);
                    }
                }
            }
            JSONObject source = alert.optJSONObject("dataSource");
            String sourceName = source == null ? "" : source.optString("name", "").trim();
            String sourceUrl = source == null ? "" : source.optString("authorityUri", "").trim();
            if (!sourceName.isEmpty()) {
                Button sourceButton = button("Source: " + sourceName + (sourceUrl.isEmpty() ? "" : " ↗"));
                sourceButton.setEnabled(!sourceUrl.isEmpty());
                if (!sourceUrl.isEmpty()) sourceButton.setOnClickListener(v -> openExternalUrl(sourceUrl));
                LinearLayout.LayoutParams sourceLp = new LinearLayout.LayoutParams(-1, dp(44));
                sourceLp.topMargin = dp(10);
                block.addView(sourceButton, sourceLp);
            }
            LinearLayout.LayoutParams blockLp = new LinearLayout.LayoutParams(-1, -2);
            blockLp.topMargin = dp(10);
            panel.addView(block, blockLp);
        }
        Button close = button("Close");
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(-1, dp(46));
        closeLp.topMargin = dp(12);
        panel.addView(close, closeLp);
        dialog.setContentView(scroller);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.76f;
            window.setAttributes(attributes);
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(30), dp(460)),
                    Math.min(getResources().getDisplayMetrics().heightPixels - dp(70), dp(720)));
        }
    }

    String formatAlertTime(String value) {
        Instant instant = parseInstant(value);
        if (instant == null) return "";
        try {
            ZoneId zone = responseZone(lastCurrentWeather, lastHourlyWeather, lastDailyWeather);
            return DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())
                    .format(instant.atZone(zone));
        } catch (Exception ignored) {
            return value;
        }
    }

    static String stringValue(JSONObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.isNull(key)) return "";
        String value = object.optString(key, "").trim();
        return "null".equalsIgnoreCase(value) ? "" : value;
    }

    static String firstNonEmpty(String first, String second) {
        if (first != null && !first.trim().isEmpty()) return first.trim();
        return second == null ? "" : second.trim();
    }

    void addHourlyCard(JSONObject hourly, ZoneId zone) {
        JSONArray hours = hourly == null ? null : hourly.optJSONArray("forecastHours");
        String diagnostic = hourlyDiagnostic(hourly);

        LinearLayout cardBody = new LinearLayout(this);
        cardBody.setOrientation(LinearLayout.VERTICAL);

        GestureHorizontalScrollView scroller = new GestureHorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        scroller.setFillViewport(true);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(5), dp(12), dp(5), dp(12));

        if (hours != null && hours.length() > 0) {
            for (int i = 0; i < Math.min(TOP_HOURLY_STRIP_HOURS, hours.length()); i++) {
                JSONObject hour = hours.optJSONObject(i);
                if (hour == null) continue;

                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(dp(5), dp(4), dp(5), dp(4));

                TextView when = text(i == 0 ? "Now" : hourLabel(hour, zone), 13, false, SOFT_WHITE);
                when.setGravity(Gravity.CENTER);
                cell.addView(when, new LinearLayout.LayoutParams(-1, dp(24)));

                WeatherGlyphView icon = new WeatherGlyphView(
                        this, description(hour), safeBoolean(hour, "isDaytime", true));
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(42), dp(42));
                iconLp.topMargin = dp(5);
                cell.addView(icon, iconLp);

                Integer temperature = degreesOrNull(hour.optJSONObject("temperature"));
                TextView t = text(temperature == null ? "—°" : temperature + "°", 19, true, WHITE);
                t.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-1, -2);
                tLp.topMargin = dp(4);
                cell.addView(t, tLp);

                int p = probability(hour);
                TextView precip = text(p > 0 ? p + "%" : " ", 11, false, ACCENT_BLUE);
                precip.setGravity(Gravity.CENTER);
                cell.addView(precip);
                StringBuilder hourlyDescription = new StringBuilder(
                        i == 0 ? "Now" : hourLabel(hour, zone));
                if (temperature != null) {
                    hourlyDescription.append(", ").append(temperature)
                            .append(" degrees ").append(temperatureUnitWord());
                }
                if (p > 0) hourlyDescription.append(", ").append(p).append(" percent precipitation");
                configureHourPreviewCell(
                        cell,
                        hour,
                        zone,
                        i == 0,
                        hourlyDescription.toString());

                row.addView(cell, new LinearLayout.LayoutParams(dp(74), -2));
            }
        } else {
            LinearLayout fallback = new LinearLayout(this);
            fallback.setOrientation(LinearLayout.HORIZONTAL);
            fallback.setGravity(Gravity.CENTER_VERTICAL);
            fallback.setPadding(dp(12), dp(8), dp(8), dp(8));

            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView empty = text("Hourly unavailable", 15, false, SOFT_WHITE);
            labels.addView(empty);
            String reason = diagnostic.isEmpty()
                    ? "No forecastHours array returned"
                    : diagnostic;
            TextView detail = text(reason, 10, false, FAINT_WHITE);
            detail.setMaxLines(3);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            detail.setPadding(0, dp(2), dp(8), 0);
            labels.addView(detail);
            fallback.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

            Button retry = button("Retry");
            retry.setContentDescription("Retry hourly forecast");
            retry.setOnClickListener(v -> retryHourlyPageOne());
            fallback.addView(retry, new LinearLayout.LayoutParams(dp(78), dp(44)));

            int fallbackWidth = Math.max(
                    dp(292), getResources().getDisplayMetrics().widthPixels - dp(58));
            row.addView(fallback, new LinearLayout.LayoutParams(fallbackWidth, -2));
        }

        scroller.addView(row);
        cardBody.addView(scroller, new LinearLayout.LayoutParams(-1, -2));

        if (hours != null && hours.length() > 0
                && hourly != null
                && hourly.optBoolean(HOURLY_LOAD_ERROR, false)
                && !diagnostic.isEmpty()) {
            TextView partial = text("Hourly data is partial · " + diagnostic, 10, false, FAINT_WHITE);
            partial.setPadding(dp(14), 0, dp(14), dp(10));
            partial.setMaxLines(3);
            partial.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cardBody.addView(partial);
        }

        LinearLayout.LayoutParams cardLp = defaultCardParams();
        cardLp.topMargin = dp(8);
        View card = card(cardBody, dp(24), cardColor);
        card.setLayoutParams(cardLp);
        pageContent().addView(card);
    }

    static String hourlyDiagnostic(JSONObject hourly) {
        if (hourly == null) return "";
        return boundedDiagnostic(hourly.optString(HOURLY_DIAGNOSTIC, ""));
    }

    interface DaySelectionListener {
        void onDaySelected(int dayIndex);
    }

    void addMultiDayCard(JSONArray days, JSONObject hourly, ZoneId zone) {
        JSONArray hours = hourly == null ? null : hourly.optJSONArray("forecastHours");

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(14), dp(14), dp(14));

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Multi-day forecast", 15, false, SOFT_WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout segmented = new LinearLayout(this);
        segmented.setOrientation(LinearLayout.HORIZONTAL);
        segmented.setPadding(dp(2), dp(2), dp(2), dp(2));
        segmented.setBackground(roundedBg(Color.argb(30, 255, 255, 255), dp(22)));
        segmented.setContentDescription("Forecast display mode");

        TextView lineButton = dailyModeButton("Line", "Line forecast");
        segmented.addView(lineButton, new LinearLayout.LayoutParams(dp(72), dp(48)));

        TextView listButton = dailyModeButton("List", "List forecast");
        segmented.addView(listButton, new LinearLayout.LayoutParams(dp(72), dp(48)));
        heading.addView(segmented);
        body.addView(heading);

        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(22, 255, 255, 255));
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(-1, 1);
        dividerLp.topMargin = dp(8);
        body.addView(divider, dividerLp);

        int dayCount = days == null ? 0 : Math.min(10, days.length());
        int viewportWidth = Math.max(dp(280), getResources().getDisplayMetrics().widthPixels - dp(64));
        int chartWidth = Math.max(viewportWidth, dp(86) * Math.max(1, dayCount));
        int chartHeight = dp(330);

        LinearLayout lineDetailHost = new LinearLayout(this);
        lineDetailHost.setOrientation(LinearLayout.VERTICAL);
        lineDetailHost.setVisibility(View.GONE);

        DayDetailCoordinator details = new DayDetailCoordinator(
                days, hours, zone, hourlyDiagnostic(hourly), lineDetailHost);
        details.expandedDay = Math.min(expandedDayIndex, dayCount - 1);
        ForecastChartView chart = new ForecastChartView(
                this, days, zone, chartWidth, chartHeight, details::toggleDay);
        chart.setLayoutParams(new FrameLayout.LayoutParams(chartWidth, chartHeight));
        forecastPreview.registerChart(chart);

        GestureHorizontalScrollView chartScroller = new GestureHorizontalScrollView(this);
        chartScroller.setHorizontalScrollBarEnabled(false);
        chartScroller.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        chartScroller.setFillViewport(true);
        chartScroller.setClipToPadding(false);
        chartScroller.setMinimumHeight(chartHeight);
        chartScroller.setContentDescription("Scrollable 10-day line forecast in "
                + temperatureUnitWord() + ". Select a day for hourly details and scene preview.");
        chartScroller.addView(chart, new FrameLayout.LayoutParams(chartWidth, chartHeight));

        GestureVerticalScrollView list = buildTenDayList(days, zone, details);
        FrameLayout modeHost = new FrameLayout(this);
        FrameLayout.LayoutParams chartHostLp = new FrameLayout.LayoutParams(-1, chartHeight);
        chartHostLp.topMargin = dp(4);
        modeHost.addView(chartScroller, chartHostLp);
        FrameLayout.LayoutParams listHostLp = new FrameLayout.LayoutParams(-1, chartHeight);
        listHostLp.topMargin = dp(4);
        modeHost.addView(list, listHostLp);
        body.addView(modeHost, new LinearLayout.LayoutParams(-1, chartHeight + dp(4)));

        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = dp(8);
        body.addView(lineDetailHost, detailLp);

        String savedMode = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getString(PREF_DAILY_MODE, "line");
        boolean listMode = "list".equals(savedMode);
        applyDailyMode(lineButton, listButton, chartScroller, list, listMode);
        details.setListMode(listMode);
        if (details.expandedDay >= 0) {
            LocalDate selectedDate = displayDate(days.optJSONObject(details.expandedDay), zone);
            ensureHourlyCoverage(selectedDate, details);
        }

        lineButton.setOnClickListener(v -> switchDailyMode(
                lineButton, listButton, chartScroller, list, details, false));
        listButton.setOnClickListener(v -> switchDailyMode(
                lineButton, listButton, chartScroller, list, details, true));

        LinearLayout.LayoutParams cardLp = defaultCardParams();
        cardLp.topMargin = dp(12);
        View card = card(body, dp(24), cardColor);
        card.setLayoutParams(cardLp);
        pageContent().addView(card);
    }

    TextView dailyModeButton(String label, String accessibilityLabel) {
        TextView button = text(label, 12, true, WHITE);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setMinWidth(dp(72));
        button.setMinHeight(dp(48));
        button.setContentDescription(accessibilityLabel);
        return button;
    }

    void switchDailyMode(
            TextView lineButton,
            TextView listButton,
            GestureHorizontalScrollView chartScroller,
            View list,
            DayDetailCoordinator details,
            boolean listMode) {
        int pageScrollY = mainScroll == null ? 0 : mainScroll.getScrollY();
        int chartScrollX = chartScroller.getScrollX();
        getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_DAILY_MODE, listMode ? "list" : "line")
                .apply();
        applyDailyMode(lineButton, listButton, chartScroller, list, listMode);
        details.setListMode(listMode);
        chartScroller.post(() -> chartScroller.scrollTo(chartScrollX, 0));
        if (mainScroll != null) {
            mainScroll.post(() -> mainScroll.scrollTo(0, pageScrollY));
        }
    }

    void applyDailyMode(
            TextView lineButton,
            TextView listButton,
            View chart,
            View list,
            boolean listMode) {
        // INVISIBLE (not GONE) keeps the custom chart measured while List mode is active.
        // That preserves the API-36 line-chart measurement fix and its horizontal scroll position.
        chart.setVisibility(listMode ? View.INVISIBLE : View.VISIBLE);
        list.setVisibility(listMode ? View.VISIBLE : View.GONE);
        updateDailyModeButton(lineButton, !listMode, "Line forecast");
        updateDailyModeButton(listButton, listMode, "List forecast");
    }

    void updateDailyModeButton(TextView button, boolean selected, String label) {
        button.setSelected(selected);
        button.setAlpha(selected ? 1f : 0.76f);
        button.setTextColor(selected ? WHITE : Color.argb(210, 255, 255, 255));
        button.setBackground(segmentedButtonBackground(selected));
        button.setContentDescription(label + (selected ? ", selected" : ", not selected"));
        if (Build.VERSION.SDK_INT >= 30) {
            button.setStateDescription(selected ? "Selected" : "Not selected");
        }
    }

    StateListDrawable segmentedButtonBackground(boolean selected) {
        StateListDrawable states = new StateListDrawable();
        int pressed = selected
                ? Color.argb(92, 255, 255, 255)
                : Color.argb(34, 255, 255, 255);
        int normal = selected
                ? Color.argb(66, 255, 255, 255)
                : Color.TRANSPARENT;
        states.addState(new int[]{android.R.attr.state_pressed}, roundedBg(pressed, dp(19)));
        states.addState(new int[]{android.R.attr.state_focused}, roundedBg(pressed, dp(19)));
        states.addState(new int[]{}, roundedBg(normal, dp(19)));
        return states;
    }

    GestureVerticalScrollView buildTenDayList(
            JSONArray days,
            ZoneId zone,
            DayDetailCoordinator details) {
        GestureVerticalScrollView scroller = new GestureVerticalScrollView(this);
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(true);
        scroller.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        scroller.setClipToPadding(false);
        scroller.setPadding(0, dp(3), 0, dp(3));
        scroller.setContentDescription("Scrollable 10-day forecast list");

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(list, new ScrollView.LayoutParams(-1, -2));

        if (days == null || days.length() == 0) {
            TextView empty = text("Daily forecast unavailable", 14, false, SOFT_WHITE);
            empty.setPadding(dp(4), dp(18), dp(4), dp(18));
            list.addView(empty);
            return scroller;
        }

        int count = Math.min(10, days.length());
        for (int i = 0; i < count; i++) {
            JSONObject day = days.optJSONObject(i);
            if (day == null) continue;
            final int dayIndex = i;

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(64));
            row.setPadding(dp(6), dp(8), dp(4), dp(8));
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackground(previewTargetBackground(dp(16)));

            String dayText = i == 0 ? "Today" : dayLabel(day, zone);
            TextView label = text(dayText, 14, i == 0, WHITE);
            TextView date = text(dayDateLabel(day, zone), 10, false, FAINT_WHITE);
            LinearLayout dayLabels = new LinearLayout(this);
            dayLabels.setOrientation(LinearLayout.VERTICAL);
            dayLabels.addView(label);
            dayLabels.addView(date);
            row.addView(dayLabels, new LinearLayout.LayoutParams(0, -2, 1));

            JSONObject daytime = day.optJSONObject("daytimeForecast");
            WeatherGlyphView glyph = new WeatherGlyphView(this, description(daytime), true);
            row.addView(glyph, new LinearLayout.LayoutParams(dp(34), dp(34)));

            int p = dayProbability(day);
            TextView rain = text(p >= 0 ? p + "%" : "—", 12, false, ACCENT_BLUE);
            rain.setGravity(Gravity.END);
            row.addView(rain, new LinearLayout.LayoutParams(dp(48), -2));

            Integer hi = degreesOrNull(day.optJSONObject("maxTemperature"));
            Integer lo = degreesOrNull(day.optJSONObject("minTemperature"));
            String temperatures = (hi == null ? "—" : hi) + "°  " + (lo == null ? "—" : lo) + "°";
            TextView temps = text(temperatures, 14, true, WHITE);
            temps.setGravity(Gravity.END);
            row.addView(temps, new LinearLayout.LayoutParams(dp(88), -2));

            TextView disclosure = text("›", 24, false, SOFT_WHITE);
            disclosure.setGravity(Gravity.CENTER);
            row.addView(disclosure, new LinearLayout.LayoutParams(dp(24), dp(40)));

            StringBuilder rowDescription = new StringBuilder(dayText);
            String condition = sceneForForecastDay(day, i).condition;
            if (!"Unknown".equals(condition)) rowDescription.append(", ").append(condition);
            if (hi != null) {
                rowDescription.append(", high ").append(hi)
                        .append(" degrees ").append(temperatureUnitWord());
            }
            if (lo != null) {
                rowDescription.append(", low ").append(lo)
                        .append(" degrees ").append(temperatureUnitWord());
            }
            if (p >= 0) rowDescription.append(", ").append(p).append(" percent precipitation");
            rowDescription.append(". Tap for hourly details and to preview this day; tap again to return to now.");
            row.setContentDescription(rowDescription.toString());
            row.setOnClickListener(v -> details.toggleDay(dayIndex));
            makeChildrenUnimportant(row);
            forecastPreview.registerTarget(row, dayPreviewKey(dayIndex));
            item.addView(row);

            LinearLayout detailHost = new LinearLayout(this);
            detailHost.setOrientation(LinearLayout.VERTICAL);
            item.addView(detailHost, new LinearLayout.LayoutParams(-1, -2));
            details.registerListDetailHost(dayIndex, detailHost);

            list.addView(item);

            if (i < count - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(Color.argb(28, 255, 255, 255));
                list.addView(divider, new LinearLayout.LayoutParams(-1, 1));
            }
        }
        return scroller;
    }

    final class DayDetailCoordinator implements HourlyCoverageCoordinator {
        private final JSONArray days;
        private final JSONArray hours;
        private final ZoneId zone;
        private final String hourlyDiagnostic;
        private final LinearLayout lineDetailHost;
        private final ArrayList<LinearLayout> listDetailHosts = new ArrayList<>();
        private int expandedDay = -1;
        private boolean listMode;

        DayDetailCoordinator(
                JSONArray days,
                JSONArray hours,
                ZoneId zone,
                String hourlyDiagnostic,
                LinearLayout lineDetailHost) {
            this.days = days;
            this.hours = hours;
            this.zone = zone;
            this.hourlyDiagnostic = hourlyDiagnostic == null ? "" : hourlyDiagnostic;
            this.lineDetailHost = lineDetailHost;
        }

        void registerListDetailHost(int index, LinearLayout host) {
            while (listDetailHosts.size() <= index) listDetailHosts.add(null);
            listDetailHosts.set(index, host);
        }

        void setListMode(boolean listMode) {
            this.listMode = listMode;
            renderExpandedDay();
        }

        void toggleDay(int index) {
            if (days == null || index < 0 || index >= days.length()) return;
            boolean sameDay = expandedDay == index;
            LocalDate previousTarget = expandedDay >= 0
                    ? displayDate(days.optJSONObject(expandedDay), zone)
                    : null;
            expandedDay = sameDay ? -1 : index;
            expandedDayIndex = expandedDay;
            if (sameDay) {
                forecastPreview.restore(true);
                synchronized (hourlyCoverageLock) {
                    pendingHourlyCoverageTarget = null;
                    if (previousTarget != null && previousTarget.equals(hourlyCoverageLoadingTarget)) {
                        hourlyCoverageLoadingTarget = null;
                    }
                }
                renderExpandedDay();
                return;
            }

            JSONObject day = days.optJSONObject(index);
            String label = index == 0 ? "Today" : dayLabel(day, zone);
            forecastPreview.select(
                    dayPreviewKey(index),
                    label,
                    sceneForForecastDay(day, index));

            // Mark/queue the target before the first detail render. That prevents a one-frame
            // unavailable + disabled-Loading-button contradiction while lazy continuation starts.
            LocalDate target = displayDate(day, zone);
            ensureHourlyCoverage(target, this);
            renderExpandedDay();
        }

        public void onHourlyCoverageChanged() {
            if (expandedDay < 0) return;
            int pageScrollY = mainScroll == null ? 0 : mainScroll.getScrollY();
            renderExpandedDay();
            if (mainScroll != null) {
                mainScroll.post(() -> mainScroll.scrollTo(0, pageScrollY));
            }
        }

        private void renderExpandedDay() {
            lineDetailHost.removeAllViews();
            lineDetailHost.setVisibility(View.GONE);
            for (LinearLayout host : listDetailHosts) {
                if (host != null) host.removeAllViews();
            }
            if (expandedDay < 0) return;

            View detail = buildDayHourlyDetail(
                    expandedDay, days, hours, zone, hourlyDiagnostic(lastHourlyWeather), this);
            if (listMode && expandedDay < listDetailHosts.size()) {
                LinearLayout host = listDetailHosts.get(expandedDay);
                if (host != null) {
                    host.addView(detail, new LinearLayout.LayoutParams(-1, -2));
                    return;
                }
            }
            if (!listMode) {
                lineDetailHost.addView(detail, new LinearLayout.LayoutParams(-1, -2));
                lineDetailHost.setVisibility(View.VISIBLE);
            }
        }
    }

    View buildDayHourlyDetail(
            int dayIndex,
            JSONArray days,
            JSONArray hours,
            ZoneId zone,
            String hourlyDiagnostic,
            DayDetailCoordinator coordinator) {
        LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setPadding(dp(10), dp(10), dp(10), dp(11));
        detail.setBackground(roundedBg(Color.argb(28, 255, 255, 255), dp(18)));

        JSONObject day = days == null ? null : days.optJSONObject(dayIndex);
        String dayName = dayIndex == 0 ? "Today" : dayLabel(day, zone);
        String date = dayDateLabel(day, zone);
        TextView title = text(dayName + (date.isEmpty() ? "" : "  " + date) + "  •  Hourly", 13, true, WHITE);
        detail.addView(title);

        TextView collapseHint = text("Tap the day again to collapse", 10, false, FAINT_WHITE);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(2);
        detail.addView(collapseHint, hintLp);

        LocalDate targetDate = displayDate(day, zone);
        HourlyPageState state = hourlyPageState;
        boolean covered = state != null
                && isHourlyStateCurrent(state)
                && hourlyDateCovered(state, targetDate, zone);
        boolean loadingTarget = !covered && isHourlyCoverageLoadingFor(targetDate);

        ArrayList<JSONObject> matches = new ArrayList<>();
        if (targetDate != null && hours != null) {
            for (int i = 0; i < hours.length(); i++) {
                JSONObject hour = hours.optJSONObject(i);
                LocalDate hourDate = hourLocalDate(hour, zone);
                if (hour != null && targetDate.equals(hourDate)) matches.add(hour);
            }
        }

        if (loadingTarget) {
            LinearLayout loading = new LinearLayout(this);
            loading.setOrientation(LinearLayout.HORIZONTAL);
            loading.setGravity(Gravity.CENTER_VERTICAL);
            loading.setPadding(0, dp(11), 0, dp(3));

            ProgressBar spinner = new ProgressBar(this);
            spinner.setIndeterminate(true);
            spinner.setContentDescription("Loading hourly details for " + dayName);
            loading.addView(spinner, new LinearLayout.LayoutParams(dp(22), dp(22)));

            TextView label = text("Loading hourly details…", 12, false, SOFT_WHITE);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-2, -2);
            labelLp.leftMargin = dp(9);
            loading.addView(label, labelLp);
            loading.setContentDescription("Loading hourly details for " + dayName);
            detail.addView(loading, new LinearLayout.LayoutParams(-1, dp(46)));
            return detail;
        }

        if (matches.isEmpty()) {
            boolean failed = state != null
                    && isHourlyStateCurrent(state)
                    && state.aggregate.optBoolean(HOURLY_LOAD_ERROR, false);
            boolean terminal = state != null
                    && isHourlyStateCurrent(state)
                    && state.terminal
                    && !failed;

            String headline = failed
                    ? "Couldn’t load hourly details."
                    : "No returned hourly data covers this day.";
            TextView unavailable = text(headline, 12, false, SOFT_WHITE);
            unavailable.setPadding(0, dp(10), 0, dp(2));
            detail.addView(unavailable);

            String diagnostic = hourlyDiagnostic == null ? "" : hourlyDiagnostic.trim();
            if (failed && !diagnostic.isEmpty()) {
                TextView reason = text(diagnostic, 10, false, FAINT_WHITE);
                reason.setMaxLines(4);
                reason.setEllipsize(android.text.TextUtils.TruncateAt.END);
                detail.addView(reason);
            } else if (terminal) {
                detail.addView(text("The returned hourly pages ended before this date.", 10, false, FAINT_WHITE));
            }

            if (hourlyRetryPossible(state)) {
                Button retry = button("Retry hourly details");
                retry.setContentDescription("Retry hourly details for " + dayName);
                retry.setOnClickListener(v -> ensureHourlyCoverage(targetDate, coordinator));
                LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(-2, dp(40));
                retryLp.topMargin = dp(8);
                detail.addView(retry, retryLp);
            }
            return detail;
        }

        GestureHorizontalScrollView scroller = new GestureHorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        scroller.setFillViewport(false);
        scroller.setContentDescription("Hourly forecast for " + dayName);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);

        for (JSONObject hour : matches) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(4), dp(3), dp(4), dp(4));

            TextView time = text(hourLabel(hour, zone), 11, true, SOFT_WHITE);
            time.setGravity(Gravity.CENTER);
            cell.addView(time, new LinearLayout.LayoutParams(-1, dp(22)));

            WeatherGlyphView glyph = new WeatherGlyphView(
                    this, description(hour), safeBoolean(hour, "isDaytime", true));
            cell.addView(glyph, new LinearLayout.LayoutParams(dp(32), dp(32)));

            Integer temperature = degreesOrNull(hour.optJSONObject("temperature"));
            TextView temp = text(temperature == null ? "—°" : temperature + "°", 15, true, WHITE);
            temp.setGravity(Gravity.CENTER);
            cell.addView(temp);

            int p = probability(hour);
            TextView rain = text(p >= 0 ? p + "%" : "—", 10, false, ACCENT_BLUE);
            rain.setGravity(Gravity.CENTER);
            cell.addView(rain);

            JSONObject hourWind = hour.optJSONObject("wind");
            String hourWindValue = formatWindSpeed(
                    hourWind == null ? null : hourWind.optJSONObject("speed"));
            String hourPressureValue = formatPressure(hour.optJSONObject("airPressure"));
            String hourVisibilityValue = formatVisibility(hour.optJSONObject("visibility"));
            addHourlyMetricLine(cell, "Wind", hourWindValue);
            addHourlyMetricLine(cell, "Pressure", hourPressureValue);
            addHourlyMetricLine(cell, "Visibility", hourVisibilityValue);
            JSONObject predictedAqi = airQualityForecastForWeatherHour(hour);
            String predictedAqiValue = aqiDisplayValue(predictedAqi);
            if (!predictedAqiValue.isEmpty()) {
                addHourlyMetricLine(cell, "AQI", predictedAqiValue);
            }

            TextView condition = text(description(hour), 9, false, FAINT_WHITE);
            condition.setGravity(Gravity.CENTER);
            condition.setMaxLines(2);
            condition.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cell.addView(condition, new LinearLayout.LayoutParams(-1, dp(30)));
            StringBuilder hourDescription = new StringBuilder(hourLabel(hour, zone));
            if (temperature != null) {
                hourDescription.append(", ").append(temperature)
                        .append(" degrees ").append(temperatureUnitWord());
            }
            if (p >= 0) hourDescription.append(", ").append(p).append(" percent precipitation");
            if (!"—".equals(hourWindValue)) hourDescription.append(", wind ").append(hourWindValue);
            if (!"—".equals(hourPressureValue)) hourDescription.append(", pressure ").append(hourPressureValue);
            if (!"—".equals(hourVisibilityValue)) hourDescription.append(", visibility ").append(hourVisibilityValue);
            if (!predictedAqiValue.isEmpty()) {
                hourDescription.append(", predicted air quality index ").append(predictedAqiValue);
            }
            configureHourPreviewCell(
                    cell,
                    hour,
                    zone,
                    false,
                    hourDescription.toString());

            row.addView(cell, new LinearLayout.LayoutParams(dp(116), -2));
        }
        scroller.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, -2);
        scrollLp.topMargin = dp(2);
        detail.addView(scroller, scrollLp);

        if (!covered && state != null && isHourlyStateCurrent(state)) {
            boolean failed = state.aggregate.optBoolean(HOURLY_LOAD_ERROR, false);
            TextView partial = text(
                    failed
                            ? "Returned hours are partial because hourly continuation stopped."
                            : "Only the returned hours for this day are available.",
                    10,
                    false,
                    FAINT_WHITE);
            LinearLayout.LayoutParams partialLp = new LinearLayout.LayoutParams(-1, -2);
            partialLp.topMargin = dp(6);
            detail.addView(partial, partialLp);
            if (hourlyRetryPossible(state)) {
                Button retry = button("Retry hourly details");
                retry.setContentDescription("Retry remaining hourly details for " + dayName);
                retry.setOnClickListener(v -> ensureHourlyCoverage(targetDate, coordinator));
                LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(-2, dp(40));
                retryLp.topMargin = dp(7);
                detail.addView(retry, retryLp);
            }
        }
        return detail;
    }

    void addHourlyMetricLine(LinearLayout cell, String label, String value) {
        if (cell == null || value == null || "—".equals(value)) return;
        TextView metric = text(label + " " + value, 8, false, FAINT_WHITE);
        metric.setGravity(Gravity.CENTER);
        metric.setSingleLine(true);
        metric.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cell.addView(metric, new LinearLayout.LayoutParams(-1, dp(17)));
    }

    JSONObject airQualityForecastForWeatherHour(JSONObject weatherHour) {
        if (!weatherPreferences.airQualityEnabled() || weatherHour == null) return null;
        OptionalDataState state = optionalDataStateForCurrentScope(true);
        JSONObject forecast = state == null || state.details == null
                ? null : state.details.optJSONObject("forecast");
        JSONArray predictions = forecast == null ? null : forecast.optJSONArray("hourlyForecasts");
        ZoneId zone = responseZone(lastCurrentWeather, lastHourlyWeather, lastDailyWeather);
        Instant target = weatherHourInstant(weatherHour, zone);
        if (predictions == null || target == null) return null;
        Instant targetHour = target.truncatedTo(ChronoUnit.HOURS);
        for (int i = 0; i < predictions.length(); i++) {
            JSONObject prediction = predictions.optJSONObject(i);
            Instant instant = parseInstant(prediction == null
                    ? null : stringValue(prediction, "dateTime"));
            if (prediction == null || instant == null) continue;
            if (targetHour.equals(instant.truncatedTo(ChronoUnit.HOURS))) {
                return universalAqiIndex(prediction.optJSONArray("indexes"));
            }
        }
        return null;
    }

    Instant weatherHourInstant(JSONObject hour, ZoneId zone) {
        JSONObject interval = firstJSONObject(hour, "interval", "timeInterval", "time_interval");
        String start = firstNonEmpty(
                firstNonEmpty(stringValue(interval, "startTime"),
                        stringValue(interval, "start_time")),
                stringValue(interval, "start"));
        Instant exact = parseInstant(start);
        if (exact != null) return exact;
        JSONObject display = firstJSONObject(hour,
                "displayDateTime", "display_date_time", "displayTime", "display_time");
        LocalDate date = localDateFields(display);
        int hourOfDay = safeInt(display, "hours", safeInt(display, "hour", -1));
        int minute = safeInt(display, "minutes", safeInt(display, "minute", 0));
        if (date == null || hourOfDay < 0 || hourOfDay > 23 || minute < 0 || minute > 59) {
            return null;
        }
        return date.atTime(hourOfDay, minute).atZone(zone).toInstant();
    }

    String aqiDisplayValue(JSONObject index) {
        if (index == null) return "";
        String display = stringValue(index, "aqiDisplay");
        if (!display.isEmpty()) return display;
        Double value = numberValue(index, "aqi");
        return value == null ? "" : Integer.toString((int) Math.round(value));
    }

    void addDetailTiles(JSONObject current) {
        Integer feels = degreesOrNull(current == null ? null : current.optJSONObject("feelsLikeTemperature"));
        int humidity = safeInt(current, "relativeHumidity", -1);
        int uv = safeInt(current, "uvIndex", -1);

        JSONObject wind = current == null ? null : current.optJSONObject("wind");
        JSONObject speed = wind == null ? null : wind.optJSONObject("speed");
        JSONObject direction = wind == null ? null : wind.optJSONObject("direction");
        String cardinal = direction == null ? "" : direction.optString("cardinal", "");
        Double degrees = direction == null ? null : numberValue(direction, "degrees");
        String windLabel = shortCardinal(cardinal, degrees);
        String windValue = formatWindSpeed(speed);

        JSONObject pressureObject = current == null ? null : current.optJSONObject("airPressure");
        String pressureValue = formatPressure(pressureObject);
        String visibilityValue = formatVisibility(
                current == null ? null : current.optJSONObject("visibility"));

        ArrayList<OverviewTileSpec> tiles = new ArrayList<>();
        if (detailEnabled(WeatherDetailSettingsActivity.PREF_UV_INDEX, true)) {
            addOverviewTile(tiles, "uv",
                    detailTile("UV", uv < 0 ? "—" : uv + "\n" + uvHint(uv).trim(), "uv"));
        }
        if (detailEnabled(WeatherDetailSettingsActivity.PREF_FEELS_LIKE, true)) {
            addOverviewTile(tiles, "feels_like", detailTile("Feels like",
                    feels == null ? "—" : feels + temperatureUnitSymbol(), "temperature"));
        }
        if (detailEnabled(WeatherDetailSettingsActivity.PREF_HUMIDITY, true)) {
            addOverviewTile(tiles, "humidity",
                    detailTile("Humidity", humidity < 0 ? "—" : humidity + "%", "humidity"));
        }
        if (detailEnabled(WeatherDetailSettingsActivity.PREF_WIND, true)) {
            addOverviewTile(tiles, "wind", detailTile(
                    windLabel.isEmpty() ? "Wind" : windLabel + " wind", windValue, "wind"));
        }
        if (detailEnabled(WeatherDetailSettingsActivity.PREF_AIR_PRESSURE, true)) {
            addOverviewTile(tiles, "air_pressure",
                    detailTile("Air pressure", pressureValue, "pressure"));
        }
        if (detailEnabled(WeatherDetailSettingsActivity.PREF_VISIBILITY, true)) {
            addOverviewTile(tiles, "visibility",
                    detailTile("Visibility", visibilityValue, "visibility"));
        }

        int cloudCover = safeInt(current, "cloudCover", -1);
        if (detailEnabled(WeatherDetailSettingsActivity.PREF_CLOUD_COVER, true)
                && cloudCover >= 0) {
            addOverviewTile(tiles, "cloud_cover",
                    detailTile("Cloud cover", cloudCover + "%", "visibility"));
        }
        String gustValue = formatWindSpeed(wind == null ? null : wind.optJSONObject("gust"));
        if (detailEnabled(WeatherDetailSettingsActivity.PREF_WIND_GUST, true)
                && !"—".equals(gustValue)) {
            addOverviewTile(tiles, "wind_gust", detailTile("Wind gust", gustValue, "wind"));
        }
        int thunder = safeInt(current, "thunderstormProbability", -1);
        if (detailEnabled(WeatherDetailSettingsActivity.PREF_THUNDERSTORM_CHANCE, true)
                && thunder > 0) {
            addOverviewTile(tiles, "thunder_chance",
                    detailTile("Thunder chance", thunder + "%", "uv"));
        }
        addTemperatureDetailTile(tiles, "dew_point", current, "Dew point", "dewPoint",
                WeatherDetailSettingsActivity.PREF_DEW_POINT, false);
        addTemperatureDetailTile(tiles, "heat_index", current, "Heat index", "heatIndex",
                WeatherDetailSettingsActivity.PREF_HEAT_INDEX, false);
        addTemperatureDetailTile(tiles, "wind_chill", current, "Wind chill", "windChill",
                WeatherDetailSettingsActivity.PREF_WIND_CHILL, false);

        JSONObject history = current == null ? null : current.optJSONObject("currentConditionsHistory");
        JSONObject temperatureChangeObject = history == null
                ? null : history.optJSONObject("temperatureChange");
        Double temperatureChangeCelsius = numberValue(temperatureChangeObject, "degrees");
        if (temperatureChangeCelsius == null) {
            temperatureChangeCelsius = numberValue(temperatureChangeObject, "value");
        }
        Integer temperatureChange = temperatureChangeCelsius == null ? null
                : (int) Math.round(isFahrenheitUnit()
                        ? temperatureChangeCelsius * 9d / 5d : temperatureChangeCelsius);
        if (detailEnabled(WeatherDetailSettingsActivity.PREF_TEMPERATURE_CHANGE_24H, false)
                && temperatureChange != null) {
            addOverviewTile(tiles, "temperature_change_24h", detailTile("24h temperature",
                    (temperatureChange > 0 ? "+" : "") + temperatureChange + temperatureUnitSymbol(),
                    "temperature"));
        }
        String precipitation24h = formatQpf(history == null ? null : history.optJSONObject("qpf"));
        if (detailEnabled(WeatherDetailSettingsActivity.PREF_PRECIPITATION_24H, false)
                && !"—".equals(precipitation24h)) {
            addOverviewTile(tiles, "precipitation_24h",
                    detailTile("24h precipitation", precipitation24h, "humidity"));
        }

        if (airQualityEnabled()) {
            OptionalDataState state = optionalDataStateForCurrentScope(true);
            addOverviewTile(tiles, "air_quality", optionalDetailTile(
                    "Air quality", state, "air", "Universal AQI loading", true));
        }
        if (pollenEnabled()) {
            OptionalDataState state = optionalDataStateForCurrentScope(false);
            addOverviewTile(tiles, "pollen", optionalDetailTile(
                    "Pollen", state, "pollen", "Pollen forecast loading", false));
        }
        addDetailTileRows(tiles);
    }

    boolean detailEnabled(String key, boolean defaultValue) {
        return getSharedPreferences(UI_PREFS, MODE_PRIVATE).getBoolean(key, defaultValue);
    }

    void addOverviewTile(ArrayList<OverviewTileSpec> tiles, String id, View view) {
        if (tiles == null || id == null || id.isEmpty() || view == null) return;
        tiles.add(new OverviewTileSpec(id, view));
    }

    void addTemperatureDetailTile(
            ArrayList<OverviewTileSpec> tiles,
            String tileId,
            JSONObject current,
            String label,
            String responseKey,
            String preferenceKey,
            boolean defaultValue) {
        if (!detailEnabled(preferenceKey, defaultValue)) return;
        Integer value = degreesOrNull(current == null ? null : current.optJSONObject(responseKey));
        if (value != null) {
            addOverviewTile(tiles, tileId,
                    detailTile(label, value + temperatureUnitSymbol(), "temperature"));
        }
    }

    void addDetailTileRows(ArrayList<OverviewTileSpec> availableTiles) {
        if (availableTiles == null || availableTiles.isEmpty()) return;
        ArrayList<OverviewTileSpec> tiles = orderedOverviewTiles(availableTiles);
        for (OverviewTileSpec tile : tiles) {
            tile.view.setTag(overviewTileStreamId(tile.id));
            pageContent().addView(tile.view, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    ArrayList<OverviewTileSpec> orderedOverviewTiles(ArrayList<OverviewTileSpec> availableTiles) {
        ArrayList<OverviewTileSpec> ordered = new ArrayList<>();
        ArrayList<String> savedOrder = overviewTileFullOrder();
        for (String id : savedOrder) {
            OverviewTileSpec tile = findOverviewTileSpec(availableTiles, id);
            if (tile != null && findOverviewTileSpec(ordered, id) == null) ordered.add(tile);
        }
        for (OverviewTileSpec tile : availableTiles) {
            if (findOverviewTileSpec(ordered, tile.id) == null) ordered.add(tile);
        }
        return ordered;
    }

    ArrayList<String> overviewTileFullOrder() {
        ArrayList<String> order = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        String saved = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                .getString(PREF_OVERVIEW_TILE_ORDER, "");
        if (saved != null && !saved.trim().isEmpty()) {
            try {
                JSONArray array = new JSONArray(saved);
                for (int i = 0; i < array.length() && order.size() < 64; i++) {
                    String id = array.optString(i, "").trim();
                    if (!id.isEmpty() && seen.add(id)) order.add(id);
                }
            } catch (Exception ignored) { }
        }
        for (String id : DEFAULT_OVERVIEW_TILE_ORDER) {
            if (seen.add(id)) order.add(id);
        }
        return order;
    }

    int overviewTileColumnCount(LinearLayout board) {
        int width = board == null ? 0 : board.getWidth();
        if (width <= 0 && pageContent() != null) width = pageContent().getWidth();
        if (width <= 0) {
            width = Math.max(1, getResources().getDisplayMetrics().widthPixels - dp(36));
        }
        int minimumTileWidth = Math.max(1, dp(96));
        return Math.max(1, Math.min(3, width / minimumTileWidth));
    }

    OverviewTileSpec findOverviewTileSpec(ArrayList<OverviewTileSpec> tiles, String id) {
        if (tiles == null || id == null) return null;
        for (OverviewTileSpec tile : tiles) {
            if (id.equals(tile.id)) return tile;
        }
        return null;
    }

    String formatQpf(JSONObject qpf) {
        Double quantity = numberValue(qpf, "quantity");
        if (quantity == null) return "—";
        String unit = qpf == null ? "" : qpf.optString("unit", "");
        if (isFahrenheitUnit()) {
            double inches = unit.toUpperCase(Locale.ROOT).contains("INCH")
                    ? quantity : quantity / 25.4d;
            return trimNumber(inches) + " in";
        }
        double millimeters = unit.toUpperCase(Locale.ROOT).contains("INCH")
                ? quantity * 25.4d : quantity;
        return trimNumber(millimeters) + " mm";
    }

    OptionalDataState optionalDataStateForCurrentScope(boolean airQuality) {
        synchronized (optionalDataLock) {
            OptionalDataState state = airQuality ? airQualityState : pollenState;
            if (state == null) return null;
            String language = Locale.getDefault().toLanguageTag();
            return state.matches(weatherRequestGeneration, latitude, longitude, language) ? state : null;
        }
    }

    View optionalDetailTile(
            String label,
            OptionalDataState state,
            String glyph,
            String loadingDescription,
            boolean airQuality) {
        String value = state == null || state.loading ? "Loading…" : state.value;
        if (value == null || value.trim().isEmpty()) value = "Unavailable";
        boolean actionableError = state != null && !state.loading && !state.available;
        boolean keyBlocked = actionableError && "Key blocked".equals(state.value);
        if (keyBlocked) value = "Key blocked\nTap to fix";
        else if (actionableError) value = "No data\nTap for help";
        View tile = detailTile(label, value, glyph);
        if (tile instanceof LinearLayout) {
            View valueView = ((LinearLayout) tile).getChildAt(2);
            if (valueView instanceof TextView) {
                ((TextView) valueView).setTextSize(actionableError ? 12.5f : 14f);
                ((TextView) valueView).setEllipsize(
                        android.text.TextUtils.TruncateAt.END);
            }
        }
        if (state == null || state.loading) {
            tile.setContentDescription(loadingDescription);
        } else if (state.accessibility != null && !state.accessibility.trim().isEmpty()) {
            tile.setContentDescription(label + ", " + state.accessibility);
        }
        if (actionableError) {
            tile.setClickable(true);
            tile.setFocusable(true);
            tile.setContentDescription(label + ", " + state.accessibility + ". Tap for help.");
            tile.setOnClickListener(v -> showOptionalDataHelpDialog(label, airQuality, state));
        } else if (state != null && !state.loading && state.available && state.details != null) {
            tile.setClickable(true);
            tile.setFocusable(true);
            tile.setContentDescription(label + ", " + state.accessibility + ". Tap for forecast details.");
            tile.setOnClickListener(v -> showEnvironmentalDetailsDialog(label, airQuality, state));
        }
        return tile;
    }

    void showEnvironmentalDetailsDialog(
            String label, boolean airQuality, OptionalDataState state) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setFillViewport(false);
        scroller.setClipToPadding(false);
        scroller.setAccessibilityPaneTitle(label + " details");

        int horizontalPadding = getResources().getConfiguration().screenWidthDp < 360
                ? dp(14) : dp(18);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(horizontalPadding, dp(17), horizontalPadding, dp(16));
        panel.setBackground(newGlassDrawable(dp(26), false));
        scroller.addView(panel, new ScrollView.LayoutParams(-1, -2));

        TextView title = text(label, 22, true, WHITE);
        title.setAccessibilityHeading(true);
        title.setPadding(0, 0, 0, 0);
        panel.addView(title);

        int sceneAccent = environmentAccentColor();

        if (airQuality) addAirQualityDialogContent(panel, state.details);
        else addPollenDialogContent(panel, state.details);

        Button close = button("Close");
        close.setContentDescription("Close " + label + " details");
        GradientDrawable closeBackground = roundedBg(Color.argb(
                44, Color.red(sceneAccent), Color.green(sceneAccent), Color.blue(sceneAccent)),
                dp(22));
        closeBackground.setStroke(dp(1), Color.argb(
                76, Color.red(sceneAccent), Color.green(sceneAccent), Color.blue(sceneAccent)));
        close.setBackground(closeBackground);
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(-1, dp(44));
        closeLp.topMargin = dp(13);
        panel.addView(close, closeLp);

        dialog.setOnDismissListener(ignored -> removePageGlassDrawables(scroller));
        dialog.setContentView(scroller);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.72f;
            window.setAttributes(attributes);
            window.getDecorView().setPadding(0, 0, 0, 0);
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int dialogWidth = Math.max(1, Math.min(screenWidth - dp(20), dp(480)));
            int dialogHeight = Math.max(1, Math.min(screenHeight - dp(36), dp(760)));
            window.setLayout(dialogWidth, dialogHeight);
        }
    }

    void addAirQualityDialogContent(LinearLayout panel, JSONObject details) {
        JSONObject current = details == null ? null : details.optJSONObject("current");
        JSONObject index = universalAqiIndex(current == null ? null : current.optJSONArray("indexes"));
        panel.addView(airQualityHero(index));

        JSONObject recommendations = current == null
                ? null : current.optJSONObject("healthRecommendations");
        View guidance = airQualityGuidanceCard(recommendations);
        if (guidance != null) {
            panel.addView(sectionHeading("HEALTH GUIDANCE"));
            panel.addView(guidance);
        }

        JSONArray pollutants = current == null ? null : current.optJSONArray("pollutants");
        if (pollutants != null) {
            ArrayList<View> pollutantCards = new ArrayList<>();
            for (int i = 0; i < pollutants.length(); i++) {
                JSONObject pollutant = pollutants.optJSONObject(i);
                JSONObject concentration = pollutant == null
                        ? null : pollutant.optJSONObject("concentration");
                Double concentrationValue = numberValue(concentration, "value");
                if (pollutant == null || concentrationValue == null) continue;
                String pollutantName = firstNonEmpty(
                        stringValue(pollutant, "displayName"),
                        stringValue(pollutant, "code").toUpperCase(Locale.ROOT));
                String units = stringValue(concentration, "units")
                        .replace("MICROGRAMS_PER_CUBIC_METER", "µg/m³")
                        .replace('_', ' ').toLowerCase(Locale.getDefault());
                String value = trimNumber(concentrationValue)
                        + (units.isEmpty() ? "" : " " + units);
                View metric = environmentMetricRow(pollutantName, value);
                String code = stringValue(pollutant, "code").toUpperCase(Locale.ROOT);
                metric.setContentDescription((pollutantName.isEmpty() ? code : pollutantName)
                        + ", " + value);
                pollutantCards.add(metric);
            }
            if (!pollutantCards.isEmpty()) {
                panel.addView(sectionHeading("POLLUTANTS"));
                addEnvironmentResponsiveCards(panel, pollutantCards);
            }
        }

        JSONObject forecast = details == null ? null : details.optJSONObject("forecast");
        JSONArray hours = forecast == null ? null : forecast.optJSONArray("hourlyForecasts");
        if (hours == null || hours.length() == 0) {
            panel.addView(sectionHeading("NEXT 24 HOURS"));
            panel.addView(environmentDetailBlock(
                    "Hourly outlook", "No hourly forecast was returned."));
            return;
        }

        panel.addView(sectionHeading("NEXT 24 HOURS"));
        TextView forecastHint = text("Swipe sideways for later hours", 10, false, FAINT_WHITE);
        forecastHint.setPadding(dp(2), 0, 0, dp(6));
        panel.addView(forecastHint);

        ZoneId zone = responseZone(lastCurrentWeather, lastHourlyWeather, lastDailyWeather);
        GestureHorizontalScrollView scroller = new GestureHorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        scroller.setContentDescription(
                "24-hour air quality forecast. Swipe horizontally for later hours.");
        LinearLayout forecastRow = new LinearLayout(this);
        forecastRow.setOrientation(LinearLayout.HORIZONTAL);
        forecastRow.setPadding(0, dp(1), dp(2), dp(4));
        for (int i = 0; i < hours.length(); i++) {
            JSONObject hour = hours.optJSONObject(i);
            if (hour == null) continue;
            JSONObject hourIndex = universalAqiIndex(hour.optJSONArray("indexes"));
            String time = formatEnvironmentalHour(stringValue(hour, "dateTime"), zone);
            forecastRow.addView(airQualityForecastCell(time, hourIndex));
        }
        scroller.addView(forecastRow, new HorizontalScrollView.LayoutParams(-2, -2));
        panel.addView(scroller, new LinearLayout.LayoutParams(-1, dp(118)));
    }

    View airQualityGuidanceCard(JSONObject recommendations) {
        if (recommendations == null) return null;
        String[][] guidance = new String[][]{
                {"General guidance", stringValue(recommendations, "generalPopulation")}
        };
        boolean hasGuidance = false;
        for (String[] row : guidance) {
            if (!row[1].isEmpty()) {
                hasGuidance = true;
                break;
            }
        }
        if (!hasGuidance) return null;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(11), dp(13), dp(11));
        card.setBackground(newGlassDrawable(dp(17), true));
        StringBuilder accessibility = new StringBuilder("Health guidance. ");
        boolean added = false;
        for (String[] row : guidance) {
            if (row[1].isEmpty()) continue;
            if (added) {
                View divider = environmentDivider();
                LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(-1, dp(1));
                dividerLp.topMargin = dp(9);
                dividerLp.bottomMargin = dp(9);
                card.addView(divider, dividerLp);
                accessibility.append(". ");
            }
            card.addView(environmentGuidanceRow(row[0], row[1]));
            accessibility.append(row[0]).append(": ").append(row[1]);
            added = true;
        }
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        card.setContentDescription(accessibility.toString());
        makeChildrenUnimportant(card);
        return card;
    }

    View environmentGuidanceRow(String titleValue, String bodyValue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(titleValue, 11, true, environmentAccentColor());
        row.addView(title);
        TextView body = text(bodyValue, 12, false, SOFT_WHITE);
        body.setLineSpacing(0f, 1.08f);
        body.setPadding(0, dp(3), 0, 0);
        row.addView(body);
        return row;
    }

    void addEnvironmentResponsiveCards(LinearLayout panel, ArrayList<View> cards) {
        if (panel == null || cards == null || cards.isEmpty()) return;
        boolean twoColumns = getResources().getConfiguration().screenWidthDp >= 410;
        if (!twoColumns) {
            for (int i = 0; i < cards.size(); i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) lp.topMargin = dp(6);
                panel.addView(cards.get(i), lp);
            }
            return;
        }

        for (int i = 0; i < cards.size();) {
            int remaining = cards.size() - i;
            if (remaining == 1) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                if (i > 0) lp.topMargin = dp(6);
                panel.addView(cards.get(i), lp);
                i++;
                continue;
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBaselineAligned(false);
            LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, -2, 1f);
            leftLp.rightMargin = dp(3);
            LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, -2, 1f);
            rightLp.leftMargin = dp(3);
            row.addView(cards.get(i), leftLp);
            row.addView(cards.get(i + 1), rightLp);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) rowLp.topMargin = dp(6);
            panel.addView(row, rowLp);
            i += 2;
        }
    }

    void addPollenDialogContent(LinearLayout panel, JSONObject details) {
        JSONArray days = details == null ? null : details.optJSONArray("dailyInfo");
        if (days == null || days.length() == 0) {
            panel.addView(environmentDetailBlock(
                    "Five-day outlook", "No pollen forecast was returned."));
            return;
        }
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            if (day == null) continue;
            JSONObject date = day.optJSONObject("date");
            String dateLabel = pollenDateLabel(date, i);
            JSONArray types = day.optJSONArray("pollenTypeInfo");
            String recommendation = "";
            String recommendationType = "";
            int recommendationIndex = -1;

            int highestValue = -1;
            String highestCategory = "";
            if (types != null) {
                for (int j = 0; j < types.length(); j++) {
                    JSONObject type = types.optJSONObject(j);
                    JSONObject typeIndex = type == null ? null : type.optJSONObject("indexInfo");
                    int value = typeIndex == null ? -1 : typeIndex.optInt("value", -1);
                    if (value > highestValue) {
                        highestValue = value;
                        highestCategory = typeIndex.optString("category", "");
                    }
                }
            }

            int severityColor = pollenColor(highestValue);
            LinearLayout dayCard = new LinearLayout(this);
            dayCard.setOrientation(LinearLayout.VERTICAL);
            dayCard.setPadding(dp(13), dp(12), dp(13), dp(12));
            dayCard.setBackground(newGlassDrawable(dp(19), false));

            LinearLayout dayHeader = new LinearLayout(this);
            dayHeader.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout headerCopy = new LinearLayout(this);
            headerCopy.setOrientation(LinearLayout.VERTICAL);
            TextView dateView = text(dateLabel, 16, true, WHITE);
            dateView.setAccessibilityHeading(true);
            headerCopy.addView(dateView);
            String dailySummary = highestValue < 0
                    ? "Daily index unavailable"
                    : firstNonEmpty(highestCategory, "Universal Pollen Index")
                            + " · highest daily level";
            TextView summary = text(dailySummary, 10, false, SOFT_WHITE);
            summary.setPadding(0, dp(2), dp(6), 0);
            headerCopy.addView(summary);
            dayHeader.addView(headerCopy, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView badge = environmentBadge(
                    highestValue < 0 ? "No index" : "UPI " + highestValue,
                    severityColor);
            dayHeader.addView(badge);
            dayHeader.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            dayHeader.setAccessibilityHeading(true);
            dayHeader.setContentDescription(dateLabel + ", "
                    + (highestValue < 0
                            ? "pollen index unavailable"
                            : "highest Universal Pollen Index " + highestValue
                                    + (highestCategory.isEmpty()
                                            ? "" : ", " + highestCategory)));
            makeChildrenUnimportant(dayHeader);
            dayCard.addView(dayHeader);

            LinearLayout.LayoutParams severityLp = new LinearLayout.LayoutParams(-1, dp(4));
            severityLp.topMargin = dp(9);
            dayCard.addView(environmentSeverityTrack(highestValue, 5, severityColor), severityLp);

            if (types != null && types.length() > 0) {
                dayCard.addView(environmentMiniHeading("POLLEN TYPES"));
                boolean typeAdded = false;
                for (int j = 0; j < types.length(); j++) {
                    JSONObject type = types.optJSONObject(j);
                    if (type == null) continue;
                    String typeName = firstNonEmpty(
                            stringValue(type, "displayName"),
                            prettyEnum(stringValue(type, "code")));
                    JSONObject typeIndex = type.optJSONObject("indexInfo");
                    if (typeIndex == null) continue;
                    int value = typeIndex.optInt("value", -1);
                    String category = stringValue(typeIndex, "category");
                    if (typeAdded) dayCard.addView(environmentDivider());
                    dayCard.addView(pollenTypeRow(typeName, value, category));
                    typeAdded = true;

                    JSONArray recs = type.optJSONArray("healthRecommendations");
                    String candidateRecommendation = recs == null || recs.isNull(0)
                            ? "" : recs.optString(0, "").trim();
                    if (!candidateRecommendation.isEmpty() && value > recommendationIndex) {
                        recommendation = candidateRecommendation;
                        recommendationType = typeName;
                        recommendationIndex = value;
                    }
                }
            }

            JSONArray plants = day.optJSONArray("plantInfo");
            int activePlantCount = 0;
            if (plants != null) {
                ArrayList<View> plantRows = new ArrayList<>();
                for (int j = 0; j < plants.length() && plantRows.size() < 5; j++) {
                    JSONObject plant = plants.optJSONObject(j);
                    JSONObject plantIndex = plant == null ? null : plant.optJSONObject("indexInfo");
                    if (plant == null || plantIndex == null) continue;
                    int plantValue = plantIndex.optInt("value", -1);
                    boolean inSeason = plant.optBoolean("inSeason", false);
                    if (plantValue <= 0 && !inSeason) continue;
                    String plantName = firstNonEmpty(
                            stringValue(plant, "displayName"),
                            prettyEnum(stringValue(plant, "code")));
                    if (plantName.isEmpty()) continue;
                    String category = stringValue(plantIndex, "category");
                    plantRows.add(pollenPlantRow(
                            plantName, plantValue, category, inSeason));
                }
                if (!plantRows.isEmpty()) {
                    dayCard.addView(environmentMiniHeading("ACTIVE PLANTS"));
                    for (int j = 0; j < plantRows.size(); j++) {
                        if (j > 0) dayCard.addView(environmentDivider());
                        dayCard.addView(plantRows.get(j));
                    }
                    activePlantCount = plantRows.size();
                }
            }

            if (!recommendation.isEmpty()) {
                dayCard.addView(environmentInset(
                        recommendationType.isEmpty() ? "Health guidance"
                                : recommendationType + " guidance",
                        recommendation));
            }
            if ((types == null || types.length() == 0) && activePlantCount == 0) {
                TextView empty = text("No in-season pollen index.", 12, false, SOFT_WHITE);
                LinearLayout.LayoutParams emptyLp = new LinearLayout.LayoutParams(-1, -2);
                emptyLp.topMargin = dp(10);
                dayCard.addView(empty, emptyLp);
            }

            LinearLayout.LayoutParams dayLp = new LinearLayout.LayoutParams(-1, -2);
            dayLp.topMargin = dp(i == 0 ? 7 : 9);
            panel.addView(dayCard, dayLp);
        }
    }

    View airQualityHero(JSONObject index) {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(13), dp(13), dp(13), dp(13));
        hero.setBackground(newGlassDrawable(dp(19), false));

        int accent = airQualityColor(index);
        LinearLayout score = new LinearLayout(this);
        score.setOrientation(LinearLayout.VERTICAL);
        score.setGravity(Gravity.CENTER);
        GradientDrawable scoreBackground = roundedBg(Color.argb(
                62, Color.red(accent), Color.green(accent), Color.blue(accent)), dp(16));
        score.setBackground(scoreBackground);
        TextView value = text(aqiDisplayValue(index).isEmpty() ? "—" : aqiDisplayValue(index),
                30, true, WHITE);
        value.setGravity(Gravity.CENTER);
        value.setIncludeFontPadding(false);
        score.addView(value);
        TextView aqi = text("UAQI", 9, true, WHITE);
        aqi.setAlpha(0.86f);
        aqi.setGravity(Gravity.CENTER);
        score.addView(aqi);
        hero.addView(score, new LinearLayout.LayoutParams(dp(80), dp(76)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView statusLabel = text("CURRENT STATUS", 9, true, environmentAccentColor());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) statusLabel.setLetterSpacing(0.08f);
        copy.addView(statusLabel);

        String category = index == null ? "Current air quality unavailable"
                : firstNonEmpty(stringValue(index, "category"), "Current air quality");
        View status = environmentStatusLine(category, accent, 16, true);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.topMargin = dp(3);
        copy.addView(status, statusLp);

        String dominant = index == null ? ""
                : prettyEnum(stringValue(index, "dominantPollutant"));
        TextView detail = text(dominant.isEmpty()
                ? "Universal Air Quality Index"
                : "Main pollutant · " + dominant, 11, false, SOFT_WHITE);
        detail.setPadding(0, dp(4), 0, 0);
        detail.setMaxLines(2);
        copy.addView(detail);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, -2, 1f);
        copyLp.leftMargin = dp(12);
        hero.addView(copy, copyLp);

        String aqiValue = aqiDisplayValue(index);
        hero.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        hero.setContentDescription("Air quality index "
                + (aqiValue.isEmpty() ? "unavailable" : aqiValue)
                + ", " + category
                + (dominant.isEmpty() ? "" : ", main pollutant " + dominant));
        makeChildrenUnimportant(hero);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(2);
        hero.setLayoutParams(lp);
        return hero;
    }

    View airQualityForecastCell(String time, JSONObject index) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(7), dp(7), dp(7), dp(7));
        cell.setBackground(newGlassDrawable(dp(15), true));
        int accent = airQualityColor(index);

        View accentBar = new View(this);
        accentBar.setBackground(roundedBg(accent, dp(2)));
        cell.addView(accentBar, new LinearLayout.LayoutParams(-1, dp(3)));

        String safeTime = time == null ? "" : time.trim();
        int split = safeTime.lastIndexOf(' ');
        String dayLabel = split > 0 ? safeTime.substring(0, split) : "";
        String clockLabel = split > 0 ? safeTime.substring(split + 1) : safeTime;
        if (!dayLabel.isEmpty()) {
            TextView day = text(dayLabel, 9, false, FAINT_WHITE);
            day.setGravity(Gravity.CENTER);
            day.setPadding(0, dp(5), 0, 0);
            cell.addView(day);
        }
        TextView timeView = text(clockLabel.isEmpty() ? "—" : clockLabel, 11, true, SOFT_WHITE);
        timeView.setGravity(Gravity.CENTER);
        if (dayLabel.isEmpty()) timeView.setPadding(0, dp(5), 0, 0);
        cell.addView(timeView);

        TextView value = text(aqiDisplayValue(index).isEmpty() ? "—" : aqiDisplayValue(index),
                21, true, WHITE);
        value.setGravity(Gravity.CENTER);
        value.setPadding(0, dp(3), 0, 0);
        cell.addView(value);

        String category = index == null ? "Unavailable" : stringValue(index, "category");
        TextView categoryView = text(category, 9, false, SOFT_WHITE);
        categoryView.setGravity(Gravity.CENTER);
        categoryView.setMaxLines(2);
        categoryView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams categoryLp = new LinearLayout.LayoutParams(-1, dp(27));
        categoryLp.topMargin = dp(2);
        cell.addView(categoryView, categoryLp);

        LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(dp(84), dp(110));
        cellLp.rightMargin = dp(6);
        cell.setLayoutParams(cellLp);
        cell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        cell.setContentDescription((safeTime.isEmpty() ? "Forecast hour" : safeTime)
                + ", air quality index "
                + (aqiDisplayValue(index).isEmpty() ? "unavailable" : aqiDisplayValue(index))
                + (category.isEmpty() ? "" : ", " + category));
        makeChildrenUnimportant(cell);
        return cell;
    }

    View environmentMetricRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(newGlassDrawable(dp(15), true));
        TextView metricLabel = text(label, 10, true, SOFT_WHITE);
        metricLabel.setMaxLines(2);
        metricLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(metricLabel);
        TextView amount = text(value, 15, true, WHITE);
        amount.setPadding(0, dp(3), 0, 0);
        amount.setMaxLines(2);
        row.addView(amount);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        row.setContentDescription(label + ", " + value);
        makeChildrenUnimportant(row);
        return row;
    }

    View pollenTypeRow(String name, int value, String category) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(8), 0, dp(8));

        LinearLayout labels = new LinearLayout(this);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labelCopy = new LinearLayout(this);
        labelCopy.setOrientation(LinearLayout.VERTICAL);
        TextView nameView = text(name, 12, true, WHITE);
        labelCopy.addView(nameView);
        if (category != null && !category.isEmpty()) {
            TextView categoryView = text(category, 10, false, SOFT_WHITE);
            categoryView.setPadding(0, dp(1), dp(6), 0);
            labelCopy.addView(categoryView);
        }
        labels.addView(labelCopy, new LinearLayout.LayoutParams(0, -2, 1f));
        labels.addView(environmentBadge(
                value < 0 ? "No index" : "UPI " + value, pollenColor(value)));
        block.addView(labels);

        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(-1, dp(4));
        trackLp.topMargin = dp(6);
        block.addView(environmentSeverityTrack(value, 5, pollenColor(value)), trackLp);
        block.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        block.setContentDescription(name + ", "
                + (value < 0 ? "pollen index unavailable" : "Universal Pollen Index " + value)
                + (category == null || category.isEmpty() ? "" : ", " + category));
        makeChildrenUnimportant(block);
        return block;
    }

    View pollenPlantRow(String name, int value, String category, boolean inSeason) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(text(name, 11, true, WHITE));
        if (inSeason) {
            TextView season = text("In season", 9, false, FAINT_WHITE);
            season.setPadding(0, dp(1), 0, 0);
            copy.addView(season);
        }
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

        String level = value < 0 ? "Index unavailable" : "UPI " + value;
        if (category != null && !category.isEmpty()) level += " · " + category;
        LinearLayout levelLine = new LinearLayout(this);
        levelLine.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(roundedBg(pollenColor(value), dp(4)));
        levelLine.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));
        TextView levelView = text(level, 10, false, SOFT_WHITE);
        levelView.setGravity(Gravity.END);
        levelView.setMaxLines(2);
        LinearLayout.LayoutParams levelLp = new LinearLayout.LayoutParams(-2, -2);
        levelLp.leftMargin = dp(5);
        levelLine.addView(levelView, levelLp);
        row.addView(levelLine);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        row.setContentDescription(name + (inSeason ? ", in season" : "") + ", " + level);
        makeChildrenUnimportant(row);
        return row;
    }

    View environmentInset(String titleValue, String bodyValue) {
        LinearLayout inset = new LinearLayout(this);
        inset.setOrientation(LinearLayout.HORIZONTAL);
        inset.setGravity(Gravity.TOP);
        inset.setPadding(0, dp(10), 0, 0);
        int accent = environmentAccentColor();
        View rail = new View(this);
        rail.setBackground(roundedBg(accent, dp(2)));
        inset.addView(rail, new LinearLayout.LayoutParams(dp(3), -1));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(titleValue, 10, true, accent);
        copy.addView(title);
        TextView body = text(bodyValue, 11, false, SOFT_WHITE);
        body.setLineSpacing(0f, 1.08f);
        body.setPadding(0, dp(3), 0, 0);
        copy.addView(body);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, -2, 1f);
        copyLp.leftMargin = dp(9);
        inset.addView(copy, copyLp);
        inset.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        inset.setContentDescription(titleValue + ". " + bodyValue);
        makeChildrenUnimportant(inset);
        return inset;
    }

    TextView environmentBadge(String value, int color) {
        TextView badge = text(value, 10, true, WHITE);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(9), dp(4), dp(9), dp(4));
        GradientDrawable background = roundedBg(Color.argb(
                78, Color.red(color), Color.green(color), Color.blue(color)), dp(13));
        badge.setBackground(background);
        badge.setContentDescription(value);
        return badge;
    }

    TextView sectionHeading(String value) {
        TextView heading = text(value, 10, true, environmentAccentColor());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) heading.setLetterSpacing(0.08f);
        heading.setAccessibilityHeading(true);
        heading.setPadding(dp(1), dp(13), 0, dp(7));
        return heading;
    }

    TextView environmentMiniHeading(String value) {
        TextView heading = text(value, 9, true, environmentAccentColor());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) heading.setLetterSpacing(0.08f);
        heading.setAccessibilityHeading(true);
        heading.setPadding(0, dp(11), 0, dp(2));
        return heading;
    }

    View environmentStatusLine(String value, int color, int textSizeSp, boolean bold) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(roundedBg(color, dp(5)));
        line.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        TextView label = text(value, textSizeSp, bold, WHITE);
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, -2, 1f);
        labelLp.leftMargin = dp(6);
        line.addView(label, labelLp);
        return line;
    }

    View environmentSeverityTrack(int value, int maximum, int color) {
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(roundedBg(Color.argb(28, 255, 255, 255), dp(3)));
        int max = Math.max(1, maximum);
        int clamped = Math.max(0, Math.min(max, value));
        View fill = new View(this);
        fill.setBackground(roundedBg(color, dp(3)));
        track.addView(fill, new LinearLayout.LayoutParams(0, -1, clamped));
        View remainder = new View(this);
        track.addView(remainder, new LinearLayout.LayoutParams(
                0, -1, Math.max(0.001f, max - clamped)));
        track.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return track;
    }

    View environmentDivider() {
        View divider = new View(this);
        divider.setBackground(roundedBg(Color.argb(28, 255, 255, 255), dp(1)));
        divider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        divider.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return divider;
    }

    int environmentAccentColor() {
        String scene = displayedScene == null ? "" : displayedScene;
        if (scene.trim().isEmpty()) scene = settingsSceneKey(lastCurrentWeather, displayedDaytime);
        return settingsAccent(scene);
    }

    int airQualityColor(JSONObject index) {
        int value = index == null ? -1 : index.optInt("aqi", -1);
        if (value < 0) return Color.rgb(157, 177, 196);
        if (value <= 20) return Color.rgb(74, 222, 128);
        if (value <= 40) return Color.rgb(163, 230, 53);
        if (value <= 60) return Color.rgb(250, 204, 21);
        if (value <= 80) return Color.rgb(251, 146, 60);
        if (value <= 100) return Color.rgb(248, 113, 113);
        return Color.rgb(192, 132, 252);
    }

    int pollenColor(int value) {
        if (value < 0) return Color.rgb(148, 163, 184);
        if (value <= 1) return Color.rgb(74, 222, 128);
        if (value == 2) return Color.rgb(163, 230, 53);
        if (value == 3) return Color.rgb(250, 204, 21);
        if (value == 4) return Color.rgb(251, 146, 60);
        return Color.rgb(248, 113, 113);
    }

    View environmentDetailBlock(String titleValue, String bodyValue) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(12), dp(10), dp(12), dp(10));
        block.setBackground(newGlassDrawable(dp(15), true));
        block.addView(text(titleValue, 12, true, WHITE));
        TextView body = text(bodyValue, 11, false, SOFT_WHITE);
        body.setLineSpacing(0f, 1.08f);
        body.setPadding(0, dp(3), 0, 0);
        block.addView(body);
        block.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        block.setContentDescription(titleValue + ". " + bodyValue);
        makeChildrenUnimportant(block);
        return block;
    }

    static JSONObject universalAqiIndex(JSONArray indexes) {
        if (indexes == null) return null;
        for (int i = 0; i < indexes.length(); i++) {
            JSONObject candidate = indexes.optJSONObject(i);
            if (candidate == null) continue;
            if ("uaqi".equalsIgnoreCase(stringValue(candidate, "code"))) return candidate;
        }
        return null;
    }

    String formatEnvironmentalHour(String value, ZoneId zone) {
        Instant instant = parseInstant(value);
        if (instant == null) return "";
        try {
            return DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())
                    .format(instant.atZone(zone));
        } catch (Exception ignored) {
            return "";
        }
    }

    String pollenDateLabel(JSONObject date, int index) {
        if (date != null) {
            try {
                LocalDate local = LocalDate.of(
                        date.optInt("year"), date.optInt("month"), date.optInt("day"));
                return DateTimeFormatter.ofPattern("EEEE d MMM", Locale.getDefault()).format(local);
            } catch (Exception ignored) { }
        }
        return index == 0 ? "Today" : "Day " + (index + 1);
    }

    void showOptionalDataHelpDialog(
            String label, boolean airQuality, OptionalDataState state) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        ScrollView scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(20), dp(20), dp(18));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(250, 12, 23, 39));
        background.setCornerRadius(dp(24));
        background.setStroke(dp(1), Color.argb(58, 255, 255, 255));
        panel.setBackground(background);
        scroller.addView(panel, new ScrollView.LayoutParams(-1, -2));
        scroller.setAccessibilityPaneTitle(label + " help");

        boolean keyBlocked = state != null && "Key blocked".equals(state.value);
        TextView title = text(keyBlocked ? label + " needs key access" : label + " help",
                22, false, WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(title);

        String message = keyBlocked
                ? "Google returned API_KEY_SERVICE_BLOCKED. The service is either not enabled "
                        + "for this key's project or this credential is not allowed to call it.\n\n"
                        + "Fix it in Google Cloud:\n"
                        + "1. Open APIs & Services → Credentials.\n"
                        + "2. Select this API key.\n"
                        + "3. Under API restrictions, choose Restrict key.\n"
                        + "4. Enable the needed APIs, then add Weather API, Air Quality API, and Pollen API under API restrictions.\n"
                        + "5. Save, wait a few minutes, then pull down to refresh."
                : "No usable data was returned. Check that the API is enabled, allowed in this "
                        + "key's API restrictions, and available for the selected location. Then refresh.";
        TextView explanation = text(message, 13, false, SOFT_WHITE);
        explanation.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams explanationLp = new LinearLayout.LayoutParams(-1, -2);
        explanationLp.topMargin = dp(7);
        panel.addView(explanation, explanationLp);

        TextView identity = text(androidRestrictionIdentity(), 11, false, FAINT_WHITE);
        identity.setTextIsSelectable(true);
        LinearLayout.LayoutParams identityLp = new LinearLayout.LayoutParams(-1, -2);
        identityLp.topMargin = dp(12);
        panel.addView(identity, identityLp);

        Button credentials = coordinateDialogButton("Open key settings ↗", false);
        credentials.setOnClickListener(v -> openExternalUrl(GOOGLE_CLOUD_CREDENTIALS_URL));
        LinearLayout.LayoutParams credentialsLp = new LinearLayout.LayoutParams(-1, dp(46));
        credentialsLp.topMargin = dp(14);
        panel.addView(credentials, credentialsLp);

        Button docs = coordinateDialogButton("Open " + label + " setup guide ↗", false);
        docs.setOnClickListener(v -> openExternalUrl(
                airQuality ? AIR_QUALITY_GUIDE_URL : POLLEN_GUIDE_URL));
        LinearLayout.LayoutParams docsLp = new LinearLayout.LayoutParams(-1, dp(46));
        docsLp.topMargin = dp(8);
        panel.addView(docs, docsLp);

        Button close = coordinateDialogButton("Done", true);
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(-1, dp(46));
        closeLp.topMargin = dp(12);
        panel.addView(close, closeLp);

        dialog.setContentView(scroller);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.76f;
            window.setAttributes(attributes);
            window.setGravity(Gravity.CENTER);
            window.getDecorView().setPadding(0, 0, 0, 0);
            int width = getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.max(1, Math.min(width - dp(30), dp(430))),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    LinearLayout tileRow(View a, View b, View c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        addWeightedTile(row, a, 0, dp(4));
        addWeightedTile(row, b, dp(4), dp(4));
        addWeightedTile(row, c, dp(4), 0);
        return row;
    }

    void addWeightedTile(LinearLayout row, View tile, int left, int right) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(138), 1);
        lp.leftMargin = left;
        lp.rightMargin = right;
        row.addView(tile, lp);
    }

    View detailTile(String label, String value, String glyphName) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.START);
        box.setPadding(dp(14), dp(15), dp(10), dp(12));
        box.setBackground(newGlassDrawable(dp(20), true));

        DetailGlyphView icon = new DetailGlyphView(this, glyphName);
        box.addView(icon, new LinearLayout.LayoutParams(dp(31), dp(31)));

        TextView l = text(label, 12, false, SOFT_WHITE);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(-1, -2);
        lLp.topMargin = dp(9);
        box.addView(l, lLp);

        TextView v = text(value, 17, false, WHITE);
        v.setMaxLines(2);
        v.setIncludeFontPadding(false);
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(-1, -2);
        vLp.topMargin = dp(3);
        box.addView(v, vLp);
        box.setContentDescription(label + ", " + (value == null || value.isEmpty() ? "unavailable" : value));
        return box;
    }

    void addSunCard(JSONArray days, JSONObject current, ZoneId zone) {
        Instant currentTime = parseInstant(current == null ? null : current.optString("currentTime", null));
        if (currentTime == null) currentTime = Instant.now();

        LocalDate currentDate;
        try {
            currentDate = currentTime.atZone(zone).toLocalDate();
        } catch (Exception ignored) {
            currentDate = LocalDate.now();
        }

        int currentIndex = findForecastDayIndex(days, currentDate, zone);
        if (currentIndex < 0 && days != null && days.length() > 0) currentIndex = 0;
        JSONObject currentDay = days == null || currentIndex < 0
                ? null : days.optJSONObject(currentIndex);
        Instant sunrise = sunEvent(currentDay, "sunriseTime");
        Instant sunset = sunEvent(currentDay, "sunsetTime");

        Instant nextSunrise = null;
        LocalDate nextSunriseDate = null;
        if (days != null) {
            for (int i = Math.max(0, currentIndex + 1); i < days.length(); i++) {
                JSONObject day = days.optJSONObject(i);
                Instant candidate = sunEvent(day, "sunriseTime");
                if (candidate != null && candidate.isAfter(currentTime)) {
                    nextSunrise = candidate;
                    nextSunriseDate = displayDate(day, zone);
                    if (nextSunriseDate == null) {
                        try {
                            nextSunriseDate = candidate.atZone(zone).toLocalDate();
                        } catch (Exception ignored) { }
                    }
                    break;
                }
            }
        }

        if (sunrise == null && sunset == null && nextSunrise == null) return;

        String nextLabel;
        Instant nextEvent;
        LocalDate nextEventDate;
        if (sunrise != null && currentTime.isBefore(sunrise)) {
            nextLabel = "Sunrise";
            nextEvent = sunrise;
            nextEventDate = currentDate;
        } else if (sunset != null && currentTime.isBefore(sunset)) {
            nextLabel = "Sunset";
            nextEvent = sunset;
            nextEventDate = currentDate;
        } else if (nextSunrise != null) {
            nextLabel = "Sunrise";
            nextEvent = nextSunrise;
            nextEventDate = nextSunriseDate;
        } else if (sunset != null && !currentTime.isBefore(sunset)) {
            nextLabel = "Sunrise";
            nextEvent = null;
            nextEventDate = currentDate.plusDays(1);
        } else {
            nextLabel = sunrise != null ? "Sunrise" : "Sunset";
            nextEvent = sunrise != null ? sunrise : sunset;
            nextEventDate = currentDate;
        }

        Instant trackStart = null;
        Instant trackEnd = null;
        boolean moonTrack = false;
        if (sunrise != null && sunset != null
                && !currentTime.isBefore(sunrise) && currentTime.isBefore(sunset)) {
            trackStart = sunrise;
            trackEnd = sunset;
        } else if (sunset != null && nextSunrise != null && !currentTime.isBefore(sunset)) {
            trackStart = sunset;
            trackEnd = nextSunrise;
            moonTrack = true;
        } else if (sunrise != null && currentTime.isBefore(sunrise)) {
            trackEnd = sunrise;
            if (sunset != null) {
                trackStart = sunset.minus(Duration.ofDays(1));
            } else {
                trackStart = sunrise.minus(Duration.ofHours(12));
            }
            moonTrack = true;
        } else if (sunrise != null && sunset != null) {
            trackStart = sunrise;
            trackEnd = sunset;
        }

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(16), dp(18), dp(18));

        TextView eyebrow = text("Next solar event", 12, true, SOFT_WHITE);
        body.addView(eyebrow);

        LinearLayout prominent = new LinearLayout(this);
        prominent.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams prominentLp = new LinearLayout.LayoutParams(-1, -2);
        prominentLp.topMargin = dp(3);
        body.addView(prominent, prominentLp);

        LinearLayout eventLabels = new LinearLayout(this);
        eventLabels.setOrientation(LinearLayout.VERTICAL);
        TextView eventName = text(nextLabel, 20, true, WHITE);
        eventLabels.addView(eventName);
        String context = solarDateContext(nextEventDate, currentDate, zone);
        TextView eventContext = text(context, 11, false, FAINT_WHITE);
        if (!context.isEmpty()) {
            LinearLayout.LayoutParams contextLp = new LinearLayout.LayoutParams(-1, -2);
            contextLp.topMargin = dp(1);
            eventLabels.addView(eventContext, contextLp);
        }
        prominent.addView(eventLabels, new LinearLayout.LayoutParams(0, -2, 1));

        TextView eventTime = text(formatTime(nextEvent, zone), 27, false, WHITE);
        eventTime.setGravity(Gravity.END);
        eventTime.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        eventTime.setContentDescription(
                nextLabel + " " + formatTime(nextEvent, zone)
                        + (context.isEmpty() ? "" : ", " + context));
        prominent.addView(eventTime, new LinearLayout.LayoutParams(dp(112), -2));

        if (trackStart != null && trackEnd != null && trackEnd.isAfter(trackStart)) {
            boolean animate = animationsAllowed();
            SunTrackView track = new SunTrackView(
                    this, trackStart, trackEnd, currentTime, moonTrack, animate);
            sunTrackViews.add(track);
            LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(-1, dp(54));
            trackLp.topMargin = dp(9);
            body.addView(track, trackLp);
        }

        String staticTimes = solarStaticText(
                sunrise, sunset, nextSunrise, currentTime, zone);
        if (!staticTimes.isEmpty()) {
            TextView times = text(staticTimes, 12, false, SOFT_WHITE);
            times.setGravity(Gravity.CENTER_HORIZONTAL);
            times.setContentDescription(staticTimes);
            LinearLayout.LayoutParams timesLp = new LinearLayout.LayoutParams(-1, -2);
            timesLp.topMargin = dp(6);
            body.addView(times, timesLp);
        }

        LinearLayout.LayoutParams lp = defaultCardParams();
        lp.topMargin = dp(12);
        View card = card(body, dp(24), cardColor);
        card.setLayoutParams(lp);
        pageContent().addView(card);
    }

    int findForecastDayIndex(JSONArray days, LocalDate target, ZoneId zone) {
        if (days == null || target == null) return -1;
        for (int i = 0; i < days.length(); i++) {
            if (target.equals(displayDate(days.optJSONObject(i), zone))) return i;
        }
        return -1;
    }

    static Instant sunEvent(JSONObject day, String key) {
        if (day == null) return null;
        JSONObject sunEvents = day.optJSONObject("sunEvents");
        return sunEvents == null ? null : parseInstant(sunEvents.optString(key, null));
    }

    static String solarDateContext(LocalDate eventDate, LocalDate currentDate, ZoneId zone) {
        if (eventDate == null || currentDate == null) return "";
        if (eventDate.equals(currentDate)) return "Today";
        if (eventDate.equals(currentDate.plusDays(1))) return "Tomorrow";
        try {
            return eventDate.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()));
        } catch (Exception ignored) {
            return "";
        }
    }

    static String solarStaticText(
            Instant sunrise,
            Instant sunset,
            Instant nextSunrise,
            Instant currentTime,
            ZoneId zone) {
        if (currentTime != null && sunset != null && !currentTime.isBefore(sunset)
                && nextSunrise != null) {
            return "Sunset " + formatTime(sunset, zone)
                    + "  •  Sunrise tomorrow " + formatTime(nextSunrise, zone);
        }
        if (sunrise != null && sunset != null) {
            return "Sunrise " + formatTime(sunrise, zone)
                    + "  •  Sunset " + formatTime(sunset, zone);
        }
        if (sunrise != null) return "Sunrise " + formatTime(sunrise, zone);
        if (sunset != null) return "Sunset " + formatTime(sunset, zone);
        if (nextSunrise != null) return "Sunrise tomorrow " + formatTime(nextSunrise, zone);
        return "";
    }

    void addMoonCard(JSONObject today, ZoneId zone) {
        JSONObject moon = today == null ? null : today.optJSONObject("moonEvents");
        if (moon == null) return;

        String phase = moon.optString("moonPhase", "");
        Instant rise = firstInstant(moon.optJSONArray("moonriseTimes"));
        Instant set = firstInstant(moon.optJSONArray("moonsetTimes"));
        if (phase.isEmpty() && rise == null && set == null) return;

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(18), dp(17), dp(14), dp(17));

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.addView(text(prettyPhase(phase), 14, false, SOFT_WHITE));

        if (rise != null) {
            textColumn.addView(moonEventRow("Moonrise", formatTime(rise, zone)));
        }
        if (set != null) {
            textColumn.addView(moonEventRow("Moonset", formatTime(set, zone)));
        }
        body.addView(textColumn, new LinearLayout.LayoutParams(0, -2, 1));

        MoonPhaseView moonView = new MoonPhaseView(this, phase);
        body.addView(moonView, new LinearLayout.LayoutParams(dp(110), dp(110)));

        LinearLayout.LayoutParams lp = defaultCardParams();
        lp.topMargin = dp(12);
        View card = card(body, dp(24), cardColor);
        card.setLayoutParams(lp);
        pageContent().addView(card);
    }

    View moonEventRow(String label, String time) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(13), dp(2), 0);
        TextView l = text(label, 16, false, WHITE);
        TextView t = text(time, 16, false, WHITE);
        t.setGravity(Gravity.END);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(t, new LinearLayout.LayoutParams(dp(76), -2));
        return row;
    }

    void addAttribution() {
        StringBuilder source = new StringBuilder("Source: Includes weather data from Google");
        if (airQualityEnabled()) {
            source.append("\nSource: Includes air quality data from Google");
        }
        if (pollenEnabled()) {
            source.append("\nSource: Includes pollen data from Google");
        }
        if (airQualityEnabled() || pollenEnabled()) {
            source.append("\nGoogle Maps");
        }
        TextView attribution = text(source.toString(), 12, false, FAINT_WHITE);
        attribution.setGravity(Gravity.CENTER);
        attribution.setPadding(dp(4), dp(24), dp(4), dp(10));
        pageContent().addView(attribution);
    }


    void makeChildrenUnimportant(ViewGroup parent) {
        if (parent == null) return;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (child instanceof ViewGroup) makeChildrenUnimportant((ViewGroup) child);
        }
    }

    SceneSpec sceneForForecastDay(JSONObject day, int index) {
        if (day == null) return forecastPreview.currentScene();
        boolean currentDaytime = safeBoolean(lastCurrentWeather, "isDaytime", true);
        JSONObject daytime = day.optJSONObject("daytimeForecast");
        JSONObject nighttime = day.optJSONObject("nighttimeForecast");
        JSONObject selected;
        boolean fallbackDaytime;
        if (index == 0) {
            if (currentDaytime) {
                selected = daytime != null ? daytime : nighttime;
                fallbackDaytime = daytime != null;
            } else {
                selected = nighttime != null ? nighttime : daytime;
                fallbackDaytime = nighttime == null;
            }
        } else {
            selected = daytime != null ? daytime : nighttime;
            fallbackDaytime = daytime != null;
        }
        return selected == null
                ? forecastPreview.currentScene()
                : SceneSpec.fromWeather(selected, fallbackDaytime);
    }

    String dayPreviewKey(int index) {
        return "day:" + index;
    }

    String hourPreviewKey(JSONObject hour) {
        String identity = hourlyIdentity(hour);
        return identity.isEmpty() ? "hour:" + System.identityHashCode(hour) : "hour:" + identity;
    }

    String hourPreviewLabel(JSONObject hour, ZoneId zone) {
        LocalDate date = hourLocalDate(hour, zone);
        String day = "";
        if (date != null) {
            try {
                day = date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()));
            } catch (Exception ignored) { }
        }
        String time = hourLabel(hour, zone);
        return day.isEmpty() ? time : day + " " + time;
    }

    void configureHourPreviewCell(
            LinearLayout cell,
            JSONObject hour,
            ZoneId zone,
            boolean nowCell,
            String detailDescription) {
        if (cell == null) return;
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setBackground(previewTargetBackground(dp(14)));
        makeChildrenUnimportant(cell);
        String condition = description(hour);
        if (nowCell) {
            cell.setContentDescription((detailDescription == null ? "Now" : detailDescription)
                    + ", " + condition + ". Tap to restore current weather.");
            cell.setOnClickListener(v -> forecastPreview.restore(true));
            applyPreviewSelectionVisual(cell, false);
            return;
        }
        String key = hourPreviewKey(hour);
        String label = hourPreviewLabel(hour, zone);
        SceneSpec scene = SceneSpec.fromWeather(hour, safeBoolean(hour, "isDaytime", true));
        cell.setContentDescription((detailDescription == null ? label : detailDescription)
                + ", " + condition + ". Tap to preview this hour; tap again to return to now.");
        cell.setOnClickListener(v -> forecastPreview.select(key, label, scene));
        forecastPreview.registerTarget(cell, key);
    }

    void applyScenePalette(String scene) {
        if ("night".equals(scene)) {
            cardColor = Color.argb(104, 18, 47, 96);
            tileColor = Color.argb(90, 18, 51, 104);
            glassCardTop = Color.argb(102, 18, 44, 84);
            glassCardBottom = Color.argb(58, 8, 24, 52);
            glassTileTop = Color.argb(90, 20, 48, 90);
            glassTileBottom = Color.argb(48, 9, 27, 56);
            glassEdge = Color.TRANSPARENT;
        } else if ("rain".equals(scene)) {
            cardColor = Color.argb(104, 40, 65, 87);
            tileColor = Color.argb(90, 42, 69, 92);
            glassCardTop = Color.argb(96, 49, 70, 89);
            glassCardBottom = Color.argb(54, 23, 40, 56);
            glassTileTop = Color.argb(84, 51, 73, 94);
            glassTileBottom = Color.argb(46, 24, 43, 60);
            glassEdge = Color.TRANSPARENT;
        } else if ("snow".equals(scene)) {
            cardColor = Color.argb(98, 67, 101, 132);
            tileColor = Color.argb(84, 70, 106, 139);
            glassCardTop = Color.argb(90, 82, 112, 138);
            glassCardBottom = Color.argb(50, 52, 80, 105);
            glassTileTop = Color.argb(78, 84, 116, 144);
            glassTileBottom = Color.argb(44, 54, 83, 110);
            glassEdge = Color.TRANSPARENT;
        } else {
            cardColor = Color.argb(86, 38, 103, 190);
            tileColor = Color.argb(68, 42, 103, 181);
            glassCardTop = Color.argb(86, 72, 132, 205);
            glassCardBottom = Color.argb(48, 72, 118, 174);
            glassTileTop = Color.argb(74, 73, 133, 198);
            glassTileBottom = Color.argb(42, 67, 112, 167);
            glassEdge = Color.TRANSPARENT;
        }
        for (GlassDrawable drawable : glassDrawables) {
            applyGlassPalette(drawable);
        }
        if (refreshIndicator != null) {
            refreshIndicator.setPalette(glassCardTop, glassCardBottom, settingsAccent(scene));
        }
    }

    void applyGlassPalette(GlassDrawable drawable) {
        if (drawable == null) return;
        if (drawable.isTile()) {
            drawable.setColors(glassTileTop, glassTileBottom, glassEdge);
        } else {
            drawable.setColors(glassCardTop, glassCardBottom, glassEdge);
        }
    }

    void showError(Exception e) {
        clearDynamicContent();
        progress.setVisibility(View.GONE);
        status.setText("Could not load forecast");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(18), dp(18), dp(18));

        String message;
        if (e instanceof WeatherRequestException) {
            message = e.getMessage();
        } else if (e instanceof IllegalStateException
                && "API key is not configured".equals(e.getMessage())) {
            message = "API key is not configured";
        } else {
            message = "Weather data could not be loaded. Please retry.";
        }
        if (message == null || message.trim().isEmpty()) {
            message = "Weather data could not be loaded. Please retry.";
        }
        box.addView(text(message, 14, false, WHITE));

        Button retry = button("Retry");
        retry.setOnClickListener(v -> refreshWeather(true));
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(-2, dp(44));
        retryLp.topMargin = dp(14);
        box.addView(retry, retryLp);

        globalErrorView = card(box, dp(24), cardColor);
        content.addView(globalErrorView);
    }

    void clearDynamicContent() {
        if (globalErrorView != null) {
            content.removeView(globalErrorView);
            globalErrorView = null;
        }
        if (overviewPageContent != null) overviewPageContent.removeAllViews();
        if (precipitationPageContent != null) precipitationPageContent.removeAllViews();
        activePageContent = precipitationMode ? precipitationPageContent : overviewPageContent;
        glassDrawables.clear();
        if (modeSwitchGlass != null) {
            applyGlassPalette(modeSwitchGlass);
            glassDrawables.add(modeSwitchGlass);
        }
        sunTrackViews.clear();
        precipitationBody = null;
    }

    View card(View child, int radiusPx, int color) {
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setBackground(newGlassDrawable(radiusPx, false));
        holder.addView(child, new LinearLayout.LayoutParams(-1, -2));
        return holder;
    }

    GlassDrawable newGlassDrawable(int radiusPx, boolean tile) {
        GlassDrawable drawable = new GlassDrawable(
                radiusPx,
                Math.max(1f, getResources().getDisplayMetrics().density),
                tile);
        applyGlassPalette(drawable);
        glassDrawables.add(drawable);
        return drawable;
    }

    LinearLayout.LayoutParams defaultCardParams() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    GradientDrawable roundedBg(int color, int radiusPx) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(radiusPx);
        return bg;
    }

    Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(WHITE);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setPadding(dp(15), 0, dp(15), 0);
        b.setBackground(roundedBg(Color.argb(36, 255, 255, 255), dp(22)));
        return b;
    }

    TextView text(String value, int sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }


    String windUnitPreference() {
        return weatherPreferences.windUnit(isFahrenheitUnit());
    }

    String pressureUnitPreference() {
        return weatherPreferences.pressureUnit();
    }

    String visibilityUnitPreference() {
        return weatherPreferences.visibilityUnit(isFahrenheitUnit());
    }

    static String normalizeWindUnit(String value) {
        return WeatherPreferences.normalizeWindUnit(value);
    }

    static String normalizePressureUnit(String value) {
        return WeatherPreferences.normalizePressureUnit(value);
    }

    static String normalizeVisibilityUnit(String value) {
        return WeatherPreferences.normalizeVisibilityUnit(value);
    }

    String formatWindSpeed(JSONObject speed) {
        Double kmh = speedKilometersPerHour(speed);
        if (kmh == null) return "—";
        String unit = windUnitPreference();
        double value;
        if (WIND_MPH.equals(unit)) value = kmh / 1.609344d;
        else if (WIND_MS.equals(unit)) value = kmh / 3.6d;
        else if (WIND_KNOTS.equals(unit)) value = kmh / 1.852d;
        else value = kmh;
        return trimNumber(value) + " " + unit;
    }

    Double speedKilometersPerHour(JSONObject speed) {
        Double value = numberValue(speed, "value");
        if (value == null) return null;
        String unit = safeUnitString(speed);
        if (unit.contains("MILE") || "MPH".equals(unit) || "MI/H".equals(unit)) {
            return value * 1.609344d;
        }
        if (unit.contains("METER") && unit.contains("SECOND")
                || "M/S".equals(unit) || "MPS".equals(unit)) {
            return value * 3.6d;
        }
        if (unit.contains("KNOT") || "KT".equals(unit) || "KTS".equals(unit)) {
            return value * 1.852d;
        }
        if (unit.contains("KILOMETER") || "KM/H".equals(unit) || "KPH".equals(unit)) {
            return value;
        }
        // Unit metadata is optional. WeatherNext always requests METRIC from Google Weather.
        return value;
    }

    String formatPressure(JSONObject pressure) {
        Double hpa = pressureHpa(pressure);
        if (hpa == null) return "—";
        String unit = pressureUnitPreference();
        if (PRESSURE_INHG.equals(unit)) {
            return String.format(Locale.getDefault(), "%.2f %s", hpa * 0.0295299830714d, unit);
        }
        if (PRESSURE_MMHG.equals(unit)) {
            return String.format(Locale.getDefault(), "%.1f %s", hpa * 0.750061683d, unit);
        }
        return Math.round(hpa) + " " + PRESSURE_HPA;
    }

    static Double pressureHpa(JSONObject pressure) {
        if (pressure == null) return null;
        Double value = numberValue(pressure, "meanSeaLevelMillibars");
        if (value != null) return value;
        value = numberValue(pressure, "meanSeaLevelHectopascals");
        if (value != null) return value;
        value = numberValue(pressure, "value");
        if (value == null) return null;
        String unit = safeUnitString(pressure);
        if (unit.contains("INHG") || unit.contains("INCH")) return value / 0.0295299830714d;
        if (unit.contains("MMHG") || unit.contains("MILLIMETER")) return value / 0.750061683d;
        if (unit.contains("KILOPASCAL") || "KPA".equals(unit)) return value * 10d;
        if ((unit.contains("PASCAL") || "PA".equals(unit))
                && !unit.contains("HECTO")) return value / 100d;
        return value;
    }

    String formatVisibility(JSONObject visibility) {
        Double km = visibilityKilometers(visibility);
        if (km == null) return "—";
        String unit = visibilityUnitPreference();
        double value = VISIBILITY_MI.equals(unit) ? km / 1.609344d : km;
        return trimNumber(value) + " " + unit;
    }

    Double visibilityKilometers(JSONObject visibility) {
        if (visibility == null) return null;
        Double value = numberValue(visibility, "distance");
        if (value == null) value = numberValue(visibility, "value");
        if (value == null) return null;
        String unit = safeUnitString(visibility);
        if (unit.contains("MILE") || "MI".equals(unit)) return value * 1.609344d;
        if (unit.contains("KILOMETER") || "KM".equals(unit)) return value;
        // Missing unit metadata follows the METRIC API request.
        return value;
    }

    static String safeUnitString(JSONObject object) {
        if (object == null) return "";
        String value = object.optString("unit", "");
        if (value == null) return "";
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    static int settingsAccent(String scene) {
        if ("night".equals(scene)) return Color.rgb(154, 202, 255);
        if ("thunder".equals(scene)) return Color.rgb(176, 213, 255);
        if ("rain".equals(scene) || "fog".equals(scene)) return Color.rgb(168, 218, 244);
        if ("snow".equals(scene)) return Color.rgb(220, 242, 255);
        return Color.rgb(151, 211, 255);
    }

    static String settingsSceneKey(JSONObject current, boolean daytime) {
        String key = conditionKey(description(current));
        if ("thunder".equals(key) || "rain".equals(key)
                || "fog".equals(key) || "snow".equals(key)) {
            return key;
        }
        return daytime ? "day" : "night";
    }


    String dataAgeLabel(JSONObject current) {
        Instant published = parseInstant(current == null ? null : current.optString("currentTime", null));
        if (published == null) return "Google Weather data loaded";
        long minutes;
        try {
            minutes = Duration.between(published, Instant.now()).toMinutes();
        } catch (Exception ignored) {
            return "Google Weather data loaded";
        }
        if (minutes < 0) minutes = 0;
        if (minutes == 0) return "Data published just now";
        if (minutes == 1) return "Data published 1 minute ago";
        return "Data published " + minutes + " minutes ago";
    }


}
