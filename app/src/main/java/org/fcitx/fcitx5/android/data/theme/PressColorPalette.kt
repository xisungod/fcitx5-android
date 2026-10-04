/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import java.util.Locale
import kotlin.random.Random

/** RGB colours shared by the picker, persistent settings and the keyboard renderer. */
object PressColorPalette {
    const val MAX_COLORS = 8
    private val rgb = Regex("[0-9a-fA-F]{6}")

    /** Full neon hue wheel: cyan/blue/violet/pink/red/orange/yellow/lime/green/mint. */
    val CYBERPUNK = intArrayOf(
        0xFF00EFFF.toInt(), 0xFF00B8FF.toInt(), 0xFF0075FF.toInt(), 0xFF344BFF.toInt(),
        0xFF7028FF.toInt(), 0xFFB126FF.toInt(), 0xFFEE20FF.toInt(), 0xFFFF20CB.toInt(),
        0xFFFF208D.toInt(), 0xFFFF2457.toInt(), 0xFFFF3824.toInt(), 0xFFFF6320.toInt(),
        0xFFFF8A1F.toInt(), 0xFFFFB521.toInt(), 0xFFFFED25.toInt(), 0xFFC8FF25.toInt(),
        0xFF6EFF2D.toInt(), 0xFF24FF67.toInt(), 0xFF20FFB1.toInt(), 0xFF00FFD9.toInt()
    )

    /** Narrow neon hue paths. Their ends are not neighbours, so selection reflects at either end. */
    val CYAN_BLUE_PURPLE = intArrayOf(0xFF00F0FF.toInt(), 0xFF00B8FF.toInt(), 0xFF0075FF.toInt(),
        0xFF334BFF.toInt(), 0xFF7028FF.toInt(), 0xFFB126FF.toInt())
    val RED_ORANGE_YELLOW = intArrayOf(0xFFFF243F.toInt(), 0xFFFF3824.toInt(), 0xFFFF6320.toInt(),
        0xFFFF8A1F.toInt(), 0xFFFFB521.toInt(), 0xFFFFED25.toInt())
    val PURPLE_PINK_RED = intArrayOf(0xFF9C26FF.toInt(), 0xFFCE20FF.toInt(), 0xFFFF20E0.toInt(),
        0xFFFF20AA.toInt(), 0xFFFF2070.toInt(), 0xFFFF243F.toInt())
    val MINT_CYAN = intArrayOf(0xFF24FF83.toInt(), 0xFF20FFA4.toInt(), 0xFF20FFC4.toInt(),
        0xFF00FFE4.toInt(), 0xFF00F0FF.toInt(), 0xFF00CDFF.toInt())
    val BLUE_PURPLE_PINK = intArrayOf(0xFF2470FF.toInt(), 0xFF4830FF.toInt(), 0xFF8628FF.toInt(),
        0xFFC126FF.toInt(), 0xFFFF20EA.toInt(), 0xFFFF209F.toInt())

    // Keep older saved enum names and palettes available, including non-neon legacy choices.
    val COOL = intArrayOf(0xFF3E91FF.toInt(), 0xFF00C2FF.toInt(), 0xFF2DE2C9.toInt(),
        0xFF7FB7FF.toInt(), 0xFF5B6CFF.toInt(), 0xFFA8E6FF.toInt())
    val RAINBOW = intArrayOf(0xFFFF4D4D.toInt(), 0xFFFF9F1C.toInt(), 0xFFFFE14D.toInt(),
        0xFF4DFF88.toInt(), 0xFF33D6FF.toInt(), 0xFF4D7CFF.toInt(), 0xFFB24DFF.toInt())
    val BERRY = intArrayOf(0xFF9F75FD.toInt(), 0xFFC08CFF.toInt(), 0xFFFF8AD8.toInt(),
        0xFF7B6CFF.toInt(), 0xFFE0B3FF.toInt())

    val recommendedPresets = linkedMapOf(
        ThemePrefs.PressEffectPalette.SamsungCool to CYAN_BLUE_PURPLE,
        ThemePrefs.PressEffectPalette.RedOrangeYellow to RED_ORANGE_YELLOW,
        ThemePrefs.PressEffectPalette.PurplePinkRed to PURPLE_PINK_RED,
        ThemePrefs.PressEffectPalette.MintCyan to MINT_CYAN,
        ThemePrefs.PressEffectPalette.BluePurplePink to BLUE_PURPLE_PINK,
        ThemePrefs.PressEffectPalette.Cyberpunk to CYBERPUNK
    )

    fun isCoordinated(palette: IntArray): Boolean = recommendedPresets.values.any { it.contentEquals(palette) }

    fun nextFamilyIndex(previousIndex: Int, size: Int, random: Random): Int {
        if (size <= 1) return 0
        if (previousIndex !in 0 until size) return random.nextInt(size)
        return when (previousIndex) {
            0 -> 1
            size - 1 -> size - 2
            else -> previousIndex + if (random.nextBoolean()) 1 else -1
        }
    }

    /** One resolver is shared by preview cards, persisted settings and the actual keyboard. */
    fun presetColors(preset: ThemePrefs.PressEffectPalette, prefs: ThemePrefs, accentColor: Int): IntArray =
        recommendedPresets[preset] ?: when (preset) {
            ThemePrefs.PressEffectPalette.Cool -> COOL
            ThemePrefs.PressEffectPalette.Rainbow -> RAINBOW
            ThemePrefs.PressEffectPalette.Berry -> BERRY
            ThemePrefs.PressEffectPalette.Accent -> intArrayOf(accentColor.takeIf { it ushr 24 != 0 } ?: 0xFF76B5FF.toInt())
            ThemePrefs.PressEffectPalette.Custom -> listOf(prefs.pressCustomColor1, prefs.pressCustomColor2,
                prefs.pressCustomColor3, prefs.pressCustomColor4)
                .take(prefs.pressCustomColorCount.getValue().coerceIn(1, 4)).map { it.getValue().argb }.toIntArray()
            else -> error("Unregistered neon preset: $preset")
        }

    fun colorsFor(prefs: ThemePrefs, accentColor: Int): IntArray = when (prefs.pressColorMode.getValue()) {
        ThemePrefs.PressColorMode.Random -> presetColors(prefs.pressEffectPalette.getValue(), prefs, accentColor)
        ThemePrefs.PressColorMode.Single -> intArrayOf(prefs.pressSingleColor.getValue() or 0xFF000000.toInt())
        ThemePrefs.PressColorMode.Custom -> prefs.userPressColors()
    }

    /**
     * Visit the whole neon wheel rather than keeping warm colours as rare accents.
     * Short random steps keep adjacent presses harmonious while a forward bias
     * prevents the sequence lingering in one small blue/purple neighbourhood.
     * Only the Cyberpunk preset uses this ordering; user palettes stay untouched.
     */
    fun nextCyberpunkIndex(previousIndex: Int, random: Random): Int {
        if (previousIndex !in CYBERPUNK.indices) return random.nextInt(CYBERPUNK.size)
        val step = if (random.nextInt(100) < 65) 2 else 1
        val direction = if (random.nextInt(100) < 75) 1 else -1
        return (previousIndex + direction * step).mod(CYBERPUNK.size)
    }

    fun parseColor(raw: String): Int? {
        val value = raw.trim().removePrefix("#")
        if (!rgb.matches(value)) return null
        return value.toInt(16) or 0xff000000.toInt()
    }

    fun formatColor(color: Int): String = String.format(Locale.ROOT, "#%06X", color and 0x00ffffff)

    fun parse(raw: String): IntArray = raw.split(',').asSequence()
        .mapNotNull(::parseColor).take(MAX_COLORS).toList().toIntArray()

    fun encode(colors: IntArray): String = colors.take(MAX_COLORS).joinToString(",", transform = ::formatColor)
}
