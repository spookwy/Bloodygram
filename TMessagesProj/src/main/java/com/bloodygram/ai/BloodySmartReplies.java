package com.bloodygram.ai;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.bloodygram.BloodyConfig;
import com.bloodygram.BloodyStrings;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

import static org.telegram.messenger.AndroidUtilities.dp;

/**
 * Chips above the chat input: after an incoming message a "✨ Suggest a reply" chip appears; tapping it asks the AI
 * for three replies, tapping one puts it into the field. No AI request happens until the chip is tapped.
 * Hides as soon as the user types or sends.
 */
public class BloodySmartReplies extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private static final int FRESH_SECONDS = 6 * 60 * 60; // on opening a chat, offer only for a recent incoming last message

    private final ChatActivity fragment;
    private final LinearLayout row;
    private boolean loading;
    private int requestId;

    public static BloodySmartReplies create(Context context, ChatActivity fragment) {
        return new BloodySmartReplies(context, fragment);
    }

    private BloodySmartReplies(Context context, ChatActivity fragment) {
        super(context);
        this.fragment = fragment;
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(10), 0, dp(10), 0);
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row);
        addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, Gravity.BOTTOM));
        setVisibility(GONE);
    }

    private boolean enabled() {
        BloodyConfig.load();
        if (!BloodyConfig.smartReplies || !BloodyAi.hasKey()) {
            return false;
        }
        long dialogId = fragment.getDialogId();
        if (dialogId < 0) {
            TLRPC.Chat chat = fragment.getMessagesController().getChat(-dialogId);
            return chat != null && !ChatObject.isChannelAndNotMegaGroup(chat) && ChatObject.canSendMessages(chat);
        }
        return true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getInstance(fragment.getCurrentAccount()).addObserver(this, NotificationCenter.didReceiveNewMessages);
        if (fragment.getChatActivityEnterView() != null && fragment.getChatActivityEnterView().getEditField() != null) {
            fragment.getChatActivityEnterView().getEditField().addTextChangedListener(watcher);
        }
        AndroidUtilities.runOnUIThread(this::offerIfLastIsIncoming, 800); // messages load a bit after the view
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getInstance(fragment.getCurrentAccount()).removeObserver(this, NotificationCenter.didReceiveNewMessages);
        if (fragment.getChatActivityEnterView() != null && fragment.getChatActivityEnterView().getEditField() != null) {
            fragment.getChatActivityEnterView().getEditField().removeTextChangedListener(watcher);
        }
    }

    private final TextWatcher watcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            if (s.length() > 0 && !inserting) {
                hide();
            }
        }
    };
    private boolean inserting;

    private void offerIfLastIsIncoming() {
        if (fragment.messages == null || fragment.messages.isEmpty() || !enabled() || fieldHasText()) {
            return;
        }
        MessageObject last = fragment.messages.get(0);
        int now = fragment.getConnectionsManager().getCurrentTime();
        if (last != null && !last.isOut() && last.messageOwner != null && now - last.messageOwner.date < FRESH_SECONDS && !TextUtils.isEmpty(last.messageOwner.message)) {
            offer();
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.didReceiveNewMessages || args.length < 2 || !(args[0] instanceof Long) || (Long) args[0] != fragment.getDialogId()) {
            return;
        }
        if (args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2]) {
            return; // scheduled
        }
        @SuppressWarnings("unchecked") ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
        boolean incoming = false, outgoing = false;
        for (MessageObject m : messages) {
            if (m.isOut()) {
                outgoing = true;
            } else {
                incoming = true;
            }
        }
        if (outgoing) {
            hide();
        } else if (incoming && enabled() && !fieldHasText()) {
            offer();
        }
    }

    private boolean fieldHasText() {
        return fragment.getChatActivityEnterView() != null && fragment.getChatActivityEnterView().getEditField() != null
                && fragment.getChatActivityEnterView().getEditField().length() > 0;
    }

    private void offer() {
        loading = false;
        requestId++;
        row.removeAllViews();
        addChip(BloodyStrings.get(R.string.BloodySmartSuggest), true, v -> generate());
        show();
    }

    private void generate() {
        if (loading) {
            return;
        }
        loading = true;
        int request = ++requestId;
        row.removeAllViews();
        addChip("✨ …", true, null);
        String transcript = BloodyAiChat.transcript(fragment, 40);
        BloodyAi.ask("Below are the latest messages of a chat, oldest first. \"Me\" is the user. "
                        + "Write exactly 3 different short replies \"Me\" could send next, matching the tone and language of the chat. "
                        + "Output only the 3 replies, one per line, without numbering or quotes.",
                transcript, BetaOutputConfig.Effort.LOW, (answer, error) -> {
                    if (request != requestId) {
                        return; // hidden or superseded meanwhile
                    }
                    loading = false;
                    if (answer == null) {
                        hide();
                        if (fragment.getParentActivity() != null) {
                            BulletinFactory.of(fragment).createErrorBulletin(error).show();
                        }
                        return;
                    }
                    row.removeAllViews();
                    int added = 0;
                    for (String line : answer.split("\n")) {
                        String reply = line.replaceFirst("^\\s*([-•*]|\\d+[.)])\\s*", "").trim();
                        if (!reply.isEmpty() && added < 3) {
                            addChip(reply, false, v -> insert(reply));
                            added++;
                        }
                    }
                    addChip("✕", false, v -> hide());
                });
    }

    private void insert(String text) {
        if (fragment.getChatActivityEnterView() != null) {
            inserting = true;
            fragment.getChatActivityEnterView().setFieldText(text);
            inserting = false;
            fragment.getChatActivityEnterView().openKeyboard();
        }
        hide();
    }

    private void addChip(CharSequence text, boolean accent, OnClickListener click) {
        TextView chip = new TextView(getContext());
        chip.setText(text);
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setMaxWidth(dp(260));
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        chip.setTextColor(accent ? 0xFFFFFFFF : 0xFFEDEDED);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(accent ? 0xE6B3182D : 0xE62A2A2A);
        chip.setBackground(bg);
        if (click != null) {
            chip.setOnClickListener(click);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36));
        lp.rightMargin = dp(6);
        lp.gravity = Gravity.CENTER_VERTICAL;
        row.addView(chip, lp);
    }

    private void show() {
        if (getVisibility() != VISIBLE) {
            setAlpha(0f);
            setVisibility(VISIBLE);
            animate().alpha(1f).setDuration(180).start();
        }
    }

    private void hide() {
        requestId++;
        loading = false;
        if (getVisibility() == VISIBLE) {
            animate().alpha(0f).setDuration(150).withEndAction(() -> setVisibility(GONE)).start();
        }
    }
}
