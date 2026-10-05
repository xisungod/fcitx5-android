/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePreset

/** A bundled OFL font for the illuminated keyboard; other UI keeps the system font. */
internal object KeyLegendTypeface {
    fun usesDisplayFont(theme: Theme, text: CharSequence): Boolean =
        (theme == ThemePreset.Sam || theme == ThemePreset.XuancaiBlackV09) &&
            text.isNotEmpty() && text.all {
                it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9'
            }

    fun resolve(context: Context, theme: Theme, text: CharSequence, fallback: Typeface): Typeface =
        if (usesDisplayFont(theme, text)) {
            ResourcesCompat.getFont(context, R.font.audiowide_regular) ?: fallback
        } else fallback
}
