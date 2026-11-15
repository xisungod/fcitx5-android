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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/** Observe native pixels from the production field, including areas hidden by black keycaps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SamWavePropagationTest {
    private class Field(expansion: Int = 400, fade: Int = 900, hold: Int = 40) : AutoCloseable {
        private val controller = Robolectric.buildActivity(Activity::class.java).setup()
        private val activity = controller.get()
        private val host = View(activity)
        private var now = 0L
        val effect = PressEffect(host, intArrayOf(Color.CYAN), true, 100, expansion, fade, 0,
            true, false, IdleBreathing(), intArrayOf(Color.CYAN), clock = { now },
            keySurfaceEffects = true, holdWhilePressed = true, exactKeyShape = true,
            keyCornerRadius = 5f, glowOnCandidates = false, animationsAllowed = { true },
            waveHoldTimeMs = hold, rippleShape = ThemePrefs.RippleShape.Sam)

        init {
            activity.setContentView(FrameLayout(activity).apply {
                addView(host, FrameLayout.LayoutParams(600, 300))
            })
            controller.visible()
            host.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, 600, 300)
            assertTrue(host.isAttachedToWindow && host.isShown)
            ReflectionHelpers.setField(effect, "random", Random(1401))
            effect.setActive(true)
            effect.onPress(120f, 150f, Rect(90, 115, 150, 185), true, 41, 0)
            now = 50L
            effect.onRelease(0)
        }

        fun render(time: Long): Bitmap {
            now = time
            return Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { drawColor(Color.BLACK); effect.drawUnder(this) }
            }
        }

        override fun close() {
            effect.setActive(false)
            controller.pause().stop().destroy()
        }
    }

    private fun light(image: Bitmap, x: Int): Int {
        var sum = 0
        for (y in 148..152) for (column in x - 2..x + 2) {
            val pixel = image.getPixel(column, y)
            sum += maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
        }
        return sum / 25
    }

    private fun save(image: Bitmap, name: String) {
        val file = File("build/outputs/effect-checks/sam-propagation/$name.png")
        file.parentFile.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** First sampled moment at which a location shows half of the most light it ever shows. */
    private fun arrival(samples: List<Pair<Long, Int>>): Long {
        val most = samples.maxOf { it.second }
        return samples.first { it.second * 2 >= most }.first
    }

    @Test
    fun lightReachesNearKeysBeforeFarOnesAndStaysLitBehindItsEdge() {
        Field().use { field ->
            val samples = (50L..400L step 10).map { time ->
                val frame = field.render(time)
                try {
                    if (time in listOf(80L, 160L, 240L, 320L, 400L)) save(frame, "pool-$time")
                    listOf(time.toInt(), light(frame, 210), light(frame, 300), light(frame, 480))
                } finally { frame.recycle() }
            }
            val near = samples.map { it[0].toLong() to it[1] }
            val middle = samples.map { it[0].toLong() to it[2] }
            val far = samples.map { it[0].toLong() to it[3] }
            assertTrue("The pool is clearly visible at every sampled distance: $samples",
                near.maxOf { it.second } >= 100 && middle.maxOf { it.second } >= 80 && far.maxOf { it.second } >= 30)
            assertTrue("It grows outwards: a far key lights up later than one half as far: ${arrival(middle)} / ${arrival(far)}",
                arrival(far) >= arrival(middle) + 60)
            assertTrue("The area behind the edge stays lit instead of going dark again: $near",
                near.last().second >= near.maxOf { it.second } * 0.9f)
            for ((previous, next) in middle.zipWithNext()) {
                assertTrue("No flicker while the pool grows: $previous / $next", next.second >= previous.second - 3)
            }
            File("build/outputs/effect-checks/sam-propagation/arrival.csv").writeText(
                "time_ms,near_light,middle_light,far_light\n" + samples.joinToString("\n") { it.joinToString(",") } + "\n")
        }
    }

    @Test
    fun aPoolIsBrightestAtItsKeyAndHasNeitherARimNorAHollowCentre() {
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
                    assertTrue("It is a broad pool rather than a spot at $time ms: $broad px", broad >= if (time == 400L) 260 else 150)
                    assertTrue("Its edge is soft: light at the far side is low but not cut off: ${profile.last()}",
                        time != 400L || profile.last().second in 1..(peak * 0.45f).toInt())
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
                    Triple(time, light(frame, 120), light(frame, 420))
                } finally { frame.recycle() }
            }
            val full = samples.first()
            assertTrue("Fully grown, the key and a place five keys away are both lit: $full",
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
                "time_ms,key_light,far_light\n" + samples.joinToString("\n") { "${it.first},${it.second},${it.third}" } + "\n")
        }
    }

    @Test
    fun configuredExpansionTimeChangesTheObservedTravelTime() {
        fun arrivalTime(expansion: Int): Long = Field(expansion = expansion).use { field ->
            arrival((50L..expansion.toLong() step 10).map { time ->
                val frame = field.render(time)
                try { time to light(frame, 420) } finally { frame.recycle() }
            })
        }
        val fast = arrivalTime(400)
        val slow = arrivalTime(800)
        assertTrue("Doubling the expansion setting must delay when distant keys light up: $fast / $slow", slow > fast * 1.7f)
        assertTrue("The propagation scales with the selected duration", abs(slow - fast * 2) <= 40)
    }

    @Test
    fun selectedWaveFadeAndHoldAreVisibleAndEndAtTheirConfiguredTimes() {
        fun sample(fade: Int, hold: Int, time: Long): Int = Field(fade = fade, hold = hold).use { field ->
            val frame = field.render(time)
            // Observe the pool away from the pressed key.
            try { light(frame, 360) } finally { frame.recycle() }
        }
        assertEquals("Short selected fade has ended", 0, sample(100, 40, 700))
        assertTrue("A long selected fade keeps a visible tail", sample(900, 40, 1000) >= 15)
        val full = sample(900, 40, 440)
        val halfway = sample(900, 40, 890)
        assertTrue("Halfway through the selected fade retains about half of its visible exposure", halfway in (full * 0.40f).toInt()..(full * 0.60f).toInt())
        assertTrue("A selected hold delays the fade", sample(100, 500, 700) >= 40)
        assertEquals("The full selected wave lifetime ends in black", 0, sample(900, 40, 1340))
    }
}
