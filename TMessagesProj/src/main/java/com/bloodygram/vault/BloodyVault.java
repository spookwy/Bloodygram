package com.bloodygram.vault;

import android.app.Dialog;
import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.Base64;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.bloodygram.BloodyConfig;
import com.bloodygram.BloodyStrings;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Locked chats (PIN / fingerprint to open) and hidden chats (not shown in the chats list, opened from a secret list).
 * One Bloodygram PIN for both; the chat sets are per account.
 */
public class BloodyVault {

    public static final int MENU_HIDE = 10004;
    public static final int MENU_LOCK = 10005;

    private static final HashSet<Long>[] hidden = new HashSet[UserConfig.MAX_ACCOUNT_COUNT];
    private static final HashSet<Long>[] locked = new HashSet[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] loadedFor = new long[UserConfig.MAX_ACCOUNT_COUNT];

    // region PIN

    public static boolean hasPin() {
        return !TextUtils.isEmpty(BloodyConfig.prefs().getString("vault_pin", null));
    }

    public static boolean checkPin(String pin) {
        String saved = BloodyConfig.prefs().getString("vault_pin", null);
        return saved != null && saved.equals(hash(pin));
    }

    public static void setPin(String pin) {
        BloodyConfig.putString("vault_pin", pin == null ? "" : hash(pin));
    }

    private static String hash(String pin) {
        try {
            String salt = BloodyConfig.prefs().getString("vault_salt", null);
            if (salt == null) {
                byte[] bytes = new byte[16];
                new SecureRandom().nextBytes(bytes);
                salt = Base64.encodeToString(bytes, Base64.NO_WRAP);
                BloodyConfig.putString("vault_salt", salt);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.encodeToString(digest.digest((salt + pin).getBytes("UTF-8")), Base64.NO_WRAP);
        } catch (Exception e) {
            return pin;
        }
    }

    // endregion

    // region chat sets

    private static void load(int account) {
        long userId = UserConfig.getInstance(account).getClientUserId();
        if (hidden[account] != null && loadedFor[account] == userId) {
            return;
        }
        loadedFor[account] = userId;
        hidden[account] = parse(BloodyConfig.prefs().getString("hidden_" + userId, ""));
        locked[account] = parse(BloodyConfig.prefs().getString("locked_" + userId, ""));
    }

    private static HashSet<Long> parse(String s) {
        HashSet<Long> set = new HashSet<>();
        for (String part : s.split(",")) {
            try {
                if (!part.isEmpty()) {
                    set.add(Long.parseLong(part));
                }
            } catch (Exception ignore) {
            }
        }
        return set;
    }

    private static void save(int account) {
        long userId = UserConfig.getInstance(account).getClientUserId();
        BloodyConfig.putString("hidden_" + userId, TextUtils.join(",", hidden[account]));
        BloodyConfig.putString("locked_" + userId, TextUtils.join(",", locked[account]));
    }

    public static boolean isHidden(int account, long dialogId) {
        load(account);
        return !hidden[account].isEmpty() && hidden[account].contains(dialogId);
    }

    public static boolean isLocked(int account, long dialogId) {
        load(account);
        return !locked[account].isEmpty() && locked[account].contains(dialogId);
    }

    public static ArrayList<Long> getHidden(int account) {
        load(account);
        return new ArrayList<>(hidden[account]);
    }

    public static ArrayList<Long> getLocked(int account) {
        load(account);
        return new ArrayList<>(locked[account]);
    }

    public static void setHidden(int account, long dialogId, boolean value) {
        load(account);
        if (value) {
            hidden[account].add(dialogId);
        } else {
            hidden[account].remove(dialogId);
        }
        save(account);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
    }

    public static void setLocked(int account, long dialogId, boolean value) {
        load(account);
        if (value) {
            locked[account].add(dialogId);
        } else {
            locked[account].remove(dialogId);
        }
        save(account);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
    }

    /** Chats list without hidden chats (the same list when nothing is hidden). */
    public static ArrayList<TLRPC.Dialog> filterHidden(int account, ArrayList<TLRPC.Dialog> dialogs) {
        load(account);
        if (dialogs == null || hidden[account].isEmpty()) {
            return dialogs;
        }
        ArrayList<TLRPC.Dialog> result = new ArrayList<>(dialogs.size());
        for (int i = 0; i < dialogs.size(); i++) {
            TLRPC.Dialog dialog = dialogs.get(i);
            if (dialog == null || !hidden[account].contains(dialog.id)) {
                result.add(dialog);
            }
        }
        return result;
    }

    /** Message preview of a locked chat in the chats list. */
    public static CharSequence lockedPreview() {
        return new SpannableStringBuilder("🔒 ").append(BloodyStrings.get(R.string.BloodyLockedPreview));
    }

    // endregion

    // region UI entry points

    /** Covers a locked chat until the PIN / fingerprint is entered; back closes the chat. */
    public static void attachChatLock(BaseFragment fragment, ViewGroup root, long dialogId) {
        if (root == null || !isLocked(fragment.getCurrentAccount(), dialogId) || !hasPin()) {
            return;
        }
        BloodyLockView lock = new BloodyLockView(root.getContext(), BloodyLockView.MODE_ENTER, BloodyStrings.get(R.string.BloodyLockedTitle));
        lock.setOnDone(() -> lock.animate().alpha(0).setDuration(180).withEndAction(() -> {
            if (lock.getParent() != null) {
                ((ViewGroup) lock.getParent()).removeView(lock);
            }
        }).start());
        lock.setOnCancel(fragment::finishFragment);
        lock.setCloseVisible(false); // the chat header has its own back button
        root.addView(lock, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        lock.bringToFront();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.hideKeyboard(root), 50);
        lock.startBiometric();
    }

    /** Asks for the PIN (creates it first if there is none), then runs {@code onOk}. */
    public static void requirePin(Context context, Runnable onOk) {
        boolean create = !hasPin();
        Dialog dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout container = new FrameLayout(context);
        BloodyLockView lock = new BloodyLockView(context, create ? BloodyLockView.MODE_CREATE : BloodyLockView.MODE_ENTER,
                BloodyStrings.get(create ? R.string.BloodyPinCreate : R.string.BloodyPinEnter));
        lock.setOnDone(() -> {
            dialog.dismiss();
            onOk.run();
        });
        lock.setOnCancel(dialog::dismiss);
        container.addView(lock, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        dialog.setContentView(container);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
        dialog.setOnKeyListener((d, keyCode, event) -> false);
        dialog.show();
        if (!create) {
            lock.startBiometric();
        }
    }

    /** Hidden chats list, behind the PIN. */
    public static void openHiddenChats(BaseFragment fragment) {
        requirePin(fragment.getParentActivity(), () -> fragment.presentFragment(new BloodyHiddenChatsActivity()));
    }

    public static void toggleHidden(BaseFragment fragment, long dialogId) {
        int account = fragment.getCurrentAccount();
        boolean hide = !isHidden(account, dialogId);
        Runnable apply = () -> {
            setHidden(account, dialogId, hide);
            com.bloodygram.ui.BloodyChatMenu.relabel(account, dialogId);
            org.telegram.ui.Components.BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip,
                    BloodyStrings.get(hide ? R.string.BloodyHiddenDone : R.string.BloodyUnhiddenDone)).show();
        };
        if (hide && !hasPin()) {
            requirePin(fragment.getParentActivity(), apply); // hidden chats open with the PIN: create it first
        } else {
            apply.run();
        }
    }

    public static void toggleLocked(BaseFragment fragment, long dialogId) {
        int account = fragment.getCurrentAccount();
        boolean lock = !isLocked(account, dialogId);
        requirePin(fragment.getParentActivity(), () -> {
            setLocked(account, dialogId, lock);
            com.bloodygram.ui.BloodyChatMenu.relabel(account, dialogId);
            org.telegram.ui.Components.BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip,
                    BloodyStrings.get(lock ? R.string.BloodyLockDone : R.string.BloodyUnlockDone)).show();
        });
    }

    // endregion
}
