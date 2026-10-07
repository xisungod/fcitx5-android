/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.typingtest

import org.fcitx.fcitx5.android.input.keyboard.typing.KeyCell
import org.junit.Assert.*
import org.junit.Test

class TypingTestMetricsTest {
    private val prompt = TypingTestPrompts.byId(3)!!
    private val wrong = "jibgchsnghui"
    private val cells = listOf("qwertyuiop" to 0f, "asdfghjkl" to 50f, "zxcvbnm" to 150f)
        .flatMapIndexed { row, (letters, left) -> letters.mapIndexed { i, letter ->
            KeyCell(letter, left + i * 100f, row * 140f, left + (i + 1) * 100f, (row + 1) * 140f)
        } }

    private fun input(
        spelling: String = prompt.pinyin,
        candidates: List<String> = listOf(prompt.text),
        complete: Boolean = true
    ) = TypingTestTrialInput(prompt, TypingTestInputKind.FULL_PINYIN,
        firstAttemptPinyin = spelling, firstAttemptComplete = complete,
        finalInputPinyin = spelling, observedAttemptPinyin = spelling,
        finalCandidateSnapshot = TypingTestCandidateSnapshot(spelling, candidates),
        committedText = prompt.text, letterKeyCount = spelling.length)

    private fun touches(spelling: String) = spelling.map { letter ->
        val cell = cells.first { it.letter == letter }
        val x = when (letter) {
            'b' -> cell.right - 1f
            's' -> cell.left + 1f
            else -> cell.centerX
        }
        TypingTestTouch(letter, x, cell.centerY, 1f, cells, "portrait")
    }

    @Test fun `twenty prescribed prompts include all lowercase qwerty letters`() {
        assertEquals(20, TypingTestPrompts.all.size)
        assertEquals((1..20).toList(), TypingTestPrompts.all.map { it.id })
        assertEquals(('a'..'z').toSet(), TypingTestPrompts.all.flatMap { it.pinyin.toList() }.toSet())
        assertTrue(TypingTestPrompts.all.all { it.pinyin.length <= TypingTestAligner.MAX_LENGTH })
        assertNull(TypingTestPrompts.byId(0))
        assertEquals("nihaoa", TypingTestPrompts.byId(1)!!.pinyin)
        assertEquals("xiaoguniang", TypingTestPrompts.byId(2)!!.pinyin)
    }

    @Test fun `correct complete full pinyin is one scored full phrase`() {
        val result = TypingTestMetrics.evaluate(input())
        assertEquals(TypingTestFraction(0, prompt.pinyin.length), result.rawEditRate)
        assertEquals(TypingTestFraction(0, prompt.pinyin.length), result.adjacentSubstitutionRate)
        assertEquals(TypingTestFraction(1, 1), result.top1HitRate)
        assertEquals(TypingTestFraction(1, 1), result.top3HitRate)
        assertTrue(result.targetCompleted)
    }

    @Test fun `final Chinese repair cannot erase first attempt neighbour errors`() {
        val result = TypingTestMetrics.evaluate(input(wrong, listOf("井场灰", prompt.text)))
        assertEquals(TypingTestFraction(2, prompt.pinyin.length), result.rawEditRate)
        assertEquals(TypingTestFraction(2, prompt.pinyin.length), result.adjacentSubstitutionRate)
        assertEquals(TypingTestFraction(0, 1), result.top1HitRate)
        assertEquals(TypingTestFraction(1, 1), result.top3HitRate)
        assertTrue(result.targetCompleted)
        assertTrue(result.calibrationSamples.isEmpty())
    }

    @Test fun `backspace repair cannot inflate the frozen initial candidate hit rate`() {
        val source = input(wrong, listOf("井场灰", "经常回")).copy(
            finalInputPinyin = prompt.pinyin, candidateInputPinyin = wrong,
            observedAttemptPinyin = wrong, backspaceCount = 2)
        val result = TypingTestMetrics.evaluate(source)
        assertEquals(TypingTestFraction(0, 1), result.top1HitRate)
        assertEquals(TypingTestFraction(0, 1), result.top3HitRate)
        assertTrue(result.targetCompleted)
        assertEquals(TypingTestFraction(2, prompt.pinyin.length), result.rawEditRate)
    }

    @Test fun `early partial first attempt followed by complete repair remains candidate unscored`() {
        val source = input().copy(firstAttemptPinyin = "jib", firstAttemptComplete = false,
            backspaceCount = 1)
        assertNull(TypingTestMetrics.evaluate(source).top1HitRate.value)
        assertNull(TypingTestMetrics.evaluate(source).top3HitRate.value)
    }

    @Test fun `early deleted prefix has no untouched target suffix errors`() {
        val result = TypingTestMetrics.evaluate(input("jib", complete = false).copy(backspaceCount = 1))
        assertNull(result.rawEditRate.value)
        assertNull(result.adjacentSubstitutionRate.value)
        assertNull(result.top1HitRate.value)
        assertTrue("incomplete_first_attempt" in result.unscoredReasons)
        assertEquals(TypingTestFraction(1, 3), result.backspaceRate)
    }

    @Test fun `severely wrong complete attempts stay in raw error denominator`() {
        val result = TypingTestMetrics.evaluate(input("z".repeat(prompt.pinyin.length)))
        assertEquals(TypingTestFraction(prompt.pinyin.length, prompt.pinyin.length), result.rawEditRate)
        assertNull(result.adjacentSubstitutionRate.value)
        assertNull(result.alignment)
        assertTrue("first_attempt_exceeds_alignment_budget" in result.unscoredReasons)
    }

    @Test fun `shorthand mixed external and unknown do not acquire full pinyin truth`() {
        for (kind in TypingTestInputKind.entries.filter { it != TypingTestInputKind.FULL_PINYIN }) {
            val result = TypingTestMetrics.evaluate(input().copy(inputKind = kind,
                firstAttemptTouches = touches(prompt.pinyin)))
            assertNull(result.rawEditRate.value)
            assertNull(result.top1HitRate.value)
            assertTrue(result.calibrationSamples.isEmpty())
            assertTrue("unsupported_input_kind" in result.unscoredReasons)
        }
    }

    @Test fun `stale mismatched and partial candidate snapshots are not denominators`() {
        val base = input()
        val excluded = listOf(
            base.copy(observedAttemptPinyin = wrong),
            base.copy(candidateInputPinyin = wrong),
            base.copy(finalCandidateSnapshot = base.finalCandidateSnapshot!!.copy(rawPinyin = wrong)),
            base.copy(finalCandidateSnapshot = base.finalCandidateSnapshot!!.copy(coherent = false)),
            base.copy(finalCandidateSnapshot = base.finalCandidateSnapshot!!.copy(completePromptComposition = false)),
            base.copy(priorCommitCount = 1),
            base.copy(finalCandidateSnapshot = null)
        )
        for (trial in excluded) assertNull(TypingTestMetrics.evaluate(trial).top1HitRate.value)
    }

    @Test fun `empty coherent candidate list is scored miss not omitted`() {
        val result = TypingTestMetrics.evaluate(input(candidates = emptyList()))
        assertEquals(TypingTestFraction(0, 1), result.top1HitRate)
        assertEquals(TypingTestFraction(0, 1), result.top3HitRate)
    }

    @Test fun `candidate scoring uses exact full phrase and first three displayed ranks`() {
        val prefix = TypingTestMetrics.evaluate(input(candidates = listOf("经常", "会", "经常回")))
        assertEquals(TypingTestFraction(0, 1), prefix.top3HitRate)
        val fourth = TypingTestMetrics.evaluate(input(candidates = listOf("甲", "乙", "丙", prompt.text)))
        assertEquals(TypingTestFraction(0, 1), fourth.top3HitRate)
    }

    @Test fun `known prompted alignment is eligible only after explicit canonical completion`() {
        val source = input(wrong).copy(firstAttemptTouches = touches(wrong),
            finalInputPinyin = prompt.pinyin, observedAttemptPinyin = prompt.pinyin,
            finalCandidateSnapshot = TypingTestCandidateSnapshot(prompt.pinyin, listOf(prompt.text)))
        val result = TypingTestMetrics.evaluate(source)
        assertEquals(prompt.pinyin.length, result.calibrationSamples.size)
        val changed = result.calibrationSamples.filter { it.originalKey != it.intendedKey }
        assertEquals(listOf('n', 'a'), changed.map { it.intendedKey })
        assertTrue(changed.all { it.normalizedOffsetX in -.65.. .65 })
        assertTrue(result.calibrationSamples.all { it.orientation == "portrait" && it.hand == "unknown" })
        assertTrue(TypingTestMetrics.evaluate(source.copy(committedText = "经常回")).calibrationSamples.isEmpty())
    }

    @Test fun `a corrected letter stream is never treated as original physical contacts`() {
        val source = input().copy(firstAttemptTouches = touches(wrong))
        assertTrue(TypingTestMetrics.evaluate(source).calibrationSamples.isEmpty())
    }

    @Test fun `unknown orientation or invalid single contact rejects whole calibration mapping`() {
        val source = input().copy(firstAttemptTouches = touches(prompt.pinyin))
        for (badTouch in listOf(source.firstAttemptTouches[0].copy(orientation = "unknown"),
            source.firstAttemptTouches[0].copy(downX = Float.NaN),
            source.firstAttemptTouches[0].copy(density = 0f),
            source.firstAttemptTouches[0].copy(cells = cells + cells.first()),
            source.firstAttemptTouches[0].copy(downY = 10000f))) {
            val bad = source.firstAttemptTouches.toMutableList().also { it[0] = badTouch }
            assertTrue(TypingTestMetrics.evaluate(source.copy(firstAttemptTouches = bad)).calibrationSamples.isEmpty())
        }
    }

    @Test fun `unconfirmed output and partial commits cannot generate calibration`() {
        val source = input().copy(firstAttemptTouches = touches(prompt.pinyin))
        assertTrue(TypingTestMetrics.evaluate(source.copy(committedText = "")).calibrationSamples.isEmpty())
        assertTrue(TypingTestMetrics.evaluate(source.copy(priorCommitCount = 1)).calibrationSamples.isEmpty())
    }

    @Test fun `backspace rate retains actions separately from character alignment`() {
        val result = TypingTestMetrics.evaluate(input().copy(backspaceCount = 3, letterKeyCount = 20))
        assertEquals(TypingTestFraction(3, 20), result.backspaceRate)
        assertEquals(3, result.backspaceCount)
        assertEquals(TypingTestFraction(0, prompt.pinyin.length), result.rawEditRate)
        assertNull(TypingTestMetrics.evaluate(input().copy(letterKeyCount = -1)).backspaceRate.value)
    }

    @Test fun `nearest rank phone latency units negative exclusions and sample cap are explicit`() {
        val timings = TypingTestMetrics.latency((1L..20L).map { it * 1_000_000L } + -1L)
        assertEquals(20, timings.sampleCount)
        assertEquals(10_000_000L, timings.p50Nanos)
        assertEquals(19_000_000L, timings.p95Nanos)
        assertEquals(20_000_000L, timings.maximumNanos)
        assertEquals(1, timings.invalidSampleCount)
        assertEquals("enqueue_to_key_action_completion_ns", timings.measurement)
        val capped = TypingTestMetrics.latency(List(300) { 0L })
        assertEquals(256, capped.sampleCount)
        assertEquals(44, capped.omittedSampleCount)
        assertNull(TypingTestMetrics.latency(emptyList()).p95Nanos)
    }

    @Test fun `summary uses weighted character counts and only eligible phrase trials`() {
        val other = TypingTestPrompts.byId(1)!!
        val short = input().copy(prompt = other, firstAttemptPinyin = "nihaoa",
            finalInputPinyin = "nihaoa", observedAttemptPinyin = "nihaoa",
            candidateInputPinyin = "nihaoa",
            finalCandidateSnapshot = TypingTestCandidateSnapshot("nihaoa", listOf(other.text)))
        val results = listOf(TypingTestMetrics.evaluate(input(wrong)), TypingTestMetrics.evaluate(short),
            TypingTestMetrics.evaluate(input("jib", complete = false)))
        val summary = summarizeTypingTest(results, listOf(1L, 10L, 100L))
        assertEquals(TypingTestFraction(2, prompt.pinyin.length + other.pinyin.length), summary.rawEditRate)
        assertEquals(TypingTestFraction(2, 2), summary.top1HitRate)
        assertEquals(100L, summary.latency.p95Nanos)
        val pooled = summarizeTypingTest(results, List(300) { it.toLong() })
        assertEquals(300, pooled.latency.sampleCount)
        assertEquals(0, pooled.latency.omittedSampleCount)
    }
    @Test fun `frozen valid initial miss survives final unknown kind and later partial commits`() {
        val source = input(wrong, listOf("井场灰", prompt.text)).copy(
            inputKind = TypingTestInputKind.UNKNOWN,
            firstAttemptInputKind = TypingTestInputKind.FULL_PINYIN,
            priorCommitCount = 2,
            finalCandidateSnapshot = TypingTestCandidateSnapshot(wrong, listOf("井场灰", prompt.text),
                inputKindAtCapture = TypingTestInputKind.FULL_PINYIN, priorCommitCountAtCapture = 0))
        val result = TypingTestMetrics.evaluate(source)
        assertEquals(TypingTestFraction(2, prompt.pinyin.length), result.rawEditRate)
        assertEquals(TypingTestFraction(0, 1), result.top1HitRate)
        assertEquals(TypingTestFraction(1, 1), result.top3HitRate)
        assertTrue(result.calibrationSamples.isEmpty())
    }

    @Test fun `frozen unsupported snapshot does not gain eligibility after full pinyin repair`() {
        val source = input().copy(firstAttemptInputKind = TypingTestInputKind.UNKNOWN,
            finalCandidateSnapshot = TypingTestCandidateSnapshot(prompt.pinyin, listOf(prompt.text),
                inputKindAtCapture = TypingTestInputKind.UNKNOWN, priorCommitCountAtCapture = 0))
        assertEquals(TypingTestFraction(0, 0), TypingTestMetrics.evaluate(source).top1HitRate)
        assertEquals(TypingTestFraction(0, 0), TypingTestMetrics.evaluate(source).rawEditRate)
    }

    @Test fun `early prefix mismatch scores only three observed positions`() {
        val result = TypingTestMetrics.evaluate(input("jib", complete = false))
        assertEquals(TypingTestFraction(1, 3), result.earlyPrefixMismatchRate)
        assertEquals(TypingTestFraction(1, 3), result.earlyPrefixAdjacentRate)
        assertEquals(TypingTestFraction(0, 0), result.rawEditRate)
        assertEquals(TypingTestFraction(0, 0), result.top1HitRate)
        assertTrue(result.calibrationSamples.isEmpty())
    }

    @Test fun `visible candidate ranks are distinct from native ranks`() {
        val source = input(candidates = listOf("井场灰", prompt.text)).copy(
            displayedCandidateSnapshot = TypingTestCandidateSnapshot(prompt.pinyin, listOf(prompt.text, "井场灰")))
        val result = TypingTestMetrics.evaluate(source)
        assertEquals(TypingTestFraction(0, 1), result.top1HitRate)
        assertEquals(TypingTestFraction(1, 1), result.displayedTop1HitRate)
        val stale = source.copy(displayedCandidateSnapshot = source.displayedCandidateSnapshot!!.copy(rawPinyin = wrong))
        assertEquals(TypingTestFraction(0, 0), TypingTestMetrics.evaluate(stale).displayedTop1HitRate)
    }

    @Test fun `initialization actual native query and total latency remain independent`() {
        val source = input().copy(stageTimings = TypingTestStageTimings(
            alternativeQueryNanos = listOf(25_000_000), probeLibraryLoadNanos = listOf(3_000_000),
            probeInitializationNanos = listOf(21_000_000), probeNativeQueryNanos = listOf(500_000)))
        val stages = TypingTestMetrics.evaluate(source).stageLatencies
        assertEquals(25_000_000L, stages.alternativeQuery.p95Nanos)
        assertEquals(3_000_000L, stages.probeLibraryLoad.p95Nanos)
        assertEquals(21_000_000L, stages.probeInitialization.p95Nanos)
        assertEquals(500_000L, stages.probeNativeQuery.p95Nanos)
    }

    @Test fun `backspaces per repaired trial never claims a per error denominator`() {
        val results = listOf(TypingTestMetrics.evaluate(input().copy(backspaceCount = 20)),
            TypingTestMetrics.evaluate(input().copy(backspaceCount = 40)),
            TypingTestMetrics.evaluate(input()))
        val summary = TypingTestMetrics.summarize(results)
        assertEquals(2, summary.repairTrialCount)
        assertEquals(TypingTestFraction(60, 2), summary.backspacesPerRepairTrial)
        assertEquals(3, summary.firstAttemptScoredTrialCount)
    }

    @Test fun `known adjacent swap does not learn two fictional key offsets`() {
        val swapPrompt = TypingTestPrompt(101, "练习", "nb")
        val swap = "bn"
        val source = input().copy(prompt = swapPrompt, firstAttemptPinyin = swap,
            firstAttemptTouches = touches(swap), finalInputPinyin = swapPrompt.pinyin,
            committedText = swapPrompt.text, letterKeyCount = 2, calibrationConfirmed = true)
        val result = TypingTestMetrics.evaluate(source)
        assertEquals(TypingTestFraction(2, 2), result.rawEditRate)
        assertTrue(result.calibrationSamples.isEmpty())
    }

    @Test fun `extra tail in raw first attempt scores an insertion but does not invent a touch label`() {
        val target = TypingTestPrompts.byId(1)!!
        val result = TypingTestMetrics.evaluate(TypingTestTrialInput(target, TypingTestInputKind.FULL_PINYIN,
            firstAttemptPinyin = "nihaoaa", firstAttemptComplete = true,
            finalInputPinyin = target.pinyin, observedAttemptPinyin = target.pinyin,
            candidateInputPinyin = target.pinyin,
            finalCandidateSnapshot = TypingTestCandidateSnapshot(target.pinyin, listOf(target.text)),
            committedText = target.text, firstAttemptTouches = touches("nihaoaa")))
        assertEquals(TypingTestFraction(1, 6), result.rawEditRate)
        assertEquals(TypingTestFraction(1, 1), result.top1HitRate)
        assertTrue(result.calibrationSamples.isEmpty())
    }

    @Test fun `unsupported later raw window cannot retroactively suppress frozen native ranks`() {
        val source = input().copy(firstAttemptInputKind = TypingTestInputKind.FULL_PINYIN,
            rawAttemptInputKind = TypingTestInputKind.UNKNOWN,
            finalCandidateSnapshot = TypingTestCandidateSnapshot(prompt.pinyin, listOf(prompt.text),
                inputKindAtCapture = TypingTestInputKind.FULL_PINYIN, priorCommitCountAtCapture = 0))
        val result = TypingTestMetrics.evaluate(source)
        assertEquals(TypingTestFraction(0, 0), result.rawEditRate)
        assertEquals(TypingTestFraction(1, 1), result.top1HitRate)
        assertTrue(result.calibrationSamples.isEmpty())
    }

}
