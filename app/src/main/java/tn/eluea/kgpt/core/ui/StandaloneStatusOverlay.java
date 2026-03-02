package tn.eluea.kgpt.core.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import tn.eluea.kgpt.util.Logger;

/**
 * A small independent floating strip rendered by KGPT app process (not the IME editor view).
 * It is intentionally tiny and non-touchable to reduce compatibility issues.
 */
public class StandaloneStatusOverlay {
    private static final StandaloneStatusOverlay INSTANCE = new StandaloneStatusOverlay();
    public static StandaloneStatusOverlay getInstance() { return INSTANCE; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler autoHide = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View root;
    private TextView text;
    private ProgressBar progress;
    private WindowManager.LayoutParams lp;

    private final Runnable hideRunnable = this::hideInternal;

    private StandaloneStatusOverlay() {}

    public void showOrUpdate(Context context, String msg, int percent, int progressColor) {
        main.post(() -> {
            try {
                if (context == null) return;
                Context app = context.getApplicationContext();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(app)) {
                    return;
                }
                ensureView(app);
                if (text != null) {
                    text.setText(msg == null ? "AI 处理中..." : msg);
                }
                if (progress != null) {
                    if (percent >= 0) {
                        progress.setVisibility(View.VISIBLE);
                        progress.setProgress(Math.max(0, Math.min(100, percent)));
                        try {
                            if (progress.getProgressDrawable() != null) {
                                progress.getProgressDrawable().setColorFilter(progressColor, PorterDuff.Mode.SRC_IN);
                            }
                        } catch (Throwable ignored) {}
                    } else {
                        progress.setVisibility(View.GONE);
                    }
                }
                attachIfNeeded();
                autoHide.removeCallbacks(hideRunnable);
            } catch (Throwable t) {
                Logger.error("StandaloneStatusOverlay show/update failed: " + t);
                Logger.log(t);
            }
        });
    }

    public void showDone(Context context, String msg) {
        showOrUpdate(context, msg == null ? "已完成" : msg, -1, Color.parseColor("#4CAF50"));
        main.postDelayed(() -> autoHide(), 1200L);
    }

    public void autoHide() {
        autoHide.removeCallbacks(hideRunnable);
        autoHide.postDelayed(hideRunnable, 50L);
    }

    public void hide() {
        main.post(this::hideInternal);
    }

    private void ensureView(Context app) {
        if (wm == null) wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (root != null && lp != null) return;

        LinearLayout container = new LinearLayout(app);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(app, 12), dp(app, 8), dp(app, 12), dp(app, 8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xDD111111);
        bg.setCornerRadius(dp(app, 14));
        container.setBackground(bg);
        container.setElevation(dp(app, 10));

        TextView tv = new TextView(app);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setMaxWidth(dp(app, 220));
        tv.setText("AI 处理中...");
        container.addView(tv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ProgressBar pb = new ProgressBar(app, null, android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(dp(app, 160), dp(app, 3));
        pblp.topMargin = dp(app, 6);
        pb.setMax(100);
        pb.setProgress(0);
        pb.setVisibility(View.GONE);
        container.addView(pb, pblp);

        int type;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(app, 12);
        lp.y = dp(app, 90);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        root = container;
        text = tv;
        progress = pb;
    }

    private void attachIfNeeded() {
        if (wm == null || root == null || lp == null) return;
        if (root.getParent() == null) {
            wm.addView(root, lp);
        } else {
            wm.updateViewLayout(root, lp);
        }
    }

    private void hideInternal() {
        try {
            if (wm != null && root != null && root.getParent() != null) {
                wm.removeViewImmediate(root);
            }
        } catch (Throwable t) {
            Logger.error("StandaloneStatusOverlay hide failed: " + t);
        }
    }

    private static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }
}
