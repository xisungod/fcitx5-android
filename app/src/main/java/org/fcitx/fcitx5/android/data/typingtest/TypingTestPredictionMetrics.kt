/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.typingtest

/** Prediction usage and prescribed-target estimates are independent of raw-pinyin accuracy. */
internal fun evaluateTypingTestPredictions(input: TypingTestTrialInput): TypingTestPredictionMetrics {
    val commits = input.predictionCommits.groupBy { it.commitToken }.mapNotNull { (_, records) ->
        records.distinct().singleOrNull()
    }.associateBy { it.commitToken }
    val successfulCommits = commits.values.filter { it.success }
    val events = input.predictionEvents.groupBy { it.offerToken to it.kind }
        .mapNotNull { (_, records) -> records.distinct().singleOrNull() }
    val published = events.filter { it.kind == TypingTestPredictionEventKind.Published &&
        commits[it.commitToken]?.success == true }.associateBy { it.offerToken }
    fun bound(event: TypingTestPredictionEvent): Boolean = published[event.offerToken]?.let {
        it.commitToken == event.commitToken && it.warmth == event.warmth
    } == true
    val drawn = events.filter { it.kind == TypingTestPredictionEventKind.Drawn && bound(it) &&
        it.visibleIndices.isNotEmpty() && it.visibleIndices.all { index -> index in 0..7 } }
        .associateBy { it.offerToken }
    val selected = events.filter { it.kind == TypingTestPredictionEventKind.Selected && bound(it) &&
        it.selectedIndex in 0..7 }.associateBy { it.offerToken }
    val adopted = events.filter { it.kind == TypingTestPredictionEventKind.Resolved && bound(it) &&
        it.success == true && selected[it.offerToken]?.selectedIndex == it.selectedIndex }
    val withDraw = adopted.filter { it.selectedIndex in (drawn[it.offerToken]?.visibleIndices ?: emptyList()) }
    val targetEvaluable = adopted.filter { it.targetMatched != null }
    val estimable = adopted.filter { it.targetMatched == true &&
        it.estimatedPinyinLetterKeys?.let { keys -> keys > 0 && keys <= 64 } == true }
    val reasons = linkedMapOf<String, Int>()
    fun reason(name: String) { reasons[name] = (reasons[name] ?: 0) + 1 }
    adopted.forEach {
        if (it.targetMatched == null || it.estimatedPinyinLetterKeys == null)
            reason(it.unscoredReason ?: "missing_target_alignment")
    }
    if (adopted.size > withDraw.size) reasons["adoption_without_draw_evidence"] = adopted.size - withDraw.size
    val samples = drawn.values.mapNotNull { event ->
        event.commitToDrawNanos?.let { TypingTestPredictionLatencySample(event.warmth, it) }
            ?: run { reason("missing_draw_timestamp"); null }
    }
    val queries = input.predictionQueries.groupBy { it.commitToken }
        .mapNotNull { (_, records) -> records.distinct().singleOrNull() }
        .filter { commits[it.commitToken]?.success == true }
    return TypingTestPredictionMetrics(
        successfulCommitCount = successfulCommits.size,
        failedCommitCount = commits.values.count { !it.success },
        publishedCount = published.size, drawnCount = drawn.size,
        selectedCount = selected.size, adoptedCount = adopted.size,
        commitDrawCoverage = TypingTestFraction(drawn.values.map { it.commitToken }.distinct().size,
            successfulCommits.size),
        adoptionRate = TypingTestFraction(withDraw.size, drawn.size),
        adoptionDrawCoverage = TypingTestFraction(withDraw.size, adopted.size),
        targetHitRate = TypingTestFraction(targetEvaluable.count { it.targetMatched == true }, targetEvaluable.size),
        targetScoringCoverage = TypingTestFraction(targetEvaluable.size, adopted.size),
        savingsCoverage = TypingTestFraction(estimable.size, adopted.size),
        estimatedPinyinLetterKeys = estimable.sumOf { it.estimatedPinyinLetterKeys!! },
        unscoredReasons = reasons,
        queryOutcomes = queries.groupingBy { it.outcome }.eachCount(),
        drawLatency = predictionLatency(samples.map { it.elapsedNanos }),
        drawLatencyByWarmth = TypingTestPredictionWarmth.entries.associateWith { warmth ->
            predictionLatency(samples.filter { it.warmth == warmth }.map { it.elapsedNanos }) },
        drawLatencySamples = samples, queryRecords = queries,
        omittedRecordCount = input.predictionOmittedRecordCount.coerceAtLeast(0)
    )
}

private fun predictionLatency(samples: List<Long>, limit: Int = TypingTestMetrics.MAX_TIMING_SAMPLES) =
    TypingTestMetrics.latency(samples, limit).copy(
        measurement = "actual_commit_success_to_first_visible_dispatch_draw_ns")

internal fun summarizeTypingTestPredictions(metrics: List<TypingTestPredictionMetrics>): TypingTestPredictionMetrics {
    fun fraction(selector: (TypingTestPredictionMetrics) -> TypingTestFraction) = TypingTestFraction(
        metrics.sumOf { selector(it).numerator }, metrics.sumOf { selector(it).denominator })
    fun counts(selector: (TypingTestPredictionMetrics) -> Map<String, Int>): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        metrics.forEach { item -> selector(item).forEach { (key, value) ->
            result[key] = (result[key] ?: 0) + value
        } }
        return result
    }
    val samples = metrics.flatMap { it.drawLatencySamples }
    return TypingTestPredictionMetrics(
        successfulCommitCount = metrics.sumOf { it.successfulCommitCount },
        failedCommitCount = metrics.sumOf { it.failedCommitCount },
        publishedCount = metrics.sumOf { it.publishedCount },
        drawnCount = metrics.sumOf { it.drawnCount },
        selectedCount = metrics.sumOf { it.selectedCount },
        adoptedCount = metrics.sumOf { it.adoptedCount },
        commitDrawCoverage = fraction { it.commitDrawCoverage },
        adoptionRate = fraction { it.adoptionRate },
        adoptionDrawCoverage = fraction { it.adoptionDrawCoverage },
        targetHitRate = fraction { it.targetHitRate },
        targetScoringCoverage = fraction { it.targetScoringCoverage },
        savingsCoverage = fraction { it.savingsCoverage },
        estimatedPinyinLetterKeys = metrics.sumOf { it.estimatedPinyinLetterKeys },
        unscoredReasons = counts { it.unscoredReasons }, queryOutcomes = counts { it.queryOutcomes },
        drawLatency = predictionLatency(samples.map { it.elapsedNanos }, TypingTestMetrics.MAX_SESSION_TIMING_SAMPLES),
        drawLatencyByWarmth = TypingTestPredictionWarmth.entries.associateWith { warmth ->
            predictionLatency(samples.filter { it.warmth == warmth }.map { it.elapsedNanos },
                TypingTestMetrics.MAX_SESSION_TIMING_SAMPLES) },
        drawLatencySamples = samples.take(TypingTestMetrics.MAX_SESSION_TIMING_SAMPLES),
        queryRecords = metrics.flatMap { it.queryRecords }.take(TypingTestMetrics.MAX_SESSION_TIMING_SAMPLES),
        omittedRecordCount = metrics.sumOf { it.omittedRecordCount }
    )
}
