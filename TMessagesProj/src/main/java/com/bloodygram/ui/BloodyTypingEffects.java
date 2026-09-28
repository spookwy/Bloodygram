package com.bloodygram.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.style.CharacterStyle;
import android.text.style.UpdateAppearance;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.NonNull;

import com.bloodygram.BloodyConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;

import static org.telegram.messenger.AndroidUtilities.dp;

/**
 * Chat input effects: typed letters fade in and slide up into place,
 * erased letters crumble into dust that flies away (like Telegram's "Thanos" message deletion).
 */
public class BloodyTypingEffects implements TextWatcher {

    private static final int APPEAR_DURATION = 220;
    private static final int MAX_ANIMATED_INSERT = 3;  // typing; pasted text appears at once
    private static final int MAX_DUST_DELETE = 16;
    private static final int MAX_PARTICLES = 2500;

    public static void attach(EditText editText) {
        if (editText != null) {
            editText.addTextChangedListener(new BloodyTypingEffects(editText));
        }
    }

    private final EditText editText;
    private final ArrayList<AppearSpan> appearing = new ArrayList<>();
    private final Runnable frame = this::onFrame;
    private boolean framePosted;
    private int savedLayerType = -1;

    private String removedText;
    private Bitmap dust;
    private float dustX, dustY;
    private int appearFrom = -1, appearTo;

    private BloodyTypingEffects(EditText editText) {
        this.editText = editText;
    }

    // region TextWatcher

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        removedText = s.subSequence(start, start + count).toString();
        dust = null;
        int removed = count - after;
        boolean clearsAll = after == 0 && count == s.length() && count > 1; // sent message / draft cleared
        if (removed > 0 && removed <= MAX_DUST_DELETE && !clearsAll && dustEnabled()) {
            captureDust(start + after, start + count);
        }
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        appearFrom = -1;
        String now = s.subSequence(start, start + count).toString();
        int prefix = commonPrefix(removedText, now);
        if (count > before) {
            boolean setsAll = before == 0 && start == 0 && count == s.length() && count > 1; // draft / edit
            if (prefix == before && count - prefix <= MAX_ANIMATED_INSERT && !setsAll && typingEnabled()) {
                appearFrom = start + prefix;
                appearTo = start + count;
            }
            dust = null;
        } else if (count < before && dust != null && prefix == count) {
            launchDust(dust, dustX, dustY);
            dust = null;
        } else {
            dust = null;
        }
    }

    @Override
    public void afterTextChanged(Editable s) {
        if (appearFrom < 0 || appearTo > s.length()) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        for (int i = appearFrom; i < appearTo; i++) {
            AppearSpan span = new AppearSpan(now);
            s.setSpan(span, i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            appearing.add(span);
        }
        appearFrom = -1;
        startFrames();
    }

    private static int commonPrefix(String a, String b) {
        if (a == null || b == null) {
            return 0;
        }
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    private static boolean typingEnabled() {
        BloodyConfig.load();
        return BloodyConfig.typingAnimation;
    }

    private static boolean dustEnabled() {
        BloodyConfig.load();
        return BloodyConfig.eraseDust && LiteMode.isEnabled(LiteMode.FLAG_PARTICLES);
    }

    // endregion

    // region appearing letters

    /** Letter fades in and slides up; drawn with the paint only, so the layout is not rebuilt. */
    private static class AppearSpan extends CharacterStyle implements UpdateAppearance {
        final long start;

        AppearSpan(long start) {
            this.start = start;
        }

        float progress() {
            return Utilities.clamp((SystemClock.uptimeMillis() - start) / (float) APPEAR_DURATION, 1f, 0f);
        }

        @Override
        public void updateDrawState(TextPaint tp) {
            float t = progress();
            float eased = 1f - (1f - t) * (1f - t) * (1f - t);
            tp.setAlpha((int) (tp.getAlpha() * eased));
            tp.baselineShift += (int) (dp(7) * (1f - eased));
        }
    }

    private void startFrames() {
        if (appearing.isEmpty() || framePosted) {
            return;
        }
        if (savedLayerType < 0) {
            // TextView caches text blocks in display lists: a software layer makes every frame redraw the spans
            savedLayerType = editText.getLayerType();
            editText.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
        framePosted = true;
        editText.postOnAnimation(frame);
    }

    private void onFrame() {
        framePosted = false;
        Editable text = editText.getText();
        for (int i = appearing.size() - 1; i >= 0; i--) {
            AppearSpan span = appearing.get(i);
            if (span.progress() >= 1f || text == null || text.getSpanStart(span) < 0) {
                appearing.remove(i);
                if (text != null) {
                    text.removeSpan(span);
                }
            }
        }
        editText.invalidate();
        if (appearing.isEmpty()) {
            if (savedLayerType >= 0) {
                editText.setLayerType(savedLayerType, null);
                savedLayerType = -1;
            }
        } else {
            framePosted = true;
            editText.postOnAnimation(frame);
        }
    }

    // endregion

    // region dust

    /** Picture of the letters that are about to be erased and where they are on the screen. */
    private void captureDust(int from, int to) {
        try {
            Layout layout = editText.getLayout();
            if (layout == null || editText.getWidth() == 0 || to > layout.getText().length()) {
                return;
            }
            int line = layout.getLineForOffset(from);
            float x1 = layout.getPrimaryHorizontal(from);
            float x2 = layout.getLineForOffset(to) == line ? layout.getPrimaryHorizontal(to) : layout.getLineRight(line);
            if (x2 < x1) {
                float t = x1;
                x1 = x2;
                x2 = t;
            }
            int top = layout.getLineTop(line);
            int bottom = layout.getLineBottom(line);
            int w = (int) Math.ceil(x2 - x1) + 2;
            int h = bottom - top;
            if (w <= 2 || h <= 0 || w > 4000) {
                return;
            }
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.translate(-x1, -top);
            canvas.clipRect(x1, top, x2 + 1, bottom);
            layout.getPaint().setColor(editText.getCurrentTextColor());
            layout.draw(canvas);

            int[] loc = new int[2];
            editText.getLocationInWindow(loc);
            dustX = loc[0] + editText.getCompoundPaddingLeft() - editText.getScrollX() + x1;
            dustY = loc[1] + editText.getExtendedPaddingTop() + top;
            dust = bitmap;
        } catch (Exception e) {
            FileLog.e(e);
            dust = null;
        }
    }

    private void launchDust(Bitmap bitmap, float x, float y) {
        View root = editText.getRootView();
        if (root == null) {
            return;
        }
        Dust drawable = Dust.create(bitmap, x, y);
        bitmap.recycle();
        if (drawable == null) {
            return;
        }
        drawable.host = root;
        drawable.setBounds(0, 0, root.getWidth(), root.getHeight());
        root.getOverlay().add(drawable);
        drawable.invalidateSelf();
    }

    private static class Dust extends android.graphics.drawable.Drawable {

        private static final int LIFE_MIN = 500;
        private static final int LIFE_MAX = 900;
        private static final int SWEEP = 180; // left letters go first

        View host;
        final Paint paint = new Paint();
        final long start = SystemClock.uptimeMillis();
        int count;
        float size;
        float[] x, y, vx, vy, delay, life;
        int[] color;
        boolean removed;

        static Dust create(Bitmap bitmap, float left, float top) {
            int w = bitmap.getWidth(), h = bitmap.getHeight();
            int step = Math.max(2, dp(1.3f));
            while ((w / step) * (h / step) > MAX_PARTICLES * 3) {
                step++;
            }
            int[] pixels = new int[w * h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            ArrayList<int[]> found = new ArrayList<>();
            for (int py = 0; py < h; py += step) {
                for (int px = 0; px < w; px += step) {
                    int c = pixels[py * w + px];
                    if ((c >>> 24) > 60) {
                        found.add(new int[]{px, py, c});
                    }
                }
            }
            if (found.isEmpty()) {
                return null;
            }
            while (found.size() > MAX_PARTICLES) {
                found.remove(Utilities.fastRandom.nextInt(found.size()));
            }
            Dust d = new Dust();
            int n = d.count = found.size();
            d.size = step;
            d.x = new float[n];
            d.y = new float[n];
            d.vx = new float[n];
            d.vy = new float[n];
            d.delay = new float[n];
            d.life = new float[n];
            d.color = new int[n];
            for (int i = 0; i < n; i++) {
                int[] p = found.get(i);
                d.x[i] = left + p[0];
                d.y[i] = top + p[1];
                d.color[i] = p[2];
                d.vx[i] = AndroidUtilities.dpf2(0.02f + 0.10f * Utilities.fastRandom.nextFloat());
                d.vy[i] = -AndroidUtilities.dpf2(0.03f + 0.12f * Utilities.fastRandom.nextFloat());
                d.delay[i] = SWEEP * p[0] / (float) Math.max(1, w) + 60 * Utilities.fastRandom.nextFloat();
                d.life[i] = LIFE_MIN + (LIFE_MAX - LIFE_MIN) * Utilities.fastRandom.nextFloat();
            }
            return d;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            float t = SystemClock.uptimeMillis() - start;
            boolean alive = false;
            for (int i = 0; i < count; i++) {
                float lt = t - delay[i];
                float px = x[i], py = y[i], a = 1f;
                if (lt > 0) {
                    float p = lt / life[i];
                    if (p >= 1f) {
                        continue;
                    }
                    px += vx[i] * lt + dp(10) * p * p;
                    py += vy[i] * lt;
                    a = 1f - p * p;
                }
                alive = true;
                int c = color[i];
                paint.setColor(c);
                paint.setAlpha((int) ((c >>> 24) * a));
                canvas.drawRect(px, py, px + size, py + size, paint);
            }
            if (alive) {
                invalidateSelf();
            } else if (!removed && host != null) {
                removed = true;
                View h = host;
                h.post(() -> h.getOverlay().remove(this));
            }
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
}
