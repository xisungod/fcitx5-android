/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.fcitx.fcitx5.android.core.InputMethodEntry
import java.util.concurrent.atomic.AtomicLong

/** One serialized engine request, bounded by the originating input-view session. */
internal class KeyboardEngineSwitch {
    data class Result(val ime: InputMethodEntry? = null, val pendingInput: Boolean = false)

    private val generation = AtomicLong()
    private var job: Job? = null

    @Synchronized
    fun begin(isSessionCurrent: suspend () -> Boolean): Request {
        cancel()
        return Request(generation.get(), isSessionCurrent)
    }

    @Synchronized
    fun track(request: Request, pendingJob: Job) {
        if (isCurrent(request)) job = pendingJob else pendingJob.cancel()
    }

    @Synchronized
    fun cancel() {
        generation.incrementAndGet()
        job?.cancel()
        job = null
    }

    fun isCurrent(request: Request): Boolean = generation.get() == request.generation

    inner class Request internal constructor(
        internal val generation: Long,
        private val isSessionCurrent: suspend () -> Boolean
    ) {
        private suspend fun checkCurrent() {
            currentCoroutineContext().ensureActive()
            if (!isCurrent(this) || !isSessionCurrent() || !isCurrent(this)) {
                throw CancellationException("The keyboard input session has ended")
            }
            currentCoroutineContext().ensureActive()
        }

        /** Check again after suspension before allowing the next native action. */
        suspend fun <T> call(block: suspend () -> T): T {
            checkCurrent()
            val result = block()
            checkCurrent()
            return result
        }
    }

    suspend fun execute(
        request: Request,
        hasPreedit: suspend () -> Boolean,
        selectEngine: suspend Request.() -> InputMethodEntry?
    ): Result {
        // Selecting a candidate can consume only part of a composition. Never guess or reset it.
        if (request.call(hasPreedit)) return Result(pendingInput = true)
        return Result(ime = request.call { request.selectEngine() })
    }
}
