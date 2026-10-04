/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig

/** "OnlineRecognizer" is Sherpa's streaming API; provider=cpu never uses a network. */
internal class SherpaDictationSpeechEngine(assets: AssetManager) : DictationSpeechEngine {
    private val recognizer = OnlineRecognizer(assets, OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$MODEL_PATH/encoder.int8.onnx",
                decoder = "$MODEL_PATH/decoder.onnx",
                joiner = "$MODEL_PATH/joiner.int8.onnx"
            ),
            tokens = "$MODEL_PATH/tokens.txt",
            numThreads = 2,
            debug = false,
            provider = "cpu",
            modelType = "zipformer"
        ),
        enableEndpoint = true,
        decodingMethod = "greedy_search"
    ))
    private val stream = try {
        recognizer.createStream()
    } catch (error: Throwable) {
        recognizer.release()
        throw error
    }
    private val punctuation = try {
        OfflinePunctuation(assets, OfflinePunctuationConfig(OfflinePunctuationModelConfig(
            ctTransformer = "asr/punctuation/model.int8.onnx", numThreads = 1, provider = "cpu")))
    } catch (error: Throwable) {
        stream.release()
        recognizer.release()
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
        try { stream.release() } finally {
            try { recognizer.release() } finally { punctuation.release() }
        }
    }

    companion object {
        const val MODEL_PATH = "asr/zh-14m"
        val MODEL_FILES = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")
    }
}
