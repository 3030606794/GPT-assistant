package tn.eluea.kgpt.ui.roles;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import android.widget.ImageButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.slider.Slider;
import com.google.android.material.color.MaterialColors;

import androidx.appcompat.widget.SwitchCompat;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.List;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.roles.RoleManager;
import tn.eluea.kgpt.ui.main.BottomSheetHelper;
import tn.eluea.kgpt.ui.main.FloatingBottomSheet;

/**
 * Roles manager UI (migrated from AI Settings -> added into Lab -> Chat Settings).
 */
public final class RolesManagerUi {

    private RolesManagerUi() {}

    public static void show(@NonNull Context context, @Nullable Runnable onRoleChanged) {
        if (!SPManager.isReady()) {
            Toast.makeText(context, "SPManager not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        SPManager sp = SPManager.getInstance();
        String rolesJson = sp.getRolesJson();
        String activeRoleId = sp.getActiveRoleId();

        List<RoleManager.Role> roles = RoleManager.loadRoles(rolesJson);

        FloatingBottomSheet sheet = BottomSheetHelper.showFloating(context, R.layout.bottom_sheet_roles);
        View content = sheet.getContentView();

        RecyclerView rv = content.findViewById(R.id.rv_roles);
        MaterialButton btnAdd = content.findViewById(R.id.btn_add_role);
        MaterialButton btnClose = content.findViewById(R.id.btn_close_roles);

        rv.setLayoutManager(new LinearLayoutManager(context));

        // NOTE: Do not reference the adapter variable from inside its own constructor call.
        // Use a tiny indirection so Java doesn't complain about "might not have been initialized".
        final RolesAdapter[] adapterRef = new RolesAdapter[1];

        final RolesAdapter adapter = new RolesAdapter(context, roles, activeRoleId, new RolesAdapter.OnRoleActionListener() {
            @Override
            public void onSelect(RoleManager.Role role) {
                sp.setActiveRoleId(role.id);
                RolesAdapter a = adapterRef[0];
                if (a != null) {
                    a.setActiveRoleId(role.id);
                    a.notifyDataSetChanged();
                }
                Toast.makeText(context, context.getString(R.string.role_selected), Toast.LENGTH_SHORT).show();
                if (onRoleChanged != null) onRoleChanged.run();
            }

            @Override
            public void onEdit(RoleManager.Role role) {
                RolesAdapter a = adapterRef[0];
                if (a == null) return;
                if (role != null && RoleManager.DEFAULT_ROLE_ID.equals(role.id)) {
                    // Default role is view-only.
                    showReadOnlyRoleDialog(context, role);
                    return;
                }
                showEditRoleDialog(context, role, roles, a, sp, onRoleChanged);
            }

            @Override
            public void onDelete(RoleManager.Role role) {
                RolesAdapter a = adapterRef[0];
                if (a != null) confirmDeleteRole(context, role, roles, a, sp, onRoleChanged);
            }
        });

        adapterRef[0] = adapter;

        rv.setAdapter(adapter);

        if (btnAdd != null) {
            btnAdd.setOnClickListener(v -> showAddRoleDialog(context, roles, adapter, sp, onRoleChanged));
        }

        if (btnClose != null) btnClose.setOnClickListener(v -> sheet.dismiss());
        sheet.show();
    }

    // ===== Agent editor helpers (emoji / variable chips / token probe / overrides) =====

    private static void bindEmojiPicker(@NonNull Context context, @Nullable TextView tvEmoji) {
        if (tvEmoji == null) return;
        tvEmoji.setOnClickListener(v -> {
            final String[] emojis = new String[]{
                    "🤖", "🧠", "💻", "📝", "🎨", "📊", "🧪", "🎯", "⚡", "🌌", "😎", "🕵️", "🧙"};
            new MaterialAlertDialogBuilder(context)
                    .setTitle("选择头像")
                    .setItems(emojis, (d, which) -> {
                        try { tvEmoji.setText(emojis[which]); } catch (Throwable ignored) {}
                    })
                    .show();
        });
    }

    private static void bindVariableChips(@NonNull Context context,
                                         @Nullable LinearLayout container,
                                         @Nullable TextInputEditText etPrompt) {
        if (container == null || etPrompt == null) return;
        container.removeAllViews();

        addVarChip(context, container, "✂️ 剪贴板", "%clipboard%", etPrompt);
        addVarChip(context, container, "📝 选中文本", "%selected%", etPrompt);
        addVarChip(context, container, "⏱️ 时间", "[time]", etPrompt);
        addVarChip(context, container, "📅 日期", "[date]", etPrompt);
    }

    private static void addVarChip(@NonNull Context context,
                                   @NonNull LinearLayout container,
                                   @NonNull String label,
                                   @NonNull String token,
                                   @NonNull TextInputEditText etPrompt) {
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);

        int fg = 0xFF607D8B;
        int bg = 0x1A607D8B;
        tv.setTextColor(fg);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bg);
        gd.setCornerRadius(dp(context, 999));
        tv.setBackground(gd);
        tv.setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMarginEnd(dp(context, 8));
        tv.setLayoutParams(lp);

        tv.setOnClickListener(v -> {
            try {
                Editable e = etPrompt.getText();
                if (e == null) return;
                int start = etPrompt.getSelectionStart();
                if (start < 0) start = e.length();
                e.insert(start, token);
                etPrompt.setSelection(Math.min(start + token.length(), e.length()));
            } catch (Throwable ignored) {}
        });

        container.addView(tv);
    }

    private static void bindTokenEstimator(@Nullable TextInputEditText etPrompt, @Nullable TextView tvToken) {
        if (etPrompt == null || tvToken == null) return;
        TextWatcher tw = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateTokenProbe(tvToken, s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {
                updateTokenProbe(tvToken, s == null ? "" : s.toString());
            }
        };
        etPrompt.addTextChangedListener(tw);
        try {
            CharSequence cur = etPrompt.getText();
            updateTokenProbe(tvToken, cur == null ? "" : cur.toString());
        } catch (Throwable ignored) {}
    }

    private static final Pattern EN_WORD = Pattern.compile("[A-Za-z0-9]+");

    private static void updateTokenProbe(@NonNull TextView tv, @NonNull String text) {
        int chars = text.length();
        int cjk = 0;
        try {
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                // Rough heuristic: treat non-ASCII as CJK/punctuation.
                if (ch > 0xFF) cjk++;
            }
        } catch (Throwable ignored) {}

        int words = 0;
        try {
            Matcher m = EN_WORD.matcher(text);
            while (m.find()) words++;
        } catch (Throwable ignored) {}

        int est = 0;
        try {
            est = Math.round((cjk * 1.5f) + words);
        } catch (Throwable ignored) {}

        try {
            tv.setText(chars + " 字 / 约 " + est + " Tokens");
        } catch (Throwable ignored) {}
    }

    private static void showOverrideModelInputDialog(@NonNull Context context, @NonNull TextView tvValue) {
        final TextInputEditText input = new TextInputEditText(context);
        input.setBackgroundResource(R.drawable.bg_input_field);
        input.setHint("留空表示跟随全局");
        input.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
        String cur = String.valueOf(tvValue.getText()).trim();
        if (!TextUtils.isEmpty(cur) && !"跟随全局".equals(cur)) input.setText(cur);

        new MaterialAlertDialogBuilder(context)
                .setTitle("专属大模型")
                .setView(input)
                .setNeutralButton("跟随全局", (d, w) -> {
                    try { tvValue.setText("跟随全局"); } catch (Throwable ignored) {}
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String v = input.getText() != null ? input.getText().toString().trim() : "";
                    if (TextUtils.isEmpty(v)) {
                        tvValue.setText("跟随全局");
                    } else {
                        tvValue.setText(v);
                    }
                })
                .show();
    }

    private static int dp(@NonNull Context context, float dp) {
        float d = context.getResources().getDisplayMetrics().density;
        return (int) (dp * d + 0.5f);
    }

    private static void showAddRoleDialog(
            @NonNull Context context,
            @NonNull List<RoleManager.Role> roles,
            @NonNull RolesAdapter adapter,
            @NonNull SPManager sp,
            @Nullable Runnable onRoleChanged
    ) {
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_add_role, null);
        TextView tvEmoji = dialogView.findViewById(R.id.tv_role_emoji);
        TextInputEditText etName = dialogView.findViewById(R.id.et_role_name);
        TextInputEditText etTrigger = dialogView.findViewById(R.id.et_role_trigger);
        TextInputEditText etPrompt = dialogView.findViewById(R.id.et_role_prompt);
        TextView tvToken = dialogView.findViewById(R.id.tv_token_count);
        LinearLayout llChips = dialogView.findViewById(R.id.ll_role_var_chips);

        SwitchCompat swOverride = dialogView.findViewById(R.id.sw_role_override);
        View panel = dialogView.findViewById(R.id.ll_role_override_panel);
        View rowOverrideModel = dialogView.findViewById(R.id.row_role_override_model);
        TextView tvOverrideModelValue = dialogView.findViewById(R.id.tv_role_override_model_value);
        Slider sliderOverrideTemp = dialogView.findViewById(R.id.slider_role_override_temp);
        TextView tvOverrideTempValue = dialogView.findViewById(R.id.tv_role_override_temp_value);

        // Defaults
        if (tvEmoji != null) tvEmoji.setText("🤖");
        if (tvOverrideModelValue != null) tvOverrideModelValue.setText("跟随全局");
        if (tvOverrideTempValue != null) tvOverrideTempValue.setText("0.7");
        if (sliderOverrideTemp != null) sliderOverrideTemp.setValue(0.7f);

        bindEmojiPicker(context, tvEmoji);
        bindVariableChips(context, llChips, etPrompt);
        bindTokenEstimator(etPrompt, tvToken);

        if (sliderOverrideTemp != null && tvOverrideTempValue != null) {
            sliderOverrideTemp.addOnChangeListener((s, value, fromUser) -> {
                try { tvOverrideTempValue.setText(String.format(Locale.US, "%.1f", value)); } catch (Throwable ignored) {}
            });
        }

        if (swOverride != null && panel != null) {
            swOverride.setOnCheckedChangeListener((btn, checked) -> panel.setVisibility(checked ? View.VISIBLE : View.GONE));
        }

        if (rowOverrideModel != null && tvOverrideModelValue != null) {
            rowOverrideModel.setOnClickListener(v -> {
                showOverrideModelInputDialog(context, tvOverrideModelValue);
            });
        }

        new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.add_role))
                .setView(dialogView)
                .setPositiveButton(context.getString(R.string.save_config), (d, w) -> {
                    String name = etName != null && etName.getText() != null ? etName.getText().toString().trim() : "";
                    String trigger = etTrigger != null && etTrigger.getText() != null ? etTrigger.getText().toString().trim() : "";
                    String prompt = etPrompt != null && etPrompt.getText() != null ? etPrompt.getText().toString().trim() : "";

                    String emoji = tvEmoji != null ? String.valueOf(tvEmoji.getText()).trim() : "🤖";

                    boolean overrideEnabled = swOverride != null && swOverride.isChecked();
                    String overrideSubModel = "";
                    if (overrideEnabled && tvOverrideModelValue != null) {
                        String v = String.valueOf(tvOverrideModelValue.getText()).trim();
                        if (!v.isEmpty() && !"跟随全局".equals(v)) overrideSubModel = v;
                    }
                    float overrideTemp = -1f;
                    if (overrideEnabled && sliderOverrideTemp != null) {
                        try { overrideTemp = sliderOverrideTemp.getValue(); } catch (Throwable ignored) {}
                    }

                    if (name.isEmpty() || prompt.isEmpty()) {
                        Toast.makeText(context, context.getString(R.string.role_name_or_prompt_empty), Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String id = "r_" + System.currentTimeMillis();
                    roles.add(new RoleManager.Role(id, name, prompt, trigger, emoji, overrideEnabled, overrideSubModel, overrideTemp));
                    sp.setRolesJson(RoleManager.serializeCustomRoles(roles));
                    sp.setActiveRoleId(id);

                    adapter.setActiveRoleId(id);
                    adapter.notifyDataSetChanged();

                    Toast.makeText(context, context.getString(R.string.role_saved), Toast.LENGTH_SHORT).show();
                    if (onRoleChanged != null) onRoleChanged.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showEditRoleDialog(
            @NonNull Context context,
            @Nullable RoleManager.Role role,
            @NonNull List<RoleManager.Role> roles,
            @NonNull RolesAdapter adapter,
            @NonNull SPManager sp,
            @Nullable Runnable onRoleChanged
    ) {
        if (role == null || RoleManager.DEFAULT_ROLE_ID.equals(role.id)) {
            Toast.makeText(context, context.getString(R.string.role_default_not_editable), Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_add_role, null);
        TextView tvEmoji = dialogView.findViewById(R.id.tv_role_emoji);
        TextInputEditText etName = dialogView.findViewById(R.id.et_role_name);
        TextInputEditText etTrigger = dialogView.findViewById(R.id.et_role_trigger);
        TextInputEditText etPrompt = dialogView.findViewById(R.id.et_role_prompt);
        TextView tvToken = dialogView.findViewById(R.id.tv_token_count);
        LinearLayout llChips = dialogView.findViewById(R.id.ll_role_var_chips);

        SwitchCompat swOverride = dialogView.findViewById(R.id.sw_role_override);
        View panel = dialogView.findViewById(R.id.ll_role_override_panel);
        View rowOverrideModel = dialogView.findViewById(R.id.row_role_override_model);
        TextView tvOverrideModelValue = dialogView.findViewById(R.id.tv_role_override_model_value);
        Slider sliderOverrideTemp = dialogView.findViewById(R.id.slider_role_override_temp);
        TextView tvOverrideTempValue = dialogView.findViewById(R.id.tv_role_override_temp_value);

        if (tvEmoji != null) tvEmoji.setText(role.emoji == null || role.emoji.trim().isEmpty() ? "🤖" : role.emoji);
        if (etName != null) etName.setText(role.name);
        if (etTrigger != null) etTrigger.setText(role.trigger);
        if (etPrompt != null) etPrompt.setText(role.prompt);

        bindEmojiPicker(context, tvEmoji);
        bindVariableChips(context, llChips, etPrompt);
        bindTokenEstimator(etPrompt, tvToken);

        if (tvOverrideModelValue != null) {
            if (role.overrideSubModel != null && !role.overrideSubModel.trim().isEmpty()) {
                tvOverrideModelValue.setText(role.overrideSubModel.trim());
            } else {
                tvOverrideModelValue.setText("跟随全局");
            }
        }
        if (sliderOverrideTemp != null && tvOverrideTempValue != null) {
            float t = role.overrideTemperature;
            if (t < 0f || t > 2.0f) t = 0.7f;
            sliderOverrideTemp.setValue(t);
            tvOverrideTempValue.setText(String.format(Locale.US, "%.1f", t));
            sliderOverrideTemp.addOnChangeListener((s, value, fromUser) -> {
                try { tvOverrideTempValue.setText(String.format(Locale.US, "%.1f", value)); } catch (Throwable ignored) {}
            });
        }

        if (swOverride != null && panel != null) {
            swOverride.setChecked(role.overrideEnabled);
            panel.setVisibility(role.overrideEnabled ? View.VISIBLE : View.GONE);
            swOverride.setOnCheckedChangeListener((btn, checked) -> panel.setVisibility(checked ? View.VISIBLE : View.GONE));
        }

        if (rowOverrideModel != null && tvOverrideModelValue != null) {
            rowOverrideModel.setOnClickListener(v -> showOverrideModelInputDialog(context, tvOverrideModelValue));
        }

        new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.edit_role))
                .setView(dialogView)
                .setPositiveButton(context.getString(R.string.save_config), (d, w) -> {
                    String name = etName != null && etName.getText() != null ? etName.getText().toString().trim() : "";
                    String trigger = etTrigger != null && etTrigger.getText() != null ? etTrigger.getText().toString().trim() : "";
                    String prompt = etPrompt != null && etPrompt.getText() != null ? etPrompt.getText().toString().trim() : "";

                    String emoji = tvEmoji != null ? String.valueOf(tvEmoji.getText()).trim() : "🤖";
                    boolean overrideEnabled = swOverride != null && swOverride.isChecked();
                    String overrideSubModel = "";
                    if (overrideEnabled && tvOverrideModelValue != null) {
                        String v = String.valueOf(tvOverrideModelValue.getText()).trim();
                        if (!v.isEmpty() && !"跟随全局".equals(v)) overrideSubModel = v;
                    }
                    float overrideTemp = -1f;
                    if (overrideEnabled && sliderOverrideTemp != null) {
                        try { overrideTemp = sliderOverrideTemp.getValue(); } catch (Throwable ignored) {}
                    }

                    if (name.isEmpty() || prompt.isEmpty()) {
                        Toast.makeText(context, context.getString(R.string.role_name_or_prompt_empty), Toast.LENGTH_SHORT).show();
                        return;
                    }

                    for (int i = 0; i < roles.size(); i++) {
                        if (role.id.equals(roles.get(i).id)) {
                            roles.set(i, new RoleManager.Role(role.id, name, prompt, trigger, emoji, overrideEnabled, overrideSubModel, overrideTemp));
                            break;
                        }
                    }
                    sp.setRolesJson(RoleManager.serializeCustomRoles(roles));
                    adapter.notifyDataSetChanged();

                    Toast.makeText(context, context.getString(R.string.role_saved), Toast.LENGTH_SHORT).show();
                    if (onRoleChanged != null) onRoleChanged.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void confirmDeleteRole(
            @NonNull Context context,
            @Nullable RoleManager.Role role,
            @NonNull List<RoleManager.Role> roles,
            @NonNull RolesAdapter adapter,
            @NonNull SPManager sp,
            @Nullable Runnable onRoleChanged
    ) {
        if (role == null || RoleManager.DEFAULT_ROLE_ID.equals(role.id)) {
            Toast.makeText(context, context.getString(R.string.role_default_not_deletable), Toast.LENGTH_SHORT).show();
            return;
        }

        new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.delete_role))
                .setMessage(context.getString(R.string.delete_role_confirm, role.name))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    RoleManager.Role toRemove = null;
                    for (RoleManager.Role r : roles) {
                        if (role.id.equals(r.id)) {
                            toRemove = r;
                            break;
                        }
                    }
                    if (toRemove != null) roles.remove(toRemove);

                    // If removed active role, fallback to default
                    if (role.id.equals(sp.getActiveRoleId())) {
                        sp.setActiveRoleId(RoleManager.DEFAULT_ROLE_ID);
                        adapter.setActiveRoleId(RoleManager.DEFAULT_ROLE_ID);
                    }

                    sp.setRolesJson(RoleManager.serializeCustomRoles(roles));
                    adapter.notifyDataSetChanged();

                    Toast.makeText(context, context.getString(R.string.role_deleted), Toast.LENGTH_SHORT).show();
                    if (onRoleChanged != null) onRoleChanged.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static class RolesAdapter extends RecyclerView.Adapter<RolesAdapter.RoleVH> {

        interface OnRoleActionListener {
            void onSelect(RoleManager.Role role);
            void onEdit(RoleManager.Role role);
            void onDelete(RoleManager.Role role);
        }

        private final Context context;
        private final List<RoleManager.Role> roles;
        private final OnRoleActionListener listener;
        private String activeRoleId;

        RolesAdapter(@NonNull Context context, @NonNull List<RoleManager.Role> roles, @Nullable String activeRoleId, @NonNull OnRoleActionListener listener) {
            this.context = context;
            this.roles = roles;
            this.activeRoleId = activeRoleId;
            this.listener = listener;
        }

        void setActiveRoleId(@Nullable String id) {
            this.activeRoleId = id;
        }

        @NonNull
        @Override
        public RoleVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_role, parent, false);
            return new RoleVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RoleVH holder, int position) {
            RoleManager.Role role = roles.get(position);

            holder.tvName.setText(role.name);

            if (holder.tvPrompt != null) {
                String p = role.prompt != null ? role.prompt.trim() : "";
                holder.tvPrompt.setText(p);
            }

            boolean isActive = role.id != null && role.id.equals(activeRoleId);
            // v8: Always show selection affordance.
            holder.ivCheck.setVisibility(View.VISIBLE);
            try {
                holder.ivCheck.setImageResource(isActive ? R.drawable.ic_check_circle_filled : R.drawable.ic_check_circle_outline);
                holder.ivCheck.setAlpha(isActive ? 1.0f : 0.35f);
            } catch (Throwable ignored) {}

            String triggerSymbol = "";
            try {
                triggerSymbol = SPManager.getInstance().getAiTriggerSymbol();
            } catch (Throwable ignored) {}

            boolean isDefault = role.id != null && RoleManager.DEFAULT_ROLE_ID.equals(role.id);

            if (isDefault) {
                holder.tvTrigger.setText(context.getString(R.string.role_trigger_display_default, triggerSymbol));
            } else if (role.trigger != null && !role.trigger.trim().isEmpty()) {
                // v8: show only role trigger keyword.
                holder.tvTrigger.setText(context.getString(R.string.role_trigger_display, role.trigger.trim()));
            } else {
                // Empty trigger means this role uses the default global AI trigger when selected.
                holder.tvTrigger.setText(context.getString(R.string.role_trigger_display, "默认"));
            }

            holder.btnEdit.setVisibility(View.VISIBLE);
            holder.btnDelete.setVisibility(isDefault ? View.GONE : View.VISIBLE);

            holder.itemView.setOnClickListener(v -> listener.onSelect(role));
            holder.ivCheck.setOnClickListener(v -> listener.onSelect(role));
            holder.btnEdit.setOnClickListener(v -> listener.onEdit(role));
            holder.btnDelete.setOnClickListener(v -> listener.onDelete(role));
        }

        @Override
        public int getItemCount() {
            return roles != null ? roles.size() : 0;
        }

        static class RoleVH extends RecyclerView.ViewHolder {
            TextView tvName;
            TextView tvPrompt;
            TextView tvTrigger;
            ImageView ivCheck;
            ImageButton btnEdit;
            ImageButton btnDelete;

            RoleVH(@NonNull View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tv_role_name);
                tvPrompt = itemView.findViewById(R.id.tv_role_prompt);
                tvTrigger = itemView.findViewById(R.id.tv_role_trigger);
                ivCheck = itemView.findViewById(R.id.iv_selected);
                btnEdit = itemView.findViewById(R.id.btn_edit);
                btnDelete = itemView.findViewById(R.id.btn_delete);
            }
        }
    }


    private static void showReadOnlyRoleDialog(Context context, RoleManager.Role role) {
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_add_role, null);
        TextInputEditText etName = dialogView.findViewById(R.id.et_role_name);
        TextInputEditText etPrompt = dialogView.findViewById(R.id.et_role_prompt);
        TextInputEditText etTrigger = dialogView.findViewById(R.id.et_role_trigger);

        if (etName != null) etName.setText(role.name);
        if (etPrompt != null) etPrompt.setText(role.prompt);
        if (etTrigger != null) etTrigger.setText(role.trigger);

        makeReadOnly(etName, false);
        makeReadOnly(etTrigger, false);
        // Prompt might be long; keep it scrollable.
        makeReadOnly(etPrompt, true);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(role.name)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, null);

        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> applyRoleDialogBackground(dialog));
        dialog.show();
    }

    private static void makeReadOnly(TextInputEditText et, boolean keepFocusableForScroll) {
        if (et == null) return;
        et.setKeyListener(null);
        et.setCursorVisible(false);
        try {
            et.setShowSoftInputOnFocus(false);
        } catch (Throwable ignored) {}
        if (!keepFocusableForScroll) {
            et.setFocusable(false);
            et.setFocusableInTouchMode(false);
        }
    }

    private static void applyRoleDialogBackground(androidx.appcompat.app.AlertDialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        int margin = (int) (16 * dialog.getContext().getResources().getDisplayMetrics().density);
        android.graphics.drawable.Drawable bg = ContextCompat.getDrawable(dialog.getContext(), R.drawable.bg_dialog_rounded_stroke);
        if (bg != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.InsetDrawable(bg, margin));
        }
    }
}
