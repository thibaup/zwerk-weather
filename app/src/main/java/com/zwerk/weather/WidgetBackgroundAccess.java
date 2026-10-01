package com.zwerk.weather;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

final class WidgetBackgroundAccess {
    enum State { ALLOWED, RESTRICTED, OPTIMIZED, UNKNOWN }

    static final String PREF_CONFIRMED = "widget_background_confirmed";
    static final String PREF_LAST_SHOWN = "widget_background_last_shown";
    static final String PREF_LAST_STATE = "widget_background_last_state";
    static final long REMINDER_INTERVAL_MS = 24L * 60L * 60L * 1000L;

    private WidgetBackgroundAccess() { }

    static State state(Context context) {
        Boolean restricted = null;
        Boolean exempt = null;
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager != null) restricted = manager.isBackgroundRestricted();
        } catch (RuntimeException ignored) { }
        try {
            PowerManager manager = context.getSystemService(PowerManager.class);
            if (manager != null) exempt = manager.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (RuntimeException ignored) { }
        return state(restricted, exempt);
    }

    static State state(Boolean restricted, Boolean exempt) {
        if (Boolean.TRUE.equals(restricted)) return State.RESTRICTED;
        if (Boolean.TRUE.equals(exempt)) return State.ALLOWED;
        if (restricted == null || exempt == null) return State.UNKNOWN;
        return State.OPTIMIZED;
    }

    static boolean needsReminder(boolean hasWidgets, State state, boolean confirmed) {
        return hasWidgets && state != State.ALLOWED
                && (state == State.RESTRICTED || !confirmed);
    }

    static boolean needsReminder(Context context) {
        State state = state(context);
        SharedPreferences prefs = preferences(context);
        // OEM battery controls are not always exposed. Explicit Android restrictions still win.
        if (state == State.RESTRICTED && prefs.getBoolean(PREF_CONFIRMED, false)) {
            prefs.edit().remove(PREF_CONFIRMED).apply();
        }
        return needsReminder(WidgetRefreshManager.hasWidgets(context), state,
                prefs.getBoolean(PREF_CONFIRMED, false));
    }

    static boolean popupDue(Context context) {
        return needsReminder(context) && popupDue(state(context),
                preferences(context).getLong(PREF_LAST_SHOWN, 0L),
                preferences(context).getString(PREF_LAST_STATE, ""), System.currentTimeMillis());
    }

    static boolean popupDue(State state, long lastShown, String lastState, long now) {
        return lastShown == 0L || (state == State.RESTRICTED && !state.name().equals(lastState))
                || now - lastShown >= REMINDER_INTERVAL_MS
                || lastShown - now >= REMINDER_INTERVAL_MS;
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(WeatherPreferences.PREFS_NAME, Context.MODE_PRIVATE);
    }

    static boolean openSettings(Context context) {
        Intent[] options = {
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.getPackageName(), null)),
                new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS)
        };
        for (Intent intent : options) {
            try {
                if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return true;
            } catch (ActivityNotFoundException | SecurityException ignored) { }
        }
        return false;
    }

    static Dialog showDialog(Activity activity, Runnable dismissed) {
        if (activity.isFinishing() || activity.isDestroyed() || !needsReminder(activity)) return null;
        Dialog dialog = new Dialog(activity);
        dialog.setOwnerActivity(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(activity, 22), dp(activity, 22), dp(activity, 22), dp(activity, 14));
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(22, 48, 77), Color.rgb(11, 25, 43)});
        background.setCornerRadius(dp(activity, 24));
        background.setStroke(dp(activity, 1), Color.argb(65, 255, 255, 255));
        scroll.setBackground(background);
        scroll.addView(panel);
        TextView title = new TextView(activity);
        title.setText(R.string.widget_background_title);
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        panel.addView(title);
        TextView message = new TextView(activity);
        message.setText(R.string.widget_background_message);
        message.setTextSize(15);
        message.setTextColor(Color.rgb(218, 230, 242));
        message.setPadding(0, dp(activity, 12), 0, dp(activity, 20));
        panel.addView(message);
        addButton(activity, panel, R.string.widget_background_settings, true, () -> {
            boolean main = activity instanceof MainActivity;
            if (main) ((MainActivity) activity).suppressNextResumeWeatherLoad = true;
            if (openSettings(activity)) dialog.dismiss();
            else {
                if (main) ((MainActivity) activity).suppressNextResumeWeatherLoad = false;
                Toast.makeText(activity, R.string.widget_background_unavailable, Toast.LENGTH_LONG).show();
            }
        });
        if (state(activity) != State.RESTRICTED) {
            addButton(activity, panel, R.string.widget_background_confirmed, false, () -> {
                if (state(activity) != State.RESTRICTED) {
                    preferences(activity).edit().putBoolean(PREF_CONFIRMED, true).apply();
                }
                dialog.dismiss();
            });
        }
        addButton(activity, panel, R.string.widget_background_later, false, dialog::dismiss);
        dialog.setOnDismissListener(ignored -> dismissed.run());
        dialog.setContentView(scroll);
        dialog.show();
        preferences(activity).edit().putLong(PREF_LAST_SHOWN, System.currentTimeMillis())
                .putString(PREF_LAST_STATE, state(activity).name()).apply();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.getDecorView().setPadding(0, 0, 0, 0);
            window.setLayout(Math.max(1, Math.min(activity.getResources().getDisplayMetrics().widthPixels
                    - dp(activity, 32), dp(activity, 420))), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    private static void addButton(Activity activity, LinearLayout panel, int label,
            boolean primary, Runnable action) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(primary ? Color.rgb(11, 25, 43) : Color.WHITE);
        button.setMinHeight(dp(activity, 48));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(activity, 14));
        background.setColor(primary ? Color.rgb(184, 222, 255) : Color.TRANSPARENT);
        button.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(activity, 6);
        panel.addView(button, params);
        button.setOnClickListener(v -> action.run());
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
