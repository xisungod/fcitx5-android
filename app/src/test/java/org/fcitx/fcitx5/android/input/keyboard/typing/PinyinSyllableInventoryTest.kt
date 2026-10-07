/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.random.Random

class PinyinSyllableInventoryTest {
    private val syllables by lazy {
        listOf(File("src/main/assets/typing/pinyin_touch_model.tsv"),
            File("app/src/main/assets/typing/pinyin_touch_model.tsv")).first { it.isFile }
            .reader().use(PinyinTouchLanguageModel::parse).syllables
    }

    @Test fun `range comparison preserves old membership and unfinished-prefix protection`() {
        val inventory = PinyinSyllableInventory(syllables)
        val prefixes = syllables.flatMap { word -> (1 until word.length).map { word.take(it) } }.toSet()
        val maximumLength = syllables.maxOf { it.length }
        val random = Random(71436)
        val words = syllables.sorted()
        val cases = words + words.flatMap { word -> (1 until word.length).map { word.take(it) } } +
            List(500) { buildString { repeat(random.nextInt(1, 9)) { append(words.random(random)) } } } +
            List(500) { buildString { repeat(random.nextInt(1, 40)) { append(('a'..'z').random(random)) } } }
        for (spelling in cases + listOf("", "jib", "jch", "nijch", "jingch", "changh")) {
            val old = BooleanArray(spelling.length + 1).also { it[0] = true }
            for (start in spelling.indices) {
                if (!old[start]) continue
                for (end in start + 1..minOf(spelling.length, start + maximumLength)) {
                    if (spelling.substring(start, end) in syllables) old[end] = true
                }
            }
            assertArrayEquals(spelling, old, inventory.fullBoundaries(spelling))
            val protected = !old[spelling.length] && spelling.indices.any { start ->
                old[start] && (spelling.substring(start) in prefixes ||
                    spelling.substring(start).all { it !in "aeiouv" })
            }
            assertEquals(spelling, protected, inventory.hasProtectedIncompleteEnding(spelling, old))
        }
    }

    @Test fun `malformed inventory entries never add boundaries`() {
        val inventory = PinyinSyllableInventory(setOf("", "JI", "a1", "ni"))
        assertArrayEquals(booleanArrayOf(true, false, true), inventory.fullBoundaries("ni"))
        assertFalse(inventory.fullBoundaries("ji").last())
    }
}
