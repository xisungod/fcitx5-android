package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.keyboard.PressEffectMasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PressEffectMasksTest {
    @Test
    fun mistVariantsHaveTransparentEdgesAndFeathering() {
        val noise = PressEffectMasks.Noise(20261003)
        val n = PressEffectMasks.TEXTURE_SIZE
        val hashes = mutableSetOf<Int>()
        repeat(PressEffectMasks.SHAPES) { i ->
            val body = PressEffectMasks.mist(noise, PressEffectMasks.bodySeed(i), false)
            hashes.add(body.contentHashCode())
            for (x in 0 until n) {
                assertEquals(0, body[x] ushr 24)
                assertEquals(0, body[(n - 1) * n + x] ushr 24)
                assertEquals(0, body[x * n] ushr 24)
                assertEquals(0, body[x * n + n - 1] ushr 24)
            }
            assertTrue(body.count { (it ushr 24) in 1..254 } > n * n / 8)
            assertTrue(body[(n / 2) * n + n / 2] ushr 24 > 30)
        }
        assertEquals(PressEffectMasks.SHAPES, hashes.size)
    }

    @Test
    fun flashFallsOffFromCenterToTransparentEdge() {
        val dot = PressEffectMasks.dot()
        val n = PressEffectMasks.DOT_SIZE
        assertTrue(dot[(n / 2) * n + n / 2] ushr 24 > 240)
        assertEquals(0, dot[0] ushr 24)
        assertEquals(0, dot[n - 1] ushr 24)
    }
    @Test
    fun fluidPhasesStayFeatheredWithNoHollowRingAndDifferentOutlines() {
        val noise = PressEffectMasks.Noise(20261004)
        val size = 128
        val hashes = mutableSetOf<Int>()
        repeat(PressEffectMasks.SHAPES) { i ->
            val initial = PressEffectMasks.fluid(noise, PressEffectMasks.bodySeed(i), 0f, size)
            val late = PressEffectMasks.fluid(noise, PressEffectMasks.bodySeed(i), 0.85f, size)
            hashes.add(late.contentHashCode())
            assertTrue(late[(size / 2) * size + size / 2] ushr 24 > 180)
            assertTrue("A cloud has a filled centre, not a hollow ring", late[(size / 2) * size + size / 2] ushr 24 > late[(size / 2) * size + size * 3 / 4] ushr 24)
            assertTrue(late.count { (it ushr 24) in 1..254 } > size * size / 3)
            assertTrue(initial.contentHashCode() != late.contentHashCode())
            for (x in 0 until size) {
                assertEquals(0, late[x] ushr 24)
                assertEquals(0, late[(size - 1) * size + x] ushr 24)
                assertEquals(0, late[x * size] ushr 24)
                assertEquals(0, late[x * size + size - 1] ushr 24)
            }
        }
        assertEquals(PressEffectMasks.SHAPES, hashes.size)
    }

    @Test
    fun fluidTravellingShoulderHasAreaAndRemainsBelowItsFilledCentre() {
        val noise = PressEffectMasks.Noise(20261004)
        val size = 128
        var total = 0L
        var samples = 0
        repeat(PressEffectMasks.SHAPES) { shape ->
            val pixels = PressEffectMasks.fluid(noise, PressEffectMasks.bodySeed(shape), 0.6f, size)
            val centre = pixels[(size / 2) * size + size / 2] ushr 24
            assertTrue("The shoulder must retain a filled core", centre > 180)
            for (y in 0 until size) for (x in 0 until size) {
                val nx = (x - (size - 1) / 2f) / ((size - 1) / 2f)
                val ny = (y - (size - 1) / 2f) / ((size - 1) / 2f)
                val radius = kotlin.math.hypot(nx, ny)
                if (radius in 0.6f..0.8f) {
                    total += pixels[y * size + x] ushr 24
                    samples++
                }
            }
        }
        assertTrue("The travelling shoulder must not disappear into a nearly transparent edge", total.toDouble() / samples >= 45.0)
    }

    @Test
    fun lateFluidKeepsAConnectedRoundedRectangleBodyInsteadOfRoundFog() {
        val noise = PressEffectMasks.Noise(20261004)
        val size = 128
        var diagonalBody = 0L
        var centreTotal = 0L
        var diagonalFront = 0L
        var sideFront = 0L
        fun at(pixels: IntArray, x: Float, y: Float): Int {
            val px = ((x + 1f) * (size - 1) / 2f).toInt()
            val py = ((y + 1f) * (size - 1) / 2f).toInt()
            return pixels[py * size + px] ushr 24
        }
        repeat(PressEffectMasks.SHAPES) { shape ->
            val pixels = PressEffectMasks.fluid(noise, PressEffectMasks.bodySeed(shape), 1f, size)
            val centre = at(pixels, 0f, 0f)
            for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
                diagonalBody += at(pixels, sx * 0.40f, sy * 0.40f)
                centreTotal += centre
                diagonalFront += at(pixels, sx * 0.55f, sy * 0.55f)
                sideFront += at(pixels, sx * 0.55f, 0f)
            }
        }
        assertTrue("The interior colour must be one almost level connected body", diagonalBody.toDouble() / centreTotal >= 0.90)
        assertTrue("The late front retains rounded rectangular corners", diagonalFront.toDouble() / sideFront >= 0.70)
    }

}
