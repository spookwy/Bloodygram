package com.epicgram;

import android.content.res.Resources;
import android.util.SparseIntArray;

import androidx.annotation.StringRes;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;

import java.util.Locale;

/**
 * Epicgram strings. The app module keeps only default-locale resources ({@code localeFilters "zz"}),
 * so translations live in values/strings_epicgram_ru.xml as {@code <name>_ru} and are picked
 * by the in-app Telegram language.
 */
public class EpicStrings {

    private static final SparseIntArray ruIds = new SparseIntArray();

    public static String get(@StringRes int res) {
        Resources resources = ApplicationLoader.applicationContext.getResources();
        if (isRussian()) {
            int ru;
            synchronized (ruIds) {
                ru = ruIds.get(res, -1);
                if (ru == -1) {
                    ru = resources.getIdentifier(resources.getResourceEntryName(res) + "_ru", "string", ApplicationLoader.applicationContext.getPackageName());
                    ruIds.put(res, ru);
                }
            }
            if (ru != 0) {
                return resources.getString(ru);
            }
        }
        return resources.getString(res);
    }

    public static String format(@StringRes int res, Object... args) {
        return String.format(get(res), args);
    }

    public static boolean isRussian() {
        Locale locale = LocaleController.getInstance().getCurrentLocale();
        return locale != null && "ru".equals(locale.getLanguage());
    }

    /** "1 день / 2 дня / 5 дней" or "1 day / 2 days". */
    public static String days(int n) {
        if (isRussian()) {
            int a = Math.abs(n);
            String word;
            if (a % 10 == 1 && a % 100 != 11) {
                word = "день";
            } else if (a % 10 >= 2 && a % 10 <= 4 && !(a % 100 >= 12 && a % 100 <= 14)) {
                word = "дня";
            } else {
                word = "дней";
            }
            return formatNumber(n) + " " + word;
        }
        return formatNumber(n) + (Math.abs(n) == 1 ? " day" : " days");
    }

    /** 18540 -> "18 540". */
    public static String formatNumber(long n) {
        return String.format(Locale.US, "%,d", n).replace(',', ' ');
    }
}
