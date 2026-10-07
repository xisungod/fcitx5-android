/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import kotlin.math.ln

/** Relative likelihoods from the validated DOWN contact, never intention probabilities. */
data class PinyinChangedContactSpatialEvidence(
    val index: Int,
    val original: Char,
    val alternative: Char,
    val logAlternativeOverOriginal: Double
)

data class PinyinTouchPromotionEvidence(
    val logModelAdvantage: Double,
    val changedContacts: List<PinyinChangedContactSpatialEvidence>
)

/**
 * A deliberately narrow first-slot experiment. It does not rewrite letters or
 * decide whether a sentence sounds natural. Complete literal pinyin and any
 * supported full-pinyin/initials interpretation win even when a backup exists.
 * A new word remains supplementary; only agreement with literal rank 2/3 may
 * be promoted, and only with independent spatial support for its one edit.
 */
internal class PinyinTouchPromotionPolicy(syllables: Set<String>) {
    private val inventory = PinyinSyllableInventory(syllables)
    private val syllablesByInitial = syllables.filter { it.isNotEmpty() && it.all { c -> c in 'a'..'z' } }
        .groupBy { it.first() }

    enum class Reason {
        Eligible, UnsupportedSpelling, InvalidModelScores, MissingInventory, CompleteLiteralPinyin,
        ProtectedIncompletePrefix, ProtectedAbbreviation, AlternativeNotFullPinyin,
        EditScope, MissingSpatialEvidence, InsufficientSpatialAdvantage,
        InsufficientPathAdvantage, MissingLiteralAgreement, NonChineseCandidate
    }

    data class Decision(val reason: Reason) {
        val promoteToFirst get() = reason == Reason.Eligible
    }

    fun evaluate(proposal: PinyinMultiPathProposal, text: String,
                 originalCandidates: List<String>): Decision {
        fun reject(reason: Reason) = Decision(reason)
        val raw = proposal.originalSpelling
        val alternative = proposal.alternativeSpelling
        if (raw.isEmpty() || raw.length > 32 || raw.length != alternative.length ||
            raw == alternative || raw.any { it !in 'a'..'z' } || alternative.any { it !in 'a'..'z' })
            return reject(Reason.UnsupportedSpelling)
        if (!proposal.originalCost.isFinite() || !proposal.alternativeCost.isFinite() ||
            !proposal.modelConfidence.isFinite() || proposal.modelConfidence !in 0f..1f ||
            proposal.alternativeCost >= proposal.originalCost) return reject(Reason.InvalidModelScores)
        if (inventory.isEmpty) return reject(Reason.MissingInventory)
        val rawBoundaries = inventory.fullBoundaries(raw)
        if (rawBoundaries[raw.length]) return reject(Reason.CompleteLiteralPinyin)
        if (inventory.hasProtectedIncompleteEnding(raw, rawBoundaries))
            return reject(Reason.ProtectedIncompletePrefix)
        if (hasInitialsInterpretation(raw)) return reject(Reason.ProtectedAbbreviation)
        if (!inventory.fullBoundaries(alternative)[alternative.length])
            return reject(Reason.AlternativeNotFullPinyin)
        val actualChanges = raw.indices.filter { raw[it] != alternative[it] }
        // More edits remain useful suggestions, but are outside this first-slot experiment.
        if (actualChanges.size != 1 || proposal.changedIndices != actualChanges)
            return reject(Reason.EditScope)
        val evidence = proposal.promotionEvidence ?: return reject(Reason.MissingSpatialEvidence)
        val contact = evidence.changedContacts.singleOrNull()
            ?: return reject(Reason.MissingSpatialEvidence)
        val index = actualChanges.single()
        if (contact.index != index || contact.original != raw[index] ||
            contact.alternative != alternative[index]) return reject(Reason.MissingSpatialEvidence)
        if (!contact.logAlternativeOverOriginal.isFinite() ||
            contact.logAlternativeOverOriginal < MIN_SPATIAL_LOG_ADVANTAGE)
            return reject(Reason.InsufficientSpatialAdvantage)
        if (!evidence.logModelAdvantage.isFinite() || evidence.logModelAdvantage < MIN_PATH_LOG_ADVANTAGE)
            return reject(Reason.InsufficientPathAdvantage)
        if (originalCandidates.indexOf(text) !in 1..2) return reject(Reason.MissingLiteralAgreement)
        if (!allHan(text) || !allHan(originalCandidates.firstOrNull().orEmpty()))
            return reject(Reason.NonChineseCandidate)
        return Decision(Reason.Eligible)
    }

    /** Membership/protection only: no alternative generation, word decoding or Rime query. */
    private fun hasInitialsInterpretation(raw: String): Boolean {
        val fullOnly = BooleanArray(raw.length + 1).also { it[0] = true }
        val withInitials = BooleanArray(raw.length + 1)
        for (start in raw.indices) {
            if (!fullOnly[start] && !withInitials[start]) continue
            for (syllable in syllablesByInitial[raw[start]].orEmpty()) {
                val end = start + syllable.length
                if (end <= raw.length && raw.regionMatches(start, syllable, 0, syllable.length)) {
                    if (fullOnly[start]) fullOnly[end] = true
                    if (withInitials[start]) withInitials[end] = true
                }
            }
            if (raw[start] in INITIAL_LETTERS) withInitials[start + 1] = true
            if (start + 2 <= raw.length && raw.substring(start, start + 2) in DOUBLE_INITIALS)
                withInitials[start + 2] = true
        }
        return withInitials[raw.length]
    }

    private fun allHan(text: String): Boolean {
        if (text.isEmpty()) return false
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            if (codePoint !in 0x3400..0x4dbf && codePoint !in 0x4e00..0x9fff &&
                codePoint !in 0xf900..0xfaff && codePoint !in 0x20000..0x323af) return false
            offset += Character.charCount(codePoint)
        }
        return true
    }

    private companion object {
        // Explicit model likelihood ratios, not calibrated correctness probabilities.
        val MIN_SPATIAL_LOG_ADVANTAGE = ln(1.35)
        const val MIN_PATH_LOG_ADVANTAGE = 3.0
        const val INITIAL_LETTERS = "bpmfdtnlgkhjqxrzcsyw"
        val DOUBLE_INITIALS = setOf("zh", "ch", "sh")
    }
}
