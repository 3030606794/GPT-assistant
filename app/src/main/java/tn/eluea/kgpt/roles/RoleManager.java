package tn.eluea.kgpt.roles;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tn.eluea.kgpt.SPManager;

/**
 * Role (persona) manager stored as JSON in preferences.
 *
 * Roles are stored as a JSON array of objects:
 * [{"id":"...","name":"...","prompt":"...","trigger":"...", ...}, ...]
 *
 * The built-in DEFAULT role can be customized (except name) via a dedicated JSON object
 * stored in SPManager (default_role_custom_json_v1).
 */
public final class RoleManager {

    public static final String DEFAULT_ROLE_ID = "default";
    public static final String DEFAULT_ROLE_NAME = "默认角色";

    // IMPORTANT: Keep placeholders. They will be replaced right before network requests.
    public static final String DEFAULT_ROLE_PROMPT = "你是ChatGPT，是光耀将你开发成了GPT键盘插件,请以对话方式回应,不要以用户身份回答。现在的日期是:[time]。";

    // Preference keys (stored via SPManager)
    public static final String PREF_ROLES_JSON = "roles_json_v1";
    public static final String PREF_ACTIVE_ROLE_ID = "active_role_id_v1";

    private RoleManager() {}

    public static class Role {
        public final String id;
        public final String name;
        public final String prompt;

        /** Optional custom trigger keyword for this role. If blank, inherits global trigger symbol. */
        public final String trigger;

        /** Optional emoji avatar shown in UI. */
        public final String emoji;

        /** Whether this role enables parameter overrides (model / temperature). */
        public final boolean overrideEnabled;

        /** Optional sub-model override (empty = follow global). */
        public final String overrideSubModel;

        /** Optional temperature override (negative = follow global). */
        public final float overrideTemperature;

        /** Whether this role enables preset/profile overrides (memory / max tokens). */
        public final boolean presetEnabled;

        /** Conversation memory level override. -1 means follow global. */
        public final int presetMemoryLevel;

        /** Max tokens override. <=0 means follow global. */
        public final int presetMaxTokens;

        /** Optional few-shot example input (user). */
        public final String exampleUser;

        /** Optional few-shot example output (assistant). */
        public final String exampleAssistant;

        public Role(String id, String name, String prompt) {
            this(id, name, prompt, "", "🤖", false, "", -1f,
                    false, -1, 0,
                    "", "");
        }

        public Role(String id, String name, String prompt, String trigger) {
            this(id, name, prompt, trigger, "🤖", false, "", -1f,
                    false, -1, 0,
                    "", "");
        }

        public Role(String id,
                    String name,
                    String prompt,
                    String trigger,
                    String emoji,
                    boolean overrideEnabled,
                    String overrideSubModel,
                    float overrideTemperature) {
            this(id, name, prompt, trigger, emoji, overrideEnabled, overrideSubModel, overrideTemperature,
                    false, -1, 0,
                    "", "");
        }

        public Role(String id,
                    String name,
                    String prompt,
                    String trigger,
                    String emoji,
                    boolean overrideEnabled,
                    String overrideSubModel,
                    float overrideTemperature,
                    boolean presetEnabled,
                    int presetMemoryLevel,
                    int presetMaxTokens,
                    String exampleUser,
                    String exampleAssistant) {
            this.id = id;
            this.name = name;
            this.prompt = prompt;
            this.trigger = trigger == null ? "" : trigger.trim();
            this.emoji = (emoji == null || emoji.trim().isEmpty()) ? "🤖" : emoji.trim();
            this.overrideEnabled = overrideEnabled;
            this.overrideSubModel = overrideSubModel == null ? "" : overrideSubModel.trim();
            this.overrideTemperature = overrideTemperature;
            this.presetEnabled = presetEnabled;
            this.presetMemoryLevel = presetMemoryLevel;
            this.presetMaxTokens = presetMaxTokens;
            this.exampleUser = exampleUser == null ? "" : exampleUser;
            this.exampleAssistant = exampleAssistant == null ? "" : exampleAssistant;
        }

        public boolean isDefault() {
            return DEFAULT_ROLE_ID.equals(id);
        }
    }

    /** Role override payload (used by controllers to avoid changing global settings). */
    public static final class RoleOverride {
        public final boolean enabled;
        public final String subModel;
        public final float temperature;

        public RoleOverride(boolean enabled, String subModel, float temperature) {
            this.enabled = enabled;
            this.subModel = subModel == null ? "" : subModel;
            this.temperature = temperature;
        }
    }

    /** Parse roles from JSON string; includes default role (possibly customized) at index 0. */
    public static List<Role> loadRoles(String rolesJson) {
        return loadRoles(rolesJson, null);
    }

    /** Parse roles from JSON string with optional SPManager access for default role customization. */
    public static List<Role> loadRoles(String rolesJson, SPManager sp) {
        if (sp == null) {
            try { sp = SPManager.getInstance(); } catch (Throwable ignored) {}
        }

        Role defaultRole = new Role(DEFAULT_ROLE_ID, DEFAULT_ROLE_NAME, DEFAULT_ROLE_PROMPT,
                "", "🤖", false, "", -1f,
                false, -1, 0,
                "", "");

        // Apply user customization for DEFAULT role (except name)
        if (sp != null) {
            try {
                String custom = sp.getDefaultRoleCustomJson();
                if (!TextUtils.isEmpty(custom)) {
                    JSONObject o = new JSONObject(custom);
                    defaultRole = applyJsonToDefaultRole(defaultRole, o);
                }
            } catch (Throwable ignored) {}
        }

        List<Role> roles = new ArrayList<>();
        roles.add(defaultRole);

        if (rolesJson == null || rolesJson.trim().isEmpty()) {
            return roles;
        }

        try {
            JSONArray arr = new JSONArray(rolesJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;

                Role r = roleFromJson(o);
                if (r == null) continue;
                if (r.id == null || r.id.trim().isEmpty()) continue;
                if (DEFAULT_ROLE_ID.equals(r.id)) continue;
                if (TextUtils.isEmpty(r.name) || TextUtils.isEmpty(r.prompt)) continue;
                roles.add(r);
            }
        } catch (Throwable ignored) {}

        return roles;
    }

    /** Serialize custom roles (excluding default) to JSON array string. */
    public static String serializeCustomRoles(List<Role> roles) {
        JSONArray arr = new JSONArray();
        if (roles == null) return arr.toString();
        for (Role r : roles) {
            if (r == null) continue;
            if (DEFAULT_ROLE_ID.equals(r.id)) continue;
            try {
                arr.put(roleToJson(r));
            } catch (Throwable ignored) {}
        }
        return arr.toString();
    }

    /** Export a role to a JSON object (used by export/share). */
    public static JSONObject roleToJson(Role r) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", r.id);
            o.put("name", r.name);
            o.put("prompt", r.prompt);
            o.put("trigger", r.trigger == null ? "" : r.trigger);
            o.put("emoji", r.emoji == null ? "🤖" : r.emoji);
            o.put("override_enabled", r.overrideEnabled);
            o.put("override_sub_model", r.overrideSubModel == null ? "" : r.overrideSubModel);
            o.put("override_temp", r.overrideTemperature);
            o.put("preset_enabled", r.presetEnabled);
            o.put("preset_memory_level", r.presetMemoryLevel);
            o.put("preset_max_tokens", r.presetMaxTokens);
            o.put("example_user", r.exampleUser == null ? "" : r.exampleUser);
            o.put("example_assistant", r.exampleAssistant == null ? "" : r.exampleAssistant);
        } catch (Throwable ignored) {}
        return o;
    }

    /** Parse a role from a JSON object. Returns null if required fields are missing. */
    public static Role roleFromJson(JSONObject o) {
        if (o == null) return null;
        String id = o.optString("id", "").trim();
        String name = o.optString("name", "").trim();
        String prompt = o.optString("prompt", "").trim();
        String trigger = o.optString("trigger", "");
        if (trigger == null) trigger = "";
        trigger = trigger.trim();

        String emoji = o.optString("emoji", "🤖");

        boolean overrideEnabled = o.optBoolean("override_enabled", false);
        String overrideSubModel = o.optString("override_sub_model", "");
        float overrideTemp = -1f;
        try {
            overrideTemp = (float) o.optDouble("override_temp", -1.0);
        } catch (Throwable ignored) {}

        boolean presetEnabled = o.optBoolean("preset_enabled", false);
        int presetMemoryLevel = o.optInt("preset_memory_level", -1);
        int presetMaxTokens = o.optInt("preset_max_tokens", 0);

        String exampleUser = o.optString("example_user", "");
        String exampleAssistant = o.optString("example_assistant", "");

        if (id.isEmpty() || name.isEmpty() || prompt.isEmpty()) {
            // Allow importing default role with empty name/prompt? No.
            return null;
        }

        return new Role(id, name, prompt, trigger, emoji,
                overrideEnabled, overrideSubModel, overrideTemp,
                presetEnabled, presetMemoryLevel, presetMaxTokens,
                exampleUser, exampleAssistant);
    }

    /**
     * Resolve role override settings (without mutating global config).
     *
     * Supports stacking mode: when enabled, the last stacked role with override_enabled wins.
     */
    public static RoleOverride resolveRoleOverride(String activeRoleId, String rolesJson) {
        SPManager sp = null;
        try { sp = SPManager.getInstance(); } catch (Throwable ignored) {}
        return resolveRoleOverride(sp, activeRoleId, rolesJson);
    }

    public static RoleOverride resolveRoleOverride(SPManager sp, String activeRoleId, String rolesJson) {
        List<Role> roles = loadRoles(rolesJson, sp);
        if (roles == null || roles.isEmpty()) return null;

        List<String> stack = getStackRoleIds(sp);
        boolean stacking = sp != null && sp.getRoleStackEnabled() && stack.size() > 0;

        List<String> candidates;
        if (stacking) {
            // Always include DEFAULT as base, then stack list.
            candidates = new ArrayList<>();
            candidates.add(DEFAULT_ROLE_ID);
            candidates.addAll(stack);
        } else {
            String rid = activeRoleId == null ? "" : activeRoleId.trim();
            if (rid.isEmpty()) rid = DEFAULT_ROLE_ID;
            candidates = Collections.singletonList(rid);
        }

        // Last wins
        for (int i = candidates.size() - 1; i >= 0; i--) {
            String rid = candidates.get(i);
            Role r = findRoleById(roles, rid);
            if (r == null) continue;
            if (!r.overrideEnabled) continue;
            return new RoleOverride(true, r.overrideSubModel, r.overrideTemperature);
        }

        return null;
    }

    /**
     * Returns a system message to use.
     *
     * - If stacking mode is enabled, always uses DEFAULT role as Base + stacked roles (in order), then Task.
     * - Otherwise uses active role.
     *
     * Few-shot example (if present) is attached right before Task.
     */
    public static String resolveSystemMessage(SPManager sp, String providedSystemMessage) {
        if (sp == null) {
            try { sp = SPManager.getInstance(); } catch (Throwable ignored) {}
        }
        String rid = null;
        String rolesJson = null;
        try { rid = sp != null ? sp.getActiveRoleId() : DEFAULT_ROLE_ID; } catch (Throwable ignored) {}
        try { rolesJson = sp != null ? sp.getRolesJson() : ""; } catch (Throwable ignored) {}

        List<Role> roles = loadRoles(rolesJson, sp);

        String provided = providedSystemMessage == null ? "" : providedSystemMessage.trim();

        boolean stacking = sp != null && sp.getRoleStackEnabled();
        List<String> stackIds = getStackRoleIds(sp);
        if (!stacking || stackIds.isEmpty()) {
            String useId = rid == null || rid.trim().isEmpty() ? DEFAULT_ROLE_ID : rid.trim();
            Role active = findRoleById(roles, useId);
            if (active == null) active = findRoleById(roles, DEFAULT_ROLE_ID);
            String merged = mergeRoleAndTask(active, provided);
            return renderTimeDatePlaceholders(merged);
        }

        // Stacking: base default + each stacked role prompt.
        Role base = findRoleById(roles, DEFAULT_ROLE_ID);
        StringBuilder sb = new StringBuilder();
        if (base != null && !TextUtils.isEmpty(base.prompt)) {
            sb.append(base.prompt.trim());
        }

        Role last = null;
        for (String id : stackIds) {
            if (TextUtils.isEmpty(id)) continue;
            if (DEFAULT_ROLE_ID.equals(id)) continue;
            Role r = findRoleById(roles, id);
            if (r == null) continue;
            if (TextUtils.isEmpty(r.prompt)) continue;
            if (sb.length() > 0) sb.append("\n\n---\n\n");
            sb.append(r.prompt.trim());
            last = r;
        }

        // Attach example (prefer last role)
        Role exampleRole = last != null ? last : base;
        if (exampleRole != null) {
            String ex = buildExampleBlock(exampleRole.exampleUser, exampleRole.exampleAssistant);
            if (!TextUtils.isEmpty(ex)) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(ex);
            }
        }

        if (!TextUtils.isEmpty(provided)) {
            if (sb.length() > 0) sb.append("\n\n### Task\n");
            sb.append(provided);
        }

        return renderTimeDatePlaceholders(sb.toString());
    }

    /**
     * Backward-compatible overload used by controllers.
     *
     * If the provided roleId matches the current active role, stacking mode (if enabled) will apply.
     * If the provided roleId is a temporary override (different from active role), stacking is not applied and
     * only that role is used.
     */
    public static String resolveSystemMessage(String roleId, String rolesJsonSnapshot, String providedSystemMessage) {
        SPManager sp = null;
        try { sp = SPManager.getInstance(); } catch (Throwable ignored) {}

        String provided = providedSystemMessage == null ? "" : providedSystemMessage.trim();
        String rid = roleId == null ? "" : roleId.trim();
        if (rid.isEmpty()) rid = DEFAULT_ROLE_ID;

        // Use the snapshot roles JSON for deterministic behavior.
        List<Role> roles = loadRoles(rolesJsonSnapshot, sp);

        boolean allowStacking = false;
        try {
            if (sp != null && sp.getRoleStackEnabled()) {
                String active = sp.getActiveRoleId();
                // Only apply stacking when this call is for the active role (not a temporary override).
                allowStacking = (active == null || active.trim().isEmpty())
                        ? DEFAULT_ROLE_ID.equals(rid)
                        : active.trim().equals(rid);
            }
        } catch (Throwable ignored) {}

        List<String> stackIds = allowStacking ? getStackRoleIds(sp) : new ArrayList<>();
        if (!allowStacking || stackIds.isEmpty()) {
            Role active = findRoleById(roles, rid);
            if (active == null) active = findRoleById(roles, DEFAULT_ROLE_ID);
            return renderTimeDatePlaceholders(mergeRoleAndTask(active, provided));
        }

        // Stacking: base default + each stacked role prompt.
        Role base = findRoleById(roles, DEFAULT_ROLE_ID);
        StringBuilder sb = new StringBuilder();
        if (base != null && !TextUtils.isEmpty(base.prompt)) {
            sb.append(base.prompt.trim());
        }

        Role last = null;
        for (String id : stackIds) {
            if (TextUtils.isEmpty(id)) continue;
            if (DEFAULT_ROLE_ID.equals(id)) continue;
            Role r = findRoleById(roles, id);
            if (r == null) continue;
            if (TextUtils.isEmpty(r.prompt)) continue;
            if (sb.length() > 0) sb.append("\n\n---\n\n");
            sb.append(r.prompt.trim());
            last = r;
        }

        // Attach example (prefer last role)
        Role exampleRole = last != null ? last : base;
        if (exampleRole != null) {
            String ex = buildExampleBlock(exampleRole.exampleUser, exampleRole.exampleAssistant);
            if (!TextUtils.isEmpty(ex)) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(ex);
            }
        }

        if (!TextUtils.isEmpty(provided)) {
            if (sb.length() > 0) sb.append("\n\n### Task\n");
            sb.append(provided);
        }

        return renderTimeDatePlaceholders(sb.toString());
    }

    private static String mergeRoleAndTask(Role role, String providedTask) {
        String rolePrompt = role != null ? (role.prompt == null ? "" : role.prompt.trim()) : "";
        String out;
        if (!TextUtils.isEmpty(rolePrompt)) {
            out = rolePrompt;
        } else {
            out = "";
        }

        // Attach example
        if (role != null) {
            String ex = buildExampleBlock(role.exampleUser, role.exampleAssistant);
            if (!TextUtils.isEmpty(ex)) {
                if (!TextUtils.isEmpty(out)) out += "\n\n";
                out += ex;
            }
        }

        if (!TextUtils.isEmpty(providedTask)) {
            if (!TextUtils.isEmpty(out)) {
                out += "\n\n### Task\n" + providedTask;
            } else {
                out = providedTask;
            }
        }
        return out;
    }

    /** Replace [time]/[date] placeholders with current system time/date. */
    public static String renderTimeDatePlaceholders(String input) {
        if (TextUtils.isEmpty(input)) return input;
        boolean hasTime = input.contains("[time]");
        boolean hasDate = input.contains("[date]");
        if (!hasTime && !hasDate) return input;
        try {
            java.util.Date now = new java.util.Date();
            String out = input;
            if (hasTime) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
                out = out.replace("[time]", sdf.format(now));
            }
            if (hasDate) {
                java.text.SimpleDateFormat sdf2 = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
                out = out.replace("[date]", sdf2.format(now));
            }
            return out;
        } catch (Throwable t) {
            String out = input;
            String fallback = String.valueOf(System.currentTimeMillis());
            if (hasTime) out = out.replace("[time]", fallback);
            if (hasDate) out = out.replace("[date]", fallback);
            return out;
        }
    }

    public static List<String> parseRoleIdArray(String jsonArray) {
        if (jsonArray == null) return new ArrayList<>();
        String raw = jsonArray.trim();
        if (raw.isEmpty()) return new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            List<String> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String v = arr.optString(i, "");
                if (v == null) v = "";
                v = v.trim();
                if (v.isEmpty()) continue;
                out.add(v);
            }
            return out;
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    private static List<String> getStackRoleIds(SPManager sp) {
        if (sp == null) return new ArrayList<>();
        try {
            String json = sp.getRoleStackJson();
            return parseRoleIdArray(json);
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    private static Role findRoleById(List<Role> roles, String id) {
        if (roles == null || id == null) return null;
        String rid = id.trim();
        for (Role r : roles) {
            if (r == null) continue;
            if (rid.equals(r.id)) return r;
        }
        return null;
    }

    private static Role applyJsonToDefaultRole(Role base, JSONObject o) {
        if (base == null) return null;
        if (o == null) return base;

        String prompt = o.optString("prompt", base.prompt);
        String trigger = o.optString("trigger", base.trigger);
        String emoji = o.optString("emoji", base.emoji);

        boolean overrideEnabled = o.optBoolean("override_enabled", base.overrideEnabled);
        String overrideSubModel = o.optString("override_sub_model", base.overrideSubModel);
        float overrideTemp = base.overrideTemperature;
        try { overrideTemp = (float) o.optDouble("override_temp", base.overrideTemperature); } catch (Throwable ignored) {}

        boolean presetEnabled = o.optBoolean("preset_enabled", base.presetEnabled);
        int presetMemory = o.optInt("preset_memory_level", base.presetMemoryLevel);
        int presetMaxTokens = o.optInt("preset_max_tokens", base.presetMaxTokens);

        String exUser = o.optString("example_user", base.exampleUser);
        String exAsst = o.optString("example_assistant", base.exampleAssistant);

        return new Role(DEFAULT_ROLE_ID, DEFAULT_ROLE_NAME,
                TextUtils.isEmpty(prompt) ? base.prompt : prompt,
                trigger,
                emoji,
                overrideEnabled,
                overrideSubModel,
                overrideTemp,
                presetEnabled,
                presetMemory,
                presetMaxTokens,
                exUser,
                exAsst);
    }

    private static String buildExampleBlock(String exampleUser, String exampleAssistant) {
        String u = exampleUser == null ? "" : exampleUser.trim();
        String a = exampleAssistant == null ? "" : exampleAssistant.trim();
        if (u.isEmpty() && a.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## Few-shot Examples（参考）\n");
        sb.append("以下示例用于引导回答风格/格式（不要求逐字照搬）：\n");
        if (!u.isEmpty()) sb.append("- 用户：").append(u).append("\n");
        if (!a.isEmpty()) sb.append("- 助手：").append(a).append("\n");
        return sb.toString().trim();
    }
}
