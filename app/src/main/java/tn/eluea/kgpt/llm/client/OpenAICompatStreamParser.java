package tn.eluea.kgpt.llm.client;

import org.json.JSONArray;
import org.json.JSONObject;
import org.reactivestreams.Subscriber;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;

import tn.eluea.kgpt.SPManager;

/**
 * Best-effort stream parser for OpenAI-compatible streaming formats.
 *
 * Supports:
 * - SSE: lines prefixed with "data:" and terminated by a blank line.
 * - JSON Lines: one JSON object per line (no "data:" prefix).
 * - AUTO: detect SSE vs JSONL based on the first non-empty line.
 *
 * Emits only incremental text deltas (content).
 *
 * Important: some proxies concatenate multiple SSE frames into a single line (e.g. "data:{...}data:{...}").
 * This parser attempts to split those frames safely (without accidentally splitting when "data:" appears
 * inside JSON string content).
 */
public final class OpenAICompatStreamParser {

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

        while ((line = reader.readLine()) != null) {
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
            }

            if (treatAsSse) {
                // Ignore comments / event metadata.
                if (trimmed.startsWith(":") || trimmed.startsWith("event:")) continue;

                List<String> payloads = splitSsePayloadsFromLine(trimmed);
                for (String payload : payloads) {
                    if (payload == null) continue;
                    String p = payload.trim();
                    if (p.isEmpty()) continue;
                    if ("[DONE]".equals(p) || "DONE".equalsIgnoreCase(p)) {
                        done = true;
                        break;
                    }

                    String piece = tryExtractDelta(p);
                    if (piece != null && !piece.isEmpty()) {
                        subscriber.onNext(piece);
                        if (emitted != null) emitted.append(piece);
                    }
                }
                if (done) break;
                continue;
            }

            // JSONL
            String piece = tryExtractDelta(trimmed);
            if (piece != null && !piece.isEmpty()) {
                subscriber.onNext(piece);
                if (emitted != null) emitted.append(piece);
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
                    subscriber.onNext(reconstructed);
                } else if (reconstructed.startsWith(already) && reconstructed.length() > already.length()) {
                    subscriber.onNext(reconstructed.substring(already.length()));
                } else if (reconstructed.length() > already.length() + 64) {
                    // Worst-case: avoid silent truncation (may duplicate a little in rare edge cases).
                    subscriber.onNext(reconstructed);
                }
            }
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
                    if ("[DONE]".equals(p) || "DONE".equalsIgnoreCase(p)) {
                        done = true;
                        break;
                    }
                    String piece = tryExtractDelta(p);
                    if (piece != null && !piece.isEmpty()) sb.append(piece);
                }
                if (done) break;
            } else {
                String piece = tryExtractDelta(trimmed);
                if (piece != null && !piece.isEmpty()) sb.append(piece);
            }
        }

        if (sb.length() > 0) return sb.toString();

        // Fallback: non-stream JSON body
        return tryExtractNonStreamContent(raw);
    }

    private static String tryExtractDelta(String jsonOrGarbage) {
        try {
            JSONObject obj = new JSONObject(jsonOrGarbage);

            // OpenAI / compatible streaming
            JSONArray choices = obj.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject c0 = choices.optJSONObject(0);
                if (c0 != null) {
                    // Chat completions stream: choices[0].delta.content
                    JSONObject delta = c0.optJSONObject("delta");
                    if (delta != null) {
                        String content = delta.optString("content", null);
                        if (content != null && !"null".equals(content)) return content;
                    }
                    // Some providers use choices[0].text
                    String text = c0.optString("text", null);
                    if (text != null && !"null".equals(text)) return text;

                    // Some providers stream as message.content
                    JSONObject message = c0.optJSONObject("message");
                    if (message != null) {
                        String content = message.optString("content", null);
                        if (content != null && !"null".equals(content)) return content;
                    }
                }
            }

            // Fallback: sometimes a direct field appears
            String content = obj.optString("content", null);
            if (content != null && !"null".equals(content)) return content;

        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String tryExtractNonStreamContent(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;

        // If response is SSE but we didn't emit any chunks (weird format), try to find the first JSON object.
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            t = t.substring(start, end + 1);
        }

        try {
            JSONObject obj = new JSONObject(t);
            JSONArray choices = obj.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject c0 = choices.optJSONObject(0);
                if (c0 != null) {
                    JSONObject message = c0.optJSONObject("message");
                    if (message != null) {
                        String content = message.optString("content", null);
                        if (content != null && !"null".equals(content)) return content;
                    }
                    String text = c0.optString("text", null);
                    if (text != null && !"null".equals(text)) return text;
                }
            }

            JSONObject message = obj.optJSONObject("message");
            if (message != null) {
                String content = message.optString("content", null);
                if (content != null && !"null".equals(content)) return content;
            }

        } catch (Throwable ignored) {
        }
        return null;
    }
}
