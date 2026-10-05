/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.content.res.Configuration
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.annotation.Keep
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
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.neural.MiniRbtModelStore
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
import org.fcitx.fcitx5.android.input.keyboard.RimeActions
import org.fcitx.fcitx5.android.input.neural.MiniRbtScorer
import org.fcitx.fcitx5.android.input.neural.NeuralCandidateCoordinator
import org.fcitx.fcitx5.android.input.neural.RankedCandidateBatch
import org.mechdancer.dependency.DynamicScope
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
    private var modelStateJob: Job? = null
    private var scoreRequestJob: Job? = null
    private var editorInfo = EditorInfo()
    private var neuralStarted = false
    private val neuralEnabled = AppPrefs.getInstance().keyboard.miniRbtEnabled
    private val modelStore by lazy { MiniRbtModelStore.get(context) }
    private val neural by lazy {
        NeuralCandidateCoordinator(service.lifecycleScope,
            enabled = { neuralEnabled.getValue() },
            modelReady = { modelStore.state.value.ready },
            scorerFactory = {
                val engine = MiniRbtScorer(context)
                object : NeuralCandidateCoordinator.Scorer {
                    override suspend fun score(contextText: String, candidates: List<String>) =
                        engine.score(contextText, candidates)
                    override fun close() = engine.close()
                }
            },
            readContext = { service.neuralContextBeforeComposition() },
            onUpdate = { applyRankedBatch(it) },
            onFailure = { Timber.w(it, "Optional candidate model could not score this composition") })
    }
    val rankedBatch: RankedCandidateBatch get() = neural.current

    @Keep private val neuralPreferenceListener = ManagedPreference.OnChangeListener<Boolean> { _, enabled ->
        service.lifecycleScope.launch {
            neural.availabilityChanged()
            scoreRequestJob?.cancel()
            if (enabled) {
                modelStore.bootstrapBundled()
                requestNeuralScoring()
            }
        }
    }
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
                holder.itemView.setOnClickListener {
                    selectBoundCandidate(holder)
                }
                holder.itemView.setOnLongClickListener {
                    showBoundCandidateActions(holder)
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
            addOnItemTouchListener(candidateTouchListener())
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
        scoreRequestJob?.cancel()
        buffer.reset(data.candidates, data.total)
        val head = neural.replace(data.candidates,
            fcitx.runImmediately { inputPanelCached.preedit.toString() })
        view.stopScroll()
        adapter.updateCandidates(data.candidates, data.total, generation = head.generation)
        layoutManager.scrollToPositionWithOffset(0, 0)
        _expandedCandidateOffset.tryEmit(0)
        bar.expandButtonStateMachine.push(ExpandedCandidatesUpdated,
            ExpandedCandidatesEmpty to data.candidates.isEmpty())
        view.post { loadMoreIfNeeded() }
        requestNeuralScoring()
    }

    override fun onScopeSetupFinished(scope: DynamicScope) {
        neuralStarted = true
        neuralEnabled.registerOnChangeListener(neuralPreferenceListener)
        modelStateJob = service.lifecycleScope.launch {
            var previous: Pair<Boolean, Long>? = null
            modelStore.state.collect { state ->
                val next = state.ready to state.generation
                if (next != previous) {
                    previous = next
                    neural.availabilityChanged()
                    if (state.ready && neuralEnabled.getValue()) requestNeuralScoring()
                }
            }
        }
        if (neuralEnabled.getValue()) service.lifecycleScope.launch {
            modelStore.bootstrapBundled()
            requestNeuralScoring()
        }
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        editorInfo = info
        invalidateNeuralCandidates()
    }

    override fun onImeUpdate(ime: InputMethodEntry) { invalidateNeuralCandidates() }

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        // Candidate and panel callbacks can arrive in either order. The engine snapshot below
        // verifies their agreement before launching a request.
        if (data.preedit.toString() != rankedBatch.preedit) {
            applyRankedBatch(neural.replace(buffer.words, data.preedit.toString()))
            requestNeuralScoring()
        }
    }

    override fun onClientPreeditUpdate(data: FormattedText) {
        if (data.isEmpty() && fcitx.runImmediately { inputPanelCached.preedit.isEmpty() })
            invalidateNeuralCandidates()
    }

    private fun requestNeuralScoring() {
        if (!neuralStarted || !neuralEnabled.getValue() || !modelStore.state.value.ready) return
        scoreRequestJob?.cancel()
        val generation = rankedBatch.generation
        scoreRequestJob = service.lifecycleScope.launch {
            val snapshot = fcitx.runOnReady {
                val source = rankedBatch
                val native = getCandidates(0, source.originalWords.size)
                if (source.generation != generation ||
                    native.size != source.originalWords.size || !source.matchesNativePage(0, native)) null
                else inputPanelCached.preedit.toString() to RimeActions.isPinyinSchema(inputMethodEntryCached, false)
            } ?: return@launch
            if (rankedBatch.generation != generation) return@launch
            neural.request(snapshot.first, snapshot.second, editorInfo.inputType, editorInfo.imeOptions)
        }
    }

    private fun applyRankedBatch(batch: RankedCandidateBatch) {
        // Native pagination continues from the original count; the head remains a permutation.
        val original = buffer.words
        val words = original.copyOf()
        val indices = words.indices.toList().toIntArray()
        batch.originalWords.indices.forEach { displayIndex ->
            if (displayIndex < words.size) {
                val entry = batch.entry(displayIndex, words[displayIndex])
                words[displayIndex] = entry.word
                indices[displayIndex] = entry.originalIndex
            }
        }
        adapter.updateCandidates(words, buffer.total, indices, batch.generation)
        _expandedCandidateOffset.tryEmit(0)
    }

    fun candidateTouchListener(): RecyclerView.OnItemTouchListener = object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> neural.interactionStarted()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> rv.post { neural.interactionFinished() }
            }
            return false
        }
    }

    fun selectBoundCandidate(holder: CandidateViewHolder) {
        if (holder.bindingAdapterPosition == RecyclerView.NO_POSITION ||
            holder.generation != rankedBatch.generation || holder.idx < 0) return
        neural.candidateSelected()
        val generation = holder.generation
        val index = holder.idx
        val word = holder.candidate
        service.postFcitxJob {
            if (generation != rankedBatch.generation) return@postFcitxJob
            val actual = getCandidates(index, 1).firstOrNull()
            if (generation == rankedBatch.generation && RankedCandidateBatch.sameNativeWord(word, actual))
                select(index)
        }
    }

    fun showBoundCandidateActions(holder: CandidateViewHolder) {
        if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION &&
            holder.generation == rankedBatch.generation && holder.idx >= 0) {
            neural.candidateSelected()
            val generation = holder.generation
            inputView.showCandidateActionMenu(holder.idx, holder.candidate.text, holder.ui.root,
                isCurrent = { generation == rankedBatch.generation }, expectedWord = holder.candidate)
        }
    }

    /** Called on the serialized engine job for Space and commit-before-symbol actions. */
    suspend fun selectPreferredCandidate(api: FcitxAPI): Boolean {
        val batch = rankedBatch
        if (!batch.isReordered || batch.firstOriginalIndex == 0 ||
            !neuralEnabled.getValue() || !modelStore.state.value.ready ||
            !RimeActions.isPinyinSchema(api.inputMethodEntryCached, false) ||
            batch.preedit != api.inputPanelCached.preedit.toString()) return false
        val native = api.getCandidates(0, batch.originalWords.size)
        if (native.size != batch.originalWords.size || !batch.matchesNativePage(0, native)) return false
        if (batch.generation != rankedBatch.generation) return false
        return api.select(batch.firstOriginalIndex)
    }

    /** Freeze on the UI key action before its serialized commit job can be delayed. */
    fun freezeNeuralOrder() { neural.candidateSelected() }

    fun invalidateNeuralCandidates() {
        scoreRequestJob?.cancel()
        neural.invalidate()
        if (neuralStarted) applyRankedBatch(neural.current)
    }

    fun closeNeuralCandidates() {
        scoreRequestJob?.cancel()
        modelStateJob?.cancel()
        if (neuralStarted) neuralEnabled.unregisterOnChangeListener(neuralPreferenceListener)
        neural.close()
        neuralStarted = false
    }
}
