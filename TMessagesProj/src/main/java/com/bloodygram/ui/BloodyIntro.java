package com.bloodygram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.content.ContextCompat;
import androidx.viewpager.widget.ViewPager;

import com.bloodygram.BloodyStrings;
import com.bloodygram.streaks.BloodyFire;

import org.telegram.messenger.R;
import org.telegram.ui.Components.LayoutHelper;

import static org.telegram.messenger.AndroidUtilities.dp;

/** Intro (login) screen: Bloodygram pages and an animated icon instead of Telegram's GL plane. */
public class BloodyIntro {

    private static final int[] TITLES = {0, R.string.BloodyIntro2Title, R.string.BloodyIntro3Title, R.string.BloodyIntro4Title, R.string.BloodyIntro5Title, R.string.BloodyIntro6Title};
    private static final int[] MESSAGES = {R.string.BloodyIntro1Text, R.string.BloodyIntro2Text, R.string.BloodyIntro3Text, R.string.BloodyIntro4Text, R.string.BloodyIntro5Text, R.string.BloodyIntro6Text};

    public static void patchTexts(CharSequence[] titles, String[] messages) {
        for (int i = 0; i < titles.length && i < TITLES.length; i++) {
            if (TITLES[i] != 0) {
                titles[i] = BloodyStrings.get(TITLES[i]);
            }
            messages[i] = BloodyStrings.get(MESSAGES[i]);
        }
    }

    /** "Bloodygram" as the first page title ("Bloody" in red). */
    public static CharSequence firstTitle() {
        SpannableStringBuilder title = new SpannableStringBuilder(BloodyStrings.APP_NAME);
        title.setSpan(new ForegroundColorSpan(0xFFE0243C), 0, "Bloody".length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return title;
    }

    /** Hides Telegram's GL animation and puts the Bloodygram icon view in its place. */
    public static void replaceLogo(FrameLayout frame, View glView, ViewPager pager, int widthDp, int heightDp) {
        glView.setVisibility(View.GONE);
        LogoView logo = new LogoView(frame.getContext());
        frame.addView(logo, LayoutHelper.createFrame(widthDp, heightDp, android.view.Gravity.CENTER));
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                logo.position = position + positionOffset;
                logo.invalidate();
            }
        });
    }

    /** Page icon with a pulsing red glow; icons cross-fade while swiping. */
    private static class LogoView extends View {
        private final Drawable[] icons = new Drawable[6];
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final long start = SystemClock.uptimeMillis();
        float position;

        LogoView(Context context) {
            super(context);
            icons[0] = ContextCompat.getDrawable(context, R.drawable.bloody_logo);
            icons[1] = tinted(context, R.drawable.msg_delete);
            icons[2] = BloodyFire.drawable(dp(96), BloodyFire.TIER_RED).getConstantState().newDrawable().mutate(); // the cached one is shared
            icons[3] = tinted(context, R.drawable.ghost);
            icons[4] = tinted(context, R.drawable.ic_lock_header);
            icons[5] = tinted(context, R.drawable.msg_theme);
        }

        private static Drawable tinted(Context context, int res) {
            Drawable d = ContextCompat.getDrawable(context, res).mutate();
            d.setColorFilter(new PorterDuffColorFilter(0xFFE0243C, PorterDuff.Mode.SRC_IN));
            return d;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float t = (SystemClock.uptimeMillis() - start) / 1000f;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float pulse = 1f + 0.06f * (float) Math.sin(t * 2.4f);
            float r = Math.min(getWidth(), getHeight()) * 0.47f * pulse; // stays inside the view, no square edge
            glow.setShader(new RadialGradient(cx, cy, r, 0x70E0243C, 0x00E0243C, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, glow);

            int page = Math.max(0, Math.min(icons.length - 1, (int) Math.floor(position)));
            float offset = position - page;
            drawIcon(canvas, page, 1f - offset, cx, cy, pulse);
            if (offset > 0 && page + 1 < icons.length) {
                drawIcon(canvas, page + 1, offset, cx, cy, pulse);
            }
            invalidate();
        }

        private void drawIcon(Canvas canvas, int index, float alpha, float cx, float cy, float pulse) {
            Drawable icon = icons[index];
            if (icon == null || alpha <= 0) {
                return;
            }
            int size = (int) ((index == 0 ? dp(150) : index == 2 ? dp(96) : dp(72)) * (0.85f + 0.15f * alpha) * (index == 0 ? pulse : 1f));
            icon.setBounds((int) (cx - size / 2f), (int) (cy - size / 2f), (int) (cx + size / 2f), (int) (cy + size / 2f));
            icon.setAlpha((int) (255 * alpha));
            icon.draw(canvas);
        }
    }
}
