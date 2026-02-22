package tn.eluea.kgpt.ui.lab;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import tn.eluea.kgpt.R;

/**
 * Adapter for "推理模型思考" options.
 *
 * UI requirement (2026-02): two-line display (title + subtitle). No third-line hint.
 */
public class ReasoningModelThinkingOptionAdapter extends RecyclerView.Adapter<ReasoningModelThinkingOptionAdapter.VH> {

    public interface OnItemClickListener {
        void onItemClick(int position);
    }

    private final List<ReasoningModelThinkingOption> items;
    private final OnItemClickListener listener;
    private int selectedIndex;

    public ReasoningModelThinkingOptionAdapter(List<ReasoningModelThinkingOption> items, int selectedIndex, OnItemClickListener listener) {
        this.items = items;
        this.selectedIndex = selectedIndex;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Reuse the standard two-line option item layout.
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_output_length_option, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        ReasoningModelThinkingOption opt = items.get(position);
        holder.title.setText(opt.title);
        holder.subtitle.setText(opt.subtitle);
        holder.rb.setChecked(position == selectedIndex);

        holder.itemView.setOnClickListener(v -> {
            int old = selectedIndex;
            selectedIndex = holder.getBindingAdapterPosition();
            if (old != selectedIndex) {
                notifyItemChanged(old);
                notifyItemChanged(selectedIndex);
            } else {
                notifyItemChanged(selectedIndex);
            }
            if (listener != null) listener.onItemClick(selectedIndex);
        });
    }

    @Override
    public int getItemCount() {
        return items == null ? 0 : items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final RadioButton rb;
        final TextView title;
        final TextView subtitle;

        VH(@NonNull View itemView) {
            super(itemView);
            rb = itemView.findViewById(R.id.rb_selected);
            title = itemView.findViewById(R.id.tv_title);
            subtitle = itemView.findViewById(R.id.tv_subtitle);
        }
    }
}
