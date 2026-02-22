package tn.eluea.kgpt.llm;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.llm.LanguageModelField;

/**
 * Centralized helper to build a sub-model suggestion list for a given provider.
 *
 * Sources (in order):
 * 1) Cached models fetched from provider endpoint
 * 2) Built-in presets
 * 3) Currently selected model (and provider default as fallback)
 */
public final class SubModelSuggestions {

    private SubModelSuggestions() {
    }

    private static final Map<LanguageModel, String[]> PRESETS = new HashMap<>();

    static {
        PRESETS.put(LanguageModel.Gemini, new String[]{
                "gemini-2.0-flash-exp",
                "gemini-1.5-pro",
                "gemini-1.5-flash",
                "gemini-1.5-flash-8b"
        });
        PRESETS.put(LanguageModel.ChatGPT, new String[]{
                "gpt-4.1",
                "gpt-4o",
                "gpt-4o-mini",
                "o1",
                "o1-mini"
        });
        PRESETS.put(LanguageModel.Groq, new String[]{
                "llama-3.1-70b-versatile",
                "llama-3.1-8b-instant",
                "mixtral-8x7b-32768",
                "gemma2-9b-it"
        });
        PRESETS.put(LanguageModel.OpenRouter, new String[]{
                "openai/gpt-4o",
                "openai/gpt-4o-mini",
                "anthropic/claude-3.5-sonnet",
                "google/gemini-2.0-flash-exp:free",
                "meta-llama/llama-3.2-3b-instruct"
        });
        PRESETS.put(LanguageModel.Claude, new String[]{
                "claude-3-5-sonnet-20240620",
                "claude-3-5-haiku-20241022",
                "claude-3-opus-20240229"
        });
        PRESETS.put(LanguageModel.Mistral, new String[]{
                "mistral-large-latest",
                "mistral-small-latest",
                "open-mistral-7b",
                "open-mixtral-8x7b"
        });
        PRESETS.put(LanguageModel.Chutes, new String[]{
                "deepseek-ai/DeepSeek-R1",
                "deepseek-ai/DeepSeek-V3",
                "Qwen/Qwen2.5-72B-Instruct"
        });
        PRESETS.put(LanguageModel.Perplexity, new String[]{
                "sonar",
                "sonar-pro",
                "sonar-reasoning",
                "sonar-reasoning-pro"
        });
        PRESETS.put(LanguageModel.GLM, new String[]{
                "glm-4-plus",
                "glm-4-air",
                "glm-4-flash",
                "glm-4"
        });
    }

    public static List<String> getSuggestions(LanguageModel model) {
        if (model == null) return Collections.emptyList();

        LinkedHashSet<String> set = new LinkedHashSet<>();

        // Cached (fetched) models
        try {
            if (SPManager.isReady()) {
                List<String> cached = SPManager.getInstance().getCachedModels(model);
                if (cached != null) {
                    for (String s : cached) {
                        if (!TextUtils.isEmpty(s)) set.add(s.trim());
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // Presets
        String[] presets = PRESETS.get(model);
        if (presets != null) {
            for (String p : presets) {
                if (!TextUtils.isEmpty(p)) set.add(p.trim());
            }
        }

        // Ensure currently selected is included (or provider default)
        String cur = null;
        try {
            if (SPManager.isReady()) cur = SPManager.getInstance().getSubModel(model);
        } catch (Throwable ignored) {
        }
        if (TextUtils.isEmpty(cur)) cur = model.getDefault(LanguageModelField.SubModel);
        if (!TextUtils.isEmpty(cur)) set.add(cur.trim());

        List<String> out = new ArrayList<>(set);
        Collections.sort(out);
        return out;
    }
}
