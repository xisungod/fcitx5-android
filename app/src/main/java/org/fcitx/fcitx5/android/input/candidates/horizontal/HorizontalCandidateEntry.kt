/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates.horizontal

import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTouchCandidateOffer

/** Display positions are independent of the live engine's selection and paging indices. */
internal sealed interface HorizontalCandidateEntry {
    data class Raw(val nativeIndex: Int, val word: CandidateWord) : HorizontalCandidateEntry
    data class Touch(val offer: PinyinTouchCandidateOffer) : HorizontalCandidateEntry
}

/**
 * An alternative already in the engine's top three may move to second. Other
 * suggestions follow the original top three, so an unverified new word cannot
 * displace them. Only display duplicates disappear; raw words stay unchanged.
 */
internal fun horizontalCandidateEntries(
    words: Array<CandidateWord>, offer: PinyinTouchCandidateOffer?
): List<HorizontalCandidateEntry> {
    val first = words.firstOrNull() ?: return emptyList()
    val touch = offer?.takeIf { it.text.isNotBlank() && it.text != first.text }
    val originalIndex = touch?.let { candidate -> words.indexOfFirst { it.text == candidate.text } }
    val insertionIndex = if (originalIndex != null && originalIndex in 1..2) 1 else minOf(3, words.size)
    return buildList {
        words.forEachIndexed { index, word ->
            if (index == insertionIndex && touch != null) add(HorizontalCandidateEntry.Touch(touch))
            if (touch == null || word.text != touch.text)
                add(HorizontalCandidateEntry.Raw(index, word))
        }
        if (insertionIndex == words.size && touch != null) add(HorizontalCandidateEntry.Touch(touch))
    }
}
