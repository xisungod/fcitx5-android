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
        NoBoundaryAlternatives, TooManyAmbiguousContacts, NoValidAlternative,
        InsufficientAdvantage, InsufficientConfidence, Offered
    }
}

class PinyinMultiPathSearchResult internal constructor(
    internal val search: PinyinMultiPathSearch,
    val proposal: PinyinMultiPathProposal?,
    val evaluation: PinyinMultiPathEvaluation
)

/**
 * Immutable bounded search input. Evaluation owns its caches and never touches
 * tracker state or Rime. It can run on a worker while the input queue advances.
 */
class PinyinMultiPathSearch internal constructor(
    val originalSpelling: String,
    val editorSequence: Long,
    internal val editorIdentity: Any,
    private val contacts: List<PinyinPathContact>,
    private val languageModel: NextLetterProbabilityModel,
    private val inventory: PinyinSyllableInventory
) {
    private data class PathScore(val spelling: String, val logScore: Double, val changes: List<Int>)
    private data class Distribution(val logProbabilities: DoubleArray?)

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
        val ambiguous = contacts.indices.filter { contacts[it].choices.size > 1 }
        if (ambiguous.isEmpty()) return early(PinyinMultiPathEvaluation.Reason.NoBoundaryAlternatives)
        if (ambiguous.size > PinyinMultiPathTracker.MAX_AMBIGUOUS_CONTACTS)
            return early(PinyinMultiPathEvaluation.Reason.TooManyAmbiguousContacts, ambiguous.size)

        var modelQueries = 0
        var cacheHits = 0
        // A sentinel also caches absent evidence; Map.getOrPut on null would
        // repeatedly run the model's full-prefix validation for an invalid path.
        val distributions = HashMap<String, Distribution>()
        fun probabilities(prefix: String): DoubleArray? {
            val cached = distributions[prefix]
            if (cached != null) {
                cacheHits++
                return cached.logProbabilities
            }
            checkCancellation()
            modelQueries++
            val row = languageModel.nextLetterProbabilities(prefix)
            var total = 0.0
            val valid = row != null && row.size == 26 && row.all {
                total += it
                it.isFinite() && it >= 0f
            } && total.isFinite() && total > 0.0
            val logs = if (valid) DoubleArray(26) {
                ln(max(PROBABILITY_FLOOR, row[it] / total))
            } else null
            distributions[prefix] = Distribution(logs)
            return logs
        }

        var originalSpatial = 0.0
        for (contact in contacts) originalSpatial += contact.choices[0].logSpatialProbability
        val originalLanguagePrefixes = DoubleArray(raw.length + 1)
        fun score(spelling: String, changes: List<Int>): PathScore? {
            checkCancellation()
            if (changes.isNotEmpty() && !inventory.fullBoundaries(spelling)[spelling.length]) return null
            var spatial = originalSpatial
            for (index in changes) {
                val contact = contacts[index]
                spatial += contact.choices.first { it.letter == spelling[index] }.logSpatialProbability -
                    contact.choices[0].logSpatialProbability
            }
            // The unmodified leading prefix has exactly the original score.
            // Reuse it instead of hashing/copying its strings in every path.
            val start = changes.firstOrNull() ?: 0
            var language = originalLanguagePrefixes[start]
            for (i in start until spelling.length) {
                val distribution = probabilities(spelling.substring(0, i))
                // Changed letters need genuine model evidence, but a rare letter
                // is scored softly. Absolute next-letter cutoffs discard valid
                // whole spellings before their following evidence can be scored.
                if (distribution == null && i in changes) return null
                language += distribution?.get(spelling[i] - 'a') ?: UNIFORM_LOG_PROBABILITY
                if (changes.isEmpty()) originalLanguagePrefixes[i + 1] = language
            }
            return PathScore(spelling, spatial + LANGUAGE_WEIGHT * language -
                changes.size * EDIT_LOG_PENALTY, changes)
        }

        val original = score(raw, emptyList())!!
        var best: PathScore? = null
        // Maintain log-sum-exp online; retaining all path objects is unnecessary.
        var greatest = original.logScore
        var weightTotal = 1.0
        var validPaths = 1
        var enumerated = 1
        fun consider(spelling: String, changes: List<Int>) {
            enumerated++
            val path = score(spelling, changes) ?: return
            validPaths++
            if (path.logScore > greatest) {
                weightTotal = weightTotal * exp(greatest - path.logScore) + 1.0
                greatest = path.logScore
            } else {
                weightTotal += exp(path.logScore - greatest)
            }
            val previous = best
            if (previous == null || path.logScore > previous.logScore ||
                (path.logScore == previous.logScore && (path.changes.size < previous.changes.size ||
                    (path.changes.size == previous.changes.size && path.spelling < previous.spelling)))) {
                best = path
            }
        }
        val letters = raw.toCharArray()
        for (position in ambiguous.indices) {
            val first = ambiguous[position]
            val firstChoices = contacts[first].choices
            for (firstChoiceIndex in 1 until firstChoices.size) {
                letters[first] = firstChoices[firstChoiceIndex].letter
                consider(String(letters), listOf(first))
                for (secondPosition in position + 1 until ambiguous.size) {
                    val second = ambiguous[secondPosition]
                    val secondChoices = contacts[second].choices
                    for (secondChoiceIndex in 1 until secondChoices.size) {
                        letters[second] = secondChoices[secondChoiceIndex].letter
                        consider(String(letters), listOf(first, second))
                    }
                    letters[second] = raw[second]
                }
            }
            letters[first] = raw[first]
        }
        check(enumerated <= PinyinMultiPathTracker.MAX_ENUMERATED_PATHS)
        val selected = best
        val advantage = selected?.let { it.logScore - original.logScore }
        val confidence = selected?.let { (exp(it.logScore - greatest) / weightTotal).toFloat() }
        val reason = when {
            selected == null -> PinyinMultiPathEvaluation.Reason.NoValidAlternative
            advantage!! < MIN_LOG_ADVANTAGE -> PinyinMultiPathEvaluation.Reason.InsufficientAdvantage
            confidence!! < MIN_MODEL_CONFIDENCE -> PinyinMultiPathEvaluation.Reason.InsufficientConfidence
            else -> PinyinMultiPathEvaluation.Reason.Offered
        }
        val evaluation = PinyinMultiPathEvaluation(reason, raw, enumerated, validPaths,
            modelQueries, cacheHits, selected?.spelling, advantage, confidence, ambiguous.size)
        val proposal = if (reason == PinyinMultiPathEvaluation.Reason.Offered) {
            val normalization = raw.length * (1.0 + LANGUAGE_WEIGHT)
            PinyinMultiPathProposal(raw, selected!!.spelling, -original.logScore / normalization,
                -selected.logScore / normalization, confidence!!, selected.changes, editorSequence, enumerated)
        } else null
        return PinyinMultiPathSearchResult(this, proposal, evaluation)
    }

    private companion object {
        const val PROBABILITY_FLOOR = 0.002
        const val LANGUAGE_WEIGHT = 0.72
        const val EDIT_LOG_PENALTY = 1.5
        const val MIN_LOG_ADVANTAGE = 2.0
        const val MIN_MODEL_CONFIDENCE = 0.82f
        val UNIFORM_LOG_PROBABILITY = ln(1.0 / 26.0)
    }
}
