/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.annotation.SuppressLint
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.LayoutParams
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import splitties.dimensions.dp
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.setPaddingDp

open class HorizontalCandidateViewAdapter(val theme: Theme) :
    RecyclerView.Adapter<CandidateViewHolder>() {


    var candidates: Array<CandidateWord> = arrayOf()
        private set

    private var originalIndices = intArrayOf()
    private var generation = 0L

    var total = -1
        private set

    @SuppressLint("NotifyDataSetChanged")
    fun updateCandidates(data: Array<CandidateWord>, total: Int,
        originalIndices: IntArray = data.indices.toList().toIntArray(), generation: Long = 0) {
        require(originalIndices.size == data.size)
        this.candidates = data
        this.originalIndices = originalIndices.copyOf()
        this.generation = generation
        this.total = total
        notifyDataSetChanged()
    }

    override fun getItemCount() = candidates.size

    fun appendCandidates(data: Array<CandidateWord>, total: Int) {
        val start = candidates.size
        candidates += data
        originalIndices += IntArray(data.size) { start + it }
        this.total = total
        notifyItemRangeInserted(start, data.size)
    }

    @CallSuper
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        val ui = CandidateItemUi(parent.context, theme)
        ui.root.apply {
            minimumWidth = dp(40)
            setPaddingDp(16, 0, 16, 0)
            layoutParams = LayoutParams(wrapContent, matchParent)
        }
        return CandidateViewHolder(ui)
    }

    @CallSuper
    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        holder.update(originalIndices[position], candidates[position], generation)
    }

    @CallSuper
    override fun onViewRecycled(holder: CandidateViewHolder) {
        holder.clear()
    }

}
