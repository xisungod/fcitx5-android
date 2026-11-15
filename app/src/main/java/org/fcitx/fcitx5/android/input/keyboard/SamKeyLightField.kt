/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Color
import android.graphics.Rect
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Key topology and cached rounded-key halos; no radial/pixel-distance wave front. */
internal class SamKeyLightField(
    private val columns: Int,
    private val rows: Int,
    private val density: Float,
    private val cornerRadius: Float,
    private val expansionMs: Long,
    private val holdMs: Long,
    private val fadeMs: Long,
    private val reach: Float
) {
    data class KeyGeometry(val id: Int, val capBounds: Rect, val touchBounds: Rect)

    private class Stamp(val pixels: IntArray, val coverage: FloatArray)
    private class Schedule {
        var start = Long.MIN_VALUE
        var keyId = -1
        var arrivals = FloatArray(0)
        var gains = FloatArray(0)
        var fallback: Stamp? = null
    }

    private var keys = emptyList<KeyGeometry>()
    private var neighbours = emptyArray<IntArray>()
    private var costs = emptyArray<FloatArray>()
    private var stamps = emptyArray<Stamp>()
    private val schedules = IdentityHashMap<PressEffectTrail.Ripple, Schedule>()
    private var distances = FloatArray(0)
    private var keyLight = FloatArray(0)
    private var keyRed = FloatArray(0)
    private var keyGreen = FloatArray(0)
    private var keyBlue = FloatArray(0)
    private var pitch = density
    private var width = 0
    private var height = 0
    private val light = FloatArray(columns * rows)
    private val red = FloatArray(columns * rows)
    private val green = FloatArray(columns * rows)
    private val blue = FloatArray(columns * rows)
    var visitedSamples = 0
        private set
    var sourceCount = 0
        private set

    fun setGeometry(value: List<KeyGeometry>) {
        if (value.size == keys.size && value.indices.all {
                value[it].id == keys[it].id && value[it].capBounds == keys[it].capBounds &&
                    value[it].touchBounds == keys[it].touchBounds
            }) return
        keys = value.filter { !it.capBounds.isEmpty && !it.touchBounds.isEmpty }.map {
            KeyGeometry(it.id, Rect(it.capBounds), Rect(it.touchBounds))
        }
        pitch = keys.map { min(it.touchBounds.width(), it.touchBounds.height()).toFloat() }
            .sorted().let { if (it.isEmpty()) density else it[it.size / 2].coerceAtLeast(density) }
        val links = Array(keys.size) { ArrayList<Int>() }
        val lengths = Array(keys.size) { ArrayList<Float>() }
        for (a in keys.indices) for (b in a + 1 until keys.size) {
            val x = keys[a].touchBounds
            val y = keys[b].touchBounds
            val overlapX = min(x.right, y.right) - max(x.left, y.left)
            val overlapY = min(x.bottom, y.bottom) - max(x.top, y.top)
            val gapX = max(x.left, y.left) - min(x.right, y.right)
            val gapY = max(x.top, y.top) - min(x.bottom, y.bottom)
            // Shared row/column edges form the graph. A corner alone does not,
            // nor does a nearby key across a genuinely empty/split-keyboard gap.
            val horizontal = overlapY > min(x.height(), y.height()) * 0.25f &&
                gapX >= -pitch * 0.10f && gapX <= pitch * 0.22f
            val vertical = overlapX > min(x.width(), y.width()) * 0.20f &&
                gapY >= -pitch * 0.10f && gapY <= pitch * 0.22f
            if (!horizontal && !vertical) continue
            val dx = x.exactCenterX() - y.exactCenterX()
            val dy = x.exactCenterY() - y.exactCenterY()
            val cost = (sqrt(dx * dx + dy * dy) / pitch).coerceAtLeast(0.6f)
            links[a].add(b); lengths[a].add(cost)
            links[b].add(a); lengths[b].add(cost)
        }
        neighbours = Array(keys.size) { links[it].toIntArray() }
        costs = Array(keys.size) { lengths[it].toFloatArray() }
        distances = FloatArray(keys.size * 5)
        keyLight = FloatArray(keys.size)
        keyRed = FloatArray(keys.size)
        keyGreen = FloatArray(keys.size)
        keyBlue = FloatArray(keys.size)
        stamps = emptyArray()
        schedules.clear()
    }

    fun clear() = schedules.clear()

    /** Expensive graph traversal and halo geometry are cached outside the sample loop. */
    private fun schedule(ripple: PressEffectTrail.Ripple): Schedule {
        val result = schedules[ripple] ?: Schedule().also { schedules[ripple] = it }
        if (result.start == ripple.start && result.keyId == ripple.keyId) return result
        result.start = ripple.start
        result.keyId = ripple.keyId
        val source = keys.indexOfFirst { it.id == ripple.keyId }
        if (source < 0) {
            // Before the first real layout there is no topology to invent.
            result.arrivals = FloatArray(0)
            result.gains = FloatArray(0)
            result.fallback = stamp(Rect((ripple.x - ripple.flashWidth / 2).toInt(),
                (ripple.y - ripple.flashHeight / 2).toInt(), (ripple.x + ripple.flashWidth / 2).toInt(),
                (ripple.y + ripple.flashHeight / 2).toInt()))
            return result
        }
        result.fallback = null
        if (result.arrivals.size != keys.size) {
            result.arrivals = FloatArray(keys.size)
            result.gains = FloatArray(keys.size)
        }
        java.util.Arrays.fill(result.arrivals, Float.POSITIVE_INFINITY)
        java.util.Arrays.fill(result.gains, 0f)
        java.util.Arrays.fill(distances, Float.POSITIVE_INFINITY)
        distances[source] = 0f
        val budget = (3 + (ripple.shape and 1)) * reach.coerceIn(0.1f, 1f)
        val hops = ceil(budget).toInt().coerceIn(1, 4)
        // Layered shortest paths enforce a true hop budget even around wide
        // space/function keys. A weighted cost alone could visit too many keys.
        for (hop in 0..hops) for (key in keys.indices) {
            val cost = distances[hop * keys.size + key]
            if (!cost.isFinite()) continue
            val arrival = (cost * expansionMs / 4f).coerceAtMost(expansionMs.toFloat())
            if (arrival < result.arrivals[key]) {
                result.arrivals[key] = arrival
                result.gains[key] = if (hop == 0) 1f else
                    (variation(ripple.keyId, keys[key].id, ripple.shape) / (1f + 0.16f * hop) *
                        (budget - hop + 1f).coerceIn(0f, 1f)).coerceAtMost(1f)
            }
            if (hop == hops) continue
            for (edge in neighbours[key].indices) {
                val next = neighbours[key][edge]
                val perturbed = costs[key][edge] * variation(keys[key].id, keys[next].id, ripple.echoShape)
                val slot = (hop + 1) * keys.size + next
                distances[slot] = min(distances[slot], cost + perturbed)
            }
        }
        return result
    }

    /** Stable, bounded ±10% variation, derived only from the press and graph edge. */
    private fun variation(from: Int, to: Int, seed: Int): Float {
        var value = from * 0x45d9f3b xor to * 0x119de1f3 xor seed * 0x27d4eb2d
        value = (value xor (value ushr 16)) * 0x45d9f3b
        value = value xor (value ushr 16)
        return 0.90f + (value and 1023) * (0.20f / 1023f)
    }

    private fun stamp(rect: Rect): Stamp {
        val cellWidth = width.toFloat() / columns
        val cellHeight = height * 1.25f / rows
        val top = -height * 0.25f
        val halfWidth = rect.width() / 2f
        val halfHeight = rect.height() / 2f
        val radius = cornerRadius.coerceIn(0f, min(halfWidth, halfHeight))
        val halo = max(2f * density, min(rect.width(), rect.height()) * 0.30f)
        val x0 = ((rect.left - halo) / cellWidth).toInt().coerceIn(0, columns - 1)
        val x1 = ((rect.right + halo) / cellWidth).toInt().coerceIn(0, columns - 1)
        val y0 = ((rect.top - halo - top) / cellHeight).toInt().coerceIn(0, rows - 1)
        val y1 = ((rect.bottom + halo - top) / cellHeight).toInt().coerceIn(0, rows - 1)
        val indices = IntArray((x1 - x0 + 1) * (y1 - y0 + 1))
        val coverage = FloatArray(indices.size)
        var count = 0
        for (y in y0..y1) for (x in x0..x1) {
            val dx = abs((x + 0.5f) * cellWidth - rect.exactCenterX()) - halfWidth + radius
            val dy = abs(top + (y + 0.5f) * cellHeight - rect.exactCenterY()) - halfHeight + radius
            val distance = if (dx > 0f && dy > 0f) sqrt(dx * dx + dy * dy) - radius
                else max(dx, dy) - radius
            val alpha = 1f - smooth(0f, halo, distance)
            if (alpha <= 0f) continue
            indices[count] = y * columns + x
            coverage[count] = alpha * alpha * alpha
            count++
        }
        return Stamp(indices.copyOf(count), coverage.copyOf(count))
    }

    fun render(trail: PressEffectTrail, colours: IntArray, time: Long, hostWidth: Int,
        hostHeight: Int, glowOnCandidates: Boolean, pixels: IntArray) {
        if (hostWidth != width || hostHeight != height || stamps.size != keys.size) {
            width = hostWidth
            height = hostHeight
            stamps = Array(keys.size) { stamp(keys[it].capBounds) }
            schedules.clear()
        }
        java.util.Arrays.fill(light, 0f)
        java.util.Arrays.fill(red, 0f)
        java.util.Arrays.fill(green, 0f)
        java.util.Arrays.fill(blue, 0f)
        java.util.Arrays.fill(keyLight, 0f)
        java.util.Arrays.fill(keyRed, 0f)
        java.util.Arrays.fill(keyGreen, 0f)
        java.util.Arrays.fill(keyBlue, 0f)
        visitedSamples = 0
        sourceCount = 0
        for (i in 0 until trail.size) {
            val ripple = trail[i]
            val age = (time - ripple.start).toFloat()
            if (age < 0 || age >= expansionMs + holdMs + fadeMs) continue
            val propagation = schedule(ripple)
            val colour = colours[ripple.colorIndex]
            val r = Color.red(colour) / 255f
            val g = Color.green(colour) / 255f
            val b = Color.blue(colour) / 255f
            sourceCount++
            val fallback = propagation.fallback
            if (fallback != null) {
                // A pre-layout press only needs its latest source halo; the
                // independently rendered key face already supplies immediate feedback.
                if (i == trail.size - 1) {
                    val e = envelope(age)
                    accumulate(fallback, e * e * e, r, g, b)
                }
                continue
            }
            for (key in keys.indices) {
                val localAge = age - propagation.arrivals[key]
                if (localAge < 0) continue
                val exposure = envelope(localAge) * propagation.gains[key]
                if (exposure <= 0.001f) continue
                val energy = exposure * exposure * exposure
                keyLight[key] += energy
                keyRed[key] += energy * r
                keyGreen[key] += energy * g
                keyBlue[key] += energy * b
            }
        }
        // Aggregate all waves at key level first. Each cached halo is stamped
        // once per frame, irrespective of how many presses overlap this key.
        for (key in keys.indices) {
            val energy = keyLight[key]
            if (energy <= 0.000000001f) continue
            accumulate(stamps[key], energy.coerceAtMost(1f), keyRed[key] / energy,
                keyGreen[key] / energy, keyBlue[key] / energy)
        }
        for (y in 0 until rows) {
            val logicalY = -height * 0.25f + (y + 0.5f) * height * 1.25f / rows
            val candidate = if (glowOnCandidates) 0.13f + 0.87f * smooth(0f, 32f * density, logicalY)
                else if (logicalY < 0) 0f else 1f
            for (x in 0 until columns) {
                val index = y * columns + x
                val w = light[index]
                if (w <= 0.000000001f || candidate <= 0) { pixels[index] = 0; continue }
                val exposure = Math.cbrt(w.toDouble()).toFloat().coerceAtMost(1f) * candidate
                pixels[index] = ((exposure * 255).toInt() shl 24) or
                    ((red[index] / w * 255).toInt().coerceIn(0, 255) shl 16) or
                    ((green[index] / w * 255).toInt().coerceIn(0, 255) shl 8) or
                    (blue[index] / w * 255).toInt().coerceIn(0, 255)
            }
        }
    }

    private fun envelope(age: Float): Float = smooth(0f, 32f, age) *
        (1f - smooth(holdMs.toFloat(), (holdMs + fadeMs).toFloat(), age))

    private fun accumulate(stamp: Stamp, energy: Float, r: Float, g: Float, b: Float) {
        for (i in stamp.pixels.indices) {
            visitedSamples++
            val w = energy * stamp.coverage[i]
            val index = stamp.pixels[i]
            light[index] += w
            red[index] += w * r
            green[index] += w * g
            blue[index] += w * b
        }
    }

    private fun smooth(start: Float, end: Float, value: Float): Float {
        val x = ((value - start) / (end - start)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}
