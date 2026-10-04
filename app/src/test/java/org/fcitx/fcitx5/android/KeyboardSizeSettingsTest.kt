/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyboardSizePolicy
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.ui.main.settings.TwinSeekBarPreference
import org.fcitx.fcitx5.android.ui.main.settings.DialogSeekBarPreference
import org.fcitx.fcitx5.android.ui.main.settings.behavior.KeyboardQuickControls
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class KeyboardSizeSettingsTest {
    @Before fun prepare() {
        val application = RuntimeEnvironment.getApplication()
        val app = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(app, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        FcitxApplication::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, app) }
        AppPrefs.init(application.getSharedPreferences("size-settings-global", Context.MODE_PRIVATE))
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    @Test fun hidingNumberRowPreservesEveryLetterRowsHeightAndRemovesItsSpace() {
        val p = ThemeManager.prefs
        val old = p.portraitNumberRow.getValue()
        val keyboards = mutableListOf<TextKeyboard>()
        try {
            for (baseHeight in listOf(260, 421, 450)) {
                p.portraitNumberRow.setValue(true)
                val before = TextKeyboard(RuntimeEnvironment.getApplication(), ThemePreset.XuancaiBlackV09).also {
                    keyboards.add(it); layout(it, 359, baseHeight)
                }
                p.portraitNumberRow.setValue(false)
                val after = TextKeyboard(RuntimeEnvironment.getApplication(), ThemePreset.XuancaiBlackV09).also {
                    keyboards.add(it); layout(it, 359, KeyboardSizePolicy.heightForLayout(baseHeight, true, true, false))
                }
                assertEquals(5, before.childCount)
                assertEquals(4, after.childCount)
                for (row in 0 until 4) {
                    assertEquals("Letter/function row $row must not stretch when numbers disappear",
                        before.getChildAt(row + 1).height.toDouble(), after.getChildAt(row).height.toDouble(), 1.0)
                }
                assertEquals(0, after.getChildAt(0).top)
                assertEquals(after.height, after.getChildAt(3).bottom)
                assertEquals("Only one row should disappear", before.getChildAt(0).height.toDouble(),
                    (before.height - after.height).toDouble(), 1.0)
                assertEquals(baseHeight, KeyboardSizePolicy.heightForLayout(baseHeight, true, false, false))
                assertEquals(baseHeight, KeyboardSizePolicy.heightForLayout(baseHeight, false, true, false))
            }
        } finally {
            keyboards.forEach { it.onDetach() }
            p.portraitNumberRow.setValue(old)
        }
    }

    @Test fun untouchedPortraitDefaultsGainAboutTenPercentHeightAndExistingSavedSizesSurvive() {
        listOf(Triple(360, 800, 1f), Triple(1080, 2400, 3f), Triple(1440, 3200, 4f), Triple(800, 1280, 1f)).forEach { (width, height, density) ->
            val percent = KeyboardSizePolicy.defaultPortraitHeightPercent(width, height, density)
            // These are the previously shipped defaults, not a copy of the sizing formula.
            val previousPercent = if (width == 800) 37 else 27
            assertEquals(if (width == 800) 41 else 30, percent)
            assertTrue("Untouched portrait height should rise by about one tenth", percent.toFloat() / previousPercent in 1.08f..1.12f)
            assertEquals("Launching while rotated must produce the same portrait default", percent,
                KeyboardSizePolicy.defaultPortraitHeightPercent(height, width, density))
            val base = height * percent / 100
            val capWidth = width / 10f - 6f * density
            val capHeight = base / 5f - 8f * density
            assertTrue("Roomier defaults must still keep balanced key proportions", capHeight / capWidth in 1.25f..1.42f)
        }
        val context = RuntimeEnvironment.getApplication()
        val saved = context.getSharedPreferences("legacy-size", Context.MODE_PRIVATE)
        saved.edit().clear().putInt("keyboard_height_percent", 41)
            .putInt("keyboard_height_percent_landscape", 53).putInt("keyboard_side_padding", 18).commit()
        val before = saved.all.toMap()
        val prefs = AppPrefs(saved).keyboard
        assertEquals(41, prefs.keyboardHeightPercent.getValue())
        assertEquals(53, prefs.keyboardHeightPercentLandscape.getValue())
        assertEquals(18, prefs.keyboardSidePadding.getValue())
        assertEquals(100, prefs.hapticStrength.getValue())
        assertEquals(100, prefs.hapticStrength.defaultValue)
        assertEquals("A newer default must not migrate any saved height or side margin", before, saved.all)
        val fresh = context.getSharedPreferences("fresh-size-defaults", Context.MODE_PRIVATE)
        fresh.edit().clear().commit()
        val freshPrefs = AppPrefs(fresh).keyboard
        assertEquals("Landscape already uses almost half the screen", 49, freshPrefs.keyboardHeightPercentLandscape.getValue())
        assertTrue("Reading default sizes must not persist them over future manual settings", fresh.all.isEmpty())
    }

    @Test fun realHeightControlPersistsBothOrientationsAndChangesActualKeyGeometry() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java).also {
            it.get().setTheme(R.style.Theme_FcitxAppTheme)
        }.setup()
        val activity = controller.get()
        val stored = activity.getSharedPreferences("real-size-control", Context.MODE_PRIVATE)
        stored.edit().clear().putInt("keyboard_height_percent", 27).putInt("keyboard_height_percent_landscape", 49).commit()
        val prefs = AppPrefs(stored).keyboard
        val manager = PreferenceManager(activity).apply { sharedPreferencesName = "real-size-control" }
        val screen = manager.createPreferenceScreen(activity)
        prefs.createUi(screen)
        KeyboardQuickControls.attach(screen, prefs)
        val category = screen.getPreference(0)
        assertEquals("keyboard_size_feedback", category.key)
        assertEquals("keyboard_size_feedback", screen.findPreference<Preference>(prefs.hapticStrength.key)!!.parent!!.key)
        val height = screen.findPreference<TwinSeekBarPreference>(prefs.keyboardHeightPercent.key)!!
        height.performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        shadowOf(Looper.getMainLooper()).idle()
        fun sliders(view: View): List<SeekBar> = when (view) {
            is SeekBar -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { sliders(view.getChildAt(it)) }
            else -> emptyList()
        }
        val bars = sliders(dialog.window!!.decorView)
        assertEquals(2, bars.size)
        bars[0].progress = 30 // 10% minimum + 30 = 40%.
        bars[1].progress = 45 // 55% landscape.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(40, prefs.keyboardHeightPercent.getValue())
        assertEquals(55, prefs.keyboardHeightPercentLandscape.getValue())
        screen.findPreference<TwinSeekBarPreference>(prefs.keyboardSidePadding.key)!!.performClick()
        val widthDialog = ShadowDialog.getLatestDialog() as AlertDialog
        shadowOf(Looper.getMainLooper()).idle()
        sliders(widthDialog.window!!.decorView)[0].progress = 28
        widthDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(28, prefs.keyboardSidePadding.getValue())
        screen.findPreference<DialogSeekBarPreference>(prefs.hapticStrength.key)!!.performClick()
        val hapticDialog = ShadowDialog.getLatestDialog() as AlertDialog
        shadowOf(Looper.getMainLooper()).idle()
        sliders(hapticDialog.window!!.decorView).single().progress = 0
        hapticDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("The visible strength control must save a real unconditional off value", 0, prefs.hapticStrength.getValue())
        val context = RuntimeEnvironment.getApplication()
        val before = TextKeyboard(context, ThemePreset.XuancaiBlackV09)
        val after = TextKeyboard(context, ThemePreset.XuancaiBlackV09)
        try {
            layout(before, 360, 800 * 27 / 100)
            val padding = KeyboardSizePolicy.sidePaddingForWidth(prefs.keyboardSidePadding.getValue(), 360, 1f)
            layout(after, 360 - padding * 2, 800 * prefs.keyboardHeightPercent.getValue() / 100)
            assertTrue(after.getChildAt(0).height > before.getChildAt(0).height)
            assertTrue((after.getChildAt(0) as ViewGroup).getChildAt(0).width <
                (before.getChildAt(0) as ViewGroup).getChildAt(0).width)
        } finally {
            before.onDetach(); after.onDetach(); controller.pause().stop().destroy()
        }
    }

    @Test fun widthMarginsRemainUsableAfterMovingAnExistingPreferenceToASmallerScreen() {
        assertEquals(24, KeyboardSizePolicy.sidePaddingForWidth(24, 360, 1f))
        assertEquals(80, KeyboardSizePolicy.sidePaddingForWidth(300, 360, 1f))
        assertEquals(300, KeyboardSizePolicy.sidePaddingForWidth(300, 800, 1f))
        assertEquals(0, KeyboardSizePolicy.sidePaddingForWidth(20, 180, 1f))
    }

    @Test fun directBootMirrorPreservesUntouchedAndExplicitVibrationStrength() {
        val context = RuntimeEnvironment.getApplication()
        val stored = context.getSharedPreferences("strength-mirror-source", Context.MODE_PRIVATE)
        stored.edit().clear().commit()
        val prefs = AppPrefs(stored)
        val mirror = PreferenceManager.getDefaultSharedPreferences(context.createDeviceProtectedStorageContext())
        mirror.edit().putInt(prefs.keyboard.hapticStrength.key, 100).commit()
        prefs.syncToDeviceEncryptedStorage()
        assertFalse("Untouched strength must stay on legacy feedback before unlock",
            mirror.contains(prefs.keyboard.hapticStrength.key))
        prefs.keyboard.hapticStrength.setValue(0)
        prefs.syncToDeviceEncryptedStorage()
        assertEquals(0, mirror.getInt(prefs.keyboard.hapticStrength.key, -1))
        prefs.keyboard.hapticStrength.setValue(100)
        prefs.syncToDeviceEncryptedStorage()
        assertEquals(100, mirror.getInt(prefs.keyboard.hapticStrength.key, -1))
    }
}
