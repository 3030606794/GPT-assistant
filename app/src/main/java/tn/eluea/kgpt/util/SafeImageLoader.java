package tn.eluea.kgpt.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.widget.ImageView;

import java.io.InputStream;

import tn.eluea.kgpt.R;

/**
 * Loads images from content/file URIs safely.
 *
 * Why this exists:
 * ImageView#setImageURI() resolves the Uri later during layout/measure.
 * If the Uri permission has expired (common for DocumentProvider Uris after app restart),
 * ImageView will throw a SecurityException on the UI thread and crash the app.
 *
 * We avoid that by explicitly attempting to open/decode the Uri first.
 */
public final class SafeImageLoader {

    private SafeImageLoader() {}

    public static void loadSampledInto(Context ctx, Uri uri, ImageView iv, int reqMaxDim, int placeholderResId) {
        if (iv == null) return;
        if (ctx == null || uri == null) {
            setPlaceholder(iv, placeholderResId);
            return;
        }

        try {
            Bitmap bmp = decodeSampledBitmapFromUri(ctx, uri, reqMaxDim);
            if (bmp != null) {
                iv.setImageBitmap(bmp);
                return;
            }
        } catch (SecurityException se) {
            // Permission expired or not granted.
            Logger.error("SafeImageLoader: permission denied for uri=" + uri);
        } catch (Throwable t) {
            Logger.error("SafeImageLoader: load failed uri=" + uri + " err=" + t);
        }

        setPlaceholder(iv, placeholderResId);
    }

    private static void setPlaceholder(ImageView iv, int placeholderResId) {
        try {
            int res = placeholderResId != 0 ? placeholderResId : R.drawable.ic_warning_filled;
            iv.setImageResource(res);
        } catch (Throwable ignored) {
            try {
                iv.setImageDrawable(null);
            } catch (Throwable ignored2) {}
        }
    }

    /**
     * Decode a sampled bitmap from a Uri to reduce OOM risk.
     */
    private static Bitmap decodeSampledBitmapFromUri(Context ctx, Uri uri, int reqMaxDim) throws Exception {
        // 1) Decode bounds
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            BitmapFactory.decodeStream(is, null, bounds);
        }

        int outW = bounds.outWidth;
        int outH = bounds.outHeight;
        if (outW <= 0 || outH <= 0) return null;

        int req = reqMaxDim <= 0 ? 1080 : reqMaxDim;
        int sample = 1;
        while ((outW / sample) > req || (outH / sample) > req) {
            sample *= 2;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = Math.max(1, sample);

        try (InputStream is2 = ctx.getContentResolver().openInputStream(uri)) {
            if (is2 == null) return null;
            return BitmapFactory.decodeStream(is2, null, opts);
        }
    }
}
