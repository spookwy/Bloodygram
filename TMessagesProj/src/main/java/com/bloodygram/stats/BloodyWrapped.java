package com.bloodygram.stats;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteException;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * "Year in review" of the local Telegram message cache: how many messages were sent/received this year,
 * the busiest hour/weekday/month, the top chats and the most used emoji. Everything is read from the
 * on-device {@code messages_v2} cache, nothing is requested from the server.
 */
public class BloodyWrapped {

    /** Only the most recent outgoing messages are decoded for the emoji tally, to keep it fast. */
    private static final int EMOJI_SCAN_LIMIT = 20000;

    public static class Chat {
        public final long dialogId;
        public final int count;
        public Chat(long dialogId, int count) {
            this.dialogId = dialogId;
            this.count = count;
        }
    }

    public static class Emoji {
        public final String emoji;
        public final int count;
        public Emoji(String emoji, int count) {
            this.emoji = emoji;
            this.count = count;
        }
    }

    public static class Result {
        public int year;
        public long sent;
        public long received;
        public int topHour = -1;      // 0..23, -1 if unknown
        public int topWeekday = -1;   // 0=Sunday .. 6=Saturday
        public int topMonth = -1;     // 1..12
        public final ArrayList<Chat> topChats = new ArrayList<>();
        public final ArrayList<Emoji> topEmoji = new ArrayList<>();
    }

    public static void compute(int account, Utilities.Callback<Result> done) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        int tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000;

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.MONTH, Calendar.JANUARY);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long yearStart = cal.getTimeInMillis() / 1000;
        int year = cal.get(Calendar.YEAR);

        storage.getStorageQueue().postRunnable(() -> {
            Result result = new Result();
            result.year = year;
            String base = "FROM messages_v2 WHERE date >= " + yearStart + " AND mid > 0";
            try {
                countDirection(storage, base, result);
                result.topHour = topOf(storage, "((date + " + tz + ") % 86400) / 3600", base + " AND out = 1");
                result.topWeekday = topOf(storage, "((date + " + tz + ") / 86400 + 4) % 7", base + " AND out = 1");
                result.topMonth = topMonth(storage, base);
                topChats(storage, base, result);
                topEmoji(storage, account, yearStart, result);
            } catch (Exception e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> done.run(result));
        });
    }

    private static void countDirection(MessagesStorage storage, String base, Result result) throws SQLiteException {
        SQLiteCursor cursor = null;
        try {
            cursor = storage.getDatabase().queryFinalized("SELECT out, COUNT(*) " + base + " GROUP BY out");
            while (cursor.next()) {
                if (cursor.intValue(0) == 1) {
                    result.sent = cursor.longValue(1);
                } else {
                    result.received = cursor.longValue(1);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
    }

    /** Value of {@code expr} (grouped) that has the most messages, or -1. */
    private static int topOf(MessagesStorage storage, String expr, String where) throws SQLiteException {
        SQLiteCursor cursor = null;
        try {
            cursor = storage.getDatabase().queryFinalized("SELECT " + expr + " AS k, COUNT(*) c " + where + " GROUP BY k ORDER BY c DESC LIMIT 1");
            if (cursor.next()) {
                return cursor.intValue(0);
            }
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return -1;
    }

    private static int topMonth(MessagesStorage storage, String base) throws SQLiteException {
        SQLiteCursor cursor = null;
        try {
            cursor = storage.getDatabase().queryFinalized("SELECT CAST(strftime('%m', date, 'unixepoch', 'localtime') AS INTEGER) AS m, COUNT(*) c " + base + " GROUP BY m ORDER BY c DESC LIMIT 1");
            if (cursor.next()) {
                return cursor.intValue(0);
            }
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return -1;
    }

    private static void topChats(MessagesStorage storage, String base, Result result) throws SQLiteException {
        SQLiteCursor cursor = null;
        try {
            cursor = storage.getDatabase().queryFinalized("SELECT uid, COUNT(*) c " + base + " AND uid != 0 GROUP BY uid ORDER BY c DESC LIMIT 8");
            while (cursor.next()) {
                result.topChats.add(new Chat(cursor.longValue(0), cursor.intValue(1)));
            }
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
    }

    private static void topEmoji(MessagesStorage storage, int account, long yearStart, Result result) throws SQLiteException {
        HashMap<String, Integer> counts = new HashMap<>();
        SQLiteCursor cursor = null;
        try {
            cursor = storage.getDatabase().queryFinalized("SELECT data FROM messages_v2 WHERE date >= " + yearStart + " AND mid > 0 AND out = 1 ORDER BY date DESC LIMIT " + EMOJI_SCAN_LIMIT);
            while (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data == null) {
                    continue;
                }
                try {
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message != null && message.message != null && !message.message.isEmpty()) {
                        collectEmoji(message.message, counts);
                    }
                } catch (Exception ignore) {
                } finally {
                    data.reuse();
                }
            }
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }

        ArrayList<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        Collections.sort(entries, (a, b) -> b.getValue() - a.getValue());
        for (int i = 0; i < Math.min(8, entries.size()); i++) {
            result.topEmoji.add(new Emoji(entries.get(i).getKey(), entries.get(i).getValue()));
        }
    }

    // region emoji extraction

    /** Counts emoji graphemes (flags and ZWJ sequences kept whole, skin-tone/variation modifiers folded in). */
    private static void collectEmoji(String text, HashMap<String, Integer> counts) {
        int i = 0;
        int length = text.length();
        while (i < length) {
            int cp = text.codePointAt(i);
            int size = Character.charCount(cp);
            if (!isEmojiBase(cp)) {
                i += size;
                continue;
            }
            int start = i;
            i += size;
            if (isRegional(cp) && i < length) {
                int next = text.codePointAt(i);
                if (isRegional(next)) {
                    i += Character.charCount(next);
                }
            }
            // fold trailing modifiers and ZWJ-joined emoji into the same grapheme
            while (i < length) {
                int cp2 = text.codePointAt(i);
                if (cp2 == 0xFE0F || cp2 == 0x20E3 || isSkinTone(cp2)) {
                    i += Character.charCount(cp2);
                } else if (cp2 == 0x200D && i + 1 < length) {
                    i += 1;
                    int joined = text.codePointAt(i);
                    i += Character.charCount(joined);
                } else {
                    break;
                }
            }
            String emoji = text.substring(start, i);
            Integer prev = counts.get(emoji);
            counts.put(emoji, prev == null ? 1 : prev + 1);
        }
    }

    private static boolean isRegional(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    private static boolean isSkinTone(int cp) {
        return cp >= 0x1F3FB && cp <= 0x1F3FF;
    }

    private static boolean isEmojiBase(int cp) {
        return (cp >= 0x1F300 && cp <= 0x1FAFF)
                || (cp >= 0x1F000 && cp <= 0x1F0FF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x2B00 && cp <= 0x2BFF)
                || cp == 0x2122 || cp == 0x2139
                || (cp >= 0x2190 && cp <= 0x21FF)
                || (cp >= 0x2300 && cp <= 0x23FF)
                || (cp >= 0x25A0 && cp <= 0x25FF)
                || isRegional(cp);
    }

    // endregion
}
