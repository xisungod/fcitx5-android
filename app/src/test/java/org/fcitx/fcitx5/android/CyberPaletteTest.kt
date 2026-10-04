/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.data.theme.PressColorPalette
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class CyberPaletteTest {
    @Test fun sixDifferentPresetsRemainOpaqueBrightAndSaturated() {
        val presets = PressColorPalette.recommendedPresets
        assertEquals(6, presets.size)
        assertEquals(ThemePrefs.PressEffectPalette.SamsungCool, presets.keys.first())
        assertEquals(6, presets.values.map { it.toList() }.toSet().size)
        presets.forEach { (preset, palette) ->
            assertTrue(preset.name, PressColorPalette.isCoordinated(palette))
            assertEquals(preset.name, palette.size, palette.toSet().size)
            palette.forEach { color ->
                val rgb = listOf((color ushr 16) and 255, (color ushr 8) and 255, color and 255)
                assertEquals(preset.name, 255, color ushr 24)
                assertEquals(preset.name, 255, rgb.max())
                // The existing full wheel keeps its tested 75% floor and exact RGB values.
                // Newly introduced narrow neon families use the stronger 80% floor.
                val saturationFloor = if (preset == ThemePrefs.PressEffectPalette.Cyberpunk) 0.75f else 0.8f
                assertTrue("${preset.name}: ${PressColorPalette.formatColor(color)} saturation",
                    (rgb.max() - rgb.min()).toFloat() / 255f >= saturationFloor)
            }
        }
        assertFalse(PressColorPalette.isCoordinated(intArrayOf(0xff989898.toInt())))
    }

    @Test fun restrictedFamiliesWalkAdjacentTonesWithoutWrappingBetweenTheEndpoints() {
        PressColorPalette.recommendedPresets.filterKeys { it != ThemePrefs.PressEffectPalette.Cyberpunk }
            .forEach { (preset, colors) ->
                val random = Random(1107)
                var previous = -1
                val visited = mutableSetOf<Int>()
                repeat(2000) {
                    val next = PressColorPalette.nextFamilyIndex(previous, colors.size, random)
                    assertTrue(preset.name, next in colors.indices)
                    if (previous >= 0) assertEquals("${preset.name}: adjacent tones only", 1, abs(next - previous))
                    visited += next
                    previous = next
                }
                assertEquals(preset.name, colors.indices.toSet(), visited)
            }
    }
}
