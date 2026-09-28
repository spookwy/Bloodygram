package com.bloodygram.ui;

import android.view.animation.Interpolator;

import com.bloodygram.BloodyConfig;

import org.telegram.messenger.LiteMode;
import org.telegram.messenger.SharedConfig;

/** Springy motion: screen transitions and chat messages settle with a small overshoot, like iOS. */
public class BloodyMotion {

    /** Damped spring 0 -> 1 in a fixed time: ~6% overshoot, settled by the end. */
    public static final Interpolator SPRING = x -> x >= 1f ? 1f : (float) (1 - Math.exp(-7 * x) * Math.cos(8 * x));

    public static boolean enabled() {
        BloodyConfig.load();
        return BloodyConfig.springAnimations;
    }

    /** Progress of the screen open/close animation. */
    public static float transition(float progress, float fallback) {
        return enabled() ? SPRING.getInterpolation(progress) : fallback;
    }

    /** Interpolator for messages sliding in the chat. */
    public static Interpolator chatInterpolator(Interpolator fallback) {
        return enabled() ? SPRING : fallback;
    }

    /** Header / input panel blur (Telegram's own, off by default on average phones). */
    public static boolean isBlurOn() {
        return LiteMode.isEnabledSetting(LiteMode.FLAG_CHAT_BLUR);
    }

    public static boolean canBlur() {
        return SharedConfig.canBlurChat();
    }

    public static void setBlur(boolean on) {
        LiteMode.toggleFlag(LiteMode.FLAG_CHAT_BLUR, on);
    }

    /** Telegram's own "Liquid Glass" look (brighter, more transparent blur on header/panels), off by default. */
    public static boolean isLiquidGlassOn() {
        return LiteMode.isEnabledSetting(LiteMode.FLAG_LIQUID_GLASS);
    }

    /** Needs blur itself on to have any visible effect. */
    public static void setLiquidGlass(boolean on) {
        LiteMode.toggleFlag(LiteMode.FLAG_LIQUID_GLASS, on);
        if (on && !isBlurOn()) {
            setBlur(true);
        }
    }
}
