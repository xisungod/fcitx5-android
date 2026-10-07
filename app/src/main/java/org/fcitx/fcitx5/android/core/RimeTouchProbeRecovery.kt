/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

/** Bounded recovery for one composition. This is a result budget, not cancellation. */
internal class RimeTouchProbeRecovery(
    private val cooldownNanos: Long = 150_000_000L,
    private val maxNativeTimeouts: Int = 3
) {
    private var nativeTimeouts = 0
    private var initializationTimeouts = 0
    private var cooldownUntil = 0L
    private val failedSpellings = LinkedHashSet<String>()

    @Synchronized fun reset() {
        nativeTimeouts = 0
        initializationTimeouts = 0
        cooldownUntil = 0L
        failedSpellings.clear()
    }

    @Synchronized fun rejection(spelling: String, nowNanos: Long, warmHandle: Boolean = false): String? = when {
        spelling in failedSpellings -> "ProbeRepeatedTimedOutSpelling"
        nativeTimeouts >= maxNativeTimeouts -> "ProbeRetryLimit"
        !warmHandle && initializationTimeouts >= MAX_INITIALIZATION_TIMEOUTS -> "ProbeInitializationRetryLimit"
        nowNanos < cooldownUntil -> "ProbeCoolingDown"
        else -> null
    }

    @Synchronized fun completed(spelling: String, nowNanos: Long, available: Boolean,
                                withinBudget: Boolean, nativeWithinBudget: Boolean,
                                coldInitialization: Boolean) {
        if (!available || withinBudget) return
        // Bound retained text even if repeated cold initialization fails unusually often.
        if (failedSpellings.size >= MAX_FAILED_SPELLINGS) failedSpellings.remove(failedSpellings.first())
        failedSpellings.add(spelling)
        // A healthy query after expensive initialization leaves a warm handle. Do
        // not confuse that with slow native queries or disable the entire phrase.
        if (coldInitialization && nativeWithinBudget) {
            initializationTimeouts++
            return
        }
        nativeTimeouts++
        cooldownUntil = nowNanos + cooldownNanos
    }

    companion object {
        private const val MAX_FAILED_SPELLINGS = 16
        private const val MAX_INITIALIZATION_TIMEOUTS = 3
    }
}

/** In-memory value snapshots only, scoped by schema/context and lifecycle generation. */
internal class RimeTouchProbeValueCache<T>(
    private val capacity: Int = 16,
    private val ttlNanos: Long = 2_000_000_000L
) {
    data class Key(val schema: String, val spelling: String, val context: String, val generation: Long)
    private data class Entry<T>(val value: T, val time: Long)
    private val entries = LinkedHashMap<Key, Entry<T>>(capacity, 0.75f, true)

    init { require(capacity > 0 && ttlNanos > 0) }

    fun get(key: Key, nowNanos: Long): T? {
        val entry = entries[key] ?: return null
        if (nowNanos - entry.time >= ttlNanos || nowNanos < entry.time) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    fun put(key: Key, value: T, nowNanos: Long) {
        entries[key] = Entry(value, nowNanos)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    fun clear() = entries.clear()
}
