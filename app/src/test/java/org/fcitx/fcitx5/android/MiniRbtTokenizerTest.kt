/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fcitx.fcitx5.android.input.neural.MiniRbtTokenizer
import org.junit.Assert.*
import org.junit.Test

class MiniRbtTokenizerTest {
    private fun tokenizer(): MiniRbtTokenizer {
        val values = MutableList(21130) { "fixture-unused-$it" }
        val subset = Json.parseToJsonElement(resource("tokenizer-vocab-subset.json")).jsonObject
        subset.forEach { (index, token) -> values[index.toInt()] = token.jsonPrimitive.content }
        values[21128] = "##😂"
        return MiniRbtTokenizer(values)
    }

    private fun resource(name: String) = requireNotNull(javaClass.classLoader!!.getResource("neural/$name")).readText()

    @Test fun matchesPinnedBertTokenizerUnicodeAndSpecialTokenFixtures() {
        val tokenizer = tokenizer()
        val fixtures = Json.parseToJsonElement(resource("tokenizer-fixtures.json")).jsonArray
        fixtures.forEach { value ->
            val item = value.jsonObject
            val text = item.getValue("text").jsonPrimitive.content
            val expected = item.getValue("ids").jsonArray.map { it.jsonPrimitive.content.toLong() }
            assertEquals(text, expected, tokenizer.tokenize(text))
        }
    }

    @Test fun eachCandidateTokenIsMaskedIndependentlyAndPaddingIsIgnored() {
        val batch = requireNotNull(tokenizer().batch("AXiang", listOf("你好啊", "你好")))
        assertEquals(5, batch.batchSize)
        batch.maskedPositions.forEachIndexed { row, position ->
            assertEquals(103L, batch.inputIds[row * batch.sequenceLength + position.toInt()])
            assertNotEquals(103L, batch.targetIds[row])
            assertEquals(1L, batch.attentionMask[row * batch.sequenceLength + position.toInt()])
        }
        assertArrayEquals(intArrayOf(0, 0, 0, 1, 1), batch.owners)
        assertEquals(listOf(-2.0, -4.0), batch.means(floatArrayOf(-1f, -2f, -3f, -3f, -5f)))
        assertTrue(batch.attentionMask.any { it == 0L })
        assertTrue(batch.tokenTypeIds.all { it == 0L })
    }

    @Test fun boundsContextAndRejectsUnknownCandidateTargets() {
        val tokenizer = tokenizer()
        val batch = requireNotNull(tokenizer.batch("你好".repeat(80), listOf("你好啊")))
        assertEquals(53, batch.sequenceLength)
        assertNull(tokenizer.batch("", listOf("unrepresentablecandidate")))
        assertNull(tokenizer.batch("", List(7) { "你好啊" }))
        assertNull(tokenizer.batch("", listOf("你")))
        assertNull(tokenizer.batch("", listOf("你好啊"), maximumLength = 4))
    }

    @Test fun outOfRangeVocabularyEntriesCannotReachEmbeddingLookup() {
        val batch = requireNotNull(tokenizer().batch("a😂", listOf("你好啊")))
        assertTrue(batch.inputIds.all { it in 0..21127 })
        assertTrue(batch.inputIds.any { it == 100L })
        assertNull(batch.means(FloatArray(batch.batchSize) { Float.NaN }))
        assertNull(batch.means(FloatArray(1)))
    }
}
