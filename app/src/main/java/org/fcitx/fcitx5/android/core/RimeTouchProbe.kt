/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import androidx.annotation.Keep

/** Read-only experiment; all methods must be invoked inside the Fcitx queue. */
@Keep
internal object RimeTouchProbe {
    @Keep
    data class Candidate(
        val text: String,
        val comment: String,
        val start: Int,
        val end: Int,
        /** One-based engine position; this is not a calibrated confidence. */
        val rank: Int
    )

    data class QueryResult(
        val candidates: List<Candidate>,
        val elapsedNanos: Long,
        val available: Boolean,
        val withinBudget: Boolean
    )

    private var libraryAvailable: Boolean? = null
    private var handle = 0L

    /** Queries exactly one alternative. Text and preceding context are never persisted. */
    fun query(
        schemaId: String,
        alternativeInput: String,
        precedingText: String = "",
        budgetNanos: Long = 20_000_000L
    ): QueryResult {
        checkFcitxThread()
        val started = System.nanoTime()
        fun result(candidates: List<Candidate>, available: Boolean): QueryResult {
            val elapsed = System.nanoTime() - started
            val withinBudget = elapsed <= budgetNanos
            return QueryResult(if (withinBudget) candidates else emptyList(), elapsed,
                available, withinBudget)
        }
        if (schemaId != "rime_ice" || alternativeInput.isEmpty() ||
            alternativeInput.length > 32 ||
            alternativeInput.any { it !in 'a'..'z' && it != '\'' } ||
            precedingText.toByteArray(Charsets.UTF_8).size > 192 || budgetNanos <= 0) {
            return result(emptyList(), false)
        }
        if (libraryAvailable == null) {
            libraryAvailable = runCatching { System.loadLibrary("axiangtouch") }.isSuccess
        }
        if (libraryAvailable != true) return result(emptyList(), false)
        return try {
            if (handle == 0L) handle = nativeCreate(schemaId)
            if (handle == 0L) result(emptyList(), false)
            else result(nativeQuery(handle, alternativeInput, precedingText, budgetNanos).toList(), true)
        } catch (_: LinkageError) {
            libraryAvailable = false
            handle = 0L
            result(emptyList(), false)
        }
    }

    /** Close on the Fcitx queue before an engine shutdown/redeploy or sensitive editor. */
    fun close() {
        checkFcitxThread()
        if (handle != 0L && libraryAvailable == true) {
            runCatching { nativeDestroy(handle) }
        }
        handle = 0L
    }

    private fun checkFcitxThread() {
        check(Thread.currentThread().name == "fcitx-main") {
            "Rime touch probe requires the Fcitx native queue"
        }
    }

    private external fun nativeCreate(schemaId: String): Long
    private external fun nativeQuery(
        handle: Long, input: String, precedingText: String, budgetNanos: Long
    ): Array<Candidate>
    private external fun nativeDestroy(handle: Long)
}
