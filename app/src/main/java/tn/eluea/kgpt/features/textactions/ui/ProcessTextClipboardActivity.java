package tn.eluea.kgpt.features.textactions.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.clipboard.AIClipboardStore;
import tn.eluea.kgpt.util.LocaleHelper;

/**
 * Entry point for ACTION_PROCESS_TEXT that allows picking an item from KGPT's AI Clipboard.
 * Returns the selected clipboard item text to the calling app via setResult()
 * (Intent.EXTRA_PROCESS_TEXT) so it replaces the selected text.
 *
 * If the caller marks the text as readonly (EXTRA_PROCESS_TEXT_READONLY), we cannot replace
 * the selection. In that case, we copy the chosen entry to the system clipboard instead.
 */
public class ProcessTextClipboardActivity extends AppCompatActivity {

    private boolean isReadonly = false;
    private boolean favoritesOnly = false;

    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private TextInputEditText etSearch;

    private final List<AIClipboardStore.Entry> allEntries = new ArrayList<>();
    private final List<AIClipboardStore.Entry> filteredEntries = new ArrayList<>();
    private ClipboardAdapter adapter;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_process_text_clipboard);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.ui_ai_clipboard);
        toolbar.setNavigationIcon(R.drawable.ic_arrow_left);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_process_text_clipboard);
        toolbar.setOnMenuItemClickListener(this::onToolbarMenuItem);

        recyclerView = findViewById(R.id.recycler);
        tvEmpty = findViewById(R.id.tv_empty);
        etSearch = findViewById(R.id.et_search);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ClipboardAdapter(filteredEntries, this::onEntryClicked);
        recyclerView.setAdapter(adapter);

        handleIntent(getIntent());
        setupSearch();
        reloadData();
        updateFavIcon(toolbar.getMenu().findItem(R.id.action_favorites));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
        reloadData();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_PROCESS_TEXT.equals(intent.getAction())) {
            isReadonly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false);
            // The selected text is not needed for clipboard picking, but some apps may pass null.
            // We tolerate that and still show clipboard.
        }
    }

    private void setupSearch() {
        if (etSearch == null) return;
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter(s != null ? s.toString() : "");
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private boolean onToolbarMenuItem(MenuItem item) {
        if (item == null) return false;
        int id = item.getItemId();
        if (id == R.id.action_favorites) {
            favoritesOnly = !favoritesOnly;
            updateFavIcon(item);
            reloadData();
            return true;
        }
        return false;
    }

    private void updateFavIcon(MenuItem favItem) {
        if (favItem == null) return;
        favItem.setIcon(favoritesOnly ? R.drawable.ic_star_filled : R.drawable.ic_star);
        favItem.setTitle(R.string.ui_favorites);
    }

    private void reloadData() {
        allEntries.clear();
        try {
            List<AIClipboardStore.Entry> entries = favoritesOnly
                    ? AIClipboardStore.getFavoriteEntries(this)
                    : AIClipboardStore.getEntries(this);
            if (entries != null) allEntries.addAll(entries);
        } catch (Throwable ignored) {}

        String q = etSearch != null && etSearch.getText() != null ? etSearch.getText().toString() : "";
        applyFilter(q);
    }

    private void applyFilter(String query) {
        filteredEntries.clear();
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            filteredEntries.addAll(allEntries);
        } else {
            String lower = q.toLowerCase(Locale.ROOT);
            for (AIClipboardStore.Entry e : allEntries) {
                if (e == null || e.text == null) continue;
                if (e.text.toLowerCase(Locale.ROOT).contains(lower)) {
                    filteredEntries.add(e);
                }
            }
        }

        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void updateEmptyState() {
        boolean empty = filteredEntries.isEmpty();
        if (tvEmpty != null) tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (recyclerView != null) recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void onEntryClicked(AIClipboardStore.Entry entry) {
        if (entry == null) return;
        String text = entry.text != null ? entry.text : "";
        if (text.trim().isEmpty()) return;

        if (isReadonly) {
            copyToSystemClipboard(text);
            Toast.makeText(this, getString(R.string.ui_text_actions_copied), Toast.LENGTH_SHORT).show();
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        Intent resultIntent = new Intent();
        resultIntent.putExtra(Intent.EXTRA_PROCESS_TEXT, text);
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    private void copyToSystemClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("KGPT", text));
            }
        } catch (Throwable ignored) {}
    }

    // ---------------- Recycler Adapter ----------------

    private interface EntryClickListener {
        void onClick(AIClipboardStore.Entry entry);
    }

    private static final class ClipboardVH extends RecyclerView.ViewHolder {
        final TextView tvText;
        final TextView tvTime;
        final ImageView ivFav;

        ClipboardVH(@NonNull View itemView) {
            super(itemView);
            tvText = itemView.findViewById(R.id.tv_text);
            tvTime = itemView.findViewById(R.id.tv_time);
            ivFav = itemView.findViewById(R.id.iv_fav);
        }
    }

    private static final class ClipboardAdapter extends RecyclerView.Adapter<ClipboardVH> {
        private final List<AIClipboardStore.Entry> entries;
        private final EntryClickListener listener;

        ClipboardAdapter(List<AIClipboardStore.Entry> entries, EntryClickListener listener) {
            this.entries = entries;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ClipboardVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = android.view.LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_process_text_clipboard, parent, false);
            return new ClipboardVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ClipboardVH holder, int position) {
            AIClipboardStore.Entry e = entries.get(position);
            String text = e != null && e.text != null ? e.text : "";
            holder.tvText.setText(text);

            if (e != null && e.timeMs > 0) {
                CharSequence ts = DateFormat.format("yyyy-MM-dd HH:mm", e.timeMs);
                holder.tvTime.setText(ts);
                holder.tvTime.setVisibility(View.VISIBLE);
            } else {
                holder.tvTime.setVisibility(View.GONE);
            }

            if (holder.ivFav != null) {
                boolean fav = e != null && e.favorite;
                holder.ivFav.setVisibility(fav ? View.VISIBLE : View.GONE);
            }

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onClick(e);
            });
        }

        @Override
        public int getItemCount() {
            return entries != null ? entries.size() : 0;
        }
    }
}
