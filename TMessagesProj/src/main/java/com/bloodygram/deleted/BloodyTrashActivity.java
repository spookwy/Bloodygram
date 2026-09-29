package com.bloodygram.deleted;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bloodygram.BloodyStrings;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Locale;

/** Trash: every message deleted by others that Bloodygram kept, across all chats, with search. */
public class BloodyTrashActivity extends BaseFragment {

    private RecyclerListView listView;
    private TextView emptyView;
    private final ArrayList<BloodyDeletedMessages.TrashItem> all = new ArrayList<>();
    private final ArrayList<BloodyDeletedMessages.TrashItem> shown = new ArrayList<>();
    private String query = "";
    private boolean loading = true;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(BloodyStrings.get(R.string.BloodyTrash));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        ActionBarMenuItem search = actionBar.createMenu().addItem(0, R.drawable.outline_header_search).setIsSearchField(true)
                .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                    @Override
                    public void onTextChanged(EditText editText) {
                        query = editText.getText().toString();
                        filter();
                    }

                    @Override
                    public void onSearchCollapse() {
                        query = "";
                        filter();
                    }
                });
        search.setSearchFieldHint(BloodyStrings.get(R.string.BloodyTrashSearch));

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = frame;

        emptyView = new TextView(context);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setGravity(Gravity.CENTER);
        frame.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 0, 32, 0));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(new Adapter());
        listView.setOnItemClickListener((view, position) -> open(shown.get(position)));
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        updateEmpty();
        BloodyDeletedMessages.getInstance(currentAccount).loadTrash(items -> {
            loading = false;
            all.clear();
            all.addAll(items);
            filter();
        });
        return fragmentView;
    }

    private void open(BloodyDeletedMessages.TrashItem item) {
        Bundle args = new Bundle();
        if (DialogObject.isUserDialog(item.dialogId)) {
            args.putLong("user_id", item.dialogId);
        } else {
            args.putLong("chat_id", -item.dialogId);
        }
        args.putInt("message_id", item.message.getId());
        presentFragment(new ChatActivity(args));
    }

    private void filter() {
        shown.clear();
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (BloodyDeletedMessages.TrashItem item : all) {
            if (q.isEmpty() || matches(item, q)) {
                shown.add(item);
            }
        }
        if (listView != null) {
            listView.getAdapter().notifyDataSetChanged();
            updateEmpty();
        }
        actionBar.setSubtitle(all.isEmpty() ? null : BloodyStrings.format(R.string.BloodyTrashCount, all.size()));
    }

    private boolean matches(BloodyDeletedMessages.TrashItem item, String q) {
        TLRPC.Message m = item.message.messageOwner;
        if (!TextUtils.isEmpty(m.message) && m.message.toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        return chatName(item.dialogId).toLowerCase(Locale.ROOT).contains(q);
    }

    private String chatName(long dialogId) {
        MessagesController controller = getMessagesController();
        if (dialogId > 0) {
            TLRPC.User user = controller.getUser(dialogId);
            return user != null ? UserObject.getUserName(user) : "";
        }
        TLRPC.Chat chat = controller.getChat(-dialogId);
        return chat != null && chat.title != null ? chat.title : "";
    }

    private void updateEmpty() {
        emptyView.setText(BloodyStrings.get(loading ? R.string.BloodyTrashLoading : query.isEmpty() ? R.string.BloodyTrashEmpty : R.string.BloodyTrashNothingFound));
        emptyView.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
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
            BloodyDeletedMessages.TrashItem item = shown.get(position);
            ((DialogCell) holder.itemView).setDialog(item.dialogId, item.message, item.message.messageOwner.date, false, false);
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }
}
