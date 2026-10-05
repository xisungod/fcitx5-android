/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.settings

import org.fcitx.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.PressColorPalette
import org.fcitx.fcitx5.android.data.theme.KeyMotionSettings
import org.fcitx.fcitx5.android.input.keyboard.KeyWidthSettings

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
        val samKeyHoldTime: Int,
        val samKeyRetreatTime: Int,
        val candidateGlow: Boolean,
        val idleBreathing: Boolean,
        val keyMotion: ThemePrefs.KeyMotionEffect,
        val motionSettings: KeyMotionSettings,
        val keyWidths: KeyWidthSettings,
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
        theme.samKeyHoldTime.getValue(), theme.samKeyRetreatTime.getValue(),
        theme.pressGlowOnCandidates.getValue(), theme.idleBreathing.getValue(),
        theme.keyMotionEffect.getValue(), KeyMotionSettings(
            pressAmplitude = theme.pressMotionAmplitude.getValue(),
            pressDuration = theme.pressMotionDuration.getValue(),
            reboundAmplitude = theme.reboundMotionAmplitude.getValue(),
            reboundDuration = theme.reboundMotionDuration.getValue()
        ).normalized(), KeyWidthSettings.parse(theme.keyWidthOverrides.getValue()), theme.portraitNumberRow.getValue(),
        keyboard.popupOnKeyPress.getValue(), keyboard.hapticOnKeyPress.getValue(),
        keyboard.hapticStrength.getValue()
    )

    var values = original

    /** Reset only the four motion controls; the rest of this staged panel is unchanged. */
    fun resetMotionSettings() {
        values = values.copy(motionSettings = KeyMotionSettings())
    }

    fun currentColors(accentColor: Int): IntArray = when (values.colorMode) {
        ThemePrefs.PressColorMode.Random -> PressColorPalette.presetColors(values.palette, theme, accentColor)
        ThemePrefs.PressColorMode.Single -> intArrayOf(theme.pressSingleColor.getValue() or 0xFF000000.toInt())
        ThemePrefs.PressColorMode.Custom -> theme.userPressColors()
    }

    /** No lifecycle callback saves this draft. Back and switching panels discard it. */
    fun apply(): Boolean {
        val next = values.copy(motionSettings = values.motionSettings.normalized())
        if (next == original) return false
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
        int(theme.samKeyHoldTime, original.samKeyHoldTime, next.samKeyHoldTime, 0..1000)
        int(theme.samKeyRetreatTime, original.samKeyRetreatTime, next.samKeyRetreatTime, 100..5000)
        bool(theme.pressGlowOnCandidates, original.candidateGlow, next.candidateGlow)
        bool(theme.idleBreathing, original.idleBreathing, next.idleBreathing)
        if (original.keyMotion != next.keyMotion) editor.putString(theme.keyMotionEffect.key, next.keyMotion.name)
        int(theme.pressMotionAmplitude, original.motionSettings.pressAmplitude,
            next.motionSettings.pressAmplitude, KeyMotionSettings.PRESS_AMPLITUDE_RANGE)
        int(theme.pressMotionDuration, original.motionSettings.pressDuration,
            next.motionSettings.pressDuration, KeyMotionSettings.PRESS_DURATION_RANGE)
        int(theme.reboundMotionAmplitude, original.motionSettings.reboundAmplitude,
            next.motionSettings.reboundAmplitude, KeyMotionSettings.REBOUND_AMPLITUDE_RANGE)
        int(theme.reboundMotionDuration, original.motionSettings.reboundDuration,
            next.motionSettings.reboundDuration, KeyMotionSettings.REBOUND_DURATION_RANGE)
        bool(theme.portraitNumberRow, original.numberRow, next.numberRow)
        if (original.keyWidths != next.keyWidths) {
            // Merge only edited keys, preserving unrelated widths changed while this panel was open.
            var widths = KeyWidthSettings.parse(theme.keyWidthOverrides.getValue())
            (original.keyWidths.overrides.keys + next.keyWidths.overrides.keys).forEach { id ->
                if (original.keyWidths[id] != next.keyWidths[id]) widths = widths.withWidth(id, next.keyWidths[id])
            }
            editor.putString(theme.keyWidthOverrides.key, widths.encode())
        }
        bool(keyboard.popupOnKeyPress, original.popup, next.popup)
        if (original.hapticMode != next.hapticMode) editor.putString(keyboard.hapticOnKeyPress.key, next.hapticMode.name)
        int(keyboard.hapticStrength, original.hapticStrength, next.hapticStrength, 0..100)
        // SharedPreferences updates every changed value before notifying listeners. In particular,
        // a theme listener rebuilding the IME sees the entire new setup, never a partial one.
        values = next
        original = next
        editor.apply()
        return true
    }
}
