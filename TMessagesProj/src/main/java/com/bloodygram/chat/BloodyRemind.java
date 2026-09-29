package com.bloodygram.chat;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.bloodygram.BloodyConfig;
import com.bloodygram.BloodyStrings;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.Calendar;

/** "Remind me about this message": a local notification later that opens the chat right at the message. */
public class BloodyRemind extends BroadcastReceiver {

    public static final int OPTION_REMIND = 10009;
    private static final String CHANNEL = "bloodygram_reminders";
    private static final String KEY = "reminders";
    private static final int[] MINUTES = {30, 60, 3 * 60, -1}; // -1 = tomorrow 9:00
    private static final int[] LABELS = {R.string.BloodyRemind30m, R.string.BloodyRemind1h, R.string.BloodyRemind3h, R.string.BloodyRemindTomorrow};

    public static void addMenuItem(MessageObject message, ArrayList<CharSequence> items, ArrayList<Integer> options, ArrayList<Integer> icons) {
        if (message == null || message.getId() <= 0 || DialogObject.isEncryptedDialog(message.getDialogId())) {
            return;
        }
        items.add(BloodyStrings.get(R.string.BloodyRemind));
        options.add(OPTION_REMIND);
        icons.add(R.drawable.menu_premium_clock);
    }

    public static void ask(ChatActivity fragment, MessageObject message) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        String[] labels = new String[LABELS.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = BloodyStrings.get(LABELS[i]);
        }
        new AlertDialog.Builder(fragment.getParentActivity())
                .setTitle(BloodyStrings.get(R.string.BloodyRemind))
                .setItems(labels, (d, which) -> {
                    long at;
                    if (MINUTES[which] > 0) {
                        at = System.currentTimeMillis() + MINUTES[which] * 60_000L;
                    } else {
                        Calendar c = Calendar.getInstance();
                        c.add(Calendar.DAY_OF_YEAR, 1);
                        c.set(Calendar.HOUR_OF_DAY, 9);
                        c.set(Calendar.MINUTE, 0);
                        c.set(Calendar.SECOND, 0);
                        at = c.getTimeInMillis();
                    }
                    add(fragment.getCurrentAccount(), message, at);
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, BloodyStrings.format(R.string.BloodyRemindSet, labels[which])).show();
                })
                .show();
    }

    private static void add(int account, MessageObject message, long at) {
        try {
            JSONArray list = load();
            String text = message.messageText != null ? message.messageText.toString() : "";
            if (text.length() > 200) {
                text = text.substring(0, 200) + "…";
            }
            list.put(new JSONObject()
                    .put("account", account)
                    .put("dialog", message.getDialogId())
                    .put("mid", message.getId())
                    .put("at", at)
                    .put("title", chatName(account, message.getDialogId()))
                    .put("text", text));
            BloodyConfig.putString(KEY, list.toString());
            schedule();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static String chatName(int account, long dialogId) {
        MessagesController controller = MessagesController.getInstance(account);
        if (dialogId > 0) {
            TLRPC.User user = controller.getUser(dialogId);
            return user != null ? UserObject.getUserName(user) : "";
        }
        TLRPC.Chat chat = controller.getChat(-dialogId);
        return chat != null && chat.title != null ? chat.title : "";
    }

    private static JSONArray load() {
        try {
            return new JSONArray(BloodyConfig.prefs().getString(KEY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** Alarm for the earliest pending reminder. */
    public static void schedule() {
        try {
            JSONArray list = load();
            long next = Long.MAX_VALUE;
            for (int i = 0; i < list.length(); i++) {
                next = Math.min(next, list.getJSONObject(i).getLong("at"));
            }
            Context context = ApplicationLoader.applicationContext;
            AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pending = PendingIntent.getBroadcast(context, 2, new Intent(context, BloodyRemind.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            alarms.cancel(pending);
            if (next == Long.MAX_VALUE) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 23) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending);
            } else {
                alarms.set(AlarmManager.RTC_WAKEUP, next, pending);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            JSONArray list = load(), left = new JSONArray();
            long now = System.currentTimeMillis();
            for (int i = 0; i < list.length(); i++) {
                JSONObject r = list.getJSONObject(i);
                if (r.getLong("at") <= now + 30_000) {
                    notify(context, r);
                } else {
                    left.put(r);
                }
            }
            BloodyConfig.putString(KEY, left.toString());
            schedule();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void notify(Context context, JSONObject r) throws Exception {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, BloodyStrings.get(R.string.BloodyRemindChannel), NotificationManager.IMPORTANCE_HIGH));
        }
        long dialogId = r.getLong("dialog");
        int mid = r.getInt("mid");
        Intent open = new Intent(context, LaunchActivity.class);
        open.setAction("com.tmessages.openchat" + Math.random() + Integer.MAX_VALUE);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (dialogId > 0) {
            open.putExtra("userId", dialogId);
        } else {
            open.putExtra("chatId", -dialogId);
        }
        open.putExtra("message_id", mid);
        open.putExtra("currentAccount", r.getInt("account"));
        PendingIntent content = PendingIntent.getActivity(context, mid, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = r.optString("title");
        String text = r.optString("text");
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.notification)
                .setColor(0xFFE0243C)
                .setContentTitle("⏰ " + (TextUtils.isEmpty(title) ? BloodyStrings.get(R.string.BloodyRemind) : title))
                .setContentText(TextUtils.isEmpty(text) ? BloodyStrings.get(R.string.BloodyRemindMedia) : text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(TextUtils.isEmpty(text) ? BloodyStrings.get(R.string.BloodyRemindMedia) : text))
                .setAutoCancel(true)
                .setContentIntent(content)
                .setCategory(NotificationCompat.CATEGORY_REMINDER);
        try {
            NotificationManagerCompat.from(context).notify(0x0B200000 + mid, builder.build());
        } catch (SecurityException e) {
            FileLog.e(e); // no notification permission
        }
    }
}
