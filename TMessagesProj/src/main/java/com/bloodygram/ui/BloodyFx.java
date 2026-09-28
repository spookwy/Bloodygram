package com.bloodygram.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;

import com.bloodygram.BloodyConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.SnowflakesEffect;

import java.util.WeakHashMap;

import static org.telegram.messenger.AndroidUtilities.dp;

/** Visual effects: spark burst on send, particles over the chat background (snow / embers / ash / sparks). */
public class BloodyFx {

    // region send burst

    public static void onSend(View sendButton) {
        BloodyConfig.load();
        if (!BloodyConfig.sendEffects || sendButton == null || !LiteMode.isEnabled(LiteMode.FLAG_PARTICLES)) {
            return;
        }
        View root = sendButton.getRootView();
        if (root == null) {
            return;
        }
        int[] loc = new int[2];
        sendButton.getLocationInWindow(loc);
        Burst burst = new Burst(loc[0] + sendButton.getWidth() / 2f, loc[1] + sendButton.getHeight() / 2f);
        burst.host = root;
        burst.setBounds(0, 0, root.getWidth(), root.getHeight());
        root.getOverlay().add(burst);
        burst.invalidateSelf();
    }

    private static class Burst extends Drawable {
        private static final int COUNT = 36;
        private static final int LIFE = 650;

        View host;
        final long start = SystemClock.uptimeMillis();
        final float cx, cy;
        final float[] vx = new float[COUNT], vy = new float[COUNT], size = new float[COUNT];
        final int[] color = new int[COUNT];
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean removed;

        Burst(float cx, float cy) {
            this.cx = cx;
            this.cy = cy;
            int[] palette = {0xFFE0243C, 0xFFFF4A5F, 0xFFFF7A1A, 0xFFFFC2C8};
            for (int i = 0; i < COUNT; i++) {
                // mostly up and to the left, where the message flies
                double angle = Math.toRadians(-160 + 150 * Utilities.fastRandom.nextFloat());
                float speed = AndroidUtilities.dpf2(0.15f + 0.45f * Utilities.fastRandom.nextFloat());
                vx[i] = (float) Math.cos(angle) * speed;
                vy[i] = (float) Math.sin(angle) * speed;
                size[i] = AndroidUtilities.dpf2(1.2f + 2.2f * Utilities.fastRandom.nextFloat());
                color[i] = palette[Utilities.fastRandom.nextInt(palette.length)];
            }
            ring.setStyle(Paint.Style.STROKE);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            float t = SystemClock.uptimeMillis() - start;
            if (t > LIFE) {
                if (!removed && host != null) {
                    removed = true;
                    View h = host;
                    h.post(() -> h.getOverlay().remove(this));
                }
                return;
            }
            float p = t / LIFE;
            // shock ring
            float ringP = Math.min(1f, t / 380f);
            ring.setStrokeWidth(dp(3) * (1f - ringP) + 1);
            ring.setColor(0xFFE0243C);
            ring.setAlpha((int) (200 * (1f - ringP)));
            canvas.drawCircle(cx, cy, dp(14) + dp(34) * ringP, ring);
            // sparks
            for (int i = 0; i < COUNT; i++) {
                float x = cx + vx[i] * t;
                float y = cy + vy[i] * t + 0.0009f * t * t;
                paint.setColor(color[i]);
                paint.setAlpha((int) (255 * (1f - p)));
                canvas.drawCircle(x, y, size[i] * (1f - p * 0.6f), paint);
            }
            invalidateSelf();
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // endregion

    // region chat background particles

    private static final WeakHashMap<View, Object> systems = new WeakHashMap<>();

    /**
     * Draws the chosen particles over the chat background.
     * @return true if Bloodygram handled it (Telegram's own holiday snow is skipped then)
     */
    public static boolean drawChatParticles(View view, Canvas canvas) {
        if (view == null) {
            return false;
        }
        BloodyConfig.load();
        int mode = BloodyConfig.chatParticles;
        if (mode == BloodyConfig.PARTICLES_OFF || !LiteMode.isEnabled(LiteMode.FLAG_PARTICLES)) {
            return false;
        }
        Object system = systems.get(view);
        if (mode == BloodyConfig.PARTICLES_SNOW) {
            if (!(system instanceof SnowflakesEffect)) {
                SnowflakesEffect snow = new SnowflakesEffect(1);
                snow.setForcedColor(0xFFFFFFFF);
                systems.put(view, system = snow);
            }
            ((SnowflakesEffect) system).onDraw(view, canvas);
            return true;
        }
        if (!(system instanceof Particles) || ((Particles) system).mode != mode) {
            systems.put(view, system = new Particles(mode));
        }
        ((Particles) system).draw(view, canvas);
        return true;
    }

    /** Ash drifts down; sparks are glowing embers floating up from a warm glow at the bottom. */
    private static class Particles {
        static final int[] SPARK_COLORS = {0xFFFFE6A6, 0xFFFFC24A, 0xFFFF8A2A, 0xFFFF5A1F};
        final int mode;
        final int count;
        final float[] x, y, vx, vy, size, phase, life, born;
        final int[] color, ci;
        final RectF rect = new RectF();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint bottom = new Paint();
        long lastTime;
        int w, h;

        Particles(int mode) {
            this.mode = mode;
            count = 70;
            x = new float[count];
            y = new float[count];
            vx = new float[count];
            vy = new float[count];
            size = new float[count];
            phase = new float[count];
            life = new float[count];
            born = new float[count];
            color = new int[count];
            ci = new int[count];
            if (mode == BloodyConfig.PARTICLES_SPARKS) {
                bottom.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            }
        }

        private void spawn(int i, float now, boolean anywhere) {
            float r = Utilities.fastRandom.nextFloat();
            born[i] = now;
            x[i] = Utilities.fastRandom.nextFloat() * w;
            phase[i] = Utilities.fastRandom.nextFloat() * 6.28f;
            if (mode == BloodyConfig.PARTICLES_ASH) {
                y[i] = anywhere ? Utilities.fastRandom.nextFloat() * h : -dp(10);
                vx[i] = AndroidUtilities.dpf2(-0.01f + 0.02f * Utilities.fastRandom.nextFloat());
                vy[i] = AndroidUtilities.dpf2(0.012f + 0.025f * r);
                size[i] = AndroidUtilities.dpf2(1.2f + 2.5f * Utilities.fastRandom.nextFloat());
                life[i] = 20000;
                int gray = 0x70 + Utilities.fastRandom.nextInt(0x50);
                color[i] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
            } else { // sparks: embers floating up, denser near the bottom
                float bias = Utilities.fastRandom.nextFloat();
                y[i] = anywhere ? h - bias * bias * h : h + dp(8);
                vx[i] = AndroidUtilities.dpf2(-0.03f + 0.06f * Utilities.fastRandom.nextFloat());
                vy[i] = -AndroidUtilities.dpf2(0.045f + 0.09f * r);
                size[i] = AndroidUtilities.dpf2(1.1f + 2.6f * r);
                life[i] = 3200 + 4200 * Utilities.fastRandom.nextFloat();
                ci[i] = Utilities.fastRandom.nextInt(SPARK_COLORS.length);
            }
        }

        void draw(View view, Canvas canvas) {
            long nowMs = SystemClock.uptimeMillis();
            float now = nowMs;
            if (w != view.getWidth() || h != view.getHeight() || lastTime == 0) {
                w = view.getWidth();
                h = view.getHeight();
                if (w == 0 || h == 0) {
                    return;
                }
                if (mode == BloodyConfig.PARTICLES_SPARKS) {
                    bottom.setShader(new LinearGradient(0, h, 0, h - h * 0.32f,
                            new int[]{0xFFFF6A18, 0x22FF4A0A, 0x00000000}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                }
                for (int i = 0; i < count; i++) {
                    spawn(i, now, true);
                }
                lastTime = nowMs;
            }
            float dt = Math.min(64, nowMs - lastTime);
            lastTime = nowMs;

            if (mode == BloodyConfig.PARTICLES_SPARKS) {
                canvas.drawRect(0, h - h * 0.32f, w, h, bottom);
            }

            for (int i = 0; i < count; i++) {
                float age = now - born[i];
                if (age < 0) {
                    continue;
                }
                if (age > life[i] || y[i] < -dp(20) || y[i] > h + dp(20)) {
                    spawn(i, now, false);
                    continue;
                }
                float sway = (float) Math.sin(age / 600f + phase[i]) * AndroidUtilities.dpf2(0.02f);
                x[i] += (vx[i] + sway) * dt;
                y[i] += vy[i] * dt;
                float fadeIn = Math.min(1f, age / 500f);
                float fadeOut = Math.min(1f, (life[i] - age) / 800f);
                float a = Math.max(0f, Math.min(fadeIn, fadeOut));
                if (mode == BloodyConfig.PARTICLES_ASH) {
                    paint.setColorFilter(null);
                    paint.setColor(color[i]);
                    paint.setAlpha((int) (150 * a));
                    canvas.save();
                    canvas.rotate(age / 20f + phase[i] * 57, x[i], y[i]);
                    canvas.drawRect(x[i] - size[i], y[i] - size[i] * 0.6f, x[i] + size[i], y[i] + size[i] * 0.6f, paint);
                    canvas.restore();
                } else { // sparks — crisp glowing embers
                    float heightFade = Math.max(0.12f, Math.min(1f, y[i] / (h * 0.85f))); // dimmer higher up
                    float flicker = 0.6f + 0.4f * (float) Math.sin(age / 130f + phase[i] * 3);
                    paint.setColorFilter(null);
                    paint.setColor(SPARK_COLORS[ci[i]]);
                    paint.setAlpha((int) (255 * a * heightFade * flicker));
                    float r = size[i];
                    float stretch = 1f + Math.min(2.6f, -vy[i] * 22f); // faster embers become short streaks
                    if (stretch > 1.4f) {
                        rect.set(x[i] - r, y[i] - r * stretch, x[i] + r, y[i] + r);
                        canvas.drawRoundRect(rect, r, r, paint);
                    } else {
                        canvas.drawCircle(x[i], y[i], r, paint);
                    }
                }
            }
            view.invalidate();
        }
    }

    // endregion
}
