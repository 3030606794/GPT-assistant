package tn.eluea.kgpt.ui.main.adapters;

import android.content.Context;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.llm.LanguageModel;
import tn.eluea.kgpt.llm.LanguageModelField;

/**
 * Provider settings list (single merged page for Model + API Keys).
 * Each provider is displayed as an accordion card.
 */
public class ProviderSettingsAdapter extends RecyclerView.Adapter<ProviderSettingsAdapter.VH> {

    public interface OnConfigChangedListener {
        void onConfigChanged();
    }

    private final Fragment fragment;
    private final Context context;
    private final OnConfigChangedListener onConfigChanged;

    private final List<LanguageModel> providers = Arrays.asList(LanguageModel.values());

    private int expandedPosition = -1;
    private LanguageModel activeModel;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long API_KEY_SAVE_DEBOUNCE_MS = 350;

    public ProviderSettingsAdapter(@NonNull Fragment fragment, @NonNull OnConfigChangedListener listener) {
        this.fragment = fragment;
        this.context = fragment.requireContext();
        this.onConfigChanged = listener;

        this.activeModel = SPManager.isReady() ? SPManager.getInstance().getLanguageModel() : LanguageModel.Gemini;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_provider_settings, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        LanguageModel model = providers.get(position);

        holder.tvName.setText(model.label);
        holder.badgeFree.setVisibility(model.isFree ? View.VISIBLE : View.GONE);

        String sub = safeGetSubModel(model);
        holder.tvSubtitle.setText(!TextUtils.isEmpty(sub) ? sub : context.getString(R.string.ui_not_set));

        boolean isActive = model == activeModel;
        holder.ivActive.setVisibility(isActive ? View.VISIBLE : View.GONE);

        boolean expanded = position == expandedPosition;
        holder.setExpanded(expanded);

        // Load values
        holder.etApiKey.setText(safeGetApiKey(model));
        holder.etBaseUrl.setText(safeGetBaseUrl(model));

        // Auto-save API key (silent) + password toggle is handled by TextInputLayout in XML
        attachApiKeyAutoSave(holder, model);

        // Click left area: select provider only
        holder.layoutSelect.setOnClickListener(v -> {
            if (!SPManager.isReady()) {
                Toast.makeText(context, "SPManager not ready", Toast.LENGTH_SHORT).show();
                return;
            }

            int oldActivePos = indexOf(activeModel);
            if (activeModel != model) {
                SPManager.getInstance().setLanguageModel(model);
                activeModel = model;
                if (oldActivePos >= 0) notifyItemChanged(oldActivePos);
                notifyItemChanged(position);
                onConfigChanged.onConfigChanged();
            }

            Toast.makeText(context, context.getString(R.string.ui_provider_selected, model.label), Toast.LENGTH_SHORT).show();
        });

        // Click right arrow: expand/collapse only
        holder.layoutExpand.setOnClickListener(v -> {
            int oldExpanded = expandedPosition;
            if (expandedPosition == position) {
                expandedPosition = -1;
                notifyItemChanged(position);
            } else {
                expandedPosition = position;
                notifyItemChanged(position);
                if (oldExpanded >= 0) notifyItemChanged(oldExpanded);
            }
        });

        holder.etBaseUrl.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                ensureVisibleOnScreen(v);
                return;
            }
            saveBaseUrlFromField(holder, model);
        });

        holder.btnFetchModels.setOnClickListener(v -> {
            if (!SPManager.isReady()) {
                Toast.makeText(context, "SPManager not ready", Toast.LENGTH_SHORT).show();
                return;
            }
            if (model == LanguageModel.Claude) {
                Toast.makeText(context, context.getString(R.string.fetch_models_claude_not_supported), Toast.LENGTH_SHORT).show();
                return;
            }

            // Use current (possibly unsaved) baseUrl & key
            String baseUrlInput = holder.etBaseUrl.getText() != null ? holder.etBaseUrl.getText().toString().trim() : "";
            String baseUrl = normalizeBaseUrl(model, baseUrlInput);
            holder.etBaseUrl.setText(baseUrl);
            SPManager.getInstance().setBaseUrl(model, baseUrl);

            String apiKeyInput = holder.etApiKey.getText() != null ? holder.etApiKey.getText().toString().trim() : "";
            String apiKey = !TextUtils.isEmpty(apiKeyInput) ? apiKeyInput : safeGetApiKey(model);

            if (TextUtils.isEmpty(apiKey)) {
                Toast.makeText(context, context.getString(R.string.fetch_models_no_key), Toast.LENGTH_SHORT).show();
                return;
            }

            holder.btnFetchModels.setEnabled(false);
            Toast.makeText(context, context.getString(R.string.fetch_models_loading), Toast.LENGTH_SHORT).show();

            final int bindPos = holder.getBindingAdapterPosition();
            new Thread(() -> {
                try {
                    List<String> models = fetchModelsFromEndpoint(model, baseUrl, apiKey);
                    fragment.requireActivity().runOnUiThread(() -> {
                        holder.btnFetchModels.setEnabled(true);
                        if (models == null || models.isEmpty()) {
                            Toast.makeText(context, context.getString(R.string.fetch_models_empty), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        SPManager.getInstance().setCachedModels(model, baseUrl, models);
                        Toast.makeText(context, context.getString(R.string.fetch_models_cached, models.size()), Toast.LENGTH_SHORT).show();
                        onConfigChanged.onConfigChanged();

                        // Refresh dropdown suggestions if this holder is still bound
                        if (bindPos >= 0) {
                            notifyItemChanged(bindPos);
                        }
                    });
                } catch (Exception e) {
                    fragment.requireActivity().runOnUiThread(() -> {
                        holder.btnFetchModels.setEnabled(true);
                        Toast.makeText(context, context.getString(R.string.fetch_models_failed) + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        });
    }

    @Override
    public int getItemCount() {
        return providers.size();
    }

    private int indexOf(LanguageModel model) {
        if (model == null) return -1;
        for (int i = 0; i < providers.size(); i++) {
            if (providers.get(i) == model) return i;
        }
        return -1;
    }

    private void saveBaseUrlFromField(@NonNull VH holder, @NonNull LanguageModel model) {
        if (!SPManager.isReady()) return;
        String input = holder.etBaseUrl.getText() != null ? holder.etBaseUrl.getText().toString().trim() : "";
        String normalized = normalizeBaseUrl(model, input);
        holder.etBaseUrl.setText(normalized);
        SPManager.getInstance().setBaseUrl(model, normalized);
        onConfigChanged.onConfigChanged();
    }

    /**
     * Silent auto-save for API key with a small debounce to avoid excessive SharedPreferences writes.
     */
    private void attachApiKeyAutoSave(@NonNull VH holder, @NonNull LanguageModel model) {
        // Remove any previously attached watcher (RecyclerView reuse)
        Object oldWatcher = holder.etApiKey.getTag(R.id.tag_api_key_watcher);
        if (oldWatcher instanceof TextWatcher) {
            holder.etApiKey.removeTextChangedListener((TextWatcher) oldWatcher);
        }
        Object oldRunnable = holder.etApiKey.getTag(R.id.tag_api_key_save_runnable);
        if (oldRunnable instanceof Runnable) {
            mainHandler.removeCallbacks((Runnable) oldRunnable);
        }

        final Runnable[] pending = new Runnable[1];
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!SPManager.isReady()) return;
                // Debounce saves to keep UI smooth
                if (pending[0] != null) mainHandler.removeCallbacks(pending[0]);
                String key = s == null ? "" : s.toString();
                pending[0] = () -> {
                    try {
                        SPManager.getInstance().setApiKey(model, key.trim());
                        onConfigChanged.onConfigChanged();
                    } catch (Throwable ignored) {
                    }
                };
                holder.etApiKey.setTag(R.id.tag_api_key_save_runnable, pending[0]);
                mainHandler.postDelayed(pending[0], API_KEY_SAVE_DEBOUNCE_MS);
            }
        };

        holder.etApiKey.addTextChangedListener(watcher);
        holder.etApiKey.setTag(R.id.tag_api_key_watcher, watcher);

        // Scroll into view on focus (important when IME is shown), and save immediately on focus loss.
        holder.etApiKey.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                ensureVisibleOnScreen(v);
                return;
            }
            if (!SPManager.isReady()) return;
            CharSequence cs = holder.etApiKey.getText();
            String key = cs == null ? "" : cs.toString().trim();
            try {
                SPManager.getInstance().setApiKey(model, key);
                onConfigChanged.onConfigChanged();
            } catch (Throwable ignored) {
            }
        });
    }


    /**
     * Ensure the focused input is scrolled into the visible area.
     * This is especially important on edge-to-edge layouts with IME insets.
     */
    private void ensureVisibleOnScreen(@NonNull View target) {
        target.post(() -> {
            // Prefer a NestedScrollView host if present (our providers list is wrapped in one).
            ViewParent p = target.getParent();
            while (p != null && !(p instanceof NestedScrollView)) {
                p = p.getParent();
            }
            if (p instanceof NestedScrollView) {
                NestedScrollView nsv = (NestedScrollView) p;
                Rect r = new Rect();
                target.getDrawingRect(r);
                // Scroll so that the target rect is visible (accounting for IME resize).
                nsv.requestChildRectangleOnScreen(target, r, true);
                return;
            }

            // Fallback: scroll the RecyclerView to this item.
            try {
                int pos = getFocusedAdapterPosition(target);
                if (pos >= 0) {
                    fragment.requireActivity().runOnUiThread(() -> {
                        RecyclerView rv = fragment.getView() != null ? fragment.getView().findViewById(R.id.rv_providers) : null;
                        if (rv != null) rv.smoothScrollToPosition(pos);
                    });
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private int getFocusedAdapterPosition(@NonNull View target) {
        try {
            View v = target;
            while (v != null && !(v.getParent() instanceof RecyclerView)) {
                if (v.getParent() instanceof View) v = (View) v.getParent();
                else break;
            }
            if (v != null) {
                RecyclerView rv = fragment.getView() != null ? fragment.getView().findViewById(R.id.rv_providers) : null;
                if (rv != null) {
                    RecyclerView.ViewHolder vh = rv.findContainingViewHolder(v);
                    if (vh != null) return vh.getBindingAdapterPosition();
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }


    private String safeGetApiKey(@NonNull LanguageModel model) {
        try {
            if (SPManager.isReady()) {
                String k = SPManager.getInstance().getApiKey(model);
                return k == null ? "" : k;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private String safeGetBaseUrl(@NonNull LanguageModel model) {
        try {
            if (SPManager.isReady()) {
                String u = SPManager.getInstance().getBaseUrl(model);
                if (!TextUtils.isEmpty(u)) return u;
            }
        } catch (Throwable ignored) {
        }
        return model.getDefault(LanguageModelField.BaseUrl);
    }

    private String safeGetSubModel(@NonNull LanguageModel model) {
        try {
            if (SPManager.isReady()) {
                String s = SPManager.getInstance().getSubModel(model);
                if (!TextUtils.isEmpty(s)) return s;
            }
        } catch (Throwable ignored) {
        }
        return model.getDefault(LanguageModelField.SubModel);
    }

    /**
     * Normalize base url: append default provider path if user only enters host.
     */
    private String normalizeBaseUrl(@NonNull LanguageModel model, String input) {
        String defaultUrl = model.getDefault(LanguageModelField.BaseUrl);
        if (input == null) return defaultUrl;

        String s = input.trim();
        if (s.isEmpty()) return defaultUrl;

        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://" + s;
        }

        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }

        try {
            Uri uri = Uri.parse(s);
            String path = uri.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                String host = uri.getHost();
                String appendPath;

                if (model == LanguageModel.OpenRouter) {
                    // OpenRouter official endpoint uses /api/v1,
                    // but most OpenAI-compatible relay servers use /v1.
                    if (host != null && (host.equalsIgnoreCase("openrouter.ai") || host.endsWith(".openrouter.ai"))) {
                        appendPath = "/api/v1";
                    } else {
                        appendPath = "/v1";
                    }
                } else {
                    Uri defUri = Uri.parse(defaultUrl);
                    appendPath = defUri.getPath();
                }

                if (appendPath != null && !appendPath.isEmpty() && !"/".equals(appendPath)) {
                    if (!appendPath.startsWith("/")) appendPath = "/" + appendPath;
                    s = s + appendPath;
                }
            }
        } catch (Exception ignored) {
        }

        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private List<String> fetchModelsFromEndpoint(@NonNull LanguageModel model, @NonNull String baseUrl, @NonNull String apiKey) throws Exception {
        String url = baseUrl;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        url = url + "/models";

        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("GET");
        con.setConnectTimeout(15000);
        con.setReadTimeout(20000);
        con.setRequestProperty("Accept", "application/json");

        String key = apiKey.trim();
        if (!key.isEmpty()) {
            if (model == LanguageModel.Gemini) {
                con.setRequestProperty("x-goog-api-key", key);
            } else {
                con.setRequestProperty("Authorization", "Bearer " + key);
            }
        }

        int code = con.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
        String body = readAll(is);

        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + " " + body);
        }

        return parseModelsFromResponse(model, body);
    }

    private String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        br.close();
        return sb.toString();
    }

    private List<String> parseModelsFromResponse(@NonNull LanguageModel model, String body) {
        List<String> list = new ArrayList<>();
        if (body == null) return list;

        try {
            JSONObject root = new JSONObject(body);

            // OpenAI / OpenRouter style: { data: [ { id: ... }, ... ] }
            if (root.has("data") && root.optJSONArray("data") != null) {
                JSONArray arr = root.optJSONArray("data");
                for (int i = 0; i < arr.length(); i++) {
                    Object it = arr.opt(i);
                    if (it instanceof JSONObject) {
                        String id = ((JSONObject) it).optString("id", "");
                        if (id.isEmpty()) id = ((JSONObject) it).optString("name", "");
                        if (!id.isEmpty()) list.add(id);
                    } else if (it instanceof String) {
                        String name = (String) it;
                    if (model == LanguageModel.Gemini && name.startsWith("models/")) {
                        name = name.substring("models/".length());
                    }
                    list.add(name);
                    }
                }
            }

            // Gemini style: { models: [ { name: "models/xxx" }, ... ] }
            if (list.isEmpty() && root.has("models") && root.optJSONArray("models") != null) {
                JSONArray arr = root.optJSONArray("models");
                for (int i = 0; i < arr.length(); i++) {
                    Object it = arr.opt(i);
                    if (it instanceof JSONObject) {
                        String name = ((JSONObject) it).optString("name", "");
                        if (!name.isEmpty()) {
                            if (model == LanguageModel.Gemini && name.startsWith("models/")) {
                                name = name.substring("models/".length());
                            }
                            list.add(name);
                        }
                    } else if (it instanceof String) {
                        list.add((String) it);
                    }
                }
            }

            // Some relays: { result: [ ... ] }
            if (list.isEmpty() && root.has("result") && root.optJSONArray("result") != null) {
                JSONArray arr = root.optJSONArray("result");
                for (int i = 0; i < arr.length(); i++) {
                    Object it = arr.opt(i);
                    if (it instanceof JSONObject) {
                        String id = ((JSONObject) it).optString("id", "");
                        if (id.isEmpty()) id = ((JSONObject) it).optString("name", "");
                        if (!id.isEmpty()) list.add(id);
                    } else if (it instanceof String) {
                        list.add((String) it);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // Normalize Gemini names
        if (model == LanguageModel.Gemini) {
            List<String> normalized = new ArrayList<>();
            for (String s : list) {
                if (s == null) continue;
                String x = s.trim();
                if (x.startsWith("models/")) x = x.substring("models/".length());
                normalized.add(x);
            }
            list = normalized;
        }

        // Sort and remove duplicates
        Collections.sort(list);
        List<String> unique = new ArrayList<>();
        String last = null;
        for (String s : list) {
            if (s == null) continue;
            if (last == null || !last.equals(s)) {
                unique.add(s);
                last = s;
            }
        }
        return unique;
    }

    public static class VH extends RecyclerView.ViewHolder {
        MaterialCardView card;
        View rowHeader;
        View layoutSelect;
        View layoutExpand;
        ImageView ivActive;
        ImageView ivExpand;
        TextView tvName;
        TextView tvSubtitle;
        TextView badgeFree;

        View layoutContent;
        TextInputEditText etApiKey;
        TextInputEditText etBaseUrl;
        MaterialButton btnFetchModels;

        public VH(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.card_provider);
            rowHeader = itemView.findViewById(R.id.row_header);
            layoutSelect = itemView.findViewById(R.id.layout_select);
            layoutExpand = itemView.findViewById(R.id.layout_expand);
            ivActive = itemView.findViewById(R.id.iv_active);
            ivExpand = itemView.findViewById(R.id.iv_expand);
            tvName = itemView.findViewById(R.id.tv_name);
            tvSubtitle = itemView.findViewById(R.id.tv_subtitle);
            badgeFree = itemView.findViewById(R.id.tv_badge_free);

            layoutContent = itemView.findViewById(R.id.layout_content);
            etApiKey = itemView.findViewById(R.id.et_api_key);
            etBaseUrl = itemView.findViewById(R.id.et_base_url);
            btnFetchModels = itemView.findViewById(R.id.btn_fetch_models);
        }

        void setExpanded(boolean expanded) {
            layoutContent.setVisibility(expanded ? View.VISIBLE : View.GONE);
            // Use a vertical chevron: down when collapsed, up when expanded
            ivExpand.setRotation(expanded ? 270f : 90f);
        }
    }
}
