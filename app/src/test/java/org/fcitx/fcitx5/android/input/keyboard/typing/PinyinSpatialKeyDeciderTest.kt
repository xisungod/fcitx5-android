/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PinyinSpatialKeyDeciderTest {
    private val cells = listOf(
        row("qwertyuiop", 0f, 0f),
        row("asdfghjkl", 50f, 140f),
        row("zxcvbnm", 150f, 280f)
    ).flatten()

    private fun row(letters: String, left: Float, top: Float) = letters.mapIndexed { i, letter ->
        KeyCell(letter, left + i * 100f, top, left + (i + 1) * 100f, top + 140f)
    }

    private fun model(favourite: Char, original: Char? = null) = PinyinSpatialKeyDecider(
        NextLetterProbabilityModel {
            FloatArray(26) { 0.002f }.apply {
                this[favourite - 'a'] = 0.90f
                original?.let { this[it - 'a'] = 0.003f }
            }
        }
    )

    private fun decision(original: Char, wanted: Char, x: Float, y: Float, prefix: String = "jia") =
        model(wanted, original).decide(TapEvidence(original, x, y, 1f), cells, prefix)

    @Test fun `center touches are protected for every actual adjacent direction`() {
        var directions = 0
        for (from in cells) for (to in cells) {
            if (from != to && sharedEdgePoint(from, to) != null) {
                val result = model(to.letter, from.letter).decide(
                    TapEvidence(from.letter, from.centerX, from.centerY, 1f), cells, "jia"
                )
                assertEquals("${from.letter} -> ${to.letter}", from.letter, result.selected)
                assertEquals(PinyinSpatialKeyDecider.Reason.ProtectedCenter, result.reason)
                directions++
            }
        }
        assertTrue("Exercise horizontal and staggered vertical neighbours", directions > 80)
    }

    @Test fun `high confidence corrections use all shared edges in both directions`() {
        var directions = 0
        for (from in cells) for (to in cells) {
            val point = sharedEdgePoint(from, to) ?: continue
            if (from == to) continue
            val result = decision(from.letter, to.letter, point.first, point.second)
            assertEquals("${from.letter} -> ${to.letter}", to.letter, result.selected)
            assertEquals(PinyinSpatialKeyDecider.Reason.BoundaryCorrection, result.reason)
            assertTrue(result.selectedProbability >= 0.82f)
            assertTrue(result.ambiguous)
            directions++
        }
        assertTrue(directions > 80)
    }

    @Test fun `documented pinyin neighbour pairs can correct at ambiguous shared boundaries`() {
        fun verify(from: Char, to: Char, prefix: String) {
            val point = sharedEdgePoint(cells.first { it.letter == from }, cells.first { it.letter == to })!!
            assertEquals(to, decision(from, to, point.first, point.second, prefix).selected)
        }
        verify('b', 'n', "ji")
        verify('s', 'a', "ch")
        verify('i', 'o', "niha")
        verify('j', 'n', "nia")
    }

    @Test fun `bundled dictionary supplies measured evidence without forbidding legitimate transitions`() {
        val decider = PinyinSpatialKeyDecider(bundledLanguageModel())
        val b = cells.first { it.letter == 'b' }
        val n = cells.first { it.letter == 'n' }
        // ji+b is legitimate as in ji ben; only an exceptionally ambiguous point
        // receives enough corpus evidence to choose n. At a b center it stays b.
        assertEquals('n', decider.decide(TapEvidence('b', b.right - 0.5f, b.centerY, 1f), cells, "ji").selected)
        assertEquals('b', decider.decide(TapEvidence('b', b.centerX, b.centerY, 1f), cells, "ji").selected)
        val sa = sharedEdgePoint(cells.first { it.letter == 's' }, cells.first { it.letter == 'a' })!!
        assertEquals('a', decider.decide(TapEvidence('s', sa.first, sa.second, 1f), cells, "ch").selected)
        val io = sharedEdgePoint(cells.first { it.letter == 'i' }, cells.first { it.letter == 'o' })!!
        assertEquals('i', decider.decide(TapEvidence('i', io.first, io.second, 1f), cells, "niha").selected)
        val bn = sharedEdgePoint(b, n)!!
        assertEquals('b', decider.decide(TapEvidence('b', bn.first, bn.second, 1f), cells, "nijch").selected)
    }

    @Test fun `synthetic border sequence corrects jingchanghui while center control stays literal`() {
        // This is a constructed geometry regression, not a replay of user taps
        // and not evidence of a measured real-world correction rate.
        val raw = "jibgchsnghui"
        val decider = PinyinSpatialKeyDecider(bundledLanguageModel())
        fun run(border: Boolean): String = buildString {
            for (letter in raw) {
                val cell = cells.first { it.letter == letter }
                val x = when {
                    border && letter == 'b' -> cell.right - 0.25f
                    border && letter == 's' -> cell.left + 0.25f
                    else -> cell.centerX
                }
                val result = decider.decide(TapEvidence(letter, x, cell.centerY, 1f), cells, toString())
                append(result.selected)
            }
        }
        assertEquals("jingchanghui", run(border = true))
        assertEquals(raw, run(border = false))
    }

    @Test fun `jib syllable boundary stays literal when language evidence is ambiguous`() {
        val b = cells.first { it.letter == 'b' }
        val n = cells.first { it.letter == 'n' }
        val point = sharedEdgePoint(b, n)!!
        val balanced = PinyinSpatialKeyDecider(NextLetterProbabilityModel {
            FloatArray(26) { 0.001f }.apply { this['b' - 'a'] = 0.4f; this['n' - 'a'] = 0.5f }
        })
        val result = balanced.decide(TapEvidence('b', point.first, point.second, 1f), cells, "ji")
        assertEquals('b', result.selected)
        assertEquals('n', result.alternative)
        assertTrue(result.originalProbability > 0f)
    }

    @Test fun `abbreviations and unsupported prefixes do not force a spelling expansion`() {
        val point = sharedEdgePoint(cells.first { it.letter == 'b' }, cells.first { it.letter == 'n' })!!
        for (prefix in listOf("", "jch", "bj", "jchsh", "你好", "ni3", "NI", "a".repeat(257))) {
            val result = decision('b', 'n', point.first, point.second, prefix)
            assertEquals(prefix, 'b', result.selected)
            assertEquals(PinyinSpatialKeyDecider.Reason.ProtectedPrefix, result.reason)
        }
    }

    @Test fun `explicit pinyin separator limits language context to unfinished last syllable`() {
        var received: String? = null
        val decider = PinyinSpatialKeyDecider(NextLetterProbabilityModel {
            received = it
            FloatArray(26) { 1f }
        })
        val point = sharedEdgePoint(cells.first { it.letter == 'b' }, cells.first { it.letter == 'n' })!!
        assertEquals('b', decider.decide(TapEvidence('b', point.first, point.second, 1f), cells, "xi'anji").selected)
        assertEquals("anji", received)
    }

    @Test fun `physical scale preserves decisions and scores`() {
        val point = sharedEdgePoint(cells.first { it.letter == 'b' }, cells.first { it.letter == 'n' })!!
        val decider = model('n', 'b')
        val baseline = decider.decide(TapEvidence('b', point.first, point.second, 1f), cells, "ji")
        for (scale in listOf(0.75f, 2f, 3.5f)) {
            val scaled = cells.map { KeyCell(it.letter, it.left * scale, it.top * scale,
                it.right * scale, it.bottom * scale) }
            val result = decider.decide(TapEvidence('b', point.first * scale, point.second * scale, scale), scaled, "ji")
            assertEquals(baseline.selected, result.selected)
            assertEquals(baseline.selectedProbability, result.selectedProbability, 0.00001f)
        }
    }

    @Test fun `custom cell widths replace nominal qwerty geometry`() {
        val custom = listOf(KeyCell('b', 0f, 0f, 70f, 140f), KeyCell('n', 70f, 0f, 205f, 140f))
        val result = model('n', 'b').decide(TapEvidence('b', 68f, 70f, 1f), custom, "ji")
        assertEquals('n', result.selected)
        // An n in another row that shares no edge is not a plausible neighbour.
        assertEquals('b', model('n', 'b').decide(TapEvidence('b', 68f, 70f, 1f),
            listOf(custom[0], KeyCell('n', 70f, 140f, 205f, 280f)), "ji").selected)
    }

    @Test fun `six dp boundary band does not reinterpret a clearly interior tap`() {
        val b = cells.first { it.letter == 'b' }
        val result = decision('b', 'n', b.right - 7f, b.centerY, "ji")
        assertEquals('b', result.selected)
        assertEquals(PinyinSpatialKeyDecider.Reason.NoNearbyBoundary, result.reason)
    }

    @Test fun `unknown and malformed geometry or evidence fails closed`() {
        val decider = model('n')
        for (tap in listOf(
            TapEvidence('1', 0f, 0f, 1f), TapEvidence('B', 0f, 0f, 1f),
            TapEvidence('b', Float.NaN, 0f, 1f), TapEvidence('b', 0f, Float.POSITIVE_INFINITY, 1f),
            TapEvidence('b', 0f, 0f, 0f), TapEvidence('b', 0f, 0f, Float.NaN)
        )) assertEquals(tap.original, decider.decide(tap, cells, "ji").selected)
        val point = sharedEdgePoint(cells.first { it.letter == 'b' }, cells.first { it.letter == 'n' })!!
        val tap = TapEvidence('b', point.first, point.second, 1f)
        assertEquals('b', decider.decide(tap, emptyList(), "ji").selected)
        assertEquals('b', decider.decide(tap, cells + cells.first(), "ji").selected)
        assertEquals('b', decider.decide(tap, listOf(KeyCell('b', 0f, 0f, 0f, 140f)), "ji").selected)
        assertEquals('b', decider.decide(tap,
            listOf(KeyCell('b', -Float.MAX_VALUE, 0f, Float.MAX_VALUE, 140f)), "ji").selected)
        for (distribution in listOf(null, floatArrayOf(1f), FloatArray(26), FloatArray(26) { Float.NaN },
            FloatArray(26) { -1f }, FloatArray(26) { Float.POSITIVE_INFINITY })) {
            val unusable = PinyinSpatialKeyDecider(NextLetterProbabilityModel { distribution })
            assertEquals('b', unusable.decide(tap, cells, "ji").selected)
        }
    }

    @Test fun `original path always remains represented even with compelling alternative`() {
        val point = sharedEdgePoint(cells.first { it.letter == 'b' }, cells.first { it.letter == 'n' })!!
        val result = decision('b', 'n', point.first, point.second, "ji")
        assertEquals('b', result.original)
        assertEquals('n', result.selected)
        assertTrue(result.originalProbability > 0f)
        assertEquals('n', result.alternative)
    }

    @Test fun `personalization modifies boundary scoring without overriding physical centers`() {
        val b = cells.first { it.letter == 'b' }
        val tap = TapEvidence('b', b.right - 0.5f, b.centerY, 1f)
        val decider = PinyinSpatialKeyDecider(NextLetterProbabilityModel {
            FloatArray(26) { 0.001f }.apply { this['b' - 'a'] = 0.01f; this['n' - 'a'] = 0.14f }
        })
        val baseline = decider.decide(tap, cells, "ji")
        val personalized = decider.decide(tap, cells, "ji", mapOf('n' to CenterOffset(-0.12f, 0f)))
        assertTrue(personalized.alternativeProbability > baseline.alternativeProbability)
        assertEquals('b', decider.decide(TapEvidence('b', b.centerX, b.centerY, 1f), cells, "ji",
            mapOf('n' to CenterOffset(-100f, Float.NaN))).selected)
        val bounded = decider.decide(tap, cells, "ji", mapOf('n' to CenterOffset(-0.12f, 0f)))
        val oversized = decider.decide(tap, cells, "ji", mapOf('n' to CenterOffset(-100f, 0f)))
        assertEquals(bounded.alternativeProbability, oversized.alternativeProbability, 0.00001f)
    }

    private fun bundledLanguageModel(): PinyinTouchLanguageModel {
        val asset = listOf(File("src/main/assets/typing/pinyin_touch_model.tsv"),
            File("app/src/main/assets/typing/pinyin_touch_model.tsv")).first { it.isFile }
        return asset.reader().use { PinyinTouchLanguageModel.parse(it) }
    }

    /** Independent test geometry: point two pixels on the original side of a shared edge. */
    private fun sharedEdgePoint(from: KeyCell, to: KeyCell): Pair<Float, Float>? {
        val top = max(from.top, to.top)
        val bottom = min(from.bottom, to.bottom)
        if (bottom > top) {
            if (abs(from.right - to.left) < 0.001f) return (from.right - 2f) to ((top + bottom) / 2f)
            if (abs(from.left - to.right) < 0.001f) return (from.left + 2f) to ((top + bottom) / 2f)
        }
        val left = max(from.left, to.left)
        val right = min(from.right, to.right)
        if (right > left) {
            if (abs(from.bottom - to.top) < 0.001f) return ((left + right) / 2f) to (from.bottom - 2f)
            if (abs(from.top - to.bottom) < 0.001f) return ((left + right) / 2f) to (from.top + 2f)
        }
        return null
    }
}
