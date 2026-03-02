package tn.eluea.kgpt.ui.lab;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.llm.LanguageModel;
import tn.eluea.kgpt.util.ProviderModelsFetcher;

/**
 * 一级弹窗：供应商状态列表（Provider List Dialog）
 * - 单选供应商
 * - 未配置时拦截弹出二级快速配置
 */
public class ProviderListDialogFragment extends DialogFragment {

    public interface Callback {
        void onProviderConfirmed(@Nullable LanguageModel provider);
    }

    public static final String TAG = "ProviderListDialog";

    private ProviderAdapter adapter;
    @Nullable private LanguageModel selected;
    private boolean selectedIsNotSet = false;

    public static ProviderListDialogFragment newInstance() {
        return new ProviderListDialogFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Listen for quick-config results and refresh list in-place.
        try {
            getParentFragmentManager().setFragmentResultListener(
                    QuickProviderConfigDialogFragment.RESULT_KEY,
                    this,
                    (requestKey, result) -> {
                        if (adapter != null) adapter.notifyDataSetChanged();

                        // Auto-select the provider only after a successful "Fetch Models".
                        try {
                            String action = result.getString(QuickProviderConfigDialogFragment.EXTRA_ACTION, null);
                            boolean shouldAutoSelect = QuickProviderConfigDialogFragment.ACTION_FETCHED.equals(action)
                                    || result.containsKey(QuickProviderConfigDialogFragment.EXTRA_COUNT);

                            if (shouldAutoSelect) {
                                String name = result.getString(QuickProviderConfigDialogFragment.EXTRA_PROVIDER, null);
                                if (name == null) {
                                    // Backward-compat: older builds used "arg_provider".
                                    name = result.getString("arg_provider", null);
                                }
                                if (name != null) {
                                    LanguageModel p = LanguageModel.valueOf(name);
                                    selected = p;
                                    selectedIsNotSet = false;
                                    if (adapter != null) adapter.setSelected(p, false);
                                }
                            }
                        } catch (Throwable ignored) {
                        }

}
            );
        } catch (Throwable ignored) {
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final Context ctx = requireContext();
        final View root = LayoutInflater.from(ctx).inflate(R.layout.dialog_provider_list, null);

        final RecyclerView rv = root.findViewById(R.id.rv_providers);
        rv.setLayoutManager(new LinearLayoutManager(ctx));

        selected = null;
        selectedIsNotSet = true;
        try {
            if (SPManager.isReady() && SPManager.getInstance().hasLanguageModel()) {
                selected = SPManager.getInstance().getLanguageModel();
                selectedIsNotSet = false;
            }
        } catch (Throwable ignored) {
        }

        adapter = new ProviderAdapter(buildProviderList(), selected, selectedIsNotSet, new ProviderAdapter.Listener() {
            @Override
            public void onClickNotSet() {
                selected = null;
                selectedIsNotSet = true;
                if (adapter != null) adapter.setSelected(null, true);
            }

            @Override
            public void onClickProvider(@NonNull LanguageModel provider) {
                if (!isAdded()) return;
                if (!SPManager.isReady()) return;

                SPManager sp = SPManager.getInstance();
                boolean hasKey = !TextUtils.isEmpty(sp.getApiKey(provider));
                boolean hasUrl = !TextUtils.isEmpty(sp.getBaseUrl(provider));

                // 拦截判断：缺 key 或缺 URL -> 弹二级快速配置，不选中
                if (!hasKey || !hasUrl) {
                    QuickProviderConfigDialogFragment.newInstance(provider.name())
                            .show(getParentFragmentManager(), QuickProviderConfigDialogFragment.TAG);
                    return;
                }

                selected = provider;
                selectedIsNotSet = false;
                if (adapter != null) adapter.setSelected(provider, false);
            }

            @Override
            public void onEdit(@NonNull LanguageModel provider) {
                if (!isAdded()) return;
                QuickProviderConfigDialogFragment.newInstance(provider.name())
                        .show(getParentFragmentManager(), QuickProviderConfigDialogFragment.TAG);
            }

            @Override
            public void onRefresh(@NonNull LanguageModel provider, @Nullable ImageView refreshView) {
                if (!isAdded()) return;
                refreshProviderModelCache(provider, refreshView);
            }

            @Override
            public void onDelete(@NonNull LanguageModel provider) {
                if (!isAdded()) return;
                confirmDeleteProviderConfig(provider);
            }
        });

        rv.setAdapter(adapter);

        final MaterialButton btnCancel = root.findViewById(R.id.btn_cancel);
        final MaterialButton btnOk = root.findViewById(R.id.btn_ok);

        final Dialog dialog = new MaterialAlertDialogBuilder(ctx)
                .setView(root)
                .create();

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dismissAllowingStateLoss());
        }

        if (btnOk != null) {
            btnOk.setOnClickListener(v -> {
                Callback cb = getCallback();
                if (cb != null) {
                    cb.onProviderConfirmed(selectedIsNotSet ? null : selected);
                }
                dismissAllowingStateLoss();
            });
        }

        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        try {
            Dialog d = getDialog();
            if (d != null && d.getWindow() != null) {
                // Make dialog span full screen width and keep a consistent height with the Sub-model picker.
                int screenH = requireContext().getResources().getDisplayMetrics().heightPixels;
                int targetH = (int) (screenH * 0.78f);
                d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, targetH);
                // Remove default dialog insets/padding.
                View decor = d.getWindow().getDecorView();
                if (decor != null) decor.setPadding(0, 0, 0, 0);
                // Use transparent window background; the root view provides its own rounded background.
                d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }
        } catch (Throwable ignored) {
        }
    }

    @Nullable
    private Callback getCallback() {
        Fragment p = getParentFragment();
        if (p instanceof Callback) return (Callback) p;
        if (getActivity() instanceof Callback) return (Callback) getActivity();
        return null;
    }

    private List<LanguageModel> buildProviderList() {
        // Keep enum order.
        return new ArrayList<>(Arrays.asList(LanguageModel.values()));
    }

    private void confirmDeleteProviderConfig(@NonNull LanguageModel provider) {
        if (!isAdded()) return;
        final Context ctx = requireContext();
        new MaterialAlertDialogBuilder(ctx)
                .setTitle("删除配置")
                .setMessage("确定要删除 " + provider.label + " 的 API Key / Base URL / 缓存模型 吗？")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("删除", (d, w) -> {
                    if (!SPManager.isReady()) return;
                    try {
                        SPManager sp = SPManager.getInstance();
                        sp.setApiKey(provider, "");
                        sp.setBaseUrl(provider, "");
                        sp.setSubModel(provider, "");
                        sp.setCachedModels(provider, "", java.util.Collections.emptyList());
                        try { sp.clearProviderHealth(provider); } catch (Throwable ignored) {}
                    } catch (Throwable ignored) {
                    }

                    // If current selection was deleted, reset to "未设置".
                    try {
                        if (!selectedIsNotSet && selected == provider) {
                            selected = null;
                            selectedIsNotSet = true;
                            if (adapter != null) adapter.setSelected(null, true);
                        }
                    } catch (Throwable ignored) {}

                    if (adapter != null) adapter.notifyDataSetChanged();
                    Toast.makeText(ctx, "已删除：" + provider.label, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void refreshProviderModelCache(@NonNull LanguageModel provider, @Nullable ImageView refreshView) {
        if (!isAdded()) return;
        if (!SPManager.isReady()) return;
        final Context ctx = requireContext();
        final SPManager sp = SPManager.getInstance();

        final String apiKey = sp.getApiKey(provider);
        final String baseUrl = sp.getBaseUrl(provider);
        if (TextUtils.isEmpty(apiKey) || TextUtils.isEmpty(baseUrl)) {
            Toast.makeText(ctx, "请先配置 " + provider.label + "（API Key / Base URL）", Toast.LENGTH_SHORT).show();
            QuickProviderConfigDialogFragment.newInstance(provider.name())
                    .show(getParentFragmentManager(), QuickProviderConfigDialogFragment.TAG);
            return;
        }

        // UI: disable + spin
        final ObjectAnimator spin;
        if (refreshView != null) {
            refreshView.setEnabled(false);
            refreshView.setAlpha(0.6f);
            spin = ObjectAnimator.ofFloat(refreshView, View.ROTATION, 0f, 360f);
            spin.setDuration(900);
            spin.setRepeatCount(ValueAnimator.INFINITE);
            spin.setInterpolator(new LinearInterpolator());
            spin.start();
        } else {
            spin = null;
        }

        Toast.makeText(ctx, "正在刷新 " + provider.label + " 模型缓存…", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            long started = android.os.SystemClock.elapsedRealtime();
            try {
                ProviderModelsFetcher.Result r = ProviderModelsFetcher.fetchModels(provider, baseUrl, apiKey);
                final java.util.List<String> models = r.models != null ? r.models : new java.util.ArrayList<>();
                final long latency = r.latencyMs;
                sp.setCachedModels(provider, baseUrl, models);
                try { sp.setProviderHealth(provider, true, latency, null); } catch (Throwable ignored) {}

                final int count = models.size();
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    if (spin != null) { try { spin.cancel(); } catch (Throwable ignored) {} }
                    if (refreshView != null) {
                        refreshView.setRotation(0f);
                        refreshView.setEnabled(true);
                        refreshView.setAlpha(1f);
                    }
                    if (adapter != null) adapter.notifyDataSetChanged();
                    Toast.makeText(ctx, "已刷新：" + provider.label + "（" + count + " 个模型 / " + latency + "ms）", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                final long latency = Math.max(0, android.os.SystemClock.elapsedRealtime() - started);
                try { sp.setProviderHealth(provider, false, latency, e.getMessage()); } catch (Throwable ignored) {}
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    if (spin != null) { try { spin.cancel(); } catch (Throwable ignored) {} }
                    if (refreshView != null) {
                        refreshView.setRotation(0f);
                        refreshView.setEnabled(true);
                        refreshView.setAlpha(1f);
                    }
                    if (adapter != null) adapter.notifyDataSetChanged();
                    Toast.makeText(ctx, "刷新失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ===== Adapter =====

    private static final class ProviderAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        interface Listener {
            void onClickNotSet();
            void onClickProvider(@NonNull LanguageModel provider);
            void onEdit(@NonNull LanguageModel provider);
            void onRefresh(@NonNull LanguageModel provider, @Nullable ImageView refreshView);
            void onDelete(@NonNull LanguageModel provider);
        }

        private static final int TYPE_NOT_SET = 0;
        private static final int TYPE_PROVIDER = 1;

        private final List<LanguageModel> items;
        private final Listener listener;
        @Nullable private LanguageModel selected;
        private boolean selectedIsNotSet;

        ProviderAdapter(@NonNull List<LanguageModel> items, @Nullable LanguageModel selected, boolean selectedIsNotSet, @NonNull Listener listener) {
            this.items = items;
            this.selected = selected;
            this.selectedIsNotSet = selectedIsNotSet;
            this.listener = listener;
        }

        void setSelected(@Nullable LanguageModel provider, boolean isNotSet) {
            this.selected = provider;
            this.selectedIsNotSet = isNotSet;
            notifyDataSetChanged();
        }


        private static int getProviderIconRes(@NonNull LanguageModel p) {
            switch (p) {
                case Gemini:
                    return R.drawable.ic_provider_gemini;
                case ChatGPT:
                    return R.drawable.ic_provider_chatgpt;
                case DeepSeek:
                    return R.drawable.ic_provider_deepseek;
                case Doubao:
                    return R.drawable.ic_provider_doubao;
                case Qwen:
                    return R.drawable.ic_provider_qwen;
                case Groq:
                    return R.drawable.ic_provider_groq;
                case Grok:
                    return R.drawable.ic_provider_grok;
                case OpenRouter:
                    return R.drawable.ic_provider_openrouter;
                case Claude:
                    return R.drawable.ic_provider_claude;
                case Mistral:
                    return R.drawable.ic_provider_mistral;
                case Chutes:
                    return R.drawable.ic_provider_chutes;
                case Perplexity:
                    return R.drawable.ic_provider_perplexity;
                case GLM:
                    return R.drawable.ic_provider_zhipuai;
                default:
                    return R.drawable.ic_provider_default;
            }
        }

        private static boolean isLikelyExpired(@Nullable String err) {
    if (err == null) return false;
    String e = err.toLowerCase();
    return e.contains("401") || e.contains("403") || e.contains("402")
            || e.contains("unauthorized") || e.contains("invalid")
            || e.contains("insufficient") || e.contains("quota")
            || err.contains("余额") || err.contains("欠费") || err.contains("credit");
}

private static int getApiKeyStateIcon(boolean hasKey, long lastCheck, boolean ok, @Nullable String err) {
    if (!hasKey) return R.drawable.ic_provider_state_unset;
    if (lastCheck > 0L && !ok) {
        return R.drawable.ic_provider_state_expired;
    }
    return R.drawable.ic_provider_state_ok;
}

private static String getApiKeyStateText(boolean hasKey, long lastCheck, boolean ok, @Nullable String err) {
    if (!hasKey) return "APIKEY未添加";
    if (lastCheck > 0L && !ok) {
        return isLikelyExpired(err) ? "APIKEY过期" : "APIKEY错误";
    }
    return "APIKEY正常";
}

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? TYPE_NOT_SET : TYPE_PROVIDER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_provider_status_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final VH h = (VH) holder;
            final Context ctx = h.itemView.getContext();

            if (position == 0) {
                if (h.ivIcon != null) {
                    h.ivIcon.setImageResource(R.drawable.ic_provider_default);
                    try { h.ivIcon.setImageTintList(null); } catch (Throwable ignored) {}
                }
                if (h.tvName != null) h.tvName.setText(R.string.ui_not_set);
                if (h.tvStatus != null) h.tvStatus.setText("");
                if (h.ivRefresh != null) h.ivRefresh.setVisibility(View.GONE);
                if (h.ivDelete != null) h.ivDelete.setVisibility(View.GONE);
                if (h.ivEdit != null) h.ivEdit.setVisibility(View.GONE);
                if (h.ivKey != null) h.ivKey.setVisibility(View.GONE);

                boolean isSel = selectedIsNotSet;
                h.itemView.setBackgroundResource(isSel ? R.drawable.bg_list_item_ripple_selected : R.drawable.bg_list_item_ripple);
                h.itemView.setOnClickListener(v -> {
                    if (listener != null) listener.onClickNotSet();
                });
                return;
            }

            final LanguageModel p = items.get(position - 1);

            // Provider logo (keep original colors; no tint)
            if (h.ivIcon != null) {
                h.ivIcon.setImageResource(getProviderIconRes(p));
                try { h.ivIcon.setImageTintList(null); } catch (Throwable ignored) {}
            }

            if (h.tvName != null) h.tvName.setText(p.label);

            boolean hasKey = false;
            boolean hasUrl = false;
            boolean hasModels = false;
            int modelCount = 0;
            try {
                if (SPManager.isReady()) {
                    SPManager sp = SPManager.getInstance();
                    hasKey = !TextUtils.isEmpty(sp.getApiKey(p));
                    hasUrl = !TextUtils.isEmpty(sp.getBaseUrl(p));
                    try {
                        modelCount = sp.getCachedModels(p) != null ? sp.getCachedModels(p).size() : 0;
                        hasModels = modelCount > 0;
                    } catch (Throwable ignored) {
                        modelCount = 0;
                        hasModels = false;
                    }
                }
            } catch (Throwable ignored) {
            }

            String status;
            if (!hasKey || !hasUrl) {
                status = ctx.getString(R.string.provider_status_unconfigured);
            } else if (hasModels) {
                status = ctx.getString(R.string.provider_status_ready_with_count, modelCount);
            } else {
                status = ctx.getString(R.string.provider_status_url_set_no_models);
            }

            // Extra info (requested): Base URL host / last validation / latency & reachability.
            String baseHost = "-";
            String checkLine = "校验: 未";
            String latencyLine = "延迟: -";
            long lastCheck = 0L;
            long latency = 0L;
            boolean ok = false;
            String lastErr = null;
            try {
                if (SPManager.isReady()) {
                    SPManager sp = SPManager.getInstance();
                    String base = sp.getBaseUrl(p);
                    try {
                        Uri u = Uri.parse(base);
                        String host = u != null ? u.getHost() : null;
                        if (!TextUtils.isEmpty(host)) baseHost = host;
                    } catch (Throwable ignored) {
                    }

                    try {
                        lastCheck = sp.getProviderHealthLastCheckMs(p);
                        latency = sp.getProviderHealthLatencyMs(p);
                        ok = sp.getProviderHealthOk(p);
                        lastErr = sp.getProviderHealthLastError(p);
                    } catch (Throwable ignored) {
                    }

                    if (lastCheck > 0L) {
                        checkLine = "校验: " + formatTimeAgo(lastCheck);
                        latencyLine = ok ? ("延迟: " + latency + "ms") : "延迟: 不可达";
                    }
                }
            } catch (Throwable ignored) {
            }

            String extra = "Base: " + baseHost + "\n" + checkLine + " · " + latencyLine;
            String apiKeyLine = getApiKeyStateText(hasKey, lastCheck, ok, lastErr);
            int apiKeyIconRes = getApiKeyStateIcon(hasKey, lastCheck, ok, lastErr);
            if (h.tvStatus != null) h.tvStatus.setText(status + "\n" + extra + "\n" + apiKeyLine);

if (h.ivRefresh != null) h.ivRefresh.setVisibility(View.VISIBLE);
if (h.ivDelete != null) h.ivDelete.setVisibility(View.VISIBLE);
if (h.ivEdit != null) h.ivEdit.setVisibility(View.VISIBLE);
if (h.ivKey != null) {
    h.ivKey.setVisibility(View.VISIBLE);
    h.ivKey.setImageResource(apiKeyIconRes);
    try { h.ivKey.setImageTintList(null); } catch (Throwable ignored) {}
}

            boolean isSelected = (!selectedIsNotSet && selected == p);
            h.itemView.setBackgroundResource(isSelected ? R.drawable.bg_list_item_ripple_selected : R.drawable.bg_list_item_ripple);
            if (h.ivEdit != null) {
                h.ivEdit.setOnClickListener(v -> {
                    if (listener != null) listener.onEdit(p);
                });
            }

            if (h.ivRefresh != null) {
                h.ivRefresh.setOnClickListener(v -> {
                    if (listener != null) listener.onRefresh(p, h.ivRefresh);
                });
            }

            if (h.ivDelete != null) {
                h.ivDelete.setOnClickListener(v -> {
                    if (listener != null) listener.onDelete(p);
                });
            }

            h.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onClickProvider(p);
            });
        }

        @Override
        public int getItemCount() {
            return (items != null ? items.size() : 0) + 1;
        }

        static final class VH extends RecyclerView.ViewHolder {
            TextView tvName;
            TextView tvStatus;
            ImageView ivIcon;
            ImageView ivRefresh;
            ImageView ivDelete;
            ImageView ivEdit;
            ImageView ivKey;

            VH(@NonNull View itemView) {
                super(itemView);
                ivIcon = itemView.findViewById(R.id.iv_provider_icon);
                tvName = itemView.findViewById(R.id.tv_provider_name);
                tvStatus = itemView.findViewById(R.id.tv_provider_status);
                ivRefresh = itemView.findViewById(R.id.iv_refresh);
                ivDelete = itemView.findViewById(R.id.iv_delete);
                ivEdit = itemView.findViewById(R.id.iv_edit);
                ivKey = itemView.findViewById(R.id.iv_key);
            }
        }
    }

    private static String formatTimeAgo(long epochMs) {
        long now = System.currentTimeMillis();
        long diff = Math.max(0, now - epochMs);
        long sec = diff / 1000L;
        if (sec < 30) return "刚刚";
        if (sec < 60) return sec + "秒前";
        long min = sec / 60L;
        if (min < 60) return min + "分钟前";
        long hr = min / 60L;
        if (hr < 24) return hr + "小时前";
        long day = hr / 24L;
        return day + "天前";
    }
}
