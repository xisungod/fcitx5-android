/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

internal data class PinyinPathChoice(val letter: Char, val logSpatialProbability: Double)
internal data class PinyinPathContact(val original: Char, val choices: List<PinyinPathChoice>)

/** These are model diagnostics, not measured probabilities of user intention. */
data class PinyinMultiPathEvaluation(
    val reason: Reason,
    val rawSpelling: String,
    val attemptedPaths: Int = 0,
    val validPaths: Int = 0,
    val modelQueries: Int = 0,
    val cacheHits: Int = 0,
    val bestAlternative: String? = null,
    val logAdvantage: Double? = null,
    val confidence: Float? = null,
    val ambiguousContactCount: Int = 0
) {
    enum class Reason {
        EmptyComposition, MissingSyllableInventory, ProtectedIncompletePrefix,
        NoBoundaryAlternatives, NoValidAlternative,
        InsufficientAdvantage, InsufficientConfidence, Offered
    }
}

class PinyinMultiPathSearchResult internal constructor(
    internal val search: PinyinMultiPathSearch,
    val proposal: PinyinMultiPathProposal?,
    val evaluation: PinyinMultiPathEvaluation
)

/** Thresholds of one search. Defaults are the shipped behaviour; tests may vary them. */
internal data class PinyinMultiPathSettings(
    val languageWeight: Double = 0.72,
    val editLogPenalty: Double = 1.5,
    val minLogAdvantage: Double = 2.0,
    val minModelConfidence: Float = 0.6f
)

/**
 * Immutable bounded search input. Evaluation owns its caches and never touches
 * tracker state or Rime. It can run on a worker while the input queue advances.
 *
 * The search is a lattice over the contacts: each contact contributes its literal
 * letter plus the nearby keys the tracker kept, and a path survives only while it
 * spells full pinyin (complete syllables, then at most one unfinished syllable).
 * Any number of contacts may differ from the literal letters; every difference pays
 * its spatial likelihood ratio and a fixed edit penalty, so distant or numerous
 * substitutions lose to the literal spelling unless the language evidence is strong.
 */
class PinyinMultiPathSearch internal constructor(
    val originalSpelling: String,
    val editorSequence: Long,
    internal val editorIdentity: Any,
    private val contacts: List<PinyinPathContact>,
    private val languageModel: NextLetterProbabilityModel,
    private val inventory: PinyinSyllableInventory,
    private val settings: PinyinMultiPathSettings = PinyinMultiPathSettings()
) {
    private class Distribution(val logProbabilities: DoubleArray?)

    /** One distinct (recognizer state, last two letters, changed?) ending of the paths so far. */
    private class Node(
        val state: Int,
        val context: Int,
        val changed: Boolean,
        var score: Double,
        var logSum: Double,
        var changes: Int,
        var previous: Node?,
        var letter: Char
    )

    /** [checkCancellation] may throw the worker's cancellation exception. */
    fun evaluate(checkCancellation: () -> Unit = {}): PinyinMultiPathSearchResult {
        checkCancellation()
        val raw = originalSpelling
        fun early(reason: PinyinMultiPathEvaluation.Reason, ambiguous: Int = 0) =
            PinyinMultiPathSearchResult(this, null,
                PinyinMultiPathEvaluation(reason, raw, ambiguousContactCount = ambiguous))
        if (raw.isEmpty()) return early(PinyinMultiPathEvaluation.Reason.EmptyComposition)
        if (inventory.isEmpty) return early(PinyinMultiPathEvaluation.Reason.MissingSyllableInventory)
        // ji+b remains an unfinished next syllable; initials are never expanded.
        if (inventory.hasProtectedIncompleteEnding(raw, inventory.fullBoundaries(raw)))
            return early(PinyinMultiPathEvaluation.Reason.ProtectedIncompletePrefix)
        val ambiguous = contacts.count { it.choices.size > 1 }
        if (ambiguous == 0) return early(PinyinMultiPathEvaluation.Reason.NoBoundaryAlternatives)

        var modelQueries = 0
        var cacheHits = 0
        fun logs(row: FloatArray?): DoubleArray? {
            var total = 0.0
            val valid = row != null && row.size == 26 && row.all {
                total += it
                it.isFinite() && it >= 0f
            } && total.isFinite() && total > 0.0
            return if (valid) DoubleArray(26) { ln(max(PROBABILITY_FLOOR, row!![it] / total)) } else null
        }
        // A sentinel also caches absent evidence; Map.getOrPut on null would
        // repeatedly run the model's full-prefix validation for an invalid path.
        val byPrefix = HashMap<String, Distribution>()
        fun probabilities(prefix: String): DoubleArray? {
            val cached = byPrefix[prefix]
            if (cached != null) {
                cacheHits++
                return cached.logProbabilities
            }
            checkCancellation()
            modelQueries++
            return logs(languageModel.nextLetterProbabilities(prefix)).also { byPrefix[prefix] = Distribution(it) }
        }
        val contextual = languageModel as? ContextualNextLetterModel
        val byContext = HashMap<Int, Distribution>()
        fun spell(node: Node): String {
            val letters = StringBuilder()
            var current: Node? = node
            while (current?.previous != null) {
                letters.append(current.letter)
                current = current.previous
            }
            return letters.reverse().toString()
        }
        /** Every lattice path is a supported full-pinyin prefix, so a contextual model needs no prefix. */
        fun row(node: Node): DoubleArray? {
            if (contextual == null) return probabilities(spell(node))
            val cached = byContext[node.context]
            if (cached != null) {
                cacheHits++
                return cached.logProbabilities
            }
            checkCancellation()
            modelQueries++
            val secondLast = (node.context / 27).takeIf { it > 0 }?.let { 'a' + (it - 1) }
            val last = (node.context % 27).takeIf { it > 0 }?.let { 'a' + (it - 1) }
            return logs(contextual.contextProbabilities(secondLast, last))
                .also { byContext[node.context] = Distribution(it) }
        }

        // The literal letters are scored exactly as typed, valid pinyin or not. Past
        // the point where they stop being full pinyin the model has no evidence.
        var literalLanguage = 0.0
        for (i in raw.indices) {
            literalLanguage += probabilities(raw.substring(0, i))?.get(raw[i] - 'a') ?: UNIFORM_LOG_PROBABILITY
        }
        val literalScore = settings.languageWeight * literalLanguage

        val automaton = inventory.automaton
        var layer = LinkedHashMap<Long, Node>()
        layer[0L] = Node(0, 0, false, 0.0, 0.0, 0, null, ' ')
        var transitions = 0
        for (index in raw.indices) {
            checkCancellation()
            val contact = contacts[index]
            val literalSpatial = contact.choices[0].logSpatialProbability
            val following = LinkedHashMap<Long, Node>()
            for (node in layer.values) {
                var distribution: DoubleArray? = null
                var loaded = false
                for (choice in contact.choices) {
                    val state = automaton.step(node.state, choice.letter)
                    if (state < 0) continue
                    if (!loaded) {
                        distribution = row(node)
                        loaded = true
                    }
                    val isChange = choice.letter != raw[index]
                    // Changed letters need genuine model evidence, but a rare letter
                    // is scored softly. Absolute next-letter cutoffs discard valid
                    // whole spellings before their following evidence can be scored.
                    if (distribution == null && isChange) continue
                    transitions++
                    val language = distribution?.get(choice.letter - 'a') ?: UNIFORM_LOG_PROBABILITY
                    val gain = choice.logSpatialProbability - literalSpatial +
                        settings.languageWeight * language - if (isChange) settings.editLogPenalty else 0.0
                    val context = (node.context % 27) * 27 + (choice.letter - 'a' + 1)
                    val changed = node.changed || isChange
                    val key = (state.toLong() * 729L + context) * 2L + if (changed) 1L else 0L
                    val score = node.score + gain
                    val changes = node.changes + if (isChange) 1 else 0
                    val existing = following[key]
                    if (existing == null) {
                        following[key] = Node(state, context, changed, score, node.logSum + gain,
                            changes, node, choice.letter)
                    } else {
                        existing.logSum = logAdd(existing.logSum, node.logSum + gain)
                        if (score > existing.score || (score == existing.score &&
                                (changes < existing.changes ||
                                    (changes == existing.changes && choice.letter < existing.letter)))) {
                            existing.score = score
                            existing.changes = changes
                            existing.previous = node
                            existing.letter = choice.letter
                        }
                    }
                }
            }
            layer = if (following.size <= MAX_LAYER_NODES) following else LinkedHashMap<Long, Node>().also { kept ->
                // Far beyond ordinary input; keep the strongest endings rather than fail.
                following.entries.sortedByDescending { it.value.score }.take(MAX_LAYER_NODES)
                    .forEach { kept[it.key] = it.value }
            }
            if (layer.isEmpty()) break
        }

        var best: Node? = null
        var total = literalScore
        var endings = 0
        for (node in layer.values) {
            if (!node.changed) continue // the literal spelling itself; already in [total]
            endings++
            total = logAdd(total, node.logSum)
            val previous = best
            if (previous == null || node.score > previous.score ||
                (node.score == previous.score && node.changes < previous.changes)) best = node
        }
        val selected = best
        val spelling = selected?.let(::spell)
        val advantage = selected?.let { it.score - literalScore }
        val confidence = selected?.let { exp(it.score - total).toFloat() }
        val reason = when {
            selected == null -> PinyinMultiPathEvaluation.Reason.NoValidAlternative
            advantage!! < settings.minLogAdvantage -> PinyinMultiPathEvaluation.Reason.InsufficientAdvantage
            confidence!! < settings.minModelConfidence -> PinyinMultiPathEvaluation.Reason.InsufficientConfidence
            else -> PinyinMultiPathEvaluation.Reason.Offered
        }
        val evaluation = PinyinMultiPathEvaluation(reason, raw, transitions, endings + 1,
            modelQueries, cacheHits, spelling, advantage, confidence, ambiguous)
        val proposal = if (reason == PinyinMultiPathEvaluation.Reason.Offered) {
            var originalSpatial = 0.0
            for (contact in contacts) originalSpatial += contact.choices[0].logSpatialProbability
            val normalization = raw.length * (1.0 + settings.languageWeight)
            val changes = raw.indices.filter { raw[it] != spelling!![it] }
            PinyinMultiPathProposal(raw, spelling!!, -(originalSpatial + literalScore) / normalization,
                -(originalSpatial + selected!!.score) / normalization, confidence!!, changes,
                editorSequence, transitions,
                PinyinTouchPromotionEvidence(advantage!!, changes.map { index ->
                    val contact = contacts[index]
                    val alternative = contact.choices.first { it.letter == spelling[index] }
                    PinyinChangedContactSpatialEvidence(index, contact.original, alternative.letter,
                        alternative.logSpatialProbability - contact.choices[0].logSpatialProbability)
                }))
        } else null
        return PinyinMultiPathSearchResult(this, proposal, evaluation)
    }

    private companion object {
        const val PROBABILITY_FLOOR = 0.002
        const val MAX_LAYER_NODES = 768
        val UNIFORM_LOG_PROBABILITY = ln(1.0 / 26.0)

        fun logAdd(a: Double, b: Double): Double =
            if (a >= b) a + ln(1.0 + exp(b - a)) else b + ln(1.0 + exp(a - b))
    }
}
