/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.prediction

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class NextWordPredictionReceiptContextTest {
    @Test fun suspendingNativeOperationsCarryTheirOwnReceiptAndRestoreTheQueueThread() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { queue ->
            val firstOrigin = NextWordPredictionOrigin(1, 2, 3, Any())
            val secondOrigin = NextWordPredictionOrigin(1, 2, 4, Any())
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val first = async(queue + NextWordPredictionReceiptContext.element(firstOrigin)) {
                assertSame(firstOrigin, NextWordPredictionReceiptContext.currentOrigin())
                entered.complete(Unit)
                resume.await()
                assertSame(firstOrigin, NextWordPredictionReceiptContext.currentOrigin())
            }
            entered.await()
            withContext(queue) { assertNull(NextWordPredictionReceiptContext.currentOrigin()) }
            withContext(queue + NextWordPredictionReceiptContext.element(secondOrigin)) {
                assertSame(secondOrigin, NextWordPredictionReceiptContext.currentOrigin())
            }
            resume.complete(Unit)
            first.await()
            withContext(queue) { assertNull(NextWordPredictionReceiptContext.currentOrigin()) }
            assertNull(NextWordPredictionReceiptContext.currentOrigin())
        }
    }
}
