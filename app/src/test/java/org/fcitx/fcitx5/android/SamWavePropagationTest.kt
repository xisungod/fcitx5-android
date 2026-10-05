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

    @Test
    fun lightCrestVisitsNearThenFarPixelsAndLeavesASoftWake() {
        Field().use { field ->
            val samples = (50L..400L step 10).map { time ->
                val frame = field.render(time)
                try {
                    if (time in listOf(80L, 160L, 240L, 320L, 400L)) save(frame, "front-$time")
                    Triple(time, light(frame, 210), light(frame, 300))
                } finally { frame.recycle() }
            }
            val near = samples.maxBy { it.second }
            val far = samples.maxBy { it.third }
            assertTrue("A real visible crest reaches both sampled distances", near.second >= 40 && far.third >= 30)
            assertTrue("Light peaks at the farther location later, rather than only filling a growing pool: $near / $far",
                far.first >= near.first + 60)
            assertTrue("Near pixels dim after the crest has moved outward", samples.last().second < near.second * 0.65f)
            assertTrue("A coloured wake remains behind the front before its centre clears",
                samples.first { it.first == 300L }.second >= 5)
            File("build/outputs/effect-checks/sam-propagation/arrival.csv").writeText(
                "time_ms,near_light,far_light\n" + samples.joinToString("\n") { "${it.first},${it.second},${it.third}" } + "\n")
        }
    }

    @Test
    fun travellingBandStaysBroadAndVisibleBeyondItsInitialBurst() {
        Field().use { field ->
            for (time in listOf(240L, 400L)) {
                val frame = field.render(time)
                try {
                    val profile = (180..480).map { x -> x to light(frame, x) }
                    val peak = profile.maxOf { it.second }
                    var run = 0
                    var longestRun = 0
                    for ((_, value) in profile) {
                        run = if (value >= peak * 0.55f) run + 1 else 0
                        longestRun = maxOf(longestRun, run)
                    }
                    assertTrue("The travelling band remains visible at $time ms: $peak", peak >= 80)
                    assertTrue("Light spans neighbouring gaps instead of a thin outline at $time ms: $longestRun px",
                        longestRun >= 84)
                    assertTrue("The front retains a dark area ahead instead of lighting the entire field",
                        light(frame, 500) < peak * 0.15f)
                    if (time == 400L) assertTrue("The source clears before the outgoing band; this is not a uniformly fading pool",
                        light(frame, 120) < peak * 0.40f)
                    save(frame, "wide-band-$time")
                } finally { frame.recycle() }
            }
        }
    }

    @Test
    fun configuredExpansionTimeChangesTheObservedTravelTime() {
        fun peakTime(expansion: Int): Long = Field(expansion = expansion).use { field ->
            (50L..expansion.toLong() step 10).map { time ->
                val frame = field.render(time)
                try { time to light(frame, 300) } finally { frame.recycle() }
            }.maxBy { it.second }.first
        }
        val fast = peakTime(400)
        val slow = peakTime(800)
        assertTrue("Doubling the expansion setting must delay actual distant pixel peaks: $fast / $slow", slow > fast * 1.7f)
        assertTrue("The propagation scales with the selected duration", abs(slow - fast * 2) <= 40)
    }

    @Test
    fun selectedWaveFadeAndHoldAreVisibleAndEndAtTheirConfiguredTimes() {
        fun sample(fade: Int, hold: Int, time: Long): Int = Field(fade = fade, hold = hold).use { field ->
            val frame = field.render(time)
            // Observe the outgoing band: the source now clears behind it.
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
