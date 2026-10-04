/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.theme

import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.edit
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceCategory
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

class ThemePrefs(sharedPreferences: SharedPreferences) :
    ManagedPreferenceCategory(R.string.theme, sharedPreferences) {

    private fun themePreference(
        @StringRes
        title: Int,
        key: String,
        defaultValue: Theme,
        @StringRes
        summary: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ): ManagedThemePreference {
        val pref = ManagedThemePreference(sharedPreferences, key, defaultValue)
        val ui = ManagedThemePreferenceUi(title, key, defaultValue, summary, enableUiOn)
        pref.register()
        ui.registerUi()
        return pref
    }

    val keyBorder = switch(R.string.key_border, "key_border", true)

    val portraitNumberRow = switch(
        R.string.portrait_number_row, "portrait_number_row", true
    )

    val keyBorderStroke = switch(
        R.string.key_border_stroke, "key_border_stroke", false,
        enableUiOn = { keyBorder.getValue() }
    )

    val keyRippleEffect = switch(R.string.key_ripple_effect, "key_ripple_effect", false)

    enum class PressEffectPalette(override val stringRes: Int) : ManagedPreferenceEnum {
        SamsungCool(R.string.cyber_palette_cyan_blue_purple),
        Cyberpunk(R.string.press_effect_palette_cyberpunk),
        RedOrangeYellow(R.string.cyber_palette_red_orange_yellow),
        PurplePinkRed(R.string.cyber_palette_purple_pink_red),
        MintCyan(R.string.cyber_palette_mint_cyan),
        BluePurplePink(R.string.cyber_palette_blue_purple_pink),
        Cool(R.string.press_effect_palette_cool),
        Rainbow(R.string.press_effect_palette_rainbow),
        Berry(R.string.press_effect_palette_berry),
        Accent(R.string.press_effect_palette_accent),
        Custom(R.string.press_effect_palette_custom);
    }

    enum class PressColorMode(override val stringRes: Int) : ManagedPreferenceEnum {
        Random(R.string.press_color_mode_random),
        Single(R.string.press_color_mode_single),
        Custom(R.string.press_color_mode_custom);
    }

    /** how the pressed key itself shows its colour (Keys Cafe "key colour effect") */
    enum class KeyColorStyle(override val stringRes: Int) : ManagedPreferenceEnum {
        Fill(R.string.key_color_style_fill),
        Border(R.string.key_color_style_border),
        Off(R.string.key_color_style_off);
    }

    /** how a released key leaves: dim in place (Samsung dark theme) or shrink to a spot (light theme) */
    enum class KeyExitStyle(override val stringRes: Int) : ManagedPreferenceEnum {
        Dim(R.string.key_exit_dim),
        Shrink(R.string.key_exit_shrink);
    }

    /** how the pressed key moves (Keys Cafe "key motion effect") */
    enum class KeyMotionEffect(override val stringRes: Int) : ManagedPreferenceEnum {
        Off(R.string.key_motion_off),
        Shrink(R.string.key_motion_shrink),
        Bounce(R.string.key_motion_bounce),
        Tilt(R.string.key_motion_tilt),
        Press(R.string.key_motion_press);
    }

    enum class RippleShape(override val stringRes: Int) : ManagedPreferenceEnum {
        SoftMist(R.string.ripple_shape_soft_mist),
        IrregularFluid(R.string.ripple_shape_irregular_fluid);
    }

    val pressEffect = switch(
        R.string.press_effect, "press_effect", true, R.string.press_effect_summary
    )

    val effectsFollowSystemAnimation = switch(
        R.string.effects_follow_system_animation, "effects_follow_system_animation", false,
        R.string.effects_follow_system_animation_summary
    )

    val idleBreathing = switch(
        R.string.idle_breathing, "idle_breathing", true, R.string.idle_breathing_summary
    )

    enum class NeonColor(override val stringRes: Int, val argb: Int) : ManagedPreferenceEnum {
        Cyan(R.string.neon_cyan, 0xFF00F0FF.toInt()),
        IceBlue(R.string.neon_iceblue, 0xFF4DB8FF.toInt()),
        Blue(R.string.neon_blue, 0xFF386BFF.toInt()),
        Indigo(R.string.neon_indigo, 0xFF805BFF.toInt()),
        Purple(R.string.neon_purple, 0xFFBE38FF.toInt()),
        Magenta(R.string.neon_magenta, 0xFFFF2BD6.toInt()),
        Pink(R.string.neon_pink, 0xFFFF438E.toInt()),
        Yellow(R.string.neon_yellow, 0xFFE9FF48.toInt()),
        Green(R.string.neon_green, 0xFF76FF4D.toInt()),
        Mint(R.string.neon_mint, 0xFF00FFC6.toInt());
    }

    val idleRandomColors = switch(
        R.string.idle_random_colors, "idle_random_colors", true,
        enableUiOn = { idleBreathing.getValue() }
    )

    val idleColorPrimary = enumList(
        R.string.idle_color_primary, "idle_color_primary", NeonColor.Cyan,
        enableUiOn = { idleBreathing.getValue() && !idleRandomColors.getValue() }
    )
    val idleColorSecondary = enumList(
        R.string.idle_color_secondary, "idle_color_secondary", NeonColor.Purple,
        enableUiOn = { idleBreathing.getValue() && !idleRandomColors.getValue() }
    )
    val idleDelay = int(
        R.string.idle_delay, "idle_delay", 1800, 500, 10000, "ms", 100,
        enableUiOn = { idleBreathing.getValue() }
    )
    val idleCycle = int(
        R.string.idle_cycle, "idle_cycle", 5200, 2000, 15000, "ms", 100,
        enableUiOn = { idleBreathing.getValue() }
    )
    val idleFadeIn = int(
        R.string.idle_fade_in, "idle_fade_in", 850, 100, 3000, "ms", 50,
        enableUiOn = { idleBreathing.getValue() }
    )
    val idleFadeOut = int(
        R.string.idle_fade_out, "idle_fade_out", 180, 50, 1500, "ms", 10,
        enableUiOn = { idleBreathing.getValue() }
    )
    val idleTimeout = int(
        R.string.idle_timeout, "idle_timeout", 60, 15, 300, "s", 15,
        enableUiOn = { idleBreathing.getValue() }
    )
    val idleBrightness = int(
        R.string.idle_brightness, "idle_brightness", 28, 5, 60, "%",
        enableUiOn = { idleBreathing.getValue() }
    )

    val pressColorMode = enumList(
        R.string.press_color_mode, "press_color_mode", PressColorMode.Random,
        enableUiOn = { pressEffect.getValue() }
    )

    val pressEffectPalette = enumList(
        R.string.press_effect_palette,
        "press_effect_palette",
        PressEffectPalette.Cyberpunk,
        enableUiOn = { pressEffect.getValue() && pressColorMode.getValue() == PressColorMode.Random }
    )

    private val customPaletteUi = {
        pressEffect.getValue() && pressColorMode.getValue() == PressColorMode.Random &&
            pressEffectPalette.getValue() == PressEffectPalette.Custom
    }
    val pressCustomColorCount = int(
        R.string.press_custom_color_count, "press_custom_color_count", 3, 1, 4, "", 1,
        enableUiOn = customPaletteUi
    )
    val pressCustomColor1 = enumList(R.string.press_custom_color_1, "press_custom_color_1", NeonColor.Cyan, enableUiOn = customPaletteUi)
    val pressCustomColor2 = enumList(R.string.press_custom_color_2, "press_custom_color_2", NeonColor.Magenta, enableUiOn = customPaletteUi)
    val pressCustomColor3 = enumList(R.string.press_custom_color_3, "press_custom_color_3", NeonColor.Yellow, enableUiOn = customPaletteUi)
    val pressCustomColor4 = enumList(R.string.press_custom_color_4, "press_custom_color_4", NeonColor.Purple, enableUiOn = customPaletteUi)

    // The dedicated effects screen edits RGB values; the original preset preferences
    // remain readable for existing configurations and backups.
    val pressSingleColor = ManagedPreference.PInt(
        sharedPreferences, "press_single_argb", NeonColor.Cyan.argb
    ).also { it.register() }
    val pressUserColors = ManagedPreference.PString(
        sharedPreferences, "press_user_colors", PressColorPalette.encode(
            listOf(pressCustomColor1, pressCustomColor2, pressCustomColor3, pressCustomColor4)
                .take(pressCustomColorCount.getValue().coerceIn(1, 4))
                .map { it.getValue().argb }.toIntArray()
        )
    ).also { it.register() }

    fun userPressColors(): IntArray = PressColorPalette.parse(pressUserColors.getValue())
        .takeIf { it.isNotEmpty() } ?: PressColorPalette.parse(pressUserColors.defaultValue)

    val keyColorStyle = enumList(
        R.string.key_color_style, "key_color_style", KeyColorStyle.Fill,
        enableUiOn = { pressEffect.getValue() }
    )

    val keyMotionEffect = enumList(R.string.key_motion_effect, "key_motion_effect", KeyMotionEffect.Press)

    val keyExitStyle = enumList(
        R.string.key_exit_style, "key_exit_style", KeyExitStyle.Dim,
        enableUiOn = { pressEffect.getValue() }
    )

    val coloredPreview = switch(
        R.string.colored_preview, "colored_preview", true, R.string.colored_preview_summary
    )

    /** one colour per press: key face, bubble and ripple share it */
    val previewSameColor = switch(
        R.string.preview_same_color, "preview_same_color", true, R.string.preview_same_color_summary,
        enableUiOn = { coloredPreview.getValue() }
    )

    val pressGlowReach = int(
        R.string.press_glow_reach, "press_glow_reach", 100, 20, 100, "%", 5,
        enableUiOn = { pressEffect.getValue() }
    )
    val pressGlowOnCandidates = switch(
        R.string.press_glow_on_candidates, "press_glow_on_candidates", true,
        enableUiOn = { pressEffect.getValue() }
    )

    val pressEffectSize = int(
        R.string.press_effect_size, "press_effect_size", 100, 40, 200, "%", 10,
        enableUiOn = { pressEffect.getValue() }
    )

    val rippleShape = enumList(
        R.string.ripple_shape, "ripple_shape", RippleShape.SoftMist,
        enableUiOn = { pressEffect.getValue() }
    )

    val pressIgnitionTime = int(
        R.string.press_ignition_time, "press_ignition_time", 40, 30, 300, "ms", 10,
        enableUiOn = { pressEffect.getValue() }
    )

    val pressKeyHoldTime = int(
        R.string.press_key_hold_time, "press_key_hold_time", 50, 20, 1000, "ms", 10,
        enableUiOn = { pressEffect.getValue() }
    )

    val pressKeyRetreatTime = int(
        // Existing installs without an explicit value pick up the softer default.
        // Preserve every saved value, including a deliberately chosen 30ms.
        R.string.press_key_retreat_time, "press_key_retreat_time", 100, 20, 5000, "ms", 10,
        enableUiOn = { pressEffect.getValue() }
    )

    val pressExpansionTime = int(
        R.string.press_expansion_time, "press_expansion_time", 400, 100, 4000, "ms", 50,
        enableUiOn = { pressEffect.getValue() }
    )

    val pressWaveHoldTime = int(
        R.string.press_wave_hold_time, "press_wave_hold_time", 40, 0, 2000, "ms", 10,
        enableUiOn = { pressEffect.getValue() }
    )

    val pressFadeOutTime = int(
        R.string.press_fade_out_time, "press_fade_out_time", 520, 100, 5000, "ms", 50,
        enableUiOn = { pressEffect.getValue() }
    )

    // Keep the old key for backup compatibility; light now always stays below glyphs.
    val pressEffectOverKeys = ManagedPreference.PInt(
        sharedPreferences, "press_effect_over_keys", 0
    ).also { it.register() }

    val pressGlowBrightness = int(
        R.string.press_glow_brightness, "press_glow_brightness", 100, 0, 100, "%", 5,
        enableUiOn = { pressEffect.getValue() }
    )

    val keyHorizontalMargin: ManagedPreference.PInt
    val keyHorizontalMarginLandscape: ManagedPreference.PInt

    init {
        val (primary, secondary) = twinInt(
            R.string.key_horizontal_margin,
            R.string.portrait,
            "key_horizontal_margin",
            3,
            R.string.landscape,
            "key_horizontal_margin_landscape",
            3,
            0,
            24,
            "dp"
        )
        keyHorizontalMargin = primary
        keyHorizontalMarginLandscape = secondary
    }

    val keyVerticalMargin: ManagedPreference.PInt
    val keyVerticalMarginLandscape: ManagedPreference.PInt

    init {
        val (primary, secondary) = twinInt(
            R.string.key_vertical_margin,
            R.string.portrait,
            "key_vertical_margin",
            4,
            R.string.landscape,
            "key_vertical_margin_landscape",
            3,
            0,
            24,
            "dp"
        )
        keyVerticalMargin = primary
        keyVerticalMarginLandscape = secondary
    }

    val keyRadius = int(R.string.key_radius, "key_radius", 5, 0, 48, "dp")

    val textEditingButtonRadius =
        int(R.string.text_editing_button_radius, "text_editing_button_radius", 8, 0, 48, "dp")

    val clipboardEntryRadius =
        int(R.string.clipboard_entry_radius, "clipboard_entry_radius", 2, 0, 48, "dp")

    enum class PunctuationPosition(override val stringRes: Int) : ManagedPreferenceEnum {
        None(R.string.punctuation_pos_none),
        TopRowDigits(R.string.punctuation_pos_top_row_digits),
        Bottom(R.string.punctuation_pos_bottom),
        TopRight(R.string.punctuation_pos_top_right);
    }

    val punctuationPosition = enumList(
        R.string.punctuation_position,
        "punctuation_position",
        PunctuationPosition.TopRowDigits
    )

    enum class NavbarBackground(override val stringRes: Int) : ManagedPreferenceEnum {
        None(R.string.navbar_bkg_none),
        ColorOnly(R.string.navbar_bkg_color_only),
        Full(R.string.navbar_bkg_full);
    }

    val navbarBackground = enumList(
        R.string.navbar_background,
        "navbar_background",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) NavbarBackground.Full else NavbarBackground.ColorOnly,
        // 35+ forces edge to edge
        enableUiOn = { Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM }
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            sharedPreferences.edit {
                remove(this@apply.key)
            }
        }
    }

    /**
     * When [followSystemDayNightTheme] is disabled, this theme is used.
     * This is effectively an internal preference which does not need UI.
     */
    val normalModeTheme = ManagedThemePreference(
        sharedPreferences, "normal_mode_theme", ThemeManager.DefaultTheme
    ).also {
        it.register()
    }

    val followSystemDayNightTheme = switch(
        R.string.follow_system_day_night_theme,
        "follow_system_dark_mode",
        false,
        summary = R.string.follow_system_day_night_theme_summary
    )

    val lightModeTheme = themePreference(
        R.string.light_mode_theme,
        "light_mode_theme",
        ThemePreset.XuancaiBlackV09,
        enableUiOn = {
            followSystemDayNightTheme.getValue()
        })

    val darkModeTheme = themePreference(
        R.string.dark_mode_theme,
        "dark_mode_theme",
        ThemePreset.XuancaiBlackV09,
        enableUiOn = {
            followSystemDayNightTheme.getValue()
        })

    val dayNightModePrefNames = setOf(
        followSystemDayNightTheme.key,
        lightModeTheme.key,
        darkModeTheme.key
    )
}
