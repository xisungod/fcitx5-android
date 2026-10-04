/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.graphics.Rect
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Verify actual touch routing, independently of engine correction and visual glow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w600dp-h900dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class V15HitTargetTest {
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
        AppPrefs.init(application.getSharedPreferences("v15-hit-targets", Context.MODE_PRIVATE))
    }

    private data class Finger(val id: Int, val x: Float, val y: Float)

    private class Harness(width: Int, height: Int, numberRow: Boolean) {
        private val restore = mutableListOf<() -> Unit>()
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        private val activity = controller.get()
        val keyboard: TextKeyboard
        val typed = mutableListOf<String>()
        private var downTime = 0L

        init {
            setting(ThemeManager.prefs.pressEffect, false)
            setting(ThemeManager.prefs.idleBreathing, false)
            setting(ThemeManager.prefs.keyMotionEffect, ThemePrefs.KeyMotionEffect.Off)
            setting(ThemeManager.prefs.portraitNumberRow, numberRow)
            setting(AppPrefs.getInstance().keyboard.popupOnKeyPress, false)
            setting(AppPrefs.getInstance().keyboard.expandKeypressArea, true)
            setting(AppPrefs.getInstance().keyboard.showLangSwitchKey, true)
            activity.setTheme(R.style.Theme_InputViewTheme)
            keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
            activity.setContentView(keyboard)
            controller.visible()
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            keyboard.layout(0, 0, width, height)
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

        fun key(label: String) = keys(keyboard).first {
            (it.def as? KeyDef.Appearance.Text)?.displayText == label
        }

        fun bounds(label: String) = Rect().also { rect ->
            key(label).getDrawingRect(rect)
            keyboard.offsetDescendantRectToMyCoords(key(label), rect)
        }

        fun finger(id: Int, label: String) = bounds(label).let {
            Finger(id, it.exactCenterX(), it.exactCenterY())
        }

        fun event(action: Int, vararg fingers: Finger) {
            if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
            val properties = fingers.map { MotionEvent.PointerProperties().apply {
                id = it.id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            } }.toTypedArray()
            val coords = fingers.map { MotionEvent.PointerCoords().apply {
                x = it.x; y = it.y; pressure = 1f; size = 1f
            } }.toTypedArray()
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, fingers.size,
                properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue("A touch inside a real key must be handled", keyboard.dispatchTouchEvent(event)) }
            finally { event.recycle() }
        }

        fun tap(finger: Finger) {
            event(MotionEvent.ACTION_DOWN, finger)
            event(MotionEvent.ACTION_UP, finger)
        }

        fun finish() {
            keyboard.onDetach()
            controller.pause().stop().destroy()
            restore.asReversed().forEach { it() }
        }
    }

    @Test
    fun allLetterCentresRemainAccurateAtDifferentSizesWithAndWithoutTheDigitRow() {
        val letters = "QWERTYUIOPASDFGHJKLZXCVBNM"
        // Shared row heights: hiding the digit row removes its height instead of
        // expanding the remaining four rows. Include odd widths / heights.
        for ((width, fullHeight) in listOf(360 to 235, 359 to 231, 599 to 391)) {
            for (numberRow in listOf(true, false)) {
                val height = if (numberRow) fullHeight else (fullHeight * 4 + 2) / 5
                val h = Harness(width, height, numberRow)
                try {
                    letters.forEachIndexed { index, letter -> h.tap(h.finger(if (index % 2 == 0) 7 else 23, letter.toString())) }
                    assertEquals("Viewport $width x $height, digit row $numberRow must not change letter targets",
                        letters.lowercase(), h.typed.joinToString(""))
                } finally { h.finish() }
            }
        }
    }

    @Test
    fun visualHorizontalKeyGapsBelongToTheAdjacentFullTouchCells() {
        for (numberRow in listOf(true, false)) {
            val h = Harness(359, if (numberRow) 231 else 185, numberRow)
            try {
                val expected = StringBuilder()
                for (row in listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")) {
                    for (index in 0 until row.lastIndex) {
                        val leftLabel = row[index].toString()
                        val rightLabel = row[index + 1].toString()
                        val left = h.bounds(leftLabel)
                        val right = h.bounds(rightLabel)
                        assertEquals("The two outer touch cells must share their boundary", left.right, right.left)
                        val y = left.exactCenterY()
                        // These points are in the visual gutter when margins are
                        // enabled, but remain in distinct outer touch cells.
                        h.tap(Finger(7, left.right - 1f, y))
                        h.tap(Finger(23, right.left + 1f, y))
                        expected.append(row[index].lowercaseChar()).append(row[index + 1].lowercaseChar())
                    }
                }
                assertEquals("Visible gutters must not silently discard taps", expected.toString(), h.typed.joinToString(""))
            } finally { h.finish() }
        }
    }

    @Test
    fun overlappingThumbsWithNonConsecutivePointerIdsDoNotLoseLetters() {
        val raw = "lianggehuanglimingcuiliu"
        val h = Harness(360, 188, numberRow = false)
        try {
            var index = 0
            while (index < raw.length) {
                val first = h.finger(7, raw[index].uppercaseChar().toString())
                h.event(MotionEvent.ACTION_DOWN, first)
                if (index + 1 < raw.length) {
                    val second = h.finger(23, raw[index + 1].uppercaseChar().toString())
                    h.event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, second)
                    // Pointer index zero lifts while index one remains down and
                    // becomes index zero in the next event.
                    h.event(MotionEvent.ACTION_POINTER_UP, first, second)
                    h.event(MotionEvent.ACTION_UP, second)
                } else h.event(MotionEvent.ACTION_UP, first)
                index += 2
            }
            assertEquals("Routing must preserve the actual taps before any engine correction", raw, h.typed.joinToString(""))
        } finally { h.finish() }
    }

    @Test
    fun actualMistypedSpellingReachesTheEngineUnaltered() {
        val raw = "shuagkashuangdai"
        val h = Harness(360, 188, numberRow = false)
        try {
            raw.forEach { h.tap(h.finger(7, it.uppercaseChar().toString())) }
            assertEquals("A hit-target test must not fake correction by rewriting the input", raw, h.typed.joinToString(""))
        } finally { h.finish() }
    }

    @Test
    fun slidingToAnotherLetterCommitsOnlyTheFinalSelectionAtEitherHeight() {
        for (numberRow in listOf(true, false)) {
            val h = Harness(359, if (numberRow) 231 else 185, numberRow)
            try {
                h.event(MotionEvent.ACTION_DOWN, h.finger(23, "G"))
                h.event(MotionEvent.ACTION_MOVE, h.finger(23, "H"))
                h.event(MotionEvent.ACTION_MOVE, h.finger(23, "J"))
                h.event(MotionEvent.ACTION_UP, h.finger(23, "J"))
                assertEquals(listOf("j"), h.typed)
            } finally { h.finish() }
        }
    }
}
