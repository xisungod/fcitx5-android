/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Application
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightEditor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w600dp-h900dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class KeyboardHeightEditorTest {
    private data class Finger(val id: Int, val rawY: Float, val rawX: Float = 300f)

    private class Harness {
        private val context = RuntimeEnvironment.getApplication()
        private val parent = FrameLayout(context)
        val previews = mutableListOf<Int>()
        var doneCount = 0
        var cancelCount = 0
        var resetCount = 0
        var height = 300
            private set
        lateinit var editor: KeyboardHeightEditor
            private set
        val handle get() = editor.getChildAt(0) as TextView
        val buttons get() = editor.getChildAt(1) as LinearLayout
        val handleRawY get() = editor.top + handle.top + handle.height / 2f
        private var eventTime = 0L
        private var downTime = 0L

        init {
            editor = KeyboardHeightEditor(
                context,
                ThemePreset.XuancaiBlackV09,
                onPreview = {
                    previews += it
                    // Match the real host: the callback changes both the reported height and
                    // the editor's screen position before the next MotionEvent arrives.
                    updateHeight(it)
                },
                onDone = { doneCount++ },
                onCancel = { cancelCount++ },
                onReset = { resetCount++ }
            )
            parent.addView(editor, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM
            ))
            show()
        }

        fun show(height: Int = 300, minimum: Int = 200, maximum: Int = 500) {
            editor.show(height, minimum, maximum)
            updateHeight(height)
            previews.clear()
        }

        fun updateHeight(height: Int) {
            this.height = height
            editor.updateHeight(height)
            editor.layoutParams = (editor.layoutParams as FrameLayout.LayoutParams).apply {
                this.height = height
            }
            parent.measure(
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY)
            )
            parent.layout(0, 0, 600, 900)
        }

        fun event(action: Int, vararg fingers: Finger) {
            eventTime += 10L
            if ((action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_DOWN) {
                downTime = eventTime
            }
            val properties = fingers.map { finger ->
                MotionEvent.PointerProperties().apply {
                    id = finger.id
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }.toTypedArray()
            val coordinates = fingers.map { finger ->
                MotionEvent.PointerCoords().apply {
                    x = finger.rawX
                    y = finger.rawY
                    pressure = 1f
                    size = 1f
                }
            }.toTypedArray()
            val event = MotionEvent.obtain(
                downTime, eventTime, action, fingers.size, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            // offsetLocation changes local coordinates while preserving physical rawY.
            event.offsetLocation(-editor.left.toFloat(), -editor.top.toFloat())
            try {
                assertTrue("The editor must consume every touch in its overlay", editor.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }

        fun assertNoButtonCallbacks() {
            assertEquals(0, doneCount)
            assertEquals(0, cancelCount)
            assertEquals(0, resetCount)
        }
    }

    @Test
    fun dragKeepsItsOriginalScreenBaselineWhenPreviewMovesTheEditorAndUpKeepsItOpen() {
        val h = Harness()
        assertEquals(48, h.handle.height)
        assertEquals(h.handle.context.getString(R.string.keyboard_height_drag_hint), h.handle.text.toString())
        val startY = h.handleRawY
        h.event(MotionEvent.ACTION_DOWN, Finger(7, startY))
        h.event(MotionEvent.ACTION_MOVE, Finger(7, startY - 40f))
        assertEquals(340, h.height)
        assertEquals("The host really moved during the callback", 560, h.editor.top)
        h.event(MotionEvent.ACTION_MOVE, Finger(7, startY - 60f))
        assertEquals("Later movement must still use DOWN's screen Y and height", 360, h.height)
        val previewsBeforeUp = h.previews.toList()
        h.event(MotionEvent.ACTION_UP, Finger(7, startY - 60f))
        assertEquals(previewsBeforeUp, h.previews)
        assertEquals(View.VISIBLE, h.editor.visibility)
        h.assertNoButtonCallbacks()
    }

    @Test
    fun dragClampsBothLimitsAndCancelRestoresTheHeightAtThisDragStart() {
        val h = Harness()
        h.updateHeight(360)
        val startY = h.handleRawY
        h.event(MotionEvent.ACTION_DOWN, Finger(7, startY))
        h.event(MotionEvent.ACTION_MOVE, Finger(7, startY + 1000f))
        assertEquals(200, h.height)
        h.event(MotionEvent.ACTION_MOVE, Finger(7, startY - 1000f))
        assertEquals(500, h.height)
        h.event(MotionEvent.ACTION_CANCEL, Finger(7, startY - 1000f))
        assertEquals("CANCEL restores this drag, rather than the editor's initial height", 360, h.height)
        assertEquals(360, h.previews.last())
        assertEquals(View.VISIBLE, h.editor.visibility)
        h.assertNoButtonCallbacks()
    }

    @Test
    fun secondaryFingerCannotClickTheToolbarOrTakeOverAfterThePrimaryFingerLeaves() {
        val h = Harness()
        val startY = h.handleRawY
        val secondary = Finger(23, 876f, 500f) // Inside the bottom Done button.
        h.event(MotionEvent.ACTION_DOWN, Finger(7, startY))
        h.event(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            Finger(7, startY), secondary
        )
        h.event(MotionEvent.ACTION_MOVE, Finger(7, startY - 30f), secondary)
        assertEquals(330, h.height)
        h.event(MotionEvent.ACTION_POINTER_UP, Finger(7, startY - 30f), secondary)
        val previewsAfterPrimaryUp = h.previews.toList()
        h.event(MotionEvent.ACTION_MOVE, secondary.copy(rawY = startY - 200f))
        h.event(MotionEvent.ACTION_UP, secondary.copy(rawY = startY - 200f))
        assertEquals("A remaining finger cannot inherit the drag", previewsAfterPrimaryUp, h.previews)
        assertEquals(330, h.height)
        assertEquals(View.VISIBLE, h.editor.visibility)
        h.assertNoButtonCallbacks()
    }

    @Test
    fun hideAndShowDiscardTheOldPointerAndTheNextDragUsesTheNewHeight() {
        val h = Harness()
        val oldStartY = h.handleRawY
        h.event(MotionEvent.ACTION_DOWN, Finger(7, oldStartY))
        h.event(MotionEvent.ACTION_MOVE, Finger(7, oldStartY - 20f))
        h.editor.hide()
        assertEquals(View.GONE, h.editor.visibility)
        h.show(height = 360, minimum = 250, maximum = 450)
        h.event(MotionEvent.ACTION_MOVE, Finger(7, oldStartY - 100f))
        h.event(MotionEvent.ACTION_UP, Finger(7, oldStartY - 100f))
        assertTrue("An old touch cannot change a reopened editor", h.previews.isEmpty())
        assertEquals(360, h.height)
        val newStartY = h.handleRawY
        h.event(MotionEvent.ACTION_DOWN, Finger(23, newStartY))
        h.event(MotionEvent.ACTION_MOVE, Finger(23, newStartY - 20f))
        h.event(MotionEvent.ACTION_UP, Finger(23, newStartY - 20f))
        assertEquals(380, h.height)
        assertEquals(View.VISIBLE, h.editor.visibility)
        h.assertNoButtonCallbacks()
    }

    @Test
    fun accessibilityAdjustsByEightDpAndRespectsBothHeightLimits() {
        val h = Harness()
        assertTrue(h.handle.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertEquals(308, h.height)
        assertTrue(h.handle.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
        assertEquals(300, h.height)
        h.updateHeight(496)
        h.handle.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
        assertEquals(500, h.height)
        h.handle.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
        assertEquals(500, h.height)
        h.updateHeight(204)
        h.handle.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)
        assertEquals(200, h.height)
        h.handle.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)
        assertEquals(200, h.height)
        assertEquals(View.VISIBLE, h.editor.visibility)
        h.assertNoButtonCallbacks()
    }

    @Test
    fun backdropConsumesTouchesWithoutEditingAndEachToolbarButtonCallsOnlyItsOwnAction() {
        val h = Harness()
        val backdropY = h.editor.top + h.editor.height / 2f
        h.event(MotionEvent.ACTION_DOWN, Finger(7, backdropY))
        h.event(MotionEvent.ACTION_MOVE, Finger(7, backdropY - 20f))
        h.event(MotionEvent.ACTION_UP, Finger(7, backdropY - 20f))
        assertTrue(h.previews.isEmpty())
        assertEquals(300, h.height)
        h.assertNoButtonCallbacks()
        assertEquals(48, h.buttons.height)
        assertEquals(3, h.buttons.childCount)
        assertTrue(h.buttons.getChildAt(0).performClick())
        assertEquals(listOf(1, 0, 0), listOf(h.cancelCount, h.resetCount, h.doneCount))
        assertTrue(h.buttons.getChildAt(1).performClick())
        assertEquals(listOf(1, 1, 0), listOf(h.cancelCount, h.resetCount, h.doneCount))
        assertTrue(h.buttons.getChildAt(2).performClick())
        assertEquals(listOf(1, 1, 1), listOf(h.cancelCount, h.resetCount, h.doneCount))
        assertEquals("Closing is the host callback's responsibility", View.VISIBLE, h.editor.visibility)
        assertTrue(h.previews.isEmpty())
    }
}
