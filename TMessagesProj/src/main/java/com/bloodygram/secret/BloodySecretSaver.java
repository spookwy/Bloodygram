package com.bloodygram.secret;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;

import com.bloodygram.BloodyConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.secretmedia.EncryptedFileInputStream;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

/**
 * Keeps a copy of self-destructing and view-once media: as soon as such a message arrives its file is downloaded
 * (downloading is not reported to the sender, only opening is) and copied to the gallery, folder "Bloodygram".
 * Telegram keeps these files encrypted (file.enc + internal key), so they are decrypted on copy.
 */
public class BloodySecretSaver implements NotificationCenter.NotificationCenterDelegate {

    private static final String FOLDER = "Bloodygram";
    private static final BloodySecretSaver[] instances = new BloodySecretSaver[org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT];

    public static void start(int account) {
        if (instances[account] == null) {
            instances[account] = new BloodySecretSaver(account);
        }
    }

    private final int account;
    /** attach file name -> message waiting for its download */
    private final HashMap<String, MessageObject> pending = new HashMap<>();

    private BloodySecretSaver(int account) {
        this.account = account;
        NotificationCenter center = NotificationCenter.getInstance(account);
        center.addObserver(this, NotificationCenter.didReceiveNewMessages);
        center.addObserver(this, NotificationCenter.fileLoaded);
    }

    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        if (id == NotificationCenter.didReceiveNewMessages) {
            if (args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2]) {
                return; // scheduled
            }
            BloodyConfig.load();
            if (!BloodyConfig.saveSecretMedia) {
                return;
            }
            ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
            for (int i = 0; i < messages.size(); i++) {
                MessageObject message = messages.get(i);
                if (message != null && !message.isOut() && isSelfDestructing(message)) {
                    download(message);
                }
            }
        } else if (id == NotificationCenter.fileLoaded) {
            String name = (String) args[0];
            MessageObject message = pending.remove(name);
            if (message != null) {
                Utilities.globalQueue.postRunnable(() -> save(message));
            }
        }
    }

    public static boolean isSelfDestructing(MessageObject message) {
        TLRPC.Message owner = message.messageOwner;
        if (owner == null || MessageObject.getMedia(owner) == null) {
            return false;
        }
        return MessageObject.isSecretMedia(owner) || message.isVoiceOnce() || message.isRoundOnce()
                || owner instanceof TLRPC.TL_message_secret && owner.ttl > 0 && (message.isPhoto() || message.isVideo() || message.isVoice() || message.isRoundVideo());
    }

    private void download(MessageObject message) {
        int cacheType = message.shouldEncryptPhotoOrVideo() ? 2 : 0;
        TLRPC.Document document = message.getDocument();
        if (document != null) {
            String name = FileLoader.getAttachFileName(document);
            pending.put(name, message);
            FileLoader.getInstance(account).loadFile(document, message, FileLoader.PRIORITY_NORMAL, cacheType);
            return;
        }
        TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(message.photoThumbs, AndroidUtilities.getPhotoSize());
        if (size != null && message.photoThumbsObject instanceof TLRPC.Photo) {
            String name = FileLoader.getAttachFileName(size);
            pending.put(name, message);
            FileLoader.getInstance(account).loadFile(ImageLocation.getForPhoto(size, (TLRPC.Photo) message.photoThumbsObject), message, "jpg", FileLoader.PRIORITY_NORMAL, cacheType);
        }
    }

    private void save(MessageObject message) {
        try {
            File file = FileLoader.getInstance(account).getPathToMessage(message.messageOwner, false);
            InputStream in;
            File encrypted = new File(file.getAbsolutePath() + ".enc");
            if (encrypted.exists()) {
                File key = new File(FileLoader.getInternalCacheDir(), encrypted.getName() + ".key");
                in = new EncryptedFileInputStream(encrypted, key);
            } else if (file.exists()) {
                in = new FileInputStream(file);
            } else {
                return;
            }
            boolean video = message.isVideo() || message.isRoundVideo();
            boolean audio = message.isVoice();
            String mime = video ? "video/mp4" : audio ? "audio/ogg" : "image/jpeg";
            TLRPC.Document document = message.getDocument();
            if (document != null && !TextUtils.isEmpty(document.mime_type)) {
                mime = document.mime_type;
            }
            String ext = video ? ".mp4" : audio ? ".ogg" : ".jpg";
            String name = "secret_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(message.messageOwner.date * 1000L)) + "_" + message.getId() + ext;
            try (InputStream input = in) {
                write(input, name, mime, video, audio);
            }
            int saved = BloodyConfig.prefs().getInt("secretSaved", 0) + 1;
            BloodyConfig.putInt("secretSaved", saved);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void write(InputStream in, String name, String mime, boolean video, boolean audio) throws Exception {
        String dir = video ? Environment.DIRECTORY_MOVIES : audio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_PICTURES;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, dir + "/" + FOLDER);
            Uri collection = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : audio ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            Uri uri = resolver.insert(collection, values);
            if (uri == null) {
                return;
            }
            try (OutputStream out = resolver.openOutputStream(uri)) {
                copy(in, out);
            }
        } else {
            File folder = new File(Environment.getExternalStoragePublicDirectory(dir), FOLDER);
            folder.mkdirs();
            File dst = new File(folder, name);
            try (OutputStream out = new FileOutputStream(dst)) {
                copy(in, out);
            }
            MediaScannerConnection.scanFile(ApplicationLoader.applicationContext, new String[]{dst.getAbsolutePath()}, new String[]{mime}, null);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
    }
}
