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

    private static final int[] STREAK_MIN_DAYS = {0, 1, 2, 3, 5, 7, 10, 30, 100};

    @Override
    protected CharSequence getTitle() {
        return BloodyStrings.get(R.string.BloodySettings);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        BloodyConfig.load();
        items.add(UItem.asHeader(BloodyStrings.get(R.string.BloodySettingsMessages)));
        items.add(UItem.asCheck(ID_SAVE_DELETED, BloodyStrings.get(R.string.BloodySaveDeleted)).setChecked(BloodyConfig.saveDeletedMessages));
        items.add(UItem.asShadow(BloodyStrings.get(R.string.BloodySaveDeletedInfo)));
        items.add(UItem.asCheck(ID_SAVE_EDITS, BloodyStrings.get(R.string.BloodySaveEdits)).setChecked(BloodyConfig.saveEditHistory));
        items.add(UItem.asShadow(BloodyStrings.get(R.string.BloodySaveEditsInfo)));

        items.add(UItem.asHeader(BloodyStrings.get(R.string.BloodyAppearance)));
        items.add(UItem.asCheck(ID_SHOW_PEER_ID, BloodyStrings.get(R.string.BloodyShowPeerId)).setChecked(BloodyConfig.showPeerId));
        items.add(UItem.asCheck(ID_STREAK_NAME_COLOR, BloodyStrings.get(R.string.BloodyStreakNameColor)).setChecked(BloodyConfig.streakNameColor));
        items.add(UItem.asCheck(ID_CHAT_SNOW, BloodyStrings.get(R.string.BloodyChatSnow)).setChecked(BloodyConfig.chatSnow));
        items.add(UItem.asShadow(BloodyStrings.get(R.string.BloodyAppearanceInfo)));

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
        } else if (item.id == ID_CHAT_SNOW) {
            BloodyConfig.setChatSnow(!BloodyConfig.chatSnow);
            listView.adapter.update(true);
        } else if (item.id == ID_STREAK_RECALC) {
            BloodyStreaks.getInstance(currentAccount).recalcAll();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, BloodyStrings.get(R.string.BloodyStreakRecalcDone)).show();
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
