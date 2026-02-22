package tn.eluea.kgpt.core.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import tn.eluea.kgpt.R;

/**
 * A small, persistent top banner shown during AI thinking/streaming.
 *
 * Why: Many ROMs / Android 11+ restrict custom Toast views and duration.
 * This banner uses WindowManager overlay when permitted, otherwise callers can fallback to Toast.
 */
public final class TopStatusBanner {

    private static final long DONE_AUTO_HIDE_MS = 1200L;

    private static volatile TopStatusBanner sInstance;

    public static TopStatusBanner getInstance() {
        if (sInstance == null) {
            synchronized (TopStatusBanner.class) {
                if (sInstance == null) {
                    sInstance = new TopStatusBanner();
                }
            }
        }
        return sInstance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    private WindowManager wm;
    private View root;
    private TextView text;
    private boolean added;

    private final Runnable hideRunnable = new Runnable() {
        @Override
        public void run() {
            hideInternal();
        }
    };

    private TopStatusBanner() {}

    public boolean canUseOverlay(Context ctx) {
        if (ctx == null) return false;
        Context app = ctx.getApplicationContext();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return Settings.canDrawOverlays(app);
    }

    public void show(Context ctx, String msg) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        main.post(() -> showInternal(app, msg));
    }

    public void update(String msg) {
        main.post(() -> {
            if (text != null) {
                text.setText(msg == null ? "" : msg);
            }
        });
    }

    public void showDone(Context ctx, String doneMsg) {
        show(ctx, doneMsg);
        main.removeCallbacks(hideRunnable);
        main.postDelayed(hideRunnable, DONE_AUTO_HIDE_MS);
    }

    public void hide() {
        main.post(this::hideInternal);
    }

    private void showInternal(Context app, String msg) {
        main.removeCallbacks(hideRunnable);

        if (!canUseOverlay(app)) {
            // Caller should fallback to Toast if needed.
            return;
        }

        if (wm == null) {
            wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        }
        if (wm == null) return;

        if (root == null) {
            root = LayoutInflater.from(app).inflate(R.layout.view_top_status_banner, null);
            text = root.findViewById(R.id.kgpt_top_banner_text);
            root.setAlpha(0f);
        }
        if (text != null) {
            text.setText(msg == null ? "" : msg);
        }

        if (!added) {
            try {
                wm.addView(root, buildLayoutParams(app));
                added = true;
            } catch (Throwable ignored) {
                // If overlay add fails, keep silent and let caller fallback.
                added = false;
                return;
            }
        }

        // Fade in
        root.animate().cancel();
        root.animate().alpha(1f).setDuration(180L).start();
    }

    private void hideInternal() {
        main.removeCallbacks(hideRunnable);
        if (root == null || wm == null || !added) return;

        try {
            root.animate().cancel();
            root.animate().alpha(0f).setDuration(150L).withEndAction(() -> {
                try {
                    if (added) {
                        wm.removeView(root);
                        added = false;
                    }
                } catch (Throwable ignored) {
                    added = false;
                }
            }).start();
        } catch (Throwable t) {
            try {
                wm.removeView(root);
            } catch (Throwable ignored) {}
            added = false;
        }
    }

    private WindowManager.LayoutParams buildLayoutParams(Context app) {
        final int type;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            //noinspection deprecation
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );

        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.x = 0;
        lp.y = dp(app, 70);
        lp.windowAnimations = android.R.style.Animation_Toast;
        lp.setTitle("KGPT.TopStatusBanner");
        return lp;
    }

    private static int dp(Context ctx, int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                ctx.getResources().getDisplayMetrics()
        );
    }
}
