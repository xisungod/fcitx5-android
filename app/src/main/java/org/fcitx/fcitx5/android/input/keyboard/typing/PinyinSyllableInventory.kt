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

    /**
     * Deterministic recognizer for "zero or more full syllables, then at most one unfinished
     * syllable prefix". Every letter string maps to at most one state path, so a lattice over
     * it never counts one spelling twice merely because it has two segmentations (xi'an/xian).
     * State 0 is the empty spelling. Built once; read-only afterwards and safe to share.
     */
    internal class Automaton(syllables: Collection<String>) {
        private val next: IntArray
        private val complete: BooleanArray
        val stateCount: Int get() = complete.size

        /** The following state, or -1 when no full-pinyin spelling continues with [letter]. */
        fun step(state: Int, letter: Char): Int =
            if (letter !in 'a'..'z') -1 else next[state * 26 + (letter - 'a')]

        /** True when the spelling read so far ends exactly on a syllable boundary. */
        fun isComplete(state: Int): Boolean = complete[state]

        init {
            // Trie node 0 is the root, i.e. "at a syllable boundary".
            val children = ArrayList<IntArray>().apply { add(IntArray(26) { -1 }) }
            val ends = ArrayList<Boolean>().apply { add(false) }
            for (word in syllables) {
                if (word.isEmpty() || word.any { it !in 'a'..'z' }) continue
                var node = 0
                for (letter in word) {
                    val index = letter - 'a'
                    if (children[node][index] < 0) {
                        children[node][index] = children.size
                        children.add(IntArray(26) { -1 })
                        ends.add(false)
                    }
                    node = children[node][index]
                }
                ends[node] = true
            }
            // Subset construction. A subset holds every trie node the spelling may be in.
            val ids = HashMap<List<Int>, Int>()
            val subsets = ArrayList<List<Int>>()
            val transitions = ArrayList<IntArray>()
            fun idOf(subset: List<Int>): Int = ids.getOrPut(subset) {
                subsets.add(subset)
                transitions.add(IntArray(26) { -1 })
                subsets.size - 1
            }
            idOf(listOf(0))
            var cursor = 0
            while (cursor < subsets.size) {
                val subset = subsets[cursor]
                for (index in 0 until 26) {
                    val target = java.util.TreeSet<Int>()
                    for (node in subset) {
                        val child = children[node][index]
                        if (child >= 0) target.add(child)
                        // A finished syllable may be followed by the first letter of the next.
                        if (node != 0 && ends[node]) {
                            val restart = children[0][index]
                            if (restart >= 0) target.add(restart)
                        }
                    }
                    if (target.isNotEmpty()) transitions[cursor][index] = idOf(target.toList())
                }
                cursor++
            }
            next = IntArray(subsets.size * 26)
            complete = BooleanArray(subsets.size)
            for (state in subsets.indices) {
                System.arraycopy(transitions[state], 0, next, state * 26, 26)
                complete[state] = subsets[state].any { ends[it] }
            }
        }
    }

    internal val automaton: Automaton by lazy { Automaton(byInitial.flatMap { it }) }

    private companion object {
        const val VOWELS = "aeiouv"
    }
}
