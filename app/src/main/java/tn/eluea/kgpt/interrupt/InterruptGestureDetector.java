package tn.eluea.kgpt.interrupt;

import tn.eluea.kgpt.SPManager;

/**
 * Detect a very lightweight "interrupt" gesture while AI output is in progress.
 *
 * Design goals:
 * - O(1) work per InputConnection event.
 * - Pure text only (ASCII triggers) to be compatible with IME-host input boxes.
 * - Avoid false positives by using a tiny rolling buffer.
 */
public final class InterruptGestureDetector {

    private static final int MAX_BUF = 32;
    private static final long MIN_HIT_GAP_MS = 550;
    // Some IMEs have relatively slow key repeat / key dispatch under "locked" state.
    // Use a slightly larger window for better compatibility.
    private static final long DOUBLE_BACKSPACE_WINDOW_MS = 650;
    private static final long DOUBLE_SPACE_WINDOW_MS = 650;

    private static final String TRIG_BANGBANG = "!!";
    private static final String TRIG_STOP = "/stop";

    private static final StringBuilder rolling = new StringBuilder();
    private static String lastComposing = "";
    private static long lastHitAtMs = 0L;

    private static long lastBackspaceAtMs = 0L;
    private static int backspaceCount = 0;

    private static long lastSpaceAtMs = 0L;
    private static int spaceCount = 0;

    private InterruptGestureDetector() {}

    public static final class Hit {
        public final boolean hit;
        /**
         * For debugging / telemetry:
         * 0 = no special stage
         * 1 = first pulse observed (armed)
         * 2 = trigger confirmed (hit=true)
         */
        public final int stage;
        /** Best-effort delete count before cursor to remove the trigger from input box. */
        public final int deleteBeforeCursor;
        /** The trigger text that caused the hit ("!!" or "/stop"), null for backspace gesture. */
        public final String triggerText;

        public Hit(boolean hit, int stage, int deleteBeforeCursor, String triggerText) {
            this.hit = hit;
            this.stage = stage;
            this.deleteBeforeCursor = deleteBeforeCursor;
            this.triggerText = triggerText;
        }
    }

    private static Hit no() {
        return new Hit(false, 0, 0, null);
    }

    private static boolean hitCooldown() {
        long now = System.currentTimeMillis();
        return now - lastHitAtMs < MIN_HIT_GAP_MS;
    }

    private static void markHit() {
        lastHitAtMs = System.currentTimeMillis();
        rolling.setLength(0);
        lastComposing = "";
        backspaceCount = 0;
        lastBackspaceAtMs = 0L;
        spaceCount = 0;
        lastSpaceAtMs = 0L;
    }

    private static void appendRolling(String s) {
        if (s == null || s.isEmpty()) return;
        rolling.append(s);
        if (rolling.length() > MAX_BUF) {
            rolling.delete(0, rolling.length() - MAX_BUF);
        }
    }

    private static String tail(String s) {
        if (s == null) return "";
        if (s.length() <= MAX_BUF) return s;
        return s.substring(s.length() - MAX_BUF);
    }

    private static boolean endsWithIgnoreCaseAscii(String s, String suffix) {
        if (s == null || suffix == null) return false;
        if (s.length() < suffix.length()) return false;
        int off = s.length() - suffix.length();
        for (int i = 0; i < suffix.length(); i++) {
            char a = s.charAt(off + i);
            char b = suffix.charAt(i);
            if (a == b) continue;
            // ASCII fold
            if (a >= 'A' && a <= 'Z') a = (char) (a + 32);
            if (b >= 'A' && b <= 'Z') b = (char) (b + 32);
            if (a != b) return false;
        }
        return true;
    }

    /**
     * Detect interrupt triggers from commitText/setComposingText.
     *
     * @param text incoming text payload
     * @param composing true for setComposingText, false for commitText
     * @param gestureType one of SPManager.INTERRUPT_GESTURE_*
     */
    public static Hit onTextEvent(CharSequence text, boolean composing, int gestureType, String customTrigger) {
        if (gestureType == SPManager.INTERRUPT_GESTURE_DOUBLE_BACKSPACE) {
            return no();
        }
        // Double-space is handled here (commit/composing) because many IMEs send spaces via commitText.

        if (hitCooldown()) return no();
        if (text == null) return no();
        String s;
        try {
            s = text.toString();
        } catch (Throwable t) {
            return no();
        }
        if (s == null || s.isEmpty()) return no();

        if (gestureType == SPManager.INTERRUPT_GESTURE_DOUBLE_SPACE) {
            // Many IMEs dispatch spaces via commitText/setComposingText.
            // Treat a trailing space as a pulse.
            try {
                if (s.charAt(s.length() - 1) == ' ') {
                    return onSpacePulse();
                }
            } catch (Throwable ignored) {
            }
            spaceCount = 0;
            lastSpaceAtMs = 0L;
            return no();
        }

        // Composing text often repeats the full composing string; avoid duplicate appends.
        if (composing) {
            lastComposing = s;
            String t = tail(s);
            if (gestureType == SPManager.INTERRUPT_GESTURE_BANGBANG) {
                if (t.endsWith(TRIG_BANGBANG)) {
                    markHit();
                    return new Hit(true, 2, TRIG_BANGBANG.length(), TRIG_BANGBANG);
                }
            } else if (gestureType == SPManager.INTERRUPT_GESTURE_STOP) {
                if (endsWithIgnoreCaseAscii(t, TRIG_STOP)) {
                    markHit();
                    return new Hit(true, 2, TRIG_STOP.length(), TRIG_STOP);
                }
            } else if (gestureType == SPManager.INTERRUPT_GESTURE_CUSTOM) {
                String trig = customTrigger;
                if (trig != null) trig = trig.trim();
                if (trig != null && !trig.isEmpty() && t.endsWith(trig)) {
                    markHit();
                    return new Hit(true, 2, trig.length(), trig);
                }
            }
            // Also keep a rolling tail so mixed composing/commit sequences still work.
            appendRolling(t);
            return no();
        }

        // commitText: append into rolling buffer
        appendRolling(s);
        String r = rolling.toString();
        if (gestureType == SPManager.INTERRUPT_GESTURE_BANGBANG) {
            if (r.endsWith(TRIG_BANGBANG)) {
                markHit();
                return new Hit(true, 2, TRIG_BANGBANG.length(), TRIG_BANGBANG);
            }
        } else if (gestureType == SPManager.INTERRUPT_GESTURE_STOP) {
            if (endsWithIgnoreCaseAscii(r, TRIG_STOP)) {
                markHit();
                return new Hit(true, 2, TRIG_STOP.length(), TRIG_STOP);
            }
        } else if (gestureType == SPManager.INTERRUPT_GESTURE_CUSTOM) {
            String trig = customTrigger;
            if (trig != null) trig = trig.trim();
            if (trig != null && !trig.isEmpty() && r.endsWith(trig)) {
                markHit();
                return new Hit(true, 2, trig.length(), trig);
            }
        }
        return no();
    }

    public static void onFinishComposing() {
        lastComposing = "";
    }

    private static Hit onBackspacePulse() {
        if (hitCooldown()) return no();
        long now = System.currentTimeMillis();
        if (lastBackspaceAtMs > 0L && (now - lastBackspaceAtMs) <= DOUBLE_BACKSPACE_WINDOW_MS) {
            backspaceCount++;
        } else {
            backspaceCount = 1;
        }
        lastBackspaceAtMs = now;

        if (backspaceCount >= 2) {
            markHit();
            return new Hit(true, 2, 0, null);
        }
        return new Hit(false, 1, 0, null);
    }

    private static Hit onSpacePulse() {
        if (hitCooldown()) return no();

        long now = System.currentTimeMillis();
        if (lastSpaceAtMs > 0L && (now - lastSpaceAtMs) <= DOUBLE_SPACE_WINDOW_MS) {
            spaceCount++;
        } else {
            spaceCount = 1;
        }
        lastSpaceAtMs = now;

        if (spaceCount >= 2) {
            markHit();
            // Best-effort remove the two spaces from the editor.
            return new Hit(true, 2, 2, "  ");
        }
        return new Hit(false, 1, 0, null);
    }


    /**
     * Detect interrupt trigger from deleteSurroundingText/deleteSurroundingTextInCodePoints
     * (double backspace).
     */
    public static Hit onDeleteEvent(int before, int after, int gestureType) {
        if (gestureType != SPManager.INTERRUPT_GESTURE_DOUBLE_BACKSPACE) {
            return no();
        }
        if (before < 1 || after != 0) {
            // Reset sequence on other delete patterns
            backspaceCount = 0;
            lastBackspaceAtMs = 0L;
            return no();
        }
        return onBackspacePulse();
    }

    /**
     * Detect backspace gesture from sendKeyEvent.
     */
    public static Hit onKeyEvent(android.view.KeyEvent ev, int gestureType) {
        if (ev == null) return no();
        try {
            if (ev.getAction() != android.view.KeyEvent.ACTION_DOWN) return no();
            final int code = ev.getKeyCode();
            if (gestureType == SPManager.INTERRUPT_GESTURE_DOUBLE_BACKSPACE) {
                if (code != android.view.KeyEvent.KEYCODE_DEL) return no();
                return onBackspacePulse();
            }
            if (gestureType == SPManager.INTERRUPT_GESTURE_DOUBLE_SPACE) {
                if (code != android.view.KeyEvent.KEYCODE_SPACE) return no();
                return onSpacePulse();
            }
        } catch (Throwable t) {
            return no();
        }
        return no();
    }
}
