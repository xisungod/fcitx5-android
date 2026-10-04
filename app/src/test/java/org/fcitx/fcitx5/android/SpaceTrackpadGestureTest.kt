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
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
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
class SpaceTrackpadGestureTest {
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
        AppPrefs.init(application.getSharedPreferences("space-trackpad", Context.MODE_PRIVATE))
    }

    @After
    fun restoreApplicationInstance() {
        FcitxApplication::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, priorAppInstance)
        }
    }

    private class Harness(
        behavior: SpaceLongPressBehavior,
        legacySwipe: Boolean
    ) {
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
            setting(keyboardPrefs.spaceKeyLongPressBehavior, behavior)
            setting(keyboardPrefs.spaceSwipeMoveCursor, legacySwipe)
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
            keyboard.space.getDrawingRect(bounds)
            keyboard.offsetDescendantRectToMyCoords(keyboard.space, bounds)
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX() + offsetX, bounds.exactCenterY() + offsetY, 0)
            try {
                assertTrue("The real keyboard must handle the space gesture",
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

        fun hold() {
            down()
            // Exercise the attached view's lifecycle coroutine, not performLongClick().
            advance(320)
            assertEquals(KeyAction.SpaceLongPressAction, actions.lastOrNull())
        }

        fun move(dx: Float, dy: Float) {
            offsetX += dx
            offsetY += dy
            touch(MotionEvent.ACTION_MOVE)
        }

        fun up() = touch(MotionEvent.ACTION_UP)

        fun cancel() = touch(MotionEvent.ACTION_CANCEL)

        fun finish() {
            keyboard.onDetach()
            controller.pause().stop().destroy()
            restore.asReversed().forEach { it() }
        }
    }

    private fun withKeyboard(
        behavior: SpaceLongPressBehavior = SpaceLongPressBehavior.MoveCursor,
        legacySwipe: Boolean = false,
        test: (Harness) -> Unit
    ) {
        val harness = Harness(behavior, legacySwipe)
        try {
            test(harness)
        } finally {
            harness.finish()
        }
    }

    private fun sym(value: Int) = KeyAction.SymAction(KeySym(value))
    private val space get() = sym(FcitxKeyMapping.FcitxKey_space)
    private val right get() = sym(FcitxKeyMapping.FcitxKey_Right)
    private val left get() = sym(FcitxKeyMapping.FcitxKey_Left)
    private val up get() = sym(FcitxKeyMapping.FcitxKey_Up)
    private val down get() = sym(FcitxKeyMapping.FcitxKey_Down)

    @Test
    fun normalTapAndDriftBeforeLongPressTypeSpaceWithoutMovingTheCursor() = withKeyboard { h ->
        h.down()
        h.advance(40)
        h.up()
        assertEquals(listOf(space), h.actions)

        h.actions.clear()
        h.down()
        h.advance(50)
        h.move(25f, -15f)
        assertTrue("Crossing the movement threshold before the hold must not send arrows", h.actions.isEmpty())
        h.advance(40)
        h.up()
        assertEquals(listOf(space), h.actions)
        h.advance(350)
        assertEquals("A released tap must not trigger a delayed long press", listOf(space), h.actions)
    }

    @Test
    fun oneHeldSpaceCanMoveRightUpLeftAndDownWithoutCommittingSpace() = withKeyboard { h ->
        // Hold mode must work even when the independent legacy swipe preference is off.
        h.hold()
        h.move(20f, 0f)
        h.move(0f, -30f)
        h.move(-30f, 0f)
        h.move(0f, 40f)
        h.up()
        assertEquals(listOf(KeyAction.SpaceLongPressAction) +
            List(2) { right } + List(3) { up } + List(3) { left } + List(4) { down }, h.actions)
    }

    @Test
    fun diagonalMovementChoosesTheDominantAxisAndDiscardsOrthogonalJitter() = withKeyboard { h ->
        h.hold()
        h.move(11f, -25f)
        h.move(7f, -5f)
        h.move(7f, -10f)
        h.move(7f, -10f)
        assertEquals("Vertical motion must not accumulate stray horizontal arrows",
            listOf(KeyAction.SpaceLongPressAction) + List(5) { up }, h.actions)

        h.move(20f, 20f)
        assertEquals("A tied diagonal uses one axis, without sending both directions",
            listOf(KeyAction.SpaceLongPressAction) + List(5) { up } + List(2) { right }, h.actions)
        h.move(0f, 10f)
        h.up()
        assertEquals("Discarded diagonal movement must not leak into the next vertical step",
            listOf(KeyAction.SpaceLongPressAction) + List(5) { up } + List(2) { right } + down,
            h.actions)
    }

    @Test
    fun activatedTrackpadKeepsItsSpaceTargetAfterDraggingOutsideTheKey() = withKeyboard { h ->
        h.hold()
        val verticalDistance = ((h.keyboard.space.height / 10) + 2) * 10
        val horizontalDistance = ((h.keyboard.space.width / 10) + 2) * 10
        h.move(0f, -verticalDistance.toFloat())
        h.move(horizontalDistance.toFloat(), 0f)
        h.up()
        assertEquals("Crossing letters and adjacent keys must remain a cursor gesture",
            listOf(KeyAction.SpaceLongPressAction) +
                List(verticalDistance / 10) { up } + List(horizontalDistance / 10) { right },
            h.actions)
    }

    @Test
    fun cancelEndsTheHeldGestureAndTheNextSpaceTapStartsFresh() = withKeyboard { h ->
        h.hold()
        h.move(20f, 0f)
        h.cancel()
        h.advance(350)
        assertEquals(listOf(KeyAction.SpaceLongPressAction, right, right), h.actions)

        h.actions.clear()
        h.down()
        h.advance(40)
        h.up()
        assertEquals(listOf(space), h.actions)

        h.actions.clear()
        h.down()
        h.advance(40)
        h.cancel()
        h.advance(350)
        assertTrue("Cancelling before activation must cancel the pending long press", h.actions.isEmpty())
        h.down()
        h.up()
        assertEquals(listOf(space), h.actions)
    }

    @Test
    fun legacySwipeRemainsHorizontalAndCanBeDisabledOnAnExistingKeyboard() =
        withKeyboard(SpaceLongPressBehavior.None, legacySwipe = true) { h ->
            h.down()
            h.move(20f, -20f)
            h.up()
            assertEquals("Legacy swipe must remain immediate and horizontal", listOf(right, right), h.actions)

            AppPrefs.getInstance().keyboard.spaceSwipeMoveCursor.setValue(false)
            shadowOf(Looper.getMainLooper()).idle()
            h.actions.clear()
            h.down()
            h.move(20f, -20f)
            h.up()
            assertEquals("Disabling legacy swipe restores an ordinary space gesture", listOf(space), h.actions)
        }
}
