package com.epicgram.streaks;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextPaint;
import android.text.TextUtils;

import com.epicgram.EpicConfig;

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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
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
public class EpicStreaks implements NotificationCenter.NotificationCenterDelegate {

    private static final int FLAG_MINE = 1;
    private static final int FLAG_THEIRS = 2;
    private static final int FLAG_BOTH = FLAG_MINE | FLAG_THEIRS;

    private static final int SYNC_MAX_REQUESTS = 400;
    private static final int PAGE_SIZE = 100;
    private static final int REQUEST_DELAY = 300;

    static final DispatchQueue queue = new DispatchQueue("epicStreaksQueue");
    private static final EpicStreaks[] instances = new EpicStreaks[UserConfig.MAX_ACCOUNT_COUNT];

    public static EpicStreaks getInstance(int account) {
        long userId = UserConfig.getInstance(account).getClientUserId();
        EpicStreaks instance = instances[account];
        if (instance == null || instance.userId != userId) {
            synchronized (EpicStreaks.class) {
                instance = instances[account];
                if (instance == null || instance.userId != userId) {
                    EpicStreaks old = instance;
                    instances[account] = instance = new EpicStreaks(account, userId);
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
    private final ArrayDeque<Long> syncQueue = new ArrayDeque<>();
    private final LinkedHashMap<Integer, Boolean> deletedSeen = new LinkedHashMap<Integer, Boolean>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) {
            return size() > 5000;
        }
    };
    private boolean syncRunning;
    private int generation;
    volatile long floodUntil;
    private DbHelper db;
    private boolean destroyed;
    private boolean refreshScheduled;

    private EpicStreaks(int account, long userId) {
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

    public int getTier(long dialogId) {
        int streak = getStreak(dialogId);
        return EpicFire.tier(streak, isAtRisk(dialogId));
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
        EpicConfig.load();
        return EpicConfig.streaksEnabled && isEligible(dialogId) && getStreak(dialogId) >= EpicConfig.streakMinDays;
    }

    /** " 🔥N" suffix with the vector fire sized for {@code paint}, or null if nothing to show. */
    public EpicFire.Suffix getSuffix(long dialogId, TextPaint paint) {
        if (paint == null || !shouldShow(dialogId)) {
            return null;
        }
        int streak = getStreak(dialogId);
        return EpicFire.suffix(streak, EpicFire.tier(streak, isAtRisk(dialogId)), paint);
    }

    /** {@code title} + " 🔥N", or {@code title} unchanged. */
    public CharSequence appendFire(CharSequence title, long dialogId, TextPaint paint) {
        EpicFire.Suffix suffix = getSuffix(dialogId, paint);
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
            syncRunning = false;
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

    /** Puts the chat first in the recount queue (opened chat / stats window). */
    public void requestSyncIfStale(long dialogId, boolean front) {
        if (userId == 0 || isCounted(dialogId) || !DialogObject.isUserDialog(dialogId)) {
            return;
        }
        if (!front && !queued.add(dialogId)) {
            return;
        }
        queued.add(dialogId);
        queue.postRunnable(() -> {
            syncQueue.remove(dialogId);
            if (front) {
                syncQueue.addFirst(dialogId);
            } else {
                syncQueue.addLast(dialogId);
            }
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
        if (syncRunning || destroyed) {
            return;
        }
        long wait = floodUntil - System.currentTimeMillis();
        if (wait > 0) {
            queue.postRunnable(this::pump, wait);
            return;
        }
        Long dialogId = syncQueue.pollFirst();
        if (dialogId == null) {
            return;
        }
        if (isCounted(dialogId)) {
            queued.remove(dialogId);
            pump();
            return;
        }
        syncRunning = true;
        Scan scan = new Scan();
        scan.dialogId = dialogId;
        scan.gen = generation;
        scan.today = today();
        scan.day = scan.today;
        scan.done = () -> {
            queued.remove(dialogId);
            if (scan.gen == generation) {
                syncRunning = false;
                queue.postRunnable(this::pump, REQUEST_DELAY);
            }
        };
        // days already known to have messages from both sides are taken from the cache, no requests
        if (walk(scan, Integer.MAX_VALUE)) {
            return;
        }
        request(scan, 0, scan.day == scan.today ? 0 : midnightOf(scan.day + 1));
    }

    /**
     * Streak scan (algorithm of the plugin's StreakScan): walk back day by day until the chain breaks.
     * As soon as a day has messages from both sides, the rest of that day is skipped
     * by jumping with offset_date straight to the end of the previous day.
     */
    private static class Scan {
        long dialogId;
        int gen;
        int today;
        int day;        // day being checked
        int requests;
        Runnable done;
    }

    /**
     * Advances {@code scan.day} over days that are already decided.
     * Days >= {@code floor} are known completely (all their messages were seen).
     *
     * @return true if the scan finished
     */
    private boolean walk(Scan scan, int floor) {
        HashMap<Integer, Integer> map = days.get(scan.dialogId);
        while (true) {
            Integer f = map == null ? null : map.get(scan.day);
            boolean both = f != null && (f & FLAG_BOTH) == FLAG_BOTH;
            boolean complete = scan.day >= floor;
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

    private void request(Scan scan, int offsetId, int offsetDate) {
        TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = MessagesController.getInstance(account).getInputPeer(scan.dialogId);
        req.offset_id = offsetId;
        req.offset_date = offsetDate;
        req.limit = PAGE_SIZE;
        if (req.peer == null || req.peer instanceof TLRPC.TL_inputPeerEmpty) {
            scan.done.run();
            return;
        }
        scan.requests++;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> queue.postRunnable(() -> {
            if (scan.gen != generation) {
                scan.done.run();
                return;
            }
            if (!(response instanceof TLRPC.messages_Messages)) {
                handleFlood(error);
                if (floodUntil > System.currentTimeMillis()) {
                    // retry the same page after FLOOD_WAIT
                    queue.postRunnable(() -> request(scan, offsetId, offsetDate), floodUntil - System.currentTimeMillis());
                } else {
                    scan.done.run();
                }
                return;
            }
            ArrayList<TLRPC.Message> messages = ((TLRPC.messages_Messages) response).messages;
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
            boolean historyEnd = messages.size() < PAGE_SIZE;
            // days strictly newer than the oldest day of the page are known completely
            int floor = historyEnd ? Integer.MIN_VALUE : oldestDay == Integer.MAX_VALUE ? Integer.MAX_VALUE : oldestDay + 1;
            if (walk(scan, floor)) {
                return;
            }
            if (historyEnd || oldestId == Integer.MAX_VALUE || scan.requests >= SYNC_MAX_REQUESTS) {
                finishScan(scan);
                return;
            }
            int nextId, nextDate;
            if (oldestDay != Integer.MAX_VALUE && scan.day < oldestDay) {
                // the current day is already counted: jump to the end of the previous one
                nextId = 0;
                nextDate = midnightOf(scan.day + 1);
            } else {
                // keep paging through the current day
                nextId = oldestId;
                nextDate = 0;
            }
            queue.postRunnable(() -> request(scan, nextId, nextDate), REQUEST_DELAY);
        }));
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
                center.postNotificationName(NotificationCenter.epicStreaksUpdated);
            }, 300);
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
