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
    public static boolean typingAnimation;
    public static boolean eraseDust;
    // ghost mode: what is hidden when ghost mode is on (on/off itself is per account, see isGhost)
    public static boolean ghostRead;
    public static boolean ghostOnline;
    public static boolean ghostTyping;
    public static boolean ghostStories;
    public static boolean saveSecretMedia;
    public static boolean streakReminder;
    public static int streakReminderHour;
    public static boolean streakCelebration;
    public static boolean sendEffects;
    /** Chat background particles: {@link #PARTICLES_OFF}, snow, embers, ash, sparks. */
    public static int chatParticles;
    public static boolean springAnimations;
    /** Message text font, index in BloodyFonts. */
    public static int messageFont;
    public static String aiApiKey;
    public static String aiModel;

    public static final int PARTICLES_OFF = 0;
    public static final int PARTICLES_SNOW = 1;
    public static final int PARTICLES_EMBERS = 2;
    public static final int PARTICLES_ASH = 3;
    public static final int PARTICLES_SPARKS = 4;
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
            typingAnimation = prefs.getBoolean("typingAnimation", true);
            eraseDust = prefs.getBoolean("eraseDust", true);
            ghostRead = prefs.getBoolean("ghostRead", true);
            ghostOnline = prefs.getBoolean("ghostOnline", true);
            ghostTyping = prefs.getBoolean("ghostTyping", true);
            ghostStories = prefs.getBoolean("ghostStories", true);
            saveSecretMedia = prefs.getBoolean("saveSecretMedia", true);
            streakReminder = prefs.getBoolean("streakReminder", true);
            streakReminderHour = prefs.getInt("streakReminderHour", 21);
            streakCelebration = prefs.getBoolean("streakCelebration", true);
            sendEffects = prefs.getBoolean("sendEffects", true);
            chatParticles = prefs.getInt("chatParticles", chatSnow ? PARTICLES_SNOW : PARTICLES_OFF);
            springAnimations = prefs.getBoolean("springAnimations", true);
            messageFont = prefs.getInt("messageFont", 0);
            aiApiKey = prefs.getString("aiApiKey", "");
            aiModel = prefs.getString("aiModel", "claude-sonnet-5");
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
        return chatParticles == PARTICLES_SNOW;
    }

    /** Snow is on and particles are allowed by Lite Mode (the holiday snow needs the "high" preset only). */
    public static boolean isChatSnowAllowed() {
        return isChatSnow() && org.telegram.messenger.LiteMode.isEnabled(org.telegram.messenger.LiteMode.FLAG_PARTICLES);
    }

    public static void setChatSnow(boolean value) {
        putBoolean("chatSnow", chatSnow = value);
    }

    public static void setTypingAnimation(boolean value) {
        putBoolean("typingAnimation", typingAnimation = value);
    }

    public static void setEraseDust(boolean value) {
        putBoolean("eraseDust", eraseDust = value);
    }

    // region per account

    public static boolean isGhost(int account) {
        load();
        long userId = org.telegram.messenger.UserConfig.getInstance(account).getClientUserId();
        return userId != 0 && prefs().getBoolean("ghost_" + userId, false);
    }

    public static void setGhost(int account, boolean value) {
        long userId = org.telegram.messenger.UserConfig.getInstance(account).getClientUserId();
        putBoolean("ghost_" + userId, value);
    }

    /** Theme chosen for this account (applied when switching to it), null = keep the current one. */
    public static String getAccountTheme(int account) {
        long userId = org.telegram.messenger.UserConfig.getInstance(account).getClientUserId();
        return prefs().getString("theme_" + userId, null);
    }

    public static void setAccountTheme(int account, String theme) {
        long userId = org.telegram.messenger.UserConfig.getInstance(account).getClientUserId();
        if (theme == null) {
            prefs().edit().remove("theme_" + userId).apply();
        } else {
            prefs().edit().putString("theme_" + userId, theme).apply();
        }
    }

    // endregion

    public static void putInt(String key, int value) {
        prefs().edit().putInt(key, value).apply();
    }

    public static void putString(String key, String value) {
        prefs().edit().putString(key, value).apply();
    }

    public static void putBoolean(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
    }

    public static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
