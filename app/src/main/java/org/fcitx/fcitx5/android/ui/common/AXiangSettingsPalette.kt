/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.common

import android.content.Context
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.R

/** Semantic settings colors shared by native preference pages and custom settings views. */
data class AXiangSettingsPalette(
    @ColorInt val background: Int,
    @ColorInt val surface: Int,
    @ColorInt val text: Int,
    @ColorInt val secondary: Int,
    @ColorInt val accent: Int,
    @ColorInt val accentSoft: Int,
    @ColorInt val divider: Int,
    @ColorInt val ripple: Int
) {
    companion object {
        fun from(context: Context) = AXiangSettingsPalette(
            ContextCompat.getColor(context, R.color.ax_settings_background),
            ContextCompat.getColor(context, R.color.ax_settings_surface),
            ContextCompat.getColor(context, R.color.ax_settings_text),
            ContextCompat.getColor(context, R.color.ax_settings_secondary),
            ContextCompat.getColor(context, R.color.ax_settings_accent),
            ContextCompat.getColor(context, R.color.ax_settings_accent_soft),
            ContextCompat.getColor(context, R.color.ax_settings_divider),
            ContextCompat.getColor(context, R.color.ax_settings_ripple)
        )
    }
}
