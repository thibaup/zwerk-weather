package com.zwerk.weather;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

/** Keeps conservative, device-local request budgets for Google weather services. */
public final class ApiRequestBudgetManager {
    public static final String PROFILE_OFF = "off";
    public static final String PROFILE_CONSERVATIVE = "conservative";
    public static final String PROFILE_GOOGLE_FREE = "google_free";
    public static final String PROFILE_CUSTOM = "custom";

    private static final String PREFS = "api_request_budgets_v1";
    private static final String KEY_PROFILE = "profile";
    private static final String KEY_DAY = "counter_day";
    private static final String KEY_MONTH = "counter_month_pacific";
    private static final ZoneId GOOGLE_BILLING_ZONE = ZoneId.of("America/Los_Angeles");
    private static final Object LOCK = new Object();

    public enum Category {
        WEATHER("weather", "Weather", "Current, hourly, daily and minute forecasts", 10_000),
        AIR_QUALITY("air_quality", "Air Quality", "Current air-quality data", 10_000),
        POLLEN("pollen", "Pollen", "Pollen forecast", 5_000);

        public final String key;
        public final String label;
        public final String description;
        public final int googleFreeMonthly;

        Category(String key, String label, String description, int googleFreeMonthly) {
            this.key = key;
            this.label = label;
            this.description = description;
            this.googleFreeMonthly = googleFreeMonthly;
        }
    }

    public static final class Limits {
        public final int daily;
        public final int monthly;

        Limits(int daily, int monthly) {
            this.daily = daily;
            this.monthly = monthly;
        }
    }

    public static final class Usage {
        public final int today;
        public final int month;
        public final Limits limits;

        Usage(int today, int month, Limits limits) {
            this.today = today;
            this.month = month;
            this.limits = limits;
        }
    }

    public static final class Decision {
        public final boolean allowed;
        public final String message;

        Decision(boolean allowed, String message) {
            this.allowed = allowed;
            this.message = message;
        }
    }

    private ApiRequestBudgetManager() { }

    public static String profile(Context context) {
        return prefs(context).getString(KEY_PROFILE, PROFILE_GOOGLE_FREE);
    }

    public static String profileLabel(Context context) {
        return profileLabel(profile(context));
    }

    public static String profileLabel(String profile) {
        if (PROFILE_CONSERVATIVE.equals(profile)) return "Conservative";
        if (PROFILE_GOOGLE_FREE.equals(profile)) return "Google free tier";
        if (PROFILE_CUSTOM.equals(profile)) return "Custom";
        return "Off";
    }

    public static void applyProfile(Context context, String profile) {
        synchronized (LOCK) {
            SharedPreferences.Editor editor = prefs(context).edit().putString(KEY_PROFILE, profile);
            for (Category category : Category.values()) {
                Limits limits = presetLimits(profile, category);
                editor.putInt(dailyLimitKey(category), limits.daily);
                editor.putInt(monthlyLimitKey(category), limits.monthly);
            }
            editor.apply();
        }
    }

    public static void saveCustomLimits(Context context, Category category, int daily, int monthly) {
        if (daily < 1 || monthly < 1) throw new IllegalArgumentException("Limits must be positive");
        synchronized (LOCK) {
            prefs(context).edit()
                    .putString(KEY_PROFILE, PROFILE_CUSTOM)
                    .putInt(dailyLimitKey(category), daily)
                    .putInt(monthlyLimitKey(category), monthly)
                    .apply();
        }
    }

    public static Usage usage(Context context, Category category) {
        synchronized (LOCK) {
            SharedPreferences shared = prefs(context);
            rollCountersIfNeeded(shared);
            return new Usage(
                    shared.getInt(dayCountKey(category), 0),
                    shared.getInt(monthCountKey(category), 0),
                    limits(shared, category));
        }
    }

    public static Decision tryAcquire(Context context, Category category) {
        synchronized (LOCK) {
            SharedPreferences shared = prefs(context);
            rollCountersIfNeeded(shared);
            Limits limits = limits(shared, category);
            int today = shared.getInt(dayCountKey(category), 0);
            int month = shared.getInt(monthCountKey(category), 0);
            if (limits.daily > 0 && today >= limits.daily) {
                return new Decision(false, category.label + " daily request limit reached. It resets tomorrow.");
            }
            if (limits.monthly > 0 && month >= limits.monthly) {
                return new Decision(false, category.label
                        + " monthly request limit reached. It resets with Google's billing month (Pacific time).");
            }
            boolean stored = shared.edit()
                    .putInt(dayCountKey(category), today + 1)
                    .putInt(monthCountKey(category), month + 1)
                    .commit();
            if (!stored) {
                return new Decision(false, "The request could not be safely counted, so it was not sent.");
            }
            return new Decision(true, "");
        }
    }

    public static void resetUsage(Context context) {
        synchronized (LOCK) {
            SharedPreferences.Editor editor = prefs(context).edit()
                    .putString(KEY_DAY, LocalDate.now().toString())
                    .putString(KEY_MONTH, googleBillingMonth());
            for (Category category : Category.values()) {
                editor.putInt(dayCountKey(category), 0);
                editor.putInt(monthCountKey(category), 0);
            }
            editor.apply();
        }
    }

    private static Limits limits(SharedPreferences shared, Category category) {
        String profile = shared.getString(KEY_PROFILE, PROFILE_GOOGLE_FREE);
        Limits defaults = presetLimits(profile, category);
        return new Limits(
                shared.getInt(dailyLimitKey(category), defaults.daily),
                shared.getInt(monthlyLimitKey(category), defaults.monthly));
    }

    private static Limits presetLimits(String profile, Category category) {
        if (PROFILE_GOOGLE_FREE.equals(profile)) {
            return new Limits(category.googleFreeMonthly / 31, category.googleFreeMonthly);
        }
        if (PROFILE_CONSERVATIVE.equals(profile)) {
            return category == Category.POLLEN
                    ? new Limits(125, 4_000)
                    : new Limits(250, 8_000);
        }
        return new Limits(-1, -1);
    }

    private static void rollCountersIfNeeded(SharedPreferences shared) {
        String today = LocalDate.now().toString();
        String month = googleBillingMonth();
        String savedDay = shared.getString(KEY_DAY, "");
        String savedMonth = shared.getString(KEY_MONTH, "");
        if (today.equals(savedDay) && month.equals(savedMonth)) return;
        SharedPreferences.Editor editor = shared.edit();
        if (!today.equals(savedDay)) {
            editor.putString(KEY_DAY, today);
            for (Category category : Category.values()) editor.putInt(dayCountKey(category), 0);
        }
        if (!month.equals(savedMonth)) {
            editor.putString(KEY_MONTH, month);
            for (Category category : Category.values()) editor.putInt(monthCountKey(category), 0);
        }
        editor.commit();
    }

    private static String googleBillingMonth() {
        return YearMonth.from(ZonedDateTime.now(GOOGLE_BILLING_ZONE)).toString();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String dailyLimitKey(Category c) { return "limit_day_" + c.key; }
    private static String monthlyLimitKey(Category c) { return "limit_month_" + c.key; }
    private static String dayCountKey(Category c) { return "count_day_" + c.key; }
    private static String monthCountKey(Category c) { return "count_month_" + c.key; }

    public static String formatLimit(int value) {
        return value < 0 ? "Unlimited" : String.format(Locale.getDefault(), "%,d", value);
    }
}
