package tn.eluea.kgpt.util;

import android.util.Log;
import tn.eluea.kgpt.SPManager;
import de.robv.android.xposed.XposedBridge;

public class Logger {
    private static final String TAG = "KGPT";
    private static boolean isXposedContext = false;

    static {
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
            isXposedContext = true;
        } catch (ClassNotFoundException e) {
            isXposedContext = false;
        }
    }

    private static boolean shouldLogToXposed() {
        try {
            return !SPManager.isReady() || SPManager.getInstance().getEnableLogs();
        } catch (Throwable t) {
            return true;
        }
    }

    public static void log(String message) {
        try { Log.d(TAG, message); } catch (Throwable ignored) {}
        if (!isXposedContext) return;
        try {
            if (shouldLogToXposed()) {
                XposedBridge.log("(" + TAG + ") " + message);
            }
        } catch (Throwable e) {
            try { Log.d(TAG, message); } catch (Throwable ignored) {}
        }
    }

    public static void log(String tag, String message) {
        try { Log.d(tag, message); } catch (Throwable ignored) {}
        if (!isXposedContext) return;
        try {
            if (shouldLogToXposed()) {
                XposedBridge.log("(" + tag + ") " + message);
            }
        } catch (Throwable e) {
            try { Log.d(tag, message); } catch (Throwable ignored) {}
        }
    }

    public static void error(String message) {
        try { Log.e(TAG, message); } catch (Throwable ignored) {}
        if (!isXposedContext) return;
        try {
            if (shouldLogToXposed()) {
                XposedBridge.log("(" + TAG + ") [ERROR] " + message);
            }
        } catch (Throwable t) {
            try { Log.e(TAG, message); } catch (Throwable ignored) {}
        }
    }

    public static void log(Throwable t) {
        try { Log.e(TAG, "Exception", t); } catch (Throwable ignored) {}
        if (!isXposedContext) return;
        try {
            if (shouldLogToXposed()) {
                XposedBridge.log(t);
            }
        } catch (Throwable th) {
            try { Log.e(TAG, "Exception", t); } catch (Throwable ignored) {}
        }
    }
}
