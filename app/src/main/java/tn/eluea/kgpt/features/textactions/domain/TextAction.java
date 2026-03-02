/*
 * Copyright (C) 2024-2025 Amr Aldeeb @Eluea
 * 
 * This file is part of KGPT - a fork of KeyboardGPT.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * GitHub: https://github.com/Eluea
 * Telegram: https://t.me/Eluea
 */
package tn.eluea.kgpt.features.textactions.domain;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

import androidx.annotation.NonNull;

import tn.eluea.kgpt.R;

/**
 * Enum representing available AI text actions that can be performed on selected
 * text.
 */
public enum TextAction {
    REPHRASE("Rephrase", "Rephrase", "改写", R.drawable.ic_message_text_filled, "#4CAF50"),
    FIX_ERRORS("Fix Errors", "Fix Errors", "修复错误", R.drawable.ic_shield_tick_filled, "#2196F3"),
    IMPROVE("Improve", "Improve", "优化", R.drawable.ic_lamp_charge_filled, "#9C27B0"),
    EXPAND("Expand", "Expand", "扩写", R.drawable.ic_arrow_circle_right_filled, "#FF9800"),
    SHORTEN("Shorten", "Shorten", "精简", R.drawable.ic_close_circle_filled, "#F44336"),
    FORMAL("Formal", "Formal", "正式", R.drawable.ic_document_text_filled, "#607D8B"),
    CASUAL("Casual", "Casual", "口语", R.drawable.ic_palette_filled, "#E91E63"),
    TRANSLATE("Translate", "Translate", "翻译", R.drawable.ic_global_search_filled, "#00BCD4");

    public final String labelEn;
    public final String labelAr;
    public final String labelZh;
    public final int iconRes;
    public final String color;

    TextAction(String labelEn, String labelAr, String labelZh, int iconRes, String color) {
        this.labelEn = labelEn;
        this.labelAr = labelAr;
        this.labelZh = labelZh;
        this.iconRes = iconRes;
        this.color = color;
    }

    public String getLabel(boolean isArabic) {
        return labelEn;
    }

    /** Localized label for settings/UI screens. */
    public String getLabel(@NonNull Context ctx) {
        try {
            Configuration cfg = ctx.getResources().getConfiguration();
            Locale locale;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                locale = cfg.getLocales() != null && cfg.getLocales().size() > 0 ? cfg.getLocales().get(0)
                        : Locale.getDefault();
            } else {
                //noinspection deprecation
                locale = cfg.locale != null ? cfg.locale : Locale.getDefault();
            }
            String lang = locale != null ? locale.getLanguage() : "";
            if (lang != null && lang.startsWith("zh")) {
                return labelZh != null ? labelZh : labelEn;
            }
        } catch (Throwable ignored) {
        }
        return labelEn;
    }
}
