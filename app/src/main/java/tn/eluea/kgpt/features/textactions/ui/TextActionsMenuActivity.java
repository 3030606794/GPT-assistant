/*
 * Copyright (C) 2024-2025 Amr Aldeeb @Eluea
 * 
 * This file is part of KGPT - a fork of KeyboardGPT.
 * 
 * GitHub: https://github.com/Eluea
 * Telegram: https://t.me/Eluea
 */
package tn.eluea.kgpt.features.textactions.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Toast;

import tn.eluea.kgpt.util.LocaleHelper;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.ui.UiInteractor;
import tn.eluea.kgpt.features.textactions.presentation.TextActionsUiComposer;
import tn.eluea.kgpt.features.textactions.SelectionHandler;
import tn.eluea.kgpt.features.textactions.domain.TextAction;
import tn.eluea.kgpt.features.textactions.data.TextActionManager;
import tn.eluea.kgpt.features.textactions.TextActionPrompts;
import tn.eluea.kgpt.llm.SimpleAIController;
import tn.eluea.kgpt.listener.GenerativeAIListener;

import tn.eluea.kgpt.features.screenunderstanding.SmartScreenActions;
import tn.eluea.kgpt.features.screenunderstanding.ScreenTextHeuristics;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Floating menu activity that appears when text is selected.
 * Shows AI text action options in a floating card.
 */
public class TextActionsMenuActivity extends AppCompatActivity
        implements GenerativeAIListener, TextActionsUiComposer.UiActionListener {

@Override
protected void attachBaseContext(android.content.Context newBase) {
    super.attachBaseContext(LocaleHelper.onAttach(newBase));
}



    public static final String EXTRA_SELECTED_TEXT = "selected_text";
    public static final String EXTRA_READONLY = "readonly";

    // Screen understanding (share/accessibility) extras
    public static final String EXTRA_CONTEXT_HINT = "context_hint"; // "share" | "accessibility" | null
    public static final String EXTRA_SHARED_TITLE = "shared_title";
    public static final String EXTRA_SHARED_URL = "shared_url";
    public static final String EXTRA_AUTO_SMART_ACTION = "auto_smart_action"; // SmartScreenActions.ID_*

    // Languages for translation
    private static final List<String> LANGUAGES = Arrays.asList(
            "Arabic", "English", "French", "Spanish", "German",
            "Italian", "Russian", "Turkish", "Chinese", "Japanese",
            "Korean", "Hindi", "Portuguese", "Indonesian");

    private String selectedText;
    private String originalSelectedText;
    private boolean isReadonly;
    private boolean fromProcessText = false; // Flag to determine return method

    private String contextHint = null;
    private String sharedTitle = null;
    private String sharedUrl = null;
    private String autoSmartActionId = null;

    private TextActionManager actionManager;
    private TextActionsUiComposer uiComposer;
    private Handler mainHandler;
    private StringBuilder responseBuilder;

    // State
    private TextAction currentAction;
    private String currentResult;
    private int selectionStart;
    private int selectionEnd;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mainHandler = new Handler(Looper.getMainLooper());

        if (!SPManager.isReady()) {
            try {
                SPManager.init(getApplicationContext());
                UiInteractor.init(getApplicationContext());
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            // Ensure UiInteractor is also ready if SPManager was ready but UiInteractor
            // wasn't
            UiInteractor.init(getApplicationContext());
        }

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);

        Intent intent = getIntent();
        selectedText = intent.getStringExtra(EXTRA_SELECTED_TEXT);
        selectionStart = intent.getIntExtra("selection_start", -1);
        selectionEnd = intent.getIntExtra("selection_end", -1);
        fromProcessText = intent.getBooleanExtra("from_process_text", false);

        // Screen understanding metadata
        contextHint = intent.getStringExtra(EXTRA_CONTEXT_HINT);
        sharedTitle = intent.getStringExtra(EXTRA_SHARED_TITLE);
        sharedUrl = intent.getStringExtra(EXTRA_SHARED_URL);
        autoSmartActionId = intent.getStringExtra(EXTRA_AUTO_SMART_ACTION);
        originalSelectedText = selectedText;
        isReadonly = intent.getBooleanExtra(EXTRA_READONLY, false);

        if (selectedText == null || selectedText.isEmpty()) {
            finish();
            return;
        }

        actionManager = tn.eluea.kgpt.core.di.ServiceLocator.getInstance().createTextActionManager(this);
        actionManager.reloadConfig();

        uiComposer = new TextActionsUiComposer(this, this);
        uiComposer.createUI(this);

        showMainMenu();
        uiComposer.animateIn();

        // Auto-run smart action if requested.
        if (autoSmartActionId != null && !autoSmartActionId.trim().isEmpty()) {
            mainHandler.post(() -> {
                try {
                    tn.eluea.kgpt.features.textactions.domain.CustomTextAction a = findCustomActionById(autoSmartActionId);
                    if (a != null) {
                        processSmartAction(a);
                    }
                } catch (Throwable ignored) {
                }
            });
        }

        if (this instanceof androidx.activity.ComponentActivity) {
            ((androidx.activity.ComponentActivity) this).getOnBackPressedDispatcher().addCallback(this,
                    new androidx.activity.OnBackPressedCallback(true) {
                        @Override
                        public void handleOnBackPressed() {
                            uiComposer.animateOut(() -> finish());
                        }
                    });
        }
    }

    // --- UI Action Listener Implementation ---

    @Override
    public void onActionClicked(TextAction action) {
        processAction(action, null);
    }

    @Override
    public void onCustomActionClicked(tn.eluea.kgpt.features.textactions.domain.CustomTextAction action) {
        if (action != null && SmartScreenActions.isSmartActionId(action.id)) {
            processSmartAction(action);
        } else {
            processCustomAction(action);
        }
    }

    @Override
    public void onCloseClicked() {
        uiComposer.animateOut(this::finish);
    }

    @Override
    public void onBackgroundClicked() {
        uiComposer.animateOut(this::finish);
    }

    @Override
    public void onTranslateClicked() {
        selectedText = currentResult != null ? currentResult : selectedText;
        uiComposer.showLanguageSelector(LANGUAGES);
    }

    @Override
    public void onLanguageSelected(String language) {
        processAction(TextAction.TRANSLATE, language);
    }

    @Override
    public void onCancelLanguageSelection() {
        if (currentResult != null) {
            uiComposer.showResult(currentResult, isReadonly);
        } else {
            showMainMenu();
        }
    }

    @Override
    public void onReplaceClicked() {
        if (currentResult != null)
            finishWithResult(currentResult);
    }

    @Override
    public void onAppendClicked() {
        if (currentResult != null) {
            // Append result after the original selected text
            // We need to replace the selection with: original + space + result
            String finalText = originalSelectedText + " " + currentResult;
            finishWithResult(finalText);
        }
    }

    @Override
    public void onCopyClicked() {
        if (currentResult != null) {
            copyToClipboard(currentResult);
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        }
    }

    // --- Logic ---

    private void showMainMenu() {
        List<tn.eluea.kgpt.features.textactions.domain.CustomTextAction> custom = new ArrayList<>();
        try {
            custom.addAll(actionManager.getCustomActions());
        } catch (Throwable ignored) {
        }

        // Inject lightweight "screen understanding" smart actions when relevant.
        try {
            String url = sharedUrl;
            if (url == null || url.trim().isEmpty()) {
                url = ScreenTextHeuristics.findFirstUrl(selectedText);
            }
            custom.addAll(SmartScreenActions.buildActionsFor(selectedText, url, contextHint));
        } catch (Throwable ignored) {
        }

        uiComposer.showMainMenu(
                actionManager.getEnabledActions(),
                custom,
                actionManager.shouldShowLabels());
    }

    private tn.eluea.kgpt.features.textactions.domain.CustomTextAction findCustomActionById(String id) {
        if (id == null) return null;
        List<tn.eluea.kgpt.features.textactions.domain.CustomTextAction> all = new ArrayList<>();
        try { all.addAll(actionManager.getCustomActions()); } catch (Throwable ignored) {}
        try {
            String url = sharedUrl;
            if (url == null || url.trim().isEmpty()) url = ScreenTextHeuristics.findFirstUrl(selectedText);
            all.addAll(SmartScreenActions.buildActionsFor(selectedText, url, contextHint));
        } catch (Throwable ignored) {}
        for (tn.eluea.kgpt.features.textactions.domain.CustomTextAction a : all) {
            if (a != null && id.equals(a.id)) return a;
        }
        return null;
    }

    private void processAction(TextAction action, String targetInfo) {
        currentAction = action;
        uiComposer.showLoading();

        responseBuilder = new StringBuilder();

        String systemMessage;
        if (action == TextAction.TRANSLATE && targetInfo != null) {
            systemMessage = TextActionPrompts.getSystemMessage(action, targetInfo);
        } else {
            systemMessage = actionManager.getActionPrompt(action);
        }
        String prompt = TextActionPrompts.buildPrompt(action, selectedText);

        SimpleAIController aiController = new SimpleAIController();
        aiController.addListener(this);

        new Thread(() -> {
            aiController.generateResponse(prompt, systemMessage);
        }).start();
    }

    private void processCustomAction(tn.eluea.kgpt.features.textactions.domain.CustomTextAction action) {
        currentAction = null;
        uiComposer.showLoading();

        responseBuilder = new StringBuilder();

        String systemMessage = action.prompt;
        // For custom actions, we just send the text as user prompt.
        String prompt = "Text: \"" + selectedText + "\"";

        SimpleAIController aiController = new SimpleAIController();
        aiController.addListener(this);

        new Thread(() -> {
            aiController.generateResponse(prompt, systemMessage);
        }).start();
    }

    private void processSmartAction(tn.eluea.kgpt.features.textactions.domain.CustomTextAction action) {
        uiComposer.showLoading();
        responseBuilder = new StringBuilder();

        String url = sharedUrl;
        if (url == null || url.trim().isEmpty()) {
            url = ScreenTextHeuristics.findFirstUrl(selectedText);
        }

        String systemMessage = action.prompt;
        String prompt = SmartScreenActions.buildUserPrompt(action.id, selectedText, sharedTitle, url);

        SimpleAIController aiController = new SimpleAIController();
        aiController.addListener(this);

        new Thread(() -> {
            aiController.generateResponse(prompt, systemMessage);
        }).start();
    }

    /**
     * Replace the original selected text with the new text.
     * 
     * Two paths:
     * 1. From ProcessTextActivity (PROCESS_TEXT intent): Return result via
     * setResult()
     * 2. From SelectionHandler (keyboard): Send broadcast to commit text
     */
    private void finishWithResult(String text) {
        if (fromProcessText) {
            // Return result to ProcessTextActivity which will forward to calling app
            Intent resultIntent = new Intent();
            resultIntent.putExtra(ProcessTextActivity.EXTRA_RESULT_TEXT, text);
            setResult(RESULT_OK, resultIntent);
            finish();
        } else {
            // Send broadcast to SelectionHandler to commit text
            Intent intent = new Intent(SelectionHandler.ACTION_COMMIT_TEXT);
            intent.putExtra(SelectionHandler.EXTRA_TEXT_TO_COMMIT, text);
            intent.putExtra("selection_start", selectionStart);
            intent.putExtra("selection_end", selectionEnd);
            sendBroadcast(intent);
            finish();
        }
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("AI Result", text);
            clipboard.setPrimaryClip(clip);
        }
    }

    // --- AI Listener ---

    @Override
    public void onAIPrepare() {
    }

    @Override
    public void onAINext(String chunk) {
        responseBuilder.append(chunk);
    }

    @Override
    public void onAIError(Throwable t) {
        mainHandler.post(() -> {
            Toast.makeText(this, "AI Error: " + t.getMessage(), Toast.LENGTH_LONG).show();
            if (currentResult != null) {
                uiComposer.showResult(currentResult, isReadonly);
            } else {
                showMainMenu();
            }
        });
    }

    @Override
    public void onAIComplete() {
        currentResult = responseBuilder.toString();
        mainHandler.post(() -> uiComposer.showResult(currentResult, isReadonly));
    }

    // --- Lifecycle & System ---

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
            uiComposer.animateOut(this::finish);
            return true;
        }
        return super.onTouchEvent(event);
    }

    // onBackPressed removed. Handled by OnBackPressedDispatcher in onCreate.

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiComposer.cancelLoading();
    }

    public int dp(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                getResources().getDisplayMetrics());
    }
}
