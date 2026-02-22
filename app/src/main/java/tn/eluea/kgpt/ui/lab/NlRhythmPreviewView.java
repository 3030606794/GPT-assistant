package tn.eluea.kgpt.ui.lab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;


/**
 * Lightweight rhythm curve preview for non-linear streaming:
 * x-axis: token index (n)
 * y-axis: delay (ms)
 *
 * Draws a line for the delay series, and an optional translucent band for uncertainty/noise.
 */
public class NlRhythmPreviewView extends View {

    private float[] series;
    private float[] bandLo;
    private float[] bandHi;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path linePath = new Path();
    private final Path bandPath = new Path();
    private final RectF chartRect = new RectF();

    public NlRhythmPreviewView(Context context) {
        super(context);
        init();
    }

    public NlRhythmPreviewView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public NlRhythmPreviewView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Keep attrs compatible across Material versions.
        // - `androidx.appcompat.R.attr.colorPrimary` is always available with AppCompat.
        // - `colorOnSurface` is stable in Material.
        int onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface);
        int line = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary);
        int band = line;
        int grid = onSurface;
        int text = onSurface;

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1));
        gridPaint.setColor(withAlpha(grid, 0.35f));

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeWidth(dp(2));
        linePaint.setColor(line);

        bandPaint.setStyle(Paint.Style.FILL);
        bandPaint.setColor(withAlpha(band, 0.16f));

        textPaint.setTextSize(dp(10));
        textPaint.setColor(withAlpha(text, 0.75f));
    }

    /**
     * Update chart data.
     * @param series delay series (ms)
     * @param bandLo lower band (ms), nullable
     * @param bandHi upper band (ms), nullable
     */
    public void setData(@NonNull float[] series, @Nullable float[] bandLo, @Nullable float[] bandHi) {
        this.series = series;
        this.bandLo = bandLo;
        this.bandHi = bandHi;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (series == null || series.length < 2) {
            drawEmpty(canvas);
            return;
        }

        final int w = getWidth();
        final int h = getHeight();
        if (w <= 1 || h <= 1) return;

        final float padL = getPaddingLeft();
        final float padR = getPaddingRight();
        final float padT = getPaddingTop();
        final float padB = getPaddingBottom();

        // Reserve a bit of space for tiny labels.
        final float labelH = dp(12);
        chartRect.set(padL + dp(26), padT + dp(6), w - padR - dp(6), h - padB - labelH);

        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (float v : series) {
            if (v < minY) minY = v;
            if (v > maxY) maxY = v;
        }
        if (bandLo != null && bandHi != null && bandLo.length == series.length && bandHi.length == series.length) {
            for (int i = 0; i < series.length; i++) {
                minY = Math.min(minY, bandLo[i]);
                maxY = Math.max(maxY, bandHi[i]);
            }
        }

        if (!Float.isFinite(minY) || !Float.isFinite(maxY)) {
            drawEmpty(canvas);
            return;
        }
        if (Math.abs(maxY - minY) < 1e-3f) {
            maxY += 1f;
            minY -= 1f;
        }

        // Add margin.
        float margin = (maxY - minY) * 0.10f;
        minY -= margin;
        maxY += margin;
        if (minY < 0f) minY = 0f;

        // Grid: 3 horizontal lines.
        for (int i = 0; i <= 3; i++) {
            float t = i / 3f;
            float y = lerp(chartRect.top, chartRect.bottom, t);
            canvas.drawLine(chartRect.left, y, chartRect.right, y, gridPaint);
        }

        final int n = series.length;
        final float x0 = chartRect.left;
        final float x1 = chartRect.right;
        final float yTop = chartRect.top;
        final float yBot = chartRect.bottom;

        // Optional band.
        if (bandLo != null && bandHi != null && bandLo.length == n && bandHi.length == n) {
            bandPath.reset();
            for (int i = 0; i < n; i++) {
                float x = x0 + (x1 - x0) * (i / (float) (n - 1));
                float y = mapY(bandHi[i], minY, maxY, yTop, yBot);
                if (i == 0) bandPath.moveTo(x, y);
                else bandPath.lineTo(x, y);
            }
            for (int i = n - 1; i >= 0; i--) {
                float x = x0 + (x1 - x0) * (i / (float) (n - 1));
                float y = mapY(bandLo[i], minY, maxY, yTop, yBot);
                bandPath.lineTo(x, y);
            }
            bandPath.close();
            canvas.drawPath(bandPath, bandPaint);
        }

        // Main line.
        linePath.reset();
        for (int i = 0; i < n; i++) {
            float x = x0 + (x1 - x0) * (i / (float) (n - 1));
            float y = mapY(series[i], minY, maxY, yTop, yBot);
            if (i == 0) linePath.moveTo(x, y);
            else linePath.lineTo(x, y);
        }
        canvas.drawPath(linePath, linePaint);

        // Tiny labels.
        String top = ((int) Math.round(maxY)) + "ms";
        String bot = ((int) Math.round(minY)) + "ms";
        canvas.drawText(top, padL, chartRect.top + dp(10), textPaint);
        canvas.drawText(bot, padL, chartRect.bottom, textPaint);

        canvas.drawText("0", chartRect.left, h - padB - dp(2), textPaint);
        canvas.drawText(String.valueOf(n - 1), chartRect.right - textPaint.measureText(String.valueOf(n - 1)), h - padB - dp(2), textPaint);
    }

    private void drawEmpty(@NonNull Canvas canvas) {
        // Minimal placeholder to avoid feeling "broken".
        String s = "preview";
        float x = getPaddingLeft() + dp(6);
        float y = getPaddingTop() + dp(14);
        canvas.drawText(s, x, y, textPaint);
    }

    private static float mapY(float v, float minY, float maxY, float top, float bottom) {
        float t = (v - minY) / (maxY - minY);
        t = clamp01(t);
        return bottom - (bottom - top) * t;
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private int dp(int dp) {
        return (int) Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private static int withAlpha(int color, float alpha01) {
        int a = (int) Math.round(255 * clamp01(alpha01));
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
