/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import android.content.res.AssetManager
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig

/**
 * The recognizer and punctuation models stay loaded for a while after a dictation ends, so the
 * next tap on the microphone starts listening at once instead of reloading about 100 MB.
 * Each dictation owns only its stream. Memory is given back after two minutes without use, or earlier on memory pressure.
 */
internal object SherpaModels {
    class Loaded(val recognizer: OnlineRecognizer, val punctuation: OfflinePunctuation)

    private val scheduler = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "axiang-dictation-model-cache").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setKeepAliveTime(15L, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }
    // Application assets are immutable for this process. Initialization does not load models.
    private var cache: IdleDictationModelCache<Loaded>? = null
    private val cacheLock = Any()

    /** "OnlineRecognizer" is Sherpa's streaming API; provider=cpu never uses a network. */
    private fun load(assets: AssetManager): Loaded {
        val recognizer = OnlineRecognizer(assets, OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "${SherpaDictationSpeechEngine.MODEL_PATH}/encoder.int8.onnx",
                    decoder = "${SherpaDictationSpeechEngine.MODEL_PATH}/decoder.onnx",
                    joiner = "${SherpaDictationSpeechEngine.MODEL_PATH}/joiner.int8.onnx"
                ),
                tokens = "${SherpaDictationSpeechEngine.MODEL_PATH}/tokens.txt",
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "zipformer"
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search"
        ))
        val punctuation = try {
            OfflinePunctuation(assets, OfflinePunctuationConfig(OfflinePunctuationModelConfig(
                ctTransformer = "asr/punctuation/model.int8.onnx", numThreads = 1, provider = "cpu")))
        } catch (error: Throwable) {
            recognizer.release()
            throw error
        }
        return Loaded(recognizer, punctuation)
    }

    /** Blocks only the dedicated native loader, never the UI or cancellation worker. */
    fun acquire(assets: AssetManager): Loaded {
        val current = synchronized(cacheLock) {
            cache ?: IdleDictationModelCache(
                create = { load(assets) },
                destroy = { idle ->
                    try { idle.recognizer.release() } finally { idle.punctuation.release() }
                },
                scheduler = scheduler
            ).also { cache = it }
        }
        return current.acquire()
    }

    fun release() = synchronized(cacheLock) { cache }?.release() ?: Unit

    /** Active streams keep ownership; low memory releases models as soon as they become idle. */
    fun trim() = synchronized(cacheLock) { cache }?.trim() ?: Unit

}

internal class SherpaDictationSpeechEngine(assets: AssetManager) : DictationSpeechEngine {
    private val models = SherpaModels.acquire(assets)
    private val recognizer = models.recognizer
    private val punctuation = models.punctuation
    private val stream = try {
        recognizer.createStream()
    } catch (error: Throwable) {
        SherpaModels.release()
        throw error
    }
    private val completed = StringBuilder()
    private var closed = false
    private var finished = false

    override fun accept(samples: FloatArray) {
        check(!closed && !finished)
        stream.acceptWaveform(samples, 16_000)
        decodeAvailable()
        if (recognizer.isEndpoint(stream)) {
            finishSentence()
            recognizer.reset(stream)
        }
    }

    private fun decodeAvailable() {
        while (recognizer.isReady(stream)) recognizer.decode(stream)
    }

    private fun finishSentence() {
        val sentence = recognizer.getResult(stream).text.trim()
        if (sentence.isNotBlank()) completed.append(punctuation.addPunctuation(sentence).trim())
    }

    override fun text() = DictationTranscript(completed.toString(),
        if (finished) "" else recognizer.getResult(stream).text.trim(), complete = finished)

    override fun finish(): DictationTranscript {
        check(!closed)
        if (!finished) {
            // Give the streaming encoder its final right-context frames before draining it.
            stream.acceptWaveform(FloatArray(8_000), 16_000)
            stream.inputFinished()
            decodeAvailable()
            finishSentence()
            finished = true
        }
        return text()
    }

    override fun close() {
        if (closed) return
        closed = true
        try { stream.release() } finally { SherpaModels.release() }
    }

    companion object {
        const val MODEL_PATH = "asr/zh-14m"
        val MODEL_FILES = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")
    }
}
