package tn.eluea.kgpt.util;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Global, reusable tag rendering engine for model names.
 *
 * <p>Rules are keyword-based and allow multiple matches. Chips are rendered as iOS-like capsules.
 */
public final class ModelTagHelper {

    private ModelTagHelper() {
    }

    public static void bindTags(@NonNull Context context,
                                @Nullable String modelName,
                                @NonNull LinearLayout container) {
        bindTags(context, modelName, container, false);
    }

    public static void bindTags(@NonNull Context context,
                                @Nullable String modelName,
                                @NonNull LinearLayout container,
                                boolean isCustom) {
        if (container == null) return;
        container.removeAllViews();

        final String raw = modelName == null ? "" : modelName;
        final String lower = raw.toLowerCase(Locale.ROOT);

        final ArrayList<TagSpec> tags = new ArrayList<>();

        // 💸 免费与特权：强制在所有标签最前面显示
        if (lower.contains(":free") || lower.contains("-free")) {
            tags.add(new TagSpec("💸 免费可用", Color.parseColor("#1A4CAF50"), Color.parseColor("#4CAF50")));
        }

        // 📚 上下文容量
        if (lower.contains("128k")) {
            tags.add(new TagSpec("📚 128K 超长", Color.parseColor("#1A9C27B0"), Color.parseColor("#9C27B0")));
        }
        if (lower.contains("200k")) {
            tags.add(new TagSpec("📚 200K 超长", Color.parseColor("#1A9C27B0"), Color.parseColor("#9C27B0")));
        }
        if (lower.contains("1m")) {
            tags.add(new TagSpec("🌌 1M 巨量", Color.parseColor("#1AFFB300"), Color.parseColor("#FFB300")));
        }
        if (lower.contains("2m")) {
            tags.add(new TagSpec("🌌 2M 巨量", Color.parseColor("#1AFFB300"), Color.parseColor("#FFB300")));
        }

        // 🧠 深度思考
        if (containsAny(lower, "thinking", "reasoning", "r1") || startsWithAnyModelPrefix(lower, "o1", "o3", "o4")) {
            tags.add(new TagSpec("🧠 深度思考", Color.parseColor("#1A4CAF50"), Color.parseColor("#4CAF50")));
        }

        // 💻 代码专家
        if (containsAny(lower, "coder", "codex") || containsCodeToken(lower)) {
            tags.add(new TagSpec("💻 代码生成", Color.parseColor("#1A3F51B5"), Color.parseColor("#3F51B5")));
        }

        // 🎬 视频生成
        if (containsAny(lower, "sora", "video")) {
            tags.add(new TagSpec("🎬 视频引擎", Color.parseColor("#1AE91E63"), Color.parseColor("#E91E63")));
        }

        // ✨ 最新/推荐
        if (containsAny(lower, "latest") || lower.contains("chat-latest") || lower.contains("-chat-") || lower.endsWith("-chat")) {
            tags.add(new TagSpec("✨ 最新对话", Color.parseColor("#1A00BCD4"), Color.parseColor("#0097A6")));
        }

        // 👁️ 多模态
        if (containsAny(lower, "vision", "image")) {
            tags.add(new TagSpec("👁️ 多模态", Color.parseColor("#1A9C27B0"), Color.parseColor("#9C27B0")));
        }

        // 🎧 语音/转写
        if (lower.contains("tts")) {
            tags.add(new TagSpec("🔊 语音生成", Color.parseColor("#1A03A9F4"), Color.parseColor("#03A9F4")));
        }
        if (containsAny(lower, "whisper", "transcribe")) {
            tags.add(new TagSpec("🎧 语音转写", Color.parseColor("#1A03A9F4"), Color.parseColor("#03A9F4")));
        }

        // 📊 检索/重排
        if (lower.contains("embedding")) {
            tags.add(new TagSpec("📊 向量", Color.parseColor("#1A607D8B"), Color.parseColor("#607D8B")));
        }
        if (lower.contains("reranker")) {
            tags.add(new TagSpec("🔍 重排", Color.parseColor("#1A607D8B"), Color.parseColor("#607D8B")));
        }

        // ⚡ 极速
        if (containsAny(lower, "mini", "lite", "haiku", "fast", "flash")) {
            tags.add(new TagSpec("⚡ 极速", Color.parseColor("#1AFF9800"), Color.parseColor("#F57C00")));
        }

        // 👑 旗舰
        if (containsAny(lower, "pro", "opus", "max")) {
            tags.add(new TagSpec("👑 旗舰", Color.parseColor("#1AE91E63"), Color.parseColor("#E91E63")));
        }

        // 🌐 全能基础底座 (Fallback)
        if (tags.isEmpty() && looksLikeBaseFoundationModel(lower)) {
            tags.add(new TagSpec("🌐 全能底座", Color.parseColor("#1A607D8B"), Color.parseColor("#607D8B")));
        }

        // 🧩 自定义
        if (isCustom) {
            tags.add(new TagSpec("🧩 自定义", Color.parseColor("#1A9E9E9E"), Color.parseColor("#9E9E9E")));
        }

        if (tags.isEmpty()) {
            container.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);

        final Context ctx = container.getContext() != null ? container.getContext() : context;
        for (TagSpec t : tags) {
            container.addView(createChipView(ctx, t));
        }
    }

    private static boolean containsAny(@NonNull String text, @NonNull String... keys) {
        for (String k : keys) {
            if (!TextUtils.isEmpty(k) && text.contains(k)) return true;
        }
        return false;
    }

    /**
     * Prefix matching on the last token (after vendor prefix like "openai/").
     */
    private static boolean startsWithAnyModelPrefix(@NonNull String lower, @NonNull String... prefixes) {
        String last = lower;
        int slash = last.lastIndexOf('/');
        if (slash >= 0 && slash < last.length() - 1) last = last.substring(slash + 1);
        for (String p : prefixes) {
            if (TextUtils.isEmpty(p)) continue;
            if (last.equals(p) || last.startsWith(p)) return true;
        }
        return false;
    }

    private static boolean containsCodeToken(@NonNull String lower) {
        // Avoid a common false positive: "decode".
        if (lower.contains("decode")) {
            return lower.contains("-code") || lower.contains("code-") || lower.endsWith("code") || lower.startsWith("code") || lower.contains("/code");
        }
        return lower.contains("code");
    }

    private static boolean looksLikeBaseFoundationModel(@NonNull String lower) {
        // Conservative whitelist for "foundation" model families.
        return lower.contains("gpt-4o")
                || lower.contains("gpt-5")
                || lower.contains("claude-3.5")
                || lower.contains("claude-3")
                || lower.contains("gemini")
                || lower.contains("llama")
                || lower.contains("mistral")
                || lower.contains("qwen")
                || lower.contains("deepseek")
                || lower.contains("command");
    }

    private static TextView createChipView(@NonNull Context ctx, @NonNull TagSpec t) {
        TextView tv = new TextView(ctx);
        tv.setText(t.text);
        tv.setTextColor(t.textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        try {
            tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        } catch (Throwable ignored) {
        }

        int ph = dp(ctx, 6);
        int pv = dp(ctx, 2);
        tv.setPadding(ph, pv, ph, pv);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(t.bgColor);
        bg.setCornerRadius(dp(ctx, 999));
        tv.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMarginEnd(dp(ctx, 6));
        tv.setLayoutParams(lp);
        return tv;
    }

    private static int dp(@NonNull Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class TagSpec {
        final String text;
        final int bgColor;
        final int textColor;

        TagSpec(String text, int bgColor, int textColor) {
            this.text = text;
            this.bgColor = bgColor;
            this.textColor = textColor;
        }
    }
}
