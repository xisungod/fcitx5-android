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

    @Test
    fun lightArrivesOutwardsAndLeavesABroadConnectedTail() {
        Field().use { field ->
            val samples = (40L..400L step 10).map { time ->
                val frame = field.render(time)
                try {
                    if (time in listOf(80L, 160L, 240L, 320L, 400L)) save(frame, "travel-$time")
                    listOf(time.toInt(), light(frame, 180), light(frame, 230), light(frame, 280))
                } finally { frame.recycle() }
            }
            val near = samples.map { it[0].toLong() to it[1] }
            val middle = samples.map { it[0].toLong() to it[2] }
            val far = samples.map { it[0].toLong() to it[3] }
            assertTrue("Far light cannot appear with the initial press: $samples", far.first().second < 5)
            assertTrue("Light reaches successive physical key positions later: $samples",
                arrival(near) < arrival(middle) && arrival(middle) + 30 <= arrival(far))
            val broad = field.render(320)
            try {
                // A connected region at least two key pitches wide distinguishes
                // this from a thin bright ring with an immediately black wake.
                assertTrue("The advancing light retains a broad connected wake",
                    (170..290 step 5).count { light(broad, it) >= 35 } >= 20)
                assertTrue("A single press must leave a distant dark region", light(broad, 480) < 5)
            } finally { broad.recycle() }
            File("build/outputs/effect-checks/sam-propagation/arrival.csv").writeText(
                "time_ms,near_light,middle_light,far_light\n" + samples.joinToString("\n") { it.joinToString(",") } + "\n")
        }
    }

    @Test
    fun centreDimsBeforeTheOutsideWithoutCuttingOffTheTravellingTail() {
        Field().use { field ->
            val samples = listOf(80L, 240L, 440L, 650L, 900L, 1100L, 1340L).map { time ->
                val frame = field.render(time)
                try {
                    save(frame, "local-fade-$time")
                    Triple(time, light(frame, 120), light(frame, 280))
                } finally { frame.recycle() }
            }
            val early = samples.first()
            val late = samples.first { it.first == 900L }
            assertTrue("Initially the source is brighter than the unreached outside: $early", early.second > early.third + 50)
            assertTrue("The source can finish fading while the outside retains light: $late",
                late.second < 12 && late.third > late.second + 15)
            assertTrue("The outside falls later, rather than keeping a fixed ratio to the centre: $samples",
                samples.first { it.first == 650L }.third > samples.first { it.first == 650L }.second)
            assertEquals("The original configured total lifetime still ends in black", Triple(1340L, 0, 0), samples.last())
            File("build/outputs/effect-checks/sam-propagation/fade.csv").writeText(
                "time_ms,source_light,outer_light\n" + samples.joinToString("\n") { "${it.first},${it.second},${it.third}" } + "\n")
        }
    }

    @Test
    fun onePressKeepsItsSelectedColourAtEveryDistanceAndAge() {
        Field(palette = PressEffect.CYBERPUNK).use { field ->
            val expected = field.sourceColour
            fun normalized(pixel: Int) = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                .map { it.toFloat() / intensity(pixel).coerceAtLeast(1) }
            val wanted = normalized(expected)
            for ((time, x) in listOf(80L to 130, 240L to 210, 400L to 280, 900L to 280)) {
                val frame = field.render(time)
                try {
                    val actual = frame.getPixel(x, 150)
                    assertTrue("Colour must be measured where the wave remains visible: $time/$x", intensity(actual) >= 20)
                    normalized(actual).zip(wanted).forEach { (a, b) ->
                        assertEquals("A single source must not acquire a different rim or aging colour: $time/$x", b, a, 0.09f)
                    }
                    save(frame, "single-colour-$time")
                } finally { frame.recycle() }
            }
        }
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

    @Test
    fun configuredExpansionTimeChangesTheObservedTravelTime() {
        fun arrivalTime(expansion: Int): Long = Field(expansion = expansion).use { field ->
            arrival((40L..expansion.toLong() step 10).map { time ->
                val frame = field.render(time)
                try { time to light(frame, 280) } finally { frame.recycle() }
            })
        }
        val fast = arrivalTime(400)
        val slow = arrivalTime(800)
        assertTrue("Doubling the expansion setting delays arrival at a fixed physical location", slow > fast * 1.7f)
        assertTrue("Propagation still scales with the selected duration", abs(slow - fast * 2) <= 50)
    }

    @Test
    fun selectedFadeAndHoldRemainVisibleWithoutExtendingTheConfiguredLifetime() {
        fun sample(fade: Int, hold: Int, time: Long): Int = Field(fade = fade, hold = hold).use { field ->
            val frame = field.render(time)
            try { light(frame, 280) } finally { frame.recycle() }
        }
        assertEquals("Short selected fade has ended", 0, sample(100, 40, 700))
        assertTrue("A long fade retains the later outer tail", sample(900, 40, 900) >= 15)
        assertTrue("A selected hold delays local fade", sample(100, 500, 600) >= 30)
        assertEquals("No source outlives expansion + hold + fade", 0, sample(900, 40, 1340))
    }
}
