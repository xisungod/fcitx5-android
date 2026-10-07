/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.typingtest

import org.junit.Assert.*
import org.junit.Test

/** Public synthetic targets only; these tests never load user reports or decoder dictionaries. */
class TypingTestPredictionMetricsTest {
    private val prompt = TypingTestPrompts.all.first()
    private fun input(recorder: TypingTestPredictionRecorder, target: TypingTestPrompt = prompt) =
        TypingTestTrialInput(target, TypingTestInputKind.SHORTHAND_OR_MIXED,
            predictionCommits = recorder.commits, predictionEvents = recorder.events,
            predictionQueries = recorder.queries,
            predictionOmittedRecordCount = recorder.omittedRecordCount)
    private fun metrics(recorder: TypingTestPredictionRecorder, target: TypingTestPrompt = prompt) =
        TypingTestMetrics.evaluate(input(recorder, target)).predictionMetrics
    private fun offer(recorder: TypingTestPredictionRecorder, observation: Any, token: Long = 1,
                      prefix: String = "你", mode: TypingTestPredictionWarmth = TypingTestPredictionWarmth.Warm,
                      committedAt: Long = 100, drawnAt: Long? = 140) {
        recorder.commit(observation, token, true, prefix, committedAt)
        recorder.publish(observation, token, token + 100, mode)
        if (drawnAt != null) recorder.draw(observation, token + 100, listOf(0, 1), drawnAt)
    }
    private fun choose(recorder: TypingTestPredictionRecorder, observation: Any, text: String = "好啊",
                       token: Long = 1, success: Boolean = true) {
        recorder.select(observation, token + 100, 0)
        recorder.resolve(observation, token + 100, 0, text, success)
    }

    @Test fun allPublicPromptMappingsAreExplicitAndExact() {
        for (target in TypingTestPrompts.all) {
            assertEquals(target.text.codePointCount(0, target.text.length), target.pinyinSyllables.size)
            assertEquals(target.pinyin, target.pinyinSyllables.joinToString(""))
            val recorder = TypingTestPredictionRecorder(target)
            val firstBoundary = target.text.offsetByCodePoints(0, 1)
            val evaluation = recorder.evaluateAppend(target, target.text.substring(0, firstBoundary),
                target.text.substring(firstBoundary))
            assertEquals(true, evaluation.matched)
            assertEquals(target.pinyinSyllables.drop(1).sumOf { it.length }, evaluation.letters)
        }
    }

    @Test fun emptyOrOrdinaryWholePhrasePracticeDoesNotInventZeroAccuracyOrSavings() {
        val recorder = TypingTestPredictionRecorder(prompt)
        recorder.commit(Any(), 1, true, prompt.text, 100)
        val result = metrics(recorder)
        assertEquals(1, result.successfulCommitCount)
        assertEquals(TypingTestFraction(0, 1), result.commitDrawCoverage)
        assertNull(result.adoptionRate.value)
        assertNull(result.targetHitRate.value)
        assertNull(result.savingsCoverage.value)
        assertNull(result.drawLatency.p95Nanos)
    }

    @Test fun publishedIsNotDrawnAndCannotSupplyDrawLatency() {
        val recorder = TypingTestPredictionRecorder(prompt)
        offer(recorder, Any(), drawnAt = null)
        val result = metrics(recorder)
        assertEquals(1, result.publishedCount)
        assertEquals(0, result.drawnCount)
        assertEquals(0, result.drawLatency.sampleCount)
        assertNull(result.adoptionRate.value)
    }

    @Test fun actualDrawAndActualSuccessfulAdoptionHaveSeparateKnownTargetEvidence() {
        val recorder = TypingTestPredictionRecorder(prompt)
        val observation = Any()
        offer(recorder, observation)
        choose(recorder, observation)
        val result = metrics(recorder)
        assertEquals(1, result.drawnCount)
        assertEquals(1, result.adoptedCount)
        assertEquals(TypingTestFraction(1, 1), result.adoptionRate)
        assertEquals(TypingTestFraction(1, 1), result.targetHitRate)
        assertEquals(TypingTestFraction(1, 1), result.savingsCoverage)
        assertEquals(4, result.estimatedPinyinLetterKeys) // hao + a, not two Hanzi taps.
        assertEquals(40L, result.drawLatency.p95Nanos)
        assertEquals(1, result.drawLatencyByWarmth[TypingTestPredictionWarmth.Warm]?.sampleCount)
        // Prediction does not fabricate original Rime/raw full-phrase accuracy or calibration.
        val evaluated = TypingTestMetrics.evaluate(input(recorder))
        assertNull(evaluated.top1HitRate.value)
        assertNull(evaluated.rawEditRate.value)
        assertTrue(evaluated.calibrationSamples.isEmpty())
    }

    @Test fun failedCommitNeverCreatesSuccessfulQueryOrPublishedAnchor() {
        val recorder = TypingTestPredictionRecorder(prompt)
        val observation = Any()
        recorder.commit(observation, 1, false, "你", 100)
        recorder.publish(observation, 1, 101, TypingTestPredictionWarmth.Warm)
        recorder.query(observation, 1, "Published", TypingTestPredictionWarmth.Warm, true, 1, 0, 1)
        val result = metrics(recorder)
        assertEquals(1, result.failedCommitCount)
        assertEquals(0, result.publishedCount)
        assertTrue(result.queryOutcomes.isEmpty())
        assertNull(result.commitDrawCoverage.value)
    }

    @Test fun checkedCommitFailureIsSelectionButNotAdoptionOrTargetHit() {
        val recorder = TypingTestPredictionRecorder(prompt)
        val observation = Any()
        offer(recorder, observation)
        choose(recorder, observation, success = false)
        val result = metrics(recorder)
        assertEquals(1, result.selectedCount)
        assertEquals(0, result.adoptedCount)
        assertEquals(TypingTestFraction(0, 1), result.adoptionRate)
        assertNull(result.targetHitRate.value)
        assertNull(result.savingsCoverage.value)
    }

    @Test fun resolveRequiresMatchingExplicitSelection() {
        val recorder = TypingTestPredictionRecorder(prompt)
        val observation = Any()
        offer(recorder, observation)
        assertNull(recorder.resolve(observation, 101, 0, "好啊", true))
        recorder.select(observation, 101, 1)
        assertNull(recorder.resolve(observation, 101, 0, "好啊", true))
        assertEquals(0, metrics(recorder).adoptedCount)
    }

    @Test fun callbacksAreBoundToOriginalObservationIdentityNotEquivalentLaterReceipt() {
        val recorder = TypingTestPredictionRecorder(prompt)
        val original = Any()
        val later = Any()
        recorder.commit(original, 1, true, "你", 100)
        recorder.publish(later, 1, 101, TypingTestPredictionWarmth.Warm)
        assertEquals(0, metrics(recorder).publishedCount)
        recorder.publish(original, 1, 101, TypingTestPredictionWarmth.Warm)
        recorder.draw(later, 101, listOf(0), 140)
        recorder.select(later, 101, 0)
        assertNull(recorder.resolve(later, 101, 0, "好啊", true))
        assertEquals(0, metrics(recorder).drawnCount)
        assertEquals(0, metrics(recorder).adoptedCount)
    }

    @Test fun afterWholeTargetIsCommittedThereIsNoKnownNextIntention() {
        val recorder = TypingTestPredictionRecorder(prompt)
        val observation = Any()
        offer(recorder, observation, prefix = prompt.text)
        choose(recorder, observation, "朋友")
        val result = metrics(recorder)
        assertEquals(1, result.adoptedCount)
        assertEquals(0, result.targetHitRate.denominator)
        assertEquals(TypingTestFraction(0, 1), result.targetScoringCoverage)
        assertEquals(1, result.unscoredReasons["target_already_complete"])
    }

    @Test fun missingSyllableMappingDoesNotReplaceLettersWithChineseCharacterCount() {
        val target = prompt.copy(pinyinSyllables = emptyList())
        val recorder = TypingTestPredictionRecorder(target)
        val observation = Any()
        offer(recorder, observation)
        choose(recorder, observation)
        val result = metrics(recorder, target)
        assertEquals(TypingTestFraction(1, 1), result.targetHitRate)
        assertEquals(TypingTestFraction(0, 1), result.savingsCoverage)
        assertEquals(0, result.estimatedPinyinLetterKeys)
        assertEquals(1, result.unscoredReasons["missing_or_invalid_pinyin_alignment"])
    }

    @Test fun wrongTargetIsAMissButNotAZeroSavingsMeasurement() {
        val recorder = TypingTestPredictionRecorder(prompt)
        val observation = Any()
        offer(recorder, observation)
        choose(recorder, observation, "们")
        val result = metrics(recorder)
        assertEquals(TypingTestFraction(0, 1), result.targetHitRate)
        assertEquals(TypingTestFraction(0, 1), result.savingsCoverage)
        assertEquals(1, result.unscoredReasons["target_mismatch"])
    }

    @Test fun manualEditInvalidatesTargetEstimateButKeepsActualUsageAndLatency() {
        val recorder = TypingTestPredictionRecorder(prompt)
        val observation = Any()
        offer(recorder, observation)
        recorder.invalidateScope()
        choose(recorder, observation)
        val result = metrics(recorder)
        assertEquals(1, result.adoptedCount)
        assertEquals(1, result.drawLatency.sampleCount)
        assertNull(result.targetHitRate.value)
        assertEquals(1, result.unscoredReasons["append_scope_not_reliable"])
    }

    @Test fun aNonTargetCommittedPrefixDoesNotInventTargetAlignment() {
        val recorder = TypingTestPredictionRecorder(prompt)
        val observation = Any()
        offer(recorder, observation, prefix = "他")
        choose(recorder, observation)
        assertEquals(1, metrics(recorder).unscoredReasons["committed_prefix_not_target"])
        assertNull(metrics(recorder).targetHitRate.value)
    }

    @Test fun unicodeSupplementaryTargetsUseCodePointSyllableAlignment() {
        val target = TypingTestPrompt(99, "𠀀好", "yihao", listOf("yi", "hao"))
        val recorder = TypingTestPredictionRecorder(target)
        val evaluation = recorder.evaluateAppend(target, "𠀀", "好")
        assertEquals(true, evaluation.matched)
        assertEquals(3, evaluation.letters)
        assertNull(recorder.evaluateAppend(target, "\uD840", "\uDC00好").matched)
    }

    @Test fun duplicateLifecycleCallbacksDoNotIncreaseSamplesOrSuccessfulAppendReceipts() {
        val recorder = TypingTestPredictionRecorder(prompt)
        val observation = Any()
        repeat(2) { offer(recorder, observation) }
        choose(recorder, observation)
        choose(recorder, observation)
        assertNull(recorder.resolve(observation, 101, 0, "好啊", true))
        val result = metrics(recorder)
        assertEquals(1, result.successfulCommitCount)
        assertEquals(1, result.publishedCount)
        assertEquals(1, result.drawLatency.sampleCount)
        assertEquals(1, result.adoptedCount)
    }

    @Test fun directPredictionCanChainOnlyAfterActualNewCommitAndUsesFrozenAnchorPrefix() {
        val recorder = TypingTestPredictionRecorder(prompt)
        val first = Any()
        val second = Any()
        offer(recorder, first)
        choose(recorder, first, "好")
        recorder.commit(second, 2, true, "好", 200)
        recorder.publish(second, 2, 102, TypingTestPredictionWarmth.Warm)
        recorder.draw(second, 102, listOf(0), 230)
        choose(recorder, second, "啊", token = 2)
        val result = metrics(recorder)
        assertEquals(2, result.adoptedCount)
        assertEquals(4, result.estimatedPinyinLetterKeys)
        assertEquals(TypingTestFraction(2, 2), result.targetHitRate)
    }

    @Test fun olderOfferCannotEstimateSavingsAfterASeparateCommitAdvancedThePrefix() {
        val recorder = TypingTestPredictionRecorder(prompt)
        val observation = Any()
        offer(recorder, observation)
        recorder.commit(Any(), 2, true, "好", 200)
        choose(recorder, observation)
        assertNull(metrics(recorder).targetHitRate.value)
    }

    @Test fun adoptionWithoutDrawEvidenceDoesNotTurnMissingEvidenceIntoDisplayedAdoption() {
        val recorder = TypingTestPredictionRecorder(prompt)
        val observation = Any()
        offer(recorder, observation, drawnAt = null)
        choose(recorder, observation)
        val result = metrics(recorder)
        assertEquals(1, result.adoptedCount)
        assertNull(result.adoptionRate.value)
        assertEquals(TypingTestFraction(0, 1), result.adoptionDrawCoverage)
        assertEquals(1, result.unscoredReasons["adoption_without_draw_evidence"])
    }

    @Test fun negativeOrOverflowDrawDeltaIsInvalidAndNotAMeasuredZero() {
        for ((start, end) in listOf(100L to 99L, Long.MIN_VALUE to Long.MAX_VALUE)) {
            val recorder = TypingTestPredictionRecorder(prompt)
            offer(recorder, Any(), committedAt = start, drawnAt = end)
            val result = metrics(recorder)
            assertEquals(1, result.drawnCount)
            assertEquals(0, result.drawLatency.sampleCount)
            assertEquals(1, result.drawLatency.invalidSampleCount)
            assertNull(result.drawLatency.p50Nanos)
        }
    }

    @Test fun summaryPoolsRealSamplesAndSeparatesColdWarmCacheUnknown() {
        val durations = listOf(1L, 2L, 3L, 1_000L)
        val trials = durations.mapIndexed { i, duration ->
            val recorder = TypingTestPredictionRecorder(prompt)
            offer(recorder, Any(), mode = TypingTestPredictionWarmth.entries[i], drawnAt = 100 + duration)
            TypingTestMetrics.evaluate(input(recorder))
        }
        val summary = TypingTestMetrics.summarize(trials).predictionMetrics
        assertEquals(2L, summary.drawLatency.p50Nanos)
        assertEquals(1_000L, summary.drawLatency.p95Nanos)
        TypingTestPredictionWarmth.entries.forEach { mode ->
            assertEquals(1, summary.drawLatencyByWarmth[mode]?.sampleCount)
        }
        assertEquals(TypingTestFraction(4, 4), summary.commitDrawCoverage)
    }

    @Test fun queryOutcomesAreDiagnosticsNotAccuracyAndContainNoContextOrCandidateStrings() {
        val trials = listOf("Unavailable", "Timeout", "Stale", "Failed", "NoCandidates", "Published")
            .mapIndexed { index, outcome ->
                val recorder = TypingTestPredictionRecorder(prompt)
                val observation = Any()
                recorder.commit(observation, index.toLong(), true, "你", 100)
                recorder.query(observation, index.toLong(), outcome, TypingTestPredictionWarmth.Cold,
                    outcome != "Unavailable", 100, 90, 10)
                recorder.query(observation, index.toLong(), outcome, TypingTestPredictionWarmth.Cold,
                    false, 100, 90, 10) // duplicate callback ignored
                TypingTestMetrics.evaluate(input(recorder))
            }
        val result = TypingTestMetrics.summarize(trials).predictionMetrics
        assertEquals(6, result.queryRecords.size)
        assertEquals(6, result.queryOutcomes.size)
        assertNull(result.targetHitRate.value)
        assertTrue(result.queryRecords.none { "你" in it.toString() })
    }

    @Test fun recorderCapsAllIndependentPredictionRecordsAndReportsOmissions() {
        val recorder = TypingTestPredictionRecorder(prompt)
        repeat(100) { index ->
            val observation = Any()
            offer(recorder, observation, token = index.toLong(), prefix = "你")
            choose(recorder, observation, token = index.toLong())
            recorder.query(observation, index.toLong(), "Published", TypingTestPredictionWarmth.Warm,
                true, 1, 0, 1)
        }
        assertEquals(64, recorder.commits.size)
        assertEquals(256, recorder.events.size)
        assertEquals(64, recorder.queries.size)
        assertTrue(recorder.omittedRecordCount > 0)
        assertEquals(recorder.omittedRecordCount, metrics(recorder).omittedRecordCount)
    }

    @Test fun laterHorizontalScrollDrawEvidenceEnablesAdoptionWithoutDuplicatingFirstLatency() {
        val recorder = TypingTestPredictionRecorder(prompt)
        val observation = Any()
        recorder.commit(observation, 1L, true, "你", 100L)
        recorder.publish(observation, 1L, 101L, TypingTestPredictionWarmth.Warm)
        recorder.draw(observation, 101L, listOf(0), 140L)
        recorder.draw(observation, 101L, listOf(2), 180L)
        recorder.draw(observation, 101L, listOf(2, 0), 200L)
        recorder.select(observation, 101L, 2)
        recorder.resolve(observation, 101L, 2, "好啊", true)
        val result = metrics(recorder)
        assertEquals(1, result.drawnCount)
        assertEquals(1, result.adoptedCount)
        assertEquals(TypingTestFraction(1, 1), result.adoptionRate)
        assertEquals(TypingTestFraction(1, 1), result.adoptionDrawCoverage)
        assertEquals(1, result.drawLatency.sampleCount)
        assertEquals(40L, result.drawLatency.p50Nanos)
        assertEquals(40L, result.drawLatency.p95Nanos)
        assertEquals(listOf(0, 2), recorder.events.single {
            it.kind == TypingTestPredictionEventKind.Drawn }.visibleIndices)
    }

    @Test fun unavailableCompletionIndexPreservesWorkingNativeFallbackAndActualEvidence() {
        val recorder = TypingTestPredictionRecorder(prompt)
        val observation = Any()
        offer(recorder, observation)
        choose(recorder, observation)
        recorder.query(observation, 1L, "Published", TypingTestPredictionWarmth.Cold,
            true, 100L, 70L, 20L, completionAvailable = false,
            completionInitializationNanos = 10L, completionFailureReason = "CompletionIndexMissing")
        val result = metrics(recorder)
        assertEquals(1, result.queryOutcomes["Published"])
        assertNull(result.queryOutcomes["Unavailable"])
        val query = result.queryRecords.single()
        assertEquals(true, query.available)
        assertEquals(false, query.completionAvailable)
        assertEquals(10L, query.completionInitializationNanos)
        assertEquals("CompletionIndexMissing", query.completionFailureReason)
        // The real base suggestion can still be correct; the query flag never invents correctness.
        assertEquals(TypingTestFraction(1, 1), result.targetHitRate)
        assertEquals(1, result.adoptedCount)
    }

    @Test fun unobservedCompletionFieldsRemainUnknownAndQueryOnlyNeverCreatesPerfectAccuracy() {
        val recorder = TypingTestPredictionRecorder(prompt)
        val observation = Any()
        recorder.commit(observation, 1L, true, "你", 100L)
        recorder.query(observation, 1L, "Published", TypingTestPredictionWarmth.Warm,
            true, 10L, 0L, 10L) // Existing callers retain the old signature.
        val result = metrics(recorder)
        val query = result.queryRecords.single()
        assertNull(query.completionAvailable)
        assertNull(query.completionInitializationNanos)
        assertNull(query.completionFailureReason)
        assertNull(result.targetHitRate.value)
        assertNull(result.savingsCoverage.value)
        assertNull(result.drawLatency.p95Nanos)
    }

    @Test fun completionFailureRecordsOnlyFixedCodesRatherThanArbitraryExceptionOrContextText() {
        val recorder = TypingTestPredictionRecorder(prompt)
        val observation = Any()
        recorder.commit(observation, 1L, true, "你", 100L)
        recorder.query(observation, 1L, "Published", TypingTestPredictionWarmth.Warm,
            true, 10L, 0L, 10L, false, 1L, "private-context-with-candidate-text")
        val query = metrics(recorder).queryRecords.single()
        assertEquals("CompletionIndexUnavailable", query.completionFailureReason)
        assertFalse(query.toString().contains("private-context"))
    }

    @Test fun conflictingExportedDuplicateEvidenceIsExcludedRatherThanScoredTwice() {
        val recorder = TypingTestPredictionRecorder(prompt)
        val observation = Any()
        offer(recorder, observation)
        choose(recorder, observation)
        val input = input(recorder)
        val resolved = input.predictionEvents.last()
        val evaluated = TypingTestMetrics.evaluate(input.copy(predictionEvents =
            input.predictionEvents + resolved.copy(targetMatched = false))).predictionMetrics
        assertEquals(0, evaluated.adoptedCount)
        assertNull(evaluated.targetHitRate.value)
    }
}
