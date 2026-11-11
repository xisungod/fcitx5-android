/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates.horizontal

import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTouchCandidateOffer
import org.fcitx.fcitx5.android.input.prediction.NextWordPredictionOffer

/** Display positions are independent of the live engine's selection and paging indices. */
internal sealed interface HorizontalCandidateEntry {
    data class Raw(val nativeIndex: Int, val word: CandidateWord) : HorizontalCandidateEntry
    data class Touch(val offer: PinyinTouchCandidateOffer) : HorizontalCandidateEntry
    data class Prediction(val token: Long, val index: Int, val text: String) : HorizontalCandidateEntry
}

/**
 * A first-slot offer is resolved through its own selection path, whether or not the
 * engine already listed that word: the promotion policy only grants it when the typed
 * letters are not pinyin, so the literal first word is not something the user asked for.
 * The literal words follow it unchanged. Without that grant, an alternative already in
 * the engine's top three may move to second and any other suggestion follows the top
 * three. Only display duplicates disappear; raw words stay unchanged.
 */
internal fun horizontalCandidateEntries(
    words: Array<CandidateWord>, offer: PinyinTouchCandidateOffer?,
    prediction: NextWordPredictionOffer? = null
): List<HorizontalCandidateEntry> {
    val first = words.firstOrNull() ?: return if (offer == null) {
        prediction?.candidates?.mapIndexedNotNull { index, text ->
            text.takeIf { it.isNotBlank() }?.let {
                HorizontalCandidateEntry.Prediction(prediction.token, index, it)
            }
        } ?: emptyList()
    } else emptyList()
    val touch = offer?.takeIf { it.text.isNotBlank() && it.text != first.text }
    val originalIndex = touch?.let { candidate -> words.indexOfFirst { it.text == candidate.text } }
    val insertionIndex = when {
        touch?.promotedToFirst == true -> 0
        originalIndex != null && originalIndex in 1..2 -> 1
        else -> minOf(3, words.size)
    }
    return buildList {
        words.forEachIndexed { index, word ->
            if (index == insertionIndex && touch != null) add(HorizontalCandidateEntry.Touch(touch))
            if (touch == null || word.text != touch.text)
                add(HorizontalCandidateEntry.Raw(index, word))
        }
        if (insertionIndex == words.size && touch != null) add(HorizontalCandidateEntry.Touch(touch))
    }
}
