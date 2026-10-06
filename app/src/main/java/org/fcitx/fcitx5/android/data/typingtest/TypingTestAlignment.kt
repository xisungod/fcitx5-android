/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.typingtest

import kotlin.math.abs

enum class TypingTestEditKind { MATCH, SUBSTITUTE, INSERT, DELETE }

data class TypingTestEdit(
    val kind: TypingTestEditKind,
    val inputIndex: Int?,
    val targetIndex: Int?,
    val actual: Char?,
    val expected: Char?
)

data class TypingTestAlignment(
    val distance: Int,
    /** Multiple cheapest edit paths mean the target-to-contact labels are not reliable. */
    val ambiguous: Boolean,
    /** Empty on ambiguity: callers must not accidentally train from an arbitrary tie-break. */
    val edits: List<TypingTestEdit>
)

/** Bounded Levenshtein alignment. It evaluates evidence, never edits an input string. */
object TypingTestAligner {
    const val MAX_LENGTH = 64
    const val MAX_EDITS = 6

    /** Counts even severely wrong complete trials without inventing a contact mapping. */
    fun distance(input: String, target: String): Int? {
        if (input.length > MAX_LENGTH || target.length > MAX_LENGTH ||
            input.any { it !in 'a'..'z' } || target.any { it !in 'a'..'z' }) return null
        var previous = IntArray(target.length + 1) { it }
        var current = IntArray(target.length + 1)
        for (i in input.indices) {
            current[0] = i + 1
            for (j in target.indices) current[j + 1] = minOf(previous[j + 1] + 1,
                current[j] + 1, previous[j] + if (input[i] == target[j]) 0 else 1)
            val swap = previous; previous = current; current = swap
        }
        return previous[target.length]
    }

    fun align(input: String, target: String, maximumEdits: Int = MAX_EDITS): TypingTestAlignment? {
        if (input.length > MAX_LENGTH || target.length > MAX_LENGTH ||
            input.any { it !in 'a'..'z' } || target.any { it !in 'a'..'z' } ||
            maximumEdits !in 0..MAX_EDITS || abs(input.length - target.length) > maximumEdits) return null
        val cols = target.length + 1
        val size = (input.length + 1) * cols
        val cost = IntArray(size)
        val paths = ByteArray(size)
        val move = ByteArray(size) // 1 diagonal, 2 inserted contact, 3 missing contact
        paths[0] = 1
        for (i in 1..input.length) {
            cost[i * cols] = i; paths[i * cols] = 1; move[i * cols] = 2
        }
        for (j in 1..target.length) {
            cost[j] = j; paths[j] = 1; move[j] = 3
        }
        for (i in 1..input.length) for (j in 1..target.length) {
            val index = i * cols + j
            val diagonal = (i - 1) * cols + j - 1
            val insert = (i - 1) * cols + j
            val delete = i * cols + j - 1
            val diagonalCost = cost[diagonal] + if (input[i - 1] == target[j - 1]) 0 else 1
            val insertCost = cost[insert] + 1
            val deleteCost = cost[delete] + 1
            val best = minOf(diagonalCost, insertCost, deleteCost)
            cost[index] = best
            var count = 0
            if (diagonalCost == best) { count += paths[diagonal]; move[index] = 1 }
            if (insertCost == best) { count += paths[insert]; move[index] = 2 }
            if (deleteCost == best) { count += paths[delete]; move[index] = 3 }
            paths[index] = minOf(2, count).toByte()
        }
        val last = size - 1
        if (cost[last] > maximumEdits) return null
        if (paths[last].toInt() != 1) return TypingTestAlignment(cost[last], true, emptyList())
        val edits = ArrayList<TypingTestEdit>(maxOf(input.length, target.length))
        var i = input.length
        var j = target.length
        while (i > 0 || j > 0) when (move[i * cols + j].toInt()) {
            1 -> {
                i--; j--
                edits += TypingTestEdit(if (input[i] == target[j]) TypingTestEditKind.MATCH else
                    TypingTestEditKind.SUBSTITUTE, i, j, input[i], target[j])
            }
            2 -> { i--; edits += TypingTestEdit(TypingTestEditKind.INSERT, i, null, input[i], null) }
            3 -> { j--; edits += TypingTestEdit(TypingTestEditKind.DELETE, null, j, null, target[j]) }
            else -> error("Invalid alignment state")
        }
        edits.reverse()
        return TypingTestAlignment(cost[last], false, edits)
    }
}

/** Abstract conventional QWERTY adjacency, including plausible neighbours in both adjacent rows. */
object TypingTestQwerty {
    private data class Position(val x: Double, val row: Int)
    private val positions = buildMap {
        listOf("qwertyuiop" to 0.0, "asdfghjkl" to .5, "zxcvbnm" to 1.5)
            .forEachIndexed { row, (letters, offset) ->
                letters.forEachIndexed { column, letter -> put(letter, Position(column + offset, row)) }
            }
    }

    fun areAdjacent(actual: Char, expected: Char): Boolean {
        if (actual == expected) return false
        val a = positions[actual] ?: return false
        val b = positions[expected] ?: return false
        return abs(a.row - b.row) <= 1 && abs(a.x - b.x) <= 1.00001
    }
}
