package tn.eluea.kgpt.ui.roles;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

    private static void showAddRoleDialog(
            @NonNull Context context,
            @NonNull List<RoleManager.Role> roles,
            @NonNull RolesAdapter adapter,
            @NonNull SPManager sp,
            @Nullable Runnable onRoleChanged
    ) {
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_add_role, null);
        TextInputEditText etName = dialogView.findViewById(R.id.et_role_name);
        TextInputEditText etTrigger = dialogView.findViewById(R.id.et_role_trigger);
        TextInputEditText etPrompt = dialogView.findViewById(R.id.et_role_prompt);

        new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.add_role))
                .setView(dialogView)
                .setPositiveButton(context.getString(R.string.save_config), (d, w) -> {
                    String name = etName != null && etName.getText() != null ? etName.getText().toString().trim() : "";
                    String trigger = etTrigger != null && etTrigger.getText() != null ? etTrigger.getText().toString().trim() : "";
                    String prompt = etPrompt != null && etPrompt.getText() != null ? etPrompt.getText().toString().trim() : "";

                    if (name.isEmpty() || prompt.isEmpty()) {
                        Toast.makeText(context, context.getString(R.string.role_name_or_prompt_empty), Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String id = "r_" + System.currentTimeMillis();
                    roles.add(new RoleManager.Role(id, name, prompt, trigger));
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
        TextInputEditText etName = dialogView.findViewById(R.id.et_role_name);
        TextInputEditText etTrigger = dialogView.findViewById(R.id.et_role_trigger);
        TextInputEditText etPrompt = dialogView.findViewById(R.id.et_role_prompt);

        if (etName != null) etName.setText(role.name);
        if (etTrigger != null) etTrigger.setText(role.trigger);
        if (etPrompt != null) etPrompt.setText(role.prompt);

        new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.edit_role))
                .setView(dialogView)
                .setPositiveButton(context.getString(R.string.save_config), (d, w) -> {
                    String name = etName != null && etName.getText() != null ? etName.getText().toString().trim() : "";
                    String trigger = etTrigger != null && etTrigger.getText() != null ? etTrigger.getText().toString().trim() : "";
                    String prompt = etPrompt != null && etPrompt.getText() != null ? etPrompt.getText().toString().trim() : "";

                    if (name.isEmpty() || prompt.isEmpty()) {
                        Toast.makeText(context, context.getString(R.string.role_name_or_prompt_empty), Toast.LENGTH_SHORT).show();
                        return;
                    }

                    for (int i = 0; i < roles.size(); i++) {
                        if (role.id.equals(roles.get(i).id)) {
                            roles.set(i, new RoleManager.Role(role.id, name, prompt, trigger));
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
            holder.ivCheck.setVisibility(isActive ? View.VISIBLE : View.GONE);

            String triggerSymbol = "";
            try {
                triggerSymbol = SPManager.getInstance().getAiTriggerSymbol();
            } catch (Throwable ignored) {}

            boolean isDefault = role.id != null && RoleManager.DEFAULT_ROLE_ID.equals(role.id);

            if (isDefault && role.trigger != null && !role.trigger.isEmpty()) {
                holder.tvTrigger.setText(context.getString(R.string.role_trigger_display_default, triggerSymbol + role.trigger));
            } else if (role.trigger != null && !role.trigger.isEmpty()) {
                holder.tvTrigger.setText(context.getString(R.string.role_trigger_display, triggerSymbol + role.trigger));
            } else {
                holder.tvTrigger.setText("");
            }

            holder.btnEdit.setVisibility(View.VISIBLE);
            holder.btnDelete.setVisibility(isDefault ? View.GONE : View.VISIBLE);

            holder.itemView.setOnClickListener(v -> listener.onSelect(role));
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
