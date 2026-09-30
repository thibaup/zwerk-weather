package com.zwerk.weather;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.time.YearMonth;

final class OpenMeteoRequestBudgetManager {
    private static final String PREFS = "open_meteo_request_budgets_v1";
    private static final String KEY_DAY = "day";
    private static final String KEY_MONTH = "month";
    private static final String KEY_DAILY_LIMIT = "daily_limit";
    private static final String KEY_MONTHLY_LIMIT = "monthly_limit";
    private static final String KEY_TODAY = "today_total";
    private static final String KEY_THIS_MONTH = "month_total";
    private static final int FREE_LOCAL_DAILY = 5_000;
    private static final int FREE_LOCAL_MONTHLY = 150_000;
    private static final Object LOCK = new Object();

    enum Category {
        FORECAST("forecast", "Forecast"),
        PRECIPITATION("precipitation", "Precipitation"),
        AIR_QUALITY("air_quality", "Air quality"),
        POLLEN("pollen", "Pollen");

        final String key;
        final String label;

        Category(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    static final class Usage {
        final int today;
        final int month;
        final int dailyLimit;
        final int monthlyLimit;
        final int[] todayByCategory;
        final int[] monthByCategory;

        Usage(int today, int month, int dailyLimit, int monthlyLimit,
                int[] todayByCategory, int[] monthByCategory) {
            this.today = today;
            this.month = month;
            this.dailyLimit = dailyLimit;
            this.monthlyLimit = monthlyLimit;
            this.todayByCategory = todayByCategory;
            this.monthByCategory = monthByCategory;
        }
    }

    static final class Decision {
        final boolean allowed;
        final String message;

        Decision(boolean allowed, String message) {
            this.allowed = allowed;
            this.message = message;
        }
    }

    private OpenMeteoRequestBudgetManager() { }

    static Usage usage(Context context) {
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            rollIfNeeded(prefs);
            Category[] categories = Category.values();
            int[] todayByCategory = new int[categories.length];
            int[] monthByCategory = new int[categories.length];
            for (int i = 0; i < categories.length; i++) {
                todayByCategory[i] = prefs.getInt(categoryDayKey(categories[i]), 0);
                monthByCategory[i] = prefs.getInt(categoryMonthKey(categories[i]), 0);
            }
            return new Usage(prefs.getInt(KEY_TODAY, 0),
                    prefs.getInt(KEY_THIS_MONTH, 0),
                    dailyLimit(prefs, context), monthlyLimit(prefs, context),
                    todayByCategory, monthByCategory);
        }
    }

    static Decision tryAcquire(Context context, Category category) {
        synchronized (LOCK) {
            SharedPreferences prefs = prefs(context);
            if (!rollIfNeeded(prefs)) {
                return new Decision(false, "Could not count the Open-Meteo request.");
            }
            int today = prefs.getInt(KEY_TODAY, 0);
            int month = prefs.getInt(KEY_THIS_MONTH, 0);
            int dailyLimit = dailyLimit(prefs, context);
            int monthlyLimit = monthlyLimit(prefs, context);
            if (dailyLimit > 0 && today >= dailyLimit) {
                return new Decision(false, "Open-Meteo daily app limit reached.");
            }
            if (monthlyLimit > 0 && month >= monthlyLimit) {
                return new Decision(false, "Open-Meteo monthly app limit reached.");
            }
            if (today == Integer.MAX_VALUE || month == Integer.MAX_VALUE) {
                return new Decision(false, "Open-Meteo request counter is full.");
            }
            int categoryToday = prefs.getInt(categoryDayKey(category), 0);
            int categoryMonth = prefs.getInt(categoryMonthKey(category), 0);
            boolean saved = prefs.edit()
                    .putInt(KEY_TODAY, today + 1)
                    .putInt(KEY_THIS_MONTH, month + 1)
                    .putInt(categoryDayKey(category), categoryToday == Integer.MAX_VALUE
                            ? categoryToday : categoryToday + 1)
                    .putInt(categoryMonthKey(category), categoryMonth == Integer.MAX_VALUE
                            ? categoryMonth : categoryMonth + 1)
                    .commit();
            return saved ? new Decision(true, "")
                    : new Decision(false, "Could not count the Open-Meteo request.");
        }
    }

    static void setLimits(Context context, int daily, int monthly) {
        if (!((daily == -1 && monthly == -1) || (daily > 0 && monthly > 0))) {
            throw new IllegalArgumentException("Invalid Open-Meteo limits");
        }
        synchronized (LOCK) {
            prefs(context).edit().putInt(KEY_DAILY_LIMIT, daily)
                    .putInt(KEY_MONTHLY_LIMIT, monthly).apply();
        }
    }

    static void restoreDefaultLimits(Context context) {
        synchronized (LOCK) {
            prefs(context).edit().remove(KEY_DAILY_LIMIT).remove(KEY_MONTHLY_LIMIT).apply();
        }
    }

    static void resetUsage(Context context) {
        synchronized (LOCK) {
            SharedPreferences.Editor edit = prefs(context).edit()
                    .putString(KEY_DAY, LocalDate.now().toString())
                    .putString(KEY_MONTH, YearMonth.now().toString())
                    .putInt(KEY_TODAY, 0)
                    .putInt(KEY_THIS_MONTH, 0);
            for (Category category : Category.values()) {
                edit.putInt(categoryDayKey(category), 0);
                edit.putInt(categoryMonthKey(category), 0);
            }
            edit.apply();
        }
    }

    private static int dailyLimit(SharedPreferences prefs, Context context) {
        return prefs.getInt(KEY_DAILY_LIMIT,
                OpenMeteoConfig.hasCustomerKey(context) ? -1 : FREE_LOCAL_DAILY);
    }

    private static int monthlyLimit(SharedPreferences prefs, Context context) {
        return prefs.getInt(KEY_MONTHLY_LIMIT,
                OpenMeteoConfig.hasCustomerKey(context) ? -1 : FREE_LOCAL_MONTHLY);
    }

    private static boolean rollIfNeeded(SharedPreferences prefs) {
        String today = LocalDate.now().toString();
        String month = YearMonth.now().toString();
        boolean newDay = !today.equals(prefs.getString(KEY_DAY, ""));
        boolean newMonth = !month.equals(prefs.getString(KEY_MONTH, ""));
        if (!newDay && !newMonth) return true;
        SharedPreferences.Editor edit = prefs.edit();
        if (newDay) {
            edit.putString(KEY_DAY, today).putInt(KEY_TODAY, 0);
            for (Category category : Category.values()) edit.putInt(categoryDayKey(category), 0);
        }
        if (newMonth) {
            edit.putString(KEY_MONTH, month).putInt(KEY_THIS_MONTH, 0);
            for (Category category : Category.values()) edit.putInt(categoryMonthKey(category), 0);
        }
        return edit.commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String categoryDayKey(Category category) { return "today_" + category.key; }
    private static String categoryMonthKey(Category category) { return "month_" + category.key; }
}
