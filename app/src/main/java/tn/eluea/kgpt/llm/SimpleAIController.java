/*
 * Copyright (C) 2024-2025 Amr Aldeeb @Eluea
 * 
 * This file is part of KGPT - a fork of KeyboardGPT.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * GitHub: https://github.com/Eluea
 * Telegram: https://t.me/Eluea
 */
package tn.eluea.kgpt.llm;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.listener.GenerativeAIListener;
import tn.eluea.kgpt.roles.RoleManager;
import tn.eluea.kgpt.llm.client.LanguageModelClient;
import tn.eluea.kgpt.llm.internet.SimpleInternetProvider;
import tn.eluea.kgpt.llm.publisher.SimpleStringPublisher;

/**
 * Simplified AI Controller for use in app context (not Xposed context).
 * Does not depend on MainHook or UiInteractor.
 */
public class SimpleAIController {
    private static final String TAG = "KGPT_SimpleAI";


    /**
     * Run all blocking network calls off the main thread.
     * SimpleInternetProvider uses blocking HttpURLConnection.
     */
    private static final java.util.concurrent.ExecutorService REQUEST_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "KGPT-LLM-App");
                t.setDaemon(true);
                return t;
            });
    
    private LanguageModelClient mModelClient = null;
    private final SPManager mSPManager;
    private final Handler mMainHandler;
    private final List<GenerativeAIListener> mListeners = new ArrayList<>();

    public SimpleAIController() {
        mSPManager = SPManager.getInstance();
        mMainHandler = new Handler(Looper.getMainLooper());
        
        if (mSPManager.hasLanguageModel()) {
            setModel(mSPManager.getLanguageModel());
        } else {
            mModelClient = LanguageModelClient.forModel(LanguageModel.Gemini);
        }
        
        // Set internet provider
        if (mModelClient != null) {
            mModelClient.setInternetProvider(new SimpleInternetProvider());
        }
    }

    private void setModel(LanguageModel model) {
        Log.d(TAG, "setModel " + model.label);
        mModelClient = LanguageModelClient.forModel(model);
        for (LanguageModelField field : LanguageModelField.values()) {
            mModelClient.setField(field, mSPManager.getLanguageModelField(model, field));
        }
        mModelClient.setInternetProvider(new SimpleInternetProvider());
    }

    public boolean needModelClient() {
        return mModelClient == null;
    }

    public boolean needApiKey() {
        return mModelClient == null || 
               mModelClient.getApiKey() == null || 
               mModelClient.getApiKey().isEmpty();
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
     * Generate response with optional multi-turn memory.
     */
    public void generateResponse(String prompt, String systemMessage, String roleIdOverride, boolean useConversationMemory) {
        Log.d(TAG, "Getting response for text length: " + prompt.length());

        if (prompt.isEmpty()) {
            return;
        }

        // Apply custom role (system prompt) if configured
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
                    try { autoSummarize = sp.getAutoSummarizeOldContextEnabled(); } catch (Throwable ignored3) {}
                    prompt = ConversationMemoryStore.getInstance().buildPromptWithHistory(prompt, mem, autoSummarize);
                }
            }
        } catch (Throwable ignored) {}

        // Notify prepare on main thread
        mMainHandler.post(() -> {
            for (GenerativeAIListener l : mListeners) {
                l.onAIPrepare();
            }
        });

        // Max tokens preset (Short / Medium / Long)
        int maxTokensOverride = 0;
        try { maxTokensOverride = SPManager.getInstance().getMaxTokensLimit(); } catch (Throwable ignored) {}

        // Scheme-2 safety: if we have a cached safe max tokens for this model, clamp to it.
        try {
            if (mModelClient != null && SPManager.isReady()) {
                Integer cap = SPManager.getInstance().getCachedSafeMaxTokens(mModelClient.getLanguageModel(), mModelClient.getSubModel());
                if (cap != null && cap > 0 && maxTokensOverride > cap) {
                    maxTokensOverride = cap;
                }
            }
        } catch (Throwable ignored) {}

        // Normal model thinking (temperature-like override)
        float normalThinking = 0.7f;
        try { normalThinking = SPManager.getInstance().getNormalModelThinking(); } catch (Throwable ignored) {}

        // Reasoning model thinking (推理模型思考)
        int reasoningThinkingMode = SPManager.REASONING_MODEL_THINKING_AUTO;
        try { reasoningThinkingMode = SPManager.getInstance().getReasoningModelThinkingMode(); } catch (Throwable ignored) {}

        // Capture final copies for lambda usage (prompt/systemMessage may be reassigned above)
        final String promptFinal = prompt;
        final String systemMessageFinal = systemMessage;
        final int reasoningThinkingModeFinal = reasoningThinkingMode;

        String prevMaxTokens = null;
        try { if (mModelClient != null) prevMaxTokens = mModelClient.getField(LanguageModelField.MaxTokens); } catch (Throwable ignored) {}
        final String prevMaxTokensFinal = prevMaxTokens;
        String prevTemperature = null;
        try { if (mModelClient != null) prevTemperature = mModelClient.getField(LanguageModelField.Temperature); } catch (Throwable ignored) {}
        final String prevTemperatureFinal = prevTemperature;
        // Track the effective max tokens used for scheme-2 retry.
        final int[] maxTokensEffective = new int[]{maxTokensOverride};

        try {
            if (mModelClient != null && maxTokensEffective[0] > 0) {
                mModelClient.setField(LanguageModelField.MaxTokens, String.valueOf(maxTokensEffective[0]));
            }
        } catch (Throwable ignored) {}

        try {
            if (mModelClient != null && tn.eluea.kgpt.llm.ModelCapabilities.supportsTemperature(mModelClient.getLanguageModel(), mModelClient.getSubModel())) {
                float v = normalThinking;
                if (v < 0.0f) v = 0.0f;
                if (v > 1.8f) v = 1.8f;
                v = Math.round(v * 10.0f) / 10.0f;
                mModelClient.setField(LanguageModelField.Temperature, String.format(Locale.US, "%.1f", v));
            }
        } catch (Throwable ignored) {}

        Publisher<String> publisher;

        final StringBuilder assistantBuffer = new StringBuilder();
        final boolean[] retriedSamplingParams = new boolean[]{false};
        final boolean[] retriedMaxTokens = new boolean[]{false};

        final Runnable[] startRequest = new Runnable[1];
        startRequest[0] = () -> {
            Publisher<String> pub;
            if (needModelClient() || needApiKey()) {
                pub = new SimpleStringPublisher("Missing API Key. Please configure your API key in KGPT settings.");
            } else {
                String effectiveSystemMessage = systemMessageFinal;
                try {
                    if (mModelClient != null && tn.eluea.kgpt.llm.ModelCapabilities.supportsReasoningThinking(mModelClient.getLanguageModel(), mModelClient.getSubModel())) {
                        effectiveSystemMessage = tn.eluea.kgpt.ui.lab.ReasoningModelThinkingOptions.applyToSystemMessage(systemMessageFinal, reasoningThinkingModeFinal);
                    }
                } catch (Throwable ignored) {}
                pub = mModelClient.submitPrompt(promptFinal, effectiveSystemMessage);
            }

            pub.subscribe(new Subscriber<String>() {
            boolean completed = false;
            boolean hasError = false;

            private void restoreOverrides() {
                try {
                    if (mModelClient != null && prevMaxTokensFinal != null) {
                        mModelClient.setField(LanguageModelField.MaxTokens, prevMaxTokensFinal);
                    }
                } catch (Throwable ignored) {}

                try {
                    if (mModelClient != null && prevTemperatureFinal != null) {
                        mModelClient.setField(LanguageModelField.Temperature, prevTemperatureFinal);
                    }
                } catch (Throwable ignored) {}
            }

@Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String s) {
                if (s == null || s.isEmpty()) {
                    return;
                }

                try { assistantBuffer.append(s); } catch (Throwable ignored) {}

                Log.d(TAG, "onNext: string with length " + s.length());

                mMainHandler.post(() -> {
                    for (GenerativeAIListener l : mListeners) {
                        l.onAINext(s);
                    }
                });
            }

            @Override
            public void onError(Throwable t) {
                if (completed || hasError) {
                    Log.d(TAG, "Skipping duplicate onError");
                    return;
                }

                // Auto-downgrade: if the provider rejects sampling params (temperature/top_p),
                // cache that capability as unsupported and retry once without them.
                boolean canRetrySampling = !retriedSamplingParams[0]
                        && assistantBuffer.length() == 0
                        && (tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "temperature")
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "top_p")
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "topP"));

                if (canRetrySampling) {
                    retriedSamplingParams[0] = true;
                    try {
                        if (mModelClient != null && SPManager.isReady()) {
                            SPManager.getInstance().setCachedSupportsTemperature(mModelClient.getLanguageModel(), mModelClient.getSubModel(), false);
                        }
                    } catch (Throwable ignored) {}

                    Log.w(TAG, "Sampling params rejected by model; retrying without temperature/top_p", t);
                    try {
                        REQUEST_EXECUTOR.execute(startRequest[0]);
                        return;
                    } catch (Throwable ignored) {
                        // Fall through to normal error handling.
                    }
                }

                // Scheme-2 safety: if the provider rejects the requested output token length,
                // retry once with a smaller safe value and cache it for this sub-model.
                boolean canRetryMaxTok = !retriedMaxTokens[0]
                        && assistantBuffer.length() == 0
                        && (tn.eluea.kgpt.llm.ModelCapabilities.isTokenLimitError(t)
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "max_tokens")
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "max_completion_tokens")
                            || tn.eluea.kgpt.llm.ModelCapabilities.isUnsupportedParamError(t, "maxoutputtokens"));

                if (canRetryMaxTok) {
                    retriedMaxTokens[0] = true;

                    int current = maxTokensEffective[0];
                    int safe = current;
                    Integer suggested = tn.eluea.kgpt.llm.ModelCapabilities.extractSuggestedMaxTokens(t);
                    if (suggested != null && suggested > 0) {
                        // Be conservative: if this is a context-window number, using it directly as output
                        // is still safer than requesting something huge.
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
                    // Ensure we actually reduce.
                    if (safe < current) {
                        maxTokensEffective[0] = safe;
                        try {
                            if (mModelClient != null) {
                                mModelClient.setField(LanguageModelField.MaxTokens, String.valueOf(safe));
                            }
                        } catch (Throwable ignored) {}
                        try {
                            if (mModelClient != null && SPManager.isReady()) {
                                SPManager.getInstance().setCachedSafeMaxTokens(mModelClient.getLanguageModel(), mModelClient.getSubModel(), safe);
                            }
                        } catch (Throwable ignored) {}

                        Log.w(TAG, "Max tokens rejected by model; retrying with safe max_tokens=" + safe, t);
                        try {
                            REQUEST_EXECUTOR.execute(startRequest[0]);
                            return;
                        } catch (Throwable ignored) {
                            // Fall through to normal error handling.
                        }
                    }
                }

                hasError = true;
                completed = true;

                restoreOverrides();

                Log.e(TAG, "AI request error", t);

                mMainHandler.post(() -> {
                    for (GenerativeAIListener l : mListeners) {
                        l.onAIError(t);
                    }
                });
            }

            @Override
            public void onComplete() {
                if (completed || hasError) {
                    Log.d(TAG, "Skipping duplicate onComplete");
                    return;
                }
                completed = true;
                restoreOverrides();

                Log.d(TAG, "AI request completed");

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

                mMainHandler.post(() -> {
                    for (GenerativeAIListener l : mListeners) {
                        l.onAIComplete();
                    }
                });
            }
        });
        };

        // Start first attempt
        REQUEST_EXECUTOR.execute(startRequest[0]);
    }


    public LanguageModel getLanguageModel() {
        return mModelClient != null ? mModelClient.getLanguageModel() : LanguageModel.Gemini;
    }
}
