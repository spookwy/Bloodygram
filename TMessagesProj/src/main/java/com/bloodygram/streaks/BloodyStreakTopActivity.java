package com.bloodygram.streaks;

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

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/** All alive streaks, longest first. Tap opens the chat, long press shows the streak window. */
public class BloodyStreakTopActivity extends BaseFragment {

    private final ArrayList<long[]> top = new ArrayList<>();

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(BloodyStrings.get(R.string.BloodyStreakTop));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        top.addAll(BloodyStreaks.getInstance(currentAccount).getTop());
        int atRisk = 0;
        for (long[] item : top) {
            atRisk += item[2];
        }
        actionBar.setSubtitle(top.isEmpty() ? null : atRisk > 0
                ? BloodyStrings.format(R.string.BloodyStreakTopSubtitleRisk, top.size(), atRisk)
                : BloodyStrings.format(R.string.BloodyStreakTopSubtitle, top.size()));

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = frame;

        TextView empty = new TextView(context);
        empty.setText(BloodyStrings.get(R.string.BloodyStreakTopEmpty));
        empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(top.isEmpty() ? View.VISIBLE : View.GONE);
        frame.addView(empty, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 0, 32, 0));

        RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(new Adapter());
        listView.setOnItemClickListener((view, position) -> {
            Bundle args = new Bundle();
            args.putLong("user_id", top.get(position)[0]);
            presentFragment(new ChatActivity(args));
        });
        listView.setOnItemLongClickListener((view, position) -> {
            BloodyStreakUi.showStats(this, currentAccount, top.get(position)[0]);
            return true;
        });
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
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
            long dialogId = top.get(position)[0];
            MessagesController controller = getMessagesController();
            TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
            if (dialog == null) {
                dialog = new TLRPC.TL_dialog();
                dialog.id = dialogId;
            }
            ((DialogCell) holder.itemView).setDialog(dialog, 0, 0);
        }

        @Override
        public int getItemCount() {
            return top.size();
        }
    }
}
