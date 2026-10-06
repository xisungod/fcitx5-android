/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PinyinTouchCandidateRuntimeTest {
    private val editor = Any()
    private val raw = "jibgchsnghui"
    private val alternative = "jingchanghui"
    private val cells = listOf(row("qwertyuiop", 0f, 0f), row("asdfghjkl", 50f, 140f),
        row("zxcvbnm", 150f, 280f)).flatten()
    private val model by lazy {
        listOf(File("src/main/assets/typing/pinyin_touch_model.tsv"),
            File("app/src/main/assets/typing/pinyin_touch_model.tsv")).first { it.isFile }
            .reader().use(PinyinTouchLanguageModel::parse)
    }
    private fun row(letters: String, left: Float, top: Float) = letters.mapIndexed { i, letter ->
        KeyCell(letter, left + i * 100f, top, left + (i + 1) * 100f, top + 140f)
    }
    private fun evidence(letter: Char): PinyinTapEvidence {
        val cell = cells.first { it.letter == letter }
        val x = when (letter) { 'b' -> cell.right - .25f; 's' -> cell.left + .25f; else -> cell.centerX }
        return PinyinTapEvidence(TapEvidence(letter, x, cell.centerY, 1f), cells)
    }
    private fun type(runtime: PinyinTouchCandidateRuntime): PinyinMultiPathProposal {
        val tracker = runtime.tracker(model)
        var proposal: PinyinMultiPathProposal? = null
        for (i in raw.indices) {
            val before = raw.take(i)
            val after = raw.take(i + 1)
            proposal = tracker.recordTap(runtime.nextAction(), editor, before, before.length,
                after, after.length, evidence(raw[i]))
        }
        return proposal!!
    }
    private fun publish(runtime: PinyinTouchCandidateRuntime): PinyinMultiPathProposal = type(runtime).also {
        assertTrue(runtime.publish(it, editor, "经常会", raw, raw.length))
    }

    @Test fun `clear invalidates queued taps and aligns existing tracker with fresh action`() {
        val runtime = PinyinTouchCandidateRuntime()
        val tracker = runtime.tracker(model)
        val oldToken = runtime.nextAction()
        val clearToken = runtime.clear()
        assertTrue(runtime.isCurrent(clearToken))
        assertFalse(runtime.isCurrent(oldToken))
        assertNull(tracker.recordTap(oldToken, editor, "", 0, "j", 1, evidence('j')))
        assertEquals("", tracker.rawSpelling)
        val freshToken = runtime.nextAction()
        assertNull(tracker.recordTap(freshToken, editor, "", 0, "j", 1, evidence('j')))
        assertEquals("j", tracker.rawSpelling)
    }

    @Test fun `clear before model initialization rejects pre-reset queued tap`() {
        val runtime = PinyinTouchCandidateRuntime()
        val oldToken = runtime.nextAction()
        runtime.clear()
        val tracker = runtime.tracker(model)
        assertNull(tracker.recordTap(oldToken, editor, "", 0, "j", 1, evidence('j')))
        assertEquals("", tracker.rawSpelling)
        val freshToken = runtime.nextAction()
        assertNull(tracker.recordTap(freshToken, editor, "", 0, "j", 1, evidence('j')))
        assertEquals("j", tracker.rawSpelling)
    }

    @Test fun `late tracker initialization retains ordered queued contacts and publishes only latest token`() {
        val runtime = PinyinTouchCandidateRuntime()
        val tokens = raw.map { runtime.nextAction() }
        val tracker = runtime.tracker(model)
        var proposal: PinyinMultiPathProposal? = null
        for (i in raw.indices) {
            val before = raw.take(i)
            val after = raw.take(i + 1)
            proposal = tracker.recordTap(tokens[i], editor, before, before.length,
                after, after.length, evidence(raw[i]))
            assertEquals(after, tracker.rawSpelling)
            if (i != raw.lastIndex) assertNull(proposal)
        }
        assertEquals(tokens.last(), proposal!!.editorSequence)
        assertEquals(alternative, proposal.alternativeSpelling)
        assertNull(runtime.offer.value)
        assertTrue(runtime.matches(proposal, editor, raw, raw.length))
        assertTrue(runtime.publish(proposal, editor, "经常会", raw, raw.length))
        assertEquals(tokens.last(), runtime.offer.value!!.token)
    }

    @Test fun `publish and pending require exact editor spelling cursor and generation`() {
        val runtime = PinyinTouchCandidateRuntime()
        val proposal = type(runtime)
        assertFalse(runtime.publish(proposal, Any(), "经常会", raw, raw.length))
        assertFalse(runtime.publish(proposal, editor, "经常会", alternative, alternative.length))
        assertFalse(runtime.publish(proposal, editor, "经常会", raw, 2))
        assertFalse(runtime.publish(proposal.copy(editorSequence = proposal.editorSequence - 1),
            editor, "经常会", raw, raw.length))
        assertFalse(runtime.publish(proposal, editor, "  ", raw, raw.length))
        assertNull(runtime.offer.value)
        assertTrue(runtime.publish(proposal, editor, "经常会", "jibg' chsng hui", 15))
        val offer = runtime.offer.value!!
        assertEquals(PinyinTouchCandidateOffer(proposal.editorSequence, "经常会", raw, alternative), offer)
        assertNull(runtime.pending(offer.token - 1, editor, raw, raw.length))
        assertNull(runtime.pending(offer.token, Any(), raw, raw.length))
        assertNull(runtime.pending(offer.token, editor, alternative, alternative.length))
        assertNull(runtime.pending(offer.token, editor, raw, 2))
        val pending = runtime.pending(offer.token, editor, raw, raw.length)!!
        assertSame(proposal, pending.proposal)
        assertSame(editor, pending.editorIdentity)
        assertEquals(offer, pending.offer)
    }

    @Test fun `next action removes chip and blocks delayed publication while retaining raw contacts`() {
        val runtime = PinyinTouchCandidateRuntime()
        val proposal = publish(runtime)
        runtime.nextAction()
        assertNull(runtime.offer.value)
        assertNull(runtime.pending(proposal.editorSequence, editor, raw, raw.length))
        assertFalse(runtime.publish(proposal, editor, "经常会", raw, raw.length))
        assertEquals(raw, runtime.tracker(model).rawSpelling)
    }

    @Test fun `backspace invalidates chip but preserves verified earlier ambiguous contacts`() {
        val runtime = PinyinTouchCandidateRuntime()
        val originalProposal = publish(runtime)
        val tracker = runtime.tracker(model)
        val before = raw.dropLast(1)
        tracker.recordBackspace(runtime.nextAction(), editor, raw, raw.length, before, before.length)
        assertNull(runtime.offer.value)
        assertNull(runtime.pending(originalProposal.editorSequence, editor, raw, raw.length))
        assertEquals(before, tracker.rawSpelling)
        val restored = tracker.recordTap(runtime.nextAction(), editor, before, before.length,
            raw, raw.length, evidence(raw.last()))!!
        assertEquals(alternative, restored.alternativeSpelling)
        assertTrue(runtime.publish(restored, editor, "经常会", raw, raw.length))
        assertEquals(restored.editorSequence, runtime.offer.value!!.token)
    }

    @Test fun `claim consumes once and hides flow offer without advancing guarded generation`() {
        val runtime = PinyinTouchCandidateRuntime()
        val proposal = publish(runtime)
        val token = proposal.editorSequence
        assertNull(runtime.claim(token - 1, editor, raw, raw.length))
        assertNull(runtime.claim(token, Any(), raw, raw.length))
        assertNull(runtime.claim(token, editor, alternative, alternative.length))
        assertNull(runtime.claim(token, editor, raw, 2))
        assertNotNull(runtime.offer.value)
        val claimed = runtime.claim(token, editor, raw, raw.length)!!
        assertSame(proposal, claimed.proposal)
        assertEquals("经常会", claimed.offer.text)
        assertNull(runtime.offer.value)
        assertNull(runtime.pending(token, editor, raw, raw.length))
        assertNull(runtime.claim(token, editor, raw, raw.length))
        assertTrue(runtime.isCurrent(token))
        assertTrue(runtime.matches(proposal, editor, raw, raw.length))
        assertFalse(runtime.matches(proposal, editor, alternative, alternative.length))
    }

    @Test fun `user action after claim invalidates selection generation and preserved proposal guard`() {
        val runtime = PinyinTouchCandidateRuntime()
        val proposal = publish(runtime)
        assertNotNull(runtime.claim(proposal.editorSequence, editor, raw, raw.length))
        val userToken = runtime.nextAction()
        assertTrue(runtime.isCurrent(userToken))
        assertFalse(runtime.isCurrent(proposal.editorSequence))
        assertFalse(runtime.matches(proposal, editor, raw, raw.length))
        assertNull(runtime.offer.value)
        assertNull(runtime.claim(proposal.editorSequence, editor, raw, raw.length))
        assertEquals(raw, runtime.tracker(model).rawSpelling)
    }

    @Test fun `claimed selection survives offer-conditioned reset and replay panel notifications`() {
        val runtime = PinyinTouchCandidateRuntime()
        // Exercise the listener contract using native reset/replay snapshots.
        // Visible chips expire on incompatible snapshots; claimed chips are hidden.
        fun preeditEmptyNotification() {
            if (runtime.offer.value != null) runtime.clear()
        }
        fun inputPanelNotification(text: String) {
            val visible = runtime.offer.value ?: return
            if (runtime.pending(visible.token, editor, text, text.length) == null) runtime.clear()
        }

        val visibleProposal = publish(runtime)
        preeditEmptyNotification()
        assertFalse(runtime.isCurrent(visibleProposal.editorSequence))
        val proposal = publish(runtime)
        assertNotNull(runtime.claim(proposal.editorSequence, editor, raw, raw.length))
        preeditEmptyNotification()
        inputPanelNotification("")
        for (end in 1..alternative.length) inputPanelNotification(alternative.take(end))
        assertNull(runtime.offer.value)
        assertTrue(runtime.isCurrent(proposal.editorSequence))
        assertTrue(runtime.matches(proposal, editor, raw, raw.length))
        runtime.nextAction()
        assertFalse(runtime.isCurrent(proposal.editorSequence))
    }

    @Test fun `clear removes offered candidate and verified history across repeated resets`() {
        val runtime = PinyinTouchCandidateRuntime()
        val proposal = publish(runtime)
        runtime.clear()
        runtime.clear()
        assertNull(runtime.offer.value)
        assertNull(runtime.pending(proposal.editorSequence, editor, raw, raw.length))
        assertFalse(runtime.publish(proposal, editor, "经常会", raw, raw.length))
        assertEquals("", runtime.tracker(model).rawSpelling)
        assertEquals(alternative, type(runtime).alternativeSpelling)
    }
}
