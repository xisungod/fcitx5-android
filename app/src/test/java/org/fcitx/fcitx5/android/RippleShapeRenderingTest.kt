/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.input.keyboard.IdleBreathing
import org.fcitx.fcitx5.android.input.keyboard.PressEffect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RippleShapeRenderingTest {
    private class Harness(shape: ThemePrefs.RippleShape? = null, expansion: Int = 400,
        hold: Int = 40, brightness: Int = 100) {
        private val controller = Robolectric.buildActivity(Activity::class.java).setup()
        private val activity = controller.get()
        private val host = View(activity)
        var now = 0L
        // A single colour deliberately rules out a palette change as the source
        // of the visible difference. Null exercises the actual constructor default.
        val effect = if (shape == null) PressEffect(host, intArrayOf(0xff00eaff.toInt()), true,
            100, expansion, 520, 0, true, false, IdleBreathing(), intArrayOf(Color.CYAN),
            clock = { now }, ignitionTimeMs = 40, keyHoldTimeMs = 50, keyRetreatTimeMs = 100,
            keySurfaceEffects = true, exactKeyShape = true, holdWhilePressed = true, dimExit = true,
            keyCornerRadius = 5f, animationsAllowed = { true }, glowBrightnessPercent = brightness,
            waveHoldTimeMs = hold)
        else PressEffect(host, intArrayOf(0xff00eaff.toInt()), true,
            100, expansion, 520, 0, true, false, IdleBreathing(), intArrayOf(Color.CYAN),
            clock = { now }, ignitionTimeMs = 40, keyHoldTimeMs = 50, keyRetreatTimeMs = 100,
            keySurfaceEffects = true, exactKeyShape = true, holdWhilePressed = true, dimExit = true,
            keyCornerRadius = 5f, animationsAllowed = { true }, glowBrightnessPercent = brightness,
            waveHoldTimeMs = hold, rippleShape = shape)

        init {
            ReflectionHelpers.setField(effect, "random", kotlin.random.Random(1401))
            activity.setContentView(FrameLayout(activity).apply {
                addView(host, FrameLayout.LayoutParams(600, 240))
            })
            controller.visible()
            host.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, 600, 240)
            effect.setActive(true)
            assertTrue(host.isAttachedToWindow && host.isShown)
        }

        fun press() {
            now = 0L
            effect.onPress(300f, 100f, Rect(270, 65, 330, 135), true, 8100, 0)
            now = 50L
            effect.onRelease(0)
        }

        fun field(time: Long): Bitmap {
            now = time
            return Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply {
                    drawColor(Color.BLACK)
                    translate(0f, 60f)
                    // Include the candidate extension in the same field image.
                    effect.drawUnder(this)
                }
            }
        }

        fun face(time: Long): Bitmap {
            now = time
            return Bitmap.createBitmap(60, 70, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply {
                    drawColor(Color.BLACK)
                    effect.drawKeySurface(this, 8100, 60, 70)
                }
            }
        }

        fun finish() {
            effect.setActive(false)
            controller.pause().stop().destroy()
        }
    }

    private fun intensity(pixel: Int) = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))

    private fun difference(a: Bitmap, b: Bitmap): Double {
        var total = 0L
        for (y in 0 until a.height) for (x in 0 until a.width)
            total += abs(intensity(a.getPixel(x, y)) - intensity(b.getPixel(x, y)))
        return total.toDouble() / (a.width * a.height)
    }

    private fun save(image: Bitmap, name: String) {
        val destination = File("build/outputs/ripple-shape-checks/$name.png")
        destination.parentFile.mkdirs()
        destination.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun existingPreferenceAndOmittedRendererOptionKeepTheCurrentMistExactly() {
        val storage = RuntimeEnvironment.getApplication().getSharedPreferences("ripple-shape-default", Context.MODE_PRIVATE)
        storage.edit().clear().commit()
        val prefs = ThemePrefs(storage)
        assertEquals(ThemePrefs.RippleShape.SoftMist, prefs.rippleShape.getValue())
        assertFalse("An upgrade must not persist a new choice implicitly", storage.contains("ripple_shape"))
        prefs.rippleShape.setValue(ThemePrefs.RippleShape.IrregularFluid)
        assertEquals(ThemePrefs.RippleShape.IrregularFluid, ThemePrefs(storage).rippleShape.getValue())
        val omitted = Harness()
        val explicit = Harness(ThemePrefs.RippleShape.SoftMist)
        try {
            omitted.press()
            explicit.press()
            for (time in longArrayOf(60, 120, 300, 650, 1000)) {
                val before = omitted.field(time)
                val after = explicit.field(time)
                assertTrue("The default shape must preserve the current field at $time ms", before.sameAs(after))
                before.recycle()
                after.recycle()
            }
        } finally { omitted.finish(); explicit.finish() }
    }

    @Test
    fun fluidChangesVisibleGeometryAndKeepsMovingContinuouslyAfterExpansionFinishes() {
        // Expansion ends at 140ms. All sampled frames are in a constant-brightness
        // hold, so movement cannot be explained by scaling, fade or recolouring.
        val mist = Harness(ThemePrefs.RippleShape.SoftMist, expansion = 100, hold = 1000)
        val fluid = Harness(ThemePrefs.RippleShape.IrregularFluid, expansion = 100, hold = 1000)
        try {
            mist.press()
            fluid.press()
            val mistFrame = mist.field(300)
            val first = fluid.field(300)
            val sameTime = fluid.field(300)
            val adjacent = fluid.field(316)
            val later = fluid.field(600)
            assertTrue("A repeated frame is stable rather than randomly jittering", first.sameAs(sameTime))
            assertTrue("The selected shape changes the visible field, with one fixed colour", difference(mistFrame, first) > 0.8)
            val oneFrame = difference(first, adjacent)
            val flowing = difference(first, later)
            assertTrue("A fluid shape keeps evolving after its expansion has stopped", flowing > 0.5)
            assertTrue("A 16ms step stays much smaller than the accumulated flowing motion", oneFrame < flowing * 0.3)

            var changedBoundary = 0
            var softPixels = 0
            for (y in 0 until first.height) for (x in 0 until first.width) {
                val a = intensity(mistFrame.getPixel(x, y))
                val b = intensity(first.getPixel(x, y))
                if ((a >= 40) != (b >= 40)) changedBoundary++
                if (b in 3..25) softPixels++
                assertTrue("Fluid uses the same bounded exposure as mist", b <= 225)
                if (y < 58) {
                    assertTrue("The candidate continuation stays faint", b <= 30)
                    assertTrue("Liquid deformation must preserve the readable candidate bridge", abs(a - b) <= 1)
                }
            }
            assertTrue("The contour changes over a visible area, not just a few interior pixels", changedBoundary > 500)
            assertTrue("The irregular contour retains a feathered edge", softPixels > 500)
            save(mistFrame, "soft-mist-300ms")
            save(first, "irregular-fluid-300ms")
            save(adjacent, "irregular-fluid-316ms")
            save(later, "irregular-fluid-600ms")
            val board = Bitmap.createBitmap(1200, 660, Bitmap.Config.ARGB_8888)
            Canvas(board).apply {
                drawColor(Color.rgb(8, 12, 20))
                drawBitmap(mistFrame, 0f, 30f, null)
                drawBitmap(first, 600f, 30f, null)
                drawBitmap(adjacent, 0f, 360f, null)
                drawBitmap(later, 600f, 360f, null)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 18f }
                drawText("Default mist / 300 ms", 20f, 23f, paint)
                drawText("Fluid / 300 ms", 620f, 23f, paint)
                drawText("Fluid / 316 ms", 20f, 353f, paint)
                drawText("Fluid / 600 ms", 620f, 353f, paint)
            }
            save(board, "ripple-shape-native-review")
            listOf(mistFrame, first, sameTime, adjacent, later, board).forEach(Bitmap::recycle)
        } finally { mist.finish(); fluid.finish() }
    }

    @Test
    fun liquidSurfaceIsVisibleInsideTheCapAndBothModesRespectBrightnessAndTheirFinalFade() {
        val mist = Harness(ThemePrefs.RippleShape.SoftMist)
        val fluid = Harness(ThemePrefs.RippleShape.IrregularFluid)
        val dark = Harness(ThemePrefs.RippleShape.IrregularFluid, brightness = 0)
        try {
            mist.press(); fluid.press(); dark.press()
            for (time in longArrayOf(50, 100, 150)) {
                val a = mist.face(time)
                val b = fluid.face(time)
                if (time < 150) {
                    assertTrue("Liquid is visibly distinct on the actual cap, even when narrow gaps hide the base field",
                        difference(a, b) > 2.0)
                    val repeated = fluid.face(time)
                    assertTrue("The local liquid surface cannot randomly jump between draws", b.sameAs(repeated))
                    repeated.recycle()
                } else assertTrue("Both face styles finish their configured release", a.sameAs(b))
                a.recycle(); b.recycle()
            }
            for ((h, time) in listOf(mist to 1000L, fluid to 1000L, dark to 300L)) {
                val frame = h.field(time)
                for (y in 0 until frame.height) for (x in 0 until frame.width)
                    assertEquals("Configured darkness includes the candidate extension", 0, intensity(frame.getPixel(x, y)))
                frame.recycle()
            }
        } finally { mist.finish(); fluid.finish(); dark.finish() }
    }
}
