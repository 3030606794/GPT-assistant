/*
 * Copyright (c) 2025 Amr Aldeeb @Eluea
 * GitHub: https://github.com/Eluea
 * Telegram: https://t.me/Eluea
 *
 * This file is part of KGPT.
 * Based on original code from KeyboardGPT by Mino260806.
 * Original: https://github.com/Mino260806/KeyboardGPT
 *
 * Licensed under the GPLv3.
 */
package tn.eluea.kgpt;

import android.content.Context;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashSet;

import java.util.Collections;
import java.util.List;

import tn.eluea.kgpt.instruction.command.Commands;
import tn.eluea.kgpt.instruction.command.GenerativeAICommand;
import tn.eluea.kgpt.llm.LanguageModel;
import tn.eluea.kgpt.listener.ConfigInfoProvider;
import tn.eluea.kgpt.llm.LanguageModelField;
import tn.eluea.kgpt.provider.ConfigClient;
import tn.eluea.kgpt.settings.OtherSettingsType;
import tn.eluea.kgpt.text.parse.ParsePattern;
import tn.eluea.kgpt.text.parse.PatternType;


import tn.eluea.kgpt.roles.RoleManager;
/**
 * Unified configuration manager that uses ContentProvider as single source of
 * truth.
 * Works in both KGPT app context and Xposed module (Gboard) context.
 */
public class SPManager implements ConfigInfoProvider {
    protected static final String PREF_MODULE_VERSION = "module_version";
    protected static final String PREF_LANGUAGE_MODEL = "language_model_v2";
    protected static final String PREF_GEN_AI_COMMANDS = "gen_ai_commands";
    protected static final String PREF_PARSE_PATTERNS = "parse_patterns";
    protected static final String PREF_OTHER_SETTING = "other_setting.%s";

    // User-defined quick jump templates (deep-link list)
    protected static final String PREF_QUICK_JUMP_CONFIG = "quick_jump_config";

    private final ConfigClient mClient;
    private List<GenerativeAICommand> generativeAICommands = List.of();
    private static SPManager instance = null;

    // Sticky cache for roles to avoid intermittent provider/Xposed read issues
    private volatile String mLastRolesJson = "";
    private volatile String mLastActiveRoleId = tn.eluea.kgpt.roles.RoleManager.DEFAULT_ROLE_ID;
    private volatile long mLastRoleSetAtMs = 0L;

    public static void init(Context context) {
        instance = new SPManager(context);
    }

    public static SPManager getInstance() {
        if (instance == null) {
            throw new RuntimeException("Missing call to SPManager.init(Context)");
        }
        return instance;
    }

    public static boolean isReady() {
        return instance != null;
    }

    private SPManager(Context context) {
        mClient = new ConfigClient(context);
        updateVersion();
        initializeDefaultCommands();
        initializeDefaultPatterns();
        updateGenerativeAICommands();
    }

    private void initializeDefaultCommands() {
        String existing = mClient.getString(PREF_GEN_AI_COMMANDS, null);
        if (existing == null || existing.equals("[]")) {
            setGenerativeAICommands(Commands.getDefaultCommands());
        }
    }

    private void initializeDefaultPatterns() {
        String existing = mClient.getString(PREF_PARSE_PATTERNS, null);
        if (existing == null) {
            // Initialize with default patterns
            setParsePatterns(ParsePattern.getDefaultPatterns());
            return;
        }

        // Migration: ensure any newly added PatternType entries exist in the saved list.
        // This keeps updates compatible without forcing users to reset their patterns.
        try {
            List<ParsePattern> patterns = ParsePattern.decode(existing);
            if (patterns == null) patterns = new ArrayList<>();
            boolean changed = false;

            for (PatternType type : PatternType.values()) {
                boolean found = false;
                for (ParsePattern p : patterns) {
                    if (p == null) continue;
                    if (p.getType() == type) { found = true; break; }
                }
                if (!found) {
                    ParsePattern p = new ParsePattern(type, type.defaultPattern);
                    p.setEnabled(true);
                    patterns.add(p);
                    changed = true;
                }
            }

            if (changed) {
                setParsePatterns(patterns);
            }
        } catch (Throwable t) {
            // If parsing fails for any reason, fall back to defaults (better than crashing).
            setParsePatterns(ParsePattern.getDefaultPatterns());
        }
    }

    private void updateVersion() {
        int version = getVersion();
        if (version != BuildConfig.VERSION_CODE) {
            mClient.putInt(PREF_MODULE_VERSION, BuildConfig.VERSION_CODE);
        }
    }

    public int getVersion() {
        return mClient.getInt(PREF_MODULE_VERSION, -1);
    }

    public boolean hasLanguageModel() {
        return mClient.contains(PREF_LANGUAGE_MODEL);
    }

    @Override
    public LanguageModel getLanguageModel() {
        String languageModelName = mClient.getString(PREF_LANGUAGE_MODEL, null);
        if (languageModelName == null) {
            languageModelName = LanguageModel.Gemini.name();
        }
        return LanguageModel.valueOf(languageModelName);
    }

    public void setLanguageModel(LanguageModel model) {
        mClient.putString(PREF_LANGUAGE_MODEL, model.name());
    }

    public void setLanguageModelField(LanguageModel model, LanguageModelField field, String value) {
        if (model == null || field == null) {
            tn.eluea.kgpt.util.Logger.log("setLanguageModelField: model or field is null");
            return;
        }
        String entryName = String.format("%s." + field, model.name());
        mClient.putString(entryName, value);
    }

    public String getLanguageModelField(LanguageModel model, LanguageModelField field) {
        String entryName = String.format("%s." + field, model.name());
        return mClient.getString(entryName, model.getDefault(field));
    }

    public void setApiKey(LanguageModel model, String apiKey) {
        setLanguageModelField(model, LanguageModelField.ApiKey, apiKey);
    }

    public String getApiKey(LanguageModel model) {
        return getLanguageModelField(model, LanguageModelField.ApiKey);
    }

    public void setSubModel(LanguageModel model, String subModel) {
        setLanguageModelField(model, LanguageModelField.SubModel, subModel);
    }

    public String getSubModel(LanguageModel model) {
        return getLanguageModelField(model, LanguageModelField.SubModel);
    }

    public void setBaseUrl(LanguageModel model, String baseUrl) {
        setLanguageModelField(model, LanguageModelField.BaseUrl, baseUrl);
    }

    public String getBaseUrl(LanguageModel model) {
        return getLanguageModelField(model, LanguageModelField.BaseUrl);
    }


// ===== Cached Models (for Model Switch) =====
private static final String PREF_CACHED_MODELS_JSON = "cached_models.%s.json";
private static final String PREF_CACHED_MODELS_BASEURL = "cached_models.%s.base_url";

public void setCachedModels(LanguageModel model, String baseUrl, List<String> models) {
    if (model == null) return;

    String keyJson = String.format(PREF_CACHED_MODELS_JSON, model.name());
    String keyUrl  = String.format(PREF_CACHED_MODELS_BASEURL, model.name());

    JSONArray arr = new JSONArray();
    HashSet<String> seen = new HashSet<>();

    if (models != null) {
        for (String s : models) {
            if (s == null) continue;
            String v = s.trim();
            if (v.isEmpty()) continue;
            if (seen.add(v)) arr.put(v);
        }
    }

    mClient.putString(keyJson, arr.toString());
    if (baseUrl != null) mClient.putString(keyUrl, baseUrl.trim());
}

public List<String> getCachedModels(LanguageModel model) {
    if (model == null) return Collections.emptyList();

    String keyJson = String.format(PREF_CACHED_MODELS_JSON, model.name());
    String raw = mClient.getString(keyJson, null);
    if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();

    ArrayList<String> out = new ArrayList<>();
    try {
        JSONArray arr = new JSONArray(raw);
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (s != null && !s.trim().isEmpty()) out.add(s.trim());
        }
    } catch (JSONException ignored) {}

    return out;
}

public String getCachedModelsBaseUrl(LanguageModel model) {
    if (model == null) return "";
    String keyUrl = String.format(PREF_CACHED_MODELS_BASEURL, model.name());
    String v = mClient.getString(keyUrl, "");
    return v == null ? "" : v;
}

    // ===== Roles (Personas) =====
    private static final String PREF_ROLES_JSON = tn.eluea.kgpt.roles.RoleManager.PREF_ROLES_JSON;
    private static final String PREF_ACTIVE_ROLE_ID = tn.eluea.kgpt.roles.RoleManager.PREF_ACTIVE_ROLE_ID;
    private static final String PREF_LAST_CUSTOM_ROLE_ID = "last_custom_role_id_v1";
    private static final String PREF_FORCE_DEFAULT_ROLE = "force_default_role_v1";

    public void setRolesJson(String rolesJson) {
        String v = rolesJson != null ? rolesJson : "";
        mLastRolesJson = v;
        mClient.putString(PREF_ROLES_JSON, v);
    }

    public String getRolesJson() {
        String v = mClient.getString(PREF_ROLES_JSON, "");
        if (v == null) v = "";
        // If provider/Xposed returns empty unexpectedly, fall back to last known value
        if (v.isEmpty() && mLastRolesJson != null && !mLastRolesJson.isEmpty()) {
            return mLastRolesJson;
        }
        mLastRolesJson = v;
        return v;
    }

    public void setActiveRoleId(String roleId) {
        String v = roleId != null ? roleId : tn.eluea.kgpt.roles.RoleManager.DEFAULT_ROLE_ID;
        v = v.trim().isEmpty() ? tn.eluea.kgpt.roles.RoleManager.DEFAULT_ROLE_ID : v.trim();

        mLastActiveRoleId = v;
        mLastRoleSetAtMs = System.currentTimeMillis();

        // Persist active role
        mClient.putString(PREF_ACTIVE_ROLE_ID, v);

        // Whether the user explicitly forced the DEFAULT role.
        // When true, we must NOT auto-fallback to last custom role.
        mClient.putBoolean(PREF_FORCE_DEFAULT_ROLE, tn.eluea.kgpt.roles.RoleManager.DEFAULT_ROLE_ID.equals(v));

        // Permanent stickiness: remember last custom role until the user explicitly selects default.
        if (tn.eluea.kgpt.roles.RoleManager.DEFAULT_ROLE_ID.equals(v)) {
            mClient.putString(PREF_LAST_CUSTOM_ROLE_ID, "");
        } else {
            mClient.putString(PREF_LAST_CUSTOM_ROLE_ID, v);
        }
    }

    
    public String getActiveRoleId() {
        // Active role id may occasionally read as DEFAULT due to provider race or cache issues,
        // which makes the assistant "jump back" to the default role after a few messages.
        // To keep the selected role stable, we keep a robust fallback chain:
        // 1) value stored in PREF_ACTIVE_ROLE_ID
        // 2) last custom role id (PREF_LAST_CUSTOM_ROLE_ID)
        // 3) in-memory last known role id
        String v = null;
        boolean forceDefault = false;
        try {
            forceDefault = mClient.getBoolean(PREF_FORCE_DEFAULT_ROLE, false);
        } catch (Throwable ignored) {}
        try {
            v = mClient.getString(PREF_ACTIVE_ROLE_ID, RoleManager.DEFAULT_ROLE_ID);
        } catch (Throwable ignored) {
        }
        if (v != null) v = v.trim();
        if (v == null || v.isEmpty()) {
            // read failure or empty -> prefer last custom
            String lastCustom = null;
            try {
                lastCustom = mClient.getString(PREF_LAST_CUSTOM_ROLE_ID, "");
            } catch (Throwable ignored) {
            }
            if (lastCustom != null) lastCustom = lastCustom.trim();
            if (lastCustom != null && !lastCustom.isEmpty() && !RoleManager.DEFAULT_ROLE_ID.equals(lastCustom)) {
                mLastActiveRoleId = lastCustom;
                // self-heal the active role value so next reads are consistent
                try { mClient.putString(PREF_ACTIVE_ROLE_ID, lastCustom); mClient.putBoolean(PREF_FORCE_DEFAULT_ROLE, false); } catch (Throwable ignored) {}
                return lastCustom;
            }
            if (mLastActiveRoleId != null) {
                String mem = mLastActiveRoleId.trim();
                if (!mem.isEmpty() && !RoleManager.DEFAULT_ROLE_ID.equals(mem)) {
                    return mem;
                }
            }
            mLastActiveRoleId = RoleManager.DEFAULT_ROLE_ID;
            return RoleManager.DEFAULT_ROLE_ID;
        }

        // If provider returns default but user previously selected a custom role, keep the custom role
        // UNLESS the user explicitly forced the default role.
        if (RoleManager.DEFAULT_ROLE_ID.equals(v)) {
            if (forceDefault) {
                mLastActiveRoleId = v;
                return v;
            }

            String lastCustom = null;
            try {
                lastCustom = mClient.getString(PREF_LAST_CUSTOM_ROLE_ID, "");
            } catch (Throwable ignored) {
            }
            if (lastCustom != null) lastCustom = lastCustom.trim();
            if (lastCustom != null && !lastCustom.isEmpty() && !RoleManager.DEFAULT_ROLE_ID.equals(lastCustom)) {
                mLastActiveRoleId = lastCustom;
                try { 
                    mClient.putString(PREF_ACTIVE_ROLE_ID, lastCustom);
                    mClient.putBoolean(PREF_FORCE_DEFAULT_ROLE, false);
                } catch (Throwable ignored) {}
                return lastCustom;
            }

            // No last custom role -> trust DEFAULT
            mLastActiveRoleId = v;
            return v;
        }
        // Normal non-default role
        mLastActiveRoleId = v;
        // keep last custom role updated (best-effort)
        try { mClient.putString(PREF_LAST_CUSTOM_ROLE_ID, v); mClient.putBoolean(PREF_FORCE_DEFAULT_ROLE, false); } catch (Throwable ignored) {}
        return v;
    }




    public void setGenerativeAICommandsRaw(String commands) {
        mClient.putString(PREF_GEN_AI_COMMANDS, commands);
        updateGenerativeAICommands();
    }

    public String getGenerativeAICommandsRaw() {
        return mClient.getString(PREF_GEN_AI_COMMANDS, "[]");
    }

    public void setGenerativeAICommands(List<GenerativeAICommand> commands) {
        setGenerativeAICommandsRaw(Commands.encodeCommands(commands));
    }

    public List<GenerativeAICommand> getGenerativeAICommands() {
        // Always get fresh data
        updateGenerativeAICommands();
        return generativeAICommands;
    }

    public void setParsePatterns(List<ParsePattern> parsePatterns) {
        setParsePatternsRaw(ParsePattern.encode(parsePatterns));
    }

    public void setParsePatternsRaw(String patternsRaw) {
        mClient.putString(PREF_PARSE_PATTERNS, patternsRaw);
    }

    public List<ParsePattern> getParsePatterns() {
        return ParsePattern.decode(getParsePatternsRaw());
    }

    /**
     * Get the current AI trigger keyword/symbol (the one configured for PatternType.CommandAI).
     *
     * This is used as the default trigger for roles when a role-specific trigger is not set.
     */
    public String getAiTriggerSymbol() {
        try {
            List<ParsePattern> patterns = getParsePatterns();
            if (patterns != null) {
                for (ParsePattern p : patterns) {
                    if (p == null) continue;
                    if (p.getType() != PatternType.CommandAI) continue;
                    String sym = null;
                    try {
                        sym = PatternType.regexToSymbol(p.getPattern().pattern());
                    } catch (Throwable ignored) {
                    }
                    if (sym != null) sym = sym.trim();
                    if (sym != null && !sym.isEmpty()) return sym;

                    // Pattern exists but we couldn't extract the symbol for some reason.
                    // Fall back to the type default.
                    return PatternType.CommandAI.defaultSymbol;
                }
            }
        } catch (Throwable ignored) {}
        return PatternType.CommandAI.defaultSymbol;
    }

    public String getParsePatternsRaw() {
        return mClient.getString(PREF_PARSE_PATTERNS, null);
    }

    
    // ===== AI Trigger (multiline prompt sending) =====
    // True: send full text (including newlines) when AI trigger symbol is used.
    // False: send only the last line (approximation of "cursor line").
    private static final String PREF_AI_TRIGGER_MULTILINE = "ai_trigger_multiline_enabled_v1";

    public boolean getAiTriggerMultilineEnabled() {
        return mClient.getBoolean(PREF_AI_TRIGGER_MULTILINE, true); // default ON
    }

    public void setAiTriggerMultilineEnabled(boolean enabled) {
        mClient.putBoolean(PREF_AI_TRIGGER_MULTILINE, enabled);
    }

    // ===== Invocation master switches =====
    // Master toggles to quickly enable/disable all invocation commands or triggers.
    // They do NOT change the per-command/per-trigger configuration; they only gate runtime parsing.
    private static final String PREF_INVOCATION_COMMANDS_ENABLED = "invocation_commands_enabled_v1";
    private static final String PREF_INVOCATION_TRIGGERS_ENABLED = "invocation_triggers_enabled_v1";
    // Backup of per-trigger enabled states for the "master switch" bulk toggle (restore previous states).
    private static final String PREF_INVOCATION_TRIGGERS_ENABLED_STATES_BACKUP = "invocation_triggers_enabled_states_backup_v1";

    // ===== Streaming output =====
    // When enabled, supported providers will request streaming responses and the keyboard
    // will show the answer as it arrives. When disabled, chunks are buffered and committed
    // only once at completion (and providers may request non-streaming responses).
    private static final String PREF_STREAMING_OUTPUT_ENABLED = "streaming_output_enabled_v1";

    // Streaming output animation speed (0-100). 100 = fastest.
    // This controls how quickly the keyboard commits streamed text chunks.
    private static final String PREF_STREAMING_OUTPUT_SPEED_PERCENT = "streaming_output_speed_percent_v1";
    // If enabled, speed is automatically adjusted based on response length/backlog.
    private static final String PREF_STREAMING_OUTPUT_SPEED_AUTO = "streaming_output_speed_auto_v1";

    // Streaming speed algorithm:
    // - LINEAR: use speed percent + auto pacing (current behavior)
    // - NONLINEAR: use physics/random models (beta; UI will be added later)
    private static final String PREF_STREAMING_OUTPUT_SPEED_ALGO = "streaming_output_speed_algo_v1";

    public static final int STREAM_SPEED_ALGO_LINEAR = 0;
    public static final int STREAM_SPEED_ALGO_NONLINEAR = 1;

    // Non-linear models (used when STREAM_SPEED_ALGO_NONLINEAR is selected)
    private static final String PREF_STREAMING_NONLINEAR_MODEL = "streaming_nonlinear_model_v1";

    public static final int STREAM_NL_MODEL_LINEAR_CONSTANT = 0;
    public static final int STREAM_NL_MODEL_EXPONENTIAL_DECAY = 1;
    public static final int STREAM_NL_MODEL_SINE_WAVE_JITTER = 2;
    public static final int STREAM_NL_MODEL_DAMPED_OSCILLATOR = 3;
    public static final int STREAM_NL_MODEL_SQUARE_WAVE_BURST = 4;
    public static final int STREAM_NL_MODEL_MARKOV_RANDOM_WALK = 5;

    // Global non-linear parameters
    // sigma: Gaussian noise (ms) applied to each tick delay
    // pauseMultiplier: multiplies delay after punctuation boundaries
    private static final String PREF_STREAMING_NONLINEAR_SIGMA_MS = "streaming_nonlinear_sigma_ms_v1";
    private static final String PREF_STREAMING_NONLINEAR_PAUSE_MULT = "streaming_nonlinear_pause_mult_v1";

    // Prefetch/render buffer (only used for NON-LINEAR streaming output)
    // This smooths network chunk jitter by buffering some text before rendering.
    private static final String PREF_STREAMING_PREFETCH_MODE = "streaming_prefetch_mode_v1";
    private static final String PREF_STREAMING_PREFETCH_START_CHARS = "streaming_prefetch_start_chars_v1";
    private static final String PREF_STREAMING_PREFETCH_LOW_WATERMARK = "streaming_prefetch_low_watermark_v1";
    private static final String PREF_STREAMING_PREFETCH_TOPUP_TARGET = "streaming_prefetch_topup_target_v1";

    // Prefetch modes
    public static final int STREAM_PREFETCH_OFF = 0;
    public static final int STREAM_PREFETCH_DEFAULT = 1;
    public static final int STREAM_PREFETCH_FAST = 2;
    public static final int STREAM_PREFETCH_STABLE = 3;
    public static final int STREAM_PREFETCH_CUSTOM = 4;

    // Per-model parameters (stored as int ms or string double)
    private static final String PREF_NL_LC_TBASE_MS = "streaming_nl_lc_tbase_ms_v1";

    private static final String PREF_NL_EXP_TMAX_MS = "streaming_nl_exp_tmax_ms_v1";
    private static final String PREF_NL_EXP_TMIN_MS = "streaming_nl_exp_tmin_ms_v1";
    private static final String PREF_NL_EXP_LAMBDA = "streaming_nl_exp_lambda_v1";

    private static final String PREF_NL_SINE_TBASE_MS = "streaming_nl_sine_tbase_ms_v1";
    private static final String PREF_NL_SINE_A_MS = "streaming_nl_sine_a_ms_v1";
    private static final String PREF_NL_SINE_OMEGA = "streaming_nl_sine_omega_v1";
    private static final String PREF_NL_SINE_PHI = "streaming_nl_sine_phi_v1";
    private static final String PREF_NL_SINE_PERIOD_N = "streaming_nl_sine_period_n_v1";

    private static final String PREF_NL_DAMP_TBASE_MS = "streaming_nl_damp_tbase_ms_v1";
    private static final String PREF_NL_DAMP_A_MS = "streaming_nl_damp_a_ms_v1";
    private static final String PREF_NL_DAMP_OMEGA = "streaming_nl_damp_omega_v1";
    private static final String PREF_NL_DAMP_ZETA = "streaming_nl_damp_zeta_v1";
    private static final String PREF_NL_DAMP_PHI = "streaming_nl_damp_phi_v1";

    private static final String PREF_NL_SQ_TBASE_MS = "streaming_nl_sq_tbase_ms_v1";
    private static final String PREF_NL_SQ_A_MS = "streaming_nl_sq_a_ms_v1";
    private static final String PREF_NL_SQ_OMEGA = "streaming_nl_sq_omega_v1";

    private static final String PREF_NL_MK_MU_MS = "streaming_nl_mk_mu_ms_v1";
    private static final String PREF_NL_MK_RHO = "streaming_nl_mk_rho_v1";
    private static final String PREF_NL_MK_SIGMA_MS = "streaming_nl_mk_sigma_ms_v1";
    private static final String PREF_NL_MK_TMIN_MS = "streaming_nl_mk_tmin_ms_v1";
    private static final String PREF_NL_MK_TMAX_MS = "streaming_nl_mk_tmax_ms_v1";
    private static final String PREF_NL_MK_PTHINK_PERCENT = "streaming_nl_mk_pthink_percent_v1";


    // Streaming output mode:
    // - AUTO: Detect by response format (SSE "data:" vs JSON lines)
    // - SSE: Force Server-Sent Events parsing (data: ...)
    // - JSONL: Force JSON Lines parsing (one JSON object per line)
    // - TYPEWRITER: Request non-streaming from backend and render with the local typewriter
    //               (useful when backend doesn't support streaming or format is incompatible)
    private static final String PREF_STREAMING_OUTPUT_MODE = "streaming_output_mode_v1";

    // Output render granularity:
    // - CHARS: commit characters
    // - WORDS: snap to whitespace boundaries
    // - PUNCT: snap to punctuation boundaries
    private static final String PREF_STREAMING_OUTPUT_GRANULARITY = "streaming_output_granularity_v1";

    // If streaming was requested but stream parsing produced no chunks, try to parse
    // a non-streaming OpenAI-compatible JSON response and display it.
    private static final String PREF_STREAMING_OUTPUT_FALLBACK_NON_STREAM = "streaming_output_fallback_non_stream_v1";

    // Public constants for code/UI
    public static final int STREAM_MODE_AUTO = 0;
    public static final int STREAM_MODE_SSE = 1;
    public static final int STREAM_MODE_JSONL = 2;
    public static final int STREAM_MODE_TYPEWRITER = 3;

    public static final int STREAM_GRANULARITY_CHARS = 0;
    public static final int STREAM_GRANULARITY_WORDS = 1;
    public static final int STREAM_GRANULARITY_PUNCT = 2;


    // Per-request overrides (ThreadLocal). Used by auto-downgrade retry logic to
    // force a different streaming mode without changing the user's global setting.
    private static final ThreadLocal<Integer> TL_STREAM_MODE_OVERRIDE = new ThreadLocal<>();

    public static void setThreadStreamingModeOverride(Integer mode) {
        if (mode == null) {
            TL_STREAM_MODE_OVERRIDE.remove();
            return;
        }
        TL_STREAM_MODE_OVERRIDE.set(mode);
    }

    public static void clearThreadStreamingModeOverride() {
        TL_STREAM_MODE_OVERRIDE.remove();
    }

    public boolean getStreamingOutputEnabled() {
        return mClient.getBoolean(PREF_STREAMING_OUTPUT_ENABLED, false);
    }

    public void setStreamingOutputEnabled(boolean enabled) {
        mClient.putBoolean(PREF_STREAMING_OUTPUT_ENABLED, enabled);
    }

    /** 0..100, 100 = fastest. */
    public int getStreamingOutputSpeedPercent() {
        int v = 45; // default: a bit slower (can adjust in Labs)
        try {
            v = mClient.getInt(PREF_STREAMING_OUTPUT_SPEED_PERCENT, v);
        } catch (Throwable ignored) {
            // Some environments may store it as string/bool; fall back to default.
            try {
                String s = mClient.getString(PREF_STREAMING_OUTPUT_SPEED_PERCENT, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        return v;
    }

    public void setStreamingOutputSpeedPercent(int percent) {
        int v = percent;
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        try {
            mClient.putInt(PREF_STREAMING_OUTPUT_SPEED_PERCENT, v);
        } catch (Throwable t) {
            // Fallback to string if putInt is not supported by the underlying client.
            try { mClient.putString(PREF_STREAMING_OUTPUT_SPEED_PERCENT, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** Auto speed adjustment based on response length/backlog. */
    public boolean getStreamingOutputSpeedAutoEnabled() {
        return mClient.getBoolean(PREF_STREAMING_OUTPUT_SPEED_AUTO, true); // default ON
    }

    public void setStreamingOutputSpeedAutoEnabled(boolean enabled) {
        mClient.putBoolean(PREF_STREAMING_OUTPUT_SPEED_AUTO, enabled);
    }

    // --- Streaming speed algorithm (Linear / Non-linear) ---

    public int getStreamingOutputSpeedAlgorithm() {
        int v = STREAM_SPEED_ALGO_LINEAR;
        try {
            v = mClient.getInt(PREF_STREAMING_OUTPUT_SPEED_ALGO, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_STREAMING_OUTPUT_SPEED_ALGO, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        return (v == STREAM_SPEED_ALGO_NONLINEAR) ? STREAM_SPEED_ALGO_NONLINEAR : STREAM_SPEED_ALGO_LINEAR;
    }

    public void setStreamingOutputSpeedAlgorithm(int algo) {
        int v = (algo == STREAM_SPEED_ALGO_NONLINEAR) ? STREAM_SPEED_ALGO_NONLINEAR : STREAM_SPEED_ALGO_LINEAR;
        try {
            mClient.putInt(PREF_STREAMING_OUTPUT_SPEED_ALGO, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_STREAMING_OUTPUT_SPEED_ALGO, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    public int getStreamingNonLinearModel() {
        int v = STREAM_NL_MODEL_MARKOV_RANDOM_WALK; // default (best realism)
        try {
            v = mClient.getInt(PREF_STREAMING_NONLINEAR_MODEL, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_STREAMING_NONLINEAR_MODEL, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < STREAM_NL_MODEL_LINEAR_CONSTANT) v = STREAM_NL_MODEL_LINEAR_CONSTANT;
        if (v > STREAM_NL_MODEL_MARKOV_RANDOM_WALK) v = STREAM_NL_MODEL_MARKOV_RANDOM_WALK;
        return v;
    }

    public void setStreamingNonLinearModel(int model) {
        int v = model;
        if (v < STREAM_NL_MODEL_LINEAR_CONSTANT) v = STREAM_NL_MODEL_LINEAR_CONSTANT;
        if (v > STREAM_NL_MODEL_MARKOV_RANDOM_WALK) v = STREAM_NL_MODEL_MARKOV_RANDOM_WALK;
        try {
            mClient.putInt(PREF_STREAMING_NONLINEAR_MODEL, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_STREAMING_NONLINEAR_MODEL, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    // Helpers for doubles stored as strings (ConfigClient has no float/double type)
    private static double safeParseDouble(String s, double def) {
        if (s == null) return def;
        try {
            String t = s.trim();
            if (t.isEmpty()) return def;
            return Double.parseDouble(t);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private double getDouble(String key, double def) {
        try {
            return safeParseDouble(mClient.getString(key, null), def);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private void putDouble(String key, double v) {
        try {
            mClient.putString(key, Double.toString(v));
        } catch (Throwable ignored) {}
    }

    /** Non-linear sigma in milliseconds (standard deviation). */
    public int getStreamingNonLinearSigmaMs() {
        int v = 0;
        try {
            v = mClient.getInt(PREF_STREAMING_NONLINEAR_SIGMA_MS, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_STREAMING_NONLINEAR_SIGMA_MS, null);
                if (s != null) v = (int) Math.round(Double.parseDouble(s.trim()));
            } catch (Throwable ignored2) {}
        }
        if (v < 0) v = 0;
        if (v > 250) v = 250;
        return v;
    }

    public void setStreamingNonLinearSigmaMs(int sigmaMs) {
        int v = sigmaMs;
        if (v < 0) v = 0;
        if (v > 250) v = 250;
        try {
            mClient.putInt(PREF_STREAMING_NONLINEAR_SIGMA_MS, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_STREAMING_NONLINEAR_SIGMA_MS, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** Pause multiplier applied after punctuation boundaries. */
    public double getStreamingNonLinearPauseMultiplier() {
        double v = getDouble(PREF_STREAMING_NONLINEAR_PAUSE_MULT, 2.0);
        if (v < 1.0) v = 1.0;
        if (v > 6.0) v = 6.0;
        return v;
    }

    public void setStreamingNonLinearPauseMultiplier(double mult) {
        double v = mult;
        if (v < 1.0) v = 1.0;
        if (v > 6.0) v = 6.0;
        putDouble(PREF_STREAMING_NONLINEAR_PAUSE_MULT, v);
    }

    // --- Prefetch / render buffer (NON-LINEAR only) ---

    /**
     * Prefetch mode controls how the app buffers text before rendering to smooth network chunk jitter.
     * Only applied when streaming algorithm is NON-LINEAR.
     */
    public int getStreamingPrefetchMode() {
        int v = STREAM_PREFETCH_DEFAULT;
        try {
            v = mClient.getInt(PREF_STREAMING_PREFETCH_MODE, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_STREAMING_PREFETCH_MODE, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < STREAM_PREFETCH_OFF) v = STREAM_PREFETCH_OFF;
        if (v > STREAM_PREFETCH_CUSTOM) v = STREAM_PREFETCH_CUSTOM;
        return v;
    }

    public void setStreamingPrefetchMode(int mode) {
        int v = mode;
        if (v < STREAM_PREFETCH_OFF) v = STREAM_PREFETCH_OFF;
        if (v > STREAM_PREFETCH_CUSTOM) v = STREAM_PREFETCH_CUSTOM;
        try {
            mClient.putInt(PREF_STREAMING_PREFETCH_MODE, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_STREAMING_PREFETCH_MODE, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** Custom: how many chars to prefetch before starting rendering. */
    public int getStreamingPrefetchStartChars() {
        int v = 120;
        try {
            v = mClient.getInt(PREF_STREAMING_PREFETCH_START_CHARS, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_STREAMING_PREFETCH_START_CHARS, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < 0) v = 0;
        if (v > 1200) v = 1200;
        return v;
    }

    public void setStreamingPrefetchStartChars(int startChars) {
        int v = startChars;
        if (v < 0) v = 0;
        if (v > 1200) v = 1200;
        try {
            mClient.putInt(PREF_STREAMING_PREFETCH_START_CHARS, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_STREAMING_PREFETCH_START_CHARS, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** Custom: low-water mark for render buffer. */
    public int getStreamingPrefetchLowWatermark() {
        int v = 80;
        try {
            v = mClient.getInt(PREF_STREAMING_PREFETCH_LOW_WATERMARK, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_STREAMING_PREFETCH_LOW_WATERMARK, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < 0) v = 0;
        if (v > 1200) v = 1200;
        return v;
    }

    public void setStreamingPrefetchLowWatermark(int low) {
        int v = low;
        if (v < 0) v = 0;
        if (v > 1200) v = 1200;
        try {
            mClient.putInt(PREF_STREAMING_PREFETCH_LOW_WATERMARK, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_STREAMING_PREFETCH_LOW_WATERMARK, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** Custom: top-up target for render buffer when it falls below low-water. */
    public int getStreamingPrefetchTopUpTarget() {
        int v = 260;
        try {
            v = mClient.getInt(PREF_STREAMING_PREFETCH_TOPUP_TARGET, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_STREAMING_PREFETCH_TOPUP_TARGET, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < 0) v = 0;
        if (v > 2400) v = 2400;
        return v;
    }

    public void setStreamingPrefetchTopUpTarget(int target) {
        int v = target;
        if (v < 0) v = 0;
        if (v > 2400) v = 2400;
        try {
            mClient.putInt(PREF_STREAMING_PREFETCH_TOPUP_TARGET, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_STREAMING_PREFETCH_TOPUP_TARGET, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    // --- Per-model defaults + getters (used by AiResponseManager non-linear framework) ---

    public int getNlLinearConstantTBaseMs() {
        int v = 50;
        try { v = mClient.getInt(PREF_NL_LC_TBASE_MS, v); } catch (Throwable ignored) {}
        if (v < 10) v = 10;
        if (v > 200) v = 200;
        return v;
    }

    public void setNlLinearConstantTBaseMs(int ms) {
        int v = ms;
        if (v < 10) v = 10;
        if (v > 200) v = 200;
        try { mClient.putInt(PREF_NL_LC_TBASE_MS, v); } catch (Throwable ignored) { try { mClient.putString(PREF_NL_LC_TBASE_MS, String.valueOf(v)); } catch (Throwable ignored2) {} }
    }

    public int getNlExpTMaxMs() {
        int v = 220;
        try { v = mClient.getInt(PREF_NL_EXP_TMAX_MS, v); } catch (Throwable ignored) {}
        if (v < 20) v = 20;
        if (v > 1200) v = 1200;
        return v;
    }

    public int getNlExpTMinMs() {
        int v = 28;
        try { v = mClient.getInt(PREF_NL_EXP_TMIN_MS, v); } catch (Throwable ignored) {}
        if (v < 10) v = 10;
        if (v > 800) v = 800;
        return v;
    }

    public double getNlExpLambda() {
        double v = getDouble(PREF_NL_EXP_LAMBDA, 0.045);
        if (v < 0.0001) v = 0.0001;
        if (v > 1.0) v = 1.0;
        return v;
    }

    public void setNlExpTMaxMs(int ms) {
        int v = ms;
        if (v < 20) v = 20;
        if (v > 1200) v = 1200;
        try { mClient.putInt(PREF_NL_EXP_TMAX_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_EXP_TMAX_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    public void setNlExpTMinMs(int ms) {
        int v = ms;
        if (v < 10) v = 10;
        if (v > 800) v = 800;
        try { mClient.putInt(PREF_NL_EXP_TMIN_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_EXP_TMIN_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    public void setNlExpLambda(double lambda) {
        double v = lambda;
        if (v < 0.0001) v = 0.0001;
        if (v > 1.0) v = 1.0;
        putDouble(PREF_NL_EXP_LAMBDA, v);
    }


    public int getNlSineTBaseMs() {
        int v = 60;
        try { v = mClient.getInt(PREF_NL_SINE_TBASE_MS, v); } catch (Throwable ignored) {}
        if (v < 20) v = 20;
        if (v > 150) v = 150;
        return v;
    }

    public int getNlSineAMs() {
        int v = 30;
        try { v = mClient.getInt(PREF_NL_SINE_A_MS, v); } catch (Throwable ignored) {}
        if (v < 10) v = 10;
        if (v > 100) v = 100;
        return v;
    }

    public double getNlSineOmega() {
        // Derived from breathing period (N chars per full cycle): ω = 2π / N
        int n = getNlSinePeriodN();
        if (n < 5) n = 5;
        return (2.0 * Math.PI) / (double) n;
    }

    public double getNlSinePhi() {
        double v = getDouble(PREF_NL_SINE_PHI, 0.0);
        if (v < -6.283185) v = -6.283185;
        if (v >  6.283185) v =  6.283185;
        return v;
    }


    public int getNlSinePeriodN() {
        int v = 15;
        try { v = mClient.getInt(PREF_NL_SINE_PERIOD_N, v); } catch (Throwable ignored) {}
        if (v < 5) v = 5;
        if (v > 50) v = 50;
        return v;
    }

    public void setNlSinePeriodN(int n) {
        int v = n;
        if (v < 5) v = 5;
        if (v > 50) v = 50;
        try { mClient.putInt(PREF_NL_SINE_PERIOD_N, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_SINE_PERIOD_N, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    public void setNlSineTBaseMs(int ms) {
        int v = ms;
        if (v < 20) v = 20;
        if (v > 150) v = 150;
        try { mClient.putInt(PREF_NL_SINE_TBASE_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_SINE_TBASE_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    public void setNlSineAMs(int ms) {
        int v = ms;
        if (v < 10) v = 10;
        if (v > 100) v = 100;
        try { mClient.putInt(PREF_NL_SINE_A_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_SINE_A_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    public int getNlDampTBaseMs() {
        int v = 90;
        try { v = mClient.getInt(PREF_NL_DAMP_TBASE_MS, v); } catch (Throwable ignored) {}
        if (v < 10) v = 10;
        if (v > 900) v = 900;
        return v;
    }

    public int getNlDampAMs() {
        int v = 85;
        try { v = mClient.getInt(PREF_NL_DAMP_A_MS, v); } catch (Throwable ignored) {}
        if (v < 0) v = 0;
        if (v > 800) v = 800;
        return v;
    }

    public double getNlDampOmega() {
        double v = getDouble(PREF_NL_DAMP_OMEGA, 1.1);
        if (v < 0.01) v = 0.01;
        if (v > 20.0) v = 20.0;
        return v;
    }

    public double getNlDampZeta() {
        double v = getDouble(PREF_NL_DAMP_ZETA, 0.05);
        if (v < 0.0) v = 0.0;
        if (v > 5.0) v = 5.0;
        return v;
    }

    public double getNlDampPhi() {
        double v = getDouble(PREF_NL_DAMP_PHI, 0.0);
        if (v < -6.283185) v = -6.283185;
        if (v >  6.283185) v =  6.283185;
        return v;
    }

    public void setNlDampAMs(int ms) {
        int v = ms;
        if (v < 0) v = 0;
        if (v > 800) v = 800;
        try { mClient.putInt(PREF_NL_DAMP_A_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_DAMP_A_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    public void setNlDampOmega(double omega) {
        double v = omega;
        if (v < 0.01) v = 0.01;
        if (v > 20.0) v = 20.0;
        putDouble(PREF_NL_DAMP_OMEGA, v);
    }

    public void setNlDampZeta(double zeta) {
        double v = zeta;
        if (v < 0.0) v = 0.0;
        if (v > 5.0) v = 5.0;
        putDouble(PREF_NL_DAMP_ZETA, v);
    }


    public int getNlSquareTBaseMs() {
        int v = 95;
        try { v = mClient.getInt(PREF_NL_SQ_TBASE_MS, v); } catch (Throwable ignored) {}
        if (v < 10) v = 10;
        if (v > 900) v = 900;
        return v;
    }

    public int getNlSquareAMs() {
        int v = 70;
        try { v = mClient.getInt(PREF_NL_SQ_A_MS, v); } catch (Throwable ignored) {}
        if (v < 0) v = 0;
        if (v > 800) v = 800;
        return v;
    }

    public double getNlSquareOmega() {
        double v = getDouble(PREF_NL_SQ_OMEGA, 0.7);
        if (v < 0.01) v = 0.01;
        if (v > 20.0) v = 20.0;
        return v;
    }

    public void setNlSquareAMs(int ms) {
        int v = ms;
        if (v < 0) v = 0;
        if (v > 800) v = 800;
        try { mClient.putInt(PREF_NL_SQ_A_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_SQ_A_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    public void setNlSquareOmega(double omega) {
        double v = omega;
        if (v < 0.01) v = 0.01;
        if (v > 20.0) v = 20.0;
        putDouble(PREF_NL_SQ_OMEGA, v);
    }


    public int getNlMarkovMuMs() {
        int v = 80;
        try { v = mClient.getInt(PREF_NL_MK_MU_MS, v); } catch (Throwable ignored) {}
        if (v < 10) v = 10;
        if (v > 200) v = 200;
        return v;
    }

    public void setNlMarkovMuMs(int ms) {
        int v = ms;
        if (v < 10) v = 10;
        if (v > 200) v = 200;
        try { mClient.putInt(PREF_NL_MK_MU_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_MK_MU_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    public double getNlMarkovRho() {
        double v = getDouble(PREF_NL_MK_RHO, 0.90);
        if (v < 0.0) v = 0.0;
        if (v > 0.99) v = 0.99;
        return v;
    }

    public void setNlMarkovRho(double rho) {
        double v = rho;
        if (v < 0.0) v = 0.0;
        if (v > 0.99) v = 0.99;
        putDouble(PREF_NL_MK_RHO, v);
    }

    public int getNlMarkovSigmaMs() {
        int v = 25;
        try { v = mClient.getInt(PREF_NL_MK_SIGMA_MS, v); } catch (Throwable ignored) {}
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        return v;
    }

    public void setNlMarkovSigmaMs(int ms) {
        int v = ms;
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        try { mClient.putInt(PREF_NL_MK_SIGMA_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_MK_SIGMA_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    public int getNlMarkovTMinMs() {
        int v = 30;
        try { v = mClient.getInt(PREF_NL_MK_TMIN_MS, v); } catch (Throwable ignored) {}
        if (v < 10) v = 10;
        if (v > 100) v = 100;
        return v;
    }

    public void setNlMarkovTMinMs(int ms) {
        int v = ms;
        if (v < 10) v = 10;
        if (v > 100) v = 100;
        try { mClient.putInt(PREF_NL_MK_TMIN_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_MK_TMIN_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    public int getNlMarkovTMaxMs() {
        int v = 450;
        try { v = mClient.getInt(PREF_NL_MK_TMAX_MS, v); } catch (Throwable ignored) {}
        if (v < 100) v = 100;
        if (v > 1000) v = 1000;
        return v;
    }

    public void setNlMarkovTMaxMs(int ms) {
        int v = ms;
        if (v < 100) v = 100;
        if (v > 1000) v = 1000;
        try { mClient.putInt(PREF_NL_MK_TMAX_MS, v); } catch (Throwable ignored) {
            try { mClient.putString(PREF_NL_MK_TMAX_MS, String.valueOf(v)); } catch (Throwable ignored2) {}
        }
    }

    /** Percent in [0, 10]. */
    public double getNlMarkovPThinkPercent() {
        double v = getDouble(PREF_NL_MK_PTHINK_PERCENT, 2.0);
        if (v < 0.0) v = 0.0;
        if (v > 10.0) v = 10.0;
        return v;
    }

    /** Probability in [0, 0.10]. */
    public double getNlMarkovPThinkProbability() {
        return getNlMarkovPThinkPercent() / 100.0;
    }

    public void setNlMarkovPThinkPercent(double percent) {
        double v = percent;
        if (v < 0.0) v = 0.0;
        if (v > 10.0) v = 10.0;
        putDouble(PREF_NL_MK_PTHINK_PERCENT, v);
    }


    /** Streaming mode preference. Default: AUTO. */
    public int getStreamingOutputMode() {
        int v = STREAM_MODE_AUTO;
        try {
            v = mClient.getInt(PREF_STREAMING_OUTPUT_MODE, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_STREAMING_OUTPUT_MODE, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {
            }
        }
        if (v < STREAM_MODE_AUTO || v > STREAM_MODE_TYPEWRITER) v = STREAM_MODE_AUTO;
        return v;
    }

    /** Streaming mode for the current request. Can be overridden via ThreadLocal. */
    public int getStreamingOutputModeForRequest() {
        Integer o = TL_STREAM_MODE_OVERRIDE.get();
        if (o != null) return o;
        return getStreamingOutputMode();
    }


    public void setStreamingOutputMode(int mode) {
        int v = mode;
        if (v < STREAM_MODE_AUTO || v > STREAM_MODE_TYPEWRITER) v = STREAM_MODE_AUTO;
        try {
            mClient.putInt(PREF_STREAMING_OUTPUT_MODE, v);
        } catch (Throwable t) {
            try {
                mClient.putString(PREF_STREAMING_OUTPUT_MODE, String.valueOf(v));
            } catch (Throwable ignored) {
            }
        }
    }

    /** Output render granularity. Default: CHARS. */
    public int getStreamingOutputGranularity() {
        int v = STREAM_GRANULARITY_CHARS;
        try {
            v = mClient.getInt(PREF_STREAMING_OUTPUT_GRANULARITY, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_STREAMING_OUTPUT_GRANULARITY, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {
            }
        }
        if (v < STREAM_GRANULARITY_CHARS || v > STREAM_GRANULARITY_PUNCT) v = STREAM_GRANULARITY_CHARS;
        return v;
    }

    public void setStreamingOutputGranularity(int granularity) {
        int v = granularity;
        if (v < STREAM_GRANULARITY_CHARS || v > STREAM_GRANULARITY_PUNCT) v = STREAM_GRANULARITY_CHARS;
        try {
            mClient.putInt(PREF_STREAMING_OUTPUT_GRANULARITY, v);
        } catch (Throwable t) {
            try {
                mClient.putString(PREF_STREAMING_OUTPUT_GRANULARITY, String.valueOf(v));
            } catch (Throwable ignored) {
            }
        }
    }

    /** If enabled, fallback to non-stream response parsing when stream parsing yields nothing. */
    public boolean getStreamingOutputFallbackNonStreamEnabled() {
        return mClient.getBoolean(PREF_STREAMING_OUTPUT_FALLBACK_NON_STREAM, true);
    }

    public void setStreamingOutputFallbackNonStreamEnabled(boolean enabled) {
        mClient.putBoolean(PREF_STREAMING_OUTPUT_FALLBACK_NON_STREAM, enabled);
    }

    public boolean getInvocationCommandsEnabled() {
        return mClient.getBoolean(PREF_INVOCATION_COMMANDS_ENABLED, true);
    }

    public void setInvocationCommandsEnabled(boolean enabled) {
        mClient.putBoolean(PREF_INVOCATION_COMMANDS_ENABLED, enabled);
    }

    public boolean getInvocationTriggersEnabled() {
        return mClient.getBoolean(PREF_INVOCATION_TRIGGERS_ENABLED, true);
    }

    public void setInvocationTriggersEnabled(boolean enabled) {
        mClient.putBoolean(PREF_INVOCATION_TRIGGERS_ENABLED, enabled);
    }

    /**
     * Bulk-disable all invocation triggers while saving a snapshot of each trigger's enabled state.
     * This allows restoring previous states later (master switch ON).
     */
    public void disableAllInvocationTriggersWithBackup() {
        try {
            // IMPORTANT:
            // Do not overwrite the backup snapshot if the master switch is already OFF.
            // Otherwise, repeated "disable" calls can save an "all disabled" snapshot,
            // and later restore will keep everything disabled.
            boolean masterWasEnabled = true;
            try { masterWasEnabled = getInvocationTriggersEnabled(); } catch (Throwable ignored) {}

            List<ParsePattern> ps = getParsePatterns();
            if (ps == null) {
                setInvocationTriggersEnabled(false);
                return;
            }

            // Already disabled: never overwrite the existing snapshot (if any).
            // If there is no snapshot, we still avoid creating an "all disabled" snapshot.
            if (!masterWasEnabled) {
                boolean changed = false;
                for (ParsePattern p : ps) {
                    if (p == null) continue;
                    if (p.isEnabled()) {
                        p.setEnabled(false);
                        changed = true;
                    }
                }
                if (changed) setParsePatterns(ps);
                setInvocationTriggersEnabled(false);
                return;
            }
            // Save snapshot
            JSONArray arr = new JSONArray();
            for (ParsePattern p : ps) {
                if (p == null || p.getType() == null) continue;
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("type", p.getType().name());
                o.put("enabled", p.isEnabled());
                arr.put(o);
            }
            mClient.putString(PREF_INVOCATION_TRIGGERS_ENABLED_STATES_BACKUP, arr.toString());
            // Disable all
            for (ParsePattern p : ps) {
                if (p == null) continue;
                p.setEnabled(false);
            }
            setParsePatterns(ps);
            // Keep the UI switch state
            setInvocationTriggersEnabled(false);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Restore invocation triggers enabled states from the last saved snapshot (if any).
     * If there is no snapshot, it simply marks the master switch as enabled.
     */
    public void restoreInvocationTriggersFromBackup() {
        try {
            String raw = mClient.getString(PREF_INVOCATION_TRIGGERS_ENABLED_STATES_BACKUP, null);
            List<ParsePattern> ps = getParsePatterns();
            if (ps == null) {
                setInvocationTriggersEnabled(true);
                return;
            }
            java.util.HashMap<String, Boolean> map = new java.util.HashMap<>();
            if (raw != null && !raw.isEmpty()) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    try {
                        org.json.JSONObject o = arr.getJSONObject(i);
                        String type = o.optString("type", null);
                        if (type == null) continue;
                        map.put(type, o.optBoolean("enabled", true));
                    } catch (Throwable ignored) {}
                }
            }

            // Heuristic recovery:
            // If the snapshot exists but ALL saved values are "false", it is very likely the snapshot
            // was overwritten while everything was already disabled (a known bug scenario).
            // Treat this as "no snapshot" so we can recover to a usable state.
            if (!map.isEmpty()) {
                boolean anyTrue = false;
                for (Boolean b : map.values()) {
                    if (b != null && b) { anyTrue = true; break; }
                }
                if (!anyTrue) {
                    map.clear();
                }
            }
            boolean restoredAny = false;
            if (!map.isEmpty()) {
                for (ParsePattern p : ps) {
                    if (p == null || p.getType() == null) continue;
                    Boolean en = map.get(p.getType().name());
                    if (en != null) {
                        p.setEnabled(en);
                        restoredAny = true;
                    }
                }
                if (restoredAny) setParsePatterns(ps);
            }

            // Fallback: if we have no snapshot (or it was empty) and ALL patterns are disabled,
            // re-enable them to avoid leaving the user in a "stuck" state.
            if (!restoredAny) {
                boolean anyEnabled = false;
                for (ParsePattern p : ps) {
                    if (p == null) continue;
                    if (p.isEnabled()) { anyEnabled = true; break; }
                }
                if (!anyEnabled) {
                    for (ParsePattern p : ps) {
                        if (p == null) continue;
                        p.setEnabled(true);
                    }
                    setParsePatterns(ps);
                }
            }
            // Clear snapshot after restore
            mClient.putString(PREF_INVOCATION_TRIGGERS_ENABLED_STATES_BACKUP, "");
            setInvocationTriggersEnabled(true);
        } catch (Throwable ignored) {
            try { setInvocationTriggersEnabled(true); } catch (Throwable ignored2) {}
        }
    }


    private void updateGenerativeAICommands() {
        String raw = mClient.getString(PREF_GEN_AI_COMMANDS, "[]");
        generativeAICommands = Collections.unmodifiableList(Commands.decodeCommands(raw));
    }

    @Override
    public Bundle getConfigBundle() {
        Bundle bundle = new Bundle();
        for (LanguageModel model : LanguageModel.values()) {
            Bundle configBundle = new Bundle();
            for (LanguageModelField field : LanguageModelField.values()) {
                configBundle.putString(field.name, getLanguageModelField(model, field));
            }
            bundle.putBundle(model.name(), configBundle);
        }
        return bundle;
    }

    @Override
    public Bundle getOtherSettings() {
        Bundle otherSettings = new Bundle();
        for (OtherSettingsType type : OtherSettingsType.values()) {
            switch (type.nature) {
                case Boolean:
                    otherSettings.putBoolean(type.name(), (Boolean) getOtherSetting(type));
                    break;
                case String:
                    otherSettings.putString(type.name(), (String) getOtherSetting(type));
                    break;
                case Integer:
                    otherSettings.putInt(type.name(), (Integer) getOtherSetting(type));
                    break;
            }
        }
        return otherSettings;
    }

    public void setOtherSetting(OtherSettingsType type, Object value) {
        String key = String.format(PREF_OTHER_SETTING, type.name());
        switch (type.nature) {
            case Boolean:
                mClient.putBoolean(key, (Boolean) value);
                break;
            case String:
                mClient.putString(key, (String) value);
                break;
            case Integer:
                mClient.putInt(key, (Integer) value);
                break;
        }
    }

    public Object getOtherSetting(OtherSettingsType type) {
        String key = String.format(PREF_OTHER_SETTING, type.name());
        switch (type.nature) {
            case Boolean:
                return mClient.getBoolean(key, (Boolean) type.defaultValue);
            case String:
                return mClient.getString(key, (String) type.defaultValue);
            case Integer:
                return mClient.getInt(key, (Integer) type.defaultValue);
            default:
                return type.defaultValue;
        }
    }

    public Boolean getEnableLogs() {
        return (Boolean) getOtherSetting(OtherSettingsType.EnableLogs);
    }

    public Boolean getEnableExternalInternet() {
        return (Boolean) getOtherSetting(OtherSettingsType.EnableExternalInternet);
    }

    public void setSearchEngine(String searchEngine) {
        setOtherSetting(OtherSettingsType.SearchEngine, searchEngine);
    }

    public String getSearchEngine() {
        return (String) getOtherSetting(OtherSettingsType.SearchEngine);
    }

    public String getSearchUrl(String query) {
        String engine = getSearchEngine();
        return buildSearchUrl(engine, query);
    }

    // Update Settings
    public boolean getUpdateCheckEnabled() {
        return (Boolean) getOtherSetting(OtherSettingsType.UpdateCheckEnabled);
    }

    public void setUpdateCheckEnabled(boolean enabled) {
        setOtherSetting(OtherSettingsType.UpdateCheckEnabled, enabled);
    }

    public int getUpdateCheckInterval() {
        return (Integer) getOtherSetting(OtherSettingsType.UpdateCheckInterval);
    }

    public void setUpdateCheckInterval(int hours) {
        setOtherSetting(OtherSettingsType.UpdateCheckInterval, hours);
    }

    public String getUpdateDownloadPath() {
        return (String) getOtherSetting(OtherSettingsType.UpdateDownloadPath);
    }

    // -----------------------------
    // Quick Jump (deep-link templates)
    // -----------------------------
    public String getQuickJumpConfig() {
        return mClient.getString(PREF_QUICK_JUMP_CONFIG, "");
    }

    public void setQuickJumpConfig(String value) {
        if (value == null) value = "";
        mClient.putString(PREF_QUICK_JUMP_CONFIG, value);
    }

    public void setUpdateDownloadPath(String path) {
        setOtherSetting(OtherSettingsType.UpdateDownloadPath, path);
    }

    public static String getSearchUrlFromKGPT(Context context, String query) {
        // IMPORTANT:
        // This method is used from places where SPManager may not be fully initialized
        // (e.g. Xposed / different process). Do NOT hardcode an engine here.
        // Always try to read the latest preference from the shared ConfigProvider.
        String engine = "duckduckgo";
        try {
            if (context != null) {
                // Same key used by setOtherSetting(OtherSettingsType.SearchEngine, ...)
                tn.eluea.kgpt.provider.ConfigClient client = new tn.eluea.kgpt.provider.ConfigClient(context);
                String key = String.format(PREF_OTHER_SETTING, tn.eluea.kgpt.settings.OtherSettingsType.SearchEngine.name());
                String v = client.getString(key, "duckduckgo");
                if (v != null && !v.trim().isEmpty()) engine = v.trim();
            } else if (SPManager.isReady()) {
                String v = SPManager.getInstance().getSearchEngine();
                if (v != null && !v.trim().isEmpty()) engine = v.trim();
            }
        } catch (Throwable ignored) {
            // fall back to default
        }
        return buildSearchUrl(engine, query);
    }

    private static String buildSearchUrl(String engine, String query) {
        String encodedQuery;
        try {
            encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
        } catch (Exception e) {
            encodedQuery = query;
        }

        switch (engine) {
            case "google":
                return "https://www.google.com/search?q=" + encodedQuery;
            case "bing":
                return "https://www.bing.com/search?q=" + encodedQuery;
            case "yahoo":
                return "https://search.yahoo.com/search?p=" + encodedQuery;
            case "yandex":
                return "https://yandex.com/search/?text=" + encodedQuery;
            case "brave":
                return "https://search.brave.com/search?q=" + encodedQuery;
            case "ecosia":
                return "https://www.ecosia.org/search?q=" + encodedQuery;
            case "qwant":
                return "https://www.qwant.com/?q=" + encodedQuery;
            case "startpage":
                return "https://www.startpage.com/do/dsearch?query=" + encodedQuery;
            case "perplexity":
                return "https://www.perplexity.ai/?q=" + encodedQuery;
            case "phind":
                return "https://www.phind.com/search?q=" + encodedQuery;
            case "duckduckgo":
            default:
                return "https://duckduckgo.com/?q=" + encodedQuery;
        }
    }

    /**
     * Register a listener for config changes
     */
    public void registerConfigChangeListener(ConfigClient.OnConfigChangeListener listener) {
        mClient.registerGlobalListener(listener);
    }

    /**
     * Get the underlying ConfigClient for advanced usage
     */
    public ConfigClient getConfigClient() {
        return mClient;
    }

    public boolean isAmoledTheme() {
        // Use raw key "amoled_mode" to match SettingsFragment implementation
        return mClient.getBoolean("amoled_mode", false);
    }

    // ===== AI Clipboard Groups =====
    private static final String PREF_AI_CLIPBOARD_GROUPS_JSON = "ai_clipboard.groups.json.v1";
    private static final int AI_CLIPBOARD_MAX_GROUPS = 10;

    /** Returns the user-created clipboard groups (max 10). */
    public List<String> getAiClipboardGroups() {
        String raw = mClient.getString(PREF_AI_CLIPBOARD_GROUPS_JSON, "[]");
        if (raw == null || raw.trim().isEmpty()) raw = "[]";

        ArrayList<String> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();

        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (s == null) continue;
                String v = s.trim();
                if (v.isEmpty()) continue;
                String key = v.toLowerCase();
                if (seen.add(key)) {
                    out.add(v);
                    if (out.size() >= AI_CLIPBOARD_MAX_GROUPS) break;
                }
            }
        } catch (JSONException ignored) {}

        return out;
    }

    /** Overwrites the user-created clipboard groups (max 10). */
    public void setAiClipboardGroups(List<String> groups) {
        JSONArray arr = new JSONArray();
        HashSet<String> seen = new HashSet<>();

        if (groups != null) {
            for (String s : groups) {
                if (s == null) continue;
                String v = s.trim();
                if (v.isEmpty()) continue;
                String key = v.toLowerCase();
                if (!seen.add(key)) continue;
                arr.put(v);
                if (arr.length() >= AI_CLIPBOARD_MAX_GROUPS) break;
            }
        }

        mClient.putString(PREF_AI_CLIPBOARD_GROUPS_JSON, arr.toString());
    }



    // =============================
    // Conversation memory & normal model thinking (Labs)
    // =============================
    private static final String PREF_CONVERSATION_MEMORY_LEVEL = "conversation_memory_level_v1";

    // Legacy key kept only for one-time migration.
    private static final String PREF_THINKING_DEPTH_LEVEL_LEGACY = "thinking_depth_level_v1";

    private static final String PREF_NORMAL_MODEL_THINKING = "normal_model_thinking_v1";
    private static final float NORMAL_MODEL_THINKING_DEFAULT = 0.7f;

    // Reasoning model thinking (推理模型思考)
    private static final String PREF_REASONING_MODEL_THINKING = "reasoning_model_thinking_v1";
    public static final int REASONING_MODEL_THINKING_DIVERGENT = 0;
    public static final int REASONING_MODEL_THINKING_CONVERGENT = 1;
    public static final int REASONING_MODEL_THINKING_AUTO = 2; // default
    public static final int REASONING_MODEL_THINKING_LOW = 3;
    public static final int REASONING_MODEL_THINKING_MEDIUM = 4;
    public static final int REASONING_MODEL_THINKING_HIGH = 5;

    // =============================
    // Per-subModel capability cache (auto-learned)
    // =============================
    // Tri-state: key absent = unknown, 0 = false, 1 = true
    private static final String PREF_MODEL_CAP_TEMP = "model_cap.temp.%s.%s";      // provider, subModelKey
    private static final String PREF_MODEL_CAP_REASON = "model_cap.reason.%s.%s";  // provider, subModelKey
    // Integer cache: key absent = unknown, value > 0 = safe max output tokens to use (auto-learned)
    private static final String PREF_MODEL_CAP_MAXTOK = "model_cap.maxtok.%s.%s";  // provider, subModelKey

    private static String sanitizeSubModelKey(String subModel) {
        if (subModel == null) return "";
        String s = subModel.trim();
        if (s.isEmpty()) return "";
        // SharedPreferences/Provider keys are strings, but keep it conservative for DB/provider backends.
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String capKey(String fmt, LanguageModel provider, String subModel) {
        String p = (provider == null ? "" : provider.name());
        String m = sanitizeSubModelKey(subModel);
        return String.format(fmt, p, m);
    }

    /**
     * Returns cached support for temperature/sampling parameters.
     * @return Boolean.TRUE/Boolean.FALSE if known, null if unknown.
     */
    public Boolean getCachedSupportsTemperature(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_TEMP, provider, subModel);
            if (!mClient.contains(key)) return null;
            int v = mClient.getInt(key, 0);
            return v == 1;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void setCachedSupportsTemperature(LanguageModel provider, String subModel, boolean supported) {
        try {
            String key = capKey(PREF_MODEL_CAP_TEMP, provider, subModel);
            mClient.putInt(key, supported ? 1 : 0);
        } catch (Throwable ignored) {}
    }

    /**
     * Returns cached support for reasoning-thinking controls.
     * @return Boolean.TRUE/Boolean.FALSE if known, null if unknown.
     */
    public Boolean getCachedSupportsReasoningThinking(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_REASON, provider, subModel);
            if (!mClient.contains(key)) return null;
            int v = mClient.getInt(key, 0);
            return v == 1;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void setCachedSupportsReasoningThinking(LanguageModel provider, String subModel, boolean supported) {
        try {
            String key = capKey(PREF_MODEL_CAP_REASON, provider, subModel);
            mClient.putInt(key, supported ? 1 : 0);
        } catch (Throwable ignored) {}
    }

    /**
     * Returns cached safe max output tokens for the given model.
     * @return Integer (>=1) if known, null if unknown.
     */
    public Integer getCachedSafeMaxTokens(LanguageModel provider, String subModel) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK, provider, subModel);
            if (!mClient.contains(key)) return null;
            int v = mClient.getInt(key, 0);

            // Guard: older builds could accidentally cache HTTP status codes (400/422/429/5xx) as token limits
            // because error messages often contain those numbers.
            if (v == 400 || v == 401 || v == 403 || v == 404 || v == 408
                    || v == 413 || v == 414 || v == 422 || v == 429
                    || v == 500 || v == 502 || v == 503 || v == 504) {
                try { mClient.putInt(key, 0); } catch (Throwable ignored2) {}
                return null;
            }

            return v > 0 ? v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Cache a safe max output tokens value for the given model.
     * Any value <= 0 clears the cache entry.
     */
    public void setCachedSafeMaxTokens(LanguageModel provider, String subModel, int safeMaxTokens) {
        try {
            String key = capKey(PREF_MODEL_CAP_MAXTOK, provider, subModel);
            int v = safeMaxTokens;
            // ConfigClient has no remove(); store 0 to represent "unknown/cleared".
            if (v <= 0) v = 0;
            mClient.putInt(key, v);
        } catch (Throwable ignored) {}
    }

    /** 0..10. 0 = stateless (no context), 1..10 = include that many previous turns. Default = 1. */
    public int getConversationMemoryLevel() {
        int v = 1;
        try {
            v = mClient.getInt(PREF_CONVERSATION_MEMORY_LEVEL, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_CONVERSATION_MEMORY_LEVEL, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < 0) v = 0;
        if (v > 10) v = 10;
        return v;
    }

    public void setConversationMemoryLevel(int level) {
        int v = level;
        if (v < 0) v = 0;
        if (v > 10) v = 10;
        try {
            mClient.putInt(PREF_CONVERSATION_MEMORY_LEVEL, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_CONVERSATION_MEMORY_LEVEL, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /**
     * Normal model thinking (temperature-like). Range: 0.0 .. 1.8. Default = 0.7.
     * Stored as float.
     */
    public float getNormalModelThinking() {
        // One-time migration: if new key is absent but legacy key exists, map 0..10 -> 0.0..2.0.
        try {
            boolean hasNew = mClient.contains(PREF_NORMAL_MODEL_THINKING);
            if (!hasNew && mClient.contains(PREF_THINKING_DEPTH_LEVEL_LEGACY)) {
                int legacy = 1;
                try { legacy = mClient.getInt(PREF_THINKING_DEPTH_LEVEL_LEGACY, 1); } catch (Throwable ignored) {}
                if (legacy < 0) legacy = 0;
                if (legacy > 10) legacy = 10;
                float mapped = legacy / 5.0f; // 0..2
                mapped = round1(clamp(mapped, 0.0f, 1.8f));
                setNormalModelThinking(mapped);
                return mapped;
            }
        } catch (Throwable ignored) {}

        float v = NORMAL_MODEL_THINKING_DEFAULT;
        try {
            v = mClient.getFloat(PREF_NORMAL_MODEL_THINKING, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_NORMAL_MODEL_THINKING, null);
                if (s != null) v = Float.parseFloat(s.trim());
            } catch (Throwable ignored2) {}
        }
        v = clamp(v, 0.0f, 1.8f);
        v = round1(v);
        return v;
    }

    public void setNormalModelThinking(float value) {
        float v = clamp(value, 0.0f, 1.8f);
        v = round1(v);
        try {
            mClient.putFloat(PREF_NORMAL_MODEL_THINKING, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_NORMAL_MODEL_THINKING, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /**
     * Reasoning model thinking mode. Range: 0..5. Default = AUTO.
     */
    public int getReasoningModelThinkingMode() {
        int v = REASONING_MODEL_THINKING_AUTO;
        try {
            v = mClient.getInt(PREF_REASONING_MODEL_THINKING, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_REASONING_MODEL_THINKING, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < REASONING_MODEL_THINKING_DIVERGENT) v = REASONING_MODEL_THINKING_AUTO;
        if (v > REASONING_MODEL_THINKING_HIGH) v = REASONING_MODEL_THINKING_AUTO;
        return v;
    }

    public void setReasoningModelThinkingMode(int mode) {
        int v = mode;
        if (v < REASONING_MODEL_THINKING_DIVERGENT) v = REASONING_MODEL_THINKING_AUTO;
        if (v > REASONING_MODEL_THINKING_HIGH) v = REASONING_MODEL_THINKING_AUTO;
        try {
            mClient.putInt(PREF_REASONING_MODEL_THINKING, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_REASONING_MODEL_THINKING, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    private static float clamp(float v, float min, float max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static float round1(float v) {
        return Math.round(v * 10.0f) / 10.0f;
    }

    // =============================
    // Labs: Output length (Max tokens)
    // =============================
    private static final String PREF_MAX_TOKENS_PRESET = "max_tokens_preset_v1";
    private static final String PREF_MAX_TOKENS_LIMIT = "max_tokens_limit_v2";
    public static final int MAX_TOKENS_PRESET_SHORT = 0;
    public static final int MAX_TOKENS_PRESET_MEDIUM = 1;
    public static final int MAX_TOKENS_PRESET_LONG = 2;

    /** Legacy preset: 0=Short, 1=Medium, 2=Long. Default=Medium. */
    public int getMaxTokensPreset() {
        int v = MAX_TOKENS_PRESET_MEDIUM;
        try {
            v = mClient.getInt(PREF_MAX_TOKENS_PRESET, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_MAX_TOKENS_PRESET, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < MAX_TOKENS_PRESET_SHORT) v = MAX_TOKENS_PRESET_MEDIUM;
        if (v > MAX_TOKENS_PRESET_LONG) v = MAX_TOKENS_PRESET_MEDIUM;
        return v;
    }

    public void setMaxTokensPreset(int preset) {
        int v = preset;
        if (v < MAX_TOKENS_PRESET_SHORT) v = MAX_TOKENS_PRESET_SHORT;
        if (v > MAX_TOKENS_PRESET_LONG) v = MAX_TOKENS_PRESET_LONG;
        try {
            mClient.putInt(PREF_MAX_TOKENS_PRESET, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_MAX_TOKENS_PRESET, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** Legacy mapping for presets (recommended defaults): Short=512, Medium=1024, Long=2048. */
    private int getMaxTokensFromPresetLegacy() {
        int p = getMaxTokensPreset();
        switch (p) {
            case MAX_TOKENS_PRESET_SHORT:
                return 512;
            case MAX_TOKENS_PRESET_LONG:
                return 2048;
            case MAX_TOKENS_PRESET_MEDIUM:
            default:
                return 1024;
        }
    }

    /**
     * Max tokens limit (integer).
     *
     * Backward compatible: if limit is not set, falls back to the legacy preset mapping.
     */
    public int getMaxTokensLimit() {
        int v = 0;
        try {
            v = mClient.getInt(PREF_MAX_TOKENS_LIMIT, 0);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_MAX_TOKENS_LIMIT, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v <= 0) v = getMaxTokensFromPresetLegacy();
        return v;
    }

    public void setMaxTokensLimit(int tokens) {
        int v = tokens;
        if (v <= 0) v = 1024;
        try {
            mClient.putInt(PREF_MAX_TOKENS_LIMIT, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_MAX_TOKENS_LIMIT, String.valueOf(v)); } catch (Throwable ignored) {}
        }
        // Keep legacy preset loosely in sync for older code paths.
        if (v == 512) setMaxTokensPreset(MAX_TOKENS_PRESET_SHORT);
        else if (v == 1024) setMaxTokensPreset(MAX_TOKENS_PRESET_MEDIUM);
        else if (v == 2048) setMaxTokensPreset(MAX_TOKENS_PRESET_LONG);
    }

    /** Backward-compat alias: previously returned the legacy preset mapping. */
    public int getMaxTokensFromPreset() {
        return getMaxTokensLimit();
    }


    // =============================
    // Labs: Auto summarize older context (memory compression)
    // =============================
    private static final String PREF_AUTO_SUMMARIZE_OLD_CONTEXT = "auto_summarize_old_context_v1";

    public boolean getAutoSummarizeOldContextEnabled() {
        // Default ON: improves stability & reduces token usage when memory is high.
        return mClient.getBoolean(PREF_AUTO_SUMMARIZE_OLD_CONTEXT, true);
    }

    public void setAutoSummarizeOldContextEnabled(boolean enabled) {
        mClient.putBoolean(PREF_AUTO_SUMMARIZE_OLD_CONTEXT, enabled);
    }

    // =============================
    // Labs: Auto downgrade strategy (Stream / BaseURL / Model)
    // =============================
    private static final String PREF_AUTO_DOWNGRADE_FLAGS = "auto_downgrade_flags_v1";
    private static final String PREF_AUTO_DOWNGRADE_BACKUP_BASEURL = "auto_downgrade_backup_baseurl_v1";
    private static final String PREF_AUTO_DOWNGRADE_BACKUP_MODEL = "auto_downgrade_backup_model_v1";

    public static final int DOWNGRADE_FLAG_STREAM = 1;
    public static final int DOWNGRADE_FLAG_BASEURL = 2;
    public static final int DOWNGRADE_FLAG_MODEL = 4;

    /** Bitmask of DOWNGRADE_FLAG_* . Default enables all (7). */
    public int getAutoDowngradeFlags() {
        int v = (DOWNGRADE_FLAG_STREAM | DOWNGRADE_FLAG_BASEURL | DOWNGRADE_FLAG_MODEL);
        try {
            v = mClient.getInt(PREF_AUTO_DOWNGRADE_FLAGS, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_AUTO_DOWNGRADE_FLAGS, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < 0) v = 0;
        if (v > 7) v = 7;
        return v;
    }

    public void setAutoDowngradeFlags(int flags) {
        int v = flags;
        if (v < 0) v = 0;
        if (v > 7) v = 7;
        try {
            mClient.putInt(PREF_AUTO_DOWNGRADE_FLAGS, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_AUTO_DOWNGRADE_FLAGS, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    public String getAutoDowngradeBackupBaseUrl() {
        try {
            String s = mClient.getString(PREF_AUTO_DOWNGRADE_BACKUP_BASEURL, "");
            return s == null ? "" : s.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public void setAutoDowngradeBackupBaseUrl(String baseUrl) {
        String v = baseUrl == null ? "" : baseUrl.trim();
        mClient.putString(PREF_AUTO_DOWNGRADE_BACKUP_BASEURL, v);
    }

    public LanguageModel getAutoDowngradeBackupModel() {
        try {
            String s = mClient.getString(PREF_AUTO_DOWNGRADE_BACKUP_MODEL, "");
            if (s == null) return null;
            s = s.trim();
            if (s.isEmpty()) return null;
            try {
                return LanguageModel.valueOf(s);
            } catch (Throwable ignored) {
                return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void setAutoDowngradeBackupModel(LanguageModel model) {
        String v = (model == null) ? "" : model.name();
        mClient.putString(PREF_AUTO_DOWNGRADE_BACKUP_MODEL, v);
    }

    // =============================
    // Labs: Request cancel / concurrency policy
    // =============================
    private static final String PREF_REQUEST_CONCURRENCY_POLICY = "request_concurrency_policy_v1";
    public static final int REQUEST_POLICY_CANCEL_PREVIOUS = 0;
    public static final int REQUEST_POLICY_IGNORE_NEW = 1;
    public static final int REQUEST_POLICY_QUEUE_LATEST = 2;

    /** 0=Cancel previous, 1=Ignore new, 2=Queue latest. Default=Cancel previous. */
    public int getRequestConcurrencyPolicy() {
        int v = REQUEST_POLICY_CANCEL_PREVIOUS;
        try {
            v = mClient.getInt(PREF_REQUEST_CONCURRENCY_POLICY, v);
        } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_REQUEST_CONCURRENCY_POLICY, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < REQUEST_POLICY_CANCEL_PREVIOUS) v = REQUEST_POLICY_CANCEL_PREVIOUS;
        if (v > REQUEST_POLICY_QUEUE_LATEST) v = REQUEST_POLICY_CANCEL_PREVIOUS;
        return v;
    }

    public void setRequestConcurrencyPolicy(int policy) {
        int v = policy;
        if (v < REQUEST_POLICY_CANCEL_PREVIOUS) v = REQUEST_POLICY_CANCEL_PREVIOUS;
        if (v > REQUEST_POLICY_QUEUE_LATEST) v = REQUEST_POLICY_QUEUE_LATEST;
        try {
            mClient.putInt(PREF_REQUEST_CONCURRENCY_POLICY, v);
        } catch (Throwable t) {
            try { mClient.putString(PREF_REQUEST_CONCURRENCY_POLICY, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }


    // =============================
    // Labs: Generating Content placeholder (pre/post keywords) + haptic on reply
    // =============================
    private static final String PREF_GENERATING_CONTENT_ENABLED = "generating_content_enabled_v1";
    /** Empty => use the localized default resource string. */
    private static final String PREF_GENERATING_CONTENT_PREFIX = "generating_content_prefix_v1";
    /** Optional trailing keyword inserted AFTER the cursor (so AI output is inserted BEFORE it). */
    private static final String PREF_GENERATING_CONTENT_SUFFIX = "generating_content_suffix_v1";
    private static final String PREF_AI_REPLY_VIBRATE = "ai_reply_vibrate_v1";

    /** Default ON to preserve existing behavior. */
    public boolean getGeneratingContentEnabled() {
        return mClient.getBoolean(PREF_GENERATING_CONTENT_ENABLED, true);
    }

    public void setGeneratingContentEnabled(boolean enabled) {
        mClient.putBoolean(PREF_GENERATING_CONTENT_ENABLED, enabled);
    }

    /** Returns custom prefix; empty means "use default". */
    public String getGeneratingContentPrefix() {
        String v = null;
        try {
            v = mClient.getString(PREF_GENERATING_CONTENT_PREFIX, "");
        } catch (Throwable ignored) {}
        if (v == null) v = "";
        return v;
    }

    public void setGeneratingContentPrefix(String prefix) {
        String v = prefix == null ? "" : prefix;
        mClient.putString(PREF_GENERATING_CONTENT_PREFIX, v);
    }

    /** Returns custom suffix inserted after cursor; empty means disabled. */
    public String getGeneratingContentSuffix() {
        String v = null;
        try {
            v = mClient.getString(PREF_GENERATING_CONTENT_SUFFIX, "");
        } catch (Throwable ignored) {}
        if (v == null) v = "";
        return v;
    }

    public void setGeneratingContentSuffix(String suffix) {
        String v = suffix == null ? "" : suffix;
        mClient.putString(PREF_GENERATING_CONTENT_SUFFIX, v);
    }

    // Backward-compatible alias (some UI components still call this name)
    public String getGeneratingContentSuffixAfterCursor() {
        return getGeneratingContentSuffix();
    }

    // Backward-compatible alias (some UI components still call this name)
    public void setGeneratingContentSuffixAfterCursor(String suffix) {
        setGeneratingContentSuffix(suffix);
    }


    /** Default OFF (avoid unexpected haptics). */
    public boolean getAiReplyVibrateEnabled() {
        return mClient.getBoolean(PREF_AI_REPLY_VIBRATE, false);
    }

    public void setAiReplyVibrateEnabled(boolean enabled) {
        mClient.putBoolean(PREF_AI_REPLY_VIBRATE, enabled);
    }


    // ===== Generating Content: Toast / Sound / Vibration strength+frequency / Marker style =====
    private static final String PREF_GEN_TOAST_ENABLED = "generating_content_toast_enabled_v1";
    private static final String PREF_GEN_COMPLETE_SOUND = "generating_content_complete_sound_v1";
    private static final String PREF_GEN_VIB_INTENSITY = "ai_reply_vibrate_intensity_v1";
    private static final String PREF_GEN_VIB_FREQUENCY = "ai_reply_vibrate_frequency_v1";
    private static final String PREF_GEN_MARKER_STYLE = "generating_content_marker_style_v1";
    private static final String PREF_GEN_MARKER_COLOR = "generating_content_marker_color_v1";
    private static final String PREF_GEN_MARKER_ANIM_LEN = "generating_content_marker_anim_len_v1";
    private static final String PREF_GEN_MARKER_ANIM_SPEED = "generating_content_marker_anim_speed_v1";

    public static final int GEN_SOUND_NONE = 0;
    public static final int GEN_SOUND_SYSTEM_NOTIFICATION = 1;
    public static final int GEN_SOUND_BEEP = 2;
    public static final int GEN_SOUND_CLICK = 3;

    public static final int GEN_MARKER_STYLE_PLAIN = 0;
    public static final int GEN_MARKER_STYLE_COLOR_TAG = 1;
    public static final int GEN_MARKER_STYLE_RAINBOW_ANIM = 2;
    public static final int GEN_MARKER_STYLE_TEXT_RAINBOW_ANIM = 3;

    public static final int GEN_MARKER_COLOR_BLUE = 0;
    public static final int GEN_MARKER_COLOR_RED = 1;
    public static final int GEN_MARKER_COLOR_GREEN = 2;
    public static final int GEN_MARKER_COLOR_YELLOW = 3;
    public static final int GEN_MARKER_COLOR_PURPLE = 4;
    public static final int GEN_MARKER_COLOR_RANDOM = 5;

    /** Toast提示：默认开启。 */
    public boolean getGeneratingContentToastEnabled() {
        return mClient.getBoolean(PREF_GEN_TOAST_ENABLED, true);
    }

    public void setGeneratingContentToastEnabled(boolean enabled) {
        mClient.putBoolean(PREF_GEN_TOAST_ENABLED, enabled);
    }

    /** 回复完成提示音：默认无。 */
    public int getGeneratingContentCompleteSound() {
        int v = GEN_SOUND_NONE;
        try { v = mClient.getInt(PREF_GEN_COMPLETE_SOUND, v); } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_GEN_COMPLETE_SOUND, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < GEN_SOUND_NONE || v > GEN_SOUND_CLICK) v = GEN_SOUND_NONE;
        return v;
    }

    public void setGeneratingContentCompleteSound(int soundType) {
        int v = soundType;
        if (v < GEN_SOUND_NONE) v = GEN_SOUND_NONE;
        if (v > GEN_SOUND_CLICK) v = GEN_SOUND_CLICK;
        try { mClient.putInt(PREF_GEN_COMPLETE_SOUND, v); } catch (Throwable t) {
            try { mClient.putString(PREF_GEN_COMPLETE_SOUND, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** 震动强度（0-100）。默认65。 */
    public int getAiReplyVibrateIntensityPercent() {
        int v = 65;
        try { v = mClient.getInt(PREF_GEN_VIB_INTENSITY, v); } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_GEN_VIB_INTENSITY, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        return v;
    }

    public void setAiReplyVibrateIntensityPercent(int percent) {
        int v = percent;
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        try { mClient.putInt(PREF_GEN_VIB_INTENSITY, v); } catch (Throwable t) {
            try { mClient.putString(PREF_GEN_VIB_INTENSITY, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** 震动频率（0-100）。默认70。 */
    public int getAiReplyVibrateFrequencyPercent() {
        int v = 70;
        try { v = mClient.getInt(PREF_GEN_VIB_FREQUENCY, v); } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_GEN_VIB_FREQUENCY, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        return v;
    }

    public void setAiReplyVibrateFrequencyPercent(int percent) {
        int v = percent;
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        try { mClient.putInt(PREF_GEN_VIB_FREQUENCY, v); } catch (Throwable t) {
            try { mClient.putString(PREF_GEN_VIB_FREQUENCY, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** 回复中标记样式。默认纯文本。 */
    public int getGeneratingContentMarkerStyle() {
        int v = GEN_MARKER_STYLE_PLAIN;
        try { v = mClient.getInt(PREF_GEN_MARKER_STYLE, v); } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_GEN_MARKER_STYLE, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < GEN_MARKER_STYLE_PLAIN || v > GEN_MARKER_STYLE_TEXT_RAINBOW_ANIM) v = GEN_MARKER_STYLE_PLAIN;
        return v;
    }

    public void setGeneratingContentMarkerStyle(int style) {
        int v = style;
        if (v < GEN_MARKER_STYLE_PLAIN) v = GEN_MARKER_STYLE_PLAIN;
        if (v > GEN_MARKER_STYLE_TEXT_RAINBOW_ANIM) v = GEN_MARKER_STYLE_TEXT_RAINBOW_ANIM;
        try { mClient.putInt(PREF_GEN_MARKER_STYLE, v); } catch (Throwable t) {
            try { mClient.putString(PREF_GEN_MARKER_STYLE, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** 色块颜色（用于色块前缀/随机/彩虹动画）。默认蓝。 */
    public int getGeneratingContentMarkerColor() {
        int v = GEN_MARKER_COLOR_BLUE;
        try { v = mClient.getInt(PREF_GEN_MARKER_COLOR, v); } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_GEN_MARKER_COLOR, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < GEN_MARKER_COLOR_BLUE || v > GEN_MARKER_COLOR_RANDOM) v = GEN_MARKER_COLOR_BLUE;
        return v;
    }

    public void setGeneratingContentMarkerColor(int color) {
        int v = color;
        if (v < GEN_MARKER_COLOR_BLUE) v = GEN_MARKER_COLOR_BLUE;
        if (v > GEN_MARKER_COLOR_RANDOM) v = GEN_MARKER_COLOR_RANDOM;
        try { mClient.putInt(PREF_GEN_MARKER_COLOR, v); } catch (Throwable t) {
            try { mClient.putString(PREF_GEN_MARKER_COLOR, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }


    /** 彩虹动画 marker 长度（格数），仅对 彩虹动画 样式生效。默认 6（可选 3/6/10）。 */
    public int getGeneratingContentMarkerAnimLength() {
        int v = 6;
        try { v = mClient.getInt(PREF_GEN_MARKER_ANIM_LEN, v); } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_GEN_MARKER_ANIM_LEN, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v != 3 && v != 6 && v != 10) v = 6;
        return v;
    }

    public void setGeneratingContentMarkerAnimLength(int len) {
        int v = len;
        if (v != 3 && v != 6 && v != 10) v = 6;
        try { mClient.putInt(PREF_GEN_MARKER_ANIM_LEN, v); } catch (Throwable t) {
            try { mClient.putString(PREF_GEN_MARKER_ANIM_LEN, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

    /** 彩虹动画 marker 速度（0-100），越大越快。默认 70。 */
    public int getGeneratingContentMarkerAnimSpeedPercent() {
        int v = 70;
        try { v = mClient.getInt(PREF_GEN_MARKER_ANIM_SPEED, v); } catch (Throwable ignored) {
            try {
                String s = mClient.getString(PREF_GEN_MARKER_ANIM_SPEED, null);
                if (s != null) v = Integer.parseInt(s.trim());
            } catch (Throwable ignored2) {}
        }
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        return v;
    }

    public void setGeneratingContentMarkerAnimSpeedPercent(int percent) {
        int v = percent;
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        try { mClient.putInt(PREF_GEN_MARKER_ANIM_SPEED, v); } catch (Throwable t) {
            try { mClient.putString(PREF_GEN_MARKER_ANIM_SPEED, String.valueOf(v)); } catch (Throwable ignored) {}
        }
    }

}