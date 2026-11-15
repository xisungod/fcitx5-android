/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice

import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One native construction at a time, with one slot for a completed task's thread handoff. */
internal class BoundedDictationEngineLoader(
    private val executor: Executor = ThreadPoolExecutor(
        0, 1, 15L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(1)
    ) { runnable -> Thread(runnable, "axiang-offline-dictation-load").apply { isDaemon = true } }
) : Executor {
    private val lock = Any()
    private var inFlight: PendingDictationEngine? = null

    override fun execute(command: Runnable) {
        require(command is PendingDictationEngine)
        synchronized(lock) {
            val previous = inFlight
            if (previous != null && !previous.isDone)
                throw RejectedExecutionException("A native dictation load is still pending")
            inFlight = command
            try {
                // Future completion precedes the pool worker returning to its queue. Keep
                // that tiny handoff queued instead of falsely reporting a busy native load.
                executor.execute {
                    try {
                        command.run()
                    } finally {
                        synchronized(lock) {
                            // Do not retain a completed Future and its editor transcript,
                            // or clear the next task accepted during this task's handoff.
                            if (inFlight === command) inFlight = null
                        }
                    }
                }
            } catch (error: RuntimeException) {
                inFlight = previous
                throw error
            }
        }
    }
}
