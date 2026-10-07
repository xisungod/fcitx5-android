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
        val failureReason: String? = null,
        val libraryLoadNanos: Long = 0L,
        val initializationNanos: Long = 0L,
        val nativeQueryNanos: Long = 0L,
        val cacheHit: Boolean = false,
        val coldInitialization: Boolean = false,
        val nativeWithinBudget: Boolean = true
    )

    data class Diagnostics(
        val libraryAvailable: Boolean?,
        val runtimeStatus: RimeTouchProbeStatus?,
        val lastFailure: String?,
        val readyHandle: Boolean = false,
        /** Ready describes the interface; these outcomes describe actual completed calls. */
        val lastOutcome: String? = null,
        val lastQueryElapsedNanos: Long? = null,
        val lastLibraryLoadNanos: Long? = null,
        val lastInitializationNanos: Long? = null,
        val lastNativeQueryNanos: Long? = null,
        val lastQueryMonotonicNanos: Long? = null,
        /** Successful JNI query time survives later failures and cache reads. */
        val lastSuccessMonotonicNanos: Long? = null,
        val queryCount: Long = 0L,
        val nativeQueryCount: Long = 0L,
        val successCount: Long = 0L,
        val noCandidateCount: Long = 0L,
        val timeoutCount: Long = 0L,
        val unavailableCount: Long = 0L,
        val cacheHitCount: Long = 0L,
        val lastCacheHit: Boolean? = null
    )

    @Volatile
    private var libraryAvailable: Boolean? = null
    @Volatile
    private var runtimeStatus: RimeTouchProbeStatus? = null
    @Volatile
    private var lastFailure: String? = null
    private var libraryFailure = "ProbeUnavailable:LibraryLoadFailure"
    private var handle = 0L
    private var dictionaryGeneration = 0L
    private val cache = RimeTouchProbeValueCache<List<Candidate>>()
    @Volatile private var snapshot = Diagnostics(null, null, null)

    /** Cached facts only: exporting a report never loads JNI or runs a query. */
    fun diagnostics() = snapshot

    /** Queries exactly one alternative. Text and preceding context are never persisted. */
    fun query(
        schemaId: String,
        alternativeInput: String,
        precedingText: String = "",
        budgetNanos: Long = 20_000_000L,
        /** Settings self-check must actually exercise JNI, not reuse a cached value. */
        bypassCache: Boolean = false
    ): QueryResult {
        checkFcitxThread()
        val started = System.nanoTime()
        var libraryLoadNanos = 0L
        var initializationNanos = 0L
        var nativeQueryNanos = 0L
        var coldInitialization = false
        var cacheHit = false
        fun result(candidates: List<Candidate>, available: Boolean,
                   failureReason: String? = null): QueryResult {
            val elapsed = System.nanoTime() - started
            val withinBudget = elapsed <= budgetNanos
            lastFailure = failureReason
            if (!available) cache.clear()
            val outcome = when {
                !available -> "Unavailable"
                !withinBudget -> "Timeout"
                candidates.isEmpty() -> "NoCandidates"
                else -> "Success"
            }
            val previous = snapshot
            snapshot = Diagnostics(libraryAvailable, runtimeStatus, failureReason,
                readyHandle = handle != 0L && runtimeStatus?.code == "Ready",
                lastOutcome = outcome, lastQueryElapsedNanos = elapsed,
                lastLibraryLoadNanos = libraryLoadNanos,
                lastInitializationNanos = initializationNanos,
                lastNativeQueryNanos = nativeQueryNanos,
                lastQueryMonotonicNanos = System.nanoTime(),
                lastSuccessMonotonicNanos = if (outcome == "Success" && !cacheHit)
                    System.nanoTime() else previous.lastSuccessMonotonicNanos,
                queryCount = previous.queryCount + 1,
                nativeQueryCount = previous.nativeQueryCount + if (nativeQueryNanos > 0L) 1 else 0,
                successCount = previous.successCount + if (outcome == "Success") 1 else 0,
                noCandidateCount = previous.noCandidateCount + if (outcome == "NoCandidates") 1 else 0,
                timeoutCount = previous.timeoutCount + if (outcome == "Timeout") 1 else 0,
                unavailableCount = previous.unavailableCount + if (outcome == "Unavailable") 1 else 0,
                cacheHitCount = previous.cacheHitCount + if (cacheHit) 1 else 0,
                lastCacheHit = cacheHit)
            return QueryResult(if (withinBudget) candidates else emptyList(), elapsed,
                available, withinBudget, failureReason, libraryLoadNanos, initializationNanos,
                nativeQueryNanos, cacheHit, coldInitialization, nativeQueryNanos <= budgetNanos)
        }
        if (schemaId != "rime_ice" || alternativeInput.isEmpty() ||
            alternativeInput.length > 32 ||
            alternativeInput.any { it !in 'a'..'z' && it != '\'' } ||
            precedingText.toByteArray(Charsets.UTF_8).size > 192 || budgetNanos <= 0) {
            return result(emptyList(), false, "ProbeUnavailable:InvalidInput")
        }
        if (libraryAvailable == null) {
            val loadStarted = System.nanoTime()
            libraryAvailable = runCatching { System.loadLibrary("axiangtouch") }.isSuccess
            libraryLoadNanos = System.nanoTime() - loadStarted
        }
        if (libraryAvailable != true) return result(emptyList(), false, libraryFailure)
        return try {
            val key = RimeTouchProbeValueCache.Key(schemaId, alternativeInput, precedingText,
                dictionaryGeneration)
            if (!bypassCache && handle != 0L && runtimeStatus?.code == "Ready") {
                cache.get(key, started)?.let {
                    cacheHit = true
                    return result(it.map { candidate -> candidate.copy() }, true)
                }
            }
            if (handle == 0L) {
                coldInitialization = true
                val createStarted = System.nanoTime()
                handle = nativeCreate(schemaId)
                runtimeStatus = RimeTouchProbeStatus.parseNative(nativeRuntimeStatus())
                initializationNanos = System.nanoTime() - createStarted
            }
            if (handle == 0L) result(emptyList(), false,
                runtimeStatus?.rejectionReason ?: "ProbeUnavailable:UnknownNativeFailure")
            else {
                val queryStarted = System.nanoTime()
                val candidates = nativeQuery(handle, alternativeInput, precedingText, budgetNanos).toList()
                nativeQueryNanos = System.nanoTime() - queryStarted
                val status = RimeTouchProbeStatus.parseNative(nativeRuntimeStatus())
                runtimeStatus = status
                if (status.code != "Ready") result(emptyList(), false, status.rejectionReason)
                else {
                    // Never cache late results. Values cannot outlive close(), a
                    // dictionary generation or the short in-memory TTL.
                    if (System.nanoTime() - started <= budgetNanos)
                        cache.put(key, candidates.map { it.copy() }, System.nanoTime())
                    result(candidates, true)
                }
            }
        } catch (_: LinkageError) {
            libraryFailure = "ProbeUnavailable:NativeLinkageFailure"
            libraryAvailable = false
            handle = 0L
            cache.clear()
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
        dictionaryGeneration++
        cache.clear()
        snapshot = snapshot.copy(readyHandle = false)
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
