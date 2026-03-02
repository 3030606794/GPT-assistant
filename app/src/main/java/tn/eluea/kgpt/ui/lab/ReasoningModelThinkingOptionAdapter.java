package tn.eluea.kgpt.ui.lab;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.CompoundButtonCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import tn.eluea.kgpt.R;

/**
 * 推理模型思考选项（支持颜色轨道 + 动画预览）
 */
public class ReasoningModelThinkingOptionAdapter extends RecyclerView.Adapter<ReasoningModelThinkingOptionAdapter.VH> {

    public static final int ANIM_MODE_SEQ_LOOP = 0;
    public static final int ANIM_MODE_TRI_PHASE = 1;
    public static final int ANIM_MODE_SWEEP = 2;
    public static final int ANIM_MODE_NEURAL_PULSE = 3;

    public interface OnItemClickListener {
        void onItemClick(int position);
    }

    private final List<ReasoningModelThinkingOption> items;
    private final OnItemClickListener listener;
    private int selectedIndex;

    private int animationMode = ANIM_MODE_SEQ_LOOP;
    private boolean animationEnabled = true;
    private int animationSpeedPercent = 70;

    @Nullable private RecyclerView attachedRecyclerView;
    @Nullable private ValueAnimator selectedIndicatorAnimator;
    private boolean restartRetryPosted = false;

    public ReasoningModelThinkingOptionAdapter(List<ReasoningModelThinkingOption> items, int selectedIndex, OnItemClickListener listener) {
        this.items = items;
        this.selectedIndex = selectedIndex;
        this.listener = listener;
        setHasStableIds(false);
    }

    public void setSelectedIndex(int index) {
        if (items == null || items.isEmpty()) return;
        int bounded = index;
        if (bounded < 0) bounded = 0;
        if (bounded >= items.size()) bounded = items.size() - 1;
        int old = selectedIndex;
        selectedIndex = bounded;
        if (old >= 0 && old < getItemCount()) notifyItemChanged(old);
        if (selectedIndex >= 0 && selectedIndex < getItemCount()) notifyItemChanged(selectedIndex);
        restartSelectedIndicatorAnimation();
    }

    public void setAnimationMode(int mode) {
        if (mode < ANIM_MODE_SEQ_LOOP || mode > ANIM_MODE_NEURAL_PULSE) mode = ANIM_MODE_SEQ_LOOP;
        if (this.animationMode == mode) return;
        this.animationMode = mode;
        restartSelectedIndicatorAnimation();
    }

    public void setAnimationEnabled(boolean enabled) {
        if (this.animationEnabled == enabled) return;
        this.animationEnabled = enabled;
        restartSelectedIndicatorAnimation();
    }

    public void setAnimationSpeedPercent(int percent) {
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
        if (this.animationSpeedPercent == percent) return;
        this.animationSpeedPercent = percent;
        restartSelectedIndicatorAnimation();
    }

    public void restartSelectedIndicatorAnimation() {
        cancelSelectedIndicatorAnimation();
        if (!animationEnabled) {
            notifySelectedRowOnly();
            return;
        }
        if (selectedIndex < 0 || selectedIndex >= getItemCount()) return;
        final RecyclerView rv = attachedRecyclerView;
        if (rv == null) return;
        final RecyclerView.ViewHolder raw = rv.findViewHolderForAdapterPosition(selectedIndex);
        if (!(raw instanceof VH)) {
            notifyItemChanged(selectedIndex);
            if (!restartRetryPosted) {
                restartRetryPosted = true;
                rv.post(() -> {
                    restartRetryPosted = false;
                    if (attachedRecyclerView != rv) return;
                    if (!animationEnabled) return;
                    if (selectedIndicatorAnimator != null) return;
                    restartSelectedIndicatorAnimation();
                });
            }
            return;
        }
        restartRetryPosted = false;
        final VH holder = (VH) raw;
        final ReasoningModelThinkingOption opt = items.get(selectedIndex);
        final int target = getProgressPercent(opt);
        if (target <= 0) {
            bindProgressAnimated(holder, opt, true, 0f, 0f, 0f);
            return;
        }

        if (animationMode == ANIM_MODE_SWEEP) {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(scaleDuration(1400L));
            a.setInterpolator(new LinearInterpolator());
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.RESTART);
            a.addUpdateListener(anim -> {
                if (!isHolderValid(holder, opt)) return;
                float t = 0f;
                Object v = anim.getAnimatedValue();
                if (v instanceof Float) t = (Float) v;
                bindProgressAnimated(holder, opt, true, 1f, t, 1f);
            });
            a.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationCancel(Animator animation) {
                    if (isHolderValid(holder, opt)) bindProgressAnimated(holder, opt, true, 1f, 0f, 0f);
                    if (selectedIndicatorAnimator == animation) selectedIndicatorAnimator = null;
                }
                @Override public void onAnimationEnd(Animator animation) {
                    if (isHolderValid(holder, opt)) bindProgressAnimated(holder, opt, true, 1f, 0f, 0f);
                    if (selectedIndicatorAnimator == animation) selectedIndicatorAnimator = null;
                }
            });
            selectedIndicatorAnimator = a;
            a.start();
            return;
        }

        if (animationMode == ANIM_MODE_TRI_PHASE) {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(scaleDuration(1800L));
            a.setInterpolator(new LinearInterpolator());
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.RESTART);
            a.addUpdateListener(anim -> {
                if (!isHolderValid(holder, opt)) return;
                float phase = 0f;
                Object v = anim.getAnimatedValue();
                if (v instanceof Float) phase = (Float) v;
                float ratio;
                if (phase <= 0.58f) {
                    float t = phase / 0.58f;
                    ratio = 1f - (1f - t) * (1f - t);
                } else if (phase <= 0.78f) {
                    ratio = 1f;
                } else {
                    float t = (phase - 0.78f) / 0.22f;
                    if (t <= 0.45f) ratio = 1f - (0.18f * (t / 0.45f));
                    else {
                        float b = (t - 0.45f) / 0.55f;
                        ratio = 0.82f + (0.08f * (float) Math.sin(b * Math.PI));
                    }
                }
                if (ratio < 0f) ratio = 0f;
                if (ratio > 1f) ratio = 1f;
                bindProgressAnimated(holder, opt, true, ratio, phase, 0f);
            });
            a.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationCancel(Animator animation) {
                    if (isHolderValid(holder, opt)) bindProgressAnimated(holder, opt, true, 1f, 0f, 0f);
                    if (selectedIndicatorAnimator == animation) selectedIndicatorAnimator = null;
                }
                @Override public void onAnimationEnd(Animator animation) {
                    if (isHolderValid(holder, opt)) bindProgressAnimated(holder, opt, true, 1f, 0f, 0f);
                    if (selectedIndicatorAnimator == animation) selectedIndicatorAnimator = null;
                }
            });
            selectedIndicatorAnimator = a;
            a.start();
            return;
        }

        if (animationMode == ANIM_MODE_NEURAL_PULSE) {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(scaleDuration(1500L));
            a.setInterpolator(new LinearInterpolator());
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.RESTART);
            a.addUpdateListener(anim -> {
                if (!isHolderValid(holder, opt)) return;
                float p = 0f;
                Object v = anim.getAnimatedValue();
                if (v instanceof Float) p = (Float) v;
                // Make the pulse visibly different from sweep: bar brightness + slight fill breathing + faster sweep.
                float sine = (float) Math.sin(p * Math.PI * 2f);
                float pulse = 0.70f + 0.30f * ((sine + 1f) * 0.5f);
                float fillRatio = 0.88f + 0.12f * ((sine + 1f) * 0.5f);
                float sweepPhase = (p * 3f) % 1f;
                bindProgressAnimated(holder, opt, true, fillRatio, sweepPhase, pulse);
            });
            a.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationCancel(Animator animation) {
                    if (isHolderValid(holder, opt)) bindProgressAnimated(holder, opt, true, 1f, 0f, 0f);
                    if (selectedIndicatorAnimator == animation) selectedIndicatorAnimator = null;
                }
                @Override public void onAnimationEnd(Animator animation) {
                    if (isHolderValid(holder, opt)) bindProgressAnimated(holder, opt, true, 1f, 0f, 0f);
                    if (selectedIndicatorAnimator == animation) selectedIndicatorAnimator = null;
                }
            });
            selectedIndicatorAnimator = a;
            a.start();
            return;
        }

        // Sequential fill loop (default)
        ValueAnimator a = ValueAnimator.ofInt(0, target);
        long base = Math.max(900L, Math.min(2400L, target * 18L + 700L));
        a.setDuration(scaleDuration(base));
        a.setInterpolator(new DecelerateInterpolator());
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.RESTART);
        a.addUpdateListener(anim -> {
            if (!isHolderValid(holder, opt)) return;
            int lit = 0;
            Object v = anim.getAnimatedValue();
            if (v instanceof Integer) lit = (Integer) v;
            if (lit < 0) lit = 0;
            if (lit > target) lit = target;
            bindProgressAnimated(holder, opt, true, lit / 100f, 0f, 0f);
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationRepeat(Animator animation) {
                if (isHolderValid(holder, opt) && holder.pbIndicator != null) holder.pbIndicator.setProgress(0);
            }
            @Override public void onAnimationCancel(Animator animation) {
                if (isHolderValid(holder, opt)) bindProgressAnimated(holder, opt, true, 1f, 0f, 0f);
                if (selectedIndicatorAnimator == animation) selectedIndicatorAnimator = null;
            }
            @Override public void onAnimationEnd(Animator animation) {
                if (isHolderValid(holder, opt)) bindProgressAnimated(holder, opt, true, 1f, 0f, 0f);
                if (selectedIndicatorAnimator == animation) selectedIndicatorAnimator = null;
            }
        });
        selectedIndicatorAnimator = a;
        a.start();
    }

    public void cancelSelectedIndicatorAnimation() {
        try {
            if (selectedIndicatorAnimator != null) selectedIndicatorAnimator.cancel();
        } catch (Throwable ignored) {}
        selectedIndicatorAnimator = null;
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        attachedRecyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        cancelSelectedIndicatorAnimation();
        attachedRecyclerView = null;
        super.onDetachedFromRecyclerView(recyclerView);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_reasoning_thinking_option, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        ReasoningModelThinkingOption opt = items.get(position);
        boolean selected = position == selectedIndex;

        holder.title.setText(opt.title);
        holder.subtitle.setText(opt.subtitle);
        holder.rb.setChecked(selected);
        applyRadioVisualState(holder.rb);

        bindCostTag(holder.costTag, opt);
        bindRiskHint(holder.riskHint, opt, selected);
        bindProgressAnimated(holder, opt, selected, 1f, 0f, 0f);

        if (selected && animationEnabled) {
            holder.itemView.post(() -> {
                try {
                    if (!animationEnabled) return;
                    if (holder.getBindingAdapterPosition() != selectedIndex) return;
                    RecyclerView rv2 = attachedRecyclerView;
                    if (rv2 == null) return;
                    RecyclerView.ViewHolder current = rv2.findViewHolderForAdapterPosition(selectedIndex);
                    if (!(current instanceof VH) || current != holder || selectedIndicatorAnimator == null) {
                        restartSelectedIndicatorAnimation();
                    }
                } catch (Throwable ignored) {}
            });
        }

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            int old = selectedIndex;
            selectedIndex = pos;
            if (old != selectedIndex) {
                if (old >= 0) notifyItemChanged(old);
                notifyItemChanged(selectedIndex);
            } else {
                notifyItemChanged(selectedIndex);
            }
            RecyclerView rv2 = attachedRecyclerView;
            if (rv2 != null) {
                rv2.post(this::restartSelectedIndicatorAnimation);
            } else {
                restartSelectedIndicatorAnimation();
            }
            if (listener != null) listener.onItemClick(selectedIndex);
        });
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        super.onViewRecycled(holder);
        try {
            if (holder.pbIndicator != null) holder.pbIndicator.setAlpha(1f);
        } catch (Throwable ignored) {}
        hideSweep(holder.sweepLight);
    }

    @Override
    public int getItemCount() {
        return items == null ? 0 : items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final RadioButton rb;
        final TextView title;
        final TextView subtitle;
        final TextView costTag;
        final TextView riskHint;
        final FrameLayout flIndicatorContainer;
        final ProgressBar pbIndicator;
        final View sweepLight;

        VH(@NonNull View itemView) {
            super(itemView);
            rb = itemView.findViewById(R.id.rb_selected);
            title = itemView.findViewById(R.id.tv_title);
            subtitle = itemView.findViewById(R.id.tv_subtitle);
            costTag = itemView.findViewById(R.id.tv_cost_tag);
            riskHint = itemView.findViewById(R.id.tv_risk_hint);
            flIndicatorContainer = itemView.findViewById(R.id.fl_reasoning_indicator_container);
            pbIndicator = itemView.findViewById(R.id.pb_reasoning_indicator);
            sweepLight = itemView.findViewById(R.id.v_reasoning_sweep_light);
        }
    }

    private void applyRadioVisualState(@Nullable RadioButton rb) {
        if (rb == null) return;
        try {
            ColorStateList tint = new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{0xFF5B6DFF, 0xFF98A2AE});
            CompoundButtonCompat.setButtonTintList(rb, tint);
            rb.setAlpha(1f);
        } catch (Throwable ignored) {}
    }

    private void notifySelectedRowOnly() {
        if (selectedIndex >= 0 && selectedIndex < getItemCount()) notifyItemChanged(selectedIndex);
    }

    private boolean isHolderValid(@NonNull VH holder, @NonNull ReasoningModelThinkingOption opt) {
        int pos = holder.getBindingAdapterPosition();
        if (pos == RecyclerView.NO_POSITION) return false;
        if (pos != selectedIndex) return false;
        if (pos < 0 || pos >= items.size()) return false;
        return items.get(pos) == opt;
    }

    private void bindProgressAnimated(@NonNull VH holder,
                                      @NonNull ReasoningModelThinkingOption opt,
                                      boolean selected,
                                      float fillRatio,
                                      float sweepPhase,
                                      float alphaOverride) {
        if (holder.pbIndicator == null) return;
        int targetPercent = getProgressPercent(opt);
        if (targetPercent < 0) targetPercent = 0;
        if (targetPercent > 100) targetPercent = 100;
        int color = getSpectrumColor(opt);
        try {
            holder.pbIndicator.setMax(100);
            holder.pbIndicator.setProgressBackgroundTintList(ColorStateList.valueOf(0xFFCAD4DB));
            holder.pbIndicator.setIndeterminateTintList(ColorStateList.valueOf(color));
            holder.pbIndicator.setProgressTintList(ColorStateList.valueOf(color));
            int p = selected ? Math.round(targetPercent * Math.max(0f, Math.min(1f, fillRatio))) : targetPercent;
            if (p < 0) p = 0;
            if (p > 100) p = 100;
            holder.pbIndicator.setProgress(p);
            holder.pbIndicator.setAlpha(alphaOverride > 0f ? alphaOverride : 1f);
        } catch (Throwable ignored) {}

        if (selected && animationEnabled && (animationMode == ANIM_MODE_SWEEP || animationMode == ANIM_MODE_NEURAL_PULSE)) {
            float sweepAlpha = (animationMode == ANIM_MODE_NEURAL_PULSE && alphaOverride > 0f)
                    ? Math.max(0.70f, Math.min(1f, alphaOverride))
                    : (animationMode == ANIM_MODE_NEURAL_PULSE ? 0.98f : 0.92f);
            applySweep(holder.flIndicatorContainer, holder.pbIndicator, holder.sweepLight, targetPercent, sweepPhase, sweepAlpha);
        } else {
            hideSweep(holder.sweepLight);
        }
    }

    private void applySweep(@Nullable FrameLayout container,
                            @Nullable ProgressBar pb,
                            @Nullable View sweep,
                            int targetPercent,
                            float phase,
                            float alpha) {
        if (container == null || pb == null || sweep == null || targetPercent <= 0) {
            hideSweep(sweep);
            return;
        }
        int cw = container.getWidth();
        if (cw <= 0) cw = pb.getWidth();
        if (cw <= 0) { hideSweep(sweep); return; }
        int sw = sweep.getWidth();
        if (sw <= 0) sw = Math.max(12, Math.round(container.getResources().getDisplayMetrics().density * 18f));
        if (phase < 0f) phase = 0f;
        if (phase > 1f) phase = 1f;
        float activeW = cw * (targetPercent / 100f);
        if (activeW <= 1f) { hideSweep(sweep); return; }
        float startX = -sw;
        float endX = Math.max(0f, activeW);
        float x = startX + ((endX - startX) * phase);
        try {
            sweep.setVisibility(View.VISIBLE);
            sweep.setAlpha(alpha);
            sweep.setTranslationX(x);
            sweep.bringToFront();
        } catch (Throwable ignored) {}
    }

    private void hideSweep(@Nullable View sweep) {
        if (sweep == null) return;
        try { sweep.animate().cancel(); } catch (Throwable ignored) {}
        try {
            sweep.setTranslationX(0f);
            sweep.setAlpha(0f);
            sweep.setVisibility(View.GONE);
        } catch (Throwable ignored) {}
    }

    private void bindRiskHint(@Nullable TextView tv, @NonNull ReasoningModelThinkingOption opt, boolean selected) {
        if (tv == null) return;
        String hint = getRiskHint(opt);
        if (hint == null || hint.isEmpty()) {
            tv.setVisibility(View.GONE);
            return;
        }
        tv.setText(hint);
        tv.setVisibility(View.VISIBLE);
        try {
            tv.setTextColor(Color.parseColor(opt.id == ReasoningModelThinkingOptions.HIGH ? "#D84315" : "#E65100"));
            tv.setAlpha(selected ? 1f : 0.88f);
        } catch (Throwable ignored) {}
    }

    @Nullable
    private String getRiskHint(@NonNull ReasoningModelThinkingOption opt) {
        switch (opt.id) {
            case ReasoningModelThinkingOptions.DIVERGENT:
                return "小风险提示：灵感跳跃·跑题概率↑";
            case ReasoningModelThinkingOptions.HIGH:
                return "高温风险：耗时高 / Token 消耗显著增加";
            case ReasoningModelThinkingOptions.CONVERGENT:
                return "小风险提示：过度收敛可能压制创意";
            default:
                return null;
        }
    }

    private int getProgressPercent(@NonNull ReasoningModelThinkingOption opt) {
        switch (opt.id) {
            case ReasoningModelThinkingOptions.CONVERGENT:
                return 24;
            case ReasoningModelThinkingOptions.LOW:
                return 36;
            case ReasoningModelThinkingOptions.AUTO:
                return 52;
            case ReasoningModelThinkingOptions.MEDIUM:
                return 60;
            case ReasoningModelThinkingOptions.DIVERGENT:
                return 78;
            case ReasoningModelThinkingOptions.HIGH:
                return 90;
            default:
                return 50;
        }
    }

    private int getSpectrumColor(@NonNull ReasoningModelThinkingOption opt) {
        // 4-tier spectrum alignment: blue / green / orange / red
        switch (opt.id) {
            case ReasoningModelThinkingOptions.CONVERGENT:
                return Color.parseColor("#2196F3");
            case ReasoningModelThinkingOptions.LOW:
                return Color.parseColor("#4CAF50");
            case ReasoningModelThinkingOptions.AUTO:
            case ReasoningModelThinkingOptions.MEDIUM:
                return Color.parseColor("#FF9800");
            case ReasoningModelThinkingOptions.DIVERGENT:
            case ReasoningModelThinkingOptions.HIGH:
            default:
                return Color.parseColor("#F44336");
        }
    }

    private void bindCostTag(TextView tag, ReasoningModelThinkingOption opt) {
        if (tag == null || opt == null) return;
        int bg;
        int fg;
        String text;
        switch (opt.id) {
            case ReasoningModelThinkingOptions.DIVERGENT:
                bg = Color.parseColor("#1A9C27B0");
                fg = Color.parseColor("#9C27B0");
                text = "✨ 灵感爆发";
                break;
            case ReasoningModelThinkingOptions.CONVERGENT:
                bg = Color.parseColor("#1A00BCD4");
                fg = Color.parseColor("#0097A6");
                text = "🎯 绝对严谨";
                break;
            case ReasoningModelThinkingOptions.LOW:
                bg = Color.parseColor("#1A4CAF50");
                fg = Color.parseColor("#4CAF50");
                text = "⚡ 极速 / 低耗";
                break;
            case ReasoningModelThinkingOptions.MEDIUM:
                bg = Color.parseColor("#1A607D8B");
                fg = Color.parseColor("#607D8B");
                text = "⚖️ 均衡";
                break;
            case ReasoningModelThinkingOptions.HIGH:
                bg = Color.parseColor("#1AFF9800");
                fg = Color.parseColor("#F57C00");
                text = "⚠️ 高耗时 / 巨量 Token";
                break;
            case ReasoningModelThinkingOptions.AUTO:
            default:
                bg = Color.parseColor("#1A03A9F4");
                fg = Color.parseColor("#03A9F4");
                text = "🤖 动态平衡";
                break;
        }
        tag.setText(text);
        tag.setTextColor(fg);
        tag.setVisibility(View.VISIBLE);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(bg);
        drawable.setCornerRadius(dp(tag, 999));
        tag.setBackground(drawable);
    }

    private long scaleDuration(long baseMs) {
        long base = Math.max(1L, baseMs);
        int p = animationSpeedPercent;
        if (p < 0) p = 0;
        if (p > 100) p = 100;
        float scale = 2.0f - (p / 100f); // 0%=slower, 100%=base
        long out = (long) (base * scale);
        if (out < 700L) out = 700L;
        if (out > 6500L) out = 6500L;
        return out;
    }

    private static int dp(TextView v, float dp) {
        float d = v.getResources().getDisplayMetrics().density;
        return (int) (dp * d + 0.5f);
    }
}
