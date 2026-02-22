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
import java.util.stream.Collectors;

import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.llm.LanguageModel;
import tn.eluea.kgpt.llm.ModelCapabilities;
import tn.eluea.kgpt.llm.LanguageModelField;
import tn.eluea.kgpt.llm.publisher.ExceptionPublisher;
import tn.eluea.kgpt.llm.publisher.InternetRequestPublisher;

public class GroqClient extends ChatGPTClient {
    @Override
    public LanguageModel getLanguageModel() {
        return LanguageModel.Groq;
    }

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

            JSONArray messagesJson = new JSONArray();
            messagesJson.put(new JSONObject()
                    .accumulate("role", "system")
                    .accumulate("content", systemMessage));
            messagesJson.put(new JSONObject()
                    .accumulate("role", "user")
                    .accumulate("content", prompt));
            JSONObject rootJson = new JSONObject();
            rootJson.put("model", getSubModel());
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
            // Some utility/non-text endpoints reject max tokens; gate by capability.
            if (ModelCapabilities.supportsMaxTokens(getLanguageModel(), getSubModel())) {
                rootJson.put("max_completion_tokens", getIntField(LanguageModelField.MaxTokens));
            }

            if (ModelCapabilities.supportsTemperature(getLanguageModel(), getSubModel())) {
                rootJson.put("temperature", getDoubleField(LanguageModelField.Temperature));
                rootJson.put("top_p", getDoubleField(LanguageModelField.TopP));
            }

            InternetRequestPublisher publisher = new InternetRequestPublisher(
                    (s, reader) -> {
                        if (streamRequest) {
                            OpenAICompatStreamParser.parse(s, reader, streamingMode, fallbackNonStream);
                            return;
                        }

                        String response = reader.lines().collect(Collectors.joining(""));
                        JSONObject responseJson = new JSONObject(response);
                        if (responseJson.has("choices")) {
                            JSONArray choices = responseJson.getJSONArray("choices");
                            if (choices.length() > 0) {
                                JSONObject msg = choices.getJSONObject(0).getJSONObject("message");
                                s.onNext(msg.optString("content", ""));
                                return;
                            }
                            throw new JSONException("choices has length 0");
                        }
                        throw new JSONException("no \"choices\" attribute found");
                    },
                    (s, reader) -> {
                        String response = reader.lines().collect(Collectors.joining(""));
                        JSONObject responseJson = new JSONObject(response);
                        if (responseJson.has("error")) {
                            JSONObject errorJson = responseJson
                                    .getJSONObject("error");
                            String message = errorJson.getString("message");
                            String type = errorJson.getString("type");

                            throw new IllegalArgumentException("(" + type + ") " + message);
                        }
                        else {
                            throw new IllegalArgumentException(response);
                        }
                    }
            );
            InputStream inputStream = sendRequest(con, rootJson.toString(), publisher);
            publisher.setInputStream(inputStream);
            return publisher;
        } catch (Throwable t) {
            return new ExceptionPublisher(t);
        }
    }

    private String extractContent(JSONObject message) throws JSONException {
        if (!message.has("content")) {
            return "";
        }

        return message.getString("content");
    }

}
