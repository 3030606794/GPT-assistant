package tn.eluea.kgpt.ui.main.fragments;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import tn.eluea.kgpt.R;

/**
 * Simple in-app console (WebView) page.
 *
 * Default URL points to a typical provider console, but the user can edit it.
 * Stored in SharedPreferences "keyboard_gpt_ui" under key "console_url".
 */
public class ConsoleFragment extends Fragment {

    private static final String PREFS_UI = "keyboard_gpt_ui";
    private static final String KEY_CONSOLE_URL = "console_url";

    // Reasonable default for OpenAI-compatible proxies. User can edit.
    private static final String DEFAULT_CONSOLE_URL = "https://www.yunqiaoai.top/console";

    private WebView webView;
    private ProgressBar progressBar;
    private TextView tvUrl;
    private ImageView btnEdit;
    private ImageView btnRefresh;
    private ImageView btnOpenExternal;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_console, container, false);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvUrl = view.findViewById(R.id.console_url_text);
        btnEdit = view.findViewById(R.id.console_btn_edit);
        btnRefresh = view.findViewById(R.id.console_btn_refresh);
        btnOpenExternal = view.findViewById(R.id.console_btn_open_external);
        progressBar = view.findViewById(R.id.console_progress);
        webView = view.findViewById(R.id.console_webview);

        String url = getConsoleUrl();
        tvUrl.setText(url);

        // WebView basic hardening + compatibility
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);

        // Some consoles require mixed content (assets/CDN).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progressBar == null) return;
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
                progressBar.setProgress(newProgress);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                if (scheme != null && (scheme.equals("http") || scheme.equals("https"))) {
                    return false; // keep inside
                }
                // Open non-http(s) links externally
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }
        });

        btnRefresh.setOnClickListener(v -> webView.reload());

        btnOpenExternal.setOnClickListener(v -> {
            Uri u = Uri.parse(tvUrl.getText().toString().trim());
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (Exception ignored) { }
        });

        btnEdit.setOnClickListener(v -> showEditDialog());

        // Load
        webView.loadUrl(url);
    }

    private String getConsoleUrl() {
        if (getContext() == null) return DEFAULT_CONSOLE_URL;
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE);
        String u = prefs.getString(KEY_CONSOLE_URL, DEFAULT_CONSOLE_URL);
        if (u == null || u.trim().isEmpty()) return DEFAULT_CONSOLE_URL;
        return u.trim();
    }

    private void setConsoleUrl(String url) {
        if (getContext() == null) return;
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_UI, android.content.Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_CONSOLE_URL, url).apply();
    }

    private void showEditDialog() {
        if (getContext() == null) return;

        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.dialog_edit_console_url, null);
        TextInputLayout til = dialogView.findViewById(R.id.console_url_input_layout);
        TextInputEditText et = dialogView.findViewById(R.id.console_url_input);
        et.setText(tvUrl.getText());

        new MaterialAlertDialogBuilder(getContext())
                .setTitle(R.string.console_title)
                .setView(dialogView)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String u = et.getText() == null ? "" : et.getText().toString().trim();
                    if (!u.isEmpty()) {
                        setConsoleUrl(u);
                        tvUrl.setText(u);
                        if (webView != null) webView.loadUrl(u);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.setWebChromeClient(null);
                webView.setWebViewClient(null);
                webView.destroy();
            } catch (Exception ignored) { }
            webView = null;
        }
        progressBar = null;
        tvUrl = null;
        btnEdit = null;
        btnRefresh = null;
        btnOpenExternal = null;
    }
}
