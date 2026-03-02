package tn.eluea.kgpt.ui.chat;

import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.util.SafeImageLoader;

public class ChatMessagesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_USER = 1;
    private static final int TYPE_ASSISTANT = 2;

    private List<ChatMessage> items;

    public ChatMessagesAdapter(List<ChatMessage> items) {
        this.items = items;
        setHasStableIds(false);
    }

    public void setItems(List<ChatMessage> items) {
        this.items = items;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        ChatMessage m = items.get(position);
        return m.getRole() == ChatMessage.Role.USER ? TYPE_USER : TYPE_ASSISTANT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_USER) {
            View v = inflater.inflate(R.layout.item_chat_message_user, parent, false);
            return new MsgVH(v);
        }
        View v = inflater.inflate(R.layout.item_chat_message_assistant, parent, false);
        return new MsgVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatMessage msg = items.get(position);
        MsgVH vh = (MsgVH) holder;

        // Reset
        vh.ivAttachment.setVisibility(View.GONE);
        vh.fileCard.setVisibility(View.GONE);

        // Attachment
        if (msg.getKind() == ChatMessage.Kind.IMAGE && !TextUtils.isEmpty(msg.getUri())) {
            vh.ivAttachment.setVisibility(View.VISIBLE);
            Uri u = null;
            try { u = Uri.parse(msg.getUri()); } catch (Throwable ignored) {}
            // IMPORTANT: do NOT use setImageURI() here.
            // If the Uri permission has expired, ImageView will throw SecurityException during layout and crash.
            SafeImageLoader.loadSampledInto(vh.ivAttachment.getContext(), u, vh.ivAttachment, 1080, R.drawable.ic_warning_filled);
            vh.ivAttachment.setOnClickListener(v -> openUri(v, msg.getUri(), msg.getMime()));
        } else if (msg.getKind() == ChatMessage.Kind.FILE && !TextUtils.isEmpty(msg.getUri())) {
            vh.fileCard.setVisibility(View.VISIBLE);
            String fileName = msg.getName();
            if (TextUtils.isEmpty(fileName)) fileName = vText(vh.tvFileName, R.string.ui_ai_chat_file_generic);
            vh.tvFileName.setText(fileName);
            String preview = safe(msg.getPreview(), "");
            vh.tvFilePreview.setText(preview);
            vh.fileCard.setOnClickListener(v -> openUri(v, msg.getUri(), msg.getMime()));
        }

        // Text / caption
        String text = safe(msg.getText(), "").trim();
        if (text.isEmpty()) {
            vh.tv.setVisibility(View.GONE);
        } else {
            vh.tv.setVisibility(View.VISIBLE);
            vh.tv.setText(text);
        }
    }

    private String vText(TextView tv, int resId) {
        try {
            return tv.getContext().getString(resId);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String safe(String s, String fallback) {
        return s == null ? fallback : s;
    }

    private void openUri(View v, String uriStr, String mime) {
        try {
            Uri uri = Uri.parse(uriStr);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            if (!TextUtils.isEmpty(mime)) {
                intent.setDataAndType(uri, mime);
            } else {
                intent.setData(uri);
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            v.getContext().startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int getItemCount() {
        return items == null ? 0 : items.size();
    }

    static class MsgVH extends RecyclerView.ViewHolder {
        final TextView tv;
        final ImageView ivAttachment;

        final View fileCard;
        final TextView tvFileName;
        final TextView tvFilePreview;

        MsgVH(@NonNull View itemView) {
            super(itemView);
            tv = itemView.findViewById(R.id.tv_message);
            ivAttachment = itemView.findViewById(R.id.iv_attachment);
            fileCard = itemView.findViewById(R.id.card_file);
            tvFileName = itemView.findViewById(R.id.tv_file_name);
            tvFilePreview = itemView.findViewById(R.id.tv_file_preview);
        }
    }
}
