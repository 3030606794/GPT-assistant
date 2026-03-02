package tn.eluea.kgpt.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

/**
 * TextView with an animated left-to-right rainbow gradient fill.
 *
 * Used for the persistent "AI replying" toast so the status text is easy to spot.
 */
public class RainbowSweepTextView extends AppCompatTextView {

    private static final String[] DEFAULT_EMOJI_PALETTE = new String[]{"🟥","🟧","🟨","🟩","🟦","🟪"};

    private final Matrix shaderMatrix = new Matrix();
    private LinearGradient shader = null;
    private ValueAnimator animator = null;

    private long sweepDurationMs = 1400L;
    private int viewW = 0;
    private int[] colors = null;

    public RainbowSweepTextView(Context context) {
        super(context);
        init();
    }

    public RainbowSweepTextView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RainbowSweepTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        // Default palette.
        setPaletteEmojis(DEFAULT_EMOJI_PALETTE);
    }

    /** Update sweep duration. Higher speed => shorter duration. */
    public void setSweepDurationMs(long durationMs) {
        if (durationMs <= 0) durationMs = 450L;
        if (this.sweepDurationMs == durationMs) return;
        this.sweepDurationMs = durationMs;
        restartAnimator();
    }

    /** Set palette using emoji blocks (we map them to colors). */
    public void setPaletteEmojis(@Nullable String[] emojis) {
        String[] src = (emojis == null || emojis.length == 0) ? DEFAULT_EMOJI_PALETTE : emojis;
        // Map commonly used blocks to approximate colors.
        // (We only need a pleasant rainbow; exact mapping is not critical.)
        int[] mapped = new int[src.length];
        for (int i = 0; i < src.length; i++) {
            String e = src[i];
            mapped[i] = mapEmojiToColor(e);
        }
        this.colors = mapped;
        rebuildShader();
    }

    private static int mapEmojiToColor(String e) {
        if ("🟥".equals(e)) return Color.parseColor("#FF3B30");
        if ("🟧".equals(e)) return Color.parseColor("#FF9500");
        if ("🟨".equals(e)) return Color.parseColor("#FFCC00");
        if ("🟩".equals(e)) return Color.parseColor("#34C759");
        if ("🟦".equals(e)) return Color.parseColor("#007AFF");
        if ("🟪".equals(e)) return Color.parseColor("#AF52DE");
        // Fallback: light blue.
        return Color.parseColor("#5AC8FA");
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewW = w;
        rebuildShader();
    }

    private void rebuildShader() {
        if (viewW <= 0) return;
        if (colors == null || colors.length == 0) return;

        // Create a gradient slightly wider than the view so the animation can start off-screen.
        int w = viewW;
        shader = new LinearGradient(
                -w, 0, 0, 0,
                colors,
                null,
                Shader.TileMode.CLAMP
        );
        getPaint().setShader(shader);
        invalidate();
        restartAnimator();
    }

    private void restartAnimator() {
        stopSweep();
        // Only start if view has a shader.
        if (shader == null || viewW <= 0) return;
        startSweep();
    }

    public void startSweep() {
        if (shader == null || viewW <= 0) return;
        if (animator != null && animator.isRunning()) return;

        animator = ValueAnimator.ofFloat(0f, (float) (2 * viewW));
        animator.setDuration(sweepDurationMs);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            if (shader == null) return;
            float x = (float) a.getAnimatedValue();
            shaderMatrix.reset();
            shaderMatrix.setTranslate(x, 0);
            try { shader.setLocalMatrix(shaderMatrix); } catch (Throwable ignored) {}
            invalidate();
        });
        animator.start();
    }

    public void stopSweep() {
        try {
            if (animator != null) {
                animator.cancel();
            }
        } catch (Throwable ignored) {}
        animator = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSweep();
        super.onDetachedFromWindow();
    }
}
