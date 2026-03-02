package tn.eluea.kgpt.ui.lab;

import android.os.Bundle;
import android.view.View;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.color.MaterialColors;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;

/**
 * Full-screen non-linear model settings screen.
 *
 * This replaces the previous BottomSheet presentation while reusing the same layout + binding logic.
 */
public class StreamingNonLinearModelSettingsActivity extends AppCompatActivity {

    private Runnable cleanup;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_streaming_nl_model_settings);

        // Edge-to-edge + safe insets (avoid camera cutout covering title).
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // Make the reused bottom sheet layout look like a normal full-screen page.
        View container = findViewById(R.id.bottom_sheet_container);
        if (container != null) {
            final int padLeft = container.getPaddingLeft();
            final int padRight = container.getPaddingRight();
            final int padBottom = container.getPaddingBottom();

            ViewCompat.setOnApplyWindowInsetsListener(container, (v, insets) -> {
                int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
                int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                v.setPadding(padLeft, top + dp(24), padRight, padBottom + bottom);
                return insets;
            });
            ViewCompat.requestApplyInsets(container);

            try {
                container.setBackground(null);
                int surface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorSurface);
                container.setBackgroundColor(surface);
            } catch (Throwable ignored) {
            }
        }
        View handle = findViewById(R.id.view_sheet_handle);
        if (handle != null) handle.setVisibility(View.GONE);

        final SPManager sp;
        try {
            sp = SPManager.getInstance();
        } catch (Throwable t) {
            finish();
            return;
        }

        // Bind UI interactions. Close button will finish this Activity.
        cleanup = StreamingNonLinearModelSettingsBottomSheet.bindForScreen(
                this,
                findViewById(android.R.id.content),
                sp,
                this::finish
        );
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        try {
            if (cleanup != null) cleanup.run();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
