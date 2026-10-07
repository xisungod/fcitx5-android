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
    private val inventory = PinyinSyllableInventory(syllables)
    private val contacts = ArrayList<PinyinPathContact>()
    private var literalSpelling = ""
    private var editorIdentity: Any? = null
    private var sequence = 0L
    private var lastProcessedSequence = 0L
    private var offered: PinyinMultiPathProposal? = null
    private var evaluated: PinyinMultiPathEvaluation? = null

    val rawSpelling: String
        @Synchronized get() = literalSpelling
    val contactCount: Int
        @Synchronized get() = contacts.size
    val currentProposal: PinyinMultiPathProposal?
        @Synchronized get() = offered

    val evaluationStats: PinyinMultiPathEvaluation?
        @Synchronized get() = evaluated

    /** Invalidates the visible offer without discarding earlier queued letters. */
    @Synchronized
    fun nextAction(): Long {
        offered = null
        evaluated = null
        return ++sequence
    }

    /** Allows an owner to use its existing enqueue generation, including model-loading time. */
    @Synchronized
    fun advanceSequence(actionSequence: Long) {
        if (actionSequence > sequence) {
            sequence = actionSequence
            offered = null
            evaluated = null
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
        offsets: Map<Char, CenterOffset> = emptyMap(),
        searchImmediately: Boolean = true
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
        contacts += PinyinPathContact(letter, spatialChoices(evidence, offsets))
        literalSpelling = after
        return if (searchImmediately) offerIfLatest(actionSequence) else null
    }

    /** Only an exact one-letter deletion at the composition end retains history. */
    @Synchronized
    fun recordBackspace(
        actionSequence: Long,
        editorIdentity: Any,
        beforeText: String,
        beforeCursor: Int,
        afterText: String?,
        afterCursor: Int,
        searchImmediately: Boolean = true
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
        literalSpelling = after
        if (contacts.isEmpty()) resetComposition()
        return if (searchImmediately) offerIfLatest(actionSequence) else null
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
        evaluated = null
        return true
    }

    private fun resetComposition() {
        contacts.clear()
        literalSpelling = ""
        editorIdentity = null
        offered = null
        evaluated = null
    }

    /** Captures immutable evidence only; worker evaluation never holds this lock. */
    @Synchronized
    fun captureSearch(actionSequence: Long): PinyinMultiPathSearch? {
        if (actionSequence != sequence || actionSequence != lastProcessedSequence || contacts.isEmpty()) return null
        val identity = editorIdentity ?: return null
        return PinyinMultiPathSearch(literalSpelling, actionSequence, identity, contacts.toList(),
            languageModel, inventory)
    }

    /** A result is usable only for its exact snapshot and the still-current composition. */
    @Synchronized
    fun acceptSearch(search: PinyinMultiPathSearch, result: PinyinMultiPathSearchResult): Boolean {
        if (result.search !== search || search.editorSequence != sequence ||
            search.editorSequence != lastProcessedSequence || editorIdentity !== search.editorIdentity ||
            literalSpelling != search.originalSpelling) return false
        offered = result.proposal
        evaluated = result.evaluation
        return true
    }

    private fun offerIfLatest(actionSequence: Long): PinyinMultiPathProposal? {
        val search = captureSearch(actionSequence) ?: return null
        val result = search.evaluate()
        return if (acceptSearch(search, result)) result.proposal else null
    }

    private fun spatialChoices(evidence: PinyinTapEvidence, offsets: Map<Char, CenterOffset>): List<PinyinPathChoice> {
        val tap = evidence.tap
        fun originalOnly() = listOf(PinyinPathChoice(tap.original, 0.0))
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
        val choices = cells.mapIndexed { index, cell -> PinyinPathChoice(cell.letter, scores[index] - logTotal) }
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

        /** Rime may insert spaces/apostrophes; neither changes the typed letters. */
        fun spellingAtEnd(text: String, cursor: Int): String? {
            if (text.isEmpty()) return if (cursor == 0 || cursor == -1) "" else null
            if (cursor != text.length || text.length > MAX_CONTACTS * 2 ||
                text.any { it !in 'a'..'z' && it != ' ' && it != '\'' }) return null
            return text.filter { it in 'a'..'z' }.takeIf { it.isNotEmpty() && it.length <= MAX_CONTACTS }
        }
    }
}
