/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.data

import kotlin.math.roundToInt

/** Maps the optional strength control without rewriting legacy pulse preferences. */
internal object HapticStrength {
    fun amplitude(percent: Int, storedAmplitude: Int): Int {
        val strength = percent.coerceIn(0, 100)
        if (strength == 0) return 0
        val base = if (storedAmplitude > 0) storedAmplitude.coerceAtMost(255) else 255
        return (base * strength / 100f).roundToInt().coerceIn(1, 255)
    }

    fun duration(storedMilliseconds: Long, longPress: Boolean, keyUp: Boolean): Long {
        if (storedMilliseconds > 0L) return storedMilliseconds
        return when {
            longPress -> 32L
            keyUp -> 10L
            else -> 16L
        }
    }
}
