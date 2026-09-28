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
 * Replaces the chat input's native (instantly-jumping) text cursor with a thicker one that glides to
 * its new position instead of snapping. Draw it after {@code super.onDraw()} in the owning EditText,
 * with the native cursor disabled via {@code setAllowDrawCursor(false)} while this is active.
 */
public class BloodyCaret {

    private static final float SPEED = 0.022f; // fraction of the remaining distance closed per ms (exponential ease)
    private static final float MIN_SPEED_DP = 1.6f; // dp/ms floor so long jumps (line changes) still arrive promptly
    private static final float WIDTH_DP = 2.6f; // thicker than the stock 2dp cursor, per user request

    private final EditText editText;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Runnable frame = this::onFrame;

    private boolean initialized;
    private float currentX, currentTop, currentBottom;
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
            initialized = false; // a real selection (start != end): let the native handles show instead
            stopFrames();
            return;
        }
        int line = layout.getLineForOffset(offset);
        // Matches EditTextBoldCursor.updateCursorPosition()'s math exactly: the cursor sits directly at the
        // Layout's own horizontal coordinate (no left-padding offset — the Layout already accounts for it),
        // top/bottom from consecutive getLineTop() calls (not getLineBottom, which can differ with spacing),
        // plus the extended top padding.
        float targetX = layout.getPrimaryHorizontal(offset);
        float targetTop = layout.getLineTop(line) + editText.getExtendedPaddingTop();
        float targetBottom = layout.getLineTop(line + 1) + editText.getExtendedPaddingTop();

        if (!initialized) {
            currentX = targetX;
            currentTop = targetTop;
            currentBottom = targetBottom;
            initialized = true;
            lastFrame = SystemClock.uptimeMillis();
        } else {
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(48, now - lastFrame);
            lastFrame = now;
            currentX = approach(currentX, targetX, dt);
            // vertical jumps (new line) look better snapping than gliding, only the horizontal position glides
            currentTop = targetTop;
            currentBottom = targetBottom;
        }

        paint.setColor(Theme.getColor(Theme.key_chat_messagePanelCursor));
        float width = dp(WIDTH_DP);
        rect.set(currentX - width / 2f, currentTop, currentX + width / 2f, currentBottom);
        canvas.drawRoundRect(rect, width / 2f, width / 2f, paint);

        boolean settled = Math.abs(currentX - targetX) < 0.5f;
        if (!settled) {
            startFrames();
        } else {
            stopFrames();
        }
    }

    /** Exponential ease with a minimum speed floor, so it's snappy on line changes but glides on same-line moves. */
    private float approach(float current, float target, float dt) {
        float diff = target - current;
        float eased = diff * (1f - (float) Math.pow(1f - SPEED, dt));
        float minStep = dp(MIN_SPEED_DP) * dt * Math.signum(diff);
        if (Math.abs(minStep) > Math.abs(eased) && Math.signum(minStep) == Math.signum(diff)) {
            eased = minStep;
        }
        if (Math.abs(eased) >= Math.abs(diff)) {
            return target;
        }
        return current + eased;
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
