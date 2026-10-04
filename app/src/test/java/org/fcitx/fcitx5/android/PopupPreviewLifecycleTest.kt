/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Activity
import android.app.Application
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.input.popup.PopupAnimationPolicy
import org.fcitx.fcitx5.android.input.popup.PopupPreviewLifecycle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PopupPreviewLifecycleTest {
    private class Harness {
        // LooperMode.PAUSED does not pause VSync. Automatic VSync advances the clock
        // by itself and can finish an entire animator during a shorter idleFor call.
        init {
            ShadowChoreographer.setPaused(true)
            ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        }
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        private val activity = controller.get()
        private val root = FrameLayout(activity)
        var animationsEnabled = true
        private val previews = mutableListOf<PopupPreviewLifecycle>()

        init {
            activity.setContentView(root)
            controller.visible()
            advance(32) // Deliver the first window traversal before adding animated previews.
            root.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, 400, 200)
        }

        fun preview(): Pair<View, PopupPreviewLifecycle> {
            val view = View(activity).apply { visibility = View.INVISIBLE }
            root.addView(view, FrameLayout.LayoutParams(100, 80))
            view.measure(
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, 100, 80)
            assertTrue(view.isAttachedToWindow)
            val lifecycle = PopupPreviewLifecycle(view, animationsEnabled = { animationsEnabled })
            previews.add(lifecycle)
            return view to lifecycle
        }

        fun advance(milliseconds: Long) {
            var remaining = milliseconds
            while (remaining > 0L) {
                val step = minOf(remaining, 16L)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
                remaining -= step
            }
        }

        fun finish() {
            previews.forEach { it.hideImmediately() }
            controller.pause().stop().destroy()
            ShadowChoreographer.setPaused(false)
        }
    }

    @Test
    fun aTwentyMillisecondTapRemainsReadableThenFinishesItsExit() {
        val h = Harness()
        try {
            val (view, preview) = h.preview()
            var hidden = 0
            preview.show()
            assertEquals(View.VISIBLE, view.visibility)
            assertTrue("The character must be readable on the first frame", view.alpha >= 0.75f)
            h.advance(20)
            preview.release { hidden++ }
            h.advance(40)
            assertEquals("A quick tap must still be readable at 60 ms", View.VISIBLE, view.visibility)
            assertTrue("It must not spend its readable interval barely visible", view.alpha >= 0.75f)
            assertEquals(0, hidden)
            h.advance(40)
            assertEquals("Exit should fade rather than disappear immediately", View.VISIBLE, view.visibility)
            assertTrue("A frame during exit must have intermediate opacity", view.alpha > 0.05f && view.alpha < 0.95f)
            // Completion is frame-quantized, but the short readable hold and fade
            // must still finish by 150 ms rather than leave the previous long tail.
            h.advance(50)
            assertEquals(View.INVISIBLE, view.visibility)
            assertEquals("The released preview must report completion once", 1, hidden)
        } finally { h.finish() }
    }

    @Test
    fun aFiftyMillisecondTapHasAShortReadableTail() {
        val h = Harness()
        try {
            val (view, preview) = h.preview()
            var hidden = 0
            preview.show()
            h.advance(50)
            preview.release { hidden++ }
            h.advance(15)
            assertEquals("The character must remain readable just after release", View.VISIBLE, view.visibility)
            assertTrue(view.alpha >= 0.75f)
            assertEquals(0, hidden)
            var sawExitFade = false
            val samples = mutableListOf<String>()
            repeat(5) { index ->
                h.advance(16)
                samples.add("${81 + index * 16}ms:${view.visibility}/${view.alpha}")
                if (view.visibility == View.VISIBLE && view.alpha > 0.05f && view.alpha < 0.95f) {
                    sawExitFade = true
                }
            }
            assertTrue("A short exit still needs a real fade frame: $samples", sawExitFade)
            h.advance(5)
            assertEquals("The released character must finish its short tail by 150 ms", View.INVISIBLE, view.visibility)
            assertEquals(1, hidden)
        } finally { h.finish() }
    }

    @Test
    fun aQuickTapFadesWithoutChangingThePreviewBoundsOnAnyFrame() {
        val h = Harness()
        try {
            val (view, preview) = h.preview()
            view.translationX = 13.25f
            view.translationY = 17.5f
            fun assertStableBounds() {
                assertEquals("The bubble must never scale horizontally", 1f, view.scaleX, 0f)
                assertEquals("The bubble must never scale vertically", 1f, view.scaleY, 0f)
                val drawn = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
                view.matrix.mapRect(drawn)
                assertEquals(13.25f, drawn.left, 0f)
                assertEquals(17.5f, drawn.top, 0f)
                assertEquals("Fading must not move the rendered right edge", 113.25f, drawn.right, 0f)
                assertEquals("Fading must not move the rendered bottom edge", 97.5f, drawn.bottom, 0f)
            }
            var hidden = 0
            preview.show()
            assertStableBounds()
            assertTrue("Removing scale must preserve the entry fade", view.alpha in 0.75f..0.95f)
            h.advance(20)
            preview.release { hidden++ }
            var sawOpaque = false
            var sawExitFade = false
            repeat(14) {
                h.advance(16)
                assertStableBounds()
                if (view.visibility == View.VISIBLE && view.alpha >= 0.98f) sawOpaque = true
                if (sawOpaque && view.visibility == View.VISIBLE && view.alpha > 0.05f && view.alpha < 0.95f) {
                    sawExitFade = true
                }
            }
            assertTrue("The exit must still fade through intermediate opacity", sawExitFade)
            assertEquals(View.INVISIBLE, view.visibility)
            assertEquals(1, hidden)
        } finally { h.finish() }
    }

    @Test
    fun pressingTheSameKeyDuringItsExitCancelsTheOldHide() {
        val h = Harness()
        try {
            val (view, preview) = h.preview()
            var oldHidden = 0
            var newHidden = 0
            preview.show()
            h.advance(20)
            preview.release { oldHidden++ }
            h.advance(85)
            assertEquals("Re-press must exercise a preview whose exit is still running", View.VISIBLE, view.visibility)
            assertTrue(view.alpha > 0.05f && view.alpha < 0.95f)
            assertEquals(0, oldHidden)
            preview.show()
            assertTrue("Re-press must immediately restore readable opacity", view.alpha >= 0.85f)
            assertEquals(1f, view.scaleX, 0f)
            assertEquals(1f, view.scaleY, 0f)
            h.advance(100)
            assertEquals("The first release must not hide the new press", View.VISIBLE, view.visibility)
            assertEquals(1f, view.alpha, 0.02f)
            assertEquals(1f, view.scaleX, 0f)
            assertEquals(1f, view.scaleY, 0f)
            assertEquals("A cancelled release must not recycle the reused popup", 0, oldHidden)
            preview.release { newHidden++ }
            h.advance(115)
            assertEquals(View.INVISIBLE, view.visibility)
            assertEquals(0, oldHidden)
            assertEquals(1, newHidden)
        } finally { h.finish() }
    }

    @Test
    fun slidingFromOneKeyToAnotherLetsTheOldPreviewExitIndependently() {
        val h = Harness()
        try {
            val (firstView, first) = h.preview()
            val (secondView, second) = h.preview()
            var firstHidden = 0
            var secondHidden = 0
            first.show()
            h.advance(20)
            first.release { firstHidden++ }
            second.show()
            h.advance(160)
            assertEquals(View.INVISIBLE, firstView.visibility)
            assertEquals(1, firstHidden)
            assertEquals("Finishing the old key must not hide the current key", View.VISIBLE, secondView.visibility)
            assertEquals(1f, secondView.alpha, 0.02f)
            assertEquals(0, secondHidden)
            second.release { secondHidden++ }
            h.advance(115)
            assertEquals(View.INVISIBLE, secondView.visibility)
            assertEquals(1, secondHidden)
        } finally { h.finish() }
    }

    @Test
    fun immediateDismissResetsTheViewAndCannotLaterHideItsReuse() {
        val h = Harness()
        try {
            val (view, preview) = h.preview()
            var obsoleteHidden = 0
            preview.show()
            h.advance(20)
            preview.release { obsoleteHidden++ }
            h.advance(85)
            assertEquals("Immediate dismiss must cancel an active exit", View.VISIBLE, view.visibility)
            assertEquals(0, obsoleteHidden)
            preview.hideImmediately()
            assertEquals(View.INVISIBLE, view.visibility)
            assertEquals(1f, view.alpha, 0f)
            assertEquals(1f, view.scaleX, 0f)
            assertEquals(1f, view.scaleY, 0f)
            preview.show()
            h.advance(200)
            assertEquals("An old delayed callback must not close a reused preview", View.VISIBLE, view.visibility)
            assertEquals(0, obsoleteHidden)
            assertEquals(1f, view.alpha, 0.02f)
        } finally { h.finish() }
    }

    @Test
    fun aHeldKeyKeepsItsPreviewUntilItIsReleased() {
        val h = Harness()
        try {
            val (view, preview) = h.preview()
            var hidden = 0
            preview.show()
            h.advance(500)
            assertEquals(View.VISIBLE, view.visibility)
            assertEquals(1f, view.alpha, 0.02f)
            preview.release { hidden++ }
            assertEquals("Release begins a transition instead of a hard cut", View.VISIBLE, view.visibility)
            h.advance(14)
            assertEquals(View.VISIBLE, view.visibility)
            assertEquals("A held character retains its short post-release hold", 1f, view.alpha, 0.02f)
            assertEquals(0, hidden)
            h.advance(56)
            assertEquals(View.VISIBLE, view.visibility)
            assertTrue("A long press must also fade through intermediate opacity", view.alpha > 0.05f && view.alpha < 0.95f)
            h.advance(45)
            assertEquals(View.INVISIBLE, view.visibility)
            assertEquals(1, hidden)
        } finally { h.finish() }
    }

    @Test
    fun duplicateReleaseCompletesOnceWithoutRestartingTheExit() {
        val h = Harness()
        try {
            val (view, preview) = h.preview()
            var originalHidden = 0
            var duplicateHidden = 0
            preview.show()
            h.advance(20)
            preview.release { originalHidden++ }
            h.advance(80)
            preview.release { duplicateHidden++ }
            // At 150 ms the original short exit must be finished. Restarting on
            // the duplicate at 100 ms could not finish its new hold and fade yet.
            h.advance(50)
            assertEquals("A second dismiss must not extend the old preview", View.INVISIBLE, view.visibility)
            assertEquals("Return the released preview to the pool only once", 1, originalHidden)
            assertEquals(0, duplicateHidden)
            preview.release { duplicateHidden++ }
            h.advance(200)
            assertEquals(1, originalHidden)
            assertEquals(0, duplicateHidden)
        } finally { h.finish() }
    }

    @Test
    fun disabledAnimationsShowAndHideImmediatelyWithNoOldCompletion() {
        val h = Harness()
        try {
            val (view, preview) = h.preview()
            var oldHidden = 0
            preview.show()
            h.advance(20)
            preview.release { oldHidden++ }
            h.advance(85)
            h.animationsEnabled = false
            preview.show()
            assertEquals(View.VISIBLE, view.visibility)
            assertEquals(1f, view.alpha, 0f)
            assertEquals(1f, view.scaleX, 0f)
            assertEquals(1f, view.scaleY, 0f)
            var newHidden = 0
            preview.release { newHidden++ }
            assertEquals(View.INVISIBLE, view.visibility)
            assertEquals(1, newHidden)
            h.advance(200)
            assertEquals(0, oldHidden)
            assertEquals("No delayed animated completion may run afterwards", 1, newHidden)
        } finally { h.finish() }
    }

    @Test
    fun pressColourMatchingRequiresAColourFromAnEnabledPressEffect() {
        assertFalse("A disabled effect has no current press colour",
            PopupAnimationPolicy.canMatchPressColor(false, false, false, true))
        assertFalse("The app animation switch stops publishing press colours",
            PopupAnimationPolicy.canMatchPressColor(true, true, false, true))
        assertFalse("Following disabled system animations must reject the old colour",
            PopupAnimationPolicy.canMatchPressColor(true, false, true, false))
        assertTrue("The manual press renderer can still publish colours when it ignores system animation scale",
            PopupAnimationPolicy.canMatchPressColor(true, false, false, false))
        assertTrue("An enabled animated press can supply its colour to the preview",
            PopupAnimationPolicy.canMatchPressColor(true, false, true, true))
    }
}
