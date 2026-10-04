package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.input.candidates.horizontal.CandidatePageBuffer
import org.junit.Assert.*
import org.junit.Test

class CandidatePageBufferTest {
    private fun words(start: Int, count: Int) = Array(count) { CandidateWord("", "word${start + it}", "") }
    @Test fun laterTypingRejectsOldPageWithoutClearingTheNewRequest() {
        val buffer = CandidatePageBuffer()
        buffer.reset(words(0, 16), -1)
        val old = buffer.request()!!
        assertNull(buffer.request())
        buffer.reset(words(100, 16), -1)
        val fresh = buffer.request()!!
        assertFalse(buffer.complete(old, words(16, 48)))
        buffer.failed(old)
        assertNull(buffer.request())
        assertTrue(buffer.complete(fresh, words(116, 3)))
        assertEquals("word100", buffer.words.first().text)
        assertEquals("word118", buffer.words.last().text)
        assertEquals(19, buffer.total)
        assertNull(buffer.request())
    }
    @Test fun retainsFinalCandidateAndRetriesFailedPageAtTheSameOffset() {
        val buffer = CandidatePageBuffer()
        buffer.reset(words(0, 16), 65)
        val failed = buffer.request()!!
        buffer.failed(failed)
        val request = buffer.request()!!
        assertEquals(16, request.offset)
        assertTrue(buffer.complete(request, words(16, 48)))
        val last = buffer.request()!!
        assertEquals(64, last.offset)
        assertTrue(buffer.complete(last, words(64, 1)))
        assertEquals(65, buffer.words.size)
        assertEquals("word64", buffer.words.last().text)
        assertNull(buffer.request())
    }
}
