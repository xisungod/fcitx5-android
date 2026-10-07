/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.prediction

import org.junit.Assert.*
import org.junit.Test

class NextWordPredictionRuntimeTest {
    private val editor = Any()
    private val connection = Any()
    private fun environment(cursor: Int, idle: Boolean = true) =
        NextWordPredictionRuntime.Environment(editor, connection, true, true, idle, cursor, cursor)
    private fun runtime() = NextWordPredictionRuntime().also { it.attach(editor, connection) }
    private fun commit(runtime: NextWordPredictionRuntime, text: String, start: Int,
        origin: NextWordPredictionOrigin = runtime.captureOrigin(), idle: Boolean = true) =
        runtime.onActualCommitted(origin, text, start, start + text.length, 100L, environment(start + text.length, idle))!!

    @Test fun earlierQueuedCommitExtendsContextButCannotResurrectAnOfferAfterNextLetter() {
        val runtime = runtime()
        val oldSpace = runtime.onUserAction()
        val nextLetter = runtime.onUserAction()
        val first = commit(runtime, "今天", 0, oldSpace)
        assertNull(first.query)
        assertEquals("今天", runtime.contextSnapshot())
        assertNull(runtime.refresh(environment(2)))
        assertNull(runtime.offer.value)
        val second = commit(runtime, "天气", 2, nextLetter)
        assertEquals("今天天气", second.query!!.context)
        assertNotNull(runtime.publish(second.query, listOf("很好"), environment(4)))
    }

    @Test fun everyHardResetRevokesEvenAnIdenticalEditorAndConnection() {
        for (reset in listOf<(NextWordPredictionRuntime) -> Unit>(
            { it.invalidate(true) }, { it.onUserAction(true) },
            { it.attach(editor, connection) }, { it.resetEditor(); it.attach(editor, connection) })) {
            val runtime = runtime()
            val old = runtime.captureOrigin()
            reset(runtime)
            assertNull(runtime.onActualCommitted(old, "今天", 0, 2, 100, environment(2)))
            assertEquals("", runtime.contextSnapshot())
        }
    }

    @Test fun aReceiptCannotCrossARecreatedControllerEvenWithReusedHostIdentities() {
        val old = runtime().captureOrigin()
        val replacement = runtime()
        assertNotEquals(old.editorEpoch, replacement.captureOrigin().editorEpoch)
        assertNull(replacement.onActualCommitted(old, "今天", 0, 2, 100, environment(2)))
        assertEquals("", replacement.contextSnapshot())
    }

    @Test fun nativeBusyEventsKeepOriginValidAndIdleRefreshUsesTheSuccessfulAnchor() {
        val runtime = runtime()
        val origin = runtime.captureOrigin()
        assertNull(runtime.refresh(environment(0, idle = false)))
        assertTrue(origin.sameGeneration(runtime.captureOrigin()))
        val committed = commit(runtime, "今天", 0, origin, idle = false)
        assertNull(committed.query)
        val query = runtime.refresh(environment(2))!!
        assertEquals(committed.anchor.commitToken, query.anchor.commitToken)
        val pending = runtime.publish(query, listOf("天气"), environment(2))!!
        assertEquals(pending.offer, runtime.offer.value)
        assertEquals(query, runtime.refresh(environment(2)))
        assertNull(runtime.refresh(environment(2, idle = false)))
        assertFalse(runtime.isCurrent(query))
        assertNull(runtime.offer.value)
        assertTrue(origin.sameGeneration(runtime.captureOrigin()))
    }

    @Test fun identitiesVisibilitySensitivityAndCollapsedCursorGuardPublicationAndSelection() {
        val runtime = runtime()
        val query = commit(runtime, "今天", 0).query!!
        val bad = listOf(environment(2).copy(editorIdentity = Any()),
            environment(2).copy(inputConnectionIdentity = Any()), environment(2).copy(eligible = false),
            environment(2).copy(visible = false), environment(2, idle = false),
            environment(2).copy(cursorEnd = 3), environment(3))
        for (env in bad) {
            assertNull(runtime.publish(query, listOf("天气"), env))
        }
        val offer = runtime.publish(query, listOf("天气"), environment(2))!!.offer
        for (env in bad) assertNull(runtime.claim(offer.token, 0, null, env))
        assertNotNull(runtime.claim(offer.token, 0, null, environment(2)))
        assertNull(runtime.claim(offer.token, 0, null, environment(2)))
    }

    @Test fun onlyHanCandidatesAreOfferedAndIndicesRemainStableAfterFiltering() {
        val runtime = runtime()
        val query = commit(runtime, "今天", 0).query!!
        val offer = runtime.publish(query, listOf("", "weather", "天气", "天气", "真好！", "很好"), environment(2))!!.offer
        assertEquals(listOf("天气", "很好"), offer.candidates)
        assertNull(runtime.claim(offer.token, -1, null, environment(2)))
        assertNull(runtime.claim(offer.token, 2, null, environment(2)))
        assertEquals("很好", runtime.claim(offer.token, 1, null, environment(2))!!.text)
    }

    @Test fun contextIsBoundedByCodePointsWithoutSplittingSupplementaryHan() {
        val runtime = runtime()
        val supplementary = String(Character.toChars(0x20000))
        val text = "天".repeat(70) + supplementary.repeat(10)
        val query = commit(runtime, text, 0).query!!
        assertEquals(64, query.context.codePointCount(0, query.context.length))
        assertEquals("天".repeat(54) + supplementary.repeat(10), query.context)
        assertFalse(query.context.first().isLowSurrogate())
        assertFalse(query.context.last().isHighSurrogate())
    }

    @Test fun api23CompatibleHanValidationIncludesAssignedExtensionsAndExcludesOtherScripts() {
        for (point in listOf(0x3400, 0x4E00, 0xF900, 0x20000, 0x2A700, 0x2F800)) {
            assertTrue(NextWordPredictionRuntime.isHanText(String(Character.toChars(point))))
        }
        for (text in listOf("a", "あ", "한", "天!", "\uD840", "\uDC00", "\u2F00")) {
            assertFalse(NextWordPredictionRuntime.isHanText(text))
        }
        // A loaded class must not link the unavailable API even on an older Android device.
        val bytecode = NextWordPredictionRuntime::class.java.classLoader!!
            .getResourceAsStream("org/fcitx/fcitx5/android/input/prediction/NextWordPredictionRuntime\$Companion.class")!!
            .use { it.readBytes().toString(Charsets.ISO_8859_1) }
        assertFalse(bytecode.contains("java/lang/Character\$UnicodeScript"))
    }

    @Test fun punctuationLatinUnknownPositionsAndDiscontinuousInsertionBreakContext() {
        for ((text, start, end) in listOf(Triple("abc", 2, 5), Triple("。", 2, 3),
            Triple("天", -1, 0), Triple("天", 2, 2), Triple("天", 9, 10))) {
            val runtime = runtime()
            commit(runtime, "今天", 0)
            val origin = runtime.onUserAction()
            val boundary = runtime.onActualCommitted(origin, text, start, end, 200, environment(end))!!
            assertNull(boundary.query)
            assertEquals("", runtime.contextSnapshot())
            assertNull(runtime.offer.value)
            assertNull(runtime.onActualCommitted(origin, "天气", end, end + 2, 300, environment(end + 2)))
        }
    }

    @Test fun observationIdentityNeverChangesWhetherAnOriginIsCurrent() {
        val runtime = runtime()
        val query = commit(runtime, "今天", 0, runtime.captureOrigin(Any())).query!!
        val otherObservation = query.copy(anchor = query.anchor.copy(origin = query.anchor.origin.copy(observation = Any())))
        assertTrue(runtime.isCurrent(otherObservation))
        assertNotNull(runtime.publish(otherObservation, listOf("天气"), environment(2)))
    }

    @Test fun ownCommitAcknowledgementIsAllowedButCursorMoveAndReturnCannotReuseOldToken() {
        val runtime = runtime()
        val query = commit(runtime, "今天", 0).query!!
        val offer = runtime.publish(query, listOf("天气"), environment(2))!!.offer
        val selected = runtime.claim(offer.token, 0, Any(), environment(2))!!
        runtime.onSelectionChanged(4, 4)
        assertTrue(selected.origin.sameGeneration(runtime.captureOrigin()))
        val next = commit(runtime, selected.text, selected.startCursor, selected.origin).query!!
        val second = runtime.publish(next, listOf("很好"), environment(4))!!.offer
        runtime.onSelectionChanged(3, 3)
        runtime.onSelectionChanged(4, 4)
        assertNull(runtime.claim(second.token, 0, null, environment(4)))
        assertEquals("", runtime.contextSnapshot())
    }
}
