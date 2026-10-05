/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.neural

import android.text.InputType
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.core.CandidateWord

/** Coordinates optional inference without delaying ordinary candidate updates or typing. */
internal class NeuralCandidateCoordinator(
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
    private val modelReady: () -> Boolean,
    private val scorerFactory: () -> Scorer,
    private val readContext: () -> String,
    private val onUpdate: (RankedCandidateBatch) -> Unit,
    private val debounceMillis: Long = 50,
    private val onFailure: (Throwable) -> Unit = {}
) {
    interface Scorer : AutoCloseable {
        suspend fun score(contextText: String, candidates: List<String>): List<Double>?
    }

    @Volatile var current: RankedCandidateBatch = RankedCandidateBatch.original(0, emptyArray())
        private set
    private var nextGeneration = 0L
    private var job: Job? = null
    private val scorerLock = Mutex()
    private var scorer: Scorer? = null
    @Volatile private var interacted = false
    @Volatile private var pressing = false
    private var restoreAfterInteraction = false
    @Volatile private var closed = false

    fun replace(words: Array<CandidateWord>, preedit: String = ""): RankedCandidateBatch {
        cancelScoring()
        interacted = pressing
        restoreAfterInteraction = false
        current = RankedCandidateBatch.original(++nextGeneration, words, preedit)
        return current
    }

    fun request(preedit: String, fullPinyin: Boolean, inputType: Int, imeOptions: Int = 0) {
        cancelScoring()
        if (closed || interacted || !enabled() || !modelReady() || !fullPinyin ||
            !supportsEditor(inputType, imeOptions) || !isFullPinyin(preedit)) return
        val source = current.withPreedit(preedit)
        val indices = RankedCandidateBatch.scoringIndices(source.originalWords)
        if (indices.size < 2) return
        current = source
        job = scope.launch {
            try {
                delay(debounceMillis)
                if (!canApply(source.generation)) return@launch
                // InputConnection access stays on the caller/UI dispatcher and is never reached
                // while the switch is off, the model is absent, or the editor is sensitive.
                val context = readContext().takeLast(48)
                val scores = withContext(Dispatchers.Default) {
                    scorerLock.withLock {
                        if (!canApply(source.generation)) return@withLock null
                        val active = scorer ?: scorerFactory().also { scorer = it }
                        active.score(context, indices.map { source.originalWords[it].text })
                    }
                } ?: return@launch
                if (!canApply(source.generation)) return@launch
                // Unmarked native first choices are protected. Even a marked correction is
                // protected when there is no meaningful committed Chinese context.
                val protectFirst = !source.originalWords.first().comment.contains("纠错") ||
                    context.codePoints().filter {
                        Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN
                    }.count() < 4
                val result = source.rerank(nextGeneration + 1, indices, scores, protectFirst)
                if (result.isReordered) {
                    nextGeneration++
                    current = result
                    onUpdate(result)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onFailure(error)
            }
        }
    }

    private fun canApply(generation: Long) = !closed && !interacted && !pressing && enabled() && modelReady() &&
        current.generation == generation

    /** Once a candidate is touched, preserve its position for the rest of this composition. */
    fun interactionStarted() {
        pressing = true
        candidateSelected()
    }

    fun candidateSelected() {
        interacted = true
        cancelScoring()
    }

    fun interactionFinished() {
        pressing = false
        if (restoreAfterInteraction) {
            restoreAfterInteraction = false
            restoreOriginal()
        }
        // Remain interacted until the next native composition update. No delayed word jumps.
    }

    fun availabilityChanged() {
        cancelScoring()
        releaseScorer()
        if (pressing) restoreAfterInteraction = true else restoreOriginal()
    }

    private fun restoreOriginal() {
        if (current.isReordered) {
            current = current.original(++nextGeneration)
            onUpdate(current)
        }
    }

    fun invalidate() {
        cancelScoring()
        current = current.original(++nextGeneration)
        interacted = true
    }

    fun close() {
        closed = true
        invalidate()
        releaseScorer()
    }

    private fun cancelScoring() { job?.cancel(); job = null }
    private fun releaseScorer() {
        // Closing may need to wait for an ONNX Run. It must never block the keyboard thread.
        scope.launch(NonCancellable + Dispatchers.Default) {
            scorerLock.withLock {
                val previous = scorer
                scorer = null
                runCatching { previous?.close() }.exceptionOrNull()?.let(onFailure)
            }
        }
    }

    companion object {
        fun supportsEditor(inputType: Int, imeOptions: Int): Boolean {
            if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
            if (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return false
            return inputType and InputType.TYPE_MASK_VARIATION !in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI)
        }
        private fun isFullPinyin(value: String): Boolean = value.length in 4..64 &&
            value.count { it in 'a'..'z' } >= 4 && value.all { it in 'a'..'z' || it == '\'' || it == ' ' }
    }
}
