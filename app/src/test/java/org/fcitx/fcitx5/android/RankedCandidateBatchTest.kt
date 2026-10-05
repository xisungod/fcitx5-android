/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.input.neural.RankedCandidateBatch
import org.junit.Assert.*
import org.junit.Test

class RankedCandidateBatchTest {
    private fun words() = arrayOf("你好啊", "不好啊", "几号啊", "你是啊", "不是啊", "怒号啊")
        .map { CandidateWord("", it, "") }.toTypedArray()

    @Test fun weakLeadInvalidScoresAndWrongScoreCountPreserveNativeOrder() {
        val original = RankedCandidateBatch.original(1, words())
        val indices = (0..5).toList()
        assertSame(original, original.rerank(2, indices, listOf(-3.0, -2.9, -2.8, -2.7, -2.6, -2.5)))
        assertSame(original, original.rerank(2, indices, listOf(Double.NaN, 0.0, 0.0, 0.0, 0.0, 0.0)))
        assertSame(original, original.rerank(2, indices, listOf(Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0, 0.0, 0.0)))
        assertSame(original, original.rerank(2, indices, listOf(0.0)))
    }

    @Test fun promotionsAreLimitedAndNativeFirstIsProtectedByDefault() {
        val source = RankedCandidateBatch.original(1, words())
        val result = source.rerank(2, (0..5).toList(), listOf(-100.0, -100.0, -100.0, -100.0, -100.0, 0.0))
        assertTrue(result.isReordered)
        assertEquals(0, result.firstOriginalIndex)
        assertEquals(5, result.originalIndex(3))
        assertEquals((0..5).toSet(), (0..5).map(result::originalIndex).toSet())
    }

    @Test fun reorderedPagesAndClicksReferToTheSameNativeWordAcrossPageBoundaries() {
        val native = words()
        val source = RankedCandidateBatch.original(10, native)
        val result = source.rerank(11, (0..5).toList(), listOf(-100.0, -100.0, 0.0, -100.0, -100.0, -100.0), false)
        val page = result.page(0, native)
        page.forEach { assertEquals(native[it.originalIndex], it.word) }
        assertEquals("几号啊", page.first().word.text)
        assertEquals(2, page.first().originalIndex)
        val partialPage = result.page(1, native.copyOfRange(1, 3))
        assertEquals(native[0], partialPage[0].word)
        assertEquals(0, partialPage[0].originalIndex)
        val tail = CandidateWord("", "尾页", "")
        val later = result.page(48, arrayOf(tail)).single()
        assertEquals(tail, later.word)
        assertEquals(48, later.originalIndex)
        assertEquals(11L, later.generation)
    }

    @Test fun nativePageValidationRejectsChangedCompositionsAndAllowsUntouchedTail() {
        val source = RankedCandidateBatch.original(1, words())
        assertTrue(source.matchesNativePage(0, words()))
        assertFalse(source.matchesNativePage(0, words().also { it[1] = it[1].copy(text = "小姑娘") }))
        assertTrue(source.matchesNativePage(48, arrayOf(CandidateWord("", "尾页", ""))))
    }

    @Test fun nonBulkNativeLabelsDoNotBreakPagingOrSelectingTheDisplayedWord() {
        val callback = words()
        val queried = callback.mapIndexed { index, word -> word.copy(label = "${index + 1}") }.toTypedArray()
        val source = RankedCandidateBatch.original(1, callback)
        assertTrue(source.matchesNativePage(0, queried))
        val result = source.rerank(2, (0..5).toList(),
            listOf(-100.0, -100.0, 0.0, -100.0, -100.0, -100.0), protectFirst = false)
        val displayed = result.page(0, queried).first()
        assertEquals(2, displayed.originalIndex)
        assertTrue(RankedCandidateBatch.sameNativeWord(displayed.word, queried[displayed.originalIndex]))
        assertFalse(RankedCandidateBatch.sameNativeWord(displayed.word, queried[0]))
        assertFalse(RankedCandidateBatch.sameNativeWord(displayed.word, null))
        assertFalse(RankedCandidateBatch.sameNativeWord(displayed.word,
            queried[2].copy(comment = "different candidate action")))
        assertFalse(RankedCandidateBatch.sameNativeWord(displayed.word,
            queried[2].copy(spaceBetweenComment = !displayed.word.spaceBetweenComment)))
    }

    @Test fun scorerOnlySeesEqualLengthChineseAlternativesFromTheFirstSix() {
        val native = words().toMutableList()
        native[1] = CandidateWord("", "你好", "")
        native[3] = CandidateWord("", "abc", "")
        native.add(CandidateWord("", "可以啊", ""))
        assertEquals(listOf(0, 2, 4, 5), RankedCandidateBatch.scoringIndices(native))
        assertTrue(RankedCandidateBatch.scoringIndices(listOf(CandidateWord("", "hello", ""))).isEmpty())
    }
}
