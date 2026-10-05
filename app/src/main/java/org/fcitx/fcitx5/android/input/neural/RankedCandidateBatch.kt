/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.neural

import org.fcitx.fcitx5.android.core.CandidateWord

/** A permutation of existing native candidates. It never invents or commits text. */
class RankedCandidateBatch private constructor(
    val generation: Long,
    val originalWords: List<CandidateWord>,
    private val indices: List<Int>,
    val preedit: String = ""
) {
    data class Entry(val word: CandidateWord, val originalIndex: Int, val generation: Long)

    val isReordered: Boolean get() = indices.indices.any { indices[it] != it }
    val firstOriginalIndex: Int get() = indices.firstOrNull() ?: 0
    val words: Array<CandidateWord> get() = indices.map(originalWords::get).toTypedArray()
    fun originalIndex(displayIndex: Int): Int = indices.getOrNull(displayIndex) ?: displayIndex
    fun entry(displayIndex: Int, fallback: CandidateWord): Entry {
        val originalIndex = originalIndex(displayIndex)
        return Entry(originalWords.getOrNull(originalIndex) ?: fallback, originalIndex, generation)
    }

    fun matchesNativePage(offset: Int, words: Array<CandidateWord>): Boolean = words.indices.all {
        val original = originalWords.getOrNull(offset + it)
        original == null || sameNativeWord(original, words[it])
    }

    fun page(offset: Int, nativeWords: Array<CandidateWord>): List<Entry> =
        nativeWords.mapIndexed { index, word -> entry(offset + index, word) }

    fun original(generation: Long = this.generation): RankedCandidateBatch =
        original(generation, originalWords.toTypedArray(), preedit)

    fun withPreedit(preedit: String): RankedCandidateBatch =
        RankedCandidateBatch(generation, originalWords, indices, preedit)

    /**
     * Generic MLM scores are experimental. Only equally long complete Chinese alternatives
     * compete; every promotion needs a substantial margin and is limited to two places.
     */
    fun rerank(generation: Long, scoredIndices: List<Int>, scores: List<Double>,
        protectFirst: Boolean = true): RankedCandidateBatch {
        if (scores.size != scoredIndices.size || scores.any { !it.isFinite() }) return this
        if (scoredIndices.size < 2 || scoredIndices != scoredIndices.sorted() ||
            scoredIndices.any { it !in originalWords.indices } || scoredIndices.distinct().size != scoredIndices.size)
            return this
        val scored = scoredIndices.zip(scores).toMap()
        val order = originalWords.indices.toMutableList()
        // Mean masked log probabilities are negative; their scale is normalized by the scorer.
        // Rank prior keeps the native model/user dictionary influential.
        val combined = scored.mapValues { (index, score) -> score * SCORE_WEIGHT - index * RANK_PRIOR }
        for (candidate in scoredIndices.drop(1)) {
            var position = order.indexOf(candidate)
            val earliest = (candidate - MAX_PROMOTION).coerceAtLeast(0)
            while (position > earliest) {
                if (position == 1 && protectFirst) break
                val previous = order[position - 1]
                val previousScore = combined[previous] ?: break
                val gain = combined.getValue(candidate) - previousScore
                val threshold = if (position == 1) FIRST_PLACE_MARGIN else OTHER_PLACE_MARGIN
                if (gain < threshold) break
                order[position] = previous
                order[--position] = candidate
            }
        }
        if (order.indices.all { order[it] == it }) return this
        return RankedCandidateBatch(generation, originalWords, order, preedit)
    }

    companion object {
        private const val SCORE_WEIGHT = 0.20
        private const val RANK_PRIOR = 0.25
        private const val FIRST_PLACE_MARGIN = 0.90
        private const val OTHER_PLACE_MARGIN = 0.15
        private const val MAX_PROMOTION = 2

        /** Non-bulk callbacks omit labels while native page queries include selection labels. */
        fun sameNativeWord(expected: CandidateWord, actual: CandidateWord?): Boolean = actual != null &&
            expected.text == actual.text && expected.comment == actual.comment &&
            expected.spaceBetweenComment == actual.spaceBetweenComment

        fun original(generation: Long, words: Array<CandidateWord>, preedit: String = "") =
            RankedCandidateBatch(generation, words.toList(), words.indices.toList(), preedit)

        /** Equal length avoids promoting a short prefix over an already complete sentence. */
        fun scoringIndices(words: List<CandidateWord>): List<Int> {
            val length = words.firstOrNull()?.text?.codePointCount(0, words.first().text.length) ?: return emptyList()
            if (length !in 2..8 || !words.first().text.codePoints().allMatch {
                    Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }) return emptyList()
            return words.take(6).mapIndexedNotNull { index, word ->
                index.takeIf { word.text.codePointCount(0, word.text.length) == length &&
                    word.text.codePoints().allMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN } }
            }
        }
    }
}
