package tn.eluea.kgpt.ui.lab;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.RadioButton;
import android.widget.ArrayAdapter;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import tn.eluea.kgpt.R;

import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.roles.RoleManager;
import tn.eluea.kgpt.ui.roles.RolesManagerUi;

import tn.eluea.kgpt.ui.main.MainActivity;

import tn.eluea.kgpt.llm.LanguageModel;
import tn.eluea.kgpt.llm.LanguageModelField;
import tn.eluea.kgpt.llm.SubModelSuggestions;
import tn.eluea.kgpt.llm.ModelCapabilities;
import tn.eluea.kgpt.ui.UiInteractor;
import android.widget.EditText;
import android.text.TextWatcher;
import android.text.Editable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.button.MaterialButton;
import tn.eluea.kgpt.ui.main.FloatingBottomSheet;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import androidx.core.content.ContextCompat;

public class LabFragment extends Fragment implements ProviderListDialogFragment.Callback {

    private View rowConversationSubModel;
    private TextView tvConversationSubModelValue;
    private TextView tvConversationSubModelProvider;
    private TextView tvConversationProviderValue;
    private View areaPickProvider;
    private View areaPickSubmodel;

    // In-process cache for OpenRouter /models capabilities.
    private static final long OPENROUTER_CAPS_TTL_MS = 10 * 60 * 1000L;
    private static volatile long sOpenRouterCapsAt = 0L;
    private static volatile Map<String, SubModelPickerAdapter.ModelCaps> sOpenRouterCapsCache = null;

    private View rowNormalModelThinking;
    private TextView tvNormalModelThinkingValue;

    private View rowReasoningModelThinking;
    private TextView tvReasoningModelThinkingValue;

    // Streaming settings row values that need refresh when returning from settings screens.
    private TextView tvStreamingNlModelValue;

    private BroadcastReceiver configChangedReceiver;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_lab, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initViews(view);
        applyAmoledIfNeeded();
        // Lab is now a main navigation tab, so we don't set dock action here
        // The navigation dock will be shown by default
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshConversationSubModelRow();

        // Refresh streaming non-linear model label (it can be changed in the settings screen).
        try {
            int m = SPManager.getInstance().getStreamingNonLinearModel();
            if (tvStreamingNlModelValue != null) {
                tvStreamingNlModelValue.setText(getStreamingNonLinearModelLabel(m));
            }
        } catch (Throwable ignored) {
        }
    }


    @Override
    public void onStart() {
        super.onStart();
        // Listen for config broadcasts so provider/sub-model UI stays in sync
        if (configChangedReceiver == null) {
            configChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, Intent intent) {
                    refreshConversationSubModelRow();
                }
            };
        }
        try {
            IntentFilter f = new IntentFilter(UiInteractor.ACTION_DIALOG_RESULT);
            ContextCompat.registerReceiver(requireContext().getApplicationContext(), configChangedReceiver,
                    f, ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        try {
            if (configChangedReceiver != null) {
                requireContext().getApplicationContext().unregisterReceiver(configChangedReceiver);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Lab is a main navigation tab, nothing to clean up
    }

    private void initViews(View view) {
        // Hide back button since Lab is now a main navigation tab
        View btnBack = view.findViewById(R.id.btn_back);
        if (btnBack != null) {
            btnBack.setVisibility(View.GONE);
        }

        // App Triggers feature
        view.findViewById(R.id.card_app_triggers).setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToAppTrigger();
            }
        });

        // Text Actions feature
        view.findViewById(R.id.card_text_actions).setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToTextActions();
            }
        });

        // Streaming output controls
        SwitchMaterial switchStreaming = view.findViewById(R.id.switch_streaming_output);
        View headerRow = view.findViewById(R.id.row_streaming_header);
        View streamingContent = view.findViewById(R.id.layout_streaming_output_content);
        ImageView ivStreamingExpand = view.findViewById(R.id.iv_streaming_output_expand);
        ViewGroup streamingCard = view.findViewById(R.id.card_streaming_output);
        View speedRow = view.findViewById(R.id.row_streaming_speed);
        View algoLinearRow = view.findViewById(R.id.row_streaming_algo_linear);
        View algoNonLinearRow = view.findViewById(R.id.row_streaming_algo_nonlinear);
        RadioButton rbAlgoLinear = view.findViewById(R.id.rb_stream_algo_linear);
        RadioButton rbAlgoNonLinear = view.findViewById(R.id.rb_stream_algo_nonlinear);
        View nlModelRow = view.findViewById(R.id.row_streaming_nonlinear_model);
        TextView tvNlModelValue = view.findViewById(R.id.tv_stream_nonlinear_model_value);
        tvStreamingNlModelValue = tvNlModelValue;
        Slider sliderSpeed = view.findViewById(R.id.slider_stream_speed);
        TextView tvSpeedValue = view.findViewById(R.id.tv_stream_speed_value);
        SwitchMaterial switchAuto = view.findViewById(R.id.switch_stream_speed_auto);

        View modeRow = view.findViewById(R.id.row_streaming_mode);
        TextView tvModeValue = view.findViewById(R.id.tv_stream_mode_value);
        View granularityRow = view.findViewById(R.id.row_streaming_granularity);
        TextView tvGranularityValue = view.findViewById(R.id.tv_stream_granularity_value);
        View fallbackRow = view.findViewById(R.id.row_streaming_fallback);
        SwitchMaterial switchFallback = view.findViewById(R.id.switch_stream_fallback_non_stream);

        boolean enabled = false;
        int speedPercent = 60;
        boolean autoSpeed = true;
        int speedAlgo = SPManager.STREAM_SPEED_ALGO_LINEAR;
        int mode = SPManager.STREAM_MODE_AUTO;
        int granularity = SPManager.STREAM_GRANULARITY_CHARS;
        boolean fallbackNonStream = true;
        try {
            enabled = SPManager.getInstance().getStreamingOutputEnabled();
        } catch (Throwable ignored) {
        }
        try {
            speedPercent = SPManager.getInstance().getStreamingOutputSpeedPercent();
        } catch (Throwable ignored) {
        }
        try {
            autoSpeed = SPManager.getInstance().getStreamingOutputSpeedAutoEnabled();
        } catch (Throwable ignored) {
        }
        try {
            mode = SPManager.getInstance().getStreamingOutputMode();
        } catch (Throwable ignored) {
        }
        try {
            granularity = SPManager.getInstance().getStreamingOutputGranularity();
        } catch (Throwable ignored) {
        }
        try {
            fallbackNonStream = SPManager.getInstance().getStreamingOutputFallbackNonStreamEnabled();
        } catch (Throwable ignored) {
        }
        try {
            speedAlgo = SPManager.getInstance().getStreamingOutputSpeedAlgorithm();
        } catch (Throwable ignored) {
        }

        int nlModel = SPManager.STREAM_NL_MODEL_LINEAR_CONSTANT;
        try {
            nlModel = SPManager.getInstance().getStreamingNonLinearModel();
        } catch (Throwable ignored) {
        }

        if (switchStreaming != null) {
            switchStreaming.setChecked(enabled);
            switchStreaming.setOnCheckedChangeListener((buttonView, isChecked) -> {
                try {
                    SPManager.getInstance().setStreamingOutputEnabled(isChecked);
                } catch (Throwable ignored) {
                }
                // Speed slider + auto-speed are linear-only. When user selects non-linear speed,
                // keep these controls visible but disabled to avoid confusion.
                setStreamingSpeedControlsEnabled(speedRow, sliderSpeed, tvSpeedValue, switchAuto,
                        isChecked, getSafeStreamingSpeedAlgorithm());
                setStreamingAlgorithmOptionRowsEnabled(algoLinearRow, algoNonLinearRow, rbAlgoLinear, rbAlgoNonLinear, isChecked);
                setStreamingNonLinearModelRowEnabled(nlModelRow, tvNlModelValue, isChecked, getSafeStreamingSpeedAlgorithm());
                setStreamingAdvancedControlsEnabled(modeRow, tvModeValue, granularityRow, tvGranularityValue, fallbackRow, switchFallback,
                        isChecked, getSafeStreamingMode());
            });
        }

        // Streaming output accordion (default: collapsed). Tap header to expand/collapse.
        if (headerRow != null && streamingContent != null && ivStreamingExpand != null && streamingCard != null) {
            final boolean[] expanded = new boolean[]{false};
            streamingContent.setVisibility(View.GONE);
            ivStreamingExpand.setRotation(0f);
            headerRow.setOnClickListener(v -> {
                expanded[0] = !expanded[0];
                tn.eluea.kgpt.util.TransitionHelper.beginTransition(streamingCard, tn.eluea.kgpt.util.TransitionHelper.DURATION_FAST);
                streamingContent.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
                ivStreamingExpand.animate()
                        .rotation(expanded[0] ? 180f : 0f)
                        .setDuration(tn.eluea.kgpt.util.TransitionHelper.DURATION_FAST)
                        .start();
            });
        }

        // Speed algorithm options (Linear / Non-linear)
        final IntConsumer applySpeedAlgo = selected -> {
            try {
                SPManager.getInstance().setStreamingOutputSpeedAlgorithm(selected);
            } catch (Throwable ignored) {
            }
            // Update radios
            if (rbAlgoLinear != null) rbAlgoLinear.setChecked(selected == SPManager.STREAM_SPEED_ALGO_LINEAR);
            if (rbAlgoNonLinear != null) rbAlgoNonLinear.setChecked(selected == SPManager.STREAM_SPEED_ALGO_NONLINEAR);
            // Linear-only controls should reflect the selected algorithm immediately.
            setStreamingSpeedControlsEnabled(speedRow, sliderSpeed, tvSpeedValue, switchAuto,
                    getSafeStreamingEnabled(), selected);
            setStreamingNonLinearModelRowEnabled(nlModelRow, tvNlModelValue, getSafeStreamingEnabled(), selected);
        };

        // Initial radio state
        if (rbAlgoLinear != null) rbAlgoLinear.setChecked(speedAlgo == SPManager.STREAM_SPEED_ALGO_LINEAR);
        if (rbAlgoNonLinear != null) rbAlgoNonLinear.setChecked(speedAlgo == SPManager.STREAM_SPEED_ALGO_NONLINEAR);

        if (algoLinearRow != null) {
            algoLinearRow.setOnClickListener(v -> applySpeedAlgo.accept(SPManager.STREAM_SPEED_ALGO_LINEAR));
        }
        if (algoNonLinearRow != null) {
            algoNonLinearRow.setOnClickListener(v -> applySpeedAlgo.accept(SPManager.STREAM_SPEED_ALGO_NONLINEAR));
        }

        // Non-linear model settings screen
        if (tvNlModelValue != null) tvNlModelValue.setText(getStreamingNonLinearModelLabel(nlModel));
        if (nlModelRow != null) {
            nlModelRow.setOnClickListener(v -> {
                if (getContext() == null) return;
                try {
                    startActivity(new Intent(requireContext(), StreamingNonLinearModelSettingsActivity.class));
                } catch (Throwable ignored) {
                    // Fallback to legacy sheet
                    StreamingNonLinearModelSettingsBottomSheet.show(requireContext(), null);
                }
            });
        }

        if (sliderSpeed != null) {
            if (speedPercent < 0) speedPercent = 0;
            if (speedPercent > 100) speedPercent = 100;
            sliderSpeed.setValue(speedPercent);
            if (tvSpeedValue != null)
                tvSpeedValue.setText(speedPercent + "%");

            // Update label live, persist on release.
            sliderSpeed.addOnChangeListener((slider, value, fromUser) -> {
                int v = Math.max(0, Math.min(100, Math.round(value)));
                if (tvSpeedValue != null)
                    tvSpeedValue.setText(v + "%");
            });
            sliderSpeed.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
                @Override
                public void onStartTrackingTouch(@NonNull Slider slider) {
                }

                @Override
                public void onStopTrackingTouch(@NonNull Slider slider) {
                    int v = Math.max(0, Math.min(100, Math.round(slider.getValue())));
                    try {
                        SPManager.getInstance().setStreamingOutputSpeedPercent(v);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        if (switchAuto != null) {
            switchAuto.setChecked(autoSpeed);
            switchAuto.setOnCheckedChangeListener((buttonView, isChecked) -> {
                try {
                    SPManager.getInstance().setStreamingOutputSpeedAutoEnabled(isChecked);
                } catch (Throwable ignored) {
                }

                // Auto-speed and manual speed slider are mutually exclusive.
                // When auto-speed is ON, disable the slider (greyed out).
                setStreamingSpeedControlsEnabled(speedRow, sliderSpeed, tvSpeedValue, switchAuto,
                        getSafeStreamingEnabled(), getSafeStreamingSpeedAlgorithm());
            });
        }

        // Mode selection
        if (tvModeValue != null) tvModeValue.setText(getStreamingModeLabel(mode));
        if (modeRow != null) {
            modeRow.setOnClickListener(v -> showStreamingModeDialog(getSafeStreamingMode(), selected -> {
                try {
                    SPManager.getInstance().setStreamingOutputMode(selected);
                } catch (Throwable ignored) {
                }
                if (tvModeValue != null) tvModeValue.setText(getStreamingModeLabel(selected));
                setStreamingAdvancedControlsEnabled(modeRow, tvModeValue, granularityRow, tvGranularityValue, fallbackRow, switchFallback,
                        getSafeStreamingEnabled(), selected);
            }));
        }

        // Granularity selection
        if (tvGranularityValue != null) tvGranularityValue.setText(getStreamingGranularityLabel(granularity));
        if (granularityRow != null) {
            granularityRow.setOnClickListener(v -> showStreamingGranularityDialog(getSafeStreamingGranularity(), selected -> {
                try {
                    SPManager.getInstance().setStreamingOutputGranularity(selected);
                } catch (Throwable ignored) {
                }
                if (tvGranularityValue != null) tvGranularityValue.setText(getStreamingGranularityLabel(selected));
            }));
        }

        // Fallback toggle
        if (switchFallback != null) {
            switchFallback.setChecked(fallbackNonStream);
            switchFallback.setOnCheckedChangeListener((buttonView, isChecked) -> {
                try {
                    SPManager.getInstance().setStreamingOutputFallbackNonStreamEnabled(isChecked);
                } catch (Throwable ignored) {
                }
            });
        }

        setStreamingSpeedControlsEnabled(speedRow, sliderSpeed, tvSpeedValue, switchAuto, enabled, speedAlgo);
        setStreamingAlgorithmOptionRowsEnabled(algoLinearRow, algoNonLinearRow, rbAlgoLinear, rbAlgoNonLinear, enabled);
        setStreamingNonLinearModelRowEnabled(nlModelRow, tvNlModelValue, enabled, speedAlgo);
        setStreamingAdvancedControlsEnabled(modeRow, tvModeValue, granularityRow, tvGranularityValue, fallbackRow, switchFallback,
                enabled, mode);

        // Conversation settings controls (memory & thinking depth)
        rowConversationSubModel = view.findViewById(R.id.row_conversation_sub_model);
        areaPickProvider = view.findViewById(R.id.area_pick_provider);
        areaPickSubmodel = view.findViewById(R.id.area_pick_submodel);
        tvConversationProviderValue = view.findViewById(R.id.tv_conversation_ai_provider_value);
        tvConversationSubModelValue = view.findViewById(R.id.tv_conversation_sub_model_value);
        tvConversationSubModelProvider = view.findViewById(R.id.tv_conversation_sub_model_provider);
        View memoryRow = view.findViewById(R.id.row_conversation_memory);

        // Conversation settings accordion (default: expanded)
        View conversationHeader = view.findViewById(R.id.conversation_settings_header);
        View conversationContent = view.findViewById(R.id.layout_conversation_settings_content);
        ImageView ivConversationExpand = view.findViewById(R.id.iv_conversation_settings_expand);
        ViewGroup conversationCard = view.findViewById(R.id.card_conversation_settings);
        if (conversationHeader != null && conversationContent != null && ivConversationExpand != null && conversationCard != null) {
            final boolean[] expanded = new boolean[]{false};
            // expand_more icon points down; rotate 180 to show ▲ when expanded.
            ivConversationExpand.setRotation(0f);
            conversationContent.setVisibility(View.GONE);
            conversationHeader.setOnClickListener(v -> {
                expanded[0] = !expanded[0];
                tn.eluea.kgpt.util.TransitionHelper.beginTransition(conversationCard, tn.eluea.kgpt.util.TransitionHelper.DURATION_FAST);
                conversationContent.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
                ivConversationExpand.animate()
                        .rotation(expanded[0] ? 180f : 0f)
                        .setDuration(tn.eluea.kgpt.util.TransitionHelper.DURATION_FAST)
                        .start();
            });
        }

        TextView tvMemoryValue = view.findViewById(R.id.tv_conversation_memory_value);
        rowNormalModelThinking = view.findViewById(R.id.row_thinking_depth);
        tvNormalModelThinkingValue = view.findViewById(R.id.tv_thinking_depth_value);

        rowReasoningModelThinking = view.findViewById(R.id.row_reasoning_model_thinking);
        tvReasoningModelThinkingValue = view.findViewById(R.id.tv_reasoning_model_thinking_value);

        int memLevel = SPManager.getInstance().getConversationMemoryLevel();
        float normalThinking = 0.7f;
        try { normalThinking = SPManager.getInstance().getNormalModelThinking(); } catch (Throwable ignored) {}
        int reasoningThinkingMode = SPManager.REASONING_MODEL_THINKING_AUTO;
        try { reasoningThinkingMode = SPManager.getInstance().getReasoningModelThinkingMode(); } catch (Throwable ignored) {}

        tvMemoryValue.setText(getLevelLabel(memLevel, 1));
        if (tvNormalModelThinkingValue != null) tvNormalModelThinkingValue.setText(getNormalModelThinkingSummary(normalThinking));
        if (tvReasoningModelThinkingValue != null) tvReasoningModelThinkingValue.setText(getReasoningModelThinkingSummary(reasoningThinkingMode));

        refreshConversationSubModelRow();
        if (areaPickProvider != null) {
            areaPickProvider.setOnClickListener(v -> showConversationProviderPicker());
        }
        if (areaPickSubmodel != null) {
            areaPickSubmodel.setOnClickListener(v -> showConversationSubModelPicker());
        }

        memoryRow.setOnClickListener(v -> showLevelDialog(
                R.string.ui_dialogue_memory,
                SPManager.getInstance().getConversationMemoryLevel(),
                1,
                selected -> {
                    SPManager.getInstance().setConversationMemoryLevel(selected);
                    tvMemoryValue.setText(getLevelLabel(selected, 1));
                }
        ));
        // Enable/disable based on current provider + sub-model capability
        refreshNormalModelThinkingRowState();
        refreshReasoningModelThinkingRowState();

        if (rowNormalModelThinking != null) {
            rowNormalModelThinking.setOnClickListener(v -> {
                if (!isNormalModelThinkingAdjustable()) return;
                float cur = 0.7f;
                try { cur = SPManager.getInstance().getNormalModelThinking(); } catch (Throwable ignored) {}
                showNormalModelThinkingDialog(cur, selected -> {
                    try { SPManager.getInstance().setNormalModelThinking(selected); } catch (Throwable ignored) {}
                    if (tvNormalModelThinkingValue != null) tvNormalModelThinkingValue.setText(getNormalModelThinkingSummary(selected));
                });
            });
        }

        if (rowReasoningModelThinking != null) {
            rowReasoningModelThinking.setOnClickListener(v -> {
                if (!isReasoningModelThinkingAdjustable()) return;
                int cur = SPManager.REASONING_MODEL_THINKING_AUTO;
                try { cur = SPManager.getInstance().getReasoningModelThinkingMode(); } catch (Throwable ignored) {}
                showReasoningModelThinkingDialog(cur, selected -> {
                    try { SPManager.getInstance().setReasoningModelThinkingMode(selected); } catch (Throwable ignored) {}
                    if (tvReasoningModelThinkingValue != null) tvReasoningModelThinkingValue.setText(getReasoningModelThinkingSummary(selected));
                });
            });
        }
        // Output length (Max tokens preset)
        View rowMaxTokens = view.findViewById(R.id.row_max_tokens_preset);
        TextView tvMaxTokens = view.findViewById(R.id.tv_max_tokens_preset_value);

        // Auto summarize older context
        View rowAutoSumm = view.findViewById(R.id.row_auto_summarize_old_context);
        SwitchMaterial switchAutoSumm = view.findViewById(R.id.switch_auto_summarize_old_context);

        // Auto fallback strategy (stream / baseurl / model)
        View rowDowngrade = view.findViewById(R.id.row_auto_downgrade);
        TextView tvDowngrade = view.findViewById(R.id.tv_auto_downgrade_value);

        // Request cancel / concurrency policy
        View rowPolicy = view.findViewById(R.id.row_request_policy);
        TextView tvPolicy = view.findViewById(R.id.tv_request_policy_value);

        try {
            int tokens = SPManager.getInstance().getMaxTokensLimit();
            if (tvMaxTokens != null) tvMaxTokens.setText(getOutputLengthSummary(tokens));
        } catch (Throwable ignored) {}

        if (rowMaxTokens != null) {
            rowMaxTokens.setOnClickListener(v -> showOutputLengthDialog(
                    safeGetMaxTokensLimit(),
                    selectedTokens -> {
                        try {
                            SPManager.getInstance().setMaxTokensLimit(selectedTokens);
                        } catch (Throwable ignored) {}
                        if (tvMaxTokens != null) tvMaxTokens.setText(getOutputLengthSummary(selectedTokens));
                    }
            ));
            rowMaxTokens.setOnLongClickListener(v -> {
                try {
                    tn.eluea.kgpt.llm.LanguageModel lm = SPManager.getInstance().getLanguageModel();
                    String sm = SPManager.getInstance().getSubModel(lm);
                    SPManager.getInstance().setCachedSafeMaxTokens(lm, sm, 0);
                    android.widget.Toast.makeText(requireContext(), "已清除该模型的自动学习输出上限", android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {}
                return true;
            });
        }

        try {
            boolean autoSumm = SPManager.getInstance().getAutoSummarizeOldContextEnabled();
            if (switchAutoSumm != null) switchAutoSumm.setChecked(autoSumm);
        } catch (Throwable ignored) {}

        if (switchAutoSumm != null) {
            switchAutoSumm.setOnCheckedChangeListener((buttonView, isChecked) -> {
                try {
                    SPManager.getInstance().setAutoSummarizeOldContextEnabled(isChecked);
                } catch (Throwable ignored) {}
            });
        }
        if (rowAutoSumm != null && switchAutoSumm != null) {
            rowAutoSumm.setOnClickListener(v -> switchAutoSumm.setChecked(!switchAutoSumm.isChecked()));
        }

        if (tvDowngrade != null) tvDowngrade.setText(getAutoDowngradeSummary());
        if (rowDowngrade != null) {
            rowDowngrade.setOnClickListener(v -> showAutoDowngradeDialog(() -> {
                if (tvDowngrade != null) tvDowngrade.setText(getAutoDowngradeSummary());
            }));
        }

        try {
            int policy = SPManager.getInstance().getRequestConcurrencyPolicy();
            if (tvPolicy != null) tvPolicy.setText(getRequestPolicyLabel(policy));
        } catch (Throwable ignored) {}

        if (rowPolicy != null) {
            rowPolicy.setOnClickListener(v -> showRequestPolicyDialog(
                    safeGetRequestPolicy(),
                    selected -> {
                        try {
                            SPManager.getInstance().setRequestConcurrencyPolicy(selected);
                        } catch (Throwable ignored) {}
                        if (tvPolicy != null) tvPolicy.setText(getRequestPolicyLabel(selected));
                    }
            ));
        }


        // Generating Content settings (placeholder + trailing keyword + haptic)
        View rowGenContent = view.findViewById(R.id.row_generating_content);
        TextView tvGenContent = view.findViewById(R.id.tv_generating_content_value);

        Runnable refreshGenContent = () -> {
            boolean en = true;
            try {
                en = SPManager.getInstance().getGeneratingContentEnabled();
            } catch (Throwable ignored) {}
            if (tvGenContent != null) {
                tvGenContent.setText(en ? getString(R.string.ui_enabled) : getString(R.string.ui_disabled));
            }
        };
        refreshGenContent.run();

        if (rowGenContent != null) {
            rowGenContent.setOnClickListener(v -> showGeneratingContentDialog(refreshGenContent));
        }


        // Roles (system prompt/persona)
        View rowRoles = view.findViewById(R.id.row_manage_roles);
        TextView tvRolesValue = view.findViewById(R.id.tv_manage_roles_value);

        Runnable refreshRoles = () -> {
            try {
                SPManager sp = SPManager.getInstance();
                String activeId = sp.getActiveRoleId();
                String rolesJson = sp.getRolesJson();
                java.util.List<RoleManager.Role> roles = RoleManager.loadRoles(rolesJson);
                String name = RoleManager.DEFAULT_ROLE_NAME;
                if (activeId != null) {
                    for (RoleManager.Role r : roles) {
                        if (r != null && activeId.equals(r.id)) {
                            name = r.name;
                            break;
                        }
                    }
                }
                if (tvRolesValue != null) tvRolesValue.setText(name);
            } catch (Throwable t) {
                if (tvRolesValue != null) tvRolesValue.setText(RoleManager.DEFAULT_ROLE_NAME);
            }
        };
        refreshRoles.run();

        if (rowRoles != null) {
            rowRoles.setOnClickListener(v -> {
                if (getContext() == null) return;
                RolesManagerUi.show(requireContext(), refreshRoles);
            });
        }



    }

    private void showGeneratingContentDialog(Runnable onSaved) {
        LayoutInflater inflater = LayoutInflater.from(getContext());
        View dialogView = inflater.inflate(R.layout.dialog_generating_content, null);

        SwitchMaterial switchEnabled = dialogView.findViewById(R.id.switch_gen_content_enabled);
        TextInputEditText etPrefix = dialogView.findViewById(R.id.et_gen_content_prefix);
        TextInputEditText etSuffix = dialogView.findViewById(R.id.et_gen_content_suffix);

        MaterialAutoCompleteTextView actMarkerStyle = dialogView.findViewById(R.id.act_gen_marker_style);
        MaterialAutoCompleteTextView actMarkerColor = dialogView.findViewById(R.id.act_gen_marker_color);
        MaterialAutoCompleteTextView actMarkerLen = dialogView.findViewById(R.id.act_gen_marker_len);
        com.google.android.material.slider.Slider sliderMarkerSpeed = dialogView.findViewById(R.id.slider_gen_marker_speed);
        TextView tvMarkerSpeed = dialogView.findViewById(R.id.tv_gen_marker_speed_value);
        SwitchMaterial switchToast = dialogView.findViewById(R.id.switch_gen_content_toast);
        MaterialAutoCompleteTextView actSound = dialogView.findViewById(R.id.act_gen_complete_sound);

        SwitchMaterial switchVibrate = dialogView.findViewById(R.id.switch_ai_reply_vibrate);
        Slider sliderIntensity = dialogView.findViewById(R.id.slider_vibrate_intensity);
        Slider sliderFrequency = dialogView.findViewById(R.id.slider_vibrate_frequency);
        TextView tvIntensityLabel = dialogView.findViewById(R.id.tv_vibrate_intensity_label);
        TextView tvFrequencyLabel = dialogView.findViewById(R.id.tv_vibrate_frequency_label);

        SPManager sp = SPManager.getInstance();

        // Make dropdowns always open on tap (some OEM ROMs + inputType=none may not open reliably)
        forceDropdownOnTap(actMarkerStyle);
        forceDropdownOnTap(actMarkerColor);
        forceDropdownOnTap(actMarkerLen);
        forceDropdownOnTap(actSound);

        // Sliders inside ScrollView: prevent parent from intercepting horizontal drags
        disallowParentInterceptOnTouch(sliderMarkerSpeed);
        disallowParentInterceptOnTouch(sliderIntensity);
        disallowParentInterceptOnTouch(sliderFrequency);

        switchEnabled.setChecked(sp.getGeneratingContentEnabled());
        etPrefix.setText(sp.getGeneratingContentPrefix());
        etSuffix.setText(sp.getGeneratingContentSuffixAfterCursor());
        switchToast.setChecked(sp.getGeneratingContentToastEnabled());

        switchVibrate.setChecked(sp.getAiReplyVibrateEnabled());
        sliderIntensity.setValue(sp.getAiReplyVibrateIntensityPercent());
        sliderFrequency.setValue(sp.getAiReplyVibrateFrequencyPercent());

        // ===== Dropdown: Marker Style =====
        final int[] styleValues = new int[] {
                SPManager.GEN_MARKER_STYLE_PLAIN,
                SPManager.GEN_MARKER_STYLE_COLOR_TAG,
                SPManager.GEN_MARKER_STYLE_RAINBOW_ANIM
        };
        final String[] styleLabels = new String[] {
                getString(R.string.ui_generating_content_marker_style_plain),
                getString(R.string.ui_generating_content_marker_style_color),
                getString(R.string.ui_generating_content_marker_style_rainbow)
        };
        ArrayAdapter<String> styleAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, styleLabels);
        actMarkerStyle.setAdapter(styleAdapter);

        // ===== Dropdown: Marker Color =====
        final int[] colorValues = new int[] {
                SPManager.GEN_MARKER_COLOR_BLUE,
                SPManager.GEN_MARKER_COLOR_RED,
                SPManager.GEN_MARKER_COLOR_GREEN,
                SPManager.GEN_MARKER_COLOR_YELLOW,
                SPManager.GEN_MARKER_COLOR_PURPLE,
                SPManager.GEN_MARKER_COLOR_RANDOM
        };
        final String[] colorLabels = new String[] {
                "🟦 " + getString(R.string.ui_generating_content_marker_color_blue),
                "🟥 " + getString(R.string.ui_generating_content_marker_color_red),
                "🟩 " + getString(R.string.ui_generating_content_marker_color_green),
                "🟨 " + getString(R.string.ui_generating_content_marker_color_yellow),
                "🟪 " + getString(R.string.ui_generating_content_marker_color_purple),
                "🎲 " + getString(R.string.ui_generating_content_marker_color_random)
        };
        ArrayAdapter<String> colorAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, colorLabels);
        actMarkerColor.setAdapter(colorAdapter);

        // ===== Dropdown: Completion Sound =====
        final int[] soundValues = new int[] {
                SPManager.GEN_SOUND_NONE,
                SPManager.GEN_SOUND_SYSTEM_NOTIFICATION,
                SPManager.GEN_SOUND_BEEP,
                SPManager.GEN_SOUND_CLICK
        };
        final String[] soundLabels = new String[] {
                getString(R.string.ui_generating_content_sound_none),
                getString(R.string.ui_generating_content_sound_system),
                getString(R.string.ui_generating_content_sound_beep),
                getString(R.string.ui_generating_content_sound_click)
        };
        ArrayAdapter<String> soundAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, soundLabels);
        actSound.setAdapter(soundAdapter);

        // ===== Dropdown: Rainbow Length =====
        final int[] lenValues = new int[] { 3, 6, 10 };
        final String[] lenLabels = new String[] { "3", "6", "10" };
        ArrayAdapter<String> lenAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, lenLabels);
        actMarkerLen.setAdapter(lenAdapter);

        // ===== Slider: Rainbow Speed (percent) =====
        Runnable updateMarkerSpeedLabel = () -> {
            if (tvMarkerSpeed != null) {
                tvMarkerSpeed.setText(Math.round(sliderMarkerSpeed.getValue()) + "%");
            }
        };
        updateMarkerSpeedLabel.run();
        sliderMarkerSpeed.addOnChangeListener((slider, value, fromUser) -> updateMarkerSpeedLabel.run());


        // ===== Preselect dropdowns =====
        int currentStyle = sp.getGeneratingContentMarkerStyle();
        for (int i = 0; i < styleValues.length; i++) {
            if (styleValues[i] == currentStyle) {
                actMarkerStyle.setText(styleLabels[i], false);
                break;
            }
        }

        int currentColor = sp.getGeneratingContentMarkerColor();
        for (int i = 0; i < colorValues.length; i++) {
            if (colorValues[i] == currentColor) {
                actMarkerColor.setText(colorLabels[i], false);
                break;
            }
        }

        int currentSound = sp.getGeneratingContentCompleteSound();
        for (int i = 0; i < soundValues.length; i++) {
            if (soundValues[i] == currentSound) {
                actSound.setText(soundLabels[i], false);
                break;
            }
        }

        int currentLen = sp.getGeneratingContentMarkerAnimLength();
        for (int i = 0; i < lenValues.length; i++) {
            if (lenValues[i] == currentLen) {
                actMarkerLen.setText(lenLabels[i], false);
                break;
            }
        }

        sliderMarkerSpeed.setValue(sp.getGeneratingContentMarkerAnimSpeedPercent());
        updateMarkerSpeedLabel.run();

        // ===== Live enable/disable =====
        Runnable updateVibrateEnabled = () -> {
            boolean on = switchVibrate.isChecked();
            sliderIntensity.setEnabled(on);
            sliderFrequency.setEnabled(on);
            tvIntensityLabel.setAlpha(on ? 1f : 0.4f);
            tvFrequencyLabel.setAlpha(on ? 1f : 0.4f);
        };
        updateVibrateEnabled.run();
        switchVibrate.setOnCheckedChangeListener((buttonView, isChecked) -> updateVibrateEnabled.run());

        Runnable updateVibrateLabels = () -> {
            tvIntensityLabel.setText(getString(R.string.ui_ai_reply_vibrate_intensity) + "（" + Math.round(sliderIntensity.getValue()) + "%）");
            tvFrequencyLabel.setText(getString(R.string.ui_ai_reply_vibrate_frequency) + "（" + Math.round(sliderFrequency.getValue()) + "%）");
        };
        updateVibrateLabels.run();
        sliderIntensity.addOnChangeListener((slider, value, fromUser) -> updateVibrateLabels.run());
        sliderFrequency.addOnChangeListener((slider, value, fromUser) -> updateVibrateLabels.run());

        // Marker controls
        Runnable updateMarkerControlsEnabled = () -> {
            String styleText = actMarkerStyle.getText() == null ? "" : actMarkerStyle.getText().toString();
            int selected = styleValues[0];
            for (int i = 0; i < styleLabels.length; i++) {
                if (styleLabels[i].equals(styleText)) {
                    selected = styleValues[i];
                    break;
                }
            }

            boolean isPlain = selected == SPManager.GEN_MARKER_STYLE_PLAIN;
            boolean isRainbow = selected == SPManager.GEN_MARKER_STYLE_RAINBOW_ANIM;

            // Color is meaningful for non-plain modes
            actMarkerColor.setEnabled(!isPlain);
            actMarkerColor.setAlpha(isPlain ? 0.4f : 1f);

            // Rainbow-only controls
            actMarkerLen.setEnabled(isRainbow);
            actMarkerLen.setAlpha(isRainbow ? 1f : 0.4f);
            sliderMarkerSpeed.setEnabled(isRainbow);
            sliderMarkerSpeed.setAlpha(isRainbow ? 1f : 0.4f);
            if (tvMarkerSpeed != null) tvMarkerSpeed.setAlpha(isRainbow ? 1f : 0.4f);
        };
        updateMarkerControlsEnabled.run();
        actMarkerStyle.setOnItemClickListener((parent, view, position, id) -> updateMarkerControlsEnabled.run());

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(getContext())
                .setTitle(getString(R.string.ui_generating_content_dialog_title))
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, (di, which) -> {
                    sp.setGeneratingContentEnabled(switchEnabled.isChecked());
                    sp.setGeneratingContentPrefix(etPrefix.getText() == null ? "" : etPrefix.getText().toString());
                    sp.setGeneratingContentSuffixAfterCursor(etSuffix.getText() == null ? "" : etSuffix.getText().toString());

                    sp.setGeneratingContentToastEnabled(switchToast.isChecked());

                    sp.setAiReplyVibrateEnabled(switchVibrate.isChecked());
                    sp.setAiReplyVibrateIntensityPercent(Math.round(sliderIntensity.getValue()));
                    sp.setAiReplyVibrateFrequencyPercent(Math.round(sliderFrequency.getValue()));

                    int selectedStyle = styleValues[0];
                    String styleSel = actMarkerStyle.getText() == null ? "" : actMarkerStyle.getText().toString();
                    for (int i = 0; i < styleLabels.length; i++) {
                        if (styleLabels[i].equals(styleSel)) {
                            selectedStyle = styleValues[i];
                            break;
                        }
                    }
                    sp.setGeneratingContentMarkerStyle(selectedStyle);

                    int selectedLen = sp.getGeneratingContentMarkerAnimLength();
                    String lenSel = actMarkerLen.getText() == null ? "" : actMarkerLen.getText().toString();
                    for (int i = 0; i < lenLabels.length; i++) {
                        if (lenLabels[i].equals(lenSel)) {
                            selectedLen = lenValues[i];
                            break;
                        }
                    }
                    sp.setGeneratingContentMarkerAnimLength(selectedLen);
                    sp.setGeneratingContentMarkerAnimSpeedPercent(Math.round(sliderMarkerSpeed.getValue()));

                    int selectedColor = colorValues[0];
                    String colorSel = actMarkerColor.getText() == null ? "" : actMarkerColor.getText().toString();
                    for (int i = 0; i < colorLabels.length; i++) {
                        if (colorLabels[i].equals(colorSel)) {
                            selectedColor = colorValues[i];
                            break;
                        }
                    }
                    sp.setGeneratingContentMarkerColor(selectedColor);

                    int selectedSound = soundValues[0];
                    String soundSel = actSound.getText() == null ? "" : actSound.getText().toString();
                    for (int i = 0; i < soundLabels.length; i++) {
                        if (soundLabels[i].equals(soundSel)) {
                            selectedSound = soundValues[i];
                            break;
                        }
                    }
                    sp.setGeneratingContentCompleteSound(selectedSound);

                    if (onSaved != null) onSaved.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() == null) return;
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int margin = (int) (16 * requireContext().getResources().getDisplayMetrics().density);
            android.graphics.drawable.Drawable bg = dialog.getWindow().getDecorView().getBackground();
            if (bg != null) {
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.InsetDrawable(bg, margin));
            }
        });

        dialog.show();

    }

    /**
     * Some ROMs + TextInputLayout combinations may not reliably open the dropdown when
     * MaterialAutoCompleteTextView uses inputType="none". Force opening on tap/focus.
     */
    private void forceDropdownOnTap(MaterialAutoCompleteTextView act) {
        if (act == null) return;
        act.setThreshold(0);
        act.setOnClickListener(v -> act.showDropDown());
        act.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                act.showDropDown();
            }
        });
    }

    /**
     * Ensure sliders inside ScrollView remain draggable (prevent parent from hijacking horizontal drag).
     */
    private void disallowParentInterceptOnTouch(View v) {
        if (v == null) return;
        v.setOnTouchListener((view, event) -> {
            if (view.getParent() != null) {
                view.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });
    }

    private boolean getSafeStreamingEnabled() {
        try {
            return SPManager.getInstance().getStreamingOutputEnabled();
        } catch (Throwable ignored) {
        }
        return false;
    }

    private int getSafeStreamingMode() {
        try {
            return SPManager.getInstance().getStreamingOutputMode();
        } catch (Throwable ignored) {
        }
        return SPManager.STREAM_MODE_AUTO;
    }

    private int getSafeStreamingGranularity() {
        try {
            return SPManager.getInstance().getStreamingOutputGranularity();
        } catch (Throwable ignored) {
        }
        return SPManager.STREAM_GRANULARITY_CHARS;
    }

    private int getSafeStreamingSpeedAlgorithm() {
        try {
            return SPManager.getInstance().getStreamingOutputSpeedAlgorithm();
        } catch (Throwable ignored) {
        }
        return SPManager.STREAM_SPEED_ALGO_LINEAR;
    }

    private String getLevelLabel(int level, int defaultValue) {
        int v = level;
        if (v < 0) v = 0;
        if (v > 10) v = 10;

        String label = String.valueOf(v);
        if (v == defaultValue) {
            label = label + " " + getString(R.string.ui_default_value);
        }
        return label;
    }

    private void showLevelDialog(@androidx.annotation.StringRes int titleRes,
                                 int currentValue,
                                 int defaultValue,
                                 IntConsumer onSelected) {
        int cur = currentValue;
        if (cur < 0) cur = 0;
        if (cur > 10) cur = 10;

        String defaultTag = " " + getString(R.string.ui_default_value);
        String[] items = new String[11];
        for (int i = 0; i <= 10; i++) {
            items[i] = String.valueOf(i) + (i == defaultValue ? defaultTag : "");
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(titleRes)
                .setSingleChoiceItems(items, cur, (dialog, which) -> {
                    if (onSelected != null) {
                        onSelected.accept(which);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

private interface IntConsumer {
        void accept(int value);
    }


    private boolean isNormalModelThinkingAdjustable() {
        return true;
    }

    private boolean isReasoningModelThinkingAdjustable() {
        return true;
    }

    private void refreshNormalModelThinkingRowState() {
        if (rowNormalModelThinking == null || tvNormalModelThinkingValue == null) return;
        boolean adjustable = isNormalModelThinkingAdjustable();
        float alpha = adjustable ? 1.0f : 0.45f;
        try {
            rowNormalModelThinking.setEnabled(adjustable);
            rowNormalModelThinking.setAlpha(alpha);
            tvNormalModelThinkingValue.setEnabled(adjustable);
        } catch (Throwable ignored) {}

        try {
            float v = SPManager.getInstance().getNormalModelThinking();
            tvNormalModelThinkingValue.setText(getNormalModelThinkingSummary(v));
        } catch (Throwable ignored) {}
    }

    private void refreshReasoningModelThinkingRowState() {
        if (rowReasoningModelThinking == null || tvReasoningModelThinkingValue == null) return;
        boolean adjustable = isReasoningModelThinkingAdjustable();
        float alpha = adjustable ? 1.0f : 0.45f;
        try {
            rowReasoningModelThinking.setEnabled(adjustable);
            rowReasoningModelThinking.setAlpha(alpha);
            tvReasoningModelThinkingValue.setEnabled(adjustable);
        } catch (Throwable ignored) {}

        try {
            int v = SPManager.getInstance().getReasoningModelThinkingMode();
            tvReasoningModelThinkingValue.setText(getReasoningModelThinkingSummary(v));
        } catch (Throwable ignored) {}
    }

    private String getReasoningModelThinkingSummary(int id) {
        try {
            ReasoningModelThinkingOption opt = ReasoningModelThinkingOptions.findById(id);
            if (opt != null) return opt.title;
        } catch (Throwable ignored) {}
        return String.valueOf(id);
    }

    private void showReasoningModelThinkingDialog(int currentId, java.util.function.Consumer<Integer> onSelected) {
        if (getContext() == null) return;

        java.util.List<ReasoningModelThinkingOption> options = ReasoningModelThinkingOptions.buildOptions();
        int initial = ReasoningModelThinkingOptions.indexOf(currentId);
        if (initial < 0) initial = ReasoningModelThinkingOptions.indexOf(ReasoningModelThinkingOptions.AUTO);

        View content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_reasoning_model_thinking, null);
        RecyclerView rv = content.findViewById(R.id.rv_reasoning_model_thinking_options);

        final androidx.appcompat.app.AlertDialog[] dialogRef = new androidx.appcompat.app.AlertDialog[1];

        final ReasoningModelThinkingOptionAdapter adapter = new ReasoningModelThinkingOptionAdapter(options, initial, pos -> {
            if (pos < 0 || pos >= options.size()) return;
            try {
                if (onSelected != null) onSelected.accept(options.get(pos).id);
            } catch (Throwable ignored) {}
            try {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
            } catch (Throwable ignored) {}
        });

        if (rv != null) {
            rv.setLayoutManager(new LinearLayoutManager(requireContext()));
            rv.setAdapter(adapter);
        }

        dialogRef[0] = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ui_reasoning_model_thinking)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialogRef[0].show();

        // Full-width dialog
        try {
            if (dialogRef[0].getWindow() != null) {
                dialogRef[0].getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable ignored) {}
    }

    private String getNormalModelThinkingSummary(float value) {
        try {
            NormalModelThinkingOption opt = NormalModelThinkingOptions.findByValue(value);
            if (opt != null) return opt.title;
        } catch (Throwable ignored) {}
        float v = value;
        if (v < 0.0f) v = 0.0f;
        if (v > 1.8f) v = 1.8f;
        v = Math.round(v * 10.0f) / 10.0f;
        return String.valueOf(v);
    }

    private void showNormalModelThinkingDialog(float currentValue, java.util.function.Consumer<Float> onSelected) {
        if (getContext() == null) return;

        java.util.List<NormalModelThinkingOption> options = NormalModelThinkingOptions.buildOptions();
        int initial = NormalModelThinkingOptions.indexOf(currentValue);
        if (initial < 0) initial = NormalModelThinkingOptions.indexOf(0.7f);

        View content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_normal_model_thinking, null);
        RecyclerView rv = content.findViewById(R.id.rv_normal_model_thinking_options);

        final androidx.appcompat.app.AlertDialog[] dialogRef = new androidx.appcompat.app.AlertDialog[1];

        final NormalModelThinkingOptionAdapter adapter = new NormalModelThinkingOptionAdapter(options, initial, pos -> {
            if (pos < 0 || pos >= options.size()) return;
            try {
                if (onSelected != null) onSelected.accept(options.get(pos).value);
            } catch (Throwable ignored) {}
            try {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
            } catch (Throwable ignored) {}
        });

        if (rv != null) {
            rv.setLayoutManager(new LinearLayoutManager(requireContext()));
            rv.setAdapter(adapter);
        }

        dialogRef[0] = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ui_thinking_depth)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialogRef[0].show();

        // Full-width dialog
        try {
            if (dialogRef[0].getWindow() != null) {
                dialogRef[0].getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable ignored) {}
    }


    private void showStreamingModeDialog(int current, IntConsumer onSelected) {
        if (getContext() == null) return;

        final int[] values = new int[] {
                SPManager.STREAM_MODE_AUTO,
                SPManager.STREAM_MODE_SSE,
                SPManager.STREAM_MODE_JSONL,
                SPManager.STREAM_MODE_TYPEWRITER,
        };

        final String[] items = new String[] {
                getString(R.string.ui_streaming_mode_auto),
                getString(R.string.ui_streaming_mode_sse),
                getString(R.string.ui_streaming_mode_jsonl),
                getString(R.string.ui_streaming_mode_typewriter),
        };

        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) { checked = i; break; }
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ui_streaming_mode)
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    if (which >= 0 && which < values.length) {
                        onSelected.accept(values[which]);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showStreamingGranularityDialog(int current, IntConsumer onSelected) {
        if (getContext() == null) return;

        final int[] values = new int[] {
                SPManager.STREAM_GRANULARITY_CHARS,
                SPManager.STREAM_GRANULARITY_WORDS,
                SPManager.STREAM_GRANULARITY_PUNCT,
        };

        final String[] items = new String[] {
                getString(R.string.ui_streaming_granularity_chars),
                getString(R.string.ui_streaming_granularity_words),
                getString(R.string.ui_streaming_granularity_punct),
        };

        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) { checked = i; break; }
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ui_streaming_granularity)
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    if (which >= 0 && which < values.length) {
                        if (onSelected != null) onSelected.accept(values[which]);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showStreamingSpeedAlgorithmDialog(int current, IntConsumer onSelected) {
        if (getContext() == null) return;

        final int[] values = new int[] {
                SPManager.STREAM_SPEED_ALGO_LINEAR,
                SPManager.STREAM_SPEED_ALGO_NONLINEAR,
        };

        final String[] items = new String[] {
                getString(R.string.ui_streaming_speed_algorithm_linear),
                getString(R.string.ui_streaming_speed_algorithm_nonlinear),
        };

        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) { checked = i; break; }
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ui_streaming_speed_algorithm)
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    if (which >= 0 && which < values.length) {
                        if (onSelected != null) onSelected.accept(values[which]);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String getStreamingSpeedAlgorithmLabel(int algo) {
        switch (algo) {
            case SPManager.STREAM_SPEED_ALGO_NONLINEAR:
                return getString(R.string.ui_streaming_speed_algorithm_nonlinear);
            case SPManager.STREAM_SPEED_ALGO_LINEAR:
            default:
                return getString(R.string.ui_streaming_speed_algorithm_linear);
        }
    }

    private String getStreamingNonLinearModelLabel(int model) {
        switch (model) {
            case SPManager.STREAM_NL_MODEL_EXPONENTIAL_DECAY:
                return getString(R.string.ui_streaming_nl_model_exponential_decay);
            case SPManager.STREAM_NL_MODEL_SINE_WAVE_JITTER:
                return getString(R.string.ui_streaming_nl_model_sine_wave_jitter);
            case SPManager.STREAM_NL_MODEL_DAMPED_OSCILLATOR:
                return getString(R.string.ui_streaming_nl_model_damped_oscillator);
            case SPManager.STREAM_NL_MODEL_SQUARE_WAVE_BURST:
                return getString(R.string.ui_streaming_nl_model_square_wave_burst);
            case SPManager.STREAM_NL_MODEL_MARKOV_RANDOM_WALK:
                return getString(R.string.ui_streaming_nl_model_markov_random_walk);
            case SPManager.STREAM_NL_MODEL_LINEAR_CONSTANT:
            default:
                return getString(R.string.ui_streaming_nl_model_linear_constant);
        }
    }

    private String getStreamingModeLabel(int mode) {
        switch (mode) {
            case SPManager.STREAM_MODE_SSE:
                return getString(R.string.ui_streaming_mode_sse);
            case SPManager.STREAM_MODE_JSONL:
                return getString(R.string.ui_streaming_mode_jsonl);
            case SPManager.STREAM_MODE_TYPEWRITER:
                return getString(R.string.ui_streaming_mode_typewriter);
            case SPManager.STREAM_MODE_AUTO:
            default:
                return getString(R.string.ui_streaming_mode_auto);
        }
    }

    private String getStreamingGranularityLabel(int granularity) {
        switch (granularity) {
            case SPManager.STREAM_GRANULARITY_WORDS:
                return getString(R.string.ui_streaming_granularity_words);
            case SPManager.STREAM_GRANULARITY_PUNCT:
                return getString(R.string.ui_streaming_granularity_punct);
            case SPManager.STREAM_GRANULARITY_CHARS:
            default:
                return getString(R.string.ui_streaming_granularity_chars);
        }
    }


    private int safeGetMaxTokensLimit() {
        try {
            return SPManager.getInstance().getMaxTokensLimit();
        } catch (Throwable ignored) {}
        return 1024;
    }

    private String getOutputLengthSummary(int tokens) {
        OutputLengthOption fixed = OutputLengthOptions.findFixedByTokens(tokens);
        if (fixed != null && fixed.title != null && !fixed.title.trim().isEmpty()) {
            return fixed.title;
        }
        // Custom
        return tokens + " Token";
    }

    private void updateCustomApproxText(TextView tv, String raw) {
        if (tv == null) return;
        int tokens = 0;
        try {
            if (raw != null && !raw.trim().isEmpty()) tokens = Integer.parseInt(raw.trim());
        } catch (Throwable ignored) {
            tokens = 0;
        }
        int chars = (int) Math.floor(tokens * 0.8d);
        int words = (int) Math.floor(tokens * 0.75d);
        try {
            tv.setText(getString(R.string.ui_output_length_custom_approx, chars, words));
        } catch (Throwable t) {
            // Fallback (should not happen)
            tv.setText("约等于 " + chars + " 字、" + words + " 词");
        }
    }

    private void showOutputLengthDialog(int currentTokens, java.util.function.IntConsumer onConfirmed) {
        if (getContext() == null) return;

        final int customIndex;
        final java.util.List<OutputLengthOption> options = OutputLengthOptions.buildOptions(
                getString(R.string.ui_output_length_custom),
                getString(R.string.ui_output_length_custom_subtitle)
        );
        customIndex = options.size() - 1;

        int initialIndex = -1;
        for (int i = 0; i < options.size(); i++) {
            OutputLengthOption o = options.get(i);
            if (!o.isCustom && o.tokens == currentTokens) {
                initialIndex = i;
                break;
            }
        }
        if (initialIndex < 0) initialIndex = customIndex;

        View content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_output_length, null);
        RecyclerView rv = content.findViewById(R.id.rv_output_length_options);
        TextInputLayout tilCustom = content.findViewById(R.id.til_custom_tokens);
        TextInputEditText etCustom = content.findViewById(R.id.et_custom_tokens);
        TextView tvHint = content.findViewById(R.id.tv_custom_tokens_hint);

        final int[] selected = new int[]{initialIndex};

        final OutputLengthOptionAdapter[] adapterRef = new OutputLengthOptionAdapter[1];
        adapterRef[0] = new OutputLengthOptionAdapter(options, initialIndex, pos -> {
            selected[0] = pos;
            if (adapterRef[0] != null) adapterRef[0].setSelectedIndex(pos);
            boolean isCustom = (pos == customIndex);
            if (tilCustom != null) tilCustom.setVisibility(isCustom ? View.VISIBLE : View.GONE);
            if (tvHint != null) tvHint.setVisibility(isCustom ? View.VISIBLE : View.GONE);
            if (isCustom && etCustom != null) {
                etCustom.requestFocus();
            }
        });

        if (rv != null) {
            rv.setLayoutManager(new LinearLayoutManager(requireContext()));
            rv.setAdapter(adapterRef[0]);
        }

        // Init custom area
        boolean startCustom = (initialIndex == customIndex);
        if (tilCustom != null) tilCustom.setVisibility(startCustom ? View.VISIBLE : View.GONE);
        if (tvHint != null) tvHint.setVisibility(startCustom ? View.VISIBLE : View.GONE);

        if (etCustom != null) {
            if (startCustom) {
                etCustom.setText(String.valueOf(currentTokens));
                etCustom.setSelection(etCustom.getText() == null ? 0 : etCustom.getText().length());
            }
            updateCustomApproxText(tvHint, etCustom.getText() == null ? "" : etCustom.getText().toString());
            etCustom.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    updateCustomApproxText(tvHint, s == null ? "" : s.toString());
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ui_output_length)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();

        dialog.setOnShowListener(d -> {
            android.widget.Button ok = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            if (ok != null) {
                ok.setOnClickListener(v -> {
                    int chosenTokens;
                    if (selected[0] == customIndex) {
                        String raw = etCustom == null || etCustom.getText() == null ? "" : etCustom.getText().toString();
                        int parsed = 0;
                        try {
                            if (raw != null && !raw.trim().isEmpty()) parsed = Integer.parseInt(raw.trim());
                        } catch (Throwable ignored) {
                            parsed = 0;
                        }
                        if (parsed <= 0) {
                            try {
                                android.widget.Toast.makeText(requireContext(), getString(R.string.ui_invalid_value), android.widget.Toast.LENGTH_SHORT).show();
                            } catch (Throwable t) {
                                android.widget.Toast.makeText(requireContext(), "Invalid value", android.widget.Toast.LENGTH_SHORT).show();
                            }
                            return;
                        }
                        chosenTokens = parsed;
                    } else {
                        chosenTokens = options.get(selected[0]).tokens;
                    }

                    if (onConfirmed != null) onConfirmed.accept(chosenTokens);
                    dialog.dismiss();
                });
            }
        });

        dialog.show();
    }

    private int safeGetRequestPolicy() {
        try {
            return SPManager.getInstance().getRequestConcurrencyPolicy();
        } catch (Throwable ignored) {
            return SPManager.REQUEST_POLICY_CANCEL_PREVIOUS;
        }
    }

    private String getRequestPolicyLabel(int policy) {
        int p = policy;
        if (p < SPManager.REQUEST_POLICY_CANCEL_PREVIOUS || p > SPManager.REQUEST_POLICY_QUEUE_LATEST) {
            p = SPManager.REQUEST_POLICY_CANCEL_PREVIOUS;
        }
        switch (p) {
            case SPManager.REQUEST_POLICY_IGNORE_NEW:
                return getString(R.string.ui_request_policy_ignore_new);
            case SPManager.REQUEST_POLICY_QUEUE_LATEST:
                return getString(R.string.ui_request_policy_queue_latest);
            case SPManager.REQUEST_POLICY_CANCEL_PREVIOUS:
            default:
                return getString(R.string.ui_request_policy_cancel_previous);
        }
    }

    private void showRequestPolicyDialog(int current, IntConsumer onSelected) {
        if (getContext() == null) return;

        final int[] values = new int[] {
                SPManager.REQUEST_POLICY_CANCEL_PREVIOUS,
                SPManager.REQUEST_POLICY_IGNORE_NEW,
                SPManager.REQUEST_POLICY_QUEUE_LATEST,
        };

        final String[] items = new String[] {
                getString(R.string.ui_request_policy_cancel_previous),
                getString(R.string.ui_request_policy_ignore_new),
                getString(R.string.ui_request_policy_queue_latest),
        };

        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) { checked = i; break; }
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ui_request_policy)
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    if (which >= 0 && which < values.length) {
                        if (onSelected != null) onSelected.accept(values[which]);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String getAutoDowngradeSummary() {
        int flags = 0;
        try { flags = SPManager.getInstance().getAutoDowngradeFlags(); } catch (Throwable ignored) {}

        ArrayList<String> parts = new ArrayList<>();
        if ((flags & SPManager.DOWNGRADE_FLAG_STREAM) != 0) parts.add(getString(R.string.ui_auto_downgrade_stream));
        if ((flags & SPManager.DOWNGRADE_FLAG_BASEURL) != 0) parts.add(getString(R.string.ui_auto_downgrade_baseurl));
        if ((flags & SPManager.DOWNGRADE_FLAG_MODEL) != 0) parts.add(getString(R.string.ui_auto_downgrade_model));

        if (parts.isEmpty()) return getString(R.string.ui_off);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(" + ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private void showAutoDowngradeDialog(@Nullable Runnable onSaved) {
        if (getContext() == null) return;

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_auto_downgrade, null, false);

        SwitchMaterial swStream = dialogView.findViewById(R.id.switch_downgrade_stream);
        SwitchMaterial swBaseUrl = dialogView.findViewById(R.id.switch_downgrade_baseurl);
        SwitchMaterial swModel = dialogView.findViewById(R.id.switch_downgrade_model);
        TextInputEditText etBaseUrl = dialogView.findViewById(R.id.et_backup_baseurl);
        View rowModel = dialogView.findViewById(R.id.row_backup_model);
        TextView tvModelValue = dialogView.findViewById(R.id.tv_backup_model_value);

        int flags = 0;
        String baseUrl = "";
        LanguageModel backupModel = null;
        try { flags = SPManager.getInstance().getAutoDowngradeFlags(); } catch (Throwable ignored) {}
        try { baseUrl = SPManager.getInstance().getAutoDowngradeBackupBaseUrl(); } catch (Throwable ignored) {}
        try { backupModel = SPManager.getInstance().getAutoDowngradeBackupModel(); } catch (Throwable ignored) {}

        final LanguageModel[] selectedModel = new LanguageModel[] { backupModel };

        if (swStream != null) swStream.setChecked((flags & SPManager.DOWNGRADE_FLAG_STREAM) != 0);
        if (swBaseUrl != null) swBaseUrl.setChecked((flags & SPManager.DOWNGRADE_FLAG_BASEURL) != 0);
        if (swModel != null) swModel.setChecked((flags & SPManager.DOWNGRADE_FLAG_MODEL) != 0);

        if (etBaseUrl != null && baseUrl != null) etBaseUrl.setText(baseUrl);

        if (tvModelValue != null) {
            tvModelValue.setText(selectedModel[0] == null ? getString(R.string.ui_not_set) : selectedModel[0].label);
        }

        final Runnable refreshEnabledState = () -> {
            boolean baseEnabled = swBaseUrl != null && swBaseUrl.isChecked();
            boolean modelEnabled = swModel != null && swModel.isChecked();

            if (etBaseUrl != null) {
                etBaseUrl.setEnabled(baseEnabled);
                etBaseUrl.setAlpha(baseEnabled ? 1.0f : 0.5f);
            }
            if (rowModel != null) {
                rowModel.setEnabled(modelEnabled);
                rowModel.setAlpha(modelEnabled ? 1.0f : 0.5f);
            }
        };

        if (swBaseUrl != null) swBaseUrl.setOnCheckedChangeListener((b, c) -> refreshEnabledState.run());
        if (swModel != null) swModel.setOnCheckedChangeListener((b, c) -> refreshEnabledState.run());
        refreshEnabledState.run();

        if (rowModel != null) {
            rowModel.setOnClickListener(v -> {
                LanguageModel[] models = LanguageModel.values();
                String[] items = new String[models.length + 1];
                items[0] = getString(R.string.ui_not_set);
                int checked = 0;
                for (int i = 0; i < models.length; i++) {
                    items[i + 1] = models[i].label;
                    if (selectedModel[0] == models[i]) checked = i + 1;
                }

                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.ui_backup_model)
                        .setSingleChoiceItems(items, checked, (dialog, which) -> {
                            if (which <= 0) {
                                selectedModel[0] = null;
                            } else if (which - 1 < models.length) {
                                selectedModel[0] = models[which - 1];
                            }
                            if (tvModelValue != null) {
                                tvModelValue.setText(selectedModel[0] == null ? getString(R.string.ui_not_set) : selectedModel[0].label);
                            }
                            dialog.dismiss();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ui_auto_downgrade_strategy)
                .setView(dialogView)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    int newFlags = 0;
                    if (swStream != null && swStream.isChecked()) newFlags |= SPManager.DOWNGRADE_FLAG_STREAM;
                    if (swBaseUrl != null && swBaseUrl.isChecked()) newFlags |= SPManager.DOWNGRADE_FLAG_BASEURL;
                    if (swModel != null && swModel.isChecked()) newFlags |= SPManager.DOWNGRADE_FLAG_MODEL;

                    String newBase = "";
                    if (etBaseUrl != null && etBaseUrl.getText() != null) {
                        newBase = etBaseUrl.getText().toString();
                    }

                    try { SPManager.getInstance().setAutoDowngradeFlags(newFlags); } catch (Throwable ignored) {}
                    try { SPManager.getInstance().setAutoDowngradeBackupBaseUrl(newBase); } catch (Throwable ignored) {}
                    try { SPManager.getInstance().setAutoDowngradeBackupModel(selectedModel[0]); } catch (Throwable ignored) {}

                    if (onSaved != null) onSaved.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
    private void setStreamingAlgorithmOptionRowsEnabled(@Nullable View rowLinear,
            @Nullable View rowNonLinear,
            @Nullable RadioButton rbLinear,
            @Nullable RadioButton rbNonLinear,
            boolean enabled) {
        float alpha = enabled ? 1.0f : 0.45f;
        if (rowLinear != null) {
            rowLinear.setEnabled(enabled);
            rowLinear.setAlpha(alpha);
        }
        if (rowNonLinear != null) {
            rowNonLinear.setEnabled(enabled);
            rowNonLinear.setAlpha(alpha);
        }
        if (rbLinear != null) rbLinear.setEnabled(enabled);
        if (rbNonLinear != null) rbNonLinear.setEnabled(enabled);
    }




    private void setStreamingNonLinearModelRowEnabled(@Nullable View nlModelRow,
            @Nullable TextView tvNlModelValue,
            boolean streamingEnabled,
            int speedAlgo) {
        // Always show the non-linear model row in the Streaming section.
        // It becomes editable only when streaming is enabled AND the speed algorithm is NON-LINEAR.
        boolean enabled = streamingEnabled && speedAlgo == SPManager.STREAM_SPEED_ALGO_NONLINEAR;
        if (nlModelRow != null) {
            nlModelRow.setVisibility(View.VISIBLE);
            nlModelRow.setEnabled(enabled);
            nlModelRow.setAlpha(enabled ? 1.0f : 0.45f);
        }
        if (tvNlModelValue != null) {
            try {
                int m = SPManager.getInstance().getStreamingNonLinearModel();
                tvNlModelValue.setText(getStreamingNonLinearModelLabel(m));
            } catch (Throwable ignored) {}
            tvNlModelValue.setEnabled(enabled);
        }
    }

    private void setStreamingSpeedControlsEnabled(@Nullable View speedRow,
            @Nullable Slider sliderSpeed,
            @Nullable TextView tvSpeedValue,
            @Nullable SwitchMaterial switchAuto,
            boolean streamingEnabled,
            int speedAlgo) {
        // These controls are only meaningful for LINEAR speed.
        boolean enabled = streamingEnabled && speedAlgo == SPManager.STREAM_SPEED_ALGO_LINEAR;
        boolean auto = false;
        try {
            if (switchAuto != null) {
                auto = switchAuto.isChecked();
            } else if (SPManager.isReady()) {
                auto = SPManager.getInstance().getStreamingOutputSpeedAutoEnabled();
            }
        } catch (Throwable ignored) {
        }

        boolean sliderEnabled = enabled && !auto;
        if (sliderSpeed != null) {
            sliderSpeed.setEnabled(sliderEnabled);
            sliderSpeed.setAlpha(sliderEnabled ? 1.0f : (enabled ? 0.35f : 0.45f));
        }
        if (switchAuto != null) switchAuto.setEnabled(enabled);
        if (tvSpeedValue != null) {
            tvSpeedValue.setEnabled(sliderEnabled);
            tvSpeedValue.setAlpha(sliderEnabled ? 1.0f : (enabled ? 0.45f : 0.45f));
        }
        if (speedRow != null) {
            speedRow.setEnabled(enabled);
            speedRow.setAlpha(enabled ? 1.0f : 0.45f);
        }
    }

    private void setStreamingAdvancedControlsEnabled(@Nullable View modeRow,
            @Nullable TextView tvModeValue,
            @Nullable View granularityRow,
            @Nullable TextView tvGranularityValue,
            @Nullable View fallbackRow,
            @Nullable SwitchMaterial switchFallback,
            boolean enabled,
            int mode) {

        // Disable controls when streaming output is OFF.
        float alpha = enabled ? 1.0f : 0.45f;

        if (modeRow != null) {
            modeRow.setEnabled(enabled);
            modeRow.setAlpha(alpha);
        }
        if (tvModeValue != null) {
            tvModeValue.setEnabled(enabled);
        }

        if (granularityRow != null) {
            granularityRow.setEnabled(enabled);
            granularityRow.setAlpha(alpha);
        }
        if (tvGranularityValue != null) {
            tvGranularityValue.setEnabled(enabled);
        }

        // Fallback is only meaningful when we actually request streaming from backend.
        boolean fallbackRelevant = enabled && mode != SPManager.STREAM_MODE_TYPEWRITER;
        float fallbackAlpha = fallbackRelevant ? 1.0f : 0.45f;
        if (fallbackRow != null) {
            fallbackRow.setEnabled(fallbackRelevant);
            fallbackRow.setAlpha(fallbackAlpha);
        }
        if (switchFallback != null) {
            switchFallback.setEnabled(fallbackRelevant);
        }
    }

    private void refreshConversationSubModelRow() {
        if (!SPManager.isReady()) return;
        try {
            LanguageModel provider = SPManager.getInstance().getLanguageModel();

            // Left subtitle: current provider (supplier)
            if (tvConversationProviderValue != null) {
                tvConversationProviderValue.setText(provider != null ? provider.label : getString(R.string.ui_not_set));
            }

            // Right small label: Sub-model
            if (tvConversationSubModelProvider != null) {
                tvConversationSubModelProvider.setText(getString(R.string.sub_model));
            }
            // Right value: selected sub-model for that provider
            if (tvConversationSubModelValue != null) {
                String sub = SPManager.getInstance().getSubModel(provider);
                if (TextUtils.isEmpty(sub) && provider != null) sub = provider.getDefault(LanguageModelField.SubModel);
                tvConversationSubModelValue.setText(!TextUtils.isEmpty(sub) ? sub : getString(R.string.ui_not_set));
            }
        } catch (Throwable ignored) {
        }
        // Update dependent rows
        refreshNormalModelThinkingRowState();
        refreshReasoningModelThinkingRowState();

    }

    

    @Override
    public void onProviderConfirmed(@NonNull LanguageModel provider) {
        if (!SPManager.isReady()) return;
        try {
            SPManager sp = SPManager.getInstance();
            sp.setLanguageModel(provider);
            refreshConversationSubModelRow();
            sendConfigBroadcast();
        } catch (Throwable ignored) {
        }
    }

    private void showConversationProviderPicker() {
        if (!isAdded()) return;
        ProviderListDialogFragment.newInstance()
                .show(getChildFragmentManager(), ProviderListDialogFragment.TAG);
    }

private void showConversationSubModelPicker() {
        if (!SPManager.isReady()) return;
        if (getContext() == null) return;

        final SPManager sp = SPManager.getInstance();

        final LanguageModel provider;
        try {
            provider = sp.getLanguageModel();
        } catch (Throwable t) {
            return;
        }

        String current = null;
        try {
            current = sp.getSubModel(provider);
        } catch (Throwable ignored) {
        }
        if (TextUtils.isEmpty(current) && provider != null) current = provider.getDefault(LanguageModelField.SubModel);
        final String selectedModel = current;

        final List<String> fullModels = SubModelSuggestions.getSuggestions(provider);
        int cachedCountTmp;
        try {
            cachedCountTmp = sp.getCachedModels(provider).size();
        } catch (Throwable t) {
            // Fallback to list size if cache is unavailable
            cachedCountTmp = fullModels != null ? fullModels.size() : 0;
        }
        final int cachedCount = cachedCountTmp;

final FloatingBottomSheet sheet = new FloatingBottomSheet(requireContext());
        final View root = LayoutInflater.from(requireContext()).inflate(R.layout.bottom_sheet_conversation_submodel, null);
        sheet.setContentView(root);

        TextView tvTitle = root.findViewById(R.id.tv_title);
        View btnClose = root.findViewById(R.id.btn_close);
        if (tvTitle != null) {
            tvTitle.setText(getString(R.string.sub_model) + " · " + (!TextUtils.isEmpty(current) ? current : getString(R.string.provider_status_unconfigured)));
        }
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> sheet.dismiss());
        }

        final TextView tvStatus = root.findViewById(R.id.tv_status);
        final RecyclerView rv = root.findViewById(R.id.rv_models);
        final EditText etSearch = root.findViewById(R.id.et_search);
        final View rowCustom = root.findViewById(R.id.row_custom_submodel);
        final com.google.android.material.radiobutton.MaterialRadioButton rbCustom =
                rowCustom != null ? rowCustom.findViewById(R.id.rb_custom_submodel) : null;

        final LinearLayoutManager lm = new LinearLayoutManager(requireContext());
        if (rv != null) {
            rv.setLayoutManager(lm);
        }

        final String customLabel = getString(R.string.ui_custom_sub_model);
        final SubModelPickerAdapter adapter = new SubModelPickerAdapter(fullModels, selectedModel);
        if (rv != null) {
            rv.setAdapter(adapter);
        }

        // Fetch OpenRouter model capabilities (Text / Image / Multimodal / Tools) from /models metadata.
        // This runs in the background and updates chips when ready.
        try {
            maybeLoadOpenRouterCapabilities(provider, sp, adapter);
        } catch (Throwable ignored) {
        }

        // Fixed custom row state (checked when selected model is not in suggestions).
        if (rbCustom != null) {
            boolean isCustomSelected = !TextUtils.isEmpty(selectedModel) && adapter.getSelectedIndexInFull() < 0;
            rbCustom.setText(customLabel);
            rbCustom.setChecked(isCustomSelected);
            rbCustom.setOnClickListener(v -> {
                sheet.dismiss();
                showCustomSubModelInputDialog(provider, selectedModel);
            });
        }

        // Bottom status is always based on FULL list (not filtered)
        Runnable updateStatus = () -> {
            if (tvStatus == null) return;
            int y = 0;
            try {
                int idx = adapter.getSelectedIndexInFull();
                y = idx >= 0 ? (idx + 1) : 0;
            } catch (Throwable ignored) {}
            tvStatus.setText(getString(R.string.ui_sub_model_status, cachedCount, y));
        };
        updateStatus.run();

        // Search: fuzzy match (contains, ignore case) over model id/name.
        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    adapter.setQuery(s != null ? s.toString() : "");
                }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        // Locate: tap the title to jump to the currently selected model (in FULL list).
        if (tvTitle != null && rv != null) {
            tvTitle.setOnClickListener(v -> {
                // If user is filtering and the selected item is hidden, clear filter first.
                if (etSearch != null && etSearch.getText() != null && etSearch.getText().length() > 0) {
                    etSearch.setText("");
                }
                rv.post(() -> {
                    int idx = adapter.getSelectedIndexInFull();
                    if (idx < 0) return;
                    try {
                        lm.scrollToPositionWithOffset(idx, rv.getHeight() / 3);
                    } catch (Throwable t) {
                        rv.scrollToPosition(idx);
                    }
                });
            });
        }

        // Item click -> persist immediately + dismiss
        adapter.setOnItemClickListener(item -> {
            if (TextUtils.isEmpty(item)) return;
            try {
                sp.setSubModel(provider, item.trim());
                refreshConversationSubModelRow();
                if (tvTitle != null) {
                    tvTitle.setText(getString(R.string.sub_model) + " · " + item.trim());
                }
                sendConfigBroadcast();
            } catch (Throwable ignored) {
            }
            sheet.dismiss();
        });

        // Optional: start near the selected model to reduce scrolling.
        if (rv != null) {
            rv.post(() -> {
                int idx = adapter.getSelectedIndexInFull();
                if (idx >= 0) {
                    try {
                        lm.scrollToPositionWithOffset(idx, rv.getHeight() / 3);
                    } catch (Throwable t) {
                        rv.scrollToPosition(idx);
                    }
                }
            });
        }

        sheet.show();
    }

    private void showCustomSubModelInputDialog(@NonNull LanguageModel provider, @Nullable String current) {
        View field = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_configure_model_field, null);
        TextInputEditText et = field.findViewById(R.id.field_edit);
        TextInputLayout til = field.findViewById(R.id.field_layout);
        if (til != null) {
            til.setHint(getString(R.string.sub_model));
        }
        if (et != null && current != null) {
            et.setText(current);
            et.setSelection(current.length());
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.sub_model) + " · " + provider.label)
                .setView(field)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String typed = et != null && et.getText() != null ? et.getText().toString().trim() : "";
                    if (TextUtils.isEmpty(typed)) return;
                    try {
                        SPManager.getInstance().setSubModel(provider, typed);
                        refreshConversationSubModelRow();
                        sendConfigBroadcast();
                    } catch (Throwable ignored) {
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // =============================
    // OpenRouter capability chips (Text / Image / Multimodal / Tools)
    // =============================
    private void maybeLoadOpenRouterCapabilities(@NonNull LanguageModel provider,
                                                 @NonNull SPManager sp,
                                                 @NonNull SubModelPickerAdapter adapter) {
        if (provider != LanguageModel.OpenRouter) return;

        String apiKey = null;
        String baseUrl = null;
        try {
            apiKey = sp.getApiKey(provider);
            baseUrl = sp.getBaseUrl(provider);
        } catch (Throwable ignored) {
        }
        if (TextUtils.isEmpty(apiKey) || TextUtils.isEmpty(baseUrl)) return;

        long now = System.currentTimeMillis();
        Map<String, SubModelPickerAdapter.ModelCaps> cached = sOpenRouterCapsCache;
        if (cached != null && (now - sOpenRouterCapsAt) < OPENROUTER_CAPS_TTL_MS) {
            adapter.setCapsMap(cached);
            return;
        }

        final String finalApiKey = apiKey.trim();
        final String finalBaseUrl = baseUrl.trim();
        final Handler main = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            try {
                String url = normalizeUrl(finalBaseUrl, "/models");
                HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                con.setRequestMethod("GET");
                con.setConnectTimeout(15000);
                con.setReadTimeout(20000);
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("Authorization", "Bearer " + finalApiKey);

                int code = con.getResponseCode();
                InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
                String body = readAll(is);
                if (code < 200 || code >= 300) {
                    // Ignore failures silently; chips are optional.
                    return;
                }

                Map<String, SubModelPickerAdapter.ModelCaps> map = parseOpenRouterCaps(body);
                if (map == null || map.isEmpty()) return;

                sOpenRouterCapsCache = map;
                sOpenRouterCapsAt = System.currentTimeMillis();

                main.post(() -> {
                    try {
                        adapter.setCapsMap(map);
                    } catch (Throwable ignored) {
                    }
                });
            } catch (Throwable ignored) {
            }
        }, "KGPT-OR-ModelsMeta").start();
    }

    private static String normalizeUrl(@NonNull String baseUrl, @NonNull String suffix) {
        String url = baseUrl.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!suffix.startsWith("/")) suffix = "/" + suffix;
        return url + suffix;
    }

    private static String readAll(@Nullable InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private static Map<String, SubModelPickerAdapter.ModelCaps> parseOpenRouterCaps(@NonNull String body) {
        HashMap<String, SubModelPickerAdapter.ModelCaps> out = new HashMap<>();
        try {
            JSONObject root = new JSONObject(body);
            JSONArray data = root.optJSONArray("data");
            if (data == null) return out;

            for (int i = 0; i < data.length(); i++) {
                JSONObject m = data.optJSONObject(i);
                if (m == null) continue;
                String id = m.optString("id", "");
                if (TextUtils.isEmpty(id)) id = m.optString("name", "");
                if (TextUtils.isEmpty(id)) continue;

                JSONObject arch = m.optJSONObject("architecture");
                JSONArray in = arch != null ? arch.optJSONArray("input_modalities") : null;
                JSONArray outMods = arch != null ? arch.optJSONArray("output_modalities") : null;
                JSONArray supported = m.optJSONArray("supported_parameters");

                SubModelPickerAdapter.ModelCaps caps = new SubModelPickerAdapter.ModelCaps();
                // "Text" is always implied for chat-style lists.
                caps.hasImage = contains(outMods, "image");
                // Mark multimodal when the model accepts non-text inputs (e.g., image).
                caps.isMultimodal = contains(in, "image") || contains(in, "audio") || contains(in, "video");
                caps.hasTools = contains(supported, "tools") || contains(supported, "tool_choice");

                out.put(id, caps);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static boolean contains(@Nullable JSONArray arr, @NonNull String v) {
        if (arr == null) return false;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, null);
            if (s != null && v.equalsIgnoreCase(s.trim())) return true;
        }
        return false;
    }

    /**
     * Keep the Xposed module in-sync (same broadcast used across settings screens).
     */
    private void sendConfigBroadcast() {
        if (!SPManager.isReady()) return;
        try {
            SPManager sp = SPManager.getInstance();
            Intent i = new Intent(UiInteractor.ACTION_DIALOG_RESULT);
            i.putExtra(UiInteractor.EXTRA_CONFIG_SELECTED_MODEL, sp.getLanguageModel().name());
            i.putExtra(UiInteractor.EXTRA_CONFIG_LANGUAGE_MODEL, sp.getConfigBundle());
            requireContext().sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }


    /**
     * Recycler adapter for Sub-model picker with lightweight in-memory filtering.
     */
    private static final class SubModelPickerAdapter extends RecyclerView.Adapter<SubModelPickerAdapter.VH> {

        /** Capability flags derived from OpenRouter /models metadata. */
        static final class ModelCaps {
            boolean hasImage;
            boolean isMultimodal;
            boolean hasTools;
        }

        interface OnItemClickListener {
            void onItemClick(String item);
        }

        private final List<String> full;      // full list (unfiltered)
        private final ArrayList<String> shown = new ArrayList<>();
        private String selected;
        private String query = "";
        private OnItemClickListener listener;

        private Map<String, ModelCaps> capsMap = null;

        SubModelPickerAdapter(@NonNull List<String> fullModels, @Nullable String selected) {
            this.full = fullModels != null ? fullModels : new ArrayList<>();
            this.selected = selected;
            rebuildShown();
        }

        void setOnItemClickListener(OnItemClickListener l) {
            this.listener = l;
        }

        void setCapsMap(@Nullable Map<String, ModelCaps> map) {
            this.capsMap = map;
            notifyDataSetChanged();
        }

        int getSelectedIndexInFull() {
            if (selected == null) return -1;
            for (int i = 0; i < full.size(); i++) {
                if (selected.equals(full.get(i))) return i;
            }
            return -1;
        }

        void setQuery(@NonNull String q) {
            String nq = q != null ? q.trim() : "";
            if (nq.equals(query)) return;
            query = nq;
            rebuildShown();
            notifyDataSetChanged();
        }

        private void rebuildShown() {
            shown.clear();
            if (query.isEmpty()) {
                shown.addAll(full);
            } else {
                String ql = query.toLowerCase();
                for (String s : full) {
                    if (s == null) continue;
                    if (s.toLowerCase().contains(ql)) {
                        shown.add(s);
                    }
                }
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_submodel_option, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            String item = shown.get(position);
            holder.radio.setText(item);
            holder.radio.setChecked(item != null && item.equals(selected));

            // Capability chips (default: Text only)
            if (holder.chipText != null) holder.chipText.setVisibility(View.VISIBLE);
            ModelCaps caps = null;
            try {
                if (capsMap != null && item != null) caps = capsMap.get(item);
            } catch (Throwable ignored) {
            }
            if (holder.chipImage != null) holder.chipImage.setVisibility(caps != null && caps.hasImage ? View.VISIBLE : View.GONE);
            if (holder.chipMultimodal != null) holder.chipMultimodal.setVisibility(caps != null && caps.isMultimodal ? View.VISIBLE : View.GONE);
            if (holder.chipTools != null) holder.chipTools.setVisibility(caps != null && caps.hasTools ? View.VISIBLE : View.GONE);
            if (holder.chipGroup != null) holder.chipGroup.setVisibility(View.VISIBLE);

            holder.radio.setOnClickListener(v -> {
                selected = item;
                notifyDataSetChanged();
                if (listener != null) listener.onItemClick(item);
            });
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final com.google.android.material.radiobutton.MaterialRadioButton radio;

            final com.google.android.material.chip.ChipGroup chipGroup;
            final com.google.android.material.chip.Chip chipText;
            final com.google.android.material.chip.Chip chipImage;
            final com.google.android.material.chip.Chip chipMultimodal;
            final com.google.android.material.chip.Chip chipTools;

            VH(@NonNull View itemView) {
                super(itemView);
                radio = itemView.findViewById(R.id.rb_item);

                chipGroup = itemView.findViewById(R.id.chip_group);
                chipText = itemView.findViewById(R.id.chip_text);
                chipImage = itemView.findViewById(R.id.chip_image);
                chipMultimodal = itemView.findViewById(R.id.chip_multimodal);
                chipTools = itemView.findViewById(R.id.chip_tools);
            }
        }
    }

    private void applyAmoledIfNeeded() {
        boolean isDarkMode = tn.eluea.kgpt.ui.main.BottomSheetHelper.isDarkMode(requireContext());
        boolean isAmoled = tn.eluea.kgpt.ui.main.BottomSheetHelper.isAmoledMode(requireContext());

        if (isDarkMode && isAmoled) {
            View root = getView() != null ? getView().findViewById(R.id.root_layout) : null;
            if (root != null) {
                root.setBackgroundColor(
                        androidx.core.content.ContextCompat.getColor(requireContext(), R.color.background_amoled));
            }
        }
    }
}
