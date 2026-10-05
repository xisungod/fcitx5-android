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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyLegendInkTest {
    @Test
    fun plainLightGlyphHasNoDarkContourOnAColouredKeyFace() {
        val text = AutoScaleTextView(RuntimeEnvironment.getApplication()).apply {
            this.text = "f"
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        text.measure(View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
        text.layout(0, 0, 80, 80)
        val background = Color.rgb(20, 110, 130)
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        text.draw(canvas)
        var lightPixels = 0
        for (y in 0 until 80) for (x in 0 until 80) {
            val pixel = bitmap.getPixel(x, y)
            assertTrue("The glyph must never paint a dark stroke", Color.red(pixel) >= Color.red(background) &&
                Color.green(pixel) >= Color.green(background) && Color.blue(pixel) >= Color.blue(background))
            if (Color.red(pixel) > 230 && Color.green(pixel) > 230 && Color.blue(pixel) > 230) lightPixels++
        }
        assertTrue("The original light fill remains visible", lightPixels > 15)
        assertEquals(0f, text.contrastOutlineWidth)
        assertEquals(Color.WHITE, text.currentTextColor)
        bitmap.recycle()
    }
}
