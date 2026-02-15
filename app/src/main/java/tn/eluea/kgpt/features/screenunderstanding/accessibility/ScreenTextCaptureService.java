package tn.eluea.kgpt.features.screenunderstanding.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.HashSet;

import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.features.screenunderstanding.ScreenTextHeuristics;
import tn.eluea.kgpt.features.screenunderstanding.SmartScreenActions;
import tn.eluea.kgpt.features.textactions.ui.TextActionsMenuActivity;
import tn.eluea.kgpt.ui.UiInteractor;

/**
 * "Light screen understanding" via Accessibility (no OCR).
 *
 * User flow:
 * 1) Enable this service in Accessibility settings.
 * 2) Use the Accessibility Button/Shortcut.
 * 3) KGPT extracts visible text from the current window and launches the
 *    floating TextActionsMenu with smart actions (news/shopping) auto-run.
 */
public class ScreenTextCaptureService extends AccessibilityService {

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No continuous processing needed.
    }

    @Override
    public void onInterrupt() {
        // No-op
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            if (!SPManager.isReady()) {
                SPManager.init(getApplicationContext());
            }
            UiInteractor.init(getApplicationContext());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityButtonClicked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "Accessibility button requires Android 8+", Toast.LENGTH_SHORT).show();
            return;
        }

        String captured = captureCurrentWindowText();
        if (captured == null || captured.trim().isEmpty()) {
            Toast.makeText(this, "No readable text on screen", Toast.LENGTH_SHORT).show();
            return;
        }

        String url = ScreenTextHeuristics.findFirstUrl(captured);

        Intent menuIntent = new Intent(this, TextActionsMenuActivity.class);
        menuIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        menuIntent.putExtra(TextActionsMenuActivity.EXTRA_SELECTED_TEXT, captured);
        menuIntent.putExtra(TextActionsMenuActivity.EXTRA_READONLY, true);
        menuIntent.putExtra(TextActionsMenuActivity.EXTRA_CONTEXT_HINT, "accessibility");
        if (url != null) menuIntent.putExtra(TextActionsMenuActivity.EXTRA_SHARED_URL, url);
        menuIntent.putExtra(TextActionsMenuActivity.EXTRA_AUTO_SMART_ACTION,
                SmartScreenActions.pickDefaultActionId(captured, url));

        try {
            startActivity(menuIntent);
        } catch (Throwable t) {
            Toast.makeText(this, "Failed to open KGPT", Toast.LENGTH_SHORT).show();
        }
    }

    private String captureCurrentWindowText() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable ignored) {
        }
        if (root == null) return null;

        StringBuilder sb = new StringBuilder();
        HashSet<String> seen = new HashSet<>();
        traverse(root, sb, seen, 0);

        // Safety limit to avoid huge prompts.
        String out = sb.toString().trim();
        if (out.length() > 16000) {
            out = out.substring(0, 16000);
        }
        return out;
    }

    private void traverse(AccessibilityNodeInfo node, StringBuilder sb, HashSet<String> seen, int depth) {
        if (node == null) return;
        if (sb.length() > 20000) return;
        if (depth > 40) return;

        try {
            CharSequence t = node.getText();
            if (t != null) {
                String s = t.toString().trim();
                if (!s.isEmpty() && s.length() <= 400) {
                    if (seen.add(s)) {
                        sb.append(s).append("\n");
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            CharSequence cd = node.getContentDescription();
            if (cd != null) {
                String s = cd.toString().trim();
                if (!s.isEmpty() && s.length() <= 200) {
                    if (seen.add(s)) {
                        sb.append(s).append("\n");
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        int n = 0;
        try {
            n = node.getChildCount();
        } catch (Throwable ignored) {
        }
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo c = null;
            try {
                c = node.getChild(i);
            } catch (Throwable ignored) {
            }
            if (c != null) {
                traverse(c, sb, seen, depth + 1);
                try {
                    c.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
