package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.data.HapticStrength
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.settings.DialogSeekBarPreference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ClassName
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemVibrator
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowVibrator
import org.robolectric.util.ReflectionHelpers

/** Captures the real public vibrator path, rather than inferring silence from preferences. */
@Implements(className = "android.os.SystemVibrator", isInAndroidSdk = false)
class RecordingHapticVibratorShadow : ShadowSystemVibrator() {
    @Implementation
    protected override fun hasAmplitudeControl(): Boolean {
        capabilityCalls++
        return super.hasAmplitudeControl()
    }

    @Implementation(minSdk = 31)
    protected override fun vibrate(
        uid: Int,
        opPkg: String?,
        @ClassName("android.os.VibrationEffect") effect: Any?,
        reason: String?,
        @ClassName("android.os.VibrationAttributes") attributes: Any?
    ) {
        effects.add(effect as VibrationEffect)
        super.vibrate(uid, opPkg, effect, reason, attributes)
    }

    companion object {
        val effects = mutableListOf<VibrationEffect>()
        var capabilityCalls = 0
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [RecordingHapticVibratorShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class HapticStrengthTest {
    private lateinit var prefs: AppPrefs.Keyboard
    private lateinit var view: RecordingView
    private lateinit var vibrator: Vibrator

    private class RecordingView(context: Context) : View(context) {
        val calls = mutableListOf<Pair<Int, Int>>()
        override fun performHapticFeedback(feedbackConstant: Int, flags: Int): Boolean {
            calls.add(feedbackConstant to flags)
            return true
        }
    }

    @Before fun prepare() {
        val application = RuntimeEnvironment.getApplication()
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, app)
        }
        AppPrefs.init(application.getSharedPreferences("haptic-strength-test", Context.MODE_PRIVATE))
        prefs = AppPrefs.getInstance().keyboard
        prefs.hapticStrength.sharedPreferences.edit().clear().commit()
        prefs.hapticOnKeyPress.setValue(InputFeedbackMode.Enabled)
        prefs.hapticOnKeyUp.setValue(true)
        prefs.hapticOnRepeat.setValue(true)
        prefs.buttonPressVibrationMilliseconds.setValue(0)
        prefs.buttonLongPressVibrationMilliseconds.setValue(0)
        prefs.buttonPressVibrationAmplitude.setValue(0)
        prefs.buttonLongPressVibrationAmplitude.setValue(0)
        vibrator = application.getSystemService(Vibrator::class.java)
        ShadowVibrator.reset()
        shadowOf(vibrator).setHasVibrator(true)
        shadowOf(vibrator).setHasAmplitudeControl(true)
        RecordingHapticVibratorShadow.effects.clear()
        RecordingHapticVibratorShadow.capabilityCalls = 0
        view = RecordingView(application)
        Settings.System.putInt(application.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        InputFeedbacks.syncSystemPrefs()
    }

    @Test fun zeroSuppressesSystemCustomLongReleaseAndRepeatedFeedbackWithoutProbingHardware() {
        prefs.hapticStrength.setValue(0)
        for (mode in InputFeedbackMode.entries) {
            prefs.hapticOnKeyPress.setValue(mode)
            for (duration in listOf(0, 21)) {
                prefs.buttonPressVibrationMilliseconds.setValue(duration)
                prefs.buttonLongPressVibrationMilliseconds.setValue(duration + if (duration == 0) 0 else 8)
                prefs.buttonPressVibrationAmplitude.setValue(80)
                prefs.buttonLongPressVibrationAmplitude.setValue(160)
                for (longPress in listOf(false, true)) for (keyUp in listOf(false, true)) {
                    // Repeat uses the same production callback, including both release and long press.
                    repeat(4) { InputFeedbacks.hapticFeedback(view, longPress, keyUp) }
                }
            }
        }
        assertTrue("No View feedback call is permitted at 0%", view.calls.isEmpty())
        assertTrue("No vibrator call is permitted at 0%", RecordingHapticVibratorShadow.effects.isEmpty())
        assertEquals("Disabled strength must not probe the vibrator", 0, RecordingHapticVibratorShadow.capabilityCalls)
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test fun absentStrengthKeepsLegacySystemFeedbackAndSavedValuesUnchanged() {
        assertFalse(prefs.hapticStrength.sharedPreferences.contains(prefs.hapticStrength.key))
        assertEquals(100, prefs.hapticStrength.getValue())
        prefs.buttonPressVibrationAmplitude.setValue(87)
        val before = prefs.hapticStrength.sharedPreferences.all.toMap()
        InputFeedbacks.hapticFeedback(view)
        InputFeedbacks.hapticFeedback(view, longPress = true)
        InputFeedbacks.hapticFeedback(view, keyUp = true)
        assertEquals(listOf(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.LONG_PRESS,
            HapticFeedbackConstants.KEYBOARD_RELEASE), view.calls.map { it.first })
        @Suppress("DEPRECATION")
        val flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        assertTrue(view.calls.all { it.second == flags })
        assertTrue(RecordingHapticVibratorShadow.effects.isEmpty())
        assertEquals(0, RecordingHapticVibratorShadow.capabilityCalls)
        assertEquals(before, prefs.hapticStrength.sharedPreferences.all)
    }

    @Test fun explicitlySavedHundredRemainsOnTheSameAmplitudeScaleAsNinetyNine() {
        prefs.hapticStrength.setValue(99)
        InputFeedbacks.hapticFeedback(view)
        prefs.hapticStrength.setValue(100)
        val before = prefs.hapticStrength.sharedPreferences.all.toMap()
        InputFeedbacks.hapticFeedback(view)
        InputFeedbacks.hapticFeedback(view, longPress = true)
        InputFeedbacks.hapticFeedback(view, keyUp = true)
        assertEquals(listOf(VibrationEffect.createOneShot(16, 252), VibrationEffect.createOneShot(16, 255),
            VibrationEffect.createOneShot(32, 255), VibrationEffect.createOneShot(10, 255)),
            RecordingHapticVibratorShadow.effects)
        assertTrue(view.calls.isEmpty())
        assertEquals(before, prefs.hapticStrength.sharedPreferences.all)
    }

    @Test fun confirmingActualDialogAtDefaultHundredPersistsExplicitStrengthWithoutInitialMutation() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java).also {
            it.get().setTheme(R.style.Theme_FcitxAppTheme)
        }.setup()
        var dialog: AlertDialog? = null
        try {
            val activity = controller.get()
            val stored = activity.getSharedPreferences("explicit-haptic-dialog", Context.MODE_PRIVATE)
            stored.edit().clear().commit()
            val localPrefs = AppPrefs(stored).keyboard
            val manager = PreferenceManager(activity).apply { sharedPreferencesName = "explicit-haptic-dialog" }
            val screen = manager.createPreferenceScreen(activity)
            localPrefs.createUi(screen)
            assertFalse("Constructing settings must preserve the absent legacy key", stored.contains(localPrefs.hapticStrength.key))
            screen.findPreference<DialogSeekBarPreference>(localPrefs.hapticStrength.key)!!.performClick()
            dialog = ShadowDialog.getLatestDialog() as AlertDialog
            shadowOf(Looper.getMainLooper()).idle()
            fun sliders(view: View): List<SeekBar> = when (view) {
                is SeekBar -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { sliders(view.getChildAt(it)) }
                else -> emptyList()
            }
            assertEquals(100, sliders(dialog.window!!.decorView).single().progress)
            assertFalse("Opening the dialog must not opt a legacy user into explicit pulses", stored.contains(localPrefs.hapticStrength.key))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Confirming even the unchanged default must opt into the explicit scale", stored.contains(localPrefs.hapticStrength.key))
            assertEquals(100, localPrefs.hapticStrength.getValue())
        } finally {
            dialog?.dismiss()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun halfStrengthProducesScaledPulsesForDefaultDurationIncludingLongAndRelease() {
        prefs.hapticStrength.setValue(50)
        prefs.buttonLongPressVibrationAmplitude.setValue(120)
        val before = prefs.hapticStrength.sharedPreferences.all.toMap()
        InputFeedbacks.hapticFeedback(view)
        InputFeedbacks.hapticFeedback(view, longPress = true)
        InputFeedbacks.hapticFeedback(view, keyUp = true)
        assertEquals(listOf(VibrationEffect.createOneShot(16, 128), VibrationEffect.createOneShot(32, 60),
            VibrationEffect.createOneShot(10, 128)), RecordingHapticVibratorShadow.effects)
        assertTrue(view.calls.isEmpty())
        assertEquals(before, prefs.hapticStrength.sharedPreferences.all)
    }

    @Test fun customPulseIsPreservedAtHundredAndScaledWithoutChangingStoredDurationOrAmplitude() {
        prefs.hapticStrength.setValue(100)
        prefs.buttonPressVibrationMilliseconds.setValue(24)
        prefs.buttonPressVibrationAmplitude.setValue(120)
        prefs.buttonLongPressVibrationMilliseconds.setValue(43)
        prefs.buttonLongPressVibrationAmplitude.setValue(200)
        InputFeedbacks.hapticFeedback(view)
        InputFeedbacks.hapticFeedback(view, longPress = true)
        prefs.hapticStrength.setValue(25)
        val before = prefs.hapticStrength.sharedPreferences.all.toMap()
        InputFeedbacks.hapticFeedback(view)
        InputFeedbacks.hapticFeedback(view, longPress = true)
        assertEquals(listOf(VibrationEffect.createOneShot(24, 120), VibrationEffect.createOneShot(43, 200),
            VibrationEffect.createOneShot(24, 30), VibrationEffect.createOneShot(43, 50)),
            RecordingHapticVibratorShadow.effects)
        assertTrue(view.calls.isEmpty())
        assertEquals(before, prefs.hapticStrength.sharedPreferences.all)
    }

    @Test fun absentStrengthPreservesLegacyCustomDurationAndDefaultAmplitudeWithoutMigration() {
        prefs.buttonPressVibrationMilliseconds.setValue(24)
        prefs.buttonPressVibrationAmplitude.setValue(0)
        prefs.buttonLongPressVibrationMilliseconds.setValue(43)
        prefs.buttonLongPressVibrationAmplitude.setValue(200)
        val before = prefs.hapticStrength.sharedPreferences.all.toMap()
        InputFeedbacks.hapticFeedback(view)
        InputFeedbacks.hapticFeedback(view, longPress = true)
        assertEquals(listOf(VibrationEffect.createOneShot(24, VibrationEffect.DEFAULT_AMPLITUDE),
            VibrationEffect.createOneShot(43, 200)), RecordingHapticVibratorShadow.effects)
        assertTrue(view.calls.isEmpty())
        assertFalse(prefs.hapticStrength.sharedPreferences.contains(prefs.hapticStrength.key))
        assertEquals(before, prefs.hapticStrength.sharedPreferences.all)
    }

    @Test fun hardwareWithoutAmplitudeControlKeepsExistingSystemAndCustomDurationPaths() {
        shadowOf(vibrator).setHasAmplitudeControl(false)
        for (strength in listOf(35, 100)) {
            prefs.hapticStrength.setValue(strength)
            InputFeedbacks.hapticFeedback(view)
            InputFeedbacks.hapticFeedback(view, longPress = true)
            InputFeedbacks.hapticFeedback(view, keyUp = true)
        }
        assertEquals(6, view.calls.size)
        assertTrue(RecordingHapticVibratorShadow.effects.isEmpty())
        prefs.buttonPressVibrationMilliseconds.setValue(19)
        prefs.buttonPressVibrationAmplitude.setValue(70)
        InputFeedbacks.hapticFeedback(view)
        assertEquals(listOf(VibrationEffect.createOneShot(19, VibrationEffect.DEFAULT_AMPLITUDE)),
            RecordingHapticVibratorShadow.effects)
    }

    @Test fun nonzeroStrengthStillRespectsDisabledModeSystemPreferenceAndKeyUpGate() {
        prefs.hapticStrength.setValue(40)
        prefs.hapticOnKeyPress.setValue(InputFeedbackMode.Disabled)
        InputFeedbacks.hapticFeedback(view)
        prefs.hapticOnKeyPress.setValue(InputFeedbackMode.FollowingSystem)
        Settings.System.putInt(RuntimeEnvironment.getApplication().contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
        InputFeedbacks.syncSystemPrefs()
        InputFeedbacks.hapticFeedback(view, longPress = true)
        prefs.hapticOnKeyPress.setValue(InputFeedbackMode.Enabled)
        prefs.hapticOnKeyUp.setValue(false)
        InputFeedbacks.hapticFeedback(view, keyUp = true)
        assertTrue(view.calls.isEmpty())
        assertTrue(RecordingHapticVibratorShadow.effects.isEmpty())
        assertEquals(0, RecordingHapticVibratorShadow.capabilityCalls)
        InputFeedbacks.hapticFeedback(view)
        assertEquals(listOf(VibrationEffect.createOneShot(16, 102)), RecordingHapticVibratorShadow.effects)
    }

    @Test fun mapperKeepsTheEntireExplicitRangeMonotonicIncludingHundred() {
        val amplitudes = (1..100).map { HapticStrength.amplitude(it, 0) }
        assertTrue(amplitudes.all { it in 1..255 })
        assertTrue(amplitudes.zipWithNext().all { (a, b) -> a <= b })
        assertEquals(0, HapticStrength.amplitude(0, 180))
        assertEquals(1, HapticStrength.amplitude(1, 20))
        assertEquals(120, HapticStrength.amplitude(100, 120))
        assertEquals(255, HapticStrength.amplitude(100, 0))
        assertEquals(47L, HapticStrength.duration(47, longPress = true, keyUp = false))
        assertEquals(16L, HapticStrength.duration(0, longPress = false, keyUp = false))
        assertEquals(32L, HapticStrength.duration(0, longPress = true, keyUp = false))
        assertEquals(10L, HapticStrength.duration(0, longPress = false, keyUp = true))
    }
}
