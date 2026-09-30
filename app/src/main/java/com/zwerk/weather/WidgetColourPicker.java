package com.zwerk.weather;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/** Colour selection stays local until the enclosing dialog's Apply action. */
final class WidgetColourPicker extends LinearLayout {
    private final float[] hsv = new float[3];
    private final SeekBar[] sliders = new SeekBar[3];
    private final TextView[] values = new TextView[3];
    private final EditText hex;
    private final TextView preview;
    private final boolean gradient;
    private boolean updating;

    WidgetColourPicker(Context context, int colour, int accent, boolean gradient) {
        super(context);
        this.gradient = gradient;
        setOrientation(VERTICAL);
        Color.colorToHSV(colour, hsv);
        preview = label("25°C", 28);
        preview.setGravity(Gravity.CENTER);
        preview.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        addView(preview, new LayoutParams(-1, dp(64)));

        hex = new EditText(context);
        hex.setSingleLine(true);
        hex.setTextColor(Color.WHITE);
        hex.setTextSize(16);
        hex.setHint("#RRGGBB");
        hex.setHintTextColor(Color.argb(170, 255, 255, 255));
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        hex.setSelectAllOnFocus(true);
        hex.setContentDescription(UiTranslations.text(context, "Hex colour"));
        hex.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        LayoutParams hexLp = new LayoutParams(-1, dp(48));
        hexLp.topMargin = dp(10);
        addView(hex, hexLp);

        String[] names = {"Hue", "Saturation", "Brightness"};
        for (int i = 0; i < sliders.length; i++) {
            final int component = i;
            LinearLayout labels = new LinearLayout(context);
            labels.setGravity(Gravity.CENTER_VERTICAL);
            labels.addView(label(names[i], 15), new LayoutParams(0, -2, 1f));
            values[i] = label("", 13);
            labels.addView(values[i]);
            LayoutParams labelsLp = new LayoutParams(-1, -2);
            labelsLp.topMargin = dp(8);
            addView(labels, labelsLp);
            SeekBar slider = new WeatherSeekBar(context);
            sliders[i] = slider;
            slider.setMax(i == 0 ? 360 : 100);
            slider.setProgressTintList(ColorStateList.valueOf(accent));
            slider.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
            slider.setContentDescription(UiTranslations.text(context, names[i]));
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    if (!fromUser || updating) return;
                    hsv[component] = component == 0 ? value : value / 100f;
                    syncColour(Color.HSVToColor(hsv), true);
                }
                @Override public void onStartTrackingTouch(SeekBar bar) { }
                @Override public void onStopTrackingTouch(SeekBar bar) { }
            });
            addView(slider, new LayoutParams(-1, dp(42)));
        }
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (updating) return;
                Integer next = parseColour();
                if (next == null) return;
                hex.setError(null);
                Color.colorToHSV(next, hsv);
                syncColour(next, false);
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        syncColour(colour | 0xff000000, true);
    }

    Integer selectedColour() {
        Integer colour = parseColour();
        if (colour == null) {
            hex.setError(UiTranslations.text(getContext(), "Enter a colour as #RRGGBB"));
            hex.requestFocus();
        }
        return colour;
    }

    private Integer parseColour() {
        String value = hex.getText().toString().trim();
        if (!value.matches("#?[0-9a-fA-F]{6}")) return null;
        return Color.parseColor(value.startsWith("#") ? value : "#" + value);
    }

    private void syncColour(int colour, boolean updateHex) {
        updating = true;
        try {
            if (updateHex) {
                hex.setText(String.format(Locale.ROOT, "#%06X", colour & 0xffffff));
                hex.setError(null);
            }
            for (int i = 0; i < sliders.length; i++) {
                int value = Math.round(i == 0 ? hsv[i] : hsv[i] * 100f);
                sliders[i].setProgress(value);
                values[i].setText(value + (i == 0 ? "°" : "%"));
            }
            GradientDrawable background = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM, WidgetBackground.colours(colour, gradient));
            background.setCornerRadius(dp(16));
            preview.setBackground(background);
            double luminance = (Color.red(colour) * 0.2126 + Color.green(colour) * 0.7152
                    + Color.blue(colour) * 0.0722) / 255;
            preview.setTextColor(luminance > 0.6 ? Color.rgb(31, 31, 31) : Color.WHITE);
        } finally {
            updating = false;
        }
    }

    private TextView label(String value, float size) {
        TextView text = new TextView(getContext());
        text.setText(UiTranslations.text(getContext(), value));
        text.setTextSize(size);
        text.setTextColor(Color.WHITE);
        return text;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
