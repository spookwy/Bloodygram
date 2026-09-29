package com.bloodygram.deleted;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import android.text.TextUtils;

import com.bloodygram.BloodyConfig;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers messages that were deleted on the server but kept locally.
 * Message ids are unique per account for private chats and basic groups (key 0)
 * and per channel for channels/supergroups (key = channel_id).
 */
public class BloodyDeletedMessages {

    private static final BloodyDeletedMessages[] instances = new BloodyDeletedMessages[UserConfig.MAX_ACCOUNT_COUNT];
    private static final DispatchQueue queue = new DispatchQueue("bloodyDeletedQueue");

    public static BloodyDeletedMessages getInstance(int account) {
        long userId = UserConfig.getInstance(account).getClientUserId();
        BloodyDeletedMessages instance = instances[account];
        if (instance == null || instance.userId != userId) {
            synchronized (BloodyDeletedMessages.class) {
                instance = instances[account];
                if (instance == null || instance.userId != userId) {
                    instances[account] = instance = new BloodyDeletedMessages(account, userId);
                }
            }
        }
        return instance;
    }

    private final int account;
    private final long userId;
    private final ConcurrentHashMap<Long, Set<Integer>> deleted = new ConcurrentHashMap<>();
    private DbHelper db;

    private BloodyDeletedMessages(int account, long userId) {
        this.account = account;
        this.userId = userId;
        if (userId != 0) {
            queue.postRunnable(this::load);
        }
    }

    /**
     * Called from the updates thread for updateDeleteMessages / updateDeleteChannelMessages.
     *
     * @return true if the deletion was intercepted and must not be applied locally
     */
    public boolean interceptDelete(long channelId, ArrayList<Integer> ids) {
        if (channelId == 0) {
            com.bloodygram.streaks.BloodyStreaks.getInstance(account).onRemoteDelete(ids);
        }
        BloodyConfig.load();
        if (!BloodyConfig.saveDeletedMessages || userId == 0 || ids == null || ids.isEmpty()) {
            return false;
        }
        ArrayList<Integer> copy = new ArrayList<>(ids);
        set(channelId).addAll(copy);
        final int deletedAt = (int) (System.currentTimeMillis() / 1000);
        queue.postRunnable(() -> {
            try {
                SQLiteDatabase database = db().getWritableDatabase();
                database.beginTransaction();
                try {
                    ContentValues values = new ContentValues();
                    for (int i = 0; i < copy.size(); i++) {
                        values.clear();
                        values.put("channel_id", channelId);
                        values.put("mid", copy.get(i));
                        values.put("deleted_at", deletedAt);
                        database.insertWithOnConflict("deleted_messages", null, values, SQLiteDatabase.CONFLICT_IGNORE);
                    }
                    database.setTransactionSuccessful();
                } finally {
                    database.endTransaction();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.bloodyMessagesMarkedDeleted, channelId, copy));
        return true;
    }

    public boolean isDeleted(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.scheduled || messageObject.getId() <= 0) {
            return false;
        }
        long channelId = messageObject.messageOwner.peer_id != null ? messageObject.messageOwner.peer_id.channel_id : 0;
        Set<Integer> ids = deleted.get(channelId);
        return ids != null && ids.contains(messageObject.getId());
    }

    public static class TrashItem {
        public final long dialogId;
        public final MessageObject message;
        public final int deletedAt;

        TrashItem(long dialogId, MessageObject message, int deletedAt) {
            this.dialogId = dialogId;
            this.message = message;
            this.deletedAt = deletedAt;
        }
    }

    /**
     * Every kept deleted message, newest deletion first. Our DB only has ids; the messages themselves are still
     * in Telegram's messages_v2 (the deletion was never applied), so they are read from there.
     */
    public void loadTrash(Utilities.Callback<ArrayList<TrashItem>> done) {
        queue.postRunnable(() -> {
            ArrayList<long[]> rows = new ArrayList<>(); // channel_id, mid, deleted_at
            try (Cursor cursor = db().getReadableDatabase().rawQuery("SELECT channel_id, mid, deleted_at FROM deleted_messages ORDER BY deleted_at DESC, mid DESC LIMIT 2000", null)) {
                while (cursor.moveToNext()) {
                    rows.add(new long[]{cursor.getLong(0), cursor.getInt(1), cursor.getInt(2)});
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(() -> readMessages(storage, rows, done));
        });
    }

    private void readMessages(MessagesStorage storage, ArrayList<long[]> rows, Utilities.Callback<ArrayList<TrashItem>> done) {
        HashMap<String, TLRPC.Message> found = new HashMap<>();
        ArrayList<Long> userIds = new ArrayList<>();
        ArrayList<Long> chatIds = new ArrayList<>();
        ArrayList<TLRPC.User> users = new ArrayList<>();
        ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        try {
            // group ids per channel (0 = private chats and basic groups, where ids are unique per account)
            HashMap<Long, ArrayList<Long>> byChannel = new HashMap<>();
            for (long[] row : rows) {
                byChannel.computeIfAbsent(row[0], k -> new ArrayList<>()).add(row[1]);
            }
            for (Map.Entry<Long, ArrayList<Long>> entry : byChannel.entrySet()) {
                long channelId = entry.getKey();
                String where = channelId != 0 ? "uid = " + (-channelId) : "is_channel = 0";
                SQLiteCursor cursor = storage.getDatabase().queryFinalized(String.format(Locale.US,
                        "SELECT data, mid, uid, date FROM messages_v2 WHERE %s AND mid IN (%s)", where, TextUtils.join(",", entry.getValue())));
                while (cursor.next()) {
                    NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data == null) {
                        continue;
                    }
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message != null) {
                        message.readAttachPath(data, UserConfig.getInstance(account).getClientUserId());
                        message.id = cursor.intValue(1);
                        message.dialog_id = cursor.longValue(2);
                        message.date = cursor.intValue(3);
                        found.put(channelId + ":" + message.id, message);
                        MessagesStorage.addUsersAndChatsFromMessage(message, userIds, chatIds, null);
                        if (message.dialog_id > 0 && !userIds.contains(message.dialog_id)) {
                            userIds.add(message.dialog_id);
                        } else if (message.dialog_id < 0 && !chatIds.contains(-message.dialog_id)) {
                            chatIds.add(-message.dialog_id);
                        }
                    }
                    data.reuse();
                }
                cursor.dispose();
            }
            if (!userIds.isEmpty()) {
                storage.getUsersInternal(userIds, users);
            }
            if (!chatIds.isEmpty()) {
                storage.getChatsInternal(TextUtils.join(",", chatIds), chats);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        AndroidUtilities.runOnUIThread(() -> {
            MessagesController controller = MessagesController.getInstance(account);
            controller.putUsers(users, true);
            controller.putChats(chats, true);
            ArrayList<TrashItem> items = new ArrayList<>();
            for (long[] row : rows) {
                TLRPC.Message message = found.get(row[0] + ":" + row[1]);
                if (message != null) {
                    items.add(new TrashItem(message.dialog_id, new MessageObject(account, message, false, true), (int) row[2]));
                }
            }
            done.run(items);
        });
    }

    private Set<Integer> set(long channelId) {
        Set<Integer> ids = deleted.get(channelId);
        if (ids == null) {
            ids = Collections.newSetFromMap(new ConcurrentHashMap<>());
            Set<Integer> prev = deleted.putIfAbsent(channelId, ids);
            if (prev != null) {
                ids = prev;
            }
        }
        return ids;
    }

    private void load() {
        try (Cursor cursor = db().getReadableDatabase().rawQuery("SELECT channel_id, mid FROM deleted_messages", null)) {
            while (cursor.moveToNext()) {
                set(cursor.getLong(0)).add(cursor.getInt(1));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.bloodyMessagesMarkedDeleted, 0L, null));
    }

    private DbHelper db() {
        if (db == null) {
            db = new DbHelper("epicgram_" + userId + ".db");
        }
        return db;
    }

    private static class DbHelper extends SQLiteOpenHelper {

        DbHelper(String name) {
            super(ApplicationLoader.applicationContext, name, null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE deleted_messages (channel_id INTEGER NOT NULL, mid INTEGER NOT NULL, deleted_at INTEGER NOT NULL, PRIMARY KEY (channel_id, mid))");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }
}
