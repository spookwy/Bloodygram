package com.bloodygram.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.Layout;
import android.widget.EditText;

import com.bloodygram.BloodyConfig;

import org.telegram.ui.ActionBar.Theme;

import static org.telegram.messenger.AndroidUtilities.dp;

/**
 * Replaces the chat input's native (instantly-jumping) text cursor with a thicker one that floats to its
 * new position on a critically damped spring. Draw it after {@code super.onDraw()} in the owning EditText,
 * with the native cursor disabled via {@code setAllowDrawCursor(false)} while this is active.
 */
public class BloodyCaret {

    // Spring stiffness as natural frequency (rad/s). Critically damped, so no overshoot; ~300ms to settle.
    // Starts from zero velocity, unlike an exponential ease whose peak speed is on the very first frame.
    private static final float OMEGA = 14f;
    private static final float SUBSTEP_MS = 4f; // fixed integration step, keeps the spring stable on long frames
    private static final float WIDTH_DP = 2.6f;

    private final EditText editText;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Runnable frame = this::onFrame;

    private boolean initialized;
    private boolean animating;
    private float x, vx, top, vTop;
    private long lastFrame;
    private boolean framePosted;

    public static boolean enabled() {
        BloodyConfig.load();
        return BloodyConfig.typingAnimation;
    }

    public BloodyCaret(EditText editText) {
        this.editText = editText;
    }

    /** Call from the EditText's onDraw, right after super.onDraw(canvas), inside the same canvas transform. */
    public void draw(Canvas canvas) {
        if (!enabled() || !editText.isFocused() || !editText.isCursorVisible()) {
            initialized = false;
            stopFrames();
            return;
        }
        Layout layout = editText.getLayout();
        if (layout == null) {
            return;
        }
        int offset = editText.getSelectionStart();
        if (offset < 0 || offset > layout.getText().length() || editText.getSelectionStart() != editText.getSelectionEnd()) {
            initialized = false; // a real selection: let the native handles show instead
            stopFrames();
            return;
        }
        int line = layout.getLineForOffset(offset);
        // same math as EditTextBoldCursor.updateCursorPosition()
        float targetX = layout.getPrimaryHorizontal(offset);
        float targetTop = layout.getLineTop(line) + editText.getExtendedPaddingTop();
        float height = layout.getLineTop(line + 1) - layout.getLineTop(line);

        long now = SystemClock.uptimeMillis();
        if (!initialized) {
            x = targetX;
            top = targetTop;
            vx = vTop = 0;
            initialized = true;
            animating = false;
        } else {
            boolean moved = Math.abs(targetX - x) > 0.3f || Math.abs(targetTop - top) > 0.3f;
            if (!animating && moved) {
                // resuming from rest: the last draw may be seconds old, so treat this as a single normal frame,
                // otherwise the first step integrates a huge dt and the caret visibly jumps
                animating = true;
                lastFrame = now - 16;
            }
            if (animating) {
                float dt = Math.min(64, now - lastFrame);
                while (dt > 0) {
                    float h = Math.min(SUBSTEP_MS, dt) / 1000f;
                    vx += (OMEGA * OMEGA * (targetX - x) - 2f * OMEGA * vx) * h;
                    x += vx * h;
                    vTop += (OMEGA * OMEGA * (targetTop - top) - 2f * OMEGA * vTop) * h;
                    top += vTop * h;
                    dt -= SUBSTEP_MS;
                }
                if (Math.abs(targetX - x) < 0.3f && Math.abs(vx) < 8f && Math.abs(targetTop - top) < 0.3f && Math.abs(vTop) < 8f) {
                    x = targetX;
                    top = targetTop;
                    vx = vTop = 0;
                    animating = false;
                }
            }
        }
        lastFrame = now;

        paint.setColor(Theme.getColor(Theme.key_chat_messagePanelCursor));
        float width = dp(WIDTH_DP);
        rect.set(x - width / 2f, top, x + width / 2f, top + height);
        canvas.drawRoundRect(rect, width / 2f, width / 2f, paint);

        if (animating) {
            startFrames();
        } else {
            stopFrames();
        }
    }

    private void startFrames() {
        if (!framePosted) {
            framePosted = true;
            editText.postOnAnimation(frame);
        }
    }

    private void stopFrames() {
        framePosted = false;
    }

    private void onFrame() {
        framePosted = false;
        editText.invalidate();
    }
}
