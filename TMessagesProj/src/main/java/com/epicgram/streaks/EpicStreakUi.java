package com.epicgram.streaks;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.epicgram.EpicConfig;
import com.epicgram.EpicStrings;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Components.BulletinFactory;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import static org.telegram.messenger.AndroidUtilities.dp;

/**
 * Streak UI outside the dialogs list: chat header fire tap, profile name/row,
 * "Streak and initiative" menu item and the statistics window (port of the "Огонёк" plugin).
 */
public class EpicStreakUi {

    public static final int MENU_ID = 10002;

    private static final int GREEN = 0xFF2EB84F; // my initiative points
    private static final int RED = 0xFFE53935;   // their points

    // region places

    public static EpicFire.Suffix listSuffix(int account, long dialogId, TextPaint paint) {
        EpicConfig.load();
        return EpicConfig.streakInList ? EpicStreaks.getInstance(account).getSuffix(dialogId, paint) : null;
    }

    public static CharSequence headerTitle(int account, CharSequence title, long dialogId, TextPaint paint) {
        EpicConfig.load();
        return EpicConfig.streakInHeader ? EpicStreaks.getInstance(account).appendFire(title, dialogId, paint) : title;
    }

    public static CharSequence profileName(int account, CharSequence name, long dialogId, TextPaint paint) {
        EpicConfig.load();
        return EpicConfig.streakInProfile ? EpicStreaks.getInstance(account).appendFire(name, dialogId, paint) : name;
    }

    public static boolean hasProfileRow(int account, long dialogId) {
        EpicConfig.load();
        return EpicConfig.streakProfileRow && EpicStreaks.getInstance(account).shouldShow(dialogId);
    }

    /** "🔥 N дней" + "Огонёк · нажмите для статистики". */
    public static void bindProfileRow(TextDetailCell cell, int account, long dialogId, boolean divider) {
        EpicStreaks streaks = EpicStreaks.getInstance(account);
        int streak = streaks.getStreak(dialogId);
        boolean atRisk = streaks.isAtRisk(dialogId);
        int size = (int) (cell.textView.getPaint().getTextSize() * 1.05f);
        SpannableStringBuilder text = new SpannableStringBuilder("🔥 ").append(EpicStrings.days(streak));
        text.setSpan(new android.text.style.ImageSpan(EpicFire.drawable(Math.max(8, size), EpicFire.tier(streak, atRisk)), android.text.style.ImageSpan.ALIGN_BASELINE), 0, 2, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        String label = EpicStrings.get(streak > 0 && atRisk ? R.string.EpicFireProfileRisk : R.string.EpicFireProfileTap);
        cell.setTextAndValue(text, label, divider);
        cell.setImage(null);
        cell.setImageClickListener(null);
    }

    /** "Streak and initiative" item in the ⋮ menu; {@code lazy} for ChatActivity's lazily built header menu. */
    public static void addMenuItem(org.telegram.ui.ActionBar.ActionBarMenuItem menu, int account, long dialogId, boolean lazy) {
        EpicConfig.load();
        if (menu == null || !EpicConfig.streaksEnabled || !EpicStreaks.getInstance(account).isEligible(dialogId)) {
            return;
        }
        if (lazy) {
            menu.lazilyAddSubItem(MENU_ID, R.drawable.msg_stats, EpicStrings.get(R.string.EpicFireMenu));
        } else {
            menu.addSubItem(MENU_ID, R.drawable.msg_stats, EpicStrings.get(R.string.EpicFireMenu));
        }
    }

    // endregion

    // region header tap

    /** Tap on the fire at the end of the chat header title opens the stats window; other taps work as usual. */
    public static class HeaderTouch {
        private boolean armed;

        public boolean onTouch(MotionEvent ev, SimpleTextView title, int account, long dialogId, BaseFragment fragment) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                armed = hit(ev, title, account, dialogId);
                return armed;
            }
            if (!armed) {
                return false;
            }
            if (action == MotionEvent.ACTION_UP) {
                armed = false;
                if (hit(ev, title, account, dialogId)) {
                    showStats(fragment, account, dialogId);
                }
            } else if (action == MotionEvent.ACTION_CANCEL) {
                armed = false;
            }
            return true;
        }

        private static boolean hit(MotionEvent ev, SimpleTextView title, int account, long dialogId) {
            EpicConfig.load();
            if (title == null || !EpicConfig.streakInHeader) {
                return false;
            }
            EpicFire.Suffix suffix = EpicStreaks.getInstance(account).getSuffix(dialogId, title.getPaint());
            if (suffix == null) {
                return false;
            }
            float textEnd = title.getLeft() + title.getTranslationX() + Math.min(title.getTextWidth(), title.getWidth());
            float top = title.getTop() + title.getTranslationY();
            float x = ev.getX(), y = ev.getY();
            return x >= textEnd - suffix.width - dp(6) && x <= textEnd + dp(10) && y >= top - dp(8) && y <= top + title.getHeight() + dp(8);
        }
    }

    // endregion

    // region stats window

    public static void showStats(BaseFragment fragment, int account, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        EpicStreaks streaks = EpicStreaks.getInstance(account);
        if (!streaks.isEligible(dialogId)) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, EpicStrings.get(R.string.EpicFireOnlyPrivate)).show();
            return;
        }
        TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
        String name = user != null && !TextUtils.isEmpty(user.first_name) ? user.first_name.trim() : "?";
        Context context = fragment.getParentActivity();
        StatsView view = new StatsView(context, name);

        streaks.requestSyncIfStale(dialogId, true);

        EpicStreakStats.Result[] last = new EpicStreakStats.Result[1];
        Runnable redraw = () -> view.fill(streaks, dialogId, last[0]);
        Utilities.Callback<EpicStreakStats.Result> listener = result -> {
            last[0] = result;
            redraw.run();
        };
        NotificationCenter.NotificationCenterDelegate observer = (id, acc, args) -> redraw.run();
        NotificationCenter.getInstance(account).addObserver(observer, NotificationCenter.epicStreaksUpdated);

        streaks.getStats(dialogId, stats -> {
            if (last[0] == null) {
                EpicStreakStats.Result known = new EpicStreakStats.Result();
                known.stats = stats;
                last[0] = known;
                redraw.run();
            }
        });
        redraw.run();
        EpicStreakStats.start(streaks, dialogId, listener);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(EpicStrings.get(R.string.EpicFireTitle));
        builder.setView(view.root);
        builder.setPositiveButton(EpicStrings.get(R.string.EpicClose), null);
        builder.setOnDismissListener(d -> {
            NotificationCenter.getInstance(account).removeObserver(observer, NotificationCenter.epicStreaksUpdated);
            EpicStreakStats.detach(streaks, dialogId, listener);
        });
        fragment.showDialog(builder.create());
    }

    private static class StatsView {
        final LinearLayout root;
        final ImageView icon;
        final TextView streak, status, me, them, rule, progress, days, total, mine, theirs, deleted;
        final View barMe, barThem;
        final String name;
        final int black, gray;

        StatsView(Context context, String name) {
            this.name = name;
            black = Theme.getColor(Theme.key_dialogTextBlack);
            gray = Theme.getColor(Theme.key_dialogTextGray3);

            root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(24), dp(4), dp(24), dp(8));

            LinearLayout head = new LinearLayout(context);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            icon = new ImageView(context);
            head.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
            streak = text(context, 22, black, true);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
            slp.leftMargin = dp(12);
            head.addView(streak, slp);
            root.addView(head, new LinearLayout.LayoutParams(-1, -2));
            status = text(context, 14, gray, false);
            root.addView(status, margins(4));

            section(context, EpicStrings.get(R.string.EpicInitiative));
            LinearLayout pts = new LinearLayout(context);
            pts.setOrientation(LinearLayout.HORIZONTAL);
            me = text(context, 17, GREEN, true);
            them = text(context, 17, RED, true);
            them.setGravity(Gravity.END);
            pts.addView(me, new LinearLayout.LayoutParams(0, -2, 1f));
            pts.addView(them, new LinearLayout.LayoutParams(0, -2, 1f));
            root.addView(pts, margins(8));

            LinearLayout bar = new LinearLayout(context);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            barMe = barPart(context, GREEN);
            barThem = barPart(context, RED);
            bar.addView(barMe, new LinearLayout.LayoutParams(0, -1, 1f));
            bar.addView(new View(context), new LinearLayout.LayoutParams(dp(3), -1));
            bar.addView(barThem, new LinearLayout.LayoutParams(0, -1, 1f));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(8));
            blp.topMargin = dp(6);
            root.addView(bar, blp);

            rule = text(context, 12, gray, false);
            rule.setText(EpicStrings.get(R.string.EpicInitiativeRule));
            root.addView(rule, margins(6));
            progress = text(context, 12, gray, false);
            root.addView(progress, new LinearLayout.LayoutParams(-1, -2));

            section(context, EpicStrings.get(R.string.EpicStats));
            days = row(context, EpicStrings.get(R.string.EpicStatsTalking));
            total = row(context, EpicStrings.get(R.string.EpicStatsTotal));
            mine = row(context, EpicStrings.get(R.string.EpicStatsMine));
            theirs = row(context, EpicStrings.format(R.string.EpicStatsTheirs, name));
            deleted = row(context, EpicStrings.get(R.string.EpicStatsDeleted));
        }

        void fill(EpicStreaks streaks, long dialogId, EpicStreakStats.Result data) {
            int streakDays = streaks.getStreak(dialogId);
            boolean atRisk = streaks.isAtRisk(dialogId);
            icon.setImageDrawable(EpicFire.drawable(dp(40), EpicFire.tier(streakDays, atRisk)));
            streak.setText(EpicStrings.format(R.string.EpicFireDaysInRow, EpicStrings.days(streakDays)));
            int flood = (int) Math.max(0, (streaks.floodUntil - System.currentTimeMillis()) / 1000);
            if (!streaks.isCounted(dialogId)) {
                status.setText(flood > 0 ? EpicStrings.format(R.string.EpicFireCountingFlood, EpicStrings.formatNumber(flood)) : EpicStrings.get(R.string.EpicFireCounting));
            } else if (streakDays <= 0) {
                status.setText(EpicStrings.get(R.string.EpicFireNone));
            } else if (atRisk) {
                status.setText(EpicStrings.get(R.string.EpicFireAtRisk));
            } else {
                status.setText(EpicStrings.get(R.string.EpicFireBurning));
            }
            if (data == null || data.stats == null) {
                return;
            }
            EpicStreaks.Stats s = data.stats;

            int totalPts = s.me + s.th;
            int mePct = totalPts > 0 ? Math.round(s.me * 100f / totalPts) : 0;
            int themPct = totalPts > 0 ? 100 - mePct : 0;
            me.setText(EpicStrings.format(R.string.EpicInitiativeMe, EpicStrings.formatNumber(s.me), mePct));
            them.setText(EpicStrings.format(R.string.EpicInitiativeThem, EpicStrings.formatNumber(s.th), themPct, name));
            barMe.setLayoutParams(new LinearLayout.LayoutParams(0, -1, totalPts > 0 ? s.me : 1));
            barThem.setLayoutParams(new LinearLayout.LayoutParams(0, -1, totalPts > 0 ? s.th : 1));

            int counted = s.cm + s.ct;
            if (!data.done && flood > 0) {
                progress.setText(EpicStrings.format(R.string.EpicStatsFlood, EpicStrings.formatNumber(flood)));
            } else if (!data.done) {
                if (data.total > 0) {
                    int pct = Math.min(100, Math.round(counted * 100f / Math.max(1, data.total)));
                    progress.setText(EpicStrings.format(R.string.EpicStatsCountingOf, EpicStrings.formatNumber(counted), EpicStrings.formatNumber(data.total), pct));
                } else if (counted > 0) {
                    progress.setText(EpicStrings.format(R.string.EpicStatsCountingN, EpicStrings.formatNumber(counted)));
                } else {
                    progress.setText(EpicStrings.get(R.string.EpicStatsCounting));
                }
            } else if (data.failed) {
                progress.setText(EpicStrings.get(R.string.EpicStatsFailed));
            } else if (data.paused) {
                progress.setText(EpicStrings.get(R.string.EpicStatsPaused));
            } else {
                progress.setText("");
            }
            progress.setVisibility(progress.getText().length() > 0 ? View.VISIBLE : View.GONE);

            if (s.first != 0) {
                int talking = EpicStreaks.today() - EpicStreaks.dayOf(s.first) + 1;
                String since = new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new Date(s.first * 1000L));
                days.setText(EpicStrings.format(R.string.EpicStatsSince, EpicStrings.days(talking), since));
            } else if (data.done) {
                days.setText("—");
            }
            mine.setText(EpicStrings.formatNumber(s.cm));
            theirs.setText(EpicStrings.formatNumber(s.ct));
            if (data.done && !data.failed && !data.paused || data.total <= 0) {
                total.setText(EpicStrings.formatNumber(counted)); // all counted: exactly the sum, without service messages
            } else {
                total.setText(EpicStrings.formatNumber(data.total));
            }
            EpicConfig.load();
            String since = new SimpleDateFormat("dd.MM", Locale.US).format(new Date(EpicConfig.deletedSince * 1000L));
            deleted.setText(EpicStrings.formatNumber(s.deleted) + " (" + (EpicStrings.isRussian() ? "с " : "since ") + since + ")");
        }

        private TextView text(Context context, int sp, int color, boolean bold) {
            TextView tv = new TextView(context);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            tv.setTextColor(color);
            if (bold) {
                tv.setTypeface(AndroidUtilities.bold());
            }
            return tv;
        }

        private void section(Context context, String title) {
            TextView tv = text(context, 16, black, true);
            tv.setText(title);
            root.addView(tv, margins(18));
        }

        private TextView row(Context context, String label) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView left = text(context, 15, gray, false);
            left.setText(label);
            TextView right = text(context, 15, black, true);
            right.setGravity(Gravity.END);
            right.setText("…");
            row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(right, new LinearLayout.LayoutParams(-2, -2));
            root.addView(row, margins(6));
            return right;
        }

        private static View barPart(Context context, int color) {
            View view = new View(context);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(color);
            shape.setCornerRadius(dp(4));
            view.setBackground(shape);
            return view;
        }

        private static LinearLayout.LayoutParams margins(int top) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(top);
            return lp;
        }
    }

    // endregion
}
