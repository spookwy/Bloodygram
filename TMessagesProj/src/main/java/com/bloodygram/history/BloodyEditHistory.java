package com.bloodygram.history;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.graphics.Typeface;

import com.bloodygram.BloodyConfig;
import com.bloodygram.BloodyStrings;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps previous text versions of edited messages.
 */
public class BloodyEditHistory {

    public static final int OPTION_EDIT_HISTORY = 10001;

    private static final BloodyEditHistory[] instances = new BloodyEditHistory[UserConfig.MAX_ACCOUNT_COUNT];
    private static final DispatchQueue queue = new DispatchQueue("bloodyEditHistoryQueue");

    public static BloodyEditHistory getInstance(int account) {
        long userId = UserConfig.getInstance(account).getClientUserId();
        BloodyEditHistory instance = instances[account];
        if (instance == null || instance.userId != userId) {
            synchronized (BloodyEditHistory.class) {
                instance = instances[account];
                if (instance == null || instance.userId != userId) {
                    instances[account] = instance = new BloodyEditHistory(account, userId);
                }
            }
        }
        return instance;
    }

    public static class Version {
        public final int date;
        public final String text;

        Version(int date, String text) {
            this.date = date;
            this.text = text;
        }
    }

    private final int account;
    private final long userId;
    private final ConcurrentHashMap<Long, Set<Integer>> withHistory = new ConcurrentHashMap<>();
    private DbHelper db;

    private BloodyEditHistory(int account, long userId) {
        this.account = account;
        this.userId = userId;
        if (userId != 0) {
            queue.postRunnable(this::load);
        }
    }

    /**
     * Called from the updates thread for updateEditMessage / updateEditChannelMessage,
     * after message.dialog_id is filled and before the new version is written to the cache.
     */
    public void onMessageEdited(TLRPC.Message message) {
        BloodyConfig.load();
        if (!BloodyConfig.saveEditHistory || userId == 0 || message == null || message.id <= 0 || message.dialog_id == 0) {
            return;
        }
        final long dialogId = message.dialog_id;
        final int mid = message.id;
        final String newText = message.message == null ? "" : message.message;
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            TLRPC.Message old = readCachedMessage(storage, dialogId, mid);
            if (old == null) {
                return;
            }
            String oldText = old.message == null ? "" : old.message;
            if (oldText.equals(newText)) {
                return;
            }
            int versionDate = old.edit_date != 0 ? old.edit_date : old.date;
            set(dialogId).add(mid);
            queue.postRunnable(() -> {
                try {
                    ContentValues values = new ContentValues();
                    values.put("dialog_id", dialogId);
                    values.put("mid", mid);
                    values.put("version_date", versionDate);
                    values.put("text", oldText);
                    db().getWritableDatabase().insert("edit_history", null, values);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            });
        });
    }

    public boolean hasHistory(MessageObject messageObject) {
        if (messageObject == null || messageObject.getId() <= 0) {
            return false;
        }
        Set<Integer> ids = withHistory.get(messageObject.getDialogId());
        return ids != null && ids.contains(messageObject.getId());
    }

    public static void addMenuItem(int account, MessageObject messageObject, ArrayList<CharSequence> items, ArrayList<Integer> options, ArrayList<Integer> icons) {
        if (getInstance(account).hasHistory(messageObject)) {
            items.add(BloodyStrings.get(R.string.BloodyEditHistory));
            options.add(OPTION_EDIT_HISTORY);
            icons.add(R.drawable.msg_recent);
        }
    }

    public void show(BaseFragment fragment, MessageObject messageObject) {
        if (fragment == null || messageObject == null) {
            return;
        }
        final long dialogId = messageObject.getDialogId();
        final int mid = messageObject.getId();
        final String currentText = messageObject.messageOwner.message == null ? "" : messageObject.messageOwner.message;
        final int currentDate = messageObject.messageOwner.edit_date != 0 ? messageObject.messageOwner.edit_date : messageObject.messageOwner.date;
        queue.postRunnable(() -> {
            ArrayList<Version> versions = getVersions(dialogId, mid);
            versions.add(new Version(currentDate, currentText));
            AndroidUtilities.runOnUIThread(() -> {
                if (fragment.getParentActivity() == null) {
                    return;
                }
                SpannableStringBuilder text = new SpannableStringBuilder();
                for (int i = 0; i < versions.size(); i++) {
                    Version version = versions.get(i);
                    if (text.length() > 0) {
                        text.append("\n\n");
                    }
                    int start = text.length();
                    text.append(LocaleController.formatDateTime(version.date, true));
                    if (i == versions.size() - 1) {
                        text.append(" · ").append(BloodyStrings.get(R.string.BloodyEditHistoryCurrent));
                    }
                    text.setSpan(new StyleSpan(Typeface.BOLD), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    text.append("\n").append(version.text);
                }
                AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
                builder.setTitle(BloodyStrings.get(R.string.BloodyEditHistory));
                builder.setMessage(text);
                builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
                fragment.showDialog(builder.create());
            });
        });
    }

    private ArrayList<Version> getVersions(long dialogId, int mid) {
        ArrayList<Version> result = new ArrayList<>();
        try (Cursor cursor = db().getReadableDatabase().rawQuery(
                "SELECT version_date, text FROM edit_history WHERE dialog_id = ? AND mid = ? ORDER BY version_date ASC, rowid ASC",
                new String[]{String.valueOf(dialogId), String.valueOf(mid)})) {
            while (cursor.moveToNext()) {
                result.add(new Version(cursor.getInt(0), cursor.getString(1)));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    private static TLRPC.Message readCachedMessage(MessagesStorage storage, long dialogId, int mid) {
        SQLiteCursor cursor = null;
        try {
            cursor = storage.getDatabase().queryFinalized("SELECT data FROM messages_v2 WHERE uid = ? AND mid = ? LIMIT 1", dialogId, mid);
            if (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data != null) {
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    data.reuse();
                    return message;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        return null;
    }

    private Set<Integer> set(long dialogId) {
        Set<Integer> ids = withHistory.get(dialogId);
        if (ids == null) {
            ids = Collections.newSetFromMap(new ConcurrentHashMap<>());
            Set<Integer> prev = withHistory.putIfAbsent(dialogId, ids);
            if (prev != null) {
                ids = prev;
            }
        }
        return ids;
    }

    private void load() {
        try (Cursor cursor = db().getReadableDatabase().rawQuery("SELECT DISTINCT dialog_id, mid FROM edit_history", null)) {
            while (cursor.moveToNext()) {
                set(cursor.getLong(0)).add(cursor.getInt(1));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private DbHelper db() {
        if (db == null) {
            db = new DbHelper("epicgram_edits_" + userId + ".db");
        }
        return db;
    }

    private static class DbHelper extends SQLiteOpenHelper {

        DbHelper(String name) {
            super(ApplicationLoader.applicationContext, name, null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE edit_history (dialog_id INTEGER NOT NULL, mid INTEGER NOT NULL, version_date INTEGER NOT NULL, text TEXT NOT NULL)");
            db.execSQL("CREATE INDEX edit_history_msg ON edit_history (dialog_id, mid)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }
}
