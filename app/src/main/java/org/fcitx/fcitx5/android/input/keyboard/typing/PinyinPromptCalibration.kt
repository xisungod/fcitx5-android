/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard.typing

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.typingtest.TypingTestCalibrationSample

/** Imports only the foreground test's explicitly confirmed, uniquely aligned prompted labels. */
object PinyinPromptCalibration {
    enum class Reason { Applied, AlreadyApplied, Empty, Invalid, Disabled, Reset, StorageUnavailable }
    data class Result(val appliedCount: Int, val alreadyAppliedCount: Int, val reason: Reason)

    /**
     * Call from the user's Apply button, never from ordinary typing, candidate prediction
     * or passive report loading. Test intent and alignment are certified by TypingTestSession.
     * The profile persists only bounded numeric aggregates and opaque receipt hashes.
     */
    suspend fun applyConfirmedSamples(context: Context, samples: List<TypingTestCalibrationSample>): Result {
        val app = context.applicationContext ?: context
        val generation = PinyinTouchProfileStore.generation(app)
        val retained = samples.toList()
        if (retained.isEmpty()) return Result(0, 0, Reason.Empty)
        if (retained.size > 64 * 20 || retained.any {
                it.confirmationId?.matches(Regex("[a-f0-9]{64}")) != true ||
                    it.inputIndex !in 0 until 64 || it.originalKey !in 'a'..'z' ||
                    it.orientation !in setOf("portrait", "landscape") ||
                    it.hand !in setOf("unknown", "left", "right") ||
                    !PinyinTouchProfile.ConfirmedSample(it.layoutSignature, it.intendedKey,
                        it.normalizedOffsetX, it.normalizedOffsetY).isValid()
            }) return Result(0, 0, Reason.Invalid)
        val groups = retained.groupBy { it.confirmationId!! }
        if (groups.size > 20 || groups.values.any { group ->
                group.size > 64 || group.map { it.inputIndex }.distinct().size != group.size ||
                    group.sortedBy { it.inputIndex }.zipWithNext().any { (a, b) ->
                        b.inputIndex == a.inputIndex + 1 && a.originalKey != a.intendedKey &&
                            a.originalKey == b.intendedKey && b.originalKey == a.intendedKey
                    }
            }) return Result(0, 0, Reason.Invalid)
        return withContext(Dispatchers.IO) {
            fun authorized() = AppPrefs.getInstance().keyboard.pinyinTouchPersonalization.getValue()
            if (!authorized()) return@withContext Result(0, 0, Reason.Disabled)
            val store = PinyinTouchProfileStore(app)
            var applied = 0
            var duplicate = 0
            for ((receipt, group) in groups) {
                if (!authorized()) return@withContext Result(applied, duplicate, Reason.Disabled)
                if (store.generation != generation) return@withContext Result(applied, duplicate, Reason.Reset)
                val numeric = group.map { PinyinTouchProfile.ConfirmedSample(it.layoutSignature,
                    it.intendedKey, it.normalizedOffsetX, it.normalizedOffsetY) }
                val imported = store.observeConfirmedGroup(receipt, numeric, generation, ::authorized)
                applied += imported.applied
                duplicate += imported.alreadyApplied
                if (imported.unavailable) return@withContext Result(applied, duplicate,
                    if (store.generation != generation) Reason.Reset else Reason.StorageUnavailable)
            }
            Result(applied, duplicate, if (applied > 0) Reason.Applied else Reason.AlreadyApplied)
        }
    }
}
