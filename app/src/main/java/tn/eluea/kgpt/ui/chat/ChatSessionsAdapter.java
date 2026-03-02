package tn.eluea.kgpt.ui.chat;

import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import tn.eluea.kgpt.R;

public class ChatSessionsAdapter extends RecyclerView.Adapter<ChatSessionsAdapter.VH> {

    public interface Listener {
        void onSelect(ChatSession session);
        void onMore(View anchor, ChatSession session);
    }

    private final List<ChatSession> sessions;
    private final Listener listener;
    private String activeSessionId;

    public ChatSessionsAdapter(List<ChatSession> sessions, Listener listener) {
        this.sessions = sessions;
        this.listener = listener;
    }

    public void setActiveSessionId(String id) {
        this.activeSessionId = id;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_session, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ChatSession s = sessions.get(position);
        String title = s.title == null ? "" : s.title.trim();
        if (title.isEmpty()) title = h.itemView.getContext().getString(R.string.ui_ai_chat_new_session_title);

        h.tvTitle.setText(title);

        String summary = s.getLastSummary();
        h.tvSummary.setText(summary);

        long t = s.updatedAt > 0 ? s.updatedAt : s.createdAt;
        CharSequence rel = DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        h.tvTime.setText(rel);

        boolean isCurrent = !TextUtils.isEmpty(activeSessionId) && activeSessionId.equals(s.id);
        h.tvCurrent.setVisibility(isCurrent ? View.VISIBLE : View.GONE);

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onSelect(s);
        });
        h.btnMore.setOnClickListener(v -> {
            if (listener != null) listener.onMore(v, s);
        });
    }

    @Override
    public int getItemCount() {
        return sessions == null ? 0 : sessions.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvTitle;
        final TextView tvSummary;
        final TextView tvTime;
        final TextView tvCurrent;
        final ImageButton btnMore;

        VH(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tv_session_title);
            tvSummary = itemView.findViewById(R.id.tv_session_summary);
            tvTime = itemView.findViewById(R.id.tv_session_time);
            tvCurrent = itemView.findViewById(R.id.tv_session_current);
            btnMore = itemView.findViewById(R.id.btn_session_more);
        }
    }
}
