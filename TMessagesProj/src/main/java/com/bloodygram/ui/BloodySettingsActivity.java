package com.bloodygram.ui;

import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;

import com.bloodygram.BloodyConfig;
import com.bloodygram.BloodyStrings;
import com.bloodygram.keepalive.BloodyKeepAliveService;
import com.bloodygram.streaks.BloodyStreaks;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

public class BloodySettingsActivity extends UniversalFragment {

    private static final int ID_SAVE_DELETED = 1;
    private static final int ID_SAVE_EDITS = 2;
    private static final int ID_STREAKS = 3;
    private static final int ID_KEEP_ALIVE = 4;
    private static final int ID_BATTERY = 5;
    private static final int ID_STREAK_LIST = 6;
    private static final int ID_STREAK_HEADER = 7;
    private static final int ID_STREAK_PROFILE = 8;
    private static final int ID_STREAK_PROFILE_ROW = 9;
    private static final int ID_STREAK_RECALC = 10;
    private static final int ID_SHOW_PEER_ID = 11;
    private static final int ID_STREAK_NAME_COLOR = 12;
    private static final int ID_CHAT_SNOW = 13;
    private static final int ID_TYPING_ANIMATION = 14;
    private static final int ID_ERASE_DUST = 15;
    private static final int ID_GHOST = 16;
    private static final int ID_GHOST_READ = 17;
    private static final int ID_GHOST_ONLINE = 18;
    private static final int ID_GHOST_TYPING = 19;
    private static final int ID_GHOST_STORIES = 20;
    private static final int ID_SAVE_SECRET = 21;
    private static final int ID_HIDDEN_CHATS = 22;
    private static final int ID_CHANGE_PIN = 23;
    private static final int ID_STREAK_REMINDER = 24;
    private static final int ID_STREAK_CELEBRATION = 25;
    private static final int ID_SEND_EFFECTS = 26;
    private static final int ID_SPRING = 27;
    private static final int ID_BLUR = 28;
    private static final int ID_FONT = 29;
    private static final int ID_ACCOUNT_THEME = 30;
    private static final int ID_AI_KEY = 31;
    private static final int ID_AI_MODEL = 32;
    private static final int ID_TRANSCRIBE_LANG = 33;
    private static final int ID_WRAPPED = 34;
    private static final int ID_LIQUID_GLASS = 35;

    private static final String[] TRANSCRIBE_LANGS = {"", "ru-RU", "uk-UA", "en-US"};

    private static final int[] BUBBLE_RADII = {0, 4, 8, 12, 17};

    private static final int[] REMINDER_HOURS = {18, 19, 20, 21, 22, 23};

    private static final int[] STREAK_MIN_DAYS = {0, 1, 2, 3, 5, 7, 10, 30, 100};

    @Override
    protected CharSequence getTitle() {
        return BloodyStrings.get(R.string.BloodySettings);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        BloodyConfig.load();
        boolean ghost = BloodyConfig.isGhost(currentAccount);
        items.add(UItem.asHeader(BloodyStrings.get(R.string.BloodyGhostSection)));
        items.add(UItem.asCheck(ID_GHOST, BloodyStrings.get(R.string.BloodyGhostEnabled)).setChecked(ghost));
        if (ghost) {
            items.add(UItem.asCheck(ID_GHOST_READ, BloodyStrings.get(R.string.BloodyGhostHideRead)).setChecked(BloodyConfig.ghostRead));
            items.add(UItem.asCheck(ID_GHOST_ONLINE, BloodyStrings.get(R.string.BloodyGhostHideOnline)).setChecked(BloodyConfig.ghostOnline));
            items.add(UItem.asCheck(ID_GHOST_TYPING, BloodyStrings.get(R.string.BloodyGhostHideTyping)).setChecked(BloodyConfig.ghostTyping));
            items.add(UItem.asCheck(ID_GHOST_STORIES, BloodyStrings.get(R.string.BloodyGhostHideStories)).setChecked(BloodyConfig.ghostStories));
        }
        items.add(UItem.asShadow(BloodyStrings.get(R.string.BloodyGhostInfo)));

        items.add(UItem.asHeader(BloodyStrings.get(R.string.BloodyPrivacySection)));
        items.add(UItem.asCheck(ID_SAVE_SECRET, BloodyStrings.get(R.string.BloodySaveSecret)).setChecked(BloodyConfig.saveSecretMedia));
        items.add(UItem.asButton(ID_HIDDEN_CHATS, R.drawable.msg_archive_hide, BloodyStrings.get(R.string.BloodyHiddenChats)));
        if (com.bloodygram.vault.BloodyVault.hasPin()) {
            items.add(UItem.asButton(ID_CHANGE_PIN, R.drawable.msg_secret, BloodyStrings.get(R.string.BloodyChangePin)));
        }
        items.add(UItem.asShadow(BloodyStrings.format(R.string.BloodySaveSecretInfo, BloodyStrings.formatNumber(BloodyConfig.prefs().getInt("secretSaved", 0)))));

        items.add(UItem.asHeader(BloodyStrings.get(R.string.BloodyAiSection)));
        items.add(UItem.asButton(ID_AI_KEY, BloodyStrings.get(R.string.BloodyAiKey), com.bloodygram.ai.BloodyAi.hasKey() ? "••••" + BloodyConfig.aiApiKey.substring(Math.max(0, BloodyConfig.aiApiKey.length() - 4)) : BloodyStrings.get(R.string.BloodyAiKeyNone)));
        items.add(UItem.asButton(ID_AI_MODEL, BloodyStrings.get(R.string.BloodyAiModel), BloodyConfig.aiModel));
        items.add(UItem.asButton(ID_TRANSCRIBE_LANG, BloodyStrings.get(R.string.BloodyTranscribeLang), transcribeLangName(BloodyConfig.prefs().getString("transcribeLang", ""))));
        items.add(UItem.asShadow(BloodyStrings.get(R.string.BloodyAiInfo)));

        items.add(UItem.asButton(ID_WRAPPED, R.drawable.msg_stats, BloodyStrings.get(R.string.BloodyWrapped)).accent());
        items.add(UItem.asShadow(BloodyStrings.get(R.string.BloodyWrappedInfo)));

        items.add(UItem.asHeader(BloodyStrings.get(R.string.BloodySettingsMessages)));
        items.add(UItem.asCheck(ID_SAVE_DELETED, BloodyStrings.get(R.string.BloodySaveDeleted)).setChecked(BloodyConfig.saveDeletedMessages));
        items.add(UItem.asShadow(BloodyStrings.get(R.string.BloodySaveDeletedInfo)));
        items.add(UItem.asCheck(ID_SAVE_EDITS, BloodyStrings.get(R.string.BloodySaveEdits)).setChecked(BloodyConfig.saveEditHistory));
        items.add(UItem.asShadow(BloodyStrings.get(R.string.BloodySaveEditsInfo)));

        items.add(UItem.asHeader(BloodyStrings.get(R.string.BloodyAppearance)));
        items.add(UItem.asButton(ID_ACCOUNT_THEME, BloodyStrings.get(R.string.BloodyAccountTheme), BloodyAccounts.themeLabel(currentAccount)));
        items.add(UItem.asCheck(ID_SHOW_PEER_ID, BloodyStrings.get(R.string.BloodyShowPeerId)).setChecked(BloodyConfig.showPeerId));
        items.add(UItem.asCheck(ID_STREAK_NAME_COLOR, BloodyStrings.get(R.string.BloodyStreakNameColor)).setChecked(BloodyConfig.streakNameColor));
        items.add(UItem.asCheck(ID_TYPING_ANIMATION, BloodyStrings.get(R.string.BloodyTypingAnimation)).setChecked(BloodyConfig.typingAnimation));
        items.add(UItem.asCheck(ID_ERASE_DUST, BloodyStrings.get(R.string.BloodyEraseDust)).setChecked(BloodyConfig.eraseDust));
        items.add(UItem.asCheck(ID_SEND_EFFECTS, BloodyStrings.get(R.string.BloodySendEffects)).setChecked(BloodyConfig.sendEffects));
        items.add(UItem.asCheck(ID_SPRING, BloodyStrings.get(R.string.BloodySpring)).setChecked(BloodyConfig.springAnimations));
        if (BloodyMotion.canBlur()) {
            items.add(UItem.asCheck(ID_BLUR, BloodyStrings.get(R.string.BloodyBlur)).setChecked(BloodyMotion.isBlurOn()));
            if (BloodyMotion.isBlurOn()) {
                items.add(UItem.asCheck(ID_LIQUID_GLASS, BloodyStrings.get(R.string.BloodyLiquidGlass)).setChecked(BloodyMotion.isLiquidGlassOn()));
            }
        }
        items.add(UItem.asButton(ID_FONT, BloodyStrings.get(R.string.BloodyFont), BloodyFonts.names()[Math.max(0, Math.min(BloodyFonts.names().length - 1, BloodyConfig.messageFont))]));
        items.add(UItem.asShadow(BloodyStrings.get(R.string.BloodyAppearanceInfo)));

        items.add(UItem.asHeader(BloodyStrings.get(R.string.BloodyChatEffect)));
        String[] effects = {
                BloodyStrings.get(R.string.BloodyEffectOff), BloodyStrings.get(R.string.BloodyEffectSnow),
                BloodyStrings.get(R.string.BloodyEffectAsh), BloodyStrings.get(R.string.BloodyEffectSparks),
                BloodyStrings.get(R.string.BloodyEffectTopo)
        };
        items.add(UItem.asSlideView(effects, Math.max(0, Math.min(effects.length - 1, BloodyConfig.chatParticles)), index -> BloodyConfig.putInt("chatParticles2", BloodyConfig.chatParticles = index)));
        items.add(UItem.asHeader(BloodyStrings.get(R.string.BloodyBubbleRadius)));
        String[] radii = new String[BUBBLE_RADII.length];
        int chosenRadius = 0;
        for (int i = 0; i < radii.length; i++) {
            radii[i] = String.valueOf(BUBBLE_RADII[i]);
            if (Math.abs(BUBBLE_RADII[i] - SharedConfig.bubbleRadius) < Math.abs(BUBBLE_RADII[chosenRadius] - SharedConfig.bubbleRadius)) {
                chosenRadius = i;
            }
        }
        items.add(UItem.asSlideView(radii, chosenRadius, index -> {
            SharedConfig.bubbleRadius = BUBBLE_RADII[index];
            MessagesController.getGlobalMainSettings().edit().putInt("bubbleRadius", SharedConfig.bubbleRadius).apply();
        }));
        items.add(UItem.asShadow(BloodyStrings.get(R.string.BloodyChatEffectInfo)));

        items.add(UItem.asHeader(BloodyStrings.get(R.string.BloodyBackground)));
        items.add(UItem.asCheck(ID_KEEP_ALIVE, BloodyStrings.get(R.string.BloodyKeepAlive)).setChecked(BloodyConfig.keepAlive));
        items.add(UItem.asButton(ID_BATTERY, BloodyStrings.get(R.string.BloodyBatterySettings)));
        items.add(UItem.asShadow(BloodyStrings.get(R.string.BloodyKeepAliveInfo)));

        items.add(UItem.asHeader(BloodyStrings.get(R.string.BloodyStreaks)));
        items.add(UItem.asCheck(ID_STREAKS, BloodyStrings.get(R.string.BloodyStreaksEnabled)).setChecked(BloodyConfig.streaksEnabled));
        if (BloodyConfig.streaksEnabled) {
            items.add(UItem.asCheck(ID_STREAK_LIST, BloodyStrings.get(R.string.BloodyStreakShowList)).setChecked(BloodyConfig.streakInList));
            items.add(UItem.asCheck(ID_STREAK_HEADER, BloodyStrings.get(R.string.BloodyStreakShowHeader)).setChecked(BloodyConfig.streakInHeader));
            items.add(UItem.asCheck(ID_STREAK_PROFILE, BloodyStrings.get(R.string.BloodyStreakShowProfile)).setChecked(BloodyConfig.streakInProfile));
            items.add(UItem.asCheck(ID_STREAK_PROFILE_ROW, BloodyStrings.get(R.string.BloodyStreakShowProfileRow)).setChecked(BloodyConfig.streakProfileRow));
            items.add(UItem.asHeader(BloodyStrings.get(R.string.BloodyStreakMinDays)));
            int chosen = 0;
            for (int i = 0; i < STREAK_MIN_DAYS.length; i++) {
                if (STREAK_MIN_DAYS[i] == BloodyConfig.streakMinDays) {
                    chosen = i;
                }
            }
            String[] choices = new String[STREAK_MIN_DAYS.length];
            for (int i = 0; i < choices.length; i++) {
                choices[i] = String.valueOf(STREAK_MIN_DAYS[i]);
            }
            items.add(UItem.asSlideView(choices, chosen, index -> {
                BloodyConfig.setStreakMinDays(STREAK_MIN_DAYS[index]);
                refreshDialogs();
            }));
        }
        items.add(UItem.asShadow(BloodyStrings.get(R.string.BloodyStreaksInfo)));
        if (BloodyConfig.streaksEnabled) {
            items.add(UItem.asCheck(ID_STREAK_REMINDER, BloodyStrings.get(R.string.BloodyStreakReminder)).setChecked(BloodyConfig.streakReminder));
            if (BloodyConfig.streakReminder) {
                items.add(UItem.asHeader(BloodyStrings.get(R.string.BloodyStreakReminderTime)));
                String[] hours = new String[REMINDER_HOURS.length];
                int chosenHour = 0;
                for (int i = 0; i < hours.length; i++) {
                    hours[i] = REMINDER_HOURS[i] + ":00";
                    if (REMINDER_HOURS[i] == BloodyConfig.streakReminderHour) {
                        chosenHour = i;
                    }
                }
                items.add(UItem.asSlideView(hours, chosenHour, index -> {
                    BloodyConfig.putInt("streakReminderHour", BloodyConfig.streakReminderHour = REMINDER_HOURS[index]);
                    com.bloodygram.streaks.BloodyStreakReminder.schedule();
                }));
            }
            items.add(UItem.asCheck(ID_STREAK_CELEBRATION, BloodyStrings.get(R.string.BloodyStreakCelebration)).setChecked(BloodyConfig.streakCelebration));
            items.add(UItem.asShadow(BloodyStrings.get(R.string.BloodyStreakCelebrationInfo)));
            items.add(UItem.asButton(ID_STREAK_RECALC, BloodyStrings.get(R.string.BloodyStreakRecalc)).accent());
            items.add(UItem.asShadow(null));
        }
    }

    private void refreshDialogs() {
        getNotificationCenter().postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_SAVE_DELETED) {
            BloodyConfig.setSaveDeletedMessages(!BloodyConfig.saveDeletedMessages);
            listView.adapter.update(true);
        } else if (item.id == ID_SAVE_EDITS) {
            BloodyConfig.setSaveEditHistory(!BloodyConfig.saveEditHistory);
            listView.adapter.update(true);
        } else if (item.id == ID_KEEP_ALIVE) {
            BloodyConfig.setKeepAlive(!BloodyConfig.keepAlive);
            if (!BloodyConfig.keepAlive) {
                BloodyKeepAliveService.stop();
            }
            ApplicationLoader.startPushService();
            listView.adapter.update(true);
        } else if (item.id == ID_BATTERY) {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ApplicationLoader.applicationContext.getPackageName()));
                getParentActivity().startActivity(intent);
            } catch (Exception e) {
                FileLog.e(e);
            }
        } else if (item.id == ID_STREAKS) {
            BloodyConfig.setStreaksEnabled(!BloodyConfig.streaksEnabled);
            listView.adapter.update(true);
            refreshDialogs();
        } else if (item.id == ID_STREAK_LIST) {
            BloodyConfig.setStreakInList(!BloodyConfig.streakInList);
            listView.adapter.update(true);
            refreshDialogs();
        } else if (item.id == ID_STREAK_HEADER) {
            BloodyConfig.setStreakInHeader(!BloodyConfig.streakInHeader);
            listView.adapter.update(true);
            refreshDialogs();
        } else if (item.id == ID_STREAK_PROFILE) {
            BloodyConfig.setStreakInProfile(!BloodyConfig.streakInProfile);
            listView.adapter.update(true);
            refreshDialogs();
        } else if (item.id == ID_STREAK_PROFILE_ROW) {
            BloodyConfig.setStreakProfileRow(!BloodyConfig.streakProfileRow);
            listView.adapter.update(true);
        } else if (item.id == ID_SHOW_PEER_ID) {
            BloodyConfig.setShowPeerId(!BloodyConfig.showPeerId);
            listView.adapter.update(true);
        } else if (item.id == ID_STREAK_NAME_COLOR) {
            BloodyConfig.setStreakNameColor(!BloodyConfig.streakNameColor);
            listView.adapter.update(true);
            refreshDialogs();
        } else if (item.id == ID_SEND_EFFECTS) {
            BloodyConfig.putBoolean("sendEffects", BloodyConfig.sendEffects = !BloodyConfig.sendEffects);
            listView.adapter.update(true);
        } else if (item.id == ID_SPRING) {
            BloodyConfig.putBoolean("springAnimations", BloodyConfig.springAnimations = !BloodyConfig.springAnimations);
            listView.adapter.update(true);
        } else if (item.id == ID_BLUR) {
            BloodyMotion.setBlur(!BloodyMotion.isBlurOn());
            listView.adapter.update(true);
        } else if (item.id == ID_LIQUID_GLASS) {
            BloodyMotion.setLiquidGlass(!BloodyMotion.isLiquidGlassOn());
            listView.adapter.update(true);
        } else if (item.id == ID_AI_KEY) {
            android.widget.EditText input = new android.widget.EditText(getParentActivity());
            input.setHint("sk-ant-...");
            input.setText(BloodyConfig.aiApiKey);
            input.setSingleLine(true);
            input.setTextColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_dialogTextBlack));
            android.widget.FrameLayout box = new android.widget.FrameLayout(getParentActivity());
            box.setPadding(org.telegram.messenger.AndroidUtilities.dp(24), 0, org.telegram.messenger.AndroidUtilities.dp(24), 0);
            box.addView(input);
            new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity())
                    .setTitle(BloodyStrings.get(R.string.BloodyAiKey))
                    .setMessage(BloodyStrings.get(R.string.BloodyAiKeyHint))
                    .setView(box)
                    .setPositiveButton(BloodyStrings.get(R.string.BloodySave), (d, w) -> {
                        BloodyConfig.putString("aiApiKey", BloodyConfig.aiApiKey = input.getText().toString().trim());
                        listView.adapter.update(true);
                    })
                    .setNegativeButton(BloodyStrings.get(R.string.BloodyClose), null)
                    .show();
        } else if (item.id == ID_AI_MODEL) {
            new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity())
                    .setTitle(BloodyStrings.get(R.string.BloodyAiModel))
                    .setItems(com.bloodygram.ai.BloodyAi.MODELS, (d, which) -> {
                        BloodyConfig.putString("aiModel", BloodyConfig.aiModel = com.bloodygram.ai.BloodyAi.MODELS[which]);
                        listView.adapter.update(true);
                    })
                    .show();
        } else if (item.id == ID_TRANSCRIBE_LANG) {
            String[] names = new String[TRANSCRIBE_LANGS.length];
            for (int i = 0; i < names.length; i++) {
                names[i] = transcribeLangName(TRANSCRIBE_LANGS[i]);
            }
            new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity())
                    .setTitle(BloodyStrings.get(R.string.BloodyTranscribeLang))
                    .setItems(names, (d, which) -> {
                        BloodyConfig.putString("transcribeLang", TRANSCRIBE_LANGS[which]);
                        listView.adapter.update(true);
                    })
                    .show();
        } else if (item.id == ID_WRAPPED) {
            com.bloodygram.stats.BloodyWrappedUi.show(this, currentAccount);
        } else if (item.id == ID_ACCOUNT_THEME) {
            BloodyAccounts.chooseTheme(getParentActivity(), currentAccount, () -> listView.adapter.update(true));
        } else if (item.id == ID_FONT) {
            org.telegram.ui.ActionBar.AlertDialog.Builder builder = new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity());
            builder.setTitle(BloodyStrings.get(R.string.BloodyFont));
            builder.setItems(BloodyFonts.names(), (dialog, which) -> {
                BloodyConfig.putInt("messageFont", BloodyConfig.messageFont = which);
                org.telegram.ui.ActionBar.Theme.createCommonMessageResources();
                listView.adapter.update(true);
            });
            showDialog(builder.create());
        } else if (item.id == ID_TYPING_ANIMATION) {
            BloodyConfig.setTypingAnimation(!BloodyConfig.typingAnimation);
            listView.adapter.update(true);
        } else if (item.id == ID_ERASE_DUST) {
            BloodyConfig.setEraseDust(!BloodyConfig.eraseDust);
            listView.adapter.update(true);
        } else if (item.id == ID_GHOST) {
            com.bloodygram.ghost.BloodyGhost.setEnabled(currentAccount, !BloodyConfig.isGhost(currentAccount));
            listView.adapter.update(true);
        } else if (item.id == ID_GHOST_READ) {
            BloodyConfig.putBoolean("ghostRead", BloodyConfig.ghostRead = !BloodyConfig.ghostRead);
            listView.adapter.update(true);
        } else if (item.id == ID_GHOST_ONLINE) {
            BloodyConfig.putBoolean("ghostOnline", BloodyConfig.ghostOnline = !BloodyConfig.ghostOnline);
            listView.adapter.update(true);
        } else if (item.id == ID_GHOST_TYPING) {
            BloodyConfig.putBoolean("ghostTyping", BloodyConfig.ghostTyping = !BloodyConfig.ghostTyping);
            listView.adapter.update(true);
        } else if (item.id == ID_GHOST_STORIES) {
            BloodyConfig.putBoolean("ghostStories", BloodyConfig.ghostStories = !BloodyConfig.ghostStories);
            listView.adapter.update(true);
        } else if (item.id == ID_STREAK_REMINDER) {
            BloodyConfig.putBoolean("streakReminder", BloodyConfig.streakReminder = !BloodyConfig.streakReminder);
            com.bloodygram.streaks.BloodyStreakReminder.schedule();
            listView.adapter.update(true);
        } else if (item.id == ID_STREAK_CELEBRATION) {
            BloodyConfig.putBoolean("streakCelebration", BloodyConfig.streakCelebration = !BloodyConfig.streakCelebration);
            listView.adapter.update(true);
        } else if (item.id == ID_SAVE_SECRET) {
            BloodyConfig.putBoolean("saveSecretMedia", BloodyConfig.saveSecretMedia = !BloodyConfig.saveSecretMedia);
            listView.adapter.update(true);
        } else if (item.id == ID_HIDDEN_CHATS) {
            com.bloodygram.vault.BloodyVault.openHiddenChats(this);
        } else if (item.id == ID_CHANGE_PIN) {
            com.bloodygram.vault.BloodyVault.requirePin(getParentActivity(), () -> {
                com.bloodygram.vault.BloodyVault.setPin(null);
                com.bloodygram.vault.BloodyVault.requirePin(getParentActivity(), () ->
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, BloodyStrings.get(R.string.BloodyPinChanged)).show());
            });
        } else if (item.id == ID_STREAK_RECALC) {
            BloodyStreaks.getInstance(currentAccount).recalcAll();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, BloodyStrings.get(R.string.BloodyStreakRecalcDone)).show();
        }
    }

    private static String transcribeLangName(String tag) {
        switch (tag) {
            case "ru-RU": return "Русский";
            case "uk-UA": return "Українська";
            case "en-US": return "English";
            default: return BloodyStrings.get(R.string.BloodyTranscribeLangAuto);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
