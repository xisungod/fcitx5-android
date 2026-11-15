/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceUi
import org.fcitx.fcitx5.android.data.theme.PressColorPalette
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class V15LightEffectPrefsTest {
    private var oldUiApplication: Any? = null

    @Before
    fun prepareWithoutStartingTheNativeEngine() {
        val application = RuntimeEnvironment.getApplication()
        val instance = FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }
        oldUiApplication = instance.get(null)
        val uiApplication = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(uiApplication, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        instance.set(null, uiApplication)
        AppPrefs.init(application.getSharedPreferences("v15-neon-prefs-host", Context.MODE_PRIVATE))
    }

    @After
    fun restoreUiApplication() {
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, oldUiApplication)
        }
    }

    private fun storage(name: String): SharedPreferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test
    fun newInstallationUsesSamWithACoolPaletteAndIndependentKeyMotion() {
        val stored = storage("v15-neon-defaults")
        val prefs = ThemePrefs(stored)
        assertEquals(ThemePrefs.PressColorMode.Random, prefs.pressColorMode.getValue())
        assertEquals(ThemePrefs.RippleShape.Sam, prefs.rippleShape.getValue())
        assertEquals(ThemePrefs.PressEffectPalette.SamsungCool, prefs.pressEffectPalette.getValue())
        val colors = PressColorPalette.colorsFor(prefs, 0)
        assertArrayEquals(PressColorPalette.CYAN_BLUE_PURPLE, colors)
        assertTrue("The default starts with green before moving through cyan and blue",
            (colors.first() ushr 8 and 255) > (colors.first() and 255))
        assertTrue("The default path must not introduce red, orange or yellow",
            colors.all { color -> (color ushr 16 and 255) < maxOf(color ushr 8 and 255, color and 255) })
        assertEquals(40, prefs.pressIgnitionTime.getValue())
        assertEquals(50, prefs.pressKeyHoldTime.getValue())
        assertEquals(ThemePrefs.KeyMotionEffect.Press, prefs.keyMotionEffect.getValue())
        assertEquals(100, prefs.pressKeyRetreatTime.getValue())
        assertEquals(80, prefs.samKeyHoldTime.getValue())
        assertEquals(800, prefs.samKeyRetreatTime.getValue())
        assertEquals(400, prefs.pressExpansionTime.getValue())
        assertEquals(40, prefs.pressWaveHoldTime.getValue())
        assertEquals(900, prefs.pressFadeOutTime.getValue())
        assertEquals(1380, prefs.pressIgnitionTime.getValue() + prefs.pressExpansionTime.getValue() +
            prefs.pressWaveHoldTime.getValue() + prefs.pressFadeOutTime.getValue())
        assertTrue("Defaults must not turn into an implicit settings migration", stored.all.isEmpty())
        assertTrue(prefs.previewSameColor.getValue())
    }

    @Test
    fun upgradeAdoptsCoolDefaultOnlyWhenNoPaletteWasSaved() {
        val stored = storage("sam-cool-unsaved-upgrade")
        stored.edit().putBoolean("sam_mode_default_applied", true)
            .putString("ripple_shape", "Sam").putInt("press_motion_amplitude", 13).commit()
        val before = stored.all.toMap()
        val prefs = ThemePrefs(stored)
        prefs.applySamDefaultOnce()
        assertEquals(ThemePrefs.PressEffectPalette.SamsungCool, prefs.pressEffectPalette.getValue())
        assertEquals(13, prefs.pressMotionAmplitude.getValue())
        assertEquals("An unsaved palette reads the default without rewriting user settings", before, stored.all)
    }

    @Test
    fun coolDefaultDoesNotOverwriteSavedPalettesSingleOrCustomColours() {
        val stored = storage("sam-cool-explicit-upgrade")
        for (palette in ThemePrefs.PressEffectPalette.entries) {
            stored.edit().clear().putBoolean("sam_mode_default_applied", true)
                .putString("ripple_shape", "Sam").putString("press_effect_palette", palette.name).commit()
            val before = stored.all.toMap()
            val prefs = ThemePrefs(stored)
            prefs.applySamDefaultOnce()
            assertEquals("Explicit ${palette.name} remains selected", palette, prefs.pressEffectPalette.getValue())
            assertEquals(before, stored.all)
        }
        val orange = 0xFFFF6320.toInt()
        val custom = intArrayOf(orange, 0xFF00B8FF.toInt())
        for (mode in listOf(ThemePrefs.PressColorMode.Single, ThemePrefs.PressColorMode.Custom)) {
            stored.edit().putString("press_color_mode", mode.name)
                .putInt("press_single_argb", orange)
                .putString("press_user_colors", PressColorPalette.encode(custom)).commit()
            val before = stored.all.toMap()
            val prefs = ThemePrefs(stored)
            assertEquals(mode, prefs.pressColorMode.getValue())
            assertArrayEquals(if (mode == ThemePrefs.PressColorMode.Single) intArrayOf(orange) else custom,
                PressColorPalette.colorsFor(prefs, 0))
            assertEquals(before, stored.all)
        }
    }

    @Test
    fun upgradeUsesTheSofterDefaultButPreservesAnExplicitThirtyMillisecondExit() {
        val stored = storage("v15-neon-existing-default")
        stored.edit().putBoolean("press_effect", true).commit()
        val before = stored.all.toMap()
        assertEquals("An existing install without a saved timing gets the new default",
            100, ThemePrefs(stored).pressKeyRetreatTime.getValue())
        assertEquals("An existing install without a saved wave fade gets the longer tail",
            900, ThemePrefs(stored).pressFadeOutTime.getValue())
        assertEquals("Loading the new default does not rewrite stored preferences", before, stored.all)

        stored.edit().putInt("press_key_retreat_time", 30).commit()
        val explicit = stored.all.toMap()
        assertEquals("An explicitly selected 30ms must not be mistaken for the old default",
            30, ThemePrefs(stored).pressKeyRetreatTime.getValue())
        assertEquals(explicit, stored.all)

        for (savedFade in intArrayOf(520, 2300)) {
            stored.edit().putInt("press_fade_out_time", savedFade).commit()
            val saved = stored.all.toMap()
            assertEquals("Saved legacy and longer custom fades must never be migrated",
                savedFade, ThemePrefs(stored).pressFadeOutTime.getValue())
            assertEquals("Reading the newer version must not change any saved setting", saved, stored.all)
        }
    }

    @Test
    fun upgradePreservesSavedTimingAndArbitraryUserColoursExactly() {
        val stored = storage("v15-neon-existing")
        val custom = intArrayOf(0xffab5421.toInt(), 0xff1a384f.toInt(), 0xffd96eff.toInt())
        stored.edit()
            .putInt("press_ignition_time", 100)
            .putInt("press_key_hold_time", 260)
            .putInt("press_key_retreat_time", 1800)
            .putInt("press_expansion_time", 1400)
            .putInt("press_wave_hold_time", 350)
            .putInt("press_fade_out_time", 900)
            .putString("press_color_mode", "Custom")
            .putString("press_user_colors", PressColorPalette.encode(custom))
            .putInt("press_single_argb", 0xff8a3b21.toInt())
            .putBoolean("preview_same_color", false)
            .putString("key_motion_effect", "Off")
            .commit()
        val before = stored.all.toMap()
        val prefs = ThemePrefs(stored)
        assertEquals(100, prefs.pressIgnitionTime.getValue())
        assertEquals(260, prefs.pressKeyHoldTime.getValue())
        assertEquals(1800, prefs.pressKeyRetreatTime.getValue())
        assertEquals(1400, prefs.pressExpansionTime.getValue())
        assertEquals(350, prefs.pressWaveHoldTime.getValue())
        assertEquals(900, prefs.pressFadeOutTime.getValue())
        assertEquals(ThemePrefs.PressColorMode.Custom, prefs.pressColorMode.getValue())
        assertArrayEquals(custom, prefs.userPressColors())
        assertEquals(0xff8a3b21.toInt(), prefs.pressSingleColor.getValue())
        assertFalse(prefs.previewSameColor.getValue())
        assertEquals(ThemePrefs.KeyMotionEffect.Off, prefs.keyMotionEffect.getValue())
        assertEquals("Reading newer defaults cannot rewrite an existing setup", before, stored.all)
    }

    @Test
    fun samColourTimingsHaveIndependentDefaultsAndPreserveBothModesSavedValues() {
        val stored = storage("sam-independent-key-colour")
        stored.edit().putInt("press_key_hold_time", 37).putInt("press_key_retreat_time", 1783).commit()
        val before = stored.all.toMap()
        val prefs = ThemePrefs(stored)
        assertEquals(80, prefs.samKeyHoldTime.getValue())
        assertEquals(800, prefs.samKeyRetreatTime.getValue())
        assertEquals("Using Sam defaults must not migrate mist or fluid timing", before, stored.all)
        prefs.samKeyHoldTime.setValue(130)
        prefs.samKeyRetreatTime.setValue(1270)
        val saved = stored.all.toMap()
        val reopened = ThemePrefs(stored)
        assertEquals(37, reopened.pressKeyHoldTime.getValue())
        assertEquals(1783, reopened.pressKeyRetreatTime.getValue())
        assertEquals(130, reopened.samKeyHoldTime.getValue())
        assertEquals(1270, reopened.samKeyRetreatTime.getValue())
        assertEquals(saved, stored.all)
    }

    @Test
    fun capTimingControlsAllowShortFlashesWhileKeepingLongCustomExits() {
        val prefs = ThemePrefs(storage("v15-neon-ranges"))
        val expected = mapOf(
            "press_ignition_time" to (30 to 300),
            "press_key_hold_time" to (20 to 1000),
            "press_key_retreat_time" to (20 to 5000),
            "sam_key_hold_time" to (0 to 1000),
            "sam_key_retreat_time" to (100 to 5000),
            "press_expansion_time" to (100 to 4000),
            "press_wave_hold_time" to (0 to 2000),
            "press_fade_out_time" to (100 to 5000)
        )
        expected.forEach { (key, range) ->
            val ui = prefs.managedPreferencesUi.single { it.key == key }
            val actualRange = when (ui) {
                is ManagedPreferenceUi.SeekBarInt -> ui.min to ui.max
                is ManagedPreferenceUi.EditTextInt -> ui.min to ui.max
                else -> error("$key must expose an integer timing control")
            }
            assertEquals("Minimum for $key", range.first, actualRange.first)
            assertEquals("Maximum for $key", range.second, actualRange.second)
        }
    }
}
