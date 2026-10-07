/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.prediction

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.core.LibimeNextWordPredictor
import java.util.concurrent.Executors

/** Host operations/callbacks run on its UI scope; only the read-only model uses the serial worker. */
internal class NextWordPredictionController(
    private val scope: CoroutineScope,
    private val environment: () -> NextWordPredictionRuntime.Environment,
    private val predict: (String, Int) -> LibimeNextWordPredictor.Result,
    private val callbacks: Callbacks = Callbacks(),
    queryDispatcher: CoroutineDispatcher? = null,
    private val closePredictor: () -> Unit = {},
    private val nowNanos: () -> Long = System::nanoTime,
    private val warmBudgetNanos: Long = 50_000_000L,
    private val coldBudgetNanos: Long = 2_000_000_000L
) : AutoCloseable {
    data class Committed(val anchor: NextWordPredictionAnchor, val text: String,
                         val startCursor: Int, val endCursor: Int)
    data class Ready(val anchor: NextWordPredictionAnchor, val offerToken: Long,
                     val result: LibimeNextWordPredictor.Result, val readyAtNanos: Long)
    data class Drawn(val anchor: NextWordPredictionAnchor, val offerToken: Long,
                     val indices: List<Int>, val drawnAtNanos: Long)
    data class Selected(val anchor: NextWordPredictionAnchor, val offerToken: Long,
                        val index: Int, val text: String, val selectedAtNanos: Long)
    data class Chosen(val anchor: NextWordPredictionAnchor, val offerToken: Long,
                      val index: Int, val text: String, val success: Boolean, val resolvedAtNanos: Long)
    data class QueryOutcome(val anchor: NextWordPredictionAnchor, val result: LibimeNextWordPredictor.Result?,
                            val outcome: Outcome, val finishedAtNanos: Long)
    enum class Outcome { Published, NoCandidates, Unavailable, Timeout, Stale, Failed }
    data class Callbacks(
        val onCommitted: (Committed) -> Unit = {},
        val onReady: (Ready) -> Unit = {},
        val onDrawn: (Drawn) -> Unit = {},
        val onSelected: (Selected) -> Unit = {},
        val onChosen: (Chosen) -> Unit = {},
        val onQuery: (QueryOutcome) -> Unit = {}
    )

    private val runtime = NextWordPredictionRuntime()
    private val ownedDispatcher = if (queryDispatcher == null)
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "axiang-next-word").apply { isDaemon = true }
        }.asCoroutineDispatcher() else null
    private val worker = queryDispatcher ?: ownedDispatcher!!
    @Volatile private var closed = false
    private var lastStarted: NextWordPredictionRuntime.Query? = null
    private var drawnToken: Long? = null
    private val drawnIndices = mutableSetOf<Int>()
    val offer get() = runtime.offer

    fun attach(editor: Any?, connection: Any?) {
        if (closed) return
        runtime.attach(editor, connection)
        lastStarted = null
        forgetDrawing()
    }

    fun resetEditor() {
        runtime.resetEditor()
        lastStarted = null
        forgetDrawing()
    }

    fun captureOrigin(observation: Any? = null) = runtime.captureOrigin(observation)

    /** Never handles or consumes the actual key; the host follows its existing input path. */
    fun onUserAction(clearContext: Boolean = false): NextWordPredictionOrigin {
        lastStarted = null
        forgetDrawing()
        return runtime.onUserAction(clearContext)
    }

    fun invalidate(clearContext: Boolean = true) {
        runtime.invalidate(clearContext)
        lastStarted = null
        forgetDrawing()
    }

    /** Invoke only after the editor actually accepted the insertion. */
    fun onActualCommitted(origin: NextWordPredictionOrigin?, text: String, startCursor: Int,
        endCursor: Int, atNanos: Long) {
        if (closed) return
        val accepted = runtime.onActualCommitted(origin, text, startCursor, endCursor,
            atNanos, currentEnvironment()) ?: return
        lastStarted = null
        forgetDrawing()
        notify { callbacks.onCommitted(Committed(accepted.anchor, text, startCursor, endCursor)) }
        accepted.query?.let(::schedule)
    }

    /** Call after native panel/candidate events. Repeated idle events do not repeat a query. */
    fun refresh() {
        if (closed) return
        val query = runtime.refresh(currentEnvironment())
        if (query == null) lastStarted = null else schedule(query)
    }

    fun onSelectionChanged(start: Int, end: Int) {
        val before = runtime.captureOrigin()
        runtime.onSelectionChanged(start, end)
        if (!before.sameGeneration(runtime.captureOrigin())) lastStarted = null
        if (runtime.offer.value == null) forgetDrawing()
    }

    fun onDrawn(token: Long, indices: List<Int>, atNanos: Long) {
        if (closed) return
        val pending = runtime.pending(token, currentEnvironment()) ?: return
        if (drawnToken != token) {
            drawnToken = token
            drawnIndices.clear()
        }
        val newlyDrawn = indices.distinct().filter {
            it in pending.offer.candidates.indices && drawnIndices.add(it)
        }
        if (newlyDrawn.isEmpty()) return
        notify { callbacks.onDrawn(Drawn(pending.query.anchor, token, newlyDrawn, atNanos)) }
    }

    /** A UI click writes synchronously through the guarded host, never a delayed native selection. */
    fun select(token: Long, index: Int, observation: Any? = null,
        checkedCommit: (String) -> Boolean): Boolean {
        if (closed) return false
        val selected = runtime.claim(token, index, observation, currentEnvironment()) ?: return false
        forgetDrawing()
        val anchor = selected.pending.query.anchor
        notify { callbacks.onSelected(Selected(anchor, token, index, selected.text, nowNanos())) }
        val success = if (!runtime.selectionStillAllowed(selected, currentEnvironment())) false else try {
            checkedCommit(selected.text)
        } catch (_: Exception) { false }
        val resolvedAt = nowNanos()
        notify { callbacks.onChosen(Chosen(anchor, token, index, selected.text, success, resolvedAt)) }
        if (success) onActualCommitted(selected.origin, selected.text, selected.startCursor,
            selected.endCursor, resolvedAt)
        else invalidate(clearContext = true)
        return success
    }

    private fun schedule(query: NextWordPredictionRuntime.Query) {
        if (closed || lastStarted == query || !runtime.allows(query, currentEnvironment())) return
        lastStarted = query
        scope.launch {
            if (closed || !runtime.allows(query, currentEnvironment())) return@launch
            val result = try {
                withContext(worker) {
                    // Superseded tasks queued behind a cold model load do no additional work.
                    if (closed || !runtime.isCurrent(query)) null
                    else try { predict(query.context, NextWordPredictionRuntime.MAX_CANDIDATES) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        LibimeNextWordPredictor.Result(emptyList(), 0, available = false,
                            failureReason = "PredictionFailure")
                    }
                }
            } catch (e: CancellationException) { throw e }
            if (closed) return@launch
            val finishedAt = nowNanos()
            val outcome: Outcome
            val published: NextWordPredictionRuntime.Pending?
            val env = currentEnvironment()
            val budget = if (result?.coldInitialization == true) coldBudgetNanos else warmBudgetNanos
            when {
                result == null || !runtime.allows(query, env) -> {
                    published = null
                    outcome = Outcome.Stale
                }
                !result.available -> {
                    published = null
                    outcome = if (result.failureReason == "PredictionFailure") Outcome.Failed else Outcome.Unavailable
                }
                result.elapsedNanos < 0 || result.elapsedNanos > budget -> {
                    published = null
                    outcome = Outcome.Timeout
                }
                else -> {
                    published = runtime.publish(query, result.candidates, env)
                    outcome = if (published == null) Outcome.NoCandidates else Outcome.Published
                }
            }
            notify { callbacks.onQuery(QueryOutcome(query.anchor, result, outcome, finishedAt)) }
            if (published != null) notify {
                callbacks.onReady(Ready(query.anchor, published.offer.token, result!!, finishedAt))
            }
        }
    }

    private fun currentEnvironment() = try { environment() } catch (_: Exception) {
        NextWordPredictionRuntime.Environment(null, null, false, false, false, -1, -1)
    }

    private fun forgetDrawing() {
        drawnToken = null
        drawnIndices.clear()
    }

    private inline fun notify(block: () -> Unit) {
        // Optional metrics cannot turn an accepted key/click into an input failure.
        try { block() } catch (_: Exception) { }
    }

    override fun close() {
        if (closed) return
        closed = true
        resetEditor()
        // Cleanup shares the serial worker with all queries, even after the host scope stops.
        CoroutineScope(SupervisorJob() + worker).launch {
            try { closePredictor() } catch (_: Exception) { } finally { ownedDispatcher?.close() }
        }
    }
}
