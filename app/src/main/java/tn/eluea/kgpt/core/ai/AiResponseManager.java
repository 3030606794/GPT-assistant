package tn.eluea.kgpt.core.ai;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import java.util.Random;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.widget.Toast;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.listener.GenerativeAIListener;
import tn.eluea.kgpt.llm.GenerativeAIController;
import tn.eluea.kgpt.ui.IMSController;
import tn.eluea.kgpt.ui.UiInteractor;
import tn.eluea.kgpt.core.ui.TopStatusBanner;

public class AiResponseManager implements GenerativeAIListener {

    private final GenerativeAIController mAIController;
    private final Runnable onAiPrepareCallback;
    private boolean justPrepared = true;

    // Per-request snapshots (so settings don't change mid-response)
    private boolean generatingContentEnabledSnapshot = true;
    private String generatingContentSnapshot = null; // actual placeholder inserted (after resolving defaults)
    private String suffixAfterCursorSnapshot = null; // inserted after cursor (cursor stays before it)
    private boolean vibrateOnReplySnapshot = false;
    private boolean replyStartedThisRequest = false;
    private boolean suffixInsertedThisRequest = false;

    private boolean toastEnabledSnapshot = true;
    private int completeSoundSnapshot = SPManager.GEN_SOUND_NONE;

    private int vibrateIntensityPercentSnapshot = 65;
    private int vibrateFrequencyPercentSnapshot = 70;
    private long minVibrateIntervalMsSnapshot = MIN_VIBRATE_INTERVAL_MS;
    private int vibAmplitudeSnapshot = VibrationEffect.DEFAULT_AMPLITUDE;

    private int markerStyleSnapshot = SPManager.GEN_MARKER_STYLE_PLAIN;
    private int markerColorSnapshot = SPManager.GEN_MARKER_COLOR_BLUE;
    private int markerAnimLengthSnapshot = 6;
    private int markerAnimSpeedPercentSnapshot = 70;

    private String rawSuffixKeywordSnapshot = "";
    private String currentReplyMarkerAfterCursor = "";
    private boolean replyToastShownThisRequest = false;

    // ===== Persistent "replying" toast (best-effort; Android toasts cannot be truly indefinite) =====
    private final Handler toastHandler = new Handler(Looper.getMainLooper());
    private boolean replyToastLoopRunning = false;
    private Toast replyToastInstance = null;
    private final Runnable replyToastLoopRunnable = new Runnable() {
        @Override
        public void run() {
            if (!replyToastLoopRunning) return;
            if (!generatingContentEnabledSnapshot || !toastEnabledSnapshot) return;
            showOrUpdateReplyToast();
            // Re-show slightly faster than LENGTH_LONG so it *looks* persistent.
            toastHandler.postDelayed(this, 2200L);
        }
    };
    private boolean completionSoundPlayedThisRequest = false;

    // Rainbow animation state (per request)
    private String[] rainbowBaseBlocks = null;
    private int rainbowAnimStep = 0;
    private int rainbowAnimTickCounter = 0;

    // Haptics: vibrate in sync with the streaming renderer until output finishes.
    private long lastVibrateAtMs = 0;
    private static final long MIN_VIBRATE_INTERVAL_MS = 25;

    private static final String[] RAINBOW_PALETTE = new String[]{"🟥","🟧","🟨","🟩","🟦","🟪"};

    /** Map marker animation speed percent (0..100) to toast shader sweep duration (ms). */
    private static long mapToastSweepDurationMs(int percent) {
        int p = clampInt(percent, 0, 100);
        double x = p / 100.0;
        // Quadratic curve: slow has more resolution.
        double y = x * x;
        final double min = 450.0;   // fastest sweep
        final double max = 3500.0;  // slowest sweep
        return Math.round(max - (max - min) * y);
    }

    // State for text actions (replace mode)
    private boolean isTextActionMode = false;
    private String pendingSelectedText = null;

    // When streaming output is disabled, we buffer chunks and commit once at completion.
    private final StringBuilder bufferedResponse = new StringBuilder();

    // Streaming output renderer: throttle commits so the user can actually see a stream/typing effect.
    // This also provides a "simulated" streaming effect when the backend returns the full text at once.
    private final Handler streamHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder streamPending = new StringBuilder();
    // Network/prefetch buffer. When enabled, we decouple network chunk timing from UI rendering.
    // We first prefetch enough text, then render from the buffer using a stable pacing clock.
    private final StringBuilder streamPrefetch = new StringBuilder();
    private boolean streamScheduled = false;
    private boolean streamCompleted = false;
    private boolean streamingEnabledSnapshot = false;

    // Prefetch mode (per request). Enabled for NON-LINEAR pacing to make rhythm stable across
    // network chunk bursts/pauses.
    private boolean prefetchEnabledSnapshot = false;
    private boolean prefetchRenderStarted = false;

    // Prefetch tuning (chars) snapshot per request.
    private int prefetchStartCharsSnapshot = 120;
    private int prefetchLowWatermarkSnapshot = 80;
    private int prefetchTopUpTargetSnapshot = 260;

    // Prefetch tuning defaults (chars). These are intentionally conservative.
    private static final int DEFAULT_PREFETCH_START_CHARS = 120;
    private static final int DEFAULT_PREFETCH_LOW_WATERMARK = 80;
    private static final int DEFAULT_PREFETCH_TOPUP_TARGET = 260;

    // Snapshot per-request for consistent output pacing.
    private int streamingSpeedPercentSnapshot = 60; // 0..100, 100 = fastest
    private boolean streamingSpeedAutoSnapshot = true;
    private int streamingGranularitySnapshot = SPManager.STREAM_GRANULARITY_CHARS;

    // Speed algorithm snapshot (Linear vs Non-linear)
    private int streamingSpeedAlgorithmSnapshot = SPManager.STREAM_SPEED_ALGO_LINEAR;

    // Non-linear snapshots
    private int streamingNonLinearModelSnapshot = SPManager.STREAM_NL_MODEL_MARKOV_RANDOM_WALK;
    private int nonLinearSigmaMsSnapshot = 0;
    private double nonLinearPauseMultiplierSnapshot = 2.0;

    // Per-model parameter snapshots (defaults are defined in SPManager getters)
    private int nlLcTBaseMs = 90;

    private int nlExpTMaxMs = 220;
    private int nlExpTMinMs = 28;
    private double nlExpLambda = 0.045;

    private int nlSineTBaseMs = 85;
    private int nlSineAMs = 35;
    private double nlSineOmega = 0.9;
    private double nlSinePhi = 0.0;

    private int nlDampTBaseMs = 90;
    private int nlDampAMs = 85;
    private double nlDampOmega = 1.1;
    private double nlDampZeta = 0.05;
    private double nlDampPhi = 0.0;

    private int nlSquareTBaseMs = 95;
    private int nlSquareAMs = 70;
    private double nlSquareOmega = 0.7;

    private int nlMarkovMuMs = 80;
    private double nlMarkovRho = 0.9;
    private int nlMarkovSigmaMs = 25;

    private int nlMarkovTMinMs = 30;
    private int nlMarkovTMaxMs = 450;
    private double nlMarkovPThinkProb = 0.02;

    // Runtime state for non-linear algorithms (per request)
    private int nonLinearTickIndex = 0;
    private double markovPrevMs = Double.NaN;
    private boolean punctuationPauseNextTick = false;

    private final Random nonLinearRng = new Random();

    private final Random markerRng = new Random();

    // For text rainbow marker style
    private int[] rainbowTextPaletteColors = null;
    private int rainbowTextPaletteLenSnapshot = -1;
    private int rainbowTextPaletteColorModeSnapshot = -1;

    // Stats for auto pacing.
    private int streamReceivedTotalChars = 0;
    private int streamCommittedTotalChars = 0;

    // Default pacing (used if preferences are unavailable)
    private static final long DEFAULT_TICK_MS = 110;
    private static final int DEFAULT_CHARS_PER_TICK = 12;

    // Keep the last scheduled tick delay so non-linear models advance exactly once per tick.
    // (Non-linear delays are stateful; computing delay multiple times per tick will distort rhythm.)
    private long lastTickDelayMs = DEFAULT_TICK_MS;

    private static int clampInt(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    /**
     * Map speed percent (0..100) to a base tick interval in ms.
     * Non-linear mapping: lower speeds have more resolution.
     */
    private static long mapPercentToTickMs(int percent) {
        int p = clampInt(percent, 0, 100);
        double x = p / 100.0;
        // Non-linear curve (quadratic). 0->0, 1->1.
        double y = x * x;
        final double min = 18.0;   // fastest
        final double max = 420.0;  // slowest (allow much slower typing)
        return Math.round(max - (max - min) * y);
    }

    /** Map speed percent (0..100) to a base chars-per-tick. */
    private static int mapPercentToCharsPerTick(int percent) {
        int p = clampInt(percent, 0, 100);
        double x = p / 100.0;
        // Slightly more aggressive curve for characters.
        double y = Math.pow(x, 1.7);
        final double min = 2.0;
        final double max = 38.0;
        return (int) Math.round(min + (max - min) * y);
    }

    /**
     * Map vibration frequency percent (0..100) to a minimum interval (ms) between pulses.
     * Higher percent => more frequent vibrations.
     */
    private static long mapVibrateFrequencyPercentToIntervalMs(int percent) {
        int p = clampInt(percent, 0, 100);
        double x = p / 100.0;
        // Quadratic curve so low values have more resolution.
        double y = x * x;
        final double max = 240.0; // least frequent
        final double min = 18.0;  // most frequent
        return Math.round(max - (max - min) * y);
    }

    /**
     * Map vibration strength percent (0..100) to amplitude (1..255).
     */
    private static int mapVibrateIntensityPercentToAmplitude(int percent) {
        int p = clampInt(percent, 0, 100);
        if (p <= 0) return 0;
        // Slightly non-linear so low values are still noticeable.
        double x = p / 100.0;
        double y = Math.pow(x, 1.4);
        int amp = (int) Math.round(1.0 + (255.0 - 1.0) * y);
        if (amp < 1) amp = 1;
        if (amp > 255) amp = 255;
        return amp;
    }

    private static int safeParseInt(String s, int defVal) {
        try {
            if (s == null) return defVal;
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return defVal;
        }
    }

    /**
     * Compute effective pacing based on speed percent + auto mode.
     * Auto mode makes long answers/backlogs render faster and short answers slower.
     */
    private long computeLinearTickDelayMs() {
        long base = mapPercentToTickMs(streamingSpeedPercentSnapshot);
        if (!streamingSpeedAutoSnapshot) return base;

        // Estimate total length (as we may still be streaming).
        int estTotal = Math.max(streamReceivedTotalChars, streamCommittedTotalChars + streamPending.length());

        // Length factor: longer => faster (approaches ~1.9x)
        double lenFactor = 1.0 + 0.9 * (1.0 - Math.exp(-estTotal / 700.0));
        // Backlog factor: if backend bursts chunks, catch up faster (up to ~1.7x)
        double backlogFactor = 1.0 + Math.min(0.7, streamPending.length() / 700.0);

        double speedUp = lenFactor * backlogFactor;
        long ms = (long) Math.round(base / speedUp);
        return clampInt((int) ms, 12, (int) base);
    }

    /**
     * Compute a tick delay using the selected non-linear model.
     * This is a pure "delay" model (ms) - characters-per-tick is still controlled elsewhere.
     */
    private long computeNonLinearTickDelayMs() {
        int n = nonLinearTickIndex++;

        double ms;
        switch (streamingNonLinearModelSnapshot) {
            case SPManager.STREAM_NL_MODEL_LINEAR_CONSTANT: {
                ms = nlLcTBaseMs;
                break;
            }
            case SPManager.STREAM_NL_MODEL_EXPONENTIAL_DECAY: {
                // T(n) = Tmin + (Tmax - Tmin) * exp(-lambda * n)
                double tmax = Math.max(nlExpTMaxMs, nlExpTMinMs);
                double tmin = Math.min(nlExpTMaxMs, nlExpTMinMs);
                ms = tmin + (tmax - tmin) * Math.exp(-nlExpLambda * n);
                break;
            }
            case SPManager.STREAM_NL_MODEL_SINE_WAVE_JITTER: {
                // T(n) = Tbase + A * sin(omega * n + phi)
                ms = nlSineTBaseMs + nlSineAMs * Math.sin(nlSineOmega * n + nlSinePhi);
                break;
            }
            case SPManager.STREAM_NL_MODEL_DAMPED_OSCILLATOR: {
                // T(n) = Tbase + A * exp(-zeta*n) * cos(omega*n + phi)
                ms = nlDampTBaseMs + nlDampAMs * Math.exp(-nlDampZeta * n) * Math.cos(nlDampOmega * n + nlDampPhi);
                break;
            }
            case SPManager.STREAM_NL_MODEL_SQUARE_WAVE_BURST: {
                // T(n) = Tbase + A * sgn(sin(omega*n))
                double s = Math.sin(nlSquareOmega * n);
                double sign = (s >= 0.0) ? 1.0 : -1.0;
                ms = nlSquareTBaseMs + nlSquareAMs * sign;
                break;
            }
            case SPManager.STREAM_NL_MODEL_MARKOV_RANDOM_WALK:
            default: {
                // AR(1): T(n) = mu + rho*(T(n-1)-mu) + sigma*epsilon
                double mu = nlMarkovMuMs;
                double rho = nlMarkovRho;
                double sigma = nlMarkovSigmaMs;

                double tmin = nlMarkovTMinMs;
                double tmax = nlMarkovTMaxMs;
                if (tmax < tmin) {
                    double tmp = tmax;
                    tmax = tmin;
                    tmin = tmp;
                }

                if (Double.isNaN(markovPrevMs)) markovPrevMs = mu;
                double eps = nonLinearRng.nextGaussian();
                double next = mu + rho * (markovPrevMs - mu) + sigma * eps;

                // Clamp physical bounds.
                if (next < tmin) next = tmin;
                if (next > tmax) next = tmax;

                markovPrevMs = next;
                ms = next;

                // Occasional "thinking" stall.
                if (nlMarkovPThinkProb > 0.0 && nonLinearRng.nextDouble() < nlMarkovPThinkProb) {
                    ms = tmax;
                }
                break;
            }
        }

        // Clamp to sane range.
        if (ms < 8.0) ms = 8.0;
        if (ms > 1800.0) ms = 1800.0;

        return Math.round(ms);
    }

    /**
     * Unified tick delay used by the renderer.
     * - Linear: existing speed percent + auto pacing
     * - Non-linear: physics/random model + optional punctuation pause + Gaussian noise
     */
    private long computeTickDelayMs() {
        long ms;

        if (streamingSpeedAlgorithmSnapshot == SPManager.STREAM_SPEED_ALGO_NONLINEAR) {
            ms = computeNonLinearTickDelayMs();

            // Pause after punctuation boundaries (applied to NEXT tick).
            if (punctuationPauseNextTick && nonLinearPauseMultiplierSnapshot > 1.0) {
                ms = (long) Math.round(ms * nonLinearPauseMultiplierSnapshot);
            }

            // Gaussian noise (ms)
            if (nonLinearSigmaMsSnapshot > 0) {
                double noise = nonLinearRng.nextGaussian() * nonLinearSigmaMsSnapshot;
                ms = (long) Math.round(ms + noise);
            }

        } else {
            ms = computeLinearTickDelayMs();
        }

        // Consume punctuation flag.
        punctuationPauseNextTick = false;

        // Final clamp.
        if (ms < 12) ms = 12;
        if (ms > 2000) ms = 2000;
        return ms;
    }


    private int computeCharsThisTick(long tickMs) {
        // In non-linear mode, keep slices smaller so the rhythm (pause/jitter) is actually visible.
        // If we scale chars-per-tick with tickMs here, the perceived speed becomes almost constant
        // and the non-linear delay curve feels like it "does nothing".
        if (streamingSpeedAlgorithmSnapshot == SPManager.STREAM_SPEED_ALGO_NONLINEAR) {
            int base = mapPercentToCharsPerTick(streamingSpeedPercentSnapshot); // 2..38
            int n = (int) Math.round(base / 4.0); // ~1..10
            return clampInt(n, 1, 12);
        }

        int base = mapPercentToCharsPerTick(streamingSpeedPercentSnapshot);
        if (!streamingSpeedAutoSnapshot) {
            return clampInt(base, 1, 80);
        }
        int estTotal = Math.max(streamReceivedTotalChars, streamCommittedTotalChars + streamPending.length());

        // Longer => increase chars per tick (up to ~2x)
        double lenFactor = 0.85 + 1.15 * (1.0 - Math.exp(-estTotal / 500.0));
        // If we have a big pending backlog, increase per-tick slice so it doesn't take forever.
        double backlogFactor = 1.0 + Math.min(1.0, streamPending.length() / 500.0);

        // Keep overall feel stable by scaling with tick interval.
        // If tickMs is bigger (slow), commit a bit more per tick; if smaller, commit less.
        double tickNorm = tickMs / (double) DEFAULT_TICK_MS;

        int n = (int) Math.round(base * lenFactor * backlogFactor * Math.max(0.65, Math.min(1.6, tickNorm)));
        return clampInt(n, 1, 120);
    }

    private static boolean isPunctuationBoundary(char ch) {
        // Common English + Chinese punctuation, plus line breaks.
        switch (ch) {
            case '\n':
            case '\r':
            case '.':
            case '!':
            case '?':
            case ',':
            case ';':
            case ':':
            case '。':
            case '！':
            case '？':
            case '，':
            case '；':
            case '：':
            case '、':
            case ')':
            case '）':
            case ']':
            case '】':
            case '"':
            case '”':
            case '’':
                return true;
            default:
                return false;
        }
    }

    /**
     * Snap the slice length to a boundary so the stream feels more "natural".
     * - WORDS: prefer whitespace boundary
     * - PUNCT: prefer punctuation boundary; fallback to whitespace
     */
    private static int snapToBoundary(CharSequence pending, int max, int granularity) {
        int len = pending != null ? pending.length() : 0;
        int limit = Math.min(Math.max(1, max), len);
        if (len <= 1 || limit <= 1) return limit;

        int minAccept = Math.max(1, limit / 3); // avoid tiny output pieces

        if (granularity == SPManager.STREAM_GRANULARITY_WORDS) {
            for (int i = limit - 1; i >= 0; i--) {
                char ch = pending.charAt(i);
                if (Character.isWhitespace(ch)) {
                    int snapped = i + 1;
                    return snapped >= minAccept ? snapped : limit;
                }
            }
            return limit;
        }

        if (granularity == SPManager.STREAM_GRANULARITY_PUNCT) {
            for (int i = limit - 1; i >= 0; i--) {
                if (isPunctuationBoundary(pending.charAt(i))) {
                    int snapped = i + 1;
                    return snapped >= minAccept ? snapped : limit;
                }
            }
            // Fallback to whitespace if no punctuation boundary.
            for (int i = limit - 1; i >= 0; i--) {
                if (Character.isWhitespace(pending.charAt(i))) {
                    int snapped = i + 1;
                    return snapped >= minAccept ? snapped : limit;
                }
            }
            return limit;
        }

        return limit;
    }

    private final Runnable streamTick = new Runnable() {
        @Override
        public void run() {
            streamScheduled = false;
            if (!streamingEnabledSnapshot) {
                streamPending.setLength(0);
                streamCompleted = false;
                return;
            }

            // Prefetch mode: keep the render buffer topped-up so output pacing is less sensitive
            // to backend chunk timing.
            if (prefetchEnabledSnapshot && prefetchRenderStarted) {
                if (streamPending.length() < prefetchLowWatermarkSnapshot) {
                    topUpFromPrefetchIfNeeded(prefetchTopUpTargetSnapshot);
                }
            }

            if (streamPending.length() == 0) {
                // Nothing to flush.
                if (streamCompleted) {
                    finishStreamingIfNeeded();
                    return;
                }

                // In prefetch mode, keep the pacing clock alive so we don't "burst" immediately
                // when the next network chunk arrives. We do NOT advance the non-linear model
                // state here (no delay recomputation) to keep rhythm consistent with commits.
                if (prefetchEnabledSnapshot && prefetchRenderStarted) {
                    IMSController.getInstance().startInputLock();
                    scheduleStreamTickDelayed(lastTickDelayMs);
                }
                return;
            }

            // Clear placeholder on first real output.
            IMSController.getInstance().endInputLock();
            clearGeneratingContent();
            // Switch to "replying" state only when we are about to show real output.
            if (!replyStartedThisRequest) {
                replyStartedThisRequest = true;
                ensureReplySuffixInsertedIfNeeded();
                showReplyingToastIfNeeded();
            }
            IMSController.getInstance().flush();

            // Take a small slice to commit (avoid splitting surrogate pairs).
            // NOTE: Use the last scheduled delay for chars-per-tick sizing.
            // The delay for the *next* tick will be computed after we commit this piece.
            long tickMsForSizing = lastTickDelayMs;
            int charsThisTick = computeCharsThisTick(tickMsForSizing);
            int n = Math.min(charsThisTick, streamPending.length());

            // Apply granularity (chars/words/punctuation)
            try {
                if (streamingGranularitySnapshot != SPManager.STREAM_GRANULARITY_CHARS) {
                    n = snapToBoundary(streamPending, n, streamingGranularitySnapshot);
                }
            } catch (Throwable ignored) {}

            if (n > 0 && n < streamPending.length()) {
                char c = streamPending.charAt(n - 1);
                if (Character.isHighSurrogate(c)) {
                    n = Math.max(0, n - 1);
                }
            }
            if (n <= 0) {
                n = Math.min(1, streamPending.length());
            }
            String piece = streamPending.substring(0, n);
            streamPending.delete(0, n);

            IMSController.getInstance().commit(piece);
            streamCommittedTotalChars += piece.length();

            // Haptics: vibrate in sync with the renderer while outputting.
            if (replyStartedThisRequest) {
                vibrateForStreamTick(tickMsForSizing, piece.length());
            }


            // Marker animation (after-cursor rainbow) in sync with the renderer ticks.
            if (replyStartedThisRequest) {
                maybeAdvanceRainbowMarkerOnTick();
            }

            // Non-linear pause after punctuation: if the piece ends with punctuation, delay the NEXT tick.
            try {
                if (piece != null && !piece.isEmpty()) {
                    char last = piece.charAt(piece.length() - 1);
                    punctuationPauseNextTick = isPunctuationBoundary(last);
                }
            } catch (Throwable ignored) {}

            // If more to output, keep locking to prevent self-trigger recursion.
            if (streamPending.length() > 0 || !streamCompleted) {
                IMSController.getInstance().startInputLock();
                // Compute delay ONCE for the next tick (stateful for non-linear models).
                long nextDelay;
                try {
                    nextDelay = computeTickDelayMs();
                } catch (Throwable ignored) {
                    nextDelay = DEFAULT_TICK_MS;
                }
                lastTickDelayMs = nextDelay;
                scheduleStreamTickDelayed(nextDelay);
            } else {
                // All done.
                finishStreamingIfNeeded();
            }
        }
    };

    /** Schedule an immediate tick (first output should appear as soon as we have data). */
    private void scheduleStreamTickNow() {
        if (streamScheduled) return;
        streamScheduled = true;
        streamHandler.post(streamTick);
    }

    /** Schedule a paced tick to make the output feel like streaming/typing. */
    private void scheduleStreamTickDelayed(long delayMs) {
        if (streamScheduled) return;
        streamScheduled = true;
        long ms = delayMs;
        if (ms < 0) ms = 0;
        streamHandler.postDelayed(streamTick, ms);
    }

    private void cancelStreamTicks() {
        streamHandler.removeCallbacks(streamTick);
        streamScheduled = false;
    }

    /**
     * Move up to {@code targetPending} chars from the prefetch buffer into the render buffer.
     * This helps keep output pacing stable even if the backend delivers chunks unevenly.
     */
    private void topUpFromPrefetchIfNeeded(int targetPending) {
        if (!prefetchEnabledSnapshot) return;
        if (streamPrefetch.length() == 0) return;
        int need = targetPending - streamPending.length();
        if (need <= 0) return;
        int take = Math.min(need, streamPrefetch.length());
        if (take <= 0) return;
        try {
            streamPending.append(streamPrefetch, 0, take);
            streamPrefetch.delete(0, take);
        } catch (Throwable ignored) {
            // Fallback: substring copy
            try {
                String s = streamPrefetch.substring(0, take);
                streamPending.append(s);
                streamPrefetch.delete(0, take);
            } catch (Throwable ignored2) {}
        }
    }

    private void finishStreamingIfNeeded() {
        cancelStreamTicks();
        streamCompleted = false;
        streamPending.setLength(0);
        streamPrefetch.setLength(0);
        prefetchRenderStarted = false;
        prefetchEnabledSnapshot = false;

        // Remove trailing "AI replying" keyword after output finishes.
        removeReplySuffixIfPresent();
        // Stop "replying" toast loop and show completion status.
        stopReplyingToastLoop(/*showDoneToast*/true);
        // Sound: play once after output completes.
        playCompletionSoundIfNeeded();
        replyStartedThisRequest = false;
        lastVibrateAtMs = 0;

        IMSController.getInstance().endInputLock();
        IMSController.getInstance().startNotifyInput();
        // Reset text action mode
        setTextActionMode(false, null);
    }

    // Use method to get string to support locale changes and resources
    private String getDefaultGeneratingContentString() {
        Context ctx = UiInteractor.getInstance().getContext();
        if (ctx != null) {
            try {
                return ctx.getString(R.string.generating_content);
            } catch (Exception e) {
                // Fallback if resource not found (e.g. running in Xposed context with wrong
                // Resources)
                return "<Generating Content...>";
            }
        }
        return "<Generating Content...>";
    }

    private void vibratePulseMs(long pulseMs) {
        if (!vibrateOnReplySnapshot) return;
        if (vibrateIntensityPercentSnapshot <= 0) return;
        long now = System.currentTimeMillis();
        long minGap = Math.max(12L, minVibrateIntervalMsSnapshot);
        if (now - lastVibrateAtMs < minGap) return;
        lastVibrateAtMs = now;
        try {
            Context ctx = UiInteractor.getInstance().getContext();
            if (ctx == null) return;
            Vibrator vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (vib == null) return;

            long d = pulseMs;
            if (d < 6) d = 6;
            if (d > 70) d = 70;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                int amp = vibAmplitudeSnapshot;
                if (amp <= 0) amp = VibrationEffect.DEFAULT_AMPLITUDE;
                if (amp > 255) amp = 255;
                VibrationEffect effect = VibrationEffect.createOneShot(d, amp);
                vib.vibrate(effect);
            } else {
                //noinspection deprecation
                vib.vibrate(d);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Emit a short vibration pulse that roughly matches the current streaming animation rhythm.
     * Called on every renderer tick that commits visible text.
     */
    private void vibrateForStreamTick(long tickMs, int committedChars) {
        if (!vibrateOnReplySnapshot) return;
        // Heuristic: slightly longer pulses on slower animation and/or larger chunks.
        double base = Math.max(8.0, Math.min(45.0, tickMs * 0.16));
        double byLen = Math.max(6.0, Math.min(45.0, committedChars * 1.6));
        long pulse = Math.round((base + byLen) / 2.0);
        vibratePulseMs(pulse);
    }

    private int dpToPx(Context ctx, int dp) {
        try {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            return (int) (dp * dm.density + 0.5f);
        } catch (Throwable ignored) {
            return dp * 2;
        }
    }

    private void toastTopSafe(final String message, final boolean isLong) {
        if (message == null) return;
        final String msg = message.trim();
        if (msg.isEmpty()) return;
        try {
            UiInteractor.getInstance().runOnUiThread(() -> {
                try {
                    Context ctx = UiInteractor.getInstance().getContext();
                    if (ctx == null) return;
                    Toast t = Toast.makeText(ctx.getApplicationContext(), msg,
                            isLong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
                    // Place near top like a status banner.
                    t.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dpToPx(ctx, 72));
                    t.show();
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {
        }
    }

    private void toastShortSafe(final String message) {
        toastTopSafe(message, /*isLong*/false);
    }

    private String resolveThinkingToastText() {
        String s = generatingContentSnapshot;
        if (s != null && !s.trim().isEmpty()) return s.trim();
        try {
            Context ctx = UiInteractor.getInstance().getContext();
            if (ctx != null) return ctx.getString(R.string.ui_toast_ai_thinking);
        } catch (Throwable ignored) {}
        return "AI is thinking";
    }

    private String resolveReplyingToastText() {
        String s = rawSuffixKeywordSnapshot;
        if (s != null && !s.trim().isEmpty()) return s.trim();
        try {
            Context ctx = UiInteractor.getInstance().getContext();
            if (ctx != null) return ctx.getString(R.string.ui_toast_ai_replying);
        } catch (Throwable ignored) {}
        return "AI is replying";
    }

    private void showThinkingToastIfEnabled() {
        if (!generatingContentEnabledSnapshot) return;
        if (!toastEnabledSnapshot) return;

        try {
            Context ctx = UiInteractor.getInstance().getContext();
            if (ctx != null && TopStatusBanner.getInstance().canUseOverlay(ctx)) {
                // Preferred: stable persistent top banner (not limited by ROM Toast restrictions).
                TopStatusBanner.getInstance().show(ctx, resolveThinkingToastText());
            } else {
                toastShortSafe(resolveThinkingToastText());
            }
        } catch (Throwable ignored) {
            toastShortSafe(resolveThinkingToastText());
        }
    }

    private void showReplyingToastIfNeeded() {
        if (!generatingContentEnabledSnapshot) return;
        if (!toastEnabledSnapshot) return;

        // Preferred: stable persistent top banner (not limited by Toast restrictions).
        try {
            Context ctx = UiInteractor.getInstance().getContext();
            if (ctx != null && TopStatusBanner.getInstance().canUseOverlay(ctx)) {
                TopStatusBanner.getInstance().show(ctx, resolveReplyingToastText());
                replyToastShownThisRequest = true;

                // Stop any fallback loop/toast instance if they were running.
                replyToastLoopRunning = false;
                toastHandler.removeCallbacks(replyToastLoopRunnable);
                if (replyToastInstance != null) {
                    try { replyToastInstance.cancel(); } catch (Throwable ignored) {}
                    replyToastInstance = null;
                }
                return;
            }
        } catch (Throwable ignored) {}

        // Fallback: show a top Toast periodically to keep it visible.
        if (replyToastShownThisRequest) return;
        replyToastShownThisRequest = true;

        replyToastLoopRunning = true;
        toastHandler.removeCallbacks(replyToastLoopRunnable);
        toastHandler.post(() -> {
            showOrUpdateReplyToast();
            toastHandler.postDelayed(replyToastLoopRunnable, 2200L);
        });
    }

    private void showOrUpdateReplyToast() {
        // Toast custom views are restricted on Android 11+ and many ROMs.
        // Keep this fallback simple and reliable: text-only top Toast.
        toastTopSafe(resolveReplyingToastText(), false);
    }

    private String resolveDoneToastText() {
        try {
            Context ctx = UiInteractor.getInstance().getContext();
            if (ctx != null) return ctx.getString(R.string.ui_toast_ai_done);
        } catch (Throwable ignored) {}
        return "已全部完成";
    }

    private void stopReplyingToastLoop(boolean showDoneToast) {
        replyToastLoopRunning = false;
        toastHandler.removeCallbacks(replyToastLoopRunnable);
        toastHandler.post(() -> {
            try {
                if (replyToastInstance != null) {
                    try { replyToastInstance.cancel(); } catch (Throwable ignored) {}
                    replyToastInstance = null;
                }
            } catch (Throwable ignored) {}

            // Prefer banner completion message when overlay is available.
            try {
                Context ctx = UiInteractor.getInstance().getContext();
                if (ctx != null && TopStatusBanner.getInstance().canUseOverlay(ctx)) {
                    if (showDoneToast && generatingContentEnabledSnapshot && toastEnabledSnapshot) {
                        TopStatusBanner.getInstance().showDone(ctx, resolveDoneToastText());
                    } else {
                        TopStatusBanner.getInstance().hide();
                    }
                    return;
                }
            } catch (Throwable ignored) {}

            if (showDoneToast && generatingContentEnabledSnapshot && toastEnabledSnapshot) {
                toastTopSafe(resolveDoneToastText(), /*isLong*/false);
            }
        });
    }

    private void playCompletionSoundIfNeeded() {
        if (!generatingContentEnabledSnapshot) return;
        if (completionSoundPlayedThisRequest) return;
        completionSoundPlayedThisRequest = true;
        int type = completeSoundSnapshot;
        if (type == SPManager.GEN_SOUND_NONE) return;
        final Context ctx = UiInteractor.getInstance().getContext();
        if (ctx == null) return;

        UiInteractor.getInstance().runOnUiThread(() -> {
            try {
                if (type == SPManager.GEN_SOUND_SYSTEM_NOTIFICATION) {
                    Uri uri = null;
                    try { uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION); } catch (Throwable ignored) {}
                    if (uri == null) {
                        try { uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM); } catch (Throwable ignored) {}
                    }
                    if (uri == null) return;
                    Ringtone rt = RingtoneManager.getRingtone(ctx.getApplicationContext(), uri);
                    if (rt != null) rt.play();
                    return;
                }

                int tone = ToneGenerator.TONE_PROP_BEEP;
                int dur = 140;
                if (type == SPManager.GEN_SOUND_CLICK) {
                    tone = ToneGenerator.TONE_PROP_ACK;
                    dur = 90;
                }

                final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70);
                tg.startTone(tone, dur);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try { tg.release(); } catch (Throwable ignored) {}
                }, dur + 80L);
            } catch (Throwable ignored) {
            }
        });
    }

    private String pickColorBlockEmoji(int color) {
        switch (color) {
            case SPManager.GEN_MARKER_COLOR_RED: return "🟥";
            case SPManager.GEN_MARKER_COLOR_GREEN: return "🟩";
            case SPManager.GEN_MARKER_COLOR_YELLOW: return "🟨";
            case SPManager.GEN_MARKER_COLOR_PURPLE: return "🟪";
            case SPManager.GEN_MARKER_COLOR_BLUE: return "🟦";
            case SPManager.GEN_MARKER_COLOR_RANDOM: {
                try {
                    return RAINBOW_PALETTE[Math.abs(markerRng.nextInt()) % RAINBOW_PALETTE.length];
                } catch (Throwable ignored) {
                    return "🟦";
                }
            }
            default: return "🟦";
        }
    }

    private void initRainbowBaseBlocksIfNeeded() {
        if (rainbowBaseBlocks != null && rainbowBaseBlocks.length > 0) return;
        int len = markerAnimLengthSnapshot;
        if (len <= 0) len = 6;
        if (len > 30) len = 30;

        List<String> palette = new ArrayList<>(Arrays.asList(RAINBOW_PALETTE));
        if (markerColorSnapshot == SPManager.GEN_MARKER_COLOR_RANDOM) {
            Collections.shuffle(palette, markerRng);
        } else {
            String start = pickColorBlockEmoji(markerColorSnapshot);
            int idx = palette.indexOf(start);
            if (idx >= 0) {
                // rotate so the chosen color leads
                Collections.rotate(palette, -idx);
            }
        }

        String[] out = new String[len];
        int pos = 0;
        while (pos < len) {
            if (markerColorSnapshot == SPManager.GEN_MARKER_COLOR_RANDOM) {
                Collections.shuffle(palette, markerRng);
            }
            for (String c : palette) {
                if (pos >= len) break;
                out[pos++] = c;
            }
        }
        rainbowBaseBlocks = out;
        rainbowAnimStep = 0;
        rainbowAnimTickCounter = 0;
    }


private void initRainbowTextPaletteIfNeeded() {
    int paletteLen = markerAnimLengthSnapshot;
    if (paletteLen <= 0) paletteLen = 6;
    if (paletteLen < 3) paletteLen = 3;
    if (paletteLen > 10) paletteLen = 10;

    if (rainbowTextPaletteColors != null
            && rainbowTextPaletteLenSnapshot == paletteLen
            && rainbowTextPaletteColorModeSnapshot == markerColorSnapshot) {
        return;
    }

    // Base rainbow palette (10 colors)
    int[] base = new int[]{
            Color.parseColor("#FF3B30"), // red
            Color.parseColor("#FF9500"), // orange
            Color.parseColor("#FFCC00"), // yellow
            Color.parseColor("#34C759"), // green
            Color.parseColor("#00C7BE"), // teal
            Color.parseColor("#007AFF"), // blue
            Color.parseColor("#5856D6"), // indigo
            Color.parseColor("#AF52DE"), // purple
            Color.parseColor("#FF2D55"), // pink
            Color.parseColor("#8E8E93"), // gray (fallback)
    };

    int[] palette = new int[paletteLen];
    for (int i = 0; i < paletteLen; i++) {
        palette[i] = base[i % base.length];
    }

    // Apply color mode: fixed color tints, or random shuffle
    if (markerColorSnapshot == SPManager.GEN_MARKER_COLOR_RANDOM) {
        for (int i = paletteLen - 1; i > 0; i--) {
            int j = markerRng.nextInt(i + 1);
            int tmp = palette[i];
            palette[i] = palette[j];
            palette[j] = tmp;
        }
    } else if (markerColorSnapshot == SPManager.GEN_MARKER_COLOR_GREEN) {
        for (int i = 0; i < paletteLen; i++) palette[i] = Color.parseColor("#34C759");
    } else if (markerColorSnapshot == SPManager.GEN_MARKER_COLOR_BLUE) {
        for (int i = 0; i < paletteLen; i++) palette[i] = Color.parseColor("#007AFF");
    } else if (markerColorSnapshot == SPManager.GEN_MARKER_COLOR_YELLOW) {
        for (int i = 0; i < paletteLen; i++) palette[i] = Color.parseColor("#FFCC00");
    } else if (markerColorSnapshot == SPManager.GEN_MARKER_COLOR_PURPLE) {
        for (int i = 0; i < paletteLen; i++) palette[i] = Color.parseColor("#AF52DE");
    } else if (markerColorSnapshot == SPManager.GEN_MARKER_COLOR_RED) {
        for (int i = 0; i < paletteLen; i++) palette[i] = Color.parseColor("#FF3B30");
    }

    rainbowTextPaletteColors = palette;
    rainbowTextPaletteLenSnapshot = paletteLen;
    rainbowTextPaletteColorModeSnapshot = markerColorSnapshot;
}

private CharSequence buildTextRainbowMarker(CharSequence plainText, int step) {
    if (plainText == null) return "";
    String s = plainText.toString();
    if (s.isEmpty()) return s;

    initRainbowTextPaletteIfNeeded();

    SpannableString ss = new SpannableString(s);
    int n = s.length();
    int paletteLen = (rainbowTextPaletteColors == null || rainbowTextPaletteColors.length == 0) ? 1 : rainbowTextPaletteColors.length;

    for (int i = 0; i < n; i++) {
        int color = rainbowTextPaletteColors[(i + step) % paletteLen];
        ss.setSpan(new ForegroundColorSpan(color), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    return ss;
}

    private String buildRainbowBlocksString(int step) {
        initRainbowBaseBlocksIfNeeded();
        if (rainbowBaseBlocks == null || rainbowBaseBlocks.length == 0) return "";
        int len = rainbowBaseBlocks.length;
        int shift = step % len;
        if (shift < 0) shift += len;

        // Right shift (left-to-right feeling)
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            int src = i - shift;
            if (src < 0) src += len;
            sb.append(rainbowBaseBlocks[src]);
        }
        return sb.toString();
    }

    private String buildReplyMarkerString(int step) {
        String keyword = rawSuffixKeywordSnapshot;
        if (keyword == null) keyword = "";
        if (keyword.trim().isEmpty()) return "";

        if (markerStyleSnapshot == SPManager.GEN_MARKER_STYLE_COLOR_TAG) {
            return pickColorBlockEmoji(markerColorSnapshot) + keyword; // B format: no space
        }
        if (markerStyleSnapshot == SPManager.GEN_MARKER_STYLE_RAINBOW_ANIM) {
            return buildRainbowBlocksString(step) + keyword; // B format: no space
        }
        // Plain
        return keyword;
    }


private CharSequence buildReplyMarkerCharSequence(int step) {
    String keyword = rawSuffixKeywordSnapshot;
    if (keyword == null) keyword = "";
    if (markerStyleSnapshot == SPManager.GEN_MARKER_STYLE_TEXT_RAINBOW_ANIM) {
        return buildTextRainbowMarker(keyword, step);
    }
    return buildReplyMarkerString(step);
}

    private int computeRainbowTickSkip() {
        int p = clampInt(markerAnimSpeedPercentSnapshot, 0, 100);
        double x = p / 100.0;
        double y = Math.pow(x, 1.2);
        int skip = (int) Math.round(6.0 - 5.0 * y); // 0->6 ticks, 100->1 tick
        if (skip < 1) skip = 1;
        if (skip > 12) skip = 12;
        return skip;
    }

    /**
     * Update the rainbow marker after the cursor in sync with the streaming renderer ticks.
     */
    private void maybeAdvanceRainbowMarkerOnTick() {
        if (!suffixInsertedThisRequest) return;
        if (markerStyleSnapshot != SPManager.GEN_MARKER_STYLE_RAINBOW_ANIM
                && markerStyleSnapshot != SPManager.GEN_MARKER_STYLE_TEXT_RAINBOW_ANIM) return;
        if (rawSuffixKeywordSnapshot == null || rawSuffixKeywordSnapshot.trim().isEmpty()) return;
        if (currentReplyMarkerAfterCursor == null || currentReplyMarkerAfterCursor.isEmpty()) return;

        int skip = computeRainbowTickSkip();
        rainbowAnimTickCounter++;
        if ((rainbowAnimTickCounter % skip) != 0) return;


rainbowAnimStep++;

try {
    if (markerStyleSnapshot == SPManager.GEN_MARKER_STYLE_RAINBOW_ANIM) {
        String next = buildReplyMarkerString(rainbowAnimStep);
        if (next == null || next.isEmpty()) return;
        if (next.equals(currentReplyMarkerAfterCursor)) return;

        boolean ok = IMSController.getInstance().replaceAfterCursorIfMatches(currentReplyMarkerAfterCursor, next);
        if (ok) {
            currentReplyMarkerAfterCursor = next;
        }
    } else if (markerStyleSnapshot == SPManager.GEN_MARKER_STYLE_TEXT_RAINBOW_ANIM) {
        CharSequence styled = buildReplyMarkerCharSequence(rainbowAnimStep);
        IMSController.getInstance().replaceAfterCursorIfMatches(currentReplyMarkerAfterCursor, styled);
    }
} catch (Throwable ignored) {
}
    }

    private void ensureReplySuffixInsertedIfNeeded() {
        if (suffixInsertedThisRequest) return;
        if (!generatingContentEnabledSnapshot) return;
        String keyword = rawSuffixKeywordSnapshot;
        if (keyword == null || keyword.trim().isEmpty()) return;


if (markerStyleSnapshot == SPManager.GEN_MARKER_STYLE_RAINBOW_ANIM) {
    initRainbowBaseBlocksIfNeeded();
} else if (markerStyleSnapshot == SPManager.GEN_MARKER_STYLE_TEXT_RAINBOW_ANIM) {
    initRainbowTextPaletteIfNeeded();
}

CharSequence markerCs = buildReplyMarkerCharSequence(rainbowAnimStep);
String markerPlain = buildReplyMarkerString(rainbowAnimStep);
if (markerPlain == null || markerPlain.isEmpty()) return;

boolean ok = IMSController.getInstance().commitAfterCursor(markerCs);
if (ok) {
    currentReplyMarkerAfterCursor = markerPlain;
    suffixInsertedThisRequest = true;
}
    }

    private void removeReplySuffixIfPresent() {
        if (!suffixInsertedThisRequest) return;

        String expected = currentReplyMarkerAfterCursor;
        if (expected == null || expected.isEmpty()) {
            expected = buildReplyMarkerString(rainbowAnimStep);
        }

        try {
            boolean ok = false;
            if (expected != null && !expected.isEmpty()) {
                ok = IMSController.getInstance().tryDeleteAfterCursorIfMatches(expected);
            }
            if (!ok) {
                String keyword = rawSuffixKeywordSnapshot;
                if (keyword != null && !keyword.trim().isEmpty()) {
                    ok = IMSController.getInstance().tryDeleteAfterCursorIfMatches(keyword);
                }
            }
            if (!ok && expected != null && !expected.isEmpty()) {
                // last resort (best-effort)
                IMSController.getInstance().deleteAfterCursorIfMatches(expected);
            }
        } catch (Throwable ignored) {
        }

        suffixInsertedThisRequest = false;
        currentReplyMarkerAfterCursor = "";
        rainbowBaseBlocks = null;
        rainbowAnimStep = 0;
        rainbowAnimTickCounter = 0;
    }

    // Thread pool for AI requests - reuse threads instead of creating new ones
    private static final ExecutorService aiExecutor = Executors.newFixedThreadPool(2);

    // Shutdown hook to clean up executor
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            aiExecutor.shutdown();
            try {
                if (!aiExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    aiExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                aiExecutor.shutdownNow();
            }
        }));
    }

    public AiResponseManager(GenerativeAIController aiController, Runnable onAiPrepareCallback) {
        this.mAIController = aiController;
        this.onAiPrepareCallback = onAiPrepareCallback;
        this.mAIController.addListener(this);
    }

    public void generateResponse(String prompt, String systemMessage) {
        generateResponse(prompt, systemMessage, null);
    }

    /**
     * Generate AI response with an optional role (persona) override.
     * If roleIdOverride is null/blank, the currently active role is used.
     */
    public void generateResponse(String prompt, String systemMessage, String roleIdOverride) {
        // If prompt is empty, don't trigger anything - treat as normal text
        if (prompt == null || prompt.trim().isEmpty()) {
            return;
        }

        if (mAIController.needModelClient()) {
            if (UiInteractor.getInstance().showChoseModelDialog()) {
                Context ctx = UiInteractor.getInstance().getContext();
                String msg = ctx != null ? ctx.getString(R.string.choose_model_message)
                        : "Chose and configure your language model";
                UiInteractor.getInstance().toastLong(msg);
            }
            return;
        }

        if (mAIController.needApiKey()) {
            if (UiInteractor.getInstance().showChoseModelDialog()) {
                Context ctx = UiInteractor.getInstance().getContext();
                String msg = ctx != null
                        ? ctx.getString(R.string.missing_api_key_message, mAIController.getLanguageModel().label)
                        : mAIController.getLanguageModel().label + " is Missing API Key";
                UiInteractor.getInstance().toastLong(msg);
            }
            return;
        }

        // Use thread pool instead of creating new threads
        aiExecutor.execute(() -> {
            boolean useMemory = !isTextActionMode;
            try {
                // Prefer role+memory API if available
                mAIController.generateResponse(prompt, systemMessage, roleIdOverride, useMemory);
            } catch (Throwable t1) {
                try {
                    // Fall back to role-aware signature
                    mAIController.generateResponse(prompt, systemMessage, roleIdOverride);
                } catch (Throwable t2) {
                    // Backward compatibility: legacy signature
                    mAIController.generateResponse(prompt, systemMessage);
                }
            }
        });
    }

    public void setTextActionMode(boolean enabled, String selectedText) {
        this.isTextActionMode = enabled;
        this.pendingSelectedText = selectedText;
    }

    public GenerativeAIController getController() {
        return mAIController;
    }

    // --- GenerativeAIListener Implementation ---

    @Override
    public void onAIPrepare() {
        // In case a previous request didn't finish cleanly.
        stopReplyingToastLoop(/*showDoneToast*/false);
        bufferedResponse.setLength(0);
        streamPending.setLength(0);
        streamPrefetch.setLength(0);
        cancelStreamTicks();
        streamCompleted = false;
        streamReceivedTotalChars = 0;
        streamCommittedTotalChars = 0;

        prefetchEnabledSnapshot = false;
        prefetchRenderStarted = false;

        // Reset non-linear runtime state per request
        nonLinearTickIndex = 0;
        markovPrevMs = Double.NaN;
        punctuationPauseNextTick = false;

        // Snapshot settings once per request so behavior doesn't change mid-response.
        streamingSpeedPercentSnapshot = 60;
        streamingSpeedAutoSnapshot = true;
        streamingGranularitySnapshot = SPManager.STREAM_GRANULARITY_CHARS;
        streamingSpeedAlgorithmSnapshot = SPManager.STREAM_SPEED_ALGO_LINEAR;
        streamingEnabledSnapshot = false;

        // Reset prefetch defaults per request (so a failed prefs read won't reuse old values).
        prefetchStartCharsSnapshot = DEFAULT_PREFETCH_START_CHARS;
        prefetchLowWatermarkSnapshot = DEFAULT_PREFETCH_LOW_WATERMARK;
        prefetchTopUpTargetSnapshot = DEFAULT_PREFETCH_TOPUP_TARGET;
        try {
            boolean userStreamingEnabled = tn.eluea.kgpt.SPManager.getInstance().getStreamingOutputEnabled();
            streamingSpeedPercentSnapshot = tn.eluea.kgpt.SPManager.getInstance().getStreamingOutputSpeedPercent();
            streamingSpeedAutoSnapshot = tn.eluea.kgpt.SPManager.getInstance().getStreamingOutputSpeedAutoEnabled();
            streamingGranularitySnapshot = tn.eluea.kgpt.SPManager.getInstance().getStreamingOutputGranularity();
            streamingSpeedAlgorithmSnapshot = tn.eluea.kgpt.SPManager.getInstance().getStreamingOutputSpeedAlgorithm();

            // If user selects the non-linear algorithm, they almost certainly expect a paced/typewriter
            // output. When the "streaming output" toggle is OFF, the app used to commit everything at
            // once (making the non-linear settings appear to not work). To reduce confusion, we
            // auto-enable local pacing for this request when NONLINEAR is selected.
            streamingEnabledSnapshot = userStreamingEnabled
                    || (streamingSpeedAlgorithmSnapshot == SPManager.STREAM_SPEED_ALGO_NONLINEAR);

            // Prefetch buffering: decouple network chunk jitter from UI rendering.
            // Only enable for NONLINEAR (unless user explicitly turns it off).
            int pfMode = tn.eluea.kgpt.SPManager.getInstance().getStreamingPrefetchMode();
            prefetchEnabledSnapshot = streamingEnabledSnapshot
                    && (streamingSpeedAlgorithmSnapshot == SPManager.STREAM_SPEED_ALGO_NONLINEAR)
                    && (pfMode != tn.eluea.kgpt.SPManager.STREAM_PREFETCH_OFF);

            // Prefetch thresholds (per request).
            // Presets are hard-coded for stability; CUSTOM reads user values.
            int pfStart = DEFAULT_PREFETCH_START_CHARS;
            int pfLow = DEFAULT_PREFETCH_LOW_WATERMARK;
            int pfTop = DEFAULT_PREFETCH_TOPUP_TARGET;
            if (pfMode == tn.eluea.kgpt.SPManager.STREAM_PREFETCH_FAST) {
                pfStart = 60;
                pfLow = 40;
                pfTop = 160;
            } else if (pfMode == tn.eluea.kgpt.SPManager.STREAM_PREFETCH_STABLE) {
                pfStart = 180;
                pfLow = 120;
                pfTop = 360;
            } else if (pfMode == tn.eluea.kgpt.SPManager.STREAM_PREFETCH_CUSTOM) {
                pfStart = tn.eluea.kgpt.SPManager.getInstance().getStreamingPrefetchStartChars();
                pfLow = tn.eluea.kgpt.SPManager.getInstance().getStreamingPrefetchLowWatermark();
                pfTop = tn.eluea.kgpt.SPManager.getInstance().getStreamingPrefetchTopUpTarget();
            }

            // Sanity constraints to avoid weird states.
            if (pfStart < 0) pfStart = 0;
            if (pfLow < 0) pfLow = 0;
            if (pfTop < 0) pfTop = 0;
            if (pfTop < pfLow) pfTop = pfLow;
            if (pfTop < pfStart) pfTop = pfStart;
            prefetchStartCharsSnapshot = pfStart;
            prefetchLowWatermarkSnapshot = pfLow;
            prefetchTopUpTargetSnapshot = pfTop;

            // Non-linear snapshots (will be used if algorithm == NONLINEAR)
            streamingNonLinearModelSnapshot = tn.eluea.kgpt.SPManager.getInstance().getStreamingNonLinearModel();
            nonLinearSigmaMsSnapshot = tn.eluea.kgpt.SPManager.getInstance().getStreamingNonLinearSigmaMs();
            nonLinearPauseMultiplierSnapshot = tn.eluea.kgpt.SPManager.getInstance().getStreamingNonLinearPauseMultiplier();

            // Per-model params
            nlLcTBaseMs = tn.eluea.kgpt.SPManager.getInstance().getNlLinearConstantTBaseMs();

            nlExpTMaxMs = tn.eluea.kgpt.SPManager.getInstance().getNlExpTMaxMs();
            nlExpTMinMs = tn.eluea.kgpt.SPManager.getInstance().getNlExpTMinMs();
            nlExpLambda = tn.eluea.kgpt.SPManager.getInstance().getNlExpLambda();

            nlSineTBaseMs = tn.eluea.kgpt.SPManager.getInstance().getNlSineTBaseMs();
            nlSineAMs = tn.eluea.kgpt.SPManager.getInstance().getNlSineAMs();
            nlSineOmega = tn.eluea.kgpt.SPManager.getInstance().getNlSineOmega();
            nlSinePhi = tn.eluea.kgpt.SPManager.getInstance().getNlSinePhi();

            nlDampTBaseMs = tn.eluea.kgpt.SPManager.getInstance().getNlDampTBaseMs();
            nlDampAMs = tn.eluea.kgpt.SPManager.getInstance().getNlDampAMs();
            nlDampOmega = tn.eluea.kgpt.SPManager.getInstance().getNlDampOmega();
            nlDampZeta = tn.eluea.kgpt.SPManager.getInstance().getNlDampZeta();
            nlDampPhi = tn.eluea.kgpt.SPManager.getInstance().getNlDampPhi();

            nlSquareTBaseMs = tn.eluea.kgpt.SPManager.getInstance().getNlSquareTBaseMs();
            nlSquareAMs = tn.eluea.kgpt.SPManager.getInstance().getNlSquareAMs();
            nlSquareOmega = tn.eluea.kgpt.SPManager.getInstance().getNlSquareOmega();

            nlMarkovMuMs = tn.eluea.kgpt.SPManager.getInstance().getNlMarkovMuMs();
            nlMarkovRho = tn.eluea.kgpt.SPManager.getInstance().getNlMarkovRho();
            nlMarkovSigmaMs = tn.eluea.kgpt.SPManager.getInstance().getNlMarkovSigmaMs();
            nlMarkovTMinMs = tn.eluea.kgpt.SPManager.getInstance().getNlMarkovTMinMs();
            nlMarkovTMaxMs = tn.eluea.kgpt.SPManager.getInstance().getNlMarkovTMaxMs();
            nlMarkovPThinkProb = tn.eluea.kgpt.SPManager.getInstance().getNlMarkovPThinkProbability();
        } catch (Throwable ignored) {}

        // Reset last tick delay to a sane value at the start of a request.
        lastTickDelayMs = DEFAULT_TICK_MS;

        // Snapshot Generating Content settings once per request
        generatingContentEnabledSnapshot = true;
        generatingContentSnapshot = null;
        suffixAfterCursorSnapshot = "";
        rawSuffixKeywordSnapshot = "";

        toastEnabledSnapshot = true;
        completeSoundSnapshot = SPManager.GEN_SOUND_NONE;

        vibrateOnReplySnapshot = false;
        vibrateIntensityPercentSnapshot = 65;
        vibrateFrequencyPercentSnapshot = 70;
        minVibrateIntervalMsSnapshot = MIN_VIBRATE_INTERVAL_MS;
        vibAmplitudeSnapshot = VibrationEffect.DEFAULT_AMPLITUDE;

        markerStyleSnapshot = SPManager.GEN_MARKER_STYLE_PLAIN;
        markerColorSnapshot = SPManager.GEN_MARKER_COLOR_BLUE;
        markerAnimLengthSnapshot = 6;
        markerAnimSpeedPercentSnapshot = 70;

        replyStartedThisRequest = false;
        suffixInsertedThisRequest = false;
        currentReplyMarkerAfterCursor = "";
        replyToastShownThisRequest = false;
        completionSoundPlayedThisRequest = false;
        rainbowBaseBlocks = null;
        rainbowAnimStep = 0;
        rainbowAnimTickCounter = 0;
        lastVibrateAtMs = 0;

        try {
            SPManager sp = tn.eluea.kgpt.SPManager.getInstance();
            generatingContentEnabledSnapshot = sp.getGeneratingContentEnabled();

            toastEnabledSnapshot = sp.getGeneratingContentToastEnabled();
            completeSoundSnapshot = sp.getGeneratingContentCompleteSound();

            String prefPrefix = sp.getGeneratingContentPrefix();
            if (prefPrefix == null || prefPrefix.length() == 0) {
                generatingContentSnapshot = getDefaultGeneratingContentString();
            } else {
                generatingContentSnapshot = prefPrefix;
            }

            String suf = sp.getGeneratingContentSuffix();
            suffixAfterCursorSnapshot = (suf == null) ? "" : suf;
            rawSuffixKeywordSnapshot = suffixAfterCursorSnapshot;

            vibrateOnReplySnapshot = sp.getAiReplyVibrateEnabled();
            vibrateIntensityPercentSnapshot = sp.getAiReplyVibrateIntensityPercent();
            vibrateFrequencyPercentSnapshot = sp.getAiReplyVibrateFrequencyPercent();
            minVibrateIntervalMsSnapshot = mapVibrateFrequencyPercentToIntervalMs(vibrateFrequencyPercentSnapshot);
            vibAmplitudeSnapshot = mapVibrateIntensityPercentToAmplitude(vibrateIntensityPercentSnapshot);

            markerStyleSnapshot = sp.getGeneratingContentMarkerStyle();
            markerColorSnapshot = sp.getGeneratingContentMarkerColor();
            markerAnimLengthSnapshot = sp.getGeneratingContentMarkerAnimLength();
            markerAnimSpeedPercentSnapshot = sp.getGeneratingContentMarkerAnimSpeedPercent();
        } catch (Throwable ignored) {
            generatingContentEnabledSnapshot = true;
            generatingContentSnapshot = getDefaultGeneratingContentString();
            suffixAfterCursorSnapshot = "";
            rawSuffixKeywordSnapshot = "";

            toastEnabledSnapshot = true;
            completeSoundSnapshot = SPManager.GEN_SOUND_NONE;

            vibrateOnReplySnapshot = false;
            vibrateIntensityPercentSnapshot = 65;
            vibrateFrequencyPercentSnapshot = 70;
            minVibrateIntervalMsSnapshot = MIN_VIBRATE_INTERVAL_MS;
            vibAmplitudeSnapshot = VibrationEffect.DEFAULT_AMPLITUDE;

            markerStyleSnapshot = SPManager.GEN_MARKER_STYLE_PLAIN;
            markerColorSnapshot = SPManager.GEN_MARKER_COLOR_BLUE;
            markerAnimLengthSnapshot = 6;
            markerAnimSpeedPercentSnapshot = 70;
        }

        if (onAiPrepareCallback != null) {
            onAiPrepareCallback.run();
        }

        // In text action mode, delete the selected text first
        if (isTextActionMode && pendingSelectedText != null) {
            // The selected text should already be selected, so we just need to delete it
            // and the AI response will replace it
            IMSController.getInstance().flush();
        } else {
            IMSController.getInstance().flush();
        }

        // Insert only the "thinking" placeholder.
        // The trailing "replying" keyword will be inserted ONLY when the first visible output arrives.
        if (generatingContentEnabledSnapshot) {
            String generatingContent = generatingContentSnapshot;
            if (generatingContent != null && !generatingContent.isEmpty()) {
                IMSController.getInstance().commit(generatingContent);
                justPrepared = true;
            } else {
                justPrepared = false;
            }
        } else {
            justPrepared = false;
        }
        // Toast: thinking (use placeholder text by default)
        showThinkingToastIfEnabled();

        IMSController.getInstance().stopNotifyInput();
        IMSController.getInstance().startInputLock();
    }

    private void clearGeneratingContent() {
        if (justPrepared) {
            justPrepared = false;
            IMSController.getInstance().flush();
            String generatingContent = generatingContentSnapshot;
            if (generatingContent == null || generatingContent.isEmpty()) {
                generatingContent = getDefaultGeneratingContentString();
            }
            IMSController.getInstance().delete(generatingContent.length());
        }
    }

    @Override
    public void onAINext(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;

        streamReceivedTotalChars += chunk.length();

        if (!streamingEnabledSnapshot) {
            // Buffer only; commit onComplete.
            bufferedResponse.append(chunk);
            return;
        }

        if (prefetchEnabledSnapshot) {
            // Buffer into prefetch first.
            streamPrefetch.append(chunk);

            // Start rendering only when we've prefetched enough to smooth out network jitter,
            // OR if we already started.
            if (!prefetchRenderStarted) {
                if (streamPrefetch.length() >= prefetchStartCharsSnapshot) {
                    topUpFromPrefetchIfNeeded(prefetchTopUpTargetSnapshot);
                    prefetchRenderStarted = true;
                    // Start immediately so user sees the first characters.
                    scheduleStreamTickNow();
                }
                // Not enough yet: keep waiting ("Generating..." placeholder stays).
                return;
            }

            // Already started: keep render buffer topped-up and ensure the clock keeps running.
            if (streamPending.length() < prefetchLowWatermarkSnapshot) {
                topUpFromPrefetchIfNeeded(prefetchTopUpTargetSnapshot);
            }
            // Do NOT schedule an immediate tick on each chunk; resume on the existing rhythm.
            if (!streamScheduled) {
                scheduleStreamTickDelayed(lastTickDelayMs);
            }
            return;
        }

        // No prefetch: append to render buffer and kick immediately.
        streamPending.append(chunk);
        scheduleStreamTickNow();
    }

    @Override
    public void onAIError(Throwable t) {
        cancelStreamTicks();
        streamPending.setLength(0);
        streamPrefetch.setLength(0);
        streamCompleted = false;
        prefetchRenderStarted = false;
        IMSController.getInstance().endInputLock();
        clearGeneratingContent();

        // Clean up "replying" marker if it was inserted.
        removeReplySuffixIfPresent();
        stopReplyingToastLoop(/*showDoneToast*/false);
        replyStartedThisRequest = false;
        lastVibrateAtMs = 0;

        // If we were buffering, clear it (avoid committing partial output on error).
        bufferedResponse.setLength(0);

        String errorMsg = t.getMessage();
        Context ctx = UiInteractor.getInstance().getContext();
        if (errorMsg == null || errorMsg.isEmpty()) {
            errorMsg = ctx != null ? ctx.getString(R.string.unknown_error) : "Unknown error occurred";
        }

        String displayError = ctx != null ? ctx.getString(R.string.error_format, errorMsg)
                : "[Error: " + errorMsg + "]";
        IMSController.getInstance().flush();
        IMSController.getInstance().commit(displayError);
        IMSController.getInstance().startNotifyInput();

        // Reset text action mode
        setTextActionMode(false, null);
    }

    @Override
    public void onAIComplete() {
        // If streaming is disabled we commit once.
        if (!streamingEnabledSnapshot) {
            IMSController.getInstance().endInputLock();
            clearGeneratingContent();

            String out = bufferedResponse.toString();
            bufferedResponse.setLength(0);
            if (out != null && !out.isEmpty()) {
                IMSController.getInstance().flush();
                // Insert "replying" marker only when we are about to show real output.
                replyStartedThisRequest = true;
                ensureReplySuffixInsertedIfNeeded();
                showReplyingToastIfNeeded();
                // A short pulse so the user still gets feedback in non-stream mode.
                vibratePulseMs(35);
                IMSController.getInstance().commit(out);
                // Remove trailing keyword after output finishes.
                removeReplySuffixIfPresent();
                stopReplyingToastLoop(/*showDoneToast*/true);
                playCompletionSoundIfNeeded();
            }

            IMSController.getInstance().startNotifyInput();

            // Reset text action mode
            setTextActionMode(false, null);
            replyStartedThisRequest = false;
            lastVibrateAtMs = 0;
            return;
        }

        // Streaming enabled: finish only after the pending buffer is drained.
        bufferedResponse.setLength(0);
        streamCompleted = true;

        if (prefetchEnabledSnapshot) {
            // Flush all remaining prefetched text into the render buffer.
            topUpFromPrefetchIfNeeded(Integer.MAX_VALUE / 4);
            // If we never started (short answer), start now.
            if (!prefetchRenderStarted) {
                prefetchRenderStarted = true;
            }
        }
        // If no pending content (e.g., backend sent empty), finish immediately.
        if (streamPending.length() == 0) {
            finishStreamingIfNeeded();
        } else {
            scheduleStreamTickNow();
        }
    }
}
