/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.text.InputType
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.neural.NeuralCandidateCoordinator
import org.fcitx.fcitx5.android.input.neural.RankedCandidateBatch
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class NeuralCandidateCoordinatorTest {
    private val words = arrayOf(CandidateWord("", "你好啊", ""),
        CandidateWord("", "不好啊", "纠错"), CandidateWord("", "几号啊", "纠错"),
        CandidateWord("", "你是啊", ""), CandidateWord("", "不是啊", ""))

    @Test fun plainSpaceAndCommitLockSelectionWhileModifiedSpaceAndReturnRetainNativeHandling() {
        val space = KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_space))
        assertTrue(CommonKeyActionListener.isPlainCandidateSpace(space))
        assertTrue(CommonKeyActionListener.locksCandidateChoice(space))
        assertTrue(CommonKeyActionListener.locksCandidateChoice(KeyAction.CommitAction("，")))
        assertTrue(CommonKeyActionListener.locksCandidateChoice(KeyAction.QuickPhraseAction))
        assertTrue(CommonKeyActionListener.locksCandidateChoice(KeyAction.UnicodeAction))
        listOf(KeyState.Ctrl, KeyState.Alt, KeyState.Shift, KeyState.Super).forEach { modifier ->
            val modified = space.copy(states = KeyStates(KeyState.Virtual, modifier))
            assertFalse(CommonKeyActionListener.isPlainCandidateSpace(modified))
            assertFalse(CommonKeyActionListener.locksCandidateChoice(modified))
        }
        assertFalse(CommonKeyActionListener.locksCandidateChoice(
            KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Return))))
    }

    @Test fun disabledOrMissingModelNeverReadsContextOrLoadsScorer() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var enabled = false
        val coordinator = NeuralCandidateCoordinator(scope, { enabled }, { false },
            { error("A disabled/absent model must not load") }, { error("Must not read editor") }, {})
        coordinator.replace(words)
        coordinator.request("ni hao a", true, InputType.TYPE_CLASS_TEXT)
        enabled = true
        coordinator.request("ni hao a", true, InputType.TYPE_CLASS_TEXT)
        assertFalse(coordinator.current.isReordered)
        coordinator.close(); scope.cancel()
    }

    @Test fun sensitiveNumericEmailAndPrivateEditorsNeverRunInference() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = NeuralCandidateCoordinator(scope, { true }, { true },
            { error("Forbidden model load") }, { error("Forbidden context read") }, {})
        coordinator.replace(words)
        listOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).forEach {
            coordinator.request("nihaoa", true, it)
        }
        coordinator.request("nihaoa", true, InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        coordinator.request("64426", false, InputType.TYPE_CLASS_TEXT)
        coordinator.request("nh", true, InputType.TYPE_CLASS_TEXT)
        coordinator.close(); scope.cancel()
    }

    @Test fun continuingTypingWhileHoldingACandidateStillCannotMoveWordsUnderTheFinger() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = NeuralCandidateCoordinator(scope, { true }, { true },
            { error("Must not load while held") }, { error("Must not read while held") }, {})
        coordinator.replace(words)
        coordinator.interactionStarted()
        coordinator.replace(words.copyOf().also { it[1] = it[1].copy(text = "可以啊") })
        coordinator.request("keyia", true, InputType.TYPE_CLASS_TEXT)
        coordinator.interactionFinished()
        assertFalse(coordinator.current.isReordered)
        coordinator.close(); scope.cancel()
    }

    @Test fun exactNativeFirstAndContextFreeCorrectionsRemainProtected() = runBlocking {
        suspend fun check(firstComment: String, context: String) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val update = CompletableDeferred<RankedCandidateBatch>()
            val closed = CompletableDeferred<Unit>()
            val coordinator = NeuralCandidateCoordinator(scope, { true }, { true }, {
                object : NeuralCandidateCoordinator.Scorer {
                    override suspend fun score(contextText: String, candidates: List<String>) =
                        listOf(-100.0, -100.0, 0.0, -100.0, -100.0)
                    override fun close() { closed.complete(Unit) }
                }
            }, { context }, { update.complete(it) }, debounceMillis = 0)
            coordinator.replace(words.copyOf().also { it[0] = it[0].copy(comment = firstComment) })
            coordinator.request("nihaoa", true, InputType.TYPE_CLASS_TEXT)
            val result = withTimeout(5_000) { update.await() }
            assertEquals(0, result.firstOriginalIndex)
            assertEquals(2, result.originalIndex(1))
            coordinator.close()
            withTimeout(5_000) { closed.await() }
            scope.cancel()
        }
        check("", "刚刚开始聊天")
        check("纠错", "")
    }

    @Test fun lateUncooperativeInferenceCannotReplaceANewerComposition() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val updates = CopyOnWriteArrayList<RankedCandidateBatch>()
        val coordinator = NeuralCandidateCoordinator(scope, { true }, { true }, {
            object : NeuralCandidateCoordinator.Scorer {
                override suspend fun score(contextText: String, candidates: List<String>): List<Double> =
                    withContext(NonCancellable) {
                        entered.complete(Unit); release.await()
                        completed.complete(Unit)
                        listOf(-100.0, -100.0, 0.0, -100.0, -100.0)
                    }
                override fun close() {}
            }
        }, { "我们接着聊天" }, { updates.add(it) }, debounceMillis = 0)
        coordinator.replace(words)
        coordinator.request("nihaoa", true, InputType.TYPE_CLASS_TEXT)
        withTimeout(5_000) { entered.await() }
        val fresh = coordinator.replace(arrayOf(CandidateWord("", "小姑娘", "")))
        release.complete(Unit)
        withTimeout(5_000) { completed.await() }
        delay(25)
        assertEquals(fresh.generation, coordinator.current.generation)
        assertEquals("小姑娘", coordinator.current.words.first().text)
        assertTrue(updates.isEmpty())
        coordinator.close(); scope.cancel()
    }

    @Test fun touchingCandidateFreezesPositionAndCancelsPendingInference() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val updates = CopyOnWriteArrayList<RankedCandidateBatch>()
        val coordinator = NeuralCandidateCoordinator(scope, { true }, { true }, {
            object : NeuralCandidateCoordinator.Scorer {
                override suspend fun score(contextText: String, candidates: List<String>): List<Double> =
                    withContext(NonCancellable) {
                        entered.complete(Unit); release.await(); completed.complete(Unit)
                        listOf(-100.0, -100.0, 0.0, -100.0, -100.0)
                    }
                override fun close() {}
            }
        }, { "我们接着聊天" }, { updates.add(it) }, debounceMillis = 0)
        coordinator.replace(words)
        coordinator.request("nihaoa", true, InputType.TYPE_CLASS_TEXT)
        withTimeout(5_000) { entered.await() }
        coordinator.interactionStarted()
        release.complete(Unit)
        withTimeout(5_000) { completed.await() }
        coordinator.interactionFinished()
        delay(25)
        assertFalse(coordinator.current.isReordered)
        assertTrue(updates.isEmpty())
        coordinator.close(); scope.cancel()
    }

    @Test fun disablingRestoresNativeOrderAndClosesLoadedSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var enabled = true
        val update = CompletableDeferred<RankedCandidateBatch>()
        val closed = CompletableDeferred<Unit>()
        val coordinator = NeuralCandidateCoordinator(scope, { enabled }, { true }, {
            object : NeuralCandidateCoordinator.Scorer {
                override suspend fun score(contextText: String, candidates: List<String>) =
                    listOf(-100.0, -100.0, 0.0, -100.0, -100.0)
                override fun close() { closed.complete(Unit) }
            }
        }, { "我们接着聊天" }, { update.complete(it) }, debounceMillis = 0)
        coordinator.replace(words)
        coordinator.request("nihaoa", true, InputType.TYPE_CLASS_TEXT)
        withTimeout(5_000) { update.await() }
        assertTrue(coordinator.current.isReordered)
        enabled = false
        coordinator.availabilityChanged()
        assertArrayEquals(words, coordinator.current.words)
        assertFalse(coordinator.current.isReordered)
        withTimeout(5_000) { closed.await() }
        coordinator.close(); scope.cancel()
    }

    @Test fun disablingAfterACandidateTouchHasEndedImmediatelyRestoresDisplayedNativeOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var enabled = true
        val update = CompletableDeferred<RankedCandidateBatch>()
        val closed = CompletableDeferred<Unit>()
        val coordinator = NeuralCandidateCoordinator(scope, { enabled }, { true }, {
            object : NeuralCandidateCoordinator.Scorer {
                override suspend fun score(contextText: String, candidates: List<String>) =
                    listOf(-100.0, -100.0, 0.0, -100.0, -100.0)
                override fun close() { closed.complete(Unit) }
            }
        }, { "我们接着聊天" }, { update.complete(it) }, debounceMillis = 0)
        coordinator.replace(words)
        coordinator.request("nihaoa", true, InputType.TYPE_CLASS_TEXT)
        withTimeout(5_000) { update.await() }
        coordinator.interactionStarted()
        coordinator.interactionFinished() // A drag/cancel did not select or replace the composition.
        assertTrue(coordinator.current.isReordered)
        enabled = false
        coordinator.availabilityChanged()
        assertArrayEquals(words, coordinator.current.words)
        assertFalse(coordinator.current.isReordered)
        withTimeout(5_000) { closed.await() }
        coordinator.close(); scope.cancel()
    }
}
