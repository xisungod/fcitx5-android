/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.voice.IdleDictationModelCache
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class IdleDictationModelCacheTest {
    @Test fun reacquiringCancelsIdleUnloadAndOnlyTheLastUserCanReleaseModels() {
        val scheduler = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
        val creates = AtomicInteger()
        val closes = AtomicInteger()
        val closed = CountDownLatch(1)
        val cache = IdleDictationModelCache({ creates.incrementAndGet(); Any() },
            { closes.incrementAndGet(); closed.countDown() }, scheduler, idleMillis = 150L)
        try {
            val first = cache.acquire()
            cache.release()
            assertSame(first, cache.acquire())
            assertSame(first, cache.acquire())
            cache.release()
            assertFalse("The remaining stream still owns the models", closed.await(250, TimeUnit.MILLISECONDS))
            assertEquals(1, creates.get())
            cache.release()
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertEquals(1, closes.get())
        } finally { scheduler.shutdownNow() }
    }

    @Test fun memoryPressureReturnsImmediatelyDuringLoadAndUnloadsAfterTheActiveStreamEnds() {
        val scheduler = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
        val loader = Executors.newSingleThreadExecutor()
        val ui = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closes = AtomicInteger()
        val cache = IdleDictationModelCache({ entered.countDown(); release.await(); Any() },
            { closes.incrementAndGet(); closed.countDown() }, scheduler, idleMillis = 120_000L)
        try {
            val model = loader.submit<Any> { cache.acquire() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            // A low-memory callback cannot wait behind a slow native constructor on the UI thread.
            ui.submit { cache.trim() }.get(500, TimeUnit.MILLISECONDS)
            release.countDown()
            assertNotNull(model.get(2, TimeUnit.SECONDS))
            scheduler.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(1L, closed.count)
            cache.release()
            assertTrue("Memory pressure bypasses the two-minute cache", closed.await(2, TimeUnit.SECONDS))
            assertEquals(1, closes.get())
        } finally {
            release.countDown(); scheduler.shutdownNow(); loader.shutdownNow(); ui.shutdownNow()
        }
    }
}
