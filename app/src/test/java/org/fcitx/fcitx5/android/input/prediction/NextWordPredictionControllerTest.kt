/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.prediction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.core.LibimeNextWordPredictor
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NextWordPredictionControllerTest {
    private fun result(words: List<String> = listOf("天气", "很好"), nanos: Long = 1_000_000,
        cold: Boolean = false, available: Boolean = true) = LibimeNextWordPredictor.Result(words,
        nanos, initializationNanos = if (cold) nanos / 2 else 0,
        queryNanos = if (cold) nanos / 2 else nanos, coldInitialization = cold,
        available = available, failureReason = if (available) null else "LibraryUnavailable")

    private class Fixture(scope: CoroutineScope,
        query: (String, Int) -> LibimeNextWordPredictor.Result,
        warmBudget: Long = 50_000_000, coldBudget: Long = 2_000_000_000,
        onSelected: (() -> Unit)? = null) {
        val editor = Any()
        val connection = Any()
        var env = NextWordPredictionRuntime.Environment(editor, connection, true, true, true, 0, 0)
        val committed = mutableListOf<NextWordPredictionController.Committed>()
        val ready = mutableListOf<NextWordPredictionController.Ready>()
        val drawn = mutableListOf<NextWordPredictionController.Drawn>()
        val selected = mutableListOf<NextWordPredictionController.Selected>()
        val chosen = mutableListOf<NextWordPredictionController.Chosen>()
        val outcomes = mutableListOf<NextWordPredictionController.QueryOutcome>()
        val closeDone = CountDownLatch(1)
        val controller = NextWordPredictionController(scope, { env }, query,
            NextWordPredictionController.Callbacks(
                onCommitted = { committed += it }, onReady = { ready += it },
                onDrawn = { drawn += it }, onSelected = { selected += it; onSelected?.invoke() },
                onChosen = { chosen += it }, onQuery = { outcomes += it }),
            closePredictor = { closeDone.countDown() },
            warmBudgetNanos = warmBudget, coldBudgetNanos = coldBudget).also {
            it.attach(editor, connection)
        }

        fun commit(text: String = "今天", origin: NextWordPredictionOrigin? = null, idle: Boolean = true) {
            val receipt = origin ?: controller.onUserAction()
            val start = env.cursorStart
            val end = start + text.length
            env = env.copy(cursorStart = end, cursorEnd = end, nativeIdle = idle)
            controller.onActualCommitted(receipt, text, start, end, 100)
        }

        fun accept(text: String): Boolean {
            val end = env.cursorStart + text.length
            env = env.copy(cursorStart = end, cursorEnd = end)
            // Match editors which report selection synchronously inside commitText.
            controller.onSelectionChanged(end, end)
            return true
        }

        suspend fun close() {
            controller.close()
            await { closeDone.count == 0L }
        }
    }

    companion object {
        private suspend fun await(condition: () -> Boolean) = withTimeout(5_000) {
            while (!condition()) delay(1)
        }
    }

    @Test fun defaultWorkerQueriesOffTheInputThreadAndOnlyExplicitAcceptedClicksChain() = runBlocking {
        val inputThread = Thread.currentThread()
        val contexts = CopyOnWriteArrayList<String>()
        val queryThreads = CopyOnWriteArrayList<Thread>()
        val fixture = Fixture(this, { text, limit ->
            assertEquals(5, limit)
            contexts += text
            queryThreads += Thread.currentThread()
            result()
        })
        try {
            val originalObservation = Any()
            fixture.commit(origin = fixture.controller.captureOrigin(originalObservation))
            await { fixture.ready.size == 1 }
            val first = fixture.controller.offer.value!!
            assertEquals(1, contexts.size)
            assertTrue(queryThreads.all { it !== inputThread && it.name == "axiang-next-word" })
            fixture.controller.onDrawn(first.token, listOf(-1, 0, 0, 99), 200)
            fixture.controller.onDrawn(first.token, listOf(0, 1), 220)
            assertEquals(listOf(listOf(0), listOf(1)), fixture.drawn.map { it.indices })
            assertSame(originalObservation, fixture.drawn.first().anchor.origin.observation)
            val clickObservation = Any()
            assertTrue(fixture.controller.select(first.token, 0, clickObservation, fixture::accept))
            assertFalse(fixture.controller.select(first.token, 0, clickObservation, fixture::accept))
            await { fixture.ready.size == 2 }
            assertEquals(listOf("今天", "今天天气"), contexts)
            assertEquals(1, fixture.chosen.size)
            assertTrue(fixture.chosen.single().success)
            assertEquals("天气", fixture.committed.last().text)
            assertSame(clickObservation, fixture.committed.last().anchor.origin.observation)
            assertNotEquals(fixture.committed.first().anchor.commitToken, fixture.committed.last().anchor.commitToken)
        } finally { fixture.close() }
    }

    @Test fun failedEditorCommitNeverAppendsContextCountsSuccessOrQueriesAgain() = runBlocking {
        val contexts = CopyOnWriteArrayList<String>()
        val fixture = Fixture(this, { text, _ -> contexts += text; result() })
        try {
            fixture.commit()
            await { fixture.ready.size == 1 }
            val token = fixture.controller.offer.value!!.token
            var editorCalls = 0
            assertFalse(fixture.controller.select(token, 0) { editorCalls++; false })
            assertEquals(1, editorCalls)
            assertEquals(1, fixture.committed.size)
            assertFalse(fixture.chosen.single().success)
            assertNull(fixture.controller.offer.value)
            fixture.controller.refresh()
            delay(5)
            assertEquals(1, contexts.size)
            fixture.commit("天气")
            await { fixture.ready.size == 2 }
            assertEquals(listOf("今天", "天气"), contexts)
        } finally { fixture.close() }
    }

    @Test fun oldSpaceReceiptAfterALaterLetterCannotStartQueryEvenWhenNativeCacheStillLooksIdle() = runBlocking {
        val queries = AtomicInteger()
        val fixture = Fixture(this, { _, _ -> queries.incrementAndGet(); result() })
        try {
            val spaceOrigin = fixture.controller.onUserAction()
            fixture.controller.onUserAction() // New letter already entered the UI queue.
            fixture.commit(origin = spaceOrigin)
            repeat(4) { fixture.controller.refresh() }
            delay(10)
            assertEquals(0, queries.get())
            assertNull(fixture.controller.offer.value)
            assertEquals(1, fixture.committed.size)
            fixture.commit("天气")
            await { fixture.ready.size == 1 }
        } finally { fixture.close() }
    }

    @Test fun inputContinuesWhileNativeQueryIsBlockedAndOnlyTheLatestSerialResultIsPublished() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val contexts = CopyOnWriteArrayList<String>()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val fixture = Fixture(this, { text, _ ->
            val running = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, running) }
            try {
                contexts += text
                if (contexts.size == 1) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                result()
            } finally { active.decrementAndGet() }
        })
        try {
            fixture.commit()
            await { entered.count == 0L }
            val actualKeys = mutableListOf<String>()
            fixture.controller.onUserAction()
            actualKeys += "letter"
            fixture.commit("天气")
            actualKeys += "space"
            assertEquals(listOf("letter", "space"), actualKeys)
            assertNull(fixture.controller.offer.value)
            release.countDown()
            await { fixture.ready.size == 1 && fixture.outcomes.size == 2 }
            assertEquals(1, maximum.get())
            assertEquals(listOf("今天", "今天天气"), contexts)
            assertEquals(listOf(NextWordPredictionController.Outcome.Stale,
                NextWordPredictionController.Outcome.Published), fixture.outcomes.map { it.outcome })
            assertEquals(fixture.committed.last().anchor.commitToken, fixture.ready.single().anchor.commitToken)
        } finally { release.countDown(); fixture.close() }
    }

    @Test fun commitBeforeNativeEmptyEventQueriesOnceWhenIdleAndKeepsReadyOfferAcrossRepeatedRefresh() = runBlocking {
        val queries = AtomicInteger()
        val fixture = Fixture(this, { _, _ -> queries.incrementAndGet(); result() })
        try {
            fixture.commit(idle = false)
            delay(5)
            assertEquals(0, queries.get())
            fixture.env = fixture.env.copy(nativeIdle = true)
            repeat(5) { fixture.controller.refresh() }
            await { fixture.ready.size == 1 }
            val offer = fixture.controller.offer.value
            repeat(5) { fixture.controller.refresh() }
            delay(5)
            assertEquals(1, queries.get())
            assertEquals(offer, fixture.controller.offer.value)
        } finally { fixture.close() }
    }

    @Test fun oneTimeoutDoesNotDisableTheNextCommitAndColdBudgetUsesActualNativeColdFlag() = runBlocking {
        val calls = AtomicInteger()
        val fixture = Fixture(this, { _, _ -> when (calls.incrementAndGet()) {
            1 -> result(nanos = 6_000_000)
            2 -> result(nanos = 6_000_000, cold = true)
            else -> result()
        } }, warmBudget = 5_000_000, coldBudget = 10_000_000)
        try {
            fixture.commit()
            await { fixture.outcomes.size == 1 }
            assertEquals(NextWordPredictionController.Outcome.Timeout, fixture.outcomes.single().outcome)
            assertNull(fixture.controller.offer.value)
            repeat(3) { fixture.controller.refresh() }
            delay(5)
            assertEquals(1, calls.get())
            fixture.commit("天气")
            await { fixture.ready.size == 1 }
            assertTrue(fixture.ready.single().result.coldInitialization)
            assertEquals(3_000_000L, fixture.ready.single().result.initializationNanos)
            fixture.commit("好")
            await { fixture.ready.size == 2 }
            assertFalse(fixture.ready.last().result.coldInitialization)
        } finally { fixture.close() }
    }

    @Test fun unavailabilityDoesNotMakeAnOfferAndTheNextSuccessfulQueryStillWorks() = runBlocking {
        val calls = AtomicInteger()
        val fixture = Fixture(this, { _, _ -> if (calls.incrementAndGet() == 1)
            result(available = false) else result() })
        try {
            fixture.commit()
            await { fixture.outcomes.size == 1 }
            assertEquals(NextWordPredictionController.Outcome.Unavailable, fixture.outcomes.single().outcome)
            assertNull(fixture.controller.offer.value)
            fixture.commit("天气")
            await { fixture.ready.size == 1 }
        } finally { fixture.close() }
    }

    @Test fun hardResetOrPrivateEditorDuringQueryRejectsLateResultAndOldClicks() = runBlocking {
        for (privateEditor in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val fixture = Fixture(this, { _, _ ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                result()
            })
            try {
                fixture.commit()
                await { entered.count == 0L }
                if (privateEditor) fixture.env = fixture.env.copy(eligible = false)
                else fixture.controller.attach(fixture.editor, fixture.connection)
                release.countDown()
                await { fixture.outcomes.size == 1 }
                assertEquals(NextWordPredictionController.Outcome.Stale, fixture.outcomes.single().outcome)
                assertNull(fixture.controller.offer.value)
                var writes = 0
                assertFalse(fixture.controller.select(1, 0) { writes++; true })
                assertEquals(0, writes)
            } finally { release.countDown(); fixture.close() }
        }
    }

    @Test fun selectionObserverCannotCauseACheckedWriteIntoANewField() = runBlocking {
        lateinit var fixture: Fixture
        fixture = Fixture(this, { _, _ -> result() }, onSelected = {
            fixture.env = fixture.env.copy(inputConnectionIdentity = Any())
        })
        try {
            fixture.commit()
            await { fixture.ready.size == 1 }
            val token = fixture.controller.offer.value!!.token
            var writes = 0
            assertFalse(fixture.controller.select(token, 0) { writes++; true })
            assertEquals(0, writes)
            assertFalse(fixture.chosen.single().success)
        } finally { fixture.close() }
    }

    @Test fun drawingAndClicksRecheckCursorAndInvalidIndicesNeverWrite() = runBlocking {
        val fixture = Fixture(this, { _, _ -> result() })
        try {
            fixture.commit()
            await { fixture.ready.size == 1 }
            val token = fixture.controller.offer.value!!.token
            var writes = 0
            assertFalse(fixture.controller.select(token, -1) { writes++; true })
            assertFalse(fixture.controller.select(token, 2) { writes++; true })
            fixture.env = fixture.env.copy(cursorStart = 1, cursorEnd = 1)
            fixture.controller.onDrawn(token, listOf(0), 200)
            assertTrue(fixture.drawn.isEmpty())
            assertFalse(fixture.controller.select(token, 0) { writes++; true })
            assertEquals(0, writes)
        } finally { fixture.close() }
    }

    @Test fun closingDuringAQueryNeverPublishesAndClosesPredictorOnTheSameSerialWorker() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = Fixture(this, { _, _ ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            result()
        })
        try {
            fixture.commit()
            await { entered.count == 0L }
            fixture.controller.close()
            release.countDown()
            await { fixture.closeDone.count == 0L }
            delay(5)
            assertNull(fixture.controller.offer.value)
            assertTrue(fixture.ready.isEmpty())
            fixture.commit("天气")
            assertNull(fixture.controller.offer.value)
        } finally { release.countDown(); fixture.close() }
    }
}
