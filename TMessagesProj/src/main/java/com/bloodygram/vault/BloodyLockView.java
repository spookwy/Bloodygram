package com.bloodygram.vault;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.bloodygram.BloodyStrings;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;

import static org.telegram.messenger.AndroidUtilities.dp;

/** Full-screen Bloodygram PIN pad: enter the PIN, or create one (typed twice). */
public class BloodyLockView extends FrameLayout {

    public static final int MODE_ENTER = 0;
    public static final int MODE_CREATE = 1;

    private static final int PIN_LENGTH = 4;
    private static final int RED = 0xFFE0243C;

    private final int mode;
    private final TextView subtitle;
    private final View[] dots = new View[PIN_LENGTH];
    private final LinearLayout dotsRow;
    private final StringBuilder typed = new StringBuilder();
    private String firstPin;
    private Runnable onDone, onCancel;
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ImageView close;
    /** The chat puts its children below the header: the lock grows upwards by this much to hide the messages there. */
    private int coverTop;

    public BloodyLockView(Context context, int mode, CharSequence title) {
        super(context);
        this.mode = mode;
        setBackgroundColor(0xFF050303);
        setClickable(true);
        setWillNotDraw(false);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        ImageView icon = new ImageView(context);
        Drawable lock = ContextCompat.getDrawable(context, R.drawable.ic_lock_header).mutate();
        lock.setColorFilter(new PorterDuffColorFilter(RED, PorterDuff.Mode.SRC_IN));
        icon.setImageDrawable(lock);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        column.addView(icon, LayoutHelper.createLinear(44, 44, Gravity.CENTER_HORIZONTAL));

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 21);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setGravity(Gravity.CENTER);
        column.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 16, 24, 0));

        subtitle = new TextView(context);
        subtitle.setTextColor(0xFF8E8E93);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setText(BloodyStrings.get(mode == MODE_CREATE ? R.string.BloodyPinCreateHint : R.string.BloodyPinEnterHint));
        column.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 6, 24, 0));

        dotsRow = new LinearLayout(context);
        dotsRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < PIN_LENGTH; i++) {
            View dot = new View(context);
            dots[i] = dot;
            dotsRow.addView(dot, LayoutHelper.createLinear(14, 14, 10, 0, 10, 0));
        }
        column.addView(dotsRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 28, 0, 36));
        updateDots();

        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "bio", "0", "del"};
        for (int row = 0; row < 4; row++) {
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 3; col++) {
                line.addView(key(context, keys[row * 3 + col]), LayoutHelper.createLinear(76, 76, 12, 0, 12, 0));
            }
            column.addView(line, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, row == 0 ? 0 : 14, 0, 0));
        }

        close = new ImageView(context);
        close.setImageResource(R.drawable.ic_close_white);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, Theme.RIPPLE_MASK_CIRCLE_20DP));
        close.setOnClickListener(v -> {
            if (onCancel != null) {
                onCancel.run();
            }
        });
        addView(close, LayoutHelper.createFrame(48, 48, Gravity.TOP | Gravity.LEFT, 8, 8 + AndroidUtilities.statusBarHeight / AndroidUtilities.density, 0, 0));
    }

    public void setOnDone(Runnable onDone) {
        this.onDone = onDone;
    }

    public void setOnCancel(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    public void setCloseVisible(boolean visible) {
        close.setVisibility(visible ? VISIBLE : GONE);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec) + coverTop, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (top > 0 && coverTop != top) {
            coverTop = top;
            setTranslationY(-top);
            post(this::requestLayout);
        }
    }

    private View key(Context context, String key) {
        if ("bio".equals(key)) {
            ImageView view = new ImageView(context);
            if (mode == MODE_ENTER && biometricAvailable(context)) {
                view.setImageResource(R.drawable.fingerprint);
                view.setColorFilter(new PorterDuffColorFilter(RED, PorterDuff.Mode.SRC_IN));
                view.setScaleType(ImageView.ScaleType.CENTER);
                view.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, Theme.RIPPLE_MASK_CIRCLE_20DP));
                view.setOnClickListener(v -> startBiometric());
            }
            return view;
        }
        if ("del".equals(key)) {
            ImageView view = new ImageView(context);
            view.setImageResource(R.drawable.msg_clear_input);
            view.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            view.setScaleType(ImageView.ScaleType.CENTER);
            view.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, Theme.RIPPLE_MASK_CIRCLE_20DP));
            view.setOnClickListener(v -> {
                if (typed.length() > 0) {
                    typed.setLength(typed.length() - 1);
                    updateDots();
                }
            });
            return view;
        }
        TextView view = new TextView(context);
        view.setText(key);
        view.setTextColor(0xFFFFFFFF);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 30);
        view.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ring.setStroke(dp(1), 0x40E0243C);
        view.setBackground(new android.graphics.drawable.LayerDrawable(new Drawable[]{ring, Theme.createCircleSelectorDrawable(0x33E0243C, 0, 0)}));
        view.setOnClickListener(v -> onDigit(key));
        return view;
    }

    private void onDigit(String digit) {
        if (typed.length() >= PIN_LENGTH) {
            return;
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        typed.append(digit);
        updateDots();
        if (typed.length() == PIN_LENGTH) {
            AndroidUtilities.runOnUIThread(this::onPinTyped, 120);
        }
    }

    private void onPinTyped() {
        String pin = typed.toString();
        if (mode == MODE_ENTER) {
            if (BloodyVault.checkPin(pin)) {
                done();
            } else {
                fail(R.string.BloodyPinWrong);
            }
        } else if (firstPin == null) {
            firstPin = pin;
            typed.setLength(0);
            updateDots();
            subtitle.setText(BloodyStrings.get(R.string.BloodyPinRepeat));
        } else if (firstPin.equals(pin)) {
            BloodyVault.setPin(pin);
            done();
        } else {
            firstPin = null;
            fail(R.string.BloodyPinMismatch);
        }
    }

    private void fail(int text) {
        subtitle.setText(BloodyStrings.get(text));
        subtitle.setTextColor(RED);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        ObjectAnimator shake = ObjectAnimator.ofFloat(dotsRow, View.TRANSLATION_X, 0, dp(14), -dp(12), dp(10), -dp(8), dp(4), 0);
        shake.setDuration(360);
        shake.start();
        AndroidUtilities.runOnUIThread(() -> {
            typed.setLength(0);
            updateDots();
            subtitle.setTextColor(0xFF8E8E93);
        }, 360);
    }

    private void done() {
        typed.setLength(0);
        if (onDone != null) {
            onDone.run();
        }
    }

    private void updateDots() {
        for (int i = 0; i < PIN_LENGTH; i++) {
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            if (i < typed.length()) {
                d.setColor(RED);
            } else {
                d.setStroke(dp(1.5f), 0x99FFFFFF);
            }
            dots[i].setBackground(d);
        }
    }

    private static boolean biometricAvailable(Context context) {
        try {
            return Build.VERSION.SDK_INT >= 23 && BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    public void startBiometric() {
        if (mode != MODE_ENTER || LaunchActivity.instance == null || !biometricAvailable(getContext())) {
            return;
        }
        try {
            BiometricPrompt prompt = new BiometricPrompt(LaunchActivity.instance, ContextCompat.getMainExecutor(getContext()), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    done();
                }
            });
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(BloodyStrings.get(R.string.BloodyPinBiometric))
                    .setNegativeButtonText(BloodyStrings.get(R.string.BloodyPinUsePin))
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    .build();
            prompt.authenticate(info);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // dim red glow behind the icon
        float cx = getWidth() / 2f, cy = getHeight() * 0.22f, r = Math.max(getWidth(), dp(200)) * 0.7f;
        glowPaint.setShader(new RadialGradient(cx, cy, r, 0x40E0243C, 0x00E0243C, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r, glowPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        super.onTouchEvent(event);
        return true; // nothing under the lock is touchable
    }
}
