package com.epicgram.ui;

import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;

import com.epicgram.EpicConfig;
import com.epicgram.EpicStrings;
import com.epicgram.keepalive.EpicKeepAliveService;
import com.epicgram.streaks.EpicStreaks;

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

public class EpicSettingsActivity extends UniversalFragment {

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

    private static final int[] STREAK_MIN_DAYS = {0, 1, 2, 3, 5, 7, 10, 30, 100};

    @Override
    protected CharSequence getTitle() {
        return EpicStrings.get(R.string.EpicSettings);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EpicConfig.load();
        items.add(UItem.asHeader(EpicStrings.get(R.string.EpicSettingsMessages)));
        items.add(UItem.asCheck(ID_SAVE_DELETED, EpicStrings.get(R.string.EpicSaveDeleted)).setChecked(EpicConfig.saveDeletedMessages));
        items.add(UItem.asShadow(EpicStrings.get(R.string.EpicSaveDeletedInfo)));
        items.add(UItem.asCheck(ID_SAVE_EDITS, EpicStrings.get(R.string.EpicSaveEdits)).setChecked(EpicConfig.saveEditHistory));
        items.add(UItem.asShadow(EpicStrings.get(R.string.EpicSaveEditsInfo)));

        items.add(UItem.asHeader(EpicStrings.get(R.string.EpicBackground)));
        items.add(UItem.asCheck(ID_KEEP_ALIVE, EpicStrings.get(R.string.EpicKeepAlive)).setChecked(EpicConfig.keepAlive));
        items.add(UItem.asButton(ID_BATTERY, EpicStrings.get(R.string.EpicBatterySettings)));
        items.add(UItem.asShadow(EpicStrings.get(R.string.EpicKeepAliveInfo)));

        items.add(UItem.asHeader(EpicStrings.get(R.string.EpicStreaks)));
        items.add(UItem.asCheck(ID_STREAKS, EpicStrings.get(R.string.EpicStreaksEnabled)).setChecked(EpicConfig.streaksEnabled));
        if (EpicConfig.streaksEnabled) {
            items.add(UItem.asCheck(ID_STREAK_LIST, EpicStrings.get(R.string.EpicStreakShowList)).setChecked(EpicConfig.streakInList));
            items.add(UItem.asCheck(ID_STREAK_HEADER, EpicStrings.get(R.string.EpicStreakShowHeader)).setChecked(EpicConfig.streakInHeader));
            items.add(UItem.asCheck(ID_STREAK_PROFILE, EpicStrings.get(R.string.EpicStreakShowProfile)).setChecked(EpicConfig.streakInProfile));
            items.add(UItem.asCheck(ID_STREAK_PROFILE_ROW, EpicStrings.get(R.string.EpicStreakShowProfileRow)).setChecked(EpicConfig.streakProfileRow));
            items.add(UItem.asHeader(EpicStrings.get(R.string.EpicStreakMinDays)));
            int chosen = 0;
            for (int i = 0; i < STREAK_MIN_DAYS.length; i++) {
                if (STREAK_MIN_DAYS[i] == EpicConfig.streakMinDays) {
                    chosen = i;
                }
            }
            String[] choices = new String[STREAK_MIN_DAYS.length];
            for (int i = 0; i < choices.length; i++) {
                choices[i] = String.valueOf(STREAK_MIN_DAYS[i]);
            }
            items.add(UItem.asSlideView(choices, chosen, index -> {
                EpicConfig.setStreakMinDays(STREAK_MIN_DAYS[index]);
                refreshDialogs();
            }));
        }
        items.add(UItem.asShadow(EpicStrings.get(R.string.EpicStreaksInfo)));
        if (EpicConfig.streaksEnabled) {
            items.add(UItem.asButton(ID_STREAK_RECALC, EpicStrings.get(R.string.EpicStreakRecalc)).accent());
            items.add(UItem.asShadow(null));
        }
    }

    private void refreshDialogs() {
        getNotificationCenter().postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_SAVE_DELETED) {
            EpicConfig.setSaveDeletedMessages(!EpicConfig.saveDeletedMessages);
            listView.adapter.update(true);
        } else if (item.id == ID_SAVE_EDITS) {
            EpicConfig.setSaveEditHistory(!EpicConfig.saveEditHistory);
            listView.adapter.update(true);
        } else if (item.id == ID_KEEP_ALIVE) {
            EpicConfig.setKeepAlive(!EpicConfig.keepAlive);
            if (!EpicConfig.keepAlive) {
                EpicKeepAliveService.stop();
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
            EpicConfig.setStreaksEnabled(!EpicConfig.streaksEnabled);
            listView.adapter.update(true);
            refreshDialogs();
        } else if (item.id == ID_STREAK_LIST) {
            EpicConfig.setStreakInList(!EpicConfig.streakInList);
            listView.adapter.update(true);
            refreshDialogs();
        } else if (item.id == ID_STREAK_HEADER) {
            EpicConfig.setStreakInHeader(!EpicConfig.streakInHeader);
            listView.adapter.update(true);
            refreshDialogs();
        } else if (item.id == ID_STREAK_PROFILE) {
            EpicConfig.setStreakInProfile(!EpicConfig.streakInProfile);
            listView.adapter.update(true);
            refreshDialogs();
        } else if (item.id == ID_STREAK_PROFILE_ROW) {
            EpicConfig.setStreakProfileRow(!EpicConfig.streakProfileRow);
            listView.adapter.update(true);
        } else if (item.id == ID_STREAK_RECALC) {
            EpicStreaks.getInstance(currentAccount).recalcAll();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, EpicStrings.get(R.string.EpicStreakRecalcDone)).show();
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
