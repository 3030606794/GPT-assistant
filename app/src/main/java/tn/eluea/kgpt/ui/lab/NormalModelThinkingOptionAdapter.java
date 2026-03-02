package tn.eluea.kgpt.ui.lab;

import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.text.TextUtils;
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
import androidx.core.view.ViewCompat;
import androidx.core.widget.CompoundButtonCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tn.eluea.kgpt.R;

public class NormalModelThinkingOptionAdapter extends RecyclerView.Adapter<NormalModelThinkingOptionAdapter.VH> {

    public static final int ANIM_MODE_SEQ_LOOP = 0;
    public static final int ANIM_MODE_TRI_PHASE = 1;
    public static final int ANIM_MODE_SWEEP = 2;

    public interface OnItemClickListener {
        void onItemClick(int position);
    }

    private final List<NormalModelThinkingOption> items;
    private int selectedIndex;
    private final OnItemClickListener listener;

    private RecyclerView attachedRecyclerView;
    private boolean animationEnabled = true;
    private int animationMode = ANIM_MODE_SEQ_LOOP;
    private ValueAnimator indicatorAnimator;
    private int animatedFillProgress = -1;
    private float sweepPhase = 0f;
    private int animationSpeedPercent = 70;

    public NormalModelThinkingOptionAdapter(List<NormalModelThinkingOption> items, int selectedIndex, OnItemClickListener listener) {
        this.items = items;
        this.selectedIndex = selectedIndex;
        this.listener = listener;
    }

    public void setSelectedIndex(int idx) {
        int old = selectedIndex;
        selectedIndex = idx;
        if (old >= 0) notifyItemChanged(old);
        if (idx >= 0) notifyItemChanged(idx);
        restartSelectedIndicatorAnimation();
    }

    public void setAnimationMode(int mode) {
        if (mode < 0 || mode > 2) mode = ANIM_MODE_SEQ_LOOP;
        if (animationMode == mode) return;
        animationMode = mode;
        restartSelectedIndicatorAnimation();
    }

    public int getAnimationMode() {
        return animationMode;
    }

    public void setAnimationSpeedPercent(int percent) {
        int p = percent;
        if (p < 0) p = 0;
        if (p > 100) p = 100;
        if (animationSpeedPercent == p) return;
        animationSpeedPercent = p;
        if (animationEnabled) {
            restartSelectedIndicatorAnimation();
        }
    }

    public int getAnimationSpeedPercent() {
        return animationSpeedPercent;
    }

    public void setAnimationEnabled(boolean enabled) {
        if (animationEnabled == enabled) return;
        animationEnabled = enabled;
        if (!animationEnabled) {
            cancelSelectedIndicatorAnimation();
            refreshSelectedRow();
        } else {
            restartSelectedIndicatorAnimation();
        }
    }

    public boolean isAnimationEnabled() {
        return animationEnabled;
    }

    public void restartSelectedIndicatorAnimation() {
        cancelSelectedIndicatorAnimation();
        animatedFillProgress = -1;
        sweepPhase = 0f;

        if (!animationEnabled) {
            refreshSelectedRow();
            return;
        }
        if (items == null || selectedIndex < 0 || selectedIndex >= items.size()) return;

        int target = computeTargetProgress(items.get(selectedIndex));
        if (target <= 0) {
            refreshSelectedRow();
            return;
        }

        if (animationMode == ANIM_MODE_SWEEP) {
            final int finalTarget = target;
            animatedFillProgress = finalTarget;
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(scaleDurationBySpeed(1400L));
            animator.setInterpolator(new LinearInterpolator());
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.RESTART);
            animator.addUpdateListener(animation -> {
                Object value = animation.getAnimatedValue();
                float phase = 0f;
                if (value instanceof Float) phase = (Float) value;
                if (phase < 0f) phase = 0f;
                if (phase > 1f) phase = 1f;
                sweepPhase = phase;
                animatedFillProgress = finalTarget;
                applyAnimatedFrameToSelectedHolder();
            });
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationCancel(android.animation.Animator animation) {
                    if (indicatorAnimator == animation) indicatorAnimator = null;
                    hideSweepOnSelectedHolder();
                }

                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (indicatorAnimator == animation) indicatorAnimator = null;
                    hideSweepOnSelectedHolder();
                }
            });
            indicatorAnimator = animator;
            animator.start();
            refreshSelectedRow();
            return;
        }

        if (animationMode == ANIM_MODE_TRI_PHASE) {
            final int finalTarget = target;
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(scaleDurationBySpeed(1800L));
            animator.setInterpolator(new LinearInterpolator());
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.RESTART);
            animator.addUpdateListener(animation -> {
                Object value = animation.getAnimatedValue();
                float phase = 0f;
                if (value instanceof Float) phase = (Float) value;
                if (phase < 0f) phase = 0f;
                if (phase > 1f) phase = 1f;

                float ratio;
                if (phase <= 0.58f) {
                    float t = phase / 0.58f;
                    float eased = 1f - (1f - t) * (1f - t);
                    ratio = eased;
                } else if (phase <= 0.78f) {
                    ratio = 1f;
                } else {
                    float t = (phase - 0.78f) / 0.22f;
                    if (t <= 0.45f) {
                        ratio = 1f - (0.18f * (t / 0.45f));
                    } else {
                        float b = (t - 0.45f) / 0.55f;
                        ratio = 0.82f + (0.08f * (float) Math.sin(b * Math.PI));
                    }
                }
                int lit = Math.round(finalTarget * ratio);
                if (lit < 1) lit = 1;
                if (lit > finalTarget) lit = finalTarget;
                animatedFillProgress = lit;
                applyAnimatedFrameToSelectedHolder();
            });
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(android.animation.Animator animation) {
                    animatedFillProgress = 0;
                    applyAnimatedFrameToSelectedHolder();
                }

                @Override
                public void onAnimationCancel(android.animation.Animator animation) {
                    if (indicatorAnimator == animation) indicatorAnimator = null;
                    hideSweepOnSelectedHolder();
                }

                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (indicatorAnimator == animation) indicatorAnimator = null;
                    hideSweepOnSelectedHolder();
                }
            });
            indicatorAnimator = animator;
            animator.start();
            refreshSelectedRow();
            return;
        }

        final int finalTarget = target;
        ValueAnimator animator = ValueAnimator.ofInt(0, finalTarget);
        long duration = finalTarget * 100L;
        if (duration < 800L) duration = 800L;
        if (duration > 2000L) duration = 2000L;
        animator.setDuration(scaleDurationBySpeed(duration));
        animator.setInterpolator(new DecelerateInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.addUpdateListener(animation -> {
            Object value = animation.getAnimatedValue();
            int lit = 0;
            if (value instanceof Integer) lit = (Integer) value;
            if (lit < 0) lit = 0;
            if (lit > finalTarget) lit = finalTarget;
            animatedFillProgress = lit;
            applyAnimatedFrameToSelectedHolder();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(android.animation.Animator animation) {
                animatedFillProgress = 0;
                applyAnimatedFrameToSelectedHolder();
            }

            @Override
            public void onAnimationRepeat(android.animation.Animator animation) {
                animatedFillProgress = 0;
                applyAnimatedFrameToSelectedHolder();
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                if (indicatorAnimator == animation) indicatorAnimator = null;
                hideSweepOnSelectedHolder();
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (indicatorAnimator == animation) indicatorAnimator = null;
                hideSweepOnSelectedHolder();
            }
        });
        indicatorAnimator = animator;
        animator.start();
        refreshSelectedRow();
    }

    public void cancelSelectedIndicatorAnimation() {
        try {
            if (indicatorAnimator != null) {
                indicatorAnimator.cancel();
            }
        } catch (Throwable ignored) {}
        indicatorAnimator = null;
        hideSweepOnSelectedHolder();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_normal_thinking_option, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        NormalModelThinkingOption opt = items.get(position);
        holder.tvTitle.setText(opt.title);
        holder.tvSubtitle.setVisibility(View.VISIBLE);
        holder.tvSubtitle.setText(opt.subtitle);
        boolean isSelected = position == selectedIndex;
        holder.rb.setChecked(isSelected);
        applyRadioVisualState(holder.rb);

        bindTempIndicator(holder, opt, isSelected);
        bindRiskHint(holder, opt, isSelected);

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            setSelectedIndex(pos);
            if (listener != null) listener.onItemClick(pos);
        });
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        hideSweepLight(holder.sweepLight);
        super.onViewRecycled(holder);
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        attachedRecyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        cancelSelectedIndicatorAnimation();
        if (attachedRecyclerView == recyclerView) attachedRecyclerView = null;
        super.onDetachedFromRecyclerView(recyclerView);
    }

    @Override
    public int getItemCount() {
        return items == null ? 0 : items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final RadioButton rb;
        final TextView tvTitle;
        final TextView tvSubtitle;
        final ProgressBar tempIndicator;
        final FrameLayout tempContainer;
        final View sweepLight;
        final TextView tvRiskHint;

        VH(@NonNull View itemView) {
            super(itemView);
            rb = itemView.findViewById(R.id.rb_selected);
            tvTitle = itemView.findViewById(R.id.tv_title);
            tvSubtitle = itemView.findViewById(R.id.tv_subtitle);
            tempIndicator = itemView.findViewById(R.id.pb_temp_indicator);
            tempContainer = itemView.findViewById(R.id.fl_temp_indicator_container);
            sweepLight = itemView.findViewById(R.id.v_temp_sweep_light);
            tvRiskHint = itemView.findViewById(R.id.tv_risk_hint);
            try { if (tvRiskHint != null) tvRiskHint.setEllipsize(TextUtils.TruncateAt.END); } catch (Throwable ignored) {}
        }
    }

    private void applyRadioVisualState(RadioButton rb) {
        if (rb == null) return;
        try {
            ColorStateList tint = new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{0xFF5B6DFF, 0xFF98A2AE});
            CompoundButtonCompat.setButtonTintList(rb, tint);
            rb.setAlpha(1f);
        } catch (Throwable ignored) {}
    }

    private static final Pattern VALUE_PATTERN = Pattern.compile("([0-9]+\\.[0-9]+)");

    private void bindTempIndicator(@NonNull VH holder, NormalModelThinkingOption opt, boolean selected) {
        if (holder.tempIndicator == null || opt == null) return;

        float v = resolveValue(opt);
        if (v < 0f) v = 0f;
        if (v > 2.0f) v = 2.0f;

        final int color = resolveColor(v);
        int targetProgress = (int) (v * 10f);
        if (targetProgress < 0) targetProgress = 0;
        if (targetProgress > 20) targetProgress = 20;

        int drawProgress = targetProgress;
        boolean useSweep = false;
        if (selected && animationEnabled && indicatorAnimator != null) {
            if (animationMode == ANIM_MODE_SWEEP) {
                useSweep = targetProgress > 0;
                drawProgress = targetProgress;
            } else if (animatedFillProgress >= 0) {
                drawProgress = animatedFillProgress;
                if (drawProgress < 0) drawProgress = 0;
                if (drawProgress > targetProgress) drawProgress = targetProgress;
            }
        }

        try {
            holder.tempIndicator.setMax(20);
            holder.tempIndicator.setProgress(drawProgress);
        } catch (Throwable ignored) {}

        int progressColor = color;
        // Make the full track clearly visible even at very low values (e.g. 0.0 / 0.1).
        int trackColor = selected ? 0xFFBFCAD2 : 0xFFC9D3DB;
        try {
            holder.tempIndicator.setProgressTintList(ColorStateList.valueOf(progressColor));
            holder.tempIndicator.setProgressBackgroundTintList(ColorStateList.valueOf(trackColor));
            holder.tempIndicator.setIndeterminateTintList(ColorStateList.valueOf(progressColor));
            holder.tempIndicator.setAlpha(1f);
        } catch (Throwable ignored) {}
        try {
            ViewCompat.setBackgroundTintList(holder.tempIndicator, ColorStateList.valueOf(trackColor));
        } catch (Throwable ignored) {}

        if (useSweep) {
            applySweepLight(holder, targetProgress);
        } else {
            hideSweepLight(holder.sweepLight);
        }
    }

    private void bindRiskHint(@NonNull VH holder, NormalModelThinkingOption opt, boolean selected) {
        if (holder.tvRiskHint == null || opt == null) return;
        float v = resolveValue(opt);
        if (v < 0f) v = 0f;
        if (v > 2.0f) v = 2.0f;

        if (v < 1.4f) {
            try {
                holder.tvRiskHint.setVisibility(View.GONE);
            } catch (Throwable ignored) {}
            return;
        }

        final boolean severe = v >= 1.7f;
        final String text = severe ? "高温风险：可能失真/乱码" : "小风险提示：高温创意·跑题概率↑";
        final int color = severe ? 0xFFD84315 : 0xFFE65100;
        try {
            holder.tvRiskHint.setVisibility(View.VISIBLE);
            holder.tvRiskHint.setText(text);
            holder.tvRiskHint.setTextColor(color);
            holder.tvRiskHint.setAlpha(selected ? 1f : 0.9f);
        } catch (Throwable ignored) {}
    }

    private void applySweepLight(@NonNull VH holder, int targetProgress) {
        if (holder.sweepLight == null) return;
        if (targetProgress <= 0) {
            hideSweepLight(holder.sweepLight);
            return;
        }
        try {
            holder.sweepLight.setVisibility(View.VISIBLE);
            holder.sweepLight.setAlpha(0.92f);
        } catch (Throwable ignored) {}

        int barWidth = 0;
        try {
            if (holder.tempIndicator != null) barWidth = holder.tempIndicator.getWidth();
            if (barWidth <= 0 && holder.tempContainer != null) barWidth = holder.tempContainer.getWidth();
        } catch (Throwable ignored) {}
        if (barWidth <= 0) {
            holder.itemView.post(() -> {
                try {
                    if (holder.getBindingAdapterPosition() == selectedIndex) {
                        applySweepLight(holder, targetProgress);
                    }
                } catch (Throwable ignored) {}
            });
            return;
        }

        int sweepWidth = 0;
        try {
            sweepWidth = holder.sweepLight.getWidth();
        } catch (Throwable ignored) {}
        if (sweepWidth <= 0) {
            sweepWidth = Math.max(10, Math.round(holder.itemView.getResources().getDisplayMetrics().density * 18f));
            try {
                ViewGroup.LayoutParams lp = holder.sweepLight.getLayoutParams();
                if (lp != null) {
                    lp.width = sweepWidth;
                    holder.sweepLight.setLayoutParams(lp);
                }
            } catch (Throwable ignored) {}
        }

        float activeWidth = (targetProgress / 20f) * (float) barWidth;
        if (activeWidth <= 0f) {
            hideSweepLight(holder.sweepLight);
            return;
        }
        float startX = -sweepWidth;
        float endX = Math.max(0f, activeWidth);
        float x = startX + ((endX - startX) * sweepPhase);
        try {
            holder.sweepLight.setTranslationX(x);
        } catch (Throwable ignored) {}
    }

    private void hideSweepOnSelectedHolder() {
        if (attachedRecyclerView == null || selectedIndex < 0) return;
        try {
            RecyclerView.ViewHolder vh = attachedRecyclerView.findViewHolderForAdapterPosition(selectedIndex);
            if (vh instanceof VH) {
                hideSweepLight(((VH) vh).sweepLight);
            }
        } catch (Throwable ignored) {}
    }

    private void hideSweepLight(View sweepLight) {
        if (sweepLight == null) return;
        try {
            sweepLight.animate().cancel();
        } catch (Throwable ignored) {}
        try {
            sweepLight.setTranslationX(0f);
            sweepLight.setAlpha(0f);
            sweepLight.setVisibility(View.GONE);
        } catch (Throwable ignored) {}
    }

    private void applyAnimatedFrameToSelectedHolder() {
        if (attachedRecyclerView == null) return;
        if (items == null || selectedIndex < 0 || selectedIndex >= items.size()) return;
        try {
            RecyclerView.ViewHolder vh = attachedRecyclerView.findViewHolderForAdapterPosition(selectedIndex);
            if (vh instanceof VH) {
                bindTempIndicator((VH) vh, items.get(selectedIndex), true);
            }
        } catch (Throwable ignored) {}
    }

    private void refreshSelectedRow() {
        if (selectedIndex >= 0) {
            try {
                notifyItemChanged(selectedIndex);
            } catch (Throwable ignored) {}
        }
    }

    private long scaleDurationBySpeed(long baseDurationMs) {
        long base = baseDurationMs;
        if (base < 1L) base = 1L;
        int p = animationSpeedPercent;
        if (p < 0) p = 0;
        if (p > 100) p = 100;
        // 100% = base speed; 0% = slower breathing (~2x duration)
        float scale = 2.0f - (p / 100f);
        long out = (long) (base * scale);
        if (out < 300L) out = 300L;
        if (out > 5000L) out = 5000L;
        return out;
    }

    private float resolveValue(NormalModelThinkingOption opt) {
        float v = opt.value;
        try {
            if (opt.title != null) {
                Matcher m = VALUE_PATTERN.matcher(opt.title);
                if (m.find()) {
                    v = Float.parseFloat(m.group(1));
                }
            }
        } catch (Throwable ignored) {}
        return v;
    }

    private int computeTargetProgress(NormalModelThinkingOption opt) {
        float v = resolveValue(opt);
        if (v < 0f) v = 0f;
        if (v > 2.0f) v = 2.0f;
        int progress = (int) (v * 10f);
        if (progress < 0) progress = 0;
        if (progress > 20) progress = 20;
        return progress;
    }

    private int resolveColor(float v) {
        if (v <= 0.4f) {
            return 0xFF2196F3;
        } else if (v <= 0.9f) {
            return 0xFF4CAF50;
        } else if (v <= 1.4f) {
            return 0xFFFF9800;
        } else {
            return 0xFFF44336;
        }
    }
}
