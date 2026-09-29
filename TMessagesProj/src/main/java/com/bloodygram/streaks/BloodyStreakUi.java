package com.bloodygram.streaks;

import android.content.Context;
import android.content.res.ColorStateList;
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
import android.widget.ProgressBar;
import android.widget.TextView;

import com.bloodygram.BloodyConfig;
import com.bloodygram.BloodyStrings;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
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
public class BloodyStreakUi {

    public static final int MENU_ID = 10002;

    private static final int GREEN = 0xFF2EB84F; // my initiative points
    private static final int RED = 0xFFE53935;   // their points

    // region places

    public static BloodyFire.Suffix listSuffix(int account, long dialogId, TextPaint paint) {
        BloodyConfig.load();
        return BloodyConfig.streakInList ? BloodyStreaks.getInstance(account).getSuffix(dialogId, paint) : null;
    }

    /** Chat name in the dialogs list, colored by the fire tier when enabled. */
    public static CharSequence listName(int account, long dialogId, CharSequence name) {
        return BloodyStreaks.getInstance(account).colorName(name, dialogId);
    }

    /** Changes whenever the list fire/name of this chat looks different (DialogCell rebuilds its layout on change). */
    public static int listHash(int account, long dialogId) {
        BloodyConfig.load();
        if (!BloodyConfig.streakInList && !BloodyConfig.streakNameColor || !DialogObject.isUserDialog(dialogId)) {
            return 0;
        }
        BloodyStreaks streaks = BloodyStreaks.getInstance(account);
        if (!streaks.shouldShow(dialogId)) {
            return 0;
        }
        int streak = streaks.getStreak(dialogId);
        int tier = BloodyFire.tier(streak, streaks.isAtRisk(dialogId));
        return (streak << 6) + (tier << 2) + (BloodyConfig.streakInList ? 2 : 0) + (BloodyConfig.streakNameColor ? 1 : 0);
    }

    public static CharSequence headerTitle(int account, CharSequence title, long dialogId, TextPaint paint) {
        BloodyConfig.load();
        BloodyStreaks streaks = BloodyStreaks.getInstance(account);
        title = streaks.colorName(title, dialogId);
        return BloodyConfig.streakInHeader ? streaks.appendFire(title, dialogId, paint) : title;
    }

    public static CharSequence profileName(int account, CharSequence name, long dialogId, TextPaint paint) {
        BloodyConfig.load();
        BloodyStreaks streaks = BloodyStreaks.getInstance(account);
        name = streaks.colorName(name, dialogId);
        return BloodyConfig.streakInProfile ? streaks.appendFire(name, dialogId, paint) : name;
    }

    public static boolean hasProfileRow(int account, long dialogId) {
        BloodyConfig.load();
        return BloodyConfig.streakProfileRow && BloodyStreaks.getInstance(account).shouldShow(dialogId);
    }

    /** "🔥 N дней" + "Огонёк · нажмите для статистики". */
    public static void bindProfileRow(TextDetailCell cell, int account, long dialogId, boolean divider) {
        BloodyStreaks streaks = BloodyStreaks.getInstance(account);
        int streak = streaks.getStreak(dialogId);
        boolean atRisk = streaks.isAtRisk(dialogId);
        int size = (int) (cell.textView.getPaint().getTextSize() * 1.05f);
        SpannableStringBuilder text = new SpannableStringBuilder("🔥 ").append(BloodyStrings.days(streak));
        text.setSpan(new android.text.style.ImageSpan(BloodyFire.drawable(Math.max(8, size), BloodyFire.tier(streak, atRisk)), android.text.style.ImageSpan.ALIGN_BASELINE), 0, 2, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        String label = BloodyStrings.get(streak > 0 && atRisk ? R.string.BloodyFireProfileRisk : R.string.BloodyFireProfileTap);
        cell.setTextAndValue(text, label, divider);
        cell.setImage(null);
        cell.setImageClickListener(null);
    }

    /** "Streak and initiative" item in the ⋮ menu; {@code lazy} for ChatActivity's lazily built header menu. */
    public static void addMenuItem(org.telegram.ui.ActionBar.ActionBarMenuItem menu, int account, long dialogId, boolean lazy) {
        BloodyConfig.load();
        if (menu == null || !BloodyConfig.streaksEnabled || !BloodyStreaks.getInstance(account).isEligible(dialogId)) {
            return;
        }
        if (lazy) {
            menu.lazilyAddSubItem(MENU_ID, R.drawable.msg_stats, BloodyStrings.get(R.string.BloodyFireMenu));
        } else {
            menu.addSubItem(MENU_ID, R.drawable.msg_stats, BloodyStrings.get(R.string.BloodyFireMenu));
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
            BloodyConfig.load();
            if (title == null || !BloodyConfig.streakInHeader) {
                return false;
            }
            BloodyFire.Suffix suffix = BloodyStreaks.getInstance(account).getSuffix(dialogId, title.getPaint());
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
        BloodyStreaks streaks = BloodyStreaks.getInstance(account);
        if (!streaks.isEligible(dialogId)) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, BloodyStrings.get(R.string.BloodyFireOnlyPrivate)).show();
            return;
        }
        TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
        String name = user != null && !TextUtils.isEmpty(user.first_name) ? user.first_name.trim() : "?";
        Context context = fragment.getParentActivity();
        StatsView view = new StatsView(context, name);

        streaks.requestSyncIfStale(dialogId, true);

        BloodyStreakStats.Result[] last = new BloodyStreakStats.Result[1];
        Runnable redraw = () -> view.fill(streaks, dialogId, last[0]);
        Utilities.Callback<BloodyStreakStats.Result> listener = result -> {
            last[0] = result;
            redraw.run();
        };
        NotificationCenter.NotificationCenterDelegate observer = (id, acc, args) -> redraw.run();
        NotificationCenter.getInstance(account).addObserver(observer, NotificationCenter.bloodyStreaksUpdated);

        streaks.getStats(dialogId, stats -> {
            if (last[0] == null) {
                BloodyStreakStats.Result known = new BloodyStreakStats.Result();
                known.stats = stats;
                last[0] = known;
                redraw.run();
            }
        });
        redraw.run();
        BloodyStreakStats.start(streaks, dialogId, listener);

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(BloodyStrings.get(R.string.BloodyFireTitle));
        builder.setView(view.root);
        builder.setPositiveButton(BloodyStrings.get(R.string.BloodyClose), null);
        builder.setOnDismissListener(d -> {
            NotificationCenter.getInstance(account).removeObserver(observer, NotificationCenter.bloodyStreaksUpdated);
            BloodyStreakStats.detach(streaks, dialogId, listener);
        });
        fragment.showDialog(builder.create());
    }

    private static class StatsView {
        final LinearLayout root;
        final ImageView icon;
        final TextView streak, status, me, them, rule, progress, days, total, mine, theirs, deleted, achievements;
        final View barMe, barThem;
        final ProgressBar scanBar;
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
            achievements = text(context, 13, gray, false);
            root.addView(achievements, margins(6));
            scanBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            scanBar.setIndeterminate(true);
            scanBar.setIndeterminateTintList(ColorStateList.valueOf(Theme.getColor(Theme.key_featuredStickers_addButton)));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, dp(4));
            plp.topMargin = dp(8);
            root.addView(scanBar, plp);

            section(context, BloodyStrings.get(R.string.BloodyInitiative));
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
            rule.setText(BloodyStrings.get(R.string.BloodyInitiativeRule));
            root.addView(rule, margins(6));
            progress = text(context, 12, gray, false);
            root.addView(progress, new LinearLayout.LayoutParams(-1, -2));

            section(context, BloodyStrings.get(R.string.BloodyStats));
            days = row(context, BloodyStrings.get(R.string.BloodyStatsTalking));
            total = row(context, BloodyStrings.get(R.string.BloodyStatsTotal));
            mine = row(context, BloodyStrings.get(R.string.BloodyStatsMine));
            theirs = row(context, BloodyStrings.format(R.string.BloodyStatsTheirs, name));
            deleted = row(context, BloodyStrings.get(R.string.BloodyStatsDeleted));
            replyMe = row(context, BloodyStrings.get(R.string.BloodyStatsReplyMe));
            replyThem = row(context, BloodyStrings.format(R.string.BloodyStatsReplyThem, name));
            activeHour = row(context, BloodyStrings.get(R.string.BloodyStatsActiveHour));
        }

        private TextView replyMe, replyThem, activeHour;

        /** "4 min", "1 h 20 min", "35 s". */
        private String duration(long seconds) {
            boolean ru = BloodyStrings.isRussian();
            if (seconds < 60) {
                return seconds + (ru ? " сек" : " s");
            }
            long minutes = seconds / 60;
            if (minutes < 60) {
                return minutes + (ru ? " мин" : " min");
            }
            long hours = minutes / 60;
            long rest = minutes % 60;
            return hours + (ru ? " ч" : " h") + (rest > 0 ? " " + rest + (ru ? " мин" : " min") : "");
        }

        void fill(BloodyStreaks streaks, long dialogId, BloodyStreakStats.Result data) {
            int streakDays = streaks.getStreak(dialogId);
            boolean atRisk = streaks.isAtRisk(dialogId);
            icon.setImageDrawable(BloodyFire.drawable(dp(40), BloodyFire.tier(streakDays, atRisk)));
            streak.setText(BloodyStrings.format(R.string.BloodyFireDaysInRow, BloodyStrings.days(streakDays)));
            int flood = (int) Math.max(0, (streaks.floodUntil - System.currentTimeMillis()) / 1000);
            achievements.setText(achievementsText(streakDays));
            icon.setOnClickListener(v -> {
                if (streakDays > 0 && v.getRootView() instanceof android.view.ViewGroup) {
                    // the stats window is a dialog: play inside its window, the activity is below it
                    BloodyStreakCelebration.show((android.view.ViewGroup) v.getRootView(), v.getContext(), streaks.account, streakDays, dialogId);
                }
            });
            boolean counting = !streaks.isCounted(dialogId);
            scanBar.setVisibility(counting ? View.VISIBLE : View.GONE);
            if (counting) {
                int checked = streaks.getScanProgress(dialogId);
                if (flood > 0) {
                    status.setText(BloodyStrings.format(R.string.BloodyFireCountingFlood, BloodyStrings.formatNumber(flood)));
                } else if (checked > 0) {
                    status.setText(BloodyStrings.format(R.string.BloodyFireCountingDays, BloodyStrings.days(checked)));
                } else {
                    status.setText(BloodyStrings.get(R.string.BloodyFireCounting));
                }
            } else if (streakDays <= 0) {
                status.setText(BloodyStrings.get(R.string.BloodyFireNone));
            } else if (atRisk) {
                status.setText(BloodyStrings.get(R.string.BloodyFireAtRisk));
            } else {
                status.setText(BloodyStrings.get(R.string.BloodyFireBurning));
            }
            if (data == null || data.stats == null) {
                return;
            }
            BloodyStreaks.Stats s = data.stats;

            int totalPts = s.me + s.th;
            int mePct = totalPts > 0 ? Math.round(s.me * 100f / totalPts) : 0;
            int themPct = totalPts > 0 ? 100 - mePct : 0;
            me.setText(BloodyStrings.format(R.string.BloodyInitiativeMe, BloodyStrings.formatNumber(s.me), mePct));
            them.setText(BloodyStrings.format(R.string.BloodyInitiativeThem, BloodyStrings.formatNumber(s.th), themPct, name));
            barMe.setLayoutParams(new LinearLayout.LayoutParams(0, -1, totalPts > 0 ? s.me : 1));
            barThem.setLayoutParams(new LinearLayout.LayoutParams(0, -1, totalPts > 0 ? s.th : 1));

            int counted = s.cm + s.ct;
            if (!data.done && flood > 0) {
                progress.setText(BloodyStrings.format(R.string.BloodyStatsFlood, BloodyStrings.formatNumber(flood)));
            } else if (!data.done) {
                if (data.total > 0) {
                    int pct = Math.min(100, Math.round(counted * 100f / Math.max(1, data.total)));
                    progress.setText(BloodyStrings.format(R.string.BloodyStatsCountingOf, BloodyStrings.formatNumber(counted), BloodyStrings.formatNumber(data.total), pct));
                } else if (counted > 0) {
                    progress.setText(BloodyStrings.format(R.string.BloodyStatsCountingN, BloodyStrings.formatNumber(counted)));
                } else {
                    progress.setText(BloodyStrings.get(R.string.BloodyStatsCounting));
                }
            } else if (data.failed) {
                progress.setText(BloodyStrings.get(R.string.BloodyStatsFailed));
            } else if (data.paused) {
                progress.setText(BloodyStrings.get(R.string.BloodyStatsPaused));
            } else {
                progress.setText("");
            }
            progress.setVisibility(progress.getText().length() > 0 ? View.VISIBLE : View.GONE);

            if (s.first != 0) {
                int talking = BloodyStreaks.today() - BloodyStreaks.dayOf(s.first) + 1;
                String since = new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new Date(s.first * 1000L));
                days.setText(BloodyStrings.format(R.string.BloodyStatsSince, BloodyStrings.days(talking), since));
            } else if (data.done) {
                days.setText("—");
            }
            mine.setText(BloodyStrings.formatNumber(s.cm));
            theirs.setText(BloodyStrings.formatNumber(s.ct));
            if (data.done && !data.failed && !data.paused || data.total <= 0) {
                total.setText(BloodyStrings.formatNumber(counted)); // all counted: exactly the sum, without service messages
            } else {
                total.setText(BloodyStrings.formatNumber(data.total));
            }
            BloodyConfig.load();
            String since = new SimpleDateFormat("dd.MM", Locale.US).format(new Date(BloodyConfig.deletedSince * 1000L));
            deleted.setText(BloodyStrings.formatNumber(s.deleted) + " (" + (BloodyStrings.isRussian() ? "с " : "since ") + since + ")");
            replyMe.setText(s.rcMe > 0 ? duration(s.rsMe / s.rcMe) : "—");
            replyThem.setText(s.rcTh > 0 ? duration(s.rsTh / s.rcTh) : "—");
            int best = -1;
            for (int h = 0; h < 24; h++) {
                if (s.hours[h] > 0 && (best < 0 || s.hours[h] > s.hours[best])) {
                    best = h;
                }
            }
            activeHour.setText(best < 0 ? "—" : String.format(Locale.US, "%02d:00–%02d:00", best, (best + 1) % 24));
        }

        /** "🏅 7 · 30 · 50 · 100 · ◦200 · ◦300": reached milestones and the next ones. */
        private CharSequence achievementsText(int streakDays) {
            SpannableStringBuilder sb = new SpannableStringBuilder(BloodyStrings.get(R.string.BloodyAchievements)).append(" ");
            int shownNext = 0;
            for (int m : BloodyStreakCelebration.milestones()) {
                boolean reached = streakDays >= m;
                if (!reached && shownNext >= 2) {
                    break;
                }
                if (!reached) {
                    shownNext++;
                }
                int start = sb.length();
                sb.append(reached ? "🏅" + m : "◦" + m).append("  ");
                sb.setSpan(new android.text.style.ForegroundColorSpan(reached ? 0xFFFFB300 : gray), start, sb.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return sb;
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
