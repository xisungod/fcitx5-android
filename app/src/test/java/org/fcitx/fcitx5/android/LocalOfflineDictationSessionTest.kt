/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.input.voice.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LocalOfflineDictationSessionTest {
    private class Audio : DictationAudioSource {
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val chunks = LinkedBlockingQueue<FloatArray>()
        @Volatile var stopped = false
        override fun start() { check(!stopped); started.countDown() }
        override fun read(): FloatArray? = chunks.take().takeUnless { stopped }
        override fun stop() { stopped = true; chunks.offer(FloatArray(0)) }
        override fun close() { stop(); closed.countDown() }
    }

    private class Engine : DictationSpeechEngine {
        val closed = CountDownLatch(1)
        val accepted = CountDownLatch(1)
        val finished = AtomicInteger()
        var acceptBlock: (() -> Unit)? = null
        var finalText = "你好，阿翔"
        override fun accept(samples: FloatArray) { acceptBlock?.invoke(); accepted.countDown() }
        override fun text() = DictationTranscript(partial = "你好")
        override fun finish(): DictationTranscript { finished.incrementAndGet(); return DictationTranscript(committed = finalText.trim(), complete = true) }
        override fun close() { closed.countDown() }
    }

    private fun waitFor(session: OfflineDictationSession, phase: OfflineDictationPhase) = runBlocking {
        withTimeout(3_000) { session.state.first { it.phase == phase } }
    }

    private fun session(
        audio: () -> DictationAudioSource = { Audio() },
        engine: () -> DictationSpeechEngine = { Engine() },
        permission: () -> Boolean = { true },
        request: () -> Unit = {},
        unavailable: String? = null,
        apply: DictationTranscriptCallback = { _, _, current, delivered -> delivered(current()) },
        abandon: () -> Unit = {}
    ) = LocalOfflineDictationSession(unavailable, permission, request, audio, engine, apply, abandon, failureMessage = { it.name })

    @Test fun openingThePanelDoesNotLoadTheModelOrCaptureAudio() {
        val calls = AtomicInteger()
        session(audio = { calls.incrementAndGet(); Audio() }, engine = { calls.incrementAndGet(); Engine() }).use {
            assertEquals(OfflineDictationPhase.Ready, it.state.value.phase)
            assertEquals(0, calls.get())
        }
    }

    @Test fun unsupportedDeviceCannotStartEvenWithPermission() {
        val calls = AtomicInteger()
        session(unavailable = "unsupported", engine = { calls.incrementAndGet(); Engine() }).use {
            it.start()
            assertEquals(OfflineDictationPhase.Unavailable, it.state.value.phase)
            assertEquals("unsupported", it.state.value.message)
            assertEquals(0, calls.get())
        }
    }

    @Test fun deniedPermissionRequestsAccessWithoutStartingAudioOrLoadingModel() {
        val requested = AtomicInteger()
        val engines = AtomicInteger()
        session(permission = { false }, request = { requested.incrementAndGet() },
            engine = { engines.incrementAndGet(); Engine() }).use {
            it.start()
            assertEquals(OfflineDictationPhase.Error, it.state.value.phase)
            assertEquals("Permission", it.state.value.message)
            assertEquals(1, requested.get())
            assertEquals(0, engines.get())
        }
    }

    @Test fun cancellationDuringSlowModelLoadNeverOpensTheMicrophone() {
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = Engine()
        val microphones = AtomicInteger()
        val session = session(audio = { microphones.incrementAndGet(); Audio() }, engine = {
            loading.countDown(); release.await(3, TimeUnit.SECONDS); engine
        })
        try {
            session.start()
            assertTrue(loading.await(3, TimeUnit.SECONDS))
            session.cancel()
            release.countDown()
            assertTrue(engine.closed.await(3, TimeUnit.SECONDS))
            assertEquals(0, microphones.get())
            assertEquals(OfflineDictationPhase.Ready, session.state.value.phase)
        } finally { release.countDown(); session.close() }
    }

    @Test fun stopDuringPreparationCannotStartMicrophoneLater() {
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = Engine()
        val microphones = AtomicInteger()
        val session = session(audio = { microphones.incrementAndGet(); Audio() }, engine = {
            loading.countDown(); release.await(3, TimeUnit.SECONDS); engine
        })
        try {
            session.start()
            assertTrue(loading.await(3, TimeUnit.SECONDS))
            session.stop()
            assertEquals(OfflineDictationPhase.Finished, session.state.value.phase)
            release.countDown()
            assertTrue(engine.closed.await(3, TimeUnit.SECONDS))
            assertEquals(0, microphones.get())
            assertEquals(0, engine.finished.get())
        } finally { release.countDown(); session.close() }
    }

    @Test fun stopUnblocksAudioAndDeliversPartialThenPunctuatedFinalText() {
        val audio = Audio()
        val engine = Engine()
        val commits = mutableListOf<String>()
        session(audio = { audio }, engine = { engine }, apply = { _, result, current, delivered ->
            if (current()) { commits.add(result.text); delivered(true) } else delivered(false)
        }).use {
            it.start()
            waitFor(it, OfflineDictationPhase.Recording)
            audio.chunks.offer(FloatArray(1_600) { 0.1f })
            assertTrue(engine.accepted.await(3, TimeUnit.SECONDS))
            it.stop()
            val result = waitFor(it, OfflineDictationPhase.Finished)
            assertEquals("你好，阿翔", result.transcript)
            assertTrue(audio.stopped)
            assertTrue(audio.closed.await(3, TimeUnit.SECONDS))
            assertTrue(engine.closed.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("你好", "你好，阿翔"), commits)
            assertEquals(1, engine.finished.get())
        }
    }

    @Test fun cancelDuringDecodingSuppressesLateTextAndFinalization() {
        val enteredDecode = CountDownLatch(1)
        val release = CountDownLatch(1)
        val audio = Audio()
        val engine = Engine().apply {
            acceptBlock = { enteredDecode.countDown(); release.await(3, TimeUnit.SECONDS) }
        }
        val session = session(audio = { audio }, engine = { engine })
        try {
            session.start()
            waitFor(session, OfflineDictationPhase.Recording)
            audio.chunks.offer(FloatArray(1_600))
            assertTrue(enteredDecode.await(3, TimeUnit.SECONDS))
            session.cancel()
            assertTrue(audio.stopped)
            release.countDown()
            assertTrue(engine.closed.await(3, TimeUnit.SECONDS))
            assertEquals(OfflineDictationPhase.Ready, session.state.value.phase)
            assertEquals("", session.state.value.transcript)
            assertEquals(0, engine.finished.get())
        } finally { release.countDown(); session.close() }
    }

    @Test fun closeInvalidatesARecordingSessionAndReleasesBothResources() {
        val audio = Audio()
        val engine = Engine()
        val session = session(audio = { audio }, engine = { engine })
        session.start()
        waitFor(session, OfflineDictationPhase.Recording)
        session.close()
        assertTrue(audio.stopped)
        assertTrue(audio.closed.await(3, TimeUnit.SECONDS))
        assertTrue(engine.closed.await(3, TimeUnit.SECONDS))
        assertEquals(0, engine.finished.get())
        session.start()
    }

    @Test fun changedEditorRejectsFinishedTextAndStopsSession() {
        val audio = Audio()
        val engine = Engine()
        session(audio = { audio }, engine = { engine }, apply = { _, _, _, delivered -> delivered(false) }).use {
            it.start()
            waitFor(it, OfflineDictationPhase.Recording)
            it.stop()
            waitFor(it, OfflineDictationPhase.Unavailable)
            assertEquals(OfflineDictationPhase.Unavailable, it.state.value.phase)
            assertEquals("Editor", it.state.value.message)
        }
    }

    @Test fun microphoneStartFailureReleasesLoadedModelAndReturnsActionableError() {
        val closed = CountDownLatch(1)
        val engine = Engine()
        val audio = object : DictationAudioSource {
            override fun start() { error("microphone is in use") }
            override fun read(): FloatArray? = null
            override fun stop() = Unit
            override fun close() { closed.countDown() }
        }
        session(audio = { audio }, engine = { engine }).use {
            it.start()
            val result = waitFor(it, OfflineDictationPhase.Error)
            assertEquals("Audio", result.message)
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            assertTrue(engine.closed.await(3, TimeUnit.SECONDS))
        }
    }

    @Test fun emptyRecognitionFinishesWithoutInventingWords() {
        val audio = Audio()
        val engine = Engine().apply { finalText = "  " }
        val commits = AtomicInteger()
        session(audio = { audio }, engine = { engine }, apply = { _, result, current, delivered ->
            if (result.text.isNotEmpty()) commits.incrementAndGet()
            delivered(current())
        }).use {
            it.start()
            waitFor(it, OfflineDictationPhase.Recording)
            it.stop()
            waitFor(it, OfflineDictationPhase.Finished)
            assertEquals(0, commits.get())
        }
    }
    @Test fun queuedPartialAndFinalCallbacksCannotWriteAfterSessionCloses() {
        val audio = Audio()
        val engine = Engine()
        val pending = LinkedBlockingQueue<() -> Unit>()
        val displayed = mutableListOf<String>()
        val session = session(audio = { audio }, engine = { engine },
            apply = { _, text, current, delivered ->
                pending.offer {
                    if (current()) { displayed.add(text.text); delivered(true) }
                    else delivered(false)
                }
            })
        try {
            session.start()
            waitFor(session, OfflineDictationPhase.Recording)
            audio.chunks.offer(FloatArray(1_600))
            assertTrue(engine.accepted.await(3, TimeUnit.SECONDS))
            val partial = pending.poll(3, TimeUnit.SECONDS)
            assertNotNull(partial)
            session.stop()
            val final = pending.poll(3, TimeUnit.SECONDS)
            assertNotNull(final)
            assertTrue(audio.stopped)
            // Simulates queued main-thread work after a hardware edit, hide or focus change.
            session.close()
            partial!!.invoke()
            final!!.invoke()
            assertTrue(displayed.isEmpty())
            assertEquals(OfflineDictationPhase.Unavailable, session.state.value.phase)
        } finally { session.close() }
    }

}
