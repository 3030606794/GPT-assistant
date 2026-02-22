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

public class NormalModelThinkingOptionAdapter extends RecyclerView.Adapter<NormalModelThinkingOptionAdapter.VH> {

    public interface OnItemClickListener {
        void onItemClick(int position);
    }

    private final List<NormalModelThinkingOption> items;
    private int selectedIndex;
    private final OnItemClickListener listener;

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
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_output_length_option, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        NormalModelThinkingOption opt = items.get(position);
        holder.tvTitle.setText(opt.title);
        holder.tvSubtitle.setVisibility(View.VISIBLE);
        holder.tvSubtitle.setText(opt.subtitle);
        holder.rb.setChecked(position == selectedIndex);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(position);
        });
    }

    @Override
    public int getItemCount() {
        return items == null ? 0 : items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final RadioButton rb;
        final TextView tvTitle;
        final TextView tvSubtitle;

        VH(@NonNull View itemView) {
            super(itemView);
            rb = itemView.findViewById(R.id.rb_selected);
            tvTitle = itemView.findViewById(R.id.tv_title);
            tvSubtitle = itemView.findViewById(R.id.tv_subtitle);
        }
    }
}
