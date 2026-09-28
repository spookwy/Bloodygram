package com.bloodygram.deleted;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.bloodygram.BloodyConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.Collections;
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
