/*
 * Copyright (C) 2025 Amr Aldeeb @Eluea
 *
 * This file is part of KGPT.
 * Licensed under the GPLv3.
 */
package tn.eluea.kgpt.ui.common;

import android.content.Context;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.Locale;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.llm.LanguageModel;
import tn.eluea.kgpt.ui.main.BottomSheetHelper;
import tn.eluea.kgpt.ui.main.FloatingBottomSheet;

/**
 * Hard cap management panel:
 * - lists provider+subModel hard cap entries
 * - shows source / updated time / last trigger reason / hit count
 * - allows clearing one or all
 * - allows toggling hard-cap clamp toast
 */
public final class HardCapManagerSheet {

    private HardCapManagerSheet() {}

    public static void show(Context context, @Nullable LanguageModel filterProvider, @Nullable String filterSubModel) {
        if (context == null) return;
        try {
            if (!SPManager.isReady()) {
                SPManager.init(context.getApplicationContext());
            }
        } catch (Throwable ignored) {}

        final SPManager sp;
        try { sp = SPManager.getInstance(); }
        catch (Throwable t) { return; }

        final FloatingBottomSheet sheet = BottomSheetHelper.showFloating(context, R.layout.bottom_sheet_hard_cap_manager);
        final View content = sheet.getContentView();
        if (content == null) return;
        try { BottomSheetHelper.applyTheme(context, content); } catch (Throwable ignored) {}

        SwitchMaterial swToast = content.findViewById(R.id.switch_hardcap_toast);
        MaterialButton btnClearAll = content.findViewById(R.id.btn_hardcap_clear_all);
        MaterialButton btnClose = content.findViewById(R.id.btn_close);
        RecyclerView rv = content.findViewById(R.id.rv_hardcap_list);
        TextView tvEmpty = content.findViewById(R.id.tv_hardcap_empty);

        try {
            if (swToast != null) {
                swToast.setChecked(sp.getHardCapToastEnabled());
                swToast.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    try { sp.setHardCapToastEnabled(isChecked); } catch (Throwable ignored) {}
                });
            }
        } catch (Throwable ignored) {}

        final ArrayList<SPManager.HardCapEntry> all = new ArrayList<>();
        try {
            ArrayList<SPManager.HardCapEntry> tmp = sp.getAllCachedHardMaxTokensEntries();
            if (tmp != null) all.addAll(tmp);
        } catch (Throwable ignored) {}

        final ArrayList<SPManager.HardCapEntry> list = new ArrayList<>();
        for (SPManager.HardCapEntry e : all) {
            if (e == null || e.provider == null || TextUtils.isEmpty(e.subModel) || e.hardCap == null) continue;
            if (filterProvider != null && e.provider != filterProvider) continue;
            if (filterSubModel != null && !filterSubModel.equals(e.subModel)) continue;
            list.add(e);
        }

        if (tvEmpty != null) tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);

        final HardCapAdapter adapter = new HardCapAdapter(context, sp, list, () -> {
            if (tvEmpty != null) tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        });

        if (rv != null) {
            rv.setLayoutManager(new LinearLayoutManager(context));
            rv.setAdapter(adapter);
        }

        if (btnClearAll != null) {
            btnClearAll.setOnClickListener(v -> {
                try { sp.clearAllCachedHardMaxTokens(); } catch (Throwable ignored) {}
                list.clear();
                try { adapter.notifyDataSetChanged(); } catch (Throwable ignored) {}
                if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
            });
        }

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                try { sheet.dismiss(); } catch (Throwable ignored) {}
            });
        }

        // IMPORTANT: FloatingBottomSheet is a Dialog; it must be shown explicitly.
        try { sheet.show(); } catch (Throwable ignored) {}
    }

    public static void show(Context context) {
        show(context, null, null);
    }

    private interface OnListChanged {
        void onChanged();
    }

    private static final class HardCapAdapter extends RecyclerView.Adapter<HardCapAdapter.VH> {
        private final Context ctx;
        private final SPManager sp;
        private final ArrayList<SPManager.HardCapEntry> items;
        private final OnListChanged onListChanged;

        HardCapAdapter(Context ctx, SPManager sp, ArrayList<SPManager.HardCapEntry> items, OnListChanged onListChanged) {
            this.ctx = ctx;
            this.sp = sp;
            this.items = items;
            this.onListChanged = onListChanged;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = android.view.LayoutInflater.from(parent.getContext()).inflate(R.layout.item_hard_cap_entry, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            SPManager.HardCapEntry e = items.get(position);
            LanguageModel p = e.provider;
            String model = e.subModel;
            Integer cap = e.hardCap;

            if (h.tvModel != null) h.tvModel.setText(model == null ? "-" : model);
            if (h.tvProvider != null) {
                String pl = (p != null && p.label != null && !p.label.isEmpty()) ? p.label : (p == null ? "-" : p.name());
                h.tvProvider.setText(pl);
            }
            if (h.tvCap != null) h.tvCap.setText(cap == null ? "-" : String.valueOf(cap));

            String src = mapSource(ctx, p, e.source);
            long timeBase = e.lastHitAtMs > 0 ? e.lastHitAtMs : e.updatedAtMs;
            String time = timeBase > 0 ? DateUtils.getRelativeTimeSpanString(timeBase, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() : "";
            int hits = e.hitCount;
            String meta = src;
            if (!TextUtils.isEmpty(time)) meta += " · " + time;
            if (hits > 0) meta += " · " + ctx.getString(R.string.ui_hard_cap_hits, hits);
            if (h.tvMeta != null) h.tvMeta.setText(meta);

            String reason = e.lastReason;
            if (h.tvReason != null) {
                if (TextUtils.isEmpty(reason)) {
                    h.tvReason.setText(ctx.getString(R.string.ui_hard_cap_last_reason_none));
                } else {
                    h.tvReason.setText(ctx.getString(R.string.ui_hard_cap_last_reason_prefix, reason));
                }
            }

            if (h.btnClear != null) {
                h.btnClear.setOnClickListener(v -> {
                    try {
                        if (p != null && !TextUtils.isEmpty(model)) {
                            sp.clearCachedHardMaxTokens(p, model);
                        }
                    } catch (Throwable ignored) {}
                    int pos = h.getBindingAdapterPosition();
                    if (pos >= 0 && pos < items.size()) {
                        items.remove(pos);
                        notifyItemRemoved(pos);
                        if (onListChanged != null) onListChanged.onChanged();
                    }
                });
            }
        }

        @Override
        public int getItemCount() {
            return items == null ? 0 : items.size();
        }

        private static String mapSource(Context ctx, @Nullable LanguageModel p, @Nullable String src) {
            if (TextUtils.isEmpty(src)) return ctx.getString(R.string.ui_hard_cap_source_unknown);
            String s = src.trim();
            if (s.startsWith("api_error")) return ctx.getString(R.string.ui_hard_cap_source_error_parse);
            if (s.contains("api_constraint")) return ctx.getString(R.string.ui_hard_cap_source_api_constraint);
            // fallback
            return s;
        }

        static final class VH extends RecyclerView.ViewHolder {
            TextView tvModel;
            TextView tvProvider;
            TextView tvCap;
            TextView tvMeta;
            TextView tvReason;
            MaterialButton btnClear;

            VH(@NonNull View itemView) {
                super(itemView);
                tvModel = itemView.findViewById(R.id.tv_model);
                tvProvider = itemView.findViewById(R.id.tv_provider);
                tvCap = itemView.findViewById(R.id.tv_cap);
                tvMeta = itemView.findViewById(R.id.tv_meta);
                tvReason = itemView.findViewById(R.id.tv_reason);
                btnClear = itemView.findViewById(R.id.btn_clear);
            }
        }
    }
}
