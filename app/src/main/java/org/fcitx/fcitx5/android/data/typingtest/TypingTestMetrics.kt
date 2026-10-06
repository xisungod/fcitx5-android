/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.typingtest

import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTouchProfile
import kotlin.math.abs
import kotlin.math.ceil

/** Evaluation is independent of UI and decoding. It never guesses missing intention or applies a profile. */
object TypingTestMetrics {
    const val MAX_TIMING_SAMPLES = 256
    const val MAX_SESSION_TIMING_SAMPLES = MAX_TIMING_SAMPLES * 20
    private val notScored = TypingTestFraction(0, 0)

    fun evaluate(input: TypingTestTrialInput): TypingTestTrialResult {
        val reasons = linkedSetOf<String>()
        val target = input.prompt.pinyin
        val validTarget = target.isNotEmpty() && target.length <= TypingTestAligner.MAX_LENGTH &&
            target.all { it in 'a'..'z' }
        val fullPinyin = input.inputKind == TypingTestInputKind.FULL_PINYIN
        if (!validTarget) reasons += "unsupported_target"
        if (!fullPinyin) reasons += "unsupported_input_kind"
        val first = input.firstAttemptPinyin
        val firstEligible = validTarget && fullPinyin && input.firstAttemptComplete &&
            first != null && first.length == target.length && first.all { it in 'a'..'z' }
        if (!input.firstAttemptComplete) reasons += "incomplete_first_attempt"
        else if (!firstEligible && fullPinyin && validTarget) reasons += "unsupported_first_attempt"
        val alignment = if (firstEligible) TypingTestAligner.align(first, target) else null
        if (firstEligible && alignment == null) reasons += "first_attempt_exceeds_alignment_budget"
        val rawEdits = if (firstEligible) TypingTestAligner.distance(first, target)
            ?.let { TypingTestFraction(it, target.length) } ?: notScored else notScored
        val neighbourEdits = when {
            alignment == null -> notScored
            alignment.ambiguous -> { reasons += "ambiguous_alignment"; notScored }
            else -> TypingTestFraction(alignment.edits.count { it.kind == TypingTestEditKind.SUBSTITUTE &&
                TypingTestQwerty.areAdjacent(it.actual!!, it.expected!!) }, target.length)
        }

        // Freeze the first complete attempt before edits/commit, not the final repaired spelling.
        // Prefixes/stale panels are excluded; later repairs cannot turn the initial miss into a hit.
        val snapshot = input.finalCandidateSnapshot
        val final = input.finalInputPinyin
        val candidateRaw = input.candidateInputPinyin
        val candidateEligible = validTarget && fullPinyin && input.firstAttemptComplete &&
            input.priorCommitCount == 0 &&
            snapshot != null && snapshot.completePromptComposition && snapshot.coherent &&
            candidateRaw != null && candidateRaw == input.observedAttemptPinyin &&
            candidateRaw == snapshot.rawPinyin && candidateRaw.length == target.length &&
            candidateRaw.all { it in 'a'..'z' }
        if (!candidateEligible) reasons += "no_coherent_full_phrase_candidate_snapshot"
        val top1 = if (candidateEligible) TypingTestFraction(
            if (snapshot.candidates.firstOrNull() == input.prompt.text) 1 else 0, 1) else notScored
        val top3 = if (candidateEligible) TypingTestFraction(
            if (snapshot.candidates.take(3).any { it == input.prompt.text }) 1 else 0, 1) else notScored

        val validCounts = input.backspaceCount >= 0 && input.letterKeyCount >= 0
        if (!validCounts) reasons += "invalid_key_counts"
        val backspaces = input.backspaceCount.coerceAtLeast(0)
        val letters = input.letterKeyCount.coerceAtLeast(0)
        val backspaceRate = if (validCounts && letters > 0) TypingTestFraction(backspaces, letters)
            else notScored
        val completed = input.committedText == input.prompt.text
        val calibration = if (fullPinyin && validTarget && firstEligible && alignment != null &&
            !alignment.ambiguous && final == target && completed && input.priorCommitCount == 0 &&
            alignment.edits.all { it.kind == TypingTestEditKind.MATCH || it.kind == TypingTestEditKind.SUBSTITUTE }) {
            calibrationSamples(first, input.firstAttemptTouches, alignment)
        } else emptyList()
        if (calibration.isEmpty()) reasons += "no_confirmed_prompted_touch_alignment"

        return TypingTestTrialResult(input.prompt.id, input.inputKind, completed,
            first?.take(TypingTestAligner.MAX_LENGTH), final?.take(TypingTestAligner.MAX_LENGTH),
            rawEdits, neighbourEdits, top1, top3, backspaceRate, backspaces, letters, alignment,
            reasons.toList(), calibration, latency(input.processingNanos))
    }

    private fun calibrationSamples(
        first: String,
        touches: List<TypingTestTouch>,
        alignment: TypingTestAlignment
    ): List<TypingTestCalibrationSample> {
        // Coordinates must be from every original contact, not the corrected output stream.
        if (touches.size != first.length || touches.map { it.originalKey }.joinToString("") != first)
            return emptyList()
        val result = ArrayList<TypingTestCalibrationSample>(touches.size)
        for (edit in alignment.edits) {
            val index = edit.inputIndex ?: return emptyList()
            val intended = edit.expected ?: return emptyList()
            val touch = touches[index]
            if (touch.orientation !in setOf("portrait", "landscape") ||
                touch.hand !in setOf("unknown", "left", "right") ||
                !touch.downX.isFinite() || !touch.downY.isFinite() ||
                touch.cells.size !in 1..26) return emptyList()
            val signature = PinyinTouchProfile.layoutSignature(touch.cells, touch.density) ?: return emptyList()
            val originalCell = touch.cells.firstOrNull { it.letter == touch.originalKey } ?: return emptyList()
            if (!originalCell.contains(touch.downX, touch.downY)) return emptyList()
            val intendedCell = touch.cells.firstOrNull { it.letter == intended } ?: return emptyList()
            val x = (touch.downX.toDouble() - intendedCell.centerX) / intendedCell.width
            val y = (touch.downY.toDouble() - intendedCell.centerY) / intendedCell.height
            if (!x.isFinite() || !y.isFinite() || abs(x) > .65 || abs(y) > .65) return emptyList()
            result += TypingTestCalibrationSample(index, touch.originalKey, intended, signature,
                touch.orientation, touch.hand, x, y)
        }
        return result
    }

    /** Nearest-rank percentiles of actual phone measurements, in ns. No interpolated samples. */
    fun latency(samples: List<Long>, maximumSamples: Int = MAX_TIMING_SAMPLES): TypingTestLatency {
        val limit = maximumSamples.coerceIn(1, MAX_SESSION_TIMING_SAMPLES)
        val retained = samples.take(limit)
        val valid = retained.filter { it >= 0 }.sorted()
        fun percentile(fraction: Double): Long? = if (valid.isEmpty()) null else
            valid[(ceil(valid.size * fraction).toInt() - 1).coerceAtLeast(0)]
        return TypingTestLatency(valid.size, percentile(.50), percentile(.95), valid.lastOrNull(),
            retained.size - valid.size, (samples.size - limit).coerceAtLeast(0))
    }

    fun summarize(trials: List<TypingTestTrialResult>, processingNanos: List<Long> = emptyList()) =
        summarizeTypingTest(trials, processingNanos)
}

/** Aggregate numerators/denominators, never average per-trial percentages or percentile values. */
data class TypingTestSummary(
    val trialCount: Int,
    val completedCount: Int,
    val rawEditRate: TypingTestFraction,
    val adjacentSubstitutionRate: TypingTestFraction,
    val top1HitRate: TypingTestFraction,
    val top3HitRate: TypingTestFraction,
    val backspaceRate: TypingTestFraction,
    val latency: TypingTestLatency
)

fun summarizeTypingTest(
    trials: List<TypingTestTrialResult>,
    /** Actual per-key samples, not already-aggregated per-trial p95 values. */
    processingNanos: List<Long> = emptyList()
): TypingTestSummary {
    fun sum(selector: (TypingTestTrialResult) -> TypingTestFraction) = TypingTestFraction(
        trials.sumOf { selector(it).numerator }, trials.sumOf { selector(it).denominator })
    return TypingTestSummary(trials.size, trials.count { it.targetCompleted }, sum { it.rawEditRate },
        sum { it.adjacentSubstitutionRate }, sum { it.top1HitRate }, sum { it.top3HitRate },
        sum { it.backspaceRate }, TypingTestMetrics.latency(processingNanos,
            TypingTestMetrics.MAX_SESSION_TIMING_SAMPLES))
}
