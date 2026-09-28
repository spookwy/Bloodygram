package com.bloodygram.chat;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;

import com.bloodygram.BloodyConfig;
import com.bloodygram.BloodyStrings;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.ItemOptions;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * "Delete for everyone in N minutes": the next messages sent to the chat are deleted for both sides later.
 * The queue survives restarts; deletion runs while the app is alive (the keep-alive service keeps it alive)
 * and from an alarm otherwise.
 */
public class BloodyAutoDelete implements NotificationCenter.NotificationCenterDelegate {

    private static final int[] SECONDS = {60, 5 * 60, 60 * 60, 24 * 60 * 60};
    private static final int[] LABELS = {R.string.BloodyAutoDelete1m, R.string.BloodyAutoDelete5m, R.string.BloodyAutoDelete1h, R.string.BloodyAutoDelete1d};
    private static final int ARM_WINDOW = 10_000;

    private static final BloodyAutoDelete[] instances = new BloodyAutoDelete[UserConfig.MAX_ACCOUNT_COUNT];

    /** {dialogId, msgId, deleteAtMillis} */
    private final ArrayList<long[]> queue = new ArrayList<>();
    private final int account;
    private long armedDialog;
    private int armedSeconds;
    private long armedUntil;
    private final Runnable tick = this::process;

    public static BloodyAutoDelete start(int account) {
        if (instances[account] == null) {
            instances[account] = new BloodyAutoDelete(account);
        }
        return instances[account];
    }

    private BloodyAutoDelete(int account) {
        this.account = account;
        load();
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messageReceivedByServer);
        process();
    }

    /** Item in the send button menu (long press). */
    public static void addSendOption(ItemOptions options, int account, long dialogId, Runnable send) {
        if (DialogObject.isEncryptedDialog(dialogId)) {
            return; // secret chats have their own timers
        }
        Context menuContext = options.getLinearLayout() != null ? options.getLinearLayout().getContext() : null;
        options.add(R.drawable.msg_autodelete, BloodyStrings.get(R.string.BloodyAutoDeleteSend), () -> {
            Context context = org.telegram.ui.LaunchActivity.instance != null ? org.telegram.ui.LaunchActivity.instance : menuContext; // the menu is gone by now
            String[] labels = new String[LABELS.length];
            for (int i = 0; i < labels.length; i++) {
                labels[i] = BloodyStrings.get(LABELS[i]);
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle(BloodyStrings.get(R.string.BloodyAutoDeleteSend));
            builder.setItems(labels, (dialog, which) -> {
                start(account).arm(dialogId, SECONDS[which]);
                send.run();
            });
            builder.show();
        });
    }

    public static boolean isScheduled(int account, MessageObject message) {
        BloodyAutoDelete instance = instances[account];
        if (instance == null || message == null || instance.queue.isEmpty()) {
            return false;
        }
        long dialogId = message.getDialogId();
        int id = message.getId();
        for (int i = 0; i < instance.queue.size(); i++) {
            long[] item = instance.queue.get(i);
            if (item[0] == dialogId && item[1] == id) {
                return true;
            }
        }
        return false;
    }

    private void arm(long dialogId, int seconds) {
        armedDialog = dialogId;
        armedSeconds = seconds;
        armedUntil = SystemClock.elapsedRealtime() + ARM_WINDOW;
    }

    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        if (id != NotificationCenter.messageReceivedByServer || armedUntil < SystemClock.elapsedRealtime()) {
            return;
        }
        long dialogId = args[3] instanceof Long ? (Long) args[3] : 0;
        if (dialogId != armedDialog || args.length > 6 && args[6] instanceof Boolean && (Boolean) args[6]) {
            return;
        }
        int newId = (Integer) args[1];
        queue.add(new long[]{dialogId, newId, System.currentTimeMillis() + armedSeconds * 1000L});
        save();
        process();
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_SEND_STATE);
    }

    /** Deletes what is due and waits for the next one. */
    private void process() {
        AndroidUtilities.cancelRunOnUIThread(tick);
        long now = System.currentTimeMillis();
        HashMap<Long, ArrayList<Integer>> due = new HashMap<>();
        long next = Long.MAX_VALUE;
        for (int i = queue.size() - 1; i >= 0; i--) {
            long[] item = queue.get(i);
            if (item[2] <= now) {
                ArrayList<Integer> ids = due.get(item[0]);
                if (ids == null) {
                    due.put(item[0], ids = new ArrayList<>());
                }
                ids.add((int) item[1]);
                queue.remove(i);
            } else {
                next = Math.min(next, item[2]);
            }
        }
        if (!due.isEmpty()) {
            save();
            for (HashMap.Entry<Long, ArrayList<Integer>> entry : due.entrySet()) {
                try {
                    MessagesController.getInstance(account).deleteMessages(entry.getValue(), null, null, entry.getKey(), 0, true, 0);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
        if (next != Long.MAX_VALUE) {
            AndroidUtilities.runOnUIThread(tick, Math.max(500, next - now));
            scheduleAlarm(next);
        }
    }

    private String key() {
        return "autodel_" + UserConfig.getInstance(account).getClientUserId();
    }

    private void load() {
        queue.clear();
        String s = BloodyConfig.prefs().getString(key(), "");
        for (String part : s.split(";")) {
            String[] f = part.split(":");
            if (f.length == 3) {
                try {
                    queue.add(new long[]{Long.parseLong(f[0]), Long.parseLong(f[1]), Long.parseLong(f[2])});
                } catch (Exception ignore) {
                }
            }
        }
    }

    private void save() {
        ArrayList<String> parts = new ArrayList<>();
        for (long[] item : queue) {
            parts.add(item[0] + ":" + item[1] + ":" + item[2]);
        }
        BloodyConfig.putString(key(), TextUtils.join(";", parts));
    }

    private static void scheduleAlarm(long at) {
        try {
            Context context = ApplicationLoader.applicationContext;
            AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pending = PendingIntent.getBroadcast(context, 1, new Intent(context, Receiver.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (Build.VERSION.SDK_INT >= 23) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
            } else {
                alarms.set(AlarmManager.RTC_WAKEUP, at, pending);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Wakes the app up when a message is due and it was not running. */
    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            PendingResult result = goAsync();
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    ApplicationLoader.postInitApplication();
                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                        if (UserConfig.getInstance(a).isClientActivated()) {
                            MessagesController.getInstance(a); // starts Bloodygram parts of the account
                            start(a).process();
                        }
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
                AndroidUtilities.runOnUIThread(result::finish, 8000);
            });
        }
    }
}
