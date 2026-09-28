package com.bloodygram.ui;

import com.bloodygram.BloodyConfig;
import com.bloodygram.BloodyStrings;
import com.bloodygram.ghost.BloodyGhost;
import com.bloodygram.streaks.BloodyStreakUi;
import com.bloodygram.vault.BloodyVault;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.DialogsActivity;

/** Bloodygram items of the chat ⋮ menu and of the ⋮ menu over the chats list. */
public class BloodyChatMenu {

    public static void addItems(ChatActivity fragment, ActionBarMenuItem menu, int account, long dialogId) {
        BloodyStreakUi.addMenuItem(menu, account, dialogId, true);
        BloodyGhost.addMenuItem(menu, account, dialogId);
        if (menu == null) {
            return;
        }
        lastMenu = new java.lang.ref.WeakReference<>(menu);
        hideItem = menu.lazilyAddSubItem(BloodyVault.MENU_HIDE, R.drawable.msg_archive_hide, hideText(account, dialogId));
        lockItem = menu.lazilyAddSubItem(BloodyVault.MENU_LOCK, R.drawable.msg_secret, lockText(account, dialogId));
    }

    private static java.lang.ref.WeakReference<ActionBarMenuItem> lastMenu;
    private static ActionBarMenuItem.Item hideItem, lockItem;

    private static String hideText(int account, long dialogId) {
        return BloodyStrings.get(BloodyVault.isHidden(account, dialogId) ? R.string.BloodyUnhide : R.string.BloodyHide);
    }

    private static String lockText(int account, long dialogId) {
        return BloodyStrings.get(BloodyVault.isLocked(account, dialogId) ? R.string.BloodyUnlockChat : R.string.BloodyLockChat);
    }

    /** Menu texts after hide/lock changed (the lazy items keep the text they were created with). */
    public static void relabel(int account, long dialogId) {
        ActionBarMenuItem menu = lastMenu == null ? null : lastMenu.get();
        relabel(menu, hideItem, BloodyVault.MENU_HIDE, hideText(account, dialogId));
        relabel(menu, lockItem, BloodyVault.MENU_LOCK, lockText(account, dialogId));
    }

    private static void relabel(ActionBarMenuItem menu, ActionBarMenuItem.Item item, int id, String text) {
        if (item != null) {
            item.text = text;
        }
        try {
            android.view.View view = menu == null ? null : menu.getSubItem(id);
            if (view instanceof org.telegram.ui.ActionBar.ActionBarMenuSubItem) {
                ((org.telegram.ui.ActionBar.ActionBarMenuSubItem) view).setText(text);
            }
        } catch (Exception ignore) {
            // the popup is not created yet
        }
    }

    /** @return true if the item was ours */
    public static boolean onItemClick(ChatActivity fragment, int id, int account, long dialogId) {
        if (id == BloodyStreakUi.MENU_ID) {
            BloodyStreakUi.showStats(fragment, account, dialogId);
            return true;
        }
        if (id == BloodyVault.MENU_HIDE) {
            BloodyVault.toggleHidden(fragment, dialogId);
            return true;
        }
        if (id == BloodyVault.MENU_LOCK) {
            BloodyVault.toggleLocked(fragment, dialogId);
            return true;
        }
        if (id == BloodyGhost.MENU_READ) {
            BloodyGhost.readNow(fragment, account, dialogId);
            return true;
        }
        return false;
    }

    /** Changes whenever a chats list cell must be rebuilt because of Bloodygram state (streak, lock). */
    public static int cellHash(int account, long dialogId, boolean user) {
        int hash = user ? BloodyStreakUi.listHash(account, dialogId) : 0;
        if (BloodyVault.isLocked(account, dialogId)) {
            hash ^= 0x40000000;
        }
        return hash;
    }

    public static void addDialogsOptions(DialogsActivity fragment, ItemOptions io) {
        int account = fragment.getCurrentAccount();
        boolean ghost = BloodyConfig.isGhost(account);
        io.addChecked(ghost, R.drawable.ghost, BloodyStrings.get(R.string.BloodyGhostMode), () -> {
            boolean enabled = !BloodyConfig.isGhost(account);
            BloodyGhost.setEnabled(account, enabled);
            refreshTitle(fragment);
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip,
                    BloodyStrings.get(enabled ? R.string.BloodyGhostOn : R.string.BloodyGhostOff)).show();
        });
        if (BloodyVault.hasPin()) {
            io.add(R.drawable.msg_archive_hide, BloodyStrings.get(R.string.BloodyHiddenChats), () -> BloodyVault.openHiddenChats(fragment));
        }
    }

    public static void refreshTitle(DialogsActivity fragment) {
        if (fragment.getActionBar() == null) {
            return;
        }
        SimpleTextView title = fragment.getActionBar().getTitleTextView();
        if (title != null) {
            title.setText(BloodyStrings.appTitle(fragment.getCurrentAccount()));
        }
    }
}
