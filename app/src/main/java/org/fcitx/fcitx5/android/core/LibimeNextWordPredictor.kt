/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import androidx.annotation.Keep
import java.io.File

/** Read-only libime prediction. The controller calls this on its own serial worker. */
@Keep
internal class LibimeNextWordPredictor(private val modelFile: File) : AutoCloseable {
    data class Result(
        val candidates: List<String>,
        val elapsedNanos: Long,
        val libraryLoadNanos: Long = 0,
        val initializationNanos: Long = 0,
        val queryNanos: Long = 0,
        val coldInitialization: Boolean = false,
        val available: Boolean = true,
        val failureReason: String? = null
    )

    private var handle = 0L
    private var closed = false

    @Synchronized
    fun query(context: String, maxCandidates: Int = 5): Result {
        val started = System.nanoTime()
        var loadNanos = 0L
        var initializeNanos = 0L
        var nativeNanos = 0L
        var cold = false
        fun result(values: List<String> = emptyList(), available: Boolean = true,
                   reason: String? = null) = Result(values.toList(), System.nanoTime() - started,
            loadNanos, initializeNanos, nativeNanos, cold, available, reason)
        if (closed) return result(available = false, reason = "Closed")
        if (context.isBlank() || maxCandidates !in 1..8) return result()
        // Count code points so supplementary Han characters are never split.
        val count = context.codePointCount(0, context.length)
        val bounded = if (count > 64) context.substring(context.offsetByCodePoints(0, count - 64)) else context
        return try {
            val loading = loadLibrary()
            loadNanos = loading.second
            if (!loading.first) return result(available = false, reason = "LibraryUnavailable")
            if (handle == 0L) {
                cold = true
                val begin = System.nanoTime()
                handle = nativeCreate(modelFile.absolutePath.toByteArray(Charsets.UTF_8))
                initializeNanos = System.nanoTime() - begin
                if (handle == 0L) return result(available = false, reason = nativeStatus())
            }
            val begin = System.nanoTime()
            val output = nativeQuery(handle, bounded.toByteArray(Charsets.UTF_8), maxCandidates)
            nativeNanos = System.nanoTime() - begin
            val status = nativeStatus()
            if (status != "Ready") result(available = false, reason = status)
            else result(output.orEmpty().map { it.toString(Charsets.UTF_8) }.distinct().take(maxCandidates))
        } catch (_: LinkageError) {
            result(available = false, reason = "NativeLinkageFailure")
        } catch (_: Exception) {
            result(available = false, reason = "PredictionFailure")
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val previous = handle
        handle = 0L
        if (previous != 0L) runCatching { nativeDestroy(previous) }
    }

    private external fun nativeCreate(path: ByteArray): Long
    private external fun nativeQuery(handle: Long, context: ByteArray, limit: Int): Array<ByteArray>?
    private external fun nativeStatus(): String
    private external fun nativeDestroy(handle: Long)

    companion object {
        private var libraryLoaded: Boolean? = null
        @Synchronized
        private fun loadLibrary(): Pair<Boolean, Long> {
            libraryLoaded?.let { return it to 0L }
            val started = System.nanoTime()
            val loaded = runCatching { System.loadLibrary("axiangpredict") }.isSuccess
            libraryLoaded = loaded
            return loaded to (System.nanoTime() - started)
        }
    }
}
