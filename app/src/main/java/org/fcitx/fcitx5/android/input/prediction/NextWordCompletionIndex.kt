/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.prediction

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** A bounded, read-only asset. Only row offsets and its UTF-8 bytes remain in memory. */
internal class NextWordCompletionIndex private constructor(
    private val bytes: ByteArray,
    private val rowOffsets: IntArray
) {
    data class Match(val prefix: String, val singles: List<String>, val multis: List<String>,
                     val specificContinuation: Boolean = false)

    fun lookup(prefix: String): Match? {
        if (!isHan(prefix, 1, MAX_PREFIX_CODE_POINTS)) return null
        val key = prefix.toByteArray(Charsets.UTF_8)
        var low = 0
        var high = rowOffsets.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val start = rowOffsets[middle]
            val prefixEnd = fieldEnd(bytes, start, TAB)
            val compared = compareBytes(bytes, start, prefixEnd, key, 0, key.size)
            when {
                compared < 0 -> low = middle + 1
                compared > 0 -> high = middle - 1
                else -> {
                    val end = fieldEnd(bytes, prefixEnd + 1, NEWLINE)
                    val line = String(bytes, start, end - start, Charsets.UTF_8)
                    val fields = line.split('\t')
                    return Match(fields[0], values(fields[1]), values(fields[2]), fields.getOrNull(3) == "1")
                }
            }
        }
        return null
    }

    /** Longer matching phrases win over their last character; only the trailing Han run is read. */
    fun trailingMatches(context: String): List<Match> {
        val prefixes = ArrayList<String>(MAX_PREFIX_CODE_POINTS)
        var start = context.length
        repeat(MAX_PREFIX_CODE_POINTS) {
            if (start == 0) return@repeat
            val point = context.codePointBefore(start)
            val previous = start - Character.charCount(point)
            if (!isHan(context.substring(previous, start), 1, 1)) return prefixes
                .asReversed().mapNotNull(::lookup)
            start = previous
            prefixes.add(context.substring(start))
        }
        return prefixes.asReversed().mapNotNull(::lookup)
    }

    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
        private const val MAX_ROWS = 524_288
        private const val MAX_LINE_BYTES = 2048
        private const val MAX_HEADER_LINE_BYTES = 8192
        private const val MAX_PREFIX_CODE_POINTS = 6
        private const val MAX_SUFFIX_CODE_POINTS = 6
        private const val MAX_SINGLE_ITEMS = 34
        private const val MAX_MULTI_ITEMS = 74
        private const val TAB = 9
        private const val NEWLINE = 10
        private const val MAGIC_V1 = "# AXiang next-word completions v1"
        private const val MAGIC_V2 = "# AXiang next-word completions v2"

        fun parse(source: ByteArray): NextWordCompletionIndex {
            require(source.isNotEmpty() && source.size <= MAX_BYTES) { "InvalidCompletionIndexSize" }
            require(source.last().toInt() == NEWLINE) { "IncompleteCompletionIndex" }
            // The caller cannot mutate a validated index by retaining its loading buffer.
            val bytes = source.copyOf()
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val rows = ArrayList<Int>()
            var lineNumber = 0
            var version = 0
            var start = 0
            var expectedRows: Int? = null
            var previousPrefixStart = -1
            var previousPrefixEnd = -1
            var dataStarted = false
            while (start < bytes.size) {
                val end = fieldEnd(bytes, start, NEWLINE)
                require(end - start <= if (bytes[start].toInt() == '#'.code && !dataStarted)
                    MAX_HEADER_LINE_BYTES else if (version == 1) 1024 else MAX_LINE_BYTES) { "CompletionIndexLineTooLong" }
                val line = decoder.reset().decode(ByteBuffer.wrap(bytes, start, end - start)).toString()
                when {
                    lineNumber == 0 -> {
                        version = when (line) {
                            MAGIC_V1 -> 1
                            MAGIC_V2 -> 2
                            else -> throw IllegalArgumentException("InvalidCompletionIndexVersion")
                        }
                        require(version != 1 || bytes.size <= 1024 * 1024) { "InvalidCompletionIndexSize" }
                    }
                    lineNumber == 1 -> {
                        require(line.startsWith("# entries: ")) { "MissingCompletionIndexCount" }
                        expectedRows = line.substringAfter("# entries: ").toIntOrNull()
                        require(expectedRows != null && expectedRows in 1..(if (version == 1) 65_536 else MAX_ROWS)) {
                            "InvalidCompletionIndexCount"
                        }
                    }
                    line.startsWith("#") -> require(!dataStarted) { "UnexpectedCompletionIndexHeader" }
                    else -> {
                        dataStarted = true
                        val fields = line.split('\t')
                        require(fields.size == (if (version == 1) 3 else 4) &&
                            isHan(fields[0], 1, if (version == 1) 4 else MAX_PREFIX_CODE_POINTS)) {
                            "InvalidCompletionIndexRow"
                        }
                        val singles = values(fields[1])
                        val multis = values(fields[2])
                        require(singles.size <= (if (version == 1) 24 else MAX_SINGLE_ITEMS) &&
                            multis.size <= (if (version == 1) 64 else MAX_MULTI_ITEMS) &&
                            singles.size + multis.size > 0 && singles.all { isHan(it, 1, 1) } &&
                            multis.all { isHan(it, 2, if (version == 1) 4 else MAX_SUFFIX_CODE_POINTS) } &&
                            singles.distinct().size == singles.size && multis.distinct().size == multis.size) {
                            "InvalidCompletionIndexCandidates"
                        }
                        if (version == 2) require((fields[3] == "0" || fields[3] == "1") &&
                            (fields[3] != "1" || (fields[0].codePointCount(0, fields[0].length) >= 2 && multis.isNotEmpty()))) {
                            "InvalidCompletionIndexSpecificity"
                        }
                        val prefixEnd = fieldEnd(bytes, start, TAB)
                        require(previousPrefixStart < 0 || compareBytes(bytes,
                            previousPrefixStart, previousPrefixEnd, bytes, start, prefixEnd) < 0) {
                            "UnsortedCompletionIndex"
                        }
                        rows.add(start)
                        require(rows.size <= MAX_ROWS) { "CompletionIndexTooManyRows" }
                        previousPrefixStart = start
                        previousPrefixEnd = prefixEnd
                    }
                }
                start = end + 1
                lineNumber++
            }
            require(dataStarted && rows.size == expectedRows) { "IncompleteCompletionIndex" }
            return NextWordCompletionIndex(bytes, rows.toIntArray())
        }

        private fun values(field: String): List<String> = if (field.isEmpty()) emptyList() else field.split(',')

        private fun isHan(text: String, minimum: Int, maximum: Int): Boolean =
            NextWordPredictionRuntime.isHanText(text) &&
                text.codePointCount(0, text.length) in minimum..maximum

        private fun fieldEnd(bytes: ByteArray, start: Int, separator: Int): Int {
            var end = start
            while (end < bytes.size && bytes[end].toInt() != separator) end++
            return end
        }

        private fun compareBytes(left: ByteArray, leftStart: Int, leftEnd: Int,
            right: ByteArray, rightStart: Int, rightEnd: Int): Int {
            val length = minOf(leftEnd - leftStart, rightEnd - rightStart)
            for (index in 0 until length) {
                val difference = (left[leftStart + index].toInt() and 0xff) -
                    (right[rightStart + index].toInt() and 0xff)
                if (difference != 0) return difference
            }
            return (leftEnd - leftStart) - (rightEnd - rightStart)
        }
    }
}
