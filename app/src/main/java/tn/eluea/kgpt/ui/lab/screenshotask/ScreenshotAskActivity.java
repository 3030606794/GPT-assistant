package tn.eluea.kgpt.ui.lab.screenshotask;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.materialswitch.MaterialSwitch;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.core.ui.FloatingAskAiService;

/** Settings page for the floating "Screenshot → Ask AI" overlay button. */
public class ScreenshotAskActivity extends AppCompatActivity {

    private MaterialSwitch switchEnabled;
    private TextView tvOverlayStatus;
    private View btnGrantOverlay;
    private View btnStop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_screenshot_ask);

        try { SPManager.init(this); } catch (Throwable ignored) {}

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        switchEnabled = findViewById(R.id.switch_enabled);
        tvOverlayStatus = findViewById(R.id.tv_overlay_status);
        btnGrantOverlay = findViewById(R.id.btn_grant_overlay);
        btnStop = findViewById(R.id.btn_stop_service);

        boolean enabled = false;
        try { enabled = SPManager.getInstance().getFloatingScreenshotAskEnabled(); } catch (Throwable ignored) {}
        switchEnabled.setChecked(enabled);

        refreshOverlayStatus();

        switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            try { SPManager.getInstance().setFloatingScreenshotAskEnabled(isChecked); } catch (Throwable ignored) {}

            if (isChecked) {
                if (!isOverlayGranted()) {
                    openOverlayPermission();
                    // Keep preference ON; service will start after permission is granted.
                } else {
                    startFloatingService();
                }
            } else {
                stopFloatingService();
            }
            refreshOverlayStatus();
        });

        btnGrantOverlay.setOnClickListener(v -> openOverlayPermission());
        btnStop.setOnClickListener(v -> {
            stopFloatingService();
            try { SPManager.getInstance().setFloatingScreenshotAskEnabled(false); } catch (Throwable ignored) {}
            switchEnabled.setChecked(false);
            refreshOverlayStatus();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshOverlayStatus();

        // If user enabled the feature and just granted overlay permission, start service.
        boolean enabled = false;
        try { enabled = SPManager.getInstance().getFloatingScreenshotAskEnabled(); } catch (Throwable ignored) {}
        if (enabled && isOverlayGranted()) {
            startFloatingService();
        }
    }

    private boolean isOverlayGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        try {
            return Settings.canDrawOverlays(getApplicationContext());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void refreshOverlayStatus() {
        if (tvOverlayStatus == null) return;
        boolean ok = isOverlayGranted();
        tvOverlayStatus.setText(ok ? getString(R.string.ui_overlay_granted) : getString(R.string.ui_overlay_not_granted));
        if (btnGrantOverlay != null) btnGrantOverlay.setVisibility(ok ? View.GONE : View.VISIBLE);
    }

    private void openOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable ignored) {}
    }

    private void startFloatingService() {
        try {
            Intent i = new Intent(getApplicationContext(), FloatingAskAiService.class);
            i.setAction(FloatingAskAiService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Throwable ignored) {}
    }

    private void stopFloatingService() {
        try {
            Intent i = new Intent(getApplicationContext(), FloatingAskAiService.class);
            i.setAction(FloatingAskAiService.ACTION_STOP);
            startService(i);
        } catch (Throwable ignored) {}
    }
}
