/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PinyinTouchCandidateOffer(
    val token: Long,
    val text: String,
    val originalSpelling: String,
    val alternativeSpelling: String,
    /** Native candidate indices remain unchanged; default-key selection resolves this offer. */
    val promotedToFirst: Boolean = false,
    /** Bounded machine reason, useful when inspecting the experiment locally. */
    val promotionReason: String? = null
)

/** One generation covers queued taps, the delayed query and its explicit selection. */
internal class PinyinTouchCandidateRuntime {
    data class Pending(val offer: PinyinTouchCandidateOffer, val editorIdentity: Any,
                       val proposal: PinyinMultiPathProposal,
                       val expectedPreedit: String, val expectedCursor: Int)
    /** A deliberate selection belongs to its already queued position, not later tap generations. */
    data class Reserved(val offer: PinyinTouchCandidateOffer, val editorIdentity: Any,
                        val expectedPreedit: String, val expectedCursor: Int)
    private var sequence = 0L
    private var lastClearSequence = 0L
    private var tracker: PinyinMultiPathTracker? = null
    private var promotionPolicy: PinyinTouchPromotionPolicy? = null
    private var pending: Pending? = null
    private val mutableOffer = MutableStateFlow<PinyinTouchCandidateOffer?>(null)
    val offer = mutableOffer.asStateFlow()

    @Synchronized fun tracker(model: PinyinTouchLanguageModel): PinyinMultiPathTracker =
        (tracker ?: PinyinMultiPathTracker(model, model.syllables).also {
            promotionPolicy = PinyinTouchPromotionPolicy(model.syllables)
            if (lastClearSequence > 0) {
                it.advanceSequence(lastClearSequence - 1)
                it.clear()
            }
            tracker = it
        })
            .also { it.advanceSequence(sequence) }

    @Synchronized fun nextAction(): Long {
        pending = null
        mutableOffer.value = null
        return (++sequence).also { tracker?.advanceSequence(it) }
    }

    @Synchronized fun clear(): Long {
        pending = null
        mutableOffer.value = null
        // clear() advances the tracker's watermark once. Keep the owner generation
        // exactly aligned so a previously queued action cannot resurrect history.
        sequence += 2
        lastClearSequence = sequence
        tracker?.advanceSequence(sequence - 1)
        tracker?.clear()
        return sequence
    }

    @Synchronized fun isCurrent(token: Long) = token == sequence

    @Synchronized fun matches(proposal: PinyinMultiPathProposal, editor: Any?,
                              text: String, cursor: Int): Boolean =
        proposal.editorSequence == sequence &&
            tracker?.matchesProposal(sequence, editor, text, cursor) == true

    @Synchronized fun publish(proposal: PinyinMultiPathProposal, editor: Any,
                              text: String, currentText: String, cursor: Int,
                              originalCandidates: List<String> = emptyList(),
                              allowFirstPromotion: Boolean = false): Boolean {
        if (!matches(proposal, editor, currentText, cursor) || text.isBlank()) return false
        val promotion = if (allowFirstPromotion) promotionPolicy?.evaluate(proposal, text, originalCandidates) else null
        val offer = PinyinTouchCandidateOffer(sequence, text, proposal.originalSpelling,
            proposal.alternativeSpelling, promotion?.promoteToFirst == true, promotion?.reason?.name)
        pending = Pending(offer, editor, proposal, currentText, cursor)
        mutableOffer.value = offer
        return true
    }

    @Synchronized fun pending(token: Long, editor: Any?, text: String, cursor: Int): Pending? =
        pending?.takeIf { it.offer.token == token && it.editorIdentity === editor &&
            matches(it.proposal, editor, text, cursor) }

    /** Hide and consume the chip before self-generated reset/replay UI events.
     * This does not advance the generation: a new user action still cancels it. */
    @Synchronized fun claim(token: Long, editor: Any?, text: String, cursor: Int): Pending? {
        val result = pending(token, editor, text, cursor) ?: return null
        pending = null
        mutableOffer.value = null
        return result
    }

    /** Capture a selected slot now, then validate its exact native state at its queue position. */
    @Synchronized fun reserve(token: Long, editor: Any?, requirePromotedFirst: Boolean = false): Reserved? {
        val selected = pending?.takeIf {
            token == sequence && it.offer.token == token && it.editorIdentity === editor &&
                (!requirePromotedFirst || it.offer.promotedToFirst)
        } ?: return null
        pending = null
        mutableOffer.value = null
        return Reserved(selected.offer, selected.editorIdentity,
            selected.expectedPreedit, selected.expectedCursor)
    }
}
