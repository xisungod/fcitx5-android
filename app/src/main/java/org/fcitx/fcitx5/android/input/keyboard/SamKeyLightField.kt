/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Color
import android.graphics.Rect
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Light follows connected keys, with a travelling front inside each rounded-key halo. */
internal class SamKeyLightField(
    private val columns: Int,
    private val rows: Int,
    private val density: Float,
    private val cornerRadius: Float,
    private val expansionMs: Long,
    private val holdMs: Long,
    private val fadeMs: Long,
    reach: Float,
    private val size: Float
) {
    private companion object {
        val HOP_EXPOSURE = floatArrayOf(1f, 0.78f, 0.60f, 0.45f, 0.33f, 0.24f, 0.17f, 0.12f)
        const val DEFAULT_HOPS = 6
        const val MAX_HOPS = 8
        const val EXPOSURE_BUDGET = 9f
        const val DIRECTIONS = 64
        const val BANDS = 17
        const val CENTRE_BAND = BANDS / 2
        const val TIMING_SIDE = 9
        const val TIMING_PIXELS = TIMING_SIDE * TIMING_SIDE
        const val HUE_STEPS = 256
        val centreProjection = ByteArray(TIMING_PIXELS) { CENTRE_BAND.toByte() }
        val directionX = FloatArray(DIRECTIONS) { cos(it * (2 * Math.PI / DIRECTIONS)).toFloat() }
        val directionY = FloatArray(DIRECTIONS) { sin(it * (2 * Math.PI / DIRECTIONS)).toFloat() }
        val hueChannels = FloatArray(6 * HUE_STEPS) { i ->
            (abs(i.toFloat() / HUE_STEPS - 3f) - 1f).coerceIn(0f, 1f)
        }
        // Positive normal Float energy = mantissa * 2^(exponent - 127).
        // The quantized mantissa changes the final 8-bit alpha by at most one.
        val cubeRootScale = FloatArray(127) { Math.cbrt(Math.scalb(1.0, it - 127)).toFloat() }
        val cubeRootMantissa = FloatArray(256) { Math.cbrt(1 + it / 256.0).toFloat() }
    }

    data class KeyGeometry(val id: Int, val capBounds: Rect, val touchBounds: Rect)

    private class Stamp(val pixels: IntArray, val coverage: FloatArray,
        val topLeft: IntArray, val blendX: FloatArray, val blendY: FloatArray,
        val halfWidth: Float, val halfHeight: Float, val halo: Float) {
        private val projections = arrayOfNulls<ByteArray>(DIRECTIONS)
        val light = FloatArray(TIMING_PIXELS)
        val red = light.copyOf()
        val green = light.copyOf()
        val blue = light.copyOf()
        val saturation = light.copyOf()
        val hue = light.copyOf()

        fun projection(direction: Int): ByteArray = projections[direction] ?: run {
            val dx = directionX[direction]
            val dy = directionY[direction]
            val span = (abs(dx) * halfWidth + abs(dy) * halfHeight).coerceAtLeast(0.5f) * 2f
            val horizontal = (halfWidth + halo) * dx / span
            val vertical = (halfHeight + halo) * dy / span
            ByteArray(TIMING_PIXELS) { i ->
                val x = 2f * (i % TIMING_SIDE) / (TIMING_SIDE - 1) - 1f
                val y = 2f * (i / TIMING_SIDE) / (TIMING_SIDE - 1) - 1f
                val fraction = (x * horizontal + y * vertical + 0.5f).coerceIn(0f, 1f)
                (fraction * (BANDS - 1)).roundToInt().toByte()
            }.also { projections[direction] = it }
        }

        fun clearFrame() {
            light.fill(0f)
            red.fill(0f)
            green.fill(0f)
            blue.fill(0f)
            saturation.fill(0f)
            hue.fill(0f)
        }
    }

    private data class Pigment(val red: Float, val green: Float, val blue: Float,
        val saturation: Float, var hue: Float)
    private class Schedule {
        var sequence = Long.MIN_VALUE
        var pathCosts = FloatArray(0)
        var arrivals = FloatArray(0)
        var gains = FloatArray(0)
        var projections = emptyArray<ByteArray?>()
        var fallback: Stamp? = null
    }

    private val hopBudget = if (reach <= 1f) DEFAULT_HOPS * reach
        else DEFAULT_HOPS + (MAX_HOPS - DEFAULT_HOPS) * (reach - 1f) / 0.5f
    private val hops = ceil(hopBudget).toInt().coerceIn(1, MAX_HOPS)
    private val ringMs = expansionMs / max(DEFAULT_HOPS.toFloat(), hopBudget)
    private val riseMs = ringMs * 1.75f
    private val halfRingMs = ringMs * 0.5f
    private val bandDelay = FloatArray(BANDS) { (it.toFloat() / (BANDS - 1) - 0.5f) * ringMs }
    private val bandEnergy = FloatArray(BANDS)
    private val lifetime = expansionMs + holdMs + fadeMs
    private val envelopeSamples = FloatArray((holdMs + fadeMs + 1).toInt()) { ms ->
        val progress = (ms / riseMs).coerceAtMost(1f)
        // Limit the initial slope, then smoothly ease out as the halo reaches
        // full strength. Several adjacent keys can still be rising at once.
        val remaining = 1f - progress
        val ignition = if (progress <= 2f / 3f) 1.2f * progress else 1f - 1.8f * remaining * remaining
        ignition * samLightFade(ms.toFloat(), holdMs, fadeMs)
    }

    private var keys = emptyList<KeyGeometry>()
    private var neighbours = emptyArray<IntArray>()
    private var costs = emptyArray<FloatArray>()
    private var stamps = emptyArray<Stamp>()
    private val schedules = IdentityHashMap<PressEffectTrail.Ripple, Schedule>()
    private var distances = FloatArray(0)
    private var keyLight = FloatArray(0)
    private var keyPeak = FloatArray(0)
    private var keyExposure = FloatArray(0)
    private var pitch = density
    private var width = 0
    private var height = 0
    private val light = FloatArray(columns * rows)
    private val red = FloatArray(columns * rows)
    private val green = FloatArray(columns * rows)
    private val blue = FloatArray(columns * rows)
    private val saturation = FloatArray(columns * rows)
    private val hue = FloatArray(columns * rows)
    private var palette = IntArray(0)
    private var pigments = emptyArray<Pigment>()
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
        distances = FloatArray(keys.size * (hops + 1))
        keyLight = FloatArray(keys.size)
        keyPeak = FloatArray(keys.size)
        keyExposure = FloatArray(keys.size)
        stamps = emptyArray()
        schedules.clear()
    }

    fun clear() = schedules.clear()

    /** Expensive graph traversal and halo geometry are cached outside the sample loop. */
    private fun schedule(ripple: PressEffectTrail.Ripple): Schedule {
        val result = schedules[ripple] ?: Schedule().also { schedules[ripple] = it }
        if (result.sequence == ripple.sequence) return result
        result.sequence = ripple.sequence
        val source = keys.indexOfFirst { it.id == ripple.keyId }
        if (source < 0) {
            // Before the first real layout there is no topology to invent.
            result.fallback = stamp(Rect((ripple.x - ripple.flashWidth / 2).toInt(),
                (ripple.y - ripple.flashHeight / 2).toInt(), (ripple.x + ripple.flashWidth / 2).toInt(),
                (ripple.y + ripple.flashHeight / 2).toInt()))
            return result
        }
        result.fallback = null
        if (result.arrivals.size != keys.size) {
            result.pathCosts = FloatArray(keys.size)
            result.arrivals = FloatArray(keys.size)
            result.gains = FloatArray(keys.size)
            result.projections = arrayOfNulls(keys.size)
        }
        result.pathCosts.fill(Float.POSITIVE_INFINITY)
        result.projections.fill(null)
        distances.fill(Float.POSITIVE_INFINITY)
        distances[source] = 0f
        // Layered shortest paths enforce a true hop budget even around wide
        // space/function keys. A weighted cost alone could visit too many keys.
        for (hop in 0..hops) for (key in keys.indices) {
            val cost = distances[hop * keys.size + key]
            if (!cost.isFinite()) continue
            if (cost < result.pathCosts[key]) {
                result.pathCosts[key] = cost
                result.gains[key] = if (hop == 0) 1f else
                    HOP_EXPOSURE[min(hop, HOP_EXPOSURE.lastIndex)] *
                        variation(ripple.keyId, keys[key].id, ripple.shape) *
                        (hopBudget - hop + 1f).coerceIn(0f, 1f)
            }
            if (hop == hops) continue
            for (edge in neighbours[key].indices) {
                val next = neighbours[key][edge]
                val perturbed = costs[key][edge] * variation(keys[key].id, keys[next].id, ripple.echoShape)
                val slot = (hop + 1) * keys.size + next
                distances[slot] = min(distances[slot], cost + perturbed)
            }
        }
        var maxCost = 0f
        for (cost in result.pathCosts) if (cost.isFinite()) maxCost = max(maxCost, cost)
        for (key in keys.indices) {
            val cost = result.pathCosts[key]
            if (!cost.isFinite()) continue
            if (key == source) {
                result.arrivals[key] = 0f
                result.projections[key] = centreProjection
            } else {
                // Invert distance(t) = 1 - (1 - t)^3 so the visible front
                // slows down. Leave half an interval at each end for the sweep.
                val position = (cost / maxCost).coerceIn(0f, 1f)
                val progress = 1f - Math.cbrt((1f - position).toDouble()).toFloat()
                result.arrivals[key] = halfRingMs + (expansionMs - ringMs) * progress
                val cap = keys[key].capBounds
                val angle = atan2(cap.exactCenterY() - ripple.y, cap.exactCenterX() - ripple.x)
                val direction = (angle * (DIRECTIONS / (2 * Math.PI))).roundToInt().mod(DIRECTIONS)
                result.projections[key] = stamps[key].projection(direction)
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
        val shortSide = min(rect.width(), rect.height()).toFloat()
        // The cap keeps its own corner shape. Give the light a rounder outline so
        // the intersecting gutters at its corners do not show four bright points.
        val radius = min(max(cornerRadius, shortSide * 0.34f), shortSide * 0.5f)
        val halo = max(2f * density, shortSide * 0.55f * size)
        val x0 = ((rect.left - halo) / cellWidth).toInt().coerceIn(0, columns - 1)
        val x1 = ((rect.right + halo) / cellWidth).toInt().coerceIn(0, columns - 1)
        val y0 = ((rect.top - halo - top) / cellHeight).toInt().coerceIn(0, rows - 1)
        val y1 = ((rect.bottom + halo - top) / cellHeight).toInt().coerceIn(0, rows - 1)
        val indices = IntArray((x1 - x0 + 1) * (y1 - y0 + 1))
        val coverage = FloatArray(indices.size)
        val topLeft = IntArray(indices.size)
        val blendX = FloatArray(indices.size)
        val blendY = FloatArray(indices.size)
        val timingScaleX = (TIMING_SIDE - 1) / (2f * (halfWidth + halo))
        val timingScaleY = (TIMING_SIDE - 1) / (2f * (halfHeight + halo))
        var count = 0
        for (y in y0..y1) for (x in x0..x1) {
            // Four samples amortized over the cached stamp soften pixel-phase
            // changes at low exposure without a full-frame blur on each draw.
            val left = (x + 0.25f) * cellWidth - rect.exactCenterX()
            val upper = top + (y + 0.25f) * cellHeight - rect.exactCenterY()
            val right = left + cellWidth * 0.5f
            val lower = upper + cellHeight * 0.5f
            val alpha = 0.25f * (
                coverageAt(left, upper, halfWidth, halfHeight, radius, halo) +
                    coverageAt(right, upper, halfWidth, halfHeight, radius, halo) +
                    coverageAt(left, lower, halfWidth, halfHeight, radius, halo) +
                    coverageAt(right, lower, halfWidth, halfHeight, radius, halo))
            if (alpha <= 0f) continue
            indices[count] = y * columns + x
            coverage[count] = alpha
            val u = ((left + right) * 0.5f * timingScaleX + (TIMING_SIDE - 1) * 0.5f)
                .coerceIn(0f, (TIMING_SIDE - 1).toFloat())
            val v = ((upper + lower) * 0.5f * timingScaleY + (TIMING_SIDE - 1) * 0.5f)
                .coerceIn(0f, (TIMING_SIDE - 1).toFloat())
            val col = u.toInt().coerceAtMost(TIMING_SIDE - 2)
            val row = v.toInt().coerceAtMost(TIMING_SIDE - 2)
            topLeft[count] = row * TIMING_SIDE + col
            blendX[count] = u - col
            blendY[count] = v - row
            count++
        }
        return Stamp(indices.copyOf(count), coverage.copyOf(count), topLeft.copyOf(count), blendX.copyOf(count),
            blendY.copyOf(count), halfWidth, halfHeight, halo)
    }

    private fun coverageAt(x: Float, y: Float, halfWidth: Float, halfHeight: Float,
        radius: Float, halo: Float): Float {
        val dx = abs(x) - halfWidth + radius
        val dy = abs(y) - halfHeight + radius
        val distance = if (dx > 0f && dy > 0f) sqrt(dx * dx + dy * dy) - radius
            else max(dx, dy) - radius
        val alpha = 1f - smooth(0f, halo, distance)
        return alpha * alpha
    }

    private fun setPalette(colours: IntArray) {
        if (palette.contentEquals(colours)) return
        palette = colours.copyOf()
        pigments = Array(colours.size) { i ->
            val colour = colours[i]
            val r = Color.red(colour) / 255f
            val g = Color.green(colour) / 255f
            val b = Color.blue(colour) / 255f
            val peak = maxOf(r, g, b)
            val chroma = peak - minOf(r, g, b)
            Pigment(r, g, b, if (peak > 0f) chroma / peak else 0f,
                if (chroma > 0f) hueOf(r, g, b, peak, chroma) else 0f)
        }
        // This hint is only used near a neutral RGB mix, where hue is ambiguous.
        // Fix the hue seam in the palette's largest empty arc, independent of
        // which wave currently wins. Nearly complementary waves then crossfade
        // instead of picking a different dominant hue on each side of a tie.
        val hues = pigments.filter { it.saturation > 0f }.map { it.hue }.sorted()
        var seam = 0f
        var gap = -1f
        for (i in hues.indices) {
            val next = if (i == hues.lastIndex) hues[0] + 6f else hues[i + 1]
            if (next - hues[i] > gap) {
                gap = next - hues[i]
                seam = (hues[i] + gap * 0.5f) % 6f
            }
        }
        for (pigment in pigments) if (pigment.hue < seam) pigment.hue += 6f
    }

    fun render(trail: PressEffectTrail, colours: IntArray, time: Long, hostWidth: Int,
        hostHeight: Int, glowOnCandidates: Boolean, pixels: IntArray) {
        if (hostWidth <= 0 || hostHeight <= 0) {
            pixels.fill(0)
            visitedSamples = 0
            sourceCount = 0
            return
        }
        setPalette(colours)
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
        java.util.Arrays.fill(saturation, 0f)
        java.util.Arrays.fill(hue, 0f)
        keyLight.fill(0f)
        keyPeak.fill(0f)
        for (stamp in stamps) stamp.clearFrame()
        visitedSamples = 0
        sourceCount = 0
        var strongestWaveExposure = 0f
        for (i in 0 until trail.size) {
            val ripple = trail[i]
            val age = (time - ripple.start).toFloat()
            if (age < 0 || age >= lifetime) continue
            val propagation = schedule(ripple)
            val colour = pigments[ripple.colorIndex]
            sourceCount++
            val fallback = propagation.fallback
            if (fallback != null) {
                // A pre-layout press only needs its latest source halo; the
                // independently rendered key face already supplies immediate feedback.
                if (i == trail.size - 1) {
                    val e = envelope(age)
                    accumulate(fallback, e * e * e, colour.red, colour.green, colour.blue,
                        colour.saturation, colour.hue * colour.saturation)
                }
                continue
            }
            var waveExposure = 0f
            val weightedHue = colour.saturation * colour.hue
            for (key in keys.indices) {
                val projection = propagation.projections[key] ?: continue
                val localAge = age - propagation.arrivals[key]
                if (localAge + halfRingMs <= 0f || localAge - halfRingMs >= holdMs + fadeMs) continue
                val gain = propagation.gains[key]
                val centred = projection === centreProjection
                val firstBand = if (centred) CENTRE_BAND else 0
                val lastBand = if (centred) CENTRE_BAND else BANDS - 1
                val bandCount = lastBand - firstBand + 1
                var sum = 0f
                var peak = 0f
                var sumExposure = 0f
                for (band in firstBand..lastBand) {
                    val exposure = envelope(localAge - bandDelay[band]) * gain
                    val energy = exposure * exposure * exposure
                    bandEnergy[band] = energy
                    sum += energy
                    peak = max(peak, energy)
                    sumExposure += exposure
                }
                if (peak <= 0.000000001f) continue
                val stamp = stamps[key]
                // All waves mix on each key's timing raster. Interpolate it
                // onto the halo once, with the original fine coverage mask.
                for (sample in projection.indices) {
                    val energy = bandEnergy[projection[sample].toInt()]
                    stamp.light[sample] += energy
                    stamp.red[sample] += energy * colour.red
                    stamp.green[sample] += energy * colour.green
                    stamp.blue[sample] += energy * colour.blue
                    stamp.saturation[sample] += energy * colour.saturation
                    stamp.hue[sample] += energy * weightedHue
                }
                keyLight[key] += sum / bandCount
                keyPeak[key] += peak
                waveExposure += sumExposure / bandCount
            }
            strongestWaveExposure = max(strongestWaveExposure, waveExposure)
        }
        var totalExposure = 0f
        for (key in keys.indices) {
            val exposure = Math.cbrt(keyLight[key].coerceAtMost(1f).toDouble()).toFloat()
            keyExposure[key] = exposure
            totalExposure += exposure
        }
        // The broader wave still shares the same exposure budget. Thin out
        // weak tails as other waves overlap, without punching holes in a single wave.
        // A soft knee reaches the fixed cap without changing the growth rate
        // abruptly in the frame where the field first fills the budget.
        val knee = EXPOSURE_BUDGET * 0.28f
        val overKnee = totalExposure - (EXPOSURE_BUDGET - knee)
        val scale = when {
            overKnee <= 0f -> 1f
            overKnee >= 2f * knee -> EXPOSURE_BUDGET / totalExposure
            else -> 1f - overKnee * overKnee / (4f * knee * totalExposure)
        }
        val crowding = smooth(0f, EXPOSURE_BUDGET * 0.7f, totalExposure - strongestWaveExposure)
        for (key in keys.indices) {
            val energy = keyLight[key]
            if (energy <= 0.000000001f) continue
            val exposure = keyExposure[key]
            val tail = if (crowding > 0f) smooth(0.34f * crowding, 0.82f * crowding, exposure) else 1f
            val dim = scale * tail
            if (exposure * dim <= 0.001f) continue
            val energyScale = dim * dim * dim / keyPeak[key].coerceAtLeast(1f)
            accumulate(stamps[key], energyScale)
        }
        for (y in 0 until rows) {
            val logicalY = -height * 0.25f + (y + 0.5f) * height * 1.25f / rows
            val candidate = if (glowOnCandidates) 0.13f + 0.87f * smooth(0f, 32f * density, logicalY)
                else if (logicalY < 0) 0f else 1f
            for (x in 0 until columns) {
                val index = y * columns + x
                val w = light[index]
                if (w <= 0.000000001f || candidate <= 0) { pixels[index] = 0; continue }
                val exposure = exposureOf(w) * candidate
                val inverse = 1f / w
                val rgb = saturatedRgb(red[index] * inverse, green[index] * inverse, blue[index] * inverse,
                    saturation[index] * inverse, hue[index], saturation[index])
                pixels[index] = ((exposure * 255).toInt() shl 24) or rgb
            }
        }
    }

    private fun envelope(age: Float): Float {
        if (age <= 0f || age >= envelopeSamples.lastIndex) return 0f
        val index = age.toInt()
        val start = envelopeSamples[index]
        return start + (envelopeSamples[index + 1] - start) * (age - index)
    }

    private fun exposureOf(energy: Float): Float {
        if (energy >= 1f) return 1f
        val bits = energy.toRawBits()
        return cubeRootScale[bits ushr 23] * cubeRootMantissa[(bits ushr 15) and 255]
    }

    private fun accumulate(stamp: Stamp, energyScale: Float) {
        for (i in stamp.pixels.indices) {
            visitedSamples++
            val scale = energyScale * stamp.coverage[i]
            val x = stamp.blendX[i]
            val y = stamp.blendY[i]
            val top = stamp.topLeft[i]
            val bottom = top + TIMING_SIDE
            val leftTop = (1f - x) * (1f - y) * scale
            val rightTop = x * (1f - y) * scale
            val leftBottom = (1f - x) * y * scale
            val rightBottom = x * y * scale
            val pixel = stamp.pixels[i]
            light[pixel] += sample(stamp.light, top, bottom, leftTop, rightTop, leftBottom, rightBottom)
            red[pixel] += sample(stamp.red, top, bottom, leftTop, rightTop, leftBottom, rightBottom)
            green[pixel] += sample(stamp.green, top, bottom, leftTop, rightTop, leftBottom, rightBottom)
            blue[pixel] += sample(stamp.blue, top, bottom, leftTop, rightTop, leftBottom, rightBottom)
            saturation[pixel] += sample(stamp.saturation, top, bottom, leftTop, rightTop, leftBottom, rightBottom)
            hue[pixel] += sample(stamp.hue, top, bottom, leftTop, rightTop, leftBottom, rightBottom)
        }
    }

    private fun sample(values: FloatArray, top: Int, bottom: Int, leftTop: Float, rightTop: Float,
        leftBottom: Float, rightBottom: Float): Float =
        values[top] * leftTop + values[top + 1] * rightTop +
            values[bottom] * leftBottom + values[bottom + 1] * rightBottom

    private fun accumulate(stamp: Stamp, energy: Float, r: Float, g: Float, b: Float,
        sat: Float, weightedHue: Float) {
        for (i in stamp.pixels.indices) {
            visitedSamples++
            val w = energy * stamp.coverage[i]
            val index = stamp.pixels[i]
            light[index] += w
            red[index] += w * r
            green[index] += w * g
            blue[index] += w * b
            saturation[index] += w * sat
            hue[index] += w * weightedHue
        }
    }

    private fun saturatedRgb(r: Float, g: Float, b: Float, sourceSaturation: Float,
        hueWeight: Float, saturationWeight: Float): Int {
        val peak = maxOf(r, g, b)
        val chroma = peak - minOf(r, g, b)
        val saturation = sourceSaturation.coerceIn(0f, 1f)
        val targetChroma = peak * saturation
        if (targetChroma - chroma < 0.001f) return packRgb(r, g, b)
        // Keep the existing peak and apply the light field's independent alpha
        // afterwards: preserving saturation cannot raise the brightness budget.
        if (chroma >= targetChroma * 0.40f) {
            val spread = targetChroma / chroma
            return packRgb(peak - (peak - r) * spread, peak - (peak - g) * spread, peak - (peak - b) * spread)
        }
        val hint = (hueWeight / saturationWeight) % 6f
        val measured = if (chroma > 0.000001f) hueOf(r, g, b, peak, chroma) else hint
        var delta = measured - hint
        if (delta > 3f) delta -= 6f
        if (delta < -3f) delta += 6f
        val h = (hint + delta * smooth(0f, targetChroma * 0.40f, chroma) + 6f) % 6f
        val base = peak - targetChroma
        val index = (h * HUE_STEPS).toInt()
        return packRgb(base + targetChroma * hueChannels[index],
            base + targetChroma * hueChannels[(index + 4 * HUE_STEPS) % hueChannels.size],
            base + targetChroma * hueChannels[(index + 2 * HUE_STEPS) % hueChannels.size])
    }

    private fun hueOf(r: Float, g: Float, b: Float, peak: Float, chroma: Float): Float {
        val hue = when (peak) {
            r -> (g - b) / chroma
            g -> 2f + (b - r) / chroma
            else -> 4f + (r - g) / chroma
        }
        return if (hue < 0f) hue + 6f else hue
    }

    private fun packRgb(r: Float, g: Float, b: Float): Int =
        ((r * 255f).toInt().coerceIn(0, 255) shl 16) or ((g * 255f).toInt().coerceIn(0, 255) shl 8) or
            (b * 255f).toInt().coerceIn(0, 255)

    private fun smooth(start: Float, end: Float, value: Float): Float {
        val x = ((value - start) / (end - start)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}
