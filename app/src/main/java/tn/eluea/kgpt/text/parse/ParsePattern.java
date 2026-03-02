/*
 * Copyright (c) 2025 Amr Aldeeb @Eluea
 * GitHub: https://github.com/Eluea
 * Telegram: https://t.me/Eluea
 *
 * This file is part of KGPT.
 * Based on original code from KeyboardGPT by Mino260806.
 * Original: https://github.com/Mino260806/KeyboardGPT
 *
 * Licensed under the GPLv3.
 */
package tn.eluea.kgpt.text.parse;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tn.eluea.kgpt.MainHook;

public class ParsePattern {
    private static final String EXTRA_ENABLED = "_enabled";

    private final PatternType mType;
    private final Pattern mPattern;
    private Map<String, String> mExtras = null;

    public ParsePattern(PatternType type, String patternRe) {
        this(type, patternRe, null);
    }

    public ParsePattern(PatternType type, String patternRe, Map<String, String> extras) {
        mType = type;
        mPattern = Pattern.compile(patternRe);
        mExtras = extras;
    }

    public void putExtra(String key, String value) {
        if (mExtras == null) {
            mExtras = new HashMap<>();
        }
        mExtras.put(key, value);
    }

    public String getExtra(String key) {
        return mExtras == null ? null : mExtras.get(key);
    }

    public Map<String, String> getExtras() {
        return mExtras;
    }

    /**
     * Check if this pattern is enabled
     * Default is false (disabled) for all patterns
     */
    public boolean isEnabled() {
        String enabled = getExtra(EXTRA_ENABLED);
        return "true".equals(enabled);
    }

    /**
     * Set whether this pattern is enabled
     */
    public void setEnabled(boolean enabled) {
        putExtra(EXTRA_ENABLED, String.valueOf(enabled));
    }

    /**
     * Create a copy of this pattern with enabled state changed
     */
    public ParsePattern withEnabled(boolean enabled) {
        Map<String, String> newExtras = mExtras != null ? new HashMap<>(mExtras) : new HashMap<>();
        newExtras.put(EXTRA_ENABLED, String.valueOf(enabled));
        return new ParsePattern(mType, mPattern.pattern(), newExtras);
    }

    public PatternType getType() {
        return mType;
    }

    public Pattern getPattern() {
        return mPattern;
    }

    public static String encode(List<ParsePattern> patterns) {
        JSONArray patternsJson = new JSONArray();
        for (ParsePattern parsePattern : patterns) {
            JSONObject patternJson = new JSONObject();
            try {
                patternJson.put("name", parsePattern.getType().name());
                patternJson.put("pattern", parsePattern.getPattern().pattern());
                Map<String, String> extras = parsePattern.getExtras();
                if (extras != null) {
                    patternJson.put("extras", new JSONObject(extras));
                }
            } catch (JSONException e) {
                MainHook.log(e);
            }
            patternsJson.put(patternJson);
        }
        return patternsJson.toString();
    }

    public static List<ParsePattern> decode(String encodedPatterns) {
        if (encodedPatterns == null) {
            return getDefaultList();
        }

        List<ParsePattern> patterns = new ArrayList<>();
        java.util.Set<PatternType> foundTypes = new java.util.HashSet<>();

        try {
            JSONArray patternsJson = new JSONArray(encodedPatterns);
            for (int i = 0; i < patternsJson.length(); i++) {
                JSONObject patternJson = patternsJson.getJSONObject(i);

                String name = patternJson.getString("name");
                String patternStr = patternJson.getString("pattern");

                // Migration: older builds used dot (.+) which doesn't match newlines.
                // When users type multi-line text and trigger AI with '$' (or WebSearch '??'),
                // only the current line would be captured. Replace legacy defaults with a
                // newline-safe pattern.
                if ("CommandAI".equals(name) && "(.+)\\$$".equals(patternStr)) {
                    // New default trigger for this build: $GPT
                    patternStr = "([\\s\\S]+)\\$GPT$";
                } else if ("CommandAI".equals(name) && "(.+)\\$GPT$".equals(patternStr)) {
                    // Keep the symbol but make it newline-safe
                    patternStr = "([\\s\\S]+)\\$GPT$";
                } else if ("CommandAI".equals(name) && "([\\s\\S]+)\\$$".equals(patternStr)) {
                    // Old default: '$' only
                    patternStr = "([\\s\\S]+)\\$GPT$";
                } else if ("WebSearch".equals(name) && "(.+)\\?\\?$".equals(patternStr)) {
                    patternStr = "([\\s\\S]+)\\?\\?$";
                }

                
                // Migration: word-based triggers could fail when the editor keeps a trailing newline.
                // Old defaults were like "模型切换$" and "AI剪贴板$"; update them to allow trailing whitespace/newlines.
                else if ("ModelSwitch".equals(name) && "模型切换$".equals(patternStr)) {
                    patternStr = "模型切换\\s*$";
                } else if ("AIClipboard".equals(name) && "AI剪贴板$".equals(patternStr)) {
                    patternStr = "AI剪贴板\\s*$";
                }

                // Migration: v1 -> v2 defaults requested by user
                // - ModelSwitch -> $ChatGPT
                // - AIClipboard -> $jtb (also migrate old $jtbl)
                // - QuickJumpMenu -> $kjtz
                else if ("ModelSwitch".equals(name)
                        && ("模型切换\\s*$".equals(patternStr) || "模型切换$".equals(patternStr)
                        || "\\$ChatGPT$".equals(patternStr))) {
                    patternStr = "\\$ChatGPT\\s*$";
                } else if ("AIClipboard".equals(name)
                        && ("AI剪贴板\\s*$".equals(patternStr) || "AI剪贴板$".equals(patternStr)
                        || "\\$jtbl\\s*$".equals(patternStr) || "\\$jtbl$".equals(patternStr)
                        || "\\$jtb$".equals(patternStr))) {
                    patternStr = "\\$jtb\\s*$";
                } else if ("QuickJumpMenu".equals(name)
                        && ("快捷跳转\\s*$".equals(patternStr) || "快捷跳转$".equals(patternStr)
                        || "\\$kjtz$".equals(patternStr))) {
                    patternStr = "\\$kjtz\\s*$";
                }

                Map<String, String> extras = null;
                if (patternJson.has("extras")) {
                    JSONObject extrasJson = patternJson.getJSONObject("extras");
                    extras = new HashMap<>();
                    for (Iterator<String> it = extrasJson.keys(); it.hasNext();) {
                        String key = it.next();
                        extras.put(key, extrasJson.getString(key));
                    }
                }

                try {
                    PatternType type = PatternType.valueOf(name);
                    ParsePattern parsePattern = new ParsePattern(type, patternStr, extras);
                    patterns.add(parsePattern);
                    foundTypes.add(type);
                } catch (IllegalArgumentException e) {
                    // Pattern type no longer exists, skip it
                    android.util.Log.w("ParsePattern", "Skipping unknown pattern type: " + name);
                }
            }
        } catch (JSONException e) {
            android.util.Log.e("ParsePattern", "Error decoding patterns", e);
        }

        // Add any missing pattern types (for migration when new types are added)
        for (PatternType type : PatternType.values()) {
            if (!foundTypes.contains(type)) {
                ParsePattern np = new ParsePattern(type, type.defaultPattern);
                np.setEnabled(true);
                patterns.add(np);
                android.util.Log.i("ParsePattern", "Added missing pattern type: " + type.name());
            }
        }

        // Sort patterns so the main trigger actions appear first in the UI:
        // 1) ChatGPT助手 (ModelSwitch)
        // 2) AI 触发器 (CommandAI)
        // 3) AI剪贴板 (AIClipboard)
        // 4) 快捷跳转 (QuickJumpMenu)
        try {
            final java.util.Map<PatternType, Integer> priority = new java.util.HashMap<>();
            priority.put(PatternType.ModelSwitch, 0);
            priority.put(PatternType.CommandAI, 1);
            priority.put(PatternType.AIClipboard, 2);
            priority.put(PatternType.QuickJumpMenu, 3);

            final java.util.Map<PatternType, Integer> firstIndex = new java.util.HashMap<>();
            for (int i = 0; i < patterns.size(); i++) {
                ParsePattern p = patterns.get(i);
                if (p == null) continue;
                PatternType t = p.getType();
                if (t == null) continue;
                if (!firstIndex.containsKey(t)) firstIndex.put(t, i);
            }

            java.util.Collections.sort(patterns, (a, b) -> {
                if (a == null && b == null) return 0;
                if (a == null) return 1;
                if (b == null) return -1;
                PatternType ta = a.getType();
                PatternType tb = b.getType();
                int pa = priority.containsKey(ta) ? priority.get(ta) : 100;
                int pb = priority.containsKey(tb) ? priority.get(tb) : 100;
                if (pa != pb) return Integer.compare(pa, pb);
                int ia = firstIndex.containsKey(ta) ? firstIndex.get(ta) : Integer.MAX_VALUE;
                int ib = firstIndex.containsKey(tb) ? firstIndex.get(tb) : Integer.MAX_VALUE;
                return Integer.compare(ia, ib);
            });
        } catch (Throwable ignored) {
        }

        return patterns;
    }

    private static List<ParsePattern> getDefaultList() {
        List<ParsePattern> patterns = new ArrayList<>();
        for (PatternType type : PatternType.values()) {
            ParsePattern pattern = new ParsePattern(type, type.defaultPattern);
            pattern.setEnabled(true); // Enable by default
            patterns.add(pattern);
        }
        return patterns;
    }

    /**
     * Get default patterns list (public access for initialization)
     */
    public static List<ParsePattern> getDefaultPatterns() {
        return getDefaultList();
    }
}
