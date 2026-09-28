package com.bloodygram.streaks;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ImageSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;
import android.util.SparseArray;

import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vector streak fire (paths from fire-svgrepo-com.svg, same look as the "Огонёк" plugin)
 * and the " 🔥N" suffix: fire image + smaller light number.
 */
public class BloodyFire {

    public static final int TIER_GRAY = 0;
    public static final int TIER_ORANGE = 1;
    public static final int TIER_RED = 2;
    public static final int TIER_PURPLE = 3;
    public static final int TIER_BLUE = 4;
    public static final int TIER_GREEN = 5;

    private static final float FIRE_SCALE = 1.05f;
    private static final float NUMBER_SCALE = 0.8f;
    private static final String NUMBER_FONT = "sans-serif-light";
    private static final String FIRE = "🔥";

    // viewBox -33 0 255 255, expanded by 3 units so the flame tip at y=-2.6 is not clipped
    private static final float VB_X = -34.5f, VB_Y = -3f, VB_SIZE = 258f;

    private static final String FLAME_OUTER =
            "M187.899,164.809 C185.803,214.868 144.574,254.812 94.000,254.812 " +
            "C42.085,254.812 -0.000,211.312 -0.000,160.812 C-0.000,154.062 -0.121,140.572 10.000,117.812 " +
            "C16.057,104.191 19.856,95.634 22.000,87.812 C23.178,83.513 25.469,76.683 32.000,87.812 " +
            "C35.851,94.374 36.000,103.812 36.000,103.812 C36.000,103.812 50.328,92.817 60.000,71.812 " +
            "C74.179,41.019 62.866,22.612 59.000,9.812 C57.662,5.384 56.822,-2.574 66.000,0.812 " +
            "C75.352,4.263 100.076,21.570 113.000,39.812 C131.445,65.847 138.000,90.812 138.000,90.812 " +
            "C138.000,90.812 143.906,83.482 146.000,75.812 C148.365,67.151 148.400,58.573 155.999,67.813 " +
            "C163.226,76.600 173.959,93.113 180.000,108.812 C190.969,137.321 187.899,164.809 187.899,164.809 Z";
    private static final String FLAME_MIDDLE =
            "M94.000,254.812 C58.101,254.812 29.000,225.711 29.000,189.812 " +
            "C29.000,168.151 37.729,155.000 55.896,137.166 C67.528,125.747 78.415,111.722 83.042,102.172 " +
            "C83.953,100.292 86.026,90.495 94.019,101.966 C98.212,107.982 104.785,118.681 109.000,127.812 " +
            "C116.266,143.555 118.000,158.812 118.000,158.812 C118.000,158.812 125.121,154.616 130.000,143.812 " +
            "C131.573,140.330 134.753,127.148 143.643,140.328 C150.166,150.000 159.127,167.390 159.000,189.812 " +
            "C159.000,225.711 129.898,254.812 94.000,254.812 Z";
    private static final String FLAME_INNER =
            "M95.000,183.812 C104.250,183.812 104.250,200.941 116.000,223.812 " +
            "C123.824,239.041 112.121,254.812 95.000,254.812 C77.879,254.812 69.000,240.933 69.000,223.812 " +
            "C69.000,206.692 85.750,183.812 95.000,183.812 Z";

    // bottom of outer gradient, top of outer gradient, middle flame, inner tongue
    private static final int[][] TIER_COLORS = {
            {0xFF5F5F5F, 0xFF8E8E8E, 0xFF9E9E9E, 0xFFCFCFCF}, // gray: 0 or about to go out
            {0xFFFF4C0D, 0xFFFC9502, 0xFFFC9502, 0xFFFCE202}, // orange: original SVG
            {0xFFB3000C, 0xFFE8262A, 0xFFF0463C, 0xFFFFB3A1}, // red: 100+
            {0xFF4A10A8, 0xFF8E44FF, 0xFFA466FF, 0xFFE2C9FF}, // purple: 200+
            {0xFF0B3C91, 0xFF1E88E5, 0xFF3FA2F5, 0xFFBFE3FF}, // blue: 300+
            {0xFF126B2B, 0xFF2EB84F, 0xFF46CC66, 0xFFCBF7D3}, // green: 500+
    };

    private static final SparseArray<BitmapDrawable> cache = new SparseArray<>();
    private static final Pattern TOKEN = Pattern.compile("[MLCZmlcz]|-?\\d*\\.?\\d+");

    public static int tier(int streak, boolean atRisk) {
        if (streak <= 0 || atRisk) {
            return TIER_GRAY;
        } else if (streak >= 500) {
            return TIER_GREEN;
        } else if (streak >= 300) {
            return TIER_BLUE;
        } else if (streak >= 200) {
            return TIER_PURPLE;
        } else if (streak >= 100) {
            return TIER_RED;
        }
        return TIER_ORANGE;
    }

    /** Chat name color for the tier (middle of the flame), 0 = don't color (gray). */
    public static int nameColor(int tier) {
        return tier <= TIER_GRAY || tier >= TIER_COLORS.length ? 0 : TIER_COLORS[tier][2];
    }

    public static class Suffix {
        public final CharSequence text;
        public final int width;

        Suffix(CharSequence text, int width) {
            this.text = text;
            this.width = width;
        }
    }

    /** " 🔥N" with the vector fire sized for {@code paint}. */
    public static Suffix suffix(int streak, int tier, TextPaint paint) {
        int size = Math.max(8, (int) (paint.getTextSize() * FIRE_SCALE));
        String number = String.valueOf(streak);

        SpannableStringBuilder text = new SpannableStringBuilder(" ").append(FIRE).append(number);
        int fireStart = 1, fireEnd = fireStart + FIRE.length();
        text.setSpan(fireSpan(size, tier), fireStart, fireEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new RelativeSizeSpan(NUMBER_SCALE), fireEnd, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new TypefaceSpan(NUMBER_FONT), fireEnd, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        TextPaint numberPaint = new TextPaint(paint);
        numberPaint.setTextSize(paint.getTextSize() * NUMBER_SCALE);
        numberPaint.setTypeface(Typeface.create(NUMBER_FONT, Typeface.NORMAL));
        int width = (int) Math.ceil(paint.measureText(" ") + size + numberPaint.measureText(number));
        return new Suffix(text, width);
    }

    private static ImageSpan fireSpan(int size, int tier) {
        BitmapDrawable drawable = drawable(size, tier);
        if (Build.VERSION.SDK_INT >= 29) {
            return new ImageSpan(drawable, ImageSpan.ALIGN_CENTER);
        }
        return new ImageSpan(drawable, ImageSpan.ALIGN_BASELINE);
    }

    public static BitmapDrawable drawable(int size, int tier) {
        int key = size * 8 + tier;
        BitmapDrawable drawable = cache.get(key);
        if (drawable != null) {
            return drawable;
        }
        int[] colors = TIER_COLORS[tier];
        float scale = size / VB_SIZE;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        // gradient like in the SVG: y=255 (bottom) -> y=0.188 (top)
        paint.setShader(new LinearGradient(0, (255f - VB_Y) * scale, 0, (0.188f - VB_Y) * scale, colors[0], colors[1], Shader.TileMode.CLAMP));
        canvas.drawPath(parsePath(FLAME_OUTER, scale), paint);
        paint.setShader(null);
        paint.setColor(colors[2]);
        canvas.drawPath(parsePath(FLAME_MIDDLE, scale), paint);
        paint.setColor(colors[3]);
        canvas.drawPath(parsePath(FLAME_INNER, scale), paint);

        drawable = new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), bitmap);
        drawable.setBounds(0, 0, size, size);
        cache.put(key, drawable);
        return drawable;
    }

    /** Minimal SVG path parser: absolute M, L, C, Z. */
    private static Path parsePath(String data, float scale) {
        ArrayList<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(data);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        Path path = new Path();
        char cmd = 'M';
        float[] n = new float[6];
        int i = 0;
        while (i < tokens.size()) {
            String t = tokens.get(i);
            if (Character.isLetter(t.charAt(0))) {
                cmd = Character.toUpperCase(t.charAt(0));
                i++;
                if (cmd == 'Z') {
                    path.close();
                }
                continue;
            }
            int need = cmd == 'C' ? 6 : 2;
            for (int k = 0; k < need; k++) {
                float v = Float.parseFloat(tokens.get(i++));
                n[k] = (k % 2 == 0 ? v - VB_X : v - VB_Y) * scale;
            }
            if (cmd == 'M') {
                path.moveTo(n[0], n[1]);
                cmd = 'L'; // pairs after M are implicit L
            } else if (cmd == 'L') {
                path.lineTo(n[0], n[1]);
            } else if (cmd == 'C') {
                path.cubicTo(n[0], n[1], n[2], n[3], n[4], n[5]);
            }
        }
        return path;
    }
}
