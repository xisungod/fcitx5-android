/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.fcitx.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.KeyMotionSettings
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.PressColorPalette
import org.fcitx.fcitx5.android.input.settings.KeyboardQuickSettingsDraft
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class KeyboardQuickSettingsDraftTest {
    private var oldApplication: Any? = null

    @Before fun prepare() {
        val application = RuntimeEnvironment.getApplication()
        val field = FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true }
        oldApplication = field.get(null)
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        field.set(null, app)
        AppPrefs.init(application.getSharedPreferences("quick-settings-host", Context.MODE_PRIVATE))
    }

    @After fun restore() {
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, oldApplication)
        }
    }

    private fun storage(name: String) = RuntimeEnvironment.getApplication()
        .getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    private class CountedStorage(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        var edits = 0
        var applies = 0
        override fun edit(): SharedPreferences.Editor {
            edits++
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun apply() { applies++; editor.apply() }
            }
        }
    }

    @Test fun abandoningTheDraftAndCompletingAnUnchangedPanelNeverWritePreferences() {
        val stored = CountedStorage(storage("quick-cancel"))
        val theme = ThemePrefs(stored)
        val keyboard = AppPrefs(stored).keyboard
        val canceled = KeyboardQuickSettingsDraft(theme, keyboard)
        canceled.values = canceled.values.copy(pressEffect = false, hapticStrength = 0, numberRow = false,
            rippleShape = ThemePrefs.RippleShape.IrregularFluid,
            motionSettings = KeyMotionSettings(16, 300, 6, 1800))
        assertTrue(stored.all.isEmpty())
        val reopened = KeyboardQuickSettingsDraft(theme, keyboard)
        assertTrue(reopened.values.pressEffect)
        assertTrue(reopened.values.numberRow)
        assertEquals(100, reopened.values.hapticStrength)
        assertEquals(ThemePrefs.RippleShape.SoftMist, reopened.values.rippleShape)
        assertEquals(KeyMotionSettings(), reopened.values.motionSettings)
        assertFalse(reopened.apply())
        assertEquals(0, stored.edits)
        assertEquals(0, stored.applies)
        assertTrue("Opening and closing must not save defaults or trigger haptic-mode migration", stored.all.isEmpty())
    }

    @Test fun doneAppliesAllEditsInOneTransactionAndEveryListenerSeesCompleteValues() {
        val actual = storage("quick-atomic")
        val stored = CountedStorage(actual)
        val theme = ThemePrefs(stored)
        val keyboard = AppPrefs(stored).keyboard
        val draft = KeyboardQuickSettingsDraft(theme, keyboard)
        draft.values = draft.values.copy(pressEffect = false, glowBrightness = 35,
            rippleShape = ThemePrefs.RippleShape.IrregularFluid,
            glowReach = 45, keyRetreatTime = 220, candidateGlow = false, idleBreathing = false,
            keyMotion = ThemePrefs.KeyMotionEffect.Bounce, numberRow = false, popup = false,
            hapticMode = InputFeedbackMode.Enabled, hapticStrength = 0,
            motionSettings = KeyMotionSettings(pressAmplitude = 14, pressDuration = 260,
                reboundAmplitude = 5, reboundDuration = 1600))
        val snapshots = mutableListOf<Map<String, *>>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ -> snapshots += prefs.all.toMap() }
        actual.registerOnSharedPreferenceChangeListener(listener)
        try {
            assertTrue(draft.apply())
            assertEquals(1, stored.edits)
            assertEquals(1, stored.applies)
            assertFalse(draft.apply())
            assertEquals(1, stored.applies)
            assertEquals(0, keyboard.hapticStrength.getValue())
            assertEquals(InputFeedbackMode.Enabled, keyboard.hapticOnKeyPress.getValue())
            assertEquals(ThemePrefs.KeyMotionEffect.Bounce, theme.keyMotionEffect.getValue())
            assertEquals(220, theme.pressKeyRetreatTime.getValue())
            assertEquals(ThemePrefs.RippleShape.IrregularFluid, theme.rippleShape.getValue())
            assertEquals(14, theme.pressMotionAmplitude.getValue())
            assertEquals(260, theme.pressMotionDuration.getValue())
            assertEquals(5, theme.reboundMotionAmplitude.getValue())
            assertEquals(1600, theme.reboundMotionDuration.getValue())
            assertTrue(snapshots.isNotEmpty())
            snapshots.forEach {
                assertEquals("Every rebuild must see the final theme and keyboard settings together", actual.all, it)
            }
        } finally {
            actual.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    @Test fun donePreservesUnchangedCustomValuesAndConcurrentEdits() {
        val stored = storage("quick-preserve")
        stored.edit().putInt("press_key_retreat_time", 1783)
            .putString("press_user_colors", "#AB5421,#1A384F,#D96EFF")
            .putInt("keyboard_height_percent", 42)
            .putInt("button_vibration_press_milliseconds", 19).commit()
        val theme = ThemePrefs(stored)
        val keyboard = AppPrefs(stored).keyboard
        theme.pressMotionAmplitude.setValue(12)
        theme.pressMotionDuration.setValue(220)
        theme.reboundMotionAmplitude.setValue(4)
        theme.reboundMotionDuration.setValue(1200)
        val draft = KeyboardQuickSettingsDraft(theme, keyboard)
        draft.values = draft.values.copy(glowBrightness = 40,
            motionSettings = draft.values.motionSettings.copy(pressAmplitude = 15))
        // A setting changed elsewhere while this panel was open must not be replaced by its snapshot.
        theme.portraitNumberRow.setValue(false)
        theme.pressMotionDuration.setValue(280)
        theme.reboundMotionAmplitude.setValue(6)
        theme.reboundMotionDuration.setValue(1700)
        assertTrue(draft.apply())
        assertEquals(40, theme.pressGlowBrightness.getValue())
        assertEquals(1783, theme.pressKeyRetreatTime.getValue())
        assertEquals("#AB5421,#1A384F,#D96EFF", theme.pressUserColors.getValue())
        assertEquals(42, keyboard.keyboardHeightPercent.getValue())
        assertEquals(19, keyboard.buttonPressVibrationMilliseconds.getValue())
        assertFalse(theme.portraitNumberRow.getValue())
        assertEquals(15, theme.pressMotionAmplitude.getValue())
        assertEquals("Editing amplitude must preserve a concurrently changed press duration", 280,
            theme.pressMotionDuration.getValue())
        assertEquals(6, theme.reboundMotionAmplitude.getValue())
        assertEquals(1700, theme.reboundMotionDuration.getValue())
        assertFalse("Untouched strength retains existing system feedback", stored.contains(keyboard.hapticStrength.key))
    }

    @Test fun motionBoundsAreNormalizedAndResetChangesOnlyTheFourStagedControls() {
        val stored = CountedStorage(storage("quick-motion-bounds-reset"))
        val theme = ThemePrefs(stored)
        val keyboard = AppPrefs(stored).keyboard
        val draft = KeyboardQuickSettingsDraft(theme, keyboard)
        assertEquals(KeyMotionSettings(), draft.values.motionSettings)
        draft.values = draft.values.copy(motionSettings = KeyMotionSettings(
            pressAmplitude = -10, pressDuration = 999, reboundAmplitude = -4, reboundDuration = 10))
        assertTrue("Constructing and editing the motion draft cannot persist defaults", stored.all.isEmpty())
        assertTrue(draft.apply())
        assertEquals(1, stored.edits)
        assertEquals(1, stored.applies)
        assertEquals(2, theme.pressMotionAmplitude.getValue())
        assertEquals(300, theme.pressMotionDuration.getValue())
        assertEquals(1, theme.reboundMotionAmplitude.getValue())
        assertEquals(400, theme.reboundMotionDuration.getValue())

        draft.values = draft.values.copy(glowBrightness = 35, keyRetreatTime = 250,
            keyMotion = ThemePrefs.KeyMotionEffect.Bounce, popup = false,
            colorMode = ThemePrefs.PressColorMode.Single,
            motionSettings = KeyMotionSettings(12, 240, 5, 1500))
        val beforeReset = draft.values
        val savedBeforeReset = stored.all.toMap()
        draft.resetMotionSettings()
        assertEquals("Reset must preserve every unrelated pending edit",
            beforeReset.copy(motionSettings = KeyMotionSettings()), draft.values)
        assertEquals("Reset is local until Done", savedBeforeReset, stored.all)
        assertEquals(1, stored.applies)
        assertTrue(draft.apply())
        assertEquals(2, stored.edits)
        assertEquals(2, stored.applies)
        assertEquals(KeyMotionSettings(), KeyboardQuickSettingsDraft(theme, keyboard).values.motionSettings)
        assertEquals(35, theme.pressGlowBrightness.getValue())
        assertEquals(250, theme.pressKeyRetreatTime.getValue())
        assertEquals(ThemePrefs.KeyMotionEffect.Bounce, theme.keyMotionEffect.getValue())
        assertFalse(keyboard.popupOnKeyPress.getValue())
        assertFalse(draft.apply())
        assertEquals(2, stored.applies)
    }

    @Test fun presetSelectionPersistsItsModeAndPaletteWithoutOverwritingSingleOrCustomColors() {
        val stored = storage("quick-presets")
        val theme = ThemePrefs(stored)
        val keyboard = AppPrefs(stored).keyboard
        theme.pressColorMode.setValue(ThemePrefs.PressColorMode.Custom)
        theme.pressUserColors.setValue("#18FFC1,#D96EFF")
        theme.pressSingleColor.setValue(0xFF8A3B21.toInt())
        PressColorPalette.recommendedPresets.forEach { (preset, colors) ->
            val draft = KeyboardQuickSettingsDraft(theme, keyboard)
            draft.values = draft.values.copy(colorMode = ThemePrefs.PressColorMode.Random, palette = preset)
            assertArrayEquals(colors, draft.currentColors(0))
            draft.apply()
            val reopened = ThemePrefs(stored)
            assertEquals(ThemePrefs.PressColorMode.Random, reopened.pressColorMode.getValue())
            assertEquals(preset, reopened.pressEffectPalette.getValue())
            assertArrayEquals(colors, PressColorPalette.colorsFor(reopened, 0))
            assertEquals("#18FFC1,#D96EFF", reopened.pressUserColors.getValue())
            assertEquals(0xFF8A3B21.toInt(), reopened.pressSingleColor.getValue())
        }
        val restored = KeyboardQuickSettingsDraft(theme, keyboard)
        restored.values = restored.values.copy(colorMode = ThemePrefs.PressColorMode.Custom)
        restored.apply()
        assertArrayEquals(intArrayOf(0xFF18FFC1.toInt(), 0xFFD96EFF.toInt()), PressColorPalette.colorsFor(theme, 0))
    }

    @Test fun shapeChangesAreStagedReversibleAndDoNotReplaceCustomColors() {
        val stored = storage("quick-shape")
        val theme = ThemePrefs(stored)
        val keyboard = AppPrefs(stored).keyboard
        theme.pressColorMode.setValue(ThemePrefs.PressColorMode.Custom)
        theme.pressUserColors.setValue("#18FFC1,#D96EFF")
        val draft = KeyboardQuickSettingsDraft(theme, keyboard)
        assertEquals(ThemePrefs.RippleShape.SoftMist, draft.values.rippleShape)
        draft.values = draft.values.copy(rippleShape = ThemePrefs.RippleShape.IrregularFluid)
        assertEquals("Selection alone must not rebuild the keyboard", ThemePrefs.RippleShape.SoftMist,
            theme.rippleShape.getValue())
        draft.apply()
        val reopened = KeyboardQuickSettingsDraft(ThemePrefs(stored), AppPrefs(stored).keyboard)
        assertEquals(ThemePrefs.RippleShape.IrregularFluid, reopened.values.rippleShape)
        reopened.values = reopened.values.copy(rippleShape = ThemePrefs.RippleShape.SoftMist)
        reopened.apply()
        assertEquals(ThemePrefs.RippleShape.SoftMist, theme.rippleShape.getValue())
        assertEquals(ThemePrefs.PressColorMode.Custom, theme.pressColorMode.getValue())
        assertEquals("#18FFC1,#D96EFF", theme.pressUserColors.getValue())
    }
}
