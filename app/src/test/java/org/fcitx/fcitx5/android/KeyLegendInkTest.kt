/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Gravity
import android.view.View
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.KeyLegendInk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyLegendInkTest {
    @Test
    fun aFadingBrightFaceRestoresInkGraduallyAndRepressHasNoStaleTransition() {
        val original = Color.WHITE
        val samples = (0..60).map { KeyLegendInk.color(0.65f - it * 0.005f, original) }
        assertEquals(0xFF161A1E.toInt(), samples.first())
        assertEquals(original, samples.last())
        assertTrue("A fading face must show multiple intermediate ink levels", samples.distinct().size > 30)
        for ((before, after) in samples.zipWithNext()) {
            assertTrue(Color.red(after) >= Color.red(before))
            assertTrue("No one-frame black/white jump", Color.red(after) - Color.red(before) <= 9)
        }
        // A new press is evaluated from its own light, without a previous fade animator.
        assertEquals(samples.first(), KeyLegendInk.color(0.65f, original))
        assertEquals(original, KeyLegendInk.color(0f, original))
    }

    @Test
    fun theFixedOutlineKeepsAGrayTransitionReadableWithoutChangingGlyphLayout() {
        val text = AutoScaleTextView(RuntimeEnvironment.getApplication()).apply {
            this.text = "f"
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(128, 128, 128))
        }
        text.measure(View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
        text.layout(0, 0, 80, 80)
        val baseline = text.baseline
        fun visiblePixels(): Int {
            val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(128, 128, 128))
            text.draw(canvas)
            var count = 0
            for (y in 0 until 80) for (x in 0 until 80) {
                if (abs(Color.red(bitmap.getPixel(x, y)) - 128) >= 60) count++
            }
            bitmap.recycle()
            return count
        }
        assertEquals("Matching gray fill alone disappears", 0, visiblePixels())
        // The production 0.8dp outline is 1.2px at the review keyboard's hdpi density.
        text.contrastOutlineWidth = 1.2f
        val readablePixels = visiblePixels()
        assertTrue("A thin constant outline protects the gray crossfade; strong pixels=$readablePixels", readablePixels > 15)
        assertEquals(baseline, text.baseline)
        assertEquals(80, text.measuredWidth)
        assertEquals(80, text.measuredHeight)
        assertFalse("Paint-only updates must not request another layout", text.isLayoutRequested)
    }
}
