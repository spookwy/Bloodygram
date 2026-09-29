package com.bloodygram.secret;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.bloodygram.BloodyConfig;
import com.bloodygram.BloodyStrings;
import com.bloodygram.ghost.BloodyGhost;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes incoming view-once / timed media behave like ordinary media, so it stays in the chat unblurred
 * instead of turning into "Expired photo".
 *
 * Every Telegram check (blur, secret viewer, self-destruct timer, local media wipe) keys off
 * {@code media.ttl_seconds}, so {@link #onDeserialized} clears it on incoming messages while they are
 * parsed, from the network and from the local DB alike. A copy of the media is kept in our own DB:
 * if the server later sends the already-expired version (media emptied), the copy is put back.
 * Secret (E2E) chats are left alone.
 */
public class BloodySecretKeeper {

    private static final String DB_NAME = "epicgram_secret_media.db";
    public static final int OPTION_BURN = 10010;
    private static final int VIEW_ONCE = 0x7FFFFFFF;

    private static Helper helper;
    /** key -> {original ttl, burned 0/1}; {0, 0} = not a kept secret message. Avoids a DB hit per cell layout. */
    private static final ConcurrentHashMap<String, int[]> states = new ConcurrentHashMap<>();

    /** Hook at the end of {@code TLRPC.Message.TLdeserialize}. */
    public static void onDeserialized(TLRPC.Message m) {
        if (m == null || m.out || !(m instanceof TLRPC.TL_message) || m instanceof TLRPC.TL_message_secret) {
            return;
        }
        TLRPC.MessageMedia media = m.media;
        if (media == null || media.ttl_seconds == 0 || ApplicationLoader.applicationContext == null) {
            return;
        }
        boolean isPhoto = media instanceof TLRPC.TL_messageMediaPhoto;
        if (!isPhoto && !(media instanceof TLRPC.TL_messageMediaDocument)) {
            return;
        }
        BloodyConfig.load();
        if (!BloodyConfig.keepSecretMedia) {
            return;
        }
        boolean hasContent = isPhoto
                ? media.photo != null && !(media.photo instanceof TLRPC.TL_photoEmpty)
                : media.document != null && !(media.document instanceof TLRPC.TL_documentEmpty);
        String key = key(m);
        if (hasContent) {
            int ttl = media.ttl_seconds;
            store(key, media, ttl);
            unlock(m, media);
        } else {
            TLRPC.MessageMedia saved = load(key);
            if (saved != null) {
                m.media = saved;
                unlock(m, saved);
            }
        }
    }

    /** True if this message had a self-destruct timer that {@link #onDeserialized} removed. */
    public static boolean wasSecret(TLRPC.Message m) {
        return state(m)[0] != 0;
    }

    public static boolean isBurned(TLRPC.Message m) {
        return state(m)[1] != 0;
    }

    /** "🔥 1 view" / "🔥 10 seconds" / "🔥 burned" prefix for the time label, or null for ordinary messages. */
    public static CharSequence timeMark(MessageObject message) {
        if (message == null || !BloodyConfig.keepSecretMedia) {
            return null;
        }
        int[] s = state(message.messageOwner);
        if (s[0] == 0) {
            return null;
        }
        if (s[1] != 0) {
            return BloodyStrings.get(R.string.BloodySecretBurned);
        }
        if (s[0] == VIEW_ONCE) {
            return BloodyStrings.get(R.string.BloodySecretOnce);
        }
        return BloodyStrings.format(R.string.BloodySecretTimed, LocaleController.formatTTLString(s[0]));
    }

    /** Photos and videos (not voice/round) come in blurred under the spoiler; a tap reveals them until the chat is reopened. */
    public static boolean needsSpoiler(MessageObject message) {
        return message != null && BloodyConfig.keepSecretMedia && (message.isPhoto() || message.isVideo())
                && !message.isRoundVideo() && wasSecret(message.messageOwner);
    }

    public static void addMenuItem(MessageObject message, ArrayList<CharSequence> items, ArrayList<Integer> options, ArrayList<Integer> icons) {
        if (message == null || message.isOut() || !wasSecret(message.messageOwner) || isBurned(message.messageOwner)) {
            return;
        }
        items.add(BloodyStrings.get(R.string.BloodySecretBurn));
        options.add(OPTION_BURN);
        icons.add(R.drawable.msg_delete);
    }

    /**
     * Tells the server the media was opened, so for the sender it counts as viewed and burns as usual.
     * Our copy is untouched: the timer was already stripped, so nothing wipes it locally. Passes ghost mode.
     */
    public static void burn(ChatActivity fragment, MessageObject message) {
        int account = fragment.getCurrentAccount();
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.Message owner = message.messageOwner;
        if (owner.peer_id != null && owner.peer_id.channel_id != 0) {
            TLRPC.TL_channels_readMessageContents req = new TLRPC.TL_channels_readMessageContents();
            req.channel = controller.getInputChannel(owner.peer_id.channel_id);
            if (req.channel == null) {
                return;
            }
            req.id.add(message.getId());
            BloodyGhost.send(account, req, null);
        } else {
            TLRPC.TL_messages_readMessageContents req = new TLRPC.TL_messages_readMessageContents();
            req.id.add(message.getId());
            BloodyGhost.send(account, req, (response, error) -> {
                if (response instanceof TLRPC.TL_messages_affectedMessages) {
                    TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;
                    controller.processNewDifferenceParams(-1, res.pts, -1, res.pts_count);
                }
            });
        }
        String key = key(owner);
        int[] s = state(owner);
        states.put(key, new int[]{s[0], 1});
        Utilities.globalQueue.postRunnable(() -> {
            try {
                ContentValues values = new ContentValues();
                values.put("burned", 1);
                db().getWritableDatabase().update("media", values, "k = ?", new String[]{key});
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.bloodyMessagesMarkedDeleted, 0L, null);
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.fire_on, BloodyStrings.get(R.string.BloodySecretBurnDone)).show();
    }

    private static int[] state(TLRPC.Message m) {
        if (m == null || m.out || m.media == null || ApplicationLoader.applicationContext == null) {
            return NONE;
        }
        String key = key(m);
        int[] s = states.get(key);
        if (s != null) {
            return s;
        }
        s = NONE;
        try (Cursor c = db().getReadableDatabase().rawQuery("SELECT ttl, burned FROM media WHERE k = ?", new String[]{key})) {
            if (c.moveToFirst()) {
                s = new int[]{Math.max(1, c.getInt(0)), c.getInt(1)};
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        states.put(key, s);
        return s;
    }

    private static final int[] NONE = {0, 0};

    private static void unlock(TLRPC.Message m, TLRPC.MessageMedia media) {
        media.ttl_seconds = 0;
        media.flags &= ~4; // ttl_seconds flag, same bit for photo and document media
        m.ttl = 0;
    }

    // stable across re-fetches and unique across accounts: sender, chat, message id and date
    private static String key(TLRPC.Message m) {
        return peer(m.from_id) + ":" + peer(m.peer_id) + ":" + m.id + ":" + m.date;
    }

    private static long peer(TLRPC.Peer p) {
        if (p == null) {
            return 0;
        }
        return p.user_id != 0 ? p.user_id : p.chat_id != 0 ? -p.chat_id : -1000000000000L - p.channel_id;
    }

    private static void store(String key, TLRPC.MessageMedia media, int ttl) {
        try {
            SerializedData data = new SerializedData(media.getObjectSize());
            media.serializeToStream(data);
            ContentValues values = new ContentValues();
            values.put("k", key);
            values.put("data", data.toByteArray());
            values.put("ttl", ttl);
            values.put("burned", 0);
            data.cleanup();
            db().getWritableDatabase().insertWithOnConflict("media", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            int[] cached = states.get(key);
            if (cached == null || cached[0] == 0) {
                states.remove(key); // re-read so an existing "burned" flag is kept
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static TLRPC.MessageMedia load(String key) {
        try (Cursor c = db().getReadableDatabase().rawQuery("SELECT data FROM media WHERE k = ?", new String[]{key})) {
            if (!c.moveToFirst()) {
                return null;
            }
            SerializedData data = new SerializedData(c.getBlob(0));
            TLRPC.MessageMedia media = TLRPC.MessageMedia.TLdeserialize(data, data.readInt32(false), false);
            data.cleanup();
            return media;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static synchronized Helper db() {
        if (helper == null) {
            helper = new Helper();
        }
        return helper;
    }

    private static class Helper extends SQLiteOpenHelper {
        Helper() {
            super(ApplicationLoader.applicationContext, DB_NAME, null, 2);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE media (k TEXT PRIMARY KEY, data BLOB, ttl INTEGER, burned INTEGER)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE media ADD COLUMN ttl INTEGER");
                db.execSQL("ALTER TABLE media ADD COLUMN burned INTEGER");
            }
        }
    }
}
