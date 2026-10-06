/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * One optional whole-spelling backup. Costs and confidence describe this model's
 * bounded hypotheses, not the probability that the user intended a word.
 * [originalSpelling] always describes the literal letters sent to the engine.
 */
data class PinyinMultiPathProposal(
    val originalSpelling: String,
    val alternativeSpelling: String,
    val originalCost: Double,
    val alternativeCost: Double,
    val modelConfidence: Float,
    val changedIndices: List<Int>,
    val editorSequence: Long,
    val enumeratedPathCount: Int
)

/**
 * Tracks the literal composition, independently of inline key correction. The
 * caller must send original letters to Rime, enqueue every action with
 * [nextAction], and [clear] on conversion, cursor movement, or unsupported edits.
 * Native before/after snapshots verify every change; uncertain history is dropped.
 *
 * There is no word model, beam, trie search, or engine query here. At most two
 * boundary contacts may differ in one bounded, exhaustively scored backup.
 */
class PinyinMultiPathTracker(
    private val languageModel: NextLetterProbabilityModel,
    syllables: Set<String>
) {
    private data class Choice(val letter: Char, val logSpatialProbability: Double)
    private data class Contact(val original: Char, val choices: List<Choice>)
    private data class PathScore(val spelling: String, val logScore: Double, val changes: List<Int>)

    private val syllables = syllables.filter { it.isNotEmpty() && it.all { c -> c in 'a'..'z' } }.toSet()
    private val syllablePrefixes = this.syllables.flatMap { word ->
        (1 until word.length).map { word.take(it) }
    }.toSet()
    private val maximumSyllableLength = this.syllables.maxOfOrNull { it.length } ?: 0
    private val contacts = ArrayList<Contact>()
    private var editorIdentity: Any? = null
    private var sequence = 0L
    private var lastProcessedSequence = 0L
    private var offered: PinyinMultiPathProposal? = null

    val rawSpelling: String
        @Synchronized get() = contacts.joinToString("") { it.original.toString() }
    val contactCount: Int
        @Synchronized get() = contacts.size
    val currentProposal: PinyinMultiPathProposal?
        @Synchronized get() = offered

    /** Invalidates the visible offer without discarding earlier queued letters. */
    @Synchronized
    fun nextAction(): Long {
        offered = null
        return ++sequence
    }

    /** Allows an owner to use its existing enqueue generation, including model-loading time. */
    @Synchronized
    fun advanceSequence(actionSequence: Long) {
        if (actionSequence > sequence) {
            sequence = actionSequence
            offered = null
        }
    }

    /** Also invalidates delayed completions from before this reset. */
    @Synchronized
    fun clear() {
        nextAction()
        lastProcessedSequence = sequence
        resetComposition()
    }

    /**
     * Called after one serialized native letter operation, with its original
     * DOWN evidence. Older queued operations still update verified history, but
     * only the most recently enqueued operation may publish a proposal.
     */
    @Synchronized
    fun recordTap(
        actionSequence: Long,
        editorIdentity: Any,
        beforeText: String,
        beforeCursor: Int,
        afterText: String?,
        afterCursor: Int,
        evidence: PinyinTapEvidence,
        offsets: Map<Char, CenterOffset> = emptyMap()
    ): PinyinMultiPathProposal? {
        if (!acceptSequence(actionSequence)) return null
        val before = spellingAtEnd(beforeText, beforeCursor)
        val after = afterText?.let { spellingAtEnd(it, afterCursor) }
        val letter = evidence.tap.original
        if (letter !in 'a'..'z' || before == null || after == null || after != before + letter ||
            after.length > MAX_CONTACTS) {
            resetComposition()
            return null
        }
        if (before.isEmpty()) {
            resetComposition()
            this.editorIdentity = editorIdentity
        } else if (this.editorIdentity !== editorIdentity || before != rawSpelling) {
            resetComposition()
            return null
        }
        contacts += Contact(letter, spatialChoices(evidence, offsets))
        return offerIfLatest(actionSequence)
    }

    /** Only an exact one-letter deletion at the composition end retains history. */
    @Synchronized
    fun recordBackspace(
        actionSequence: Long,
        editorIdentity: Any,
        beforeText: String,
        beforeCursor: Int,
        afterText: String?,
        afterCursor: Int
    ): PinyinMultiPathProposal? {
        if (!acceptSequence(actionSequence)) return null
        val before = spellingAtEnd(beforeText, beforeCursor)
        val after = afterText?.let { spellingAtEnd(it, afterCursor) }
        if (this.editorIdentity !== editorIdentity || before == null || before != rawSpelling ||
            contacts.isEmpty() || after != before.dropLast(1)) {
            resetComposition()
            return null
        }
        contacts.removeAt(contacts.lastIndex)
        if (contacts.isEmpty()) resetComposition()
        return offerIfLatest(actionSequence)
    }

    /** Guard again inside the native selection transaction, immediately before use. */
    @Synchronized
    fun matchesProposal(
        token: Long,
        editorIdentity: Any?,
        currentText: String,
        currentCursor: Int
    ): Boolean = offered?.let {
        token == sequence && token == it.editorSequence &&
            this.editorIdentity === editorIdentity &&
            spellingAtEnd(currentText, currentCursor) == it.originalSpelling &&
            rawSpelling == it.originalSpelling
    } == true

    private fun acceptSequence(actionSequence: Long): Boolean {
        if (actionSequence <= lastProcessedSequence || actionSequence > sequence) return false
        lastProcessedSequence = actionSequence
        offered = null
        return true
    }

    private fun resetComposition() {
        contacts.clear()
        editorIdentity = null
        offered = null
    }

    private fun offerIfLatest(actionSequence: Long): PinyinMultiPathProposal? {
        if (actionSequence != sequence) return null
        offered = propose(actionSequence)
        return offered
    }

    private fun propose(actionSequence: Long): PinyinMultiPathProposal? {
        val raw = rawSpelling
        if (raw.isEmpty() || syllables.isEmpty()) return null
        val boundaries = fullSyllableBoundaries(raw)
        // ji+b is a legitimate unfinished next syllable. Likewise ni+jch and
        // initials-only input must not acquire a full-spelling backup by expansion.
        if (!boundaries[raw.length] && (0 until raw.length).any { start ->
                boundaries[start] && (raw.substring(start) in syllablePrefixes ||
                    raw.substring(start).all { it !in VOWELS })
            }) return null

        val ambiguous = contacts.indices.filter { contacts[it].choices.size > 1 }
        if (ambiguous.isEmpty() || ambiguous.size > MAX_AMBIGUOUS_CONTACTS) return null
        val distributions = HashMap<String, DoubleArray?>()
        fun probabilities(prefix: String): DoubleArray? = distributions.getOrPut(prefix) {
            languageModel.nextLetterProbabilities(prefix)?.takeIf { row ->
                row.size == 26 && row.all { it.isFinite() && it >= 0f } &&
                    row.sum().isFinite() && row.sum() > 0f
            }?.let { row ->
                val total = row.sum().toDouble()
                DoubleArray(26) { row[it] / total }
            }
        }
        fun score(spelling: String, changes: List<Int>): PathScore? {
            if (changes.isNotEmpty() && !fullSyllableBoundaries(spelling)[spelling.length]) return null
            var spatial = 0.0
            var language = 0.0
            for (i in spelling.indices) {
                val letter = spelling[i]
                spatial += contacts[i].choices.first { it.letter == letter }.logSpatialProbability
                val distribution = probabilities(spelling.substring(0, i))
                // An invalid raw prefix remains finite and represented. Missing
                // evidence contributes a uniform prior, never a skipped position.
                val probability = distribution?.get(letter - 'a') ?: 1.0 / 26.0
                if (i in changes && (distribution == null || probability < MIN_CHANGED_LANGUAGE_PROBABILITY))
                    return null
                language += ln(max(PROBABILITY_FLOOR, probability))
            }
            return PathScore(spelling, spatial + LANGUAGE_WEIGHT * language -
                changes.size * EDIT_LOG_PENALTY, changes)
        }

        val original = score(raw, emptyList())!!
        val paths = ArrayList<PathScore>()
        paths += original
        var enumerated = 1
        val letters = raw.toCharArray()
        for ((position, first) in ambiguous.withIndex()) {
            for (firstChoice in contacts[first].choices.drop(1)) {
                letters[first] = firstChoice.letter
                enumerated++
                score(String(letters), listOf(first))?.let(paths::add)
                for (second in ambiguous.drop(position + 1)) {
                    for (secondChoice in contacts[second].choices.drop(1)) {
                        letters[second] = secondChoice.letter
                        enumerated++
                        score(String(letters), listOf(first, second))?.let(paths::add)
                    }
                    letters[second] = raw[second]
                }
            }
            letters[first] = raw[first]
        }
        check(enumerated <= MAX_ENUMERATED_PATHS)
        val best = paths.drop(1).minWithOrNull(compareByDescending<PathScore> { it.logScore }
            .thenBy { it.changes.size }.thenBy { it.spelling }) ?: return null
        if (best.logScore - original.logScore < MIN_LOG_ADVANTAGE) return null
        val greatest = paths.maxOf { it.logScore }
        val confidence = (exp(best.logScore - greatest) /
            paths.sumOf { exp(it.logScore - greatest) }).toFloat()
        if (confidence < MIN_MODEL_CONFIDENCE) return null
        val normalization = raw.length * (1.0 + LANGUAGE_WEIGHT)
        return PinyinMultiPathProposal(raw, best.spelling, -original.logScore / normalization,
            -best.logScore / normalization, confidence, best.changes, actionSequence, enumerated)
    }

    /** Inventory membership only; this DP neither generates nor ranks paths. */
    private fun fullSyllableBoundaries(spelling: String): BooleanArray {
        val boundaries = BooleanArray(spelling.length + 1)
        boundaries[0] = true
        for (start in spelling.indices) {
            if (!boundaries[start]) continue
            for (end in start + 1..minOf(spelling.length, start + maximumSyllableLength)) {
                if (spelling.substring(start, end) in syllables) boundaries[end] = true
            }
        }
        return boundaries
    }

    private fun spatialChoices(evidence: PinyinTapEvidence, offsets: Map<Char, CenterOffset>): List<Choice> {
        val tap = evidence.tap
        fun originalOnly() = listOf(Choice(tap.original, 0.0))
        if (!tap.downX.isFinite() || !tap.downY.isFinite() || !tap.density.isFinite() || tap.density <= 0f)
            return originalOnly()
        if (evidence.cells.any { !it.isValid() } ||
            evidence.cells.groupingBy { it.letter }.eachCount().any { it.value != 1 }) return originalOnly()
        val original = evidence.cells.firstOrNull { it.letter == tap.original } ?: return originalOnly()
        if (!original.contains(tap.downX, tap.downY)) return originalOnly()
        if (tap.downX >= original.left + original.width * CORE_INSET &&
            tap.downX <= original.right - original.width * CORE_INSET &&
            tap.downY >= original.top + original.height * CORE_INSET &&
            tap.downY <= original.bottom - original.height * CORE_INSET) return originalOnly()
        val band = min(MAX_BOUNDARY_DP * tap.density, min(original.width, original.height) * CORE_INSET)
        val neighbours = evidence.cells.filter { cell ->
            cell.letter != tap.original && sharedBoundaryDistance(original, cell, tap.downX, tap.downY)
                ?.let { it <= band } == true
        }
        // A contact near an unknown junction must not silently discard competitors.
        if (neighbours.isEmpty() || neighbours.size > MAX_NEIGHBOURS_PER_TOUCH) return originalOnly()
        val cells = listOf(original) + neighbours.sortedBy { it.letter }
        val scores = cells.map { cell ->
            spatialLogLikelihood(cell, tap.downX, tap.downY, offsets[cell.letter]) +
                if (cell.letter == tap.original) ORIGINAL_LOG_BONUS else 0.0
        }
        val greatest = scores.maxOrNull()!!
        val logTotal = greatest + ln(scores.sumOf { exp(it - greatest) })
        val choices = cells.mapIndexed { index, cell -> Choice(cell.letter, scores[index] - logTotal) }
        return if (exp(choices[0].logSpatialProbability) >= MIN_UNAMBIGUOUS_SPATIAL_PROBABILITY)
            originalOnly() else choices
    }

    private fun spatialLogLikelihood(cell: KeyCell, x: Float, y: Float, offset: CenterOffset?): Double {
        val offsetX = offset?.x?.takeIf { it.isFinite() }?.coerceIn(-MAX_CENTER_OFFSET, MAX_CENTER_OFFSET) ?: 0f
        val offsetY = offset?.y?.takeIf { it.isFinite() }?.coerceIn(-MAX_CENTER_OFFSET, MAX_CENTER_OFFSET) ?: 0f
        val dx = (x.toDouble() - cell.centerX.toDouble() - cell.width.toDouble() * offsetX) /
            (cell.width.toDouble() * SIGMA_X)
        val dy = (y.toDouble() - cell.centerY.toDouble() - cell.height.toDouble() * offsetY) /
            (cell.height.toDouble() * SIGMA_Y)
        // Width/height normalization preserves evidence under custom key sizing.
        return -0.5 * (dx * dx + dy * dy) -
            ln(cell.width.toDouble() * SIGMA_X) - ln(cell.height.toDouble() * SIGMA_Y)
    }

    /** Keys that share only a corner, or have a gap/overlap, are not neighbours. */
    private fun sharedBoundaryDistance(a: KeyCell, b: KeyCell, x: Float, y: Float): Float? {
        val epsilon = min(min(a.width, a.height), min(b.width, b.height)) * EDGE_EPSILON
        val overlapTop = max(a.top, b.top)
        val overlapBottom = min(a.bottom, b.bottom)
        if (overlapBottom - overlapTop > epsilon) {
            val edge = when {
                abs(a.right - b.left) <= epsilon -> (a.right + b.left) / 2f
                abs(b.right - a.left) <= epsilon -> (b.right + a.left) / 2f
                else -> null
            }
            if (edge != null) return hypot(x - edge, y - y.coerceIn(overlapTop, overlapBottom))
        }
        val overlapLeft = max(a.left, b.left)
        val overlapRight = min(a.right, b.right)
        if (overlapRight - overlapLeft > epsilon) {
            val edge = when {
                abs(a.bottom - b.top) <= epsilon -> (a.bottom + b.top) / 2f
                abs(b.bottom - a.top) <= epsilon -> (b.bottom + a.top) / 2f
                else -> null
            }
            if (edge != null) return hypot(y - edge, x - x.coerceIn(overlapLeft, overlapRight))
        }
        return null
    }

    companion object {
        const val MAX_CONTACTS = 64
        const val MAX_AMBIGUOUS_CONTACTS = 15
        // 1 original + 30 single changes + 4 * C(15, 2) pairs = 451, below 512.
        const val MAX_ENUMERATED_PATHS = 451
        private const val MAX_NEIGHBOURS_PER_TOUCH = 2
        private const val CORE_INSET = 0.20f
        private const val MAX_BOUNDARY_DP = 6f
        private const val SIGMA_X = 0.32
        private const val SIGMA_Y = 0.30
        private const val EDGE_EPSILON = 0.0001f
        private const val MAX_CENTER_OFFSET = 0.12f
        private const val ORIGINAL_LOG_BONUS = 0.26236426446749106
        private const val MIN_UNAMBIGUOUS_SPATIAL_PROBABILITY = 0.90
        private const val PROBABILITY_FLOOR = 0.002
        private const val LANGUAGE_WEIGHT = 0.72
        private const val EDIT_LOG_PENALTY = 1.5
        private const val MIN_CHANGED_LANGUAGE_PROBABILITY = 0.06
        private const val MIN_LOG_ADVANTAGE = 2.0
        private const val MIN_MODEL_CONFIDENCE = 0.82f
        private const val VOWELS = "aeiouv"

        /** Rime may insert spaces/apostrophes; neither changes the typed letters. */
        fun spellingAtEnd(text: String, cursor: Int): String? {
            if (text.isEmpty()) return if (cursor == 0 || cursor == -1) "" else null
            if (cursor != text.length || text.length > MAX_CONTACTS * 2 ||
                text.any { it !in 'a'..'z' && it != ' ' && it != '\'' }) return null
            return text.filter { it in 'a'..'z' }.takeIf { it.isNotEmpty() && it.length <= MAX_CONTACTS }
        }
    }
}
