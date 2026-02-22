package tn.eluea.kgpt.util;

import android.content.Context;

import tn.eluea.kgpt.ui.UiInteractor;

/**
 * Context helper for Xposed / IME injected processes.
 * UiInteractor context might be null in some host processes; fall back to ActivityThread.currentApplication().
 */
public final class ContextUtils {

    private ContextUtils() {}

    public static Context getAnyContext() {
        Context ctx = null;
        try {
            ctx = UiInteractor.getInstance().getContext();
        } catch (Throwable ignore) {}

        if (ctx != null) return ctx;

        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignore) {}

        return null;
    }
}
