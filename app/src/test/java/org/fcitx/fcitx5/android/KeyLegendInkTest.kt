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
    fun theFixedOutlineKeepsUnchangedLightGlyphsReadableOnBrightFacesWithoutChangingLayout() {
        val text = AutoScaleTextView(RuntimeEnvironment.getApplication()).apply {
            this.text = "f"
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        text.measure(View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
        text.layout(0, 0, 80, 80)
        val baseline = text.baseline
        fun visiblePixels(): Int {
            val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            text.draw(canvas)
            var count = 0
            for (y in 0 until 80) for (x in 0 until 80) {
                if (abs(Color.red(bitmap.getPixel(x, y)) - 255) >= 60) count++
            }
            bitmap.recycle()
            return count
        }
        assertEquals("Matching white fill alone disappears", 0, visiblePixels())
        // The production 0.8dp outline is 1.2px at the review keyboard's hdpi density.
        text.contrastOutlineWidth = 1.2f
        val readablePixels = visiblePixels()
        assertTrue("A thin constant outline protects white glyphs on a bright face; strong pixels=$readablePixels", readablePixels > 15)
        assertEquals(baseline, text.baseline)
        assertEquals(80, text.measuredWidth)
        assertEquals(80, text.measuredHeight)
        assertFalse("Paint-only updates must not request another layout", text.isLayoutRequested)
    }
}
