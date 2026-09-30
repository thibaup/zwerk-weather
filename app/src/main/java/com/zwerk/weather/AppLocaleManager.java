package com.zwerk.weather;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;

import java.util.Locale;

final class AppLocaleManager {
    private static final String PREFS_NAME = "APP_LOCALE";
    private static final String PREF_LANGUAGE_TAG = "language_tag";

    static final String SYSTEM_DEFAULT = "";

    static final class LanguageOption {
        final String tag;
        final int labelResId;

        LanguageOption(String tag, int labelResId) {
            this.tag = tag;
            this.labelResId = labelResId;
        }
    }

    static final LanguageOption[] OPTIONS = new LanguageOption[]{
            new LanguageOption(SYSTEM_DEFAULT, R.string.language_system_default),
            new LanguageOption("en", R.string.language_name_en),
            new LanguageOption("nl", R.string.language_name_nl),
            new LanguageOption("fr", R.string.language_name_fr),
            new LanguageOption("de", R.string.language_name_de),
            new LanguageOption("es", R.string.language_name_es),
            new LanguageOption("it", R.string.language_name_it),
            new LanguageOption("pt-PT", R.string.language_name_pt_pt),
            new LanguageOption("pt-BR", R.string.language_name_pt_br),
            new LanguageOption("pl", R.string.language_name_pl),
            new LanguageOption("ru", R.string.language_name_ru),
            new LanguageOption("uk", R.string.language_name_uk),
            new LanguageOption("tr", R.string.language_name_tr),
            new LanguageOption("sv", R.string.language_name_sv),
            new LanguageOption("da", R.string.language_name_da),
            new LanguageOption("nb", R.string.language_name_nb),
            new LanguageOption("fi", R.string.language_name_fi),
            new LanguageOption("cs", R.string.language_name_cs),
            new LanguageOption("sk", R.string.language_name_sk),
            new LanguageOption("hu", R.string.language_name_hu),
            new LanguageOption("ro", R.string.language_name_ro),
            new LanguageOption("el", R.string.language_name_el),
            new LanguageOption("bg", R.string.language_name_bg),
            new LanguageOption("hr", R.string.language_name_hr)
    };

    private AppLocaleManager() {}

    static Context wrap(Context base) {
        String tag = selectedTag(base);
        LocaleList locales = localesForTag(tag);
        applyProcessDefault(locales);

        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocales(locales);
        return base.createConfigurationContext(configuration);
    }

    static String selectedTag(Context context) {
        return normalizeTag(preferences(context).getString(PREF_LANGUAGE_TAG, SYSTEM_DEFAULT));
    }

    static void setSelectedTag(Context context, String tag) {
        String normalized = normalizeTag(tag);
        preferences(context).edit().putString(PREF_LANGUAGE_TAG, normalized).apply();
        applyProcessDefault(localesForTag(normalized));
    }

    static int selectedIndex(Context context) {
        String selected = selectedTag(context);
        for (int i = 0; i < OPTIONS.length; i++) {
            if (OPTIONS[i].tag.equals(selected)) return i;
        }
        return 0;
    }

    static String selectedLanguageLabel(Context context) {
        int index = selectedIndex(context);
        LanguageOption option = OPTIONS[index];
        if (!SYSTEM_DEFAULT.equals(option.tag)) return context.getString(option.labelResId);
        Locale systemLocale = systemLocales().get(0);
        String nativeName = systemLocale.getDisplayName(systemLocale);
        return context.getString(
                R.string.language_system_choice_format,
                context.getString(R.string.language_system_default),
                capitalize(nativeName, systemLocale));
    }

    static String systemLanguageNativeName() {
        Locale systemLocale = systemLocales().get(0);
        return capitalize(systemLocale.getDisplayName(systemLocale), systemLocale);
    }

    static boolean isContextStale(Context context) {
        Locale desired = localesForTag(selectedTag(context)).get(0);
        Locale current = context.getResources().getConfiguration().getLocales().get(0);
        return !desired.toLanguageTag().equalsIgnoreCase(current.toLanguageTag());
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String normalizeTag(String tag) {
        if (tag == null || tag.trim().isEmpty()) return SYSTEM_DEFAULT;
        String candidate = Locale.forLanguageTag(tag.trim()).toLanguageTag();
        for (LanguageOption option : OPTIONS) {
            if (option.tag.equalsIgnoreCase(candidate)) return option.tag;
        }
        return SYSTEM_DEFAULT;
    }

    private static LocaleList localesForTag(String tag) {
        if (tag == null || tag.isEmpty()) return systemLocales();
        return new LocaleList(Locale.forLanguageTag(tag));
    }

    private static LocaleList systemLocales() {
        LocaleList locales = Resources.getSystem().getConfiguration().getLocales();
        return locales.size() == 0 ? new LocaleList(Locale.ENGLISH) : locales;
    }

    private static void applyProcessDefault(LocaleList locales) {
        LocaleList.setDefault(locales);
        Locale.setDefault(locales.get(0));
    }

    private static String capitalize(String value, Locale locale) {
        if (value == null || value.isEmpty()) return "";
        int first = value.offsetByCodePoints(0, 1);
        return value.substring(0, first).toUpperCase(locale) + value.substring(first);
    }
}
