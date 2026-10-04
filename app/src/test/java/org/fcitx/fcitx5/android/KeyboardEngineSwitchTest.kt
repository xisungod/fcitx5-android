/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.input.keyboard.KeyboardEngineSwitch
import org.junit.Assert.*
import org.junit.Test

class KeyboardEngineSwitchTest {
    private val selected = InputMethodEntry("rime")

    @Test
    fun pendingCompositionIsPreservedWithoutSelectingAnyCandidateOrResetting() = runBlocking {
        // Covers both a partial first candidate and a composition with no selectable candidate.
        for (availableCandidate in listOf("你", null)) {
            val controller = KeyboardEngineSwitch()
            val request = controller.begin { true }
            var preedit = "nihao"
            val mutations = mutableListOf<String>()
            val result = controller.execute(request, hasPreedit = { preedit.isNotEmpty() }) {
                mutations += "select $availableCandidate"
                preedit = ""
                mutations += "reset and activate"
                selected
            }
            assertTrue(result.pendingInput)
            assertNull(result.ime)
            assertEquals("nihao", preedit)
            assertTrue("An incomplete composition must never reach a mutating engine operation", mutations.isEmpty())
        }
    }

    @Test
    fun endingTheSessionWhileItsJobIsQueuedPreventsEvenTheFirstNativeRead() = runBlocking {
        val controller = KeyboardEngineSwitch()
        val request = controller.begin { true }
        var nativeCalls = 0
        val pending = launch(start = CoroutineStart.LAZY) {
            controller.execute(request, hasPreedit = { nativeCalls++; false }) {
                nativeCalls++
                selected
            }
        }
        controller.track(request, pending)
        controller.cancel()
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(0, nativeCalls)
    }

    @Test
    fun endingTheSessionDuringANativeReadPreventsTheFollowingSchemaMutation() = runBlocking {
        val controller = KeyboardEngineSwitch()
        val request = controller.begin { true }
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.execute(request, hasPreedit = { false }) {
                call {
                    calls += "read current menu"
                    readStarted.complete(Unit)
                    releaseRead.await()
                }
                call { calls += "activate schema" }
                selected
            }
        }
        controller.track(request, pending)
        readStarted.await()
        controller.cancel()
        releaseRead.complete(Unit)
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(listOf("read current menu"), calls)
    }

    @Test
    fun replacingARequestCancelsItsOldContinuationButTheNewRequestCanComplete() = runBlocking {
        val controller = KeyboardEngineSwitch()
        val first = controller.begin { true }
        val waiting = CompletableDeferred<Unit>()
        var staleActivations = 0
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.execute(first, hasPreedit = { false }) {
                call { waiting.await() }
                call { staleActivations++ }
                selected
            }
        }
        controller.track(first, pending)
        val second = controller.begin { true }
        waiting.complete(Unit)
        pending.join()
        assertFalse(controller.isCurrent(first))
        assertTrue(pending.isCancelled)
        val calls = mutableListOf<String>()
        val result = controller.execute(second, hasPreedit = { false }) {
            call { calls += "enable" }
            call { calls += "activate" }
            selected
        }
        assertEquals(0, staleActivations)
        assertEquals(listOf("enable", "activate"), calls)
        assertSame(selected, result.ime)
        assertFalse(result.pendingInput)
    }

    @Test
    fun changingTheInputConnectionDuringASuspendedReadBlocksTheNextActionEvenWithoutAnExplicitCancel() = runBlocking {
        val controller = KeyboardEngineSwitch()
        val originalConnection = Any()
        var connection = originalConnection
        val request = controller.begin { connection === originalConnection }
        val releaseRead = CompletableDeferred<Unit>()
        var activations = 0
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.execute(request, hasPreedit = { false }) {
                call { releaseRead.await() }
                call { activations++ }
                selected
            }
        }
        controller.track(request, pending)
        connection = Any()
        releaseRead.complete(Unit)
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(0, activations)
    }

    @Test
    fun anInvalidatedUntrackedRequestCannotStartAndMissingSchemaDoesNotPretendToSucceed() = runBlocking {
        val controller = KeyboardEngineSwitch()
        val stale = controller.begin { true }
        controller.cancel()
        var calls = 0
        try {
            controller.execute(stale, hasPreedit = { calls++; false }) { selected }
            fail("A stale request must not execute")
        } catch (_: CancellationException) {
            assertEquals(0, calls)
        }
        val current = controller.begin { true }
        val result = controller.execute(current, hasPreedit = { false }) { null }
        assertNull(result.ime)
        assertFalse(result.pendingInput)
    }
}
