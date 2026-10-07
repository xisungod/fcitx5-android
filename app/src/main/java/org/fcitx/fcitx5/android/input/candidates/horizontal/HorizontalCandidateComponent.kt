/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.content.res.Configuration
import android.graphics.Rect
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
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.typing.PinyinTouchCandidateOffer
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
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val buffer = CandidatePageBuffer()
    private var pageJob: Job? = null
    private var displayedTouchToken: Long? = null
    private var visibilityCheckPosted = false
    private var candidateSpelling: String? = null
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
            }
        }.apply {
            onRawSelect = { index ->
                commonKeyActionListener.invalidateTouchCandidates()
                fcitx.launchOnReady { it.select(index) }
            }
            onRawLongClick = { index, candidate, anchor ->
                commonKeyActionListener.invalidateTouchCandidates()
                inputView.showCandidateActionMenu(index, candidate.text, anchor)
            }
            onTouchSelect = { token ->
                commonKeyActionListener.listener.onKeyAction(
                    KeyAction.SelectTouchCandidateAction(token), KeyActionListener.Source.Keyboard)
            }
            onCandidatesLayoutRequested = { scheduleTouchVisibilityCheck() }
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
                    scheduleTouchVisibilityCheck()
                }
            })
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleTouchVisibilityCheck() }
        }
    }

    fun setTouchCandidate(offer: PinyinTouchCandidateOffer?) {
        adapter.setTouchCandidate(offer?.takeIf { it.originalSpelling == candidateSpelling })
        scheduleTouchVisibilityCheck()
    }

    /** Records only attached, laid-out visible slots, never the adapter's offscreen top three. */
    private fun scheduleTouchVisibilityCheck() {
        if (visibilityCheckPosted) return
        visibilityCheckPosted = true
        view.post {
            visibilityCheckPosted = false
            if (!view.isAttachedToWindow || !view.isShown) return@post
            val viewport = Rect()
            if (!view.getGlobalVisibleRect(viewport) || viewport.isEmpty) return@post
            val visible = (0 until view.childCount).mapNotNull { index ->
                val child = view.getChildAt(index)
                val holder = view.getChildViewHolder(child) as? CandidateViewHolder ?: return@mapNotNull null
                val binding = adapter.currentBinding(holder) ?: return@mapNotNull null
                val bounds = Rect()
                if (!child.isShown || !holder.ui.visibleTextBounds(bounds) ||
                    !bounds.intersect(viewport) || bounds.isEmpty) return@mapNotNull null
                val body = Rect()
                val fullyVisible = holder.ui.mainTextBounds(body) && bounds.contains(body)
                Triple(holder.bindingAdapterPosition, binding.entry, fullyVisible)
            }.sortedBy { it.first }
            // Only the first-screen contiguous prefix counts as completely readable.
            // A sliver, a clipped long phrase or a scrolled page cannot masquerade as top three.
            val fullyVisiblePrefix = visible.withIndex()
                .takeWhile { (index, entry) -> entry.first == index && entry.third }
                .take(3).map { it.value }
            if (fullyVisiblePrefix.isNotEmpty()) commonKeyActionListener.onCandidatesDisplayed(
                fullyVisiblePrefix.map { (_, entry, _) ->
                    when (entry) {
                        is HorizontalCandidateEntry.Raw -> entry.word.text
                        is HorizontalCandidateEntry.Touch -> entry.offer.text
                    }
                }, candidateSpelling)
            visible.map { it.second }.filterIsInstance<HorizontalCandidateEntry.Touch>().firstOrNull()?.let { entry ->
                if (displayedTouchToken != entry.offer.token) {
                    displayedTouchToken = entry.offer.token
                    commonKeyActionListener.onTouchCandidateDisplayed(entry.offer.token)
                }
            }
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
        candidateSpelling = commonKeyActionListener.currentRenderedPinyinSpelling()
        val offer = commonKeyActionListener.touchCandidateOffer.value
            ?.takeIf { it.originalSpelling == candidateSpelling }
        adapter.updateCandidates(data.candidates, data.total, offer)
        layoutManager.scrollToPositionWithOffset(0, 0)
        _expandedCandidateOffset.tryEmit(0)
        bar.expandButtonStateMachine.push(ExpandedCandidatesUpdated,
            ExpandedCandidatesEmpty to data.candidates.isEmpty())
        view.post { loadMoreIfNeeded(); scheduleTouchVisibilityCheck() }
    }
}
