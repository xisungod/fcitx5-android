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
 * Thresholds of the first-slot decision. Defaults are the shipped behaviour, chosen on a
 * synthetic Gaussian-tap simulation over the bundled dictionary (docs/axiang/touch9.md).
 */
internal data class PinyinTouchPromotionSettings(
    /** Combined spatial and language log advantage of the alternative over the literal letters. */
    val minPathLogAdvantage: Double = 2.0,
    val maxChanges: Int = 2,
    /** A changed contact must keep at least this likelihood relative to the literal key. */
    val minSpatialLogRatio: Double = -3.0,
    /**
     * Stricter limit when the literal letters could also be read as syllables plus bare
     * initials (xhuan, zhge). Only a tap close to the border may then override them.
     */
    val minSpatialLogRatioWithInitials: Double = -1.5,
    /** The same letters also need a larger combined advantage. */
    val minPathLogAdvantageWithInitials: Double = 4.0
)

/**
 * Decides whether a corrected spelling's word may take the first slot.
 *
 * No personal calibration is required. The literal letters always win when they are
 * complete full pinyin, an unfinished syllable, or initials only. Promotion is for
 * letters that are not full pinyin, where a nearby-key spelling is, and both the touch
 * positions and the language model favour it. Letters that could also be a mix of
 * syllables and initials need the changed tap to be close to the border. The literal
 * candidates stay in the bar directly after the promoted word.
 */
internal class PinyinTouchPromotionPolicy(
    syllables: Set<String>,
    private val settings: PinyinTouchPromotionSettings = PinyinTouchPromotionSettings()
) {
    private val inventory = PinyinSyllableInventory(syllables)
    private val syllablesByInitial = syllables.filter { it.isNotEmpty() && it.all { c -> c in 'a'..'z' } }
        .groupBy { it.first() }

    enum class Reason {
        Eligible, UnsupportedSpelling, InvalidModelScores, MissingInventory, CompleteLiteralPinyin,
        ProtectedIncompletePrefix, ProtectedAbbreviation, AlternativeNotFullPinyin,
        EditScope, MissingSpatialEvidence, InsufficientSpatialAdvantage,
        InsufficientPathAdvantage, NonChineseCandidate
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
        if (!inventory.fullBoundaries(alternative)[alternative.length])
            return reject(Reason.AlternativeNotFullPinyin)
        val actualChanges = raw.indices.filter { raw[it] != alternative[it] }
        if (actualChanges.isEmpty() || actualChanges.size > settings.maxChanges ||
            proposal.changedIndices != actualChanges) return reject(Reason.EditScope)
        val evidence = proposal.promotionEvidence ?: return reject(Reason.MissingSpatialEvidence)
        if (evidence.changedContacts.size != actualChanges.size)
            return reject(Reason.MissingSpatialEvidence)
        for ((position, index) in actualChanges.withIndex()) {
            val contact = evidence.changedContacts[position]
            if (contact.index != index || contact.original != raw[index] ||
                contact.alternative != alternative[index]) return reject(Reason.MissingSpatialEvidence)
            if (!contact.logAlternativeOverOriginal.isFinite() ||
                contact.logAlternativeOverOriginal < settings.minSpatialLogRatio)
                return reject(Reason.InsufficientSpatialAdvantage)
        }
        if (!evidence.logModelAdvantage.isFinite() ||
            evidence.logModelAdvantage < settings.minPathLogAdvantage)
            return reject(Reason.InsufficientPathAdvantage)
        // Letters that also read as an abbreviation are overridden only on close spatial
        // evidence and a clearly better spelling.
        if (hasInitialsInterpretation(raw) &&
            (evidence.logModelAdvantage < settings.minPathLogAdvantageWithInitials ||
                evidence.changedContacts.any {
                    it.logAlternativeOverOriginal < settings.minSpatialLogRatioWithInitials }))
            return reject(Reason.ProtectedAbbreviation)
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
        const val INITIAL_LETTERS = "bpmfdtnlgkhjqxrzcsyw"
        val DOUBLE_INITIALS = setOf("zh", "ch", "sh")
    }
}
