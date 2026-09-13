package com.dfiles.i18n;

import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Small wrapper around ResourceBundle for the app's five supported locales.
 * The UI is rebuilt (not live-rebound) when the locale changes, so lookups
 * just need to reflect whatever locale was last set.
 */
public final class I18n {

    public static final List<Locale> SUPPORTED = List.of(
            Locale.ENGLISH, Locale.of("es"), Locale.FRENCH, Locale.of("pt"), Locale.ITALIAN
    );

    private static Locale currentLocale = Locale.ENGLISH;
    private static ResourceBundle bundle = loadBundle(currentLocale);

    private I18n() {}

    private static ResourceBundle loadBundle(Locale locale) {
        return ResourceBundle.getBundle("i18n.messages", locale);
    }

    public static void setLocale(Locale locale) {
        currentLocale = locale;
        bundle = loadBundle(locale);
    }

    public static Locale getLocale() {
        return currentLocale;
    }

    public static String t(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    public static String t(String key, Object... args) {
        try {
            return String.format(bundle.getString(key), args);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    public static String displayNameFor(Locale locale) {
        return switch (locale.getLanguage()) {
            case "es" -> "Español";
            case "fr" -> "Français";
            case "pt" -> "Português";
            case "it" -> "Italiano";
            default -> "English";
        };
    }

    /** Best-effort match of the JVM's default locale against our supported list, falling back to English. */
    public static Locale detectInitialLocale() {
        String lang = Locale.getDefault().getLanguage();
        for (Locale l : SUPPORTED) {
            if (l.getLanguage().equals(lang)) return l;
        }
        return Locale.ENGLISH;
    }

    public static Locale fromTag(String tag) {
        for (Locale l : SUPPORTED) {
            if (l.getLanguage().equals(tag)) return l;
        }
        return Locale.ENGLISH;
    }
}
