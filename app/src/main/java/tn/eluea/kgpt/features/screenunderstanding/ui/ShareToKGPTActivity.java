package tn.eluea.kgpt.features.screenunderstanding.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.ui.UiInteractor;
import tn.eluea.kgpt.features.screenunderstanding.ScreenTextHeuristics;
import tn.eluea.kgpt.features.screenunderstanding.SmartScreenActions;
import tn.eluea.kgpt.features.textactions.ui.TextActionsMenuActivity;

/**
 * Entry point for Android Share Sheet.
 *
 * We keep this intentionally lightweight:
 * - If the share contains page text, we forward it to TextActionsMenuActivity.
 * - We do NOT do OCR.
 */
public class ShareToKGPTActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            finish();
            return;
        }

        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action)) {
            finish();
            return;
        }

        // Ensure managers are initialized.
        try {
            if (!SPManager.isReady()) {
                SPManager.init(getApplicationContext());
            }
            UiInteractor.init(getApplicationContext());
        } catch (Throwable ignored) {
        }

        String sharedText = safeGetSharedText(intent, this);
        String subject = null;
        try {
            subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        } catch (Throwable ignored) {
        }

        if (sharedText == null || sharedText.trim().isEmpty()) {
            Toast.makeText(this, "No shared text", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String url = ScreenTextHeuristics.findFirstUrl(sharedText);

        // Forward to the floating menu.
        Intent menuIntent = new Intent(this, TextActionsMenuActivity.class);
        menuIntent.putExtra(TextActionsMenuActivity.EXTRA_SELECTED_TEXT, sharedText);
        menuIntent.putExtra(TextActionsMenuActivity.EXTRA_READONLY, true);
        menuIntent.putExtra(TextActionsMenuActivity.EXTRA_CONTEXT_HINT, "share");
        if (subject != null) menuIntent.putExtra(TextActionsMenuActivity.EXTRA_SHARED_TITLE, subject);
        if (url != null) menuIntent.putExtra(TextActionsMenuActivity.EXTRA_SHARED_URL, url);

        // Auto-run for page-like text; otherwise show menu only.
        if (!ScreenTextHeuristics.looksLikeUrlOnly(sharedText)) {
            menuIntent.putExtra(TextActionsMenuActivity.EXTRA_AUTO_SMART_ACTION,
                    SmartScreenActions.pickDefaultActionId(sharedText, url));
        }
        startActivity(menuIntent);
        finish();
    }

    private static String safeGetSharedText(Intent intent, android.content.Context ctx) {
        try {
            String t = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (t != null && !t.trim().isEmpty()) return t;
        } catch (Throwable ignored) {
        }

        try {
            ClipData cd = intent.getClipData();
            if (cd != null && cd.getItemCount() > 0) {
                CharSequence cs = null;
                try {
                    cs = cd.getItemAt(0).getText();
                } catch (Throwable ignored) {
                }
                if (cs == null) {
                    try {
                        cs = cd.getItemAt(0).coerceToText(ctx);
                    } catch (Throwable ignored) {
                    }
                }
                if (cs != null) {
                    String t = cs.toString();
                    if (!t.trim().isEmpty()) return t;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
