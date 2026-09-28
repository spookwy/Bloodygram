package com.bloodygram.ui;

import com.bloodygram.BloodyConfig;
import com.bloodygram.BloodyStrings;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Components.BulletinFactory;

/** "1329248998 / ID" row in the profile info block (like AyuGram), tap copies the id. */
public class BloodyProfile {

    public static boolean showId() {
        BloodyConfig.load();
        return BloodyConfig.showPeerId;
    }

    /** Bot API style id: user id as is, -100… for channels/supergroups, -… for basic groups. */
    public static String peerId(int account, long userId, long chatId) {
        if (userId != 0) {
            return String.valueOf(userId);
        }
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(chatId);
        return (chat == null || ChatObject.isChannel(chat) ? "-100" : "-") + chatId;
    }

    public static void bindIdRow(TextDetailCell cell, int account, long userId, long chatId, boolean divider) {
        cell.setTextAndValue(peerId(account, userId, chatId), "ID", divider);
        cell.setImage(null);
        cell.setImageClickListener(null);
    }

    public static void copyId(BaseFragment fragment, int account, long userId, long chatId) {
        AndroidUtilities.addToClipboard(peerId(account, userId, chatId));
        if (AndroidUtilities.shouldShowClipboardToast()) {
            BulletinFactory.of(fragment).createCopyBulletin(BloodyStrings.get(R.string.BloodyIdCopied)).show();
        }
    }
}
