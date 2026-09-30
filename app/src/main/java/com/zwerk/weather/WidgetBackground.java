package com.zwerk.weather;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.widget.RemoteViews;

/** The same custom colour and alpha gradient for the picker and the native widget shape. */
final class WidgetBackground {
    static final int DEFAULT_COLOUR = Color.rgb(26, 92, 165);

    private WidgetBackground() { }

    static int[] colours(int colour, boolean gradient) {
        int top = colour | 0xff000000;
        int bottom = gradient ? (top & 0x00ffffff) | 0xad000000 : top;
        return new int[]{top, bottom};
    }

    static void apply(Context context, RemoteViews views, int sceneId,
            String conditionType, boolean daytime) {
        SharedPreferences prefs = context.getSharedPreferences(
                WeatherPreferences.PREFS_NAME, Context.MODE_PRIVATE);
        int transparency = Math.max(0, Math.min(100,
                prefs.getInt(WeatherWidgetProvider.PREF_TRANSPARENCY, 15)));
        boolean gradient = prefs.getBoolean(WeatherWidgetProvider.PREF_GRADIENT, true);
        String colour = prefs.getString(WeatherWidgetProvider.PREF_COLOUR, "weather");
        String key = WeatherWidgetProvider.conditionKey(conditionType);
        int background = R.drawable.widget_background_blue;
        int top = DEFAULT_COLOUR;
        if ("dark".equals(colour)) {
            background = R.drawable.widget_background_dark;
            top = Color.rgb(29, 40, 57);
        } else if ("weather".equals(colour)) {
            if (!daytime) {
                background = R.drawable.widget_background_night;
                top = Color.rgb(27, 42, 73);
            } else if ("rain".equals(key) || "thunder".equals(key) || "fog".equals(key)) {
                background = R.drawable.widget_background_rain;
                top = Color.rgb(55, 78, 101);
            } else if ("snow".equals(key)) {
                background = R.drawable.widget_background_snow;
                top = Color.rgb(90, 120, 150);
            }
        }
        if ("custom".equals(colour)) {
            background = gradient ? R.drawable.widget_background_custom
                    : R.drawable.widget_background_blue;
            top = prefs.getInt(WeatherWidgetProvider.PREF_CUSTOM_COLOUR, DEFAULT_COLOUR)
                    | 0xff000000;
        }
        views.setImageViewResource(sceneId, background);
        views.setInt(sceneId, "setColorFilter",
                !"custom".equals(colour) && gradient ? Color.TRANSPARENT : top);
        views.setInt(sceneId, "setImageAlpha", Math.round(255f * (100 - transparency) / 100f));
    }
}
