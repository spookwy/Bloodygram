package com.bloodygram.streaks;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.bloodygram.BloodyConfig;
import com.bloodygram.BloodyStrings;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.Calendar;

/** Evening notification "the fire with X goes out at midnight" for streaks that are alive only thanks to yesterday. */
public class BloodyStreakReminder extends BroadcastReceiver {

    private static final String CHANNEL = "bloodygram_streaks";
    private static final int NOTIFICATION_ID = 0x0B100D;

    /** (Re)schedules the next reminder at the configured hour. */
    public static void schedule() {
        try {
            Context context = ApplicationLoader.applicationContext;
            AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pending = pendingIntent(context);
            alarms.cancel(pending);
            BloodyConfig.load();
            if (!BloodyConfig.streaksEnabled || !BloodyConfig.streakReminder) {
                return;
            }
            Calendar at = Calendar.getInstance();
            at.set(Calendar.HOUR_OF_DAY, BloodyConfig.streakReminderHour);
            at.set(Calendar.MINUTE, 0);
            at.set(Calendar.SECOND, 0);
            at.set(Calendar.MILLISECOND, 0);
            if (at.getTimeInMillis() <= System.currentTimeMillis() + 60_000) {
                at.add(Calendar.DAY_OF_YEAR, 1);
            }
            if (Build.VERSION.SDK_INT >= 23) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.getTimeInMillis(), pending);
            } else {
                alarms.set(AlarmManager.RTC_WAKEUP, at.getTimeInMillis(), pending);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, BloodyStreakReminder.class);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult result = goAsync();
        AndroidUtilities.runOnUIThread(() -> {
            try {
                ApplicationLoader.postInitApplication();
                for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                    if (UserConfig.getInstance(account).isClientActivated()) {
                        check(account);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            schedule();
            AndroidUtilities.runOnUIThread(result::finish, 3000);
        });
    }

    private static void check(int account) {
        BloodyStreaks streaks = BloodyStreaks.getInstance(account);
        streaks.collectAtRisk(atRisk -> {
            if (atRisk.isEmpty()) {
                return;
            }
            atRisk.sort((a, b) -> Long.compare(b[1], a[1]));
            MessagesController controller = MessagesController.getInstance(account);
            ArrayList<String> names = new ArrayList<>();
            for (long[] item : atRisk) {
                TLRPC.User user = controller.getUser(item[0]);
                if (user == null) {
                    user = org.telegram.messenger.MessagesStorage.getInstance(account).getUserSync(item[0]); // cold start from the alarm
                }
                names.add((user != null ? UserObject.getFirstName(user) : "?") + " 🔥" + item[1]);
            }
            notify(account, atRisk.get(0)[0], names);
        });
    }

    private static void notify(int account, long dialogId, ArrayList<String> names) {
        Context context = ApplicationLoader.applicationContext;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(CHANNEL, BloodyStrings.get(R.string.BloodyReminderChannel), NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(context, LaunchActivity.class);
        open.setAction("com.tmessages.openchat" + Math.random() + Integer.MAX_VALUE);
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra("userId", dialogId);
        open.putExtra("currentAccount", account);
        PendingIntent content = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = BloodyStrings.get(R.string.BloodyReminderTitle);
        String text = names.size() == 1
                ? BloodyStrings.format(R.string.BloodyReminderOne, names.get(0))
                : BloodyStrings.format(R.string.BloodyReminderMany, android.text.TextUtils.join(", ", names));
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.notification)
                .setColor(0xFFE0243C)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(content)
                .setCategory(NotificationCompat.CATEGORY_REMINDER);
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID + account, builder.build());
        } catch (SecurityException e) {
            FileLog.e(e); // no notification permission
        }
    }
}
