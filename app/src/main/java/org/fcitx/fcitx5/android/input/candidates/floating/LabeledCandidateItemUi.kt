/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.floating

import android.content.Context
import android.graphics.Color
import android.widget.TextView
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.styledCandidateText
import org.fcitx.fcitx5.android.input.candidates.candidateCommentForeground
import splitties.views.backgroundColor
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.textView

class LabeledCandidateItemUi(
    override val ctx: Context,
    val theme: Theme,
    setupTextView: TextView.() -> Unit
) : Ui {
    private val commentForeground = candidateCommentForeground(theme, floating = true)

    override val root = textView {
        setupTextView(this)
    }

    fun update(candidate: CandidateWord, active: Boolean) {
        val labelFg = if (active) theme.genericActiveForegroundColor else theme.candidateLabelColor
        val fg = if (active) theme.genericActiveForegroundColor else theme.candidateTextColor
        val altFg = if (active) theme.genericActiveForegroundColor else commentForeground
        root.text = styledCandidateText(candidate, fg, altFg, labelFg)
        val bg = if (active) theme.genericActiveBackgroundColor else Color.TRANSPARENT
        root.backgroundColor = bg
    }
}
