package com.bloodygram.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
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

import java.util.ArrayList;
import java.util.HashMap;
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
     * Contour lines on black, like a topographic map. A value-noise field is sampled on a grid and its
     * iso-lines (marching squares) are stitched into continuous, midpoint-smoothed paths — no dots at cell
     * joins and no visible corners. The pattern is static (it does not drift), so it is computed once and cached.
     */
    private static class TopoMap {
        private static final int CELL_DP = 26;   // grid resolution: bigger = coarser, rounder lines
        private static final int BANDS = 6;       // number of stacked contour levels (fewer = sparser)
        private static final float FREQ = 0.0042f; // noise frequency: lower = broader, more spaced-out contours

        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint backdrop = new Paint();
        private float[][] field; // noise value per grid corner, [row][col]
        private int cols, rows;
        private float cell;
        private int w, h;
        private Bitmap frameBitmap;

        TopoMap() {
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(1.6f));
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setColor(0xFFB01A2C);
            backdrop.setColor(0xFF050303); // paint over the wallpaper underneath, like a map
        }

        void draw(View view, Canvas canvas, int scrollOffset) {
            if (frameBitmap == null || w != view.getWidth() || h != view.getHeight()) {
                w = view.getWidth();
                h = view.getHeight();
                if (w == 0 || h == 0) {
                    return;
                }
                render();
            }
            canvas.drawBitmap(frameBitmap, 0, 0, null);
        }

        private void render() {
            cell = dp(CELL_DP);
            cols = (int) (w / cell) + 3;
            rows = (int) (h / cell) + 3;
            field = new float[rows][cols];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    field[r][c] = noise(c * cell * FREQ, r * cell * FREQ);
                }
            }
            if (frameBitmap != null) {
                frameBitmap.recycle();
            }
            frameBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas fc = new Canvas(frameBitmap);
            fc.drawRect(0, 0, w, h, backdrop);
            Path path = new Path();
            for (int band = 0; band < BANDS; band++) {
                float threshold = (band + 0.5f) / BANDS;
                line.setAlpha(band == BANDS / 2 ? 120 : 80);
                buildContours(threshold, path);
                fc.drawPath(path, line);
            }
        }

        // Each grid edge has a unique key, so a crossing on a shared edge matches exactly between the two
        // cells and their segments stitch into one continuous line instead of two capped stubs (the "dots").
        private long hKey(int r, int c) {
            return ((long) r * cols + c) * 2L;
        }

        private long vKey(int r, int c) {
            return ((long) r * cols + c) * 2L + 1L;
        }

        private void buildContours(float threshold, Path out) {
            out.reset();
            HashMap<Long, float[]> pts = new HashMap<>();
            HashMap<Long, ArrayList<Long>> adj = new HashMap<>();
            for (int r = 0; r < rows - 1; r++) {
                for (int c = 0; c < cols - 1; c++) {
                    float tl = field[r][c], tr = field[r][c + 1], bl = field[r + 1][c], br = field[r + 1][c + 1];
                    int mask = (tl > threshold ? 8 : 0) | (tr > threshold ? 4 : 0) | (br > threshold ? 2 : 0) | (bl > threshold ? 1 : 0);
                    if (mask == 0 || mask == 15) {
                        continue;
                    }
                    switch (mask) {
                        case 1: case 14: link(pts, adj, left(r, c, threshold), bottom(r, c, threshold)); break;
                        case 2: case 13: link(pts, adj, bottom(r, c, threshold), right(r, c, threshold)); break;
                        case 3: case 12: link(pts, adj, left(r, c, threshold), right(r, c, threshold)); break;
                        case 4: case 11: link(pts, adj, top(r, c, threshold), right(r, c, threshold)); break;
                        case 6: case 9:  link(pts, adj, top(r, c, threshold), bottom(r, c, threshold)); break;
                        case 7: case 8:  link(pts, adj, left(r, c, threshold), top(r, c, threshold)); break;
                        case 5:
                            link(pts, adj, left(r, c, threshold), top(r, c, threshold));
                            link(pts, adj, bottom(r, c, threshold), right(r, c, threshold));
                            break;
                        case 10:
                            link(pts, adj, top(r, c, threshold), right(r, c, threshold));
                            link(pts, adj, left(r, c, threshold), bottom(r, c, threshold));
                            break;
                        default: break;
                    }
                }
            }
            trace(pts, adj, out);
        }

        // edge crossing points keyed by their shared-edge id
        private long[] top(int r, int c, float th) {
            return new long[]{hKey(r, c), Float.floatToRawIntBits(lerp(c * cell, (c + 1) * cell, invLerp(field[r][c], field[r][c + 1], th))), Float.floatToRawIntBits(r * cell)};
        }

        private long[] bottom(int r, int c, float th) {
            return new long[]{hKey(r + 1, c), Float.floatToRawIntBits(lerp(c * cell, (c + 1) * cell, invLerp(field[r + 1][c], field[r + 1][c + 1], th))), Float.floatToRawIntBits((r + 1) * cell)};
        }

        private long[] left(int r, int c, float th) {
            return new long[]{vKey(r, c), Float.floatToRawIntBits(c * cell), Float.floatToRawIntBits(lerp(r * cell, (r + 1) * cell, invLerp(field[r][c], field[r + 1][c], th)))};
        }

        private long[] right(int r, int c, float th) {
            return new long[]{vKey(r, c + 1), Float.floatToRawIntBits((c + 1) * cell), Float.floatToRawIntBits(lerp(r * cell, (r + 1) * cell, invLerp(field[r][c + 1], field[r + 1][c + 1], th)))};
        }

        private void link(HashMap<Long, float[]> pts, HashMap<Long, ArrayList<Long>> adj, long[] a, long[] b) {
            long ka = a[0], kb = b[0];
            pts.put(ka, new float[]{Float.intBitsToFloat((int) a[1]), Float.intBitsToFloat((int) a[2])});
            pts.put(kb, new float[]{Float.intBitsToFloat((int) b[1]), Float.intBitsToFloat((int) b[2])});
            adj.computeIfAbsent(ka, k -> new ArrayList<>()).add(kb);
            adj.computeIfAbsent(kb, k -> new ArrayList<>()).add(ka);
        }

        /** Walks the segment graph into polylines and appends each, midpoint-smoothed, to {@code out}. */
        private void trace(HashMap<Long, float[]> pts, HashMap<Long, ArrayList<Long>> adj, Path out) {
            ArrayList<float[]> poly = new ArrayList<>();
            while (!adj.isEmpty()) {
                Long start = null;
                for (HashMap.Entry<Long, ArrayList<Long>> e : adj.entrySet()) {
                    if (e.getValue().size() == 1) {
                        start = e.getKey();
                        break;
                    }
                }
                if (start == null) {
                    start = adj.keySet().iterator().next();
                }
                poly.clear();
                poly.add(pts.get(start));
                Long cur = start;
                while (true) {
                    ArrayList<Long> ns = adj.get(cur);
                    if (ns == null || ns.isEmpty()) {
                        adj.remove(cur);
                        break;
                    }
                    Long next = ns.get(0);
                    removeEdge(adj, cur, next);
                    poly.add(pts.get(next));
                    if (next.equals(start)) {
                        break;
                    }
                    cur = next;
                }
                smoothInto(out, poly);
            }
        }

        private void removeEdge(HashMap<Long, ArrayList<Long>> adj, Long a, Long b) {
            ArrayList<Long> la = adj.get(a);
            if (la != null) {
                la.remove(b);
                if (la.isEmpty()) {
                    adj.remove(a);
                }
            }
            ArrayList<Long> lb = adj.get(b);
            if (lb != null) {
                lb.remove(a);
                if (lb.isEmpty()) {
                    adj.remove(b);
                }
            }
        }

        /** Quadratic midpoint smoothing so the polyline reads as a smooth curve, not chained straight bits. */
        private void smoothInto(Path out, ArrayList<float[]> poly) {
            int n = poly.size();
            if (n < 2) {
                return;
            }
            out.moveTo(poly.get(0)[0], poly.get(0)[1]);
            if (n == 2) {
                out.lineTo(poly.get(1)[0], poly.get(1)[1]);
                return;
            }
            for (int i = 1; i < n - 1; i++) {
                float mx = (poly.get(i)[0] + poly.get(i + 1)[0]) / 2f;
                float my = (poly.get(i)[1] + poly.get(i + 1)[1]) / 2f;
                out.quadTo(poly.get(i)[0], poly.get(i)[1], mx, my);
            }
            out.lineTo(poly.get(n - 1)[0], poly.get(n - 1)[1]);
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
