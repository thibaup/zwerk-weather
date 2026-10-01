package com.zwerk.weather;

import android.annotation.TargetApi;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.RemoteViews;

final class WidgetPreviews {
    private WidgetPreviews() { }

    static void publish(Context context) {
        if (Build.VERSION.SDK_INT >= 35) Api35.publish(context);
    }

    @TargetApi(35)
    private static final class Api35 {
        static void publish(Context context) {
            Class<?>[] providers = {WeatherWidgetProvider.class, WeatherSmallWidgetProvider.class,
                    WeatherMiniWidgetProvider.class, PrecipitationWidgetProvider.class};
            int[] layouts = {R.layout.widget_weather_picker_preview, R.layout.widget_weather_small_picker_preview,
                    R.layout.widget_weather_mini_picker_preview, R.layout.widget_precipitation_picker_preview};
            SharedPreferences prefs = context.getSharedPreferences("WIDGET_PREVIEWS", Context.MODE_PRIVATE);
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            for (int i = 0; i < providers.length; i++) {
                String key = "image_" + providers[i].getSimpleName();
                if (prefs.getInt(key, 0) == BuildConfig.VERSION_CODE) continue;
                RemoteViews preview = new RemoteViews(context.getPackageName(), layouts[i]);
                try {
                    // Android limits preview publication; remaining entries use the bundled image.
                    if (!manager.setWidgetPreview(new ComponentName(context, providers[i]),
                            AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, preview)) return;
                    prefs.edit().putInt(key, BuildConfig.VERSION_CODE).apply();
                } catch (RuntimeException ignored) {
                    return;
                }
            }
        }
    }
}
