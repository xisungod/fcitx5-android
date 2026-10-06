/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.ScancodeMapping

/**
 * An explicitly chosen backup goes through live Rime's normal selection path.
 * The caller must claim its offer and run this inside [FcitxAPI.withInputTransaction].
 * No external suspension, direct editor commit, Return or Space is used here.
 */
internal suspend fun FcitxAPI.resolvePinyinTouchCandidate(
    offer: PinyinTouchCandidateOffer,
    allowed: () -> Boolean
): Boolean {
    fun currentSpelling(): String? {
        val preedit = inputPanelCached.preedit
        return PinyinMultiPathTracker.spellingAtEnd(preedit.toString(), preedit.cursor)
    }
    fun validSpelling(spelling: String) = spelling.isNotEmpty() && spelling.length <= 32 &&
        spelling.all { it in 'a'..'z' }
    if (!allowed() || !validSpelling(offer.originalSpelling) ||
        !validSpelling(offer.alternativeSpelling) ||
        offer.originalSpelling.length != offer.alternativeSpelling.length ||
        offer.originalSpelling == offer.alternativeSpelling || offer.text.isBlank() ||
        currentSpelling() != offer.originalSpelling) return false

    suspend fun replay(spelling: String) {
        reset()
        for (letter in spelling) sendKey(letter.toString(), KeyStates.Virtual.states,
            ScancodeMapping.charToScancode(letter))
    }
    replay(offer.alternativeSpelling)
    val index = if (allowed() && currentSpelling() == offer.alternativeSpelling)
        getCandidates(0, 32).indexOfFirst { it.text == offer.text }
    else -1
    if (index >= 0 && allowed() && select(index) &&
        inputPanelCached.preedit.isEmpty() && clientPreeditCached.isEmpty()) return true

    // A partial selection remains composing in the pinned Rime engine. Discard
    // it and put the original literal path back, without forcing a commit.
    replay(offer.originalSpelling)
    return false
}
