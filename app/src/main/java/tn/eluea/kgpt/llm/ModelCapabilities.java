package tn.eluea.kgpt.llm;

import android.text.TextUtils;

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
     * Best-effort detection for "token limit" / "max_tokens too large" failures.
     */
    public static boolean isTokenLimitError(Throwable t) {
        if (t == null) return false;
        String msg = null;
        try { msg = t.getMessage(); } catch (Throwable ignored) {}
        if (TextUtils.isEmpty(msg)) return false;
        String m = msg.toLowerCase();

        // Common patterns across providers
        if (m.contains("max_tokens") && (m.contains("too") || m.contains("exceed") || m.contains("maximum") || m.contains("limit"))) return true;
        if (m.contains("maxoutputtokens") && (m.contains("too") || m.contains("exceed") || m.contains("maximum") || m.contains("limit"))) return true;
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
    public static Integer extractSuggestedMaxTokens(Throwable t) {
        if (t == null) return null;
        String msg = null;
        try { msg = t.getMessage(); } catch (Throwable ignored) {}
        if (TextUtils.isEmpty(msg)) return null;

        final String raw = msg;
        final String lower = raw.toLowerCase();

        // High-confidence patterns (avoid accidentally capturing HTTP status codes like 400/422/429).
        // Prefer limits expressed with "max_tokens" / "maximum context length" / "<= N".
        try {
            // NOTE: Android/AGP often compiles Java sources with -source 8.
            // Therefore DO NOT use Java 15+ string escapes like "\s" in string literals.
            // Always double-escape backslashes for regex patterns: "\\s", "\\d", etc.
            java.util.regex.Pattern[] ps = new java.util.regex.Pattern[] {
                    // max_tokens must be <= 4096
                    java.util.regex.Pattern.compile("(?i)max[_\\s-]*tokens[^\\d]{0,80}(?:<=|<|less\\s+than\\s+or\\s+equal\\s+to)\\s*(\\d{2,7})"),
                    // max_completion_tokens must be <= 4096
                    java.util.regex.Pattern.compile("(?i)max[_\\s-]*(?:completion[_\\s-]*tokens|max[_\\s-]*completion[_\\s-]*tokens|maxoutputtokens|max[_\\s-]*output[_\\s-]*tokens)[^\\d]{0,80}(?:<=|<|less\\s+than\\s+or\\s+equal\\s+to)\\s*(\\d{2,7})"),
                    // between 1 and 4096
                    java.util.regex.Pattern.compile("(?i)max[_\\s-]*tokens[^\\d]{0,80}between\\s*\\d{1,7}\\s*and\\s*(\\d{2,7})"),
                    // maximum context length is 8192 tokens
                    java.util.regex.Pattern.compile("(?i)maximum\\s+(?:context\\s+(?:length|window)|output\\s+tokens)[^\\d]{0,40}(\\d{2,7})\\s*tokens"),
                    // context window: 8192 tokens
                    java.util.regex.Pattern.compile("(?i)context\\s+(?:length|window)[^\\d]{0,40}(\\d{2,7})\\s*tokens"),
                    // limit is 8192 tokens
                    java.util.regex.Pattern.compile("(?i)limit[^\\d]{0,40}(\\d{2,7})\\s*tokens"),
            };

            for (java.util.regex.Pattern p : ps) {
                java.util.regex.Matcher mm = p.matcher(raw);
                if (mm.find()) {
                    String g = mm.group(1);
                    if (g != null) {
                        int v = Integer.parseInt(g);
                        if (v > 0) return v;
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Medium-confidence: numbers followed by "tokens" where the nearby context suggests a LIMIT
        // and not a "requested X tokens" count.
        try {
            java.util.ArrayList<Integer> cands = new java.util.ArrayList<>();
            java.util.regex.Matcher mt = java.util.regex.Pattern.compile("(?i)(\\d{2,7})\\s*tokens").matcher(raw);
            while (mt.find()) {
                String g = mt.group(1);
                if (g == null) continue;
                int v = Integer.parseInt(g);
                if (v <= 0) continue;

                int ctxStart = Math.max(0, mt.start() - 60);
                String ctx = lower.substring(ctxStart, mt.start());

                boolean looksLikeLimit = (ctx.contains("max") || ctx.contains("maximum") || ctx.contains("limit") || ctx.contains("context"));
                boolean looksRequested = (ctx.contains("request") || ctx.contains("requested"));
                if (looksLikeLimit && !looksRequested) cands.add(v);
            }
            if (!cands.isEmpty()) {
                int best = -1;
                for (Integer v : cands) {
                    if (v != null && v > best) best = v;
                }
                if (best > 0) return best;
            }
        } catch (Throwable ignored) {}

        // Fallback: pick the most plausible integer, skipping HTTP status codes when larger limits exist.
        try {
            java.util.ArrayList<Integer> all = new java.util.ArrayList<>();
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{3,7})").matcher(raw);
            while (matcher.find()) {
                String g = matcher.group(1);
                if (g == null) continue;
                int v = Integer.parseInt(g);
                if (v > 0) all.add(v);
            }
            if (all.isEmpty()) return null;

            boolean hasLarge = false;
            for (Integer v : all) {
                if (v != null && v >= 600) { hasLarge = true; break; }
            }

            int best = -1;
            for (Integer v : all) {
                if (v == null) continue;
                if (hasLarge && v >= 100 && v <= 599) continue; // likely HTTP status code
                if (v > best) best = v;
            }
            return best > 0 ? best : null;
        } catch (Throwable ignored) {}

        return null;
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

        return false;
    }
}
