package com.zwerk.weather;

import android.os.Build;
import android.view.View;

import java.lang.reflect.Method;

/** Optional Oplus compositor integration. Platform effects stay isolated from API 28. */
final class OemBackdropBlur {
    private OemBackdropBlur() { }

    static boolean apply(View view, Object effect) {
        if (Build.VERSION.SDK_INT < 31 || BuildConfig.FORCE_GENERIC_RADAR_BLUR) return false;
        return Api31.apply(view, effect);
    }

    static void clear(View view) {
        if (Build.VERSION.SDK_INT >= 31) Api31.apply(view, null);
    }

    @android.annotation.TargetApi(31)
    private static final class Api31 {
        private static Method setter;
        private static boolean resolved;

        static boolean apply(View view, Object effect) {
            if (!resolved) {
                resolved = true;
                try {
                    setter = Class.forName("com.oplus.view.OplusViewBackgroundRenderEffect")
                            .getMethod("setBackgroundRenderEffect", android.graphics.RenderEffect.class, View.class);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError | OutOfMemoryError unavailable) {
                    setter = null;
                }
            }
            if (setter == null || view == null) return false;
            try {
                setter.invoke(null, effect, view);
                return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError | OutOfMemoryError unavailable) {
                return false;
            }
        }
    }
}
