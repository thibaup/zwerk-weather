package com.zwerk.weather;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shared translucent dark-glass dialog matching Zwerk Weather's UI. */
final class GlassDialog {
    private GlassDialog() { }

    static Dialog showConfirm(
            Activity activity,
            String title,
            String message,
            String negativeLabel,
            String positiveLabel,
            boolean destructive,
            Runnable onConfirm) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;

        Dialog dialog = new Dialog(activity);
        dialog.setOwnerActivity(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(activity, 22), dp(activity, 22), dp(activity, 22), dp(activity, 18));

        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(22, 48, 77), Color.rgb(11, 25, 43)});
        background.setCornerRadius(dp(activity, 24));
        background.setStroke(dp(activity, 1), Color.argb(65, 255, 255, 255));
        scroll.setBackground(background);
        scroll.addView(panel);

        if (title != null && !title.isEmpty()) {
            TextView titleView = new TextView(activity);
            titleView.setText(title);
            titleView.setTextSize(20);
            titleView.setTextColor(Color.WHITE);
            titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            panel.addView(titleView);
        }

        if (message != null && !message.isEmpty()) {
            TextView messageView = new TextView(activity);
            messageView.setText(message);
            messageView.setTextSize(15);
            messageView.setTextColor(Color.rgb(218, 230, 242));
            messageView.setLineSpacing(0f, 1.15f);
            messageView.setPadding(0, dp(activity, 12), 0, dp(activity, 20));
            panel.addView(messageView);
        }

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        panel.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        boolean hasNegative = negativeLabel != null && !negativeLabel.isEmpty();
        boolean hasPositive = positiveLabel != null && !positiveLabel.isEmpty();

        if (hasNegative) {
            Button negative = pillButton(activity, negativeLabel, false, false);
            negative.setOnClickListener(v -> dialog.dismiss());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 48));
            if (hasPositive) lp.rightMargin = dp(activity, 10);
            actions.addView(negative, lp);
        }

        if (hasPositive) {
            Button positive = pillButton(activity, positiveLabel, true, destructive);
            positive.setOnClickListener(v -> {
                dialog.dismiss();
                if (onConfirm != null) onConfirm.run();
            });
            actions.addView(positive, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 48)));
        }

        dialog.setContentView(scroll);
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.getDecorView().setPadding(0, 0, 0, 0);
            int width = Math.max(1, Math.min(
                    activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 32),
                    dp(activity, 420)));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    private static Button pillButton(Activity activity, String label,
            boolean primary, boolean destructive) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.create(
                primary ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        button.setMinWidth(dp(activity, 84));
        button.setMinHeight(dp(activity, 48));
        button.setPadding(dp(activity, 18), 0, dp(activity, 18), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(activity, 24));
        if (primary && destructive) {
            bg.setColor(Color.argb(215, 198, 56, 62));
            bg.setStroke(dp(activity, 1), Color.argb(95, 255, 168, 172));
        } else if (primary) {
            bg.setColor(Color.argb(225, 52, 138, 224));
            bg.setStroke(dp(activity, 1), Color.argb(95, 186, 228, 255));
        } else {
            bg.setColor(Color.argb(30, 255, 255, 255));
            bg.setStroke(dp(activity, 1), Color.argb(55, 255, 255, 255));
        }
        button.setBackground(bg);
        return button;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
