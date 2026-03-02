/*
 * Copyright (C) 2025 Amr Aldeeb @Eluea
 *
 * This file is part of KGPT.
 * Licensed under the GPLv3.
 */
package tn.eluea.kgpt.ui.common;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.llm.LanguageModel;
import tn.eluea.kgpt.ui.main.BottomSheetHelper;
import tn.eluea.kgpt.ui.main.FloatingBottomSheet;

/**
 * "Why" panel: explain how effective max_tokens is determined.
 *
 * This is intentionally lightweight: it reads existing caches (learned cap / hard cap)
 * and the last recorded decision snapshot written by controllers.
 */
public final class MaxTokensWhySheet {

    private MaxTokensWhySheet() {}

    public static void show(Context context, @Nullable LanguageModel provider, @Nullable String subModel, @Nullable String entryTag) {
        if (context == null) return;
        try {
            if (!SPManager.isReady()) {
                // Best-effort init in case caller is from a context where SPManager wasn't initialized yet.
                SPManager.init(context.getApplicationContext());
            }
        } catch (Throwable ignored) {}

        SPManager sp;
        try { sp = SPManager.getInstance(); }
        catch (Throwable t) { return; }

        LanguageModel p = provider;
        if (p == null) {
            try { p = sp.getLanguageModel(); } catch (Throwable ignored) {}
        }
        if (p == null) return;

        String m = subModel;
        if (TextUtils.isEmpty(m)) {
            try { m = sp.getSubModel(p); } catch (Throwable ignored) {}
        }
        if (TextUtils.isEmpty(m)) m = "-";

        final FloatingBottomSheet sheet = BottomSheetHelper.showFloating(context, R.layout.bottom_sheet_why_max_tokens);
        final View content = sheet.getContentView();
        if (content == null) return;
        try { BottomSheetHelper.applyTheme(context, content); } catch (Throwable ignored) {}

        TextView tvTitle = content.findViewById(R.id.tv_why_title);
        TextView tvSubtitle = content.findViewById(R.id.tv_why_subtitle);

        TextView tvUserValue = content.findViewById(R.id.tv_user_value);
        TextView tvUserMeta = content.findViewById(R.id.tv_user_meta);
        TextView tvLearnedValue = content.findViewById(R.id.tv_learned_value);
        TextView tvLearnedMeta = content.findViewById(R.id.tv_learned_meta);
        TextView tvHardValue = content.findViewById(R.id.tv_hard_value);
        TextView tvHardMeta = content.findViewById(R.id.tv_hard_meta);
        TextView tvEffValue = content.findViewById(R.id.tv_effective_value);
        TextView tvEffMeta = content.findViewById(R.id.tv_effective_meta);
        TextView tvSyncValue = content.findViewById(R.id.tv_sync_value);
        TextView tvSyncMeta = content.findViewById(R.id.tv_sync_meta);

        MaterialButton btnCopy = content.findViewById(R.id.btn_copy);
        MaterialButton btnClose = content.findViewById(R.id.btn_close);
        MaterialButton btnManageHard = content.findViewById(R.id.btn_manage_hard_cap);

        if (tvTitle != null) tvTitle.setText(R.string.ui_why_max_tokens_title);
        if (tvSubtitle != null) {
            String providerLabel = (p.label != null && !p.label.isEmpty()) ? p.label : p.name();
            tvSubtitle.setText(providerLabel + " · " + m);
        }

        // Current user selection
        int userSel = 0;
        boolean userCustom = false;
        int selSource = SPManager.MAX_TOKENS_SELECTION_SOURCE_AUTO_MAPPED;
        try { userSel = sp.getMaxTokensLimit(); } catch (Throwable ignored) {}
        try { userCustom = sp.getMaxTokensIsCustom(); } catch (Throwable ignored) {}
        try { selSource = sp.getMaxTokensSelectionSource(); } catch (Throwable ignored) {}

        // Cached caps
        Integer learned = null;
        Integer learnedLb = null;
        String learnedSrc = null;
        long learnedAt = 0L;
        Integer hard = null;
        String hardSrc = null;
        long hardAt = 0L;
        try { learned = sp.getCachedSafeMaxTokens(p, m); } catch (Throwable ignored) {}
        try { learnedLb = sp.getCachedSafeMaxTokensLowerBound(p, m); } catch (Throwable ignored) {}
        try { learnedSrc = sp.getCachedSafeMaxTokensSource(p, m); } catch (Throwable ignored) {}
        try { learnedAt = sp.getCachedSafeMaxTokensUpdatedAt(p, m); } catch (Throwable ignored) {}
        try { hard = sp.getCachedHardMaxTokens(p, m); } catch (Throwable ignored) {}
        try { hardSrc = sp.getCachedHardMaxTokensSource(p, m); } catch (Throwable ignored) {}
        try { hardAt = sp.getCachedHardMaxTokensUpdatedAt(p, m); } catch (Throwable ignored) {}

        boolean protectManual = false;
        boolean hasManual = false;
        try { protectManual = sp.isOutputCapManualProtectEnabled(p, m); } catch (Throwable ignored) {}
        try { hasManual = sp.hasManualOutputCapResult(p, m); } catch (Throwable ignored) {}

        // Last decision snapshot
        SPManager.MaxTokensDecisionSnapshot last = null;
        try { last = sp.getLastMaxTokensDecision(p, m); } catch (Throwable ignored) {}

        int lastReq = last != null ? last.requested : 0;
        int lastEff = last != null ? last.effective : 0;
        String lastReason = last != null ? last.reason : null;
        boolean lastSynced = last != null && last.synced;
        int lastSyncedTo = last != null ? last.syncedTo : 0;
        long lastAt = last != null ? last.atMs : 0L;

        // Compute a fallback effective estimate if there is no last record.
        int estEff = userSel;
        if (estEff <= 0) estEff = lastReq > 0 ? lastReq : 0;
        if (learned != null && learned > 0 && estEff > learned) estEff = learned;
        if (hard != null && hard > 0 && estEff > hard) estEff = hard;

        // === Fill UI ===
        // User preset row
        if (tvUserValue != null) tvUserValue.setText(userSel > 0 ? String.valueOf(userSel) : "-");
        if (tvUserMeta != null) {
            String mode = (selSource == SPManager.MAX_TOKENS_SELECTION_SOURCE_USER_FIXED)
                    ? context.getString(R.string.ui_output_length_mode_user_fixed)
                    : context.getString(R.string.ui_output_length_mode_auto_mapped);
            String custom = userCustom ? context.getString(R.string.ui_custom) : context.getString(R.string.ui_fixed);
            String meta = mode + " · " + custom;
            if (lastReq > 0 && userSel > 0 && lastReq != userSel) {
                meta += " · " + context.getString(R.string.ui_why_request_used, lastReq);
            }
            tvUserMeta.setText(meta);
        }

        // Learned cap row
        String learnedValue;
        if (learned != null && learned > 0) learnedValue = String.valueOf(learned);
        else if (learnedLb != null && learnedLb > 0) learnedValue = "≥" + learnedLb;
        else learnedValue = "-";
        if (tvLearnedValue != null) tvLearnedValue.setText(learnedValue);
        if (tvLearnedMeta != null) {
            String src = mapLearnedSource(context, learnedSrc);
            String time = learnedAt > 0 ? formatAgo(learnedAt) : "";
            String meta = src;
            if (!TextUtils.isEmpty(time)) meta += " · " + time;
            if (protectManual && hasManual) meta += " · " + context.getString(R.string.ui_why_manual_protected);
            tvLearnedMeta.setText(meta);
        }

        // Hard cap row
        if (tvHardValue != null) tvHardValue.setText(hard != null && hard > 0 ? String.valueOf(hard) : "-");
        if (tvHardMeta != null) {
            String src = mapHardSource(context, p, hardSrc);
            String time = hardAt > 0 ? formatAgo(hardAt) : "";
            String meta = src;
            if (!TextUtils.isEmpty(time)) meta += " · " + time;
            tvHardMeta.setText(meta);
        }

        
        // Manage hard cap button (only visible when hard cap exists)
        if (btnManageHard != null) {
            if (hard != null && hard > 0) {
                btnManageHard.setVisibility(View.VISIBLE);
                final LanguageModel _p = p;
                final String _m = m;
                btnManageHard.setOnClickListener(v -> {
                    try {
                        HardCapManagerSheet.show(context, _p, _m);
                    } catch (Throwable ignored) {}
                });
            } else {
                btnManageHard.setVisibility(View.GONE);
            }
        }

// Effective row
        int shownEff = (lastEff > 0 ? lastEff : estEff);
        if (tvEffValue != null) tvEffValue.setText(shownEff > 0 ? String.valueOf(shownEff) : "-");
        if (tvEffMeta != null) {
            if (last != null && lastAt > 0) {
                String meta = context.getString(R.string.ui_why_last_event_at, formatAgo(lastAt));
                if (lastReq > 0 && lastEff > 0) meta += " · " + lastReq + " → " + lastEff;
                if (!TextUtils.isEmpty(lastReason)) meta += " · " + context.getString(R.string.ui_why_reason, lastReason);
                tvEffMeta.setText(meta);
            } else {
                tvEffMeta.setText(R.string.ui_why_no_last_record);
            }
        }

        // Synced row
        if (tvSyncValue != null) {
            if (last != null) {
                if (lastSynced) {
                    tvSyncValue.setText(context.getString(R.string.ui_yes) + (lastSyncedTo > 0 ? " (" + lastSyncedTo + ")" : ""));
                } else {
                    tvSyncValue.setText(R.string.ui_no);
                }
            } else {
                tvSyncValue.setText("-");
            }
        }
        if (tvSyncMeta != null) {
            if (last != null && lastAt > 0) {
                tvSyncMeta.setText(context.getString(R.string.ui_why_synced_note));
            } else {
                tvSyncMeta.setText("");
            }
        }

        final String copyText = buildCopyText(context, p, m,
                userSel, userCustom, selSource,
                learned, learnedLb, learnedSrc, learnedAt,
                hard, hardSrc, hardAt,
                lastReq, lastEff, lastReason, lastSynced, lastSyncedTo, lastAt,
                entryTag);

        if (btnCopy != null) {
            btnCopy.setOnClickListener(v -> {
                try {
                    ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("max_tokens", copyText));
                        Toast.makeText(context, context.getString(R.string.msg_copied), Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable ignored) {}
            });
        }
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> {
                try { sheet.dismiss(); } catch (Throwable ignored) {}
            });
        }

        try { sheet.show(); } catch (Throwable ignored) {}
    }

    private static String formatAgo(long atMs) {
        try {
            CharSequence cs = DateUtils.getRelativeTimeSpanString(atMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
            return cs == null ? "" : cs.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String mapLearnedSource(Context ctx, @Nullable String src) {
        if (ctx == null) return "-";
        if (src == null) return ctx.getString(R.string.ui_unknown);
        String s = src.trim();
        if (s.isEmpty()) return ctx.getString(R.string.ui_unknown);
        if (s.startsWith("manual_precise")) return ctx.getString(R.string.ui_why_src_manual_precise);
        if (s.startsWith("manual_conservative")) return ctx.getString(R.string.ui_why_src_manual_conservative);
        if (s.startsWith("auto_retry")) return ctx.getString(R.string.ui_why_src_auto_retry);
        if (s.startsWith("manual_probe")) return ctx.getString(R.string.ui_why_src_manual_probe);
        return s;
    }

    private static String mapHardSource(Context ctx, LanguageModel provider, @Nullable String src) {
        if (ctx == null) return "-";
        String providerLabel = (provider != null && provider.label != null && !provider.label.isEmpty()) ? provider.label : (provider == null ? "API" : provider.name());
        if (src == null) return providerLabel + " " + ctx.getString(R.string.ui_why_src_error_parse);
        String s = src.trim();
        if (s.isEmpty()) return providerLabel + " " + ctx.getString(R.string.ui_why_src_error_parse);
        if (s.startsWith("api_error")) return providerLabel + " " + ctx.getString(R.string.ui_why_src_error_parse);
        return s;
    }

    private static String buildCopyText(Context ctx,
                                        LanguageModel provider,
                                        String subModel,
                                        int userSel,
                                        boolean userCustom,
                                        int selSource,
                                        @Nullable Integer learned,
                                        @Nullable Integer learnedLb,
                                        @Nullable String learnedSrc,
                                        long learnedAt,
                                        @Nullable Integer hard,
                                        @Nullable String hardSrc,
                                        long hardAt,
                                        int lastReq,
                                        int lastEff,
                                        @Nullable String lastReason,
                                        boolean lastSynced,
                                        int lastSyncedTo,
                                        long lastAt,
                                        @Nullable String entryTag) {
        String providerLabel = (provider != null && provider.label != null && !provider.label.isEmpty()) ? provider.label : (provider == null ? "-" : provider.name());
        StringBuilder sb = new StringBuilder();
        sb.append(ctx.getString(R.string.ui_why_max_tokens_title)).append("\n");
        sb.append(providerLabel).append(" · ").append(subModel).append("\n\n");

        String mode = (selSource == SPManager.MAX_TOKENS_SELECTION_SOURCE_USER_FIXED)
                ? ctx.getString(R.string.ui_output_length_mode_user_fixed)
                : ctx.getString(R.string.ui_output_length_mode_auto_mapped);
        String custom = userCustom ? ctx.getString(R.string.ui_custom) : ctx.getString(R.string.ui_fixed);
        sb.append(ctx.getString(R.string.ui_why_user_slot)).append(": ")
                .append(userSel > 0 ? userSel : "-")
                .append(" (").append(mode).append(", ").append(custom).append(")");
        if (lastReq > 0 && userSel > 0 && lastReq != userSel) {
            sb.append(" · ").append(ctx.getString(R.string.ui_why_request_used, lastReq));
        }
        sb.append("\n");

        String learnedV = (learned != null && learned > 0) ? String.valueOf(learned)
                : ((learnedLb != null && learnedLb > 0) ? ("≥" + learnedLb) : "-");
        sb.append(ctx.getString(R.string.ui_why_learned_cap)).append(": ").append(learnedV);
        String lsrc = mapLearnedSource(ctx, learnedSrc);
        if (!TextUtils.isEmpty(lsrc)) sb.append(" (").append(lsrc).append(")");
        if (learnedAt > 0) sb.append(" · ").append(formatAgo(learnedAt));
        sb.append("\n");

        sb.append(ctx.getString(R.string.ui_why_hard_cap)).append(": ").append(hard != null && hard > 0 ? hard : "-");
        String hsrc = mapHardSource(ctx, provider, hardSrc);
        if (!TextUtils.isEmpty(hsrc)) sb.append(" (").append(hsrc).append(")");
        if (hardAt > 0) sb.append(" · ").append(formatAgo(hardAt));
        sb.append("\n");

        sb.append(ctx.getString(R.string.ui_why_effective)).append(": ").append(lastEff > 0 ? lastEff : "-");
        if (lastReq > 0 && lastEff > 0) sb.append(" · ").append(lastReq).append(" → ").append(lastEff);
        if (!TextUtils.isEmpty(lastReason)) sb.append(" · ").append(ctx.getString(R.string.ui_why_reason, lastReason));
        if (lastAt > 0) sb.append(" · ").append(ctx.getString(R.string.ui_why_last_event_at, formatAgo(lastAt)));
        sb.append("\n");

        sb.append(ctx.getString(R.string.ui_why_synced_preset)).append(": ");
        if (lastAt <= 0) sb.append("-");
        else if (lastSynced) sb.append(ctx.getString(R.string.ui_yes)).append(lastSyncedTo > 0 ? (" (" + lastSyncedTo + ")") : "");
        else sb.append(ctx.getString(R.string.ui_no));
        sb.append("\n");

        if (!TextUtils.isEmpty(entryTag)) {
            sb.append("\n").append("entry=").append(entryTag);
        }
        return sb.toString();
    }
}
