package tn.eluea.kgpt.roles;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import tn.eluea.kgpt.SPManager;

/**
 * Simple role (persona) manager stored as JSON in preferences.
 *
 * Roles are stored as a JSON array of objects:
 * [{ "id": "...", "name": "...", "prompt": "...", "trigger": "..." }, ...]
 *
 * Active role id is stored separately.
 */
public final class RoleManager {

    public static final String DEFAULT_ROLE_ID = "default";
    public static final String DEFAULT_ROLE_NAME = "默认角色";
    // IMPORTANT: Keep the [time] placeholder. It will be replaced right before network requests.
    public static final String DEFAULT_ROLE_PROMPT = "你是ChatGPT，是光耀将你开发成了GPT键盘插件,请以对话方式回应,不要以用户身份回答。现在的日期是:[time]。";

    // Preference keys (stored via SPManager / ConfigProvider)
    public static final String PREF_ROLES_JSON = "roles_json_v1";
    public static final String PREF_ACTIVE_ROLE_ID = "active_role_id_v1";

    private RoleManager() {}

    public static class Role {
        public final String id;
        public final String name;
        public final String prompt;
        /**
         * Optional custom trigger keyword for this role.
         *
         * If blank, the role inherits the global AI trigger keyword.
         */
        public final String trigger;

        public Role(String id, String name, String prompt) {
            this(id, name, prompt, "");
        }

        public Role(String id, String name, String prompt, String trigger) {
            this.id = id;
            this.name = name;
            this.prompt = prompt;
            this.trigger = trigger == null ? "" : trigger;
        }
    }

    public static List<Role> loadRoles(String rolesJson) {
        List<Role> roles = new ArrayList<>();
        // Always include default role first
        roles.add(new Role(DEFAULT_ROLE_ID, DEFAULT_ROLE_NAME, DEFAULT_ROLE_PROMPT, ""));

        if (rolesJson == null || rolesJson.trim().isEmpty()) {
            return roles;
        }

        try {
            JSONArray arr = new JSONArray(rolesJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "").trim();
                String name = o.optString("name", "").trim();
                String prompt = o.optString("prompt", "").trim();
                String trigger = o.optString("trigger", "");
                if (trigger == null) trigger = "";
                trigger = trigger.trim();
                if (id.isEmpty() || name.isEmpty() || prompt.isEmpty()) continue;
                if (DEFAULT_ROLE_ID.equals(id)) continue;
                roles.add(new Role(id, name, prompt, trigger));
            }
        } catch (Exception ignored) {}

        return roles;
    }

    public static String serializeCustomRoles(List<Role> roles) {
        JSONArray arr = new JSONArray();
        if (roles == null) return arr.toString();
        for (Role r : roles) {
            if (r == null) continue;
            if (DEFAULT_ROLE_ID.equals(r.id)) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("id", r.id);
                o.put("name", r.name);
                o.put("prompt", r.prompt);
                // Store trigger even if empty (backward compatible)
                o.put("trigger", r.trigger == null ? "" : r.trigger);
                arr.put(o);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    /**
     * Returns a system message to use.
     *
     * - If the active role is default: returns the providedSystemMessage unchanged (trimmed).
     * - If a custom role is active and providedSystemMessage is null/blank: returns only the role prompt.
     * - If both exist: merges as "rolePrompt\n\n### Task\nprovided".
     */
    public static String resolveSystemMessage(SPManager sp, String providedSystemMessage) {
        if (sp == null) sp = SPManager.getInstance();
        String rid = null;
        String rolesJson = null;
        try { rid = sp.getActiveRoleId(); } catch (Throwable ignored) {}
        try { rolesJson = sp.getRolesJson(); } catch (Throwable ignored) {}
        return resolveSystemMessage(rid, rolesJson, providedSystemMessage);
    }

    /**
     * Overload kept for backward compatibility (older controllers still call this signature).
     */
    public static String resolveSystemMessage(String activeRoleId, String rolesJson, String providedSystemMessage) {
        String rid = activeRoleId == null ? "" : activeRoleId.trim();
        if (rid.isEmpty()) rid = DEFAULT_ROLE_ID;

        String rolePrompt;
        if (DEFAULT_ROLE_ID.equals(rid)) {
            rolePrompt = DEFAULT_ROLE_PROMPT;
        } else {
            rolePrompt = getActiveRolePrompt(rid, rolesJson);
            if (TextUtils.isEmpty(rolePrompt)) {
                // If stored role is missing/corrupt, fall back to default role prompt.
                rolePrompt = DEFAULT_ROLE_PROMPT;
            }
        }

        String provided = providedSystemMessage == null ? "" : providedSystemMessage.trim();
        String merged;
        if (!TextUtils.isEmpty(rolePrompt)) {
            if (!TextUtils.isEmpty(provided)) {
                merged = rolePrompt + "\n\n### Task\n" + provided;
            } else {
                merged = rolePrompt;
            }
        } else {
            merged = provided;
        }

        return replaceTimePlaceholder(merged);
    }

    /** Replace [time] placeholder with current system time (used by default role and custom roles). */
    private static String replaceTimePlaceholder(String input) {
        if (TextUtils.isEmpty(input)) return input;
        if (!input.contains("[time]")) return input;
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
            String now = sdf.format(new java.util.Date());
            return input.replace("[time]", now);
        } catch (Throwable t) {
            return input.replace("[time]", String.valueOf(System.currentTimeMillis()));
        }
    }

    private static String getActiveRolePrompt(String activeRoleId, String rolesJson) {
        if (activeRoleId == null) return null;
        String id = activeRoleId.trim();
        if (id.isEmpty() || DEFAULT_ROLE_ID.equals(id)) return null;
        if (rolesJson == null || rolesJson.trim().isEmpty()) return null;

        try {
            JSONArray arr = new JSONArray(rolesJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String rid = o.optString("id", "");
                if (rid == null) continue;
                rid = rid.trim();
                if (!id.equals(rid)) continue;

                String prompt = o.optString("prompt", null);
                if (prompt == null) return null;
                prompt = prompt.trim();
                return prompt.isEmpty() ? null : prompt;
            }
        } catch (Exception ignored) {}

        return null;
    }
}
