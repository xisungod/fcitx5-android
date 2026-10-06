/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.core.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PinyinTapFeedbackTransactionTest {
    private val editor = Any()
    private val evidence = PinyinTapEvidence(TapEvidence('b', 98f, 40f, 1f), listOf(
        KeyCell('b', 0f, 0f, 100f, 100f), KeyCell('n', 100f, 0f, 200f, 100f)))

    private fun correction(runtime: PinyinTapRuntime): Long {
        val sequence = runtime.nextAction()
        runtime.recordCorrection(sequence, editor, "ji", "jin",
            PinyinSpatialKeyDecider.Decision('b', 'n', .05f, .95f,
                reason = PinyinSpatialKeyDecider.Reason.BoundaryCorrection),
            KeyStates.Virtual.states, 48, evidence)
        return sequence
    }

    /** Actual queue behavior; every guard/cache read and key mutation asserts native-thread affinity. */
    private class NativeQueue : FcitxAPI by unusedApi(), AutoCloseable {
        @Volatile
        private var nativeQueueThread: Thread? = null
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-native-queue").also { nativeQueueThread = it }
        }.asCoroutineDispatcher()
        val callers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var spelling = "jin"
        var nativeDeletesCommittedText = 0
        val calls = mutableListOf<String>()
        var onBackspace: (() -> Unit)? = null

        fun assertNativeThread() { assertSame(nativeQueueThread, Thread.currentThread()) }

        private fun nativeThread() { assertNativeThread() }

        override val inputPanelCached: FcitxEvent.InputPanelEvent.Data
            get() {
                nativeThread()
                calls += "read:$spelling"
                return FcitxEvent.InputPanelEvent.Data(
                    FormattedText(arrayOf(spelling), intArrayOf(0), spelling.length),
                    FormattedText.Empty, FormattedText.Empty, emptyArray())
            }

        override suspend fun <T> withInputTransaction(block: suspend FcitxAPI.() -> T): T =
            withContext(dispatcher) { block(this@NativeQueue) }

        override suspend fun sendKey(sym: KeySym, states: KeyStates, code: Int, up: Boolean, timestamp: Int) {
            withContext(dispatcher) {
                nativeThread()
                check(sym.sym == FcitxKeyMapping.FcitxKey_BackSpace)
                calls += "backspace"
                if (spelling.isEmpty()) nativeDeletesCommittedText++ else spelling = spelling.dropLast(1)
                onBackspace?.invoke()
            }
        }

        override suspend fun sendKey(c: Char, states: UInt, code: Int, up: Boolean, timestamp: Int) {
            withContext(dispatcher) {
                nativeThread()
                calls += "key:$c"
                spelling += c
            }
        }

        override suspend fun select(idx: Int): Boolean = withContext(dispatcher) {
            nativeThread()
            calls += "candidate-commit:$spelling"
            spelling = ""
            true
        }

        override fun close() { callers.cancel(); dispatcher.close() }

        companion object {
            private fun unusedApi(): FcitxAPI = Proxy.newProxyInstance(
                FcitxAPI::class.java.classLoader, arrayOf(FcitxAPI::class.java)
            ) { _, method, _ -> error("Unexpected native call: ${method.name}") } as FcitxAPI
        }
    }

    @Test fun candidateQueuedBeforeRestoreIsObservedBeforeTheGuardAndNoKeyIsSent() = runBlocking {
        NativeQueue().use { api ->
            val runtime = PinyinTapRuntime()
            val token = correction(runtime)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blocker = api.callers.launch(api.dispatcher) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val selection = api.callers.async(start = CoroutineStart.UNDISPATCHED) { api.select(0) }
            val restore = api.callers.async(start = CoroutineStart.UNDISPATCHED) {
                api.resolvePinyinTapFeedback(runtime, token, editor, true) { true }
            }
            release.countDown()
            blocker.join()
            selection.await()
            assertNull(restore.await())
            assertEquals(listOf("candidate-commit:jin", "read:"), api.calls)
            assertEquals(0, api.nativeDeletesCommittedText)
        }
    }

    @Test fun candidateEnqueuedDuringBackspaceCannotCommitBetweenTheReplacementKeys() = runBlocking {
        NativeQueue().use { api ->
            val runtime = PinyinTapRuntime()
            val token = correction(runtime)
            var selection: Deferred<Boolean>? = null
            api.onBackspace = {
                selection = api.callers.async(start = CoroutineStart.UNDISPATCHED) { api.select(0) }
            }
            val pending = api.resolvePinyinTapFeedback(runtime, token, editor, true) { true }
            assertNotNull(pending)
            selection!!.await()
            assertEquals(listOf("read:jin", "backspace", "key:b", "candidate-commit:jib"), api.calls)
            assertEquals(0, api.nativeDeletesCommittedText)
        }
    }

    @Test fun confirmationReadsNativeCompositionWithoutDeletingOrAddingAnyKey() = runBlocking {
        NativeQueue().use { api ->
            val runtime = PinyinTapRuntime()
            val token = correction(runtime)
            assertNotNull(api.resolvePinyinTapFeedback(runtime, token, editor, false) { true })
            assertEquals(listOf("read:jin"), api.calls)
            assertEquals("jin", api.spelling)
            assertNull(runtime.feedback.value)
        }
    }

    @Test fun revokedContextIsCheckedOnNativeQueueBeforeReadingOrMutating() = runBlocking {
        NativeQueue().use { api ->
            val runtime = PinyinTapRuntime()
            val token = correction(runtime)
            assertNull(api.resolvePinyinTapFeedback(runtime, token, editor, true) {
                api.assertNativeThread()
                false
            })
            assertTrue(api.calls.isEmpty())
            assertEquals("jin", api.spelling)
            assertNull(runtime.feedback.value)
        }
    }
}
