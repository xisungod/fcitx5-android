/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.neural

import java.io.File
import java.text.Normalizer
import java.util.Locale

/** BERT basic tokenization and greedy WordPiece for the pinned Chinese vocabulary. */
internal class MiniRbtTokenizer(vocabulary: List<String>) {
    private val ids = vocabulary.withIndex().associate { it.value to it.index.toLong() }
    private val unknown = requireNotNull(ids["[UNK]"])
    private val cls = requireNotNull(ids["[CLS]"])
    private val sep = requireNotNull(ids["[SEP]"])
    private val mask = requireNotNull(ids["[MASK]"])
    private val pad = requireNotNull(ids["[PAD]"])

    constructor(file: File) : this(file.readLines(Charsets.UTF_8))

    fun tokenize(text: String): List<Long> {
        val result = ArrayList<Long>()
        var previous = 0
        SPECIAL.findAll(text).forEach { match ->
            result.addAll(basic(text.substring(previous, match.range.first)))
            result.add(requireNotNull(ids[match.value]))
            previous = match.range.last + 1
        }
        result.addAll(basic(text.substring(previous)))
        return result
    }

    private fun basic(text: String): List<Long> {
        val clean = StringBuilder()
        var cursor = 0
        while (cursor < text.length) {
            val cp = text.codePointAt(cursor)
            cursor += Character.charCount(cp)
            val kind = Character.getType(cp)
            when {
                cp == 32 || cp == 9 || cp == 10 || cp == 13 ||
                    kind == Character.SPACE_SEPARATOR.toInt() -> clean.append(' ')
                cp == 0 || cp == 0xFFFD || kind == Character.CONTROL.toInt() ||
                    kind == Character.FORMAT.toInt() || kind == Character.PRIVATE_USE.toInt() ||
                    kind == Character.SURROGATE.toInt() || kind == Character.UNASSIGNED.toInt() -> Unit
                isChinese(cp) || isPunctuation(cp) -> clean.append(' ').appendCodePoint(cp).append(' ')
                else -> clean.appendCodePoint(cp)
            }
        }
        val normalized = Normalizer.normalize(clean.toString().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        return normalized.split(Regex("\\s+")).filter { it.isNotEmpty() }.flatMap { word ->
            if (word.length > 100) return@flatMap listOf(unknown)
            val result = ArrayList<Long>()
            var start = 0
            while (start < word.length) {
                var end = word.length
                var found: Long? = null
                while (end > start) {
                    val piece = (if (start == 0) "" else "##") + word.substring(start, end)
                    found = ids[piece]
                    if (found != null) break
                    end--
                }
                if (found == null) return@flatMap listOf(unknown)
                result.add(found)
                start = end
            }
            result
        }
    }

    /** Mask every candidate token separately. Context remains visible. */
    fun batch(context: String, candidates: List<String>, maximumLength: Int = 96): MaskedBatch? {
        if (candidates.isEmpty() || candidates.size > 6 || maximumLength !in 4..96) return null
        // Keep every ID inside the checkpoint's embedding table. Unknown
        // context tokens are safe; candidate targets need a trained embedding.
        val prefix = tokenize(context).takeLast(48).map { if (it < 21128) it else unknown }
        val words = candidates.map(::tokenize)
        if (words.any { it.size !in 2..8 || it.size + 2 > maximumLength ||
                unknown in it || it.any { token -> token >= 21128 } }) return null
        val rows = ArrayList<List<Long>>()
        val positions = ArrayList<Long>()
        val targets = ArrayList<Long>()
        val owners = ArrayList<Int>()
        words.forEachIndexed { owner, tokens ->
            val preceding = prefix.takeLast(maximumLength - tokens.size - 2)
            val sequence = listOf(cls) + preceding + tokens + sep
            tokens.forEachIndexed { index, token ->
                val position = preceding.size + 1 + index
                rows.add(sequence.toMutableList().apply { this[position] = mask })
                positions.add(position.toLong())
                targets.add(token)
                owners.add(owner)
            }
        }
        if (rows.size > 48) return null
        val length = rows.maxOf { it.size }
        val inputIds = LongArray(rows.size * length) { pad }
        val attention = LongArray(inputIds.size)
        rows.forEachIndexed { index, row ->
            row.forEachIndexed { column, token ->
                inputIds[index * length + column] = token
                attention[index * length + column] = 1
            }
        }
        return MaskedBatch(inputIds, attention, LongArray(inputIds.size),
            positions.toLongArray(), targets.toLongArray(), owners.toIntArray(), rows.size, length, words.size)
    }

    companion object {
        private val SPECIAL = Regex("\\[(?:UNK|CLS|SEP|PAD|MASK)\\]")
        private fun isChinese(cp: Int) = cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF ||
            cp in 0x20000..0x2A6DF || cp in 0x2A700..0x2B73F || cp in 0x2B740..0x2B81F ||
            cp in 0x2B820..0x2CEAF || cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F

        private fun isPunctuation(cp: Int): Boolean {
            if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
            return Character.getType(cp) in setOf(Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(), Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt())
        }
    }
}

internal data class MaskedBatch(
    val inputIds: LongArray, val attentionMask: LongArray, val tokenTypeIds: LongArray,
    val maskedPositions: LongArray, val targetIds: LongArray, val owners: IntArray,
    val batchSize: Int, val sequenceLength: Int, val candidateCount: Int
) {
    fun means(scores: FloatArray): List<Double>? {
        if (scores.size != batchSize || scores.any { !it.isFinite() }) return null
        val totals = DoubleArray(candidateCount)
        val counts = IntArray(candidateCount)
        scores.forEachIndexed { index, score ->
            totals[owners[index]] += score.toDouble()
            counts[owners[index]]++
        }
        return totals.mapIndexed { index, total -> total / counts[index] }
    }
}
