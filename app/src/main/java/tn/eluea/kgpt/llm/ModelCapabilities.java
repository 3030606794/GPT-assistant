package tn.eluea.kgpt.llm;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tn.eluea.kgpt.SPManager;

/**
 * Lightweight capability checks for sub-model strings.
 * Used to disable UI controls and avoid sending unsupported parameters.
 */
public final class ModelCapabilities {

    private ModelCapabilities() {}

    /**
     * Whether the given model is expected to accept a temperature-like parameter.
     *
     * We infer from the model id string. This is intentionally conservative for
     * non-text modalities (image / tts / transcribe / reranker, etc.).
     */
    public static boolean supportsTemperature(LanguageModel provider, String subModel) {
        // 1) Cached runtime knowledge (most reliable)
        try {
            if (SPManager.isReady()) {
                Boolean cached = SPManager.getInstance().getCachedSupportsTemperature(provider, subModel);
                if (cached != null) return cached;
            }
        } catch (Throwable ignored) {}

        if (TextUtils.isEmpty(subModel)) {
            // If unknown, assume supported for text models.
            return true;
        }

        String m = subModel.trim().toLowerCase();

        // Reasoning-first families often reject sampling params on some providers.
        // Based on the user's screenshot list, GPT-5.* should be treated as not temperature-adjustable.
        if (m.startsWith("gpt-5") || m.contains("gpt-5.")) {
            return false;
        }

        // Ranking / reranking models
        if (m.contains("rerank") || m.contains("reranker") || m.contains("ranker")) {
            return false;
        }

        // Embedding models
        if (m.contains("embedding") || m.contains("text-embedding") || m.contains("embed")) {
            return false;
        }

        // Speech / audio utility models
        if (m.contains("transcribe") || m.contains("tts") || m.contains("whisper")) {
            return false;
        }

        // Image generation / image-only endpoints
        if (m.contains("gpt-image") || m.contains("dall-e") || m.contains("image-preview") || m.contains("pro-image")) {
            return false;
        }

        // Generic moderation / safety endpoints
        if (m.contains("moderation") || m.contains("safety")) {
            return false;
        }

        return true;
    }

    /**
     * Whether "推理模型思考" should be enabled for the given model.
     *
     * Heuristic based on model id strings from the user's screenshot list:
     * - OpenAI GPT-5 family (gpt-5 / gpt-5.*)
     * - Claude / Gemini variants that contain "thinking"
     *
     * Also conservative exclusions for non-text modalities.
     */
    public static boolean supportsReasoningThinking(LanguageModel provider, String subModel) {
        // 1) Cached runtime knowledge (most reliable)
        try {
            if (SPManager.isReady()) {
                Boolean cached = SPManager.getInstance().getCachedSupportsReasoningThinking(provider, subModel);
                if (cached != null) return cached;
            }
        } catch (Throwable ignored) {}

        if (TextUtils.isEmpty(subModel)) {
            return false;
        }

        String m = subModel.trim().toLowerCase();

        // Exclude non-text modalities first.
        if (m.contains("rerank") || m.contains("reranker") || m.contains("ranker")) return false;
        if (m.contains("embedding") || m.contains("text-embedding") || m.contains("embed")) return false;
        if (m.contains("transcribe") || m.contains("tts") || m.contains("whisper")) return false;
        if (m.contains("gpt-image") || m.contains("dall-e") || m.contains("image-preview") || m.contains("pro-image")) return false;
        if (m.contains("moderation") || m.contains("safety")) return false;

        // Reasoning-capable indicators.
        if (m.contains("thinking")) return true;
        if (m.startsWith("gpt-5") || m.contains("gpt-5.")) return true;

        return false;
    }

    /**
     * Whether the given model is expected to accept a max output tokens parameter.
     *
     * Most text/chat models accept it, while non-text utility endpoints (tts/transcribe/rerank/etc.)
     * often reject it.
     */
    public static boolean supportsMaxTokens(LanguageModel provider, String subModel) {
        if (TextUtils.isEmpty(subModel)) return true;
        String m = subModel.trim().toLowerCase();

        if (m.contains("rerank") || m.contains("reranker") || m.contains("ranker")) return false;
        if (m.contains("embedding") || m.contains("text-embedding") || m.contains("embed")) return false;
        if (m.contains("transcribe") || m.contains("tts") || m.contains("whisper")) return false;
        if (m.contains("gpt-image") || m.contains("dall-e") || m.contains("image-preview") || m.contains("pro-image")) return false;
        if (m.contains("moderation") || m.contains("safety")) return false;
        return true;
    }


    /**
     * Best-effort classification for whether a model can be tested via chat-completion probe.
     * This is stricter than "text model" heuristics and is used only for the capability-test UI.
     */
    public static boolean isChatCapabilityTestable(LanguageModel provider, String subModel) {
        if (TextUtils.isEmpty(subModel)) return true;
        String m = subModel.trim().toLowerCase();

        // Non-chat / utility endpoints (common across providers and aggregators)
        if (m.contains("embedding") || m.contains("text-embedding") || m.contains("embed")) return false;
        if (m.contains("tts") || m.contains("whisper") || m.contains("transcribe") || m.contains("speech")) return false;
        if (m.contains("rerank") || m.contains("reranker") || m.contains("ranker")) return false;
        if (m.contains("moderation") || m.contains("safety")) return false;
        if (m.contains("gpt-image") || m.contains("dall-e") || m.contains("image-preview") || m.contains("imagegen") || m.contains("image")) {
            // Be conservative: image-* endpoints should not use chat probes.
            // (Some multimodal chat models also contain "vision" but usually not plain "image" endpoint ids.)
            if (m.contains("image") && !m.contains("vision")) return false;
        }
        return true;
    }

    /**
     * Detects probe failures caused by thinking budget / max_tokens parameter relationships,
     * which should not be cached as capability unsupported.
     */
    public static boolean isThinkingBudgetParamConflict(Throwable t) {
        if (t == null) return false;
        String msg = null;
        try { msg = t.getMessage(); } catch (Throwable ignored) {}
        if (TextUtils.isEmpty(msg)) return false;
        String m = msg.toLowerCase();
        return (m.contains("thinking.budget_tokens") && m.contains("max_tokens"))
                || (m.contains("budget_tokens") && (m.contains("must be greater") || m.contains("greater than")))
                || (m.contains("max_tokens") && m.contains("budget") && m.contains("thinking"));
    }

    /**
     * Detects non-chat endpoint errors (chatCompletions/chatCompletion not supported).
     */
    public static boolean isNonChatEndpointError(Throwable t) {
        if (t == null) return false;
        String msg = null;
        try { msg = t.getMessage(); } catch (Throwable ignored) {}
        if (TextUtils.isEmpty(msg)) return false;
        String m = msg.toLowerCase();
        return (m.contains("chatcompletion") || m.contains("chatcompletions") || m.contains("chat completion"))
                && (m.contains("does not work") || m.contains("not support") || m.contains("unsupported") || m.contains("specified model"));
    }

    /**
     * Best-effort detection for "token limit" / "max_tokens too large" failures.
     */
    public static boolean isLikelyMaxTokensConstraintError(@Nullable Throwable t) {
        if (isTokenLimitError(t)) return true;
        String s = errText(t).toLowerCase(Locale.US);
        if (s.isEmpty()) return false;
        boolean mentionsTokenBudget = (s.contains("token") || s.contains("max_tokens") || s.contains("max completion") || s.contains("max_completion_tokens") || s.contains("maxoutputtokens"));
        boolean looksLimit = s.contains("exceed") || s.contains("too large") || s.contains("too long") || s.contains("must be")
                || s.contains("at most") || s.contains("less than or equal") || s.contains("maximum") || s.contains("max is")
                || s.contains("invalid request") || s.contains("bad request") || s.contains("parameter") || s.contains("range");
        boolean exactField = s.contains("max_tokens") || s.contains("max completion tokens") || s.contains("max_completion_tokens") || s.contains("maxoutputtokens");
        return exactField || (mentionsTokenBudget && looksLimit);
    }

    public static boolean isTokenLimitError(Throwable t) {
        if (t == null) return false;
        String msg = null;
        try { msg = t.getMessage(); } catch (Throwable ignored) {}
        if (TextUtils.isEmpty(msg)) return false;
        String m = msg.toLowerCase();

        // Common patterns across providers
        if (m.contains("max_tokens") && (m.contains("too") || m.contains("exceed") || m.contains("maximum") || m.contains("limit"))) return true;
        if (m.contains("maxoutputtokens") && (m.contains("too") || m.contains("exceed") || m.contains("maximum") || m.contains("limit") || m.contains("supported range") || m.contains("out of range") || m.contains("range is from"))) return true;
        if (m.contains("context") && (m.contains("length") || m.contains("window")) && (m.contains("exceed") || m.contains("maximum") || m.contains("limit"))) return true;
        if (m.contains("context length") && m.contains("exceeded")) return true;
        if (m.contains("token") && m.contains("limit") && (m.contains("exceed") || m.contains("maximum"))) return true;
        if (m.contains("too many tokens")) return true;
        if (m.contains("requested") && m.contains("tokens") && (m.contains("exceed") || m.contains("maximum"))) return true;

        return false;
    }

    /**
     * Attempts to extract a suggested safe max token value from an error message.
     * Returns null if not found.
     */
    public static Integer extractSuggestedMaxTokens(@Nullable Throwable t) {
        String m = errText(t);
        if (m.isEmpty()) return null;
        String s = m.toLowerCase(Locale.US);

        // High-confidence patterns first.
        Pattern[] ps = new Pattern[]{
                Pattern.compile("(?i)(?:max[_\\s-]*tokens|max[_\\s-]*completion[_\\s-]*tokens|maxoutputtokens)[^\\d]{0,120}(?:must|should|cannot|can't|<=|less than or equal to|at most)[^\\d]{0,40}(\\d{2,7})"),
                Pattern.compile("(?i)(?:allowed|maximum|max)\\s*(?:is|:)?\\s*(\\d{2,7})\\s*(?:tokens?|token)"),
                Pattern.compile("(?i)(?:supports?|supports up to|up to)\\s*(\\d{2,7})\\s*(?:tokens?|token)"),
                Pattern.compile("(?i)requested\\s*(\\d{2,7}).{0,80}maximum\\s*(?:is|:)?\\s*(\\d{2,7})"),
                Pattern.compile("(?i)max(?:imum)?\\s*output\\s*(?:tokens?)?[^\\d]{0,40}(\\d{2,7})")
        };
        for (Pattern p : ps) {
            Matcher mm = p.matcher(m);
            if (mm.find()) {
                for (int gi = mm.groupCount(); gi >= 1; gi--) {
                    try {
                        String g = mm.group(gi);
                        if (g == null) continue;
                        int v = Integer.parseInt(g);
                        if (v >= 16 && v <= 2000000) return v;
                    } catch (Throwable ignored) {}
                }
            }
        }

        // Fallback heuristic only if the message looks token-limit related.
        if (!(s.contains("token") && (s.contains("max") || s.contains("limit") || s.contains("exceed") || s.contains("too large")))) {
            return null;
        }
        Matcher mnum = Pattern.compile("(\\d{2,7})").matcher(s);
        Integer best = null;
        while (mnum.find()) {
            try {
                int v = Integer.parseInt(mnum.group(1));
                if (v < 16 || v > 2000000) continue;
                if (v == 400 || v == 401 || v == 403 || v == 404 || v == 408 || v == 413 || v == 414 || v == 422 || v == 429 || v == 500 || v == 502 || v == 503 || v == 504) continue;
                if (best == null || v < best) best = v;
            } catch (Throwable ignored) {}
        }
        return best;
    }

    /**
     * Extract a provider/API hard completion-token cap from an error message.
     * This is more strict than {@link #extractSuggestedMaxTokens(Throwable)} and only matches
     * messages explicitly mentioning completion/output limits (e.g. "at most 32768 completion tokens").
     */
    public static Integer extractCompletionHardCap(@Nullable Throwable t) {
        String m = errText(t);
        if (m.isEmpty()) return null;
        String s = m.toLowerCase(Locale.US);
        // Must look like a completion/output limit, not a context window.
        if (!(s.contains("completion") || s.contains("max_completion") || s.contains("max completion")
                || s.contains("output") || s.contains("maxoutput") || s.contains("max_tokens"))) {
            return null;
        }

        Pattern[] ps = new Pattern[]{
                Pattern.compile("(?i)(?:at most|<=|less than or equal to)\\s*(\\d{2,7})\\s*(?:completion\\s*tokens?)"),
                Pattern.compile("(?i)(?:supports?)\\s*(?:at most|up to)?\\s*(\\d{2,7})\\s*(?:completion\\s*tokens?)"),
                Pattern.compile("(?i)max[_\\s-]*completion[_\\s-]*tokens[^\\d]{0,40}(\\d{2,7})"),
                Pattern.compile("(?i)max[_\\s-]*tokens[^\\d]{0,40}(\\d{2,7})\\s*(?:completion\\s*tokens?)")
        };
        for (Pattern p : ps) {
            Matcher mm = p.matcher(m);
            if (mm.find()) {
                try {
                    int v = Integer.parseInt(mm.group(1));
                    if (v >= 16 && v <= 2000000) {
                        // Skip common HTTP codes.
                        if (v == 400 || v == 401 || v == 403 || v == 404 || v == 408 || v == 413 || v == 414 || v == 422 || v == 429
                                || v == 500 || v == 502 || v == 503 || v == 504) return null;
                        return v;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    @NonNull
    private static String errText(@Nullable Throwable t) {
        if (t == null) return "";
        try {
            String msg = t.getMessage();
            if (!TextUtils.isEmpty(msg)) return msg;
        } catch (Throwable ignored) {}
        try {
            String s = String.valueOf(t);
            return s == null ? "" : s;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Best-effort detection for "unsupported parameter" style failures.
     * Used for auto-downgrade (retry without that parameter) and capability caching.
     */
    public static boolean isUnsupportedParamError(Throwable t, String paramName) {
        if (t == null || TextUtils.isEmpty(paramName)) return false;
        String msg = null;
        try { msg = t.getMessage(); } catch (Throwable ignored) {}
        if (TextUtils.isEmpty(msg)) return false;
        String m = msg.toLowerCase();
        String p = paramName.trim().toLowerCase();

        if (!m.contains(p)) return false;

        // Common phrases across providers
        if (m.contains("unsupported")
                || m.contains("not supported")
                || m.contains("does not support")
                || m.contains("unknown parameter")
                || m.contains("unrecognized")
                || m.contains("not allowed")
                || m.contains("invalid parameter")
                || m.contains("unexpected parameter")
                || m.contains("not a valid")
        ) {
            return true;
        }

        // Some providers use terse formats like: "parameter temperature is not available"
        if (m.contains("parameter") && (m.contains("not available") || m.contains("not accepted"))) {
            return true;
        }

        // Mutual-exclusion / one-of validation (common for temperature + top_p, etc.)
        boolean mentionsOneOfPair = false;
        if ("temperature".equals(p) || "top_p".equals(p) || "topp".equals(p)) {
            mentionsOneOfPair = (m.contains("temperature") && (m.contains("top_p") || m.contains("top p") || m.contains("topp")));
        }
        if (mentionsOneOfPair || m.contains(p)) {
            if (m.contains("cannot both be specified")
                    || m.contains("mutually exclusive")
                    || m.contains("choose one of")
                    || m.contains("only one of")
                    || (m.contains("one of") && (m.contains("temperature") || m.contains("top_p") || m.contains("topp")))
            ) {
                return true;
            }
        }

        return false;
    }
}
