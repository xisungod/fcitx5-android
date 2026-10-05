/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded

import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.neural.RankedCandidateBatch

open class PagingCandidateViewAdapter(val theme: Theme) :
    PagingDataAdapter<RankedCandidateBatch.Entry, CandidateViewHolder>(diffCallback) {

    companion object {
        /**
         * Always re-bind all [CandidateViewHolder]s every time to make sure `idx` is up-to-date.
         * [CandidateViewHolder.update] would skip unnecessary UI updates.
         */
        private val diffCallback = object : DiffUtil.ItemCallback<RankedCandidateBatch.Entry>() {
            override fun areItemsTheSame(oldItem: RankedCandidateBatch.Entry, newItem: RankedCandidateBatch.Entry) = false
            override fun areContentsTheSame(oldItem: RankedCandidateBatch.Entry, newItem: RankedCandidateBatch.Entry) = false
        }
    }

    var offset = 0
        private set

    fun refreshWithOffset(offset: Int) {
        this.offset = offset
        refresh()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        return CandidateViewHolder(CandidateItemUi(parent.context, theme))
    }

    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        val candidate = getItem(position)
        holder.update(candidate?.originalIndex ?: -1, candidate?.word ?: CandidateWord.Empty,
            candidate?.generation ?: -1)
    }
}
