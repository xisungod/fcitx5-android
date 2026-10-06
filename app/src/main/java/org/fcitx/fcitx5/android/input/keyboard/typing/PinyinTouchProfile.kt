/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bounded numeric statistics. observeConfirmed must only receive a letter the
 * user explicitly confirmed, never the decoder's prediction or an inferred edit.
 * A profile retains no spelling, editor identity, timestamps or individual taps.
 */
class PinyinTouchProfile {
    data class Statistics(
        val count: Int,
        val meanX: Double,
        val meanY: Double,
        val m2X: Double,
        val m2Y: Double
    ) {
        internal fun isValid() = count in 1..MAXIMUM_COUNT &&
            listOf(meanX, meanY, m2X, m2Y).all { it.isFinite() } &&
            abs(meanX) <= MAX_SAMPLE_OFFSET && abs(meanY) <= MAX_SAMPLE_OFFSET &&
            m2X in 0.0..(count * 4.0) && m2Y in 0.0..(count * 4.0)
    }

    private val layouts = LinkedHashMap<String, MutableMap<Char, Statistics>>()

    fun offsets(cells: List<KeyCell>, density: Float): Map<Char, CenterOffset> {
        val key = layoutSignature(cells, density) ?: return emptyMap()
        val statistics = layouts[key] ?: return emptyMap()
        return statistics.mapNotNull { (letter, sample) ->
            if (sample.count < MINIMUM_CONFIRMED_COUNT) null else {
                val shrinkage = sample.count.toDouble() / (sample.count + PRIOR_COUNT)
                letter to CenterOffset(
                    (sample.meanX.coerceIn(-MAX_OFFSET, MAX_OFFSET) * shrinkage).toFloat(),
                    (sample.meanY.coerceIn(-MAX_OFFSET, MAX_OFFSET) * shrinkage).toFloat()
                )
            }
        }.toMap()
    }

    fun confirmedSampleCount(cells: List<KeyCell>, density: Float, letter: Char): Int =
        layoutSignature(cells, density)?.let { layouts[it]?.get(letter)?.count } ?: 0

    fun observeConfirmed(cells: List<KeyCell>, tap: TapEvidence, intended: Char): Boolean {
        val key = layoutSignature(cells, tap.density) ?: return false
        if (!tap.downX.isFinite() || !tap.downY.isFinite() || intended !in 'a'..'z') return false
        val original = cells.firstOrNull { it.letter == tap.original } ?: return false
        if (!original.contains(tap.downX, tap.downY)) return false
        val cell = cells.firstOrNull { it.letter == intended } ?: return false
        val x = (tap.downX.toDouble() - cell.centerX) / cell.width
        val y = (tap.downY.toDouble() - cell.centerY) / cell.height
        // Confirmation cannot align a far-away key or a stale layout to this tap.
        if (!x.isFinite() || !y.isFinite() || abs(x) > MAX_SAMPLE_OFFSET || abs(y) > MAX_SAMPLE_OFFSET) return false
        val values = layouts.remove(key) ?: linkedMapOf()
        var old = values[intended]
        if (old != null && old.count == MAXIMUM_COUNT) {
            // Discount the oldest aggregate after enough evidence, without retaining taps.
            old = old.copy(count = old.count / 2, m2X = old.m2X / 2, m2Y = old.m2Y / 2)
        }
        val updated = if (old == null) Statistics(1, x, y, 0.0, 0.0) else {
            val count = old.count + 1
            val dx = x - old.meanX
            val dy = y - old.meanY
            val meanX = old.meanX + dx / count
            val meanY = old.meanY + dy / count
            Statistics(count, meanX, meanY,
                (old.m2X + dx * (x - meanX)).coerceAtLeast(0.0),
                (old.m2Y + dy * (y - meanY)).coerceAtLeast(0.0))
        }
        values[intended] = updated
        layouts[key] = values
        while (layouts.size > MAXIMUM_LAYOUTS) layouts.remove(layouts.keys.first())
        return true
    }

    fun snapshot(): Map<String, Map<Char, Statistics>> = layouts.mapValues { it.value.toMap() }

    fun restore(snapshot: Map<String, Map<Char, Statistics>>) {
        layouts.clear()
        for ((layout, statistics) in snapshot.entries.toList().takeLast(MAXIMUM_LAYOUTS)) {
            if (!layout.matches(Regex("[a-f0-9]{64}"))) continue
            val valid = statistics.filter { (letter, data) -> letter in 'a'..'z' && data.isValid() }
            if (valid.isNotEmpty()) layouts[layout] = valid.toMutableMap()
        }
    }

    fun clear() = layouts.clear()

    companion object {
        const val MAXIMUM_LAYOUTS = 16
        const val MINIMUM_CONFIRMED_COUNT = 8
        private const val PRIOR_COUNT = 20
        private const val MAXIMUM_COUNT = 4096
        private const val MAX_SAMPLE_OFFSET = 0.65
        private const val MAX_OFFSET = 0.12

        /** Geometry itself separates portrait, landscape, dimensions and custom key widths. */
        fun layoutSignature(cells: List<KeyCell>, density: Float): String? {
            if (!density.isFinite() || density <= 0f || cells.isEmpty() ||
                cells.any { !it.isValid() } || cells.map { it.letter }.toSet().size != cells.size) return null
            val text = buildString {
                append("pinyin-touch-layout-v1;")
                for (cell in cells.sortedBy { it.letter }) {
                    append(cell.letter)
                    for (coordinate in listOf(cell.left, cell.top, cell.right, cell.bottom)) {
                        val scaled = coordinate.toDouble() / density * 2
                        if (!scaled.isFinite() || scaled < Int.MIN_VALUE || scaled > Int.MAX_VALUE) return null
                        append(':'); append(scaled.roundToInt()) // nearest half dp
                    }
                    append(';')
                }
            }
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.US_ASCII))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
