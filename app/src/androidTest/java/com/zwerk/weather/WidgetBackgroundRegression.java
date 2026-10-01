package com.zwerk.weather;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

final class WidgetBackgroundRegression {
    private int checks;

    int run(Context context) throws Exception {
        checkStateAndEligibility();
        checkCooldown();
        checkSettingsFallbacks(context);
        checkPreferences(context);
        String report = DiagnosticLog.report(context);
        check(report.contains("Android background access: " + WidgetBackgroundAccess.state(context)),
                "export includes the current Android battery state");
        return checks;
    }

    private void checkStateAndEligibility() {
        Boolean[] values = {null, false, true};
        for (Boolean restricted : values) {
            for (Boolean exempt : values) {
                WidgetBackgroundAccess.State state = WidgetBackgroundAccess.state(restricted, exempt);
                if (Boolean.TRUE.equals(restricted)) {
                    check(state == WidgetBackgroundAccess.State.RESTRICTED,
                            "explicit restriction takes priority over a battery exemption");
                } else if (Boolean.TRUE.equals(exempt)) {
                    check(state == WidgetBackgroundAccess.State.ALLOWED, "positive exemption hides reminder");
                } else if (restricted == null || exempt == null) {
                    check(state == WidgetBackgroundAccess.State.UNKNOWN, "missing signals are unknown");
                } else {
                    check(state == WidgetBackgroundAccess.State.OPTIMIZED,
                            "default optimization is not labelled as a background block");
                }
            }
        }
        for (WidgetBackgroundAccess.State state : WidgetBackgroundAccess.State.values()) {
            for (boolean confirmed : new boolean[]{false, true}) {
                check(!WidgetBackgroundAccess.needsReminder(false, state, confirmed),
                        "users without widgets never receive the reminder");
                boolean needed = WidgetBackgroundAccess.needsReminder(true, state, confirmed);
                if (state == WidgetBackgroundAccess.State.ALLOWED) {
                    check(!needed, "allowlisted users never receive the reminder");
                } else if (state == WidgetBackgroundAccess.State.RESTRICTED) {
                    check(needed, "explicit restriction overrides user confirmation");
                } else {
                    check(needed == !confirmed, "OEM confirmation handles unexposed settings");
                }
            }
        }
    }

    private void checkCooldown() {
        WidgetBackgroundAccess.State state = WidgetBackgroundAccess.State.OPTIMIZED;
        long now = 5L * WidgetBackgroundAccess.REMINDER_INTERVAL_MS;
        check(WidgetBackgroundAccess.popupDue(state, 0L, "", now), "first eligible launch can show prompt");
        check(!WidgetBackgroundAccess.popupDue(state, now, state.name(), now + 2000L),
                "rotation and immediate reopening do not show another prompt");
        check(!WidgetBackgroundAccess.popupDue(state, now, state.name(),
                now + WidgetBackgroundAccess.REMINDER_INTERVAL_MS - 1L), "cooldown includes its last millisecond");
        check(WidgetBackgroundAccess.popupDue(state, now, state.name(),
                now + WidgetBackgroundAccess.REMINDER_INTERVAL_MS), "reminder becomes due after a day");
        check(WidgetBackgroundAccess.popupDue(WidgetBackgroundAccess.State.RESTRICTED, now,
                state.name(), now + 2000L), "new explicit restriction can prompt immediately");
        check(!WidgetBackgroundAccess.popupDue(WidgetBackgroundAccess.State.UNKNOWN, now,
                state.name(), now + 2000L), "transient unavailable signals cannot cause popup spam");
        check(!WidgetBackgroundAccess.popupDue(state, now, state.name(), now - 2000L),
                "small clock corrections do not retrigger popup");
        check(WidgetBackgroundAccess.popupDue(state, now, state.name(),
                now - WidgetBackgroundAccess.REMINDER_INTERVAL_MS), "large clock rollback cannot suppress forever");
    }

    private void checkSettingsFallbacks(Context context) {
        for (int available = 0; available <= 3; available++) {
            SettingsContext fake = new SettingsContext(context, available);
            check(WidgetBackgroundAccess.openSettings(fake) == (available < 3),
                    "unavailable settings activities are handled safely");
            check(fake.attempts.size() == Math.min(available + 1, 3), "fallback stops at first usable intent");
            Intent first = fake.attempts.get(0);
            check(Settings.ACTION_APPLICATION_DETAILS_SETTINGS.equals(first.getAction()),
                    "first action opens this app, without manufacturer-specific components");
            check(("package:" + context.getPackageName()).equals(first.getDataString()), "app URI is correct");
            for (Intent intent : fake.attempts) {
                check((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0,
                        "settings also launch safely from a non-activity context");
                check(!Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.equals(intent.getAction()),
                        "no direct battery exemption request");
            }
            if (fake.attempts.size() >= 2) {
                Intent second = fake.attempts.get(1);
                check(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS.equals(second.getAction()),
                        "battery optimization list is second fallback");
                check(second.getData() == null, "battery list does not pretend to support a package deep link");
            }
            if (fake.attempts.size() == 3) {
                check(Settings.ACTION_SETTINGS.equals(fake.attempts.get(2).getAction()),
                        "general settings is the final fallback");
            }
        }
        Context failingServices = new ContextWrapper(context) {
            @Override public Object getSystemService(String name) { throw new SecurityException(); }
        };
        check(WidgetBackgroundAccess.state(failingServices) == WidgetBackgroundAccess.State.UNKNOWN,
                "unavailable system services do not crash the app");
    }

    private void checkPreferences(Context context) {
        SharedPreferences prefs = WidgetBackgroundAccess.preferences(context);
        String[] keys = {WidgetBackgroundAccess.PREF_CONFIRMED, WidgetBackgroundAccess.PREF_LAST_SHOWN,
                WidgetBackgroundAccess.PREF_LAST_STATE};
        java.util.Map<String, ?> original = prefs.getAll();
        try {
            prefs.edit().remove(keys[0]).remove(keys[1]).remove(keys[2]).commit();
            WidgetBackgroundAccess.State state = WidgetBackgroundAccess.state(context);
            boolean widgets = WidgetRefreshManager.hasWidgets(context);
            check(WidgetBackgroundAccess.needsReminder(context)
                    == WidgetBackgroundAccess.needsReminder(widgets, state, false),
                    "real device widget IDs and system signals gate the reminder");
            prefs.edit().putBoolean(keys[0], true).commit();
            check(WidgetBackgroundAccess.needsReminder(context)
                    == (widgets && state == WidgetBackgroundAccess.State.RESTRICTED),
                    "confirmation persists except when Android explicitly reports restrictions");
            prefs.edit().remove(keys[0]).putLong(keys[1], System.currentTimeMillis())
                    .putString(keys[2], state.name()).commit();
            check(!WidgetBackgroundAccess.popupDue(context), "persistent timestamp suppresses reopen popup");
        } finally {
            SharedPreferences.Editor editor = prefs.edit();
            for (String key : keys) {
                editor.remove(key);
                Object value = original.get(key);
                if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                else if (value instanceof Long) editor.putLong(key, (Long) value);
                else if (value instanceof String) editor.putString(key, (String) value);
            }
            editor.commit();
        }
    }

    private void check(boolean passed, String message) {
        checks++;
        if (!passed) throw new AssertionError(message);
    }

    private static final class SettingsContext extends ContextWrapper {
        final List<Intent> attempts = new ArrayList<>();
        final int available;

        SettingsContext(Context base, int available) { super(base); this.available = available; }

        @Override public void startActivity(Intent intent) {
            attempts.add(intent);
            if (attempts.size() - 1 < available) {
                if (attempts.size() == 1) throw new ActivityNotFoundException();
                throw new SecurityException();
            }
        }
    }
}
