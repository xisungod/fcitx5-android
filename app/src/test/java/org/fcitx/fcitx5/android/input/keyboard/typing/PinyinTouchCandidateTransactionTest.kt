/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PinyinTouchCandidateTransactionTest {
    private val offer = PinyinTouchCandidateOffer(9, "经常会", "jibgchsnghui", "jingchanghui")

    private fun reservePromotedFirst(runtime: PinyinTouchCandidateRuntime, editor: Any)
        : PinyinTouchCandidateRuntime.Reserved {
        val model = listOf(File("src/main/assets/typing/pinyin_touch_model.tsv"),
            File("app/src/main/assets/typing/pinyin_touch_model.tsv")).first { it.isFile }
            .reader().use(PinyinTouchLanguageModel::parse)
        val rows = listOf("qwertyuiop" to 0f, "asdfghjkl" to 50f, "zxcvbnm" to 150f)
        val cells = rows.flatMapIndexed { row, (letters, left) -> letters.mapIndexed { index, letter ->
            KeyCell(letter, left + index * 100f, row * 140f,
                left + (index + 1) * 100f, (row + 1) * 140f)
        } }
        val raw = "giren"
        val offsets = mapOf('i' to CenterOffset(.12f, 0f), 'u' to CenterOffset(.12f, 0f))
        val tracker = runtime.tracker(model)
        var proposal: PinyinMultiPathProposal? = null
        for (index in raw.indices) {
            val cell = cells.first { it.letter == raw[index] }
            val downX = if (index == 1) cell.left + .25f else cell.centerX
            proposal = tracker.recordTap(runtime.nextAction(), editor, raw.take(index), index,
                raw.take(index + 1), index + 1,
                PinyinTapEvidence(TapEvidence(raw[index], downX, cell.centerY, 1f), cells), offsets)
        }
        assertTrue(runtime.publish(proposal!!, editor, "古人", raw, raw.length,
            originalCandidates = listOf("一人", "古人", "此人"), allowFirstPromotion = true))
        val token = runtime.offer.value!!.token
        return runtime.reserve(token, editor, requirePromotedFirst = true)!!
    }

    private class NativeQueue(initial: String) : FcitxAPI by unusedApi(), AutoCloseable {
        private var owner: Thread? = null
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-native-queue").also { owner = it }
        }.asCoroutineDispatcher()
        var spelling = initial
        var cursor: Int? = null
        var clientPreedit = initial
        var candidates = arrayOf(CandidateWord("", "原来的候选", ""), CandidateWord("", "经常会", ""))
        val operations = mutableListOf<String>()
        val committed = mutableListOf<String>()
        var partialSelection = false
        var selectSucceeded = true
        var onReset: (() -> Unit)? = null
        var onKey: ((Char) -> Unit)? = null
        var onCandidates: (() -> Unit)? = null

        private fun assertOwner() = assertSame(owner, Thread.currentThread())

        override val inputPanelCached: FcitxEvent.InputPanelEvent.Data
            get() {
                assertOwner()
                return FcitxEvent.InputPanelEvent.Data(
                    FormattedText(arrayOf(spelling), intArrayOf(0), cursor ?: spelling.length),
                    FormattedText.Empty, FormattedText.Empty, emptyArray())
            }
        override val clientPreeditCached: FormattedText
            get() {
                assertOwner()
                return FormattedText(arrayOf(clientPreedit), intArrayOf(0), clientPreedit.length)
            }
        override suspend fun <T> withInputTransaction(block: suspend FcitxAPI.() -> T): T =
            withContext(dispatcher) { block(this@NativeQueue) }
        override suspend fun reset() {
            assertOwner()
            operations += "reset"
            spelling = ""
            clientPreedit = ""
            cursor = null
            onReset?.invoke()
        }
        override suspend fun sendKey(key: String, states: UInt, code: Int, up: Boolean, timestamp: Int) {
            assertOwner()
            assertEquals(1, key.length)
            val letter = key.single()
            assertTrue(letter in 'a'..'z')
            assertEquals(KeyStates.Virtual.states, states)
            assertEquals(ScancodeMapping.charToScancode(letter), code)
            assertFalse(up)
            operations += "key:$letter"
            spelling += letter
            clientPreedit = spelling
            onKey?.invoke(letter)
        }
        override suspend fun getCandidates(offset: Int, limit: Int): Array<CandidateWord> {
            assertOwner()
            assertEquals(0, offset)
            assertEquals(32, limit)
            operations += "candidates"
            onCandidates?.invoke()
            return candidates
        }
        override suspend fun select(idx: Int): Boolean {
            assertOwner()
            operations += "select:$idx"
            if (selectSucceeded && !partialSelection) {
                committed += candidates[idx].text
                spelling = ""
                clientPreedit = ""
            } else if (partialSelection) {
                clientPreedit = candidates[idx].text + " hui"
            }
            return selectSucceeded
        }
        override fun close() = dispatcher.close()
        companion object {
            private fun unusedApi(): FcitxAPI = Proxy.newProxyInstance(
                FcitxAPI::class.java.classLoader, arrayOf(FcitxAPI::class.java)
            ) { _, method, _ -> error("Unexpected native call: ${method.name}") } as FcitxAPI
        }
    }

    @Test fun exactExplicitChoiceCommitsOnceThroughNormalCandidateSelection() = runBlocking {
        NativeQueue(offer.originalSpelling).use { api ->
            val selected = api.withInputTransaction { resolvePinyinTouchCandidate(offer) { true } }
            assertTrue(selected)
            assertEquals(listOf("经常会"), api.committed)
            assertEquals("", api.spelling)
            assertEquals(listOf("reset") + offer.alternativeSpelling.map { "key:$it" } +
                listOf("candidates", "select:1"), api.operations)
        }
    }

    @Test fun queuedFirstChoiceCommitsBeforeALaterLetterEvenWhenThatLetterAlreadyAdvancedUiGeneration() = runBlocking {
        val runtime = PinyinTouchCandidateRuntime()
        val editor = Any()
        NativeQueue("giren").use { api ->
            api.candidates = arrayOf(CandidateWord("", "别的词", ""), CandidateWord("", "古人", ""))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val pausedNative = launch(start = CoroutineStart.UNDISPATCHED) {
                api.withInputTransaction {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                }
            }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                // The already-visible first choice is fixed at the Space/Return/tap action.
                val reserved = reservePromotedFirst(runtime, editor)
                val selection = async(start = CoroutineStart.UNDISPATCHED) {
                    api.withInputTransaction { resolveReservedPinyinTouchCandidate(reserved, editor) { true } }
                }
                // This UI event occurs while both native actions still wait behind the gate.
                val nextLetterSequence = runtime.nextAction()
                assertFalse(runtime.isCurrent(reserved.offer.token))
                val nextLetter = async(start = CoroutineStart.UNDISPATCHED) {
                    api.withInputTransaction {
                        val before = inputPanelCached.preedit
                        assertTrue(before.isEmpty())
                        sendKey("n", KeyStates.Virtual.states, ScancodeMapping.charToScancode('n'))
                        val after = inputPanelCached.preedit
                        val n = KeyCell('n', 0f, 0f, 100f, 140f)
                        val model = listOf(File("src/main/assets/typing/pinyin_touch_model.tsv"),
                            File("app/src/main/assets/typing/pinyin_touch_model.tsv")).first { it.isFile }
                            .reader().use(PinyinTouchLanguageModel::parse)
                        runtime.tracker(model).recordTap(nextLetterSequence, editor,
                            before.toString(), before.cursor, after.toString(), after.cursor,
                            PinyinTapEvidence(TapEvidence('n', n.centerX, n.centerY, 1f), listOf(n)),
                            searchImmediately = false)
                        assertEquals("n", runtime.tracker(model).rawSpelling)
                    }
                }
                release.countDown()
                pausedNative.join()
                assertTrue(selection.await())
                nextLetter.await()
                assertEquals(listOf("古人"), api.committed)
                assertEquals("n", api.spelling)
                assertEquals(1, api.operations.count { it.startsWith("select:") })
                assertEquals(listOf("reset") + "guren".map { "key:$it" } +
                    listOf("candidates", "select:1", "key:n"), api.operations)
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun reservedChoiceRequiresExactEditorPreeditCaretAndUnrevokedContext() = runBlocking {
        for (mode in 0..4) {
            val runtime = PinyinTouchCandidateRuntime()
            val editor = Any()
            val reserved = reservePromotedFirst(runtime, editor)
            runtime.nextAction() // Later ordinary input generation is not an editor/context revocation.
            NativeQueue("giren").use { api ->
                when (mode) {
                    2 -> api.spelling = "nihao"
                    3 -> api.cursor = 2
                    4 -> api.spelling = "gi ren" // Same letters, a different published preedit snapshot.
                }
                assertFalse(api.withInputTransaction {
                    resolveReservedPinyinTouchCandidate(reserved, if (mode == 1) Any() else editor) { mode != 0 }
                })
                assertTrue("mode=$mode", api.operations.isEmpty())
                assertTrue(api.committed.isEmpty())
            }
        }
    }

    @Test fun absentExactCandidateRestoresRawWithoutCommittingOrSelectingAnything() = runBlocking {
        NativeQueue(offer.originalSpelling).use { api ->
            api.candidates = arrayOf(CandidateWord("", "经常", ""))
            assertFalse(api.withInputTransaction { resolvePinyinTouchCandidate(offer) { true } })
            assertEquals(offer.originalSpelling, api.spelling)
            assertTrue(api.committed.isEmpty())
            assertEquals(2, api.operations.count { it == "reset" })
            assertFalse(api.operations.any { it.startsWith("select:") })
        }
    }

    @Test fun partialCandidateIsDiscardedAndOriginalLiteralSpellingIsRestored() = runBlocking {
        NativeQueue(offer.originalSpelling).use { api ->
            api.partialSelection = true
            assertFalse(api.withInputTransaction { resolvePinyinTouchCandidate(offer) { true } })
            assertEquals(offer.originalSpelling, api.spelling)
            assertEquals(offer.originalSpelling, api.clientPreedit)
            assertTrue(api.committed.isEmpty())
            assertEquals(1, api.operations.count { it.startsWith("select:") })
        }
    }

    @Test fun refusedNativeSelectionRestoresOriginalInsteadOfForcingReturnOrSpace() = runBlocking {
        NativeQueue(offer.originalSpelling).use { api ->
            api.selectSucceeded = false
            assertFalse(api.withInputTransaction { resolvePinyinTouchCandidate(offer) { true } })
            assertEquals(offer.originalSpelling, api.spelling)
            assertTrue(api.committed.isEmpty())
        }
    }

    @Test fun initialRevocationOrDifferentRawOrMovedCursorPerformsNoNativeMutation() = runBlocking {
        for (mode in 0..2) NativeQueue(offer.originalSpelling).use { api ->
            if (mode == 1) api.spelling = "nihao"
            if (mode == 2) api.cursor = 2
            assertFalse(api.withInputTransaction { resolvePinyinTouchCandidate(offer) { mode != 0 } })
            assertTrue(api.operations.isEmpty())
            assertTrue(api.committed.isEmpty())
        }
    }

    @Test fun revocationAfterReplayRestoresRawWithoutLookingUpOrSelectingCandidate() = runBlocking {
        NativeQueue(offer.originalSpelling).use { api ->
            var valid = true
            api.onKey = { valid = false }
            assertFalse(api.withInputTransaction { resolvePinyinTouchCandidate(offer) { valid } })
            assertEquals(offer.originalSpelling, api.spelling)
            assertFalse(api.operations.any { it == "candidates" || it.startsWith("select:") })
            assertTrue(api.committed.isEmpty())
        }
    }

    @Test fun revocationDuringLookupCannotCommitCandidate() = runBlocking {
        NativeQueue(offer.originalSpelling).use { api ->
            var valid = true
            api.onCandidates = { valid = false }
            assertFalse(api.withInputTransaction { resolvePinyinTouchCandidate(offer) { valid } })
            assertEquals(offer.originalSpelling, api.spelling)
            assertFalse(api.operations.any { it.startsWith("select:") })
            assertTrue(api.committed.isEmpty())
        }
    }

    @Test fun resetAndReplayUiEventsCannotInvalidateAnAlreadyClaimedOffer() = runBlocking {
        val runtime = PinyinTouchCandidateRuntime()
        val editor = Any()
        val model = listOf(File("src/main/assets/typing/pinyin_touch_model.tsv"),
            File("app/src/main/assets/typing/pinyin_touch_model.tsv")).first { it.isFile }
            .reader().use(PinyinTouchLanguageModel::parse)
        val rows = listOf("qwertyuiop" to 0f, "asdfghjkl" to 50f, "zxcvbnm" to 150f)
        val cells = rows.flatMapIndexed { row, (letters, left) -> letters.mapIndexed { i, letter ->
            KeyCell(letter, left + i * 100f, row * 140f, left + (i + 1) * 100f, (row + 1) * 140f)
        } }
        val tracker = runtime.tracker(model)
        var proposal: PinyinMultiPathProposal? = null
        for (i in offer.originalSpelling.indices) {
            val letter = offer.originalSpelling[i]
            val cell = cells.first { it.letter == letter }
            val x = when (letter) { 'b' -> cell.right - .25f; 's' -> cell.left + .25f; else -> cell.centerX }
            proposal = tracker.recordTap(runtime.nextAction(), editor, offer.originalSpelling.take(i), i,
                offer.originalSpelling.take(i + 1), i + 1,
                PinyinTapEvidence(TapEvidence(letter, x, cell.centerY, 1f), cells))
        }
        assertTrue(runtime.publish(proposal!!, editor, offer.text, offer.originalSpelling, offer.originalSpelling.length))
        val token = runtime.offer.value!!.token
        val claimed = runtime.claim(token, editor, offer.originalSpelling, offer.originalSpelling.length)!!
        NativeQueue(offer.originalSpelling).use { api ->
            fun selfUiEvent() {
                if (runtime.offer.value != null) runtime.clear()
                assertTrue(runtime.isCurrent(token))
            }
            api.onReset = ::selfUiEvent
            api.onKey = { selfUiEvent() }
            assertTrue(api.withInputTransaction {
                resolvePinyinTouchCandidate(claimed.offer) { runtime.isCurrent(token) }
            })
            assertEquals(listOf(offer.text), api.committed)
        }
    }
}
