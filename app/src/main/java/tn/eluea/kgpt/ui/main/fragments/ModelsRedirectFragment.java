/*
 * Copyright (C) 2024-2026 Amr Aldeeb @Eluea
 *
 * This file is part of KGPT - a fork of KeyboardGPT.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package tn.eluea.kgpt.ui.main.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import tn.eluea.kgpt.R;

/**
 * "AI Settings / Models" has been fully integrated into:
 * Lab -> Conversation Settings -> AI 大模型.
 *
 * This fragment acts as a lightweight placeholder to avoid duplicated
 * configuration surfaces.
 */
public class ModelsRedirectFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_models_redirect, container, false);
        View btn = v.findViewById(R.id.btn_open_lab);
        if (btn != null) {
            btn.setOnClickListener(view -> {
                View labTab = requireActivity().findViewById(R.id.nav_lab);
                if (labTab != null) {
                    labTab.performClick();
                }
            });
        }
        return v;
    }
}
