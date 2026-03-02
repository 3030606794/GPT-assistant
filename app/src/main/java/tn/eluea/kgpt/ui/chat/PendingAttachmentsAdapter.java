package tn.eluea.kgpt.ui.chat;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.List;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.util.SafeImageLoader;

/**
 * Pending attachments shown above the input box.
 *
 * v13: Large image preview + file detail card + drag-to-reorder.
 *      (Per-attachment note fields were removed to prevent focus stealing.)
 */
public class PendingAttachmentsAdapter extends RecyclerView.Adapter<PendingAttachmentsAdapter.VH> {

    public interface Listener {
        void onRemove(int position);
        void onOpen(int position);
        void onStartDrag(RecyclerView.ViewHolder holder);
    }

    private final List<PendingAttachment> items;
    private final Listener listener;

    public PendingAttachmentsAdapter(List<PendingAttachment> items, Listener listener) {
        this.items = items;
        this.listener = listener;
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        try {
            PendingAttachment a = items.get(position);
            return (a.kind.name() + "|" + a.uri).hashCode();
        } catch (Throwable ignored) {
            return super.getItemId(position);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_ai_chat_pending_attachment, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        PendingAttachment a = items.get(position);

        h.layoutImage.setVisibility(View.GONE);
        h.layoutFile.setVisibility(View.GONE);

        String name = a.safeName();
        String size = formatBytes(a.sizeBytes);
        String mime = (a.mime == null ? "" : a.mime.trim());
        if (mime.isEmpty()) mime = "-";

        if (a.kind == PendingAttachment.Kind.IMAGE) {
            h.layoutImage.setVisibility(View.VISIBLE);
            h.tvMeta.setText(h.itemView.getContext().getString(R.string.ui_ai_chat_pending_image_meta_fmt, name, size));
            bindSampledImage(h.itemView.getContext(), Uri.parse(a.uri), h.ivImage);
        } else {
            h.layoutFile.setVisibility(View.VISIBLE);
            h.tvFileName.setText(name);
            h.tvFileMeta.setText(h.itemView.getContext().getString(R.string.ui_ai_chat_pending_file_meta_fmt, size, mime));
            if (!TextUtils.isEmpty(a.preview)) {
                h.tvFilePreview.setText(a.preview);
                h.tvFilePreview.setVisibility(View.VISIBLE);
            } else {
                h.tvFilePreview.setVisibility(View.GONE);
            }
            h.tvMeta.setText(h.itemView.getContext().getString(R.string.ui_ai_chat_pending_file_title));
        }

        h.btnRemove.setOnClickListener(v -> {
            if (listener != null) listener.onRemove(h.getBindingAdapterPosition());
        });

        // Click to open (image/file)
        View openTarget = (a.kind == PendingAttachment.Kind.IMAGE) ? h.ivImage : h.layoutFile;
        openTarget.setOnClickListener(v -> {
            if (listener != null) listener.onOpen(h.getBindingAdapterPosition());
        });

        // Drag handle
        h.ivDragHandle.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (listener != null) listener.onStartDrag(h);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return items == null ? 0 : items.size();
    }

    /** ItemTouchHelper callback uses this. */
    public boolean onItemMove(int fromPosition, int toPosition) {
        if (items == null) return false;
        if (fromPosition < 0 || toPosition < 0) return false;
        if (fromPosition >= items.size() || toPosition >= items.size()) return false;
        if (fromPosition == toPosition) return false;
        Collections.swap(items, fromPosition, toPosition);
        notifyItemMoved(fromPosition, toPosition);
        return true;
    }

    // --- helpers ---

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "-";
        final String[] units = new String[]{"B", "KB", "MB", "GB"};
        double v = bytes;
        int idx = 0;
        while (v >= 1024 && idx < units.length - 1) {
            v /= 1024.0;
            idx++;
        }
        if (idx == 0) return String.format("%d %s", (long) v, units[idx]);
        return String.format("%.1f %s", v, units[idx]);
    }

    /**
     * Load a sampled bitmap for preview to reduce OOM risk.
     */
    private static void bindSampledImage(Context ctx, Uri uri, ImageView iv) {
        // IMPORTANT: do NOT fallback to ImageView#setImageURI().
        // If the Uri permission has expired, ImageView will crash later during layout/measure.
        SafeImageLoader.loadSampledInto(ctx, uri, iv, 1080, R.drawable.ic_warning_filled);
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView ivDragHandle;
        final ImageButton btnRemove;
        final TextView tvMeta;

        // Image
        final LinearLayout layoutImage;
        final ImageView ivImage;

        // File
        final LinearLayout layoutFile;
        final TextView tvFileName;
        final TextView tvFileMeta;
        final TextView tvFilePreview;

        VH(@NonNull View itemView) {
            super(itemView);
            ivDragHandle = itemView.findViewById(R.id.iv_pending_drag);
            btnRemove = itemView.findViewById(R.id.btn_pending_remove);
            tvMeta = itemView.findViewById(R.id.tv_pending_meta);

            layoutImage = itemView.findViewById(R.id.layout_pending_image);
            ivImage = itemView.findViewById(R.id.iv_pending_image);

            layoutFile = itemView.findViewById(R.id.layout_pending_file);
            tvFileName = itemView.findViewById(R.id.tv_pending_file_name);
            tvFileMeta = itemView.findViewById(R.id.tv_pending_file_meta);
            tvFilePreview = itemView.findViewById(R.id.tv_pending_file_preview);
        }
    }
}
