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
import org.fcitx.fcitx5.android.data.theme.PressColorPalette
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.input.keyboard.IdleBreathing
import org.fcitx.fcitx5.android.input.keyboard.PressEffect
import org.fcitx.fcitx5.android.input.keyboard.SamKeyLightField
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
import kotlin.random.Random

/** Actual production pixels over a real rectangular-key topology, not a radial mock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SamWavePropagationTest {
    companion object {
        private fun intensity(pixel: Int) = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
        private const val SOURCE = 23 // column 2, row 2
        private fun grid(): List<SamKeyLightField.KeyGeometry> = (0 until 5).flatMap { row ->
            (0 until 10).map { col ->
                val touch = Rect(col * 60, row * 72, (col + 1) * 60, (row + 1) * 72)
                SamKeyLightField.KeyGeometry(row * 10 + col + 1,
                    Rect(touch.left + 6, touch.top + 6, touch.right - 6, touch.bottom - 6), touch)
            }
        }
    }

    private class Field(val geometry: List<SamKeyLightField.KeyGeometry> = grid(),
        palette: IntArray = intArrayOf(Color.CYAN), autoPress: Boolean = true,
        expansion: Int = 400, fade: Int = 900, hold: Int = 40, reach: Int = 100) : AutoCloseable {
        private val controller = Robolectric.buildActivity(Activity::class.java).setup()
        private val activity = controller.get()
        private val host = View(activity)
        private var now = 0L
        var sourceColour = Color.BLACK
            private set
        val effect = PressEffect(host, palette, true, 100, expansion, fade, 0,
            true, false, IdleBreathing(), intArrayOf(Color.CYAN), clock = { now },
            keySurfaceEffects = true, holdWhilePressed = true, exactKeyShape = true,
            keyCornerRadius = 7f, glowOnCandidates = false, animationsAllowed = { true },
            waveHoldTimeMs = hold, glowReachPercent = reach, rippleShape = ThemePrefs.RippleShape.Sam)

        init {
            activity.setContentView(FrameLayout(activity).apply {
                addView(host, FrameLayout.LayoutParams(600, 360))
            })
            controller.visible()
            host.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, 600, 360)
            assertTrue(host.isAttachedToWindow && host.isShown)
            ReflectionHelpers.setField(effect, "random", Random(1401))
            effect.setSamKeyGeometry(geometry)
            effect.setActive(true)
            if (autoPress) { press(0); release(50) }
        }

        fun press(time: Long, id: Int = SOURCE) {
            now = time
            val key = geometry.first { it.id == id }
            effect.onPress(key.capBounds.exactCenterX(), key.capBounds.exactCenterY(), key.capBounds,
                true, id, 0, minOf(key.touchBounds.width(), key.touchBounds.height()).toFloat())
            sourceColour = requireNotNull(PressEffect.colorForKey(id))
        }
        fun release(time: Long) { now = time; effect.onRelease(0) }
        fun frame(time: Long): Bitmap {
            now = time
            return Bitmap.createBitmap(600, 360, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { drawColor(Color.BLACK); effect.drawUnder(this) }
            }
        }
        fun light(frame: Bitmap, id: Int): Int {
            val key = geometry.first { it.id == id }.capBounds
            var sum = 0
            for (y in key.centerY() - 2..key.centerY() + 2)
                for (x in key.centerX() - 2..key.centerX() + 2) sum += intensity(frame.getPixel(x, y))
            return sum / 25
        }
        fun samplesVisited(): Int = ReflectionHelpers.getField<Int>(
            ReflectionHelpers.getField<Any>(effect, "neonField"), "visitedSamples")
        override fun close() { effect.setActive(false); controller.pause().stop().destroy() }
    }


    private fun save(frame: Bitmap, name: String) {
        val file = File("build/outputs/effect-checks/sam-propagation/$name.png")
        file.parentFile.mkdirs()
        file.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun firstArrival(field: Field, id: Int, end: Long = 800): Long = (0L..end step 10).first { time ->
        val frame = field.frame(time)
        try { field.light(frame, id) >= 20 } finally { frame.recycle() }
    }

    @Test fun neighbourKeysLightInOrderAndTheFourthHopIsTheMaximumReach() {
        Field().use { field ->
            val one = firstArrival(field, SOURCE + 1)
            val two = firstArrival(field, SOURCE + 2)
            val three = firstArrival(field, SOURCE + 3)
            assertTrue("Weighted neighbouring edges arrive successively: $one/$two/$three", one + 50 <= two && two + 50 <= three)
            val frame = field.frame(440)
            try {
                assertTrue(field.light(frame, SOURCE + 3) >= 25)
                assertEquals("Five hops must remain outside a three/four-hop wave", 0, field.light(frame, SOURCE + 5))
                val coloured = (0 until frame.width).filter { intensity(frame.getPixel(it, 180)) >= 25 }
                assertTrue("One press still reaches across half the panel", coloured.last() - coloured.first() >= 300)
                save(frame, "graph-half-panel")
            } finally { frame.recycle() }
        }
    }

    @Test fun anEmptyColumnBreaksPropagationEvenWhenTheOtherBankIsGeometricallyNear() {
        val disconnected = grid().filter { (it.id - 1) % 10 != 4 }
        Field(disconnected).use { field ->
            val frame = field.frame(440)
            try {
                assertTrue("The source bank is lit", field.light(frame, SOURCE + 1) >= 25)
                assertEquals("A pixel-distance ripple would cross this gap; the actual graph must not", 0,
                    field.light(frame, SOURCE + 3))
                save(frame, "graph-disconnected")
            } finally { frame.recycle() }
        }
    }

    @Test fun aSingleKeyFeathersLightPastTheNarrowRimBeforeNeighboursArrive() {
        Field().use { field ->
            val cap = field.geometry.first { it.id == SOURCE }.capBounds
            val frame = field.frame(60)
            try {
                val peak = field.light(frame, SOURCE)
                val inner = intensity(frame.getPixel(cap.right + 10, cap.centerY()))
                val outer = intensity(frame.getPixel(cap.right + 20, cap.centerY()))
                val diagonal = intensity(frame.getPixel(cap.right + 10, cap.bottom + 10))
                assertTrue("Soft light survives outside the old rim: $peak/$inner/$outer", inner > peak / 2)
                assertTrue("The wide feather fades rather than ending at a bright edge", outer in 16 until inner)
                assertTrue("Early glow rounds off diagonal corners while retaining its side light: $diagonal/$inner",
                    diagonal in 16..inner / 2)
                assertEquals(0, intensity(frame.getPixel(cap.right + 32, cap.centerY())))
                save(frame, "graph-soft-feather")
            } finally { frame.recycle() }
        }
    }

    @Test fun sourceFinishesBeforeLaterKeysAndLongHoldDoesNotRestartTheWave() {
        Field(autoPress = false).use { field ->
            field.press(0)
            val tail = field.frame(900)
            try {
                val source = field.light(tail, SOURCE)
                val later = field.light(tail, SOURCE + 3)
                assertTrue("Source already faded: $source", source < 4)
                assertTrue("Even the dim third hop retains a visible independent tail: $source/$later", later >= source + 9)
                save(tail, "graph-local-tail")
            } finally { tail.recycle() }
            val ended = field.frame(1500)
            field.release(1500)
            val released = field.frame(1516)
            try {
                assertEquals(0, field.light(ended, SOURCE + 3))
                assertTrue("UP cannot relight an expired background wave", ended.sameAs(released))
            } finally { ended.recycle(); released.recycle() }
        }
    }

    @Test fun everyPresetWaveKeepsItsSelectedColourAtAllReachedKeysAndAges() {
        for ((name, palette) in listOf("cool" to PressEffect.SAMSUNG_COOL,
            "warm" to PressColorPalette.RED_ORANGE_YELLOW, "full" to PressEffect.CYBERPUNK)) {
            Field(palette = palette).use { field ->
                val expected = field.sourceColour
                val peak = intensity(expected).toFloat()
                for (time in listOf(80L, 240L, 480L, 740L, 900L)) {
                    val frame = field.frame(time)
                    try {
                        var measured = 0
                        for (key in field.geometry) {
                            val pixel = frame.getPixel(key.capBounds.centerX(), key.capBounds.centerY())
                            val brightness = intensity(pixel).toFloat()
                            if (brightness < 20) continue
                            measured++
                            assertEquals(Color.red(expected) / peak, Color.red(pixel) / brightness, 0.09f)
                            assertEquals(Color.green(expected) / peak, Color.green(pixel) / brightness, 0.09f)
                            assertEquals(Color.blue(expected) / peak, Color.blue(pixel) / brightness, 0.09f)
                        }
                        assertTrue("Visible fixed-colour light at $name/$time", measured > 0)
                        save(frame, "fixed-$name-$time")
                    } finally { frame.recycle() }
                }
            }
        }
    }

    @Test fun overlappingColoursUseWeightedLocalEnergyAndStayBounded() {
        Field(palette = intArrayOf(Color.RED, Color.GREEN), autoPress = false).use { field ->
            field.press(0, SOURCE); field.release(50)
            field.press(0, SOURCE + 2); field.release(50)
            val frame = field.frame(350)
            try {
                val between = field.geometry.first { it.id == SOURCE + 1 }.capBounds
                val pixel = frame.getPixel(between.centerX(), between.centerY())
                assertTrue("Both wave colours contribute at their shared neighbour", Color.red(pixel) >= 40 && Color.green(pixel) >= 40)
                assertEquals("No unrelated blue is introduced", 0, Color.blue(pixel))
                assertTrue("The later two source keys stay visibly lit", field.light(frame, SOURCE) >= 40 && field.light(frame, SOURCE + 2) >= 40)
                save(frame, "graph-weighted-overlap")
            } finally { frame.recycle() }
        }
    }

    @Test fun complementaryWavesStayChromaticAsTheirSharedHaloFades() {
        val onlySource = grid().filter { it.id == SOURCE }
        Field(geometry = onlySource, palette = intArrayOf(Color.RED, Color.CYAN), autoPress = false).use { field ->
            // Equal red and cyan would cancel to grey in an RGB average. Use the
            // same source/timing so both contributions always have equal energy.
            field.press(0); field.release(50)
            field.press(0); field.release(50)
            val bright = field.frame(125)
            val faded = field.frame(650)
            try {
                val cap = onlySource.single().capBounds
                fun channels(frame: Bitmap): List<Int> {
                    val pixel = frame.getPixel(cap.centerX(), cap.centerY())
                    return listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                }
                val a = channels(bright)
                val b = channels(faded)
                val peak = a.max()
                val dimPeak = b.max()
                assertTrue("The mixed halo fades without becoming brighter than the RGB peak: $peak/$dimPeak",
                    dimPeak in 20 until peak && peak in 100..130)
                assertTrue("A complementary overlap remains saturated at both brightnesses: $a / $b",
                    a.min() <= peak / 8 && b.min() <= dimPeak / 8)
                a.indices.forEach { index ->
                    assertEquals("Dimming does not shift the mixed hue", a[index].toFloat() / peak,
                        b[index].toFloat() / dimPeak, 0.06f)
                }
                save(faded, "graph-complementary-fade")
            } finally { bright.recycle(); faded.recycle() }
        }
    }

    @Test fun weakOverlapUsesL3AndRasterWorkDoesNotMultiplyByTheWaveCount() {
        Field().use { single -> Field(autoPress = false).use { many ->
            repeat(12) { many.press(0); many.release(50) }
            val one = single.frame(650)
            val several = many.frame(650)
            try {
                val baseline = single.light(one, SOURCE)
                val combined = many.light(several, SOURCE)
                assertTrue("Twelve weak tails remain well below full brightness", combined < 180)
                assertTrue("The overlap is brighter than a single tail", combined > baseline * 1.5f)
                assertTrue("Cached key halos are rasterized once, not twelve times", many.samplesVisited() < single.samplesVisited() * 2)
                save(several, "graph-burst-tail")
            } finally { one.recycle(); several.recycle() }
        } }
    }

    @Test fun rapidBroadBurstKeepsHotSpotsAndLetsTheOuterKeyboardStayDark() {
        Field(autoPress = false).use { field ->
            val presses = (1..3).flatMap { row -> (2..7).map { column -> row * 10 + column + 1 } }
            presses.forEachIndexed { index, key ->
                val time = index * 30L
                field.press(time, key)
                field.release(time + 20)
            }
            val frame = field.frame(730)
            try {
                val brightness = field.geometry.map { field.light(frame, it.id) }
                assertTrue("The burst still has visibly bright local keys: $brightness", brightness.max() >= 48)
                assertTrue("Weak tails leave several dark regions amid overlapping waves: $brightness",
                    brightness.count { it <= 12 } >= 8)
                assertTrue("The burst does not saturate the background field: $brightness", brightness.max() < 160)
                save(frame, "graph-burst-contrast")
            } finally { frame.recycle() }
        }
    }

    @Test fun reachSettingChangesTheHopBudgetWithoutChangingTheSource() {
        Field(reach = 20).use { small -> Field(reach = 100).use { full ->
            val a = small.frame(440); val b = full.frame(440)
            try {
                assertEquals("The source retains its own brightness", small.light(a, SOURCE), full.light(b, SOURCE))
                assertEquals(0, small.light(a, SOURCE + 3))
                assertTrue(full.light(b, SOURCE + 3) >= 25)
            } finally { a.recycle(); b.recycle() }
        } }
    }

    @Test fun elapsedTimeAndExpansionSettingControlArrivalWithoutFrameCountDependence() {
        Field(expansion = 400).use { fast -> Field(expansion = 800).use { slow ->
            val a = firstArrival(fast, SOURCE + 2)
            val b = firstArrival(slow, SOURCE + 2)
            assertTrue("A doubled expansion setting delays the same graph route", b > a * 1.7f)
        } }
        Field().use { sparse -> Field().use { dense ->
            for (time in 60L..660L step 10) dense.frame(time).recycle()
            val a = sparse.frame(700); val b = dense.frame(700)
            try { assertTrue("Deterministic geometry/edge jitter cannot flicker with dropped frames", a.sameAs(b)) }
            finally { a.recycle(); b.recycle() }
        } }
    }

    @Test fun samCyclesTheSelectedPaletteAndSingleColourRemainsSingle() {
        Field(palette = PressEffect.CYBERPUNK, autoPress = false).use { field ->
            repeat(45) { index ->
                field.press(index * 1400L); field.release(index * 1400L + 50)
                assertEquals(PressEffect.CYBERPUNK[index % PressEffect.CYBERPUNK.size], field.sourceColour)
            }
        }
        Field(palette = intArrayOf(Color.MAGENTA), autoPress = false).use { field ->
            repeat(3) { field.press(it * 1400L); assertEquals(Color.MAGENTA, field.sourceColour) }
        }
    }
}
