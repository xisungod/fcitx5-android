/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded

import android.text.Layout
import android.text.TextPaint
import android.util.LruCache
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.styledCandidateText
import splitties.dimensions.dp
import splitties.views.dsl.core.matchParent

abstract class GridPagingCandidateViewAdapter(theme: Theme) : PagingCandidateViewAdapter(theme) {

    companion object {
        // 20f here is chosen randomly, since we only care about the ratio
        private const val TEXT_SIZE = 20f
    }

    // cache measureWidth
    private val measuredWidths = object : LruCache<CandidateWord, Float>(200) {
        private val cachedPaint = TextPaint().apply { textSize = TEXT_SIZE }
        override fun create(key: CandidateWord): Float {
            return Layout.getDesiredWidth(styledCandidateText(key,
                theme.candidateTextColor, theme.candidateCommentColor), cachedPaint) / TEXT_SIZE
        }
    }

    fun measureWidth(position: Int): Float {
        val candidate = getItem(position) ?: return 0f
        return measuredWidths[candidate]
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        return super.onCreateViewHolder(parent, viewType).apply {
            itemView.apply {
                layoutParams = GridLayoutManager.LayoutParams(matchParent, dp(52))
            }
        }
    }
}
