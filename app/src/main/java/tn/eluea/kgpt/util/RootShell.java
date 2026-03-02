package tn.eluea.kgpt.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Root helper (Magisk / KernelSU / APatch).
 *
 * We intentionally keep this class dependency-free so it can be used from both:
 * - the KGPT app UI process
 * - the hooked IME process (Xposed)
 *
 * Notes:
 * - Some OEM ROMs (incl. vivo) on Android 14/15 may block cross-window paste/commit from overlay UIs.
 * - With root we can fall back to shell input injection (paste keyevent or type text).
 */
public final class RootShell {
    private RootShell() {}

    public static int execSu(String cmd) {
        if (cmd == null) return -1;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            // Drain streams to avoid deadlock on some devices
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                while (r.readLine() != null) { /* ignore */ }
            } catch (Throwable ignored) {}
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                while (r.readLine() != null) { /* ignore */ }
            } catch (Throwable ignored) {}
            return p.waitFor();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Best-effort root availability check (will trigger a su prompt on first use). */
    public static boolean hasRootAccess() {
        try {
            return execSu("id") == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String shellQuoteSingle(String s) {
        // Wrap in single quotes and escape single quotes inside: ' => '\''
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Start a VIEW intent for the given URL via root.
     */
    public static boolean startViewUrl(String url) {
        if (url == null) return false;
        String u = url.trim();
        if (u.isEmpty()) return false;

        String cmd = "am start --user 0 "
                + "-a android.intent.action.VIEW "
                + "-c android.intent.category.BROWSABLE "
                + "-f 0x10000000 "
                + "-d " + shellQuoteSingle(u);

        return execSu(cmd) == 0;
    }

    /** Try to trigger "paste" in the current focused field via shell keyevent. */
    public static boolean pasteKeyevent() {
        // KEYCODE_PASTE = 279
        int code = execSu("input keyevent 279");
        if (code == 0) return true;

        // Some ROMs prefer cmd input
        code = execSu("cmd input keyevent 279");
        return code == 0;
    }

    private static String escapeForInputText(String s) {
        if (s == null) return "";
        // Android shell "input text" uses % escapes, so escape '%' first.
        String out = s.replace("%", "%%");
        // Replace spaces/tabs with escapes to avoid shell splitting issues.
        out = out.replace(" ", "%s").replace("\t", "%t");
        return out;
    }

    /**
     * Type text into the current focused field via shell.
     * This bypasses "paste disabled" in some apps, but is slower and may not perfectly handle every emoji.
     */
    public static boolean typeText(String text) {
        String v = text == null ? "" : text;
        // Split by \n to keep commands small and handle multiline.
        String[] lines = v.split("\n", -1);

        StringBuilder cmd = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line != null && !line.isEmpty()) {
                cmd.append("input text ").append(shellQuoteSingle(escapeForInputText(line))).append("; ");
            }
            if (i < lines.length - 1) {
                // newline
                cmd.append("input keyevent 66; ");
            }
        }
        if (cmd.length() == 0) return true;

        int code = execSu(cmd.toString());
        return code == 0;
    }

    /**
     * Paste first, then fallback to typing the provided text if paste didn't work.
     * Returns true if the shell command(s) returned exit code 0.
     */
    public static boolean pasteOrType(String text) {
        if (pasteKeyevent()) return true;
        return typeText(text);
    }
}
