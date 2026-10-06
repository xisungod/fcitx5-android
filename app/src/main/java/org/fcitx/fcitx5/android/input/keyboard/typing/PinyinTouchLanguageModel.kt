/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import java.io.Reader
import java.util.Collections

/**
 * Immutable next-letter backoff table derived from the bundled public dictionary.
 * This table supplies soft evidence, including transitions across full syllable
 * boundaries. It neither validates nor changes the user's Pinyin spelling.
 *
 * Loading must happen outside the per-key path. Parsing failures are surfaced to
 * the caller so the keyboard can keep literal input without a language model.
 */
class PinyinTouchLanguageModel(reader: Reader) : NextLetterProbabilityModel {
    private val probabilities: Map<String, FloatArray>
    val syllables: Set<String>
    private val syllablePrefixes: Set<String>
    private val maximumSyllableLength: Int
    val contextCount: Int get() = probabilities.size

    init {
        val rows = LinkedHashMap<String, FloatArray>()
        val inventory = LinkedHashSet<String>()
        var hasHeader = false
        reader.buffered().forEachLine { line ->
            if (line.startsWith("# AXPL1\t")) {
                hasHeader = true
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                require(hasHeader) { "Missing AXPL1 header" }
                val parts = line.split('\t')
                when (parts[0]) {
                    "P" -> {
                        require(parts.size == 28 && validContext(parts[1])) { "Invalid probability row" }
                        require(!rows.containsKey(parts[1])) { "Duplicate context" }
                        val values = parts.drop(2).map { it.toInt() }
                        require(values.all { it in 1..PROBABILITY_SCALE } && values.sum() == PROBABILITY_SCALE) {
                            "Invalid probability normalization"
                        }
                        rows[parts[1]] = FloatArray(26) { values[it].toFloat() / PROBABILITY_SCALE }
                    }
                    "S" -> {
                        require(parts.size == 3 && parts[1].isNotEmpty() && parts[1].all { it in 'a'..'z' }) {
                            "Invalid syllable row"
                        }
                        require(parts[2].toLong() >= 0 && inventory.add(parts[1])) { "Invalid syllable weight or duplicate" }
                    }
                    else -> throw IllegalArgumentException("Unknown AXPL1 row")
                }
            }
        }
        require(hasHeader && rows.containsKey("-") && rows.containsKey("^") && inventory.isNotEmpty()) {
            "Incomplete AXPL1 model"
        }
        probabilities = Collections.unmodifiableMap(rows)
        syllables = Collections.unmodifiableSet(inventory)
        syllablePrefixes = inventory.flatMap { syllable -> (1..syllable.length).map { syllable.take(it) } }.toSet()
        maximumSyllableLength = inventory.maxOf { it.length }
    }

    override fun nextLetterProbabilities(prefix: String): FloatArray? {
        // Unsupported punctuation/English text supplies no language evidence.
        // Apostrophe is Rime's explicit separator; a new segment uses its own
        // start context. Likely mixed abbreviation supplies no evidence, while
        // full syllables plus an unfinished final prefix retain soft backoff.
        if (prefix.any { it !in 'a'..'z' && it !in 'A'..'Z' && it != '\'' }) return null
        val segment = buildString {
            prefix.substringAfterLast('\'').forEach { append(it.lowercaseChar()) }
        }
        if (!supportsFullPinyinPrefix(segment)) return null
        val context = when (segment.length) {
            0 -> "^"
            1 -> "^$segment"
            else -> segment.takeLast(2)
        }
        val row = probabilities[context]
            ?: segment.lastOrNull()?.let { probabilities[it.toString()] }
            ?: probabilities.getValue("-")
        return row.copyOf()
    }

    private fun supportsFullPinyinPrefix(segment: String): Boolean {
        // This controls whether to supply statistical evidence, never whether a
        // letter may be typed. Full syllables plus one unfinished final prefix
        // are supported; mixed abbreviation such as ni+jch gets no evidence.
        if (segment.length > MAX_PREFIX_LENGTH) return false
        val boundaries = BooleanArray(segment.length + 1)
        boundaries[0] = true
        for (start in 0..segment.length) {
            if (!boundaries[start]) continue
            if (start == segment.length || (segment.length - start <= maximumSyllableLength &&
                        segment.substring(start) in syllablePrefixes)) return true
            val endLimit = minOf(segment.length, start + maximumSyllableLength)
            for (end in start + 1..endLimit) {
                if (segment.substring(start, end) in syllables) boundaries[end] = true
            }
        }
        return false
    }

    companion object {
        const val PROBABILITY_SCALE = 32768
        private const val MAX_PREFIX_LENGTH = 128

        fun parse(reader: Reader) = PinyinTouchLanguageModel(reader)

        private fun validContext(context: String): Boolean = context == "-" || context == "^" ||
            (context.length in 1..2 && context.all { it in 'a'..'z' }) ||
            (context.length == 2 && context[0] == '^' && context[1] in 'a'..'z')
    }
}
