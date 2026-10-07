/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates.horizontal

import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTouchCandidateOffer
import org.fcitx.fcitx5.android.input.prediction.NextWordPredictionOffer
import org.junit.Assert.*
import org.junit.Test

class HorizontalCandidateEntryTest {
    private fun word(text: String) = CandidateWord("", text, "")
    private fun offer(text: String = "经常会", token: Long = 1) =
        PinyinTouchCandidateOffer(token, text, "jibgchsnghui", "jingchanghui")

    @Test fun `verified first offer retains literal first and unchanged native selection indices`() {
        val words = arrayOf(word("军长会"), word("其他"), word("经常会"), word("第四"))
        val promoted = offer().copy(promotedToFirst = true)
        val rows = horizontalCandidateEntries(words, promoted)
        assertEquals(promoted, (rows[0] as HorizontalCandidateEntry.Touch).offer)
        assertEquals(listOf(0, 1, 3), rows.filterIsInstance<HorizontalCandidateEntry.Raw>().map { it.nativeIndex })
        assertEquals("军长会", (rows[1] as HorizontalCandidateEntry.Raw).word.text)
        assertEquals(listOf("军长会", "其他", "经常会", "第四"), words.map { it.text })
        assertEquals(words.toList(), horizontalCandidateEntries(words, null)
            .filterIsInstance<HorizontalCandidateEntry.Raw>().map { it.word })
    }

    @Test fun `stale first designation cannot promote novel or lower ranked word`() {
        val words = arrayOf(word("原词"), word("其他"), word("第三"), word("经常会"))
        for (text in listOf("经常会", "新词")) {
            val rows = horizontalCandidateEntries(words, offer(text).copy(promotedToFirst = true))
            assertEquals(words.take(3), rows.take(3).map { (it as HorizontalCandidateEntry.Raw).word })
        }
    }

    @Test fun `touch is second and all remaining words retain native indices`() {
        val words = arrayOf(word("基本"), word("经常会"), word("机场"), word("经常会"), word("检查"))
        val original = words.copyOf()
        val rows = horizontalCandidateEntries(words, offer())
        assertEquals(listOf(0, 2, 4), rows.filterIsInstance<HorizontalCandidateEntry.Raw>().map { it.nativeIndex })
        assertEquals("基本", (rows[0] as HorizontalCandidateEntry.Raw).word.text)
        assertEquals(1L, (rows[1] as HorizontalCandidateEntry.Touch).offer.token)
        assertEquals(1, rows.count { it is HorizontalCandidateEntry.Touch })
        assertArrayEquals(original, words)
    }

    @Test fun `same first candidate blank or missing raw list suppresses synthetic slot`() {
        val words = arrayOf(word("经常会"), word("机场"))
        for (candidate in listOf(offer(), offer(""), null)) {
            val rows = horizontalCandidateEntries(words, candidate)
            assertEquals(listOf(0, 1), rows.filterIsInstance<HorizontalCandidateEntry.Raw>().map { it.nativeIndex })
            assertFalse(rows.any { it is HorizontalCandidateEntry.Touch })
        }
        assertTrue(horizontalCandidateEntries(emptyArray(), offer()).isEmpty())
    }

    @Test fun `dismissal restores duplicate raw words at original indices`() {
        val words = arrayOf(word("基本"), word("经常会"), word("经常会"), word("检查"))
        val raw = horizontalCandidateEntries(words, null).filterIsInstance<HorizontalCandidateEntry.Raw>()
        assertEquals(listOf(0, 1, 2, 3), raw.map { it.nativeIndex })
        assertEquals(words.toList(), raw.map { it.word })
    }

    @Test fun `existing third word can move second without losing original top three`() {
        val words = arrayOf(word("基本"), word("检查"), word("经常会"), word("机场"))
        val rows = horizontalCandidateEntries(words, offer())
        assertEquals(listOf("基本", "经常会", "检查"), rows.take(3).map { entry ->
            when (entry) {
                is HorizontalCandidateEntry.Raw -> entry.word.text
                is HorizontalCandidateEntry.Touch -> entry.offer.text
                is HorizontalCandidateEntry.Prediction -> entry.text
            }
        })
        assertEquals(listOf(0, 1, 3), rows.filterIsInstance<HorizontalCandidateEntry.Raw>().map { it.nativeIndex })
    }

    @Test fun `novel wrong alternative never pushes the actual original second target out of top three`() {
        val words = arrayOf(word("祝你内天都开心"), word("祝你每天都开心"), word("祝你天天都开心"), word("其他"))
        val rows = horizontalCandidateEntries(words, offer("祝你内甜豆开心"))
        assertEquals(words.take(3), rows.take(3).map { (it as HorizontalCandidateEntry.Raw).word })
        assertEquals("祝你内甜豆开心", (rows[3] as HorizontalCandidateEntry.Touch).offer.text)
        assertEquals(1, (rows[1] as HorizontalCandidateEntry.Raw).nativeIndex)
    }

    @Test fun `lower ranked and novel offers follow available original top three`() {
        val words = arrayOf(word("首选"), word("第二"), word("第三"), word("经常会"), word("第五"))
        for (candidate in listOf(offer(), offer("新词"))) {
            val rows = horizontalCandidateEntries(words, candidate)
            assertEquals(listOf(0, 1, 2), rows.take(3).map { (it as HorizontalCandidateEntry.Raw).nativeIndex })
            assertTrue(rows[3] is HorizontalCandidateEntry.Touch)
        }
        for (length in 1..2) {
            val rows = horizontalCandidateEntries(words.take(length).toTypedArray(), offer("新词"))
            assertEquals(length, rows.indexOfFirst { it is HorizontalCandidateEntry.Touch })
            assertEquals((0 until length).toList(), rows.take(length).map { (it as HorizontalCandidateEntry.Raw).nativeIndex })
        }
    }

    @Test fun `every original rank and novel path preserve the original top three set`() {
        val words = Array(6) { word("候选$it") }
        val originalTopThree = words.take(3).map { it.text }.toSet()
        for (text in words.map { it.text } + "新建议") {
            val rows = horizontalCandidateEntries(words, offer(text))
            val topThree = rows.take(3).map { entry -> when (entry) {
                is HorizontalCandidateEntry.Raw -> entry.word.text
                is HorizontalCandidateEntry.Touch -> entry.offer.text
                is HorizontalCandidateEntry.Prediction -> entry.text
            } }.toSet()
            assertEquals("Offer $text must retain the literal top three", originalTopThree, topThree)
            for (raw in rows.filterIsInstance<HorizontalCandidateEntry.Raw>())
                assertEquals(words[raw.nativeIndex], raw.word)
        }
    }

    @Test fun `prediction only uses idle slots and keeps source indices distinct from native indices`() {
        val prediction = NextWordPredictionOffer(45, listOf("", "快乐", "朋友"))
        assertEquals(listOf(
            HorizontalCandidateEntry.Prediction(45, 1, "快乐"),
            HorizontalCandidateEntry.Prediction(45, 2, "朋友")
        ), horizontalCandidateEntries(emptyArray(), null, prediction))
        val words = arrayOf(word("原词"), word("经常会"), word("第三"))
        assertEquals(horizontalCandidateEntries(words, offer()), horizontalCandidateEntries(words, offer(), prediction))
        assertTrue(horizontalCandidateEntries(emptyArray(), offer(), prediction).isEmpty())
        assertEquals(words.toList(), horizontalCandidateEntries(words, null, prediction)
            .filterIsInstance<HorizontalCandidateEntry.Raw>().map { it.word })
    }

    @Test fun `synthetic slot does not change page offsets totals or late page guard`() {
        val words = arrayOf(word("基本"), word("经常会"), word("检查"))
        val buffer = CandidatePageBuffer()
        buffer.reset(words, 80)
        val rows = horizontalCandidateEntries(buffer.words, offer("新词"))
        assertEquals(4, rows.size)
        val request = buffer.request()!!
        assertEquals(3, request.offset)
        assertEquals(80, buffer.total)
        val next = Array(48) { word(if (it == 0) "新词" else "候选$it") }
        assertTrue(buffer.complete(request, next))
        val rendered = horizontalCandidateEntries(buffer.words, offer("新词"))
        assertEquals(51, buffer.words.size)
        assertEquals(51, rendered.size) // one synthetic replaces only the duplicate's display position
        assertEquals(51, buffer.request()!!.offset)
        buffer.reset(arrayOf(word("新输入")), 1)
        assertFalse(buffer.complete(request, next))
    }
}
