package tn.eluea.kgpt.ui.chat;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.util.LocaleHelper;

/**
 * A sessions list page for the in-app AI chat.
 *
 * Allows switching, creating, renaming and deleting chat sessions.
 */
public class ChatSessionsActivity extends AppCompatActivity {

    public static final String EXTRA_SELECTED_SESSION_ID = "selected_session_id";

    private static final String PREFS_NAME = "kgpt_ai_chat";
    private static final String KEY_SESSIONS_JSON = "sessions_json";
    private static final String KEY_ACTIVE_SESSION_ID = "active_session_id";

    private static final int MAX_SAVED_SESSIONS = 20;
    private static final int MAX_SAVED_MESSAGES_PER_SESSION = 200;

    private MaterialToolbar toolbar;
    private RecyclerView rv;
    private FloatingActionButton fab;

    private final List<ChatSession> sessions = new ArrayList<>();
    private ChatSessionsAdapter adapter;
    private String activeId;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        setContentView(R.layout.activity_chat_sessions);

        toolbar = findViewById(R.id.toolbar_chat_sessions);
        rv = findViewById(R.id.rv_chat_sessions);
        fab = findViewById(R.id.fab_new_chat);

        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        applyInsets();
        restore();
        setupRecycler();

        fab.setOnClickListener(v -> createNewSessionAndReturn());
    }

    private void applyInsets() {
        // Toolbar top padding (status bars)
        if (toolbar != null) {
            final int pl = toolbar.getPaddingLeft();
            final int pt = toolbar.getPaddingTop();
            final int pr = toolbar.getPaddingRight();
            final int pb = toolbar.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, insets) -> {
                int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
                v.setPadding(pl, pt + top, pr, pb);
                return insets;
            });
            ViewCompat.requestApplyInsets(toolbar);
        }

        // Content bottom padding (nav bars)
        View content = findViewById(R.id.content_chat_sessions);
        if (content != null) {
            final int pl = content.getPaddingLeft();
            final int pt = content.getPaddingTop();
            final int pr = content.getPaddingRight();
            final int pb = content.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
                int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                v.setPadding(pl, pt, pr, pb + bottom);
                return insets;
            });
            ViewCompat.requestApplyInsets(content);
        }
    }

    private void setupRecycler() {
        adapter = new ChatSessionsAdapter(sessions, new ChatSessionsAdapter.Listener() {
            @Override
            public void onSelect(ChatSession session) {
                returnWithSession(session == null ? null : session.id);
            }

            @Override
            public void onMore(View anchor, ChatSession session) {
                showMoreMenu(anchor, session);
            }
        });
        adapter.setActiveSessionId(activeId);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
    }

    private void showMoreMenu(View anchor, ChatSession session) {
        if (session == null) return;
        androidx.appcompat.widget.PopupMenu pm = new androidx.appcompat.widget.PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, R.string.ui_chat_sessions_rename);
        pm.getMenu().add(0, 2, 1, R.string.ui_chat_sessions_delete);
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                renameSession(session);
                return true;
            } else if (item.getItemId() == 2) {
                deleteSession(session);
                return true;
            }
            return false;
        });
        pm.show();
    }

    private void renameSession(ChatSession session) {
        final EditText et = new EditText(this);
        et.setText(session.title == null ? "" : session.title);
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(this)
                .setTitle(R.string.ui_chat_sessions_rename)
                .setView(et)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String t = et.getText() == null ? "" : et.getText().toString().trim();
                    if (t.isEmpty()) t = getString(R.string.ui_ai_chat_new_session_title);
                    session.title = t;
                    session.touch();
                    sortSessions();
                    save();
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteSession(ChatSession session) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.ui_chat_sessions_delete)
                .setMessage(R.string.ui_chat_sessions_delete_confirm)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String deletingId = session.id;
                    sessions.remove(session);
                    if (sessions.isEmpty()) {
                        // Always keep at least one
                        ChatSession s = new ChatSession(UUID.randomUUID().toString(), System.currentTimeMillis(), getString(R.string.ui_ai_chat_new_session_title));
                        s.touch();
                        sessions.add(s);
                    }
                    if (!TextUtils.isEmpty(activeId) && activeId.equals(deletingId)) {
                        activeId = sessions.get(0).id;
                    }
                    sortSessions();
                    save();
                    adapter.setActiveSessionId(activeId);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void createNewSessionAndReturn() {
        ChatSession s = new ChatSession(UUID.randomUUID().toString(), System.currentTimeMillis(), getString(R.string.ui_ai_chat_new_session_title));
        s.touch();
        sessions.add(0, s);
        activeId = s.id;
        sortSessions();
        save();
        returnWithSession(s.id);
    }

    private void returnWithSession(String sessionId) {
        Intent data = new Intent();
        data.putExtra(EXTRA_SELECTED_SESSION_ID, sessionId);
        setResult(RESULT_OK, data);
        finish();
    }

    private void restore() {
        sessions.clear();
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        activeId = sp.getString(KEY_ACTIVE_SESSION_ID, null);

        String sessionsJson = sp.getString(KEY_SESSIONS_JSON, null);
        try {
            if (!TextUtils.isEmpty(sessionsJson)) {
                JSONArray arr = new JSONArray(sessionsJson);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    ChatSession s = ChatSession.fromJson(o);
                    if (s == null || TextUtils.isEmpty(s.id)) continue;
                    if (TextUtils.isEmpty(s.title)) s.title = getString(R.string.ui_ai_chat_new_session_title);
                    if (s.updatedAt <= 0) s.updatedAt = s.createdAt;
                    sessions.add(s);
                }
            }
        } catch (Throwable ignored) {
        }
        if (sessions.isEmpty()) {
            ChatSession s = new ChatSession(UUID.randomUUID().toString(), System.currentTimeMillis(), getString(R.string.ui_ai_chat_new_session_title));
            s.touch();
            sessions.add(s);
            activeId = s.id;
            save();
        }
        if (TextUtils.isEmpty(activeId)) {
            activeId = sessions.get(0).id;
        }
        sortSessions();
    }

    private void sortSessions() {
        Collections.sort(sessions, new Comparator<ChatSession>() {
            @Override
            public int compare(ChatSession a, ChatSession b) {
                long ta = a.updatedAt > 0 ? a.updatedAt : a.createdAt;
                long tb = b.updatedAt > 0 ? b.updatedAt : b.createdAt;
                return Long.compare(tb, ta);
            }
        });
    }

    private void save() {
        try {
            while (sessions.size() > MAX_SAVED_SESSIONS) {
                sessions.remove(sessions.size() - 1);
            }
            JSONArray arr = new JSONArray();
            for (ChatSession s : sessions) {
                arr.put(s.toJson(MAX_SAVED_MESSAGES_PER_SESSION));
            }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SESSIONS_JSON, arr.toString())
                    .putString(KEY_ACTIVE_SESSION_ID, activeId)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
