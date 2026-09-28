package com.epicgram.keepalive;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.epicgram.EpicConfig;
import com.epicgram.EpicStrings;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.LaunchActivity;

/**
 * Foreground service that keeps the process and Telegram's push connection alive
 * without Firebase: updates (and messages that get deleted later) arrive while the app is closed.
 */
public class EpicKeepAliveService extends Service {

    private static final String CHANNEL_ID = "epicgram_keep_alive";
    private static final int NOTIFICATION_ID = 0x0E91C;

    /**
     * Hook for {@code ApplicationLoader.startPushService()}.
     *
     * @return true if Epicgram handled it and the upstream service must not be started
     */
    public static boolean onStartPushService() {
        EpicConfig.load();
        Context context = ApplicationLoader.applicationContext;
        Intent intent = new Intent(context, EpicKeepAliveService.class);
        if (!EpicConfig.keepAlive) {
            context.stopService(intent);
            return false;
        }
        MessagesController.getGlobalNotificationsSettings().edit()
                .putBoolean("pushConnection", true)
                .putBoolean("pushService", false)
                .apply();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                ConnectionsManager.getInstance(a).setPushConnectionEnabled(true);
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable e) {
            // Android 12+ forbids starting from background in some states; next app open will retry
            FileLog.e(e);
        }
        return true;
    }

    public static void stop() {
        Context context = ApplicationLoader.applicationContext;
        context.stopService(new Intent(context, EpicKeepAliveService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ApplicationLoader.postInitApplication();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable e) {
            FileLog.e(e);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, EpicStrings.get(R.string.EpicKeepAliveChannel), NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            channel.enableVibration(false);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(this, LaunchActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(EpicStrings.get(R.string.EpicKeepAliveTitle))
                .setContentText(EpicStrings.get(R.string.EpicKeepAliveText))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pendingIntent)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
