/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.LayoutParams
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTouchCandidateOffer
import org.fcitx.fcitx5.android.input.prediction.NextWordPredictionOffer
import java.util.WeakHashMap
import splitties.dimensions.dp
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.setPaddingDp

open class HorizontalCandidateViewAdapter(val theme: Theme) :
    RecyclerView.Adapter<CandidateViewHolder>() {


    var candidates: Array<CandidateWord> = arrayOf()
        private set

    var total = -1
        private set

    private var touchOffer: PinyinTouchCandidateOffer? = null
    private var predictionOffer: NextWordPredictionOffer? = null
    internal var entries: List<HorizontalCandidateEntry> = emptyList()
        private set
    internal var renderGeneration = 0L
        private set
    internal var onRawSelect: (Int) -> Unit = {}
    internal var onRawLongClick: (Int, CandidateWord, View) -> Unit = { _, _, _ -> }
    internal var onTouchSelect: (Long) -> Unit = {}
    internal var onPredictionSelect: (Long, Int) -> Unit = { _, _ -> }
    internal var onCandidatesLayoutRequested: () -> Unit = {}
    private val holderBindings = WeakHashMap<CandidateViewHolder, Binding>()

    internal data class Binding(val generation: Long, val entry: HorizontalCandidateEntry)
    internal fun binding(position: Int) = Binding(renderGeneration, entries[position])
    internal fun isCurrent(binding: Binding, position: Int): Boolean =
        binding.generation == renderGeneration && position >= 0 && entries.getOrNull(position) == binding.entry
    internal fun currentBinding(holder: RecyclerView.ViewHolder): Binding? {
        val candidate = holder as? CandidateViewHolder ?: return null
        return holderBindings[candidate]?.takeIf { isCurrent(it, candidate.bindingAdapterPosition) }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun render() {
        renderGeneration++
        entries = horizontalCandidateEntries(candidates, touchOffer, predictionOffer)
        notifyDataSetChanged()
    }

    fun setTouchCandidate(offer: PinyinTouchCandidateOffer?) {
        if (offer != null) predictionOffer = null
        if (offer == touchOffer) return
        touchOffer = offer
        render()
    }

    fun setPredictionOffer(offer: NextWordPredictionOffer?) {
        val accepted = offer?.takeIf { candidates.isEmpty() && touchOffer == null }
            ?.let { it.copy(candidates = it.candidates.toList()) }
        if (accepted == predictionOffer) return
        predictionOffer = accepted
        render()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateCandidates(data: Array<CandidateWord>, total: Int,
                         offer: PinyinTouchCandidateOffer? = touchOffer) {
        this.candidates = data
        this.total = total
        this.touchOffer = offer
        predictionOffer = null
        render()
    }

    override fun getItemCount() = entries.size

    fun appendCandidates(data: Array<CandidateWord>, total: Int) {
        predictionOffer = null
        candidates += data
        this.total = total
        render()
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
        val binding = binding(position)
        holderBindings[holder] = binding
        val entry = binding.entry
        when (entry) {
            is HorizontalCandidateEntry.Raw -> {
                holder.update(entry.nativeIndex, entry.word)
                holder.itemView.contentDescription = null
                holder.itemView.setOnLongClickListener {
                    if (isCurrent(binding, holder.bindingAdapterPosition))
                        onRawLongClick(entry.nativeIndex, entry.word, holder.ui.root)
                    true
                }
            }
            is HorizontalCandidateEntry.Touch -> {
                holder.update(-1, CandidateWord("", entry.offer.text, "*", false))
                holder.itemView.contentDescription = holder.itemView.context.getString(
                    R.string.pinyin_touch_alternative_description, entry.offer.text)
                holder.itemView.setOnLongClickListener(null)
                holder.itemView.isLongClickable = false
            }
            is HorizontalCandidateEntry.Prediction -> {
                holder.update(-1, CandidateWord("", entry.text, ""))
                holder.itemView.contentDescription = entry.text
                holder.itemView.setOnLongClickListener(null)
                holder.itemView.isLongClickable = false
            }
        }
        holder.itemView.setOnClickListener {
            if (isCurrent(binding, holder.bindingAdapterPosition)) when (entry) {
                is HorizontalCandidateEntry.Raw -> onRawSelect(entry.nativeIndex)
                is HorizontalCandidateEntry.Touch -> onTouchSelect(entry.offer.token)
                is HorizontalCandidateEntry.Prediction -> onPredictionSelect(entry.token, entry.index)
            }
        }
        onCandidatesLayoutRequested()
    }

    override fun onViewAttachedToWindow(holder: CandidateViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder.bindingAdapterPosition >= 0) onCandidatesLayoutRequested()
    }

    @CallSuper
    override fun onViewRecycled(holder: CandidateViewHolder) {
        holderBindings.remove(holder)
        holder.itemView.setOnClickListener(null)
        holder.itemView.setOnLongClickListener(null)
        holder.itemView.isLongClickable = false
        holder.itemView.contentDescription = null
        holder.clear()
    }

}
