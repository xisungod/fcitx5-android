/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates

import android.graphics.Color
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.text.inSpans
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme

/**
 * Small hints need normal-text contrast. Horizontal/expanded items may inherit
 * the theme background or sit on its opaque bar; floating items use the popup
 * background. Transparent/image surfaces remain unknown rather than guessed.
 */
internal fun candidateCommentForeground(theme: Theme, floating: Boolean = false): Int {
    val original = theme.candidateCommentColor
    if (!floating && theme is Theme.Custom && theme.backgroundImage != null) return original
    val surfaces = if (floating) listOf(theme.backgroundColor)
        else listOf(theme.backgroundColor, theme.barColor).distinct()
    if (surfaces.any { Color.alpha(it) != 255 }) return original
    fun readable(color: Int) = surfaces.all { ColorUtils.calculateContrast(color, it) >= 4.5 }
    if (readable(original) || !readable(theme.candidateTextColor)) return original
    // Keep the lightest possible adjustment toward the existing primary color.
    var low = 0f
    var high = 1f
    repeat(12) {
        val middle = (low + high) / 2f
        if (readable(ColorUtils.blendARGB(original, theme.candidateTextColor, middle))) high = middle
        else low = middle
    }
    return ColorUtils.blendARGB(original, theme.candidateTextColor, high)
}

/** Candidate text remains primary; spelling hints and correction marks are secondary. */
internal fun styledCandidateText(
    candidate: CandidateWord,
    foreground: Int,
    commentForeground: Int,
    labelForeground: Int? = null
): Spanned = buildSpannedString {
    if (labelForeground != null) color(labelForeground) { append(candidate.label) }
    color(foreground) { append(candidate.text) }
    if (candidate.comment.isNotBlank()) {
        if (candidate.spaceBetweenComment) {
            inSpans(RelativeSizeSpan(.5f)) { append(" ") }
        }
        color(commentForeground) {
            if (candidate.displayComment == "*") {
                inSpans(RelativeSizeSpan(.55f), SuperscriptSpan()) { append("*") }
            } else {
                inSpans(RelativeSizeSpan(.625f)) { append(candidate.displayComment) }
            }
        }
    }
}
