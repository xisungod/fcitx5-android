/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.PressColorPalette
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.PressEffect
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.popup.PopupEntryUi
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import kotlin.math.abs
import kotlin.random.Random

/** User RGB values must survive settings, keyboard construction and popup colour routing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w600dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PressColorSettingsTest {
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
        AppPrefs.init(application.getSharedPreferences("press-color-settings", Context.MODE_PRIVATE))
    }

    @After
    fun restoreUiApplication() {
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, oldUiApplication)
        }
    }

    private class Harness {
        init {
            ShadowChoreographer.setPaused(true)
            ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        }

        private val restore = mutableListOf<() -> Unit>()
        private val saved = mutableSetOf<Pair<SharedPreferences, String>>()
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        private val activity = controller.get()
        private val root = FrameLayout(activity)
        private val popup = PopupComponent()
        private val preview = PopupEntryUi(activity, ThemePreset.XuancaiBlackV09, 52, 12f)
        private var keyboard: TextKeyboard? = null
        private var unlitFace = intArrayOf(0, 0, 0)
        private var downTime = 0L
        private var popupTint: Int? = null
        val prefs = ThemeManager.prefs

        init {
            setting(prefs.pressEffect, true)
            setting(prefs.idleBreathing, false)
            setting(prefs.effectsFollowSystemAnimation, false)
            setting(prefs.pressGlowBrightness, 0)
            setting(prefs.keyColorStyle, ThemePrefs.KeyColorStyle.Fill)
            setting(prefs.keyExitStyle, ThemePrefs.KeyExitStyle.Dim)
            setting(prefs.keyMotionEffect, ThemePrefs.KeyMotionEffect.Off)
            setting(prefs.coloredPreview, true)
            setting(prefs.previewSameColor, true)
            setting(AppPrefs.getInstance().advanced.disableAnimation, false)
            setting(AppPrefs.getInstance().keyboard.popupOnKeyPress, true)
            activity.setTheme(R.style.Theme_InputViewTheme)
            activity.setContentView(root)
            root.addView(preview.root, FrameLayout.LayoutParams(44, 100))
            controller.visible()
            advance(32)
            root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 360, 400)
            assertTrue(root.isAttachedToWindow && root.isShown)
        }

        fun <T : Any> setting(preference: ManagedPreference<T>, value: T) {
            if (saved.add(preference.sharedPreferences to preference.key)) {
                val existed = preference.sharedPreferences.contains(preference.key)
                val old = preference.getValue()
                restore.add {
                    if (existed) preference.setValue(old)
                    else preference.sharedPreferences.edit().remove(preference.key).commit()
                }
            }
            preference.setValue(value)
        }

        fun rebuild() {
            keyboard?.let { it.onDetach(); root.removeView(it) }
            val next = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
            keyboard = next
            root.addView(next, 0, FrameLayout.LayoutParams(360, 260))
            next.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(260, View.MeasureSpec.EXACTLY))
            next.layout(0, 0, 360, 260)
            keys(next).forEach { it.longPressEnabled = false; it.repeatEnabled = false }
            next.keyActionListener = KeyActionListener { _, _ -> }
            val effect = ReflectionHelpers.getField<PressEffect>(next, "pressEffectLayer")
            ReflectionHelpers.setField(effect, "random", Random(1405))
            next.popupActionListener = PopupActionListener { action ->
                if (action is PopupAction.PreviewAction) {
                    // Invoke the production resolver without constructing engine dependencies.
                    val tint = ReflectionHelpers.callInstanceMethod<Int?>(popup, "nextPreviewColor",
                        ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, action.viewId))
                    popupTint = tint
                    preview.setText(action.content)
                    preview.tint(tint)
                }
            }
            assertTrue(next.isAttachedToWindow && next.isShown)
            unlitFace = sample(render(), key())
        }

        private fun keys(view: View): List<KeyView> = when (view) {
            is KeyView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { keys(view.getChildAt(it)) }
            else -> emptyList()
        }

        private fun key() = keys(keyboard!!).first {
            (it.def as? KeyDef.Appearance.Text)?.displayText == "A"
        }

        private fun bounds(key: KeyView) = Rect().also {
            key.getDrawingRect(it)
            keyboard!!.offsetDescendantRectToMyCoords(key, it)
        }

        private fun touch(action: Int, key: KeyView) {
            if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            val rect = bounds(key)
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                rect.exactCenterX(), rect.exactCenterY(), 0)
            try { assertTrue(keyboard!!.dispatchTouchEvent(event)) }
            finally { event.recycle() }
        }

        private fun render(): Bitmap = Bitmap.createBitmap(360, 260, Bitmap.Config.ARGB_8888).also {
            keyboard!!.draw(Canvas(it).apply { drawColor(Color.BLACK) })
        }

        /** A patch beside the glyph measures the key face through its real view background. */
        private fun sample(image: Bitmap, key: KeyView): IntArray {
            val rect = bounds(key)
            val x = rect.left + key.hMargin + ((rect.width() - key.hMargin * 2) * 0.18f).toInt()
            val y = rect.top + key.vMargin + ((rect.height() - key.vMargin * 2) * 0.55f).toInt()
            val rgb = IntArray(3)
            for (dy in -1..1) for (dx in -1..1) {
                val colour = image.getPixel(x + dx, y + dy)
                rgb[0] += Color.red(colour); rgb[1] += Color.green(colour); rgb[2] += Color.blue(colour)
            }
            return rgb.map { it / 9 }.toIntArray()
        }

        fun pressAndCheck(): Int {
            val key = key()
            popupTint = null
            touch(MotionEvent.ACTION_DOWN, key)
            try {
                val colour = PressEffect.colorForKey(key.id)
                assertNotNull("A real DOWN must publish its current key colour", colour)
                val tint = colour!!
                assertEquals("The real popup resolver must select the current arbitrary RGB", tint, popupTint)
                val popupImage = Bitmap.createBitmap(44, 52, Bitmap.Config.ARGB_8888)
                preview.textView.background.apply {
                    setBounds(0, 0, 44, 52)
                    draw(Canvas(popupImage))
                }
                assertEquals("The rendered bubble must retain that exact colour", tint, popupImage.getPixel(6, 26))
                val actual = sample(render(), key)
                val channels = intArrayOf(Color.red(tint), Color.green(tint), Color.blue(tint))
                for (i in 0..2) {
                    val expected = (channels[i] * 0.92f + unlitFace[i] * 0.08f).toInt()
                    assertTrue("Real key face channel $i must follow the chosen RGB: ${actual.toList()}",
                        abs(actual[i] - expected) <= 8)
                }
                return tint
            } finally {
                touch(MotionEvent.ACTION_UP, key)
                advance(16)
            }
        }

        fun previewTextColour(): Int = preview.textView.currentTextColor

        fun randomIdleStyleCount(): Int {
            val effect = ReflectionHelpers.getField<PressEffect>(keyboard, "pressEffectLayer")
            return ReflectionHelpers.getField<Array<Any>>(effect, "idleStyles").size
        }

        fun resolveIndependentPopupColour(): Int = requireNotNull(
            ReflectionHelpers.callInstanceMethod<Int?>(popup, "nextPreviewColor",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, -1)))

        private fun advance(milliseconds: Long) {
            var remaining = milliseconds
            while (remaining > 0) {
                val step = minOf(remaining, 16L)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
                remaining -= step
            }
        }

        fun finish() {
            try {
                keyboard?.onDetach()
                controller.pause().stop().destroy()
            } finally {
                restore.asReversed().forEach { it() }
                ShadowChoreographer.setPaused(false)
            }
        }
    }

    @Test
    fun everyNewPresetReachesTheRealKeySurfaceAndPopupWithOneSharedColorPerPress() {
        val h = Harness()
        try {
            h.setting(h.prefs.pressColorMode, ThemePrefs.PressColorMode.Random)
            PressColorPalette.recommendedPresets.forEach { (preset, palette) ->
                h.setting(h.prefs.pressEffectPalette, preset)
                h.rebuild()
                val observed = (0 until 12).map { h.pressAndCheck() }
                assertTrue(preset.name, observed.all { it in palette })
                assertTrue("${preset.name} must produce varying colors", observed.toSet().size > 1)
                observed.zipWithNext().forEach { (left, right) -> assertNotEquals(preset.name, left, right) }
            }
        } finally { h.finish() }
    }

    @Test
    fun anArbitrarySingleRgbPersistsAndSurvivesRebuildingTheRealKeyboard() {
        val orange = 0xffff8a32.toInt()
        assertFalse(ThemePrefs.NeonColor.entries.any { it.argb == orange })
        val h = Harness()
        try {
            h.setting(h.prefs.pressColorMode, ThemePrefs.PressColorMode.Single)
            h.setting(h.prefs.pressSingleColor, orange)
            h.setting(h.prefs.pressUserColors, "#18FFC1,#D96EFF")
            h.setting(h.prefs.pressExpansionTime, 1400)
            h.setting(h.prefs.pressWaveHoldTime, 350)
            h.setting(h.prefs.pressFadeOutTime, 900)
            h.rebuild()
            repeat(4) { assertEquals(orange, h.pressAndCheck()) }

            val storage = h.prefs.pressSingleColor.sharedPreferences
            assertEquals(orange, storage.getInt("press_single_argb", 0))
            assertEquals("#18FFC1,#D96EFF", storage.getString("press_user_colors", null))
            // A fresh preference provider reads persisted data, not the previous objects.
            val reopened = ThemePrefs(storage)
            assertEquals(ThemePrefs.PressColorMode.Single, reopened.pressColorMode.getValue())
            assertEquals(orange, reopened.pressSingleColor.getValue())
            assertArrayEquals(intArrayOf(0xff18ffc1.toInt(), 0xffd96eff.toInt()), reopened.userPressColors())
            assertEquals(1400, reopened.pressExpansionTime.getValue())
            assertEquals(350, reopened.pressWaveHoldTime.getValue())
            assertEquals(900, reopened.pressFadeOutTime.getValue())
            h.rebuild()
            repeat(3) { assertEquals(orange, h.pressAndCheck()) }
        } finally { h.finish() }
    }

    @Test
    fun aCustomRgbPairIsTheOnlySourceForRealKeysAndTheirBubbles() {
        val expected = setOf(0xff18ffc1.toInt(), 0xffd96eff.toInt())
        val h = Harness()
        try {
            h.setting(h.prefs.pressColorMode, ThemePrefs.PressColorMode.Custom)
            h.setting(h.prefs.pressUserColors, "#18FFC1,#D96EFF")
            h.setting(h.prefs.pressEffectPalette, ThemePrefs.PressEffectPalette.Rainbow)
            h.rebuild()
            val observed = (0 until 8).map { h.pressAndCheck() }.toSet()
            assertEquals("Custom mode must use both user colours and no preset colours", expected, observed)
        } finally { h.finish() }
    }

    @Test
    fun aMediumGrayUserColourKeepsTheRealPopupCharacterReadable() {
        val gray = 0xff989898.toInt()
        val h = Harness()
        try {
            h.setting(h.prefs.pressColorMode, ThemePrefs.PressColorMode.Single)
            h.setting(h.prefs.pressSingleColor, gray)
            h.rebuild()
            assertEquals(gray, h.pressAndCheck())
            val actualInk = h.previewTextColour()
            val contrast = ColorUtils.calculateContrast(actualInk, gray)
            assertTrue("The actual popup character needs readable contrast on a user-picked gray", contrast >= 4.5)
            assertTrue("The real popup must select the more readable of its two existing inks",
                contrast >= ColorUtils.calculateContrast(Color.WHITE, gray))
        } finally { h.finish() }
    }

    @Test
    fun randomBreathingAndIndependentBubblesUseTheFullCyberNeonPreset() {
        val h = Harness()
        try {
            h.setting(h.prefs.idleBreathing, true)
            h.setting(h.prefs.idleRandomColors, true)
            h.setting(h.prefs.pressColorMode, ThemePrefs.PressColorMode.Custom)
            h.setting(h.prefs.pressUserColors, "#989898")
            h.setting(h.prefs.previewSameColor, false)
            h.rebuild()
            assertEquals("The real keyboard prepares every full-wheel random breathing colour",
                PressEffect.CYBERPUNK.size, h.randomIdleStyleCount())
            var previous: Int? = null
            repeat(128) {
                val selected = h.resolveIndependentPopupColour()
                assertTrue("Independent popup colours come from the full cyber preset",
                    selected in PressEffect.CYBERPUNK)
                assertNotEquals("Independent bubbles retain a change on each press", previous, selected)
                previous = selected
            }
            // Turning random breathing off still keeps the user's two named colours.
            h.setting(h.prefs.idleRandomColors, false)
            h.setting(h.prefs.idleColorPrimary, ThemePrefs.NeonColor.Yellow)
            h.setting(h.prefs.idleColorSecondary, ThemePrefs.NeonColor.Purple)
            h.rebuild()
            assertEquals("Manual breathing keeps one configured two-colour style", 1, h.randomIdleStyleCount())
            assertEquals(ThemePrefs.NeonColor.Yellow, h.prefs.idleColorPrimary.getValue())
            assertEquals(ThemePrefs.NeonColor.Purple, h.prefs.idleColorSecondary.getValue())
            assertArrayEquals("The shared default does not replace arbitrary user colours",
                intArrayOf(0xff989898.toInt()), h.prefs.userPressColors())
        } finally { h.finish() }
    }

    @Test
    fun legacyRandomModeKeepsItsSavedNeonCustomPalette() {
        val expected = setOf(ThemePrefs.NeonColor.IceBlue.argb, ThemePrefs.NeonColor.Magenta.argb)
        val h = Harness()
        try {
            h.setting(h.prefs.pressColorMode, ThemePrefs.PressColorMode.Random)
            h.setting(h.prefs.pressEffectPalette, ThemePrefs.PressEffectPalette.Custom)
            h.setting(h.prefs.pressCustomColorCount, 2)
            h.setting(h.prefs.pressCustomColor1, ThemePrefs.NeonColor.IceBlue)
            h.setting(h.prefs.pressCustomColor2, ThemePrefs.NeonColor.Magenta)
            h.setting(h.prefs.pressUserColors, "#18FFC1,#D96EFF")
            h.setting(h.prefs.pressSingleColor, 0xffff8a32.toInt())
            h.rebuild()
            assertEquals("Existing custom NeonColor settings must remain the Random-mode palette",
                expected, (0 until 6).map { h.pressAndCheck() }.toSet())
        } finally { h.finish() }
    }

    @Test
    fun rgbTextRoundTripsAndBoundsTheUserPaletteToEightColours() {
        assertEquals(0xff18ffc1.toInt(), PressColorPalette.parseColor("  #18ffC1  "))
        assertEquals("#18FFC1", PressColorPalette.formatColor(0x0018ffc1))
        assertNull(PressColorPalette.parseColor("#F80"))
        assertNull(PressColorPalette.parseColor("#11223344"))
        assertNull(PressColorPalette.parseColor("not-a-colour"))
        val eight = intArrayOf(0xff102030.toInt(), 0xff203040.toInt(), 0xff304050.toInt(),
            0xff405060.toInt(), 0xff506070.toInt(), 0xff607080.toInt(), 0xff708090.toInt(), 0xff8090a0.toInt())
        val encoded = PressColorPalette.encode(eight + intArrayOf(0xff90a0b0.toInt(), 0xffa0b0c0.toInt()))
        assertArrayEquals(eight, PressColorPalette.parse(encoded))
        assertArrayEquals(eight, PressColorPalette.parse(encoded + ",#90A0B0,invalid,#A0B0C0"))
    }

    @Test
    fun invalidUserPaletteFallsBackToTheSavedLegacyColours() {
        val context = RuntimeEnvironment.getApplication()
        val storage = context.getSharedPreferences("invalid-user-press-palette", Context.MODE_PRIVATE)
        storage.edit().clear().commit()
        try {
            val legacy = ThemePrefs(storage)
            legacy.pressCustomColorCount.setValue(2)
            legacy.pressCustomColor1.setValue(ThemePrefs.NeonColor.Mint)
            legacy.pressCustomColor2.setValue(ThemePrefs.NeonColor.Purple)
            legacy.pressUserColors.setValue("#F80,broken,#11223344")
            val reopened = ThemePrefs(storage)
            assertArrayEquals("An invalid custom string must not silently replace a user's saved legacy palette",
                intArrayOf(ThemePrefs.NeonColor.Mint.argb, ThemePrefs.NeonColor.Purple.argb), reopened.userPressColors())
            reopened.pressUserColors.setValue("broken,#18FFC1")
            assertArrayEquals(intArrayOf(0xff18ffc1.toInt()), reopened.userPressColors())
        } finally { storage.edit().clear().commit() }
    }
}
