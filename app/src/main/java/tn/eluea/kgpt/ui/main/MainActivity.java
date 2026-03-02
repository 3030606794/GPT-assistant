/*
 * Copyright (C) 2024-2025 Amr Aldeeb @Eluea
 * 
 * This file is part of KGPT - a fork of KeyboardGPT.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * GitHub: https://github.com/Eluea
 * Telegram: https://t.me/Eluea
 */
package tn.eluea.kgpt.ui.main;

import android.content.SharedPreferences;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.util.LocaleHelper;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.ui.main.fragments.AiInvocationFragment;
import tn.eluea.kgpt.ui.main.fragments.ApiKeysFragment;
import tn.eluea.kgpt.ui.main.fragments.HomeFragment;
import tn.eluea.kgpt.ui.main.fragments.ConsoleFragment;
import tn.eluea.kgpt.ui.main.fragments.SettingsFragment;

public class MainActivity extends AppCompatActivity {

@Override
protected void attachBaseContext(android.content.Context newBase) {
    super.attachBaseContext(LocaleHelper.onAttach(newBase));
}



    private static final String PREF_THEME = "theme_mode";
    private static final String PREF_AMOLED = "amoled_mode";
    private static final String KEY_NAV_INDEX = "nav_index";

    // Root tab fragment tags (used to preserve state when switching tabs)
    private static final String TAG_TAB_HOME = "tab_home";
    private static final String TAG_TAB_CONSOLE = "tab_console";
    private static final String TAG_TAB_LAB = "tab_lab";
    private static final String TAG_TAB_SETTINGS = "tab_settings";

    public static final String EXTRA_OPEN_AI_INVOCATION = "kgpt_open_ai_invocation";
    public static final String EXTRA_AI_INVOCATION_TAB = "kgpt_ai_invocation_tab";

    private FrameLayout navHome, navConsole, navLab, navSettings;
    private ImageView navHomeIcon, navConsoleIcon, navLabIcon, navSettingsIcon;
    private LinearLayout floatingDock;
    private android.widget.FrameLayout navItemsContainer;
    private LinearLayout navItemsLinear;
    private View dockSelectionIndicator;
    private android.animation.AnimatorSet dockIndicatorAnimator;
    private boolean dockIndicatorReady = false;
    private LinearLayout dockActionContainer;
    private ImageView dockActionIcon; // For the action icon
    private android.widget.TextView dockActionText; // For action text
    private int currentNavIndex = 0;
    private boolean isAmoledMode = false;

    private tn.eluea.kgpt.ui.view.SnowfallView snowfallView;
    private static final String PREF_WINTER_MODE = "winter_mode";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Theme is handled globally by KGPTApplication and MaterialYouManager
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        SPManager.init(this);
        initViews();
        setupNavigation();
        setupWindowInsets();
        setupBackStackListener();

        // Initialize Winter Mode
        snowfallView = findViewById(R.id.snowfall_view);
        applyWinterMode();

        // Handle Back Press
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                    getSupportFragmentManager().popBackStack();
                    // IMPORTANT:
                    // Do NOT force-select Home here.
                    // Feature fragments (e.g., App Trigger, Text Actions) are opened from the Lab tab.
                    // Forcing Home causes the dock to jump incorrectly.
                } else {
                    // Default back behavior (finish activity)
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });

        // Restore navigation state
        if (savedInstanceState != null) {
            int savedIndex = savedInstanceState.getInt(KEY_NAV_INDEX, 0);
            currentNavIndex = normalizeNavIndex(savedIndex);
            // Don't animate during state restoration
            switchToRootTab(currentNavIndex, false);
            updateNavSelection(currentNavIndex);
        } else {
            // Don't animate the initial launch
            switchToRootTab(0, false);
            updateNavSelection(0);
            // Check for updates on first launch
            checkForUpdates();
        }

	        // Handle special intents (e.g., open AI invocation at a specific tab)
	        handleSpecialIntent(getIntent());
	    }

    public void applyWinterMode() {
        if (snowfallView == null)
            return;

        SharedPreferences prefs = getSharedPreferences("keyboard_gpt_ui", android.content.Context.MODE_PRIVATE);
        boolean isWinterMode = prefs.getBoolean(PREF_WINTER_MODE, false);

        if (isWinterMode) {
            snowfallView.setVisibility(View.VISIBLE);
            snowfallView.bringToFront();
        } else {
            snowfallView.setVisibility(View.GONE);
        }
    }

    /**
     * Check for updates and show bottom sheet if available.
     * Only shows automatically if auto-update checking is enabled in settings.
     * Manual checks via "Check for Updates" button bypass this setting.
     */
    private void checkForUpdates() {
        // Only check automatically if auto-updates are enabled
        if (!SPManager.isReady() || !SPManager.getInstance().getUpdateCheckEnabled()) {
            // Auto-updates disabled - don't show update card on app launch
            return;
        }

        // Show cached update if available (from background check)
        tn.eluea.kgpt.updater.UpdateInfo cachedUpdate = tn.eluea.kgpt.updater.UpdateWorker.getCachedUpdate(this);

        if (cachedUpdate != null) {
            // Small delay to let the UI settle
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                tn.eluea.kgpt.updater.UpdateBottomSheet.showCachedUpdate(this);
            }, 1000);
        } else {
            // Trigger a fresh background check
            // This will only show the dialog if an update is found
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                tn.eluea.kgpt.updater.UpdateBottomSheet.checkAndShow(this);
            }, 2000);
        }
    }

    private void setupBackStackListener() {
        getSupportFragmentManager().addOnBackStackChangedListener(() -> {
            // When backstack becomes empty (all feature fragments popped), we're back at
            // one of the root tabs (Home / Console / Lab / Settings)
            if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
                // Restore navigation dock when returning to base fragments (Home, Settings,
                // etc.)
                showDockNavigation();
                // Sync dock selection with the currently visible root tab.
                int idx = getVisibleRootIndex();
                updateNavSelection(idx);
            }
        });
    }

    /**
     * Returns which root tab fragment is currently visible.
     * This avoids dock selection desync when popping feature fragments.
     */
    private int getVisibleRootIndex() {
        Fragment home = getSupportFragmentManager().findFragmentByTag(TAG_TAB_HOME);
        if (home != null && home.isVisible()) return 0;
        Fragment console = getSupportFragmentManager().findFragmentByTag(TAG_TAB_CONSOLE);
        if (console != null && console.isVisible()) return 1;
        Fragment lab = getSupportFragmentManager().findFragmentByTag(TAG_TAB_LAB);
        if (lab != null && lab.isVisible()) return 2;
        Fragment settings = getSupportFragmentManager().findFragmentByTag(TAG_TAB_SETTINGS);
        if (settings != null && settings.isVisible()) return 3;

        // Fallback to current index if sane.
        if (currentNavIndex >= 0 && currentNavIndex <= 3) return currentNavIndex;
        return 0;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_NAV_INDEX, currentNavIndex);
    }

    private String tagForRootIndex(int index) {
        switch (index) {
            case 1:
                return TAG_TAB_CONSOLE;
            case 2:
                return TAG_TAB_LAB;
            case 3:
                return TAG_TAB_SETTINGS;
            default:
                return TAG_TAB_HOME;
        }
    }

    private Fragment createRootFragment(int index) {
        switch (index) {
            case 1:
                return new ConsoleFragment();
            case 2:
                return new tn.eluea.kgpt.ui.lab.LabFragment();
            case 3:
                return new SettingsFragment();
            default:
                return new HomeFragment();
        }
    }

    /**
     * Switches between the 4 root tabs WITHOUT recreating them (preserves state).
     * Uses add/show/hide instead of replace.
     */
    private void switchToRootTab(int index) {
        switchToRootTab(index, true);
    }

    /**
     * Same as {@link #switchToRootTab(int)} but allows controlling animation.
     */
    private void switchToRootTab(int index, boolean animate) {
        String targetTag = tagForRootIndex(index);

        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();

        if (animate) {
            // Simple left/right motion + fade, based on dock order
            boolean forward = index > currentNavIndex;
            transaction.setCustomAnimations(
                    forward ? R.anim.tab_enter_from_right : R.anim.tab_enter_from_left,
                    forward ? R.anim.tab_exit_to_left : R.anim.tab_exit_to_right
            );
        }

        // Hide all root tabs (if they exist)
        Fragment home = getSupportFragmentManager().findFragmentByTag(TAG_TAB_HOME);
        if (home != null) transaction.hide(home);

        Fragment console = getSupportFragmentManager().findFragmentByTag(TAG_TAB_CONSOLE);
        if (console != null) transaction.hide(console);

        Fragment lab = getSupportFragmentManager().findFragmentByTag(TAG_TAB_LAB);
        if (lab != null) transaction.hide(lab);

        Fragment settings = getSupportFragmentManager().findFragmentByTag(TAG_TAB_SETTINGS);
        if (settings != null) transaction.hide(settings);

        // Show or create target
        Fragment target = getSupportFragmentManager().findFragmentByTag(targetTag);
        if (target == null) {
            target = createRootFragment(index);
            transaction.add(R.id.fragment_container, target, targetTag);
        } else {
            transaction.show(target);
        }

        transaction.commit();
    }


    /** 
     * Migrates legacy dock indices into the current 4-tab dock:
     *   Current: Home=0, Console=1, Lab=2, Settings=3
     *
     * Handles older layouts too:
     *   - Very old: Home=0, Models=1, Lab=2, Settings=3
     *   - Recent 3-tab: Home=0, Lab=1, Settings=2
     */
    private int normalizeNavIndex(int legacyIndex) {
        if (legacyIndex <= 0) return 0;

        // Recent 3-tab layout
        if (legacyIndex == 1) return 2; // Lab
        if (legacyIndex == 2) return 3; // Settings

        // Very old 4-tab layout (Models/Lab/Settings)
        if (legacyIndex == 3) return 3; // Settings

        // Default fallback
        return 0;
    }

    // applyAmoledThemeIfNeeded removed - handled globally by MaterialYouManager

    // Manual AMOLED coloring removed as Theme.KGPT.AMOLED handles it globally.

    private void initViews() {
        navHome = findViewById(R.id.nav_home);
        navConsole = findViewById(R.id.nav_console);
        navLab = findViewById(R.id.nav_lab);
        navSettings = findViewById(R.id.nav_settings);

        navHomeIcon = findViewById(R.id.nav_home_icon);
        navConsoleIcon = findViewById(R.id.nav_console_icon);
        navLabIcon = findViewById(R.id.nav_lab_icon);
        navSettingsIcon = findViewById(R.id.nav_settings_icon);

        floatingDock = findViewById(R.id.floating_dock);
        navItemsContainer = findViewById(R.id.nav_items_container);
        navItemsLinear = findViewById(R.id.nav_items_linear);
        dockSelectionIndicator = findViewById(R.id.dock_selection_indicator);
        dockActionContainer = findViewById(R.id.dock_action_container);
        dockActionIcon = findViewById(R.id.dock_action_icon);
        dockActionText = findViewById(R.id.dock_action_text);
    }

    private void setupNavigation() {
        navHome.setOnClickListener(v -> {
            if (currentNavIndex != 0) {
                switchToRootTab(0);
                updateNavSelection(0);
            }
        });

        navConsole.setOnClickListener(v -> {
            if (currentNavIndex != 1) {
                switchToRootTab(1);
                updateNavSelection(1);
            }
        });

        navLab.setOnClickListener(v -> {
            if (currentNavIndex != 2) {
                switchToRootTab(2);
                updateNavSelection(2);
            }
        });

        navSettings.setOnClickListener(v -> {
            if (currentNavIndex != 3) {
                switchToRootTab(3);
                updateNavSelection(3);
            }
        });
    }

    private void setupWindowInsets() {
        // We draw edge-to-edge (decorFitsSystemWindows=false), so we must handle insets manually.
        // Include IME insets so focused inputs are not covered by the soft keyboard.
        View mainView = findViewById(R.id.coordinator);
        ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, windowInsets) -> {
            Insets sysBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            final boolean imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime());

            // When IME is visible, hide the bottom navigation dock so it won't be pushed into the middle
            // of the screen. This keeps all vertical space available for input fields.
            if (floatingDock != null) {
                floatingDock.setVisibility(imeVisible ? View.GONE : View.VISIBLE);
            }

            int bottom = Math.max(sysBars.bottom, ime.bottom);
            v.setPadding(sysBars.left, sysBars.top, sysBars.right, bottom);
            return windowInsets;
        });
    }

    private void loadFragment(Fragment fragment) {
        // Reset snow obstacles for smooth transition
        if (snowfallView != null) {
            snowfallView.shakeOff();
        }

        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out);
        transaction.replace(R.id.fragment_container, fragment);
        transaction.commit();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private void ensureDockIndicatorReady() {
        if (dockIndicatorReady) return;
        if (dockSelectionIndicator == null || navItemsContainer == null) return;

        // Wait for layout to compute correct positions.
        navItemsContainer.post(() -> {
            if (dockSelectionIndicator == null) return;
            dockIndicatorReady = true;
            dockSelectionIndicator.setAlpha(1f);
            // Place the indicator at the current selected tab without animation.
            animateDockIndicatorToIndex(currentNavIndex, false);
        });
    }

    private View getDockTabViewByIndex(int index) {
        switch (index) {
            case 0: return navHome;
            case 1: return navConsole;
            case 2: return navLab;
            case 3: return navSettings;
            default: return null;
        }
    }

    private float getXRelativeToDockContainer(View target) {
        int[] locTarget = new int[2];
        int[] locContainer = new int[2];
        target.getLocationOnScreen(locTarget);
        navItemsContainer.getLocationOnScreen(locContainer);
        return locTarget[0] - locContainer[0];
    }

    /**
     * "Liquid" indicator: a single glass pill that flows between tabs.
     * This avoids the hard jump between per-item backgrounds and feels more like iOS-style fluid motion.
     */
    private void animateDockIndicatorToIndex(int index, boolean animated) {
        if (dockSelectionIndicator == null || navItemsContainer == null) return;
        if (index < 0) {
            dockSelectionIndicator.animate().alpha(0f).setDuration(120).start();
            return;
        }

        View target = getDockTabViewByIndex(index);
        if (target == null) return;

        if (!dockIndicatorReady) {
            // If not ready yet, just place it after layout.
            ensureDockIndicatorReady();
            return;
        }

        dockSelectionIndicator.setAlpha(1f);
        float targetX = getXRelativeToDockContainer(target);

        if (!animated) {
            dockSelectionIndicator.setTranslationX(targetX);
            dockSelectionIndicator.setScaleX(1f);
            dockSelectionIndicator.setScaleY(1f);
            return;
        }

        float startX = dockSelectionIndicator.getTranslationX();
        float dx = targetX - startX;
        int dir = dx >= 0 ? 1 : -1;

        // Directional "water smear": stretch towards movement direction.
        dockSelectionIndicator.setPivotX(dir > 0 ? 0f : dockSelectionIndicator.getWidth());
        dockSelectionIndicator.setPivotY(dockSelectionIndicator.getHeight() / 2f);

        // Cancel previous animation if any.
        if (dockIndicatorAnimator != null) {
            dockIndicatorAnimator.cancel();
        }

        float overshoot = dp(6) * dir;  // tiny overshoot to feel fluid, not bouncy
        android.animation.ObjectAnimator move = android.animation.ObjectAnimator.ofFloat(
                dockSelectionIndicator,
                View.TRANSLATION_X,
                startX,
                targetX + overshoot,
                targetX
        );
        move.setDuration(420);
        move.setInterpolator(new android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f));

        android.animation.ObjectAnimator sx = android.animation.ObjectAnimator.ofFloat(
                dockSelectionIndicator,
                View.SCALE_X,
                1f,
                1.16f,
                1f
        );
        sx.setDuration(420);
        sx.setInterpolator(new android.view.animation.PathInterpolator(0.18f, 0f, 0.2f, 1f));

        android.animation.ObjectAnimator sy = android.animation.ObjectAnimator.ofFloat(
                dockSelectionIndicator,
                View.SCALE_Y,
                1f,
                0.92f,
                1f
        );
        sy.setDuration(420);
        sy.setInterpolator(new android.view.animation.PathInterpolator(0.18f, 0f, 0.2f, 1f));

        dockIndicatorAnimator = new android.animation.AnimatorSet();
        dockIndicatorAnimator.playTogether(move, sx, sy);
        dockIndicatorAnimator.start();
    }

void updateNavSelection(int index) {
        currentNavIndex = index;

        // Ensure indicator is ready after layout.
        ensureDockIndicatorReady();

        // Reset all selected states (used for label colors + icon scale state animators)
        navHome.setSelected(false);
        navConsole.setSelected(false);
        navLab.setSelected(false);
        navSettings.setSelected(false);

        // Selected tab
        switch (index) {
            case 0:
                navHome.setSelected(true);
                break;
            case 1:
                navConsole.setSelected(true);
                break;
            case 2:
                navLab.setSelected(true);
                break;
            case 3:
                navSettings.setSelected(true);
                break;
        }

        // Animate the moving "liquid glass" indicator
        animateDockIndicatorToIndex(index, true);
    }

    public void navigateToModels() {
        // Models tab has been removed. Keep the method for compatibility and redirect to Lab.
        loadFragment(new tn.eluea.kgpt.ui.lab.LabFragment());
        updateNavSelection(2);
    }

    public void navigateToApiKeys() {
        loadFragment(new ApiKeysFragment());
        // Not a dock tab.
        updateNavSelection(-1);
    }

    public void navigateToAiInvocation() {
        navigateToAiInvocation(0);
    }

    public void navigateToAiInvocation(int initialTab) {
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out,
                android.R.anim.fade_in,
                android.R.anim.fade_out);
        AiInvocationFragment frag = new AiInvocationFragment();
        Bundle args = new Bundle();
        args.putInt(AiInvocationFragment.ARG_INITIAL_TAB, (initialTab == 1) ? 1 : 0);
        frag.setArguments(args);
        transaction.replace(R.id.fragment_container, frag);
        transaction.addToBackStack("ai_invocation");
        transaction.commit();
        updateNavSelection(-1);
    }

    public void navigateToLab() {
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out,
                android.R.anim.fade_in,
                android.R.anim.fade_out);
        transaction.replace(R.id.fragment_container, new tn.eluea.kgpt.ui.lab.LabFragment());
        transaction.addToBackStack("lab");
        transaction.commit();
        updateNavSelection(-1);
    }

    public void navigateToAppTrigger() {
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out,
                android.R.anim.fade_in,
                android.R.anim.fade_out);
        transaction.replace(R.id.fragment_container, new tn.eluea.kgpt.ui.lab.apptrigger.AppTriggerFragment());
        transaction.addToBackStack("app_trigger");
        transaction.commit();
        // Keep Lab tab highlighted while navigating within Lab.
        updateNavSelection(2);
    }

    public void navigateToTextActions() {
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.setCustomAnimations(
                android.R.anim.fade_in,
                android.R.anim.fade_out,
                android.R.anim.fade_in,
                android.R.anim.fade_out);
        transaction.replace(R.id.fragment_container, new tn.eluea.kgpt.ui.lab.textactions.TextActionsFragment());
        transaction.addToBackStack("text_actions");
        transaction.commit();
        // Keep Lab tab highlighted while navigating within Lab.
        updateNavSelection(2);
    }

    // onBackPressed removed. Handled by OnBackPressedDispatcher in onCreate.

    public void setDockAction(String text, int iconRes, View.OnClickListener listener) {
        if (floatingDock == null || navItemsContainer == null || dockActionContainer == null)
            return;

        // Check if we're switching from navigation mode to action mode
        boolean isFromNavigation = navItemsContainer.getVisibility() == View.VISIBLE;

        // Check if we're already in action mode (switching between actions)
        boolean isAlreadyInActionMode = dockActionContainer.getVisibility() == View.VISIBLE;

        if (isFromNavigation) {
            // Transition from Navigation to Action mode with animation
            animateNavToActionMode(text, iconRes, listener);
        } else if (isAlreadyInActionMode) {
            // Already in action mode - animate content change
            animateDockContentChange(text, iconRes, listener);
        } else {
            // Fallback: just set it up directly
            navItemsContainer.setVisibility(View.GONE);
            dockActionContainer.setVisibility(View.VISIBLE);
            dockActionText.setText(text);
            dockActionIcon.setImageResource(iconRes);
            applyDockActionStyle();
            floatingDock.setOnClickListener(listener);
        }
    }

    private void animateNavToActionMode(String text, int iconRes, View.OnClickListener listener) {
        // Fade out navigation items
        navItemsContainer.animate()
                .alpha(0f)
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(120)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    // Start smooth width transition
                    android.transition.Transition transition = new android.transition.ChangeBounds();
                    transition.setDuration(300);
                    transition.setInterpolator(new android.view.animation.DecelerateInterpolator());
                    android.transition.TransitionManager.beginDelayedTransition(floatingDock, transition);

                    // Hide nav, show action
                    navItemsContainer.setVisibility(View.GONE);
                    navItemsContainer.setAlpha(1f);
                    navItemsContainer.setScaleX(1f);
                    navItemsContainer.setScaleY(1f);

                    // Set up action content
                    dockActionText.setText(text);
                    dockActionIcon.setImageResource(iconRes);
                    applyDockActionStyle();
                    floatingDock.setOnClickListener(listener);

                    // Prepare for animation
                    dockActionContainer.setAlpha(0f);
                    dockActionContainer.setScaleX(0.95f);
                    dockActionContainer.setScaleY(0.95f);
                    dockActionIcon.setTranslationX(15f);
                    dockActionIcon.setAlpha(0f);
                    dockActionText.setTranslationX(15f);
                    dockActionText.setAlpha(0f);
                    dockActionContainer.setVisibility(View.VISIBLE);

                    // Animate container in with expand
                    dockActionContainer.animate()
                            .alpha(1f)
                            .scaleX(1.02f)
                            .scaleY(1.02f)
                            .setDuration(100)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .withEndAction(() -> {
                                dockActionContainer.animate()
                                        .scaleX(1f)
                                        .scaleY(1f)
                                        .setDuration(120)
                                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                                        .start();
                            })
                            .start();

                    // Animate content sliding in
                    dockActionIcon.animate()
                            .alpha(1f)
                            .translationX(0f)
                            .setDuration(180)
                            .setStartDelay(30)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .start();

                    dockActionText.animate()
                            .alpha(1f)
                            .translationX(0f)
                            .setDuration(180)
                            .setStartDelay(60)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .start();
                })
                .start();
    }

    private void animateDockContentChange(String newText, int newIconRes, View.OnClickListener newListener) {
        // Animate icon and text separately for smoother effect

        // First, fade out just the content (icon + text) while keeping container
        // visible
        dockActionIcon.animate()
                .alpha(0f)
                .translationX(-10f)
                .setDuration(100)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .start();

        dockActionText.animate()
                .alpha(0f)
                .translationX(-10f)
                .setDuration(100)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    // Update content while faded out
                    dockActionText.setText(newText);
                    dockActionIcon.setImageResource(newIconRes);
                    applyDockActionStyle();
                    floatingDock.setOnClickListener(newListener);

                    // Reset position for incoming animation
                    dockActionIcon.setTranslationX(10f);
                    dockActionText.setTranslationX(10f);

                    // Subtle stretch/expand on the container
                    dockActionContainer.animate()
                            .scaleX(1.03f)
                            .scaleY(1.03f)
                            .setDuration(80)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .withEndAction(() -> {
                                // Settle back to normal
                                dockActionContainer.animate()
                                        .scaleX(1f)
                                        .scaleY(1f)
                                        .setDuration(120)
                                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.5f))
                                        .start();
                            })
                            .start();

                    // Fade in new content with slide
                    dockActionIcon.animate()
                            .alpha(1f)
                            .translationX(0f)
                            .setDuration(180)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .start();

                    dockActionText.animate()
                            .alpha(1f)
                            .translationX(0f)
                            .setDuration(180)
                            .setStartDelay(30) // Slight stagger for elegance
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .start();
                })
                .start();
    }

    private void applyDockActionStyle() {
        int primaryContainer = com.google.android.material.color.MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorPrimaryContainer,
                ContextCompat.getColor(this, R.color.primary_light));
        int onPrimaryContainer = com.google.android.material.color.MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnPrimaryContainer,
                ContextCompat.getColor(this, R.color.primary));

        floatingDock.setBackgroundTintList(null); // keep dock background pure white
        dockActionText.setTextColor(onPrimaryContainer);
        dockActionIcon.setColorFilter(onPrimaryContainer);
    }

    public void showDockNavigation() {
        if (floatingDock == null || navItemsContainer == null || dockActionContainer == null)
            return;

        // Start smooth width transition
        android.transition.Transition transition = new android.transition.ChangeBounds();
        transition.setDuration(300);
        transition.setInterpolator(new android.view.animation.DecelerateInterpolator());
        android.transition.TransitionManager.beginDelayedTransition(floatingDock, transition);

        dockActionContainer.setVisibility(View.GONE);
        navItemsContainer.setVisibility(View.VISIBLE);

        // Restore Dock Style
        int surfaceContainer = com.google.android.material.color.MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurfaceContainer,
                ContextCompat.getColor(this, R.color.container_background));

        floatingDock.setBackgroundTintList(null); // keep dock background pure white
        floatingDock.setOnClickListener(null); // Disable action click
        floatingDock.setClickable(false); // Let events pass to children (but items are clickable)
        // Actually items capture clicks. floating_dock layout click is irrelevant if
        // children handle it.
        // But setting null is safer.
    }

    public void updateSnowObstacles(java.util.List<android.graphics.Rect> obstacles) {
        if (snowfallView != null) {
            snowfallView.updateObstacles(obstacles);
        }
    }

    public void onContentScrolled() {
        if (snowfallView != null) {
            snowfallView.shakeOff();
        }
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (snowfallView != null && snowfallView.getVisibility() == View.VISIBLE) {
            int action = ev.getAction();
            boolean active = (action == android.view.MotionEvent.ACTION_DOWN
                    || action == android.view.MotionEvent.ACTION_MOVE);
            snowfallView.updateFinger(ev.getX(), ev.getY(), active);
        }
        return super.dispatchTouchEvent(ev);
    }

    private void handleSpecialIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(EXTRA_OPEN_AI_INVOCATION, false)) {
            int tab = intent.getIntExtra(EXTRA_AI_INVOCATION_TAB, 0);
            navigateToAiInvocation(tab);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSpecialIntent(intent);
    }

}