package com.bloodygram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

public class BloodyConfig {

    private static final String PREFS = "epicgram_config";

    public static boolean saveDeletedMessages;
    public static boolean saveEditHistory;
    public static boolean streaksEnabled;
    public static int streakMinDays;
    public static boolean streakInList;
    public static boolean streakInHeader;
    public static boolean streakInProfile;
    public static boolean streakProfileRow;
    public static boolean keepAlive;
    public static boolean showPeerId;
    public static boolean streakNameColor;
    public static boolean chatSnow;
    /** Unix time when counting of deleted messages started (shown in the stats window). */
    public static int deletedSince;

    private static boolean loaded;

    public static void load() {
        if (loaded) {
            return;
        }
        synchronized (BloodyConfig.class) {
            if (loaded) {
                return;
            }
            SharedPreferences prefs = prefs();
            saveDeletedMessages = prefs.getBoolean("saveDeletedMessages", true);
            saveEditHistory = prefs.getBoolean("saveEditHistory", true);
            streaksEnabled = prefs.getBoolean("streaksEnabled", true);
            streakMinDays = prefs.getInt("streakMinDays2", 0);
            streakInList = prefs.getBoolean("streakInList", true);
            streakInHeader = prefs.getBoolean("streakInHeader", true);
            streakInProfile = prefs.getBoolean("streakInProfile", true);
            streakProfileRow = prefs.getBoolean("streakProfileRow", true);
            keepAlive = prefs.getBoolean("keepAlive", true);
            showPeerId = prefs.getBoolean("showPeerId", true);
            streakNameColor = prefs.getBoolean("streakNameColor", true);
            chatSnow = prefs.getBoolean("chatSnow", true);
            deletedSince = prefs.getInt("deletedSince", 0);
            if (deletedSince == 0) {
                deletedSince = (int) (System.currentTimeMillis() / 1000);
                prefs.edit().putInt("deletedSince", deletedSince).apply();
            }
            loaded = true;
        }
    }

    public static void setSaveDeletedMessages(boolean value) {
        putBoolean("saveDeletedMessages", saveDeletedMessages = value);
    }

    public static void setSaveEditHistory(boolean value) {
        putBoolean("saveEditHistory", saveEditHistory = value);
    }

    public static void setStreaksEnabled(boolean value) {
        putBoolean("streaksEnabled", streaksEnabled = value);
    }

    public static void setStreakMinDays(int value) {
        streakMinDays = value;
        prefs().edit().putInt("streakMinDays2", value).apply();
    }

    public static void setStreakInList(boolean value) {
        putBoolean("streakInList", streakInList = value);
    }

    public static void setStreakInHeader(boolean value) {
        putBoolean("streakInHeader", streakInHeader = value);
    }

    public static void setStreakInProfile(boolean value) {
        putBoolean("streakInProfile", streakInProfile = value);
    }

    public static void setStreakProfileRow(boolean value) {
        putBoolean("streakProfileRow", streakProfileRow = value);
    }

    public static void setKeepAlive(boolean value) {
        putBoolean("keepAlive", keepAlive = value);
    }

    public static void setShowPeerId(boolean value) {
        putBoolean("showPeerId", showPeerId = value);
    }

    public static void setStreakNameColor(boolean value) {
        putBoolean("streakNameColor", streakNameColor = value);
    }

    /** Falling snow on the chat background all year (Telegram shows it only on holidays). */
    public static boolean isChatSnow() {
        load();
        return chatSnow;
    }

    /** Snow is on and particles are allowed by Lite Mode (the holiday snow needs the "high" preset only). */
    public static boolean isChatSnowAllowed() {
        return isChatSnow() && org.telegram.messenger.LiteMode.isEnabled(org.telegram.messenger.LiteMode.FLAG_PARTICLES);
    }

    public static void setChatSnow(boolean value) {
        putBoolean("chatSnow", chatSnow = value);
    }

    private static void putBoolean(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
