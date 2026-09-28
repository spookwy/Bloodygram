package com.bloodygram.ai;

import android.text.TextUtils;

import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.bloodygram.BloodyStrings;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** "✨ Summarize chat" and "✨ Reply ideas" from the chat ⋮ menu. */
public class BloodyAiChat {

    public static final int MENU_SUMMARY = 10008;
    public static final int MENU_REPLIES = 10009;

    private static final int MAX_MESSAGES = 150;

    public static void summarize(ChatActivity fragment) {
        String transcript = transcript(fragment, MAX_MESSAGES);
        if (transcript.isEmpty()) {
            BulletinFactory.of(fragment).createErrorBulletin(BloodyStrings.get(R.string.BloodyAiNothing)).show();
            return;
        }
        AlertDialog progress = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.show();
        BloodyAi.ask("Below are the latest messages of a chat, oldest first, as \"time name: text\". "
                        + "Summarize what was discussed: the main topics, decisions, open questions and anything addressed to \"Me\". "
                        + "Use a few short bullet points starting with \"• \".",
                transcript, BetaOutputConfig.Effort.MEDIUM, (answer, error) -> {
                    progress.dismiss();
                    if (answer == null) {
                        BulletinFactory.of(fragment).createErrorBulletin(error).show();
                        return;
                    }
                    new AlertDialog.Builder(fragment.getParentActivity())
                            .setTitle(BloodyStrings.get(R.string.BloodyAiSummary))
                            .setMessage(answer)
                            .setPositiveButton(BloodyStrings.get(R.string.BloodyClose), null)
                            .show();
                });
    }

    public static void suggestReplies(ChatActivity fragment) {
        String transcript = transcript(fragment, 40);
        if (transcript.isEmpty()) {
            BulletinFactory.of(fragment).createErrorBulletin(BloodyStrings.get(R.string.BloodyAiNothing)).show();
            return;
        }
        AlertDialog progress = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.show();
        BloodyAi.ask("Below are the latest messages of a chat, oldest first. \"Me\" is the user. "
                        + "Write exactly 3 different short replies \"Me\" could send next, matching the tone and language of the chat. "
                        + "Output only the 3 replies, one per line, without numbering or quotes.",
                transcript, BetaOutputConfig.Effort.LOW, (answer, error) -> {
                    progress.dismiss();
                    if (answer == null) {
                        BulletinFactory.of(fragment).createErrorBulletin(error).show();
                        return;
                    }
                    ArrayList<String> replies = new ArrayList<>();
                    for (String line : answer.split("\n")) {
                        String reply = line.replaceFirst("^\\s*([-•*]|\\d+[.)])\\s*", "").trim();
                        if (!reply.isEmpty()) {
                            replies.add(reply);
                        }
                    }
                    if (replies.isEmpty()) {
                        BulletinFactory.of(fragment).createErrorBulletin(BloodyStrings.get(R.string.BloodyAiEmpty)).show();
                        return;
                    }
                    new AlertDialog.Builder(fragment.getParentActivity())
                            .setTitle(BloodyStrings.get(R.string.BloodyAiReplies))
                            .setItems(replies.toArray(new CharSequence[0]), (dialog, which) -> {
                                if (fragment.getChatActivityEnterView() != null) {
                                    fragment.getChatActivityEnterView().setFieldText(replies.get(which));
                                    fragment.getChatActivityEnterView().openKeyboard();
                                }
                            })
                            .show();
                });
    }

    /** Latest messages with text (or a voice transcript), oldest first. */
    private static String transcript(ChatActivity fragment, int limit) {
        ArrayList<MessageObject> messages = fragment.messages;
        MessagesController controller = fragment.getMessagesController();
        SimpleDateFormat time = new SimpleDateFormat("dd.MM HH:mm", Locale.US);
        ArrayList<String> lines = new ArrayList<>();
        for (int i = 0; i < messages.size() && lines.size() < limit; i++) {
            MessageObject message = messages.get(i);
            if (message == null || message.messageOwner == null) {
                continue;
            }
            String text = message.messageOwner.message;
            if (TextUtils.isEmpty(text)) {
                text = message.messageOwner.voiceTranscription;
            }
            if (TextUtils.isEmpty(text)) {
                continue;
            }
            String name;
            if (message.isOut()) {
                name = "Me";
            } else {
                TLRPC.User user = controller.getUser(message.getSenderId());
                TLRPC.Chat chat = user == null ? controller.getChat(-message.getSenderId()) : null;
                name = user != null ? UserObject.getUserName(user) : chat != null ? chat.title : "?";
            }
            lines.add(time.format(new Date(message.messageOwner.date * 1000L)) + " " + name + ": " + text.replace('\n', ' '));
        }
        StringBuilder sb = new StringBuilder();
        for (int i = lines.size() - 1; i >= 0; i--) {
            sb.append(lines.get(i)).append('\n');
        }
        return sb.toString().trim();
    }
}
