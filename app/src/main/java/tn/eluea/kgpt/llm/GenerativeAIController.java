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
package tn.eluea.kgpt.llm;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.listener.GenerativeAIListener;
import tn.eluea.kgpt.llm.client.LanguageModelClient;
import tn.eluea.kgpt.listener.ConfigChangeListener;
import tn.eluea.kgpt.llm.internet.InternetProvider;
import tn.eluea.kgpt.llm.internet.SimpleInternetProvider;
import tn.eluea.kgpt.llm.publisher.SimpleStringPublisher;
import tn.eluea.kgpt.roles.RoleManager;
import tn.eluea.kgpt.llm.service.ExternalInternetProvider;
import tn.eluea.kgpt.settings.OtherSettingsType;
import tn.eluea.kgpt.ui.UiInteractor;

public class GenerativeAIController implements ConfigChangeListener {
    private static final String TAG = "KGPT-GenAI";
    private LanguageModelClient mModelClient = null;

    private final SPManager mSPManager;
    private final UiInteractor mInteractor;
    private ExternalInternetProvider mExternalClient = null;

    private List<GenerativeAIListener> mListeners = new ArrayList<>();
    private InternetProvider mInternetProvider = new SimpleInternetProvider();

    /**
     * All model/network work must run off the IME main thread.
     * Many providers in this codebase use blocking HttpURLConnection calls.
     * Running them on the input method (keyboard) main thread can ANR/kill the IME ("keyboard crash").
     */
    private static final java.util.concurrent.ExecutorService REQUEST_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "KGPT-LLM");
                t.setDaemon(true);
                return t;
            });



    // =============================
    // Request lifecycle (cancel / concurrency / auto-downgrade)
    // =============================
    private final Object mRequestLock = new Object();
    private volatile Subscription mCurrentSubscription = null;
    private volatile boolean mRequestInFlight = false;
    private volatile PendingRequest mPendingRequest = null;

    private final java.util.concurrent.atomic.AtomicInteger mRequestSeq = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile int mActiveRequestId = 0;

    private static final class PendingRequest {
        final String prompt;
        final String systemMessage;
        final String roleIdOverride;
        final boolean useConversationMemory;

        PendingRequest(String prompt, String systemMessage, String roleIdOverride, boolean useConversationMemory) {
            this.prompt = prompt;
            this.systemMessage = systemMessage;
            this.roleIdOverride = roleIdOverride;
            this.useConversationMemory = useConversationMemory;
        }
    }

    private static final class Attempt {
        final LanguageModelClient client;
        final Integer streamModeOverride; // null = no override
        final String baseUrlOverride;     // null/empty = no override

        Attempt(LanguageModelClient client, Integer streamModeOverride, String baseUrlOverride) {
            this.client = client;
            this.streamModeOverride = streamModeOverride;
            this.baseUrlOverride = baseUrlOverride;
        }
    }

    /**
     * Per-user-request auto-downgrade state.
     * Used to retry once when the provider rejects certain optional parameters.
     */
    private static final class ParamDowngradeState {
        boolean retriedSamplingParams = false;
        boolean retriedReasoning = false;
        boolean retriedMaxTokens = false;
    }

    public GenerativeAIController() {
        mSPManager = SPManager.getInstance();
        mInteractor = UiInteractor.getInstance();

        mInteractor.registerConfigChangeListener(this);
        if (mSPManager.hasLanguageModel()) {
            setModel(mSPManager.getLanguageModel());
        } else {
            mModelClient = LanguageModelClient.forModel(LanguageModel.Gemini);
        }

        updateInternetProvider();
    }

    private void updateInternetProvider() {
        updateInternetProvider(null);
    }

    private void updateInternetProvider(Boolean enableExternalInternet) {
        // Always use SimpleInternetProvider for now
        // ExternalInternetProvider has issues on Android 12+
        tn.eluea.kgpt.util.Logger.log("Using SimpleInternetProvider");
        mInternetProvider = new SimpleInternetProvider();

        if (mModelClient != null) {
            mModelClient.setInternetProvider(mInternetProvider);
        }
    }

    public boolean needModelClient() {
        return mModelClient == null;
    }

    public boolean needApiKey() {
        return mModelClient.getApiKey() == null || mModelClient.getApiKey().isEmpty();
    }

    private void setModel(LanguageModel model) {
        tn.eluea.kgpt.util.Logger.log("setModel " + model.label);
        mModelClient = LanguageModelClient.forModel(model);
        for (LanguageModelField field : LanguageModelField.values()) {
            mModelClient.setField(field, mSPManager.getLanguageModelField(model, field));
        }
        mModelClient.setInternetProvider(mInternetProvider);
    }

    @Override
    public void onLanguageModelChange(LanguageModel model) {
        if (mModelClient == null || mModelClient.getLanguageModel() != model) {
            setModel(model);
        }
    }

    @Override
    public void onLanguageModelFieldChange(LanguageModel model, LanguageModelField field, String value) {
        if (mModelClient != null && mModelClient.getLanguageModel() == model) {
            mModelClient.setField(field, value);
        }
    }

    @Override
    public void onCommandsChange(String commandsRaw) {
    }

    @Override
    public void onPatternsChange(String patternsRaw) {

    }

    @Override
    public void onOtherSettingsChange(Bundle otherSettings) {
        String enableInternetKey = OtherSettingsType.EnableExternalInternet.name();
        if (otherSettings.containsKey(enableInternetKey)) {
            boolean enableExternalInternet = otherSettings.getBoolean(enableInternetKey);
            updateInternetProvider(enableExternalInternet);
        }
    }

    public void addListener(GenerativeAIListener listener) {
        mListeners.add(listener);
    }

    public void removeListener(GenerativeAIListener listener) {
        mListeners.remove(listener);
    }

    public void generateResponse(String prompt) {
        generateResponse(prompt, null);
    }

    public void generateResponse(String prompt, String systemMessage) {
        generateResponse(prompt, systemMessage, null);
    }

    /**
     * Generate response with optional role id override.
     */
    public void generateResponse(String prompt, String systemMessage, String roleIdOverride) {
        // Default behaviour (stateless). Use the 4-arg overload to enable memory.
        generateResponse(prompt, systemMessage, roleIdOverride, false);
    }

    /**
     * Generate response with optional role id override, and optional multi-turn memory.
     * When memory is enabled, a small number of previous turns will be attached to the prompt
     * based on the "Conversation memory" level in Labs.
     */
    public void generateResponse(String prompt, String systemMessage, String roleIdOverride, boolean useConversationMemory) {
        // Ensure we don't block UI thread. Network work is already off main thread.
        Log.d(TAG, "Getting response for text length: " + (prompt == null ? 0 : prompt.length()));

        if (prompt == null || prompt.isEmpty()) {
            return;
        }

        // Apply request concurrency policy (cancel / ignore / queue).
        final int myRequestId;
        synchronized (mRequestLock) {
            int policy = SPManager.REQUEST_POLICY_CANCEL_PREVIOUS;
            try {
                policy = mSPManager.getRequestConcurrencyPolicy();
            } catch (Throwable ignored) {}

            if (mRequestInFlight) {
                if (policy == SPManager.REQUEST_POLICY_IGNORE_NEW) {
                    return;
                }
                if (policy == SPManager.REQUEST_POLICY_QUEUE_LATEST) {
                    mPendingRequest = new PendingRequest(prompt, systemMessage, roleIdOverride, useConversationMemory);
                    return;
                }

                // Cancel previous (default).
                try {
                    if (mCurrentSubscription != null) {
                        mCurrentSubscription.cancel();
                    }
                } catch (Throwable ignored) {}
                mCurrentSubscription = null;
            }

            mRequestInFlight = true;
            mPendingRequest = null;

            myRequestId = mRequestSeq.incrementAndGet();
            mActiveRequestId = myRequestId;
        }

        // Resolve active role / system message
        String resolvedRoleId = null;
        try {
            SPManager sp = SPManager.getInstance();
            String rid = (roleIdOverride != null && !roleIdOverride.trim().isEmpty())
                    ? roleIdOverride.trim()
                    : sp.getActiveRoleId();
            resolvedRoleId = rid;
            systemMessage = RoleManager.resolveSystemMessage(rid, sp.getRolesJson(), systemMessage);
        } catch (Exception ignored) {}

        // Build conversation-aware prompt (provider-agnostic)
        final String originalPrompt = prompt;
        try {
            if (useConversationMemory) {
                SPManager sp = SPManager.getInstance();
                int mem = sp.getConversationMemoryLevel();
                if (mem > 0) {
                    String modelLabel = "";
                    try {
                        if (mModelClient != null && mModelClient.getLanguageModel() != null)
                            modelLabel = mModelClient.getLanguageModel().name();
                    } catch (Throwable ignored) {}
                    String scope = (modelLabel == null ? "" : modelLabel) + "|" + (resolvedRoleId == null ? "" : resolvedRoleId);
                    ConversationMemoryStore.getInstance().ensureScope(scope);

                    boolean autoSummarize = false;
                    try { autoSummarize = sp.getAutoSummarizeOldContextEnabled(); } catch (Throwable ignored) {}
                    prompt = ConversationMemoryStore.getInstance().buildPromptWithHistory(prompt, mem, autoSummarize);
                }
            }
        } catch (Throwable ignored) {}

        // Max tokens preset (Short / Medium / Long)
        int maxTokensOverride = 0;
        try {
            maxTokensOverride = SPManager.getInstance().getMaxTokensLimit();
        } catch (Throwable ignored) {}

        // Normal model thinking (temperature-like override)
        float normalThinking = 0.7f;
        try { normalThinking = SPManager.getInstance().getNormalModelThinking(); } catch (Throwable ignored) {}

        // Reasoning model thinking (推理模型思考)
        int reasoningThinkingMode = SPManager.REASONING_MODEL_THINKING_AUTO;
        try { reasoningThinkingMode = SPManager.getInstance().getReasoningModelThinkingMode(); } catch (Throwable ignored) {}

        // Build auto-downgrade attempts list
        final ArrayList<Attempt> attempts = new ArrayList<>();
        final LanguageModelClient primaryClient = mModelClient;
        attempts.add(new Attempt(primaryClient, null, null));

        int flags = 0;
        try { flags = SPManager.getInstance().getAutoDowngradeFlags(); } catch (Throwable ignored) {}

        // 1) Stream -> non-stream (force TYPEWRITER mode), only if user isn't already on TYPEWRITER.
        try {
            if ((flags & SPManager.DOWNGRADE_FLAG_STREAM) != 0) {
                int mode = SPManager.getInstance().getStreamingOutputMode();
                if (mode != SPManager.STREAM_MODE_TYPEWRITER) {
                    attempts.add(new Attempt(primaryClient, SPManager.STREAM_MODE_TYPEWRITER, null));
                }
            }
        } catch (Throwable ignored) {}

        // 2) BaseURL fallback (same model)
        try {
            if ((flags & SPManager.DOWNGRADE_FLAG_BASEURL) != 0) {
                String backupUrl = SPManager.getInstance().getAutoDowngradeBackupBaseUrl();
                if (backupUrl != null && !backupUrl.trim().isEmpty()) {
                    attempts.add(new Attempt(primaryClient, null, backupUrl.trim()));
                }
            }
        } catch (Throwable ignored) {}

        // 3) Model fallback (use the user's stored config for that model)
        try {
            if ((flags & SPManager.DOWNGRADE_FLAG_MODEL) != 0) {
                LanguageModel backupModel = SPManager.getInstance().getAutoDowngradeBackupModel();
                if (backupModel != null && primaryClient != null && primaryClient.getLanguageModel() != backupModel) {
                    LanguageModelClient backupClient = LanguageModelClient.forModel(backupModel);
                    for (LanguageModelField field : LanguageModelField.values()) {
                        backupClient.setField(field, mSPManager.getLanguageModelField(backupModel, field));
                    }
                    backupClient.setInternetProvider(mInternetProvider);
                    attempts.add(new Attempt(backupClient, null, null));
                }
            }
        } catch (Throwable ignored) {}

        // Notify prepare (once per user request)
        if (mInteractor != null) {
            mInteractor.runOnUiThread(() -> {
                for (GenerativeAIListener l : mListeners) {
                    l.onAIPrepare();
                }
            });
        }

        final String finalPrompt = prompt;
        final String finalSystemMessage = systemMessage;
        // Capture non-effectively-final locals for lambda
        final String finalResolvedRoleId = resolvedRoleId;
        final boolean finalUseConversationMemory = useConversationMemory;
        final int finalMaxTokensOverride = maxTokensOverride;
        final float finalNormalThinking = normalThinking;
        final int finalReasoningThinkingMode = reasoningThinkingMode;
        final StringBuilder assistantBuffer = new StringBuilder();

        final ParamDowngradeState paramState = new ParamDowngradeState();

        // Start the first attempt
        REQUEST_EXECUTOR.execute(() -> startAttemptInternal(
                myRequestId,
                attempts,
                0,
                finalPrompt,
                finalSystemMessage,
                originalPrompt,
                finalResolvedRoleId,
                finalUseConversationMemory,
                assistantBuffer,
                finalMaxTokensOverride,
                finalNormalThinking,
                finalReasoningThinkingMode,
                paramState
        ));
    }

    private void startAttemptInternal(
            final int requestId,
            final ArrayList<Attempt> attempts,
            final int attemptIndex,
            final String prompt,
            final String systemMessage,
            final String originalPrompt,
            final String resolvedRoleId,
            final boolean useConversationMemory,
            final StringBuilder assistantBuffer,
            final int maxTokensOverride,
            final float normalThinking,
            final int reasoningThinkingMode,
            final ParamDowngradeState paramState
    ) {
        if (attempts == null || attempts.isEmpty()) {
            finishWithError(requestId, new RuntimeException("No request attempt available"));
            return;
        }
        if (attemptIndex < 0 || attemptIndex >= attempts.size()) {
            finishWithError(requestId, new RuntimeException("All downgrade attempts failed"));
            return;
        }

        // Ignore if a newer request has started.
        if (mActiveRequestId != requestId) {
            return;
        }

        final Attempt attempt = attempts.get(attemptIndex);
        final LanguageModelClient client = attempt.client;

        Publisher<String> publisher;

        // Apply temporary overrides (max_tokens / base_url / streaming mode).
        String _prevMaxTokens = null;
        String _prevBaseUrl = null;
        try { if (client != null) _prevMaxTokens = client.getField(LanguageModelField.MaxTokens); } catch (Throwable ignored) {}
        try { if (client != null) _prevBaseUrl = client.getField(LanguageModelField.BaseUrl); } catch (Throwable ignored) {}
        final String prevMaxTokens = _prevMaxTokens;
        final String prevBaseUrl = _prevBaseUrl;

        String _prevTemperature = null;
        try { if (client != null) _prevTemperature = client.getField(LanguageModelField.Temperature); } catch (Throwable ignored) {}
        final String prevTemperature = _prevTemperature;

        // Scheme-2 safety: clamp to cached safe max tokens if known for this specific sub-model.
        int effectiveMaxTokens = maxTokensOverride;
        int requestedMaxTokens = maxTokensOverride;
        try {
            if (client != null && SPManager.isReady() && effectiveMaxTokens > 0) {
                Integer cap = SPManager.getInstance().getCachedSafeMaxTokens(client.getLanguageModel(), client.getSubModel());
                if (cap != null && cap > 0 && effectiveMaxTokens > cap) {
                    effectiveMaxTokens = cap;
                }
            }
        } catch (Throwable ignored) {}

        // UI hint: when we clamp silently, it can look like the model "stopped halfway".
        try {
            if (mInteractor != null && requestedMaxTokens > 0 && effectiveMaxTokens > 0 && requestedMaxTokens != effectiveMaxTokens) {
                final int _req = requestedMaxTokens;
                final int _eff = effectiveMaxTokens;
                mInteractor.runOnUiThread(() -> {
                    try { mInteractor.toastShort("该模型已学习到输出上限，已将 " + _req + " → " + _eff + " tokens"); } catch (Throwable ignoredToast) {}
                });
            }
        } catch (Throwable ignoredToastOuter) {}
try {
            if (client != null && effectiveMaxTokens > 0) {
                client.setField(LanguageModelField.MaxTokens, String.valueOf(effectiveMaxTokens));
            }
        } catch (Throwable ignored) {}

        try {
            if (client != null && attempt.baseUrlOverride != null && !attempt.baseUrlOverride.isEmpty()) {
                client.setField(LanguageModelField.BaseUrl, attempt.baseUrlOverride);
            }
        } catch (Throwable ignored) {}

        try {
            if (attempt.streamModeOverride != null) {
                SPManager.setThreadStreamingModeOverride(attempt.streamModeOverride);
            } else {
                SPManager.clearThreadStreamingModeOverride();
            }
        } catch (Throwable ignored) {}


        // Apply normal model thinking (temperature) override if the selected model supports it.
        try {
            if (client != null && tn.eluea.kgpt.llm.ModelCapabilities.supportsTemperature(client.getLanguageModel(), client.getSubModel())) {
                float v = normalThinking;
                if (v < 0.0f) v = 0.0f;
                if (v > 1.8f) v = 1.8f;
                v = Math.round(v * 10.0f) / 10.0f;
                client.setField(LanguageModelField.Temperature, String.format(Locale.US, "%.1f", v));
            }
        } catch (Throwable ignored) {}

        // Apply reasoning model thinking (system-message hint) if the selected model supports it.
        String effectiveSystemMessage = systemMessage;
        try {
            if (client != null && tn.eluea.kgpt.llm.ModelCapabilities.supportsReasoningThinking(client.getLanguageModel(), client.getSubModel())) {
                effectiveSystemMessage = tn.eluea.kgpt.ui.lab.ReasoningModelThinkingOptions.applyToSystemMessage(systemMessage, reasoningThinkingMode);
            }
        } catch (Throwable ignored) {}

        // Choose publisher
        if (client == null) {
            publisher = new SimpleStringPublisher("Missing model client. Please configure your model in settings.");
        } else if (client.getApiKey() == null || client.getApiKey().isEmpty()) {
            publisher = new SimpleStringPublisher("Missing API Key. Please configure your API key in KeyboardGPT settings.");
        } else {
            publisher = client.submitPrompt(prompt, effectiveSystemMessage);
        }

        publisher.subscribe(new Subscriber<String>() {
            boolean completed = false;
            boolean hasError = false;

            private void cleanupOverrides() {
                try { SPManager.clearThreadStreamingModeOverride(); } catch (Throwable ignored) {}
                try {
                    if (client != null && prevMaxTokens != null) {
                        client.setField(LanguageModelField.MaxTokens, prevMaxTokens);
                    }
                } catch (Throwable ignored) {}
                try {
                    if (client != null && prevBaseUrl != null) {
                        client.setField(LanguageModelField.BaseUrl, prevBaseUrl);
                    }
                } catch (Throwable ignored) {}
                try {
                    if (client != null && prevTemperature != null) {
                        client.setField(LanguageModelField.Temperature, prevTemperature);
                    }
                } catch (Throwable ignored) {}
            }

            @Override
            public void onSubscribe(Subscription s) {
                // Store subscription for cancellation/concurrency
                synchronized (mRequestLock) {
                    if (mActiveRequestId == requestId) {
                        mCurrentSubscription = s;
                    }
                }
                try {
                    s.request(Long.MAX_VALUE);
                } catch (Throwable ignored) {}
            }

            @Override
            public void onNext(String s) {
                if (mActiveRequestId != requestId) return;

                if (s == null || s.isEmpty()) {
                    return;
                }

                try { assistantBuffer.append(s); } catch (Throwable ignored) {}

                if (mInteractor != null) {
                    final String chunk = s;
                    mInteractor.runOnUiThread(() -> {
                        for (GenerativeAIListener l : mListeners) {
                            l.onAINext(chunk);
                        }
                    });
                }
            }

            @Override
            public void onError(Throwable t) {
                if (mActiveRequestId != requestId) return;
                if (completed || hasError) return;
                hasError = true;
                completed = true;

                // 1) Parameter downgrade retry (max tokens) - retry same attempt once.
                boolean canRetryMaxTok = paramState != null
                        && !paramState.retriedMaxTokens
                        && assistantBuffer.length() == 0
                        && (tn.eluea.kgpt.llm.ModelCapabilities.isTokenLimitError(t)
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "max_tokens")
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "max_completion_tokens")
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "maxoutputtokens"));

                if (canRetryMaxTok) {
                    paramState.retriedMaxTokens = true;

                    int current = maxTokensOverride;
                    int safe = current;
                    Integer suggested = tn.eluea.kgpt.llm.ModelCapabilities.extractSuggestedMaxTokens(t);
                    if (suggested != null && suggested > 0) {
                        safe = Math.min(current, suggested);
                    }

                    // If we couldn't infer a number (or failed to reduce), fall back to a sane conservative output limit.
                    if (safe <= 0 || safe >= current) {
                        if (current > 8192) safe = 8192;
                        else if (current > 4096) safe = 4096;
                        else if (current > 2048) safe = 2048;
                        else if (current > 1024) safe = 1024;
                        else safe = Math.max(256, current / 2);
                    }
if (safe < current) {
                        try {
                            if (mInteractor != null) {
                                final int _safeTok = safe;
                                mInteractor.runOnUiThread(() -> {
                                    try { mInteractor.toastShort("已自动降低输出长度到 " + _safeTok + " tokens"); } catch (Throwable ignoredToast) {}
                                });
                            }
                        } catch (Throwable ignoredToastOuter) {}

                        try {
                            if (client != null && SPManager.isReady()) {
                                SPManager.getInstance().setCachedSafeMaxTokens(client.getLanguageModel(), client.getSubModel(), safe);
                            }
                        } catch (Throwable ignored) {}

                        cleanupOverrides();
                        startAttemptInternal(requestId, attempts, attemptIndex, prompt, systemMessage,
                                originalPrompt, resolvedRoleId, useConversationMemory, assistantBuffer,
                                safe, normalThinking, reasoningThinkingMode, paramState);
                        return;
                    }
                }

                // 2) Parameter downgrade retry (sampling params) - retry same attempt once.
                boolean canRetrySampling = paramState != null
                        && !paramState.retriedSamplingParams
                        && assistantBuffer.length() == 0
                        && (tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "temperature")
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "top_p")
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "topP"));

                if (canRetrySampling) {
                    paramState.retriedSamplingParams = true;
                    try {
                        if (client != null && SPManager.isReady()) {
                            SPManager.getInstance().setCachedSupportsTemperature(client.getLanguageModel(), client.getSubModel(), false);
                        }
                    } catch (Throwable ignored) {}

                    cleanupOverrides();

                    // Retry the SAME attempt (same stream/baseUrl/model), but now the client will omit sampling params.
                    startAttemptInternal(requestId, attempts, attemptIndex, prompt, systemMessage,
                            originalPrompt, resolvedRoleId, useConversationMemory, assistantBuffer,
                            maxTokensOverride, normalThinking, reasoningThinkingMode, paramState);
                    return;
                }

                cleanupOverrides();

                // 3) Existing auto-downgrade retry only if we haven't emitted anything yet.
                boolean canRetry = (assistantBuffer.length() == 0) && (attemptIndex + 1 < attempts.size());
                if (canRetry) {
                    startAttemptInternal(requestId, attempts, attemptIndex + 1, prompt, systemMessage,
                            originalPrompt, resolvedRoleId, useConversationMemory, assistantBuffer, maxTokensOverride, normalThinking, reasoningThinkingMode, paramState);
                    return;
                }

                finishWithError(requestId, t);
            }

            @Override
            public void onComplete() {
                if (mActiveRequestId != requestId) return;
                if (completed || hasError) return;
                completed = true;

                cleanupOverrides();

                // Save turn into memory
                try {
                    if (useConversationMemory) {
                        int mem = SPManager.getInstance().getConversationMemoryLevel();
                        if (mem > 0) {
                            String assistant = assistantBuffer.toString();
                            if (assistant != null && !assistant.trim().isEmpty()) {
                                ConversationMemoryStore.getInstance().addTurn(originalPrompt, assistant);
                            }
                        }
                    }
                } catch (Throwable ignored) {}

                // Notify complete
                if (mInteractor != null) {
                    mInteractor.runOnUiThread(() -> {
                        for (GenerativeAIListener l : mListeners) {
                            l.onAIComplete();
                        }
                    });
                }

                finishAndMaybeRunPending(requestId);
            }
        });
    }

    private void finishWithError(final int requestId, final Throwable t) {
        // Notify error
        if (mInteractor != null) {
            mInteractor.runOnUiThread(() -> {
                for (GenerativeAIListener l : mListeners) {
                    l.onAIError(t);
                }
            });
        }
        finishAndMaybeRunPending(requestId);
    }

    private void finishAndMaybeRunPending(final int requestId) {
        PendingRequest pending = null;
        synchronized (mRequestLock) {
            if (mActiveRequestId != requestId) {
                return;
            }
            mRequestInFlight = false;
            mCurrentSubscription = null;
            pending = mPendingRequest;
            mPendingRequest = null;
        }

        if (pending != null) {
            generateResponse(pending.prompt, pending.systemMessage, pending.roleIdOverride, pending.useConversationMemory);
        }
    }


    public LanguageModel getLanguageModel() {
        return mModelClient.getLanguageModel();
    }

    public LanguageModelClient getModelClient() {
        return mModelClient;
    }
}
