package com.bloodygram.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;

/** "Bloody" in red + "gram" as a drawable, for places where Telegram shows its wordmark image. */
public class BloodyTitleDrawable extends Drawable {

    private final TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    private final StaticLayout layout;

    public BloodyTitleDrawable(CharSequence title, int textColor, int textSizePx) {
        paint.setTypeface(AndroidUtilities.bold());
        paint.setTextSize(textSizePx);
        paint.setColor(textColor);
        int width = (int) Math.ceil(Layout.getDesiredWidth(title, paint));
        layout = new StaticLayout(title, paint, width, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top + (getBounds().height() - layout.getHeight()) / 2f);
        layout.draw(canvas);
        canvas.restore();
    }

    @Override
    public int getIntrinsicWidth() {
        return layout.getWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return layout.getHeight();
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
