/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Native construction cannot be interrupted. A cancelled session leaves ownership here,
 * so its worker can return immediately and the loader closes the late result exactly once.
 */
internal class PendingDictationEngine(factory: () -> DictationSpeechEngine) : Runnable {
    private val lock = Any()
    private var abandoned = false
    private var claimed = false
    private val future = object : FutureTask<DictationSpeechEngine>({ factory() }) {
        override fun done() = releaseAbandoned()
    }

    val isDone: Boolean get() = future.isDone
    override fun run() = future.run()

    /** A short wait lets cancellation return without waiting for native construction. */
    fun await(stillCurrent: () -> Boolean): DictationSpeechEngine? {
        while (stillCurrent()) {
            val result = try {
                future.get(50, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                continue
            }
            return synchronized(lock) {
                if (abandoned || !stillCurrent()) null
                else result.also { claimed = true }
            }
        }
        return null
    }

    fun abandon() {
        synchronized(lock) { abandoned = true }
        releaseAbandoned()
    }

    private fun releaseAbandoned() {
        val result = synchronized(lock) {
            if (!abandoned || claimed || !future.isDone) return
            claimed = true
            try { future.get() } catch (_: Throwable) { null }
        }
        try { result?.close() } catch (_: Throwable) {}
    }
}
