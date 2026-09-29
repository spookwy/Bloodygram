package com.bloodygram.chat;

import android.text.TextUtils;

import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.bloodygram.BloodyStrings;
import com.bloodygram.ai.BloodyAi;
import com.bloodygram.history.BloodyEditHistory;
import com.bloodygram.secret.BloodySecretKeeper;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;

/** Bloodygram items of the message context menu (long press on a message). */
public class BloodyMessageMenu {

    public static final int OPTION_TRANSCRIBE = 10006;
    public static final int OPTION_AI_EXPLAIN = 10007;

    public static void addItems(int account, MessageObject message, ArrayList<CharSequence> items, ArrayList<Integer> options, ArrayList<Integer> icons) {
        BloodyEditHistory.addMenuItem(account, message, items, options, icons);
        if (message == null) {
            return;
        }
        if (BloodyTranscriber.canTranscribe(message) && TextUtils.isEmpty(message.messageOwner.voiceTranscription)) {
            items.add(BloodyStrings.get(R.string.BloodyTranscribe));
            options.add(OPTION_TRANSCRIBE);
            icons.add(R.drawable.msg_translate);
        }
        if (BloodyAi.hasKey() && (BloodyTranscriber.canTranscribe(message) || !TextUtils.isEmpty(message.messageOwner.message))) {
            items.add(BloodyStrings.get(R.string.BloodyAiExplain));
            options.add(OPTION_AI_EXPLAIN);
            icons.add(R.drawable.msg_emoji_objects);
        }
        com.bloodygram.ai.BloodyAiDigest.addMenuItem(message, items, options, icons);
        BloodySecretKeeper.addMenuItem(message, items, options, icons);
        BloodyRemind.addMenuItem(message, items, options, icons);
        if (message.getDocument() != null && message.getDocumentName() != null && message.getDocumentName().toLowerCase(java.util.Locale.ROOT).endsWith(".js")) {
            items.add(BloodyStrings.get(R.string.BloodyPluginsInstallFromChat));
            options.add(OPTION_INSTALL_PLUGIN);
            icons.add(R.drawable.msg_addbot);
        }
    }

    public static final int OPTION_INSTALL_PLUGIN = 10014;

    private static void installPlugin(ChatActivity fragment, MessageObject message) {
        java.io.File file = org.telegram.messenger.FileLoader.getInstance(fragment.getCurrentAccount()).getPathToMessage(message.messageOwner);
        if (file == null || !file.exists()) {
            BulletinFactory.of(fragment).createErrorBulletin(BloodyStrings.get(R.string.BloodyPluginsDownloadFirst)).show();
            return;
        }
        try {
            String source = com.bloodygram.plugins.BloodyPlugins.read(new java.io.FileInputStream(file));
            com.bloodygram.plugins.BloodyPluginsActivity.confirmInstall(fragment, message.getDocumentName(), source, null);
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
            BulletinFactory.of(fragment).createErrorBulletin(BloodyStrings.get(R.string.BloodyPluginsBadFile)).show();
        }
    }

    public static void onOption(ChatActivity fragment, int option, MessageObject message) {
        if (message == null) {
            return;
        }
        if (option == BloodyEditHistory.OPTION_EDIT_HISTORY) {
            BloodyEditHistory.getInstance(fragment.getCurrentAccount()).show(fragment, message);
        } else if (option == OPTION_TRANSCRIBE) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, BloodyStrings.get(R.string.BloodyTranscribing)).show();
            BloodyTranscriber.transcribe(message, (text, error) -> {
                if (text != null) {
                    BloodyTranscriber.apply(message, text);
                } else {
                    BulletinFactory.of(fragment).createErrorBulletin(error).show();
                }
            });
        } else if (option == OPTION_AI_EXPLAIN) {
            explain(fragment, message);
        } else if (option == BloodySecretKeeper.OPTION_BURN) {
            BloodySecretKeeper.burn(fragment, message);
        } else if (option == BloodyRemind.OPTION_REMIND) {
            BloodyRemind.ask(fragment, message);
        } else if (option == OPTION_INSTALL_PLUGIN) {
            installPlugin(fragment, message);
        } else if (option == com.bloodygram.ai.BloodyAiDigest.OPTION_TRANSLATE) {
            com.bloodygram.ai.BloodyAiDigest.translate(fragment, message);
        }
    }

    private static void explain(ChatActivity fragment, MessageObject message) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        AlertDialog progress = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.show();
        if (BloodyTranscriber.canTranscribe(message)) {
            String known = message.messageOwner.voiceTranscription;
            if (!TextUtils.isEmpty(known)) {
                askExplain(fragment, progress, known, true);
                return;
            }
            BloodyTranscriber.transcribe(message, (text, error) -> {
                if (text == null) {
                    progress.dismiss();
                    if (fragment.getParentActivity() != null) {
                        BulletinFactory.of(fragment).createErrorBulletin(error).show();
                    }
                    return;
                }
                BloodyTranscriber.apply(message, text);
                askExplain(fragment, progress, text, true);
            });
        } else {
            askExplain(fragment, progress, message.messageOwner.message, false);
        }
    }

    private static void askExplain(ChatActivity fragment, AlertDialog progress, String text, boolean voice) {
        String task = voice
                ? "The user got a voice message; below is its automatic transcript (it may contain recognition errors). "
                        + "Explain briefly what the sender says and wants, in 2-4 sentences."
                : "Explain briefly what this message means and what the sender wants, in 2-4 sentences. "
                        + "Decode slang, abbreviations and hidden meaning if there is any.";
        BloodyAi.ask(task, text, BetaOutputConfig.Effort.LOW, (answer, error) -> {
            progress.dismiss();
            if (fragment.getParentActivity() == null) {
                return;
            }
            if (answer == null) {
                BulletinFactory.of(fragment).createErrorBulletin(error).show();
                return;
            }
            new AlertDialog.Builder(fragment.getParentActivity())
                    .setTitle(BloodyStrings.get(R.string.BloodyAiExplain))
                    .setMessage(answer)
                    .setPositiveButton(BloodyStrings.get(R.string.BloodyClose), null)
                    .show();
        });
    }
}
