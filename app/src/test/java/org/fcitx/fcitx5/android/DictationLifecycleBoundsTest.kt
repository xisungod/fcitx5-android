/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.input.voice.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DictationLifecycleBoundsTest {
    private class Audio : DictationAudioSource {
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val chunks = LinkedBlockingQueue<FloatArray>()
        @Volatile var stopped = false
        override fun start() { started.countDown() }
        override fun read() = chunks.take().takeUnless { stopped }
        override fun stop() { stopped = true; chunks.offer(FloatArray(0)) }
        override fun close() { stop(); closed.countDown() }
    }

    private class Engine : DictationSpeechEngine {
        val closed = CountDownLatch(1)
        val closes = AtomicInteger()
        override fun accept(samples: FloatArray) = Unit
        override fun text() = DictationTranscript(partial = "还在说话")
        override fun finish() = DictationTranscript(committed = "还在说话。", complete = true)
        override fun close() { closes.incrementAndGet(); closed.countDown() }
    }

    private fun waitFor(session: OfflineDictationSession, phase: OfflineDictationPhase) = runBlocking {
        withTimeout(3_000) { session.state.first { it.phase == phase } }
    }

    @Test fun cancelDuringNativeConstructionFreesTheWorkerAndClosesTheLateResultOnce() {
        val worker = Executors.newSingleThreadExecutor()
        val loader = Executors.newSingleThreadExecutor()
        val timers = ScheduledThreadPoolExecutor(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = Engine()
        val audio = Audio()
        val writes = AtomicInteger()
        val session = LocalOfflineDictationSession(null, { true }, {}, { audio }, {
            entered.countDown(); release.await(); engine
        }, { _, _, current, applied ->
            if (current()) writes.incrementAndGet()
            applied(current())
        }, {}, { it.name }, worker, captureWhileLoading = true,
            engineLoader = loader, timers = timers)
        try {
            session.start()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            session.cancel()
            assertTrue(audio.stopped)
            // This cannot finish if cancellation is still waiting on native construction.
            assertEquals("free", worker.submit<String> { "free" }.get(1, TimeUnit.SECONDS))
            assertTrue(audio.closed.await(1, TimeUnit.SECONDS))
            assertEquals(0, engine.closes.get())
            release.countDown()
            assertTrue(engine.closed.await(3, TimeUnit.SECONDS))
            loader.submit {}.get(1, TimeUnit.SECONDS)
            assertEquals(1, engine.closes.get())
            assertEquals(0, writes.get())
            assertEquals(OfflineDictationPhase.Ready, session.state.value.phase)
        } finally {
            release.countDown(); session.close()
            worker.shutdownNow(); loader.shutdownNow(); timers.shutdownNow()
        }
    }

    @Test fun nativeLoadDeadlineStopsAudioAndRejectsOverlappingLoadsUntilTheOldOneReturns() {
        val worker = Executors.newSingleThreadExecutor()
        val loader = ThreadPoolExecutor(0, 1, 1L, TimeUnit.SECONDS, SynchronousQueue<Runnable>())
        val timers = ScheduledThreadPoolExecutor(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        val engine = Engine()
        val firstAudio = Audio()
        val secondAudio = Audio()
        fun create(audio: Audio) = LocalOfflineDictationSession(null, { true }, {}, { audio }, {
            attempts.incrementAndGet(); entered.countDown(); release.await(); engine
        }, { _, _, current, applied -> applied(current()) }, {}, { it.name }, worker,
            captureWhileLoading = true, engineLoader = loader, timers = timers, loadTimeoutMillis = 100L)
        val first = create(firstAudio)
        val second = create(secondAudio)
        try {
            first.start()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val timeout = waitFor(first, OfflineDictationPhase.Error)
            assertEquals("LoadTimeout", timeout.message)
            assertTrue(firstAudio.stopped)
            assertEquals("free", worker.submit<String> { "free" }.get(1, TimeUnit.SECONDS))
            second.start()
            assertEquals("LoadBusy", waitFor(second, OfflineDictationPhase.Error).message)
            assertTrue(secondAudio.closed.await(1, TimeUnit.SECONDS))
            assertEquals("There is only one native model allocation", 1, attempts.get())
            release.countDown()
            assertTrue(engine.closed.await(3, TimeUnit.SECONDS))
            assertEquals(1, engine.closes.get())
            assertEquals("", first.state.value.transcript)
            assertEquals("", second.state.value.transcript)
        } finally {
            release.countDown(); first.close(); second.close()
            worker.shutdownNow(); loader.shutdownNow(); timers.shutdownNow()
        }
    }

    @Test fun repeatedAudioActivityDoesNotNeedNewWordsOrChangingLoudness() {
        val audio = Audio()
        val engine = Engine()
        val session = LocalOfflineDictationSession(null, { true }, {}, { audio }, { engine },
            { _, _, current, applied -> applied(current()) }, {}, { it.name })
        try {
            session.start()
            waitFor(session, OfflineDictationPhase.Recording)
            audio.chunks.offer(FloatArray(1_600) { 0.1f })
            runBlocking { withTimeout(3_000) { session.audioActivity.first { it >= 1 } } }
            audio.chunks.offer(FloatArray(1_600) { 0.1f })
            runBlocking { withTimeout(3_000) { session.audioActivity.first { it >= 2 } } }
            assertTrue(session.level.value > 0f)
        } finally { session.close() }
        assertTrue(engine.closed.await(3, TimeUnit.SECONDS))
    }

    @Test fun silenceDeadlineFollowsAudioAndDoesNotCountColdLoadingAsSilence() {
        val policy = DictationSilencePolicy(0L)
        val loading = OfflineDictationState(OfflineDictationPhase.Recording, loadingModel = true)
        policy.onState(loading, 0L)
        assertFalse(policy.shouldStop(loading, 20_000L))
        val recording = loading.copy(loadingModel = false)
        policy.onState(recording, 20_000L)
        assertFalse(policy.shouldStop(recording, 34_000L))
        policy.onAudioActivity(1L, 34_000L)
        policy.onAudioActivity(2L, 44_000L)
        assertFalse(policy.shouldStop(recording, 58_000L))
        assertTrue(policy.shouldStop(recording, 59_000L))
        assertFalse(policy.shouldStop(recording.copy(phase = OfflineDictationPhase.Finishing), 90_000L))
    }
}
