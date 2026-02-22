package tn.eluea.kgpt.ui.lab;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.view.MotionEvent;
import android.os.Build;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ArrayAdapter;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.slider.Slider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.ui.main.BottomSheetHelper;
import tn.eluea.kgpt.ui.main.FloatingBottomSheet;

/**
 * Non-linear streaming model settings (Phase 2 models).
 *
 * Improvements (Phase 1 UI polish):
 * - Grouped model list (section headers)
 * - Model description + parameter summary (live)
 * - Global "human feel" parameters: punctuation pause multiplier (τ) + micro jitter noise (σ)
 *
 * Crash fix:
 * Material Slider enforces: value == valueFrom + k * stepSize when stepSize > 0.
 * Older stored prefs may contain values that don't align to stepSize (e.g. 28 with stepSize=5),
 * which causes IllegalStateException when the view is laid out.
 * We snap all loaded values to valid ticks BEFORE calling Slider.setValue().
 */
public final class StreamingNonLinearModelSettingsBottomSheet {

    private StreamingNonLinearModelSettingsBottomSheet() {}

    public static void show(@NonNull Context context, @Nullable Runnable onDismiss) {
        FloatingBottomSheet sheet = BottomSheetHelper.showFloating(context, R.layout.bottomsheet_model_settings);
        View content = sheet.getContentView();
        if (content == null) {
            sheet.show();
            return;
        }

        final SPManager sp;
        try {
            sp = SPManager.getInstance();
        } catch (Throwable t) {
            sheet.show();
            return;
        }

        // Bind all controls to the provided content view.
        final Runnable cleanup = bindForScreen(context, content, sp, sheet::dismiss);

        sheet.setOnDismissListener(d -> {
            try {
                cleanup.run();
            } catch (Throwable ignored) {
            }
            if (onDismiss != null) onDismiss.run();
        });

        sheet.show();
    }

    /**
     * Bind the non-linear model settings UI to an arbitrary container (BottomSheet or Activity).
     * Returns a cleanup runnable that should be invoked on dismiss/destroy.
     */
    @NonNull
    public static Runnable bindForScreen(@NonNull Context context,
                                         @NonNull View content,
                                         @NonNull SPManager sp,
                                         @Nullable Runnable onClose) {

        View itemModelTrigger = content.findViewById(R.id.item_nl_model_trigger);
        TextView tvModelSelected = content.findViewById(R.id.tv_nl_model_selected);

        // Global (τ / σ)
        Slider sPauseMult = content.findViewById(R.id.slider_nl_pause_mult);
        TextView tvPauseMult = content.findViewById(R.id.tv_nl_pause_mult_value);
        Slider sSigma = content.findViewById(R.id.slider_nl_sigma);
        TextView tvSigma = content.findViewById(R.id.tv_nl_sigma_value);

        // Prefetch / render buffer (for real NON-LINEAR output)
        View itemPrefetchTrigger = content.findViewById(R.id.item_nl_prefetch_trigger);
        TextView tvPrefetchSelected = content.findViewById(R.id.tv_nl_prefetch_selected);
        View panelPrefetchCustom = content.findViewById(R.id.panel_nl_prefetch_custom);
        Slider sPfStart = content.findViewById(R.id.slider_pf_start);
        TextView tvPfStart = content.findViewById(R.id.tv_pf_start_value);
        Slider sPfLow = content.findViewById(R.id.slider_pf_low);
        TextView tvPfLow = content.findViewById(R.id.tv_pf_low_value);
        Slider sPfTarget = content.findViewById(R.id.slider_pf_target);
        TextView tvPfTarget = content.findViewById(R.id.tv_pf_target_value);
        View btnResetPrefetchCustom = content.findViewById(R.id.btn_reset_pf_custom);

        // Model info
        TextView tvModelDesc = null; // UI simplified: removed secondary description line
        TextView tvModelSummary = content.findViewById(R.id.tv_nl_model_summary);

        // Rhythm preview
        NlRhythmPreviewView preview = content.findViewById(R.id.view_nl_rhythm_preview);

        // Typing preview
        TextView tvPreviewSample = content.findViewById(R.id.tv_nl_preview_sample);
        TextView tvPreviewOutput = content.findViewById(R.id.tv_nl_preview_output);
        View btnPreviewPlay = content.findViewById(R.id.btn_nl_preview_play);
        View btnPreviewReplay = content.findViewById(R.id.btn_nl_preview_replay);

        View panelLc = content.findViewById(R.id.panel_lc);
        View panelExp = content.findViewById(R.id.panel_exp);
        View panelSine = content.findViewById(R.id.panel_sine);
        View panelDamp = content.findViewById(R.id.panel_damp);
        View panelSquare = content.findViewById(R.id.panel_square);
        View panelMarkov = content.findViewById(R.id.panel_markov);


        View btnResetLc = content.findViewById(R.id.btn_reset_lc);
        View btnResetExp = content.findViewById(R.id.btn_reset_exp);
        View btnResetSine = content.findViewById(R.id.btn_reset_sine);
        View btnResetDamp = content.findViewById(R.id.btn_reset_damp);
        View btnResetSquare = content.findViewById(R.id.btn_reset_square);
        View btnResetMarkov = content.findViewById(R.id.btn_reset_markov);

        // Constant Speed
        Slider sLcT = content.findViewById(R.id.slider_lc_t);
        TextView tvLcT = content.findViewById(R.id.tv_lc_t_value);

        // Harmonic breathing
        Slider sSineTBase = content.findViewById(R.id.slider_sine_tbase);
        TextView tvSineTBase = content.findViewById(R.id.tv_sine_tbase_value);
        Slider sSineA = content.findViewById(R.id.slider_sine_a);
        TextView tvSineA = content.findViewById(R.id.tv_sine_a_value);
        Slider sSinePeriod = content.findViewById(R.id.slider_sine_period);
        TextView tvSinePeriod = content.findViewById(R.id.tv_sine_period_value);

        // Markov
        Slider sMkMu = content.findViewById(R.id.slider_markov_mu);
        TextView tvMkMu = content.findViewById(R.id.tv_markov_mu_value);
        Slider sMkRho = content.findViewById(R.id.slider_markov_rho);
        TextView tvMkRho = content.findViewById(R.id.tv_markov_rho_value);
        Slider sMkSigma = content.findViewById(R.id.slider_markov_sigma);
        TextView tvMkSigma = content.findViewById(R.id.tv_markov_sigma_value);
        Slider sMkTmin = content.findViewById(R.id.slider_markov_tmin);
        TextView tvMkTmin = content.findViewById(R.id.tv_markov_tmin_value);
        Slider sMkTmax = content.findViewById(R.id.slider_markov_tmax);
        TextView tvMkTmax = content.findViewById(R.id.tv_markov_tmax_value);
        Slider sMkPthink = content.findViewById(R.id.slider_markov_pthink);
        TextView tvMkPthink = content.findViewById(R.id.tv_markov_pthink_value);

        // Exponential
        Slider sExpTmax = content.findViewById(R.id.slider_exp_tmax);
        TextView tvExpTmax = content.findViewById(R.id.tv_exp_tmax_value);
        Slider sExpTmin = content.findViewById(R.id.slider_exp_tmin);
        TextView tvExpTmin = content.findViewById(R.id.tv_exp_tmin_value);
        Slider sExpLambda = content.findViewById(R.id.slider_exp_lambda);
        TextView tvExpLambda = content.findViewById(R.id.tv_exp_lambda_value);

        // Damped
        Slider sDampA = content.findViewById(R.id.slider_damp_a);
        TextView tvDampA = content.findViewById(R.id.tv_damp_a_value);
        Slider sDampOmega = content.findViewById(R.id.slider_damp_omega);
        TextView tvDampOmega = content.findViewById(R.id.tv_damp_omega_value);
        Slider sDampZeta = content.findViewById(R.id.slider_damp_zeta);
        TextView tvDampZeta = content.findViewById(R.id.tv_damp_zeta_value);

        // Square
        Slider sSquareA = content.findViewById(R.id.slider_square_a);
        TextView tvSquareA = content.findViewById(R.id.tv_square_a_value);
        Slider sSquareOmega = content.findViewById(R.id.slider_square_omega);
        TextView tvSquareOmega = content.findViewById(R.id.tv_square_omega_value);

        final int[] selectedModelHolder = new int[] { sp.getStreamingNonLinearModel() };

        // Preview controller (simulated streaming inside the sheet)
        final TypingPreviewController typingPreview = new TypingPreviewController(
                context,
                sp,
                () -> selectedModelHolder[0],
                tvPreviewSample,
                tvPreviewOutput,
                btnPreviewPlay,
                btnPreviewReplay,
                sPauseMult,
                sSigma,
                sLcT,
                sExpTmax,
                sExpTmin,
                sExpLambda,
                sSineTBase,
                sSineA,
                sSinePeriod,
                sDampA,
                sDampOmega,
                sDampZeta,
                sSquareA,
                sSquareOmega,
                sMkMu,
                sMkRho,
                sMkSigma,
                sMkTmin,
                sMkTmax,
                sMkPthink
        );

        final Runnable refreshAll = () -> {
            updateModelInfo(
                    context,
                    sp,
                    selectedModelHolder[0],
                    tvModelDesc,
                    tvModelSummary,
                    sPauseMult,
                    sSigma,
                    sLcT,
                    sExpTmax,
                    sExpTmin,
                    sExpLambda,
                    sSineTBase,
                    sSineA,
                    sSinePeriod,
                    sDampA,
                    sDampOmega,
                    sDampZeta,
                    sSquareA,
                    sSquareOmega,
                    sMkMu,
                    sMkRho,
                    sMkSigma,
                    sMkTmin,
                    sMkTmax,
                    sMkPthink
            );
            updatePreview(
                    sp,
                    selectedModelHolder[0],
                    preview,
                    sPauseMult,
                    sSigma,
                    sLcT,
                    sExpTmax,
                    sExpTmin,
                    sExpLambda,
                    sSineTBase,
                    sSineA,
                    sSinePeriod,
                    sDampA,
                    sDampOmega,
                    sDampZeta,
                    sSquareA,
                    sSquareOmega,
                    sMkMu,
                    sMkRho,
                    sMkSigma,
                    sMkTmin,
                    sMkTmax,
                    sMkPthink
            );

            // While preview is running, let it pick up the newest params.
            typingPreview.onParamsChanged();
        };

        // ---- Global controls: τ / σ ----
        if (sPauseMult != null && tvPauseMult != null) {
            float raw = (float) sp.getStreamingNonLinearPauseMultiplier();
            float v = snapToSlider(sPauseMult, raw);
            if (!nearlyEqual(v, raw)) sp.setStreamingNonLinearPauseMultiplier(v);
            sPauseMult.setValue(v);
            tvPauseMult.setText(formatMultiplier(v));
            sPauseMult.addOnChangeListener((slider, value, fromUser) -> {
                tvPauseMult.setText(formatMultiplier(value));
                refreshAll.run();
            });
            sPauseMult.addOnSliderTouchListener(new SimpleTouchListener(() -> {
                float snapped = snapToSlider(sPauseMult, sPauseMult.getValue());
                if (!nearlyEqual(snapped, sPauseMult.getValue())) sPauseMult.setValue(snapped);
                sp.setStreamingNonLinearPauseMultiplier(snapped);
                refreshAll.run();
            }));
        }

        if (sSigma != null && tvSigma != null) {
            float raw = sp.getStreamingNonLinearSigmaMs();
            float v = snapToSlider(sSigma, raw);
            if (!nearlyEqual(v, raw)) sp.setStreamingNonLinearSigmaMs(Math.round(v));
            sSigma.setValue(v);
            tvSigma.setText(formatMs(Math.round(v)));
            sSigma.addOnChangeListener((slider, value, fromUser) -> {
                tvSigma.setText(formatMs(Math.round(value)));
                refreshAll.run();
            });
            sSigma.addOnSliderTouchListener(new SimpleTouchListener(() -> {
                float snapped = snapToSlider(sSigma, sSigma.getValue());
                if (!nearlyEqual(snapped, sSigma.getValue())) sSigma.setValue(snapped);
                sp.setStreamingNonLinearSigmaMs(Math.round(snapped));
                refreshAll.run();
            }));
        }

        // ---- Prefetch / render buffer ----
if (itemPrefetchTrigger != null) {
    final List<PrefetchRow> pfRows = buildPrefetchRows(context);

    // Initialize custom sliders (but only show panel when CUSTOM is selected)
    final Runnable applyPfPanelVisibility = () -> {
        int mode = sp.getStreamingPrefetchMode();
        if (panelPrefetchCustom != null) {
            panelPrefetchCustom.setVisibility(mode == SPManager.STREAM_PREFETCH_CUSTOM ? View.VISIBLE : View.GONE);
        }
    };

    final Runnable refreshPfValues = () -> {
        if (sPfStart != null && tvPfStart != null) {
            float raw = sp.getStreamingPrefetchStartChars();
            float v = snapToSlider(sPfStart, raw);
            if (!nearlyEqual(v, raw)) sp.setStreamingPrefetchStartChars(Math.round(v));
            sPfStart.setValue(v);
            tvPfStart.setText(formatChars(Math.round(v)));
        }
        if (sPfLow != null && tvPfLow != null) {
            float raw = sp.getStreamingPrefetchLowWatermark();
            float v = snapToSlider(sPfLow, raw);
            if (!nearlyEqual(v, raw)) sp.setStreamingPrefetchLowWatermark(Math.round(v));
            sPfLow.setValue(v);
            tvPfLow.setText(formatChars(Math.round(v)));
        }
        if (sPfTarget != null && tvPfTarget != null) {
            float raw = sp.getStreamingPrefetchTopUpTarget();
            float v = snapToSlider(sPfTarget, raw);
            if (!nearlyEqual(v, raw)) sp.setStreamingPrefetchTopUpTarget(Math.round(v));
            sPfTarget.setValue(v);
            tvPfTarget.setText(formatChars(Math.round(v)));
        }
        enforcePrefetchConstraints(sp, sPfStart, sPfLow, sPfTarget, tvPfStart, tvPfLow, tvPfTarget);
    };

    int curMode = sp.getStreamingPrefetchMode();
    int curPos = 0;
    for (int i = 0; i < pfRows.size(); i++) {
        if (pfRows.get(i).mode == curMode) { curPos = i; break; }
    }
    if (tvPrefetchSelected != null && curPos >= 0 && curPos < pfRows.size()) {
        tvPrefetchSelected.setText(pfRows.get(curPos).label);
    }

    applyPfPanelVisibility.run();
    refreshPfValues.run();

    final Runnable showPrefetchDialog = () -> {
        int curMode2 = sp.getStreamingPrefetchMode();
        int checked2 = 0;
        for (int i = 0; i < pfRows.size(); i++) {
            if (pfRows.get(i).mode == curMode2) { checked2 = i; break; }
        }

        PrefetchDialogAdapter dialogAdapter = new PrefetchDialogAdapter(context, pfRows, checked2);

        View pfTitleView = LayoutInflater.from(context).inflate(R.layout.dialog_title_with_subtitle, null);
        TextView pfTitle = pfTitleView.findViewById(R.id.tv_dialog_title);
        TextView pfSubtitle = pfTitleView.findViewById(R.id.tv_dialog_subtitle);
        if (pfTitle != null) pfTitle.setText(R.string.ui_streaming_nl_prefetch_title);
        if (pfSubtitle != null) pfSubtitle.setText(R.string.ui_streaming_nl_prefetch_desc);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setCustomTitle(pfTitleView)
                .setSingleChoiceItems(dialogAdapter, checked2, (d, which) -> {
                    if (which < 0 || which >= pfRows.size()) return;
                    // Do NOT apply immediately. Only update temporary selection.
                    if (!dialogAdapter.isEnabled(which)) return;
                    dialogAdapter.setTempSelectedIndex(which);
                    try {
                        ((AlertDialog) d).getListView().setItemChecked(which, true);
                    } catch (Throwable ignored) {
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    int pos = dialogAdapter.getTempSelectedIndex();
                    if (pos < 0 || pos >= pfRows.size()) return;
                    PrefetchRow row = pfRows.get(pos);
                    sp.setStreamingPrefetchMode(row.mode);
                    if (tvPrefetchSelected != null) tvPrefetchSelected.setText(row.label);
                    applyPfPanelVisibility.run();
                    refreshPfValues.run();
                })
                .create();
        dialog.show();
    };

    itemPrefetchTrigger.setOnClickListener(v -> showPrefetchDialog.run());

    if (btnResetPrefetchCustom != null) {
        btnResetPrefetchCustom.setOnClickListener(v -> {
            sp.setStreamingPrefetchStartChars(120);
            sp.setStreamingPrefetchLowWatermark(80);
            sp.setStreamingPrefetchTopUpTarget(260);
            refreshPfValues.run();
        });
    }

    if (sPfStart != null && tvPfStart != null) {
        sPfStart.addOnChangeListener((slider, value, fromUser) -> tvPfStart.setText(formatChars(Math.round(value))));
        sPfStart.addOnSliderTouchListener(new SimpleTouchListener(() -> {
            float snapped = snapToSlider(sPfStart, sPfStart.getValue());
            if (!nearlyEqual(snapped, sPfStart.getValue())) sPfStart.setValue(snapped);
            sp.setStreamingPrefetchStartChars(Math.round(snapped));
            enforcePrefetchConstraints(sp, sPfStart, sPfLow, sPfTarget, tvPfStart, tvPfLow, tvPfTarget);
        }));
    }

    if (sPfLow != null && tvPfLow != null) {
        sPfLow.addOnChangeListener((slider, value, fromUser) -> tvPfLow.setText(formatChars(Math.round(value))));
        sPfLow.addOnSliderTouchListener(new SimpleTouchListener(() -> {
            float snapped = snapToSlider(sPfLow, sPfLow.getValue());
            if (!nearlyEqual(snapped, sPfLow.getValue())) sPfLow.setValue(snapped);
            sp.setStreamingPrefetchLowWatermark(Math.round(snapped));
            enforcePrefetchConstraints(sp, sPfStart, sPfLow, sPfTarget, tvPfStart, tvPfLow, tvPfTarget);
        }));
    }

    if (sPfTarget != null && tvPfTarget != null) {
        sPfTarget.addOnChangeListener((slider, value, fromUser) -> tvPfTarget.setText(formatChars(Math.round(value))));
        sPfTarget.addOnSliderTouchListener(new SimpleTouchListener(() -> {
            float snapped = snapToSlider(sPfTarget, sPfTarget.getValue());
            if (!nearlyEqual(snapped, sPfTarget.getValue())) sPfTarget.setValue(snapped);
            sp.setStreamingPrefetchTopUpTarget(Math.round(snapped));
            enforcePrefetchConstraints(sp, sPfStart, sPfLow, sPfTarget, tvPfStart, tvPfLow, tvPfTarget);
        }));
    }
}

        // ---- Model selector ----
final List<ModelRow> rows = buildModelRows(context);

if (itemModelTrigger != null) {
    int currentModel = selectedModelHolder[0];
    int checked = findPositionForModel(rows, currentModel);
    if (checked < 0) checked = findFirstSelectable(rows);
    if (checked < 0) checked = 0;

    if (tvModelSelected != null && checked >= 0 && checked < rows.size()) {
        tvModelSelected.setText(rows.get(checked).label);
    }

    final Runnable showModelDialog = () -> {
        int checkedPos = findPositionForModel(rows, selectedModelHolder[0]);
        if (checkedPos < 0) checkedPos = findFirstSelectable(rows);
        if (checkedPos < 0) checkedPos = 0;

        ModelDialogAdapter dialogAdapter = new ModelDialogAdapter(context, rows, checkedPos);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.ui_streaming_nl_model)
                .setSingleChoiceItems(dialogAdapter, checkedPos, (d, which) -> {
                    if (which < 0 || which >= rows.size()) return;
                    // Headers are disabled by adapter; this is just a safety net.
                    if (!dialogAdapter.isEnabled(which)) return;
                    dialogAdapter.setTempSelectedIndex(which);
                    try {
                        ((AlertDialog) d).getListView().setItemChecked(which, true);
                    } catch (Throwable ignored) {
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    int pos = dialogAdapter.getTempSelectedIndex();
                    if (pos < 0 || pos >= rows.size()) return;
                    ModelRow row = rows.get(pos);
                    if (row.isHeader) return;

                    int selected = row.modelValue;
                    selectedModelHolder[0] = selected;
                    sp.setStreamingNonLinearModel(selected);

                    if (tvModelSelected != null) tvModelSelected.setText(row.label);
                    updatePanels(selected, panelLc, panelExp, panelSine, panelDamp, panelSquare, panelMarkov);
                    refreshAll.run();
                })
                .create();
        dialog.show();
    };

    itemModelTrigger.setOnClickListener(v -> showModelDialog.run());
    updatePanels(currentModel, panelLc, panelExp, panelSine, panelDamp, panelSquare, panelMarkov);
} else {
    // If the trigger view is missing, still keep panel visibility coherent.
    updatePanels(selectedModelHolder[0], panelLc, panelExp, panelSine, panelDamp, panelSquare, panelMarkov);
}

        // --- Bind sliders (snap to stepSize-safe values before setValue) ---


        if (sLcT != null && tvLcT != null) {
            float raw = sp.getNlLinearConstantTBaseMs();
            float v = snapToSlider(sLcT, raw);
            if (!nearlyEqual(v, raw)) sp.setNlLinearConstantTBaseMs(Math.round(v));
            sLcT.setValue(v);
            tvLcT.setText(formatMs(Math.round(v)));
            sLcT.addOnChangeListener((slider, value, fromUser) -> {
                tvLcT.setText(formatMs(Math.round(value)));
                refreshAll.run();
            });
            sLcT.addOnSliderTouchListener(new SimpleTouchListener(() -> {
                float snapped = snapToSlider(sLcT, sLcT.getValue());
                if (!nearlyEqual(snapped, sLcT.getValue())) sLcT.setValue(snapped);
                sp.setNlLinearConstantTBaseMs(Math.round(snapped));
                refreshAll.run();
            }));
        }

        if (sSineTBase != null && tvSineTBase != null) {
            float raw = sp.getNlSineTBaseMs();
            float v = snapToSlider(sSineTBase, raw);
            if (!nearlyEqual(v, raw)) sp.setNlSineTBaseMs(Math.round(v));
            sSineTBase.setValue(v);
            tvSineTBase.setText(formatMs(Math.round(v)));
            sSineTBase.addOnChangeListener((slider, value, fromUser) -> {
                tvSineTBase.setText(formatMs(Math.round(value)));
                refreshAll.run();
            });
            sSineTBase.addOnSliderTouchListener(new SimpleTouchListener(() -> {
                float snapped = snapToSlider(sSineTBase, sSineTBase.getValue());
                if (!nearlyEqual(snapped, sSineTBase.getValue())) sSineTBase.setValue(snapped);
                sp.setNlSineTBaseMs(Math.round(snapped));
                refreshAll.run();
            }));
        }

        if (sSineA != null && tvSineA != null) {
            float raw = sp.getNlSineAMs();
            float v = snapToSlider(sSineA, raw);
            if (!nearlyEqual(v, raw)) sp.setNlSineAMs(Math.round(v));
            sSineA.setValue(v);
            tvSineA.setText(formatMs(Math.round(v)));
            sSineA.addOnChangeListener((slider, value, fromUser) -> {
                tvSineA.setText(formatMs(Math.round(value)));
                refreshAll.run();
            });
            sSineA.addOnSliderTouchListener(new SimpleTouchListener(() -> {
                float snapped = snapToSlider(sSineA, sSineA.getValue());
                if (!nearlyEqual(snapped, sSineA.getValue())) sSineA.setValue(snapped);
                sp.setNlSineAMs(Math.round(snapped));
                refreshAll.run();
            }));
        }

        if (sSinePeriod != null && tvSinePeriod != null) {
            float raw = sp.getNlSinePeriodN();
            float v = snapToSlider(sSinePeriod, raw);
            if (!nearlyEqual(v, raw)) sp.setNlSinePeriodN(Math.round(v));
            sSinePeriod.setValue(v);
            tvSinePeriod.setText(formatChars(Math.round(v)));
            sSinePeriod.addOnChangeListener((slider, value, fromUser) -> {
                tvSinePeriod.setText(formatChars(Math.round(value)));
                refreshAll.run();
            });
            sSinePeriod.addOnSliderTouchListener(new SimpleTouchListener(() -> {
                float snapped = snapToSlider(sSinePeriod, sSinePeriod.getValue());
                if (!nearlyEqual(snapped, sSinePeriod.getValue())) sSinePeriod.setValue(snapped);
                sp.setNlSinePeriodN(Math.round(snapped));
                refreshAll.run();
            }));
        }

        if (sMkMu != null && tvMkMu != null) {
            float raw = sp.getNlMarkovMuMs();
            float v = snapToSlider(sMkMu, raw);
            if (!nearlyEqual(v, raw)) sp.setNlMarkovMuMs(Math.round(v));
            sMkMu.setValue(v);
            tvMkMu.setText(formatMs(Math.round(v)));
            sMkMu.addOnChangeListener((slider, value, fromUser) -> {
                tvMkMu.setText(formatMs(Math.round(value)));
                refreshAll.run();
            });
            sMkMu.addOnSliderTouchListener(new SimpleTouchListener(() -> {
                float snapped = snapToSlider(sMkMu, sMkMu.getValue());
                if (!nearlyEqual(snapped, sMkMu.getValue())) sMkMu.setValue(snapped);
                sp.setNlMarkovMuMs(Math.round(snapped));
                refreshAll.run();
            }));
        }

        if (sMkRho != null && tvMkRho != null) {
            float raw = (float) sp.getNlMarkovRho();
            float v = snapToSlider(sMkRho, raw);
            if (!nearlyEqual(v, raw)) sp.setNlMarkovRho(v);
            sMkRho.setValue(v);
            tvMkRho.setText(formatDouble(v, 2));
            sMkRho.addOnChangeListener((slider, value, fromUser) -> {
                tvMkRho.setText(formatDouble(value, 2));
                refreshAll.run();
            });
            sMkRho.addOnSliderTouchListener(new SimpleTouchListener(() -> {
                float snapped = snapToSlider(sMkRho, sMkRho.getValue());
                if (!nearlyEqual(snapped, sMkRho.getValue())) sMkRho.setValue(snapped);
                sp.setNlMarkovRho(sMkRho.getValue());
                refreshAll.run();
            }));
        }

        if (sMkSigma != null && tvMkSigma != null) {
            float raw = sp.getNlMarkovSigmaMs();
            float v = snapToSlider(sMkSigma, raw);
            if (!nearlyEqual(v, raw)) sp.setNlMarkovSigmaMs(Math.round(v));
            sMkSigma.setValue(v);
            tvMkSigma.setText(formatMs(Math.round(v)));
            sMkSigma.addOnChangeListener((slider, value, fromUser) -> {
                tvMkSigma.setText(formatMs(Math.round(value)));
                refreshAll.run();
            });
            sMkSigma.addOnSliderTouchListener(new SimpleTouchListener(() -> {
                float snapped = snapToSlider(sMkSigma, sMkSigma.getValue());
                if (!nearlyEqual(snapped, sMkSigma.getValue())) sMkSigma.setValue(snapped);
                sp.setNlMarkovSigmaMs(Math.round(snapped));
                refreshAll.run();
            }));
        }

        if (sMkTmin != null && tvMkTmin != null) {
            float raw = sp.getNlMarkovTMinMs();
            float v = snapToSlider(sMkTmin, raw);
            if (!nearlyEqual(v, raw)) sp.setNlMarkovTMinMs(Math.round(v));
            sMkTmin.setValue(v);
            tvMkTmin.setText(formatMs(Math.round(v)));
            sMkTmin.addOnChangeListener((slider, value, fromUser) -> {
                tvMkTmin.setText(formatMs(Math.round(value)));
                refreshAll.run();
            });
            sMkTmin.addOnSliderTouchListener(new SimpleTouchListener(() -> {
                float snapped = snapToSlider(sMkTmin, sMkTmin.getValue());
                if (!nearlyEqual(snapped, sMkTmin.getValue())) sMkTmin.setValue(snapped);
                sp.setNlMarkovTMinMs(Math.round(snapped));
                refreshAll.run();
            }));
        }

        if (sMkTmax != null && tvMkTmax != null) {
            float raw = sp.getNlMarkovTMaxMs();
            float v = snapToSlider(sMkTmax, raw);
            if (!nearlyEqual(v, raw)) sp.setNlMarkovTMaxMs(Math.round(v));
            sMkTmax.setValue(v);
            tvMkTmax.setText(formatMs(Math.round(v)));
            sMkTmax.addOnChangeListener((slider, value, fromUser) -> {
                tvMkTmax.setText(formatMs(Math.round(value)));
                refreshAll.run();
            });
            sMkTmax.addOnSliderTouchListener(new SimpleTouchListener(() -> {
                float snapped = snapToSlider(sMkTmax, sMkTmax.getValue());
                if (!nearlyEqual(snapped, sMkTmax.getValue())) sMkTmax.setValue(snapped);
                sp.setNlMarkovTMaxMs(Math.round(snapped));
                refreshAll.run();
            }));
        }

        if (sMkPthink != null && tvMkPthink != null) {
            float raw = (float) sp.getNlMarkovPThinkPercent();
            float v = snapToSlider(sMkPthink, raw);
            if (!nearlyEqual(v, raw)) sp.setNlMarkovPThinkPercent(v);
            sMkPthink.setValue(v);
            tvMkPthink.setText(formatPercent(v, 1));
            sMkPthink.addOnChangeListener((slider, value, fromUser) -> {
                tvMkPthink.setText(formatPercent(value, 1));
                refreshAll.run();
            });
            sMkPthink.addOnSliderTouchListener(new SimpleTouchListener(() -> {
                float snapped = snapToSlider(sMkPthink, sMkPthink.getValue());
                if (!nearlyEqual(snapped, sMkPthink.getValue())) sMkPthink.setValue(snapped);
                sp.setNlMarkovPThinkPercent(sMkPthink.getValue());
                refreshAll.run();
            }));
        }

        if (sExpTmax != null && tvExpTmax != null) {
            float raw = sp.getNlExpTMaxMs();
            float v = snapToSlider(sExpTmax, raw);
            if (!nearlyEqual(v, raw)) sp.setNlExpTMaxMs(Math.round(v));
            sExpTmax.setValue(v);
            tvExpTmax.setText(formatMs(Math.round(v)));
            sExpTmax.addOnChangeListener((slider, value, fromUser) -> {
                tvExpTmax.setText(formatMs(Math.round(value)));
                refreshAll.run();
            });
            sExpTmax.addOnSliderTouchListener(new SimpleTouchListener(() -> {
                float snapped = snapToSlider(sExpTmax, sExpTmax.getValue());
                if (!nearlyEqual(snapped, sExpTmax.getValue())) sExpTmax.setValue(snapped);
                sp.setNlExpTMaxMs(Math.round(snapped));
                refreshAll.run();
            }));
        }

        if (sExpTmin != null && tvExpTmin != null) {
            float raw = sp.getNlExpTMinMs();
            float v = snapToSlider(sExpTmin, raw);
            if (!nearlyEqual(v, raw)) sp.setNlExpTMinMs(Math.round(v));
            sExpTmin.setValue(v);
            tvExpTmin.setText(formatMs(Math.round(v)));
            sExpTmin.addOnChangeListener((slider, value, fromUser) -> {
                tvExpTmin.setText(formatMs(Math.round(value)));
                refreshAll.run();
            });
            sExpTmin.addOnSliderTouchListener(new SimpleTouchListener(() -> {
                float snapped = snapToSlider(sExpTmin, sExpTmin.getValue());
                if (!nearlyEqual(snapped, sExpTmin.getValue())) sExpTmin.setValue(snapped);
                sp.setNlExpTMinMs(Math.round(snapped));
                refreshAll.run();
            }));
        }

        if (sExpLambda != null && tvExpLambda != null) {
            float raw = (float) sp.getNlExpLambda();
            float v = snapToSlider(sExpLambda, raw);
            if (!nearlyEqual(v, raw)) sp.setNlExpLambda(v);
            sExpLambda.setValue(v);
            tvExpLambda.setText(formatDouble(v, 3));
            sExpLambda.addOnChangeListener((slider, value, fromUser) -> {
                tvExpLambda.setText(formatDouble(value, 3));
                refreshAll.run();
            });
            sExpLambda.addOnSliderTouchListener(new SimpleTouchListener(() -> {
                float snapped = snapToSlider(sExpLambda, sExpLambda.getValue());
                if (!nearlyEqual(snapped, sExpLambda.getValue())) sExpLambda.setValue(snapped);
                sp.setNlExpLambda(sExpLambda.getValue());
                refreshAll.run();
            }));
        }

        if (sDampA != null && tvDampA != null) {
            float raw = sp.getNlDampAMs();
            float v = snapToSlider(sDampA, raw);
            if (!nearlyEqual(v, raw)) sp.setNlDampAMs(Math.round(v));
            sDampA.setValue(v);
            tvDampA.setText(formatMs(Math.round(v)));
            sDampA.addOnChangeListener((slider, value, fromUser) -> {
                tvDampA.setText(formatMs(Math.round(value)));
                refreshAll.run();
            });
            sDampA.addOnSliderTouchListener(new SimpleTouchListener(() -> {
                float snapped = snapToSlider(sDampA, sDampA.getValue());
                if (!nearlyEqual(snapped, sDampA.getValue())) sDampA.setValue(snapped);
                sp.setNlDampAMs(Math.round(snapped));
                refreshAll.run();
            }));
        }

        if (sDampOmega != null && tvDampOmega != null) {
            float raw = (float) sp.getNlDampOmega();
            float v = snapToSlider(sDampOmega, raw);
            if (!nearlyEqual(v, raw)) sp.setNlDampOmega(v);
            sDampOmega.setValue(v);
            tvDampOmega.setText(formatDouble(v, 2));
            sDampOmega.addOnChangeListener((slider, value, fromUser) -> {
                tvDampOmega.setText(formatDouble(value, 2));
                refreshAll.run();
            });
            sDampOmega.addOnSliderTouchListener(new SimpleTouchListener(() -> {
                float snapped = snapToSlider(sDampOmega, sDampOmega.getValue());
                if (!nearlyEqual(snapped, sDampOmega.getValue())) sDampOmega.setValue(snapped);
                sp.setNlDampOmega(sDampOmega.getValue());
                refreshAll.run();
            }));
        }

        if (sDampZeta != null && tvDampZeta != null) {
            float raw = (float) sp.getNlDampZeta();
            float v = snapToSlider(sDampZeta, raw);
            if (!nearlyEqual(v, raw)) sp.setNlDampZeta(v);
            sDampZeta.setValue(v);
            tvDampZeta.setText(formatDouble(v, 2));
            sDampZeta.addOnChangeListener((slider, value, fromUser) -> {
                tvDampZeta.setText(formatDouble(value, 2));
                refreshAll.run();
            });
            sDampZeta.addOnSliderTouchListener(new SimpleTouchListener(() -> {
                float snapped = snapToSlider(sDampZeta, sDampZeta.getValue());
                if (!nearlyEqual(snapped, sDampZeta.getValue())) sDampZeta.setValue(snapped);
                sp.setNlDampZeta(sDampZeta.getValue());
                refreshAll.run();
            }));
        }

        if (sSquareA != null && tvSquareA != null) {
            float raw = sp.getNlSquareAMs();
            float v = snapToSlider(sSquareA, raw);
            if (!nearlyEqual(v, raw)) sp.setNlSquareAMs(Math.round(v));
            sSquareA.setValue(v);
            tvSquareA.setText(formatMs(Math.round(v)));
            sSquareA.addOnChangeListener((slider, value, fromUser) -> {
                tvSquareA.setText(formatMs(Math.round(value)));
                refreshAll.run();
            });
            sSquareA.addOnSliderTouchListener(new SimpleTouchListener(() -> {
                float snapped = snapToSlider(sSquareA, sSquareA.getValue());
                if (!nearlyEqual(snapped, sSquareA.getValue())) sSquareA.setValue(snapped);
                sp.setNlSquareAMs(Math.round(snapped));
                refreshAll.run();
            }));
        }

        if (sSquareOmega != null && tvSquareOmega != null) {
            float raw = (float) sp.getNlSquareOmega();
            float v = snapToSlider(sSquareOmega, raw);
            if (!nearlyEqual(v, raw)) sp.setNlSquareOmega(v);
            sSquareOmega.setValue(v);
            tvSquareOmega.setText(formatDouble(v, 2));
            sSquareOmega.addOnChangeListener((slider, value, fromUser) -> {
                tvSquareOmega.setText(formatDouble(value, 2));
                refreshAll.run();
            });
            sSquareOmega.addOnSliderTouchListener(new SimpleTouchListener(() -> {
                float snapped = snapToSlider(sSquareOmega, sSquareOmega.getValue());
                if (!nearlyEqual(snapped, sSquareOmega.getValue())) sSquareOmega.setValue(snapped);
                sp.setNlSquareOmega(sSquareOmega.getValue());
                refreshAll.run();
            }));
        }

// ---- Reset-to-default buttons ----
if (btnResetLc != null) {
    btnResetLc.setOnClickListener(v -> {
        int defT = 50;
        sp.setNlLinearConstantTBaseMs(defT);
        if (sLcT != null) sLcT.setValue(snapToSlider(sLcT, defT));
        refreshAll.run();
    });
}

if (btnResetExp != null) {
    btnResetExp.setOnClickListener(v -> {
        int defTmax = 220;
        int defTmin = 28;
        float defLambda = 0.045f;
        sp.setNlExpTMaxMs(defTmax);
        sp.setNlExpTMinMs(defTmin);
        sp.setNlExpLambda(defLambda);
        if (sExpTmax != null) sExpTmax.setValue(snapToSlider(sExpTmax, defTmax));
        if (sExpTmin != null) sExpTmin.setValue(snapToSlider(sExpTmin, defTmin));
        if (sExpLambda != null) sExpLambda.setValue(snapToSlider(sExpLambda, defLambda));
        refreshAll.run();
    });
}

if (btnResetSine != null) {
    btnResetSine.setOnClickListener(v -> {
        int defTBase = 60;
        int defA = 30;
        int defN = 15;
        sp.setNlSineTBaseMs(defTBase);
        sp.setNlSineAMs(defA);
        sp.setNlSinePeriodN(defN);
        if (sSineTBase != null) sSineTBase.setValue(snapToSlider(sSineTBase, defTBase));
        if (sSineA != null) sSineA.setValue(snapToSlider(sSineA, defA));
        if (sSinePeriod != null) sSinePeriod.setValue(snapToSlider(sSinePeriod, defN));
        refreshAll.run();
    });
}

if (btnResetDamp != null) {
    btnResetDamp.setOnClickListener(v -> {
        int defA = 85;
        float defOmega = 1.1f;
        float defZeta = 0.05f;
        sp.setNlDampAMs(defA);
        sp.setNlDampOmega(defOmega);
        sp.setNlDampZeta(defZeta);
        if (sDampA != null) sDampA.setValue(snapToSlider(sDampA, defA));
        if (sDampOmega != null) sDampOmega.setValue(snapToSlider(sDampOmega, defOmega));
        if (sDampZeta != null) sDampZeta.setValue(snapToSlider(sDampZeta, defZeta));
        refreshAll.run();
    });
}

if (btnResetSquare != null) {
    btnResetSquare.setOnClickListener(v -> {
        int defA = 70;
        float defOmega = 0.7f;
        sp.setNlSquareAMs(defA);
        sp.setNlSquareOmega(defOmega);
        if (sSquareA != null) sSquareA.setValue(snapToSlider(sSquareA, defA));
        if (sSquareOmega != null) sSquareOmega.setValue(snapToSlider(sSquareOmega, defOmega));
        refreshAll.run();
    });
}

if (btnResetMarkov != null) {
    btnResetMarkov.setOnClickListener(v -> {
        int defMu = 80;
        float defRho = 0.90f;
        int defSigma = 25;
        int defTmin = 30;
        int defTmax = 450;
        float defPthink = 2.0f;
        sp.setNlMarkovMuMs(defMu);
        sp.setNlMarkovRho(defRho);
        sp.setNlMarkovSigmaMs(defSigma);
        sp.setNlMarkovTMinMs(defTmin);
        sp.setNlMarkovTMaxMs(defTmax);
        sp.setNlMarkovPThinkPercent(defPthink);
        if (sMkMu != null) sMkMu.setValue(snapToSlider(sMkMu, defMu));
        if (sMkRho != null) sMkRho.setValue(snapToSlider(sMkRho, defRho));
        if (sMkSigma != null) sMkSigma.setValue(snapToSlider(sMkSigma, defSigma));
        if (sMkTmin != null) sMkTmin.setValue(snapToSlider(sMkTmin, defTmin));
        if (sMkTmax != null) sMkTmax.setValue(snapToSlider(sMkTmax, defTmax));
        if (sMkPthink != null) sMkPthink.setValue(snapToSlider(sMkPthink, defPthink));
        refreshAll.run();
    });
}

        // Initial model info render
        refreshAll.run();
        return typingPreview::stop;
    }

    private static void updatePanels(int model,
                                     @Nullable View panelLc,
                                     @Nullable View panelExp,
                                     @Nullable View panelSine,
                                     @Nullable View panelDamp,
                                     @Nullable View panelSquare,
                                     @Nullable View panelMarkov) {
        if (panelLc != null) panelLc.setVisibility(model == SPManager.STREAM_NL_MODEL_LINEAR_CONSTANT ? View.VISIBLE : View.GONE);
        if (panelExp != null) panelExp.setVisibility(model == SPManager.STREAM_NL_MODEL_EXPONENTIAL_DECAY ? View.VISIBLE : View.GONE);
        if (panelSine != null) panelSine.setVisibility(model == SPManager.STREAM_NL_MODEL_SINE_WAVE_JITTER ? View.VISIBLE : View.GONE);
        if (panelDamp != null) panelDamp.setVisibility(model == SPManager.STREAM_NL_MODEL_DAMPED_OSCILLATOR ? View.VISIBLE : View.GONE);
        if (panelSquare != null) panelSquare.setVisibility(model == SPManager.STREAM_NL_MODEL_SQUARE_WAVE_BURST ? View.VISIBLE : View.GONE);
        if (panelMarkov != null) panelMarkov.setVisibility(model == SPManager.STREAM_NL_MODEL_MARKOV_RANDOM_WALK ? View.VISIBLE : View.GONE);
    }

    private static void updateModelInfo(@NonNull Context context,
                                        @NonNull SPManager sp,
                                        int model,
                                        @Nullable TextView tvDesc,
                                        @Nullable TextView tvSummary,
                                        @Nullable Slider sPauseMult,
                                        @Nullable Slider sSigma,
                                        @Nullable Slider sLcT,
                                        @Nullable Slider sExpTmax,
                                        @Nullable Slider sExpTmin,
                                        @Nullable Slider sExpLambda,
                                        @Nullable Slider sSineTBase,
                                        @Nullable Slider sSineA,
                                        @Nullable Slider sSinePeriod,
                                        @Nullable Slider sDampA,
                                        @Nullable Slider sDampOmega,
                                        @Nullable Slider sDampZeta,
                                        @Nullable Slider sSquareA,
                                        @Nullable Slider sSquareOmega,
                                        @Nullable Slider sMkMu,
                                        @Nullable Slider sMkRho,
                                        @Nullable Slider sMkSigma,
                                        @Nullable Slider sMkTmin,
                                        @Nullable Slider sMkTmax,
                                        @Nullable Slider sMkPthink) {
        if (tvDesc != null) {
            tvDesc.setText(getModelDescription(context, model));
        }
        if (tvSummary != null) {
            tvSummary.setText(buildSummary(
                    context,
                    sp,
                    model,
                    sPauseMult,
                    sSigma,
                    sLcT,
                    sExpTmax,
                    sExpTmin,
                    sExpLambda,
                    sSineTBase,
                    sSineA,
                    sSinePeriod,
                    sDampA,
                    sDampOmega,
                    sDampZeta,
                    sSquareA,
                    sSquareOmega,
                    sMkMu,
                    sMkRho,
                    sMkSigma,
                    sMkTmin,
                    sMkTmax,
                    sMkPthink
            ));
        }
    }

    /**
     * Build and render a small rhythm curve preview.
     * The preview is deterministic (stable across refresh) so it doesn't "flicker" while dragging.
     */
    private static void updatePreview(@NonNull SPManager sp,
                                      int model,
                                      @Nullable NlRhythmPreviewView preview,
                                      @Nullable Slider sPauseMult,
                                      @Nullable Slider sSigma,
                                      @Nullable Slider sLcT,
                                      @Nullable Slider sExpTmax,
                                      @Nullable Slider sExpTmin,
                                      @Nullable Slider sExpLambda,
                                      @Nullable Slider sSineTBase,
                                      @Nullable Slider sSineA,
                                      @Nullable Slider sSinePeriod,
                                      @Nullable Slider sDampA,
                                      @Nullable Slider sDampOmega,
                                      @Nullable Slider sDampZeta,
                                      @Nullable Slider sSquareA,
                                      @Nullable Slider sSquareOmega,
                                      @Nullable Slider sMkMu,
                                      @Nullable Slider sMkRho,
                                      @Nullable Slider sMkSigma,
                                      @Nullable Slider sMkTmin,
                                      @Nullable Slider sMkTmax,
                                      @Nullable Slider sMkPthink) {
        if (preview == null) return;

        final int N = 81; // 0..80

        double tau = sp.getStreamingNonLinearPauseMultiplier();
        if (sPauseMult != null) tau = sPauseMult.getValue();

        int globalSigma = sp.getStreamingNonLinearSigmaMs();
        if (sSigma != null) globalSigma = Math.round(sSigma.getValue());

        float[] y = new float[N];
        float[] lo = null;
        float[] hi = null;

        // A fixed punctuation pattern to visualize the pause multiplier (applied on selected ticks).
        final int[] punctTicks = new int[] { 12, 28, 45, 63, 76 };

        switch (model) {
            case SPManager.STREAM_NL_MODEL_LINEAR_CONSTANT: {
                int t = sp.getNlLinearConstantTBaseMs();
                if (sLcT != null) t = Math.round(sLcT.getValue());
                for (int i = 0; i < N; i++) y[i] = clampPreviewMs(t);
                break;
            }
            case SPManager.STREAM_NL_MODEL_EXPONENTIAL_DECAY: {
                int tmax = sp.getNlExpTMaxMs();
                int tmin = sp.getNlExpTMinMs();
                double lambda = sp.getNlExpLambda();
                if (sExpTmax != null) tmax = Math.round(sExpTmax.getValue());
                if (sExpTmin != null) tmin = Math.round(sExpTmin.getValue());
                if (sExpLambda != null) lambda = sExpLambda.getValue();
                double hiT = Math.max(tmax, tmin);
                double loT = Math.min(tmax, tmin);
                for (int i = 0; i < N; i++) {
                    double ms = loT + (hiT - loT) * Math.exp(-lambda * i);
                    y[i] = clampPreviewMs(ms);
                }
                break;
            }
            case SPManager.STREAM_NL_MODEL_SINE_WAVE_JITTER: {
                int tBase = sp.getNlSineTBaseMs();
                int a = sp.getNlSineAMs();
                int period = sp.getNlSinePeriodN();
                if (sSineTBase != null) tBase = Math.round(sSineTBase.getValue());
                if (sSineA != null) a = Math.round(sSineA.getValue());
                if (sSinePeriod != null) period = Math.round(sSinePeriod.getValue());
                if (period < 5) period = 5;
                double omega = (2.0 * Math.PI) / (double) period;
                for (int i = 0; i < N; i++) {
                    double ms = tBase + a * Math.sin(omega * i);
                    y[i] = clampPreviewMs(ms);
                }
                break;
            }
            case SPManager.STREAM_NL_MODEL_DAMPED_OSCILLATOR: {
                int tBase = sp.getNlDampTBaseMs();
                int a = sp.getNlDampAMs();
                double omega = sp.getNlDampOmega();
                double zeta = sp.getNlDampZeta();
                double phi = sp.getNlDampPhi();
                if (sDampA != null) a = Math.round(sDampA.getValue());
                if (sDampOmega != null) omega = sDampOmega.getValue();
                if (sDampZeta != null) zeta = sDampZeta.getValue();
                for (int i = 0; i < N; i++) {
                    double ms = tBase + a * Math.exp(-zeta * i) * Math.cos(omega * i + phi);
                    y[i] = clampPreviewMs(ms);
                }
                break;
            }
            case SPManager.STREAM_NL_MODEL_SQUARE_WAVE_BURST: {
                int tBase = sp.getNlSquareTBaseMs();
                int a = sp.getNlSquareAMs();
                double omega = sp.getNlSquareOmega();
                if (sSquareA != null) a = Math.round(sSquareA.getValue());
                if (sSquareOmega != null) omega = sSquareOmega.getValue();
                for (int i = 0; i < N; i++) {
                    double s = Math.sin(omega * i);
                    double sign = (s >= 0.0) ? 1.0 : -1.0;
                    double ms = tBase + a * sign;
                    y[i] = clampPreviewMs(ms);
                }
                break;
            }
            case SPManager.STREAM_NL_MODEL_MARKOV_RANDOM_WALK:
            default: {
                int mu = sp.getNlMarkovMuMs();
                double rho = sp.getNlMarkovRho();
                int sigma = sp.getNlMarkovSigmaMs();
                int tmin = sp.getNlMarkovTMinMs();
                int tmax = sp.getNlMarkovTMaxMs();
                double pThink = sp.getNlMarkovPThinkProbability();

                if (sMkMu != null) mu = Math.round(sMkMu.getValue());
                if (sMkRho != null) rho = sMkRho.getValue();
                if (sMkSigma != null) sigma = Math.round(sMkSigma.getValue());
                if (sMkTmin != null) tmin = Math.round(sMkTmin.getValue());
                if (sMkTmax != null) tmax = Math.round(sMkTmax.getValue());
                if (sMkPthink != null) pThink = sMkPthink.getValue() / 100.0;

                if (tmax < tmin) {
                    int tmp = tmax;
                    tmax = tmin;
                    tmin = tmp;
                }

                // Deterministic eps stream to keep the preview stable while dragging.
                Random rng = new Random(42);
                double prev = mu;
                for (int i = 0; i < N; i++) {
                    double eps = rng.nextGaussian();
                    double next = mu + rho * (prev - mu) + sigma * eps;
                    // Clamp physical bounds
                    if (next < tmin) next = tmin;
                    if (next > tmax) next = tmax;
                    prev = next;

                    // Occasional think stall
                    double ms = next;
                    if (pThink > 0.0 && rng.nextDouble() < pThink) ms = tmax;

                    y[i] = clampPreviewMs(ms);
                }

                // Show a soft band to communicate "randomness".
                lo = new float[N];
                hi = new float[N];
                double statStd;
                if (rho >= 0.99) statStd = sigma * 4.0;
                else statStd = sigma / Math.sqrt(Math.max(1e-6, 1.0 - rho * rho));
                double totalStd = Math.sqrt(statStd * statStd + globalSigma * globalSigma);
                for (int i = 0; i < N; i++) {
                    lo[i] = clampPreviewMs(y[i] - totalStd);
                    hi[i] = clampPreviewMs(y[i] + totalStd);
                }
                break;
            }
        }

        // Apply a simple punctuation visualization for τ (pause multiplier).
        if (tau > 1.0) {
            for (int t : punctTicks) {
                if (t >= 0 && t < N) {
                    y[t] = clampPreviewMs(y[t] * tau);
                    if (lo != null && hi != null) {
                        lo[t] = clampPreviewMs(lo[t] * tau);
                        hi[t] = clampPreviewMs(hi[t] * tau);
                    }
                }
            }
        }

        // If we don't already have a band (non-Markov), use global sigma as a light uncertainty band.
        if (lo == null || hi == null) {
            if (globalSigma > 0) {
                lo = new float[N];
                hi = new float[N];
                for (int i = 0; i < N; i++) {
                    lo[i] = clampPreviewMs(y[i] - globalSigma);
                    hi[i] = clampPreviewMs(y[i] + globalSigma);
                }
            }
        }

        preview.setData(y, lo, hi);
    }

    private static float clampPreviewMs(double ms) {
        double v = ms;
        if (v < 12.0) v = 12.0;
        if (v > 2000.0) v = 2000.0;
        return (float) v;
    }

    private static String getModelDescription(@NonNull Context context, int model) {
        switch (model) {
            case SPManager.STREAM_NL_MODEL_LINEAR_CONSTANT:
                return context.getString(R.string.ui_streaming_nl_desc_linear_constant);
            case SPManager.STREAM_NL_MODEL_EXPONENTIAL_DECAY:
                return context.getString(R.string.ui_streaming_nl_desc_exponential_decay);
            case SPManager.STREAM_NL_MODEL_SINE_WAVE_JITTER:
                return context.getString(R.string.ui_streaming_nl_desc_sine_wave_jitter);
            case SPManager.STREAM_NL_MODEL_DAMPED_OSCILLATOR:
                return context.getString(R.string.ui_streaming_nl_desc_damped_oscillator);
            case SPManager.STREAM_NL_MODEL_SQUARE_WAVE_BURST:
                return context.getString(R.string.ui_streaming_nl_desc_square_wave_burst);
            case SPManager.STREAM_NL_MODEL_MARKOV_RANDOM_WALK:
            default:
                return context.getString(R.string.ui_streaming_nl_desc_markov_random_walk);
        }
    }

    private static String buildSummary(@NonNull Context context,
                                       @NonNull SPManager sp,
                                       int model,
                                       @Nullable Slider sPauseMult,
                                       @Nullable Slider sSigma,
                                       @Nullable Slider sLcT,
                                       @Nullable Slider sExpTmax,
                                       @Nullable Slider sExpTmin,
                                       @Nullable Slider sExpLambda,
                                       @Nullable Slider sSineTBase,
                                       @Nullable Slider sSineA,
                                       @Nullable Slider sSinePeriod,
                                       @Nullable Slider sDampA,
                                       @Nullable Slider sDampOmega,
                                       @Nullable Slider sDampZeta,
                                       @Nullable Slider sSquareA,
                                       @Nullable Slider sSquareOmega,
                                       @Nullable Slider sMkMu,
                                       @Nullable Slider sMkRho,
                                       @Nullable Slider sMkSigma,
                                       @Nullable Slider sMkTmin,
                                       @Nullable Slider sMkTmax,
                                       @Nullable Slider sMkPthink) {

        double tau = sp.getStreamingNonLinearPauseMultiplier();
        if (sPauseMult != null) tau = sPauseMult.getValue();

        int sigmaGlobal = sp.getStreamingNonLinearSigmaMs();
        if (sSigma != null) sigmaGlobal = Math.round(sSigma.getValue());

        String globalLabel = context.getString(R.string.ui_streaming_nl_global_short);
        String global = String.format(Locale.US, "%s τ=%.1f×, σ=%dms", globalLabel, tau, sigmaGlobal);

        String modelPart;
        switch (model) {
            case SPManager.STREAM_NL_MODEL_LINEAR_CONSTANT: {
                int t = sp.getNlLinearConstantTBaseMs();
                if (sLcT != null) t = Math.round(sLcT.getValue());
                modelPart = String.format(Locale.US, "T=%dms", t);
                break;
            }
            case SPManager.STREAM_NL_MODEL_EXPONENTIAL_DECAY: {
                int tmax = sp.getNlExpTMaxMs();
                int tmin = sp.getNlExpTMinMs();
                double lambda = sp.getNlExpLambda();
                if (sExpTmax != null) tmax = Math.round(sExpTmax.getValue());
                if (sExpTmin != null) tmin = Math.round(sExpTmin.getValue());
                if (sExpLambda != null) lambda = sExpLambda.getValue();
                modelPart = String.format(Locale.US, "Tmax=%dms → Tmin=%dms, λ=%s", tmax, tmin, formatDouble(lambda, 3));
                break;
            }
            case SPManager.STREAM_NL_MODEL_SINE_WAVE_JITTER: {
                int tBase = sp.getNlSineTBaseMs();
                int a = sp.getNlSineAMs();
                int n = sp.getNlSinePeriodN();
                if (sSineTBase != null) tBase = Math.round(sSineTBase.getValue());
                if (sSineA != null) a = Math.round(sSineA.getValue());
                if (sSinePeriod != null) n = Math.round(sSinePeriod.getValue());
                modelPart = String.format(Locale.US, "Tbase=%dms, A=%dms, N=%s", tBase, a, formatChars(n));
                break;
            }
            case SPManager.STREAM_NL_MODEL_DAMPED_OSCILLATOR: {
                int a = sp.getNlDampAMs();
                double omega = sp.getNlDampOmega();
                double zeta = sp.getNlDampZeta();
                if (sDampA != null) a = Math.round(sDampA.getValue());
                if (sDampOmega != null) omega = sDampOmega.getValue();
                if (sDampZeta != null) zeta = sDampZeta.getValue();
                modelPart = String.format(Locale.US, "A=%dms, ω=%s, ζ=%s", a, formatDouble(omega, 2), formatDouble(zeta, 2));
                break;
            }
            case SPManager.STREAM_NL_MODEL_SQUARE_WAVE_BURST: {
                int a = sp.getNlSquareAMs();
                double omega = sp.getNlSquareOmega();
                if (sSquareA != null) a = Math.round(sSquareA.getValue());
                if (sSquareOmega != null) omega = sSquareOmega.getValue();
                modelPart = String.format(Locale.US, "A=%dms, ω=%s", a, formatDouble(omega, 2));
                break;
            }
            case SPManager.STREAM_NL_MODEL_MARKOV_RANDOM_WALK:
            default: {
                int mu = sp.getNlMarkovMuMs();
                double rho = sp.getNlMarkovRho();
                int sig = sp.getNlMarkovSigmaMs();
                int tmin = sp.getNlMarkovTMinMs();
                int tmax = sp.getNlMarkovTMaxMs();
                double pThinkPct = sp.getNlMarkovPThinkPercent();

                if (sMkMu != null) mu = Math.round(sMkMu.getValue());
                if (sMkRho != null) rho = sMkRho.getValue();
                if (sMkSigma != null) sig = Math.round(sMkSigma.getValue());
                if (sMkTmin != null) tmin = Math.round(sMkTmin.getValue());
                if (sMkTmax != null) tmax = Math.round(sMkTmax.getValue());
                if (sMkPthink != null) pThinkPct = sMkPthink.getValue();

                modelPart = String.format(Locale.US,
                        "μ=%dms, ρ=%s, σ=%dms, Tmin=%dms, Tmax=%dms, Pthink=%s",
                        mu,
                        formatDouble(rho, 2),
                        sig,
                        tmin,
                        tmax,
                        formatPercent(pThinkPct, 1));
                break;
            }
        }

        return modelPart + "  ·  " + global;
    }

    private static List<ModelRow> buildModelRows(@NonNull Context context) {
        List<ModelRow> rows = new ArrayList<>();
        rows.add(ModelRow.header(context.getString(R.string.ui_streaming_nl_group_classic)));
        rows.add(ModelRow.model(
                SPManager.STREAM_NL_MODEL_LINEAR_CONSTANT,
                context.getString(R.string.ui_streaming_nl_model_linear_constant),
                context.getString(R.string.ui_streaming_nl_desc_linear_constant)
        ));
        rows.add(ModelRow.model(
                SPManager.STREAM_NL_MODEL_EXPONENTIAL_DECAY,
                context.getString(R.string.ui_streaming_nl_model_exponential_decay),
                context.getString(R.string.ui_streaming_nl_desc_exponential_decay)
        ));
        rows.add(ModelRow.model(
                SPManager.STREAM_NL_MODEL_SINE_WAVE_JITTER,
                context.getString(R.string.ui_streaming_nl_model_sine_wave_jitter),
                context.getString(R.string.ui_streaming_nl_desc_sine_wave_jitter)
        ));
        rows.add(ModelRow.header(context.getString(R.string.ui_streaming_nl_group_advanced)));
        rows.add(ModelRow.model(
                SPManager.STREAM_NL_MODEL_DAMPED_OSCILLATOR,
                context.getString(R.string.ui_streaming_nl_model_damped_oscillator),
                context.getString(R.string.ui_streaming_nl_desc_damped_oscillator)
        ));
        rows.add(ModelRow.model(
                SPManager.STREAM_NL_MODEL_SQUARE_WAVE_BURST,
                context.getString(R.string.ui_streaming_nl_model_square_wave_burst),
                context.getString(R.string.ui_streaming_nl_desc_square_wave_burst)
        ));
        rows.add(ModelRow.model(
                SPManager.STREAM_NL_MODEL_MARKOV_RANDOM_WALK,
                context.getString(R.string.ui_streaming_nl_model_markov_random_walk),
                context.getString(R.string.ui_streaming_nl_desc_markov_random_walk)
        ));
        return rows;
    }

    private static int findPositionForModel(@NonNull List<ModelRow> rows, int model) {
        for (int i = 0; i < rows.size(); i++) {
            ModelRow r = rows.get(i);
            if (!r.isHeader && r.modelValue == model) return i;
        }
        return -1;
    }

    private static int findFirstSelectable(@NonNull List<ModelRow> rows) {
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).isHeader) return i;
        }
        return -1;
    }

    private static int findNearestSelectable(@NonNull List<ModelRow> rows, int fromPos) {
        // Try next
        for (int i = fromPos + 1; i < rows.size(); i++) {
            if (!rows.get(i).isHeader) return i;
        }
        // Try previous
        for (int i = fromPos - 1; i >= 0; i--) {
            if (!rows.get(i).isHeader) return i;
        }
        return -1;
    }

    private static String formatMs(int ms) {
        return ms + " ms";
    }

    private static String formatChars(int chars) {
        String lang = Locale.getDefault() != null ? Locale.getDefault().getLanguage() : "";
        String unit = (lang != null && lang.startsWith("zh")) ? "字" : "chars";
        return chars + " " + unit;
    }

    private static String formatPercent(double percent, int decimals) {
        double v = percent;
        if (Double.isNaN(v) || Double.isInfinite(v)) v = 0.0;
        if (v < 0.0) v = 0.0;
        if (v > 100.0) v = 100.0;
        String fmt = "%." + Math.max(0, decimals) + "f%%";
        return String.format(Locale.US, fmt, v);
    }

    private static final class PrefetchRow {
        final int mode;
        final String label;

        PrefetchRow(int mode, String label) {
            this.mode = mode;
            this.label = label;
        }

        @NonNull
        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Prefetch/render-buffer mode dialog adapter.
     * Uses the same two-line row layout, but only shows a single title line.
     * Selection is temporary inside the dialog and must be committed by the caller on "OK".
     */
    private static final class PrefetchDialogAdapter extends BaseAdapter {
        private final LayoutInflater inflater;
        private final List<PrefetchRow> rows;
        private int tempSelectedIndex;

        private final int colorTitle;

        PrefetchDialogAdapter(@NonNull Context context, @NonNull List<PrefetchRow> rows, int initiallyCheckedPos) {
            this.inflater = LayoutInflater.from(context);
            this.rows = rows;
            this.tempSelectedIndex = initiallyCheckedPos;
            this.colorTitle = ModelDialogAdapter.resolveThemeColor(context, "colorOnSurface", 0xFF000000);
        }

        int getTempSelectedIndex() {
            return tempSelectedIndex;
        }

        void setTempSelectedIndex(int index) {
            if (index < 0 || index >= rows.size()) return;
            tempSelectedIndex = index;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) v = inflater.inflate(R.layout.item_dialog_two_line_choice, parent, false);

            TextView tvTitle = v.findViewById(R.id.tvTitle);
            TextView tvDesc = v.findViewById(R.id.tvDesc);
            RadioButton rbChoice = v.findViewById(R.id.rbChoice);

            PrefetchRow row = rows.get(position);
            tvTitle.setText(row.label);
            tvTitle.setTextColor(colorTitle);
            tvTitle.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            tvTitle.setTextSize(16f);

            if (tvDesc != null) tvDesc.setVisibility(View.GONE);

            if (rbChoice != null) {
                rbChoice.setVisibility(View.VISIBLE);
                rbChoice.setChecked(position == tempSelectedIndex);
            }

            int padH = dp(v, 20);
            int padV = dp(v, 12);
            v.setPadding(padH, padV, padH, padV);
            v.setEnabled(true);
            v.setAlpha(1f);
            return v;
        }

        private static int dp(@NonNull View v, int dp) {
            return (int) Math.round(dp * v.getResources().getDisplayMetrics().density);
        }
    }

    private static List<PrefetchRow> buildPrefetchRows(@NonNull Context context) {
        List<PrefetchRow> rows = new ArrayList<>();
        rows.add(new PrefetchRow(SPManager.STREAM_PREFETCH_OFF, context.getString(R.string.ui_streaming_nl_prefetch_mode_off)));
        rows.add(new PrefetchRow(SPManager.STREAM_PREFETCH_FAST, context.getString(R.string.ui_streaming_nl_prefetch_mode_fast)));
        rows.add(new PrefetchRow(SPManager.STREAM_PREFETCH_DEFAULT, context.getString(R.string.ui_streaming_nl_prefetch_mode_default)));
        rows.add(new PrefetchRow(SPManager.STREAM_PREFETCH_STABLE, context.getString(R.string.ui_streaming_nl_prefetch_mode_stable)));
        rows.add(new PrefetchRow(SPManager.STREAM_PREFETCH_CUSTOM, context.getString(R.string.ui_streaming_nl_prefetch_mode_custom)));
        return rows;
    }

    /**
        * Ensure prefetch thresholds are sane and persisted (CUSTOM mode only).
        * Constraints:
        * - topUpTarget >= max(start, low)
        */
    private static void enforcePrefetchConstraints(
            @NonNull SPManager sp,
            @Nullable Slider start,
            @Nullable Slider low,
            @Nullable Slider target,
            @Nullable TextView tvStart,
            @Nullable TextView tvLow,
            @Nullable TextView tvTarget
    ) {
        if (sp.getStreamingPrefetchMode() != SPManager.STREAM_PREFETCH_CUSTOM) return;
        if (start == null || low == null || target == null) return;

        int s = Math.round(start.getValue());
        int l = Math.round(low.getValue());
        int t = Math.round(target.getValue());

        int minTarget = Math.max(s, l);
        if (t < minTarget) {
            float snapped = snapToSlider(target, minTarget);
            target.setValue(snapped);
            t = Math.round(snapped);
            sp.setStreamingPrefetchTopUpTarget(t);
        }

        // Persist current values
        sp.setStreamingPrefetchStartChars(s);
        sp.setStreamingPrefetchLowWatermark(l);
        sp.setStreamingPrefetchTopUpTarget(t);

        if (tvStart != null) tvStart.setText(formatChars(s));
        if (tvLow != null) tvLow.setText(formatChars(l));
        if (tvTarget != null) tvTarget.setText(formatChars(t));
    }

    private static String formatMultiplier(double v) {
        return String.format(Locale.US, "%1$.1f×", v);
    }

    private static String formatDouble(double v, int decimals) {
        String fmt = "%1$." + decimals + "f";
        return String.format(Locale.US, fmt, v);
    }

    private static boolean nearlyEqual(float a, float b) {
        return Math.abs(a - b) < 1e-4f;
    }

    /**
     * Snap a desired value to a slider's valid configuration.
     * Ensures the returned value is within [valueFrom, valueTo] and aligned to stepSize.
     */
    private static float snapToSlider(@NonNull Slider slider, float desired) {
        float from = slider.getValueFrom();
        float to = slider.getValueTo();
        float step = slider.getStepSize();

        double v = desired;
        if (v < from) v = from;
        if (v > to) v = to;

        if (step > 0f) {
            double n = Math.rint((v - from) / step); // round to nearest integer step
            v = from + n * step;
            // Clamp again to protect against rounding drift
            if (v < from) v = from;
            if (v > to) v = to;
            // Avoid float representation issues by rounding to 1e-6
            v = Math.round(v * 1_000_000d) / 1_000_000d;
        }

        return (float) v;
    }

    private static final class SimpleTouchListener implements Slider.OnSliderTouchListener {
        private final Runnable onStop;

        SimpleTouchListener(Runnable onStop) {
            this.onStop = onStop;
        }

        @Override
        public void onStartTrackingTouch(@NonNull Slider slider) {
        }

        @Override
        public void onStopTrackingTouch(@NonNull Slider slider) {
            if (onStop != null) onStop.run();
        }
    }

    private static final class ModelRow {
        final boolean isHeader;
        final String label;
        final String description;
        final int modelValue;

        private ModelRow(boolean isHeader, @NonNull String label, @NonNull String description, int modelValue) {
            this.isHeader = isHeader;
            this.label = label;
            this.description = description;
            this.modelValue = modelValue;
        }

        static ModelRow header(@NonNull String label) {
            return new ModelRow(true, label, "", -1);
        }

        static ModelRow model(int value, @NonNull String label, @NonNull String description) {
            return new ModelRow(false, label, description, value);
        }
    }

    /**
     * Two-line dialog adapter with section headers and a left-side radio indicator.
     *
     * IMPORTANT: Selection is *temporary* inside the dialog. Caller must commit on "OK".
     */
    private static final class ModelDialogAdapter extends BaseAdapter {
        private final LayoutInflater inflater;
        private final List<ModelRow> rows;

        /** Temporary selection (list position), committed only when the user presses OK. */
        private int tempSelectedIndex;

        private final int colorHeader;
        private final int colorTitle;
        private final int colorDesc;
        ModelDialogAdapter(@NonNull Context context, @NonNull List<ModelRow> rows, int initiallyCheckedPos) {
            this.inflater = LayoutInflater.from(context);
            this.rows = rows;
            this.tempSelectedIndex = initiallyCheckedPos;
            // IMPORTANT:
            // This project uses non-transitive R classes, so dependency attrs are NOT available
            // via the app's R.attr.* at compile time. Also, some Material versions don't expose
            // certain framework attrs (e.g., colorPrimary) in com.google.android.material.R.attr.
            // To keep CI/build stable, resolve attr IDs by *name* at runtime.
            this.colorHeader = resolveThemeColor(context, "colorOnSurfaceVariant", 0xFF888888);
            this.colorTitle = resolveThemeColor(context, "colorOnSurface", 0xFF000000);
            this.colorDesc = resolveThemeColor(context, "colorOnSurfaceVariant", 0xFF666666);
            // Note: we don't need a dedicated checked color because we use the framework RadioButton.
        }

        int getTempSelectedIndex() {
            return tempSelectedIndex;
        }

        void setTempSelectedIndex(int index) {
            if (index < 0 || index >= rows.size()) return;
            if (rows.get(index).isHeader) return;
            tempSelectedIndex = index;
            notifyDataSetChanged();
        }

        private static int resolveThemeColor(@NonNull Context context, @NonNull String attrName, int fallback) {
            int attrId = 0;
            try {
                // First try the app package (merged resources include dependency attrs).
                attrId = context.getResources().getIdentifier(attrName, "attr", context.getPackageName());
                if (attrId == 0) {
                    // Then try framework attrs.
                    attrId = context.getResources().getIdentifier(attrName, "attr", "android");
                }
            } catch (Throwable ignored) {
                // Ignore and fall back.
            }

            if (attrId == 0) return fallback;

            try {
                return MaterialColors.getColor(context, attrId, fallback);
            } catch (Throwable ignored) {
                return fallback;
            }
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean isEnabled(int position) {
            return !rows.get(position).isHeader;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) v = inflater.inflate(R.layout.item_dialog_two_line_choice, parent, false);

            TextView tvTitle = v.findViewById(R.id.tvTitle);
            TextView tvDesc = v.findViewById(R.id.tvDesc);
            RadioButton rbChoice = v.findViewById(R.id.rbChoice);

            ModelRow row = rows.get(position);

            tvTitle.setText(row.label);
            tvDesc.setText(row.description);

            if (row.isHeader) {
                // Header: single line, non-clickable.
                tvTitle.setTextColor(colorHeader);
                tvTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                tvTitle.setTextSize(12f);
                tvDesc.setVisibility(View.GONE);
                if (rbChoice != null) rbChoice.setVisibility(View.GONE);

                // Add a bit more top padding for visual separation.
                int padH = dp(v, 20);
                int padTop = dp(v, 14);
                int padBottom = dp(v, 8);
                v.setPadding(padH, padTop, padH, padBottom);
                v.setEnabled(false);
                v.setAlpha(1f);
            } else {
                // Normal option: two lines + check indicator.
                tvTitle.setTextColor(colorTitle);
                tvTitle.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
                tvTitle.setTextSize(16f);
                if (row.description == null || row.description.trim().isEmpty()) {
                    tvDesc.setVisibility(View.GONE);
                } else {
                    tvDesc.setVisibility(View.VISIBLE);
                    tvDesc.setTextColor(colorDesc);
                    tvDesc.setTextSize(13f);
                }

                if (rbChoice != null) {
                    rbChoice.setVisibility(View.VISIBLE);
                    rbChoice.setChecked(position == tempSelectedIndex);
                }

                int padH = dp(v, 20);
                int padV = dp(v, 12);
                v.setPadding(padH, padV, padH, padV);
                v.setEnabled(true);
                v.setAlpha(1f);
            }

            return v;
        }

        private static int dp(@NonNull View v, int dp) {
            return (int) Math.round(dp * v.getResources().getDisplayMetrics().density);
        }
    }

    private static final class GroupedModelAdapter extends ArrayAdapter<ModelRow> {
        private final int colorHeader;
        private final int colorItem;

        GroupedModelAdapter(@NonNull Context context, @NonNull List<ModelRow> rows) {
            super(context, android.R.layout.simple_spinner_item, rows);
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            colorHeader = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF888888);
            colorItem = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0xFF000000);
        }

        @Override
        public boolean isEnabled(int position) {
            ModelRow row = getItem(position);
            return row != null && !row.isHeader;
        }

        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View v = super.getView(position, convertView, parent);
            TextView tv = (TextView) v;
            ModelRow row = getItem(position);
            if (row != null) tv.setText(row.label);

            // When used in a popup dialog, this getView() is the list row renderer.
            // Style section headers clearly.
            if (row != null && row.isHeader) {
                tv.setEnabled(false);
                tv.setTextColor(colorHeader);
                tv.setAllCaps(true);
                tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                tv.setTextSize(12f);
                int padLeft = tv.getPaddingLeft();
                int padRight = tv.getPaddingRight();
                tv.setPadding(padLeft, dp(tv, 10), padRight, dp(tv, 6));
            } else {
                tv.setEnabled(true);
                tv.setTextColor(colorItem);
                tv.setAllCaps(false);
                tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
                tv.setTextSize(14f);
            }
            return v;
        }

        @Override
        public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View v = super.getDropDownView(position, convertView, parent);
            TextView tv = (TextView) v;
            ModelRow row = getItem(position);
            if (row == null) return v;

            tv.setText(row.label);

            if (row.isHeader) {
                tv.setEnabled(false);
                tv.setTextColor(colorHeader);
                tv.setAllCaps(true);
                tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                tv.setTextSize(12f);
                // Add a bit more top padding for section headers
                int padLeft = tv.getPaddingLeft();
                int padRight = tv.getPaddingRight();
                tv.setPadding(padLeft, dp(tv, 10), padRight, dp(tv, 6));
            } else {
                tv.setEnabled(true);
                tv.setTextColor(colorItem);
                tv.setAllCaps(false);
                tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
                tv.setTextSize(14f);
            }
            return v;
        }

        private static int dp(@NonNull View v, int dp) {
            return (int) Math.round(dp * v.getResources().getDisplayMetrics().density);
        }
    }

    private interface IntGetter {
        int get();
    }

    /**
     * A lightweight "typing audition" preview.
     * Simulates streaming output inside the bottom sheet only.
     */
    private static final class TypingPreviewController {

        private final Context context;
        private final SPManager sp;
        private final IntGetter modelGetter;

        @Nullable private final TextView tvSample;
        @Nullable private final TextView tvOut;
        @Nullable private final TextView btnPlay;
        @Nullable private final View btnReplay;

        @Nullable private final Slider sPauseMult;
        @Nullable private final Slider sSigma;

        // Constant
        @Nullable private final Slider sLcT;

        // Exponential
        @Nullable private final Slider sExpTmax;
        @Nullable private final Slider sExpTmin;
        @Nullable private final Slider sExpLambda;

        // Sine (breathing)
        @Nullable private final Slider sSineTBase;
        @Nullable private final Slider sSineA;
        @Nullable private final Slider sSinePeriod;

        // Damped
        @Nullable private final Slider sDampA;
        @Nullable private final Slider sDampOmega;
        @Nullable private final Slider sDampZeta;

        // Square
        @Nullable private final Slider sSquareA;
        @Nullable private final Slider sSquareOmega;

        // Markov
        @Nullable private final Slider sMkMu;
        @Nullable private final Slider sMkRho;
        @Nullable private final Slider sMkSigma;
        @Nullable private final Slider sMkTmin;
        @Nullable private final Slider sMkTmax;
        @Nullable private final Slider sMkPthink;

        private final Handler handler = new Handler(Looper.getMainLooper());
        private Random rng = new Random();

        @Nullable private final android.os.Vibrator vibrator;

        private boolean playing = false;
        private boolean paused = false;
        private int index = 0;

        // Markov state
        private boolean markovInit = false;
        private double markovPrev = 0.0;

        private final Runnable step = new Runnable() {
            @Override
            public void run() {
                if (!playing || paused) return;
                if (tvOut == null) { stop(); return; }

                String text = getSampleText();
                if (text == null) { stop(); return; }

                if (index >= text.length()) {
                    playing = false;
                    paused = false;
                    updateButtons();
                    return;
                }

                char c = text.charAt(index);

                CharSequence cur = tvOut.getText();
                String hint = context.getString(R.string.ui_streaming_nl_preview_hint);
                if (cur == null || cur.toString().equals(hint)) {
                    tvOut.setText(String.valueOf(c));
                } else {
                    tvOut.append(String.valueOf(c));
                }

                long delay = computeDelayMs(index, c);

                // Haptics: pulse per tick so the vibration rhythm follows current params.
                vibrateForTick(delay, c);

                index++;
                handler.postDelayed(this, delay);
            }
        };

        TypingPreviewController(@NonNull Context context,
                                @NonNull SPManager sp,
                                @NonNull IntGetter modelGetter,
                                @Nullable TextView tvSample,
                                @Nullable TextView tvOut,
                                @Nullable View btnPlay,
                                @Nullable View btnReplay,
                                @Nullable Slider sPauseMult,
                                @Nullable Slider sSigma,
                                @Nullable Slider sLcT,
                                @Nullable Slider sExpTmax,
                                @Nullable Slider sExpTmin,
                                @Nullable Slider sExpLambda,
                                @Nullable Slider sSineTBase,
                                @Nullable Slider sSineA,
                                @Nullable Slider sSinePeriod,
                                @Nullable Slider sDampA,
                                @Nullable Slider sDampOmega,
                                @Nullable Slider sDampZeta,
                                @Nullable Slider sSquareA,
                                @Nullable Slider sSquareOmega,
                                @Nullable Slider sMkMu,
                                @Nullable Slider sMkRho,
                                @Nullable Slider sMkSigma,
                                @Nullable Slider sMkTmin,
                                @Nullable Slider sMkTmax,
                                @Nullable Slider sMkPthink) {
            this.context = context;
            this.sp = sp;
            this.modelGetter = modelGetter;
            this.tvSample = tvSample;
            this.tvOut = tvOut;
            this.btnPlay = (btnPlay instanceof TextView) ? (TextView) btnPlay : null;
            this.btnReplay = btnReplay;

            this.sPauseMult = sPauseMult;
            this.sSigma = sSigma;

            this.sLcT = sLcT;

            this.sExpTmax = sExpTmax;
            this.sExpTmin = sExpTmin;
            this.sExpLambda = sExpLambda;

            this.sSineTBase = sSineTBase;
            this.sSineA = sSineA;
            this.sSinePeriod = sSinePeriod;

            this.sDampA = sDampA;
            this.sDampOmega = sDampOmega;
            this.sDampZeta = sDampZeta;

            this.sSquareA = sSquareA;
            this.sSquareOmega = sSquareOmega;

            this.sMkMu = sMkMu;
            this.sMkRho = sMkRho;
            this.sMkSigma = sMkSigma;
            this.sMkTmin = sMkTmin;
            this.sMkTmax = sMkTmax;
            this.sMkPthink = sMkPthink;

	            android.os.Vibrator vib = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        android.os.VibratorManager vm = (android.os.VibratorManager)
                                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                        if (vm != null) vib = vm.getDefaultVibrator();
                    } catch (Throwable ignored) {
                    }
                }
                if (vib == null) {
                    vib = (android.os.Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                }
            } catch (Throwable ignored) {
            }
            this.vibrator = vib;

	            if (this.btnPlay != null) this.btnPlay.setOnClickListener(view -> togglePlayPause());
	            if (this.btnReplay != null) this.btnReplay.setOnClickListener(view -> replay());
            updateButtons();
        }

        void onParamsChanged() {
            // No-op: delays are computed per-tick from current params.
        }

        void togglePlayPause() {
            if (!playing) {
                start();
                return;
            }
            if (!paused) {
                paused = true;
                handler.removeCallbacks(step);
            } else {
                paused = false;
                handler.removeCallbacks(step);
                handler.post(step);
            }
            updateButtons();
        }

        void replay() {
            stopInternal(false);
            start();
        }

        void stop() {
            stopInternal(true);
        }

        private void start() {
            if (tvOut == null) return;
            playing = true;
            paused = false;
            index = 0;
            markovInit = false;
            markovPrev = 0.0;
            rng = new Random(System.nanoTime());

            tvOut.setText("");
            updateButtons();

            // Immediate feedback when starting preview.
            vibrateStartFeedback();

            handler.removeCallbacks(step);
            handler.post(step);
        }


        private void vibrateStartFeedback() {
            if (vibrator == null) return;
            // Make the "Preview" button vibration clearly noticeable across devices.
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    vibrator.vibrate(android.os.VibrationEffect.createPredefined(
                            android.os.VibrationEffect.EFFECT_HEAVY_CLICK));
                    return;
                } catch (Throwable ignored) {
                }
            }
            vibrateSimple(36, 180);
        }

        private void vibrateForTick(long delayMs, char c) {
            if (vibrator == null) return;

            // On modern Android, predefined haptics are more consistent across OEM devices.
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    int effect = isPauseChar(c)
                            ? android.os.VibrationEffect.EFFECT_CLICK
                            : android.os.VibrationEffect.EFFECT_TICK;
                    vibrator.vibrate(android.os.VibrationEffect.createPredefined(effect));
                    return;
                } catch (Throwable ignored) {
                }
            }

            // Fallback: map delay to a subtle pulse duration so longer pauses feel heavier.
            int dur = (int) Math.max(20, Math.min(45, delayMs / 10));
            int amp = isPauseChar(c) ? 200 : 140;

            vibrateSimple(dur, amp);
        }

        private static boolean isPauseChar(char c) {
            return c == '\n' || c == '\r'
                    || c == '。' || c == '！' || c == '？'
                    || c == '.' || c == '!' || c == '?'
                    || c == ',' || c == '，'
                    || c == ';' || c == '；'
                    || c == ':' || c == '：';
        }

        private void vibrateSimple(int durationMs, int amplitude) {
            if (vibrator == null) return;

            // Some OEMs misreport hasVibrator() on newer Android builds. Attempt anyway.
            int dur = Math.max(20, durationMs);

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    int amp = android.os.VibrationEffect.DEFAULT_AMPLITUDE;
                    try {
                        if (vibrator.hasAmplitudeControl()) {
                            amp = Math.max(1, Math.min(255, amplitude));
                        }
                    } catch (Throwable ignored) {
                    }
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(dur, amp));
                } else {
                    //noinspection deprecation
                    vibrator.vibrate(dur);
                }
            } catch (Throwable ignored) {
            }
        }

        private void stopInternal(boolean resetText) {
            playing = false;
            paused = false;
            handler.removeCallbacks(step);
            markovInit = false;
            if (resetText && tvOut != null) {
                tvOut.setText(context.getString(R.string.ui_streaming_nl_preview_hint));
            }
            updateButtons();
        }

        private void updateButtons() {
            if (btnPlay == null) return;
            if (!playing) btnPlay.setText(context.getString(R.string.ui_streaming_nl_preview_play));
            else if (paused) btnPlay.setText(context.getString(R.string.ui_streaming_nl_preview_resume));
            else btnPlay.setText(context.getString(R.string.ui_streaming_nl_preview_pause));
        }

        @Nullable
        private String getSampleText() {
            if (tvSample == null) return null;
            CharSequence cs = tvSample.getText();
            if (cs == null) return null;
            return cs.toString();
        }

        private long computeDelayMs(int n, char lastChar) {
            int model = modelGetter.get();

            double tau = sp.getStreamingNonLinearPauseMultiplier();
            if (sPauseMult != null) tau = sPauseMult.getValue();

            int globalSigma = sp.getStreamingNonLinearSigmaMs();
            if (sSigma != null) globalSigma = Math.round(sSigma.getValue());

            double ms;
            switch (model) {
                case SPManager.STREAM_NL_MODEL_LINEAR_CONSTANT: {
                    ms = (sLcT != null) ? sLcT.getValue() : sp.getNlLinearConstantTBaseMs();
                    break;
                }
                case SPManager.STREAM_NL_MODEL_EXPONENTIAL_DECAY: {
                    int tmax = sp.getNlExpTMaxMs();
                    int tmin = sp.getNlExpTMinMs();
                    double lambda = sp.getNlExpLambda();
                    if (sExpTmax != null) tmax = Math.round(sExpTmax.getValue());
                    if (sExpTmin != null) tmin = Math.round(sExpTmin.getValue());
                    if (sExpLambda != null) lambda = sExpLambda.getValue();
                    double hiT = Math.max(tmax, tmin);
                    double loT = Math.min(tmax, tmin);
                    ms = loT + (hiT - loT) * Math.exp(-lambda * n);
                    break;
                }
                case SPManager.STREAM_NL_MODEL_SINE_WAVE_JITTER: {
                    int tBase = sp.getNlSineTBaseMs();
                    int a = sp.getNlSineAMs();
                    int period = sp.getNlSinePeriodN();
                    if (sSineTBase != null) tBase = Math.round(sSineTBase.getValue());
                    if (sSineA != null) a = Math.round(sSineA.getValue());
                    if (sSinePeriod != null) period = Math.round(sSinePeriod.getValue());
                    if (period < 5) period = 5;
                    double omega = (2.0 * Math.PI) / (double) period;
                    double phi = sp.getNlSinePhi();
                    ms = tBase + a * Math.sin(omega * n + phi);
                    break;
                }
                case SPManager.STREAM_NL_MODEL_DAMPED_OSCILLATOR: {
                    int tBase = sp.getNlDampTBaseMs();
                    int a = sp.getNlDampAMs();
                    double omega = sp.getNlDampOmega();
                    double zeta = sp.getNlDampZeta();
                    double phi = sp.getNlDampPhi();
                    if (sDampA != null) a = Math.round(sDampA.getValue());
                    if (sDampOmega != null) omega = sDampOmega.getValue();
                    if (sDampZeta != null) zeta = sDampZeta.getValue();
                    ms = tBase + a * Math.exp(-zeta * n) * Math.cos(omega * n + phi);
                    break;
                }
                case SPManager.STREAM_NL_MODEL_SQUARE_WAVE_BURST: {
                    int tBase = sp.getNlSquareTBaseMs();
                    int a = sp.getNlSquareAMs();
                    double omega = sp.getNlSquareOmega();
                    if (sSquareA != null) a = Math.round(sSquareA.getValue());
                    if (sSquareOmega != null) omega = sSquareOmega.getValue();
                    double s = Math.sin(omega * n);
                    double sign = (s >= 0.0) ? 1.0 : -1.0;
                    ms = tBase + a * sign;
                    break;
                }
                case SPManager.STREAM_NL_MODEL_MARKOV_RANDOM_WALK:
                default: {
                    int mu = sp.getNlMarkovMuMs();
                    double rho = sp.getNlMarkovRho();
                    int sigma = sp.getNlMarkovSigmaMs();
                    int tmin = sp.getNlMarkovTMinMs();
                    int tmax = sp.getNlMarkovTMaxMs();
                    double pThink = sp.getNlMarkovPThinkProbability();

                    if (sMkMu != null) mu = Math.round(sMkMu.getValue());
                    if (sMkRho != null) rho = sMkRho.getValue();
                    if (sMkSigma != null) sigma = Math.round(sMkSigma.getValue());
                    if (sMkTmin != null) tmin = Math.round(sMkTmin.getValue());
                    if (sMkTmax != null) tmax = Math.round(sMkTmax.getValue());
                    if (sMkPthink != null) pThink = sMkPthink.getValue() / 100.0;

                    if (tmax < tmin) {
                        int tmp = tmax;
                        tmax = tmin;
                        tmin = tmp;
                    }

                    if (!markovInit) {
                        markovPrev = mu;
                        markovInit = true;
                    }

                    double eps = rng.nextGaussian();
                    double next = mu + rho * (markovPrev - mu) + sigma * eps;
                    if (next < tmin) next = tmin;
                    if (next > tmax) next = tmax;
                    markovPrev = next;

                    ms = next;
                    if (pThink > 0.0 && rng.nextDouble() < pThink) {
                        ms = tmax;
                    }
                    break;
                }
            }

            // Apply punctuation pause after punctuation.
            if (tau > 1.0 && isPausePunct(lastChar)) ms *= tau;

            // Global micro jitter (σ): additive noise.
            if (globalSigma > 0) {
                double j = rng.nextGaussian() * globalSigma;
                double cap = 3.0 * globalSigma;
                if (j > cap) j = cap;
                if (j < -cap) j = -cap;
                ms += j;
            }

            // Guard against NaN/Inf.
            if (Double.isNaN(ms) || Double.isInfinite(ms)) ms = 120.0;

            // In preview, keep a slightly higher minimum so the animation is perceptible.
            if (ms < 24.0) ms = 24.0;
            if (ms > 2000.0) ms = 2000.0;

            if (Double.isNaN(ms) || Double.isInfinite(ms)) ms = 120.0;
            return (long) Math.round(ms);
        }

        private static boolean isPausePunct(char c) {
            switch (c) {
                case '.':
                case ',':
                case ';':
                case ':':
                case '!':
                case '?':
                case '\n':
                case '\r':
                case '\t':
                case '，':
                case '。':
                case '！':
                case '？':
                case '；':
                case '：':
                case '、':
                    return true;
                default:
                    return false;
            }
        }
    }
}
