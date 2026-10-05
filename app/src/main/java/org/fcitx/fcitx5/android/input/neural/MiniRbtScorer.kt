/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.neural

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.neural.MiniRbtModelStore
import java.util.concurrent.atomic.AtomicBoolean

/** Experimental pretrained MLM scoring. It has not been fine-tuned for typing. */
class MiniRbtScorer(context: Context) : AutoCloseable {
    private val store = MiniRbtModelStore.get(context.applicationContext)
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var handle = 0L
    private var tokenizer: MiniRbtTokenizer? = null

    suspend fun score(contextText: String, candidates: List<String>): List<Double>? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (closed.get()) return@withLock null
                // Never fetch a model from this path. Only use a verified local install.
                val files = store.readyModel() ?: return@withLock null
                currentCoroutineContext().ensureActive()
                if (handle == 0L) {
                    val tokens = MiniRbtTokenizer(files.vocab)
                    val session = MiniRbtNative.create(files.model.absolutePath)
                    if (closed.get()) { MiniRbtNative.destroy(session); return@withLock null }
                    tokenizer = tokens
                    handle = session
                }
                currentCoroutineContext().ensureActive()
                val request = tokenizer!!.batch(contextText, candidates) ?: return@withLock null
                val scores = MiniRbtNative.score(handle, request.inputIds, request.attentionMask,
                    request.tokenTypeIds, request.maskedPositions, request.targetIds,
                    request.batchSize, request.sequenceLength)
                if (closed.get()) null else request.means(scores)
            }
        }

    /** Does not block the keyboard thread while a native inference is finishing. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cleanup.launch {
            mutex.withLock {
                if (handle != 0L) MiniRbtNative.destroy(handle)
                handle = 0L
                tokenizer = null
            }
            cleanup.cancel()
        }
    }
}
