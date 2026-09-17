package com.zwerk.weather;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** Dedicated settings screen for app-side API request budgets. */
public final class ApiUsageLimitsActivity extends Activity {
    private static final String PRICING_URL =
            "https://developers.google.com/maps/billing-and-pricing/pricing";
    private static final int PRIMARY = Color.rgb(246, 249, 253);
    private static final int SECONDARY = Color.rgb(190, 207, 226);
    private static final int ACCENT = Color.rgb(143, 207, 255);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        buildUi();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.rgb(5, 16, 31));
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        GradientDrawable pageBackground = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(16, 52, 91), Color.rgb(7, 25, 49), Color.rgb(4, 14, 28)});
        scroll.setBackground(pageBackground);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(14), dp(18), dp(34));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 34f, PRIMARY);
        back.setGravity(Gravity.CENTER);
        back.setIncludeFontPadding(false);
        back.setPadding(0, 0, 0, dp(4));
        back.setBackground(controlBackground());
        back.setContentDescription("Back");
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(52), dp(54)));
        TextView title = text("API request limits", 25f, PRIMARY);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, dp(54), 1f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, titleParams);
        page.addView(header);

        TextView intro = text(
                "Set a hard local budget before Zwerk Weather sends a request. Retries and additional pages count too; cached forecasts do not.",
                14f, SECONDARY);
        intro.setLineSpacing(dp(2), 1f);
        intro.setPadding(dp(4), dp(8), dp(4), dp(16));
        page.addView(intro);

        addHeading(page, "PROFILE · " + ApiRequestBudgetManager.profileLabel(this).toUpperCase(Locale.ROOT));
        addProfile(page, "Off", "No app-side cap. Usage is still counted.",
                ApiRequestBudgetManager.PROFILE_OFF);
        addProfile(page, "Conservative", "Weather/Air Quality 8,000 monthly · Pollen 4,000.",
                ApiRequestBudgetManager.PROFILE_CONSERVATIVE);
        addProfile(page, "Google free tier", "Weather/Air Quality 10,000 monthly · Pollen 5,000.",
                ApiRequestBudgetManager.PROFILE_GOOGLE_FREE);

        addHeading(page, "USAGE & CUSTOM LIMITS");
        for (ApiRequestBudgetManager.Category category : ApiRequestBudgetManager.Category.values()) {
            addCategory(page, category);
        }

        TextView note = text(
                "These limits apply only to this app on this device. Other apps or devices using the same Google Cloud project can still consume its allowance. The monthly counter follows Google's Pacific-time billing month.",
                12.5f, SECONDARY);
        note.setLineSpacing(dp(2), 1f);
        note.setPadding(dp(5), dp(12), dp(5), dp(8));
        page.addView(note);

        Button pricing = button("View Google pricing ↗");
        pricing.setOnClickListener(v -> startActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse(PRICING_URL))));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, dp(50));
        actionParams.topMargin = dp(8);
        page.addView(pricing, actionParams);

        Button reset = button("Reset local usage counters");
        reset.setOnClickListener(v -> confirmReset());
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(-1, dp(50));
        resetParams.topMargin = dp(8);
        page.addView(reset, resetParams);

        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = 0;
            int bottom = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            page.setPadding(dp(18), top + dp(8), dp(18), bottom + dp(30));
            return insets;
        });
        scroll.requestApplyInsets();
    }

    private void addProfile(LinearLayout page, String title, String subtitle, String profile) {
        boolean selected = profile.equals(ApiRequestBudgetManager.profile(this));
        LinearLayout card = card(selected);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(13), dp(14), dp(13));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 17f, PRIMARY));
        TextView detail = text(subtitle, 12.5f, SECONDARY);
        detail.setPadding(0, dp(3), dp(6), 0);
        labels.addView(detail);
        card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView marker = text(selected ? "✓" : "", 22f, ACCENT);
        marker.setGravity(Gravity.CENTER);
        card.addView(marker, new LinearLayout.LayoutParams(dp(34), dp(40)));
        card.setOnClickListener(v -> {
            ApiRequestBudgetManager.applyProfile(this, profile);
            buildUi();
        });
        page.addView(card, cardParams());
    }

    private void addCategory(LinearLayout page, ApiRequestBudgetManager.Category category) {
        ApiRequestBudgetManager.Usage usage = ApiRequestBudgetManager.usage(this, category);
        LinearLayout card = card(false);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(category.label, 18f, PRIMARY));
        TextView description = text(category.description, 12.5f, SECONDARY);
        description.setPadding(0, dp(2), 0, 0);
        labels.addView(description);
        top.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView edit = text("Edit  ›", 14f, ACCENT);
        edit.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        top.addView(edit, new LinearLayout.LayoutParams(dp(70), dp(40)));
        card.addView(top);

        String today = String.format(Locale.getDefault(), "Today  %,d / %s",
                usage.today, ApiRequestBudgetManager.formatLimit(usage.limits.daily));
        String month = String.format(Locale.getDefault(), "This month  %,d / %s",
                usage.month, ApiRequestBudgetManager.formatLimit(usage.limits.monthly));
        TextView counters = text(today + "\n" + month, 14f, PRIMARY);
        counters.setLineSpacing(dp(5), 1f);
        counters.setPadding(0, dp(11), 0, 0);
        card.addView(counters);
        card.setOnClickListener(v -> editLimits(category, usage.limits));
        page.addView(card, cardParams());
    }

    private void editLimits(
            ApiRequestBudgetManager.Category category,
            ApiRequestBudgetManager.Limits current) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(20), dp(20), dp(18));
        panel.setBackground(dialogBackground());

        TextView title = text(category.label + " limits", 22f, PRIMARY);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(title);

        TextView help = text(
                "Set the maximum requests Zwerk Weather may send. Saving switches the profile to Custom.",
                13f, SECONDARY);
        help.setLineSpacing(dp(2), 1f);
        help.setPadding(0, dp(5), 0, dp(15));
        panel.addView(help);

        panel.addView(fieldLabel("DAILY LIMIT"));
        EditText daily = numberField("Daily limit", current.daily);
        panel.addView(daily, new LinearLayout.LayoutParams(-1, dp(54)));

        TextView monthlyLabel = fieldLabel("MONTHLY LIMIT");
        monthlyLabel.setPadding(dp(3), dp(14), dp(3), dp(6));
        panel.addView(monthlyLabel);
        EditText monthly = numberField("Monthly limit", current.monthly);
        panel.addView(monthly, new LinearLayout.LayoutParams(-1, dp(54)));

        TextView error = text("Enter a positive number for both limits.", 12.5f,
                Color.rgb(255, 190, 190));
        error.setPadding(dp(3), dp(9), dp(3), 0);
        error.setVisibility(View.GONE);
        panel.addView(error);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, dp(50));
        actionsParams.topMargin = dp(18);
        panel.addView(actions, actionsParams);

        Button cancel = dialogButton("Cancel", false);
        cancel.setOnClickListener(v -> dialog.dismiss());
        actions.addView(cancel, new LinearLayout.LayoutParams(0, -1, 1f));

        Button save = dialogButton("Save limits", true);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, -1, 1f);
        saveParams.leftMargin = dp(10);
        actions.addView(save, saveParams);
        save.setOnClickListener(v -> {
            Integer dayValue = parsePositive(daily);
            Integer monthValue = parsePositive(monthly);
            if (dayValue == null || monthValue == null) {
                error.setVisibility(View.VISIBLE);
                if (dayValue == null) daily.requestFocus();
                else monthly.requestFocus();
                return;
            }
            ApiRequestBudgetManager.saveCustomLimits(
                    this, category, dayValue, monthValue);
            dialog.dismiss();
            buildUi();
        });

        dialog.setContentView(panel);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.width = getResources().getDisplayMetrics().widthPixels - dp(36);
            attributes.height = WindowManager.LayoutParams.WRAP_CONTENT;
            attributes.dimAmount = 0.62f;
            window.setAttributes(attributes);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private EditText numberField(String hint, int value) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setHintTextColor(Color.argb(155, 190, 207, 226));
        field.setTextColor(PRIMARY);
        field.setTextSize(17f);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setPadding(dp(15), 0, dp(15), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(185, 8, 28, 50));
        background.setCornerRadius(dp(13));
        background.setStroke(dp(1), Color.argb(115, 163, 211, 246));
        field.setBackground(background);
        if (value > 0) field.setText(Integer.toString(value));
        field.setSelection(field.getText().length());
        return field;
    }

    private TextView fieldLabel(String value) {
        TextView label = text(value, 11.5f, SECONDARY);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setPadding(dp(3), 0, dp(3), dp(6));
        return label;
    }

    private Button dialogButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(14f);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(PRIMARY);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                primary
                        ? new int[]{Color.rgb(48, 132, 194), Color.rgb(31, 91, 146)}
                        : new int[]{Color.argb(190, 49, 76, 105), Color.argb(190, 29, 52, 78)});
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), primary
                ? Color.argb(210, 163, 220, 255)
                : Color.argb(85, 220, 235, 250));
        button.setBackground(background);
        return button;
    }

    private GradientDrawable dialogBackground() {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(26, 65, 101), Color.rgb(11, 32, 58)});
        background.setCornerRadius(dp(22));
        background.setStroke(dp(1), Color.argb(120, 190, 226, 255));
        return background;
    }

    private Integer parsePositive(EditText field) {
        try {
            int value = Integer.parseInt(field.getText().toString().trim());
            return value > 0 ? value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("Reset usage counters?")
                .setMessage("This only clears Zwerk Weather's local counters. It does not reset Google Cloud billing usage.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", (dialog, which) -> {
                    ApiRequestBudgetManager.resetUsage(this);
                    buildUi();
                })
                .show();
    }

    private void addHeading(LinearLayout page, String value) {
        TextView heading = text(value, 12f, SECONDARY);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setPadding(dp(5), dp(17), dp(5), dp(7));
        page.addView(heading);
    }

    private LinearLayout card(boolean selected) {
        LinearLayout view = new LinearLayout(this);
        view.setClickable(true);
        view.setFocusable(true);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                selected
                        ? new int[]{Color.argb(220, 42, 105, 158), Color.argb(220, 25, 68, 111)}
                        : new int[]{Color.argb(205, 35, 71, 108), Color.argb(205, 20, 45, 77)});
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), selected ? Color.argb(220, 143, 207, 255)
                : Color.argb(75, 220, 235, 250));
        view.setBackground(background);
        return view;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(9);
        return params;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(PRIMARY);
        button.setTextSize(14f);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setBackground(controlBackground());
        return button;
    }

    private GradientDrawable controlBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(125, 60, 103, 145));
        background.setCornerRadius(dp(15));
        background.setStroke(dp(1), Color.argb(75, 220, 235, 250));
        return background;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        return view;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
