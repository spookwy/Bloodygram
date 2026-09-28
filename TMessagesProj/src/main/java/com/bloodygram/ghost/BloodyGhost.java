package com.bloodygram.ghost;

import com.bloodygram.BloodyConfig;
import com.bloodygram.BloodyStrings;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Ghost mode: read receipts, "online", "typing" and story views are not sent to the server.
 * Everything goes through {@link #intercept}, called at the start of ConnectionsManager.sendRequestInternal.
 */
public class BloodyGhost {

    public static final int MENU_READ = 10003;

    /** Requests created by us that must pass even in ghost mode. */
    private static final Set<TLObject> allowed = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    /** @return true if the request must not be sent */
    public static boolean intercept(int account, TLObject req) {
        if (req == null || allowed.remove(req) || !BloodyConfig.isGhost(account)) {
            return false;
        }
        if (req instanceof TL_account.updateStatus) {
            return BloodyConfig.ghostOnline;
        }
        if (req instanceof TLRPC.TL_messages_setTyping || req instanceof TLRPC.TL_messages_setEncryptedTyping) {
            return BloodyConfig.ghostTyping;
        }
        if (req instanceof TLRPC.TL_messages_readHistory
                || req instanceof TLRPC.TL_channels_readHistory
                || req instanceof TLRPC.TL_messages_readEncryptedHistory
                || req instanceof TLRPC.TL_messages_readDiscussion
                || req instanceof TLRPC.TL_messages_readMessageContents
                || req instanceof TLRPC.TL_channels_readMessageContents) {
            return BloodyConfig.ghostRead;
        }
        if (req instanceof TL_stories.TL_stories_readStories || req instanceof TL_stories.TL_stories_incrementStoryViews) {
            return BloodyConfig.ghostStories;
        }
        return false;
    }

    public static void setEnabled(int account, boolean enabled) {
        BloodyConfig.setGhost(account, enabled);
        if (enabled && BloodyConfig.ghostOnline) {
            // go offline right away: later "online" updates are not sent
            TL_account.updateStatus req = new TL_account.updateStatus();
            req.offline = true;
            send(account, req);
        }
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
    }

    /** "Read" from the chat menu in ghost mode: marks the chat read on the server on purpose. */
    public static void readNow(BaseFragment fragment, int account, long dialogId) {
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
        int maxId = dialog != null ? dialog.top_message : Integer.MAX_VALUE;
        if (DialogObject.isChatDialog(dialogId) && ChatObject.isChannel(controller.getChat(-dialogId))) {
            TLRPC.TL_channels_readHistory req = new TLRPC.TL_channels_readHistory();
            req.channel = controller.getInputChannel(-dialogId);
            req.max_id = maxId;
            send(account, req);
        } else if (!DialogObject.isEncryptedDialog(dialogId)) {
            TLRPC.TL_messages_readHistory req = new TLRPC.TL_messages_readHistory();
            req.peer = controller.getInputPeer(dialogId);
            req.max_id = maxId;
            send(account, req);
        }
        controller.markDialogAsRead(dialogId, maxId, maxId, 0, false, 0, 0, true, 0);
        if (fragment != null) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, BloodyStrings.get(R.string.BloodyGhostReadDone)).show();
        }
    }

    public static void addMenuItem(ActionBarMenuItem menu, int account, long dialogId) {
        if (menu != null && BloodyConfig.isGhost(account) && BloodyConfig.ghostRead && !DialogObject.isEncryptedDialog(dialogId)) {
            menu.lazilyAddSubItem(MENU_READ, R.drawable.msg_markread, BloodyStrings.get(R.string.BloodyGhostRead));
        }
    }

    private static void send(int account, TLObject req) {
        allowed.add(req);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {});
    }
}
