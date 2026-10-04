/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.content.res.Configuration
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import timber.log.Timber

class HorizontalCandidateComponent :
    UniqueViewComponent<HorizontalCandidateComponent, RecyclerView>(), InputBroadcastReceiver {
    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val inputView by manager.inputView()
    private val service by manager.inputMethodService()
    private val bar: KawaiiBarComponent by manager.must()
    private val buffer = CandidatePageBuffer()
    private var pageJob: Job? = null
    private val fillStyle by AppPrefs.getInstance().keyboard.horizontalCandidateStyle
    private val maxSpanCountPref by lazy {
        AppPrefs.getInstance().keyboard.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                expandedCandidateGridSpanCount else expandedCandidateGridSpanCountLandscape
        }
    }
    private val _expandedCandidateOffset = MutableSharedFlow<Int>(replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val expandedCandidateOffset = _expandedCandidateOffset.asSharedFlow()

    val adapter: HorizontalCandidateViewAdapter by lazy {
        object : HorizontalCandidateViewAdapter(theme) {
            override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                val slots = when (fillStyle) {
                    HorizontalCandidateMode.NeverFillWidth -> 0
                    HorizontalCandidateMode.AutoFillWidth -> maxSpanCountPref.getValue()
                    HorizontalCandidateMode.AlwaysFillWidth ->
                        minOf(itemCount, maxSpanCountPref.getValue()).coerceAtLeast(1)
                }
                holder.itemView.minimumWidth = if (slots == 0) context.dp(40)
                    else maxOf(context.dp(40), view.width / slots)
                val generation = buffer.generation
                holder.itemView.setOnClickListener {
                    if (generation == buffer.generation && holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                        val index = holder.idx
                        fcitx.launchOnReady { it.select(index) }
                    }
                }
                holder.itemView.setOnLongClickListener {
                    if (generation == buffer.generation && holder.bindingAdapterPosition != RecyclerView.NO_POSITION)
                        inputView.showCandidateActionMenu(holder.idx, holder.candidate.text, holder.ui.root)
                    true
                }
            }
            override fun onViewRecycled(holder: CandidateViewHolder) {
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
                super.onViewRecycled(holder)
            }
        }
    }
    val layoutManager: LinearLayoutManager by lazy { LinearLayoutManager(context, RecyclerView.HORIZONTAL, false) }
    override val view: RecyclerView by lazy {
        RecyclerView(context).apply {
            id = R.id.candidate_view
            itemAnimator = null
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            adapter = this@HorizontalCandidateComponent.adapter
            layoutManager = this@HorizontalCandidateComponent.layoutManager
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    loadMoreIfNeeded()
                }
            })
        }
    }
    private fun loadMoreIfNeeded() {
        if (layoutManager.findLastVisibleItemPosition() < adapter.itemCount - 5) return
        val request = buffer.request() ?: return
        pageJob = service.lifecycleScope.launch {
            try {
                val page = fcitx.runOnReady { getCandidates(request.offset, request.limit) }
                if (buffer.complete(request, page)) adapter.appendCandidates(page, buffer.total)
            } catch (e: CancellationException) {
                buffer.failed(request)
                throw e
            } catch (e: Exception) {
                buffer.failed(request)
                Timber.w(e, "Could not load the next candidate page")
            }
        }
    }
    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        pageJob?.cancel()
        buffer.reset(data.candidates, data.total)
        view.stopScroll()
        adapter.updateCandidates(data.candidates, data.total)
        layoutManager.scrollToPositionWithOffset(0, 0)
        _expandedCandidateOffset.tryEmit(0)
        bar.expandButtonStateMachine.push(ExpandedCandidatesUpdated,
            ExpandedCandidatesEmpty to data.candidates.isEmpty())
        view.post { loadMoreIfNeeded() }
    }
}
