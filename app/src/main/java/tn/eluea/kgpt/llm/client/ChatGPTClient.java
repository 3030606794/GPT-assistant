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
package tn.eluea.kgpt.llm.client;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.reactivestreams.Publisher;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;

import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.util.AiDiagnostics;
import tn.eluea.kgpt.llm.LanguageModel;
import tn.eluea.kgpt.llm.ModelCapabilities;
import tn.eluea.kgpt.llm.LanguageModelField;
import tn.eluea.kgpt.llm.publisher.ExceptionPublisher;
import tn.eluea.kgpt.llm.publisher.InternetRequestPublisher;

public class ChatGPTClient extends LanguageModelClient {

    /**
     * Submit a multimodal prompt with a single image (data URI) using the OpenAI-compatible
     * chat/completions format. If the current provider/model rejects multimodal content,
     * the caller should fall back to plain-text prompts.
     */
    public Publisher<String> submitPromptWithImageDataUri(String prompt, String systemMessage, String imageDataUri) {
        if (imageDataUri == null || imageDataUri.trim().isEmpty()) {
            return submitPrompt(prompt, systemMessage);
        }
        return submitPromptWithImageDataUris(prompt, systemMessage, java.util.Collections.singletonList(imageDataUri));
    }

    /**
     * Submit a multimodal prompt with multiple images (data URIs) using the OpenAI-compatible
     * chat/completions format.
     */
    public Publisher<String> submitPromptWithImageDataUris(String prompt, String systemMessage, List<String> imageDataUris) {
        if (getApiKey() == null || getApiKey().isEmpty()) {
            return LanguageModelClient.MISSING_API_KEY_PUBLISHER;
        }

        if (systemMessage == null) {
            systemMessage = getDefaultSystemMessage();
        }

        if (imageDataUris == null || imageDataUris.isEmpty()) {
            return submitPrompt(prompt, systemMessage);
        }

        String url = getBaseUrl() + "/chat/completions";
        HttpURLConnection con;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Authorization", "Bearer " + getApiKey());

            try {
                if (getLanguageModel() == LanguageModel.OpenRouter) {
                    con.setRequestProperty("HTTP-Referer", "https://github.com/Eluea/KGPT");
                    con.setRequestProperty("X-Title", "KGPT");
                    con.setRequestProperty("X-API-Key", getApiKey());
                }
            } catch (Throwable ignored) {
            }

            JSONArray messagesJson = new JSONArray();
            messagesJson.put(new JSONObject()
                    .accumulate("role", "system")
                    .accumulate("content", systemMessage));

            // user content parts
            JSONArray userParts = new JSONArray();
            userParts.put(new JSONObject()
                    .put("type", "text")
                    .put("text", prompt == null ? "" : prompt));
            for (String dataUri : imageDataUris) {
                if (dataUri == null || dataUri.trim().isEmpty()) continue;
                userParts.put(new JSONObject()
                        .put("type", "image_url")
                        .put("image_url", new JSONObject().put("url", dataUri)));
            }

            messagesJson.put(new JSONObject()
                    .accumulate("role", "user")
                    .accumulate("content", userParts));

            JSONObject rootJson = new JSONObject();
            String modelName = getSubModel();
            try {
                String base = getBaseUrl();
                if (getLanguageModel() == tn.eluea.kgpt.llm.LanguageModel.OpenRouter
                        && base != null
                        && base.contains("/v1")
                        && !base.contains("/api/v1")
                        && modelName.contains("/")) {
                    modelName = modelName.substring(modelName.lastIndexOf('/') + 1);
                }
            } catch (Throwable ignored) {
            }
            rootJson.put("model", modelName);
            rootJson.put("messages", messagesJson);

            boolean streamingEnabledTmp = false;
            int streamingModeTmp = SPManager.STREAM_MODE_AUTO;
            boolean fallbackNonStreamTmp = true;
            try {
                streamingEnabledTmp = SPManager.getInstance().getStreamingOutputEnabled();
                streamingModeTmp = SPManager.getInstance().getStreamingOutputModeForRequest();
                fallbackNonStreamTmp = SPManager.getInstance().getStreamingOutputFallbackNonStreamEnabled();
            } catch (Throwable ignored) {}
            final boolean streamingEnabled = streamingEnabledTmp;
            final int streamingMode = streamingModeTmp;
            final boolean fallbackNonStream = fallbackNonStreamTmp;

            final boolean streamRequest = streamingEnabled && streamingMode != SPManager.STREAM_MODE_TYPEWRITER;
            rootJson.put("stream", streamRequest);

            if (ModelCapabilities.supportsMaxTokens(getLanguageModel(), getSubModel())) {
                rootJson.put("max_tokens", getIntField(LanguageModelField.MaxTokens));
            }
            if (ModelCapabilities.supportsTemperature(getLanguageModel(), getSubModel())) {
                rootJson.put("temperature", getDoubleField(LanguageModelField.Temperature));
                rootJson.put("top_p", getDoubleField(LanguageModelField.TopP));
            }

            try {
                String reasoningEffort = SPManager.getThreadReasoningEffortOverride();
                if (reasoningEffort != null && !reasoningEffort.trim().isEmpty()) {
                    rootJson.put("reasoning_effort", reasoningEffort.trim());
                }
            } catch (Throwable ignored) {
            }

            InternetRequestPublisher publisher = new InternetRequestPublisher(
                    (s, reader) -> {
                        if (streamRequest) {
                            OpenAICompatStreamParser.parse(s, reader, streamingMode, fallbackNonStream);
                            return;
                        }

                        String response = reader.lines().collect(Collectors.joining(""));
                        try { AiDiagnostics.setLastRawBlob(response); } catch (Throwable ignored) {}
                        JSONObject responseJson = new JSONObject(response);
                        if (responseJson.has("choices")) {
                            JSONArray choices = responseJson.getJSONArray("choices");
                            for (int i = 0; i < choices.length(); i++) {
                                JSONObject choice = choices.getJSONObject(i).getJSONObject("message");
                                if (choice.has("role") && "assistant".equals(choice.getString("role"))) {
                                    Object cv = choice.opt("content");
                                    if (cv != null && cv != JSONObject.NULL) {
                                        s.onNext(String.valueOf(cv));
                                        return;
                                    }
                                }
                            }
                            if (choices.length() > 0) {
                                JSONObject msg0 = choices.getJSONObject(0).getJSONObject("message");
                                Object cv0 = msg0.opt("content");
                                if (cv0 != null && cv0 != JSONObject.NULL) {
                                    s.onNext(String.valueOf(cv0));
                                } else {
                                    throw new JSONException("assistant content is null");
                                }
                            } else {
                                throw new JSONException("choices has length 0");
                            }
                        } else {
                            throw new JSONException("no \"choices\" attribute found");
                        }
                    },
                    (s, reader) -> {
                        String response = reader.lines().collect(Collectors.joining(""));
                        try { AiDiagnostics.setLastRawBlob(response); } catch (Throwable ignored) {}
                        JSONObject responseJson = new JSONObject(response);
                        if (responseJson.has("error")) {
                            JSONObject errorJson = responseJson.getJSONObject("error");
                            String message = errorJson.optString("message", response);
                            String type = errorJson.optString("type", "");
                            String code = errorJson.optString("code", "");

                            String userMessage;
                            if ("insufficient_quota".equals(code) || message.contains("quota")) {
                                userMessage = "API quota exceeded. Check your OpenAI billing or use a different model";
                            } else if ("invalid_api_key".equals(code) || message.contains("API key")) {
                                userMessage = "Invalid API key. Please check your OpenAI API key";
                            } else if ("model_not_found".equals(code) || message.contains("does not exist")) {
                                userMessage = "Model not found: " + getSubModel() + ". Please check the model name";
                            } else if ("rate_limit_exceeded".equals(type)) {
                                userMessage = "Rate limit exceeded. Please wait and try again";
                            } else {
                                userMessage = "OpenAI Error: " + message;
                            }
                            throw new RuntimeException(userMessage);
                        }
                        else {
                            throw new RuntimeException(response);
                        }
                    });
            InputStream inputStream = sendRequest(con, rootJson.toString(), publisher);
            publisher.setInputStream(inputStream);
            return publisher;
        } catch (Throwable t) {
            return new ExceptionPublisher(t);
        }
    }

    @Override
    public Publisher<String> submitPrompt(String prompt, String systemMessage) {
        if (getApiKey() == null || getApiKey().isEmpty()) {
            return LanguageModelClient.MISSING_API_KEY_PUBLISHER;
        }

        if (systemMessage == null) {
            systemMessage = getDefaultSystemMessage();
        }

        String url = getBaseUrl() + "/chat/completions";
        HttpURLConnection con;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Authorization", "Bearer " + getApiKey());

            // OpenRouter recommends setting these; also add a secondary key header to be
            // more tolerant of odd proxy setups that strip Authorization.
            try {
                if (getLanguageModel() == LanguageModel.OpenRouter) {
                    con.setRequestProperty("HTTP-Referer", "https://github.com/Eluea/KGPT");
                    con.setRequestProperty("X-Title", "KGPT");
                    con.setRequestProperty("X-API-Key", getApiKey());
                }
            } catch (Throwable ignored) {
            }

            JSONArray messagesJson = new JSONArray();
            messagesJson.put(new JSONObject()
                    .accumulate("role", "system")
                    .accumulate("content", systemMessage));
            messagesJson.put(new JSONObject()
                    .accumulate("role", "user")
                    .accumulate("content", prompt));
            JSONObject rootJson = new JSONObject();
            String modelName = getSubModel();
            // If user uses an OpenAI-compatible relay (usually ends with /v1),
            // OpenRouter-style model names like "openai/gpt-4o-mini" should be converted to "gpt-4o-mini".
            try {
                String base = getBaseUrl();
                if (getLanguageModel() == tn.eluea.kgpt.llm.LanguageModel.OpenRouter
                        && base != null
                        && base.contains("/v1")
                        && !base.contains("/api/v1")
                        && modelName.contains("/")) {
                    modelName = modelName.substring(modelName.lastIndexOf('/') + 1);
                }
            } catch (Throwable ignored) {
            }
            rootJson.put("model", modelName);
            rootJson.put("messages", messagesJson);
            boolean streamingEnabledTmp = false;
            int streamingModeTmp = SPManager.STREAM_MODE_AUTO;
            boolean fallbackNonStreamTmp = true;
            try {
                streamingEnabledTmp = SPManager.getInstance().getStreamingOutputEnabled();
                streamingModeTmp = SPManager.getInstance().getStreamingOutputModeForRequest();
                fallbackNonStreamTmp = SPManager.getInstance().getStreamingOutputFallbackNonStreamEnabled();
            } catch (Throwable ignored) {}
            final boolean streamingEnabled = streamingEnabledTmp;
            final int streamingMode = streamingModeTmp;
            final boolean fallbackNonStream = fallbackNonStreamTmp;

            final boolean streamRequest = streamingEnabled && streamingMode != SPManager.STREAM_MODE_TYPEWRITER;
            rootJson.put("stream", streamRequest);
            // Some utility/non-text endpoints reject max_tokens; gate by capability.
            if (ModelCapabilities.supportsMaxTokens(getLanguageModel(), getSubModel())) {
                rootJson.put("max_tokens", getIntField(LanguageModelField.MaxTokens));
            }

            // Some models (especially non-chat / reasoning-only endpoints) reject sampling params.
            // Gate these fields by capability (with runtime cache + heuristics).
            if (ModelCapabilities.supportsTemperature(getLanguageModel(), getSubModel())) {
                rootJson.put("temperature", getDoubleField(LanguageModelField.Temperature));
                rootJson.put("top_p", getDoubleField(LanguageModelField.TopP));
            }

            try {
                String reasoningEffort = SPManager.getThreadReasoningEffortOverride();
                if (reasoningEffort != null && !reasoningEffort.trim().isEmpty()) {
                    rootJson.put("reasoning_effort", reasoningEffort.trim());
                }
            } catch (Throwable ignored) {
            }

            InternetRequestPublisher publisher = new InternetRequestPublisher(
                    (s, reader) -> {
                        if (streamRequest) {
                            // OpenAI-compatible streaming (SSE/JSONL/Auto)
                            OpenAICompatStreamParser.parse(s, reader, streamingMode, fallbackNonStream);
                            return;
                        }

                        // Non-streaming response
                        String response = reader.lines().collect(Collectors.joining(""));
                        try { AiDiagnostics.setLastRawBlob(response); } catch (Throwable ignored) {}
                        JSONObject responseJson = new JSONObject(response);
                        if (responseJson.has("choices")) {
                            JSONArray choices = responseJson.getJSONArray("choices");
                            for (int i = 0; i < choices.length(); i++) {
                                JSONObject choice = choices.getJSONObject(i).getJSONObject("message");
                                if (choice.has("role") && "assistant".equals(choice.getString("role"))) {
                                    Object cv = choice.opt("content");
                                    if (cv != null && cv != JSONObject.NULL) {
                                        s.onNext(String.valueOf(cv));
                                        return;
                                    }
                                }
                            }
                            if (choices.length() > 0) {
                                JSONObject msg0 = choices.getJSONObject(0).getJSONObject("message");
                                Object cv0 = msg0.opt("content");
                                if (cv0 != null && cv0 != JSONObject.NULL) {
                                    s.onNext(String.valueOf(cv0));
                                } else {
                                    throw new JSONException("assistant content is null");
                                }
                            } else {
                                throw new JSONException("choices has length 0");
                            }
                        } else {
                            throw new JSONException("no \"choices\" attribute found");
                        }
                    },
                    (s, reader) -> {
                        String response = reader.lines().collect(Collectors.joining(""));
                        try { AiDiagnostics.setLastRawBlob(response); } catch (Throwable ignored) {}
                        JSONObject responseJson = new JSONObject(response);
                        if (responseJson.has("error")) {
                            JSONObject errorJson = responseJson.getJSONObject("error");
                            String message = errorJson.optString("message", response);
                            String type = errorJson.optString("type", "");
                            String code = errorJson.optString("code", "");
                            
                            // Provide user-friendly error messages
                            String userMessage;
                            if ("insufficient_quota".equals(code) || message.contains("quota")) {
                                userMessage = "API quota exceeded. Check your OpenAI billing or use a different model";
                            } else if ("invalid_api_key".equals(code) || message.contains("API key")) {
                                userMessage = "Invalid API key. Please check your OpenAI API key";
                            } else if ("model_not_found".equals(code) || message.contains("does not exist")) {
                                userMessage = "Model not found: " + getSubModel() + ". Please check the model name";
                            } else if ("rate_limit_exceeded".equals(type)) {
                                userMessage = "Rate limit exceeded. Please wait and try again";
                            } else {
                                userMessage = "OpenAI Error: " + message;
                            }
                            
                            throw new RuntimeException(userMessage);
                        }
                        else {
                            throw new RuntimeException(response);
                        }
                    });
            InputStream inputStream = sendRequest(con, rootJson.toString(), publisher);
            publisher.setInputStream(inputStream);
            return publisher;
        } catch (Throwable t) {
            return new ExceptionPublisher(t);
        }
    }

    @Override
    public LanguageModel getLanguageModel() {
        return LanguageModel.ChatGPT;
    }
}
