package com.bloodygram.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
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

    /** Embers rise and flicker, ash falls slowly, sparks shoot up. */
    private static class Particles {
        final int mode;
        final int count;
        final float[] x, y, vx, vy, size, phase, life, born;
        final int[] color;
        final android.graphics.PorterDuffColorFilter[] filters;
        final android.graphics.RectF rect = new android.graphics.RectF();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint trail = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bitmap glow;
        long lastTime;
        int w, h;

        Particles(int mode) {
            this.mode = mode;
            count = mode == BloodyConfig.PARTICLES_EMBERS ? 55 : mode == BloodyConfig.PARTICLES_ASH ? 70 : 28;
            x = new float[count];
            y = new float[count];
            vx = new float[count];
            vy = new float[count];
            size = new float[count];
            phase = new float[count];
            life = new float[count];
            born = new float[count];
            color = new int[count];
            filters = new android.graphics.PorterDuffColorFilter[count];
            trail.setStrokeCap(Paint.Cap.ROUND);
            if (mode == BloodyConfig.PARTICLES_EMBERS) {
                int s = dp(16);
                glow = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
                Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
                g.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f, new int[]{0xFFFFFFFF, 0x66FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
                new Canvas(glow).drawCircle(s / 2f, s / 2f, s / 2f, g);
            }
        }

        private void spawn(int i, float now, boolean anywhere) {
            float r = Utilities.fastRandom.nextFloat();
            born[i] = now;
            x[i] = Utilities.fastRandom.nextFloat() * w;
            phase[i] = Utilities.fastRandom.nextFloat() * 6.28f;
            if (mode == BloodyConfig.PARTICLES_EMBERS) {
                y[i] = anywhere ? Utilities.fastRandom.nextFloat() * h : h + dp(10);
                vx[i] = AndroidUtilities.dpf2(-0.01f + 0.02f * Utilities.fastRandom.nextFloat());
                vy[i] = -AndroidUtilities.dpf2(0.03f + 0.06f * r);
                size[i] = AndroidUtilities.dpf2(1.5f + 3f * Utilities.fastRandom.nextFloat());
                life[i] = 6000 + 6000 * Utilities.fastRandom.nextFloat();
                int[] c = {0xFFFF5A1F, 0xFFFF2E3F, 0xFFFFA23A, 0xFFE0243C};
                color[i] = c[Utilities.fastRandom.nextInt(c.length)];
                filters[i] = new android.graphics.PorterDuffColorFilter(color[i], android.graphics.PorterDuff.Mode.SRC_IN);
            } else if (mode == BloodyConfig.PARTICLES_ASH) {
                y[i] = anywhere ? Utilities.fastRandom.nextFloat() * h : -dp(10);
                vx[i] = AndroidUtilities.dpf2(-0.01f + 0.02f * Utilities.fastRandom.nextFloat());
                vy[i] = AndroidUtilities.dpf2(0.012f + 0.025f * r);
                size[i] = AndroidUtilities.dpf2(1.2f + 2.5f * Utilities.fastRandom.nextFloat());
                life[i] = 20000;
                int gray = 0x70 + Utilities.fastRandom.nextInt(0x50);
                color[i] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
            } else {
                y[i] = h + dp(10);
                x[i] = w * (0.1f + 0.8f * Utilities.fastRandom.nextFloat());
                double angle = Math.toRadians(-90 + (Utilities.fastRandom.nextFloat() - 0.5f) * 70);
                float speed = AndroidUtilities.dpf2(0.35f + 0.5f * r);
                vx[i] = (float) Math.cos(angle) * speed;
                vy[i] = (float) Math.sin(angle) * speed;
                size[i] = AndroidUtilities.dpf2(1.2f + 1.3f * Utilities.fastRandom.nextFloat());
                life[i] = 900 + 900 * Utilities.fastRandom.nextFloat();
                // sparks come in waves: most of them wait
                born[i] = now + (anywhere ? 0 : Utilities.fastRandom.nextFloat() * 4000);
                int[] c = {0xFFFFD27A, 0xFFFF8A2A, 0xFFFF3B4F};
                color[i] = c[Utilities.fastRandom.nextInt(c.length)];
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
                for (int i = 0; i < count; i++) {
                    spawn(i, now, mode != BloodyConfig.PARTICLES_SPARKS);
                }
                lastTime = nowMs;
            }
            float dt = Math.min(64, nowMs - lastTime);
            lastTime = nowMs;
            for (int i = 0; i < count; i++) {
                float age = now - born[i];
                if (age < 0) {
                    continue;
                }
                if (age > life[i] || y[i] < -dp(20) || y[i] > h + dp(20)) {
                    spawn(i, now, false);
                    continue;
                }
                float sway = (float) Math.sin(age / 700f + phase[i]) * AndroidUtilities.dpf2(0.012f);
                x[i] += (vx[i] + sway) * dt;
                y[i] += vy[i] * dt;
                if (mode == BloodyConfig.PARTICLES_SPARKS) {
                    vy[i] += 0.0006f * dt * AndroidUtilities.density; // gravity
                }
                float fadeIn = Math.min(1f, age / 600f);
                float fadeOut = Math.min(1f, (life[i] - age) / 800f);
                float a = Math.max(0f, Math.min(fadeIn, fadeOut));
                if (mode == BloodyConfig.PARTICLES_EMBERS) {
                    float flicker = 0.65f + 0.35f * (float) Math.sin(age / 120f + phase[i] * 3);
                    paint.setColorFilter(filters[i]);
                    paint.setAlpha((int) (230 * a * flicker));
                    float s = size[i] * 4;
                    rect.set(x[i] - s, y[i] - s, x[i] + s, y[i] + s);
                    canvas.drawBitmap(glow, null, rect, paint);
                } else if (mode == BloodyConfig.PARTICLES_ASH) {
                    paint.setColorFilter(null);
                    paint.setColor(color[i]);
                    paint.setAlpha((int) (150 * a));
                    canvas.save();
                    canvas.rotate(age / 20f + phase[i] * 57, x[i], y[i]);
                    canvas.drawRect(x[i] - size[i], y[i] - size[i] * 0.6f, x[i] + size[i], y[i] + size[i] * 0.6f, paint);
                    canvas.restore();
                } else {
                    trail.setColor(color[i]);
                    trail.setAlpha((int) (255 * a));
                    trail.setStrokeWidth(size[i]);
                    canvas.drawLine(x[i], y[i], x[i] - vx[i] * 40, y[i] - vy[i] * 40, trail);
                }
            }
            view.invalidate();
        }
    }

    // endregion
}
