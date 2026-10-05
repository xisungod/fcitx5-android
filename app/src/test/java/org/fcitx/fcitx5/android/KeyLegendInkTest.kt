/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.KeyLegendTypeface
import org.fcitx.fcitx5.android.input.popup.PopupEntryUi
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
    fun bundledKeyboardFontHasLatinDigitsAndPopupReuseRestoresTheSystemFontForChinese() {
        val context = RuntimeEnvironment.getApplication()
        val system = Typeface.DEFAULT
        val face = KeyLegendTypeface.resolve(context, ThemePreset.Sam, "q", system)
        assertNotEquals("The bundled font must load rather than silently use a fallback", system, face)
        val paint = Paint().apply { typeface = face }
        for (glyph in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") {
            assertTrue("Bundled font is missing $glyph", paint.hasGlyph(glyph.toString()))
        }
        assertSame(system, KeyLegendTypeface.resolve(context, ThemePreset.Sam, "中", system))
        assertSame(system, KeyLegendTypeface.resolve(context, ThemePreset.MaterialLight, "q", system))
        val popup = PopupEntryUi(context, ThemePreset.Sam, 52, 8f)
        val original = popup.textView.typeface
        popup.setText("q")
        assertEquals(face, popup.textView.typeface)
        popup.setText("中")
        assertEquals(original, popup.textView.typeface)
        popup.setText("Q")
        assertEquals(face, popup.textView.typeface)
        assertEquals("Q", popup.textView.text.toString())
    }

    @Test
    fun roundedKeyboardLettersRemainInsideNarrowKeysWithoutChangingTheirCase() {
        val context = RuntimeEnvironment.getApplication()
        for (glyph in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") {
            val text = AutoScaleTextView(context).apply {
                this.text = glyph.toString()
                textSize = 22f
                typeface = KeyLegendTypeface.resolve(context, ThemePreset.Sam, text, typeface)
                scaleMode = AutoScaleTextView.Mode.Proportional
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }
            val width = 26
            val height = 52
            val margin = 8
            text.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            text.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width + 2 * margin, height + 2 * margin, Bitmap.Config.ARGB_8888)
            text.draw(Canvas(bitmap).apply { translate(margin.toFloat(), margin.toFloat()) })
            var inkPixels = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 10) {
                    assertTrue("$glyph must fit the configured key width", x in margin until margin + width)
                    assertTrue("$glyph must fit the key height", y in margin until margin + height)
                    inkPixels++
                }
            }
            assertTrue("$glyph must render visible ink", inkPixels > 10)
            assertEquals(glyph.toString(), text.text.toString())
            bitmap.recycle()
        }
    }

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
