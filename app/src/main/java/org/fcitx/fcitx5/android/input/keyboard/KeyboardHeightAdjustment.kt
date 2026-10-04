/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import kotlin.math.roundToInt

/** A preview transaction. Only Done writes the preference captured for this orientation. */
internal class KeyboardHeightAdjustment(
    private val preference: ManagedPreference.PInt,
    private val baseHeightPx: Int,
    private val portrait: Boolean,
    private val textLayout: Boolean,
    private val showNumberRow: Boolean,
    minimumHeightPx: Int,
    maximumHeightPx: Int
) {
    private fun height(percent: Int) = KeyboardSizePolicy.heightForLayout(
        baseHeightPx * percent / 100, portrait, textLayout, showNumberRow
    )

    // Use the same integer percentage and layout rounding as the saved setting.
    private val maximumPercent = (10..90).lastOrNull { height(it) <= maximumHeightPx } ?: 10
    private val minimumPercent = (10..maximumPercent).firstOrNull { height(it) >= minimumHeightPx }
        ?: maximumPercent
    val minimumHeightPx get() = height(minimumPercent)
    val maximumHeightPx get() = height(maximumPercent)
    var percent = preference.getValue()
        private set
    val heightPx get() = height(percent)
    private var changed = false

    fun preview(requestedHeightPx: Int): Int {
        val ratio = if (portrait && textLayout && !showNumberRow) 0.8f else 1f
        percent = (requestedHeightPx * 100f / (baseHeightPx.coerceAtLeast(1) * ratio))
            .roundToInt().coerceIn(minimumPercent, maximumPercent)
        changed = true
        return heightPx
    }

    fun reset(): Int {
        percent = preference.defaultValue.coerceIn(minimumPercent, maximumPercent)
        changed = true
        return heightPx
    }

    fun save() {
        // Opening and closing the editor must not clamp a user's existing setting.
        if (changed && percent != preference.getValue()) preference.setValue(percent)
    }
}
