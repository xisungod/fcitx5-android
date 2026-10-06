/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

data class TouchDiagnosticStatus(
    val files: Int = 0,
    val records: Int = 0,
    val bytes: Int = 0,
    val droppedRecords: Long = 0,
    val failed: Boolean = false
)

/** Bounded nonblocking append queue; management work uses ordered barriers. */
internal class TouchDiagnosticWriter(
    private val files: TouchDiagnosticFiles,
    private val queueCapacity: Int = 128,
    private val beforeAppend: (() -> Unit)? = null
) {
    private data class Work(val append: Boolean, val run: () -> Unit)
    private val lock = Object()
    private val queue = ArrayDeque<Work>()
    private var closed = false
    private val epoch = AtomicLong()
    private val dropped = AtomicLong()
    private val mutableStatus = MutableStateFlow(TouchDiagnosticStatus())
    val status = mutableStatus.asStateFlow()
    private val worker = Thread({
        while (true) {
            val work = synchronized(lock) {
                while (queue.isEmpty() && !closed) lock.wait()
                if (queue.isEmpty() && closed) return@Thread
                queue.removeFirst()
            }
            work.run()
        }
    }, "axiang-touch-diagnostics").apply { isDaemon = true; start() }

    init {
        synchronized(lock) {
            queue.addLast(Work(false) {
                try { refresh() }
                catch (_: Exception) { mutableStatus.value = mutableStatus.value.copy(failed = true) }
            })
            lock.notifyAll()
        }
    }

    fun rejectedRecord() { dropped.incrementAndGet() }

    fun append(bytes: ByteArray, valid: () -> Boolean): Boolean = synchronized(lock) {
        if (closed || queue.size >= queueCapacity) {
            dropped.incrementAndGet()
            return false
        }
        val version = epoch.get()
        queue.addLast(Work(true) {
            try {
                beforeAppend?.invoke()
                // Clear/editor changes revoke work even if it has already left the queue.
                if (epoch.get() == version && valid() && files.append(bytes)) refresh()
            } catch (_: Exception) {
                mutableStatus.value = mutableStatus.value.copy(failed = true,
                    droppedRecords = dropped.incrementAndGet())
            }
        })
        lock.notifyAll()
        true
    }

    private fun refresh() {
        mutableStatus.value = files.status(dropped.get())
    }

    private suspend fun <T> barrier(action: () -> T): T {
        val result = CompletableDeferred<T>()
        synchronized(lock) {
            if (closed) result.completeExceptionally(IllegalStateException("Diagnostic writer closed"))
            else {
                if (queue.size >= queueCapacity) {
                    val iterator = queue.iterator()
                    while (iterator.hasNext()) {
                        if (iterator.next().append) {
                            iterator.remove(); dropped.incrementAndGet(); break
                        }
                    }
                }
                if (queue.size >= queueCapacity) {
                    result.completeExceptionally(IllegalStateException("Diagnostic controls busy"))
                } else {
                    queue.addLast(Work(false) {
                        try { result.complete(action()) }
                        catch (error: Exception) { result.completeExceptionally(error) }
                    })
                    lock.notifyAll()
                }
            }
        }
        return result.await()
    }

    suspend fun snapshot(): TouchDiagnosticSnapshot = barrier {
        files.snapshot(epoch.get(), dropped.get()).also { refresh() }
    }

    suspend fun clear() {
        // Immediate revocation happens before the ordered delete; queued old records cannot return.
        epoch.incrementAndGet()
        barrier { files.clear(); dropped.set(0); refresh() }
    }

    suspend fun export(snapshot: TouchDiagnosticSnapshot, output: OutputStream) = barrier {
        check(snapshot.epoch == epoch.get()) { "Diagnostic preview was cleared; preview again" }
        snapshot.writeTo(output)
        output.flush()
    }

    internal suspend fun close() {
        barrier { }
        synchronized(lock) { closed = true; lock.notifyAll() }
    }
}
