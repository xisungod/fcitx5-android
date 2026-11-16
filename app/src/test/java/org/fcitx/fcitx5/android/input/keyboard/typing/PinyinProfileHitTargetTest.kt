/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import org.junit.Assert.assertEquals
import org.junit.Test

class PinyinProfileHitTargetTest {
    private val a = KeyCell('a', 0f, 0f, 30f, 40f)
    private val b = KeyCell('b', 30f, 0f, 90f, 40f)
    private val c = KeyCell('c', 0f, 40f, 25f, 90f)

    @Test fun horizontalBoundaryMovesBetweenUnequalWidthsButLeavesTheirCentresAlone() {
        val cells = listOf(a, b)
        val offsets = mapOf('a' to CenterOffset(-.12f, 0f), 'b' to CenterOffset(-.12f, 0f))
        assertEquals('a', PinyinProfileHitTarget.choose('a', 29f, 20f, cells, emptyMap()))
        assertEquals('b', PinyinProfileHitTarget.choose('a', 29f, 20f, cells, offsets))
        assertEquals('a', PinyinProfileHitTarget.choose('a', 24f, 20f, cells, offsets))
        assertEquals('a', PinyinProfileHitTarget.choose('a', a.centerX, a.centerY, cells, offsets))
        assertEquals('b', PinyinProfileHitTarget.choose('b', b.centerX, b.centerY, cells, offsets))
    }

    @Test fun verticalBoundaryMovesOnlyOverTheSharedRowSpan() {
        val offsets = mapOf('c' to CenterOffset(0f, -.12f))
        val cells = listOf(a, c)
        assertEquals('c', PinyinProfileHitTarget.choose('a', 12f, 39f, cells, offsets))
        assertEquals('a', PinyinProfileHitTarget.choose('a', 28f, 39f, cells, offsets))
        val diagonal = KeyCell('d', a.right, a.bottom, 60f, 80f)
        assertEquals('a', PinyinProfileHitTarget.choose('a', 29.9f, 39.9f, listOf(a, diagonal),
            mapOf('d' to CenterOffset(-.12f, -.12f))))
    }

    @Test fun clampsBadOffsetsAndPreservesLiteralAtAnAmbiguousCorner() {
        assertEquals('a', PinyinProfileHitTarget.choose('a', 25f, 20f, listOf(a, b),
            mapOf('b' to CenterOffset(-10f, 0f))))
        assertEquals('a', PinyinProfileHitTarget.choose('a', 29f, 20f, listOf(a, b),
            mapOf('b' to CenterOffset(Float.NaN, 0f))))
        val wideC = c.copy(right = a.right)
        assertEquals('a', PinyinProfileHitTarget.choose('a', 29f, 39f, listOf(a, b, wideC),
            mapOf('b' to CenterOffset(-.12f, 0f), 'c' to CenterOffset(0f, -.12f))))
    }
}
