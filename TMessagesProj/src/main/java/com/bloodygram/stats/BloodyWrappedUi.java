package com.bloodygram.stats;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.bloodygram.BloodyStrings;

import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.text.DateFormatSymbols;
import java.util.Locale;

import static org.telegram.messenger.AndroidUtilities.dp;

/** The "Bloodygram Wrapped" window: a compact year-in-review card built from {@link BloodyWrapped}. */
public class BloodyWrappedUi {

    public static void show(BaseFragment fragment, int account) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        Context context = fragment.getParentActivity();
        AlertDialog progress = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
        progress.show();
        BloodyWrapped.compute(account, result -> {
            progress.dismiss();
            if (fragment.getParentActivity() == null) {
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle(BloodyStrings.format(R.string.BloodyWrappedTitle, result.year));
            builder.setView(build(context, account, result));
            builder.setPositiveButton(BloodyStrings.get(R.string.BloodyClose), null);
            fragment.showDialog(builder.create());
        });
    }

    private static ScrollView build(Context context, int account, BloodyWrapped.Result result) {
        int black = Theme.getColor(Theme.key_dialogTextBlack);
        int gray = Theme.getColor(Theme.key_dialogTextGray3);
        int accent = Theme.getColor(Theme.key_dialogTextLink);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(6), dp(22), dp(8));

        if (result.sent == 0 && result.received == 0) {
            root.addView(line(context, black, 16, BloodyStrings.get(R.string.BloodyWrappedEmpty)));
            return wrap(context, root);
        }

        TextView big = new TextView(context);
        big.setTextColor(accent);
        big.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 34);
        big.setTypeface(null, android.graphics.Typeface.BOLD);
        big.setGravity(Gravity.CENTER);
        big.setText(number(result.sent));
        root.addView(big, lp(0));
        root.addView(center(context, gray, 14, BloodyStrings.get(R.string.BloodyWrappedSent)), lp(0));
        root.addView(center(context, gray, 14, BloodyStrings.format(R.string.BloodyWrappedReceived, number(result.received))), lp(6));

        addStat(context, root, black, gray, BloodyStrings.get(R.string.BloodyWrappedHour), hourText(result.topHour));
        addStat(context, root, black, gray, BloodyStrings.get(R.string.BloodyWrappedWeekday), weekday(result.topWeekday));
        addStat(context, root, black, gray, BloodyStrings.get(R.string.BloodyWrappedMonth), month(result.topMonth));

        if (!result.topChats.isEmpty()) {
            root.addView(header(context, gray, BloodyStrings.get(R.string.BloodyWrappedTopChats)), lp(16));
            MessagesController controller = MessagesController.getInstance(account);
            for (int i = 0; i < result.topChats.size(); i++) {
                BloodyWrapped.Chat chat = result.topChats.get(i);
                root.addView(row(context, black, gray, (i + 1) + ". " + chatName(controller, chat.dialogId), number(chat.count)), lp(6));
            }
        }

        if (!result.topEmoji.isEmpty()) {
            root.addView(header(context, gray, BloodyStrings.get(R.string.BloodyWrappedTopEmoji)), lp(16));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < result.topEmoji.size(); i++) {
                if (sb.length() > 0) {
                    sb.append("   ");
                }
                sb.append(result.topEmoji.get(i).emoji).append(' ').append(number(result.topEmoji.get(i).count));
            }
            TextView emoji = line(context, black, 17, sb.toString());
            emoji.setGravity(Gravity.CENTER);
            root.addView(emoji, lp(6));
        }

        return wrap(context, root);
    }

    private static void addStat(Context context, LinearLayout root, int black, int gray, String label, String value) {
        if (value == null) {
            return;
        }
        root.addView(row(context, black, gray, label, value), lp(10));
    }

    private static LinearLayout row(Context context, int black, int gray, String left, String right) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView l = line(context, gray, 15, left);
        TextView r = line(context, black, 15, right);
        r.setTypeface(null, android.graphics.Typeface.BOLD);
        r.setGravity(Gravity.END);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(r, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private static TextView header(Context context, int color, String text) {
        TextView tv = line(context, color, 13, text.toUpperCase(Locale.getDefault()));
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        return tv;
    }

    private static TextView center(Context context, int color, int size, String text) {
        TextView tv = line(context, color, size, text);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private static TextView line(Context context, int color, int size, String text) {
        TextView tv = new TextView(context);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        tv.setText(text);
        return tv;
    }

    private static ScrollView wrap(Context context, LinearLayout root) {
        ScrollView scroll = new ScrollView(context);
        scroll.addView(root);
        return scroll;
    }

    private static LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        return params;
    }

    private static String number(long n) {
        return BloodyStrings.formatNumber(n);
    }

    private static String hourText(int hour) {
        if (hour < 0 || hour > 23) {
            return null;
        }
        return String.format(Locale.getDefault(), "%02d:00 – %02d:00", hour, (hour + 1) % 24);
    }

    private static String weekday(int weekday) {
        if (weekday < 0 || weekday > 6) {
            return null;
        }
        String[] names = new DateFormatSymbols(Locale.getDefault()).getWeekdays();
        String name = names[weekday + 1];
        return name.isEmpty() ? null : capitalize(name);
    }

    private static String month(int month) {
        if (month < 1 || month > 12) {
            return null;
        }
        String[] names = new DateFormatSymbols(Locale.getDefault()).getMonths();
        String name = names[month - 1];
        return name.isEmpty() ? null : capitalize(name);
    }

    private static String capitalize(String s) {
        return s.substring(0, 1).toUpperCase(Locale.getDefault()) + s.substring(1);
    }

    private static String chatName(MessagesController controller, long dialogId) {
        if (dialogId > 0) {
            TLRPC.User user = controller.getUser(dialogId);
            if (user != null) {
                return ContactsController.formatName(user.first_name, user.last_name);
            }
        } else {
            TLRPC.Chat chat = controller.getChat(-dialogId);
            if (chat != null && chat.title != null) {
                return chat.title;
            }
        }
        return "#" + dialogId;
    }
}
