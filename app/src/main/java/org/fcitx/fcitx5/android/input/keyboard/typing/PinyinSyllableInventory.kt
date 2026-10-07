/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

/**
 * Inventory membership only. This does not generate, rank, or decode spelling.
 * Comparing the original character ranges avoids allocating a substring for
 * every possible boundary of every already-enumerated path.
 */
internal class PinyinSyllableInventory(syllables: Set<String>) {
    private val byInitial: Array<List<String>> = Array(26) { index ->
        syllables.filter { word -> word.isNotEmpty() && word.all { it in 'a'..'z' } &&
            word.first() == 'a' + index }.sortedBy { it.length }
    }

    val isEmpty: Boolean = byInitial.all { it.isEmpty() }

    fun fullBoundaries(spelling: String): BooleanArray {
        val boundaries = BooleanArray(spelling.length + 1)
        boundaries[0] = true
        for (start in spelling.indices) {
            if (!boundaries[start]) continue
            val initial = spelling[start] - 'a'
            if (initial !in byInitial.indices) continue
            for (word in byInitial[initial]) {
                val end = start + word.length
                if (end > spelling.length) break
                if (spelling.regionMatches(start, word, 0, word.length)) boundaries[end] = true
            }
        }
        return boundaries
    }

    fun hasProtectedIncompleteEnding(spelling: String, boundaries: BooleanArray): Boolean {
        if (boundaries[spelling.length]) return false
        for (start in spelling.indices) {
            if (!boundaries[start]) continue
            val remaining = spelling.length - start
            if ((start until spelling.length).all { spelling[it] !in VOWELS }) return true
            val initial = spelling[start] - 'a'
            if (initial !in byInitial.indices) continue
            if (byInitial[initial].any { word -> word.length > remaining &&
                    spelling.regionMatches(start, word, 0, remaining) }) return true
        }
        return false
    }

    private companion object {
        const val VOWELS = "aeiouv"
    }
}
