/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** Only in-memory PCM buffers; no audio file, upload, foreground service or wake lock. */
internal class AndroidDictationAudioSource : DictationAudioSource {
    private val lock = Any()
    @Volatile private var stopped = false
    private var recorder: AudioRecord? = null
    private val pcm = ShortArray(1_600)

    @SuppressLint("MissingPermission")
    override fun start() = synchronized(lock) {
        check(!stopped)
        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0)
        val instance = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum * 2, 32_000))
        recorder = instance
        check(instance.state == AudioRecord.STATE_INITIALIZED)
        instance.startRecording()
        check(instance.recordingState == AudioRecord.RECORDSTATE_RECORDING)
    }

    override fun read(): FloatArray? {
        if (stopped) return null
        val instance = synchronized(lock) { recorder } ?: return null
        val count = instance.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
        if (stopped) return null
        check(count > 0) { "Audio capture failed" }
        return FloatArray(count) { pcm[it] / 32768f }
    }

    override fun stop() = synchronized(lock) {
        stopped = true
        recorder?.let { instance ->
            if (instance.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { instance.stop() }
            }
        }
        Unit
    }

    override fun close() = synchronized(lock) {
        stop()
        recorder?.release()
        recorder = null
    }
}
