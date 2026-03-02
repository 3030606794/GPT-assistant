package tn.eluea.kgpt.util;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.Arrays;

/**
 * Token tag (capsule) rendering helper.
 *
 * <p>Implements range-based "sniffing" for Max Tokens, and appends "(自定义)" when
 * the value is not one of the standard preset levels.
 */
public final class TokenTagHelper {

    private TokenTagHelper() {}

    // Standard preset levels (must match the fixed list in the UI).
    private static final int[] STANDARD_LEVELS = new int[]{
            64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 49152, 65536, 81920, 100000, 131072
    };

    public static boolean isStandardLevel(int tokens) {
        if (tokens <= 0) return true; // Special case: "⚙️ 自由定义" should not be marked as custom.
        return Arrays.binarySearch(STANDARD_LEVELS, tokens) >= 0;
    }

    @NonNull
    public static TokenTagStyle getTokenTagStyle(int tokens) {
        final BaseSpec base = getBaseSpecByRange(tokens);
        final boolean standard = isStandardLevel(tokens);

        final String text;
        if (standard) {
            text = base.icon + " " + base.label;
        } else {
            text = base.icon + " " + base.label + " (自定义)";
        }

        return new TokenTagStyle(text, base.bgColor, base.textColor);
    }

    public static void applyToCapsule(@NonNull TextView tv, int tokens) {
        TokenTagStyle style = getTokenTagStyle(tokens);
        tv.setText(style.text);
        try { tv.setTextColor(style.textColor); } catch (Throwable ignored) {}

        // Keep XML padding, but ensure a pill background with small rounding.
        Context ctx = tv.getContext();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(style.bgColor);
        bg.setCornerRadius(dp(ctx, 999));
        tv.setBackground(bg);
    }

    private static BaseSpec getBaseSpecByRange(int tokens) {
        // Colors: background is ~10% opacity of the primary hue, to match existing chip styling.
        if (tokens <= 0) {
            return new BaseSpec("⚙️", "自由定义", Color.parseColor("#1A9E9E9E"), Color.parseColor("#616161"));
        }
        if (tokens <= 256) {
            return new BaseSpec("⚡", "超低耗", Color.parseColor("#1A4CAF50"), Color.parseColor("#2E7D32"));
        }
        if (tokens <= 2048) {
            return new BaseSpec("☕", "标准输出", Color.parseColor("#1A2196F3"), Color.parseColor("#1565C0"));
        }
        if (tokens <= 8192) {
            return new BaseSpec("📚", "深度长文", Color.parseColor("#1A9C27B0"), Color.parseColor("#6A1B9A"));
        }
        if (tokens <= 32768) {
            return new BaseSpec("🚀", "扩展级", Color.parseColor("#1AFF9800"), Color.parseColor("#EF6C00"));
        }
        if (tokens <= 65536) {
            return new BaseSpec("🔥", "算力怪兽", Color.parseColor("#1AE53935"), Color.parseColor("#C62828"));
        }
        if (tokens <= 100000) {
            // Dark red series
            return new BaseSpec("🌋", "o1-Pro级", Color.parseColor("#26B71C1C"), Color.parseColor("#B71C1C"));
        }
        // tokens > 100000
        return new BaseSpec("🌌", "宇宙边界", Color.parseColor("#268E0000"), Color.parseColor("#8E0000"));
    }

    private static int dp(@NonNull Context ctx, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }

    private static final class BaseSpec {
        final String icon;
        final String label;
        final int bgColor;
        final int textColor;

        BaseSpec(String icon, String label, int bgColor, int textColor) {
            this.icon = icon;
            this.label = label;
            this.bgColor = bgColor;
            this.textColor = textColor;
        }
    }

    public static final class TokenTagStyle {
        public final String text;
        public final int bgColor;
        public final int textColor;

        TokenTagStyle(String text, int bgColor, int textColor) {
            this.text = text;
            this.bgColor = bgColor;
            this.textColor = textColor;
        }
    }
}
