package com.zwerk.weather;

import android.annotation.SuppressLint;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RemoteViews;

import java.util.List;

/** Renders the launcher widget at its requested size inside a non-interactive preview. */
@SuppressLint("ViewConstructor") // Created by the configuration screen with launcher dimensions.
final class WidgetConfigurationPreview extends ViewGroup {
    private final int widthDp;
    private final int heightDp;
    private final int widgetWidth;
    private final int widgetHeight;
    private final boolean rain;
    final WeatherWidgetProvider.WidgetMode weatherMode;

    WidgetConfigurationPreview(Context context, Bundle options, boolean rain,
            WeatherWidgetProvider.WidgetMode defaultMode, int defaultWidth, int defaultHeight) {
        super(context);
        this.rain = rain;
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        float width = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
        float height = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
        SizeF exactSize = launcherSize(options, width, height, landscape);
        if (exactSize != null) {
            width = exactSize.getWidth();
            height = exactSize.getHeight();
        }
        float displayWidth = width > 0 ? width : defaultWidth;
        float displayHeight = height > 0 ? height : defaultHeight;
        widthDp = Math.round(displayWidth);
        heightDp = Math.round(displayHeight);
        float density = getResources().getDisplayMetrics().density;
        widgetWidth = Math.round(displayWidth * density);
        widgetHeight = Math.round(displayHeight * density);
        if (height <= 0) {
            weatherMode = defaultMode;
        } else if (Build.VERSION.SDK_INT >= 31) {
            weatherMode = WeatherWidgetProvider.modeForSize(displayWidth, displayHeight);
        } else {
            weatherMode = WeatherWidgetProvider.modeForOptions(options);
        }
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @SuppressWarnings("deprecation")
    private static SizeF launcherSize(Bundle options, float width, float height, boolean landscape) {
        if (Build.VERSION.SDK_INT < 31) return null;
        List<SizeF> sizes = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
        if (sizes == null) return null;
        SizeF best = null;
        float nearest = Float.MAX_VALUE;
        for (SizeF size : sizes) {
            if (size == null || size.getWidth() <= 0 || size.getHeight() <= 0) continue;
            // The legacy portrait/landscape pair identifies the corresponding responsive size.
            float distance = width > 0 ? Math.abs(size.getWidth() - width)
                    : landscape ? -size.getWidth() : size.getWidth();
            if (height > 0) distance += Math.abs(size.getHeight() - height);
            if (distance < nearest) {
                nearest = distance;
                best = size;
            }
        }
        return best;
    }

    void render(WidgetConfiguration config) {
        Context context = getContext();
        CityManagerActivity.LocationSnapshot city = config.city(context);
        int previewId = AppWidgetManager.INVALID_APPWIDGET_ID;
        RemoteViews views;
        if (rain) {
            views = PrecipitationWidgetProvider.buildForSize(
                    context, widthDp, heightDp, previewId, config, city);
        } else {
            SharedPreferences snapshot = WidgetConfiguration.snapshot(context, city == null ? "" : city.id);
            views = WeatherWidgetProvider.buildMode(context, weatherMode, previewId, config, city, snapshot);
        }
        View widget = getChildAt(0);
        if (widget == null) {
            widget = views.apply(context, this);
            widget.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            addView(widget);
        } else {
            views.reapply(context, widget);
        }
        setContentDescription(widget.getContentDescription());
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(widgetWidth, widthMeasureSpec);
        int height = resolveSize(Math.round(widgetHeight * (width / (float) widgetWidth)),
                heightMeasureSpec);
        setMeasuredDimension(width, height);
        View widget = getChildAt(0);
        if (widget != null) {
            widget.measure(MeasureSpec.makeMeasureSpec(widgetWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(widgetHeight, MeasureSpec.EXACTLY));
        }
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        View widget = getChildAt(0);
        if (widget == null) return;
        widget.layout(0, 0, widgetWidth, widgetHeight);
        float scale = getWidth() / (float) widgetWidth;
        widget.setPivotX(0f);
        widget.setPivotY(0f);
        widget.setScaleX(scale);
        widget.setScaleY(scale);
    }
}
