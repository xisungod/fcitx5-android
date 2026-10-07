/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.typingtest

import android.content.Context
import org.fcitx.fcitx5.android.R
import java.util.Locale

/** Report-only formatting: target metadata and test receipts never reach the predictor. */
internal object TypingTestPredictionReport {
    fun warning(context: Context, metrics: TypingTestPredictionMetrics): String? {
        val failures = (metrics.queryOutcomes["Unavailable"] ?: 0) + (metrics.queryOutcomes["Failed"] ?: 0)
        val timeouts = metrics.queryOutcomes["Timeout"] ?: 0
        val nativeWarning = if (failures > 0 || timeouts > 0) context.getString(
            R.string.typing_test_prediction_warning, failures, timeouts) else null
        val completionFailures = metrics.queryRecords.count {
            it.available == true && (it.completionAvailable == false || it.completionFailureReason != null)
        }
        val completionWarning = if (completionFailures > 0) context.getString(
            R.string.typing_test_prediction_completion_warning, completionFailures) else null
        return listOfNotNull(nativeWarning, completionWarning).takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    fun summary(context: Context, metrics: TypingTestPredictionMetrics): String {
        fun fraction(value: TypingTestFraction) = value.value?.let { context.getString(
            R.string.typing_test_prediction_fraction, it * 100, value.numerator, value.denominator) }
            ?: context.getString(R.string.typing_test_prediction_no_samples)
        fun time(nanos: Long?) = nanos?.let { String.format(Locale.ROOT, "%.2f ms", it / 1_000_000.0) }
            ?: "--"
        fun warmth(value: TypingTestPredictionWarmth) = context.getString(when (value) {
            TypingTestPredictionWarmth.Cold -> R.string.typing_test_prediction_cold
            TypingTestPredictionWarmth.Warm -> R.string.typing_test_prediction_warm
            TypingTestPredictionWarmth.Cache -> R.string.typing_test_prediction_cache
            TypingTestPredictionWarmth.Unknown -> R.string.typing_test_prediction_unknown
        })
        fun latency(label: String, value: TypingTestLatency) = context.getString(
            R.string.typing_test_prediction_latency, label, time(value.p50Nanos), time(value.p95Nanos),
            value.sampleCount, value.invalidSampleCount)
        val reasons = metrics.unscoredReasons.entries.joinToString(" · ") { "${it.key}: ${it.value}" }
            .ifEmpty { context.getString(R.string.typing_test_prediction_empty_reasons) }
        val outcomes = metrics.queryOutcomes.entries.joinToString(" · ") { "${it.key}: ${it.value}" }
            .ifEmpty { context.getString(R.string.typing_test_prediction_no_samples) }
        return buildString {
            appendLine(context.getString(R.string.typing_test_prediction_title))
            appendLine(context.getString(R.string.typing_test_prediction_counts,
                metrics.successfulCommitCount, metrics.failedCommitCount, metrics.publishedCount,
                metrics.drawnCount, metrics.selectedCount, metrics.adoptedCount))
            appendLine(context.getString(R.string.typing_test_prediction_coverage,
                fraction(metrics.commitDrawCoverage), fraction(metrics.adoptionRate),
                fraction(metrics.adoptionDrawCoverage), fraction(metrics.targetHitRate),
                fraction(metrics.targetScoringCoverage)))
            appendLine(context.getString(R.string.typing_test_prediction_savings,
                if (metrics.savingsCoverage.numerator > 0) metrics.estimatedPinyinLetterKeys.toString()
                else context.getString(R.string.typing_test_prediction_no_samples),
                fraction(metrics.savingsCoverage)))
            appendLine(latency(context.getString(R.string.typing_test_prediction_all), metrics.drawLatency))
            TypingTestPredictionWarmth.entries.forEach { mode ->
                val value = metrics.drawLatencyByWarmth[mode]
                if (value != null && (value.sampleCount > 0 || value.invalidSampleCount > 0))
                    appendLine(latency(warmth(mode), value))
                val queries = metrics.queryRecords.filter { it.warmth == mode }
                if (queries.isNotEmpty()) appendLine(context.getString(R.string.typing_test_prediction_query,
                    warmth(mode), queries.size,
                    time(TypingTestMetrics.latency(queries.mapNotNull { it.elapsedNanos }).p95Nanos),
                    time(TypingTestMetrics.latency(queries.mapNotNull { it.initializationNanos }).p95Nanos),
                    time(TypingTestMetrics.latency(queries.mapNotNull { it.queryNanos }).p95Nanos)))
            }
            appendLine(context.getString(R.string.typing_test_prediction_outcomes, outcomes))
            appendLine(context.getString(R.string.typing_test_prediction_reasons, reasons))
            if (metrics.omittedRecordCount > 0) appendLine(context.getString(
                R.string.typing_test_prediction_limits, metrics.omittedRecordCount))
            append(context.getString(R.string.typing_test_prediction_report_help))
        }
    }
}
