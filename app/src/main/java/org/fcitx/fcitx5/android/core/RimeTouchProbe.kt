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
        val withinBudget: Boolean,
        val failureReason: String? = null
    )

    data class Diagnostics(
        val libraryAvailable: Boolean?,
        val runtimeStatus: RimeTouchProbeStatus?,
        val lastFailure: String?
    )

    @Volatile
    private var libraryAvailable: Boolean? = null
    @Volatile
    private var runtimeStatus: RimeTouchProbeStatus? = null
    @Volatile
    private var lastFailure: String? = null
    private var libraryFailure = "ProbeUnavailable:LibraryLoadFailure"
    private var handle = 0L

    /** Cached facts only: exporting a report never loads JNI or runs a query. */
    fun diagnostics() = Diagnostics(libraryAvailable, runtimeStatus, lastFailure)

    /** Queries exactly one alternative. Text and preceding context are never persisted. */
    fun query(
        schemaId: String,
        alternativeInput: String,
        precedingText: String = "",
        budgetNanos: Long = 20_000_000L
    ): QueryResult {
        checkFcitxThread()
        val started = System.nanoTime()
        fun result(candidates: List<Candidate>, available: Boolean,
                   failureReason: String? = null): QueryResult {
            val elapsed = System.nanoTime() - started
            val withinBudget = elapsed <= budgetNanos
            lastFailure = failureReason
            return QueryResult(if (withinBudget) candidates else emptyList(), elapsed,
                available, withinBudget, failureReason)
        }
        if (schemaId != "rime_ice" || alternativeInput.isEmpty() ||
            alternativeInput.length > 32 ||
            alternativeInput.any { it !in 'a'..'z' && it != '\'' } ||
            precedingText.toByteArray(Charsets.UTF_8).size > 192 || budgetNanos <= 0) {
            return result(emptyList(), false, "ProbeUnavailable:InvalidInput")
        }
        if (libraryAvailable == null) {
            libraryAvailable = runCatching { System.loadLibrary("axiangtouch") }.isSuccess
        }
        if (libraryAvailable != true) return result(emptyList(), false, libraryFailure)
        return try {
            if (handle == 0L) {
                handle = nativeCreate(schemaId)
                runtimeStatus = RimeTouchProbeStatus.parseNative(nativeRuntimeStatus())
            }
            if (handle == 0L) result(emptyList(), false,
                runtimeStatus?.rejectionReason ?: "ProbeUnavailable:UnknownNativeFailure")
            else {
                val candidates = nativeQuery(handle, alternativeInput, precedingText, budgetNanos).toList()
                val status = RimeTouchProbeStatus.parseNative(nativeRuntimeStatus())
                runtimeStatus = status
                if (status.code != "Ready") result(emptyList(), false, status.rejectionReason)
                else result(candidates, true)
            }
        } catch (_: LinkageError) {
            libraryFailure = "ProbeUnavailable:NativeLinkageFailure"
            libraryAvailable = false
            handle = 0L
            result(emptyList(), false, libraryFailure)
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
    private external fun nativeRuntimeStatus(): String
    private external fun nativeQuery(
        handle: Long, input: String, precedingText: String, budgetNanos: Long
    ): Array<Candidate>
    private external fun nativeDestroy(handle: Long)
}
