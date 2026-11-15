/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

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
    private val worker: ExecutorService = sharedWorker,
    /**
     * Open the microphone before the models are ready and decode the captured audio once
     * they are, so a cold engine does not swallow the first words. Off by default: the
     * original order never records while a slow load can still be cancelled.
     */
    private val captureWhileLoading: Boolean = false,
    private val engineLoader: Executor = sharedEngineLoader,
    private val timers: ScheduledExecutorService = loadTimers,
    private val loadTimeoutMillis: Long = 20_000L
) : OfflineDictationSession {
    private val lock = Any()
    private val mutableState = MutableStateFlow(
        if (unavailableMessage == null) OfflineDictationState()
        else OfflineDictationState(OfflineDictationPhase.Unavailable, message = unavailableMessage)
    )
    override val state: StateFlow<OfflineDictationState> = mutableState.asStateFlow()
    private val mutableLevel = MutableStateFlow(0f)
    override val level: StateFlow<Float> = mutableLevel.asStateFlow()
    private val mutableAudioActivity = MutableStateFlow(0L)
    override val audioActivity: StateFlow<Long> = mutableAudioActivity.asStateFlow()
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

    /** Loudness of one chunk on a perceptual scale: about -50 dBFS is silence, -12 dBFS is full. */
    private fun publishLevel(samples: FloatArray) {
        if (samples.isEmpty()) return
        var energy = 0.0
        for (sample in samples) energy += sample.toDouble() * sample
        val decibels = 20.0 * log10(sqrt(energy / samples.size) + 1e-7)
        mutableLevel.value = ((decibels + 50.0) / 38.0).coerceIn(0.0, 1.0).toFloat()
        // An activity counter also changes for steady speech with unchanged loudness/text.
        // This is an energy gate, not a linguistic VAD: it never retains microphone samples.
        if (decibels >= -42.0) mutableAudioActivity.value++
    }

    private fun recognize(token: Long) {
        var engine: DictationSpeechEngine? = null
        var audio: DictationAudioSource? = null
        var loading: PendingDictationEngine? = null
        var timeout: ScheduledFuture<*>? = null
        var failure = DictationFailure.Engine
        try {
            synchronized(lock) { if (!valid(token) || stopRequested) return }
            if (captureWhileLoading) {
                failure = DictationFailure.Audio
                audio = openAudio(token, loadingModel = true) ?: return
            }
            failure = DictationFailure.Engine
            val pending = PendingDictationEngine(engineFactory).also { loading = it }
            try {
                engineLoader.execute(pending)
            } catch (_: RejectedExecutionException) {
                reportFailure(token, DictationFailure.LoadBusy)
                return
            }
            timeout = timers.schedule({
                // Also unblocks a microphone read when the native load makes no progress.
                if (!pending.isDone) reportFailure(token, DictationFailure.LoadTimeout)
            }, loadTimeoutMillis, TimeUnit.MILLISECONDS)
            val backlog = ArrayDeque<FloatArray>()
            var captured = 0
            if (captureWhileLoading) {
                while (!pending.isDone && synchronized(lock) { valid(token) && !stopRequested }) {
                    failure = DictationFailure.Audio
                    val samples = audio!!.read() ?: break
                    synchronized(lock) { if (!valid(token)) return }
                    publishLevel(samples)
                    backlog.addLast(samples)
                    captured += samples.size
                    while (captured > MAX_BACKLOG_SAMPLES && backlog.size > 1)
                        captured -= backlog.removeFirst().size
                }
            }
            failure = DictationFailure.Engine
            engine = pending.await { synchronized(lock) { valid(token) } } ?: return
            timeout.cancel(false)
            synchronized(lock) {
                if (!valid(token) || (!captureWhileLoading && stopRequested)) return
                mutableState.value = mutableState.value.copy(loadingModel = false)
            }
            if (!captureWhileLoading) {
                failure = DictationFailure.Audio
                audio = openAudio(token) ?: return
            }
            failure = DictationFailure.Engine
            var decodedBacklog = false
            while (backlog.isNotEmpty()) {
                engine.accept(backlog.removeFirst())
                decodedBacklog = true
                synchronized(lock) { if (!valid(token)) return }
            }
            if (decodedBacklog) publishTranscript(token, engine.text())
            while (synchronized(lock) { valid(token) && !stopRequested }) {
                failure = DictationFailure.Audio
                val samples = audio!!.read() ?: break
                synchronized(lock) { if (!valid(token)) return }
                publishLevel(samples)
                failure = DictationFailure.Engine
                engine.accept(samples)
                publishTranscript(token, engine.text())
            }
            audio?.stop()
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
            releaseRun(token, audio, engine, loading, timeout)
        }
    }

    private fun openAudio(token: Long, loadingModel: Boolean = false): DictationAudioSource? {
        val audio = audioFactory()
        synchronized(lock) {
            if (!valid(token) || stopRequested) {
                closeAudio(audio)
                return null
            }
            source = audio
        }
        try {
            audio.start()
            synchronized(lock) {
                if (!valid(token) || stopRequested) {
                    closeAudio(audio)
                    return null
                }
                mutableState.value = OfflineDictationState(OfflineDictationPhase.Recording, loadingModel = loadingModel)
            }
            return audio
        } catch (error: Throwable) {
            closeAudio(audio)
            throw error
        }
    }

    private fun closeAudio(audio: DictationAudioSource?) {
        try { audio?.close() } catch (_: Throwable) {}
        synchronized(lock) { if (source === audio) source = null }
    }

    private fun publishTranscript(token: Long, transcript: DictationTranscript) {
        synchronized(lock) {
            if (!valid(token)) return
            if (!stopRequested) mutableState.value = OfflineDictationState(
                OfflineDictationPhase.Recording, transcript.text)
        }
        deliverTranscript(token, transcript, finished = false)
    }

    /** Keep exception handling outside inlined synchronized returns, and never await a late load. */
    private fun releaseRun(
        token: Long, audio: DictationAudioSource?, engine: DictationSpeechEngine?,
        pendingEngine: PendingDictationEngine?, timeout: ScheduledFuture<*>?
    ) {
        timeout?.cancel(false)
        synchronized(lock) { if (valid(token)) mutableLevel.value = 0f }
        closeAudio(audio)
        try { engine?.close() } catch (_: Throwable) {}
        pendingEngine?.abandon()
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
            mutableLevel.value = 0f
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
            mutableLevel.value = 0f
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
            mutableLevel.value = 0f
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
        // Thirty seconds at 16 kHz.
        const val MAX_BACKLOG_SAMPLES = 480_000
        // Native construction is bounded separately: cancel/timeout frees the recognition
        // worker, while a late load owns and closes its result. A stuck load rejects retries
        // rather than accumulating threads or parallel native model allocations.
        val sharedEngineLoader: Executor = ThreadPoolExecutor(
            0, 1, 15L, TimeUnit.SECONDS, SynchronousQueue()
        ) { runnable -> Thread(runnable, "axiang-offline-dictation-load").apply { isDaemon = true } }
        val loadTimers = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "axiang-dictation-load-timeout").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(15L, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }
        // Decoding still runs on one worker and releases it promptly after cancellation.
        val sharedWorker: ExecutorService = ThreadPoolExecutor(
            0, 1, 15L, TimeUnit.SECONDS, LinkedBlockingQueue()
        ) { runnable -> Thread(runnable, "axiang-offline-dictation").apply { isDaemon = true } }
    }
}
