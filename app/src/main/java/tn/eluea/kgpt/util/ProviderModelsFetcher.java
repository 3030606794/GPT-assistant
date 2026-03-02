package tn.eluea.kgpt.util;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import tn.eluea.kgpt.llm.LanguageModel;

/**
 * Small helper to fetch provider model list from an OpenAI-compatible endpoint:
 *   GET {baseUrl}/models
 *
 * Used by: Lab (refresh cache button) and quick config flows.
 */
public final class ProviderModelsFetcher {

    private ProviderModelsFetcher() {}

    public static final class Result {
        public final List<String> models;
        public final long latencyMs;

        public Result(@NonNull List<String> models, long latencyMs) {
            this.models = models;
            this.latencyMs = Math.max(0, latencyMs);
        }
    }

    @NonNull
    public static Result fetchModels(@NonNull LanguageModel model,
                                     @NonNull String baseUrl,
                                     @NonNull String apiKey) throws Exception {
        long started = android.os.SystemClock.elapsedRealtime();

        String url = baseUrl;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        url = url + "/models";

        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("GET");
        con.setConnectTimeout(15000);
        con.setReadTimeout(20000);
        con.setRequestProperty("Accept", "application/json");

        String key = apiKey.trim();
        if (!key.isEmpty()) {
            if (model == LanguageModel.Gemini) {
                con.setRequestProperty("x-goog-api-key", key);
            } else {
                con.setRequestProperty("Authorization", "Bearer " + key);
            }
        }

        int code = con.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
        String body = readAll(is);

        long latency = Math.max(0, android.os.SystemClock.elapsedRealtime() - started);

        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + " " + body);
        }

        return new Result(parseModelsFromResponse(model, body), latency);
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        br.close();
        return sb.toString();
    }

    @NonNull
    private static List<String> parseModelsFromResponse(@NonNull LanguageModel model, String body) {
        List<String> list = new ArrayList<>();
        if (body == null) return list;

        try {
            JSONObject root = new JSONObject(body);

            // OpenAI / OpenRouter style: { data: [ { id: ... }, ... ] }
            if (root.has("data") && root.optJSONArray("data") != null) {
                JSONArray arr = root.optJSONArray("data");
                for (int i = 0; i < arr.length(); i++) {
                    Object it = arr.opt(i);
                    if (it instanceof JSONObject) {
                        String id = ((JSONObject) it).optString("id", "");
                        if (id.isEmpty()) id = ((JSONObject) it).optString("name", "");
                        if (!id.isEmpty()) list.add(id);
                    } else if (it instanceof String) {
                        String name = (String) it;
                        if (model == LanguageModel.Gemini && name.startsWith("models/")) {
                            name = name.substring("models/".length());
                        }
                        if (!TextUtils.isEmpty(name)) list.add(name);
                    }
                }
            }

            // Gemini style: { models: [ { name: "models/xxx" }, ... ] }
            if (list.isEmpty() && root.has("models") && root.optJSONArray("models") != null) {
                JSONArray arr = root.optJSONArray("models");
                for (int i = 0; i < arr.length(); i++) {
                    Object it = arr.opt(i);
                    if (it instanceof JSONObject) {
                        String name = ((JSONObject) it).optString("name", "");
                        if (!TextUtils.isEmpty(name)) {
                            if (model == LanguageModel.Gemini && name.startsWith("models/")) {
                                name = name.substring("models/".length());
                            }
                            list.add(name);
                        }
                    } else if (it instanceof String) {
                        String name = (String) it;
                        if (model == LanguageModel.Gemini && name.startsWith("models/")) {
                            name = name.substring("models/".length());
                        }
                        if (!TextUtils.isEmpty(name)) list.add(name);
                    }
                }
            }

            // Some relays: { result: [ ... ] }
            if (list.isEmpty() && root.has("result") && root.optJSONArray("result") != null) {
                JSONArray arr = root.optJSONArray("result");
                for (int i = 0; i < arr.length(); i++) {
                    Object it = arr.opt(i);
                    if (it instanceof JSONObject) {
                        String id = ((JSONObject) it).optString("id", "");
                        if (!TextUtils.isEmpty(id)) list.add(id);
                    } else if (it instanceof String) {
                        String id = (String) it;
                        if (!TextUtils.isEmpty(id)) list.add(id);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // De-dupe while keeping order
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String s : list) {
            if (s == null) continue;
            String v = s.trim();
            if (!v.isEmpty()) set.add(v);
        }
        return new java.util.ArrayList<>(set);
    }
}
