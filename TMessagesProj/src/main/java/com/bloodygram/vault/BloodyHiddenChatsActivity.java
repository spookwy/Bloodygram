package com.bloodygram.vault;

import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bloodygram.BloodyStrings;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/** Hidden chats: they are not in the chats list, only here (opened with the PIN). */
public class BloodyHiddenChatsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private RecyclerListView listView;
    private TextView emptyView;
    private final ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(BloodyStrings.get(R.string.BloodyHiddenChats));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = frame;

        emptyView = new TextView(context);
        emptyView.setText(BloodyStrings.get(R.string.BloodyHiddenEmpty));
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setGravity(Gravity.CENTER);
        frame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 0, 32, 0));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(new Adapter());
        listView.setOnItemClickListener((view, position) -> open(dialogs.get(position).id));
        listView.setOnItemLongClickListener((view, position) -> {
            long dialogId = dialogs.get(position).id;
            ItemOptions.makeOptions(this, view)
                    .add(R.drawable.msg_archive_hide, BloodyStrings.get(R.string.BloodyUnhide), () -> {
                        BloodyVault.setHidden(currentAccount, dialogId, false);
                        reload();
                    })
                    .show();
            return true;
        });
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        reload();
        return fragmentView;
    }

    private void open(long dialogId) {
        Bundle args = new Bundle();
        if (DialogObject.isEncryptedDialog(dialogId)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(dialogId));
        } else if (DialogObject.isUserDialog(dialogId)) {
            args.putLong("user_id", dialogId);
        } else {
            args.putLong("chat_id", -dialogId);
        }
        presentFragment(new ChatActivity(args));
    }

    private void reload() {
        dialogs.clear();
        MessagesController controller = getMessagesController();
        for (long id : BloodyVault.getHidden(currentAccount)) {
            TLRPC.Dialog dialog = controller.dialogs_dict.get(id);
            if (dialog == null) {
                dialog = new TLRPC.TL_dialog();
                dialog.id = id;
            }
            dialogs.add(dialog);
        }
        dialogs.sort((a, b) -> Integer.compare(b.last_message_date, a.last_message_date));
        if (listView != null) {
            listView.getAdapter().notifyDataSetChanged();
            emptyView.setVisibility(dialogs.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogsNeedReload) {
            reload();
        } else if (id == NotificationCenter.updateInterfaces && listView != null) {
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                if (child instanceof DialogCell) {
                    ((DialogCell) child).update((Integer) args[0]);
                }
            }
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            DialogCell cell = new DialogCell(null, parent.getContext(), false, true);
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((DialogCell) holder.itemView).setDialog(dialogs.get(position), 0, 0);
        }

        @Override
        public int getItemCount() {
            return dialogs.size();
        }
    }
}
