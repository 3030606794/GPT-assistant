package tn.eluea.kgpt.util;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import tn.eluea.kgpt.SPManager;

/**
 * Lightweight cross-process diagnostics buffer (stored in shared config via SPManager).
 * Goal: keep a small rolling log for AI request failures / fallback / trigger recovery,
 * so exported logs remain useful even when logcat capture is incomplete.
 */
public final class AiDiagnostics {
    private static final String KEY_BUFFER = "ai_diag_buffer";
    private static final int MAX_LINES = 400;
    private static final int MAX_CHARS = 120_000;

    // Last-request capture (for exporting "raw fragments" and interrupt info).
    private static final String KEY_LAST_META = "ai_diag_last_meta";
    private static final String KEY_LAST_RAW = "ai_diag_last_raw";
    private static final String KEY_LAST_INTERRUPT = "ai_diag_last_interrupt";
    private static final int MAX_LAST_RAW_LINES = 1400;
    private static final int MAX_LAST_RAW_CHARS = 220_000;
    private static final int MAX_LAST_INTERRUPT_LINES = 500;
    private static final int MAX_LAST_INTERRUPT_CHARS = 80_000;

    private AiDiagnostics() {}

    public static void append(String category, String message) {
        try {
            SPManager sp = SPManager.getInstance();
            if (sp == null || sp.getConfigClient() == null) return;
            String old = null;
            try { old = sp.getConfigClient().getString(KEY_BUFFER, ""); } catch (Throwable ignored) {}
            if (old == null) old = "";
            String line = now() + " [" + safe(category) + "] " + safe(message);

            // Mirror interrupt-related lines into the per-request interrupt buffer (for export).
            try {
                if (category != null && category.startsWith("INTERRUPT")) {
                    appendLastInterrupt(line);
                }
            } catch (Throwable ignored) {}


            ArrayDeque<String> dq = new ArrayDeque<>();
            if (!old.isEmpty()) {
                for (String s : old.split("\n")) {
                    if (s == null) continue;
                    String t = s.trim();
                    if (!t.isEmpty()) dq.addLast(t);
                }
            }
            dq.addLast(line);

            while (dq.size() > MAX_LINES) dq.pollFirst();
            int chars = 0;
            List<String> lines = new ArrayList<>(dq);
            for (int i = lines.size() - 1; i >= 0; i--) {
                chars += lines.get(i).length() + 1;
                if (chars > MAX_CHARS) {
                    lines = lines.subList(i + 1, lines.size());
                    break;
                }
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(lines.get(i));
            }
            sp.getConfigClient().putString(KEY_BUFFER, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    public static String readAll() {
        try {
            SPManager sp = SPManager.getInstance();
            if (sp == null || sp.getConfigClient() == null) return "";
            String s = sp.getConfigClient().getString(KEY_BUFFER, "");
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    public static void clear() {
        try {
            SPManager sp = SPManager.getInstance();
            if (sp == null || sp.getConfigClient() == null) return;
            sp.getConfigClient().putString(KEY_BUFFER, "");
        } catch (Throwable ignored) {
        }
    }


    // -----------------------------
    // Last-request capture helpers
    // -----------------------------

    /**
     * Mark the start of a new AI request (clears last raw/interrupt buffers).
     * @param meta single-line meta snapshot (provider/subModel/reqId/attempt/etc)
     */
    public static void beginNewRequest(String meta) {
        try {
            SPManager sp = SPManager.getInstance();
            if (sp == null || sp.getConfigClient() == null) return;
            sp.getConfigClient().putString(KEY_LAST_META, safe(meta));
            sp.getConfigClient().putString(KEY_LAST_RAW, "");
            sp.getConfigClient().putString(KEY_LAST_INTERRUPT, "");
        } catch (Throwable ignored) {}
    }

    /** Append a raw response line (SSE/JSONL) for the most recent request. */
    public static void appendLastRawLine(String line) {
        if (line == null) return;
        try {
            updateRollingText(KEY_LAST_RAW, line, MAX_LAST_RAW_LINES, MAX_LAST_RAW_CHARS);
        } catch (Throwable ignored) {}
    }

    /** Replace last raw buffer with a full blob (used for non-streaming responses). */
    public static void setLastRawBlob(String blob) {
        try {
            SPManager sp = SPManager.getInstance();
            if (sp == null || sp.getConfigClient() == null) return;
            if (blob == null) blob = "";
            if (blob.length() > MAX_LAST_RAW_CHARS) {
                // keep tail (often contains error or truncated content)
                blob = blob.substring(blob.length() - MAX_LAST_RAW_CHARS);
            }
            sp.getConfigClient().putString(KEY_LAST_RAW, blob);
        } catch (Throwable ignored) {}
    }

    /** Append interrupt-related line for the most recent request. */
    public static void appendLastInterrupt(String line) {
        if (line == null) return;
        try {
            updateRollingText(KEY_LAST_INTERRUPT, line, MAX_LAST_INTERRUPT_LINES, MAX_LAST_INTERRUPT_CHARS);
        } catch (Throwable ignored) {}
    }

    public static String readLastRequestMeta() {
        try {
            SPManager sp = SPManager.getInstance();
            if (sp == null || sp.getConfigClient() == null) return "";
            String s = sp.getConfigClient().getString(KEY_LAST_META, "");
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    public static String readLastRaw() {
        try {
            SPManager sp = SPManager.getInstance();
            if (sp == null || sp.getConfigClient() == null) return "";
            String s = sp.getConfigClient().getString(KEY_LAST_RAW, "");
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    public static String readLastInterrupt() {
        try {
            SPManager sp = SPManager.getInstance();
            if (sp == null || sp.getConfigClient() == null) return "";
            String s = sp.getConfigClient().getString(KEY_LAST_INTERRUPT, "");
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    private static void updateRollingText(String key, String newLine, int maxLines, int maxChars) {
        try {
            SPManager sp = SPManager.getInstance();
            if (sp == null || sp.getConfigClient() == null) return;
            String old = null;
            try { old = sp.getConfigClient().getString(key, ""); } catch (Throwable ignored) {}
            if (old == null) old = "";
            String line = newLine.replace('\n', ' ').replace('\u0000', ' ');
            if (line.length() > 3000) line = line.substring(0, 3000) + "…";

            java.util.ArrayDeque<String> dq = new java.util.ArrayDeque<>();
            if (!old.isEmpty()) {
                for (String s : old.split("\n")) {
                    if (s == null) continue;
                    String t = s.trim();
                    if (!t.isEmpty()) dq.addLast(t);
                }
            }
            dq.addLast(line);

            while (dq.size() > maxLines) dq.pollFirst();
            int chars = 0;
            java.util.List<String> lines = new java.util.ArrayList<>(dq);
            for (int i = lines.size() - 1; i >= 0; i--) {
                chars += lines.get(i).length() + 1;
                if (chars > maxChars) {
                    lines = lines.subList(i + 1, lines.size());
                    break;
                }
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(lines.get(i));
            }
            sp.getConfigClient().putString(key, sb.toString());
        } catch (Throwable ignored) {}
    }

    private static String now() {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        } catch (Throwable t) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    private static String safe(String s) {
        if (s == null) return "-";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() > 800) t = t.substring(0, 800) + "…";
        return t;
    }
}
