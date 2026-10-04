/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import kotlin.math.roundToInt

/** Stateless: a re-press follows its new face immediately, with no queued colour animator. */
internal object KeyLegendInk {
    private const val DARK = 0xFF161A1E.toInt()

    fun color(brightness: Float, original: Int): Int {
        val t = ((brightness - 0.38f) / 0.22f).coerceIn(0f, 1f)
        if (t <= 0f) return original
        if (t >= 1f) return DARK
        val blend = t * t * (3f - 2f * t)
        fun channel(shift: Int): Int {
            val from = original ushr shift and 255
            val to = DARK ushr shift and 255
            return (from + (to - from) * blend).roundToInt() shl shift
        }
        return channel(24) or channel(16) or channel(8) or channel(0)
    }
}
