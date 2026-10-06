/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.view.View
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTapFeedback
import org.fcitx.fcitx5.android.utils.borderlessRippleDrawable
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

class CandidateUi(override val ctx: Context, private val theme: Theme, private val horizontalView: View) : Ui {

    private fun feedbackButton() = TextView(ctx).apply {
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(theme.altKeyTextColor)
        setPadding(dp(8), 0, dp(8), 0)
        minWidth = dp(48)
        isClickable = true
        isFocusable = true
        background = borderlessRippleDrawable(theme.keyPressHighlightColor, dp(20))
    }

    private val restoreButton = feedbackButton()
    private val confirmButton = feedbackButton()
    private val feedbackUi = LinearLayout(ctx).apply {
        id = View.generateViewId()
        orientation = LinearLayout.HORIZONTAL
        visibility = View.GONE
        addView(restoreButton, LinearLayout.LayoutParams(wrapContent, matchParent))
        addView(confirmButton, LinearLayout.LayoutParams(wrapContent, matchParent))
    }

    fun setPinyinFeedback(
        feedback: PinyinTapFeedback?, restore: (Long) -> Unit, confirm: (Long) -> Unit
    ) {
        feedbackUi.visibility = if (feedback == null) View.GONE else View.VISIBLE
        restoreButton.setOnClickListener(null)
        confirmButton.setOnClickListener(null)
        if (feedback == null) return
        restoreButton.text = ctx.getString(R.string.pinyin_touch_restore_letter, feedback.original.toString())
        restoreButton.contentDescription = ctx.getString(
            R.string.pinyin_touch_restore_letter_description, feedback.original.toString())
        confirmButton.text = ctx.getString(R.string.pinyin_touch_confirm_letter, feedback.selected.toString())
        confirmButton.contentDescription = ctx.getString(
            R.string.pinyin_touch_confirm_letter_description, feedback.selected.toString())
        restoreButton.setOnClickListener { restore(feedback.token) }
        confirmButton.setOnClickListener { confirm(feedback.token) }
    }

    val expandButton = ToolButton(ctx, R.drawable.ic_baseline_expand_more_24, theme).apply {
        id = R.id.expand_candidate_btn
        visibility = View.INVISIBLE
    }

    override val root = ctx.constraintLayout {
        add(expandButton, lParams(dp(48)) {
            centerVertically()
            endOfParent()
        })
        add(feedbackUi, lParams(wrapContent, matchParent) {
            centerVertically()
            before(expandButton)
        })
        add(horizontalView, lParams {
            centerVertically()
            startOfParent()
            before(feedbackUi)
        })
    }
}
