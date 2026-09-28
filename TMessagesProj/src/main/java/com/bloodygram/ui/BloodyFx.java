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
     * @param scrollOffset the wallpaper's current scroll-driven parallax translation (same signal
     *                     Telegram uses to shift the wallpaper on message-list scroll/swipe), used
     *                     by the topo map to shift its contours with the chat instead of sitting still
     * @return true if Bloodygram handled it (Telegram's own holiday snow is skipped then)
     */
    public static boolean drawChatParticles(View view, Canvas canvas, int scrollOffset) {
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
        if (mode == BloodyConfig.PARTICLES_TOPO) {
            if (!(system instanceof TopoMap)) {
                systems.put(view, system = new TopoMap());
            }
            ((TopoMap) system).draw(view, canvas, scrollOffset);
            return true;
        }
        if (!(system instanceof Particles) || ((Particles) system).mode != mode) {
            systems.put(view, system = new Particles(mode));
        }
        ((Particles) system).draw(view, canvas);
        return true;
    }

    /**
     * Slowly drifting red contour lines on black, like a topographic map. A coarse value-noise field is
     * sampled on a grid and its iso-lines (marching squares, one threshold band at a time) are stroked
     * with rounded joins/caps for softer curves; the sample window orbits gently around a fixed center
     * (instead of drifting off in one direction forever) and also shifts with the chat's own scroll, so
     * swiping the message list visibly moves the map too.
     */
    private static class TopoMap {
        private static final int CELL_DP = 22;   // grid resolution: bigger = cheaper, coarser lines
        private static final int BANDS = 9;       // number of stacked contour levels
        private static final float ORBIT_MS = 26000f; // one full lazy loop of the sample window
        private static final float ORBIT_RADIUS = 0.9f; // noise-space radius of the orbit (center never runs away)
        private static final float SCROLL_FOLLOW = 0.35f; // how much of the wallpaper's own scroll offset leaks into the field

        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint backdrop = new Paint();
        private float[][] field; // noise value per grid corner, [row][col]
        private int cols, rows;
        private float cell;
        private int w, h;
        private long start;
        private long lastFrame;
        private Bitmap frameBitmap;
        private Canvas frameCanvas;

        TopoMap() {
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(1.6f));
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setColor(0xFFB01A2C);
            backdrop.setColor(0xFF050303); // full-background mode: paint over the wallpaper underneath, like a map
        }

        private static final int FRAME_INTERVAL = 90; // ms; a slow crawl doesn't need 60fps recompute

        void draw(View view, Canvas canvas, int scrollOffset) {
            if (w != view.getWidth() || h != view.getHeight()) {
                w = view.getWidth();
                h = view.getHeight();
                if (w == 0 || h == 0) {
                    return;
                }
                cell = dp(CELL_DP);
                cols = (int) (w / cell) + 3;
                rows = (int) (h / cell) + 3;
                field = new float[rows][cols];
                start = SystemClock.uptimeMillis();
                lastFrame = 0;
                if (frameBitmap != null) {
                    frameBitmap.recycle();
                }
                frameBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                frameCanvas = new Canvas(frameBitmap);
            }
            long now = SystemClock.uptimeMillis();
            if (now - lastFrame >= FRAME_INTERVAL) {
                // recompute the noise field and redraw the cached bitmap only every FRAME_INTERVAL;
                // a slow crawl doesn't need 60fps marching-squares recompute
                lastFrame = now;
                float phase = ((now - start) % ORBIT_MS) / ORBIT_MS * (float) (Math.PI * 2);
                // orbit around a fixed center in noise-space, so the pattern never runs off-screen in one direction
                float ox = (float) Math.cos(phase) * ORBIT_RADIUS;
                float oy = (float) Math.sin(phase) * ORBIT_RADIUS;
                // fold the chat's scroll into the same noise-space offset, scaled down and by dp so a full-height
                // swipe shifts the map a noticeable but not disorienting amount
                float scrollNoise = -scrollOffset / dp(1) * SCROLL_FOLLOW * 0.006f;
                for (int r = 0; r < rows; r++) {
                    for (int c = 0; c < cols; c++) {
                        field[r][c] = noise(c * cell * 0.006f + ox, r * cell * 0.006f + oy + scrollNoise);
                    }
                }
                frameCanvas.drawRect(0, 0, w, h, backdrop);
                for (int band = 0; band < BANDS; band++) {
                    float threshold = (band + 0.5f) / BANDS;
                    line.setAlpha(band == BANDS / 2 ? 130 : 90);
                    for (int r = 0; r < rows - 1; r++) {
                        for (int c = 0; c < cols - 1; c++) {
                            marchCell(frameCanvas, r, c, threshold);
                        }
                    }
                }
            }
            canvas.drawBitmap(frameBitmap, 0, 0, null);
            view.postInvalidateOnAnimation();
        }

        /** One cell of marching squares: draws the segment(s) where the field crosses {@code threshold}. */
        private void marchCell(Canvas canvas, int r, int c, float threshold) {
            float x0 = c * cell, y0 = r * cell, x1 = x0 + cell, y1 = y0 + cell;
            float tl = field[r][c], tr = field[r][c + 1], bl = field[r + 1][c], br = field[r + 1][c + 1];
            int mask = (tl > threshold ? 8 : 0) | (tr > threshold ? 4 : 0) | (br > threshold ? 2 : 0) | (bl > threshold ? 1 : 0);
            if (mask == 0 || mask == 15) {
                return;
            }
            float top = lerp(x0, x1, invLerp(tl, tr, threshold));
            float bottom = lerp(x0, x1, invLerp(bl, br, threshold));
            float left = lerp(y0, y1, invLerp(tl, bl, threshold));
            float right = lerp(y0, y1, invLerp(tr, br, threshold));
            switch (mask) {
                case 1: case 14: canvas.drawLine(x0, left, bottom, y1, line); break;
                case 2: case 13: canvas.drawLine(bottom, y1, x1, right, line); break;
                case 3: case 12: canvas.drawLine(x0, left, x1, right, line); break;
                case 4: case 11: canvas.drawLine(top, y0, x1, right, line); break;
                case 6: case 9: canvas.drawLine(top, y0, bottom, y1, line); break;
                case 7: case 8: canvas.drawLine(x0, left, top, y0, line); break;
                case 5: // saddle: two separate segments
                    canvas.drawLine(x0, left, top, y0, line);
                    canvas.drawLine(bottom, y1, x1, right, line);
                    break;
                case 10:
                    canvas.drawLine(top, y0, x1, right, line);
                    canvas.drawLine(x0, left, bottom, y1, line);
                    break;
                default: break;
            }
        }

        private static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        private static float invLerp(float a, float b, float v) {
            if (Math.abs(b - a) < 1e-5f) {
                return 0.5f;
            }
            return Utilities.clamp((v - a) / (b - a), 1f, 0f);
        }

        /**
         * Smoothed value noise, blended from two octaves (a coarse one plus a lighter fine one) so the
         * contour bends are rounder and less blocky than a single hash-grid octave produces.
         */
        private static float noise(float x, float y) {
            return noiseOctave(x, y) * 0.75f + noiseOctave(x * 2.13f + 11.7f, y * 2.13f + 5.3f) * 0.25f;
        }

        private static float noiseOctave(float x, float y) {
            int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
            float fx = x - ix, fy = y - iy;
            fx = fx * fx * (3 - 2 * fx);
            fy = fy * fy * (3 - 2 * fy);
            float v00 = hash(ix, iy), v10 = hash(ix + 1, iy), v01 = hash(ix, iy + 1), v11 = hash(ix + 1, iy + 1);
            return lerp(lerp(v00, v10, fx), lerp(v01, v11, fx), fy);
        }

        private static float hash(int x, int y) {
            int h = x * 374761393 + y * 668265263;
            h = (h ^ (h >> 13)) * 1274126177;
            h = h ^ (h >> 16);
            return (h & 0xFFFF) / 65535f;
        }
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
