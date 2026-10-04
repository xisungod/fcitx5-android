/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Native decoding is serialized on a worker; cancel invalidates callbacks before stopping audio. */
internal class LocalOfflineDictationSession(
    unavailableMessage: String?,
    private val hasPermission: () -> Boolean,
    private val requestPermission: () -> Unit,
    private val audioFactory: () -> DictationAudioSource,
    private val engineFactory: () -> DictationSpeechEngine,
    private val applyTranscript: DictationTranscriptCallback,
    private val onAbandon: () -> Unit,
    private val failureMessage: (DictationFailure) -> String,
    private val worker: ExecutorService = sharedWorker
) : OfflineDictationSession {
    private val lock = Any()
    private val mutableState = MutableStateFlow(
        if (unavailableMessage == null) OfflineDictationState()
        else OfflineDictationState(OfflineDictationPhase.Unavailable, message = unavailableMessage)
    )
    override val state: StateFlow<OfflineDictationState> = mutableState.asStateFlow()
    private var generation = 0L
    private var closed = false
    private var stopRequested = false
    private var source: DictationAudioSource? = null

    override fun start() {
        synchronized(lock) {
            if (closed || mutableState.value.phase in setOf(
                    OfflineDictationPhase.Preparing, OfflineDictationPhase.Recording,
                    OfflineDictationPhase.Finishing, OfflineDictationPhase.Unavailable)) return
            if (!hasPermission()) {
                mutableState.value = OfflineDictationState(OfflineDictationPhase.Error,
                    message = failureMessage(DictationFailure.Permission))
                runCatching { requestPermission() }
                return
            }
            val token = ++generation
            stopRequested = false
            mutableState.value = OfflineDictationState(OfflineDictationPhase.Preparing)
            worker.execute { recognize(token) }
        }
    }

    private fun valid(token: Long) = !closed && token == generation

    private fun recognize(token: Long) {
        var engine: DictationSpeechEngine? = null
        var audio: DictationAudioSource? = null
        var failure = DictationFailure.Engine
        try {
            synchronized(lock) { if (!valid(token) || stopRequested) return }
            engine = engineFactory()
            synchronized(lock) { if (!valid(token) || stopRequested) return }
            failure = DictationFailure.Audio
            audio = audioFactory()
            synchronized(lock) {
                if (!valid(token) || stopRequested) return
                source = audio
            }
            // Source.stop() also prevents a subsequent start(), closing the cancellation race.
            audio.start()
            synchronized(lock) {
                if (!valid(token) || stopRequested) return
                mutableState.value = OfflineDictationState(OfflineDictationPhase.Recording)
            }
            while (synchronized(lock) { valid(token) && !stopRequested }) {
                failure = DictationFailure.Audio
                val samples = audio.read() ?: break
                synchronized(lock) { if (!valid(token)) return }
                failure = DictationFailure.Engine
                engine.accept(samples)
                val transcript = engine.text()
                synchronized(lock) {
                    if (!valid(token)) return
                    if (!stopRequested) mutableState.value = OfflineDictationState(
                        OfflineDictationPhase.Recording, transcript.text)
                }
                deliverTranscript(token, transcript, finished = false)
            }
            audio.stop()
            synchronized(lock) {
                if (!valid(token)) return
                mutableState.value = OfflineDictationState(OfflineDictationPhase.Finishing,
                    mutableState.value.transcript)
            }
            failure = DictationFailure.Engine
            deliverTranscript(token, engine.finish(), finished = true)
        } catch (_: Exception) {
            reportFailure(token, failure)
        } catch (_: LinkageError) {
            reportFailure(token, DictationFailure.Engine)
        } catch (_: OutOfMemoryError) {
            reportFailure(token, DictationFailure.Engine)
        } finally {
            runCatching { audio?.close() }
            runCatching { engine?.close() }
            synchronized(lock) { if (valid(token)) source = null }
        }
    }

    private fun deliverTranscript(token: Long, transcript: DictationTranscript, finished: Boolean) {
        applyTranscript(token, transcript, { synchronized(lock) { valid(token) } }) { applied ->
            synchronized(lock) {
                if (!valid(token)) return@synchronized
                if (!applied) {
                    generation++
                    stopRequested = true
                    runCatching { source?.stop() }
                    source = null
                    mutableState.value = OfflineDictationState(OfflineDictationPhase.Unavailable,
                        message = failureMessage(DictationFailure.Editor))
                    onAbandon()
                } else if (finished) {
                    mutableState.value = OfflineDictationState(OfflineDictationPhase.Finished, transcript.text)
                }
            }
        }
    }

    private fun reportFailure(token: Long, failure: DictationFailure) = synchronized(lock) {
        if (valid(token)) {
            generation++
            stopRequested = true
            runCatching { source?.stop() }
            source = null
            mutableState.value = OfflineDictationState(
                if (failure == DictationFailure.Engine) OfflineDictationPhase.Unavailable else OfflineDictationPhase.Error,
                message = failureMessage(failure))
            onAbandon()
        }
    }

    override fun stop() {
        val audio = synchronized(lock) {
            if (closed) return
            when (mutableState.value.phase) {
                OfflineDictationPhase.Preparing -> {
                    // Model loading may finish, but can never turn on the mic after this tap.
                    generation++
                    stopRequested = true
                    mutableState.value = OfflineDictationState(OfflineDictationPhase.Finished)
                }
                OfflineDictationPhase.Recording -> {
                    stopRequested = true
                    mutableState.value = mutableState.value.copy(phase = OfflineDictationPhase.Finishing)
                }
                else -> return
            }
            source
        }
        runCatching { audio?.stop() }
    }

    override fun cancel() {
        val audio = synchronized(lock) {
            if (closed) return
            generation++
            stopRequested = true
            mutableState.value = OfflineDictationState()
            source.also { source = null }
        }
        runCatching { audio?.stop() }
        onAbandon()
    }

    override fun close() {
        val audio = synchronized(lock) {
            if (closed) return
            closed = true
            generation++
            stopRequested = true
            mutableState.value = OfflineDictationState(OfflineDictationPhase.Unavailable,
                message = failureMessage(DictationFailure.Editor))
            source.also { source = null }
        }
        runCatching { audio?.stop() }
        onAbandon()
    }

    private companion object {
        // Native model creation cannot be interrupted. Serialize sessions to prevent overlapping
        // model allocations when the user closes/reopens the panel; idle threads expire promptly.
        val sharedWorker: ExecutorService = ThreadPoolExecutor(
            0, 1, 15L, TimeUnit.SECONDS, LinkedBlockingQueue()
        ) { runnable -> Thread(runnable, "axiang-offline-dictation").apply { isDaemon = true } }
    }
}
