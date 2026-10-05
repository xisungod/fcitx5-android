/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
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
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import kotlin.math.abs

/** Exercise real MotionEvent routing and rendered key faces, rather than effect internals. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w600dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class V14TouchRoutingTest {
    @Before
    fun prepareWithoutStartingTheNativeEngine() {
        val application = RuntimeEnvironment.getApplication()
        val uiApplication = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(uiApplication, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, uiApplication)
        }
        AppPrefs.init(application.getSharedPreferences("v14-touch-routing", Context.MODE_PRIVATE))
    }

    private class Harness {
        private val restore = mutableListOf<() -> Unit>()
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        private val activity = controller.get()
        val keyboard: TextKeyboard
        val typed = mutableListOf<String>()
        private var downTime = 0L

        init {
            val p = ThemeManager.prefs
            setting(p.pressEffect, true)
            setting(p.idleBreathing, false)
            setting(p.pressColorMode, ThemePrefs.PressColorMode.Random)
            setting(p.pressEffectPalette, ThemePrefs.PressEffectPalette.Custom)
            setting(p.pressCustomColorCount, 1)
            setting(p.pressCustomColor1, ThemePrefs.NeonColor.Cyan)
            setting(p.keyColorStyle, ThemePrefs.KeyColorStyle.Fill)
            setting(p.keyExitStyle, ThemePrefs.KeyExitStyle.Dim)
            setting(p.keyMotionEffect, ThemePrefs.KeyMotionEffect.Off)
            // This fixture measures held-face routing. End its travelling light
            // before sampling the released face, independently of user defaults.
            setting(p.pressIgnitionTime, 100)
            setting(p.pressExpansionTime, 100)
            setting(p.pressWaveHoldTime, 0)
            setting(p.pressFadeOutTime, 100)
            setting(p.pressKeyHoldTime, 120)
            setting(p.pressKeyRetreatTime, 300)
            setting(AppPrefs.getInstance().advanced.disableAnimation, false)
            activity.setTheme(R.style.Theme_InputViewTheme)
            keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
            activity.setContentView(keyboard)
            controller.visible()
            keyboard.measure(
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(260, View.MeasureSpec.EXACTLY))
            keyboard.layout(0, 0, 360, 260)
            // A held letter should test light routing, not its long-press symbol menu.
            keys(keyboard).forEach { it.longPressEnabled = false; it.repeatEnabled = false }
            keyboard.keyActionListener = KeyActionListener { action, _ ->
                if (action is KeyAction.FcitxKeyAction) typed.add(action.act)
            }
        }

        private fun <T : Any> setting(preference: ManagedPreference<T>, value: T) {
            val old = preference.getValue()
            restore.add { preference.setValue(old) }
            preference.setValue(value)
        }

        private fun keys(view: View): List<KeyView> = when (view) {
            is KeyView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { keys(view.getChildAt(it)) }
            else -> emptyList()
        }

        private fun key(label: String) = keys(keyboard).first {
            (it.def as? KeyDef.Appearance.Text)?.displayText == label
        }

        private fun bounds(key: KeyView) = Rect().also {
            key.getDrawingRect(it)
            keyboard.offsetDescendantRectToMyCoords(key, it)
        }

        fun event(action: Int, vararg pointers: Pair<Int, String>) {
            if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            val properties = pointers.map { (id, _) -> MotionEvent.PointerProperties().apply {
                this.id = id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            } }.toTypedArray()
            val coordinates = pointers.map { (_, label) ->
                val rect = bounds(key(label))
                MotionEvent.PointerCoords().apply {
                    x = rect.exactCenterX(); y = rect.exactCenterY(); pressure = 1f; size = 1f
                }
            }.toTypedArray()
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, pointers.size,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                assertTrue("The real keyboard must handle the touch sequence", keyboard.dispatchTouchEvent(event))
            } finally { event.recycle() }
        }

        fun advance(milliseconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))

        fun render(): Bitmap {
            val image = Bitmap.createBitmap(360, 260, Bitmap.Config.ARGB_8888)
            keyboard.draw(Canvas(image).apply { drawColor(Color.BLACK) })
            return image
        }

        /** Small patches inside the key face avoid the white/dark centre character. */
        fun sample(image: Bitmap, label: String): IntArray {
            val key = key(label)
            val rect = bounds(key)
            val x = rect.left + key.hMargin + ((rect.width() - key.hMargin * 2) * 0.18f).toInt()
            val y = rect.top + key.vMargin + ((rect.height() - key.vMargin * 2) * 0.55f).toInt()
            val rgb = IntArray(3)
            for (dy in -1..1) for (dx in -1..1) {
                val color = image.getPixel(x + dx, y + dy)
                rgb[0] += Color.red(color); rgb[1] += Color.green(color); rgb[2] += Color.blue(color)
            }
            return rgb.map { it / 9 }.toIntArray()
        }

        fun finish() {
            keyboard.onDetach()
            controller.pause().stop().destroy()
            restore.asReversed().forEach { it() }
        }
    }

    private fun difference(first: IntArray, second: IntArray) = first.indices.sumOf { abs(first[it] - second[it]) }

    @Test
    fun aSecondFingerAndItsReleaseCannotEndTheFirstFingersHeldLight() {
        val h = Harness()
        try {
            val baseline = h.render()
            val normalA = h.sample(baseline, "A")
            val normalB = h.sample(baseline, "B")
            h.event(MotionEvent.ACTION_DOWN, 7 to "A")
            val aAlone = difference(h.sample(h.render(), "A"), normalA)
            assertTrue("Pressing A must visibly fill its real key face", aAlone > 150)
            h.advance(40)
            h.event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                7 to "A", 23 to "B")
            val both = h.render()
            val aWithB = difference(h.sample(both, "A"), normalA)
            val bHeld = difference(h.sample(both, "B"), normalB)
            assertTrue("B's DOWN must preserve A's held brightness", aWithB >= aAlone * 0.85f)
            assertTrue("The second finger must independently light B", bHeld > 150)
            h.advance(60)
            h.event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                7 to "A", 23 to "B")
            h.advance(700)
            val afterBReleased = h.render()
            assertTrue("A must remain filled after B's entire release tail",
                difference(h.sample(afterBReleased, "A"), normalA) >= aAlone * 0.85f)
            assertTrue("Only the released B may return close to its unlit face",
                difference(h.sample(afterBReleased, "B"), normalB) < bHeld * 0.35f)
            h.event(MotionEvent.ACTION_UP, 7 to "A")
            h.advance(800)
            assertTrue("The final finger's UP must end its held light",
                difference(h.sample(h.render(), "A"), normalA) < aAlone * 0.15f)
        } finally { h.finish() }
    }

    @Test
    fun cancelEndsAllPointersAndCannotReleaseAnImmediatelyFollowingPress() {
        val h = Harness()
        try {
            val baseline = h.render()
            val normalA = h.sample(baseline, "A")
            val normalB = h.sample(baseline, "B")
            h.event(MotionEvent.ACTION_DOWN, 7 to "A")
            h.event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                7 to "A", 23 to "B")
            h.event(MotionEvent.ACTION_CANCEL, 7 to "A", 23 to "B")
            h.event(MotionEvent.ACTION_DOWN, 23 to "B")
            val bFresh = difference(h.sample(h.render(), "B"), normalB)
            h.advance(700)
            val frame = h.render()
            assertTrue("CANCEL must finish every old pointer's light",
                difference(h.sample(frame, "A"), normalA) < 90)
            assertTrue("An immediate new DOWN must stay held beyond the old tail",
                difference(h.sample(frame, "B"), normalB) >= bFresh * 0.85f)
            h.event(MotionEvent.ACTION_CANCEL, 23 to "B")
            h.advance(800)
            assertTrue("Cancelling the new stream must release its remaining light",
                difference(h.sample(h.render(), "B"), normalB) < bFresh * 0.15f)
            assertTrue("Cancelled streams must never commit letters", h.typed.isEmpty())
        } finally { h.finish() }
    }

    @Test
    fun anEstablishedSlideFinalUpCannotLeaveItsNewKeyHeld() {
        val h = Harness()
        try {
            val baseline = h.render()
            val normalH = h.sample(baseline, "H")
            h.event(MotionEvent.ACTION_DOWN, 23 to "F")
            h.event(MotionEvent.ACTION_MOVE, 23 to "G")
            h.advance(64)
            h.event(MotionEvent.ACTION_MOVE, 23 to "G")
            h.advance(30)
            h.event(MotionEvent.ACTION_UP, 23 to "H")
            assertEquals("Lift-off routing must commit only the newly selected letter", listOf("h"), h.typed)
            val hTail = difference(h.sample(h.render(), "H"), normalH)
            assertTrue("The retargeted key should still receive its visible feedback", hTail > 150)
            h.advance(1700)
            assertTrue("The UP that selected H must also release H's light",
                difference(h.sample(h.render(), "H"), normalH) < hTail * 0.15f)
        } finally { h.finish() }
    }
}
