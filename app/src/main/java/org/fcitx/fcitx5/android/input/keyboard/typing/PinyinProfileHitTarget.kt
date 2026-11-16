/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import kotlin.math.abs

/** Moves only a shared letter boundary; the layout and slide targets stay unmodified. */
internal object PinyinProfileHitTarget {
    fun choose(
        original: Char,
        x: Float,
        y: Float,
        cells: List<KeyCell>,
        offsets: Map<Char, CenterOffset>
    ): Char {
        if (offsets.isEmpty() || !x.isFinite() || !y.isFinite()) return original
        val current = cells.firstOrNull { it.letter == original } ?: return original
        if (!current.isValid() || !current.contains(x, y)) return original

        var choice = original
        for (neighbor in cells) {
            if (neighbor.letter == original || !neighbor.isValid()) continue
            val currentOffset = offsets[original]
            val neighborOffset = offsets[neighbor.letter]
            if (currentOffset == null && neighborOffset == null) continue

            val crosses = when {
                between(y, current.top, current.bottom, neighbor.top, neighbor.bottom) &&
                    abs(current.right - neighbor.left) <= EDGE_EPSILON -> {
                    x > current.right + shift(current.width, neighbor.width, currentOffset?.x, neighborOffset?.x)
                }
                between(y, current.top, current.bottom, neighbor.top, neighbor.bottom) &&
                    abs(current.left - neighbor.right) <= EDGE_EPSILON -> {
                    x < current.left + shift(current.width, neighbor.width, currentOffset?.x, neighborOffset?.x)
                }
                between(x, current.left, current.right, neighbor.left, neighbor.right) &&
                    abs(current.bottom - neighbor.top) <= EDGE_EPSILON -> {
                    y > current.bottom + shift(current.height, neighbor.height, currentOffset?.y, neighborOffset?.y)
                }
                between(x, current.left, current.right, neighbor.left, neighbor.right) &&
                    abs(current.top - neighbor.bottom) <= EDGE_EPSILON -> {
                    y < current.top + shift(current.height, neighbor.height, currentOffset?.y, neighborOffset?.y)
                }
                else -> false
            }
            if (crosses) {
                // Two different neighbouring keys can overlap at a corner.
                // Neither wins on ambiguous evidence; keep the original key.
                if (choice != original) return original
                choice = neighbor.letter
            }
        }
        return choice
    }

    private fun shift(width: Float, neighborWidth: Float, offset: Float?, neighborOffset: Float?): Float {
        // Interpolate the displaced centres in the same proportion as the
        // original boundary. With unequal widths, two zero offsets still
        // preserve the old boundary rather than moving to the midpoint.
        return (fraction(offset) + fraction(neighborOffset)) * (width * (neighborWidth / (width + neighborWidth)))
    }

    private fun fraction(offset: Float?): Float = offset?.takeIf(Float::isFinite)
        ?.coerceIn(-PinyinTouchProfile.MAX_OFFSET.toFloat(), PinyinTouchProfile.MAX_OFFSET.toFloat()) ?: 0f

    private fun between(value: Float, firstStart: Float, firstEnd: Float, secondStart: Float, secondEnd: Float): Boolean =
        value >= maxOf(firstStart, secondStart) && value < minOf(firstEnd, secondEnd)

    private const val EDGE_EPSILON = 0.01f // only float rounding, never a gap between keys
}
