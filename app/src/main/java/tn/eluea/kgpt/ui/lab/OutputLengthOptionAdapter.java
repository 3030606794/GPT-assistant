package tn.eluea.kgpt.ui.lab;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.widget.CompoundButtonCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.util.Logger;
import tn.eluea.kgpt.util.TokenTagHelper;

public class OutputLengthOptionAdapter extends RecyclerView.Adapter<OutputLengthOptionAdapter.VH> {

    public interface OnItemClickListener {
        void onItemClick(int position);
    }

    private final List<OutputLengthOption> items;
    private int selectedIndex;
    private final OnItemClickListener listener;

    /** Current custom token value used to render the capsule for the "Custom" row. */
    private int customTokensValue;
    private final int customIndex;

    public OutputLengthOptionAdapter(List<OutputLengthOption> items,
                                    int selectedIndex,
                                    int customTokensValue,
                                    OnItemClickListener listener) {
        this.items = items;
        this.selectedIndex = selectedIndex;
        this.listener = listener;
        this.customTokensValue = customTokensValue;

        int idx = -1;
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                OutputLengthOption o = items.get(i);
                if (o != null && o.isCustom) {
                    idx = i;
                    break;
                }
            }
        }
        this.customIndex = idx;
    }

    public void setSelectedIndex(int idx) {
        int old = selectedIndex;
        selectedIndex = idx;
        if (old >= 0) notifyItemChanged(old);
        if (idx >= 0) notifyItemChanged(idx);
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setCustomTokensValue(int tokens) {
        if (tokens == customTokensValue) return;
        customTokensValue = tokens;
        if (customIndex >= 0) notifyItemChanged(customIndex);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_output_length_option, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        OutputLengthOption opt = items.get(position);
        holder.tvTitle.setText(opt.title);

        if (opt.subtitle == null || opt.subtitle.trim().isEmpty()) {
            holder.tvSubtitle.setVisibility(View.GONE);
        } else {
            holder.tvSubtitle.setVisibility(View.VISIBLE);
            holder.tvSubtitle.setText(opt.subtitle);
        }

        // Capsule (3rd line)
        if (holder.tvCapsule != null) {
            int capsuleTokens = opt.isCustom ? customTokensValue : opt.tokens;
            // When user hasn't entered anything yet, show "⚙️ 自由定义".
            if (opt.isCustom && capsuleTokens <= 0) capsuleTokens = 0;
            TokenTagHelper.applyToCapsule(holder.tvCapsule, capsuleTokens);
        }

        if (holder.tvModelCapNote != null) {
            if (opt.modelCapNote == null || opt.modelCapNote.trim().isEmpty()) {
                holder.tvModelCapNote.setVisibility(View.GONE);
            } else {
                holder.tvModelCapNote.setVisibility(View.VISIBLE);
                holder.tvModelCapNote.setText(opt.modelCapNote);
            }
        }

        final boolean enabled = (opt == null) || opt.enabled;
        holder.rb.setChecked(position == selectedIndex);
        holder.rb.setEnabled(enabled);
        applyRadioVisualState(holder.rb, enabled);
        applyEnabledState(holder, enabled);

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            OutputLengthOption item = (items != null && pos >= 0 && pos < items.size()) ? items.get(pos) : null;
            if (item != null && !item.enabled) {
                try {
                    Logger.log("OUTLEN_UI", "blocked disabled preset click pos=" + pos + ", tokens=" + item.tokens);
                } catch (Throwable ignored) {}
                return;
            }
            if (listener != null) listener.onItemClick(pos);
        });
    }

    private void applyEnabledState(@NonNull VH h, boolean enabled) {
        float alpha = enabled ? 1.0f : 0.42f;
        try {
            h.itemView.setAlpha(alpha);
            h.itemView.setEnabled(enabled);
        } catch (Throwable ignored) {}
        try { h.tvTitle.setEnabled(enabled); } catch (Throwable ignored) {}
        try { h.tvSubtitle.setEnabled(enabled); } catch (Throwable ignored) {}
        try { h.tvCapsule.setEnabled(enabled); } catch (Throwable ignored) {}
        try { if (h.tvModelCapNote != null) h.tvModelCapNote.setEnabled(enabled); } catch (Throwable ignored) {}
    }

    private void applyRadioVisualState(RadioButton rb, boolean enabled) {
        if (rb == null) return;
        try {
            ColorStateList tint = new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{0xFF5B6DFF, enabled ? 0xFF98A2AE : 0xFFBFC6CF});
            CompoundButtonCompat.setButtonTintList(rb, tint);
            rb.setAlpha(enabled ? 1f : 0.7f);
        } catch (Throwable ignored) {}
    }

    @Override
    public int getItemCount() {
        return items == null ? 0 : items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final RadioButton rb;
        final TextView tvTitle;
        final TextView tvSubtitle;
        final TextView tvCapsule;
        final TextView tvModelCapNote;

        VH(@NonNull View itemView) {
            super(itemView);
            rb = itemView.findViewById(R.id.rb_selected);
            tvTitle = itemView.findViewById(R.id.tv_title);
            tvSubtitle = itemView.findViewById(R.id.tv_subtitle);
            tvCapsule = itemView.findViewById(R.id.tv_capsule);
            tvModelCapNote = itemView.findViewById(R.id.tv_model_cap_note);
        }
    }
}
