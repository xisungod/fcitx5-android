/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.voice.BoundedDictationEngineLoader
import org.fcitx.fcitx5.android.input.voice.DictationSpeechEngine
import org.fcitx.fcitx5.android.input.voice.DictationTranscript
import org.fcitx.fcitx5.android.input.voice.PendingDictationEngine
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BoundedDictationEngineLoaderTest {
    private class Engine : DictationSpeechEngine {
        val closes = AtomicInteger()
        override fun accept(samples: FloatArray) = Unit
        override fun text() = DictationTranscript()
        override fun finish() = DictationTranscript(complete = true)
        override fun close() { closes.incrementAndGet() }
    }

    /** Hold the worker after a result is ready but before it can take the next task. */
    private class HandoffWorker : ThreadPoolExecutor(
        0, 1, 1L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(1)
    ) {
        val resultReady = CountDownLatch(1)
        val releaseHandoff = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        private val completed = AtomicInteger()
        override fun afterExecute(task: Runnable, error: Throwable?) {
            super.afterExecute(task, error)
            when (completed.incrementAndGet()) {
                1 -> {
                    resultReady.countDown()
                    releaseHandoff.await(3, TimeUnit.SECONDS)
                }
                2 -> secondFinished.countDown()
            }
        }
    }

    @Test fun completedResultAcceptsOneNextLoadBeforeItsWorkerReturnsToTheQueue() {
        val worker = HandoffWorker()
        val loader = BoundedDictationEngineLoader(worker)
        val firstEngine = Engine()
        val secondEngine = Engine()
        val secondStarts = AtomicInteger()
        val first = PendingDictationEngine { firstEngine }
        val second = PendingDictationEngine { secondStarts.incrementAndGet(); secondEngine }
        val third = PendingDictationEngine { fail("A third task must not be queued"); Engine() }
        try {
            loader.execute(first)
            assertTrue(worker.resultReady.await(3, TimeUnit.SECONDS))
            assertTrue(first.isDone)
            // A zero-capacity SynchronousQueue rejects here despite completed construction.
            loader.execute(second)
            assertEquals(0, secondStarts.get())
            assertEquals(1, worker.queue.size)
            try {
                loader.execute(third)
                fail("The single pending handoff slot must stay bounded")
            } catch (_: RejectedExecutionException) { }
            worker.releaseHandoff.countDown()
            assertTrue(worker.secondFinished.await(3, TimeUnit.SECONDS))
            assertEquals(1, secondStarts.get())
            assertSame(secondEngine, second.await { true })
            secondEngine.close()
            first.abandon()
            assertEquals(1, firstEngine.closes.get())
            assertEquals(1, secondEngine.closes.get())
        } finally {
            first.abandon(); second.abandon(); third.abandon()
            worker.releaseHandoff.countDown(); worker.shutdownNow()
        }
    }

    @Test fun cancellingAQueuedHandoffDoesNotConstructAnUnwantedNativeModel() {
        val worker = HandoffWorker()
        val loader = BoundedDictationEngineLoader(worker)
        val first = PendingDictationEngine { Engine() }
        val unwantedStarts = AtomicInteger()
        val second = PendingDictationEngine { unwantedStarts.incrementAndGet(); Engine() }
        try {
            loader.execute(first)
            assertTrue(worker.resultReady.await(3, TimeUnit.SECONDS))
            loader.execute(second)
            second.abandon()
            worker.releaseHandoff.countDown()
            assertTrue(worker.secondFinished.await(3, TimeUnit.SECONDS))
            assertTrue(second.isDone)
            assertEquals(0, unwantedStarts.get())
        } finally {
            first.abandon(); second.abandon()
            worker.releaseHandoff.countDown(); worker.shutdownNow()
        }
    }
}
