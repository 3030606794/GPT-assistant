package tn.eluea.kgpt.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import androidx.annotation.NonNull;

import java.util.Map;

/**
 * SharedPreferences compatibility helpers.
 *
 * Some environments (e.g., modules/hooks, older builds, or migrations) may store values under the same key
 * with a different type (e.g., int instead of boolean). Using SharedPreferences getters directly can throw
 * ClassCastException and crash during app startup.
 */
public final class PrefsCompat {

    private PrefsCompat() {
        // no instances
    }

    /**
     * Reads a theme flag safely.
     *
     * The app currently stores theme_mode as a boolean, but some installations may have an int value:
     *   1 => MODE_NIGHT_NO
     *   2 => MODE_NIGHT_YES
     *  -1 => FOLLOW_SYSTEM
     *   3 => AUTO_BATTERY
     */
    public static boolean getThemeIsDark(@NonNull Context context,
                                         @NonNull SharedPreferences prefs,
                                         @NonNull String key,
                                         boolean defaultValue) {
        return getBooleanCompat(context, prefs, key, defaultValue);
    }

    /**
     * Safely read a boolean preference even if the stored type is not boolean.
     * If a non-boolean type is found, it will be interpreted and (best-effort) migrated back to boolean.
     */
    public static boolean getBooleanCompat(@NonNull Context context,
                                           @NonNull SharedPreferences prefs,
                                           @NonNull String key,
                                           boolean defaultValue) {
        try {
            return prefs.getBoolean(key, defaultValue);
        } catch (ClassCastException ignored) {
            // fall through
        } catch (Throwable ignored) {
            // ultra defensive: some OEMs may throw other runtime errors.
        }

        boolean resolved = defaultValue;
        try {
            Map<String, ?> all = prefs.getAll();
            Object v = all != null ? all.get(key) : null;

            if (v instanceof Boolean) {
                resolved = (Boolean) v;
            } else if (v instanceof Integer) {
                resolved = interpretThemeInt(context, (Integer) v, defaultValue);
            } else if (v instanceof Long) {
                long lv = (Long) v;
                int iv;
                if (lv > Integer.MAX_VALUE) iv = Integer.MAX_VALUE;
                else if (lv < Integer.MIN_VALUE) iv = Integer.MIN_VALUE;
                else iv = (int) lv;
                resolved = interpretThemeInt(context, iv, defaultValue);
            } else if (v instanceof String) {
                resolved = interpretString(context, (String) v, defaultValue);
            }

            // Best-effort migration back to boolean to prevent future crashes
            try {
                prefs.edit().putBoolean(key, resolved).apply();
            } catch (Throwable ignored) {
                // ignore migration failure
            }
        } catch (Throwable ignored) {
            // keep default
        }

        return resolved;
    }

    private static boolean interpretString(@NonNull Context context, @NonNull String s, boolean defaultValue) {
        String t = s.trim().toLowerCase();
        if ("true".equals(t) || "1".equals(t) || "yes".equals(t) || "on".equals(t)) {
            return true;
        }
        if ("false".equals(t) || "0".equals(t) || "no".equals(t) || "off".equals(t)) {
            return false;
        }
        // Maybe it's an int serialized as string
        try {
            int iv = Integer.parseInt(t);
            return interpretThemeInt(context, iv, defaultValue);
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }

    private static boolean interpretThemeInt(@NonNull Context context, int mode, boolean defaultValue) {
        // AppCompatDelegate values commonly used:
        // MODE_NIGHT_NO = 1
        // MODE_NIGHT_YES = 2
        // MODE_NIGHT_FOLLOW_SYSTEM = -1
        // MODE_NIGHT_AUTO_BATTERY = 3
        // MODE_NIGHT_UNSPECIFIED = 0
        if (mode == 2) return true;
        if (mode == 1) return false;
        if (mode == -1 || mode == 3) {
            return isSystemNight(context);
        }
        return defaultValue;
    }

    private static boolean isSystemNight(@NonNull Context context) {
        try {
            int nightModeFlags = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
