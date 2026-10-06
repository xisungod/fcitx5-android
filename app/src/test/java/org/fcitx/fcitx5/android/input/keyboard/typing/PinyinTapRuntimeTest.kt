/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import org.junit.Assert.*
import org.junit.Test

class PinyinTapRuntimeTest {
    private val editor = Any()
    private val evidence = PinyinTapEvidence(TapEvidence('b', 98f, 40f, 1f), listOf(
        KeyCell('b', 0f, 0f, 100f, 100f), KeyCell('n', 100f, 0f, 200f, 100f)))
    private val corrected = PinyinSpatialKeyDecider.Decision('b', 'n', .05f, .95f,
        reason = PinyinSpatialKeyDecider.Reason.BoundaryCorrection)

    private fun record(runtime: PinyinTapRuntime, sequence: Long = runtime.nextAction(),
                       before: String = "ji", after: String? = "jin") = sequence.also {
        runtime.recordCorrection(it, editor, before, after, corrected, 13u, 48, evidence, 4L)
    }

    @Test fun pureSpellingOnlyUsesTheActualEndOfComposition() {
        assertEquals("jingchang", PinyinTapRuntime.spellingAtEnd("jing chang", 10))
        assertEquals("xi'an", PinyinTapRuntime.spellingAtEnd("xi'an", 5))
        assertEquals("", PinyinTapRuntime.spellingAtEnd("", -1))
        assertEquals("", PinyinTapRuntime.spellingAtEnd("", 0))
        assertNull(PinyinTapRuntime.spellingAtEnd("jing chang", 4))
        assertNull(PinyinTapRuntime.spellingAtEnd("jing", -1))
        assertNull(PinyinTapRuntime.spellingAtEnd("", 1))
    }

    @Test fun mixedCommittedTextOrFormattedVowelCannotBeMistakenForRawPinyin() {
        for (text in listOf("经常hui", "nü", "ABC", "abc\n", "abc1", "abc@")) {
            assertNull(text, PinyinTapRuntime.spellingAtEnd(text, text.length))
        }
        assertNull(PinyinTapRuntime.spellingAtEnd("a".repeat(257), 257))
    }

    @Test fun restoreCarriesOriginalKeyAndStatesOnlyForExactStillUncommittedSpelling() {
        val runtime = PinyinTapRuntime()
        val token = record(runtime)
        assertEquals(PinyinTapFeedback(token, 'b', 'n'), runtime.feedback.value)
        val restore = runtime.takePending(token, editor, "jin")!!
        assertEquals('b', restore.feedback.original)
        assertEquals(13u, restore.states)
        assertEquals(48, restore.originalCode)
        assertSame(evidence, restore.evidence)
        assertEquals(4L, restore.profileGeneration)
        assertNull(runtime.feedback.value)
        assertNull(runtime.takePending(token, editor, "jin"))
    }

    @Test fun nextQueuedKeyInvalidatesRestoreEvenBeforeItsNativeExecution() {
        val runtime = PinyinTapRuntime()
        val token = record(runtime)
        runtime.nextAction()
        assertNull(runtime.takePending(token, editor, "jin"))
        assertNull(runtime.feedback.value)
    }

    @Test fun oldNativeCompletionCannotRecreateFeedbackAfterANewerAction() {
        val runtime = PinyinTapRuntime()
        val token = runtime.nextAction()
        runtime.nextAction()
        record(runtime, token)
        assertNull(runtime.feedback.value)
        assertNull(runtime.takePending(token, editor, "jin"))
    }

    @Test fun changingEditorIdentityRejectsUndoAndTraining() {
        val runtime = PinyinTapRuntime()
        val token = record(runtime)
        assertNull(runtime.takePending(token, Any(), "jin"))
        assertNull(runtime.feedback.value)
    }

    @Test fun candidateCommitCursorMoveOrExtraLetterRejectsUndoAndTraining() {
        for (actual in listOf(null, "", "经常", "jing", "ji", "ji'n")) {
            val runtime = PinyinTapRuntime()
            val token = record(runtime)
            assertNull(actual, runtime.takePending(token, editor, actual))
            assertNull(runtime.feedback.value)
        }
    }

    @Test fun feedbackIsNotPublishedIfNativeEngineCommittedOrTransformedTheSpelling() {
        for (after in listOf(null, "", "进", "ji'n", "jing")) {
            val runtime = PinyinTapRuntime()
            record(runtime, after = after)
            assertNull(after, runtime.feedback.value)
        }
    }

    @Test fun unchangedKeyDoesNotOfferAnUndoOrPersonalizationLabel() {
        val runtime = PinyinTapRuntime()
        val token = runtime.nextAction()
        runtime.recordCorrection(token, editor, "ji", "jib", corrected.copy(selected = 'b'),
            0u, 48, evidence)
        assertNull(runtime.feedback.value)
        assertNull(runtime.takePending(token, editor, "jib"))
    }

    @Test fun tokenFromAnOlderCorrectionCannotRestoreTheLatestContact() {
        val runtime = PinyinTapRuntime()
        val old = record(runtime)
        val latest = record(runtime, before = "jin", after = "jinn")
        assertNotEquals(old, latest)
        assertNull(runtime.takePending(old, editor, "jinn"))
    }
}
