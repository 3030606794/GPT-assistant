package tn.eluea.kgpt.ui.lab;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.net.ssl.SSLHandshakeException;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.llm.LanguageModel;
import tn.eluea.kgpt.llm.LanguageModelField;
import tn.eluea.kgpt.provider.ConfigClient;

/**
 * 二级弹窗：快捷拦截配置（Quick Config Dialog）
 * - 支持多配置档案（同供应商多套 Key/Base URL）
 * - Base URL 智能纠错/补全 + 最终请求预览
 * - 一键校验（不保存也可测试）
 * - 点击“确定” -> 自动网络请求获取模型 -> 成功后缓存模型并保存默认子模型
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

    // Profiles stored in ConfigProvider (works in app & module)
    private static final String PREF_PROFILES_JSON = "provider_profiles.%s.json";
    private static final String PREF_PROFILES_ACTIVE = "provider_profiles.%s.active";

    private static final class Profile {
        String id;
        String name;
        String apiKey;
        String baseUrl;

        static Profile fromJson(JSONObject o) {
            Profile p = new Profile();
            p.id = o.optString("id", "");
            p.name = o.optString("name", "默认");
            p.apiKey = o.optString("apiKey", "");
            p.baseUrl = o.optString("baseUrl", "");
            if (TextUtils.isEmpty(p.id)) p.id = "default";
            if (TextUtils.isEmpty(p.name)) p.name = "默认";
            return p;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id == null ? "" : id);
                o.put("name", name == null ? "" : name);
                o.put("apiKey", apiKey == null ? "" : apiKey);
                o.put("baseUrl", baseUrl == null ? "" : baseUrl);
            } catch (Throwable ignored) {}
            return o;
        }
    }

    // Import profiles from a local JSON file (SAF)
    private ActivityResultLauncher<String[]> importProfilesLauncher;
    private LanguageModel pendingImportProvider;
    private ConfigClient pendingImportClient;
    private ArrayList<Profile> pendingImportProfiles;
    private String[] pendingImportActiveProfileId;
    private MaterialAutoCompleteTextView pendingImportActProfile;
    private TextInputEditText pendingImportEtKey;
    private TextInputEditText pendingImportEtUrl;
    private TextView pendingImportKeyMaskHint;
    private TextView pendingImportFinalUrl;
    private String[] pendingImportBaselineKey;
    private String[] pendingImportBaselineUrl;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            importProfilesLauncher = registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri == null) return;
                        handleImportProfilesFromUri(uri);
                    }
            );
        } catch (Throwable ignored) {
        }
    }

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

        final TextInputLayout tilProfile = root.findViewById(R.id.til_profile);
        final MaterialAutoCompleteTextView actProfile = root.findViewById(R.id.act_profile);
        final MaterialButton btnProfileAdd = root.findViewById(R.id.btn_profile_add);
        final MaterialButton btnProfileDelete = root.findViewById(R.id.btn_profile_delete);
        final MaterialButton btnProfileImport = root.findViewById(R.id.btn_profile_import);
        final MaterialButton btnProfileExport = root.findViewById(R.id.btn_profile_export);

        final TextInputLayout tilKey = root.findViewById(R.id.til_api_key);
        final TextInputEditText etKey = root.findViewById(R.id.et_api_key);
        final TextView tvKeyMaskHint = root.findViewById(R.id.tv_api_key_mask_hint);
        final MaterialButton btnKeyCopy = root.findViewById(R.id.btn_api_key_copy);
        final MaterialButton btnKeyClear = root.findViewById(R.id.btn_api_key_clear);

        final TextInputLayout tilUrl = root.findViewById(R.id.til_base_url);
        final TextInputEditText etUrl = root.findViewById(R.id.et_base_url);
        final TextView tvFinalUrl = root.findViewById(R.id.tv_final_url_preview);

        final MaterialButton btnTest = root.findViewById(R.id.btn_test_connection);
        final MaterialButton btnCancel = root.findViewById(R.id.btn_cancel);
        final MaterialButton btnConfirm = root.findViewById(R.id.btn_confirm);

        if (tvName != null) tvName.setText(provider.label);
        if (ivIcon != null) {
            ivIcon.setImageResource(getProviderIconRes(provider));
            // Keep provider logo original colors (no tint)
            try { ivIcon.setImageTintList(null); } catch (Throwable ignored) {}
        }

        final ConfigClient client = new ConfigClient(ctx.getApplicationContext());

        // Baseline snapshot (used for safe revert on dismiss)
        final String[] baselineKey = new String[]{""};
        final String[] baselineUrl = new String[]{""};
        final boolean[] revertOnDismiss = new boolean[]{true};

        // Load current config (may already reflect active profile)
        try {
            if (SPManager.isReady()) {
                SPManager sp = SPManager.getInstance();
                baselineKey[0] = safeStr(sp.getApiKey(provider));
                baselineUrl[0] = safeStr(sp.getBaseUrl(provider));
            }
        } catch (Throwable ignored) {}

        // Profiles: ensure at least one profile exists (migrates legacy config into default)
        final ArrayList<Profile> profiles = loadOrInitProfiles(client, provider, baselineKey[0], baselineUrl[0]);
        final String[] activeProfileId = new String[]{getActiveProfileId(client, provider, profiles)};
        Profile activeProfile = findProfile(profiles, activeProfileId[0]);
        if (activeProfile == null && !profiles.isEmpty()) {
            activeProfile = profiles.get(0);
            activeProfileId[0] = activeProfile.id;
        }

        // Prefill inputs from active profile
        if (activeProfile != null) {
            if (etKey != null && !TextUtils.isEmpty(activeProfile.apiKey)) etKey.setText(activeProfile.apiKey);
            if (etUrl != null && !TextUtils.isEmpty(activeProfile.baseUrl)) etUrl.setText(activeProfile.baseUrl);
        } else {
            if (etKey != null && !TextUtils.isEmpty(baselineKey[0])) etKey.setText(baselineKey[0]);
            if (etUrl != null && !TextUtils.isEmpty(baselineUrl[0])) etUrl.setText(baselineUrl[0]);
        }

        // Setup profile dropdown
        if (actProfile != null) {
            try {
                actProfile.setKeyListener(null);
            } catch (Throwable ignored) {}
            final ArrayAdapter<String> adapter = new ArrayAdapter<>(ctx, android.R.layout.simple_list_item_1, getProfileNames(profiles));
            actProfile.setAdapter(adapter);
            actProfile.setText(getProfileNameById(profiles, activeProfileId[0]), false);

            actProfile.setOnItemClickListener((parent, view, position, id) -> {
                if (position < 0 || position >= profiles.size()) return;

                // Save current fields into current active profile (local store only)
                Profile cur = findProfile(profiles, activeProfileId[0]);
                if (cur != null) {
                    cur.apiKey = sanitizeApiKey(textOf(etKey));
                    cur.baseUrl = textOf(etUrl).trim();
                }

                Profile picked = profiles.get(position);
                activeProfileId[0] = picked.id;
                setActiveProfileId(client, provider, picked.id);
                saveProfiles(client, provider, profiles);

                // Apply picked profile into fields
                if (etKey != null) etKey.setText(safeStr(picked.apiKey));
                if (etUrl != null) etUrl.setText(safeStr(picked.baseUrl));

                // One-click switch: apply to current provider config immediately
                applyProfileToProvider(provider, picked, baselineKey, baselineUrl);

                updateKeyMaskHint(tvKeyMaskHint, sanitizeApiKey(textOf(etKey)));
                updateFinalUrlPreview(tvFinalUrl, provider, textOf(etUrl));

                Toast.makeText(ctx, "已切换到：" + picked.name, Toast.LENGTH_SHORT).show();
            });

            // Ensure displayed list updates when we modify profiles
            actProfile.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    try { actProfile.dismissDropDown(); } catch (Throwable ignored) {}
                }
            });
        }

        if (btnProfileAdd != null) {
            btnProfileAdd.setOnClickListener(v -> {
                final EditText input = new EditText(ctx);
                input.setHint("例如：官方 / 代理 / 公司网关");
                new MaterialAlertDialogBuilder(ctx)
                        .setTitle("新建配置档案")
                        .setView(input)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton("创建", (d, w) -> {
                            String name = input.getText() != null ? input.getText().toString().trim() : "";
                            if (TextUtils.isEmpty(name)) name = "新档案";
                            Profile np = new Profile();
                            np.id = UUID.randomUUID().toString();
                            np.name = name;
                            np.apiKey = "";
                            np.baseUrl = "";
                            profiles.add(np);
                            activeProfileId[0] = np.id;
                            setActiveProfileId(client, provider, np.id);
                            saveProfiles(client, provider, profiles);

                            if (actProfile != null) {
                                ArrayAdapter<String> ad = new ArrayAdapter<>(ctx, android.R.layout.simple_list_item_1, getProfileNames(profiles));
                                actProfile.setAdapter(ad);
                                actProfile.setText(np.name, false);
                            }
                            if (etKey != null) etKey.setText("");
                            if (etUrl != null) etUrl.setText("");
                            updateKeyMaskHint(tvKeyMaskHint, "");
                            updateFinalUrlPreview(tvFinalUrl, provider, "");
                            Toast.makeText(ctx, "已创建：" + name, Toast.LENGTH_SHORT).show();
                        })
                        .show();
            });
        }

        if (btnProfileDelete != null) {
            btnProfileDelete.setOnClickListener(v -> {
                if (profiles.size() <= 1) {
                    Toast.makeText(ctx, "至少保留一个配置档案", Toast.LENGTH_SHORT).show();
                    return;
                }
                Profile cur = findProfile(profiles, activeProfileId[0]);
                String curName = cur != null ? cur.name : "当前档案";
                new MaterialAlertDialogBuilder(ctx)
                        .setTitle("删除配置档案")
                        .setMessage("确定删除：" + curName + " ?")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton("删除", (d, w) -> {
                            int idx = indexOfProfile(profiles, activeProfileId[0]);
                            if (idx >= 0) profiles.remove(idx);
                            if (profiles.isEmpty()) {
                                Profile def = new Profile();
                                def.id = "default";
                                def.name = "默认";
                                def.apiKey = "";
                                def.baseUrl = "";
                                profiles.add(def);
                            }
                            Profile newActive = profiles.get(Math.max(0, Math.min(idx, profiles.size() - 1)));
                            activeProfileId[0] = newActive.id;
                            setActiveProfileId(client, provider, newActive.id);
                            saveProfiles(client, provider, profiles);

                            if (actProfile != null) {
                                ArrayAdapter<String> ad = new ArrayAdapter<>(ctx, android.R.layout.simple_list_item_1, getProfileNames(profiles));
                                actProfile.setAdapter(ad);
                                actProfile.setText(newActive.name, false);
                            }

                            if (etKey != null) etKey.setText(safeStr(newActive.apiKey));
                            if (etUrl != null) etUrl.setText(safeStr(newActive.baseUrl));
                            applyProfileToProvider(provider, newActive, baselineKey, baselineUrl);
                            updateKeyMaskHint(tvKeyMaskHint, sanitizeApiKey(textOf(etKey)));
                            updateFinalUrlPreview(tvFinalUrl, provider, textOf(etUrl));

                            Toast.makeText(ctx, "已删除：" + curName, Toast.LENGTH_SHORT).show();
                        })
                        .show();
            });
        }

        if (btnProfileExport != null) {
            btnProfileExport.setOnClickListener(v -> {
                String json = profilesToJsonString(profiles);
                new MaterialAlertDialogBuilder(ctx)
                        .setTitle("导出配置 JSON")
                        .setMessage(json)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton("复制", (d, w) -> {
                            try {
                                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                                if (cm != null) {
                                    cm.setPrimaryClip(ClipData.newPlainText("provider_profiles", json));
                                    Toast.makeText(ctx, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                                }
                            } catch (Throwable ignored) {}
                        })
                        .show();
            });
        }

        if (btnProfileImport != null) {
            btnProfileImport.setOnClickListener(v -> {
                if (importProfilesLauncher == null) {
                    Toast.makeText(ctx, "当前环境不支持文件选择", Toast.LENGTH_SHORT).show();
                    return;
                }
                // Stash references so the launcher callback can update UI + storage.
                pendingImportProvider = provider;
                pendingImportClient = client;
                pendingImportProfiles = profiles;
                pendingImportActiveProfileId = activeProfileId;
                pendingImportActProfile = actProfile;
                pendingImportEtKey = etKey;
                pendingImportEtUrl = etUrl;
                pendingImportKeyMaskHint = tvKeyMaskHint;
                pendingImportFinalUrl = tvFinalUrl;
                pendingImportBaselineKey = baselineKey;
                pendingImportBaselineUrl = baselineUrl;

                try {
                    // Prefer JSON files; allow text fallbacks.
                    importProfilesLauncher.launch(new String[]{"application/json", "text/*"});
                } catch (Throwable t) {
                    Toast.makeText(ctx, "无法打开文件选择器：" + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        // API Key: copy + clear (requested)
        if (btnKeyCopy != null) {
            btnKeyCopy.setOnClickListener(v -> {
                String key = sanitizeApiKey(textOf(etKey));
                if (TextUtils.isEmpty(key)) {
                    Toast.makeText(ctx, "API 密钥为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("api_key", key));
                        Toast.makeText(ctx, "已复制 API 密钥", Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable ignored) {
                }
            });
        }

        if (btnKeyClear != null) {
            btnKeyClear.setOnClickListener(v -> {
                try {
                    if (etKey != null) etKey.setText("");
                } catch (Throwable ignored) {
                }
                updateKeyMaskHint(tvKeyMaskHint, "");
                Toast.makeText(ctx, "已清除", Toast.LENGTH_SHORT).show();
            });
        }

        // Key: auto remove spaces/newlines after paste
        if (etKey != null) {
            etKey.addTextChangedListener(new TextWatcher() {
                boolean editing;
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    if (editing) return;
                    String cur = s != null ? s.toString() : "";
                    String cleaned = sanitizeApiKey(cur);
                    if (!cur.equals(cleaned)) {
                        editing = true;
                        int sel = etKey.getSelectionStart();
                        etKey.setText(cleaned);
                        try {
                            int nsel = Math.min(cleaned.length(), Math.max(0, sel - (cur.length() - cleaned.length())));
                            etKey.setSelection(nsel);
                        } catch (Throwable ignored) {}
                        editing = false;
                    }
                    updateKeyMaskHint(tvKeyMaskHint, cleaned);
                }
            });
        }

        // Base URL: auto remove whitespace, update preview; normalize on blur
        if (etUrl != null) {
            etUrl.addTextChangedListener(new TextWatcher() {
                boolean editing;
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    if (editing) return;
                    String cur = s != null ? s.toString() : "";
                    String cleaned = cur.replaceAll("\\s+", "");
                    if (!cur.equals(cleaned)) {
                        editing = true;
                        int sel = etUrl.getSelectionStart();
                        etUrl.setText(cleaned);
                        try {
                            int nsel = Math.min(cleaned.length(), Math.max(0, sel - (cur.length() - cleaned.length())));
                            etUrl.setSelection(nsel);
                        } catch (Throwable ignored) {}
                        editing = false;
                    }
                    updateFinalUrlPreview(tvFinalUrl, provider, cleaned);
                }
            });
            etUrl.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    String raw = textOf(etUrl);
                    if (!TextUtils.isEmpty(raw)) {
                        try {
                            String n = normalizeBaseUrl(provider, raw);
                            if (!TextUtils.isEmpty(n)) {
                                etUrl.setText(n);
                                updateFinalUrlPreview(tvFinalUrl, provider, n);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            });
        }

        // Initialize hints
        updateKeyMaskHint(tvKeyMaskHint, sanitizeApiKey(textOf(etKey)));
        updateFinalUrlPreview(tvFinalUrl, provider, textOf(etUrl));

        final Dialog dialog = new MaterialAlertDialogBuilder(ctx)
                .setView(root)
                .create();

        // Safety net: if user cancels/back/outside-dismiss, revert to last baseline (last explicit switch/save)
        try {
            dialog.setOnDismissListener(d -> {
                if (!revertOnDismiss[0]) return;
                if (!SPManager.isReady()) return;
                try {
                    SPManager sp = SPManager.getInstance();
                    sp.setApiKey(provider, baselineKey[0] == null ? "" : baselineKey[0]);
                    sp.setBaseUrl(provider, baselineUrl[0] == null ? "" : baselineUrl[0]);
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dismissAllowingStateLoss());
        }

        if (btnTest != null) {
            btnTest.setOnClickListener(v -> {
                if (!SPManager.isReady()) return;

                if (tilKey != null) tilKey.setError(null);
                if (tilUrl != null) tilUrl.setError(null);

                final String apiKey = sanitizeApiKey(textOf(etKey));
                final String baseUrlInput = textOf(etUrl).trim();

                if (TextUtils.isEmpty(apiKey)) {
                    if (tilKey != null) tilKey.setError(ctx.getString(R.string.msg_enter_api_key));
                    return;
                }
                if (TextUtils.isEmpty(baseUrlInput)) {
                    if (tilUrl != null) tilUrl.setError(ctx.getString(R.string.field_base_url));
                    return;
                }

                final String baseUrl;
                try {
                    baseUrl = normalizeBaseUrl(provider, baseUrlInput);
                } catch (Throwable t) {
                    if (tilUrl != null) tilUrl.setError(ctx.getString(R.string.field_base_url));
                    return;
                }

                // UI loading state
                btnTest.setEnabled(false);
                btnTest.setText("测试中…");
                if (btnConfirm != null) btnConfirm.setEnabled(false);
                if (btnCancel != null) btnCancel.setEnabled(false);

                new Thread(() -> {
                    final long started = SystemClock.elapsedRealtime();
                    try {
                        // Only test connection/auth; do NOT cache models and do NOT persist key/url.
                        List<String> models = fetchModelsFromEndpoint(provider, baseUrl, apiKey);
                        final long latency = Math.max(0, SystemClock.elapsedRealtime() - started);
                        try { SPManager.getInstance().setProviderHealth(provider, true, latency, null); } catch (Throwable ignored) {}

                        new Handler(Looper.getMainLooper()).post(() -> {
                            btnTest.setEnabled(true);
                            btnTest.setText("测试连接");
                            if (btnConfirm != null) btnConfirm.setEnabled(true);
                            if (btnCancel != null) btnCancel.setEnabled(true);
                            Toast.makeText(ctx, "连接成功 · " + latency + "ms · " + (models != null ? models.size() : 0) + " 模型", Toast.LENGTH_LONG).show();
                        });
                    } catch (Exception e) {
                        final long latency = Math.max(0, SystemClock.elapsedRealtime() - started);
                        try { SPManager.getInstance().setProviderHealth(provider, false, latency, e.getMessage()); } catch (Throwable ignored) {}
                        final String msg = prettyError(e);
                        new Handler(Looper.getMainLooper()).post(() -> {
                            btnTest.setEnabled(true);
                            btnTest.setText("测试连接");
                            if (btnConfirm != null) btnConfirm.setEnabled(true);
                            if (btnCancel != null) btnCancel.setEnabled(true);
                            Toast.makeText(ctx, "测试失败：" + msg, Toast.LENGTH_LONG).show();
                        });
                    }
                }).start();
            });
        }

        if (btnConfirm != null) {
            btnConfirm.setOnClickListener(v -> {
                if (!SPManager.isReady()) return;

                if (tilKey != null) tilKey.setError(null);
                if (tilUrl != null) tilUrl.setError(null);

                final String apiKey = sanitizeApiKey(textOf(etKey));
                final String baseUrlInput = textOf(etUrl).trim();

                if (TextUtils.isEmpty(apiKey)) {
                    if (tilKey != null) tilKey.setError(ctx.getString(R.string.msg_enter_api_key));
                    return;
                }
                if (TextUtils.isEmpty(baseUrlInput)) {
                    if (tilUrl != null) tilUrl.setError(ctx.getString(R.string.field_base_url));
                    return;
                }

                final String baseUrl;
                try {
                    baseUrl = normalizeBaseUrl(provider, baseUrlInput);
                } catch (Throwable t) {
                    if (tilUrl != null) tilUrl.setError(ctx.getString(R.string.field_base_url));
                    return;
                }
                if (TextUtils.isEmpty(baseUrl)) {
                    if (tilUrl != null) tilUrl.setError(ctx.getString(R.string.field_base_url));
                    return;
                }

                // Update current profile store BEFORE fetching
                Profile cur = findProfile(profiles, activeProfileId[0]);
                if (cur != null) {
                    cur.apiKey = apiKey;
                    cur.baseUrl = baseUrl;
                }
                saveProfiles(client, provider, profiles);
                setActiveProfileId(client, provider, activeProfileId[0]);

                // UI loading
                btnConfirm.setEnabled(false);
                btnConfirm.setText(R.string.fetch_models_loading);
                if (btnCancel != null) btnCancel.setEnabled(false);
                if (btnTest != null) btnTest.setEnabled(false);

                new Thread(() -> {
                    final long started = SystemClock.elapsedRealtime();
                    try {
                        List<String> fetched = fetchModelsFromEndpoint(provider, baseUrl, apiKey);
                        final List<String> models = (fetched != null) ? fetched : new ArrayList<>();
                        final long latency = Math.max(0, SystemClock.elapsedRealtime() - started);

                        // Persist only after successful fetch.
                        SPManager sp = SPManager.getInstance();
                        sp.setApiKey(provider, apiKey);
                        sp.setBaseUrl(provider, baseUrl);
                        sp.setCachedModels(provider, baseUrl, models);
                        try { sp.setProviderHealth(provider, true, latency, null); } catch (Throwable ignored) {}

                        if (!models.isEmpty()) {
                            String first = models.get(0);
                            if (!TextUtils.isEmpty(first)) {
                                sp.setSubModel(provider, first.trim());
                            }
                        }

                        final int count = models.size();
                        new Handler(Looper.getMainLooper()).post(() -> {
                            // Update baseline so dismiss won't revert.
                            baselineKey[0] = apiKey;
                            baselineUrl[0] = baseUrl;
                            revertOnDismiss[0] = false;

                            Toast.makeText(ctx, ctx.getString(R.string.fetch_models_cached, count) + "（" + latency + "ms）", Toast.LENGTH_SHORT).show();
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
                    } catch (Exception e) {
                        // Record last check even when the fetch fails (so UI can show "不可达").
                        try {
                            final long latency = Math.max(0, SystemClock.elapsedRealtime() - started);
                            SPManager.getInstance().setProviderHealth(provider, false, latency, e.getMessage());
                        } catch (Throwable ignored) {
                        }
                        final String msg = prettyError(e);
                        new Handler(Looper.getMainLooper()).post(() -> {
                            btnConfirm.setEnabled(true);
                            btnConfirm.setText(R.string.ok);
                            if (btnCancel != null) btnCancel.setEnabled(true);
                            if (btnTest != null) btnTest.setEnabled(true);
                            Toast.makeText(ctx, "获取模型失败：" + msg, Toast.LENGTH_LONG).show();
                        });
                    }
                }).start();
            });
        }

        return dialog;
    }

    private static void updateKeyMaskHint(@Nullable TextView tv, @NonNull String apiKey) {
        if (tv == null) return;
        if (TextUtils.isEmpty(apiKey)) {
            tv.setText("");
            return;
        }
        if (apiKey.length() <= 8) {
            tv.setText("已输入：" + apiKey);
            return;
        }
        String head = apiKey.substring(0, 4);
        String tail = apiKey.substring(apiKey.length() - 4);
        tv.setText("已输入：" + head + "…" + tail);
    }

    private static void updateFinalUrlPreview(@Nullable TextView tv, @NonNull LanguageModel model, @Nullable String rawBaseUrl) {
        if (tv == null) return;
        String base = rawBaseUrl == null ? "" : rawBaseUrl.trim();
        if (TextUtils.isEmpty(base)) {
            tv.setText("");
            return;
        }
        try {
            String normalized = normalizeBaseUrlStatic(model, base);
            if (TextUtils.isEmpty(normalized)) {
                tv.setText("");
                return;
            }
            String url = normalized;
            while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            url = url + "/models";
            tv.setText("最终请求：" + url);
        } catch (Throwable ignored) {
            tv.setText("");
        }
    }

    private static void applyProfileToProvider(@NonNull LanguageModel provider, @NonNull Profile p, String[] baselineKey, String[] baselineUrl) {
        if (!SPManager.isReady()) return;
        try {
            SPManager sp = SPManager.getInstance();
            String k = sanitizeApiKey(p.apiKey);
            String u = p.baseUrl == null ? "" : p.baseUrl.trim();
            sp.setApiKey(provider, k);
            sp.setBaseUrl(provider, u);
            baselineKey[0] = k;
            baselineUrl[0] = u;
        } catch (Throwable ignored) {}
    }

    private static String safeStr(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static String textOf(@Nullable TextInputEditText et) {
        try {
            return et != null && et.getText() != null ? et.getText().toString() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Remove all whitespace (spaces/newlines) from API key.
     */
    private static String sanitizeApiKey(@Nullable String raw) {
        if (raw == null) return "";
        return raw.replaceAll("\\s+", "");
    }

    /**
     * Normalize base url: add scheme, remove duplicate slashes, fix /api/v1 vs /v1, and append default path when user only enters host.
     */
    private String normalizeBaseUrl(@NonNull LanguageModel model, @Nullable String input) {
        return normalizeBaseUrlStatic(model, input);
    }

    private static String normalizeBaseUrlStatic(@NonNull LanguageModel model, @Nullable String input) {
        String defaultUrl = model.getDefault(LanguageModelField.BaseUrl);
        if (input == null) return defaultUrl;

        String s = input.trim();
        if (s.isEmpty()) return defaultUrl;
        s = s.replaceAll("\\s+", "");

        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://" + s;
        }

        // Trim trailing slashes
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);

        try {
            Uri uri = Uri.parse(s);
            String host = uri.getHost();
            String path = uri.getPath();
            if (path == null) path = "";

            boolean isOpenRouterOfficial = (host != null && (host.equalsIgnoreCase("openrouter.ai") || host.endsWith(".openrouter.ai")));

            // If user only entered host (no path), append default path
            if (path.isEmpty() || "/".equals(path)) {
                String appendPath;
                if (model == LanguageModel.OpenRouter) {
                    appendPath = isOpenRouterOfficial ? "/api/v1" : "/v1";
                } else {
                    Uri defUri = Uri.parse(defaultUrl);
                    appendPath = defUri.getPath();
                    if (TextUtils.isEmpty(appendPath) || "/".equals(appendPath)) appendPath = "/v1";
                }
                path = appendPath;
            }

            // Normalize common mistakes
            String p = path;
            // Remove trailing slashes again
            while (p.endsWith("/")) p = p.substring(0, p.length() - 1);

            // /api/v1 mistake
            if (p.endsWith("/api/v1")) {
                if (model == LanguageModel.OpenRouter && isOpenRouterOfficial) {
                    // keep
                } else {
                    p = p.substring(0, p.length() - "/api/v1".length()) + "/v1";
                }
            }

            // OpenRouter official should be /api/v1 (if user typed /v1)
            if (model == LanguageModel.OpenRouter && isOpenRouterOfficial && p.endsWith("/v1")) {
                p = p.substring(0, p.length() - "/v1".length()) + "/api/v1";
            }

            // Collapse duplicate "//" in path
            p = p.replaceAll("/{2,}", "/");

            Uri out = uri.buildUpon().path(p).build();
            s = out.toString();
        } catch (Throwable ignored) {
            // Fallback: don't crash
        }

        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
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

        String key = sanitizeApiKey(apiKey);
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

    private static String readAll(InputStream is) throws Exception {
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

    private static List<String> parseModelsFromResponse(@NonNull LanguageModel model, String body) {
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

    private static String prettyError(@NonNull Exception e) {
        try {
            if (e instanceof UnknownHostException) return "DNS 解析失败";
            if (e instanceof SocketTimeoutException) return "连接超时";
            if (e instanceof SSLHandshakeException) return "SSL 握手失败";
        } catch (Throwable ignored) {}

        String msg = e.getMessage();
        if (msg == null) msg = e.toString();
        msg = msg.trim();

        if (msg.contains("HTTP 401")) return "鉴权失败（401）";
        if (msg.contains("HTTP 403")) return "无权限（403）";
        if (msg.contains("HTTP 404")) return "路径错误（404）";
        if (msg.contains("HTTP 429")) return "请求过多（429）";
        if (msg.contains("HTTP 5")) return "服务异常（" + msg.substring(msg.indexOf("HTTP")) + "）";

        // Avoid printing full error body if huge
        if (msg.length() > 140) msg = msg.substring(0, 140) + "…";
        return msg;
    }

    private static ArrayList<Profile> loadOrInitProfiles(@NonNull ConfigClient client, @NonNull LanguageModel provider, String legacyKey, String legacyUrl) {
        ArrayList<Profile> out = new ArrayList<>();
        String key = String.format(PREF_PROFILES_JSON, provider.name());
        String raw = client.getString(key, "");
        try {
            if (!TextUtils.isEmpty(raw)) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    out.add(Profile.fromJson(o));
                }
            }
        } catch (Throwable ignored) {
            out.clear();
        }

        if (out.isEmpty()) {
            // Migrate legacy single config into default profile
            Profile def = new Profile();
            def.id = "default";
            def.name = "默认";
            def.apiKey = sanitizeApiKey(legacyKey);
            def.baseUrl = legacyUrl == null ? "" : legacyUrl.trim();
            out.add(def);
            saveProfiles(client, provider, out);
            setActiveProfileId(client, provider, def.id);
        }

        // Ensure each profile has an id
        for (Profile p : out) {
            if (TextUtils.isEmpty(p.id)) p.id = UUID.randomUUID().toString();
            if (TextUtils.isEmpty(p.name)) p.name = "默认";
            if (p.apiKey == null) p.apiKey = "";
            if (p.baseUrl == null) p.baseUrl = "";
        }

        saveProfiles(client, provider, out);
        return out;
    }

    private static void saveProfiles(@NonNull ConfigClient client, @NonNull LanguageModel provider, @NonNull List<Profile> profiles) {
        try {
            JSONArray arr = new JSONArray();
            for (Profile p : profiles) {
                if (p == null) continue;
                arr.put(p.toJson());
            }
            String key = String.format(PREF_PROFILES_JSON, provider.name());
            client.putString(key, arr.toString());
        } catch (Throwable ignored) {}
    }

    private static String getActiveProfileId(@NonNull ConfigClient client, @NonNull LanguageModel provider, @NonNull List<Profile> profiles) {
        try {
            String k = String.format(PREF_PROFILES_ACTIVE, provider.name());
            String id = client.getString(k, "");
            if (!TextUtils.isEmpty(id) && findProfile(profiles, id) != null) return id;
        } catch (Throwable ignored) {}
        return profiles.isEmpty() ? "default" : profiles.get(0).id;
    }

    private static void setActiveProfileId(@NonNull ConfigClient client, @NonNull LanguageModel provider, @NonNull String id) {
        try {
            String k = String.format(PREF_PROFILES_ACTIVE, provider.name());
            client.putString(k, id);
        } catch (Throwable ignored) {}
    }

    private static Profile findProfile(@NonNull List<Profile> profiles, @Nullable String id) {
        if (id == null) return null;
        for (Profile p : profiles) {
            if (p == null) continue;
            if (id.equals(p.id)) return p;
        }
        return null;
    }

    private static int indexOfProfile(@NonNull List<Profile> profiles, @Nullable String id) {
        if (id == null) return -1;
        for (int i = 0; i < profiles.size(); i++) {
            Profile p = profiles.get(i);
            if (p != null && id.equals(p.id)) return i;
        }
        return -1;
    }

    private static ArrayList<String> getProfileNames(@NonNull List<Profile> profiles) {
        ArrayList<String> names = new ArrayList<>();
        for (Profile p : profiles) {
            if (p == null) continue;
            names.add(TextUtils.isEmpty(p.name) ? "默认" : p.name);
        }
        return names;
    }

    private static String getProfileNameById(@NonNull List<Profile> profiles, @Nullable String id) {
        Profile p = findProfile(profiles, id);
        return p != null ? p.name : (profiles.isEmpty() ? "默认" : profiles.get(0).name);
    }

    private static String profilesToJsonString(@NonNull List<Profile> profiles) {
        try {
            JSONArray arr = new JSONArray();
            for (Profile p : profiles) {
                if (p == null) continue;
                arr.put(p.toJson());
            }
            return arr.toString(2);
        } catch (Throwable t) {
            return "[]";
        }
    }

    private static ArrayList<Profile> parseProfilesJson(@NonNull String raw) throws Exception {
        ArrayList<Profile> out = new ArrayList<>();
        String s = raw.trim();
        if (s.isEmpty()) return out;
        JSONArray arr = new JSONArray(s);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Profile p = Profile.fromJson(o);
            if (TextUtils.isEmpty(p.id)) p.id = UUID.randomUUID().toString();
            if (TextUtils.isEmpty(p.name)) p.name = "默认";
            if (p.apiKey == null) p.apiKey = "";
            if (p.baseUrl == null) p.baseUrl = "";
            out.add(p);
        }
        return out;
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

    /**
     * Import provider profiles from a local JSON file.
     * This is triggered by the SAF OpenDocument launcher.
     */
    private void handleImportProfilesFromUri(@NonNull Uri uri) {
        if (!isAdded()) return;
        final Context ctx = requireContext();
        final LanguageModel provider = pendingImportProvider;
        final ConfigClient client = pendingImportClient;
        final ArrayList<Profile> profiles = pendingImportProfiles;
        final String[] activeProfileId = pendingImportActiveProfileId;

        if (provider == null || client == null || profiles == null || activeProfileId == null) {
            Toast.makeText(ctx, "导入失败：状态丢失", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String raw = readAllFromUri(ctx, uri);
            ArrayList<Profile> imported = parseProfilesJson(raw);
            if (imported.isEmpty()) {
                Toast.makeText(ctx, "导入失败：空配置", Toast.LENGTH_SHORT).show();
                return;
            }

            profiles.clear();
            profiles.addAll(imported);

            // Keep active if possible
            String newActiveId = getActiveProfileId(client, provider, profiles);
            activeProfileId[0] = newActiveId;
            setActiveProfileId(client, provider, newActiveId);
            saveProfiles(client, provider, profiles);

            Profile cur = findProfile(profiles, activeProfileId[0]);
            if (cur == null) cur = profiles.get(0);
            activeProfileId[0] = cur.id;
            setActiveProfileId(client, provider, cur.id);

            // Update UI
            if (pendingImportActProfile != null) {
                ArrayAdapter<String> ad = new ArrayAdapter<>(ctx, android.R.layout.simple_list_item_1, getProfileNames(profiles));
                pendingImportActProfile.setAdapter(ad);
                pendingImportActProfile.setText(cur.name, false);
            }

            if (pendingImportEtKey != null) pendingImportEtKey.setText(safeStr(cur.apiKey));
            if (pendingImportEtUrl != null) pendingImportEtUrl.setText(safeStr(cur.baseUrl));

            try {
                applyProfileToProvider(provider, cur,
                        pendingImportBaselineKey != null ? pendingImportBaselineKey : new String[]{""},
                        pendingImportBaselineUrl != null ? pendingImportBaselineUrl : new String[]{""});
            } catch (Throwable ignored) {
            }

            try { updateKeyMaskHint(pendingImportKeyMaskHint, sanitizeApiKey(textOf(pendingImportEtKey))); } catch (Throwable ignored) {}
            try { updateFinalUrlPreview(pendingImportFinalUrl, provider, textOf(pendingImportEtUrl)); } catch (Throwable ignored) {}

            Toast.makeText(ctx, "导入成功：" + profiles.size() + " 个档案", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(ctx, "导入失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String readAllFromUri(@NonNull Context ctx, @NonNull Uri uri) throws Exception {
        InputStream is = null;
        try {
            is = ctx.getContentResolver().openInputStream(uri);
            if (is == null) return "";
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            return sb.toString();
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignored) {}
        }
    }
}
