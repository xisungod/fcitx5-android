/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import android.content.Context
import android.os.UserManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Private credential-storage persistence. Construct/preload on an IO dispatcher;
 * offsets reads only the loaded numeric snapshot. Confirmation writes must run
 * off the key-dispatch thread and require a fresh opt-in check by the caller.
 */
class PinyinTouchProfileStore(context: Context) {
    private val credentialContext = context.applicationContext ?: context
    private val state = synchronized(states) {
        states.getOrPut(storageIdentity(credentialContext)) { State() }
    }
    @Volatile private var unlocked = false

    init { preload() }

    /** Memory-only readiness check for lifecycle-triggered background retries. */
    val isPreloaded: Boolean get() = synchronized(states) { state.loaded && !state.pendingClear }

    /**
     * Call on an IO dispatcher at startup and after first unlock. A locked-start
     * instance can load saved offsets later without requiring another confirmed
     * tap. Key decisions only read offsets and never invoke this disk operation.
     */
    fun preload(): Boolean {
        if (!storageAvailable()) return false
        return synchronized(states) {
            runCatching { loadIfNeeded(); state.loaded }.getOrDefault(false)
        }
    }

    /** Capture with the pending confirmation, before scheduling asynchronous work. */
    val generation: Long get() = synchronized(states) { state.generation }

    fun offsets(cells: List<KeyCell>, density: Float): Map<Char, CenterOffset> {
        if (!storageAvailable()) return emptyMap()
        return synchronized(states) {
            if (state.loaded) state.profile.offsets(cells, density) else emptyMap()
        }
    }

    fun confirmedSampleCount(cells: List<KeyCell>, density: Float, letter: Char): Int {
        if (!storageAvailable()) return 0
        return synchronized(states) {
            if (state.loaded) state.profile.confirmedSampleCount(cells, density, letter) else 0
        }
    }

    /** The intended letter must come from an explicit user choice, never automatic selection. */
    fun observe(
        cells: List<KeyCell>,
        tap: TapEvidence,
        intended: Char,
        expectedGeneration: Long = generation
    ): Boolean {
        if (!storageAvailable()) return false
        return synchronized(states) {
            if (expectedGeneration != state.generation) return@synchronized false
            if (runCatching { loadIfNeeded() }.isFailure) return@synchronized false
            if (!state.profile.observeConfirmed(cells, tap, intended)) return@synchronized false
            val encoded = encode(state.profile.snapshot(), state.receipts)
            // apply updates SharedPreferences memory before enqueueing its disk operation.
            // Holding the same lock as clear prevents a queued older write restoring data.
            runCatching { preferences().edit().putString(DATA_KEY, encoded).apply() }.isSuccess
        }
    }

    data class ImportResult(val applied: Int, val alreadyApplied: Int, val unavailable: Boolean = false)

    /**
     * One explicit prompted-test confirmation group is atomic and may be imported once.
     * Receipt hashes retain no prompt text, raw spelling, timestamps or individual taps.
     */
    internal fun observeConfirmedGroup(
        receipt: String,
        samples: List<PinyinTouchProfile.ConfirmedSample>,
        expectedGeneration: Long,
        stillAuthorized: () -> Boolean = { true }
    ): ImportResult {
        if (!receipt.matches(Regex("[a-f0-9]{64}")) || samples.isEmpty() || samples.size > 64 ||
            samples.any { !it.isValid() }) return ImportResult(0, 0, unavailable = true)
        if (!storageAvailable()) return ImportResult(0, 0, unavailable = true)
        return synchronized(states) {
            if (!stillAuthorized() || expectedGeneration != state.generation || runCatching { loadIfNeeded() }.isFailure)
                return@synchronized ImportResult(0, 0, unavailable = true)
            if (receipt in state.receipts) return@synchronized ImportResult(0, samples.size)
            val before = state.profile.snapshot()
            samples.forEach { check(state.profile.observeConfirmed(it)) }
            state.receipts.add(receipt)
            val removed = if (state.receipts.size > MAXIMUM_RECEIPTS) state.receipts.removeAt(0) else null
            val saved = runCatching {
                preferences().edit().putString(DATA_KEY, encode(state.profile.snapshot(), state.receipts)).apply()
            }.isSuccess
            if (!saved) {
                state.profile.restore(before)
                state.receipts.remove(receipt)
                removed?.let { state.receipts.add(0, it) }
                ImportResult(0, 0, unavailable = true)
            } else ImportResult(samples.size, 0)
        }
    }

    private fun storageAvailable(): Boolean {
        if (credentialContext.isDeviceProtectedStorage) return false
        if (unlocked) return true
        if (runCatching { credentialContext.getSystemService(UserManager::class.java)?.isUserUnlocked }
                .getOrNull() != true) return false
        unlocked = true
        return true
    }

    private fun preferences() = credentialContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun loadIfNeeded() {
        if (state.pendingClear) {
            preferences().edit().clear().apply()
            state.pendingClear = false
        }
        if (state.loaded) return
        val encoded = runCatching { preferences().getString(DATA_KEY, null) }.getOrNull()
        state.profile.restore(encoded?.let { runCatching { decode(it) }.getOrNull() } ?: emptyMap())
        state.receipts.clear()
        if (encoded != null) runCatching {
            val receipts = JSONObject(encoded).optJSONArray("confirmed_receipts") ?: return@runCatching
            require(receipts.length() <= MAXIMUM_RECEIPTS)
            for (i in 0 until receipts.length()) {
                val receipt = receipts.getString(i)
                require(receipt.matches(Regex("[a-f0-9]{64}")))
                if (receipt !in state.receipts) state.receipts.add(receipt)
            }
        }.onFailure { state.receipts.clear() }
        state.loaded = true
    }

    companion object {
        private const val PREFERENCES_NAME = "axiang_pinyin_touch_profile"
        private const val DATA_KEY = "normalized_statistics_v1"
        private const val MAXIMUM_SERIALIZED_BYTES = 128 * 1024
        private const val MAXIMUM_RECEIPTS = 32
        private val states = LinkedHashMap<String, State>()

        private class State {
            val profile = PinyinTouchProfile()
            val receipts = mutableListOf<String>()
            var generation = 0L
            var loaded = false
            var pendingClear = false
        }

        private fun storageIdentity(context: Context) = context.dataDir.absolutePath

        /** Captured before dispatching calibration IO; a subsequent reset invalidates the job. */
        internal fun generation(context: Context): Long {
            val app = context.applicationContext ?: context
            return synchronized(states) {
                states.getOrPut(storageIdentity(app)) { State() }.generation
            }
        }

        /** Clear affects every existing store and rejects observations queued before this call. */
        fun clear(context: Context) {
            val credentialContext = context.applicationContext ?: context
            synchronized(states) {
                val state = states.getOrPut(storageIdentity(credentialContext)) { State() }
                state.generation++
                state.profile.clear()
                state.receipts.clear()
                state.loaded = true
                val accessible = !credentialContext.isDeviceProtectedStorage &&
                    runCatching { credentialContext.getSystemService(UserManager::class.java)?.isUserUnlocked }
                        .getOrNull() == true
                state.pendingClear = !accessible
                if (accessible) state.pendingClear = runCatching {
                    credentialContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                        .edit().clear().apply()
                }.isFailure
            }
        }

        private fun encode(snapshot: Map<String, Map<Char, PinyinTouchProfile.Statistics>>, receipts: List<String>): String =
            JSONObject().apply {
                put("version", 1)
                put("confirmed_receipts", JSONArray(receipts))
                put("layouts", JSONArray().apply {
                    for ((layout, statistics) in snapshot) put(JSONObject().apply {
                        put("layout", layout)
                        put("keys", JSONArray().apply {
                            for ((letter, data) in statistics) put(JSONArray(listOf(
                                letter.toString(), data.count, data.meanX, data.meanY, data.m2X, data.m2Y
                            )))
                        })
                    })
                })
            }.toString()

        private fun decode(encoded: String): Map<String, Map<Char, PinyinTouchProfile.Statistics>> {
            require(encoded.length <= MAXIMUM_SERIALIZED_BYTES) { "Oversized touch profile" }
            val root = JSONObject(encoded)
            require(root.getInt("version") == 1)
            val layouts = root.getJSONArray("layouts")
            require(layouts.length() <= PinyinTouchProfile.MAXIMUM_LAYOUTS)
            val result = LinkedHashMap<String, Map<Char, PinyinTouchProfile.Statistics>>()
            for (i in 0 until layouts.length()) {
                val entry = layouts.getJSONObject(i)
                val keys = entry.getJSONArray("keys")
                require(keys.length() <= 26)
                val statistics = LinkedHashMap<Char, PinyinTouchProfile.Statistics>()
                for (j in 0 until keys.length()) {
                    val row = keys.getJSONArray(j)
                    val letter = row.getString(0)
                    require(row.length() == 6 && letter.length == 1)
                    statistics[letter[0]] = PinyinTouchProfile.Statistics(row.getInt(1),
                        row.getDouble(2), row.getDouble(3), row.getDouble(4), row.getDouble(5))
                }
                result[entry.getString("layout")] = statistics
            }
            return result
        }
    }
}
