/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** A dictionary-derived distribution for the next letter, including syllable boundaries. */
fun interface NextLetterProbabilityModel {
    /** Lowercase a-z probabilities, or null when this spelling has no usable evidence. */
    fun nextLetterProbabilities(prefix: String): FloatArray?
}

/** Hit rectangles and DOWN coordinates must share the keyboard's unanimated pixel space. */
data class KeyCell(
    val letter: Char,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = left + width / 2f
    val centerY get() = top + height / 2f

    internal fun isValid() = letter in 'a'..'z' &&
        listOf(left, top, right, bottom).all { it.isFinite() } &&
        width.isFinite() && height.isFinite() && width > 0f && height > 0f

    internal fun contains(x: Float, y: Float) = x >= left && x <= right && y >= top && y <= bottom
}

data class TapEvidence(
    val original: Char,
    val downX: Float,
    val downY: Float,
    val density: Float
)

/** Personalized center displacement, as fractions of this key's width and height. */
data class CenterOffset(val x: Float, val y: Float)

/**
 * A conservative decision for this contact only. It never revises earlier spelling.
 * The posterior is a model score, not a measured probability of user intention.
 * English, sensitive fields, slides and non-pinyin engines are excluded by the caller.
 */
class PinyinSpatialKeyDecider(private val languageModel: NextLetterProbabilityModel) {
    enum class Reason {
        UnsupportedTouch, ProtectedCenter, NoNearbyBoundary, ProtectedPrefix,
        MissingLanguageEvidence, InsufficientConfidence, OriginalPreferred, BoundaryCorrection
    }

    data class Decision(
        val original: Char,
        val selected: Char,
        val originalProbability: Float,
        val selectedProbability: Float,
        /** A nearby alternative remains available without modifying the prefix. */
        val alternative: Char? = null,
        val alternativeProbability: Float = 0f,
        val ambiguous: Boolean = false,
        val reason: Reason
    ) {
        val corrected get() = original != selected
    }

    fun decide(
        tap: TapEvidence,
        cells: List<KeyCell>,
        literalPrefix: String,
        offsets: Map<Char, CenterOffset> = emptyMap()
    ): Decision {
        fun keep(reason: Reason) = Decision(tap.original, tap.original, 1f, 1f, reason = reason)
        if (tap.original !in 'a'..'z' || !tap.downX.isFinite() || !tap.downY.isFinite() ||
            !tap.density.isFinite() || tap.density <= 0f) return keep(Reason.UnsupportedTouch)
        val validCells = cells.filter { it.isValid() }
        // Duplicate letters describe a split/unknown layout; avoid making up a hit location.
        if (validCells.groupingBy { it.letter }.eachCount().any { it.value != 1 })
            return keep(Reason.UnsupportedTouch)
        val original = validCells.firstOrNull { it.letter == tap.original }
            ?: return keep(Reason.UnsupportedTouch)
        if (!original.contains(tap.downX, tap.downY)) return keep(Reason.UnsupportedTouch)
        if (tap.downX >= original.left + original.width * CORE_INSET &&
            tap.downX <= original.right - original.width * CORE_INSET &&
            tap.downY >= original.top + original.height * CORE_INSET &&
            tap.downY <= original.bottom - original.height * CORE_INSET)
            return keep(Reason.ProtectedCenter)

        val band = min(MAX_BOUNDARY_DP * tap.density, min(original.width, original.height) * CORE_INSET)
        val nearby = validCells.filter { cell ->
            cell !== original && sharedBoundaryDistance(original, cell, tap.downX, tap.downY)
                ?.let { it <= band } == true
        }
        if (nearby.isEmpty()) return keep(Reason.NoNearbyBoundary)

        // A sequence of initials such as jch is valid input. Do not turn it into full pinyin.
        // Apostrophes are accepted, but language context does not cross an explicit separator.
        val prefix = literalPrefix.substringAfterLast('\'')
        if (prefix.isEmpty() || prefix.length > MAX_PREFIX_LENGTH ||
            prefix.any { it !in 'a'..'z' } ||
            (prefix.none { it in VOWELS } && prefix !in INCOMPLETE_INITIALS))
            return keep(Reason.ProtectedPrefix)
        val probabilities = languageModel.nextLetterProbabilities(prefix)
            ?: return keep(Reason.MissingLanguageEvidence)
        if (probabilities.size != 26 || probabilities.any { !it.isFinite() || it < 0f })
            return keep(Reason.MissingLanguageEvidence)
        val total = probabilities.sum().toDouble()
        if (!total.isFinite() || total <= 0.0) return keep(Reason.MissingLanguageEvidence)

        val hypotheses = listOf(original) + nearby
        val scores = hypotheses.map { cell ->
            spatialLogLikelihood(cell, tap.downX, tap.downY, offsets[cell.letter]) +
                LANGUAGE_WEIGHT * ln(max(PROBABILITY_FLOOR, probabilities[cell.letter - 'a'] / total)) +
                if (cell === original) ORIGINAL_LOG_BONUS else 0.0
        }
        val greatest = scores.maxOrNull()!!
        val weights = scores.map { exp(it - greatest) }
        val weightTotal = weights.sum()
        val posterior = weights.map { (it / weightTotal).toFloat() }
        val bestAlternativeIndex = (1 until hypotheses.size).maxByOrNull { scores[it] }!!
        val bestIndex = scores.indices.maxByOrNull { scores[it] }!!
        val alternative = hypotheses[bestAlternativeIndex]
        val confident = posterior[bestIndex] >= MIN_CORRECTION_POSTERIOR &&
            scores[bestIndex] - scores[0] >= MIN_LOG_ADVANTAGE &&
            probabilities[hypotheses[bestIndex].letter - 'a'] / total >= MIN_LANGUAGE_PROBABILITY
        val selectedIndex = if (bestIndex != 0 && confident) bestIndex else 0
        val reason = when {
            selectedIndex != 0 -> Reason.BoundaryCorrection
            bestIndex == 0 -> Reason.OriginalPreferred
            else -> Reason.InsufficientConfidence
        }
        return Decision(
            original = tap.original,
            selected = hypotheses[selectedIndex].letter,
            originalProbability = posterior[0],
            selectedProbability = posterior[selectedIndex],
            alternative = alternative.letter,
            alternativeProbability = posterior[bestAlternativeIndex],
            ambiguous = posterior[0] < MIN_UNAMBIGUOUS_POSTERIOR,
            reason = reason
        )
    }

    private fun spatialLogLikelihood(cell: KeyCell, x: Float, y: Float, offset: CenterOffset?): Double {
        val offsetX = offset?.x?.takeIf { it.isFinite() }?.coerceIn(-MAX_CENTER_OFFSET, MAX_CENTER_OFFSET) ?: 0f
        val offsetY = offset?.y?.takeIf { it.isFinite() }?.coerceIn(-MAX_CENTER_OFFSET, MAX_CENTER_OFFSET) ?: 0f
        val centerX = cell.centerX.toDouble() + cell.width.toDouble() * offsetX
        val centerY = cell.centerY.toDouble() + cell.height.toDouble() * offsetY
        val dx = (x.toDouble() - centerX) / (cell.width.toDouble() * SIGMA_X)
        val dy = (y.toDouble() - centerY) / (cell.height.toDouble() * SIGMA_Y)
        // The normalization matters when the user changes individual key widths.
        // The common 2*pi factor cancels when comparing this contact's hypotheses.
        return -0.5 * (dx * dx + dy * dy) -
            ln(cell.width.toDouble() * SIGMA_X) - ln(cell.height.toDouble() * SIGMA_Y)
    }

    /** Point-to-shared-edge distance; diagonal keys sharing only a corner are not neighbours. */
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
        private const val CORE_INSET = 0.20f
        private const val MAX_BOUNDARY_DP = 6f
        private const val SIGMA_X = 0.32f
        private const val SIGMA_Y = 0.30f
        private const val EDGE_EPSILON = 0.0001f
        private const val PROBABILITY_FLOOR = 0.002
        private const val LANGUAGE_WEIGHT = 0.72
        private const val ORIGINAL_LOG_BONUS = 0.26236426446749106 // ln(1.3)
        private const val MIN_CORRECTION_POSTERIOR = 0.82f
        private const val MIN_UNAMBIGUOUS_POSTERIOR = 0.90f
        private const val MIN_LOG_ADVANTAGE = 1.5
        private const val MIN_LANGUAGE_PROBABILITY = 0.06
        private const val MAX_PREFIX_LENGTH = 256
        const val MAX_CENTER_OFFSET = 0.12f
        private const val VOWELS = "aeiouv"
        private val INCOMPLETE_INITIALS = setOf(
            "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x",
            "zh", "ch", "sh", "r", "z", "c", "s", "y", "w"
        )
    }
}
