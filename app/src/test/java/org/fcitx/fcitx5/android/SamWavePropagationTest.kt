/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.input.keyboard.IdleBreathing
import org.fcitx.fcitx5.android.input.keyboard.PressEffect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/** Native pixels from the production field; no redrawn mock keyboard or copied effect formula. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SamWavePropagationTest {
    private class Field(
        expansion: Int = 400, fade: Int = 900, hold: Int = 40,
        val width: Int = 600, val height: Int = 300,
        palette: IntArray = intArrayOf(Color.CYAN), autoPress: Boolean = true
    ) : AutoCloseable {
        private val controller = Robolectric.buildActivity(Activity::class.java).setup()
        private val activity = controller.get()
        private val host = View(activity)
        private var now = 0L
        var sourceColour = Color.BLACK
            private set
        val effect = PressEffect(host, palette, true, 100, expansion, fade, 0,
            true, false, IdleBreathing(), intArrayOf(Color.CYAN), clock = { now },
            keySurfaceEffects = true, holdWhilePressed = true, exactKeyShape = true,
            keyCornerRadius = 5f, glowOnCandidates = false, animationsAllowed = { true },
            waveHoldTimeMs = hold, rippleShape = ThemePrefs.RippleShape.Sam)

        init {
            activity.setContentView(FrameLayout(activity).apply {
                addView(host, FrameLayout.LayoutParams(width, height))
            })
            controller.visible()
            host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, width, height)
            assertTrue(host.isAttachedToWindow && host.isShown)
            ReflectionHelpers.setField(effect, "random", Random(1401))
            effect.setActive(true)
            if (autoPress) { press(0); release(50) }
        }

        fun press(time: Long, x: Float = 120f, y: Float = 150f,
            pitch: Float = 60f, keyId: Int = 41, pointerId: Int = 0) {
            now = time
            // Hold the visible cap dimensions fixed: pitch represents the real
            // surrounding touch cell and can differ after width/layout editing.
            effect.onPress(x, y, Rect((x - 30).toInt(), (y - 35).toInt(),
                (x + 30).toInt(), (y + 35).toInt()), true, keyId, pointerId, pitch)
            sourceColour = requireNotNull(PressEffect.colorForKey(keyId))
        }

        fun release(time: Long, pointerId: Int = 0) {
            now = time
            effect.onRelease(pointerId)
        }

        fun render(time: Long): Bitmap {
            now = time
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { drawColor(Color.BLACK); effect.drawUnder(this) }
            }
        }

        override fun close() {
            effect.setActive(false)
            controller.pause().stop().destroy()
        }
    }

    private fun intensity(pixel: Int) = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))

    private fun light(image: Bitmap, x: Int, y: Int = 150): Int {
        var sum = 0
        for (row in y - 2..y + 2) for (column in x - 2..x + 2) sum += intensity(image.getPixel(column, row))
        return sum / 25
    }

    private fun save(image: Bitmap, name: String) {
        val file = File("build/outputs/effect-checks/sam-propagation/$name.png")
        file.parentFile.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun arrival(samples: List<Pair<Long, Int>>): Long {
        val most = samples.maxOf { it.second }
        assertTrue("Arrival must refer to visible light", most >= 20)
        return samples.first { it.second * 2 >= most }.first
    }

    // The key is at x = 120 and its touch cell is 60 wide; a pool reaches four to five cells.
    @Test
    fun lightReachesNearKeysBeforeFarOnesAndStaysLitBehindItsEdge() {
        Field().use { field ->
            val samples = (50L..700L step 10).map { time ->
                val frame = field.render(time)
                try {
                    if (time in listOf(80L, 160L, 240L, 320L, 400L)) save(frame, "pool-$time")
                    listOf(time.toInt(), light(frame, 210), light(frame, 270), light(frame, 315))
                } finally { frame.recycle() }
            }
            val near = samples.map { it[0].toLong() to it[1] }
            val middle = samples.map { it[0].toLong() to it[2] }
            val far = samples.map { it[0].toLong() to it[3] }
            assertTrue("The pool is visible at every sampled distance and dimmer further out: $samples",
                near.maxOf { it.second } >= 100 && middle.maxOf { it.second } >= 45 && far.maxOf { it.second } >= 8 &&
                    near.maxOf { it.second } > middle.maxOf { it.second } && middle.maxOf { it.second } > far.maxOf { it.second })
            assertTrue("It grows outwards: ${arrival(near)} / ${arrival(middle)} / ${arrival(far)}",
                arrival(middle) >= arrival(near) + 20 && arrival(far) >= arrival(middle) + 60)
            assertTrue("The area behind the edge stays lit until the fade begins: $near",
                near.first { it.first == 440L }.second >= near.maxOf { it.second } * 0.9f)
            for ((previous, next) in middle.filter { it.first <= 440L }.zipWithNext()) {
                assertTrue("No flicker while the pool grows: $previous / $next", next.second >= previous.second - 3)
            }
            File("build/outputs/effect-checks/sam-propagation/arrival.csv").writeText(
                "time_ms,near_light,middle_light,far_light\n" + samples.joinToString("\n") { it.joinToString(",") } + "\n")
        }
    }

    @Test
    fun aPoolIsBrightestAtItsKeyAndFadesSmoothlyToNothingAtItsReach() {
        Field().use { field ->
            for (time in listOf(240L, 400L)) {
                val frame = field.render(time)
                try {
                    val profile = (120..580 step 4).map { x -> x to light(frame, x) }
                    val peak = profile.maxOf { it.second }
                    assertTrue("The pool is bright at $time ms: $peak", peak >= 120)
                    assertEquals("Its brightest point is the pressed key at $time ms", peak, profile.first().second)
                    for ((inner, outer) in profile.zipWithNext()) {
                        assertTrue("Light only falls with distance at $time ms, so there is no ring: $inner / $outer",
                            outer.second <= inner.second + 3)
                    }
                    val broad = profile.count { it.second >= peak * 0.4f } * 4
                    assertTrue("It is a pool rather than a spot at $time ms: $broad px", broad >= 90)
                    val skirt = profile.count { it.second >= 1 && it.second <= peak * 0.2f } * 4
                    assertTrue("Its edge is soft at $time ms: $skirt px of faint light", skirt >= 24)
                    assertEquals("It does not flood the keyboard: no light eight cells away", 0, profile.last().second)
                    save(frame, "pool-profile-$time")
                } finally { frame.recycle() }
            }
        }
    }

    @Test
    fun thePoolDimsAsAWholeAndEndsInBlack() {
        Field().use { field ->
            val samples = listOf(440L, 500L, 650L, 900L, 1100L, 1340L).map { time ->
                val frame = field.render(time)
                try {
                    save(frame, "fade-$time")
                    Triple(time, light(frame, 120), light(frame, 240))
                } finally { frame.recycle() }
            }
            val full = samples.first()
            assertTrue("When the fade begins, the key and a place two cells away are both lit: $full",
                full.second >= 120 && full.third >= 40)
            for ((previous, next) in samples.zipWithNext()) {
                assertTrue("Both places only dim once the fade has begun: $previous / $next",
                    next.second <= previous.second + 2 && next.third <= previous.third + 2)
            }
            val shape = full.third.toFloat() / full.second
            for (sample in samples.filter { it.first in listOf(500L, 650L, 900L) }) {
                assertTrue("The pool keeps its shape while it dims, it does not retreat to a ring or a spot: $full / $sample",
                    abs(sample.third.toFloat() / sample.second - shape) <= 0.08f)
            }
            assertTrue("Halfway through the fade it is clearly dimmer but still there: $samples",
                samples.first { it.first == 900L }.second in (full.second * 0.3f).toInt()..(full.second * 0.7f).toInt())
            assertEquals("The whole pool obeys the selected lifetime", Triple(1340L, 0, 0), samples.last())
            File("build/outputs/effect-checks/sam-propagation/fade.csv").writeText(
                "time_ms,key_light,near_light\n" + samples.joinToString("\n") { "${it.first},${it.second},${it.third}" } + "\n")
        }
    }

    /** The reference keyboard lights green first, then blue, then violet; it never shows warm colours. */
    @Test
    fun withTheDefaultPaletteLightAgesFromGreenThroughBlueToVioletWithoutWarmColours() {
        Field(palette = PressEffect.CYBERPUNK).use { field ->
            fun hue(pixel: Int): Float {
                val hsv = FloatArray(3)
                Color.colorToHSV(pixel, hsv)
                return hsv[0]
            }
            val young = field.render(160)
            val old = field.render(900)
            try {
                save(young, "aurora-160"); save(old, "aurora-900")
                val edge = young.getPixel(200, 150)
                assertTrue("Freshly reached light is visible: ${Integer.toHexString(edge)}", intensity(edge) >= 40)
                assertTrue("Freshly reached light is green to teal: ${hue(edge)}", hue(edge) in 120f..195f)
                val aged = old.getPixel(124, 150)
                assertTrue("Aged light is visible: ${Integer.toHexString(aged)}", intensity(aged) >= 30)
                assertTrue("Aged light is blue to violet: ${hue(aged)}", hue(aged) in 215f..300f)
                for (frame in listOf(young, old)) for (x in 100..400 step 6) {
                    val pixel = frame.getPixel(x, 150)
                    if (intensity(pixel) < 20) continue
                    assertTrue("No red, orange or yellow light at $x: ${hue(pixel)}", hue(pixel) in 110f..320f)
                }
            } finally { young.recycle(); old.recycle() }
        }
    }

    @Test
    fun configuredExpansionTimeChangesTheObservedTravelTime() {
        fun arrivalTime(expansion: Int): Long = Field(expansion = expansion).use { field ->
            arrival((50L..expansion.toLong() step 10).map { time ->
                val frame = field.render(time)
                try { time to light(frame, 270) } finally { frame.recycle() }
            })
        }
        val fast = arrivalTime(400)
        val slow = arrivalTime(800)
        assertTrue("Doubling the expansion setting must delay when further keys light up: $fast / $slow", slow > fast * 1.7f)
        assertTrue("The propagation scales with the selected duration: $fast / $slow", abs(slow - fast * 2) <= 50)
    }

    @Test
    fun selectedWaveFadeAndHoldAreVisibleAndEndAtTheirConfiguredTimes() {
        fun sample(fade: Int, hold: Int, time: Long): Int = Field(fade = fade, hold = hold).use { field ->
            val frame = field.render(time)
            // Observe the pool away from the pressed key.
            try { light(frame, 240) } finally { frame.recycle() }
        }
        assertEquals("Short selected fade has ended", 0, sample(100, 40, 700))
        assertTrue("A long selected fade keeps a visible tail", sample(900, 40, 1000) >= 15)
        val full = sample(900, 40, 440)
        val halfway = sample(900, 40, 890)
        assertTrue("Halfway through the selected fade retains about half of its visible exposure", halfway in (full * 0.40f).toInt()..(full * 0.60f).toInt())
        assertTrue("A selected hold delays the fade", sample(100, 500, 700) >= 40)
        assertEquals("The full selected wave lifetime ends in black", 0, sample(900, 40, 1340))
    }

    @Test
    fun aDistantSecondPressDoesNotRecolourOrRestartTheOlderWave() {
        Field().use { control ->
            Field().use { multiple ->
                multiple.press(200, 500f, 150f, keyId = 42)
                multiple.release(250)
                for (time in listOf(300L, 600L, 900L)) {
                    val expected = control.render(time)
                    val actual = multiple.render(time)
                    try {
                        for (y in 140..160) for (x in 100..180) {
                            assertEquals("A distant press cannot alter the old source at $time/$x/$y",
                                expected.getPixel(x, y), actual.getPixel(x, y))
                        }
                        save(actual, "two-sources-$time")
                    } finally { expected.recycle(); actual.recycle() }
                }
            }
        }
    }

    @Test
    fun longHoldAndReleaseDoNotRestartAnExpiredBackgroundWave() {
        Field(autoPress = false).use { field ->
            field.press(0)
            val ended = field.render(1500)
            field.release(1500)
            val released = field.render(1516)
            try {
                assertEquals(0, light(ended, 120))
                assertEquals(0, light(ended, 280))
                assertTrue("UP only releases the independent key face; it must not flash a new background wave", ended.sameAs(released))
            } finally { ended.recycle(); released.recycle() }
        }
    }

    @Test
    fun measuredKeyPitchControlsBothAxesInsteadOfAssumingTenColumns() {
        fun boundsForPitch(pitch: Float): Rect = Field(width = 1000, height = 800, autoPress = false).use { field ->
            field.press(0, 500f, 400f, pitch)
            field.release(50)
            val frame = field.render(400)
            try {
                val bounds = Rect(frame.width, frame.height, 0, 0)
                for (y in 0 until frame.height) for (x in 0 until frame.width) if (intensity(frame.getPixel(x, y)) >= 25) {
                    bounds.left = minOf(bounds.left, x); bounds.right = maxOf(bounds.right, x)
                    bounds.top = minOf(bounds.top, y); bounds.bottom = maxOf(bounds.bottom, y)
                }
                save(frame, "pitch-${pitch.toInt()}")
                bounds
            } finally { frame.recycle() }
        }
        val small = boundsForPitch(50f)
        val large = boundsForPitch(100f)
        assertTrue("A larger nine-key touch cell must produce a proportionate horizontal reach", large.width().toFloat() / small.width() in 1.7f..2.3f)
        assertTrue("Vertical reach must use the same physical metric", large.height().toFloat() / small.height() in 1.7f..2.3f)
        assertEquals("Changing layout must not flatten or stretch the light", small.width().toFloat() / small.height(), large.width().toFloat() / large.height(), 0.06f)
    }

    @Test
    fun droppedDisplayFramesDoNotChangeTheWaveState() {
        Field().use { sparse ->
            Field().use { dense ->
                for (time in 60L..660L step 10) dense.render(time).recycle()
                val a = sparse.render(700)
                val b = dense.render(700)
                try { assertTrue("Wave state depends on elapsed time, not the number of frames drawn", a.sameAs(b)) }
                finally { a.recycle(); b.recycle() }
            }
        }
    }


}
