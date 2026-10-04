/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.flow.StateFlow

enum class OfflineDictationPhase { Ready, Preparing, Recording, Finishing, Finished, Unavailable, Error }

data class OfflineDictationState(
    val phase: OfflineDictationPhase = OfflineDictationPhase.Ready,
    val transcript: String = "",
    val message: String? = null
)

/** A single editor-bound session. No audio is captured until [start] is explicitly called. */
interface OfflineDictationSession : AutoCloseable {
    val state: StateFlow<OfflineDictationState>
    fun start()
    fun stop()
    fun cancel()
    override fun close()
}

/** Completed sentences have local punctuation; only the current partial sentence may change. */
internal data class DictationTranscript(val committed: String = "", val partial: String = "", val complete: Boolean = false) {
    val text: String get() = committed + partial
}

internal typealias DictationTranscriptCallback =
    (run: Long, transcript: DictationTranscript, stillCurrent: () -> Boolean, applied: (Boolean) -> Unit) -> Unit

internal interface DictationAudioSource : AutoCloseable {
    fun start()
    /** null means the microphone stopped; returned samples are mono PCM at 16 kHz. */
    fun read(): FloatArray?
    /** Must unblock [read]; safe before start and from another thread. */
    fun stop()
}

internal interface DictationSpeechEngine : AutoCloseable {
    fun accept(samples: FloatArray)
    fun text(): DictationTranscript
    fun finish(): DictationTranscript
}

internal enum class DictationFailure { Permission, Audio, Engine, Editor }
