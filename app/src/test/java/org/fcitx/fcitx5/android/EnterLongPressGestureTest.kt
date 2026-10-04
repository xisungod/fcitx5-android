/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w600dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class EnterLongPressGestureTest {
    private var priorAppInstance: FcitxApplication? = null

    @Before
    fun prepareWithoutStartingTheNativeEngine() {
        val instanceField = FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
        }
        priorAppInstance = instanceField.get(null) as? FcitxApplication
        val application = RuntimeEnvironment.getApplication()
        val uiApplication = FcitxApplication()
        ReflectionHelpers.callInstanceMethod<Void>(uiApplication, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, application))
        instanceField.set(null, uiApplication)
        AppPrefs.init(application.getSharedPreferences("enter-long-press", Context.MODE_PRIVATE))
    }

    @After
    fun restoreApplicationInstance() {
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, priorAppInstance)
        }
    }

    private class Harness {
        private val restore = mutableListOf<() -> Unit>()
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        private val activity = controller.get()
        val keyboard: TextKeyboard
        val actions = mutableListOf<KeyAction>()
        private var downTime = 0L
        private var offsetX = 0f
        private var offsetY = 0f

        init {
            val keyboardPrefs = AppPrefs.getInstance().keyboard
            setting(keyboardPrefs.longPressDelay, 300)
            setting(keyboardPrefs.popupOnKeyPress, false)
            val themePrefs = ThemeManager.prefs
            setting(themePrefs.pressEffect, false)
            setting(themePrefs.idleBreathing, false)
            setting(themePrefs.keyMotionEffect, ThemePrefs.KeyMotionEffect.Off)
            activity.setTheme(R.style.Theme_InputViewTheme)
            keyboard = TextKeyboard(activity, ThemePreset.XuancaiBlackV09)
            activity.setContentView(keyboard)
            controller.visible()
            advance(32)
            keyboard.measure(
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(450, View.MeasureSpec.EXACTLY))
            keyboard.layout(0, 0, 600, 450)
            keyboard.keyActionListener = KeyActionListener { action, _ -> actions.add(action) }
        }

        private fun <T : Any> setting(preference: ManagedPreference<T>, value: T) {
            val existed = preference.sharedPreferences.contains(preference.key)
            val oldValue = preference.getValue()
            restore.add {
                if (existed) preference.setValue(oldValue)
                else assertTrue("Remove fixture-only preference ${preference.key}",
                    preference.sharedPreferences.edit().remove(preference.key).commit())
            }
            preference.setValue(value)
        }

        fun advance(milliseconds: Long) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
        }

        private fun touch(action: Int) {
            val bounds = Rect()
            keyboard.`return`.getDrawingRect(bounds)
            keyboard.offsetDescendantRectToMyCoords(keyboard.`return`, bounds)
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX() + offsetX, bounds.exactCenterY() + offsetY, 0)
            try {
                assertTrue("The real keyboard must handle the return-key gesture",
                    keyboard.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }

        fun pointerEvent(action: Int, vararg pointerIds: Int) {
            if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) {
                downTime = SystemClock.uptimeMillis()
            }
            val bounds = Rect()
            keyboard.`return`.getDrawingRect(bounds)
            keyboard.offsetDescendantRectToMyCoords(keyboard.`return`, bounds)
            val properties = pointerIds.map { pointerId ->
                MotionEvent.PointerProperties().apply {
                    id = pointerId
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }.toTypedArray()
            val coordinates = pointerIds.map {
                MotionEvent.PointerCoords().apply {
                    x = bounds.exactCenterX()
                    y = bounds.exactCenterY()
                    pressure = 1f
                    size = 1f
                }
            }.toTypedArray()
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                pointerIds.size, properties, coordinates, 0, 0, 1f, 1f,
                0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try {
                assertTrue("The real keyboard must handle each return-key pointer",
                    keyboard.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }

        fun down() {
            downTime = SystemClock.uptimeMillis()
            offsetX = 0f
            offsetY = 0f
            touch(MotionEvent.ACTION_DOWN)
        }

        fun move(dx: Float, dy: Float) {
            offsetX += dx
            offsetY += dy
            touch(MotionEvent.ACTION_MOVE)
        }

        fun up() = touch(MotionEvent.ACTION_UP)

        fun cancel() = touch(MotionEvent.ACTION_CANCEL)

        fun tap() {
            down()
            advance(40)
            up()
        }

        fun finish() {
            keyboard.onDetach()
            controller.pause().stop().destroy()
            restore.asReversed().forEach { it() }
        }
    }

    private fun withKeyboard(test: (Harness) -> Unit) {
        val harness = Harness()
        try {
            test(harness)
        } finally {
            harness.finish()
        }
    }

    private val returnAction get() = KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Return))
    private val newlineAction get() = KeyAction.CommitAction("\n")

    @Test
    fun shortTapKeepsTheOriginalReturnActionForReturnAndSendIcons() = withKeyboard { h ->
        for (icon in listOf(R.drawable.ic_baseline_keyboard_return_24, R.drawable.ic_send_swoosh_24)) {
            h.keyboard.onReturnDrawableUpdate(icon)
            h.actions.clear()
            h.down()
            h.advance(40)
            assertTrue("Return must wait for release during a short tap", h.actions.isEmpty())
            h.up()
            assertEquals(listOf(returnAction), h.actions)
            h.advance(350)
            assertEquals("A short tap must cancel its pending newline", listOf(returnAction), h.actions)
        }
    }

    @Test
    fun longPressCommitsOneLiteralNewlineAndConsumesTheRelease() = withKeyboard { h ->
        h.keyboard.onReturnDrawableUpdate(R.drawable.ic_send_swoosh_24)
        h.down()
        h.advance(250)
        assertTrue("Holding below the configured delay must not send or insert text", h.actions.isEmpty())
        // Run the attached view's actual long-press coroutine.
        h.advance(70)
        assertEquals(listOf(newlineAction), h.actions)
        h.up()
        h.advance(350)
        assertEquals("UP after a newline must not dispatch Return or a second newline",
            listOf(newlineAction), h.actions)
    }

    @Test
    fun sustainedHoldNeverRepeatsAndASeparateHoldCanInsertTheNextNewline() = withKeyboard { h ->
        h.down()
        h.advance(320)
        assertEquals(listOf(newlineAction), h.actions)
        h.advance(1200)
        h.move(2f, -2f)
        h.advance(1200)
        assertEquals("Remaining on the key must not repeat newline insertion", listOf(newlineAction), h.actions)
        h.up()
        assertEquals(listOf(newlineAction), h.actions)

        h.down()
        h.advance(320)
        h.up()
        assertEquals("Only a separate long press may insert another newline",
            listOf(newlineAction, newlineAction), h.actions)
    }

    @Test
    fun twoFingersHoldingReturnNeverSendOnEitherReleaseOrder() = withKeyboard { h ->
        for (firstPointerUp in listOf(true, false)) {
            h.actions.clear()
            h.pointerEvent(MotionEvent.ACTION_DOWN, 7)
            h.advance(40)
            h.pointerEvent(MotionEvent.ACTION_POINTER_DOWN or
                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 7, 23)
            h.advance(320)
            assertEquals(listOf(newlineAction), h.actions)

            val releaseIndex = if (firstPointerUp) 0 else 1
            h.pointerEvent(MotionEvent.ACTION_POINTER_UP or
                (releaseIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 7, 23)
            assertEquals("The first release must preserve the consumed long press",
                listOf(newlineAction), h.actions)
            h.pointerEvent(MotionEvent.ACTION_UP, if (firstPointerUp) 23 else 7)
            h.advance(350)
            assertEquals("The remaining finger must not dispatch Return; primary released first=$firstPointerUp",
                listOf(newlineAction), h.actions)
        }
    }

    @Test
    fun secondFingerAfterNewlineCannotRearmTheHoldOrSendAndANewTapStillWorks() = withKeyboard { h ->
        for (firstPointerUp in listOf(true, false)) {
            h.actions.clear()
            h.pointerEvent(MotionEvent.ACTION_DOWN, 7)
            h.advance(320)
            assertEquals(listOf(newlineAction), h.actions)
            h.pointerEvent(MotionEvent.ACTION_POINTER_DOWN or
                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 7, 23)
            h.advance(350)
            assertEquals("An extra finger must not schedule another newline", listOf(newlineAction), h.actions)

            val releaseIndex = if (firstPointerUp) 0 else 1
            h.pointerEvent(MotionEvent.ACTION_POINTER_UP or
                (releaseIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 7, 23)
            h.pointerEvent(MotionEvent.ACTION_UP, if (firstPointerUp) 23 else 7)
            assertEquals("Both releases must stay consumed; primary released first=$firstPointerUp",
                listOf(newlineAction), h.actions)
            h.tap()
            assertEquals("A new single-finger tap must retain ordinary Return behavior",
                listOf(newlineAction, returnAction), h.actions)
        }
    }

    @Test
    fun leavingTheKeyBeforeActivationCancelsTheHoldAndTheNextTapWorks() = withKeyboard { h ->
        h.down()
        h.advance(50)
        h.move(-h.keyboard.`return`.width.toFloat(), 0f)
        h.advance(350)
        assertTrue("Dragging off Return must cancel its pending newline", h.actions.isEmpty())
        h.up()
        assertTrue("The outside release must not dispatch Return or an adjacent key", h.actions.isEmpty())
        h.tap()
        assertEquals(listOf(returnAction), h.actions)
    }

    @Test
    fun cancelBeforeOrAfterActivationLeavesTheNextReturnTapFresh() = withKeyboard { h ->
        h.down()
        h.advance(50)
        h.cancel()
        h.advance(350)
        assertTrue("CANCEL must stop the pending long-press coroutine", h.actions.isEmpty())
        h.tap()
        assertEquals(listOf(returnAction), h.actions)

        h.actions.clear()
        h.down()
        h.advance(320)
        assertEquals(listOf(newlineAction), h.actions)
        h.cancel()
        h.advance(350)
        assertEquals("CANCEL after activation must not insert or send anything else",
            listOf(newlineAction), h.actions)
        h.tap()
        assertEquals(listOf(newlineAction, returnAction), h.actions)
    }
}
