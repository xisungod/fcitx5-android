/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.TextView
import org.fcitx.fcitx5.android.input.voice.InlineDictationBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InlineDictationBarTest {
    private val accent = Color.rgb(0, 120, 255)

    private fun bar() = InlineDictationBar(
        RuntimeEnvironment.getApplication(), Color.WHITE, accent, Color.WHITE, null
    ).also { layout(it) }

    private fun layout(bar: InlineDictationBar) {
        val density = bar.resources.displayMetrics.density
        val width = (360 * density).toInt()
        val height = (52 * density).toInt()
        bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        bar.layout(0, 0, width, height)
    }

    private fun draw(bar: InlineDictationBar): Bitmap {
        layout(bar)
        val bitmap = Bitmap.createBitmap(bar.width, bar.height, Bitmap.Config.ARGB_8888)
        bar.draw(Canvas(bitmap))
        return bitmap
    }

    private fun label(bar: InlineDictationBar) = bar.findViewWithTag<TextView>("inline_dictation_label")
    private fun done(bar: InlineDictationBar) = bar.findViewWithTag<TextView>("inline_dictation_done")

    @Test fun listeningShowsTheStateAndOneWayToFinish() {
        val bar = bar()
        bar.render(InlineDictationBar.Mode.Listening, "正在聆听", "完成")
        layout(bar)
        assertEquals("正在聆听", label(bar).text.toString())
        assertEquals(1, label(bar).maxLines)
        assertEquals(View.VISIBLE, done(bar).visibility)
        assertEquals("完成", done(bar).text.toString())
        assertEquals("正在聆听, 完成", bar.contentDescription.toString())
        assertTrue("The whole strip is the button", bar.isClickable && bar.isEnabled)
        assertFalse("The pill never takes the tap away from the strip", done(bar).isClickable)
        assertTrue(done(bar).right <= bar.width && label(bar).right <= done(bar).left)
    }

    @Test fun finishingCannotBeTappedAgain() {
        val bar = bar()
        bar.render(InlineDictationBar.Mode.Finishing, "正在整理", "完成")
        assertFalse(bar.isEnabled)
        assertEquals(InlineDictationBar.Mode.Finishing, bar.mode)
    }

    @Test fun noticeGivesTheMessageTheRoomOfTheLevelDisplay() {
        val bar = bar()
        bar.render(InlineDictationBar.Mode.Listening, "正在聆听", "完成")
        layout(bar)
        val listeningWidth = label(bar).width
        bar.render(InlineDictationBar.Mode.Notice, "需要麦克风权限才能听写。请允许后回到键盘，再点一次麦克风。", "知道了")
        layout(bar)
        assertEquals(2, label(bar).maxLines)
        assertTrue("A long reason is not squeezed into the short state label",
            label(bar).width > listeningWidth * 2)
        assertTrue(bar.isEnabled)
        assertEquals("知道了", done(bar).text.toString())
        assertTrue(done(bar).right <= bar.width)
    }

    @Test fun loudnessIsDrawnWithTheAccentColourAndSilenceIsNot() {
        val bar = bar()
        bar.animationsAllowed = { false }
        bar.render(InlineDictationBar.Mode.Listening, "正在聆听", "完成")
        bar.setLevel(0f)
        val quiet = accentPixels(draw(bar))
        repeat(30) {
            bar.setLevel(1f)
            draw(bar)
        }
        val loud = accentPixels(draw(bar))
        assertTrue("Loud input must cover visibly more of the strip ($quiet -> $loud)", loud > quiet * 1.3)
    }

    @Test fun badLevelsAndEveryModeDrawWithoutMotionAllowed() {
        val bar = bar()
        bar.animationsAllowed = { false }
        for (mode in InlineDictationBar.Mode.values()) {
            bar.render(mode, "状态", if (mode == InlineDictationBar.Mode.Notice) "" else "完成")
            bar.setLevel(Float.NaN)
            bar.setLevel(7f)
            bar.setLevel(-3f)
            draw(bar)
        }
        assertEquals(View.GONE, done(bar).visibility)
        assertEquals("状态", bar.contentDescription.toString())
    }

    /** Pixels left of the label that carry the accent hue: the level display only. */
    private fun accentPixels(bitmap: Bitmap): Int {
        var count = 0
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (pixel in pixels) {
            if (Color.alpha(pixel) > 40 && Color.blue(pixel) > Color.red(pixel) + 60) count++
        }
        return count
    }
}
