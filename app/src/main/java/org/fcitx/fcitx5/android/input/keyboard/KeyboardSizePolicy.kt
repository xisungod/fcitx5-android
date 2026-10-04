/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Row geometry shared by the keyboard container and settings validation. */
object KeyboardSizePolicy {
    private const val PORTRAIT_REFERENCE_ROWS = 5
    private const val KEYCAP_HEIGHT_RATIO = 1.16f
    private const val PORTRAIT_DEFAULT_HEIGHT_SCALE = 1.10f

    /** Roomier portrait default; this never rewrites a user's saved height percentage. */
    fun defaultPortraitHeightPercent(widthPixels: Int, heightPixels: Int, density: Float): Int {
        if (widthPixels <= 0 || heightPixels <= 0 || density <= 0f) return 35
        val portraitWidth = min(widthPixels, heightPixels).toFloat()
        val portraitHeight = max(widthPixels, heightPixels).toFloat()
        val capWidth = (portraitWidth / 10f - 6f * density).coerceAtLeast(12f * density)
        val rowHeight = capWidth * KEYCAP_HEIGHT_RATIO + 8f * density
        val referencePercent = (rowHeight * PORTRAIT_REFERENCE_ROWS / portraitHeight * 100f).roundToInt()
        return (referencePercent * PORTRAIT_DEFAULT_HEIGHT_SCALE).roundToInt().coerceIn(10, 90)
    }

    /** The toolbar lives outside this height, and number/symbol pages keep the normal page height. */
    fun heightForLayout(baseHeightPixels: Int, portrait: Boolean, textLayout: Boolean, showNumberRow: Boolean): Int =
        if (portrait && textLayout && !showNumberRow) (baseHeightPixels * 4f / PORTRAIT_REFERENCE_ROWS).roundToInt()
        else baseHeightPixels

    /** A saved side margin can exceed the available width after rotation or on a smaller phone. */
    fun sidePaddingForWidth(requestedPixels: Int, widthPixels: Int, density: Float): Int {
        if (widthPixels <= 0) return requestedPixels.coerceAtLeast(0)
        val minimumContent = min(widthPixels, (200f * density).roundToInt().coerceAtLeast(1))
        return requestedPixels.coerceIn(0, (widthPixels - minimumContent) / 2)
    }
}
