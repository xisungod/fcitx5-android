/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PinyinTapFeedback(val token: Long, val original: Char, val selected: Char)

/** Only the last, still uncommitted corrected contact can be restored. No inferred training labels. */
internal class PinyinTapRuntime {
    data class Pending(
        val feedback: PinyinTapFeedback,
        val editorIdentity: Any,
        val expectedSpelling: String,
        val states: UInt,
        val originalCode: Int,
        val evidence: PinyinTapEvidence,
        val profileGeneration: Long?
    )

    private var sequence = 0L
    private var pending: Pending? = null
    private val mutableFeedback = MutableStateFlow<PinyinTapFeedback?>(null)
    val feedback: StateFlow<PinyinTapFeedback?> = mutableFeedback.asStateFlow()

    /** Called when an input action is enqueued, before its serialized native operation. */
    @Synchronized
    fun nextAction(): Long {
        sequence++
        pending = null
        mutableFeedback.value = null
        return sequence
    }

    @Synchronized
    fun recordCorrection(
        actionSequence: Long,
        editorIdentity: Any,
        before: String,
        after: String?,
        decision: PinyinSpatialKeyDecider.Decision,
        states: UInt,
        originalCode: Int,
        evidence: PinyinTapEvidence,
        profileGeneration: Long? = null
    ) {
        if (actionSequence != sequence || !decision.corrected ||
            after != before + decision.selected) return
        val feedback = PinyinTapFeedback(actionSequence, decision.original, decision.selected)
        pending = Pending(feedback, editorIdentity, after, states, originalCode, evidence, profileGeneration)
        mutableFeedback.value = feedback
    }

    /** Recheck at execution, not when the chip was tapped or its view was rendered. */
    @Synchronized
    fun takePending(token: Long, editorIdentity: Any?, currentSpelling: String?): Pending? {
        val result = pending?.takeIf {
            token == sequence && token == it.feedback.token &&
                editorIdentity === it.editorIdentity && currentSpelling == it.expectedSpelling
        }
        nextAction()
        return result
    }

    companion object {
        /** Rime's syllable display spaces are presentation; explicit apostrophes remain boundaries. */
        fun spellingAtEnd(text: String, cursor: Int): String? {
            if (text.isEmpty()) return if (cursor == 0 || cursor == -1) "" else null
            if (cursor != text.length || text.length > 512 ||
                text.any { it !in 'a'..'z' && it != ' ' && it != '\'' }) return null
            return text.replace(" ", "").takeIf { it.length <= 256 }
        }
    }
}
