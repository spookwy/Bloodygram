package com.bloodygram.ui;

import android.graphics.Typeface;
import android.text.TextPaint;

import com.bloodygram.BloodyConfig;
import com.bloodygram.BloodyStrings;

import org.telegram.messenger.R;

/** Message text font (system families, nothing is bundled). */
public class BloodyFonts {

    private static final String[] FAMILIES = {null, "sans-serif-light", "sans-serif-condensed", "serif", "monospace", "casual", "cursive"};
    private static final int[] NAMES = {R.string.BloodyFontDefault, R.string.BloodyFontLight, R.string.BloodyFontCondensed, R.string.BloodyFontSerif, R.string.BloodyFontMono, R.string.BloodyFontCasual, R.string.BloodyFontCursive};

    public static String[] names() {
        String[] names = new String[NAMES.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = BloodyStrings.get(NAMES[i]);
        }
        return names;
    }

    public static Typeface typeface(int index) {
        if (index <= 0 || index >= FAMILIES.length) {
            return null;
        }
        return Typeface.create(FAMILIES[index], Typeface.NORMAL);
    }

    /** Called when Telegram (re)creates the message text paint. */
    public static void apply(TextPaint paint) {
        BloodyConfig.load();
        Typeface typeface = typeface(BloodyConfig.messageFont);
        if (typeface != null || paint.getTypeface() != null) {
            paint.setTypeface(typeface);
        }
    }
}
