/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.typingtest

import org.junit.Assert.*
import org.junit.Test

class TypingTestAlternativeMetricsTest {
    private val prompt = TypingTestPrompts.byId(3)!!
    private val wrong = "jibgchsnghui"

    private fun input(events: List<TypingTestAlternativeEvent> = emptyList()) = TypingTestTrialInput(
        prompt = prompt,
        inputKind = TypingTestInputKind.FULL_PINYIN,
        firstAttemptPinyin = wrong,
        firstAttemptComplete = true,
        finalInputPinyin = wrong,
        observedAttemptPinyin = wrong,
        finalCandidateSnapshot = TypingTestCandidateSnapshot(wrong, listOf("井场灰")),
        candidateInputPinyin = wrong,
        letterKeyCount = wrong.length,
        alternativeEvents = events
    )

    private fun event(
        kind: TypingTestAlternativeEventKind,
        token: Long = 1,
        text: String? = prompt.text,
        success: Boolean? = null
    ) = TypingTestAlternativeEvent(token, kind, wrong, prompt.pinyin, text,
        success = success, fullPromptComposition = true)

    @Test fun `lifecycle event counts are not a correctness or display claim`() {
        val generated = event(TypingTestAlternativeEventKind.Generated)
        val published = event(TypingTestAlternativeEventKind.Published)
        val result = TypingTestMetrics.evaluate(input(listOf(generated, generated, published, published)))
        assertEquals(1, result.alternativeMetrics.generatedCount)
        assertEquals(1, result.alternativeMetrics.publishedCount)
        assertEquals(0, result.alternativeMetrics.displayedCount)
        assertEquals(0, result.alternativeMetrics.selectedCount)
        assertEquals(TypingTestFraction(1, 1), result.alternativeMetrics.publishedHitRate)
        assertNull(result.alternativeMetrics.targetSelectionRate.value)
        assertNull(result.alternativeMetrics.selectionSuccessRate.value)
        // The original Rime candidate snapshot keeps its miss even if the separate offer is right.
        assertEquals(TypingTestFraction(0, 1), result.top1HitRate)
        assertEquals(TypingTestFraction(0, 1), result.top3HitRate)
    }

    @Test fun `all lifecycle callbacks are deduplicated by trial scoped token and kind`() {
        val events = TypingTestAlternativeEventKind.entries.map {
            event(it, success = if (it == TypingTestAlternativeEventKind.Resolved) true else null)
        }
        val metrics = TypingTestMetrics.evaluate(input(events + events)).alternativeMetrics
        assertEquals(listOf(1, 1, 1, 1, 1, 1, 1), listOf(metrics.generatedCount,
            metrics.rejectedCount, metrics.publishedCount, metrics.displayedCount,
            metrics.selectedCount, metrics.resolvedCount, metrics.resolvedSuccessCount))
        assertEquals(mapOf("unspecified" to 1), metrics.rejectionReasons)
        assertEquals(TypingTestFraction(1, 1), metrics.selectionSuccessRate)
    }

    @Test fun `unknown partial or mixed intentions never become alternative truth`() {
        val base = event(TypingTestAlternativeEventKind.Published)
        val excluded = listOf(
            base.copy(fullPromptComposition = false),
            base.copy(originalPinyin = "jib"),
            base.copy(originalPinyin = "JINGCHANGHUI"),
            base.copy(originalPinyin = null),
            base.copy(candidateText = null),
            base.copy(candidateText = "")
        )
        for (excludedEvent in excluded) {
            val result = TypingTestMetrics.evaluate(input(listOf(excludedEvent)))
            assertEquals(1, result.alternativeMetrics.publishedCount)
            assertNull(result.alternativeMetrics.publishedHitRate.value)
        }
        for (kind in TypingTestInputKind.entries.filter { it != TypingTestInputKind.FULL_PINYIN }) {
            val result = TypingTestMetrics.evaluate(input(listOf(base,
                base.copy(kind = TypingTestAlternativeEventKind.Selected))).copy(inputKind = kind))
            assertNull(result.alternativeMetrics.publishedHitRate.value)
            assertNull(result.alternativeMetrics.targetSelectionRate.value)
        }
        val unsupported = TypingTestMetrics.evaluate(input(listOf(base)).copy(
            prompt = prompt.copy(pinyin = "jing'chang'hui")))
        assertNull(unsupported.alternativeMetrics.publishedHitRate.value)
    }

    @Test fun `alternative correctness is exact whole target text not a prefix or synonym`() {
        val events = listOf("经常", "经常回", prompt.text).mapIndexed { index, text ->
            event(TypingTestAlternativeEventKind.Published, index.toLong(), text)
        }
        val metrics = TypingTestMetrics.evaluate(input(events)).alternativeMetrics
        assertEquals(TypingTestFraction(1, 3), metrics.publishedHitRate)
    }

    @Test fun `selected target correctness and successful resolution are different denominators`() {
        val events = listOf(
            event(TypingTestAlternativeEventKind.Selected, 1),
            event(TypingTestAlternativeEventKind.Selected, 2, "经常回"),
            event(TypingTestAlternativeEventKind.Selected, 3).copy(fullPromptComposition = false),
            event(TypingTestAlternativeEventKind.Resolved, 1, success = false),
            event(TypingTestAlternativeEventKind.Resolved, 2, "经常回", success = true),
            event(TypingTestAlternativeEventKind.Resolved, 3, success = true)
                .copy(fullPromptComposition = false),
            event(TypingTestAlternativeEventKind.Resolved, 4, success = null)
        )
        val metrics = TypingTestMetrics.evaluate(input(events)).alternativeMetrics
        assertEquals(3, metrics.selectedCount)
        assertEquals(4, metrics.resolvedCount)
        assertEquals(2, metrics.resolvedSuccessCount)
        assertEquals(TypingTestFraction(1, 2), metrics.targetSelectionRate)
        assertEquals(TypingTestFraction(2, 3), metrics.selectionSuccessRate)
    }

    @Test fun `later commits do not discard frozen precommit full prompt alternative evidence`() {
        val metrics = TypingTestMetrics.evaluate(input(listOf(
            event(TypingTestAlternativeEventKind.Published),
            event(TypingTestAlternativeEventKind.Selected))).copy(priorCommitCount = 1,
            committedText = prompt.text)).alternativeMetrics
        assertEquals(TypingTestFraction(1, 1), metrics.publishedHitRate)
        assertEquals(TypingTestFraction(1, 1), metrics.targetSelectionRate)
    }

    @Test fun `conflicting duplicate callbacks cannot inflate hit or resolution rates`() {
        val published = event(TypingTestAlternativeEventKind.Published)
        val resolved = event(TypingTestAlternativeEventKind.Resolved, success = true)
        val rejected = event(TypingTestAlternativeEventKind.Rejected).copy(reason = "stale")
        val metrics = TypingTestMetrics.evaluate(input(listOf(published,
            published.copy(candidateText = "经常回"), resolved, resolved.copy(success = false),
            rejected, rejected.copy(reason = "timeout")))).alternativeMetrics
        assertEquals(1, metrics.publishedCount)
        assertEquals(1, metrics.resolvedCount)
        assertEquals(0, metrics.resolvedSuccessCount)
        assertNull(metrics.publishedHitRate.value)
        assertNull(metrics.selectionSuccessRate.value)
        assertEquals(mapOf("conflicting_event" to 1), metrics.rejectionReasons)
    }

    @Test fun `rejection reason counts do not double count replayed events`() {
        val stale = event(TypingTestAlternativeEventKind.Rejected, 1).copy(reason = "stale")
        val metrics = TypingTestMetrics.evaluate(input(listOf(stale, stale,
            stale.copy(offerToken = 2), stale.copy(offerToken = 3, reason = "timeout"))))
            .alternativeMetrics
        assertEquals(3, metrics.rejectedCount)
        assertEquals(mapOf("stale" to 2, "timeout" to 1), metrics.rejectionReasons)
    }

    @Test fun `explanatory engine ranks and searched path counts do not affect correctness`() {
        val published = event(TypingTestAlternativeEventKind.Published)
        val baseline = TypingTestMetrics.evaluate(input(listOf(published))).alternativeMetrics
        val annotated = TypingTestMetrics.evaluate(input(listOf(published,
            published.copy(originalRank = 2, searchPathCount = 451)))).alternativeMetrics
        assertEquals(baseline, annotated)
    }

    @Test fun `summary sums actual eligible events and permits same token in different trials`() {
        val hit = TypingTestMetrics.evaluate(input(listOf(
            event(TypingTestAlternativeEventKind.Published),
            event(TypingTestAlternativeEventKind.Rejected).copy(reason = "stale"))))
        val second = TypingTestMetrics.evaluate(input((1L..3L).map {
            event(TypingTestAlternativeEventKind.Published, it,
                if (it == 1L) prompt.text else "经常回")
        } + event(TypingTestAlternativeEventKind.Rejected).copy(reason = "stale")))
        val summary = TypingTestMetrics.summarize(listOf(hit, second))
        assertEquals(4, summary.alternativeMetrics.publishedCount)
        assertEquals(TypingTestFraction(2, 4), summary.alternativeMetrics.publishedHitRate)
        assertEquals(mapOf("stale" to 2), summary.alternativeMetrics.rejectionReasons)
        assertEquals(TypingTestFraction(0, 2), summary.top1HitRate)
    }

    @Test fun `stage latency samples preserve units exclusions and separate measurement scopes`() {
        val timings = TypingTestStageTimings(
            sendKeyNanos = listOf(-1L, 10L, 20L),
            touchSearchNanos = listOf(3L),
            alternativeQueryNanos = emptyList()
        )
        val result = TypingTestMetrics.evaluate(input().copy(stageTimings = timings,
            processingNanos = listOf(100L)))
        assertEquals(2, result.stageLatencies.sendKey.sampleCount)
        assertEquals(1, result.stageLatencies.sendKey.invalidSampleCount)
        assertEquals(20L, result.stageLatencies.sendKey.p95Nanos)
        assertEquals("send_key_ns", result.stageLatencies.sendKey.measurement)
        assertEquals("touch_search_ns", result.stageLatencies.touchSearch.measurement)
        assertEquals(3L, result.stageLatencies.touchSearch.p95Nanos)
        assertEquals("alternative_query_ns", result.stageLatencies.alternativeQuery.measurement)
        assertNull(result.stageLatencies.alternativeQuery.p95Nanos)
        assertEquals(100L, result.latency.p95Nanos)
        val capped = TypingTestMetrics.stageLatencies(TypingTestStageTimings(
            sendKeyNanos = List(300) { 1L }))
        assertEquals(256, capped.sendKey.sampleCount)
        assertEquals(44, capped.sendKey.omittedSampleCount)
    }

    @Test fun `pooled stage percentiles use real samples rather than average of trial percentiles`() {
        val fast = List(19) { 1L }
        val slow = listOf(100L)
        val trials = listOf(
            TypingTestMetrics.evaluate(input().copy(stageTimings = TypingTestStageTimings(fast))),
            TypingTestMetrics.evaluate(input().copy(stageTimings = TypingTestStageTimings(slow)))
        )
        val summary = TypingTestMetrics.summarize(trials,
            stageTimings = TypingTestStageTimings(fast + slow))
        assertEquals(20, summary.stageLatencies.sendKey.sampleCount)
        assertEquals(1L, summary.stageLatencies.sendKey.p95Nanos)
        assertEquals(100L, summary.stageLatencies.sendKey.maximumNanos)
        val noSamples = TypingTestMetrics.summarize(trials)
        assertNull(noSamples.stageLatencies.sendKey.p95Nanos)
        val pooled300 = TypingTestMetrics.summarize(trials,
            stageTimings = TypingTestStageTimings(List(300) { 1L }))
        assertEquals(300, pooled300.stageLatencies.sendKey.sampleCount)
        assertEquals(0, pooled300.stageLatencies.sendKey.omittedSampleCount)
    }

    @Test fun `published offer turnaround has its own real sample denominator and overlaps stages`() {
        val timings = TypingTestStageTimings(sendKeyNanos = listOf(10L),
            touchSearchNanos = listOf(20L), alternativeQueryNanos = listOf(30L),
            offerReadyNanos = listOf(100L, 200L, -1L))
        val result = TypingTestMetrics.evaluate(input().copy(stageTimings = timings))
        assertEquals("enqueue_to_offer_publish_ns", result.stageLatencies.offerReady.measurement)
        assertEquals(2, result.stageLatencies.offerReady.sampleCount)
        assertEquals(1, result.stageLatencies.offerReady.invalidSampleCount)
        assertEquals(200L, result.stageLatencies.offerReady.p95Nanos)
        assertEquals(10L, result.stageLatencies.sendKey.p95Nanos)
        val pooled = TypingTestMetrics.summarize(listOf(result),
            stageTimings = timings.copy(offerReadyNanos = List(19) { 50L } + 200L))
        assertEquals(20, pooled.stageLatencies.offerReady.sampleCount)
        assertEquals(50L, pooled.stageLatencies.offerReady.p95Nanos)
        assertNull(TypingTestMetrics.stageLatencies(TypingTestStageTimings()).offerReady.p95Nanos)
    }
}
