/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.theme.PressColorPalette
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class V15NeonPaletteTest {
    private fun hue(color: Int): Float {
        val red = ((color ushr 16) and 255) / 255f
        val green = ((color ushr 8) and 255) / 255f
        val blue = (color and 255) / 255f
        val high = max(red, max(green, blue))
        val delta = high - min(red, min(green, blue))
        if (delta == 0f) return 0f
        val sector = when (high) {
            red -> (green - blue) / delta
            green -> (blue - red) / delta + 2f
            else -> (red - green) / delta + 4f
        }
        return (sector * 60f + 360f) % 360f
    }

    private fun sequence(seed: Int, count: Int): List<Int> {
        val random = Random(seed)
        var previous = -1
        return List(count) {
            PressColorPalette.nextCyberpunkIndex(previous, random).also { previous = it }
        }
    }

    @Test
    fun presetStaysBrightAndSaturatedAcrossItsFullColourRange() {
        val palette = PressColorPalette.CYBERPUNK
        assertEquals(20, palette.toSet().size)
        palette.forEach { color ->
            val components = intArrayOf((color ushr 16) and 255, (color ushr 8) and 255, color and 255)
            val high = components.max()
            val low = components.min()
            assertEquals("Neon colours remain opaque", 255, color ushr 24)
            assertEquals("Each hue retains a full-brightness channel", 255, high)
            assertTrue("Saturation must remain at least 75%", (high - low).toFloat() / high >= 0.75f)
        }
    }

    @Test
    fun consecutiveColoursVisitTheWholeNeonWheelWithWarmColoursAsOftenAsCoolOnes() {
        val palette = PressColorPalette.CYBERPUNK
        val presses = sequence(1501, 20000)
        assertEquals("A long session retains the complete neon variety", palette.indices.toSet(), presses.toSet())
        presses.zipWithNext().forEach { (left, right) ->
            assertNotEquals("Consecutive presses keep a visible colour change", left, right)
            val distance = abs(hue(palette[left]) - hue(palette[right]))
            assertTrue("A neighbouring hue cannot jump across the colour wheel", min(distance, 360f - distance) <= 65f)
        }
        val warmShare = presses.count { hue(palette[it]) >= 340f || hue(palette[it]) < 80f }.toFloat() / presses.size
        val coolShare = presses.count { hue(palette[it]) in 180f..280f }.toFloat() / presses.size
        assertTrue("Red, orange and yellow are regular colours, rather than rare accents", warmShare in 0.25f..0.45f)
        assertTrue("The cool colours cannot dominate almost the whole sequence", coolShare in 0.20f..0.40f)
        palette.indices.forEach { index ->
            val share = presses.count { it == index }.toFloat() / presses.size
            assertTrue("Every neon colour remains part of normal typing: $index has share $share", share in 0.025f..0.08f)
        }
        assertEquals("A seeded session remains reproducible", presses.take(200), sequence(1501, 200))
    }

}
