package com.bloodygram.streaks;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;

import com.bloodygram.BloodyConfig;
import com.bloodygram.BloodyStrings;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.FireworksOverlay;
import org.telegram.ui.LaunchActivity;

import static org.telegram.messenger.AndroidUtilities.dp;

/** The streak grew today: a burst of fire over the open chat, fireworks on milestones, a bulletin elsewhere. */
public class BloodyStreakCelebration implements NotificationCenter.NotificationCenterDelegate {

    private static final int[] MILESTONES = {7, 30, 50, 100, 150, 200, 250, 300, 365, 500, 730, 1000};
    private static final BloodyStreakCelebration[] instances = new BloodyStreakCelebration[UserConfig.MAX_ACCOUNT_COUNT];

    public static void start(int account) {
        if (instances[account] == null) {
            instances[account] = new BloodyStreakCelebration(account);
            NotificationCenter.getInstance(account).addObserver(instances[account], NotificationCenter.bloodyStreakGrew);
        }
    }

    public static boolean isMilestone(int days) {
        for (int m : MILESTONES) {
            if (m == days) {
                return true;
            }
        }
        return days > 0 && days % 100 == 0;
    }

    public static int[] milestones() {
        return MILESTONES;
    }

    private final int account;

    private BloodyStreakCelebration(int account) {
        this.account = account;
    }

    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        BloodyConfig.load();
        if (id != NotificationCenter.bloodyStreakGrew || !BloodyConfig.streakCelebration || !BloodyConfig.streaksEnabled) {
            return;
        }
        long dialogId = (Long) args[0];
        int days = (Integer) args[1];
        if (days < Math.max(1, BloodyConfig.streakMinDays)) {
            return;
        }
        BaseFragment fragment = LaunchActivity.getLastFragment();
        if (fragment == null || fragment.getParentActivity() == null || fragment.getCurrentAccount() != account) {
            return;
        }
        if (fragment instanceof ChatActivity && ((ChatActivity) fragment).getDialogId() == dialogId) {
            show(fragment.getParentActivity(), account, days, dialogId);
        } else {
            TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
            String name = user != null ? UserObject.getFirstName(user) : "";
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.fire_on, BloodyStrings.format(R.string.BloodyStreakGrewBulletin, name, BloodyStrings.days(days))).show();
        }
    }

    public static void show(Activity activity, int account, int days, long dialogId) {
        show((ViewGroup) activity.getWindow().getDecorView(), activity, account, days, dialogId);
    }

    public static void show(ViewGroup decor, Context activity, int account, int days, long dialogId) {
        TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
        String name = user != null ? UserObject.getFirstName(user) : "";
        BurstView view = new BurstView(activity, days, BloodyStrings.format(R.string.BloodyStreakGrewCaption, name));
        decor.addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (isMilestone(days)) {
            FireworksOverlay fireworks = new FireworksOverlay(activity);
            decor.addView(fireworks, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            fireworks.start();
            AndroidUtilities.runOnUIThread(() -> decor.removeView(fireworks), 5000);
        }
        try {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        } catch (Exception ignore) {
        }
    }

    private static class BurstView extends View {

        private static final int DURATION = 2600;
        private static final int SPARKS = 70;

        private final long start = SystemClock.uptimeMillis();
        private final BitmapDrawable fire;
        private final String number;
        private final String caption;
        private final TextPaint numberPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint captionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] sx = new float[SPARKS], sy = new float[SPARKS], svx = new float[SPARKS], svy = new float[SPARKS], sdelay = new float[SPARKS], ssize = new float[SPARKS];
        private final int[] scolor = new int[SPARKS];

        BurstView(Context context, int days, String caption) {
            super(context);
            int tier = BloodyFire.tier(days, false);
            fire = (BitmapDrawable) BloodyFire.drawable(dp(140), tier).getConstantState().newDrawable().mutate(); // the cached one is shared
            number = BloodyStrings.formatNumber(days);
            this.caption = caption;
            numberPaint.setColor(0xFFFFFFFF);
            numberPaint.setTextSize(dp(58));
            numberPaint.setTypeface(AndroidUtilities.bold());
            numberPaint.setTextAlign(Paint.Align.CENTER);
            numberPaint.setShadowLayer(dp(8), 0, 0, 0xAAE0243C);
            captionPaint.setColor(0xFFFFFFFF);
            captionPaint.setTextSize(dp(17));
            captionPaint.setTextAlign(Paint.Align.CENTER);
            int[] palette = {0xFFFFC107, 0xFFFF7A1A, 0xFFE0243C, 0xFFFFE08A};
            for (int i = 0; i < SPARKS; i++) {
                double angle = -Math.PI / 2 + (Utilities.fastRandom.nextFloat() - 0.5f) * Math.PI * 1.3;
                float speed = AndroidUtilities.dpf2(0.25f + 0.55f * Utilities.fastRandom.nextFloat());
                svx[i] = (float) Math.cos(angle) * speed;
                svy[i] = (float) Math.sin(angle) * speed;
                sdelay[i] = 250 + 400 * Utilities.fastRandom.nextFloat();
                ssize[i] = AndroidUtilities.dpf2(1.5f + 2.5f * Utilities.fastRandom.nextFloat());
                scolor[i] = palette[Utilities.fastRandom.nextInt(palette.length)];
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float t = SystemClock.uptimeMillis() - start;
            if (t > DURATION) {
                post(() -> {
                    if (getParent() != null) {
                        ((ViewGroup) getParent()).removeView(this);
                    }
                });
                return;
            }
            float appear = Math.min(1f, t / 500f);
            float fade = t > DURATION - 600 ? (DURATION - t) / 600f : 1f;
            float cx = getWidth() / 2f, cy = getHeight() * 0.42f;

            // dim + red glow
            canvas.drawColor(((int) (0x99 * Math.min(appear, fade)) << 24));
            glowPaint.setShader(new RadialGradient(cx, cy, dp(220), 0x90E0243C, 0x00E0243C, Shader.TileMode.CLAMP));
            glowPaint.setAlpha((int) (255 * Math.min(appear, fade)));
            canvas.drawCircle(cx, cy, dp(220), glowPaint);

            // sparks fly out of the fire
            for (int i = 0; i < SPARKS; i++) {
                float lt = t - sdelay[i];
                if (lt < 0) {
                    continue;
                }
                float p = lt / 1400f;
                if (p >= 1f) {
                    continue;
                }
                float x = cx + svx[i] * lt;
                float y = cy + dp(10) + svy[i] * lt + 0.00012f * lt * lt * dp(1);
                sparkPaint.setColor(scolor[i]);
                sparkPaint.setAlpha((int) (255 * (1f - p) * fade));
                canvas.drawCircle(x, y, ssize[i] * (1f - p * 0.5f), sparkPaint);
            }

            // fire: spring in, then flicker
            float scale = spring(appear) * (1f + 0.035f * (float) Math.sin(t / 90f)) * (t > DURATION - 600 ? 1f + 0.15f * (1f - fade) : 1f);
            int size = dp(140);
            canvas.save();
            canvas.scale(scale, scale, cx, cy);
            fire.setBounds((int) (cx - size / 2f), (int) (cy - size / 2f), (int) (cx + size / 2f), (int) (cy + size / 2f));
            fire.setAlpha((int) (255 * Math.min(1f, appear * 2) * fade));
            fire.draw(canvas);
            canvas.restore();

            // number and caption
            float textAppear = Math.max(0f, Math.min(1f, (t - 250) / 350f));
            numberPaint.setAlpha((int) (255 * textAppear * fade));
            captionPaint.setAlpha((int) (230 * textAppear * fade));
            float ty = cy + size / 2f + dp(64) + dp(20) * (1f - textAppear);
            canvas.drawText(number, cx, ty, numberPaint);
            canvas.drawText(caption, cx, ty + dp(34), captionPaint);
            invalidate();
        }

        /** Damped spring 0 -> 1 with a little overshoot. */
        private static float spring(float x) {
            if (x >= 1f) {
                return 1f;
            }
            return (float) (1 - Math.exp(-6 * x) * Math.cos(9 * x));
        }
    }
}
