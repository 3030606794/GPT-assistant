package tn.eluea.kgpt.llm.client;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;

import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.util.AiDiagnostics;
import org.reactivestreams.Subscriber;

/**
 * Robust parser for OpenAI-compatible streaming responses.
 * Supports:
 *  - SSE:  data: {...}\n\n ... data: [DONE]
 *  - JSONL: one JSON object per line (no data: prefix)
 *  - Broken/concatenated frames (e.g. "data:{...}data:{...}" in one line)
 */
public final class OpenAICompatStreamParser {

    private static void diag(String category, String msg) {
        try { AiDiagnostics.append("PARSER_" + category, msg); } catch (Throwable ignored) {}
    }

    private static String safe(String s) {
        if (s == null) return "-";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (s.length() > 180) s = s.substring(0, 180) + "…";
        return s.isEmpty() ? "-" : s;
    }

    private OpenAICompatStreamParser() {}

    public static void parse(Subscriber<? super String> subscriber,
                             BufferedReader reader,
                             int streamingMode,
                             boolean fallbackNonStreamEnabled) throws Throwable {

        boolean treatAsSse = (streamingMode == SPManager.STREAM_MODE_SSE);
        boolean decided = (streamingMode == SPManager.STREAM_MODE_SSE || streamingMode == SPManager.STREAM_MODE_JSONL);

        StringBuilder raw = fallbackNonStreamEnabled ? new StringBuilder() : null;
        StringBuilder emitted = fallbackNonStreamEnabled ? new StringBuilder() : null;

        String line;
        boolean done = false;
        boolean emittedAny = false;


        long lastKeepAliveAtMs = 0L;
        int frames = 0;
        int nullContentFrames = 0;
        int reasoningFrames = 0;
        diag("START", "cfgMode=" + streamingMode + " fallbackNonStream=" + fallbackNonStreamEnabled);
        try { tn.eluea.kgpt.util.AiDiagnostics.appendLastRawLine("# parser_start cfgMode=" + streamingMode + " fallbackNonStream=" + fallbackNonStreamEnabled); } catch (Throwable ignored) {}

        while ((line = reader.readLine()) != null) {
            try { tn.eluea.kgpt.util.AiDiagnostics.appendLastRawLine(line); } catch (Throwable ignored) {}
            if (raw != null) raw.append(line).append('\n');

            String trimmed = (line == null) ? "" : line.trim();
            if (trimmed.isEmpty()) continue;

            if (!decided) {
                // AUTO: first meaningful line decides.
                // Prefer JSONL if it looks like JSON; otherwise SSE if it starts with data:/event:/:
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    treatAsSse = false;
                } else {
                    treatAsSse = trimmed.startsWith("data:") || trimmed.startsWith("event:") || trimmed.startsWith(":");
                }
                decided = true;
                diag("DETECT", "mode=" + (treatAsSse ? "SSE" : "JSONL") + " firstLine=" + safe(trimmed));
                // Persist AUTO-detect result for UI hinting (shown in Streaming Output settings).
                if (streamingMode == SPManager.STREAM_MODE_AUTO) {
                    try {
                        SPManager.getInstance().setStreamingOutputAutoDetectedMode(treatAsSse ? SPManager.STREAM_MODE_SSE : SPManager.STREAM_MODE_JSONL);
                    } catch (Throwable ignored) {
                    }
                }
            }

            if (treatAsSse) {
                // Ignore comments / event metadata.
                if (trimmed.startsWith(":") || trimmed.startsWith("event:")) continue;

                List<String> payloads = splitSsePayloadsFromLine(trimmed);
                for (String payload : payloads) {
                    if (payload == null) continue;
                    String p = payload.trim();
                    if (p.isEmpty()) continue;
                    frames++;
                    if (p.contains("\"content\":null")) nullContentFrames++;
                    if (p.contains("\"reasoning_content\"")) reasoningFrames++;
                    if ("[DONE]".equals(p) || "DONE".equalsIgnoreCase(p)) {
                        diag("DONE", "SSE_DONE");
                        done = true;
                        break;
                    }

                    String errMsg = tryExtractTopLevelErrorMessage(p);
                    if (errMsg != null) {
                        diag("API_ERROR", safe(errMsg));
                        throw new RuntimeException(errMsg);
                    }
String piece = tryExtractDelta(p);
if (piece != null && !piece.isEmpty()) {
    subscriber.onNext(piece);
    emittedAny = true;
    if (emitted != null) emitted.append(piece);
} else {
    // Keep the request watchdog alive for providers/models that stream reasoning first
    // while the final answer "content" remains null (e.g., DeepSeek thinking models).
    if (hasNonEmptyReasoningDelta(p)) {
        long now = System.currentTimeMillis();
        if ((now - lastKeepAliveAtMs) > 2000L) {
            subscriber.onNext(LanguageModelClient.INTERNAL_KEEPALIVE_MARKER);
            lastKeepAliveAtMs = now;
        }
    }
}
                }
                if (done) break;
                continue;
            }

            // JSONL
            frames++;
            if (trimmed.contains("\"content\":null")) nullContentFrames++;
            if (trimmed.contains("\"reasoning_content\"")) reasoningFrames++;
            String errMsg = tryExtractTopLevelErrorMessage(trimmed);
            if (errMsg != null) {
                diag("API_ERROR", safe(errMsg));
                throw new RuntimeException(errMsg);
            }
String piece = tryExtractDelta(trimmed);
if (piece != null && !piece.isEmpty()) {
    subscriber.onNext(piece);
    emittedAny = true;
    if (emitted != null) emitted.append(piece);
} else {
    if (hasNonEmptyReasoningDelta(trimmed)) {
        long now = System.currentTimeMillis();
        if ((now - lastKeepAliveAtMs) > 2000L) {
            subscriber.onNext(LanguageModelClient.INTERNAL_KEEPALIVE_MARKER);
            lastKeepAliveAtMs = now;
        }
    }
}
        }

        if (raw != null) {
            String rawErr = tryExtractTopLevelErrorMessage(raw.toString());
            if (rawErr != null) {
                diag("API_ERROR", safe(rawErr));
                throw new RuntimeException(rawErr);
            }
        }

        // Robust fallback / tail-recovery:
        // If we can reconstruct more text from the raw response than we emitted (common when a proxy
        // concatenates frames or injects non-JSON noise), append only the missing tail.
        if (fallbackNonStreamEnabled && raw != null) {
            String reconstructed = reconstructFromRaw(raw.toString());
            if (reconstructed != null && !reconstructed.isEmpty()) {
                String already = emitted == null ? "" : emitted.toString();
                if (already == null) already = "";
                if (already.isEmpty()) {
                    diag("FALLBACK_RECONSTRUCT", "mode=all chars=" + reconstructed.length());
                    subscriber.onNext(reconstructed);
                    emittedAny = emittedAny || !reconstructed.isEmpty();
                } else if (reconstructed.startsWith(already) && reconstructed.length() > already.length()) {
                    String tail = reconstructed.substring(already.length());
                    diag("FALLBACK_RECONSTRUCT", "mode=tail chars=" + tail.length());
                    subscriber.onNext(tail);
                    emittedAny = emittedAny || !tail.isEmpty();
                } else if (reconstructed.length() > already.length() + 64) {
                    // Worst-case: avoid silent truncation (may duplicate a little in rare edge cases).
                    diag("FALLBACK_RECONSTRUCT", "mode=dup chars=" + reconstructed.length());
                    subscriber.onNext(reconstructed);
                    emittedAny = emittedAny || !reconstructed.isEmpty();
                }
            }
        }
        diag("STATS", "frames=" + frames + " nullContentFrames=" + nullContentFrames + " reasoningFrames=" + reasoningFrames + " emittedAny=" + emittedAny);
        if (!emittedAny) {
            diag("EMPTY", "no_parsable_content");
        } else {
            diag("COMPLETE", "ok");
        }

    }

    /**
     * Split a single SSE line into one or more payloads.
     * Handles:
     *  - "data: {...}"
     *  - "data: {...}data:{...}" (concatenated frames in one line)
     *  - "data: [DONE]"
     *  - Rare case: the line does not start with data: but still contains it (garbled proxy output)
     */
    private static List<String> splitSsePayloadsFromLine(String trimmedLine) {
        ArrayList<String> out = new ArrayList<>();
        if (trimmedLine == null) return out;

        String t = trimmedLine.trim();
        if (t.isEmpty()) return out;

        // If the line does not begin with data: but contains it, drop any leading noise.
        int first = t.startsWith("data:") ? 0 : t.indexOf("data:");
        if (first >= 0) {
            t = t.substring(first);
        }

        if (t.startsWith("data:")) {
            // Strip the first prefix and then split any concatenated frames.
            String payloadLine = t.substring(5).trim();
            splitConcatenatedPayloads(payloadLine, out);
        } else {
            // No data: prefix at all (some relays). Treat as JSON directly.
            out.add(t);
        }

        return out;
    }

    /**
     * Split concatenated frames such as:
     *   "{...}data:{...}data:[DONE]"
     * without accidentally splitting when "data:" appears inside JSON string content.
     *
     * Heuristic: treat "data:" as a frame boundary only when:
     *  - the previous non-whitespace char is '}' or ']'
     *  - the next non-whitespace char after "data:" is '{' or '['
     */
    private static void splitConcatenatedPayloads(String payloadLine, List<String> out) {
        if (payloadLine == null) return;
        String s = payloadLine;
        if (s.isEmpty()) return;

        int start = 0;
        while (true) {
            int next = findNextFrameBoundary(s, start);
            if (next < 0) {
                String last = s.substring(start).trim();
                if (!last.isEmpty()) out.add(last);
                break;
            }
            String part = s.substring(start, next).trim();
            if (!part.isEmpty()) out.add(part);
            start = next + 5; // skip "data:"
        }
    }

    private static int findNextFrameBoundary(String s, int from) {
        if (s == null) return -1;
        int i = Math.max(0, from);
        while (true) {
            int idx = s.indexOf("data:", i);
            if (idx < 0) return -1;

            // prev non-ws
            int p = idx - 1;
            while (p >= 0 && Character.isWhitespace(s.charAt(p))) p--;
            char prev = p >= 0 ? s.charAt(p) : 0;

            // next non-ws
            int n = idx + 5;
            while (n < s.length() && Character.isWhitespace(s.charAt(n))) n++;
            char next = n < s.length() ? s.charAt(n) : 0;

            boolean okPrev = (prev == '}' || prev == ']');
            boolean okNext = (next == '{' || next == '[');

            if (okPrev && okNext) return idx;

            i = idx + 5;
        }
    }

    private static String reconstructFromRaw(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;

        // Local stats for reconstruction (do NOT reuse the streaming counters above).
        int frames = 0;
        int nullContentFrames = 0;
        int reasoningFrames = 0;

        // If the raw body is actually a non-stream JSON error, do not convert it into fake content.
        if (tryExtractTopLevelErrorMessage(t) != null) {
            return null;
        }

        // Attempt to reconstruct by re-parsing the raw lines (handles concatenated SSE frames).
        StringBuilder sb = new StringBuilder();

        boolean decided = false;
        boolean treatAsSse = false;

        String[] lines = raw.split("\n");
        boolean done = false;
        for (String line : lines) {
            if (line == null) continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (!decided) {
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    treatAsSse = false;
                } else {
                    treatAsSse = trimmed.startsWith("data:") || trimmed.startsWith("event:") || trimmed.startsWith(":");
                }
                decided = true;
            }

            if (treatAsSse) {
                if (trimmed.startsWith(":") || trimmed.startsWith("event:")) continue;

                List<String> payloads = splitSsePayloadsFromLine(trimmed);
                for (String payload : payloads) {
                    if (payload == null) continue;
                    String p = payload.trim();
                    if (p.isEmpty()) continue;
                    frames++;
                    if (p.contains("\"content\":null")) nullContentFrames++;
                    if (p.contains("\"reasoning_content\"")) reasoningFrames++;
                    if ("[DONE]".equals(p) || "DONE".equalsIgnoreCase(p)) {
                        done = true;
                        break;
                    }
                    if (tryExtractTopLevelErrorMessage(p) != null) {
                        return null;
                    }
                    String piece = tryExtractDelta(p);
                    if (piece != null && !piece.isEmpty()) sb.append(piece);
                }
                if (done) break;
                continue;
            }

            if (tryExtractTopLevelErrorMessage(trimmed) != null) {
                return null;
            }
            String piece = tryExtractDelta(trimmed);
            if (piece != null && !piece.isEmpty()) sb.append(piece);
        }

        String out = sb.toString();
        diag("RECONSTRUCT_STATS", "frames=" + frames + " nullContentFrames=" + nullContentFrames + " reasoningFrames=" + reasoningFrames + " outLen=" + out.length());
        return out.isEmpty() ? null : out;
    }

    private static String tryExtractTopLevelErrorMessage(String json) {
        try {
            if (json == null) return null;
            String s = json.trim();
            if (s.isEmpty()) return null;
            JSONObject obj = new JSONObject(s);
            if (!obj.has("error")) return null;
            Object err = obj.opt("error");
            if (err instanceof JSONObject) {
                JSONObject e = (JSONObject) err;
                String msg = e.optString("message", null);
                if (msg != null && !msg.trim().isEmpty()) return msg.trim();
                String code = e.optString("code", null);
                if (code != null && !code.trim().isEmpty()) return code.trim();
                return e.toString();
            }
            if (err != null) {
                String msg = String.valueOf(err).trim();
                if (!msg.isEmpty()) return msg;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    
private static boolean hasNonEmptyReasoningDelta(String json) {
    try {
        JSONObject obj = new JSONObject(json);
        JSONArray choices = obj.optJSONArray("choices");
        if (choices == null) return false;
        for (int i = 0; i < choices.length(); i++) {
            JSONObject choice = choices.optJSONObject(i);
            if (choice == null) continue;
            JSONObject delta = choice.optJSONObject("delta");
            if (delta == null) continue;

            Object rc = delta.opt("reasoning_content");
            if (rc != null && rc != JSONObject.NULL) {
                String s = String.valueOf(rc);
                if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return true;
            }
            // Some providers may use other keys; treat any non-empty "thinking"/"analysis" style delta as activity.
            Object thinking = delta.opt("thinking");
            if (thinking != null && thinking != JSONObject.NULL) {
                String s = String.valueOf(thinking);
                if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return true;
            }
            Object analysis = delta.opt("analysis");
            if (analysis != null && analysis != JSONObject.NULL) {
                String s = String.valueOf(analysis);
                if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return true;
            }
        }
    } catch (Throwable ignored) {}
    return false;
}

private static String tryExtractDelta(String json) {
    try {
        JSONObject root = new JSONObject(json);

        // OpenAI/OpenRouter style: choices[0].delta.content OR choices[0].message.content
        JSONArray choices = root.optJSONArray("choices");
        if (choices != null && choices.length() > 0) {
            JSONObject c0 = choices.optJSONObject(0);
            if (c0 != null) {
                JSONObject delta = c0.optJSONObject("delta");
                if (delta != null) {
                    // IMPORTANT: Some providers (e.g. DeepSeek thinking streams) send: "content": null
                    // org.json may stringify null as "null" in some cases; treat JSON null as absent.
                    Object cv = delta.opt("content");
                    if (cv != null && cv != JSONObject.NULL) {
                        String content = String.valueOf(cv);
                        if (!content.isEmpty()) return content;
                    }

                    // Some providers use "text" in delta instead of "content"
                    Object tv = delta.opt("text");
                    if (tv != null && tv != JSONObject.NULL) {
                        String t = String.valueOf(tv);
                        if (!t.isEmpty()) return t;
                    }

                    // DeepSeek / some relays: reasoning_content (do NOT emit by default)
                    // We intentionally ignore it here to avoid flooding the input with "thinking" text.
                }

                JSONObject message = c0.optJSONObject("message");
                if (message != null) {
                    Object mv = message.opt("content");
                    if (mv != null && mv != JSONObject.NULL) {
                        String content = String.valueOf(mv);
                        if (!content.isEmpty()) return content;
                    }
                }

                Object textV = c0.opt("text"); // some providers
                if (textV != null && textV != JSONObject.NULL) {
                    String text = String.valueOf(textV);
                    if (!text.isEmpty()) return text;
                }
            }
        }

        // Some providers: {"response":"..."} / {"content":"..."}
        Object responseV = root.opt("response");
        if (responseV != null && responseV != JSONObject.NULL) {
            String response = String.valueOf(responseV);
            if (!response.isEmpty()) return response;
        }

        Object contentV = root.opt("content");
        if (contentV != null && contentV != JSONObject.NULL) {
            String content = String.valueOf(contentV);
            if (!content.isEmpty()) return content;
        }

    } catch (Throwable ignore) {
    }
    return null;
}

}
