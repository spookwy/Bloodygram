package com.bloodygram;

import com.bloodygram.secret.BloodySecretSaver;
import com.bloodygram.streaks.BloodyStreakCelebration;
import com.bloodygram.streaks.BloodyStreakReminder;

/** Starts Bloodygram background parts for an account (called when its MessagesController is created). */
public class Bloody {

    private static boolean started;

    public static void onAccountReady(int account) {
        BloodySecretSaver.start(account);
        BloodyStreakCelebration.start(account);
        com.bloodygram.chat.BloodyAutoDelete.start(account);
        if (!started) {
            started = true;
            BloodyStreakReminder.schedule();
            com.bloodygram.chat.BloodyRemind.schedule(); // alarms are lost on reboot
        }
    }
}
