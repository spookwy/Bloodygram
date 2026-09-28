package com.bloodygram.streaks;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;

import com.bloodygram.BloodyConfig;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local "fire" streaks for private chats (port of the "Огонёк" plugin).
 * A day counts when both sides sent at least one message; streak = consecutive such days
 * ending today or yesterday (yesterday = gray fire, goes out at midnight).
 * Also keeps per-chat statistics for the "Огонёк" window: initiative points, message counters, deleted.
 * All maps except {@link #streaks}/{@link #synced}/{@link #queued} are touched only on {@link #queue}.
 */
public class BloodyStreaks implements NotificationCenter.NotificationCenterDelegate {

    private static final int FLAG_MINE = 1;
    private static final int FLAG_THEIRS = 2;
    private static final int FLAG_BOTH = FLAG_MINE | FLAG_THEIRS;

    private static final int SYNC_MAX_REQUESTS = 800;
    private static final int PROBES = 3;
    private static final int PAGE_SIZE = 100;
    private static final int REQUEST_DELAY = 50;
    /** getHistory pace shared by all scans: at ~3 req/s Telegram already answers FLOOD_WAIT of 10-25 s. */
    private static final int REQUEST_INTERVAL = 500;
    private static final int MAX_PARALLEL = 3;
    private static final long FRONT_BOOST = 3600_000L;

    static final DispatchQueue queue = new DispatchQueue("bloodyStreaksQueue");
    private static final BloodyStreaks[] instances = new BloodyStreaks[UserConfig.MAX_ACCOUNT_COUNT];

    public static BloodyStreaks getInstance(int account) {
        long userId = UserConfig.getInstance(account).getClientUserId();
        BloodyStreaks instance = instances[account];
        if (instance == null || instance.userId != userId) {
            synchronized (BloodyStreaks.class) {
                instance = instances[account];
                if (instance == null || instance.userId != userId) {
                    BloodyStreaks old = instance;
                    instances[account] = instance = new BloodyStreaks(account, userId);
                    if (old != null) {
                        old.destroy();
                    }
                }
            }
        }
        return instance;
    }

    /** Run of "both" days ending at {@code lastBothDay}. */
    private static class Streak {
        final int lastBothDay;
        final int length;

        Streak(int lastBothDay, int length) {
            this.lastBothDay = lastBothDay;
            this.length = length;
        }
    }

    /** Persistent per-chat statistics (same fields as the plugin's stats cache). */
    public static class Stats {
        public int mx;      // last counted message id
        public int lp;      // date of the last initiative point
        public int me;      // my initiative points
        public int th;      // their initiative points
        public int cm;      // messages from me
        public int ct;      // messages from them
        public int first;   // date of the first message
        public int deleted; // deleted messages seen since tracking start

        Stats copy() {
            Stats s = new Stats();
            s.mx = mx; s.lp = lp; s.me = me; s.th = th; s.cm = cm; s.ct = ct; s.first = first; s.deleted = deleted;
            return s;
        }
    }

    final int account;
    private final long userId;
    private final HashMap<Long, HashMap<Integer, Integer>> days = new HashMap<>();
    private final HashMap<Long, Stats> stats = new HashMap<>();
    private final ConcurrentHashMap<Long, Streak> streaks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> synced = new ConcurrentHashMap<>();
    private final Set<Long> queued = ConcurrentHashMap.newKeySet();
    /** When the chat was last shown: the most recently shown (visible) chats are counted first. */
    private final ConcurrentHashMap<Long, Long> wanted = new ConcurrentHashMap<>();
    /** Days already checked by a running scan, for the progress in the stats window. */
    private final ConcurrentHashMap<Long, Integer> scanProgress = new ConcurrentHashMap<>();
    private final HashSet<Long> syncQueue = new HashSet<>();
    private final HashSet<Long> scanning = new HashSet<>();
    private final LinkedHashMap<Integer, Boolean> deletedSeen = new LinkedHashMap<Integer, Boolean>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) {
            return size() > 5000;
        }
    };
    private int running;
    private boolean pumpScheduled;
    private long nextRequestAt;
    private int generation;
    volatile long floodUntil;
    private DbHelper db;
    private boolean destroyed;
    private boolean refreshScheduled;
    private boolean progressScheduled;

    private BloodyStreaks(int account, long userId) {
        this.account = account;
        this.userId = userId;
        if (userId == 0) {
            return;
        }
        queue.postRunnable(this::load);
        AndroidUtilities.runOnUIThread(() -> {
            if (!destroyed) {
                NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didReceiveNewMessages);
            }
        });
    }

    private void destroy() {
        destroyed = true;
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.didReceiveNewMessages));
    }

    public static int today() {
        return dayOf(System.currentTimeMillis() / 1000);
    }

    public static int dayOf(long unixSeconds) {
        long ms = unixSeconds * 1000L;
        return (int) ((ms + TimeZone.getDefault().getOffset(ms)) / 86400000L);
    }

    // region public API for UI

    /** Streak length, 0 if none. Schedules a recount when the chat was not counted today. */
    public int getStreak(long dialogId) {
        requestSyncIfStale(dialogId, false);
        Streak streak = streaks.get(dialogId);
        if (streak == null) {
            return 0;
        }
        return streak.lastBothDay >= today() - 1 ? streak.length : 0;
    }

    /** Alive only thanks to yesterday: write today or it goes out at midnight. */
    public boolean isAtRisk(long dialogId) {
        Streak streak = streaks.get(dialogId);
        return streak != null && streak.lastBothDay == today() - 1;
    }

    /** Whether the streak for this chat has been counted today. */
    public boolean isCounted(long dialogId) {
        Integer day = synced.get(dialogId);
        return day != null && day == today();
    }

    /** Days already checked by the running scan of this chat, -1 if it isn't being scanned. */
    public int getScanProgress(long dialogId) {
        Integer days = scanProgress.get(dialogId);
        return days == null ? -1 : days;
    }

    public int getTier(long dialogId) {
        int streak = getStreak(dialogId);
        return BloodyFire.tier(streak, isAtRisk(dialogId));
    }

    public boolean isEligible(long dialogId) {
        if (!DialogObject.isUserDialog(dialogId) || dialogId == userId || userId == 0) {
            return false;
        }
        TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
        return user != null && !user.bot && !UserObject.isDeleted(user) && !UserObject.isReplyUser(user) && !UserObject.isService(user.id);
    }

    /** Whether the fire should be shown for this chat (enabled, eligible, above the minimum). */
    public boolean shouldShow(long dialogId) {
        BloodyConfig.load();
        return BloodyConfig.streaksEnabled && isEligible(dialogId) && getStreak(dialogId) >= BloodyConfig.streakMinDays;
    }

    /** Name color by the fire tier when the setting is on, 0 = keep the usual color. */
    public int getNameColor(long dialogId) {
        BloodyConfig.load();
        if (!BloodyConfig.streakNameColor || !shouldShow(dialogId)) {
            return 0;
        }
        return BloodyFire.nameColor(getTier(dialogId));
    }

    /** {@code name} painted with {@link #getNameColor}, or unchanged. */
    public CharSequence colorName(CharSequence name, long dialogId) {
        int color = name == null ? 0 : getNameColor(dialogId);
        if (color == 0 || name.length() == 0) {
            return name;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(name);
        sb.setSpan(new ForegroundColorSpan(color), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /** " 🔥N" suffix with the vector fire sized for {@code paint}, or null if nothing to show. */
    public BloodyFire.Suffix getSuffix(long dialogId, TextPaint paint) {
        if (paint == null || !shouldShow(dialogId)) {
            return null;
        }
        int streak = getStreak(dialogId);
        return BloodyFire.suffix(streak, BloodyFire.tier(streak, isAtRisk(dialogId)), paint);
    }

    /** {@code title} + " 🔥N", or {@code title} unchanged. */
    public CharSequence appendFire(CharSequence title, long dialogId, TextPaint paint) {
        BloodyFire.Suffix suffix = getSuffix(dialogId, paint);
        return suffix == null || title == null ? title : TextUtils.concat(title, suffix.text);
    }

    /** Reads the stats snapshot on the streaks queue and delivers it on the UI thread. */
    public void getStats(long dialogId, Utilities.Callback<Stats> callback) {
        queue.postRunnable(() -> {
            Stats s = stats.get(dialogId);
            Stats copy = s == null ? new Stats() : s.copy();
            AndroidUtilities.runOnUIThread(() -> callback.run(copy));
        });
    }

    /** Drops all counted streaks and counts them again (settings → "Пересчитать"). */
    public void recalcAll() {
        queue.postRunnable(() -> {
            generation++;
            days.clear();
            streaks.clear();
            synced.clear();
            queued.clear();
            syncQueue.clear();
            scanProgress.clear();
            scanning.clear();
            running = 0;
            try {
                SQLiteDatabase database = db().getWritableDatabase();
                database.delete("streak_days", null, null);
                database.delete("streak_sync", null, null);
            } catch (Exception e) {
                FileLog.e(e);
            }
            notifyUi();
        });
    }

    /**
     * Queues the chat for a recount. Chats shown most recently go first (the visible ones),
     * {@code front} (opened chat / stats window) goes before all of them.
     */
    public void requestSyncIfStale(long dialogId, boolean front) {
        if (userId == 0 || isCounted(dialogId) || !DialogObject.isUserDialog(dialogId)) {
            return;
        }
        wanted.merge(dialogId, SystemClock.elapsedRealtime() + (front ? FRONT_BOOST : 0), Math::max);
        if (!queued.add(dialogId) && !front) {
            return;
        }
        queue.postRunnable(() -> {
            syncQueue.add(dialogId);
            pump();
        });
    }

    // endregion

    // region deleted messages counter

    /** updateDeleteMessages (private chats / basic groups): ids only, chat is looked up in the cache. */
    public void onRemoteDelete(ArrayList<Integer> ids) {
        if (userId == 0 || ids == null || ids.isEmpty()) {
            return;
        }
        ArrayList<Integer> fresh = new ArrayList<>();
        synchronized (deletedSeen) {
            for (int i = 0; i < ids.size(); i++) {
                if (deletedSeen.put(ids.get(i), Boolean.TRUE) == null) {
                    fresh.add(ids.get(i));
                }
            }
        }
        if (fresh.isEmpty()) {
            return;
        }
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            HashMap<Long, Integer> perDialog = new HashMap<>();
            SQLiteCursor cursor = null;
            try {
                cursor = storage.getDatabase().queryFinalized("SELECT uid FROM messages_v2 WHERE mid IN (" + TextUtils.join(",", fresh) + ") AND uid > 0");
                while (cursor.next()) {
                    long uid = cursor.longValue(0);
                    Integer n = perDialog.get(uid);
                    perDialog.put(uid, n == null ? 1 : n + 1);
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            for (Map.Entry<Long, Integer> entry : perDialog.entrySet()) {
                addDeleted(entry.getKey(), entry.getValue());
            }
        });
    }

    /** Own deletions (the server doesn't echo them back as updates). */
    public void onOwnDelete(long dialogId, int count) {
        if (userId != 0 && count > 0 && DialogObject.isUserDialog(dialogId)) {
            addDeleted(dialogId, count);
        }
    }

    private void addDeleted(long dialogId, int count) {
        queue.postRunnable(() -> {
            Stats s = stats(dialogId);
            s.deleted += count;
            saveStats(dialogId, s);
            notifyUi();
        });
    }

    // endregion

    // region stats storage (queue only)

    Stats stats(long dialogId) {
        Stats s = stats.get(dialogId);
        if (s == null) {
            stats.put(dialogId, s = new Stats());
        }
        return s;
    }

    void saveStats(long dialogId, Stats s) {
        try {
            ContentValues values = new ContentValues();
            values.put("dialog_id", dialogId);
            values.put("mx", s.mx);
            values.put("lp", s.lp);
            values.put("me", s.me);
            values.put("th", s.th);
            values.put("cm", s.cm);
            values.put("ct", s.ct);
            values.put("first", s.first);
            values.put("deleted", s.deleted);
            db().getWritableDatabase().insertWithOnConflict("streak_stats", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // endregion

    // region incremental updates

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.didReceiveNewMessages || destroyed) {
            return;
        }
        if (args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2]) {
            return; // scheduled
        }
        long dialogId = (Long) args[0];
        if (!isEligible(dialogId)) {
            return;
        }
        ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
        ArrayList<int[]> marks = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            if (messageObject == null || messageObject.messageOwner == null || messageObject.messageOwner.action != null) {
                continue;
            }
            marks.add(new int[]{dayOf(messageObject.messageOwner.date), messageObject.isOut() ? FLAG_MINE : FLAG_THEIRS});
        }
        if (marks.isEmpty()) {
            return;
        }
        queue.postRunnable(() -> {
            boolean changed = false;
            for (int i = 0; i < marks.size(); i++) {
                changed |= mark(dialogId, marks.get(i)[0], marks.get(i)[1], true);
            }
            if (changed) {
                recompute(dialogId);
                notifyUi();
            }
        });
    }

    // endregion

    // region sync with server history

    private void pump() {
        if (destroyed) {
            return;
        }
        long wait = floodUntil - System.currentTimeMillis();
        if (wait > 0) {
            if (!pumpScheduled) {
                pumpScheduled = true;
                queue.postRunnable(() -> {
                    pumpScheduled = false;
                    pump();
                }, wait);
            }
            return;
        }
        while (running < MAX_PARALLEL && !syncQueue.isEmpty()) {
            long dialogId = 0;
            long best = Long.MIN_VALUE;
            for (Long id : syncQueue) {
                if (scanning.contains(id)) {
                    continue;
                }
                Long w = wanted.get(id);
                long t = w == null ? 0 : w;
                if (dialogId == 0 || t > best) {
                    dialogId = id;
                    best = t;
                }
            }
            if (dialogId == 0) {
                return;
            }
            syncQueue.remove(dialogId);
            if (isCounted(dialogId)) {
                queued.remove(dialogId);
                continue;
            }
            startScan(dialogId);
        }
    }

    private void startScan(long dialogId) {
        running++;
        scanning.add(dialogId);
        wanted.remove(dialogId);
        Scan scan = new Scan();
        scan.dialogId = dialogId;
        scan.gen = generation;
        scan.today = today();
        scan.day = scan.today;
        scan.done = () -> {
            if (scan.gen == generation) {
                scanning.remove(dialogId);
                scanProgress.remove(dialogId);
                queued.remove(dialogId);
                running--;
                queue.postRunnable(this::pump, REQUEST_DELAY);
                notifyProgress();
            }
        };
        scanProgress.put(dialogId, 0);
        loadLocalDays(scan, () -> {
            if (scan.gen != generation) {
                scan.done.run();
                return;
            }
            // days already known to have messages from both sides are taken from the cache, no requests
            if (walk(scan)) {
                return;
            }
            next(scan);
        });
    }

    /**
     * Day flags from Telegram's own message cache (plugin's _local_day_flags). The cache has gaps,
     * so only days with messages from both sides are taken, the rest is still asked from the server.
     */
    private void loadLocalDays(Scan scan, Runnable next) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        long now = System.currentTimeMillis();
        int tzOffset = TimeZone.getDefault().getOffset(now) / 1000;
        storage.getStorageQueue().postRunnable(() -> {
            ArrayList<Integer> bothDays = new ArrayList<>();
            SQLiteCursor cursor = null;
            try {
                cursor = storage.getDatabase().queryFinalized("SELECT ((date + " + tzOffset + ") / 86400) AS d, MAX(out), MIN(out) FROM messages_v2 WHERE uid = " + scan.dialogId + " AND mid > 0 AND date > 0 GROUP BY d ORDER BY d DESC LIMIT 3000");
                while (cursor.next()) {
                    if (cursor.intValue(1) == 1 && cursor.intValue(2) == 0) {
                        bothDays.add(cursor.intValue(0));
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            queue.postRunnable(() -> {
                if (scan.gen == generation) {
                    for (int i = 0; i < bothDays.size(); i++) {
                        mark(scan.dialogId, bothDays.get(i), FLAG_BOTH, true);
                    }
                }
                next.run();
            });
        });
    }

    /**
     * Streak scan (algorithm of the plugin's StreakScan): walk back day by day until the chain breaks.
     * Each unknown day is probed with offset_date = end of that day, so as soon as a page shows messages
     * from both sides the rest of the day is skipped. {@link #PROBES} days are probed in parallel.
     */
    private static class Scan {
        long dialogId;
        int gen;
        int today;
        int day;        // day being checked
        int requests;
        Runnable done;
        /** Days whose messages were all seen. */
        final HashSet<Integer> complete = new HashSet<>();
        /** Days below this are complete too (the history ended). */
        int completeBelow = Integer.MIN_VALUE;
        /** Day -> offset_id to keep paging a day that didn't fit into one page. */
        final HashMap<Integer, Integer> continueFrom = new HashMap<>();

        boolean isComplete(int d) {
            return d < completeBelow || complete.contains(d);
        }
    }

    private static boolean isBoth(HashMap<Integer, Integer> map, int day) {
        Integer f = map == null ? null : map.get(day);
        return f != null && (f & FLAG_BOTH) == FLAG_BOTH;
    }

    /**
     * Advances {@code scan.day} over days that are already decided.
     *
     * @return true if the scan finished
     */
    private boolean walk(Scan scan) {
        HashMap<Integer, Integer> map = days.get(scan.dialogId);
        while (true) {
            boolean both = isBoth(map, scan.day);
            boolean complete = scan.isComplete(scan.day);
            if (scan.day == scan.today) {
                // today doesn't have to count yet, just learn its flags
                if (complete || both) {
                    scan.day--;
                    continue;
                }
                return false;
            }
            if (both) {
                scan.day--;
                continue;
            }
            if (complete) {
                finishScan(scan); // a complete day without both sides: the chain is broken
                return true;
            }
            return false; // the day is known only partially, need another page
        }
    }

    /** Requests the pages needed to decide {@code scan.day} and a few days after it. */
    private void next(Scan scan) {
        if (scan.gen != generation) {
            scan.done.run();
            return;
        }
        if (scan.requests >= SYNC_MAX_REQUESTS) {
            finishScan(scan);
            return;
        }
        scanProgress.put(scan.dialogId, scan.today - scan.day);
        notifyProgress();
        ArrayList<int[]> pages = new ArrayList<>(); // {day, offset_id, offset_date}
        Integer offsetId = scan.continueFrom.get(scan.day);
        if (offsetId != null) {
            pages.add(new int[]{scan.day, offsetId, 0});
        } else {
            HashMap<Integer, Integer> map = days.get(scan.dialogId);
            for (int d = scan.day; pages.size() < PROBES && d > scan.day - PROBES * 3 && d >= scan.completeBelow; d--) {
                if (d != scan.day && (scan.isComplete(d) || isBoth(map, d) || scan.continueFrom.containsKey(d))) {
                    continue;
                }
                pages.add(new int[]{d, 0, d == scan.today ? 0 : midnightOf(d + 1)});
            }
        }
        fetch(scan, pages);
    }

    private void fetch(Scan scan, ArrayList<int[]> pages) {
        TLRPC.InputPeer peer = MessagesController.getInstance(account).getInputPeer(scan.dialogId);
        if (peer == null || peer instanceof TLRPC.TL_inputPeerEmpty || pages.isEmpty()) {
            scan.done.run();
            return;
        }
        int count = pages.size();
        TLRPC.messages_Messages[] results = new TLRPC.messages_Messages[count];
        TLRPC.TL_error[] errors = new TLRPC.TL_error[count];
        int[] pending = {count};
        scan.requests += count;
        for (int i = 0; i < count; i++) {
            int index = i;
            int[] page = pages.get(i);
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = peer;
            req.offset_id = page[1];
            req.offset_date = page[2];
            req.limit = PAGE_SIZE;
            long now = System.currentTimeMillis();
            long at = Math.max(Math.max(now, nextRequestAt), floodUntil);
            nextRequestAt = at + REQUEST_INTERVAL;
            queue.postRunnable(() -> ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> queue.postRunnable(() -> {
                if (response instanceof TLRPC.messages_Messages) {
                    results[index] = (TLRPC.messages_Messages) response;
                } else {
                    errors[index] = error != null ? error : new TLRPC.TL_error();
                }
                if (--pending[0] == 0) {
                    onFetched(scan, pages, results, errors);
                }
            })), at - now);
        }
    }

    private void onFetched(Scan scan, ArrayList<int[]> pages, TLRPC.messages_Messages[] results, TLRPC.TL_error[] errors) {
        if (scan.gen != generation) {
            scan.done.run();
            return;
        }
        boolean failed = false;
        for (int i = 0; i < pages.size(); i++) {
            if (results[i] != null) {
                applyPage(scan, pages.get(i)[0], results[i].messages);
            } else {
                handleFlood(errors[i]);
                failed = true;
            }
        }
        if (walk(scan)) {
            return;
        }
        long wait = floodUntil - System.currentTimeMillis();
        if (failed && wait <= 0) {
            scan.done.run(); // not a FLOOD_WAIT: give up, the chat is retried when shown again
            return;
        }
        queue.postRunnable(() -> next(scan), Math.max(REQUEST_DELAY, wait));
    }

    /** Page of history that starts at the end of {@code day} (or inside it, when continuing). */
    private void applyPage(Scan scan, int day, ArrayList<TLRPC.Message> messages) {
        int oldestId = Integer.MAX_VALUE;
        int oldestDay = Integer.MAX_VALUE;
        for (int i = 0; i < messages.size(); i++) {
            TLRPC.Message message = messages.get(i);
            oldestId = Math.min(oldestId, message.id);
            if (message.date != 0) {
                oldestDay = Math.min(oldestDay, dayOf(message.date));
            }
            if (message instanceof TLRPC.TL_messageService || message instanceof TLRPC.TL_messageEmpty || message.date == 0) {
                continue;
            }
            mark(scan.dialogId, dayOf(message.date), message.out ? FLAG_MINE : FLAG_THEIRS, true);
        }
        if (messages.size() < PAGE_SIZE || oldestDay == Integer.MAX_VALUE) {
            // history ended: this day and everything before it is known
            scan.completeBelow = Math.max(scan.completeBelow, day + 1);
            scan.continueFrom.remove(day);
        } else if (oldestDay >= day) {
            // the whole page is inside the day: keep paging it
            scan.continueFrom.put(day, oldestId);
        } else {
            // days newer than the oldest one of the page are known completely
            for (int d = oldestDay + 1; d <= day; d++) {
                scan.complete.add(d);
            }
            scan.continueFrom.remove(day);
        }
    }

    private void finishScan(Scan scan) {
        recompute(scan.dialogId);
        synced.put(scan.dialogId, scan.today);
        saveSynced(scan.dialogId, scan.today);
        notifyUi();
        scan.done.run();
    }

    /** Unix time of the local midnight at the start of {@code day}. */
    private static int midnightOf(int day) {
        long ms = day * 86400000L;
        return (int) ((ms - TimeZone.getDefault().getOffset(ms)) / 1000);
    }

    void handleFlood(TLRPC.TL_error error) {
        if (error != null && error.text != null && error.text.startsWith("FLOOD_WAIT_")) {
            int seconds = Utilities.parseInt(error.text);
            floodUntil = System.currentTimeMillis() + Math.max(1, seconds) * 1000L;
        }
    }

    // endregion

    // region day storage (queue only)

    private boolean mark(long dialogId, int day, int flag, boolean persist) {
        HashMap<Integer, Integer> map = days.get(dialogId);
        if (map == null) {
            days.put(dialogId, map = new HashMap<>());
        }
        Integer old = map.get(day);
        int oldFlags = old == null ? 0 : old;
        int newFlags = oldFlags | flag;
        if (newFlags == oldFlags) {
            return false;
        }
        map.put(day, newFlags);
        if (persist) {
            try {
                ContentValues values = new ContentValues();
                values.put("dialog_id", dialogId);
                values.put("day", day);
                values.put("flags", newFlags);
                db().getWritableDatabase().insertWithOnConflict("streak_days", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return true;
    }

    private void recompute(long dialogId) {
        HashMap<Integer, Integer> map = days.get(dialogId);
        if (map == null) {
            streaks.remove(dialogId);
            return;
        }
        int lastBoth = Integer.MIN_VALUE;
        for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
            if ((entry.getValue() & FLAG_BOTH) == FLAG_BOTH && entry.getKey() > lastBoth) {
                lastBoth = entry.getKey();
            }
        }
        if (lastBoth == Integer.MIN_VALUE) {
            streaks.remove(dialogId);
            return;
        }
        int length = 0;
        for (int day = lastBoth; ; day--) {
            Integer flags = map.get(day);
            if (flags == null || (flags & FLAG_BOTH) != FLAG_BOTH) {
                break;
            }
            length++;
        }
        streaks.put(dialogId, new Streak(lastBoth, length));
    }

    private void saveSynced(long dialogId, int day) {
        try {
            ContentValues values = new ContentValues();
            values.put("dialog_id", dialogId);
            values.put("synced_day", day);
            db().getWritableDatabase().insertWithOnConflict("streak_sync", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void load() {
        SQLiteDatabase database;
        try {
            database = db().getReadableDatabase();
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }
        try (Cursor cursor = database.rawQuery("SELECT dialog_id, day, flags FROM streak_days", null)) {
            while (cursor.moveToNext()) {
                mark(cursor.getLong(0), cursor.getInt(1), cursor.getInt(2), false);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        try (Cursor cursor = database.rawQuery("SELECT dialog_id, synced_day FROM streak_sync", null)) {
            while (cursor.moveToNext()) {
                synced.put(cursor.getLong(0), cursor.getInt(1));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        try (Cursor cursor = database.rawQuery("SELECT dialog_id, mx, lp, me, th, cm, ct, first, deleted FROM streak_stats", null)) {
            while (cursor.moveToNext()) {
                Stats s = new Stats();
                s.mx = cursor.getInt(1);
                s.lp = cursor.getInt(2);
                s.me = cursor.getInt(3);
                s.th = cursor.getInt(4);
                s.cm = cursor.getInt(5);
                s.ct = cursor.getInt(6);
                s.first = cursor.getInt(7);
                s.deleted = cursor.getInt(8);
                stats.put(cursor.getLong(0), s);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        for (Long dialogId : days.keySet()) {
            recompute(dialogId);
        }
        notifyUi();
    }

    /** Debounced repaint of dialogs list, chat header, profile and open stats windows. */
    void notifyUi() {
        AndroidUtilities.runOnUIThread(() -> {
            if (refreshScheduled) {
                return;
            }
            refreshScheduled = true;
            AndroidUtilities.runOnUIThread(() -> {
                refreshScheduled = false;
                NotificationCenter center = NotificationCenter.getInstance(account);
                center.postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
                center.postNotificationName(NotificationCenter.bloodyStreaksUpdated);
            }, 300);
        });
    }

    /** Debounced progress of running scans for open stats windows (no list/header rebuild). */
    private void notifyProgress() {
        AndroidUtilities.runOnUIThread(() -> {
            if (progressScheduled) {
                return;
            }
            progressScheduled = true;
            AndroidUtilities.runOnUIThread(() -> {
                progressScheduled = false;
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.bloodyStreaksUpdated);
            }, 250);
        });
    }

    private DbHelper db() {
        if (db == null) {
            db = new DbHelper("epicgram_streaks_" + userId + ".db");
        }
        return db;
    }

    private static class DbHelper extends SQLiteOpenHelper {

        DbHelper(String name) {
            super(ApplicationLoader.applicationContext, name, null, 3);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE streak_days (dialog_id INTEGER NOT NULL, day INTEGER NOT NULL, flags INTEGER NOT NULL, PRIMARY KEY (dialog_id, day))");
            db.execSQL("CREATE TABLE streak_sync (dialog_id INTEGER PRIMARY KEY, synced_day INTEGER NOT NULL)");
            onUpgrade(db, 1, 3);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                db.execSQL("CREATE TABLE streak_stats (dialog_id INTEGER PRIMARY KEY, mx INTEGER NOT NULL, lp INTEGER NOT NULL, me INTEGER NOT NULL, th INTEGER NOT NULL, cm INTEGER NOT NULL, ct INTEGER NOT NULL, first INTEGER NOT NULL, deleted INTEGER NOT NULL)");
            }
            if (oldVersion < 3) {
                // v2 scans stopped after 4000 messages: recount everything with day-skipping scans
                db.execSQL("DELETE FROM streak_sync");
            }
        }
    }

    // endregion
}
