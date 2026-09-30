package com.zwerk.weather;

import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/** Shared system-bar appearance and safe overlay bounds for the app and radar dialog. */
final class WeatherSystemBars {
    private WeatherSystemBars() { }

    static void applyAppearance(Window window) {
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        }
    }

    static Rect safeInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            return new Rect(bars.left, bars.top, bars.right, bars.bottom);
        }
        DisplayCutout cutout = insets.getDisplayCutout();
        return new Rect(
                Math.max(insets.getSystemWindowInsetLeft(), cutout == null ? 0 : cutout.getSafeInsetLeft()),
                Math.max(insets.getSystemWindowInsetTop(), cutout == null ? 0 : cutout.getSafeInsetTop()),
                Math.max(insets.getSystemWindowInsetRight(), cutout == null ? 0 : cutout.getSafeInsetRight()),
                Math.max(insets.getSystemWindowInsetBottom(), cutout == null ? 0 : cutout.getSafeInsetBottom()));
    }
}
