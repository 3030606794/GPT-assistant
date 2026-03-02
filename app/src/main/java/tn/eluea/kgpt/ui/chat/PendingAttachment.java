package tn.eluea.kgpt.ui.chat;

import android.text.TextUtils;

/**
 * A pending attachment that is selected but not yet sent.
 * We keep only lightweight metadata here. Heavy data (e.g. image base64)
 * is generated only when the user presses Send.
 */
public class PendingAttachment {
    public enum Kind { IMAGE, FILE }

    public final Kind kind;
    public final String uri;
    public final String mime;
    public final String name;
    public final long sizeBytes;

    // For files: a short preview for UI only (optional)
    public final String preview;

    /**
     * Editable description (caption) before sending.
     * For images: question / note about this image.
     * For files: question / note about this file.
     */
    public String note;

    public PendingAttachment(Kind kind, String uri, String mime, String name, long sizeBytes, String preview, String note) {
        this.kind = kind;
        this.uri = uri;
        this.mime = mime;
        this.name = name;
        this.sizeBytes = sizeBytes;
        this.preview = preview;
        this.note = note;
    }

    public String safeName() {
        if (!TextUtils.isEmpty(name)) return name;
        return kind == Kind.IMAGE ? "image" : "file";
    }
}
