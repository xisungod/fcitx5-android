/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import kotlin.math.exp
import kotlin.math.ln

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
    val enumeratedPathCount: Int,
    /** Read-only spatial evidence, separate from the combined path score. */
    val promotionEvidence: PinyinTouchPromotionEvidence? = null
)

/**
 * Tracks the literal composition, independently of inline key correction. The
 * caller must send original letters to Rime, enqueue every action with
 * [nextAction], and [clear] on conversion, cursor movement, or unsupported edits.
 * Native before/after snapshots verify every change; uncertain history is dropped.
 *
 * There is no word model or engine query here. Each contact keeps its literal
 * letter plus the nearest keys that remain spatially plausible; the search then
 * scores every full-pinyin spelling those contacts allow.
 */
class PinyinMultiPathTracker internal constructor(
    private val languageModel: NextLetterProbabilityModel,
    syllables: Set<String>,
    private val settings: PinyinMultiPathSettings,
    private val maxSpatialLogDeficit: Double
) {
    constructor(languageModel: NextLetterProbabilityModel, syllables: Set<String>) :
        this(languageModel, syllables, PinyinMultiPathSettings(), MAX_SPATIAL_LOG_DEFICIT)

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
            languageModel, inventory, settings)
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

    /**
     * The literal key first, then the nearest other keys whose likelihood for this DOWN
     * point is within [maxSpatialLogDeficit] of it. No hard band around the border: a tap
     * deeper inside a key simply carries a larger deficit, which the search must overcome
     * with language evidence. The centre of a key leaves no plausible neighbour at all.
     */
    private fun spatialChoices(evidence: PinyinTapEvidence, offsets: Map<Char, CenterOffset>): List<PinyinPathChoice> {
        val tap = evidence.tap
        fun originalOnly() = listOf(PinyinPathChoice(tap.original, 0.0))
        if (!tap.downX.isFinite() || !tap.downY.isFinite() || !tap.density.isFinite() || tap.density <= 0f)
            return originalOnly()
        if (evidence.cells.any { !it.isValid() } ||
            evidence.cells.groupingBy { it.letter }.eachCount().any { it.value != 1 }) return originalOnly()
        val original = evidence.cells.firstOrNull { it.letter == tap.original } ?: return originalOnly()
        if (!original.contains(tap.downX, tap.downY)) return originalOnly()
        val originalScore = spatialLogLikelihood(original, tap.downX, tap.downY, offsets[original.letter]) +
            ORIGINAL_LOG_BONUS
        val neighbours = evidence.cells.asSequence().filter { it.letter != tap.original }
            .map { it to spatialLogLikelihood(it, tap.downX, tap.downY, offsets[it.letter]) }
            .filter { it.second.isFinite() && originalScore - it.second <= maxSpatialLogDeficit }
            .sortedWith(compareByDescending<Pair<KeyCell, Double>> { it.second }.thenBy { it.first.letter })
            .take(MAX_NEIGHBOURS_PER_TOUCH).toList()
        if (neighbours.isEmpty() || !originalScore.isFinite()) return originalOnly()
        val scores = listOf(originalScore) + neighbours.map { it.second }
        val greatest = scores.maxOrNull()!!
        val logTotal = greatest + ln(scores.sumOf { exp(it - greatest) })
        return listOf(PinyinPathChoice(tap.original, originalScore - logTotal)) +
            neighbours.map { PinyinPathChoice(it.first.letter, it.second - logTotal) }
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

    companion object {
        const val MAX_CONTACTS = 64
        private const val MAX_NEIGHBOURS_PER_TOUCH = 3
        private const val SIGMA_X = 0.32
        private const val SIGMA_Y = 0.30
        private const val MAX_CENTER_OFFSET = 0.12f
        private const val ORIGINAL_LOG_BONUS = 0.26236426446749106
        /** exp(-4) is about 2 percent of the literal key's likelihood. */
        internal const val MAX_SPATIAL_LOG_DEFICIT = 4.0

        /** Rime may insert spaces/apostrophes; neither changes the typed letters. */
        fun spellingAtEnd(text: String, cursor: Int): String? {
            if (text.isEmpty()) return if (cursor == 0 || cursor == -1) "" else null
            if (cursor != text.length || text.length > MAX_CONTACTS * 2 ||
                text.any { it !in 'a'..'z' && it != ' ' && it != '\'' }) return null
            return text.filter { it in 'a'..'z' }.takeIf { it.isNotEmpty() && it.length <= MAX_CONTACTS }
        }
    }
}
