package tn.eluea.kgpt.ui.lab;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.llm.LanguageModel;
import tn.eluea.kgpt.llm.LanguageModelField;

/**
 * 二级弹窗：快捷拦截配置（Quick Config Dialog）
 * - 输入 API Key + Base URL
 * - 点击“获取模型” -> 网络请求 -> 成功后缓存模型并保存默认子模型
 */
public class QuickProviderConfigDialogFragment extends DialogFragment {

    public static final String TAG = "QuickProviderConfigDialog";
    public static final String RESULT_KEY = "quick_provider_config_result";
    public static final String EXTRA_PROVIDER = "provider";
    public static final String EXTRA_COUNT = "count";

    public static final String EXTRA_ACTION = "action";
    public static final String ACTION_FETCHED = "fetched";
    public static final String ACTION_SAVED = "saved";
private static final String ARG_PROVIDER = "arg_provider";

    public static QuickProviderConfigDialogFragment newInstance(@NonNull String providerName) {
        QuickProviderConfigDialogFragment f = new QuickProviderConfigDialogFragment();
        Bundle b = new Bundle();
        b.putString(ARG_PROVIDER, providerName);
        f.setArguments(b);
        return f;
    }

    @Nullable
    private LanguageModel getProvider() {
        try {
            Bundle a = getArguments();
            if (a == null) return null;
            String name = a.getString(ARG_PROVIDER, null);
            if (name == null) return null;
            return LanguageModel.valueOf(name);
        } catch (Throwable ignored) {
        }
        return null;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final Context ctx = requireContext();
        final LanguageModel provider = getProvider();
        if (provider == null) {
            return new MaterialAlertDialogBuilder(ctx)
                    .setMessage("-")
                    .setPositiveButton(android.R.string.ok, null)
                    .create();
        }

        final View root = LayoutInflater.from(ctx).inflate(R.layout.dialog_quick_provider_config, null);

        final ImageView ivIcon = root.findViewById(R.id.iv_provider_icon);
        final TextView tvName = root.findViewById(R.id.tv_provider_name);
        final TextInputLayout tilKey = root.findViewById(R.id.til_api_key);
        final TextInputEditText etKey = root.findViewById(R.id.et_api_key);
        final TextInputLayout tilUrl = root.findViewById(R.id.til_base_url);
        final TextInputEditText etUrl = root.findViewById(R.id.et_base_url);
        final MaterialButton btnFetch = root.findViewById(R.id.btn_fetch_models);
        final MaterialButton btnCancel = root.findViewById(R.id.btn_cancel);
        final MaterialButton btnConfirm = root.findViewById(R.id.btn_confirm);

        if (tvName != null) tvName.setText(provider.label);
        if (ivIcon != null) ivIcon.setImageResource(getProviderIconRes(provider));

        // Prefill existing values (do NOT persist on typing).
        // We keep an immutable snapshot and only commit on successful "Fetch Models".
        final String[] originalKey = new String[]{""};
        final String[] originalUrl = new String[]{""};
        final boolean[] committed = new boolean[]{false};
        try {
            if (SPManager.isReady()) {
                SPManager sp = SPManager.getInstance();
                originalKey[0] = sp.getApiKey(provider);
                originalUrl[0] = sp.getBaseUrl(provider);
                if (etKey != null && !TextUtils.isEmpty(originalKey[0])) etKey.setText(originalKey[0]);
                if (etUrl != null && !TextUtils.isEmpty(originalUrl[0])) etUrl.setText(originalUrl[0]);
            }
        } catch (Throwable ignored) {
        }

        final Dialog dialog = new MaterialAlertDialogBuilder(ctx)
                .setView(root)
                .create();

        // Safety net: if user cancels/back/outside-dismiss, revert any accidental persistence.
        // (Some OEM ROMs / input methods may commit values unexpectedly.)
        try {
            dialog.setOnDismissListener(d -> {
                if (committed[0]) return;
                if (!SPManager.isReady()) return;
                try {
                    SPManager sp = SPManager.getInstance();
                    sp.setApiKey(provider, originalKey[0] == null ? "" : originalKey[0]);
                    sp.setBaseUrl(provider, originalUrl[0] == null ? "" : originalUrl[0]);
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dismissAllowingStateLoss());
        }

        
        if (btnConfirm != null) {
            btnConfirm.setOnClickListener(v -> {
                if (!SPManager.isReady()) {
                    dismissAllowingStateLoss();
                    return;
                }

                if (tilKey != null) tilKey.setError(null);
                if (tilUrl != null) tilUrl.setError(null);

                String apiKey = etKey != null && etKey.getText() != null ? etKey.getText().toString().trim() : "";
                String baseUrlInput = etUrl != null && etUrl.getText() != null ? etUrl.getText().toString().trim() : "";

                String baseUrl = "";
                if (!TextUtils.isEmpty(baseUrlInput)) {
                    try {
                        baseUrl = normalizeBaseUrl(provider, baseUrlInput);
                    } catch (Throwable ignored) {
                        baseUrl = "";
                    }
                    if (TextUtils.isEmpty(baseUrl)) {
                        if (tilUrl != null) tilUrl.setError(ctx.getString(R.string.field_base_url));
                        return;
                    }
                }

                try {
                    SPManager sp = SPManager.getInstance();
                    sp.setApiKey(provider, apiKey);
                    sp.setBaseUrl(provider, baseUrl);
                } catch (Throwable ignored) {}

                committed[0] = true;

                try {
                    Bundle res = new Bundle();
                    res.putString(EXTRA_PROVIDER, provider.name());
                    res.putString(EXTRA_ACTION, ACTION_SAVED);
                    getParentFragmentManager().setFragmentResult(RESULT_KEY, res);
                } catch (Throwable ignored) {}

                dismissAllowingStateLoss();
            });
        }

if (btnFetch != null) {
            btnFetch.setOnClickListener(v -> {
                if (!SPManager.isReady()) return;

                if (tilKey != null) tilKey.setError(null);
                if (tilUrl != null) tilUrl.setError(null);

                String apiKey = etKey != null && etKey.getText() != null ? etKey.getText().toString().trim() : "";
                String baseUrlInput = etUrl != null && etUrl.getText() != null ? etUrl.getText().toString().trim() : "";

                if (TextUtils.isEmpty(apiKey)) {
                    if (tilKey != null) tilKey.setError(ctx.getString(R.string.msg_enter_api_key));
                    return;
                }

                if (TextUtils.isEmpty(baseUrlInput)) {
                    if (tilUrl != null) tilUrl.setError(ctx.getString(R.string.field_base_url));
                    return;
                }

                final String baseUrl = normalizeBaseUrl(provider, baseUrlInput);

                // UI loading
                btnFetch.setEnabled(false);
                btnFetch.setText(R.string.fetch_models_loading);

                new Thread(() -> {
                    try {
                        // NOTE: variables captured by runOnUiThread() must be effectively-final.
                        // Avoid re-assigning the list to keep Java lambdas happy on older toolchains.
                        List<String> fetched = fetchModelsFromEndpoint(provider, baseUrl, apiKey);
                        final List<String> models = (fetched != null) ? fetched : new ArrayList<>();

                        // Persist
                        SPManager sp = SPManager.getInstance();
                        sp.setApiKey(provider, apiKey);
                        sp.setBaseUrl(provider, baseUrl);
                        sp.setCachedModels(provider, baseUrl, models);

                        if (!models.isEmpty()) {
                            String first = models.get(0);
                            if (!TextUtils.isEmpty(first)) {
                                sp.setSubModel(provider, first.trim());
                            }
                        }

                        Activity act = getActivity();
                        if (act != null) {
                            final int count = models.size();
                            act.runOnUiThread(() -> {
                                committed[0] = true;
                                Toast.makeText(ctx, ctx.getString(R.string.fetch_models_cached, count), Toast.LENGTH_SHORT).show();
                                // Notify provider list dialog to refresh
                                Bundle res = new Bundle();
                                res.putString(EXTRA_PROVIDER, provider.name());
                                res.putInt(EXTRA_COUNT, count);
                                res.putString(EXTRA_ACTION, ACTION_FETCHED);
                                try {
                                    getParentFragmentManager().setFragmentResult(RESULT_KEY, res);
                                } catch (Throwable ignored) {
                                }
                                dismissAllowingStateLoss();
                            });
                        }
                    } catch (Exception e) {
                        Activity act = getActivity();
                        if (act != null) {
                            act.runOnUiThread(() -> {
                                btnFetch.setEnabled(true);
                                btnFetch.setText(R.string.fetch_models);
                                Toast.makeText(ctx, ctx.getString(R.string.fetch_models_failed) + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
                            });
                        }
                    }
                }).start();
            });
        }

        return dialog;
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
                        list.add((String) it);
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
                        if (!TextUtils.isEmpty(name)) {
                            // GeminiClient expects raw id (without "models/")
                            if (model == LanguageModel.Gemini && name.startsWith("models/")) {
                                name = name.substring("models/".length());
                            }
                            list.add(name);
                        }
                    } else if (it instanceof String) {
                        String name = (String) it;
                        if (model == LanguageModel.Gemini && name.startsWith("models/")) {
                            name = name.substring("models/".length());
                        }
                        if (!TextUtils.isEmpty(name)) list.add(name);
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

        // Normalize & de-dup
        ArrayList<String> out = new ArrayList<>();
        for (String s : list) {
            if (s == null) continue;
            String v = s.trim();
            if (v.isEmpty()) continue;
            if (!out.contains(v)) out.add(v);
        }
        return out;
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
