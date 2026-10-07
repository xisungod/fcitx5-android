/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import android.content.Context
import android.graphics.Rect
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter

class CandidateItemUi(override val ctx: Context, val theme: Theme) : Ui {
    private var mainTextLength = 0
    private val commentForeground = candidateCommentForeground(theme)

    private val text = view(::CandidateTextView) {
        textSize = 24f // sp
        isSingleLine = true
        gravity = gravityCenter
        setTextColor(theme.candidateTextColor)
    }

    override val root = view(::CustomGestureView) {
        background = pressHighlightDrawable(theme.keyPressHighlightColor)

        /**
         * candidate long press feedback is handled by [org.fcitx.fcitx5.android.input.BaseInputView.showCandidateActionMenu]
         */
        longPressFeedbackEnabled = false

        add(text, lParams(wrapContent, matchParent) {
            gravity = gravityCenter
        })
    }

    fun updateCandidate(candidate: CandidateWord) {
        mainTextLength = candidate.text.length
        val fg = theme.candidateTextColor
        val altFg = commentForeground
        text.text = styledCandidateText(candidate, fg, altFg)
    }

    /** Ignore a slot whose viewport intersection contains only its horizontal padding. */
    internal fun visibleTextBounds(bounds: Rect): Boolean = text.isShown &&
        text.getGlobalVisibleRect(bounds) && !bounds.isEmpty

    internal fun mainTextBounds(bounds: Rect): Boolean = text.mainTextBounds(mainTextLength, bounds)
}
