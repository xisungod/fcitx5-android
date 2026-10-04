/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.settings

import org.fcitx.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.PressColorPalette

/** Editing a keyboard panel must not rebuild its owning InputView before Done is tapped. */
internal class KeyboardQuickSettingsDraft(
    private val theme: ThemePrefs,
    private val keyboard: AppPrefs.Keyboard
) {
    data class Values(
        val pressEffect: Boolean,
        val colorMode: ThemePrefs.PressColorMode,
        val palette: ThemePrefs.PressEffectPalette,
        val rippleShape: ThemePrefs.RippleShape,
        val glowBrightness: Int,
        val glowReach: Int,
        val keyRetreatTime: Int,
        val candidateGlow: Boolean,
        val idleBreathing: Boolean,
        val keyMotion: ThemePrefs.KeyMotionEffect,
        val numberRow: Boolean,
        val popup: Boolean,
        val hapticMode: InputFeedbackMode,
        val hapticStrength: Int
    )

    private val storage = theme.pressEffect.sharedPreferences

    init {
        require(keyboard.hapticStrength.sharedPreferences === storage) {
            "Keyboard and theme preferences must share storage for an atomic update"
        }
    }

    private var original = Values(
        theme.pressEffect.getValue(), theme.pressColorMode.getValue(), theme.pressEffectPalette.getValue(),
        theme.rippleShape.getValue(),
        theme.pressGlowBrightness.getValue(),
        theme.pressGlowReach.getValue(), theme.pressKeyRetreatTime.getValue(),
        theme.pressGlowOnCandidates.getValue(), theme.idleBreathing.getValue(),
        theme.keyMotionEffect.getValue(), theme.portraitNumberRow.getValue(),
        keyboard.popupOnKeyPress.getValue(), keyboard.hapticOnKeyPress.getValue(),
        keyboard.hapticStrength.getValue()
    )

    var values = original

    fun currentColors(accentColor: Int): IntArray = when (values.colorMode) {
        ThemePrefs.PressColorMode.Random -> PressColorPalette.presetColors(values.palette, theme, accentColor)
        ThemePrefs.PressColorMode.Single -> intArrayOf(theme.pressSingleColor.getValue() or 0xFF000000.toInt())
        ThemePrefs.PressColorMode.Custom -> theme.userPressColors()
    }

    /** No lifecycle callback saves this draft. Back and switching panels discard it. */
    fun apply(): Boolean {
        if (values == original) return false
        val next = values
        val editor = storage.edit()
        fun bool(pref: ManagedPreference.PBool, before: Boolean, after: Boolean) {
            if (before != after) editor.putBoolean(pref.key, after)
        }
        fun int(pref: ManagedPreference.PInt, before: Int, after: Int, range: IntRange) {
            if (before != after) editor.putInt(pref.key, after.coerceIn(range))
        }
        bool(theme.pressEffect, original.pressEffect, next.pressEffect)
        if (original.colorMode != next.colorMode) editor.putString(theme.pressColorMode.key, next.colorMode.name)
        if (original.palette != next.palette) editor.putString(theme.pressEffectPalette.key, next.palette.name)
        if (original.rippleShape != next.rippleShape) editor.putString(theme.rippleShape.key, next.rippleShape.name)
        int(theme.pressGlowBrightness, original.glowBrightness, next.glowBrightness, 0..100)
        int(theme.pressGlowReach, original.glowReach, next.glowReach, 20..100)
        int(theme.pressKeyRetreatTime, original.keyRetreatTime, next.keyRetreatTime, 20..5000)
        bool(theme.pressGlowOnCandidates, original.candidateGlow, next.candidateGlow)
        bool(theme.idleBreathing, original.idleBreathing, next.idleBreathing)
        if (original.keyMotion != next.keyMotion) editor.putString(theme.keyMotionEffect.key, next.keyMotion.name)
        bool(theme.portraitNumberRow, original.numberRow, next.numberRow)
        bool(keyboard.popupOnKeyPress, original.popup, next.popup)
        if (original.hapticMode != next.hapticMode) editor.putString(keyboard.hapticOnKeyPress.key, next.hapticMode.name)
        int(keyboard.hapticStrength, original.hapticStrength, next.hapticStrength, 0..100)
        // SharedPreferences updates every changed value before notifying listeners. In particular,
        // a theme listener rebuilding the IME sees the entire new setup, never a partial one.
        original = next
        editor.apply()
        return true
    }
}
