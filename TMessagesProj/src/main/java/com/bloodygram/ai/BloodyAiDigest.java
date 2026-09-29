package com.bloodygram.ai;

import android.text.TextUtils;

import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.bloodygram.BloodyStrings;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;

/**
 * "✨ Channels digest": unread posts of subscribed channels (fetched with getHistory, which doesn't mark them read)
 * summarized by the AI, channel by channel. And "✨ Translate" for a single message.
 */
public class BloodyAiDigest {

    public static final int OPTION_TRANSLATE = 10012;
    private static final int MAX_CHANNELS = 15;
    private static final int POSTS_PER_CHANNEL = 20;

    public static void digest(BaseFragment fragment) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        int account = fragment.getCurrentAccount();
        MessagesController controller = MessagesController.getInstance(account);
        ArrayList<TLRPC.Dialog> channels = new ArrayList<>();
        for (TLRPC.Dialog dialog : controller.getAllDialogs()) {
            TLRPC.Chat chat = dialog.id < 0 ? controller.getChat(-dialog.id) : null;
            if (chat != null && ChatObject.isChannelAndNotMegaGroup(chat) && dialog.unread_count > 0) {
                channels.add(dialog);
            }
        }
        if (channels.isEmpty()) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, BloodyStrings.get(R.string.BloodyAiDigestNothing)).show();
            return;
        }
        channels.sort((a, b) -> Integer.compare(b.last_message_date, a.last_message_date));
        if (channels.size() > MAX_CHANNELS) {
            channels.subList(MAX_CHANNELS, channels.size()).clear();
        }
        AlertDialog progress = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.show();

        String[] parts = new String[channels.size()];
        int[] left = {channels.size()};
        for (int i = 0; i < channels.size(); i++) {
            int index = i;
            TLRPC.Dialog dialog = channels.get(i);
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = controller.getInputPeer(dialog.id);
            req.limit = Math.min(POSTS_PER_CHANNEL, dialog.unread_count);
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.messages_Messages) {
                    parts[index] = channelBlock(controller.getChat(-dialog.id), ((TLRPC.messages_Messages) response).messages);
                }
                if (--left[0] == 0) {
                    summarize(fragment, progress, parts);
                }
            }));
        }
    }

    private static String channelBlock(TLRPC.Chat chat, ArrayList<TLRPC.Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (int i = messages.size() - 1; i >= 0; i--) { // oldest first
            String text = messages.get(i).message;
            if (!TextUtils.isEmpty(text)) {
                sb.append("- ").append(text.length() > 600 ? text.substring(0, 600) + "…" : text.replace('\n', ' ')).append('\n');
            }
        }
        if (sb.length() == 0) {
            return null;
        }
        return "## " + (chat != null ? chat.title : "?") + "\n" + sb;
    }

    private static void summarize(BaseFragment fragment, AlertDialog progress, String[] parts) {
        StringBuilder all = new StringBuilder();
        for (String part : parts) {
            if (part != null) {
                all.append(part).append('\n');
            }
        }
        if (all.length() == 0) {
            progress.dismiss();
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, BloodyStrings.get(R.string.BloodyAiDigestNothing)).show();
            return;
        }
        BloodyAi.ask("Below are unread posts from the user's Telegram channels, grouped under \"## channel name\". "
                        + "Write a digest: for each channel its name in bold-free plain text followed by 1-3 short bullet points "
                        + "with what actually matters. Skip ads and fluff. Put the most important channels first.",
                all.toString(), BetaOutputConfig.Effort.MEDIUM, (answer, error) -> {
                    progress.dismiss();
                    if (fragment.getParentActivity() == null) {
                        return;
                    }
                    if (answer == null) {
                        BulletinFactory.of(fragment).createErrorBulletin(error).show();
                        return;
                    }
                    new AlertDialog.Builder(fragment.getParentActivity())
                            .setTitle(BloodyStrings.get(R.string.BloodyAiDigest))
                            .setMessage(answer)
                            .setPositiveButton(BloodyStrings.get(R.string.BloodyClose), null)
                            .show();
                });
    }

    public static void addMenuItem(MessageObject message, ArrayList<CharSequence> items, ArrayList<Integer> options, ArrayList<Integer> icons) {
        if (message != null && BloodyAi.hasKey() && !TextUtils.isEmpty(message.messageOwner.message)) {
            items.add(BloodyStrings.get(R.string.BloodyAiTranslate));
            options.add(OPTION_TRANSLATE);
            icons.add(R.drawable.msg_translate);
        }
    }

    public static void translate(BaseFragment fragment, MessageObject message) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        String language = LocaleController.getInstance().getCurrentLocaleInfo() != null
                ? LocaleController.getInstance().getCurrentLocaleInfo().name : "Russian";
        AlertDialog progress = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.show();
        BloodyAi.ask("Translate the message below into " + language + ". Keep the tone, slang and emoji. "
                        + "If it is already in " + language + ", translate it into English instead. Output only the translation.",
                message.messageOwner.message, BetaOutputConfig.Effort.LOW, (answer, error) -> {
                    progress.dismiss();
                    if (fragment.getParentActivity() == null) {
                        return;
                    }
                    if (answer == null) {
                        BulletinFactory.of(fragment).createErrorBulletin(error).show();
                        return;
                    }
                    new AlertDialog.Builder(fragment.getParentActivity())
                            .setTitle(BloodyStrings.get(R.string.BloodyAiTranslate))
                            .setMessage(answer)
                            .setPositiveButton(BloodyStrings.get(R.string.BloodyClose), null)
                            .setNeutralButton(LocaleController.getString(R.string.Copy), (d, w) -> AndroidUtilities.addToClipboard(answer))
                            .show();
                });
    }
}
