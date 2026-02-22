package tn.eluea.kgpt.ui.lab;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

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
import com.google.android.material.radiobutton.MaterialRadioButton;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.llm.LanguageModel;

/**
 * 一级弹窗：供应商状态列表（Provider List Dialog）
 * - 单选供应商
 * - 未配置时拦截弹出二级快速配置
 */
public class ProviderListDialogFragment extends DialogFragment {

    public interface Callback {
        void onProviderConfirmed(@NonNull LanguageModel provider);
    }

    public static final String TAG = "ProviderListDialog";

    private ProviderAdapter adapter;
    private LanguageModel selected;

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
                                    if (adapter != null) adapter.setSelected(p);
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

        final TextView tvQuota = root.findViewById(R.id.tv_quota);
        final MaterialButton btnRefreshQuota = root.findViewById(R.id.btn_refresh_quota);

        final RecyclerView rv = root.findViewById(R.id.rv_providers);
        rv.setLayoutManager(new LinearLayoutManager(ctx));

        LanguageModel cur = null;
        try {
            if (SPManager.isReady()) cur = SPManager.getInstance().getLanguageModel();
        } catch (Throwable ignored) {
        }
        if (cur == null) cur = LanguageModel.Gemini;
        selected = cur;

        adapter = new ProviderAdapter(buildProviderList(), selected, new ProviderAdapter.Listener() {
            @Override
            public void onClick(@NonNull LanguageModel provider) {
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
                adapter.setSelected(provider);

                // Hide stale quota display when switching away from OpenRouter.
                if (tvQuota != null && provider != LanguageModel.OpenRouter) {
                    tvQuota.setVisibility(View.GONE);
                }
            }

            @Override
            public void onEdit(@NonNull LanguageModel provider) {
                if (!isAdded()) return;
                QuickProviderConfigDialogFragment.newInstance(provider.name())
                        .show(getParentFragmentManager(), QuickProviderConfigDialogFragment.TAG);
            }
        });

        rv.setAdapter(adapter);

        if (btnRefreshQuota != null) {
            btnRefreshQuota.setOnClickListener(v -> {
                if (!isAdded() || !SPManager.isReady()) return;

                // Only OpenRouter exposes /key quota info.
                if (selected != LanguageModel.OpenRouter) {
                    Toast.makeText(ctx, R.string.ui_refresh_quota_only_openrouter, Toast.LENGTH_SHORT).show();
                    return;
                }

                SPManager sp = SPManager.getInstance();
                String apiKey = sp.getApiKey(LanguageModel.OpenRouter);
                String baseUrl = sp.getBaseUrl(LanguageModel.OpenRouter);
                if (TextUtils.isEmpty(apiKey) || TextUtils.isEmpty(baseUrl)) {
                    Toast.makeText(ctx, R.string.ui_refresh_quota_need_config, Toast.LENGTH_SHORT).show();
                    return;
                }

                btnRefreshQuota.setEnabled(false);
                if (tvQuota != null) {
                    tvQuota.setVisibility(View.VISIBLE);
                    tvQuota.setText(R.string.ui_refreshing);
                }

                final Handler main = new Handler(Looper.getMainLooper());
                new Thread(() -> {
                    String msg;
                    try {
                        // OpenRouter quota endpoint is /api/v1/key on the official host.
                        // Some users may store a compat baseUrl like "https://openrouter.ai/api/v1" or even ".../v1".
                        // We normalize common variants to the canonical endpoint.
                        String url = buildOpenRouterQuotaUrl(baseUrl);
                        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                        con.setRequestMethod("GET");
                        con.setConnectTimeout(15000);
                        con.setReadTimeout(20000);
                        con.setRequestProperty("Accept", "application/json");
                        con.setRequestProperty("Authorization", "Bearer " + apiKey.trim());

                        int code = con.getResponseCode();
                        InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
                        String body = readAll(is);

                        if (code < 200 || code >= 300) {
                            msg = "HTTP " + code;
                            if (!TextUtils.isEmpty(body)) {
                                msg += ": " + body;

                                // Friendly hint for the most common misconfiguration:
                                // user filled an OpenAI-compatible /v1 baseUrl, which doesn't implement /key.
                                if (body.contains("Invalid URL") && body.contains("/v1/key")) {
                                    msg += "\n\n" + ctx.getString(R.string.ui_refresh_quota_hint_openrouter_baseurl);
                                }
                            }
                        } else {
                            msg = formatOpenRouterQuota(body);
                        }
                    } catch (Throwable t) {
                        msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                    }

                    final String finalMsg = msg;
                    main.post(() -> {
                        try {
                            btnRefreshQuota.setEnabled(true);
                            if (tvQuota != null) {
                                tvQuota.setVisibility(View.VISIBLE);
                                tvQuota.setText(finalMsg);
                            }
                        } catch (Throwable ignored) {
                        }
                    });
                }, "KGPT-OR-Quota").start();
            });
        }

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
                if (selected != null) {
                    Callback cb = getCallback();
                    if (cb != null) {
                        cb.onProviderConfirmed(selected);
                    }
                }
                dismissAllowingStateLoss();
            });
        }

        return dialog;
    }

    private static String normalizeUrl(@NonNull String baseUrl, @NonNull String suffix) {
        String url = baseUrl.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!suffix.startsWith("/")) suffix = "/" + suffix;
        return url + suffix;
    }

    /**
     * OpenRouter exposes quota at:
     *   https://openrouter.ai/api/v1/key
     * Users may have stored these common variants:
     *   - https://openrouter.ai
     *   - https://openrouter.ai/api
     *   - https://openrouter.ai/api/v1
     *   - https://openrouter.ai/v1 (wrong, but seen in the wild)
     * We normalize official-host variants to the canonical endpoint.
     * If it's not the official host, fall back to baseUrl + /key (may fail, but is explicit).
     */
    private static String buildOpenRouterQuotaUrl(@NonNull String baseUrl) {
        String raw = baseUrl.trim();
        while (raw.endsWith("/")) raw = raw.substring(0, raw.length() - 1);

        try {
            URL u = new URL(raw);
            String host = u.getHost() != null ? u.getHost().toLowerCase(Locale.US) : "";
            boolean isOfficial = host.equals("openrouter.ai") || host.endsWith(".openrouter.ai");
            if (!isOfficial) {
                // Likely a proxy. Some proxies won't implement /key; keep behavior deterministic.
                return normalizeUrl(raw, "/key");
            }

            String scheme = TextUtils.isEmpty(u.getProtocol()) ? "https" : u.getProtocol();
            String origin = scheme + "://" + u.getHost();
            String path = u.getPath() != null ? u.getPath() : "";

            // If already /api/v1..., keep it.
            if (path.contains("/api/v1")) {
                String prefix = origin + path;
                while (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
                // Ensure it ends with /api/v1
                int idx = prefix.indexOf("/api/v1");
                prefix = prefix.substring(0, idx + "/api/v1".length());
                return prefix + "/key";
            }

            // Handle common variants.
            if (path.equals("/api")) {
                return origin + "/api/v1/key";
            }
            if (path.endsWith("/v1")) {
                // Some users mistakenly store /v1; OpenRouter uses /api/v1.
                return origin + "/api/v1/key";
            }
            if (TextUtils.isEmpty(path) || path.equals("/")) {
                return origin + "/api/v1/key";
            }

            // Unknown path on official host: best effort fallback.
            return normalizeUrl(origin + path, "/key");
        } catch (Throwable ignored) {
            // If URL parsing fails, fallback.
            return normalizeUrl(raw, "/key");
        }
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

    /**
     * OpenRouter /api/v1/key response example:
     * {"data":{"limit_remaining":74.5,"usage_daily":25.5,...}}
     */
    private String formatOpenRouterQuota(@NonNull String body) {
        try {
            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return body;

            String remaining;
            if (data.isNull("limit_remaining")) {
                remaining = getString(R.string.ui_quota_unlimited);
            } else {
                double v = data.optDouble("limit_remaining", Double.NaN);
                remaining = Double.isNaN(v) ? getString(R.string.ui_quota_unknown)
                        : trimNumber(v);
            }

            String today;
            if (data.isNull("usage_daily")) {
                today = getString(R.string.ui_quota_unknown);
            } else {
                double v = data.optDouble("usage_daily", Double.NaN);
                today = Double.isNaN(v) ? getString(R.string.ui_quota_unknown)
                        : trimNumber(v);
            }

            return getString(R.string.ui_quota_fmt, remaining, today);
        } catch (Throwable t) {
            return body;
        }
    }

    private static String trimNumber(double v) {
        // keep up to 2 decimals, but remove trailing zeros
        String s = String.format(Locale.US, "%.2f", v);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    @Override
    public void onStart() {
        super.onStart();
        try {
            Dialog d = getDialog();
            if (d != null && d.getWindow() != null) {
                // Make dialog span full screen width.
                d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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

    // ===== Adapter =====

    private static final class ProviderAdapter extends RecyclerView.Adapter<ProviderAdapter.VH> {

        interface Listener {
            void onClick(@NonNull LanguageModel provider);
            void onEdit(@NonNull LanguageModel provider);
        }

        private final List<LanguageModel> items;
        private final Listener listener;
        private LanguageModel selected;

        ProviderAdapter(@NonNull List<LanguageModel> items, @NonNull LanguageModel selected, @NonNull Listener listener) {
            this.items = items;
            this.selected = selected;
            this.listener = listener;
        }

        void setSelected(@NonNull LanguageModel provider) {
            this.selected = provider;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_provider_status_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            final LanguageModel p = items.get(position);
            final Context ctx = h.itemView.getContext();

            if (h.tvName != null) h.tvName.setText(p.label);

            if (h.ivLogo != null) {
                h.ivLogo.setImageResource(getProviderIconRes(p));
            }

            boolean hasKey = false;
            boolean hasUrl = false;
            boolean hasModels = false;
            try {
                if (SPManager.isReady()) {
                    SPManager sp = SPManager.getInstance();
                    hasKey = !TextUtils.isEmpty(sp.getApiKey(p));
                    hasUrl = !TextUtils.isEmpty(sp.getBaseUrl(p));
                    try {
                        hasModels = sp.getCachedModels(p) != null && !sp.getCachedModels(p).isEmpty();
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }

            String status;
            if (!hasKey || !hasUrl) {
                status = ctx.getString(R.string.provider_status_unconfigured);
            } else {
                status = hasModels ? ctx.getString(R.string.provider_status_ready)
                        : ctx.getString(R.string.provider_status_url_set_no_models);
            }
            if (h.tvStatus != null) h.tvStatus.setText(status);

            if (h.ivKey != null) {
                h.ivKey.setVisibility(hasKey ? View.VISIBLE : View.GONE);
            }

            boolean isSelected = (selected == p);
            if (h.rbSelected != null) {
                h.rbSelected.setChecked(isSelected);
            }

            if (h.ivEdit != null) {
                h.ivEdit.setOnClickListener(v -> {
                    if (listener != null) listener.onEdit(p);
                });
            }

            h.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onClick(p);
            });
        }

        @Override
        public int getItemCount() {
            return items != null ? items.size() : 0;
        }

        static final class VH extends RecyclerView.ViewHolder {
            TextView tvName;
            TextView tvStatus;
            MaterialRadioButton rbSelected;
            ImageView ivLogo;
            ImageView ivEdit;
            ImageView ivKey;

            VH(@NonNull View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tv_provider_name);
                tvStatus = itemView.findViewById(R.id.tv_provider_status);
                rbSelected = itemView.findViewById(R.id.rb_selected);
                ivLogo = itemView.findViewById(R.id.iv_provider_logo);
                ivEdit = itemView.findViewById(R.id.iv_edit);
                ivKey = itemView.findViewById(R.id.iv_key);
            }
        }

        private static int getProviderIconRes(@NonNull LanguageModel p) {
            switch (p) {
                case Gemini:
                    return R.drawable.ic_magic_star_filled;
                case ChatGPT:
                    return R.drawable.ic_message_text_filled;
                case Groq:
                    return R.drawable.ic_flash_bolt_filled;
                case OpenRouter:
                    return R.drawable.ic_radar_filled;
                case Claude:
                    return R.drawable.ic_lamp_filled;
                case Mistral:
                    return R.drawable.ic_cloud_snow_outline;
                case Chutes:
                    return R.drawable.ic_tool;
                case Perplexity:
                    return R.drawable.ic_search_filled;
                case GLM:
                    return R.drawable.ic_cpu_filled;
                default:
                    return R.drawable.ic_model_default;
            }
        }
    }
}
